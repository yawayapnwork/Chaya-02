package dev.chaya.api.erasure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.chaya.api.audit.AuditService;
import dev.chaya.api.pipeline.PipelineService;
import dev.chaya.api.rescan.ScanVersionService;
import dev.chaya.api.security.Actor;
import dev.chaya.api.security.Role;
import dev.chaya.api.security.TenantGuard;
import dev.chaya.api.storage.StorageProperties;
import dev.chaya.api.storage.TenantKeys;
import dev.chaya.api.web.ApiException;
import dev.chaya.api.web.NotFoundException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Erasure of a venue, a capture or a scan version, and everything derived from it (review E-1; docs/privacy-erasure.md).
 *
 * <p>One request is two phases:
 * <ol>
 *   <li><b>Rows</b>, in one transaction: the dependency closure is computed (below), every object key and prefix it owns
 *       is written to erasure_object, then every row is deleted -- not flagged -- with the immutability guards suspended
 *       for this transaction only (chaya.erasure, V30). A floor that published an erased version falls back to the
 *       nearest surviving ancestor version, or publishes nothing. The request, the ids it removed (erasure_item) and the
 *       audit record (counts only, never content) commit with the deletion, or nothing does.</li>
 *   <li><b>Objects</b>: {@link ErasureObjectPurger} deletes them right after commit and, until none is left, every minute.</li>
 * </ol>
 *
 * <p>The closure. Starting from the target, repeated until nothing is added:
 * <ul>
 *   <li>a capture brings its scan; a scan brings its scan versions and its pipeline run;</li>
 *   <li>a version brings every version whose parent it is (a re-scan's merged scene contains its parent's geometry), and
 *       every capture that is a re-scan of it (that capture exists only to change it);</li>
 *   <li>a version brings the run that produced it; a run brings every run whose reconstruction frame it is.</li>
 * </ul>
 * From the runs, scans and versions: their jobs, stage runs, artifacts (and their objects), PII purge and sweep records,
 * coordinate frames, version pins, navigation graphs (nodes, edges), detected POIs and their embeddings (the
 * pgvector index entries go with the rows), anchor poses, and capture media, upload parts and HUD samples. Staff-entered
 * data that merely referred to an erased version is kept but detached: a manual POI loses its version and frame tag, an
 * anchor whose pose was in an erased version becomes unversioned and uncalibrated, and a POI that an erased re-scan had
 * superseded is restored. A venue erasure removes every row of the venue and leaves a scrubbed tombstone (the audit log
 * refers to it), so the venue is 404 everywhere and every public link to it is gone.
 *
 * <p>Erasing anything but a whole venue that has dependents (cascaded captures) needs {@code cascade=true}; otherwise
 * 409 ERASURE_HAS_DEPENDENTS names them. Only an organization admin or the venue's manager may erase a capture or a
 * version; only an organization admin a venue. A repeated request for anything already erased returns that erasure (and
 * retries its objects): erasure is idempotent.
 */
@Service
public class ErasureService {

    private static final Logger log = LoggerFactory.getLogger(ErasureService.class);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    public enum Target { VENUE, CAPTURE, SCAN_VERSION }

    public record ErasureView(UUID id, Target targetType, UUID targetId, UUID venueId, String status, boolean cascade,
                              String requestedBy, Instant requestedAt, Instant rowsErasedAt, Instant completedAt,
                              int objectAttempts, String lastError, Map<String, Object> summary) {
        public boolean completed() {
            return "COMPLETED".equals(status);
        }
    }

    private final JdbcClient jdbc;
    private final TenantGuard guard;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final ErasureObjectPurger purger;
    private final ScanVersionService versions;
    private final PipelineService pipeline;
    private final StorageProperties storage;
    private final ObjectMapper mapper;

    public ErasureService(JdbcClient jdbc, TenantGuard guard, AuditService audit, TransactionTemplate tx, ErasureObjectPurger purger,
                          ScanVersionService versions, PipelineService pipeline, StorageProperties storage, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
        this.tx = tx;
        this.purger = purger;
        this.versions = versions;
        this.pipeline = pipeline;
        this.storage = storage;
        this.mapper = mapper;
    }

    // =========================================================================================
    // Entry points
    // =========================================================================================

    public ErasureView erase(Actor actor, UUID venueId, Target target, UUID targetId, boolean cascade) {
        Optional<UUID> earlier = covering(actor, venueId, target, targetId);
        if (earlier.isPresent()) {
            // Idempotent: the same answer as the first time, and one more attempt at whatever objects remain.
            requireEraser(actor, venueId, target, targetId, false);
            retryObjects(earlier.get());
            return view(earlier.get());
        }
        guard.requireVenue(actor, venueId);
        requireEraser(actor, venueId, target, targetId, true);
        UUID requestId = tx.execute(s -> eraseRows(actor, venueId, target, targetId, cascade));
        retryObjects(requestId);
        return view(requestId);
    }

    /** The erasure, to its requester or an admin of its organization. */
    public ErasureView get(Actor actor, UUID requestId) {
        ErasureView v = loadView(requestId).filter(e -> visibleTo(actor, e))
            .orElseThrow(() -> new NotFoundException("erasure not found"));
        return v;
    }

    /** The organization's erasures, newest first. Admins only (controller). */
    public List<ErasureView> list(Actor actor, int limit) {
        return jdbc.sql("SELECT id FROM erasure_request WHERE organization_id = :o ORDER BY requested_at DESC LIMIT :l")
            .param("o", actor.organizationId()).param("l", Math.min(Math.max(limit, 1), 500)).query(UUID.class).list()
            .stream().map(this::view).toList();
    }

    private boolean visibleTo(Actor actor, ErasureView v) {
        return actor.organizationId() != null && jdbc.sql("SELECT organization_id FROM erasure_request WHERE id = :id")
            .param("id", v.id()).query(UUID.class).single().equals(actor.organizationId())
            && (actor.isOrganizationAdmin() || actor.subject().equals(v.requestedBy()));
    }

    private void retryObjects(UUID requestId) {
        try {
            purger.purge(requestId);
        } catch (RuntimeException e) {
            // The rows are gone and the request is durable; the sweep retries the objects.
            log.error("erasure {}: object pass failed, the sweep will retry: {}", requestId, e.getMessage());
        }
    }

    /** An earlier erasure of this organization and venue that already removed the target. */
    private Optional<UUID> covering(Actor actor, UUID venueId, Target target, UUID targetId) {
        if (actor.organizationId() == null) {
            return Optional.empty();
        }
        return jdbc.sql("""
                SELECT r.id FROM erasure_item i JOIN erasure_request r ON r.id = i.request_id
                 WHERE i.item_type = :t AND i.item_id = :id AND r.organization_id = :o AND r.venue_id = :v
                 UNION ALL
                SELECT r.id FROM erasure_request r WHERE r.target_type = 'VENUE' AND r.target_id = :v AND r.organization_id = :o
                 LIMIT 1
                """)
            .param("t", target.name()).param("id", targetId).param("o", actor.organizationId()).param("v", venueId)
            .query(UUID.class).optional();
    }

    /** Admin of the organization for anything; the venue's manager for a capture or a version. Refusals are audited. */
    private void requireEraser(Actor actor, UUID venueId, Target target, UUID targetId, boolean auditDenial) {
        boolean admin = actor.kind() == Actor.Kind.USER && actor.isOrganizationAdmin();
        boolean manager = actor.kind() == Actor.Kind.USER && actor.rolesAt(venueId).contains(Role.VENUE_MANAGER);
        boolean allowed = target == Target.VENUE ? admin : admin || manager;
        if (!allowed) {
            if (auditDenial) {
                audit.denied(actor, "erasure." + target.name().toLowerCase(), resourceType(target), targetId);
            }
            throw new ApiException(HttpStatus.FORBIDDEN, "ERASURE_NOT_PERMITTED", target == Target.VENUE
                ? "only an organization administrator may erase a venue"
                : "only an organization administrator or the venue's manager may erase captured data");
        }
    }

    private static String resourceType(Target target) {
        return switch (target) {
            case VENUE -> "venue";
            case CAPTURE -> "capture_session";
            case SCAN_VERSION -> "scan_version";
        };
    }

    // =========================================================================================
    // Phase 1: rows
    // =========================================================================================

    private UUID eraseRows(Actor actor, UUID venueId, Target target, UUID targetId, boolean cascade) {
        UUID orgId = actor.organizationId();
        // Serialises with everything that writes under the venue: a child insert's foreign key check takes a share lock on
        // the venue row, so nothing new can reference what this transaction deletes.
        jdbc.sql("SELECT id FROM venue WHERE id = :v AND organization_id = :o FOR UPDATE")
            .param("v", venueId).param("o", orgId).query(UUID.class).single();
        jdbc.sql("CREATE TEMP TABLE erase_set (kind text NOT NULL, id uuid NOT NULL, PRIMARY KEY (kind, id)) ON COMMIT DROP").update();

        List<UUID> initialCaptures = new ArrayList<>();
        switch (target) {
            case VENUE -> {
                seedAll(venueId, "capture", "capture_session");
                seedAll(venueId, "scan", "scan");
                seedAll(venueId, "version", "scan_version");
                seedAll(venueId, "run", "pipeline_run");
            }
            case CAPTURE -> {
                requireRow("capture_session", targetId, venueId, orgId, "capture not found");
                add("capture", targetId);
                initialCaptures.add(targetId);
            }
            case SCAN_VERSION -> {
                requireRow("scan_version", targetId, venueId, orgId, "scan version not found");
                add("version", targetId);
            }
        }
        close(venueId);

        List<UUID> captures = ids("capture");
        List<UUID> dependents = captures.stream().filter(c -> !initialCaptures.contains(c)).toList();
        if (target != Target.VENUE && !dependents.isEmpty() && !cascade) {
            throw new ApiException(HttpStatus.CONFLICT, "ERASURE_HAS_DEPENDENTS", "erasing this also erases " + dependents.size()
                + " capture(s) derived from it (re-scans and their versions): " + dependents
                + ". Repeat the request with cascade=true to erase them too");
        }
        // Lock what a concurrent stage report would lock (PipelineService locks the run, then the job), so a report either
        // committed before this or finds its rows gone.
        jdbc.sql("SELECT id FROM pipeline_run WHERE id IN (SELECT id FROM erase_set WHERE kind = 'run') ORDER BY id FOR UPDATE")
            .query(UUID.class).list();
        derive(venueId);

        boolean leaseOutstanding = jdbc.sql("SELECT EXISTS (SELECT 1 FROM processing_job WHERE status = 'RUNNING' "
                + "AND id IN (SELECT id FROM erase_set WHERE kind = 'job'))").query(Boolean.class).single();
        UUID requestId = UUID.randomUUID();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("captures", captures.size());
        summary.put("dependentCaptures", dependents.size());
        summary.put("scanVersions", ids("version").size());
        summary.put("pipelineRuns", ids("run").size());
        jdbc.sql("""
                INSERT INTO erasure_request (id, organization_id, venue_id, target_type, target_id, cascade, requested_by, status,
                                             settle_after, summary)
                VALUES (:id, :o, :v, :t, :tid, :c, :by, 'OBJECTS_PENDING', now() + make_interval(secs => :settle), '{}'::jsonb)
                """)
            .param("id", requestId).param("o", orgId).param("v", venueId).param("t", target.name()).param("tid", targetId)
            .param("c", cascade).param("by", actor.subject())
            .param("settle", leaseOutstanding ? (double) pipeline.settleSeconds() : 0.0).update();
        recordItems(requestId, target, targetId);
        recordObjects(requestId, venueId, orgId, target);

        Map<UUID, UUID> fallback = fallbackVersions();
        jdbc.sql("SELECT set_config('chaya.erasure', 'on', true)").query(String.class).single();
        Map<String, Integer> rows = deleteRows(venueId, target);
        jdbc.sql("SELECT set_config('chaya.erasure', 'off', true)").query(String.class).single();
        if (target == Target.VENUE) {
            rows.putAll(eraseVenue(venueId, orgId));
        }
        Map<String, Object> publication = republish(fallback);

        summary.put("rowsDeleted", rows);
        summary.putAll(publication);
        summary.put("objectsDeleted", 0);
        jdbc.sql("UPDATE erasure_request SET summary = CAST(:s AS jsonb) WHERE id = :id").param("s", json(summary))
            .param("id", requestId).update();

        Map<String, Object> meta = new LinkedHashMap<>(summary);
        meta.remove("objectsDeleted");
        meta.put("erasureId", requestId.toString());
        meta.put("cascade", cascade);
        audit.success(actor, venueId, "erasure." + target.name().toLowerCase(), resourceType(target), targetId, meta);
        return requestId;
    }

    private void requireRow(String table, UUID id, UUID venueId, UUID orgId, String notFound) {
        boolean exists = jdbc.sql("SELECT EXISTS (SELECT 1 FROM " + table + " WHERE id = :id AND venue_id = :v AND organization_id = :o)")
            .param("id", id).param("v", venueId).param("o", orgId).query(Boolean.class).single();
        if (!exists) {
            throw new NotFoundException(notFound);
        }
    }

    private void seedAll(UUID venueId, String kind, String table) {
        jdbc.sql("INSERT INTO erase_set SELECT :k, id FROM " + table + " WHERE venue_id = :v ON CONFLICT DO NOTHING")
            .param("k", kind).param("v", venueId).update();
    }

    private void add(String kind, UUID id) {
        jdbc.sql("INSERT INTO erase_set VALUES (:k, :id) ON CONFLICT DO NOTHING").param("k", kind).param("id", id).update();
    }

    private List<UUID> ids(String kind) {
        return jdbc.sql("SELECT id FROM erase_set WHERE kind = :k ORDER BY id").param("k", kind).query(UUID.class).list();
    }

    private static String in(String kind) {
        return "(SELECT id FROM erase_set WHERE kind = '" + kind + "')";
    }

    /** The dependency closure (class comment), to a fixed point. Everything stays inside the venue. */
    private void close(UUID venueId) {
        String[] rules = {
            "INSERT INTO erase_set SELECT 'scan', id FROM scan WHERE venue_id = :v AND capture_session_id IN " + in("capture"),
            "INSERT INTO erase_set SELECT 'version', id FROM scan_version WHERE venue_id = :v AND scan_id IN " + in("scan"),
            "INSERT INTO erase_set SELECT 'version', id FROM scan_version WHERE venue_id = :v AND parent_version_id IN " + in("version"),
            "INSERT INTO erase_set SELECT 'capture', id FROM capture_session WHERE venue_id = :v AND parent_scan_version_id IN " + in("version"),
            "INSERT INTO erase_set SELECT 'run', id FROM pipeline_run WHERE venue_id = :v AND (scan_id IN " + in("scan")
                + " OR scan_version_id IN " + in("version") + " OR id IN (SELECT pipeline_run_id FROM scan_version WHERE id IN "
                + in("version") + ") OR reconstruction_frame_run_id IN " + in("run") + ")",
            "INSERT INTO erase_set SELECT 'version', scan_version_id FROM pipeline_run WHERE venue_id = :v AND scan_version_id IS NOT NULL "
                + "AND id IN " + in("run"),
            "INSERT INTO erase_set SELECT 'version', id FROM scan_version WHERE venue_id = :v AND pipeline_run_id IN " + in("run"),
        };
        for (int round = 0; ; round++) {
            int added = 0;
            for (String rule : rules) {
                added += jdbc.sql(rule + " ON CONFLICT DO NOTHING").param("v", venueId).update();
            }
            if (added == 0) {
                return;
            }
            if (round > 1000) {
                throw new IllegalStateException("erasure closure did not converge");
            }
        }
    }

    /** What the closure's runs, scans and versions own. */
    private void derive(UUID venueId) {
        String[] rules = {
            "INSERT INTO erase_set SELECT 'job', id FROM processing_job WHERE venue_id = :v AND (run_id IN " + in("run")
                + " OR scan_id IN " + in("scan") + " OR scan_version_id IN " + in("version") + ")",
            "INSERT INTO erase_set SELECT 'stage_run', id FROM pipeline_stage_run WHERE venue_id = :v AND (run_id IN " + in("run")
                + " OR job_id IN " + in("job") + ")",
            "INSERT INTO erase_set SELECT 'artifact', id FROM processing_artifact WHERE venue_id = :v AND (job_id IN " + in("job")
                + " OR stage_run_id IN " + in("stage_run") + " OR scan_id IN " + in("scan") + " OR scan_version_id IN " + in("version") + ")",
            "INSERT INTO erase_set SELECT 'frame', id FROM coordinate_frame WHERE venue_id = :v AND source_run_id IN " + in("run"),
            "INSERT INTO erase_set SELECT 'graph', id FROM navigation_graph WHERE venue_id = :v AND (scan_version_id IN " + in("version")
                + " OR pipeline_run_id IN " + in("run") + " OR coordinate_frame_id IN " + in("frame")
                + " OR navmesh_artifact_id IN " + in("artifact") + " OR navmesh_manifest_artifact_id IN " + in("artifact") + ")",
            "INSERT INTO erase_set SELECT 'media', id FROM capture_media WHERE venue_id = :v AND capture_session_id IN " + in("capture"),
            "INSERT INTO erase_set SELECT 'pose', id FROM ar_anchor_pose WHERE venue_id = :v AND (scan_version_id IN " + in("version")
                + " OR coordinate_frame_id IN " + in("frame") + ")",
            // Detected objects are derived from the capture's frames (their image embeddings are of frame crops).
            "INSERT INTO erase_set SELECT 'detected', id FROM poi_version WHERE venue_id = :v AND (pipeline_run_id IN " + in("run")
                + " OR (source = 'AUTO_DETECTED' AND (scan_version_id IN " + in("version") + " OR coordinate_frame_id IN " + in("frame") + ")))",
            "INSERT INTO erase_set SELECT 'poi', poi_id FROM poi_version WHERE id IN " + in("detected"),
        };
        for (String rule : rules) {
            jdbc.sql(rule + " ON CONFLICT DO NOTHING").param("v", venueId).update();
        }
    }

    private void recordItems(UUID requestId, Target target, UUID targetId) {
        for (String[] kind : new String[][] {{"capture", "CAPTURE"}, {"scan", "SCAN"}, {"version", "SCAN_VERSION"}, {"run", "PIPELINE_RUN"}}) {
            jdbc.sql("INSERT INTO erasure_item (request_id, item_type, item_id) SELECT :r, :t, id FROM erase_set WHERE kind = :k "
                    + "ON CONFLICT DO NOTHING")
                .param("r", requestId).param("t", kind[1]).param("k", kind[0]).update();
        }
        if (target == Target.VENUE) {
            jdbc.sql("INSERT INTO erasure_item (request_id, item_type, item_id) VALUES (:r, 'VENUE', :v) ON CONFLICT DO NOTHING")
                .param("r", requestId).param("v", targetId).update();
        }
    }

    /**
     * Every object the closure owns, before the rows that name them go: each registered key (artifacts: the sealed copy and
     * the worker's original; raw media), and each prefix under which anything of it may exist without a row (what a worker
     * uploaded but never registered, unfinished multipart uploads). A venue erasure clears the venue's whole prefixes.
     */
    private void recordObjects(UUID requestId, UUID venueId, UUID orgId, Target target) {
        String insert = "INSERT INTO erasure_object (request_id, bucket, object_key, is_prefix) ";
        jdbc.sql(insert + "SELECT :r, bucket, object_key, false FROM capture_media WHERE id IN " + in("media") + " ON CONFLICT DO NOTHING")
            .param("r", requestId).update();
        jdbc.sql(insert + "SELECT :r, bucket, object_key, false FROM processing_artifact WHERE id IN " + in("artifact")
            + " UNION SELECT :r, bucket, worker_object_key, false FROM processing_artifact WHERE id IN " + in("artifact")
            + " AND worker_object_key IS NOT NULL ON CONFLICT DO NOTHING").param("r", requestId).update();

        String own = TenantKeys.prefix(orgId, venueId);
        List<String[]> prefixes = new ArrayList<>();
        if (target == Target.VENUE) {
            for (String bucket : List.of(storage.bucket(), storage.derivedBucket())) {
                prefixes.add(new String[] {bucket, own});
                prefixes.add(new String[] {bucket, TenantKeys.SEALED_ROOT + own});
            }
        } else {
            for (UUID capture : ids("capture")) {
                prefixes.add(new String[] {storage.bucket(), own + "capture/" + capture + "/"});
            }
            for (UUID scan : ids("scan")) {
                prefixes.add(new String[] {storage.derivedBucket(), own + "scan/" + scan + "/"});
                prefixes.add(new String[] {storage.derivedBucket(), TenantKeys.SEALED_ROOT + own + "scan/" + scan + "/"});
            }
            // A run of a scan that is kept (erasing one version keeps its capture): just the run's own prefix.
            jdbc.sql("SELECT scan_id, id FROM pipeline_run WHERE id IN " + in("run") + " AND scan_id NOT IN " + in("scan"))
                .query((rs, i) -> own + "scan/" + rs.getObject(1, UUID.class) + "/run/" + rs.getObject(2, UUID.class) + "/")
                .list().forEach(p -> {
                    prefixes.add(new String[] {storage.derivedBucket(), p});
                    prefixes.add(new String[] {storage.derivedBucket(), TenantKeys.SEALED_ROOT + p});
                });
        }
        for (String[] p : prefixes) {
            jdbc.sql(insert + "VALUES (:r, :b, :k, true) ON CONFLICT DO NOTHING").param("r", requestId).param("b", p[0]).param("k", p[1])
                .update();
        }
    }

    /** For each floor that publishes an erased version: the nearest ancestor of it that survives and is FINALIZED (null:
     * none). Read before the lineage pointers are cut. */
    private Map<UUID, UUID> fallbackVersions() {
        Map<UUID, UUID> out = new LinkedHashMap<>();
        jdbc.sql("SELECT id, current_scan_version_id FROM floor WHERE current_scan_version_id IN " + in("version") + " FOR UPDATE")
            .query((rs, i) -> Map.entry(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class))).list()
            .forEach(e -> out.put(e.getKey(), jdbc.sql("""
                    SELECT l.id FROM scan_version_lineage(:cur) l JOIN scan_version v ON v.id = l.id
                     WHERE v.status = 'FINALIZED' AND v.coordinate_frame_id IS NOT NULL
                       AND l.id NOT IN (SELECT id FROM erase_set WHERE kind = 'version')
                     ORDER BY l.depth LIMIT 1
                    """).param("cur", e.getValue()).query(UUID.class).optional().orElse(null)));
        return out;
    }

    /** The deletes, in dependency order. Runs with the guards suspended (chaya.erasure). Returns rows per table. */
    private Map<String, Integer> deleteRows(UUID venueId, Target target) {
        Map<String, Integer> n = new LinkedHashMap<>();
        String V = in("version"), R = in("run"), F = in("frame"), A = in("artifact"), C = in("capture");

        // Publication: a floor never points at an erased version or frame (re-published from a survivor afterwards).
        n.merge("floor_unpublished", u("UPDATE floor SET current_scan_version_id = NULL WHERE current_scan_version_id IN " + V), Integer::sum);
        u("UPDATE floor SET current_coordinate_frame_id = NULL WHERE current_coordinate_frame_id IN " + F);

        // Anchors are staff-entered: a pose in an erased version goes, the anchor stays, unversioned and uncalibrated.
        n.put("ar_anchor_detached", u("UPDATE ar_anchor SET scan_version_id = NULL, coordinate_frame_id = NULL, current_pose_id = NULL, "
            + "calibration_status = 'UNCALIBRATED', last_calibrated_at = NULL WHERE scan_version_id IN " + V
            + " OR coordinate_frame_id IN " + F + " OR current_pose_id IN " + in("pose")));
        n.put("ar_anchor_pose", u("DELETE FROM ar_anchor_pose WHERE id IN " + in("pose")));

        String G = in("graph");
        n.put("navigation_edge", u("DELETE FROM navigation_edge WHERE graph_id IN " + G));
        n.put("navigation_node", u("DELETE FROM navigation_node WHERE graph_id IN " + G));
        n.put("navigation_graph", u("DELETE FROM navigation_graph WHERE id IN " + G));

        // POIs: detected objects go with their embeddings; staff POIs are detached from an erased version or frame; a POI an
        // erased re-scan superseded comes back; a POI left with no version goes (or, still referenced by navigation or a
        // floor connection, is soft-deleted).
        n.put("poi_version_detected", u("DELETE FROM poi_version WHERE id IN " + in("detected")));
        n.put("poi_version_detached", u("UPDATE poi_version SET "
            + "scan_version_id = CASE WHEN scan_version_id IN " + V + " THEN NULL ELSE scan_version_id END, "
            + "coordinate_frame_id = CASE WHEN coordinate_frame_id IN " + F + " THEN NULL ELSE coordinate_frame_id END "
            + "WHERE scan_version_id IN " + V + " OR coordinate_frame_id IN " + F));
        n.put("poi_restored", u("UPDATE poi SET superseded_by_scan_version_id = NULL, deleted_at = NULL "
            + "WHERE superseded_by_scan_version_id IN " + V));
        String orphan = "id IN " + in("poi") + " AND NOT EXISTS (SELECT 1 FROM poi_version pv WHERE pv.poi_id = poi.id)";
        n.put("poi", u("DELETE FROM poi WHERE " + orphan
            + " AND NOT EXISTS (SELECT 1 FROM navigation_node nn WHERE nn.poi_id = poi.id)"
            + " AND NOT EXISTS (SELECT 1 FROM floor_connection fc WHERE fc.from_poi_id = poi.id OR fc.to_poi_id = poi.id)"));
        n.put("poi_soft_deleted", u("UPDATE poi SET deleted_at = coalesce(deleted_at, now()) WHERE " + orphan));

        // Cycles between runs, versions, frames, artifacts and re-scan captures are cut before anything is deleted.
        u("UPDATE pipeline_run SET scan_version_id = NULL, reconstruction_frame_run_id = NULL WHERE id IN " + R);
        u("UPDATE scan_version SET parent_version_id = NULL WHERE id IN " + V);
        u("UPDATE coordinate_frame SET source_artifact_id = NULL WHERE id IN " + F);
        u("UPDATE capture_session SET parent_scan_version_id = NULL, region_geometry = NULL WHERE id IN " + C);

        n.put("scan_version_artifact", u("DELETE FROM scan_version_artifact WHERE scan_version_id IN " + V + " OR owner_version_id IN " + V));
        n.put("pii_staging_purge", u("DELETE FROM pii_staging_purge WHERE artifact_id IN " + A + " OR run_id IN " + R));
        n.put("pii_staging_sweep", u("DELETE FROM pii_staging_sweep WHERE run_id IN " + R));
        n.put("processing_artifact", u("DELETE FROM processing_artifact WHERE id IN " + A));
        n.put("pipeline_stage_run", u("DELETE FROM pipeline_stage_run WHERE id IN " + in("stage_run")));
        n.put("processing_job", u("DELETE FROM processing_job WHERE id IN " + in("job")));
        n.put("scan_version", u("DELETE FROM scan_version WHERE id IN " + V));
        n.put("coordinate_frame", u("DELETE FROM coordinate_frame WHERE id IN " + F));
        n.put("pipeline_run", u("DELETE FROM pipeline_run WHERE id IN " + R));

        n.put("capture_hud_pose_sample", u("DELETE FROM capture_hud_pose_sample WHERE capture_session_id IN " + C));
        n.put("capture_hud_quality_sample", u("DELETE FROM capture_hud_quality_sample WHERE capture_session_id IN " + C));
        n.put("capture_hud_scene", u("DELETE FROM capture_hud_scene WHERE capture_session_id IN " + C));
        n.put("capture_media_part", u("DELETE FROM capture_media_part WHERE media_id IN " + in("media")));
        n.put("capture_media", u("DELETE FROM capture_media WHERE id IN " + in("media")));
        n.put("scan", u("DELETE FROM scan WHERE id IN " + in("scan")));
        n.put("capture_session", u("DELETE FROM capture_session WHERE id IN " + C));
        n.values().removeIf(v -> v == 0);
        return n;
    }

    /**
     * What a venue holds besides captures and what derives from them: staff data (POIs, anchors, spaces, floors, floor
     * connections, the remaining navigation), search history (free text) and public links with their tokens. The venue
     * row stays as a scrubbed tombstone, deleted_at set: the audit log and the erasure record refer to it, and TenantGuard
     * answers 404 for it from now on.
     */
    private Map<String, Integer> eraseVenue(UUID venueId, UUID orgId) {
        Map<String, Integer> n = new LinkedHashMap<>();
        jdbc.sql("SELECT set_config('chaya.erasure', 'on', true)").query(String.class).single();
        String v = "venue_id = '" + venueId + "'";
        n.put("public_viewer_token", u("DELETE FROM public_viewer_token WHERE link_id IN (SELECT id FROM public_viewer_link WHERE " + v + ")"));
        n.put("public_viewer_link", u("DELETE FROM public_viewer_link WHERE " + v));
        n.put("search_query", u("DELETE FROM search_query WHERE " + v));
        u("UPDATE floor SET current_scan_version_id = NULL, current_coordinate_frame_id = NULL WHERE " + v);
        n.put("navigation_edge_venue", u("DELETE FROM navigation_edge WHERE " + v));
        n.put("navigation_node_venue", u("DELETE FROM navigation_node WHERE " + v));
        n.put("navigation_graph_venue", u("DELETE FROM navigation_graph WHERE " + v));
        n.put("floor_connection", u("DELETE FROM floor_connection WHERE " + v));
        u("UPDATE ar_anchor SET current_pose_id = NULL WHERE " + v);
        n.put("ar_anchor_pose_venue", u("DELETE FROM ar_anchor_pose WHERE " + v));
        n.put("ar_anchor", u("DELETE FROM ar_anchor WHERE " + v));
        n.put("poi_version_venue", u("DELETE FROM poi_version WHERE " + v));
        n.put("poi_venue", u("DELETE FROM poi WHERE " + v));
        n.put("coordinate_frame_venue", u("DELETE FROM coordinate_frame WHERE " + v));
        n.put("space", u("DELETE FROM space WHERE " + v));
        n.put("floor", u("DELETE FROM floor WHERE " + v));
        jdbc.sql("UPDATE venue SET deleted_at = coalesce(deleted_at, now()), name = 'erased', "
                + "slug = 'erased-' || replace(id::text, '-', ''), timezone = 'UTC' WHERE id = :v AND organization_id = :o")
            .param("v", venueId).param("o", orgId).update();
        jdbc.sql("SELECT set_config('chaya.erasure', 'off', true)").query(String.class).single();
        n.values().removeIf(x -> x == 0);
        return n;
    }

    /**
     * Floors that published an erased version publish its nearest surviving FINALIZED ancestor instead
     * (ScanVersionService#promote, with every V28 check), or nothing when there is none or that is refused. Runs with the
     * guards back in force, each floor inside its own savepoint.
     */
    private Map<String, Object> republish(Map<UUID, UUID> fallback) {
        List<String> republished = new ArrayList<>();
        List<String> unpublished = new ArrayList<>();
        for (Map.Entry<UUID, UUID> e : fallback.entrySet()) {
            boolean exists = jdbc.sql("SELECT EXISTS (SELECT 1 FROM floor WHERE id = :f)").param("f", e.getKey()).query(Boolean.class).single();
            if (!exists) {
                continue; // the venue itself is being erased
            }
            if (e.getValue() == null) {
                unpublished.add(e.getKey().toString());
                continue;
            }
            jdbc.sql("SAVEPOINT erasure_republish").update();
            try {
                versions.promote(e.getValue(), Map.of());
                jdbc.sql("RELEASE SAVEPOINT erasure_republish").update();
                republished.add(e.getKey().toString());
            } catch (RuntimeException ex) {
                jdbc.sql("ROLLBACK TO SAVEPOINT erasure_republish").update();
                versions.deferPublicationChecks();
                log.warn("erasure: floor {} could not fall back to scan version {}: {}", e.getKey(), e.getValue(), ex.getMessage());
                unpublished.add(e.getKey().toString());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (!republished.isEmpty()) {
            out.put("floorsRepublished", republished);
        }
        if (!unpublished.isEmpty()) {
            out.put("floorsUnpublished", unpublished);
        }
        return out;
    }

    private int u(String sql) {
        return jdbc.sql(sql).update();
    }

    // =========================================================================================
    // Views
    // =========================================================================================

    private ErasureView view(UUID id) {
        return loadView(id).orElseThrow();
    }

    private Optional<ErasureView> loadView(UUID id) {
        return jdbc.sql("""
                SELECT id, target_type, target_id, venue_id, status, cascade, requested_by, requested_at, rows_erased_at,
                       completed_at, object_attempts, last_error, summary
                  FROM erasure_request WHERE id = :id
                """).param("id", id)
            .query((rs, i) -> new ErasureView(rs.getObject("id", UUID.class), Target.valueOf(rs.getString("target_type")),
                rs.getObject("target_id", UUID.class), rs.getObject("venue_id", UUID.class), rs.getString("status"),
                rs.getBoolean("cascade"), rs.getString("requested_by"), rs.getTimestamp("requested_at").toInstant(),
                rs.getTimestamp("rows_erased_at").toInstant(),
                rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant(),
                rs.getInt("object_attempts"), rs.getString("last_error"), parse(rs.getString("summary"))))
            .optional();
    }

    private Map<String, Object> parse(String json) {
        try {
            return mapper.readValue(json, MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
