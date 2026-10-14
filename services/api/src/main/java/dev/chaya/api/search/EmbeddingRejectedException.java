package dev.chaya.api.search;

/** The vision service is up but refused to embed this one text (an HTTP 4xx other than 408/429). Unlike an outage it says
 * nothing about other texts, so it does not open HttpTextEmbeddingClient's circuit breaker: the embedding backfill records
 * it against the one POI and moves on (PoiEmbeddingService), and a search for that text falls back to lexical matching
 * like any unavailable embedding. */
public class EmbeddingRejectedException extends EmbeddingUnavailableException {
    public EmbeddingRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
