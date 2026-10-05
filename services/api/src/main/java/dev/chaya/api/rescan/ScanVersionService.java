package dev.chaya.api.rescan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.chaya.api.frame.CoordinateFrameService;
import dev.chaya.api.frame.FrameDtos.FrameView;
import dev.chaya.api.security.Actor;
import dev.chaya.api.security.TenantGuard;
import dev.chaya.api.web.ApiException;
import dev.chaya.api.web.NotFoundException;
import java.sql.Array;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * What a ScanVersion is made of (docs/rescan.md, "VERSIONING"; V23__scan_version_integrity.sql): the run it is, the exact
 * coordinate frame its canonical data is in, and the exact artifacts that make it up (scan_version_artifact). Every
 * reader that shows or routes on one version -- viewer, POI list, search, routing -- resolves it through here, so nothing
 * of a newer version is ever read in its place.
 */
@Service
public class ScanVersionService {

    /** Kinds a version pins from its own run, besides its cloud (SPLAT_MERGED for a re-scan, SPLAT_CLEAN otherwise). */
    static final List<String> OWN_KINDS = List.of("KSPLAT", "ARTIFACT_MANIFEST", "PLANE_MODEL", "NAVMESH", "NAVMESH_MANIFEST",
        "NAVIGATION_GRAPH", "DETECTED_OBJECTS");

    /** A version's navigation: its own, all three, or none. Never inherited: a re-scan of a parent with a navmesh re-bakes
     * it from its merged scene (RescanService#parentHasNavigation), so a newer version never routes on navigation built
     * for geometry it no longer has. V26 enforces the same in the database. */
    static final Set<String> NAVIGATION_KINDS = Set.of("NAVMESH", "NAVMESH_MANIFEST", "NAVIGATION_GRAPH");

    public static final String VERSION_NOT_FINALIZED = "VERSION_NOT_FINALIZED";

    /** A FINALIZED version as readers use it. coordinateFrameId: the frame its canonical data (POIs, graphs) is in. */
    public record Scope(UUID id, UUID venueId, UUID floorId, int versionNumber, UUID parentVersionId, UUID runId,
                        UUID coordinateFrameId) {}

    /** One pinned artifact. ownerVersionId is the version whose run produced it (this one, or an inherited ancestor). */
    public record Pin(UUID artifactId, String kind, UUID ownerVersionId) {}

    private final JdbcClient jdbc;
    private final TenantGuard guard;
    private final CoordinateFrameService frames;
    private final ObjectMapper mapper;

    public ScanVersionService(JdbcClient jdbc, TenantGuard guard, CoordinateFrameService frames, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.frames = frames;
        this.mapper = mapper;
    }

    // ---- reading ----------------------------------------------------------------------------------------------------

    /** The FINALIZED version, within the actor's venue and organization: 404 otherwise, 409 when it is not finalized. */
    public Scope requireFinalized(Actor actor, UUID venueId, UUID versionId) {
        guard.requireVenue(actor, venueId);
        record Row(Scope scope, String status) {}
        Row row = jdbc.sql("""
                SELECT id, venue_id, floor_id, version_number, parent_version_id, pipeline_run_id, coordinate_frame_id, status
                  FROM scan_version WHERE id = :id AND venue_id = :v AND organization_id = :o
                """)
            .param("id", versionId).param("v", venueId).param("o", actor.organizationId())
            .query((rs, i) -> new Row(new Scope(rs.getObject("id", UUID.class), rs.getObject("venue_id", UUID.class),
                rs.getObject("floor_id", UUID.class), rs.getInt("version_number"), rs.getObject("parent_version_id", UUID.class),
                rs.getObject("pipeline_run_id", UUID.class), rs.getObject("coordinate_frame_id", UUID.class)), rs.getString("status")))
            .optional().orElseThrow(() -> new NotFoundException("scan version not found"));
        if (!"FINALIZED".equals(row.status())) {
            throw new ApiException(HttpStatus.CONFLICT, VERSION_NOT_FINALIZED,
                "scan version " + versionId + " is " + row.status() + "; only a FINALIZED version can be viewed or routed on");
        }
        if (row.scope().runId() == null || row.scope().coordinateFrameId() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_INCOMPLETE", "scan version " + versionId
                + " was finalized before versions recorded their run and coordinate frame, so its data cannot be identified");
        }
        return row.scope();
    }

    /** The FINALIZED version a run is, if any. A version finalized before V23 whose frame was never recorded does not
     * count: its data cannot be identified as a version's, so readers treat its run as a plain reconstruction. */
    public Optional<UUID> finalizedVersionOfRun(UUID runId) {
        return jdbc.sql("SELECT id FROM scan_version WHERE pipeline_run_id = :r AND status = 'FINALIZED' "
                + "AND coordinate_frame_id IS NOT NULL").param("r", runId)
            .query(UUID.class).optional();
    }

    public List<Pin> pins(UUID versionId) {
        return jdbc.sql("SELECT artifact_id, kind, owner_version_id FROM scan_version_artifact WHERE scan_version_id = :v ORDER BY kind")
            .param("v", versionId)
            .query((rs, i) -> new Pin(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class))).list();
    }

    /** The version the floor publishes (floor.current_scan_version_id, V28), if any. Callers have checked tenancy. */
    public Optional<UUID> currentOf(UUID floorId) {
        return jdbc.sql("SELECT current_scan_version_id FROM floor WHERE id = :f").param("f", floorId)
            .query((rs, i) -> Optional.ofNullable(rs.getObject(1, UUID.class))).optional().flatMap(o -> o);
    }

    /** The floor's current version as a Scope, or 409 NO_CURRENT_SCAN_VERSION. */
    public Scope requireCurrent(Actor actor, UUID venueId, UUID floorId) {
        UUID current = currentOf(floorId).orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, NO_CURRENT_SCAN_VERSION,
            "floor " + floorId + " has not published a scan version yet; nothing derived from a scan is current there"));
        return requireFinalized(actor, venueId, current);
    }

    public static final String NO_CURRENT_SCAN_VERSION = "NO_CURRENT_SCAN_VERSION";
    public static final String VERSION_MISMATCH = "VERSION_MISMATCH";

    // ---- numbering --------------------------------------------------------------------------------------------------

    /** The floor's next version number. Locks the floor row, so two versions created concurrently cannot share a number
     * (scan_version_floor_number_idx would refuse the second anyway). Call inside the inserting transaction. */
    public int nextVersionNumber(UUID floorId) {
        jdbc.sql("SELECT id FROM floor WHERE id = :f FOR UPDATE").param("f", floorId).query(UUID.class).optional();
        return jdbc.sql("SELECT coalesce(max(version_number), 0) + 1 FROM scan_version WHERE floor_id = :f")
            .param("f", floorId).query(Integer.class).single();
    }

    /**
     * The DRAFT version a full-venue run will be (review N-4): created with the run, so everything the run produces names
     * its version from the start, and nothing of it is current until {@link #promote}. No parent: a full reconstruction
     * is not derived from an earlier version's geometry. Call inside the transaction that creates the run, then record the
     * run on it with {@link #attachRun}.
     */
    public UUID createFullRunDraft(Actor actor, UUID venueId, UUID scanId, UUID floorId, Map<String, Object> processingConfig) {
        return jdbc.sql("""
                INSERT INTO scan_version (organization_id, venue_id, scan_id, floor_id, version_number, status, processing_config,
                    created_by)
                VALUES (:o, :v, :s, :f, :n, 'DRAFT', CAST(:config AS jsonb), :by) RETURNING id
                """)
            .param("o", actor.organizationId()).param("v", venueId).param("s", scanId).param("f", floorId)
            .param("n", nextVersionNumber(floorId)).param("config", json(processingConfig)).param("by", actor.subject())
            .query(UUID.class).single();
    }

    public void attachRun(UUID versionId, UUID runId) {
        jdbc.sql("UPDATE scan_version SET pipeline_run_id = :r WHERE id = :v AND pipeline_run_id IS NULL")
            .param("r", runId).param("v", versionId).update();
    }

    /** What a promotion published. */
    public record Promotion(UUID versionId, UUID floorId, UUID previousVersionId, UUID coordinateFrameId, int graphsActivated,
                            int graphsRetired, int poiVersionsBound, int anchorsBound, Map<String, Object> frameChange) {}

    /**
     * Makes the version what its floor publishes (docs/rescan.md, "Publication"), all at once inside the caller's
     * transaction: finalizes it if it is still DRAFT ({@link #finalizeVersion}); binds what its run produced before
     * versions existed (legacy graphs, POIs and anchors in a frame of its reconstruction); retires every ACTIVE graph of the
     * floor and activates the version's own (none, when it has no navigation: an older version's graph is never routed on
     * in its place); makes the ACTIVE canonical frame of its reconstruction the floor's current frame; and sets
     * floor.current_scan_version_id. The V28 commit-time checks refuse any other end state, so a failure anywhere leaves
     * the previous version published, untouched.
     *
     * <p>A re-scan is only promoted over its own parent (or onto a floor that publishes nothing): a re-scan of an older
     * version would silently drop whatever was published since (PARENT_NOT_CURRENT). A full reconstruction replaces
     * whatever is current. An ALIGNMENT_REJECTED version is never promoted. Promoting the current version again is a no-op.
     */
    public Promotion promote(UUID versionId, Map<String, Object> provenance) {
        record Version(UUID floorId, UUID parent, UUID runId, String status, UUID frameId) {}
        Version v = jdbc.sql("SELECT floor_id, parent_version_id, pipeline_run_id, status, coordinate_frame_id FROM scan_version "
                + "WHERE id = :v FOR UPDATE")
            .param("v", versionId)
            .query((rs, i) -> new Version(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                rs.getString(4), rs.getObject(5, UUID.class)))
            .optional().orElseThrow(() -> new NotFoundException("scan version not found"));
        UUID previous = jdbc.sql("SELECT current_scan_version_id FROM floor WHERE id = :f FOR UPDATE").param("f", v.floorId())
            .query((rs, i) -> Optional.ofNullable(rs.getObject(1, UUID.class))).single().orElse(null);
        if (versionId.equals(previous)) {
            return new Promotion(versionId, v.floorId(), previous, v.frameId(), 0, 0, 0, 0, Map.of());
        }
        if ("ALIGNMENT_REJECTED".equals(v.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_REJECTED", "scan version " + versionId
                + " failed its alignment gate and can never be published");
        }
        if (v.parent() != null && previous != null && !previous.equals(v.parent())) {
            throw new ApiException(HttpStatus.CONFLICT, "PARENT_NOT_CURRENT", "scan version " + versionId + " is a re-scan of "
                + v.parent() + ", but its floor now publishes " + previous + "; publishing it would drop that version's changes. "
                + "Re-scan the current version instead");
        }
        UUID frameId = "DRAFT".equals(v.status()) ? finalizeVersion(versionId, provenance) : v.frameId();
        if (frameId == null) {
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_INCOMPLETE", "scan version " + versionId
                + " was finalized before versions recorded their coordinate frame and cannot be published");
        }
        UUID frameRun = jdbc.sql("SELECT source_run_id FROM coordinate_frame WHERE id = :f").param("f", frameId)
            .query(UUID.class).single();

        // Bound once, never re-bound (V23 guards): what the run produced before it was a version.
        jdbc.sql("""
                UPDATE navigation_graph SET scan_version_id = :sv
                 WHERE pipeline_run_id = :r AND scan_version_id IS NULL AND coordinate_frame_id = :frame
                """).param("sv", versionId).param("r", v.runId()).param("frame", frameId).update();
        int pois = jdbc.sql("""
                UPDATE poi_version SET scan_version_id = :sv
                 WHERE scan_version_id IS NULL
                   AND (pipeline_run_id = :r
                        OR (source = 'MANUAL' AND coordinate_frame_id IN (SELECT id FROM coordinate_frame WHERE source_run_id = :fr)))
                   AND poi_id IN (SELECT id FROM poi WHERE floor_id = :f)
                """).param("sv", versionId).param("r", v.runId()).param("fr", frameRun).param("f", v.floorId()).update();
        int anchors = bindUnversionedAnchors(versionId, v.floorId(), frameRun);

        int retired = jdbc.sql("UPDATE navigation_graph SET status = 'RETIRED' WHERE floor_id = :f AND status = 'ACTIVE'")
            .param("f", v.floorId()).update();
        int activated = jdbc.sql("""
                UPDATE navigation_graph SET status = 'ACTIVE'
                 WHERE id IN (SELECT DISTINCT ON (profile) id FROM navigation_graph
                               WHERE scan_version_id = :sv AND floor_id = :f AND coordinate_frame_id = :frame
                               ORDER BY profile, created_at DESC)
                """).param("sv", versionId).param("f", v.floorId()).param("frame", frameId).update();

        FrameView floorFrame = frames.activeForRun(frameRun).filter(FrameView::canonical)
            .orElseGet(() -> frames.load(frameId).orElseThrow());
        Map<String, Object> frameChange = frames.adoptForFloor(v.floorId(), floorFrame);
        jdbc.sql("UPDATE floor SET current_scan_version_id = :sv WHERE id = :f").param("sv", versionId).param("f", v.floorId()).update();
        checkPublicationNow();
        return new Promotion(versionId, v.floorId(), previous, frameId, activated, retired, pois, anchors, frameChange);
    }

    private static final String PUBLICATION_CONSTRAINTS =
        "floor_publication_floor, floor_publication_graph, ar_anchor_pose_consistency, ar_anchor_current_pose_fkey";

    /** Runs the V28 commit-time checks now, so a promotion that would leave a floor inconsistent fails here -- inside the
     * caller's savepoint -- rather than at commit, where it would take the whole transaction with it. */
    public void checkPublicationNow() {
        jdbc.sql("SET CONSTRAINTS " + PUBLICATION_CONSTRAINTS + " IMMEDIATE").update();
        deferPublicationChecks();
    }

    /** Back to the V28 default (checked at commit). */
    public void deferPublicationChecks() {
        jdbc.sql("SET CONSTRAINTS " + PUBLICATION_CONSTRAINTS + " DEFERRED").update();
    }

    /** Anchors placed in a frame of the version's reconstruction before any version existed: their pose is recorded against
     * the version (a new revision), so they can take part in relocalization. */
    private int bindUnversionedAnchors(UUID versionId, UUID floorId, UUID frameRun) {
        List<UUID> anchors = jdbc.sql("""
                SELECT id FROM ar_anchor
                 WHERE floor_id = :f AND deleted_at IS NULL AND scan_version_id IS NULL
                   AND coordinate_frame_id IN (SELECT id FROM coordinate_frame WHERE source_run_id = :fr)
                """).param("f", floorId).param("fr", frameRun).query(UUID.class).list();
        for (UUID anchor : anchors) {
            UUID pose = jdbc.sql("""
                    INSERT INTO ar_anchor_pose (organization_id, venue_id, anchor_id, revision, scan_version_id, coordinate_frame_id,
                        physical_x, physical_y, physical_z, physical_qx, physical_qy, physical_qz, physical_qw,
                        digital_x, digital_y, digital_z, digital_qx, digital_qy, digital_qz, digital_qw, source, calibrated_at, created_by)
                    SELECT a.organization_id, a.venue_id, a.id,
                           coalesce((SELECT max(revision) FROM ar_anchor_pose WHERE anchor_id = a.id), 0) + 1, :sv, a.coordinate_frame_id,
                           a.physical_x, a.physical_y, a.physical_z, a.physical_qx, a.physical_qy, a.physical_qz, a.physical_qw,
                           a.digital_x, a.digital_y, a.digital_z, a.digital_qx, a.digital_qy, a.digital_qz, a.digital_qw, 'BACKFILL',
                           CASE WHEN a.calibration_status = 'CALIBRATED' THEN a.last_calibrated_at END, 'system:scan-version-promotion'
                      FROM ar_anchor a WHERE a.id = :a
                    RETURNING id
                    """).param("sv", versionId).param("a", anchor).query(UUID.class).single();
            jdbc.sql("UPDATE ar_anchor SET scan_version_id = :sv, current_pose_id = :p WHERE id = :a")
                .param("sv", versionId).param("p", pose).param("a", anchor).update();
        }
        return anchors.size();
    }

    // ---- finalization -----------------------------------------------------------------------------------------------

    /**
     * DRAFT -> FINALIZED, with everything the version is made of recorded first: its own run's published artifacts of
     * {@link #OWN_KINDS} and its cloud; for a re-scan, the parent's detections (its own cover only its region); and the
     * ACTIVE canonical frame of the run's reconstruction frame. Navigation is never inherited: it is all of the version's
     * own NAVMESH, NAVMESH_MANIFEST and NAVIGATION_GRAPH, or none; a re-scan of a parent with a navmesh must have re-baked
     * one; and the version's routing graphs must be the ones bound to its pinned navmesh (VERSION_INCOMPLETE /
     * VERSION_INCONSISTENT otherwise). The scan_version_guard trigger refuses
     * the transition unless the frame belongs to that reconstruction and the version's own viewer asset and cloud are
     * pinned. Must be called inside the caller's transaction. Returns the frame.
     */
    public UUID finalizeVersion(UUID versionId, Map<String, Object> provenance) {
        record Version(UUID parent, UUID runId, String status) {}
        Version v = jdbc.sql("SELECT parent_version_id, pipeline_run_id, status FROM scan_version WHERE id = :v FOR UPDATE")
            .param("v", versionId)
            .query((rs, i) -> new Version(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3)))
            .single();
        if (!"DRAFT".equals(v.status()) || v.runId() == null) {
            throw new IllegalStateException("scan version " + versionId + " is " + v.status() + " / run " + v.runId());
        }
        record Run(UUID frameRun, boolean incremental) {}
        Run run = jdbc.sql("SELECT coalesce(reconstruction_frame_run_id, id), stages FROM pipeline_run WHERE id = :r")
            .param("r", v.runId())
            .query((rs, i) -> new Run(rs.getObject(1, UUID.class), contains(rs.getArray(2), "REGION_SPLICE"))).single();
        FrameView frame = frames.activeForRun(run.frameRun()).filter(FrameView::canonical)
            .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, CoordinateFrameService.NOT_CALIBRATED,
                "the reconstruction has no calibrated canonical coordinate frame; a version's POIs, anchors and navigation are "
                    + "canonical metres, so it cannot be finalized until that reconstruction is calibrated"));

        List<String> kinds = new java.util.ArrayList<>(OWN_KINDS);
        kinds.add(run.incremental() ? "SPLAT_MERGED" : "SPLAT_CLEAN");
        jdbc.sql("""
                INSERT INTO scan_version_artifact (scan_version_id, artifact_id, kind, owner_version_id)
                SELECT DISTINCT ON (a.kind) :v, a.id, a.kind, :v
                  FROM processing_artifact a JOIN pipeline_stage_run sr ON sr.id = a.stage_run_id
                 WHERE sr.run_id = :r AND sr.status = 'SUCCEEDED' AND NOT a.contains_pii AND a.kind IN (:kinds)
                 ORDER BY a.kind, sr.finished_at DESC, a.created_at DESC
                """)
            .param("v", versionId).param("r", v.runId()).param("kinds", kinds).update();
        if (v.parent() != null) {
            jdbc.sql("""
                    INSERT INTO scan_version_artifact (scan_version_id, artifact_id, kind, owner_version_id)
                    SELECT :v, p.artifact_id, p.kind, p.owner_version_id FROM scan_version_artifact p
                     WHERE p.scan_version_id = :parent AND p.kind = 'DETECTED_OBJECTS'
                    """)
                .param("v", versionId).param("parent", v.parent()).update();
        }

        List<String> own = jdbc.sql("SELECT kind FROM scan_version_artifact WHERE scan_version_id = :v AND owner_version_id = :v")
            .param("v", versionId).query(String.class).list();
        String cloudKind = run.incremental() ? "SPLAT_MERGED" : "SPLAT_CLEAN";
        if (!own.contains("KSPLAT") || !own.contains(cloudKind)) {
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_INCOMPLETE", "run " + v.runId() + " published no "
                + (own.contains("KSPLAT") ? cloudKind : "viewer asset (KSPLAT)") + "; a version is only finalized with its own "
                + "viewer asset and cloud");
        }
        requireConsistentNavigation(versionId, v.parent(), own);
        Integer foreignGraphs = jdbc.sql("SELECT count(*) FROM navigation_graph WHERE scan_version_id = :v "
                + "AND coordinate_frame_id IS DISTINCT FROM :f").param("v", versionId).param("f", frame.id()).query(Integer.class).single();
        if (foreignGraphs > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_FRAME_MISMATCH", "the version's navigation graphs were baked in "
                + "another coordinate frame than " + frame.id() + " (the reconstruction was recalibrated); bake them again");
        }
        jdbc.sql("UPDATE scan_version SET status = 'FINALIZED', finalized_at = now(), coordinate_frame_id = :f, "
                + "provenance = CAST(:p AS jsonb) WHERE id = :v AND status = 'DRAFT'")
            .param("f", frame.id()).param("p", json(provenance)).param("v", versionId).update();
        return frame.id();
    }

    private void requireConsistentNavigation(UUID versionId, UUID parentId, List<String> own) {
        List<String> ownNavigation = own.stream().filter(NAVIGATION_KINDS::contains).sorted().toList();
        if (!ownNavigation.isEmpty() && ownNavigation.size() != NAVIGATION_KINDS.size()) {
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_INCOMPLETE", "the version's run published only " + ownNavigation
                + " of " + NAVIGATION_KINDS + "; a navmesh, its manifest and its routing graph are pinned together or not at all");
        }
        boolean parentNavigation = parentId != null && jdbc.sql(
                "SELECT EXISTS (SELECT 1 FROM scan_version_artifact WHERE scan_version_id = :p AND kind = 'NAVMESH')")
            .param("p", parentId).query(Boolean.class).single();
        if (parentNavigation && ownNavigation.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_INCOMPLETE", "the parent version has a navmesh but this re-scan "
                + "published none; navigation is never inherited, because it was baked for geometry the re-scan replaced");
        }
        Integer unbound = jdbc.sql("""
                SELECT count(*) FROM navigation_graph g
                 WHERE g.scan_version_id = :v AND g.navmesh_artifact_id IS DISTINCT FROM (
                     SELECT artifact_id FROM scan_version_artifact WHERE scan_version_id = :v AND kind = 'NAVMESH')
                """).param("v", versionId).query(Integer.class).single();
        if (unbound > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_INCONSISTENT", unbound + " of the version's routing graphs are "
                + "not bound to the navmesh it pins");
        }
    }

    private static boolean contains(Array sqlArray, String value) throws SQLException {
        if (sqlArray == null) {
            return false;
        }
        for (Object o : (Object[]) sqlArray.getArray()) {
            if (value.equals(String.valueOf(o))) {
                return true;
            }
        }
        return false;
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("not serializable", e);
        }
    }
}
