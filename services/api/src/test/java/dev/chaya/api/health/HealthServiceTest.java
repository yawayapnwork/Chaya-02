package dev.chaya.api.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.chaya.api.capture.MalwareScanner;
import dev.chaya.api.storage.ObjectStore;
import java.sql.SQLException;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** Failure modes of the health report: each broken dependency is named, and a broken core dependency is DOWN. */
class HealthServiceTest {

    private final DataSource downDb = mock(DataSource.class);
    private final ObjectStore storage = mock(ObjectStore.class);
    private final MalwareScanner scanner = mock(MalwareScanner.class);

    private HealthService service(IdentityProviderProbe idp, Duration cacheTtl) {
        return new HealthService(downDb, storage, idp, scanner, new HealthProperties(cacheTtl, Duration.ofMinutes(15)));
    }

    @Test
    void databaseUnavailableIsDownAndProcessingUnknown() throws Exception {
        when(downDb.getConnection()).thenThrow(new SQLException("Connection refused"));
        when(storage.ping()).thenReturn(true);
        when(scanner.health()).thenReturn("UP");
        HealthService.HealthReport r = service(() -> true, Duration.ZERO).check();
        assertThat(r.status()).isEqualTo("DOWN");
        assertThat(r.database()).isEqualTo("DOWN");
        assertThat(r.processing().status()).isEqualTo("UNKNOWN");
        assertThat(r.storage()).isEqualTo("UP");
    }

    @Test
    void storageOrIdentityProviderUnavailableIsDown() throws Exception {
        when(downDb.getConnection()).thenThrow(new SQLException("down"));
        when(storage.ping()).thenReturn(false);
        when(scanner.health()).thenReturn("UP");
        HealthService.HealthReport r = service(() -> false, Duration.ZERO).check();
        assertThat(r.storage()).isEqualTo("DOWN");
        assertThat(r.identityProvider()).isEqualTo("DOWN");
        assertThat(r.status()).isEqualTo("DOWN");
    }

    @Test
    void aCheckThatThrowsCountsAsDownNotUp() throws Exception {
        when(downDb.getConnection()).thenThrow(new SQLException("down"));
        when(storage.ping()).thenThrow(new IllegalStateException("boom"));
        when(scanner.health()).thenThrow(new IllegalStateException("boom"));
        HealthService.HealthReport r = service(() -> { throw new IllegalStateException("boom"); }, Duration.ZERO).check();
        assertThat(r.storage()).isEqualTo("DOWN");
        assertThat(r.identityProvider()).isEqualTo("DOWN");
        assertThat(r.malwareScanner()).isEqualTo("DOWN");
    }

    @Test
    void scannerOrStalledProcessingDegradesButCoreDependenciesDecideDown() {
        HealthService.Processing idle = new HealthService.Processing("IDLE", 0L, 0L, null);
        assertThat(HealthService.assemble("UP", "UP", "UP", "UP", idle, null).status()).isEqualTo("UP");
        assertThat(HealthService.assemble("UP", "UP", "UP", "DISABLED", idle, null).status()).isEqualTo("DEGRADED");
        assertThat(HealthService.assemble("UP", "UP", "UP", "DOWN", idle, null).status()).isEqualTo("DEGRADED");
        HealthService.Processing stalled = new HealthService.Processing("STALLED", 3L, 0L, 7200L);
        assertThat(HealthService.assemble("UP", "UP", "UP", "UP", stalled, null).status()).isEqualTo("DEGRADED");
        assertThat(HealthService.assemble("UP", "UP", "DOWN", "DISABLED", stalled, null).status()).isEqualTo("DOWN");
    }

    @Test
    void workerUnavailableIsDetectedFromTheQueue() {
        Duration stall = Duration.ofMinutes(15);
        assertThat(HealthService.processingStatus(0, 0, null, stall)).isEqualTo("IDLE");
        assertThat(HealthService.processingStatus(0, 1, null, stall)).isEqualTo("ACTIVE");
        assertThat(HealthService.processingStatus(2, 0, 60L, stall)).as("recently queued").isEqualTo("ACTIVE");
        assertThat(HealthService.processingStatus(2, 0, 3600L, stall)).as("nobody claimed for an hour").isEqualTo("STALLED");
        assertThat(HealthService.processingStatus(2, 1, 3600L, stall)).as("a worker is busy").isEqualTo("ACTIVE");
    }

    @Test
    void resultsAreCachedSoAnonymousTrafficCannotFanOut() throws Exception {
        when(downDb.getConnection()).thenThrow(new SQLException("down"));
        when(storage.ping()).thenReturn(true);
        when(scanner.health()).thenReturn("UP");
        HealthService s = service(() -> true, Duration.ofMinutes(1));
        s.check();
        s.check();
        s.check();
        verify(storage, times(1)).ping();
    }
}
