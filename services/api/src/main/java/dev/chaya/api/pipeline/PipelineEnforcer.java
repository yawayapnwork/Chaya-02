package dev.chaya.api.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically fails abandoned jobs, enforces run time budgets and purges due PII staging. The rules live in PipelineService. */
@Component
@EnableScheduling
public class PipelineEnforcer {

    private static final Logger log = LoggerFactory.getLogger(PipelineEnforcer.class);

    private final PipelineService pipeline;

    public PipelineEnforcer(PipelineService pipeline) {
        this.pipeline = pipeline;
    }

    @Scheduled(fixedDelayString = "${chaya.pipeline.enforcer-interval:PT30S}", initialDelayString = "PT30S")
    void tick() {
        try {
            int handled = pipeline.enforceLeasesAndDeadlines();
            if (handled > 0) {
                log.info("pipeline enforcer handled {} job(s)", handled);
            }
        } catch (RuntimeException e) {
            log.error("pipeline enforcer failed", e);
        }
    }

    /** Deletes PII staging that is due under the retention policy and retries failed deletions (review S-7). */
    @Scheduled(fixedDelayString = "${chaya.pipeline.pii-sweep-interval:PT1M}", initialDelayString = "PT45S")
    void sweepPii() {
        try {
            int runs = pipeline.sweepPiiStaging();
            if (runs > 0) {
                log.info("PII staging sweep purged {} run(s)", runs);
            }
        } catch (RuntimeException e) {
            log.error("PII staging sweep failed", e);
        }
    }
}
