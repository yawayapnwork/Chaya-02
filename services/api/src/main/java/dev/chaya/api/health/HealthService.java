package dev.chaya.api.health;

import dev.chaya.api.capture.MalwareScanner;
import dev.chaya.api.storage.ObjectStore;
import jakarta.annotation.PreDestroy;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
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
 *   <li>DEGRADED (HTTP 200): the API works, but something it relies on is impaired: uploads cannot be accepted (malware
 *       scanner down or disabled: files are quarantined); no processing worker is live, or queued work has a stage no
 *       live worker serves, or the queue is stalled; or the vision service is down (semantic search falls back and POI
 *       embeddings wait).</li>
 * </ul>
 * Worker liveness is measured: every claim poll and heartbeat stamps worker_presence (V34). Components report fixed
 * status words, counts and stage names only, never exception text or worker ids: the endpoint is public.
 */
@Service
public class HealthService {

    public static final String UP = "UP";
    public static final String DOWN = "DOWN";
    public static final String DEGRADED = "DEGRADED";
    public static final String UNKNOWN = "UNKNOWN";

    /**
     * @param status           IDLE (nothing queued), ACTIVE, NO_WORKER (a claimable queued job's stage is offered by no live
     *                         worker), STALLED (oldest claimable queued job older than the stall threshold and nothing
     *                         running), or UNKNOWN (the database could not be asked)
     * @param liveWorkers      workers that polled or heartbeated within chaya.health.worker-live-after
     * @param staleRunningJobs RUNNING jobs whose lease has expired: their worker is gone and the lease enforcer has not
     *                         failed them yet (it runs every 30 s; a lasting non-zero value means it is not running)
     * @param unservedStages   stages of claimable queued jobs that no live worker offers to run (sorted)
     */
    public record Processing(String status, Long queuedJobs, Long runningJobs, Long oldestQueuedAgeSeconds, Long liveWorkers,
                             Long staleRunningJobs, List<String> unservedStages) {}

    /**
     * @param worker UP (at least one live worker), DOWN (none), UNKNOWN (the database could not be asked)
     * @param vision UP, DOWN or NOT_CONFIGURED. Optional for serving: when it is down the API still answers, but semantic
     *               search falls back and POI embeddings wait, so the report is DEGRADED.
     */
    public record HealthReport(String status, String database, String storage, String identityProvider,
                               String malwareScanner, String worker, String vision, Processing processing, Instant checkedAt) {}

    static final Processing UNKNOWN_PROCESSING = new Processing(UNKNOWN, null, null, null, null, null, List.of());
    private static final Duration CHECK_TIMEOUT = Duration.ofSeconds(6);

    private static final String QUEUE_SQL = """
        SELECT count(*) FILTER (WHERE j.status = 'QUEUED') AS queued,
               count(*) FILTER (WHERE j.status = 'RUNNING' AND j.lease_expires_at > now()) AS running,
               count(*) FILTER (WHERE j.status = 'RUNNING' AND j.lease_expires_at <= now()) AS stale,
               CAST(EXTRACT(EPOCH FROM now() - min(j.queued_at) FILTER (WHERE j.status = 'QUEUED')) AS bigint) AS oldest,
               array_agg(DISTINCT j.stage) FILTER (WHERE j.status = 'QUEUED') AS queued_stages
          FROM processing_job j
          LEFT JOIN pipeline_run r ON r.id = j.run_id
         WHERE j.status IN ('QUEUED', 'RUNNING')
           AND (r.id IS NULL OR r.deadline_at >= now() OR j.status = 'RUNNING')
        """;

    private static final String WORKERS_SQL = """
        SELECT count(DISTINCT w.worker_id) AS live, array_agg(DISTINCT s.stage) FILTER (WHERE s.stage IS NOT NULL) AS stages
          FROM worker_presence w
          LEFT JOIN LATERAL unnest(w.stages) AS s(stage) ON true
         WHERE w.last_seen_at >= now() - make_interval(secs => ?)
        """;

    private final DataSource dataSource;
    private final ObjectStore storage;
    /** Pipeline outputs: storage is UP only when both buckets exist and answer (uploads need one, processing the other). */
    private final ObjectStore derivedStorage;
    private final IdentityProviderProbe identityProvider;
    private final MalwareScanner scanner;
    private final VisionProbe vision;
    private final HealthProperties props;
    private final ExecutorService executor = Executors.newFixedThreadPool(5, r -> {
        Thread t = new Thread(r, "health-check");
        t.setDaemon(true);
        return t;
    });

    private HealthReport cached;
    private Instant cachedAt = Instant.EPOCH;

    public HealthService(DataSource dataSource, ObjectStore storage,
                         @org.springframework.beans.factory.annotation.Qualifier("derived") ObjectStore derivedStorage,
                         IdentityProviderProbe identityProvider, MalwareScanner scanner, VisionProbe vision, HealthProperties props) {
        this.dataSource = dataSource;
        this.storage = storage;
        this.derivedStorage = derivedStorage;
        this.identityProvider = identityProvider;
        this.scanner = scanner;
        this.vision = vision;
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
        CompletableFuture<String> store = async(() -> storage.ping() && derivedStorage.ping() ? UP : DOWN, DOWN);
        CompletableFuture<String> idp = async(() -> identityProvider.reachable() ? UP : DOWN, DOWN);
        CompletableFuture<String> av = async(scanner::health, DOWN);
        CompletableFuture<String> vis = async(vision::status, DOWN);
        Processing processing = db.join();
        String database = processing == UNKNOWN_PROCESSING ? DOWN : UP;
        cached = assemble(database, store.join(), idp.join(), av.join(), vis.join(), processing, now);
        cachedAt = now;
        return cached;
    }

    static HealthReport assemble(String database, String storage, String identityProvider, String scanner, String vision,
                                 Processing processing, Instant at) {
        String worker = processing.liveWorkers() == null ? UNKNOWN : processing.liveWorkers() > 0 ? UP : DOWN;
        String status;
        if (!UP.equals(database) || !UP.equals(storage) || !UP.equals(identityProvider)) {
            status = DOWN;
        } else if (DOWN.equals(scanner) || MalwareScanner.DISABLED.equals(scanner) || !UP.equals(worker)
                   || "STALLED".equals(processing.status()) || "NO_WORKER".equals(processing.status()) || DOWN.equals(vision)) {
            status = DEGRADED;
        } else {
            status = UP;
        }
        return new HealthReport(status, database, storage, identityProvider, scanner, worker, vision, processing, at);
    }

    private <T> CompletableFuture<T> async(Supplier<T> check, T onFailure) {
        return CompletableFuture.supplyAsync(check, executor)
            .completeOnTimeout(onFailure, CHECK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
            .exceptionally(e -> onFailure);
    }

    /** Database reachability, the processing queue and live workers, over one short-lived connection. */
    private Processing databaseAndQueue() {
        try (Connection c = dataSource.getConnection()) {
            if (!c.isValid(2)) {
                return UNKNOWN_PROCESSING;
            }
            long queued;
            long running;
            long stale;
            Long oldest;
            List<String> queuedStages;
            try (PreparedStatement st = c.prepareStatement(QUEUE_SQL)) {
                st.setQueryTimeout(3);
                try (ResultSet rs = st.executeQuery()) {
                    rs.next();
                    queued = rs.getLong("queued");
                    running = rs.getLong("running");
                    stale = rs.getLong("stale");
                    oldest = rs.getObject("oldest") == null ? null : rs.getLong("oldest");
                    queuedStages = strings(rs.getArray("queued_stages"));
                }
            }
            long live;
            List<String> servedStages;
            try (PreparedStatement st = c.prepareStatement(WORKERS_SQL)) {
                st.setQueryTimeout(3);
                st.setLong(1, props.workerLiveAfter().toSeconds());
                try (ResultSet rs = st.executeQuery()) {
                    rs.next();
                    live = rs.getLong("live");
                    servedStages = strings(rs.getArray("stages"));
                }
            }
            TreeSet<String> unserved = new TreeSet<>(queuedStages);
            unserved.removeAll(servedStages);
            return new Processing(processingStatus(queued, running, oldest, props.queueStallAfter(), !unserved.isEmpty()),
                queued, running, oldest, live, stale, List.copyOf(unserved));
        } catch (Exception e) {
            return UNKNOWN_PROCESSING;
        }
    }

    static String processingStatus(long queued, long running, Long oldestQueuedAgeSeconds, Duration stallAfter,
                                   boolean unservedStages) {
        if (queued == 0) {
            return running > 0 ? "ACTIVE" : "IDLE";
        }
        if (unservedStages) {
            return "NO_WORKER";
        }
        if (running == 0 && oldestQueuedAgeSeconds != null && oldestQueuedAgeSeconds > stallAfter.toSeconds()) {
            return "STALLED";
        }
        return "ACTIVE";
    }

    private static List<String> strings(Array a) throws SQLException {
        List<String> out = new ArrayList<>();
        if (a != null) {
            for (Object o : (Object[]) a.getArray()) {
                out.add(String.valueOf(o));
            }
        }
        return out;
    }
}
