package dev.chaya.api.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically embeds POI versions that have no embedding yet (PoiEmbeddingService). A new or edited POI therefore
 * becomes findable by semantic search within one interval (chaya.search.embedding-backfill-interval, default 5 s) of
 * being saved, while the vision service is up. While it is down, search falls back to lexical matching as before and
 * the backlog is worked off once it returns.
 */
@Component
public class PoiEmbeddingBackfill {

    private static final Logger log = LoggerFactory.getLogger(PoiEmbeddingBackfill.class);

    private final PoiEmbeddingService service;
    private final int batchSize;
    private String lastStopReason;

    public PoiEmbeddingBackfill(PoiEmbeddingService service,
                                @Value("${chaya.search.embedding-backfill-batch:50}") int batchSize) {
        this.service = service;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${chaya.search.embedding-backfill-interval:PT5S}",
               initialDelayString = "${chaya.search.embedding-backfill-initial-delay:PT10S}")
    void tick() {
        try {
            PoiEmbeddingService.Result r = service.embedPending(batchSize);
            if (r.embedded() > 0) {
                log.info("embedded {} POI version(s) for semantic search", r.embedded());
            }
            // Log a stall once, not every few seconds for as long as the vision service is down.
            if (r.stoppedBecause() != null && !r.stoppedBecause().equals(lastStopReason)) {
                log.warn("POI embedding paused, {} POI(s) not yet searchable by meaning: {}", service.pendingCount(), r.stoppedBecause());
            } else if (r.stoppedBecause() == null && lastStopReason != null) {
                log.info("POI embedding resumed");
            }
            lastStopReason = r.stoppedBecause();
        } catch (RuntimeException e) {
            log.error("POI embedding backfill failed", e);
        }
    }
}
