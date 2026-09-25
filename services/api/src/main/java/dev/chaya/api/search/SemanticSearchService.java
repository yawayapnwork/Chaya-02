package dev.chaya.api.search;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.chaya.api.search.SearchDtos.SearchResponse;
import dev.chaya.api.search.SearchDtos.SearchResult;
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
 * Natural-language object search over POIs (both human-placed and Grounding-DINO-detected, see
 * dev.chaya.api.pipeline.PipelineService#ingestDetectedObjects). The query is embedded with the exact
 * same CLIP model that embedded every POI (TextEmbeddingClient -> services/vision) and ranked by pgvector
 * cosine similarity; there is no hardcoded synonym table anywhere in this class -- "couch"/"sofa"/"seating"
 * rank near each other because CLIP's text and image towers were trained to put semantically related text
 * and images near each other in the same space, not because any code here maps one word to another.
 *
 * If the embedding model is unavailable the search degrades to Postgres trigram similarity on the label
 * text (pg_trgm) -- still a real ranking, just a lexical one, and always reported as such via
 * SearchResponse.matchType so a caller never mistakes it for semantic matching.
 *
 * <p>Relevance: CLIP puts almost any two short phrases at cosine ~0.8, so the top-k nearest POIs are returned even for
 * something the venue does not have ("swimming pool" -> "Water fountain"). A match of a manually placed POI therefore
 * counts as a result only if its similarity exceeds the query's MEAN similarity to all searched POIs of the same
 * source by chaya.search.relevance-min-margin: a real match stands out from the rest of the venue, a query for
 * something absent is about equally close to everything. When nothing clears it, results is empty and the nearest
 * candidates are returned separately as closestMatches. The margin was chosen on a calibration venue and measured on a
 * held-out one (docs/BENCHMARKS.md B3); detected objects (image embeddings) and scopes with fewer than
 * chaya.search.relevance-min-pois POIs are not judged, because nothing calibrates them.
 */
@Service
public class SemanticSearchService {

    private static final Logger log = LoggerFactory.getLogger(SemanticSearchService.class);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    // Shared WHERE clause (venue/org/floor/accessibility scope); each query below prepends its own SELECT
    // (with a query-specific `similarity` column) and appends its own extra predicate and ORDER BY.
    private static final String SCOPE_WHERE = """
              FROM poi p
              JOIN poi_version v ON v.poi_id = p.id
             WHERE p.venue_id = :venue AND p.organization_id = :org AND p.deleted_at IS NULL
               AND v.version_number = (SELECT max(version_number) FROM poi_version WHERE poi_id = p.id)
               AND (CAST(:floor AS uuid) IS NULL OR p.floor_id = CAST(:floor AS uuid))
               AND (:accessible = false OR COALESCE((v.attributes->>'accessible')::boolean, false) = true)
            """;

    // Every searched POI is scored (not only the top k), so each result's margin over the mean similarity of its own
    // source can be computed: manual POIs carry CLIP text embeddings and detected ones CLIP image embeddings, whose
    // similarities to a text query live on different scales. A venue's POIs number in the hundreds to thousands, so
    // this exact scan replaces the HNSW index lookup at no practical cost.
    private static final String VECTOR_SQL =
        "WITH scored AS (SELECT p.id AS poi_id, p.floor_id, v.label, v.category, v.tags, v.x, v.y, v.z, v.detection_confidence, "
        + "v.bounding_box, v.source, 1 - (v.embedding <=> CAST(:qv AS vector)) AS similarity\n" + SCOPE_WHERE
        + "   AND v.embedding IS NOT NULL)\n"
        + "SELECT s.*, s.similarity - avg(s.similarity) OVER (PARTITION BY s.source) AS margin,\n"
        + "       count(*) OVER (PARTITION BY s.source) AS peers\n"
        + "  FROM scored s ORDER BY s.similarity DESC, s.poi_id\n"
        + " LIMIT :k";

    static final String FILTERED = "FILTERED";
    static final String UNFILTERED = "UNFILTERED";
    static final String LEXICAL = "LEXICAL";

    private record Scored(SearchResult result, double margin, long peers) {}

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

    public SemanticSearchService(JdbcClient jdbc, TenantGuard guard, TextEmbeddingClient embeddings, SearchProperties props,
                                 ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.embeddings = embeddings;
        this.props = props;
        this.mapper = mapper;
    }

    @Transactional
    public SearchResponse search(Actor actor, UUID venueId, String query, UUID floorId, Integer topK, Boolean accessibleOnly) {
        guard.requireVenue(actor, venueId);
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
            float[] queryEmbedding = embeddings.embed(normalized);
            List<Scored> scored = jdbc.sql(VECTOR_SQL)
                .param("venue", venueId).param("org", actor.organizationId()).param("floor", floorId).param("accessible", accessible)
                .param("qv", vectorLiteral(queryEmbedding)).param("k", k)
                .query((rs, i) -> new Scored(mapRow(rs, i, rs.getDouble("margin")), rs.getDouble("margin"), rs.getLong("peers"))).list();
            results = new ArrayList<>();
            List<SearchResult> below = new ArrayList<>();
            boolean judged = false;
            for (Scored s : scored) {
                Boolean relevant = relevant(s);
                judged |= relevant != null;
                if (relevant == null || relevant) {
                    results.add(s.result());
                } else {
                    below.add(s.result());
                }
            }
            relevance = judged ? FILTERED : UNFILTERED;
            if (results.isEmpty()) {
                closest = below.subList(0, Math.min(props.closestMatches(), below.size()));
            }
            matchType = "embedding";
        } catch (EmbeddingUnavailableException e) {
            log.warn("embedding model unavailable, falling back to lexical search: {}", e.getMessage());
            results = jdbc.sql(LEXICAL_SQL)
                .param("venue", venueId).param("org", actor.organizationId()).param("floor", floorId).param("accessible", accessible)
                .param("q", normalized).param("k", k)
                .query((rs, i) -> mapRow(rs, i, null)).list();
            matchType = "lexical_fallback";
            relevance = LEXICAL;
        }
        int latencyMs = (int) ((System.nanoTime() - started) / 1_000_000);
        logQuery(actor, venueId, normalized, results.size(), latencyMs);
        return new SearchResponse(query, matchType, results, closest, relevance);
    }

    /**
     * Whether an embedding match is relevant enough to count as a result, or null when it cannot be judged. Judged only
     * for MANUAL POIs (CLIP text embeddings, the case the threshold was calibrated on) and only when there are enough of
     * them in the searched scope for their mean to mean something. Detected objects carry image embeddings, for which
     * no threshold has been calibrated, so they are never filtered out.
     */
    private Boolean relevant(Scored s) {
        if (!"MANUAL".equals(s.result().source()) || s.peers() < props.relevanceMinPois()) {
            return null;
        }
        return s.margin() >= props.relevanceMinMargin();
    }

    private SearchResult mapRow(ResultSet rs, int i, Double margin) throws SQLException {
        String[] tagsArr = (String[]) rs.getArray("tags").getArray();
        Object bboxRaw = rs.getObject("bounding_box");
        Map<String, Object> bbox = bboxRaw == null ? null : parseJson(bboxRaw.toString());
        Object confidenceRaw = rs.getObject("detection_confidence");
        Double confidence = confidenceRaw == null ? null : ((Number) confidenceRaw).doubleValue();
        return new SearchResult(rs.getObject("poi_id", UUID.class), rs.getObject("floor_id", UUID.class), rs.getString("label"),
            rs.getString("category"), List.of(tagsArr), rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"),
            rs.getDouble("similarity"), confidence, rs.getString("source"), bbox,
            margin == null || !"MANUAL".equals(rs.getString("source")) ? null : margin);
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
