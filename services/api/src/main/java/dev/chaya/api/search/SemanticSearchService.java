package dev.chaya.api.search;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.chaya.api.navigation.NavigationDtos.RouteRequest;
import dev.chaya.api.navigation.NavigationDtos.RouteResponse;
import dev.chaya.api.navigation.RouteService;
import dev.chaya.api.search.QueryInterpreter.Interpretation;
import dev.chaya.api.search.SearchDtos.Correction;
import dev.chaya.api.search.SearchDtos.SearchInterpretation;
import dev.chaya.api.search.SearchDtos.SearchPosition;
import dev.chaya.api.search.SearchDtos.SearchResponse;
import dev.chaya.api.search.SearchDtos.SearchResult;
import dev.chaya.api.rescan.ScanVersionService;
import dev.chaya.api.security.Actor;
import dev.chaya.api.security.PublicExposure;
import dev.chaya.api.security.TenantGuard;
import dev.chaya.api.web.ApiException;
import dev.chaya.api.web.BadRequestException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Natural-language object search over POIs, both human-placed and Grounding-DINO-detected (see
 * dev.chaya.api.pipeline.PipelineService#ingestDetectedObjects). The full description, with every rule and constant, is
 * docs/search.md; this comment is its summary.
 *
 * <p><b>Query understanding</b> (QueryInterpreter): the query is normalised, request phrasing ("where is", "find the") and
 * modifiers ("nearest", "wheelchair accessible") are taken off, misspelt words are corrected against the venue's own words
 * and a small synonym table, and the synonym table adds alternative phrasings for name matching. The subject that is
 * left is what CLIP embeds. No language model is involved; the same query always reads the same way.
 *
 * <p><b>Two embedding spaces, never mixed</b> (V21__search_embedding_spaces.sql). The subject is embedded with CLIP's text
 * tower (TextEmbeddingClient -> services/vision).
 * <ul>
 *   <li><b>Text space: ranking and relevance for every POI.</b> Every POI has a text-tower {@code embedding} of its own
 *       text (a manual POI's label, category and tags; a detected object's detector label). Query-to-POI cosine is
 *       therefore text-to-text for every candidate, one scale, and one ordering for manual and detected POIs alike.</li>
 *   <li><b>Image space: detected objects only.</b> {@code image_embedding} is the image tower's vector of the detection
 *       crop. A text-to-image cosine is never compared with a text-to-text one. It orders detected objects with the same
 *       text score (the same detector label: "red chair" orders the chairs), and admits a detected object whose crop the
 *       query describes at least as well as every detector label in scope (CLIP zero-shot classification).</li>
 * </ul>
 * Only embeddings from the query's own model are compared; rows from any other model, and rows whose embedding is still
 * pending, take part by name only (indexStatus PENDING_EMBEDDING).
 *
 * <p><b>Relevance.</b> A POI is a result if its text similarity exceeds the query's mean similarity to the venue's distinct
 * POI texts by chaya.search.relevance-min-margin (calibrated, docs/BENCHMARKS.md B3), if the image test admits it, or if
 * one of the query's phrasings matches its name (pg_trgm strict_word_similarity of at least
 * chaya.search.lexical-min-similarity). Nothing relevant: results is empty, the nearest candidates are closestMatches.
 *
 * <p><b>Order</b> (deterministic for identical inputs and data): relevant first; spatially VALID before every other tier;
 * for a "nearest" query with a position, by distance (below); then exact name/category/tag matches; then the rank score
 * {@code similarity + lexicalWeight * lexical + evidenceWeight * (evidence - 1)}; then lexical similarity; then image
 * similarity; then POI id.
 *
 * <p><b>Nearest.</b> Only with a position (SearchPosition). Distances are real walking routes from RouteService (STEP_FREE
 * when accessibility is required), planned in increasing order of their straight-line lower bound and stopped as soon as
 * no unplanned candidate can be nearer than the k-th planned one, at most chaya.search.max-route-evaluations per search.
 * When the floor has no routable navigation graph, the straight-line distance in the floor's frame is used and labelled
 * as such. Without a position the modifier is reported as not applied, and results keep their relevance order.
 *
 * <p><b>Accessibility</b> is only what staff recorded ({@code attributes.accessible}). If nothing recorded as accessible
 * matches, the matches whose accessibility nobody recorded are returned separately (accessibilityUnconfirmed), never as
 * results.
 *
 * <p><b>Duplicates.</b> Two detected objects of one synonym group within chaya.search.duplicate-distance-m on one floor
 * and in one frame ("sofa" and "couch" detections of one sofa, which clustering keeps apart) are shown once.
 *
 * <p>If the embedding model is unavailable the search degrades to Postgres trigram similarity on the label (pg_trgm),
 * reported as matchType "lexical_fallback"; its results keep the spatial tiers, distance ordering and duplicate folding.
 */
@Service
public class SemanticSearchService {

    private static final Logger log = LoggerFactory.getLogger(SemanticSearchService.class);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    // Shared WHERE clause (venue/org/floor scope); each query below prepends its own SELECT and appends its own extra
    // predicate and ORDER BY. The accessibility filter is not part of the scope (IN_FILTER below): relevance is judged
    // against everything the venue has, and only then are results limited to what staff recorded as accessible, so the
    // filter cannot shrink the scope into the unjudged "too few POIs" case and return whatever is left. Without :sv the scope is what each floor publishes now: every live POI at its
    // latest version that belongs to its floor's current scan version (poi_version_is_current, V28), so a search never
    // returns a detection of an unpublished run or of a replaced version next to the current ones; with :sv (a FINALIZED
    // ScanVersion) it is exactly that version's POIs, each as it is in that version (scan_version_poi_version). Machine-made
    // POIs always name the pipeline run that made them (poi_version_auto_detected_has_provenance, V12).
    private static final String SCOPE_WHERE = """
              FROM poi p
              JOIN poi_version v ON v.poi_id = p.id
             WHERE p.venue_id = :venue AND p.organization_id = :org
               AND (CASE WHEN CAST(:sv AS uuid) IS NULL
                         THEN p.deleted_at IS NULL
                              AND v.version_number = (SELECT max(version_number) FROM poi_version WHERE poi_id = p.id)
                              AND poi_version_is_current(p.floor_id, v.scan_version_id, v.source)
                         ELSE v.id = scan_version_poi_version(p.id, CAST(:sv AS uuid))
                              AND p.floor_id = (SELECT floor_id FROM scan_version WHERE id = CAST(:sv AS uuid)) END)
               AND (CAST(:floor AS uuid) IS NULL OR p.floor_id = CAST(:floor AS uuid))
               -- a public link never sees what was detected in frames that were never anonymised (review S-6)
               AND (:anonymous = false OR v.pipeline_run_id IS NULL
                    OR EXISTS (SELECT 1 FROM pipeline_run pr WHERE pr.id = v.pipeline_run_id AND pr.privacy_enabled))
            """;

    // Where a POI's position stands relative to what is being viewed: the floor's current frame, or the selected version's.
    private static final String SPATIAL_STATUS = """
            CASE WHEN v.coordinate_frame_id IS NULL THEN 'UNBOUND'
                 WHEN v.coordinate_frame_id IS DISTINCT FROM (CASE WHEN CAST(:sv AS uuid) IS NULL
                          THEN (SELECT current_coordinate_frame_id FROM floor WHERE id = p.floor_id)
                          ELSE (SELECT coordinate_frame_id FROM scan_version WHERE id = CAST(:sv AS uuid)) END) THEN 'STALE_FRAME'
                 WHEN v.source = 'AUTO_DETECTED' AND v.localization_status IS NULL THEN 'UNVERIFIED'
                 ELSE 'VALID' END""";

    // A manual POI: 1 (staff placed it). A detected object: its detector confidence, times how its position is supported.
    private static final String EVIDENCE = """
            CASE WHEN v.source = 'AUTO_DETECTED'
                 THEN COALESCE(v.detection_confidence, 0)
                      * CASE v.localization_status WHEN 'MULTI_VIEW' THEN 1.0 WHEN 'SINGLE_VIEW' THEN 0.75 ELSE 0.5 END
                 ELSE 1.0 END""";

    // The POI's own text as lexical matching sees it, and the normalisation QueryInterpreter.normalize applies to a query.
    private static final String DOC = "lower(concat_ws(' ', v.label, v.category, array_to_string(v.tags, ' ')))";
    private static final String NORM = "btrim(regexp_replace(lower(%s), '[^[:alnum:]]+', ' ', 'g'))";

    private static final String COMMON_COLUMNS = " " + """
            p.id AS poi_id, p.floor_id, v.label, v.category, v.tags, v.x, v.y, v.z, v.detection_confidence, v.bounding_box,
            v.source, v.scan_version_id, v.pipeline_run_id, v.detection_artifact_id, v.detection_provenance,
            CASE WHEN jsonb_typeof(v.attributes->'accessible') = 'boolean' THEN (v.attributes->>'accessible')::boolean END AS accessible_attr,
            (:accessible = false OR (jsonb_typeof(v.attributes->'accessible') = 'boolean'
                                     AND (v.attributes->>'accessible')::boolean)) AS in_filter,
            v.localization_status, v.localization_uncertainty_m, """ + SPATIAL_STATUS + " AS spatial_status, " + EVIDENCE + " AS evidence, "
        + DOC + " AS doc, " + NORM.formatted("v.label") + " AS norm_label, " + NORM.formatted("v.category") + " AS norm_category, "
        + "ARRAY(SELECT " + NORM.formatted("t") + " FROM unnest(v.tags) t) AS norm_tags";

    // Per row: the best lexical match over the query's phrasings (on a tie, the user's own words before synonyms: terms keep
    // QueryInterpreter's order), and whether a phrasing IS the POI's label, category or tag.
    private static final String LEXICAL_JOIN = """
            LEFT JOIN LATERAL (SELECT CAST(strict_word_similarity(t.t, s.doc) AS double precision) AS lexical_similarity,
                                      t.t AS matched_term
                                 FROM terms t ORDER BY strict_word_similarity(t.t, s.doc) DESC, t.ord LIMIT 1) lex ON true""";
    private static final String EXACT = """
            EXISTS (SELECT 1 FROM terms t WHERE t.t = s.norm_label OR t.t = s.norm_category OR t.t = ANY (s.norm_tags))""";

    // Every POI in scope is scored (not only the index's top k): the relevance statistics need all of them, and the image
    // channel can admit a detected object whose text score alone would not reach the top k. A venue's POIs number in the
    // hundreds to thousands, so this exact scan replaces the HNSW lookup at no practical cost.
    //
    //   vocabulary       the distinct text vectors in scope: what the venue has names for, each name counted once
    //   detector_labels  the distinct text vectors of detected objects: the classes a detected crop is classified over
    //   relevant_image   CLIP zero-shot: the crop is at least as close to the query as to every detector label in scope
    //                    (needs >= 2 labels: against a single class the test says nothing)
    //   relevant_lexical a phrasing of the query (QueryInterpreter: as typed, corrected, synonyms) matches the POI's text
    //                    whole-word by trigrams
    //
    // A row whose text embedding is missing or of another model has similarity NULL: it can be relevant by name only.
    private static final String VECTOR_SQL = """
            WITH q AS (SELECT CAST(:qv AS vector) AS v),
            terms AS (SELECT u.t, min(u.ord) AS ord FROM unnest(CAST(ARRAY[:terms] AS text[])) WITH ORDINALITY AS u(t, ord) GROUP BY u.t),
            scope AS (
            SELECT """ + COMMON_COLUMNS + """
                   , CASE WHEN v.embedding_model = :model THEN v.embedding END AS embedding,
                   CASE WHEN v.image_embedding_model = :model THEN v.image_embedding END AS image_embedding
            """ + SCOPE_WHERE + """
            ),
            vocabulary AS (SELECT DISTINCT embedding FROM scope WHERE embedding IS NOT NULL),
            stats AS (SELECT avg(1 - (vo.embedding <=> q.v)) AS mean_similarity, count(*) AS peers FROM vocabulary vo, q),
            detector_labels AS (SELECT DISTINCT embedding FROM scope WHERE source = 'AUTO_DETECTED' AND embedding IS NOT NULL),
            label_count AS (SELECT count(*) AS n FROM detector_labels),
            scored AS (
            SELECT s.*, lex.lexical_similarity, lex.matched_term, """ + EXACT + """
                    AS exact_match,
                   1 - (s.embedding <=> q.v) AS similarity,
                   1 - (s.image_embedding <=> q.v) AS image_similarity,
                   CASE WHEN s.image_embedding IS NOT NULL
                        THEN (SELECT max(1 - (s.image_embedding <=> d.embedding)) FROM detector_labels d) END AS best_label_image_similarity
              FROM scope s CROSS JOIN q
            """ + LEXICAL_JOIN + """
            ),
            judged AS (
            SELECT sc.*, sc.similarity - st.mean_similarity AS margin, st.peers,
                   st.peers >= :minPois AS judged,
                   COALESCE(sc.similarity - st.mean_similarity >= :minMargin, false) AS relevant_text,
                   COALESCE(lc.n >= 2 AND sc.image_similarity >= sc.best_label_image_similarity, false) AS relevant_image,
                   COALESCE(sc.lexical_similarity >= :lexMin, false) AS relevant_lexical,
                   sc.similarity + :wLex * COALESCE(sc.lexical_similarity, 0) + :wEv * (sc.evidence - 1) AS rank_score
              FROM scored sc, stats st, label_count lc)
            SELECT j.poi_id, j.floor_id, j.label, j.category, j.tags, j.x, j.y, j.z, j.detection_confidence, j.bounding_box,
                   j.source, j.scan_version_id, j.pipeline_run_id, j.detection_artifact_id, j.detection_provenance,
                   j.accessible_attr, j.localization_status, j.localization_uncertainty_m, j.spatial_status, j.evidence,
                   j.norm_label, j.lexical_similarity, j.matched_term, j.exact_match, j.similarity, j.image_similarity, j.margin,
                   j.judged, j.relevant_text, j.relevant_image, j.relevant_lexical, j.rank_score,
                   j.embedding IS NOT NULL AS indexed,
                   ((j.embedding IS NOT NULL AND NOT j.judged) OR j.relevant_text OR j.relevant_image OR j.relevant_lexical) AS relevant
              FROM judged j
             WHERE j.in_filter
             ORDER BY relevant DESC, (j.spatial_status = 'VALID') DESC, j.exact_match DESC, j.rank_score DESC NULLS LAST,
                      j.lexical_similarity DESC NULLS LAST, j.image_similarity DESC NULLS LAST, j.poi_id
             LIMIT :limit
            """;

    // The fallback while the vision service is down: trigram similarity of each phrasing with the label.
    private static final String LEXICAL_SQL = """
            WITH terms AS (SELECT u.t, min(u.ord) AS ord FROM unnest(CAST(ARRAY[:terms] AS text[])) WITH ORDINALITY AS u(t, ord) GROUP BY u.t),
            scope AS (SELECT """ + COMMON_COLUMNS + SCOPE_WHERE + """
            ),
            scored AS (
            SELECT s.*, lex.lexical_similarity, lex.matched_term, """ + EXACT + """
                    AS exact_match,
                   (SELECT max(similarity(s.label, t.t)) FROM terms t) AS label_similarity,
                   EXISTS (SELECT 1 FROM terms t WHERE s.label ILIKE ('%' || t.t || '%')) AS contains_term
              FROM scope s
            """ + LEXICAL_JOIN + """
            )
            SELECT sc.*, CAST(sc.label_similarity AS double precision) AS similarity
              FROM scored sc
             WHERE sc.in_filter AND (sc.contains_term OR sc.label_similarity > 0.2)
             ORDER BY (sc.spatial_status = 'VALID') DESC, sc.exact_match DESC, sc.label_similarity DESC, sc.poi_id
             LIMIT :limit
            """;

    private static final String VOCABULARY_SQL = "SELECT DISTINCT " + DOC + " AS doc" + SCOPE_WHERE;

    static final String FILTERED = "FILTERED";
    static final String UNFILTERED = "UNFILTERED";
    static final String LEXICAL = "LEXICAL";

    /** Which evidence made a judged embedding match relevant (SearchResult.matchedBy). */
    static final String BY_TEXT = "TEXT";
    static final String BY_IMAGE = "IMAGE";
    static final String BY_TEXT_AND_IMAGE = "TEXT_AND_IMAGE";
    static final String BY_LEXICAL = "LEXICAL";
    static final String BY_SYNONYM = "SYNONYM";

    static final String ROUTE = "ROUTE";
    static final String STRAIGHT_LINE = "STRAIGHT_LINE";
    static final String NOT_EVALUATED = "NOT_EVALUATED";

    /** Routing failures that mean "there is nothing to route on here", not "this destination is unreachable". */
    private static final Set<String> ROUTING_UNAVAILABLE = Set.of("NAVMESH_NOT_READY", "METRIC_CALIBRATION_REQUIRED",
        "VERSION_MISMATCH", "VERSION_WRONG_FLOOR");

    /** All search parameters. position: for "nearest" (SearchPosition); everything else as on the endpoint. */
    public record SearchQuery(String q, UUID floorId, Integer topK, Boolean accessible, UUID scanVersionId,
                              SearchPosition position) {}

    /** One scored row while the response is put together. */
    private static final class Candidate {
        UUID poiId;
        UUID floorId;
        String label;
        String category;
        List<String> tags;
        double x;
        double y;
        double z;
        Double similarity;
        Double confidence;
        String source;
        Map<String, Object> bbox;
        Double margin;
        Double imageSimilarity;
        String matchedBy;
        String spatialStatus;
        String localizationStatus;
        Double localizationUncertainty;
        Double lexicalSimilarity;
        Double rankScore;
        UUID scanVersionId;
        UUID pipelineRunId;
        UUID artifactId;
        Map<String, Object> provenance;
        boolean exact;
        String matchedTerm;
        String normLabel;
        Boolean accessible;
        boolean indexed;
        boolean judged;
        boolean relevant;
        Double distance;
        String distanceKind;
        String routeStatus;
        Double lowerBound;
        final List<UUID> duplicates = new ArrayList<>();
    }

    private final JdbcClient jdbc;
    private final TenantGuard guard;
    private final TextEmbeddingClient embeddings;
    private final SearchProperties props;
    private final ObjectMapper mapper;
    private final ScanVersionService versions;
    private final RouteService routes;

    public SemanticSearchService(JdbcClient jdbc, TenantGuard guard, TextEmbeddingClient embeddings, SearchProperties props,
                                 ObjectMapper mapper, ScanVersionService versions, RouteService routes) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.embeddings = embeddings;
        this.props = props;
        this.mapper = mapper;
        this.versions = versions;
        this.routes = routes;
    }

    public SearchResponse search(Actor actor, UUID venueId, String query, UUID floorId, Integer topK, Boolean accessibleOnly) {
        return search(actor, venueId, new SearchQuery(query, floorId, topK, accessibleOnly, null, null));
    }

    /** scanVersionId: search only that FINALIZED version's POIs (what a viewer showing that version displays). */
    public SearchResponse search(Actor actor, UUID venueId, String query, UUID floorId, Integer topK, Boolean accessibleOnly,
                                 UUID scanVersionId) {
        return search(actor, venueId, new SearchQuery(query, floorId, topK, accessibleOnly, scanVersionId, null));
    }

    /**
     * Not one transaction: every statement reads committed data on its own, and "nearest" plans routes through RouteService,
     * whose expected failures (no route to one candidate) must not mark a surrounding transaction rollback-only.
     */
    public SearchResponse search(Actor actor, UUID venueId, SearchQuery request) {
        long started = System.nanoTime();
        guard.requireVenue(actor, venueId);
        ScanVersionService.Scope version = request.scanVersionId() == null ? null
            : versions.requireFinalized(actor, venueId, request.scanVersionId());
        if (QueryInterpreter.normalize(request.q()).isEmpty()) {
            throw new BadRequestException("q must not be blank");
        }
        SearchPosition position = validPosition(venueId, request.position());
        int k = Math.max(1, Math.min(props.maxTopK(), request.topK() == null || request.topK() <= 0 ? props.defaultTopK() : request.topK()));
        boolean anonymous = PublicExposure.isAnonymous(actor);

        List<String> vocabulary = jdbc.sql(VOCABULARY_SQL)
            .param("venue", venueId).param("org", actor.organizationId()).param("floor", request.floorId())
            .param("anonymous", anonymous).param("sv", request.scanVersionId())
            .query(String.class).list();
        Interpretation interp = QueryInterpreter.interpret(request.q(), QueryInterpreter.words(vocabulary));
        boolean accessible = Boolean.TRUE.equals(request.accessible()) || interp.accessibilityRequired();

        Scope scope = new Scope(venueId, actor.organizationId(), request.floorId(), anonymous, request.scanVersionId());
        Scored scored = score(scope, interp, accessible);

        List<Candidate> relevant = new ArrayList<>(scored.rows().stream().filter(c -> c.relevant).toList());
        foldDuplicates(relevant);

        boolean nearest = interp.spatialModifier() == QueryInterpreter.SpatialModifier.NEAREST;
        String spatialNote = null;
        boolean spatialApplied = false;
        if (nearest) {
            if (position == null) {
                spatialNote = "NO_POSITION";
            } else if (version != null && !version.floorId().equals(position.floorId())) {
                spatialNote = "POSITION_NOT_ON_VERSION_FLOOR";
            } else {
                orderByDistance(actor, venueId, request.scanVersionId(), position, accessible, relevant, k);
                spatialApplied = true;
            }
        }

        List<SearchResult> results = relevant.stream().limit(k).map(this::toResult).toList();
        List<SearchResult> closest = List.of();
        List<SearchResult> unconfirmed = List.of();
        if (results.isEmpty()) {
            closest = scored.rows().stream().filter(c -> !c.relevant).limit(props.closestMatches()).map(this::toResult).toList();
            if (accessible) {
                // Nothing staff recorded as accessible matches: offer the matches nobody recorded either way, as such.
                Scored open = score(scope, interp, false);
                List<Candidate> unknown = new ArrayList<>(open.rows().stream()
                    .filter(c -> c.relevant && !Boolean.FALSE.equals(c.accessible)).toList());
                foldDuplicates(unknown);
                unconfirmed = unknown.stream().limit(props.closestMatches()).map(this::toResult).toList();
            }
        }
        List<String> suggestions = results.isEmpty()
            ? closest.stream().map(SearchResult::label).distinct().toList() : List.of();
        boolean lowConfidence = !results.isEmpty() && results.stream()
            .allMatch(r -> Boolean.TRUE.equals(r.lowConfidence()) || BY_IMAGE.equals(r.matchedBy()));

        SearchInterpretation interpretation = new SearchInterpretation(interp.normalized(), interp.subject(), interp.lexicalTerms(),
            interp.synonymGroups(), interp.corrections().stream().map(c -> new Correction(c.from(), c.to())).toList(),
            targetKind(interp, relevant), interp.spatialModifier().name(), spatialApplied, spatialNote, accessible);

        int latencyMs = (int) ((System.nanoTime() - started) / 1_000_000);
        logQuery(actor, venueId, interp.normalized(), results.size(), latencyMs);
        return new SearchResponse(request.q(), scored.matchType(), results, closest, scored.relevance(), interpretation,
            lowConfidence, unconfirmed, suggestions);
    }

    private record Scope(UUID venue, UUID org, UUID floor, boolean anonymous, UUID scanVersion) {}

    private record Scored(List<Candidate> rows, String matchType, String relevance) {}

    /** The scored candidates of the scope, relevant first, in the order the class comment describes (without distance). */
    private Scored score(Scope scope, Interpretation interp, boolean accessible) {
        List<String> terms = interp.lexicalTerms();
        try {
            TextEmbeddingClient.Embedding queryEmbedding = embeddings.embedWithModel(interp.embeddingText());
            List<Candidate> rows = jdbc.sql(VECTOR_SQL)
                .param("venue", scope.venue()).param("org", scope.org()).param("floor", scope.floor()).param("accessible", accessible)
                .param("anonymous", scope.anonymous()).param("sv", scope.scanVersion())
                .param("qv", vectorLiteral(queryEmbedding.vector())).param("model", queryEmbedding.model())
                .param("terms", terms).param("lexMin", props.lexicalMinSimilarity())
                .param("wLex", props.lexicalWeight()).param("wEv", props.evidenceWeight())
                .param("minPois", props.relevanceMinPois()).param("minMargin", props.relevanceMinMargin())
                .param("limit", props.candidateLimit())
                .query((rs, i) -> mapScored(rs, interp)).list();
            boolean judged = rows.stream().anyMatch(c -> c.judged);
            return new Scored(rows, "embedding", judged ? FILTERED : UNFILTERED);
        } catch (EmbeddingUnavailableException e) {
            log.warn("embedding model unavailable, falling back to lexical search: {}", e.getMessage());
            List<Candidate> rows = jdbc.sql(LEXICAL_SQL)
                .param("venue", scope.venue()).param("org", scope.org()).param("floor", scope.floor()).param("accessible", accessible)
                .param("anonymous", scope.anonymous()).param("sv", scope.scanVersion())
                .param("terms", terms).param("limit", props.candidateLimit())
                .query((rs, i) -> {
                    Candidate c = mapRow(rs);
                    c.similarity = rs.getDouble("similarity");
                    c.relevant = true;
                    return c;
                }).list();
            return new Scored(rows, "lexical_fallback", LEXICAL);
        }
    }

    private Candidate mapScored(ResultSet rs, Interpretation interp) throws SQLException {
        Candidate c = mapRow(rs);
        c.similarity = rs.getObject("similarity", Double.class);
        c.judged = rs.getBoolean("judged");
        c.relevant = rs.getBoolean("relevant");
        c.indexed = rs.getBoolean("indexed");
        c.margin = rs.getObject("margin", Double.class);
        c.rankScore = rs.getObject("rank_score", Double.class);
        c.imageSimilarity = rs.getObject("image_similarity", Double.class);
        if (c.judged && c.relevant) {
            boolean text = rs.getBoolean("relevant_text");
            boolean image = rs.getBoolean("relevant_image");
            c.matchedBy = text && image ? BY_TEXT_AND_IMAGE : text ? BY_TEXT : image ? BY_IMAGE
                : interp.synonymTerms().contains(c.matchedTerm) ? BY_SYNONYM : BY_LEXICAL;
        }
        return c;
    }

    private Candidate mapRow(ResultSet rs) throws SQLException {
        Candidate c = new Candidate();
        c.poiId = rs.getObject("poi_id", UUID.class);
        c.floorId = rs.getObject("floor_id", UUID.class);
        c.label = rs.getString("label");
        c.category = rs.getString("category");
        c.tags = List.of((String[]) rs.getArray("tags").getArray());
        c.x = rs.getDouble("x");
        c.y = rs.getDouble("y");
        c.z = rs.getDouble("z");
        Object confidence = rs.getObject("detection_confidence");
        c.confidence = confidence == null ? null : ((Number) confidence).doubleValue();
        c.source = rs.getString("source");
        Object bbox = rs.getObject("bounding_box");
        c.bbox = bbox == null ? null : parseJson(bbox.toString());
        Object provenance = rs.getObject("detection_provenance");
        c.provenance = provenance == null ? null : parseJson(provenance.toString());
        c.spatialStatus = rs.getString("spatial_status");
        c.localizationStatus = rs.getString("localization_status");
        c.localizationUncertainty = rs.getObject("localization_uncertainty_m", Double.class);
        c.lexicalSimilarity = rs.getObject("lexical_similarity", Double.class);
        c.scanVersionId = rs.getObject("scan_version_id", UUID.class);
        c.pipelineRunId = rs.getObject("pipeline_run_id", UUID.class);
        c.artifactId = rs.getObject("detection_artifact_id", UUID.class);
        c.exact = rs.getBoolean("exact_match");
        c.matchedTerm = rs.getString("matched_term");
        c.normLabel = rs.getString("norm_label");
        c.accessible = rs.getObject("accessible_attr", Boolean.class);
        c.indexed = true;
        return c;
    }

    private SearchResult toResult(Candidate c) {
        Object sourceFrame = c.bbox == null ? null : c.bbox.get("sourceFrame");
        boolean detected = "AUTO_DETECTED".equals(c.source);
        Boolean low = detected ? (c.confidence == null || c.confidence < props.lowConfidenceBelow()) : null;
        return new SearchResult(c.poiId, c.floorId, c.label, c.category, c.tags, c.x, c.y, c.z,
            c.similarity == null ? 0.0 : c.similarity, c.confidence, c.source, c.bbox, c.margin, c.imageSimilarity, c.matchedBy,
            sourceFrame instanceof String s ? s : null, c.spatialStatus, c.localizationStatus, c.localizationUncertainty,
            c.lexicalSimilarity, c.rankScore, c.scanVersionId, c.pipelineRunId, c.artifactId, c.provenance, c.exact, c.matchedTerm,
            c.distance, c.distanceKind, c.routeStatus, low, List.copyOf(c.duplicates), c.accessible,
            c.indexed ? "INDEXED" : "PENDING_EMBEDDING");
    }

    /**
     * NAMED_PLACE: exactly one relevant staff POI is named like the subject (or a synonym of it). CATEGORY: the subject is a
     * synonym-table group, or equals the category, a tag or a detector label of relevant POIs. DESCRIPTION: anything else.
     */
    private static String targetKind(Interpretation interp, List<Candidate> relevant) {
        Set<String> terms = Set.copyOf(interp.lexicalTerms());
        long named = relevant.stream().filter(c -> "MANUAL".equals(c.source) && terms.contains(c.normLabel)).count();
        if (named == 1) {
            return "NAMED_PLACE";
        }
        if (!interp.synonymGroups().isEmpty() || relevant.stream().anyMatch(c -> c.exact)) {
            return "CATEGORY";
        }
        return "DESCRIPTION";
    }

    /**
     * Folds detected objects that are one physical object seen under two detector labels of one synonym group (or under
     * the same label twice) into the better-ranked one: both VALID, on one floor, within duplicateDistanceM. Manual POIs are
     * never folded: staff placed each of them on purpose.
     */
    private void foldDuplicates(List<Candidate> ranked) {
        double max = props.duplicateDistanceM();
        List<Candidate> kept = new ArrayList<>();
        for (Candidate c : ranked) {
            Candidate twin = null;
            if ("AUTO_DETECTED".equals(c.source) && "VALID".equals(c.spatialStatus)) {
                String group = Objects.requireNonNullElse(QueryInterpreter.groupOf(c.label), c.normLabel);
                for (Candidate k : kept) {
                    if ("AUTO_DETECTED".equals(k.source) && "VALID".equals(k.spatialStatus) && Objects.equals(k.floorId, c.floorId)
                            && group.equals(Objects.requireNonNullElse(QueryInterpreter.groupOf(k.label), k.normLabel))
                            && dist(k.x, k.y, k.z, c.x, c.y, c.z) <= max) {
                        twin = k;
                        break;
                    }
                }
            }
            if (twin != null) {
                twin.duplicates.add(c.poiId);
            } else {
                kept.add(c);
            }
        }
        ranked.clear();
        ranked.addAll(kept);
    }

    /**
     * Reorders the VALID tier of `ranked` (relevant candidates) by distance from `position` (class comment, "Nearest").
     * Same-floor candidates are routed in increasing straight-line distance -- a lower bound of any walking route in the
     * same frame -- until k routed ones are no farther than the next lower bound, or the evaluation cap is reached; other
     * floors' candidates are routed after them in relevance order while the cap allows. Order within the tier: routed
     * distances ascending, then straight-line estimates ascending (floors with nothing to route on), then candidates not
     * routed (same floor: by straight-line lower bound), then those no route reaches; ties keep their relevance order.
     */
    private void orderByDistance(Actor actor, UUID venueId, UUID scanVersionId, SearchPosition position, boolean stepFree,
                                 List<Candidate> ranked, int k) {
        List<Candidate> valid = ranked.stream().filter(c -> "VALID".equals(c.spatialStatus) && c.floorId != null).toList();
        List<Candidate> sameFloor = new ArrayList<>();
        List<Candidate> otherFloors = new ArrayList<>();
        for (Candidate c : valid) {
            if (c.floorId.equals(position.floorId())) {
                c.lowerBound = dist(position.x(), position.y(), position.z(), c.x, c.y, c.z);
                sameFloor.add(c);
            } else {
                otherFloors.add(c);
            }
        }
        sameFloor.sort(Comparator.comparingDouble((Candidate c) -> c.lowerBound));

        int evaluations = 0;
        boolean floorRoutable = true;
        List<Double> routed = new ArrayList<>();
        for (Candidate c : sameFloor) {
            double bound = c.lowerBound;
            boolean decided = routed.stream().filter(d -> d <= bound).count() >= k;
            if (!floorRoutable) {
                c.distance = c.lowerBound;
                c.distanceKind = STRAIGHT_LINE;
                continue;
            }
            if (decided || evaluations >= props.maxRouteEvaluations()) {
                c.routeStatus = NOT_EVALUATED;
                continue;
            }
            evaluations++;
            String failure = route(actor, venueId, scanVersionId, position, stepFree, c);
            if (failure == null) {
                routed.add(c.distance);
            } else if (ROUTING_UNAVAILABLE.contains(failure)) {
                // Nothing on this floor can be routed: fall back to straight-line distances, labelled as such.
                floorRoutable = false;
                for (Candidate s : sameFloor) {
                    if (s.distanceKind == null) {
                        s.distance = s.lowerBound;
                        s.distanceKind = STRAIGHT_LINE;
                        s.routeStatus = failure;
                    }
                }
            }
        }
        for (Candidate c : otherFloors) {
            if (evaluations >= props.maxRouteEvaluations()) {
                c.routeStatus = NOT_EVALUATED;
                continue;
            }
            evaluations++;
            route(actor, venueId, scanVersionId, position, stepFree, c);
        }

        // routed, then straight-line estimates, then not routed (by lower bound when known), then unreachable
        Comparator<Candidate> byDistance = Comparator
            .comparingInt((Candidate c) -> ROUTE.equals(c.distanceKind) ? 0 : STRAIGHT_LINE.equals(c.distanceKind) ? 1
                : c.routeStatus == null || NOT_EVALUATED.equals(c.routeStatus) ? 2 : 3)
            .thenComparingDouble(c -> c.distance != null ? c.distance : c.lowerBound != null ? c.lowerBound : Double.MAX_VALUE);
        List<Candidate> tier = new ArrayList<>(valid);
        tier.sort(byDistance); // stable: ties keep the relevance order
        List<Candidate> reordered = new ArrayList<>(tier);
        for (Candidate c : ranked) {
            if (!tier.contains(c)) {
                reordered.add(c);
            }
        }
        ranked.clear();
        ranked.addAll(reordered);
    }

    /** Plans one route; sets distance or routeStatus and returns the failure code (null on success). */
    private String route(Actor actor, UUID venueId, UUID scanVersionId, SearchPosition position, boolean stepFree, Candidate c) {
        try {
            RouteResponse r = routes.route(actor, new RouteRequest(venueId, position.floorId(),
                List.of(position.x(), position.y(), position.z()), c.poiId, stepFree ? "STEP_FREE" : "STANDARD", List.of(),
                scanVersionId));
            c.distance = r.distanceMeters();
            c.distanceKind = ROUTE;
            return null;
        } catch (ApiException e) {
            c.routeStatus = e.code();
        } catch (RuntimeException e) {
            log.warn("search: route to POI {} failed: {}", c.poiId, e.getMessage());
            c.routeStatus = "ROUTE_FAILED";
        }
        return c.routeStatus;
    }

    private SearchPosition validPosition(UUID venueId, SearchPosition position) {
        if (position == null) {
            return null;
        }
        if (position.floorId() == null || !Double.isFinite(position.x()) || !Double.isFinite(position.y())
                || !Double.isFinite(position.z())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_POSITION",
                "a position needs positionFloorId and three finite canonical coordinates");
        }
        boolean onVenue = jdbc.sql("SELECT EXISTS (SELECT 1 FROM floor WHERE id = :f AND venue_id = :v)")
            .param("f", position.floorId()).param("v", venueId).query(Boolean.class).single();
        if (!onVenue) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_POSITION", "positionFloorId is not a floor of this venue");
        }
        return position;
    }

    private static double dist(double ax, double ay, double az, double bx, double by, double bz) {
        double dx = ax - bx;
        double dy = ay - by;
        double dz = az - bz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
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
