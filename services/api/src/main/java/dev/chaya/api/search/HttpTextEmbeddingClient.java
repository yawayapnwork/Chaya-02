package dev.chaya.api.search;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Calls services/vision's POST /v1/embed-text, behind a circuit breaker.
 *
 * <p>Why the breaker: when the vision service is down, a call does not always fail fast. Its container name stops
 * resolving, the JVM caches that failed lookup for only 10 s, and each fresh lookup waited ~3.7 s in the E2E stack
 * (docs/BENCHMARKS.md B3) -- so about one search in nine stalled for seconds before falling back, and the POI
 * embedding backfill held Spring's shared scheduler thread just as long. With the breaker, the first failure opens
 * it: every call after that fails immediately (callers fall back or retry later) and never touches DNS or the
 * network. A probe on its own daemon thread checks GET /health/ready every chaya.search.vision-probe-interval and
 * closes the breaker once the service reports ready (model loaded), not merely reachable.
 */
@Component
public class HttpTextEmbeddingClient implements TextEmbeddingClient, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(HttpTextEmbeddingClient.class);

    public record EmbedRequest(String text) {}

    public record EmbedResponse(List<Double> embedding, String model) {}

    private final RestClient client;
    private final Duration probeInterval;
    private final ScheduledExecutorService prober = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "vision-probe");
        t.setDaemon(true);
        return t;
    });
    // null = closed (calls go through); otherwise why it opened. Guarded by `this` for transitions.
    private volatile String openReason;
    private ScheduledFuture<?> probe;

    public HttpTextEmbeddingClient(RestClient.Builder builder, SearchProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int timeoutMs = (int) props.visionTimeout().toMillis();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        this.client = builder.baseUrl(props.visionServiceUrl()).requestFactory(factory).build();
        this.probeInterval = props.visionProbeInterval();
    }

    @Override
    public Embedding embedWithModel(String text) {
        String reason = openReason;
        if (reason != null) {
            throw new EmbeddingUnavailableException("the vision service (embedding model) is unavailable: " + reason
                + " (checking again every " + probeInterval.toSeconds() + " s)");
        }
        EmbedResponse response;
        try {
            response = client.post().uri("/v1/embed-text").body(new EmbedRequest(text)).retrieve().body(EmbedResponse.class);
        } catch (RestClientException e) {
            throw fail("the vision service (embedding model) is unavailable: " + e.getMessage(), e);
        }
        if (response == null || response.embedding() == null || response.embedding().isEmpty()) {
            throw fail("the vision service returned no embedding", null);
        }
        if (response.model() == null || response.model().isBlank()) {
            throw fail("the vision service did not say which model produced the embedding", null);
        }
        List<Double> values = response.embedding();
        float[] out = new float[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i).floatValue();
        }
        return new Embedding(out, response.model());
    }

    /** Whether calls currently go through to the vision service. */
    public boolean available() {
        return openReason == null;
    }

    private EmbeddingUnavailableException fail(String reason, Throwable cause) {
        synchronized (this) {
            if (openReason == null) {
                openReason = reason;
                log.warn("vision service unavailable, semantic search falls back until it recovers: {}", reason);
                probe = prober.scheduleWithFixedDelay(this::probe, probeInterval.toMillis(), probeInterval.toMillis(),
                    TimeUnit.MILLISECONDS);
            }
        }
        return new EmbeddingUnavailableException(reason, cause);
    }

    private void probe() {
        try {
            client.get().uri("/health/ready").retrieve().toBodilessEntity(); // 503 while the model loads -> exception
        } catch (RuntimeException e) {
            return; // still unavailable; try again next interval
        }
        synchronized (this) {
            if (openReason != null) {
                openReason = null;
                probe.cancel(false);
                log.info("vision service is ready again; semantic search resumed");
            }
        }
    }

    @Override
    public void destroy() {
        prober.shutdownNow();
    }
}
