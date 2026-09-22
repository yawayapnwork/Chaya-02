package dev.chaya.api.search;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** `visionServiceUrl` points at services/vision, the small FastAPI service that embeds search query text
 * with the same CLIP model chaya_worker used to embed detected objects (see HttpTextEmbeddingClient). */
@ConfigurationProperties("chaya.search")
public record SearchProperties(String visionServiceUrl, Duration visionTimeout, int defaultTopK, int maxTopK) {

    public SearchProperties {
        if (visionTimeout == null) {
            visionTimeout = Duration.ofSeconds(5);
        }
        if (defaultTopK <= 0) {
            defaultTopK = 10;
        }
        if (maxTopK <= 0) {
            maxTopK = 50;
        }
    }
}
