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
     *                        are comparable and ordered by it
     * @param relevanceMargin for an embedding match: similarity minus the query's mean similarity to the distinct POI
     *                        texts in scope (see SemanticSearchService); null for the lexical fallback
     * @param imageSimilarity for a detected object: the query's CLIP text-to-image cosine with its crop. On a different
     *                        scale from similarity (~0.2-0.35), and only comparable with other imageSimilarity values;
     *                        null for manual POIs and the lexical fallback
     * @param matchedBy       for a result of a FILTERED search: which evidence made it relevant, TEXT (the text margin),
     *                        IMAGE (CLIP zero-shot: the crop is closer to the query than to every detector label in scope),
     *                        TEXT_AND_IMAGE, or LEXICAL (only the whole-word trigram match: a misspelt name); null otherwise
     * @param sourceFrame     for a detected object: the capture frame its bounding box was measured in (also in
     *                        boundingBox.sourceFrame); null otherwise
     * @param spatialStatus   whether the position can be shown where it is: VALID (in the frame being viewed; a detected
     *                        object also placed with depth evidence), UNVERIFIED (a detected object placed before
     *                        depth-tested localization), STALE_FRAME (an older coordinate frame) or UNBOUND (no frame).
     *                        Every VALID result ranks before every other relevant one
     * @param localizationStatus       detected objects placed with depth evidence: MULTI_VIEW or SINGLE_VIEW; else null
     * @param localizationUncertaintyM their measured placement spread in metres (views' disagreement or the thickness of
     *                                 the surface placed on); a spread, not an accuracy. Null otherwise
     * @param lexicalSimilarity        whole-word trigram similarity of the query with the POI's text, in [0, 1]
     * @param rankScore                for an embedding match: similarity + lexicalWeight x lexicalSimilarity
     *                                 + evidenceWeight x (evidence - 1), the order within a spatial tier; null for the
     *                                 lexical fallback */
    public record SearchResult(UUID poiId, UUID floorId, String label, String category, List<String> tags,
                               double x, double y, double z, double similarity, Double detectionConfidence,
                               String source, Map<String, Object> boundingBox, Double relevanceMargin,
                               Double imageSimilarity, String matchedBy, String sourceFrame, String spatialStatus,
                               String localizationStatus, Double localizationUncertaintyM, Double lexicalSimilarity,
                               Double rankScore) {}

    /**
     * @param results        what the venue has for the query. For an embedding search, only matches that clear the
     *                       relevance threshold (see relevance); empty means "nothing like this here".
     * @param closestMatches only when results is empty: the nearest candidates that did not clear the threshold, for
     *                       a "nothing matching -- closest:" display. Never presented as matches.
     * @param relevance      FILTERED (results were thresholded), UNFILTERED (fewer distinct POI texts in scope than
     *                       chaya.search.relevance-min-pois: results are the plain top-k) or
     *                       LEXICAL (the fallback, which has its own similarity cut-off)
     */
    public record SearchResponse(String query, String matchType, List<SearchResult> results, List<SearchResult> closestMatches,
                                 String relevance) {}
}
