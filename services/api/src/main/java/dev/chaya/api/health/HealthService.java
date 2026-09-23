package dev.chaya.api.health;

import dev.chaya.api.capture.MalwareScanner;
import dev.chaya.api.storage.ObjectStore;
import jakarta.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.stereotype.Service;

/**
 * Checks real dependencies, each within a bounded time, and says plainly what is broken.
 *
 * <ul>
 *   <li>DOWN (HTTP 503): the database, object storage or the identity provider's signing keys are unreachable, so the
 *       API cannot serve authenticated requests correctly.</li>
 *   <li>DEGRADED (HTTP 200): the API works, but uploads cannot be accepted (malware scanner down or disabled: files are
 *       quarantined) or processing is stalled (claimable jobs are waiting and no worker is taking them).</li>
 * </ul>
 * Components report fixed status words only, never exception text: the endpoint is public.
 */
@Service
public class HealthService {

    public static final String UP = "UP";
    public static final String DOWN = "DOWN";
    public static final String DEGRADED = "DEGRADED";

    /**
     * @param status IDLE (nothing queued), ACTIVE, STALLED (oldest claimable queued job older than the stall threshold
     *               and nothing running), or UNKNOWN (the database could not be asked)
     */
    public record Processing(String status, Long queuedJobs, Long runningJobs, Long oldestQueuedAgeSeconds) {}

    public record HealthReport(String status, String database, String storage, String identityProvider,
                               String malwareScanner, Processing processing, Instant checkedAt) {}

    static final Processing UNKNOWN_PROCESSING = new Processing("UNKNOWN", null, null, null);
    private static final Duration CHECK_TIMEOUT = Duration.ofSeconds(6);

    private static final String QUEUE_SQL = """
        SELECT count(*) FILTER (WHERE j.status = 'QUEUED') AS queued,
               count(*) FILTER (WHERE j.status = 'RUNNING' AND j.lease_expires_at > now()) AS running,
               CAST(EXTRACT(EPOCH FROM now() - min(j.queued_at) FILTER (WHERE j.status = 'QUEUED')) AS bigint) AS oldest
          FROM processing_job j
          LEFT JOIN pipeline_run r ON r.id = j.run_id
         WHERE j.status IN ('QUEUED', 'RUNNING')
           AND (r.id IS NULL OR r.deadline_at >= now())
        """;

    private final DataSource dataSource;
    private final ObjectStore storage;
    private final IdentityProviderProbe identityProvider;
    private final MalwareScanner scanner;
    private final HealthProperties props;
    private final ExecutorService executor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "health-check");
        t.setDaemon(true);
        return t;
    });

    private HealthReport cached;
    private Instant cachedAt = Instant.EPOCH;

    public HealthService(DataSource dataSource, ObjectStore storage, IdentityProviderProbe identityProvider,
                         MalwareScanner scanner, HealthProperties props) {
        this.dataSource = dataSource;
        this.storage = storage;
        this.identityProvider = identityProvider;
        this.scanner = scanner;
        this.props = props;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /** One check at a time; callers within the cache window share the result. */
    public synchronized HealthReport check() {
        Instant now = Instant.now();
        if (cached != null && Duration.between(cachedAt, now).compareTo(props.cacheTtl()) < 0) {
            return cached;
        }
        CompletableFuture<Processing> db = async(this::databaseAndQueue, UNKNOWN_PROCESSING);
        CompletableFuture<String> store = async(() -> storage.ping() ? UP : DOWN, DOWN);
        CompletableFuture<String> idp = async(() -> identityProvider.reachable() ? UP : DOWN, DOWN);
        CompletableFuture<String> av = async(scanner::health, DOWN);
        Processing processing = db.join();
        String database = processing == UNKNOWN_PROCESSING ? DOWN : UP;
        cached = assemble(database, store.join(), idp.join(), av.join(), processing, now);
        cachedAt = now;
        return cached;
    }

    static HealthReport assemble(String database, String storage, String identityProvider, String scanner,
                                 Processing processing, Instant at) {
        String status;
        if (!UP.equals(database) || !UP.equals(storage) || !UP.equals(identityProvider)) {
            status = DOWN;
        } else if (DOWN.equals(scanner) || MalwareScanner.DISABLED.equals(scanner) || "STALLED".equals(processing.status())) {
            status = DEGRADED;
        } else {
            status = UP;
        }
        return new HealthReport(status, database, storage, identityProvider, scanner, processing, at);
    }

    private <T> CompletableFuture<T> async(Supplier<T> check, T onFailure) {
        return CompletableFuture.supplyAsync(check, executor)
            .completeOnTimeout(onFailure, CHECK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
            .exceptionally(e -> onFailure);
    }

    /** Database reachability plus the processing queue, over one short-lived connection. */
    private Processing databaseAndQueue() {
        try (Connection c = dataSource.getConnection()) {
            if (!c.isValid(2)) {
                return UNKNOWN_PROCESSING;
            }
            try (PreparedStatement st = c.prepareStatement(QUEUE_SQL)) {
                st.setQueryTimeout(3);
                try (ResultSet rs = st.executeQuery()) {
                    rs.next();
                    long queued = rs.getLong("queued");
                    long running = rs.getLong("running");
                    Long oldest = rs.getObject("oldest") == null ? null : rs.getLong("oldest");
                    return new Processing(processingStatus(queued, running, oldest, props.queueStallAfter()), queued, running, oldest);
                }
            }
        } catch (Exception e) {
            return UNKNOWN_PROCESSING;
        }
    }

    static String processingStatus(long queued, long running, Long oldestQueuedAgeSeconds, Duration stallAfter) {
        if (queued == 0) {
            return running > 0 ? "ACTIVE" : "IDLE";
        }
        if (running == 0 && oldestQueuedAgeSeconds != null && oldestQueuedAgeSeconds > stallAfter.toSeconds()) {
            return "STALLED";
        }
        return "ACTIVE";
    }
}
