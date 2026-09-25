package dev.chaya.api.search;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** `visionServiceUrl` points at services/vision, the small FastAPI service that embeds search query text
 * with the same CLIP model chaya_worker used to embed detected objects (see HttpTextEmbeddingClient).
 *
 * @param visionProbeInterval      while the vision service is unavailable, how often a background probe checks
 *                                 whether it is back (HttpTextEmbeddingClient's circuit breaker)
 * @param relevanceMinMargin       an embedding match of a manually placed POI counts as a result only if its similarity
 *                                 exceeds the query's mean similarity to the venue's searched POIs by at least this.
 *                                 0.078 was chosen on a calibration venue (benchmarks/b3_semantic_search/calibrate.py,
 *                                 docs/BENCHMARKS.md) and never tuned on the test set
 * @param relevanceMinPois         fewer comparable POIs than this and the mean is not meaningful: results are unfiltered
 * @param closestMatches           how many below-threshold candidates to return when nothing clears it */
@ConfigurationProperties("chaya.search")
public record SearchProperties(String visionServiceUrl, Duration visionTimeout, int defaultTopK, int maxTopK,
                               Duration visionProbeInterval, Double relevanceMinMargin, int relevanceMinPois, int closestMatches) {

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
        if (visionProbeInterval == null) {
            visionProbeInterval = Duration.ofSeconds(5);
        }
        if (relevanceMinMargin == null) {
            relevanceMinMargin = 0.078;
        }
        if (relevanceMinPois <= 0) {
            relevanceMinPois = 8;
        }
        if (closestMatches <= 0) {
            closestMatches = 3;
        }
    }
}
