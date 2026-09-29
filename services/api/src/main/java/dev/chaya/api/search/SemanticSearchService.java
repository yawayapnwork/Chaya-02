package dev.chaya.api.search;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.chaya.api.search.SearchDtos.SearchResponse;
import dev.chaya.api.search.SearchDtos.SearchResult;
import dev.chaya.api.rescan.ScanVersionService;
import dev.chaya.api.security.Actor;
import dev.chaya.api.security.TenantGuard;
import dev.chaya.api.web.BadRequestException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Natural-language object search over POIs, both human-placed and Grounding-DINO-detected (see
 * dev.chaya.api.pipeline.PipelineService#ingestDetectedObjects). There is no hardcoded synonym table: "couch" finds
 * "sofa" because CLIP puts related text near each other, not because code maps one word to another.
 *
 * <p><b>Two embedding spaces, never mixed</b> (V21__search_embedding_spaces.sql, docs/search.md "Ranking"). The query is
 * embedded with CLIP's text tower (TextEmbeddingClient -> services/vision).
 * <ul>
 *   <li><b>Text space: ranking and relevance for every POI.</b> Every POI has a text-tower {@code embedding} of its own
 *       text (a manual POI's label, category and tags; a detected object's detector label). Query-to-POI cosine is
 *       therefore text-to-text for every candidate, one scale, and one ordering for manual and detected POIs alike.</li>
 *   <li><b>Image space: detected objects only.</b> {@code image_embedding} is the image tower's vector of the detection
 *       crop. A text-to-image cosine is never compared with a text-to-text one. It is used only in the two ways CLIP
 *       defines for its towers: ranking images for one text (among detected objects whose text score is identical, i.e.
 *       the same detector label, so "red chair" orders the chairs), and zero-shot classification of one image over a
 *       set of texts (a detected object also counts as relevant if the query describes its crop at least as well as
 *       every detector label in scope does).</li>
 * </ul>
 * Only embeddings from the query's own model are compared (embedding_model / image_embedding_model = the model
 * services/vision reports); rows from any other model are left out rather than scored in a foreign space.
 *
 * <p><b>Relevance.</b> CLIP puts almost any two short phrases at cosine ~0.8, so the nearest POIs come back even for
 * something the venue does not have. A POI counts as a result only if its text similarity exceeds the query's mean
 * similarity to the venue's distinct POI texts by chaya.search.relevance-min-margin (calibrated on a separate venue,
 * docs/BENCHMARKS.md B3). The mean is over DISTINCT text vectors so that 200 detected "chair"s count once, not 200
 * times. When nothing clears it, results is empty and the nearest candidates are returned as closestMatches. Scopes
 * with fewer than chaya.search.relevance-min-pois distinct texts are not judged.
 *
 * <p>If the embedding model is unavailable the search degrades to Postgres trigram similarity on the label text
 * (pg_trgm), reported as such via SearchResponse.matchType so a caller never mistakes it for semantic matching.
 */
@Service
public class SemanticSearchService {

    private static final Logger log = LoggerFactory.getLogger(SemanticSearchService.class);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    // Shared WHERE clause (venue/org/floor/accessibility scope); each query below prepends its own SELECT and appends its
    // own extra predicate and ORDER BY. Without :sv the scope is every live POI at its latest version; with :sv (a
    // FINALIZED ScanVersion) it is exactly that version's POIs, each as it is in that version (scan_version_poi_version).
    private static final String SCOPE_WHERE = """
              FROM poi p
              JOIN poi_version v ON v.poi_id = p.id
             WHERE p.venue_id = :venue AND p.organization_id = :org
               AND (CASE WHEN CAST(:sv AS uuid) IS NULL
                         THEN p.deleted_at IS NULL
                              AND v.version_number = (SELECT max(version_number) FROM poi_version WHERE poi_id = p.id)
                         ELSE v.id = scan_version_poi_version(p.id, CAST(:sv AS uuid))
                              AND p.floor_id = (SELECT floor_id FROM scan_version WHERE id = CAST(:sv AS uuid)) END)
               AND (CAST(:floor AS uuid) IS NULL OR p.floor_id = CAST(:floor AS uuid))
               AND (:accessible = false OR COALESCE((v.attributes->>'accessible')::boolean, false) = true)
            """;

    // Every POI in scope is scored (not only the index's top k): the relevance statistics need all of them, and the image
    // channel can admit a detected object whose text score alone would not reach the top k. A venue's POIs number in the
    // hundreds to thousands, so this exact scan replaces the HNSW lookup at no practical cost.
    //
    //   vocabulary       the distinct text vectors in scope: what the venue has names for, each name counted once
    //   detector_labels  the distinct text vectors of detected objects: the classes a detected crop is classified over
    //   relevant_image   CLIP zero-shot: the crop is at least as close to the query as to every detector label in scope
    //                    (needs >= 2 labels: against a single class the test says nothing)
    //
    // Relevant rows sort first, then by text similarity (the one space every row shares), then, among rows with the same
    // text similarity, by image similarity (CLIP text-to-image retrieval), then by id for a stable order.
    private static final String VECTOR_SQL = """
            WITH q AS (SELECT CAST(:qv AS vector) AS v),
            scope AS (
            SELECT p.id AS poi_id, p.floor_id, v.label, v.category, v.tags, v.x, v.y, v.z, v.detection_confidence,
                   v.bounding_box, v.source, v.embedding,
                   CASE WHEN v.image_embedding_model = :model THEN v.image_embedding END AS image_embedding
            """ + SCOPE_WHERE + """
               AND v.embedding IS NOT NULL AND v.embedding_model = :model),
            vocabulary AS (SELECT DISTINCT embedding FROM scope),
            stats AS (SELECT avg(1 - (vo.embedding <=> q.v)) AS mean_similarity, count(*) AS peers FROM vocabulary vo, q),
            detector_labels AS (SELECT DISTINCT embedding FROM scope WHERE source = 'AUTO_DETECTED'),
            label_count AS (SELECT count(*) AS n FROM detector_labels),
            scored AS (
            SELECT s.poi_id, s.floor_id, s.label, s.category, s.tags, s.x, s.y, s.z, s.detection_confidence, s.bounding_box,
                   s.source,
                   1 - (s.embedding <=> q.v) AS similarity,
                   1 - (s.image_embedding <=> q.v) AS image_similarity,
                   CASE WHEN s.image_embedding IS NOT NULL
                        THEN (SELECT max(1 - (s.image_embedding <=> d.embedding)) FROM detector_labels d) END AS best_label_image_similarity
              FROM scope s, q),
            judged AS (
            SELECT sc.*, sc.similarity - st.mean_similarity AS margin, st.peers,
                   st.peers >= :minPois AS judged,
                   sc.similarity - st.mean_similarity >= :minMargin AS relevant_text,
                   COALESCE(lc.n >= 2 AND sc.image_similarity >= sc.best_label_image_similarity, false) AS relevant_image
              FROM scored sc, stats st, label_count lc)
            SELECT j.*, (NOT j.judged OR j.relevant_text OR j.relevant_image) AS relevant
              FROM judged j
             ORDER BY relevant DESC, j.similarity DESC, j.image_similarity DESC NULLS LAST, j.poi_id
             LIMIT :limit
            """;

    static final String FILTERED = "FILTERED";
    static final String UNFILTERED = "UNFILTERED";
    static final String LEXICAL = "LEXICAL";

    /** Which evidence made a judged embedding match relevant (SearchResult.matchedBy). */
    static final String BY_TEXT = "TEXT";
    static final String BY_IMAGE = "IMAGE";
    static final String BY_TEXT_AND_IMAGE = "TEXT_AND_IMAGE";

    private record Scored(SearchResult result, boolean judged, boolean relevant) {}

    private static final String LEXICAL_SQL =
        "SELECT p.id AS poi_id, p.floor_id, v.label, v.category, v.tags, v.x, v.y, v.z, v.detection_confidence, "
        + "v.bounding_box, v.source, similarity(v.label, :q) AS similarity\n" + SCOPE_WHERE
        + "   AND (v.label ILIKE ('%' || :q || '%') OR similarity(v.label, :q) > 0.2)\n"
        + " ORDER BY similarity(v.label, :q) DESC\n"
        + " LIMIT :k";

    private final JdbcClient jdbc;
    private final TenantGuard guard;
    private final TextEmbeddingClient embeddings;
    private final SearchProperties props;
    private final ObjectMapper mapper;
    private final ScanVersionService versions;

    public SemanticSearchService(JdbcClient jdbc, TenantGuard guard, TextEmbeddingClient embeddings, SearchProperties props,
                                 ObjectMapper mapper, ScanVersionService versions) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.embeddings = embeddings;
        this.props = props;
        this.mapper = mapper;
        this.versions = versions;
    }

    @Transactional
    public SearchResponse search(Actor actor, UUID venueId, String query, UUID floorId, Integer topK, Boolean accessibleOnly) {
        return search(actor, venueId, query, floorId, topK, accessibleOnly, null);
    }

    /** scanVersionId: search only that FINALIZED version's POIs (what a viewer showing that version displays). */
    @Transactional
    public SearchResponse search(Actor actor, UUID venueId, String query, UUID floorId, Integer topK, Boolean accessibleOnly,
                                 UUID scanVersionId) {
        guard.requireVenue(actor, venueId);
        if (scanVersionId != null) {
            versions.requireFinalized(actor, venueId, scanVersionId);
        }
        String normalized = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new BadRequestException("q must not be blank");
        }
        int k = Math.max(1, Math.min(props.maxTopK(), topK == null || topK <= 0 ? props.defaultTopK() : topK));
        boolean accessible = Boolean.TRUE.equals(accessibleOnly);

        long started = System.nanoTime();
        String matchType;
        String relevance;
        List<SearchResult> results;
        List<SearchResult> closest = List.of();
        try {
            TextEmbeddingClient.Embedding queryEmbedding = embeddings.embedWithModel(normalized);
            List<Scored> scored = jdbc.sql(VECTOR_SQL)
                .param("venue", venueId).param("org", actor.organizationId()).param("floor", floorId).param("accessible", accessible)
                .param("sv", scanVersionId)
                .param("qv", vectorLiteral(queryEmbedding.vector())).param("model", queryEmbedding.model())
                .param("minPois", props.relevanceMinPois()).param("minMargin", props.relevanceMinMargin())
                .param("limit", Math.max(k, props.closestMatches()))
                .query(this::mapScored).list();
            // Relevant rows come first (VECTOR_SQL's ORDER BY), so the results are a prefix of the list.
            results = scored.stream().filter(Scored::relevant).limit(k).map(Scored::result).toList();
            boolean judged = !scored.isEmpty() && scored.get(0).judged();
            relevance = judged ? FILTERED : UNFILTERED;
            if (results.isEmpty()) {
                closest = scored.stream().limit(props.closestMatches()).map(Scored::result).toList();
            }
            matchType = "embedding";
        } catch (EmbeddingUnavailableException e) {
            log.warn("embedding model unavailable, falling back to lexical search: {}", e.getMessage());
            results = jdbc.sql(LEXICAL_SQL)
                .param("venue", venueId).param("org", actor.organizationId()).param("floor", floorId).param("accessible", accessible)
                .param("sv", scanVersionId)
                .param("q", normalized).param("k", k)
                .query((rs, i) -> mapRow(rs, null, null, null)).list();
            matchType = "lexical_fallback";
            relevance = LEXICAL;
        }
        int latencyMs = (int) ((System.nanoTime() - started) / 1_000_000);
        logQuery(actor, venueId, normalized, results.size(), latencyMs);
        return new SearchResponse(query, matchType, results, closest, relevance);
    }

    private Scored mapScored(ResultSet rs, int i) throws SQLException {
        boolean judged = rs.getBoolean("judged");
        boolean relevant = rs.getBoolean("relevant");
        String matchedBy = null;
        if (judged && relevant) {
            boolean text = rs.getBoolean("relevant_text");
            boolean image = rs.getBoolean("relevant_image");
            matchedBy = text && image ? BY_TEXT_AND_IMAGE : text ? BY_TEXT : BY_IMAGE;
        }
        Double imageSimilarity = rs.getObject("image_similarity", Double.class);
        return new Scored(mapRow(rs, rs.getDouble("margin"), imageSimilarity, matchedBy), judged, relevant);
    }

    private SearchResult mapRow(ResultSet rs, Double margin, Double imageSimilarity, String matchedBy) throws SQLException {
        String[] tagsArr = (String[]) rs.getArray("tags").getArray();
        Object bboxRaw = rs.getObject("bounding_box");
        Map<String, Object> bbox = bboxRaw == null ? null : parseJson(bboxRaw.toString());
        Object confidenceRaw = rs.getObject("detection_confidence");
        Double confidence = confidenceRaw == null ? null : ((Number) confidenceRaw).doubleValue();
        Object sourceFrame = bbox == null ? null : bbox.get("sourceFrame");
        return new SearchResult(rs.getObject("poi_id", UUID.class), rs.getObject("floor_id", UUID.class), rs.getString("label"),
            rs.getString("category"), List.of(tagsArr), rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"),
            rs.getDouble("similarity"), confidence, rs.getString("source"), bbox, margin, imageSimilarity, matchedBy,
            sourceFrame instanceof String s ? s : null);
    }

    private Map<String, Object> parseJson(String json) {
        try {
            return mapper.readValue(json, MAP);
        } catch (Exception e) {
            return null;
        }
    }

    private void logQuery(Actor actor, UUID venueId, String normalized, int resultCount, int latencyMs) {
        jdbc.sql("INSERT INTO search_query (organization_id, venue_id, actor_id, query_normalized, result_count, latency_ms) "
                + "VALUES (:o, :v, :a, :q, :rc, :lat)")
            .param("o", actor.organizationId()).param("v", venueId).param("a", actor.subject())
            .param("q", normalized).param("rc", resultCount).param("lat", latencyMs).update();
    }

    static String vectorLiteral(float[] values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(values[i]);
        }
        return sb.append(']').toString();
    }
}
