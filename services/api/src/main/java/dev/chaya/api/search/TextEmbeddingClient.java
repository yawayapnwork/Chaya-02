package dev.chaya.api.search;

/** Embeds free-text into the same vector space POI embeddings live in (CLIP's joint image/text space --
 * see chaya_worker.clip_embeddings and services/vision). This is the actual mechanism behind matching a
 * query like "couch" against a POI labelled "sofa": CLIP's text and image towers were trained so that
 * semantically related text and images land near each other, not a hardcoded synonym table. */
public interface TextEmbeddingClient {

    /** @throws EmbeddingUnavailableException if no real embedding could be produced. */
    float[] embed(String text);
}
