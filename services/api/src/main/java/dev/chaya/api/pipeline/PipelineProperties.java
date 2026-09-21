package dev.chaya.api.pipeline;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param timeBudgetSeconds     default wall-clock budget of a run
 * @param maxTimeBudgetSeconds  upper bound a caller may request
 * @param leaseSeconds          how long a claimed job stays owned without a heartbeat
 * @param deadlineGraceSeconds  how long a running job may overrun the run deadline before it is forced to fail
 */
@ConfigurationProperties("chaya.pipeline")
public record PipelineProperties(int timeBudgetSeconds, int maxTimeBudgetSeconds, int leaseSeconds, int deadlineGraceSeconds) {

    public PipelineProperties {
        if (timeBudgetSeconds <= 0 || maxTimeBudgetSeconds < timeBudgetSeconds || leaseSeconds <= 0 || deadlineGraceSeconds < 0) {
            throw new IllegalStateException("invalid chaya.pipeline.* configuration");
        }
    }
}
