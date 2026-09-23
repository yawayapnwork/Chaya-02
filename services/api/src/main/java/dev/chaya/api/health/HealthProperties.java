package dev.chaya.api.health;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param cacheTtl         how long one health result is reused. /health is public, so without this every anonymous
 *                         request would fan out to the database, object storage, Keycloak and clamd.
 * @param queueStallAfter  a claimable job queued longer than this, with no job running, means no worker is taking work
 */
@ConfigurationProperties("chaya.health")
public record HealthProperties(Duration cacheTtl, Duration queueStallAfter) {

    public HealthProperties {
        cacheTtl = cacheTtl == null ? Duration.ofSeconds(5) : cacheTtl;
        queueStallAfter = queueStallAfter == null ? Duration.ofMinutes(15) : queueStallAfter;
    }
}
