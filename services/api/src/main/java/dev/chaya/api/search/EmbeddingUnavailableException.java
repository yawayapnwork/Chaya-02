package dev.chaya.api.search;

/** The CLIP text-embedding model (services/vision) could not be reached or produced no embedding. A
 * search request never fabricates a zero/random vector when this happens; SemanticSearchService instead
 * degrades to the lexical trigram fallback and reports that it did so. */
public class EmbeddingUnavailableException extends RuntimeException {
    public EmbeddingUnavailableException(String message) {
        super(message);
    }

    public EmbeddingUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
