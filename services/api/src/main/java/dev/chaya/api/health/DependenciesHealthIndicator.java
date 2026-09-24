package dev.chaya.api.health;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Feeds the real dependency checks (HealthService: database, object storage, identity provider, scanner, queue) into
 * Spring's readiness probe, /actuator/health/readiness (management.endpoint.health.group.readiness). Container
 * orchestration routes traffic on that probe, so the API is "ready" only when it can actually serve authenticated
 * requests -- not merely because the JVM started. DEGRADED (scanner down, queue stalled) stays ready: the API works,
 * and /api/v1/health names the problem. Liveness (/actuator/health/liveness) deliberately does NOT include this, so a
 * database outage makes the API unready, not restarted in a loop.
 */
@Component("dependencies")
public class DependenciesHealthIndicator implements HealthIndicator {

    private final HealthService health;

    public DependenciesHealthIndicator(HealthService health) {
        this.health = health;
    }

    @Override
    public Health health() {
        return toHealth(health.check());
    }

    static Health toHealth(HealthService.HealthReport r) {
        Health.Builder b = HealthService.DOWN.equals(r.status()) ? Health.down() : Health.up();
        return b.withDetail("status", r.status()).withDetail("database", r.database()).withDetail("storage", r.storage())
            .withDetail("identityProvider", r.identityProvider()).withDetail("malwareScanner", r.malwareScanner())
            .withDetail("processing", r.processing().status()).build();
    }
}
