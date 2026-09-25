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
 * user-entered metadata ({@link #embeddingText}); the vector comes from the same CLIP model as search queries
 * (TextEmbeddingClient -> services/vision) and is recorded with that model's id.
 *
 * <p>The vision call happens outside any transaction. The write is {@code UPDATE ... WHERE embedding IS NULL}: the one
 * update the immutability trigger allows, and a no-op if another API instance got there first.
 */
@Service
public class PoiEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(PoiEmbeddingService.class);

    static final int EMBEDDING_DIM = 512; // poi_version.embedding is vector(512)

    private static final String PENDING = """
            SELECT v.id, v.label, v.category, v.tags
              FROM poi_version v
              JOIN poi p ON p.id = v.poi_id
             WHERE v.embedding IS NULL
               AND p.deleted_at IS NULL
               AND v.version_number = (SELECT max(version_number) FROM poi_version WHERE poi_id = v.poi_id)
             ORDER BY v.created_at
             LIMIT :n
            """;

    record Pending(UUID id, String label, String category, List<String> tags) {}

    /** What one pass did. {@code stoppedBecause} is null when the backlog (up to the batch size) was worked through. */
    public record Result(int embedded, int skipped, String stoppedBecause) {}

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
        for (Pending p : pending) {
            TextEmbeddingClient.Embedding e;
            try {
                e = embeddings.embedWithModel(embeddingText(p.label(), p.category(), p.tags()));
            } catch (EmbeddingUnavailableException ex) {
                // Nothing is written; the rows stay pending and the next pass retries them.
                return new Result(embedded, skipped, ex.getMessage());
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
            if (rows == 1) {
                embedded++;
            } else {
                skipped++; // embedded concurrently by another instance
            }
        }
        return new Result(embedded, skipped, null);
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
