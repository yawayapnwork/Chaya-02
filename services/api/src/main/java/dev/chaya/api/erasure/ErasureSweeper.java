package dev.chaya.api.erasure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Finishes erasures whose objects are not all deleted yet (storage was unavailable, or a prefix had to settle). */
@Component
public class ErasureSweeper {

    private static final Logger log = LoggerFactory.getLogger(ErasureSweeper.class);

    private final ErasureObjectPurger purger;

    public ErasureSweeper(ErasureObjectPurger purger) {
        this.purger = purger;
    }

    @Scheduled(fixedDelayString = "${chaya.erasure.sweep-interval:PT1M}", initialDelayString = "${chaya.erasure.sweep-initial-delay:PT50S}")
    void sweep() {
        try {
            int completed = purger.sweep();
            if (completed > 0) {
                log.info("erasure sweep completed {} erasure(s)", completed);
            }
        } catch (RuntimeException e) {
            log.error("erasure sweep failed", e);
        }
    }
}
