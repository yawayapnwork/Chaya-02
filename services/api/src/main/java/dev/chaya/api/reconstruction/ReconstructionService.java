package dev.chaya.api.reconstruction;

import dev.chaya.api.frame.CoordinateFrameService;
import dev.chaya.api.frame.FrameDtos.FrameView;
import dev.chaya.api.security.Actor;
import dev.chaya.api.security.TenantGuard;
import dev.chaya.api.web.BadRequestException;
import dev.chaya.api.web.NotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only access to the reconstruction artifacts the Python worker produced, for the digital twin
 * viewer. A "reconstruction" here is one pipeline_run whose ARTIFACT_GENERATION stage succeeded -- that
 * is the honest signal that a real, loadable .ksplat exists, not pipeline_run.status.
 *
 * <p>pipeline_run.status is deliberately NOT used to decide whether a reconstruction is viewable: the
 * plan also includes NAVIGATION_BAKING and SEMANTIC_INDEXING (chaya_worker.stages.unimplemented), which
 * are not implemented yet and always fail the run overall (see docs/pipeline.md). A run can therefore end
 * FAILED at NAVIGATION_BAKING while still holding a perfectly real, complete reconstruction from the
 * stage before it. Keying off the ARTIFACT_GENERATION stage_run directly is what lets the viewer show
 * that reconstruction instead of "No reconstruction available" for every run that exists today.
 */
@Service
public class ReconstructionService {

    /** Kinds ARTIFACT_GENERATION and PLANE_FITTING publish that are safe and meaningful to hand to a viewer.
     * NAVMESH is the Detour navmesh tile NAVIGATION_BAKING publishes (chaya_worker.stages.navigation_baking, built by
     * the real Recast/Detour library; docs/navigation.md), served as opaque bytes. */
    private static final Set<String> VIEWER_ARTIFACT_KINDS = Set.of("KSPLAT", "ARTIFACT_MANIFEST", "PLANE_MODEL", "NAVMESH");

    /** The content type each viewer kind is SERVED as. Fixed here rather than taken from the worker's report: the
     * bytes are streamed from the API's own origin to any viewer (public links included), so a worker-chosen type
     * such as text/html or image/svg+xml would turn a published artifact into script running on that origin. */
    static final Map<String, String> SERVED_CONTENT_TYPES = Map.of(
        "KSPLAT", "application/octet-stream",
        "ARTIFACT_MANIFEST", "application/json",
        "PLANE_MODEL", "application/json",
        "NAVMESH", "application/octet-stream");

    public record ReconstructionVersion(UUID runId, UUID floorId, Instant generatedAt, String runStatus, String runQuality) {}

    public record ArtifactRef(String kind, String contentType, long sizeBytes, String sha256, String url) {}

    /** coordinateFrame: the ACTIVE calibration of the reconstruction frame the artifacts are in, or null when it has never
     * been calibrated. The .ksplat is always in its reconstruction frame (arbitrary scale/rotation/origin); a viewer places
     * it in canonical metres only through a canonical frame, and must not overlay canonical POIs or routes otherwise. */
    public record Reconstruction(UUID runId, UUID scanId, UUID floorId, Instant generatedAt, String runStatus,
                                 String runQuality, List<ArtifactRef> artifacts, FrameView coordinateFrame) {}

    public record StoredArtifact(String bucket, String objectKey, String contentType, long sizeBytes) {}

    private final JdbcClient jdbc;
    private final TenantGuard guard;
    private final CoordinateFrameService frames;

    public ReconstructionService(JdbcClient jdbc, TenantGuard guard, CoordinateFrameService frames) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.frames = frames;
    }

    @Transactional(readOnly = true)
    public List<ReconstructionVersion> listForFloor(Actor actor, UUID venueId, UUID floorId) {
        guard.requireVenue(actor, venueId);
        requireFloor(venueId, floorId);
        return jdbc.sql("""
                SELECT r.id AS run_id, cs.floor_id, sr.finished_at, r.status, r.quality
                  FROM pipeline_stage_run sr
                  JOIN pipeline_run r ON r.id = sr.run_id
                  JOIN capture_session cs ON cs.id = r.capture_session_id
                 WHERE sr.stage = 'ARTIFACT_GENERATION' AND sr.status = 'SUCCEEDED'
                   AND cs.floor_id = :floor AND r.venue_id = :venue AND r.organization_id = :org
                 ORDER BY sr.finished_at DESC
                 LIMIT 50
                """)
            .param("floor", floorId).param("venue", venueId).param("org", actor.organizationId())
            .query((rs, i) -> new ReconstructionVersion(rs.getObject("run_id", UUID.class), rs.getObject("floor_id", UUID.class),
                rs.getTimestamp("finished_at").toInstant(), rs.getString("status"), rs.getString("quality")))
            .list();
    }

    @Transactional(readOnly = true)
    public Reconstruction latestForFloor(Actor actor, UUID venueId, UUID floorId) {
        List<ReconstructionVersion> versions = listForFloor(actor, venueId, floorId);
        if (versions.isEmpty()) {
            throw new NotFoundException("no reconstruction is available for this floor yet");
        }
        return get(actor, venueId, versions.get(0).runId());
    }

    @Transactional(readOnly = true)
    public Reconstruction get(Actor actor, UUID venueId, UUID runId) {
        guard.requireVenue(actor, venueId);
        record Row(UUID scanId, UUID floorId, Instant generatedAt, String status, String quality, UUID frameRunId) {}
        Row row = jdbc.sql("""
                SELECT r.scan_id, cs.floor_id, sr.finished_at, r.status, r.quality,
                       coalesce(r.reconstruction_frame_run_id, r.id) AS frame_run_id
                  FROM pipeline_stage_run sr
                  JOIN pipeline_run r ON r.id = sr.run_id
                  JOIN capture_session cs ON cs.id = r.capture_session_id
                 WHERE sr.stage = 'ARTIFACT_GENERATION' AND sr.status = 'SUCCEEDED'
                   AND r.id = :run AND r.venue_id = :venue AND r.organization_id = :org
                """)
            .param("run", runId).param("venue", venueId).param("org", actor.organizationId())
            .query((rs, i) -> new Row(rs.getObject("scan_id", UUID.class), rs.getObject("floor_id", UUID.class),
                rs.getTimestamp("finished_at").toInstant(), rs.getString("status"), rs.getString("quality"),
                rs.getObject("frame_run_id", UUID.class)))
            .optional().orElseThrow(() -> new NotFoundException("reconstruction not found"));

        List<ArtifactRef> artifacts = jdbc.sql("""
                SELECT a.kind, a.size_bytes, a.checksum_sha256
                  FROM processing_artifact a
                  JOIN pipeline_stage_run sr ON sr.id = a.stage_run_id
                 WHERE sr.run_id = :run AND sr.status = 'SUCCEEDED' AND a.kind IN (:kinds) AND a.contains_pii = false
                 ORDER BY a.kind
                """)
            .param("run", runId).param("kinds", VIEWER_ARTIFACT_KINDS)
            .query((rs, i) -> new ArtifactRef(rs.getString("kind"), SERVED_CONTENT_TYPES.get(rs.getString("kind")), rs.getLong("size_bytes"),
                rs.getString("checksum_sha256"),
                "/api/v1/venues/" + venueId + "/reconstructions/" + runId + "/artifacts/" + rs.getString("kind")))
            .list();
        if (artifacts.stream().noneMatch(a -> a.kind().equals("KSPLAT"))) {
            // ARTIFACT_GENERATION succeeded but the .ksplat itself is missing/was never published: nothing to view.
            throw new NotFoundException("reconstruction has no viewer asset (.ksplat) yet");
        }
        return new Reconstruction(runId, row.scanId(), row.floorId(), row.generatedAt(), row.status(), row.quality(), artifacts,
            frames.activeForRun(row.frameRunId()).orElse(null));
    }

    @Transactional(readOnly = true)
    public StoredArtifact artifactBytes(Actor actor, UUID venueId, UUID runId, String kind) {
        guard.requireVenue(actor, venueId);
        if (!VIEWER_ARTIFACT_KINDS.contains(kind)) {
            throw new BadRequestException("unknown viewer artifact kind: " + kind);
        }
        return jdbc.sql("""
                SELECT a.bucket, a.object_key, a.size_bytes
                  FROM processing_artifact a
                  JOIN pipeline_stage_run sr ON sr.id = a.stage_run_id
                  JOIN pipeline_run r ON r.id = sr.run_id
                 WHERE sr.run_id = :run AND sr.status = 'SUCCEEDED' AND a.kind = :kind AND a.contains_pii = false
                   AND r.venue_id = :venue AND r.organization_id = :org
                """)
            .param("run", runId).param("kind", kind).param("venue", venueId).param("org", actor.organizationId())
            .query((rs, i) -> new StoredArtifact(rs.getString("bucket"), rs.getString("object_key"),
                SERVED_CONTENT_TYPES.get(kind), rs.getLong("size_bytes")))
            .optional().orElseThrow(() -> new NotFoundException("artifact not found"));
    }

    private void requireFloor(UUID venueId, UUID floorId) {
        if (jdbc.sql("SELECT count(*) FROM floor WHERE id = :f AND venue_id = :v AND deleted_at IS NULL")
                .param("f", floorId).param("v", venueId).query(Integer.class).single() == 0) {
            throw new NotFoundException("floor not found");
        }
    }
}
