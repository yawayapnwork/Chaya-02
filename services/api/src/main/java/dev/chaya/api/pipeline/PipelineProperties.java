package dev.chaya.api.pipeline;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param timeBudgetSeconds     default wall-clock budget of a run
 * @param maxTimeBudgetSeconds  upper bound a caller may request
 * @param leaseSeconds          how long a claimed job stays owned without a heartbeat
 * @param deadlineGraceSeconds  how long a running job may overrun the run deadline before it is forced to fail
 * @param piiStagingRetention   how long a FAILED run keeps its PII staging (unanonymised frames under pii/) so that it can
 *                              be retried; afterwards the sweep deletes it (review S-7). Default 24 hours. Runs that end
 *                              any other way, and every run once privacy preprocessing succeeded, keep none.
 */
@ConfigurationProperties("chaya.pipeline")
public record PipelineProperties(int timeBudgetSeconds, int maxTimeBudgetSeconds, int leaseSeconds, int deadlineGraceSeconds,
                                 Duration piiStagingRetention) {

    public PipelineProperties {
        if (piiStagingRetention == null) {
            piiStagingRetention = Duration.ofHours(24);
        }
        if (timeBudgetSeconds <= 0 || maxTimeBudgetSeconds < timeBudgetSeconds || leaseSeconds <= 0 || deadlineGraceSeconds < 0
            || piiStagingRetention.isNegative()) {
            throw new IllegalStateException("invalid chaya.pipeline.* configuration");
        }
    }
}
