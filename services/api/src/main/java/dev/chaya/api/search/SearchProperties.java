package dev.chaya.api.search;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** `visionServiceUrl` points at services/vision, the small FastAPI service that embeds search query text
 * with the same CLIP model chaya_worker used to embed detected objects (see HttpTextEmbeddingClient).
 *
 * @param visionProbeInterval      while the vision service is unavailable, how often a background probe checks
 *                                 whether it is back (HttpTextEmbeddingClient's circuit breaker)
 * @param relevanceMinMargin       an embedding match (manual or detected POI) counts as a result if its text similarity
 *                                 exceeds the query's mean similarity to the distinct POI texts in scope by at least this.
 *                                 0.078 was chosen on a calibration venue (benchmarks/b3_semantic_search/calibrate.py,
 *                                 docs/BENCHMARKS.md) and never tuned on the test set
 * @param relevanceMinPois         fewer distinct POI texts than this and the mean is not meaningful: results are unfiltered
 * @param closestMatches           how many below-threshold candidates to return when nothing clears it
 * @param lexicalMinSimilarity     a POI whose text (label, category, tags) has pg_trgm strict_word_similarity with the query of
 *                                 at least this is also relevant (a misspelt name: "fire extingusher" 0.75, "elevater" 0.5;
 *                                 a word inside another, "sign" in "design", 0.33). Chosen on a handful of such pairs, not
 *                                 calibrated on a venue
 * @param lexicalWeight            weight of that lexical similarity in the rank score (docs/search.md, "Ranking")
 * @param evidenceWeight           weight of a detected object's evidence shortfall (1 - confidence x view support) in the
 *                                 rank score. Both weights are small on purpose: they reorder near-ties in CLIP similarity
 *                                 and never replace it. Not calibrated: B3 has not been re-run with them */
@ConfigurationProperties("chaya.search")
public record SearchProperties(String visionServiceUrl, Duration visionTimeout, int defaultTopK, int maxTopK,
                               Duration visionProbeInterval, Double relevanceMinMargin, int relevanceMinPois, int closestMatches,
                               Double lexicalMinSimilarity, Double lexicalWeight, Double evidenceWeight) {

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
        if (lexicalMinSimilarity == null) {
            lexicalMinSimilarity = 0.5;
        }
        if (lexicalWeight == null) {
            lexicalWeight = 0.05;
        }
        if (evidenceWeight == null) {
            evidenceWeight = 0.05;
        }
    }
}
