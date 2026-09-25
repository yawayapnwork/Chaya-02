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
     * @param relevanceMargin for an embedding match of a MANUAL POI: its similarity minus the mean similarity of the
     *                        query to every searched POI of the same source (see SemanticSearchService); null otherwise */
    public record SearchResult(UUID poiId, UUID floorId, String label, String category, List<String> tags,
                               double x, double y, double z, double similarity, Double detectionConfidence,
                               String source, Map<String, Object> boundingBox, Double relevanceMargin) {}

    /**
     * @param results        what the venue has for the query. For an embedding search, only matches that clear the
     *                       relevance threshold (see relevance); empty means "nothing like this here".
     * @param closestMatches only when results is empty: the nearest candidates that did not clear the threshold, for
     *                       a "nothing matching -- closest:" display. Never presented as matches.
     * @param relevance      FILTERED (results were thresholded), UNFILTERED (too few comparable POIs to judge, or
     *                       detected objects, which have no calibrated threshold: results are the plain top-k) or
     *                       LEXICAL (the fallback, which has its own similarity cut-off)
     */
    public record SearchResponse(String query, String matchType, List<SearchResult> results, List<SearchResult> closestMatches,
                                 String relevance) {}
}
