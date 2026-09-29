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

    /** Kinds a re-scan inherits from its parent when its own run did not produce them: navigation is only re-baked when
     * the region touches the floor's graph (RescanService#navigationIntersectsRegion). */
    static final Set<String> INHERITABLE_KINDS = Set.of("NAVMESH", "NAVMESH_MANIFEST", "NAVIGATION_GRAPH");

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

    // ---- numbering --------------------------------------------------------------------------------------------------

    /** The floor's next version number. Locks the floor row, so two versions created concurrently cannot share a number
     * (scan_version_floor_number_idx would refuse the second anyway). Call inside the inserting transaction. */
    public int nextVersionNumber(UUID floorId) {
        jdbc.sql("SELECT id FROM floor WHERE id = :f FOR UPDATE").param("f", floorId).query(UUID.class).optional();
        return jdbc.sql("SELECT coalesce(max(version_number), 0) + 1 FROM scan_version WHERE floor_id = :f")
            .param("f", floorId).query(Integer.class).single();
    }

    // ---- finalization -----------------------------------------------------------------------------------------------

    /**
     * DRAFT -> FINALIZED, with everything the version is made of recorded first: its own run's published artifacts of
     * {@link #OWN_KINDS} and its cloud; for a re-scan, the parent's navigation pins it did not replace and the parent's
     * detections; and the ACTIVE canonical frame of the run's reconstruction frame. The scan_version_guard trigger refuses
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
                     WHERE p.scan_version_id = :parent
                       AND (p.kind = 'DETECTED_OBJECTS'
                            OR (p.kind IN (:inheritable) AND NOT EXISTS (
                                SELECT 1 FROM scan_version_artifact own WHERE own.scan_version_id = :v AND own.kind = p.kind)))
                    """)
                .param("v", versionId).param("parent", v.parent()).param("inheritable", INHERITABLE_KINDS).update();
        }

        List<String> own = jdbc.sql("SELECT kind FROM scan_version_artifact WHERE scan_version_id = :v AND owner_version_id = :v")
            .param("v", versionId).query(String.class).list();
        String cloudKind = run.incremental() ? "SPLAT_MERGED" : "SPLAT_CLEAN";
        if (!own.contains("KSPLAT") || !own.contains(cloudKind)) {
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_INCOMPLETE", "run " + v.runId() + " published no "
                + (own.contains("KSPLAT") ? cloudKind : "viewer asset (KSPLAT)") + "; a version is only finalized with its own "
                + "viewer asset and cloud");
        }
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
