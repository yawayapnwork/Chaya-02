package dev.chaya.api.erasure;

import dev.chaya.api.audit.AuditService;
import dev.chaya.api.security.Actor;
import dev.chaya.api.security.Role;
import dev.chaya.api.storage.ObjectStore;
import dev.chaya.api.storage.StorageProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The object half of an erasure (docs/privacy-erasure.md). The rows went in one transaction; objects cannot be part of
 * it, so ErasureService wrote every key and prefix to delete into erasure_object first. This deletes them, and is safe to
 * run any number of times: deleting a missing key succeeds, and a prefix is listed again on every pass.
 *
 * <ul>
 *   <li>A key is done once its delete succeeded.</li>
 *   <li>A prefix: unfinished multipart uploads under it are aborted and every object under it is deleted. It is done only
 *       when a listing after {@code settle_after} finds it empty, because a worker that held a lease when the rows went
 *       may still upload until that lease ends.</li>
 *   <li>The request is COMPLETED when nothing is left; until then it stays OBJECTS_PENDING with the last error, and
 *       {@link ErasureSweeper} retries it every minute (the {@code chaya_erasure_pending_overdue} gauge and
 *       {@code ChayaErasureIncomplete} alert report one that keeps failing).</li>
 * </ul>
 */
@Component
public class ErasureObjectPurger {

    private static final Logger log = LoggerFactory.getLogger(ErasureObjectPurger.class);
    static final Actor SYSTEM = new Actor(Actor.Kind.SERVICE, "system:erasure", null, Set.of(), Set.of(Role.SERVICE));

    private final JdbcClient jdbc;
    private final ObjectStore raw;
    private final ObjectStore derived;
    private final StorageProperties storage;
    private final AuditService audit;
    private final TransactionTemplate tx;

    public ErasureObjectPurger(JdbcClient jdbc, ObjectStore raw, @Qualifier("derived") ObjectStore derived, StorageProperties storage,
                               AuditService audit, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.raw = raw;
        this.derived = derived;
        this.storage = storage;
        this.audit = audit;
        this.tx = tx;
    }

    private ObjectStore storeFor(String bucket) {
        if (bucket.equals(storage.bucket())) {
            return raw;
        }
        if (bucket.equals(storage.derivedBucket())) {
            return derived;
        }
        throw new IllegalStateException("erasure names bucket " + bucket + ", which this API does not manage");
    }

    /** One pass over the request's outstanding objects. Returns true when the request is (now) COMPLETED. */
    public boolean purge(UUID requestId) {
        record Request(UUID orgId, UUID venueId, String status, boolean settled, String targetType, UUID targetId) {}
        Request r = jdbc.sql("SELECT organization_id, venue_id, status, settle_after <= now(), target_type, target_id "
                + "FROM erasure_request WHERE id = :id")
            .param("id", requestId)
            .query((rs, i) -> new Request(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                rs.getBoolean(4), rs.getString(5), rs.getObject(6, UUID.class)))
            .optional().orElseThrow(() -> new IllegalArgumentException("no erasure request " + requestId));
        if ("COMPLETED".equals(r.status())) {
            return true;
        }
        record Item(String bucket, String key, boolean prefix) {}
        List<Item> items = jdbc.sql("SELECT bucket, object_key, is_prefix FROM erasure_object "
                + "WHERE request_id = :id AND deleted_at IS NULL ORDER BY is_prefix, bucket, object_key")
            .param("id", requestId)
            .query((rs, i) -> new Item(rs.getString(1), rs.getString(2), rs.getBoolean(3))).list();
        long deleted = 0;
        List<String> errors = new ArrayList<>();
        for (Item item : items) {
            try {
                ObjectStore store = storeFor(item.bucket());
                boolean done;
                if (item.prefix()) {
                    store.abortUploads(item.key());
                    for (String key : store.list(item.key())) {
                        store.delete(key);
                        deleted++;
                    }
                    // Declared empty only once no worker can still write under it.
                    done = r.settled() && store.list(item.key()).isEmpty();
                } else {
                    store.delete(item.key());
                    deleted++;
                    done = true;
                }
                if (done) {
                    jdbc.sql("UPDATE erasure_object SET deleted_at = now() WHERE request_id = :id AND bucket = :b AND object_key = :k")
                        .param("id", requestId).param("b", item.bucket()).param("k", item.key()).update();
                }
            } catch (RuntimeException e) {
                // Object keys are server-generated ids; the message carries no content.
                errors.add(item.bucket() + "/" + item.key() + ": " + e.getMessage());
                log.error("erasure {}: could not delete {}/{}: {}", requestId, item.bucket(), item.key(), e.getMessage());
            }
        }
        boolean complete = jdbc.sql("SELECT NOT EXISTS (SELECT 1 FROM erasure_object WHERE request_id = :id AND deleted_at IS NULL)")
            .param("id", requestId).query(Boolean.class).single();
        String error = errors.isEmpty() ? null : errors.size() + " failed; first: " + errors.get(0);
        final long d = deleted;
        return Boolean.TRUE.equals(tx.execute(s -> {
            int changed = jdbc.sql("""
                    UPDATE erasure_request
                       SET object_attempts = object_attempts + 1, last_error = :err,
                           summary = jsonb_set(summary, '{objectsDeleted}',
                                               to_jsonb(coalesce((summary->>'objectsDeleted')::bigint, 0) + :n)),
                           status = CASE WHEN :complete THEN 'COMPLETED' ELSE status END,
                           completed_at = CASE WHEN :complete THEN now() ELSE completed_at END
                     WHERE id = :id AND status = 'OBJECTS_PENDING'
                    """).param("err", error).param("n", d).param("complete", complete).param("id", requestId).update();
            if (complete && changed == 1) {
                audit.successInOrganization(SYSTEM, r.orgId(), r.venueId(), "erasure.completed", "erasure_request", requestId,
                    Map.of("targetType", r.targetType(), "targetId", r.targetId().toString()));
            }
            return complete;
        }));
    }

    /** Retries every request whose objects are not all gone yet. Returns how many completed. */
    public int sweep() {
        List<UUID> pending = jdbc.sql("SELECT id FROM erasure_request WHERE status = 'OBJECTS_PENDING' ORDER BY requested_at LIMIT 50")
            .query(UUID.class).list();
        int completed = 0;
        for (UUID id : pending) {
            try {
                if (purge(id)) {
                    completed++;
                }
            } catch (RuntimeException e) {
                log.error("erasure {}: object pass failed: {}", id, e.getMessage());
            }
        }
        return completed;
    }

    /** OBJECTS_PENDING requests older than this many seconds: what the gauge reports. */
    public static final String PENDING_OVERDUE =
        "FROM erasure_request WHERE status = 'OBJECTS_PENDING' AND settle_after < now() - make_interval(secs => :overdue)";
}
