package dev.chaya.api.search;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class SearchDtos {

    private SearchDtos() {}

    /** matchType: "embedding" (real CLIP cosine similarity) or "lexical_fallback" (trigram text
     * similarity, used only when the embedding model was unavailable -- see EmbeddingUnavailableException).
     * similarity is always in [0, 1] regardless of which one produced it, but the two are not comparable
     * to each other, which is exactly why the field is reported. */
    public record SearchResult(UUID poiId, UUID floorId, String label, String category, List<String> tags,
                               double x, double y, double z, double similarity, Double detectionConfidence,
                               String source, Map<String, Object> boundingBox) {}

    public record SearchResponse(String query, String matchType, List<SearchResult> results) {}
}
