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
     *                        IMAGE (CLIP zero-shot: the crop is closer to the query than to every detector label in scope)
     *                        or TEXT_AND_IMAGE; null otherwise
     * @param sourceFrame     for a detected object: the capture frame its bounding box was measured in (also in
     *                        boundingBox.sourceFrame); null otherwise */
    public record SearchResult(UUID poiId, UUID floorId, String label, String category, List<String> tags,
                               double x, double y, double z, double similarity, Double detectionConfidence,
                               String source, Map<String, Object> boundingBox, Double relevanceMargin,
                               Double imageSimilarity, String matchedBy, String sourceFrame) {}

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
