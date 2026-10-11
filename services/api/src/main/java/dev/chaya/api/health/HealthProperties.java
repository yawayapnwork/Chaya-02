package dev.chaya.api.health;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param cacheTtl         how long one health result is reused. /health is public, so without this every anonymous
 *                         request would fan out to the database, object storage, Keycloak and clamd.
 * @param queueStallAfter  a claimable job queued longer than this, with no job running, means no worker is taking work
 * @param workerLiveAfter  a worker that has neither polled for work nor heartbeated within this long is not live. Must
 *                         exceed the worker's heartbeat interval (WORKER_HEARTBEAT_INTERVAL, 60 s by default), since a
 *                         worker busy with one long stage only heartbeats.
 */
@ConfigurationProperties("chaya.health")
public record HealthProperties(Duration cacheTtl, Duration queueStallAfter, Duration workerLiveAfter) {

    public HealthProperties {
        cacheTtl = cacheTtl == null ? Duration.ofSeconds(5) : cacheTtl;
        queueStallAfter = queueStallAfter == null ? Duration.ofMinutes(15) : queueStallAfter;
        workerLiveAfter = workerLiveAfter == null ? Duration.ofMinutes(3) : workerLiveAfter;
    }
}
