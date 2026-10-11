package dev.chaya.api.processing;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * When each worker last contacted the control plane (V34__worker_presence.sql). A claim poll stamps the worker and the
 * stages it offered; a heartbeat stamps a worker that is busy with a long stage and therefore not polling. Workers serve
 * every tenant: nothing here is tenant data, and no job or tenant id is stored.
 */
@Repository
public class WorkerPresenceRepository {

    static final int MAX_WORKER_ID = 200;

    public record Worker(String workerId, List<String> stages, Instant firstSeenAt, Instant lastSeenAt, Instant lastClaimedAt) {}

    private final JdbcClient jdbc;

    public WorkerPresenceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A claim poll: upserts the worker with the stages it offered; {@code claimed} stamps last_claimed_at too. */
    public void polled(String workerId, String serviceSubject, List<JobStage> stages, boolean claimed) {
        jdbc.sql("""
                INSERT INTO worker_presence (worker_id, service_subject, stages, last_claimed_at)
                VALUES (:w, :s, CAST(:stages AS text[]), CASE WHEN :claimed THEN now() END)
                ON CONFLICT (worker_id) DO UPDATE
                   SET service_subject = EXCLUDED.service_subject, stages = EXCLUDED.stages, last_seen_at = now(),
                       last_claimed_at = COALESCE(EXCLUDED.last_claimed_at, worker_presence.last_claimed_at)
                """)
            .param("w", bounded(workerId)).param("s", bounded(serviceSubject))
            .param("stages", "{" + String.join(",", stages.stream().map(Enum::name).distinct().sorted().toList()) + "}")
            .param("claimed", claimed)
            .update();
    }

    /** A heartbeat from a worker already known from a claim. Unknown ids are ignored: a heartbeat says nothing about stages. */
    public void heartbeat(String workerId) {
        if (workerId == null || workerId.isBlank()) {
            return;
        }
        jdbc.sql("UPDATE worker_presence SET last_seen_at = now() WHERE worker_id = :w").param("w", bounded(workerId)).update();
    }

    /** Workers seen within {@code window} of the database clock, newest first. */
    public List<Worker> seenWithin(Duration window) {
        return jdbc.sql("""
                SELECT worker_id, stages, first_seen_at, last_seen_at, last_claimed_at FROM worker_presence
                 WHERE last_seen_at >= now() - make_interval(secs => :secs)
                 ORDER BY last_seen_at DESC, worker_id
                 LIMIT 100
                """)
            .param("secs", window.toSeconds())
            .query((rs, i) -> new Worker(rs.getString("worker_id"), stages(rs.getArray("stages")),
                rs.getTimestamp("first_seen_at").toInstant(), rs.getTimestamp("last_seen_at").toInstant(),
                instant(rs.getTimestamp("last_claimed_at"))))
            .list();
    }

    /** Removes workers not seen for {@code olderThan}; returns how many. */
    public int prune(Duration olderThan) {
        return jdbc.sql("DELETE FROM worker_presence WHERE last_seen_at < now() - make_interval(secs => :secs)")
            .param("secs", olderThan.toSeconds()).update();
    }

    private static String bounded(String s) {
        return s.length() <= MAX_WORKER_ID ? s : s.substring(0, MAX_WORKER_ID);
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static List<String> stages(java.sql.Array a) throws java.sql.SQLException {
        List<String> out = new ArrayList<>();
        if (a != null) {
            for (Object o : (Object[]) a.getArray()) {
                out.add(String.valueOf(o));
            }
        }
        return out;
    }
}
