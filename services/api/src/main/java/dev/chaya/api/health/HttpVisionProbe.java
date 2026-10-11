package dev.chaya.api.health;

import dev.chaya.api.search.SearchProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * GET {vision-service-url}/health/ready with a short timeout: the same readiness the embedding client's circuit breaker
 * probes (model loaded, not merely reachable).
 */
@Component
public class HttpVisionProbe implements VisionProbe {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private final URI ready;
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(TIMEOUT)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    public HttpVisionProbe(SearchProperties props) {
        String base = props.visionServiceUrl();
        this.ready = base == null || base.isBlank() ? null : URI.create(base.replaceAll("/+$", "") + "/health/ready");
    }

    @Override
    public String status() {
        if (ready == null) {
            return NOT_CONFIGURED;
        }
        try {
            HttpResponse<Void> res = http.send(HttpRequest.newBuilder(ready).timeout(TIMEOUT).GET().build(),
                HttpResponse.BodyHandlers.discarding());
            return res.statusCode() == 200 ? HealthService.UP : HealthService.DOWN;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return HealthService.DOWN;
        } catch (Exception e) {
            return HealthService.DOWN;
        }
    }
}
