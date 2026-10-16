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
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** Failure modes of the health report: each broken dependency is named, and a broken core dependency is DOWN. */
class HealthServiceTest {

    private final DataSource downDb = mock(DataSource.class);
    private final ObjectStore storage = mock(ObjectStore.class);
    private final ObjectStore derived = mock(ObjectStore.class);

    @org.junit.jupiter.api.BeforeEach
    void derivedBucketAnswersUnlessATestSaysOtherwise() {
        when(derived.ping()).thenReturn(true);
    }
    private final MalwareScanner scanner = mock(MalwareScanner.class);

    private HealthService service(IdentityProviderProbe idp, Duration cacheTtl) {
        return new HealthService(downDb, storage, derived, idp, scanner, () -> "UP", new HealthProperties(cacheTtl, Duration.ofMinutes(15), null));
    }

    static HealthService.Processing processing(String status, long liveWorkers) {
        return new HealthService.Processing(status, 0L, 0L, null, liveWorkers, 0L, List.of());
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
        assertThat(r.worker()).as("worker liveness cannot be known without the database").isEqualTo("UNKNOWN");
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
        HealthService.Processing idle = processing("IDLE", 1);
        assertThat(HealthService.assemble("UP", "UP", "UP", "UP", "UP", idle, null).status()).isEqualTo("UP");
        assertThat(HealthService.assemble("UP", "UP", "UP", "DISABLED", "UP", idle, null).status()).isEqualTo("DEGRADED");
        assertThat(HealthService.assemble("UP", "UP", "UP", "DOWN", "UP", idle, null).status()).isEqualTo("DEGRADED");
        HealthService.Processing stalled = new HealthService.Processing("STALLED", 3L, 0L, 7200L, 1L, 0L, List.of());
        assertThat(HealthService.assemble("UP", "UP", "UP", "UP", "UP", stalled, null).status()).isEqualTo("DEGRADED");
        assertThat(HealthService.assemble("UP", "UP", "DOWN", "DISABLED", "UP", stalled, null).status()).isEqualTo("DOWN");
    }

    @Test
    void workersAndVisionAreReportedSeparatelyFromTheApiItself() {
        // No live worker: the API serves requests (not DOWN), but nothing can be processed.
        HealthService.HealthReport noWorker = HealthService.assemble("UP", "UP", "UP", "UP", "UP", processing("IDLE", 0), null);
        assertThat(noWorker.worker()).isEqualTo("DOWN");
        assertThat(noWorker.status()).isEqualTo("DEGRADED");
        // A live worker that cannot run a queued stage.
        HealthService.Processing unserved = new HealthService.Processing("NO_WORKER", 1L, 0L, 5L, 2L, 0L, List.of("POSE_ESTIMATION"));
        HealthService.HealthReport r = HealthService.assemble("UP", "UP", "UP", "UP", "UP", unserved, null);
        assertThat(r.worker()).isEqualTo("UP");
        assertThat(r.status()).isEqualTo("DEGRADED");
        // Vision is optional for serving: down degrades, not configured does not.
        assertThat(HealthService.assemble("UP", "UP", "UP", "UP", "DOWN", processing("IDLE", 1), null).status()).isEqualTo("DEGRADED");
        assertThat(HealthService.assemble("UP", "UP", "UP", "UP", "NOT_CONFIGURED", processing("IDLE", 1), null).status()).isEqualTo("UP");
        // A vision outage never makes the API DOWN.
        assertThat(HealthService.assemble("UP", "UP", "UP", "UP", "DOWN", processing("IDLE", 0), null).status()).isEqualTo("DEGRADED");
    }

    @Test
    void workerUnavailableIsDetectedFromTheQueue() {
        Duration stall = Duration.ofMinutes(15);
        assertThat(HealthService.processingStatus(0, 0, null, stall, false)).isEqualTo("IDLE");
        assertThat(HealthService.processingStatus(0, 1, null, stall, false)).isEqualTo("ACTIVE");
        assertThat(HealthService.processingStatus(2, 0, 60L, stall, false)).as("recently queued").isEqualTo("ACTIVE");
        assertThat(HealthService.processingStatus(2, 0, 3600L, stall, false)).as("nobody claimed for an hour").isEqualTo("STALLED");
        assertThat(HealthService.processingStatus(2, 1, 3600L, stall, false)).as("a worker is busy").isEqualTo("ACTIVE");
        assertThat(HealthService.processingStatus(2, 1, 5L, stall, true)).as("a queued stage no live worker runs").isEqualTo("NO_WORKER");
        assertThat(HealthService.processingStatus(0, 0, null, stall, true)).as("nothing queued").isEqualTo("IDLE");
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

    @Test
    void callersWaitingForASlowCheckShareItInsteadOfEachRunningAnother() throws Exception {
        // A dependency that takes longer to answer than the cache window, as a database outage does (connection timeout).
        when(downDb.getConnection()).thenThrow(new SQLException("down"));
        when(scanner.health()).thenReturn("UP");
        when(storage.ping()).thenAnswer(inv -> {
            Thread.sleep(800);
            return true;
        });
        HealthService s = service(() -> true, Duration.ofMillis(100));
        Thread first = new Thread(s::check);
        first.start();
        Thread.sleep(200); // the first check is in progress; these callers queue behind it
        Thread second = new Thread(s::check);
        Thread third = new Thread(s::check);
        long start = System.nanoTime();
        second.start();
        third.start();
        first.join();
        second.join();
        third.join();
        verify(storage, times(1)).ping();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1500));
    }

    @Test
    void storageIsUpOnlyWhenBothBucketsAnswer() throws Exception {
        when(downDb.getConnection()).thenThrow(new SQLException("down"));
        when(scanner.health()).thenReturn("UP");
        when(storage.ping()).thenReturn(true);
        when(derived.ping()).thenReturn(false); // e.g. the derived bucket was never created
        assertThat(service(() -> true, Duration.ZERO).check().storage()).isEqualTo("DOWN");
        when(derived.ping()).thenReturn(true);
        assertThat(service(() -> true, Duration.ZERO).check().storage()).isEqualTo("UP");
    }
}
