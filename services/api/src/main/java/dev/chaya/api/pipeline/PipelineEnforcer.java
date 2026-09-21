package dev.chaya.api.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically fails abandoned jobs and enforces run time budgets. The rules live in PipelineService. */
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
}
