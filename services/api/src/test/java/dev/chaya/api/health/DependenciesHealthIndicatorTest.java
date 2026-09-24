package dev.chaya.api.health;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

/** Readiness follows the real dependency report: DOWN is not ready; DEGRADED is still ready (the API works). */
class DependenciesHealthIndicatorTest {

    private static HealthService.HealthReport report(String db, String storage, String idp, String scanner, String processing) {
        return HealthService.assemble(db, storage, idp, scanner, new HealthService.Processing(processing, 0L, 0L, null), Instant.now());
    }

    @Test
    void aDownDependencyMakesTheApiUnready() {
        assertThat(DependenciesHealthIndicator.toHealth(report("DOWN", "UP", "UP", "UP", "UNKNOWN")).getStatus()).isEqualTo(Status.DOWN);
        assertThat(DependenciesHealthIndicator.toHealth(report("UP", "DOWN", "UP", "UP", "IDLE")).getStatus()).isEqualTo(Status.DOWN);
        assertThat(DependenciesHealthIndicator.toHealth(report("UP", "UP", "DOWN", "UP", "IDLE")).getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void degradedStaysReadyButSaysWhy() {
        var h = DependenciesHealthIndicator.toHealth(report("UP", "UP", "UP", "DISABLED", "STALLED"));
        assertThat(h.getStatus()).isEqualTo(Status.UP);
        assertThat(h.getDetails()).containsEntry("status", "DEGRADED").containsEntry("processing", "STALLED");
    }
}
