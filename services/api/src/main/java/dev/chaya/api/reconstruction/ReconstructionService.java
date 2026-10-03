package dev.chaya.api.reconstruction;

import dev.chaya.api.frame.CoordinateFrameService;
import dev.chaya.api.frame.FrameDtos.FrameView;
import dev.chaya.api.rescan.ScanVersionService;
import dev.chaya.api.security.Actor;
import dev.chaya.api.security.Role;
import dev.chaya.api.security.TenantGuard;
import dev.chaya.api.web.BadRequestException;
import dev.chaya.api.web.NotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 *
 * <p>Privacy (review S-6): a run an administrator started with privacy preprocessing disabled was trained on frames whose
 * faces, screens and documents were never anonymised, so its splat can show them. It is never served to a PUBLIC_VIEWER
 * (an anonymous public link): it is not listed, and every read of it or of its artifacts is 404, as if it did not
 * exist. Signed-in venue members still see it. A pinned artifact is checked against the run that produced it, so a
 * re-scan cannot carry a privacy-disabled parent's splat onto a public link either.
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

    /** scanVersionId / versionNumber / parentVersionId: the FINALIZED ScanVersion this run is, or null for a reconstruction
     * that was never formalized as a version. */
    public record ReconstructionVersion(UUID runId, UUID floorId, Instant generatedAt, String runStatus, String runQuality,
                                        UUID scanVersionId, Integer versionNumber, UUID parentVersionId) {}

    public record ArtifactRef(String kind, String contentType, long sizeBytes, String sha256, String url) {}

    /** coordinateFrame: how the artifacts (always in their reconstruction frame: arbitrary scale/rotation/origin) are placed
     * in canonical metres; a viewer must not overlay canonical POIs or routes without a canonical one.
     *
     * <p>For a FINALIZED ScanVersion (scanVersionId set) everything is that version's own: the artifacts are exactly the ones
     * it pinned (scan_version_artifact; served from /scan-versions/{id}/artifacts), and the frame is the one it recorded at
     * finalization, never a later calibration or another version's asset. Otherwise (a reconstruction never formalized as
     * a version) the artifacts are the run's and the frame is the reconstruction's ACTIVE calibration, or null. */
    public record Reconstruction(UUID runId, UUID scanId, UUID floorId, Instant generatedAt, String runStatus,
                                 String runQuality, List<ArtifactRef> artifacts, FrameView coordinateFrame,
                                 UUID scanVersionId, Integer versionNumber, UUID parentVersionId) {}

    public record StoredArtifact(String bucket, String objectKey, String contentType, long sizeBytes) {}

    private final JdbcClient jdbc;
    private final TenantGuard guard;
    private final CoordinateFrameService frames;
    private final ScanVersionService versions;

    public ReconstructionService(JdbcClient jdbc, TenantGuard guard, CoordinateFrameService frames, ScanVersionService versions) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.frames = frames;
        this.versions = versions;
    }

    @Transactional(readOnly = true)
    public List<ReconstructionVersion> listForFloor(Actor actor, UUID venueId, UUID floorId) {
        guard.requireVenue(actor, venueId);
        requireFloor(venueId, floorId);
        return jdbc.sql("""
                SELECT r.id AS run_id, cs.floor_id, sr.finished_at, r.status, r.quality,
                       sv.id AS scan_version_id, sv.version_number, sv.parent_version_id
                  FROM pipeline_stage_run sr
                  JOIN pipeline_run r ON r.id = sr.run_id
                  JOIN capture_session cs ON cs.id = r.capture_session_id
                  LEFT JOIN scan_version sv ON sv.pipeline_run_id = r.id AND sv.status = 'FINALIZED'
                                           AND sv.coordinate_frame_id IS NOT NULL
                 WHERE sr.stage = 'ARTIFACT_GENERATION' AND sr.status = 'SUCCEEDED'
                   AND cs.floor_id = :floor AND r.venue_id = :venue AND r.organization_id = :org
                   AND (r.privacy_enabled OR NOT :anonymous)
                   -- a re-scan's merged model is listed only once its version is FINALIZED: a re-scan that later failed or
                   -- was rejected never replaces what viewers see (docs/rescan.md)
                   AND (r.scan_version_id IS NULL
                        OR EXISTS (SELECT 1 FROM scan_version v WHERE v.id = r.scan_version_id AND v.status = 'FINALIZED'))
                 ORDER BY sr.finished_at DESC
                 LIMIT 50
                """)
            .param("floor", floorId).param("venue", venueId).param("org", actor.organizationId())
            .param("anonymous", anonymous(actor))
            .query((rs, i) -> new ReconstructionVersion(rs.getObject("run_id", UUID.class), rs.getObject("floor_id", UUID.class),
                rs.getTimestamp("finished_at").toInstant(), rs.getString("status"), rs.getString("quality"),
                rs.getObject("scan_version_id", UUID.class), (Integer) rs.getObject("version_number"),
                rs.getObject("parent_version_id", UUID.class)))
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

    private record Row(UUID scanId, UUID floorId, Instant generatedAt, String status, String quality, UUID frameRunId) {}

    /** The reconstruction a run produced. When the run is a FINALIZED ScanVersion, that version's view of it. */
    @Transactional(readOnly = true)
    public Reconstruction get(Actor actor, UUID venueId, UUID runId) {
        guard.requireVenue(actor, venueId);
        Optional<UUID> version = versions.finalizedVersionOfRun(runId);
        if (version.isPresent()) {
            return getVersion(actor, venueId, version.get());
        }
        Row row = runRow(actor, venueId, runId);
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
        requireViewerAsset(artifacts);
        return new Reconstruction(runId, row.scanId(), row.floorId(), row.generatedAt(), row.status(), row.quality(), artifacts,
            frames.activeForRun(row.frameRunId()).orElse(null), null, null, null);
    }

    /** A FINALIZED ScanVersion's reconstruction: its pinned artifacts and its recorded frame, nothing else. */
    @Transactional(readOnly = true)
    public Reconstruction getVersion(Actor actor, UUID venueId, UUID scanVersionId) {
        ScanVersionService.Scope scope = versions.requireFinalized(actor, venueId, scanVersionId);
        Row row = runRow(actor, venueId, scope.runId());
        List<ArtifactRef> artifacts = jdbc.sql("""
                SELECT pin.kind, a.size_bytes, a.checksum_sha256
                  FROM scan_version_artifact pin
                  JOIN processing_artifact a ON a.id = pin.artifact_id
                  JOIN pipeline_stage_run asr ON asr.id = a.stage_run_id
                  JOIN pipeline_run ar ON ar.id = asr.run_id
                 WHERE pin.scan_version_id = :sv AND pin.kind IN (:kinds) AND a.contains_pii = false
                   AND (ar.privacy_enabled OR NOT :anonymous)
                 ORDER BY pin.kind
                """)
            .param("sv", scanVersionId).param("kinds", VIEWER_ARTIFACT_KINDS).param("anonymous", anonymous(actor))
            .query((rs, i) -> new ArtifactRef(rs.getString("kind"), SERVED_CONTENT_TYPES.get(rs.getString("kind")), rs.getLong("size_bytes"),
                rs.getString("checksum_sha256"),
                "/api/v1/venues/" + venueId + "/scan-versions/" + scanVersionId + "/artifacts/" + rs.getString("kind")))
            .list();
        requireViewerAsset(artifacts);
        return new Reconstruction(scope.runId(), row.scanId(), row.floorId(), row.generatedAt(), row.status(), row.quality(),
            artifacts, frames.load(scope.coordinateFrameId()).orElseThrow(), scope.id(), scope.versionNumber(),
            scope.parentVersionId());
    }

    private static void requireViewerAsset(List<ArtifactRef> artifacts) {
        if (artifacts.stream().noneMatch(a -> a.kind().equals("KSPLAT"))) {
            // ARTIFACT_GENERATION succeeded but the .ksplat itself is missing/was never published: nothing to view.
            throw new NotFoundException("reconstruction has no viewer asset (.ksplat) yet");
        }
    }

    private Row runRow(Actor actor, UUID venueId, UUID runId) {
        return jdbc.sql("""
                SELECT r.scan_id, cs.floor_id, sr.finished_at, r.status, r.quality,
                       coalesce(r.reconstruction_frame_run_id, r.id) AS frame_run_id
                  FROM pipeline_stage_run sr
                  JOIN pipeline_run r ON r.id = sr.run_id
                  JOIN capture_session cs ON cs.id = r.capture_session_id
                 WHERE sr.stage = 'ARTIFACT_GENERATION' AND sr.status = 'SUCCEEDED'
                   AND r.id = :run AND r.venue_id = :venue AND r.organization_id = :org
                   AND (r.privacy_enabled OR NOT :anonymous)
                """)
            .param("run", runId).param("venue", venueId).param("org", actor.organizationId()).param("anonymous", anonymous(actor))
            .query((rs, i) -> new Row(rs.getObject("scan_id", UUID.class), rs.getObject("floor_id", UUID.class),
                rs.getTimestamp("finished_at").toInstant(), rs.getString("status"), rs.getString("quality"),
                rs.getObject("frame_run_id", UUID.class)))
            .optional().orElseThrow(() -> new NotFoundException("reconstruction not found"));
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
                   AND (r.privacy_enabled OR NOT :anonymous)
                """)
            .param("run", runId).param("kind", kind).param("venue", venueId).param("org", actor.organizationId())
            .param("anonymous", anonymous(actor))
            .query((rs, i) -> new StoredArtifact(rs.getString("bucket"), rs.getString("object_key"),
                SERVED_CONTENT_TYPES.get(kind), rs.getLong("size_bytes")))
            .optional().orElseThrow(() -> new NotFoundException("artifact not found"));
    }

    /** The bytes of one artifact a FINALIZED version pinned -- the exact object, even when a later run of the same floor
     * published another of that kind. */
    @Transactional(readOnly = true)
    public StoredArtifact versionArtifactBytes(Actor actor, UUID venueId, UUID scanVersionId, String kind) {
        if (!VIEWER_ARTIFACT_KINDS.contains(kind)) {
            throw new BadRequestException("unknown viewer artifact kind: " + kind);
        }
        ScanVersionService.Scope scope = versions.requireFinalized(actor, venueId, scanVersionId);
        runRow(actor, venueId, scope.runId()); // 404 for a public viewer when the version's own run had privacy disabled
        return jdbc.sql("""
                SELECT a.bucket, a.object_key, a.size_bytes
                  FROM scan_version_artifact pin JOIN processing_artifact a ON a.id = pin.artifact_id
                  JOIN pipeline_stage_run asr ON asr.id = a.stage_run_id
                  JOIN pipeline_run ar ON ar.id = asr.run_id
                 WHERE pin.scan_version_id = :sv AND pin.kind = :kind AND a.contains_pii = false
                   AND (ar.privacy_enabled OR NOT :anonymous)
                """)
            .param("sv", scanVersionId).param("kind", kind).param("anonymous", anonymous(actor))
            .query((rs, i) -> new StoredArtifact(rs.getString("bucket"), rs.getString("object_key"),
                SERVED_CONTENT_TYPES.get(kind), rs.getLong("size_bytes")))
            .optional().orElseThrow(() -> new NotFoundException("artifact not found"));
    }

    /** A public-link viewer: never served a reconstruction trained without privacy preprocessing. */
    private static boolean anonymous(Actor actor) {
        return actor.kind() == Actor.Kind.PUBLIC_VIEWER || actor.roles().contains(Role.PUBLIC_VIEWER);
    }

    private void requireFloor(UUID venueId, UUID floorId) {
        if (jdbc.sql("SELECT count(*) FROM floor WHERE id = :f AND venue_id = :v AND deleted_at IS NULL")
                .param("f", floorId).param("v", venueId).query(Integer.class).single() == 0) {
            throw new NotFoundException("floor not found");
        }
    }
}
