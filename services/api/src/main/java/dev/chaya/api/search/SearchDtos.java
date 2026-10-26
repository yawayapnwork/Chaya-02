package dev.chaya.api.search;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class SearchDtos {

    private SearchDtos() {}

    /** matchType: "embedding" (real CLIP cosine similarity) or "lexical_fallback" (trigram text
     * similarity, used only when the embedding model was unavailable -- see EmbeddingUnavailableException).
     * similarity is always in [0, 1] regardless of which one produced it, but the two are not comparable
     * to each other, which is exactly why the field is reported.
     *
     * @param similarity      for an embedding match: the query's CLIP text-to-text cosine with the POI's own text (a
     *                        detected object's text is its detector label). The same space for every result, so results
     *                        are comparable and ordered by it. 0 for a POI found only by name while its embedding is
     *                        still pending (indexStatus PENDING_EMBEDDING)
     * @param relevanceMargin for an embedding match: similarity minus the query's mean similarity to the distinct POI
     *                        texts in scope (see SemanticSearchService); null for the lexical fallback and pending POIs
     * @param imageSimilarity for a detected object: the query's CLIP text-to-image cosine with its crop. On a different
     *                        scale from similarity (~0.2-0.35), and only comparable with other imageSimilarity values;
     *                        null for manual POIs and the lexical fallback
     * @param matchedBy       for a result of a FILTERED search: which evidence made it relevant, TEXT (the text margin),
     *                        IMAGE (CLIP zero-shot: the crop is closer to the query than to every detector label in scope),
     *                        TEXT_AND_IMAGE, LEXICAL (only a whole-word fuzzy match of the name) or SYNONYM (only a
     *                        name match through the synonym table, QueryInterpreter); null otherwise
     * @param sourceFrame     for a detected object: the capture frame its bounding box was measured in (also in
     *                        boundingBox.sourceFrame); null otherwise
     * @param spatialStatus   whether the position can be shown where it is: VALID (in the frame being viewed; a detected
     *                        object also placed with depth evidence), UNVERIFIED (a detected object placed before
     *                        depth-tested localization), STALE_FRAME (an older coordinate frame) or UNBOUND (no frame).
     *                        Every VALID result ranks before every other relevant one
     * @param localizationStatus       detected objects placed with depth evidence: MULTI_VIEW or SINGLE_VIEW; else null
     * @param localizationUncertaintyM their measured placement spread in metres (views' disagreement or the thickness of
     *                                 the surface placed on); a spread, not an accuracy. Null otherwise
     * @param lexicalSimilarity        whole-word trigram similarity of the best of the query's phrasings (see
     *                                 SearchInterpretation.lexicalTerms) with the POI's text, in [0, 1]
     * @param rankScore                for an embedding match: similarity + lexicalWeight x lexicalSimilarity
     *                                 + evidenceWeight x (evidence - 1), the order within a spatial tier; null for the
     *                                 lexical fallback and pending POIs
     * @param scanVersionId            the scan version the POI's position belongs to (null: a staff POI placed before any)
     * @param pipelineRunId            detected objects: the pipeline run that detected it
     * @param detectionArtifactId      detected objects: the DETECTED_OBJECTS artifact it was ingested from (null for
     *                                 detections ingested before V36)
     * @param provenance               detected objects: what produced it, as ingested (detector and embedding model, the
     *                                 artifact's SHA-256, the frames its detections came from); null otherwise
     * @param exactMatch               the POI's label, category or one of its tags equals the query's subject or one of its
     *                                 synonyms (after normalisation); such results rank first within their spatial tier
     * @param matchedTerm              the phrasing of the query that matched the POI's text best (lexically)
     * @param distanceMeters           only when the query asked for the nearest and a position was given: the walking
     *                                 route length (distanceKind ROUTE) or, when the floor has no routable navigation
     *                                 graph, the straight-line distance in the floor's frame (STRAIGHT_LINE)
     * @param distanceKind             ROUTE or STRAIGHT_LINE; null when there is no distance
     * @param routeStatus              when a route was asked for and none was found: the routing error code (NO_ROUTE,
     *                                 NO_ACCESSIBLE_ROUTE, NAVMESH_NOT_READY, ...), or NOT_EVALUATED when the result was
     *                                 not routed (see docs/search.md, "Nearest")
     * @param lowConfidence            detected objects whose detector confidence is unknown or below
     *                                 chaya.search.low-confidence-below; null for manual POIs
     * @param duplicateIds             detected objects of the same synonym group (e.g. a "sofa" and a "couch") within
     *                                 chaya.search.duplicate-distance-m of this one, folded into it; usually empty
     * @param accessible               what staff recorded in the POI's attributes.accessible; null when nothing was
     *                                 recorded. Never inferred from a label or an image
     * @param indexStatus              INDEXED (has a text embedding of the query's model) or PENDING_EMBEDDING (found by
     *                                 name only, until the embedding backfill reaches it) */
    public record ResultDiagnostics(String origin, String retrievalMode, String matchChannel, String matchedTerm,
                                    Double semanticSimilarity, Double imageSimilarity, Double lexicalSimilarity,
                                    Double detectionConfidence, Double evidenceScore, Double rankScore,
                                    String spatialStatus, String localizationStatus, boolean embeddingFailed,
                                    String explanation) {}

    public record IndexDiagnostics(String embeddingModel, long totalPois, long indexedPois, long pendingEmbeddingPois,
                                   long failedEmbeddingPois, boolean indexComplete) {}

    public record SearchResult(UUID poiId, UUID floorId, String label, String category, List<String> tags,
                               double x, double y, double z, double similarity, Double detectionConfidence,
                               String source, Map<String, Object> boundingBox, Double relevanceMargin,
                               Double imageSimilarity, String matchedBy, String sourceFrame, String spatialStatus,
                               String localizationStatus, Double localizationUncertaintyM, Double lexicalSimilarity,
                               Double rankScore, UUID scanVersionId, UUID pipelineRunId, UUID detectionArtifactId,
                               Map<String, Object> provenance, boolean exactMatch, String matchedTerm, Double distanceMeters,
                               String distanceKind, String routeStatus, Boolean lowConfidence, List<UUID> duplicateIds,
                               Boolean accessible, String indexStatus, ResultDiagnostics diagnostics) {
        public SearchResult(UUID poiId, UUID floorId, String label, String category, List<String> tags,
                            double x, double y, double z, double similarity, Double detectionConfidence,
                            String source, Map<String, Object> boundingBox, Double relevanceMargin,
                            Double imageSimilarity, String matchedBy, String sourceFrame, String spatialStatus,
                            String localizationStatus, Double localizationUncertaintyM, Double lexicalSimilarity,
                            Double rankScore, UUID scanVersionId, UUID pipelineRunId, UUID detectionArtifactId,
                            Map<String, Object> provenance, boolean exactMatch, String matchedTerm, Double distanceMeters,
                            String distanceKind, String routeStatus, Boolean lowConfidence, List<UUID> duplicateIds,
                            Boolean accessible, String indexStatus) {
            this(poiId, floorId, label, category, tags, x, y, z, similarity, detectionConfidence, source, boundingBox,
                relevanceMargin, imageSimilarity, matchedBy, sourceFrame, spatialStatus, localizationStatus,
                localizationUncertaintyM, lexicalSimilarity, rankScore, scanVersionId, pipelineRunId, detectionArtifactId,
                provenance, exactMatch, matchedTerm, distanceMeters, distanceKind, routeStatus, lowConfidence, duplicateIds,
                accessible, indexStatus, null);
        }
    }

    /** A spelling correction QueryInterpreter applied: the word as typed and the venue or synonym-table word used. */
    public record Correction(String from, String to) {}

    /**
     * How the query was read (QueryInterpreter; docs/search.md, "Query understanding").
     *
     * @param normalized              the query normalised (lower case, punctuation removed)
     * @param subject                 what was searched for: the query without request phrasing and modifiers, spelling
     *                                corrected. This text was embedded
     * @param lexicalTerms            every phrasing matched by name: the subject, the subject as typed, and its synonyms
     * @param synonymGroups           synonym-table groups the subject belongs to
     * @param corrections             spelling corrections
     * @param targetKind              NAMED_PLACE (exactly one POI is named like this), CATEGORY (a kind of place or object:
     *                                a synonym-table group, or the category, tag or detector label of POIs in scope) or
     *                                DESCRIPTION (anything else: matched by meaning only)
     * @param spatialModifier         NEAREST when the query asked for the nearest/closest one, else NONE
     * @param spatialModifierApplied  whether results are ordered by distance from the given position
     * @param spatialNote             why not, when they are not: NO_POSITION (none was given), POSITION_NOT_ON_VERSION_FLOOR
     * @param accessibilityRequired   the query or the accessible parameter asked for an accessible place: only POIs whose
     *                                attributes.accessible is true are results, and routes use the STEP_FREE profile
     */
    public record SearchInterpretation(String normalized, String subject, List<String> lexicalTerms, List<String> synonymGroups,
                                       List<Correction> corrections, String targetKind, String spatialModifier,
                                       boolean spatialModifierApplied, String spatialNote, boolean accessibilityRequired) {}

    /**
     * @param results                  what the venue has for the query. For an embedding search, only matches that clear the
     *                                 relevance threshold (see relevance); empty means "nothing like this here".
     * @param closestMatches           only when results is empty: the nearest candidates that did not clear the threshold,
     *                                 for a "nothing matching -- closest:" display. Never presented as matches.
     * @param relevance                FILTERED (results were thresholded), UNFILTERED (fewer distinct POI texts in scope
     *                                 than chaya.search.relevance-min-pois: results are the plain top-k) or
     *                                 LEXICAL (the fallback, which has its own similarity cut-off)
     * @param interpretation           how the query was read
     * @param lowConfidence            true when every result is weak evidence: a low-confidence detection, or admitted only
     *                                 by the uncalibrated image test (matchedBy IMAGE)
     * @param accessibilityUnconfirmed only when accessibility was required and nothing recorded as accessible matched: the
     *                                 matches whose accessibility nobody recorded (never ones recorded as not accessible).
     *                                 Not results: whether they are accessible is unknown
     * @param suggestions              only when results is empty: other searches that do return something here (the labels
     *                                 of closestMatches). Never a match by itself
     */
    public record SearchResponse(String query, String matchType, List<SearchResult> results, List<SearchResult> closestMatches,
                                 String relevance, SearchInterpretation interpretation, boolean lowConfidence,
                                 List<SearchResult> accessibilityUnconfirmed, List<String> suggestions,
                                 IndexDiagnostics indexDiagnostics) {
        public SearchResponse(String query, String matchType, List<SearchResult> results, List<SearchResult> closestMatches,
                              String relevance, SearchInterpretation interpretation, boolean lowConfidence,
                              List<SearchResult> accessibilityUnconfirmed, List<String> suggestions) {
            this(query, matchType, results, closestMatches, relevance, interpretation, lowConfidence,
                accessibilityUnconfirmed, suggestions, null);
        }
    }

    /** Optional position of the person searching, for "nearest": canonical metres on positionFloorId, in that floor's
     * current coordinate frame (or the scan version's, when one is searched), exactly as a route's start. */
    public record SearchPosition(UUID floorId, double x, double y, double z) {}
}
