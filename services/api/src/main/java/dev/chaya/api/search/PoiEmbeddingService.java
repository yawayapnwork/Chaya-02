package dev.chaya.api.search;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Fills in the CLIP embedding of POI versions that have none: the asynchronous step V5__poi.sql describes ("the only
 * permitted update to a poi_version is filling in its embedding once, which happens asynchronously after creation")
 * and poi_version_pending_embedding_idx indexes. Without it a manually placed POI is invisible to semantic search.
 *
 * <p>Only the latest version of a live POI is embedded (search only ever reads that one). The text is the POI's own
 * metadata ({@link #embeddingText}); the vector comes from the same CLIP text tower as search queries
 * (TextEmbeddingClient -> services/vision) and is recorded with that model's id. That includes AUTO_DETECTED POIs: their
 * text is the detector label, and this text-space vector is what ranks them next to manual POIs. Their CLIP image vector
 * lives separately in image_embedding (V21__search_embedding_spaces.sql).
 *
 * <p>The vision call happens outside any transaction. The write is {@code UPDATE ... WHERE embedding IS NULL}: the one
 * update the immutability trigger allows, and a no-op if another API instance got there first.
 *
 * <p>Failures: an outage of the vision service stops the pass without writing anything (the next pass retries). A text the
 * service refuses (EmbeddingRejectedException) is recorded in poi_embedding_failure and retried with exponential backoff,
 * while the rest of the backlog proceeds; until it succeeds that POI is found by name only.
 *
 * <p>When the vision service's model changes, {@link #reembedStale} re-embeds the text of every live POI whose embedding
 * came from another model (V35__poi_reembedding.sql). Until then search simply does not see those POIs by meaning: it never
 * compares vectors of different models.
 */
@Service
public class PoiEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(PoiEmbeddingService.class);

    public static final int EMBEDDING_DIM = 512; // poi_version.embedding and image_embedding are vector(512)

    private static final String PENDING = """
            SELECT v.id, v.label, v.category, v.tags
              FROM poi_version v
              JOIN poi p ON p.id = v.poi_id
             WHERE v.embedding IS NULL
               AND p.deleted_at IS NULL
               AND NOT EXISTS (SELECT 1 FROM poi_embedding_failure f WHERE f.poi_version_id = v.id AND f.next_attempt_at > now())
               AND v.version_number = (SELECT max(version_number) FROM poi_version WHERE poi_id = v.poi_id)
             ORDER BY v.created_at
             LIMIT :n
            """;

    private static final String STALE = """
            SELECT v.id, v.label, v.category, v.tags
              FROM poi_version v
              JOIN poi p ON p.id = v.poi_id
             WHERE v.embedding IS NOT NULL AND v.embedding_model IS DISTINCT FROM :model
               AND p.deleted_at IS NULL
               AND NOT EXISTS (SELECT 1 FROM poi_embedding_failure f WHERE f.poi_version_id = v.id AND f.next_attempt_at > now())
               AND v.version_number = (SELECT max(version_number) FROM poi_version WHERE poi_id = v.poi_id)
             ORDER BY v.created_at
             LIMIT :n
            """;

    /** Text used only to ask the vision service which model it serves; its vector is discarded. */
    static final String MODEL_PROBE_TEXT = "chaya embedding model probe";

    record Pending(UUID id, String label, String category, List<String> tags) {}

    /** What one pass did. {@code stoppedBecause} is null when the backlog (up to the batch size) was worked through.
     * {@code rejected}: texts the vision service refused, recorded in poi_embedding_failure and retried later. */
    public record Result(int embedded, int skipped, String stoppedBecause, int rejected) {
        public Result(int embedded, int skipped, String stoppedBecause) {
            this(embedded, skipped, stoppedBecause, 0);
        }
    }

    // A refused text is retried after 30 s, then 1, 2, 4 ... minutes, at most hourly (V36__search_provenance_and_index_retries).
    private static final String RECORD_FAILURE = """
            INSERT INTO poi_embedding_failure (poi_version_id, attempts, last_error, next_attempt_at)
            VALUES (:id, 1, :err, now() + interval '30 seconds')
            ON CONFLICT (poi_version_id) DO UPDATE
               SET attempts = poi_embedding_failure.attempts + 1, last_error = EXCLUDED.last_error, last_attempt_at = now(),
                   next_attempt_at = now() + least(interval '1 hour', interval '30 seconds' * power(2, poi_embedding_failure.attempts))
            """;

    private final JdbcClient jdbc;
    private final TextEmbeddingClient embeddings;

    public PoiEmbeddingService(JdbcClient jdbc, TextEmbeddingClient embeddings) {
        this.jdbc = jdbc;
        this.embeddings = embeddings;
    }

    /**
     * The text embedded for a POI: its label, then its category and tags (the alternative names a venue manager
     * entered), de-duplicated case-insensitively. Only user-entered metadata; no synonym table.
     */
    public static String embeddingText(String label, String category, List<String> tags) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> parts = new ArrayList<>();
        List<String> candidates = new ArrayList<>();
        candidates.add(label);
        candidates.add(category);
        if (tags != null) {
            candidates.addAll(tags);
        }
        for (String c : candidates) {
            if (c == null || c.isBlank()) {
                continue;
            }
            String s = c.strip();
            if (seen.add(s.toLowerCase(Locale.ROOT))) {
                parts.add(s);
            }
        }
        return String.join(", ", parts);
    }

    /** Embeds up to {@code batchSize} pending POI versions. Stops early, without failing, if the model is unavailable. */
    public Result embedPending(int batchSize) {
        List<Pending> pending = jdbc.sql(PENDING).param("n", batchSize)
            .query((rs, i) -> new Pending(rs.getObject("id", UUID.class), rs.getString("label"), rs.getString("category"),
                List.of((String[]) rs.getArray("tags").getArray())))
            .list();
        int embedded = 0;
        int skipped = 0;
        int rejected = 0;
        for (Pending p : pending) {
            TextEmbeddingClient.Embedding e;
            try {
                e = embeddings.embedWithModel(embeddingText(p.label(), p.category(), p.tags()));
            } catch (EmbeddingRejectedException ex) {
                // This one text was refused: back it off and carry on with the rest of the backlog.
                recordFailure(p.id(), ex.getMessage());
                rejected++;
                continue;
            } catch (EmbeddingUnavailableException ex) {
                // Nothing is written; the rows stay pending and the next pass retries them.
                return new Result(embedded, skipped, ex.getMessage(), rejected);
            }
            if (e.vector().length != EMBEDDING_DIM) {
                // A model with another width cannot be stored next to the others; never truncate or pad.
                log.error("embedding model {} produced {}-d vectors, the schema stores {}-d; POI version {} left pending",
                    e.model(), e.vector().length, EMBEDDING_DIM, p.id());
                return new Result(embedded, skipped, "embedding model " + e.model() + " has the wrong dimension");
            }
            int rows = jdbc.sql("UPDATE poi_version SET embedding = CAST(:e AS vector), embedding_model = :m "
                    + "WHERE id = :id AND embedding IS NULL")
                .param("e", SemanticSearchService.vectorLiteral(e.vector())).param("m", e.model()).param("id", p.id())
                .update();
            clearFailure(p.id());
            if (rows == 1) {
                embedded++;
            } else {
                skipped++; // embedded concurrently by another instance
            }
        }
        return new Result(embedded, skipped, null, rejected);
    }

    /**
     * Re-embeds, in the model the vision service serves now, up to {@code batchSize} live POI versions whose text
     * embedding came from another model. The current model is learned from one probe call per pass. Stops without writing
     * when the service is unavailable, has the wrong dimension, or changes model during the pass (the next pass continues).
     */
    public Result reembedStale(int batchSize) {
        String model;
        try {
            model = embeddings.embedWithModel(MODEL_PROBE_TEXT).model();
        } catch (EmbeddingUnavailableException ex) {
            return new Result(0, 0, ex.getMessage());
        }
        List<Pending> stale = jdbc.sql(STALE).param("model", model).param("n", batchSize)
            .query((rs, i) -> new Pending(rs.getObject("id", UUID.class), rs.getString("label"), rs.getString("category"),
                List.of((String[]) rs.getArray("tags").getArray())))
            .list();
        int embedded = 0;
        int skipped = 0;
        int rejected = 0;
        for (Pending p : stale) {
            TextEmbeddingClient.Embedding e;
            try {
                e = embeddings.embedWithModel(embeddingText(p.label(), p.category(), p.tags()));
            } catch (EmbeddingRejectedException ex) {
                recordFailure(p.id(), ex.getMessage());
                rejected++;
                continue;
            } catch (EmbeddingUnavailableException ex) {
                return new Result(embedded, skipped, ex.getMessage(), rejected);
            }
            if (!model.equals(e.model())) {
                return new Result(embedded, skipped, "the embedding model changed from " + model + " to " + e.model() + " during the pass");
            }
            if (e.vector().length != EMBEDDING_DIM) {
                log.error("embedding model {} produced {}-d vectors, the schema stores {}-d; POI version {} keeps its {} embedding",
                    e.model(), e.vector().length, EMBEDDING_DIM, p.id(), "earlier");
                return new Result(embedded, skipped, "embedding model " + e.model() + " has the wrong dimension");
            }
            int rows = jdbc.sql("UPDATE poi_version SET embedding = CAST(:e AS vector), embedding_model = :m "
                    + "WHERE id = :id AND embedding IS NOT NULL AND embedding_model IS DISTINCT FROM :m")
                .param("e", SemanticSearchService.vectorLiteral(e.vector())).param("m", e.model()).param("id", p.id())
                .update();
            clearFailure(p.id());
            if (rows == 1) {
                embedded++;
            } else {
                skipped++;
            }
        }
        return new Result(embedded, skipped, null, rejected);
    }

    private void recordFailure(UUID poiVersionId, String error) {
        log.warn("the vision service refused the text of POI version {}; retried later: {}", poiVersionId, error);
        jdbc.sql(RECORD_FAILURE).param("id", poiVersionId).param("err", error == null ? "refused" : error).update();
    }

    private void clearFailure(UUID poiVersionId) {
        jdbc.sql("DELETE FROM poi_embedding_failure WHERE poi_version_id = :id").param("id", poiVersionId).update();
    }

    /** POI versions search cannot see yet (the same definition as the operations dashboard's "awaiting embedding"). */
    public long pendingCount() {
        return jdbc.sql("""
                SELECT count(*) FROM poi p
                  JOIN LATERAL (SELECT embedding IS NULL AS no_embedding FROM poi_version
                                 WHERE poi_id = p.id ORDER BY version_number DESC LIMIT 1) v ON true
                 WHERE p.deleted_at IS NULL AND v.no_embedding
                """).query(Long.class).single();
    }
}
