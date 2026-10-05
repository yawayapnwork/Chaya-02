package dev.chaya.api.ar;

import dev.chaya.api.ar.ArDtos.Anchor;
import dev.chaya.api.ar.ArDtos.AnchorObservation;
import dev.chaya.api.ar.ArDtos.AnchorRequest;
import dev.chaya.api.ar.ArDtos.Pose;
import dev.chaya.api.ar.ArDtos.RelocalizationResponse;
import dev.chaya.api.audit.AuditService;
import dev.chaya.api.frame.CoordinateFrameService;
import dev.chaya.api.frame.FrameDtos.FrameView;
import dev.chaya.api.frame.FrameProperties;
import dev.chaya.api.rescan.ScanVersionService;
import dev.chaya.api.security.Actor;
import dev.chaya.api.security.TenantGuard;
import dev.chaya.api.web.ApiException;
import dev.chaya.api.web.NotFoundException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CRUD and relocalization for {@code ar_anchor}. Every method takes the venue and floor explicitly and
 * goes through {@link TenantGuard} first (see docs/security.md); a floor-scoped AR session (authenticated
 * user or PUBLIC_VIEWER, both already bound to one venue by the token that authenticated them) can never
 * read another venue's anchors -- there is no path here that queries by anchor id alone.
 *
 * <p>Frames (docs/coordinate-frames.md): an anchor's digital pose is canonical venue metres, +Z up, in the frame recorded
 * on it -- always the floor's current coordinate frame when the pose was entered, so anchors cannot be created on a floor
 * without one (NOT_CALIBRATED). An anchor whose frame is no longer the floor's current one cannot be calibrated or used
 * for relocalization (ANCHOR_FRAME_STALE) until its pose is re-entered; CoordinateFrameService re-projects anchors
 * automatically when only the calibration of the same reconstruction changed.
 *
 * <p>Versions (V28, review V-1): a pose is entered against the scan version its floor publishes (NO_CURRENT_SCAN_VERSION
 * when there is none) and recorded as an immutable ar_anchor_pose revision -- the version, the frame (a calibration of
 * that version's reconstruction), both poses, and when an operator verified it. Editing, or a recalibration re-projecting
 * it, adds a revision; nothing rewrites an earlier one. {@link #list(Actor, UUID, UUID, UUID)} with a version returns the
 * anchors as they were in that version (scan_version_anchor_pose). Relocalization only uses anchors entered against the
 * published version or an ancestor of it, and refuses a client showing another version (VERSION_MISMATCH).
 */
@Service
public class AnchorService {

    private static final String SELECT = """
            SELECT a.id, a.venue_id, a.floor_id, a.marker_type, a.marker_identifier, a.marker_size_m,
                   a.physical_x, a.physical_y, a.physical_z, a.physical_qx, a.physical_qy, a.physical_qz, a.physical_qw,
                   a.digital_x, a.digital_y, a.digital_z, a.digital_qx, a.digital_qy, a.digital_qz, a.digital_qw,
                   a.calibration_status, a.last_calibrated_at, a.coordinate_frame_id, a.scan_version_id,
                   a.current_pose_id AS pose_id, (SELECT revision FROM ar_anchor_pose WHERE id = a.current_pose_id) AS pose_revision
              FROM ar_anchor a
             WHERE a.venue_id = :v AND a.organization_id = :o AND a.floor_id = :f AND a.deleted_at IS NULL
            """;

    /** The anchors of a floor as they were in version :sv: each one's pose of that version's lineage. */
    private static final String AS_OF_VERSION = """
            SELECT a.id, a.venue_id, a.floor_id, a.marker_type, a.marker_identifier, a.marker_size_m,
                   p.physical_x, p.physical_y, p.physical_z, p.physical_qx, p.physical_qy, p.physical_qz, p.physical_qw,
                   p.digital_x, p.digital_y, p.digital_z, p.digital_qx, p.digital_qy, p.digital_qz, p.digital_qw,
                   CASE WHEN p.calibrated_at IS NULL THEN 'UNCALIBRATED' ELSE 'CALIBRATED' END AS calibration_status,
                   p.calibrated_at AS last_calibrated_at, p.coordinate_frame_id, p.scan_version_id,
                   p.id AS pose_id, p.revision AS pose_revision
              FROM ar_anchor a
              JOIN ar_anchor_pose p ON p.id = scan_version_anchor_pose(a.id, :sv)
             WHERE a.venue_id = :v AND a.organization_id = :o AND a.floor_id = :f AND a.deleted_at IS NULL
            """;

    private final JdbcClient jdbc;
    private final TenantGuard guard;
    private final AuditService audit;
    private final CoordinateFrameService frames;
    private final FrameProperties frameProps;
    private final ScanVersionService versions;

    public AnchorService(JdbcClient jdbc, TenantGuard guard, AuditService audit, CoordinateFrameService frames,
                         FrameProperties frameProps, ScanVersionService versions) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
        this.frames = frames;
        this.frameProps = frameProps;
        this.versions = versions;
    }

    private static Anchor map(ResultSet rs, int i) throws SQLException {
        Pose physical = new Pose(rs.getDouble("physical_x"), rs.getDouble("physical_y"), rs.getDouble("physical_z"),
            rs.getDouble("physical_qx"), rs.getDouble("physical_qy"), rs.getDouble("physical_qz"), rs.getDouble("physical_qw"));
        Pose digital = new Pose(rs.getDouble("digital_x"), rs.getDouble("digital_y"), rs.getDouble("digital_z"),
            rs.getDouble("digital_qx"), rs.getDouble("digital_qy"), rs.getDouble("digital_qz"), rs.getDouble("digital_qw"));
        // PgJDBC cannot convert timestamptz to Instant directly (only to Timestamp/OffsetDateTime).
        java.sql.Timestamp calibrated = rs.getTimestamp("last_calibrated_at");
        Instant lastCalibratedAt = calibrated == null ? null : calibrated.toInstant();
        return new Anchor(rs.getObject("id", UUID.class), rs.getObject("venue_id", UUID.class),
            rs.getObject("floor_id", UUID.class), rs.getString("marker_type"), rs.getString("marker_identifier"),
            rs.getObject("marker_size_m") == null ? null : rs.getDouble("marker_size_m"), physical, digital, rs.getString("calibration_status"), lastCalibratedAt, rs.getObject("coordinate_frame_id", UUID.class),
            rs.getObject("scan_version_id", UUID.class), rs.getObject("pose_id", UUID.class), (Integer) rs.getObject("pose_revision"));
    }

    @Transactional(readOnly = true)
    public List<Anchor> list(Actor actor, UUID venueId, UUID floorId) {
        return list(actor, venueId, floorId, null);
    }

    /** scanVersionId null: the anchors as they are now. Otherwise as they were in that FINALIZED version of this floor; an
     * anchor with no pose in that version's lineage is not part of it. */
    @Transactional(readOnly = true)
    public List<Anchor> list(Actor actor, UUID venueId, UUID floorId, UUID scanVersionId) {
        guard.requireVenue(actor, venueId);
        requireFloor(venueId, floorId);
        if (scanVersionId == null) {
            return jdbc.sql(SELECT + " ORDER BY a.marker_identifier").param("v", venueId).param("o", actor.organizationId())
                .param("f", floorId).query(AnchorService::map).list();
        }
        ScanVersionService.Scope scope = versions.requireFinalized(actor, venueId, scanVersionId);
        if (!scope.floorId().equals(floorId)) {
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_WRONG_FLOOR",
                "scan version " + scope.id() + " is a version of floor " + scope.floorId() + ", not " + floorId);
        }
        return jdbc.sql(AS_OF_VERSION + " ORDER BY a.marker_identifier").param("v", venueId).param("o", actor.organizationId())
            .param("f", floorId).param("sv", scope.id()).query(AnchorService::map).list();
    }

    @Transactional(readOnly = true)
    public Anchor get(Actor actor, UUID venueId, UUID floorId, UUID anchorId) {
        guard.requireVenue(actor, venueId);
        return jdbc.sql(SELECT + " AND a.id = :a").param("v", venueId).param("o", actor.organizationId())
            .param("f", floorId).param("a", anchorId).query(AnchorService::map).optional()
            .orElseThrow(() -> new NotFoundException("anchor not found"));
    }

    @Transactional
    public Anchor create(Actor actor, UUID venueId, UUID floorId, AnchorRequest r) {
        guard.requireVenue(actor, venueId);
        requireFloor(venueId, floorId);
        requireMarkerType(r.markerType());
        requireMarkerSize(r);
        UUID frame = requireCurrentFrame(venueId, floorId).id();
        UUID version = versions.requireCurrent(actor, venueId, floorId).id();
        UUID id = UUID.randomUUID();
        UUID pose = UUID.randomUUID();
        // The anchor names its first pose before that row exists: the reference is checked at commit (V28).
        jdbc.sql("""
                INSERT INTO ar_anchor (id, organization_id, venue_id, floor_id, marker_type, marker_identifier, marker_size_m,
                    physical_x, physical_y, physical_z, physical_qx, physical_qy, physical_qz, physical_qw,
                    digital_x, digital_y, digital_z, digital_qx, digital_qy, digital_qz, digital_qw, coordinate_frame_id,
                    scan_version_id, current_pose_id)
                VALUES (:id, :o, :v, :f, :mt, :mi, :size, :px, :py, :pz, :pqx, :pqy, :pqz, :pqw, :dx, :dy, :dz, :dqx, :dqy, :dqz, :dqw,
                    :frame, :sv, :pose)
                """)
            .param("id", id).param("o", actor.organizationId()).param("v", venueId).param("f", floorId).param("frame", frame)
            .param("sv", version).param("pose", pose)
            .param("mt", r.markerType()).param("mi", r.markerIdentifier()).param("size", r.markerSizeMeters())
            .param("px", r.physicalPose().x()).param("py", r.physicalPose().y()).param("pz", r.physicalPose().z())
            .param("pqx", r.physicalPose().qx()).param("pqy", r.physicalPose().qy()).param("pqz", r.physicalPose().qz()).param("pqw", r.physicalPose().qw())
            .param("dx", r.digitalPose().x()).param("dy", r.digitalPose().y()).param("dz", r.digitalPose().z())
            .param("dqx", r.digitalPose().qx()).param("dqy", r.digitalPose().qy()).param("dqz", r.digitalPose().qz()).param("dqw", r.digitalPose().qw())
            .update();
        insertPose(actor, venueId, id, pose, 1, version, frame, r);
        audit.success(actor, venueId, "ar_anchor.create", "ar_anchor", id, Map.of("markerIdentifier", r.markerIdentifier(),
            "scanVersionId", version.toString(), "poseId", pose.toString()));
        return get(actor, venueId, floorId, id);
    }

    /** Any pose edit invalidates the previous calibration -- it is reset to UNCALIBRATED, never carried
     * forward, so a stale calibration can never be reported as current. The edit is a new pose revision against the
     * version the floor publishes now; the earlier revisions stay as they were. */
    @Transactional
    public Anchor update(Actor actor, UUID venueId, UUID floorId, UUID anchorId, AnchorRequest r) {
        guard.requireVenue(actor, venueId);
        requireMarkerType(r.markerType());
        requireMarkerSize(r);
        get(actor, venueId, floorId, anchorId);
        UUID frame = requireCurrentFrame(venueId, floorId).id();
        UUID version = versions.requireCurrent(actor, venueId, floorId).id();
        UUID pose = UUID.randomUUID();
        int revision = jdbc.sql("SELECT coalesce(max(revision), 0) + 1 FROM ar_anchor_pose WHERE anchor_id = :a")
            .param("a", anchorId).query(Integer.class).single();
        insertPose(actor, venueId, anchorId, pose, revision, version, frame, r);
        int rows = jdbc.sql("""
                UPDATE ar_anchor SET marker_type = :mt, marker_identifier = :mi, marker_size_m = :size, coordinate_frame_id = :frame,
                    scan_version_id = :sv, current_pose_id = :pose,
                    physical_x = :px, physical_y = :py, physical_z = :pz,
                    physical_qx = :pqx, physical_qy = :pqy, physical_qz = :pqz, physical_qw = :pqw,
                    digital_x = :dx, digital_y = :dy, digital_z = :dz,
                    digital_qx = :dqx, digital_qy = :dqy, digital_qz = :dqz, digital_qw = :dqw,
                    calibration_status = 'UNCALIBRATED', last_calibrated_at = NULL
                WHERE id = :a AND venue_id = :v AND floor_id = :f AND organization_id = :o AND deleted_at IS NULL
                """)
            .param("mt", r.markerType()).param("mi", r.markerIdentifier()).param("size", r.markerSizeMeters())
            .param("px", r.physicalPose().x()).param("py", r.physicalPose().y()).param("pz", r.physicalPose().z())
            .param("pqx", r.physicalPose().qx()).param("pqy", r.physicalPose().qy()).param("pqz", r.physicalPose().qz()).param("pqw", r.physicalPose().qw())
            .param("dx", r.digitalPose().x()).param("dy", r.digitalPose().y()).param("dz", r.digitalPose().z())
            .param("dqx", r.digitalPose().qx()).param("dqy", r.digitalPose().qy()).param("dqz", r.digitalPose().qz()).param("dqw", r.digitalPose().qw())
            .param("a", anchorId).param("v", venueId).param("f", floorId).param("o", actor.organizationId())
            .param("frame", frame).param("sv", version).param("pose", pose).update();
        if (rows == 0) {
            throw new NotFoundException("anchor not found");
        }
        audit.success(actor, venueId, "ar_anchor.update", "ar_anchor", anchorId, Map.of("scanVersionId", version.toString(),
            "poseId", pose.toString(), "poseRevision", revision));
        return get(actor, venueId, floorId, anchorId);
    }

    private void insertPose(Actor actor, UUID venueId, UUID anchorId, UUID poseId, int revision, UUID version, UUID frame,
                            AnchorRequest r) {
        jdbc.sql("""
                INSERT INTO ar_anchor_pose (id, organization_id, venue_id, anchor_id, revision, scan_version_id, coordinate_frame_id,
                    physical_x, physical_y, physical_z, physical_qx, physical_qy, physical_qz, physical_qw,
                    digital_x, digital_y, digital_z, digital_qx, digital_qy, digital_qz, digital_qw, source, created_by)
                VALUES (:id, :o, :v, :a, :rev, :sv, :frame, :px, :py, :pz, :pqx, :pqy, :pqz, :pqw, :dx, :dy, :dz, :dqx, :dqy, :dqz, :dqw,
                    'ENTERED', :by)
                """)
            .param("id", poseId).param("o", actor.organizationId()).param("v", venueId).param("a", anchorId).param("rev", revision)
            .param("sv", version).param("frame", frame).param("by", actor.subject())
            .param("px", r.physicalPose().x()).param("py", r.physicalPose().y()).param("pz", r.physicalPose().z())
            .param("pqx", r.physicalPose().qx()).param("pqy", r.physicalPose().qy()).param("pqz", r.physicalPose().qz()).param("pqw", r.physicalPose().qw())
            .param("dx", r.digitalPose().x()).param("dy", r.digitalPose().y()).param("dz", r.digitalPose().z())
            .param("dqx", r.digitalPose().qx()).param("dqy", r.digitalPose().qy()).param("dqz", r.digitalPose().qz()).param("dqw", r.digitalPose().qw())
            .update();
    }

    /** Marks an anchor CALIBRATED after an operator has physically verified its digital pose against the
     * built reconstruction (a workflow step, not something this call computes) -- see docs/ar.md. The verification is
     * recorded on the anchor's current pose revision, once. */
    @Transactional
    public Anchor calibrate(Actor actor, UUID venueId, UUID floorId, UUID anchorId) {
        guard.requireVenue(actor, venueId);
        Anchor anchor = get(actor, venueId, floorId, anchorId);
        requireInCurrentFrame(anchor, requireCurrentFrame(venueId, floorId));
        requireInCurrentVersion(anchor, versions.requireCurrent(actor, venueId, floorId));
        jdbc.sql("UPDATE ar_anchor_pose SET calibrated_at = now() WHERE id = :p AND calibrated_at IS NULL")
            .param("p", anchor.poseId()).update();
        int rows = jdbc.sql("""
                UPDATE ar_anchor SET calibration_status = 'CALIBRATED', last_calibrated_at = now()
                WHERE id = :a AND venue_id = :v AND floor_id = :f AND organization_id = :o AND deleted_at IS NULL
                """)
            .param("a", anchorId).param("v", venueId).param("f", floorId).param("o", actor.organizationId()).update();
        if (rows == 0) {
            throw new NotFoundException("anchor not found");
        }
        audit.success(actor, venueId, "ar_anchor.calibrate", "ar_anchor", anchorId, Map.of("poseId", anchor.poseId().toString()));
        return get(actor, venueId, floorId, anchorId);
    }

    @Transactional
    public void delete(Actor actor, UUID venueId, UUID floorId, UUID anchorId) {
        guard.requireVenue(actor, venueId);
        int rows = jdbc.sql("UPDATE ar_anchor SET deleted_at = now() WHERE id = :a AND venue_id = :v AND floor_id = :f "
                + "AND organization_id = :o AND deleted_at IS NULL")
            .param("a", anchorId).param("v", venueId).param("f", floorId).param("o", actor.organizationId()).update();
        if (rows == 0) {
            throw new NotFoundException("anchor not found");
        }
        audit.success(actor, venueId, "ar_anchor.delete", "ar_anchor", anchorId, Map.of());
    }

    @Transactional(readOnly = true)
    public RelocalizationResponse relocalize(Actor actor, UUID venueId, UUID floorId, List<AnchorObservation> observations) {
        return relocalize(actor, venueId, floorId, observations, null);
    }

    /**
     * Solves the device-frame -> venue-frame transform from the marker observations an AR client reports
     * during relocalization. Every anchor referenced must belong to this venue/floor (loaded through the
     * same tenant-scoped query as everything else here), must be CALIBRATED -- an uncalibrated anchor's
     * digital pose has not been verified against the reconstruction, so it is refused rather than silently
     * trusted -- and must have been entered against the version the floor publishes or an ancestor of it. See
     * {@link CoordinateTransform} for the actual math and why the reported residual is a real measurement, not a
     * claimed accuracy figure. clientVersionId: the version the client shows, if it says; anything but the published
     * version is refused.
     */
    @Transactional(readOnly = true)
    public RelocalizationResponse relocalize(Actor actor, UUID venueId, UUID floorId, List<AnchorObservation> observations,
                                             UUID clientVersionId) {
        guard.requireVenue(actor, venueId);
        if (observations == null || observations.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NO_OBSERVATIONS", "at least one anchor observation is required");
        }
        if (observations.size() > MAX_OBSERVATIONS
                || observations.stream().map(AnchorObservation::anchorId).distinct().count() != observations.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_OBSERVATIONS",
                "at most " + MAX_OBSERVATIONS + " observations, each of a different anchor");
        }
        FrameView current = requireCurrentFrame(venueId, floorId);
        ScanVersionService.Scope version = versions.requireCurrent(actor, venueId, floorId);
        if (clientVersionId != null && !clientVersionId.equals(version.id())) {
            throw new ApiException(HttpStatus.CONFLICT, ScanVersionService.VERSION_MISMATCH, "the client shows scan version "
                + clientVersionId + " but floor " + floorId + " publishes " + version.id() + "; reload the current version");
        }
        List<Pose> candidates = new ArrayList<>();
        double tilt = 0;
        for (AnchorObservation obs : observations) {
            Anchor anchor = get(actor, venueId, floorId, obs.anchorId());
            if (!"CALIBRATED".equals(anchor.calibrationStatus())) {
                throw new ApiException(HttpStatus.CONFLICT, "ANCHOR_NOT_CALIBRATED",
                    "anchor " + anchor.id() + " has not been calibrated and cannot be used for relocalization");
            }
            requireInCurrentFrame(anchor, current);
            requireInCurrentVersion(anchor, version);
            Pose candidate = CoordinateTransform.deviceToVenueFromAnchor(anchor.digitalPose(), obs.observedPose());
            tilt = Math.max(tilt, ArDeviceFrame.gravityTiltDegrees(candidate));
            candidates.add(candidate);
        }
        if (tilt > frameProps.maxDeviceGravityTiltDeg()) {
            throw new ApiException(HttpStatus.CONFLICT, "RELOCALIZATION_GRAVITY_MISMATCH", "the solved transform tilts the device's "
                + "gravity-aligned up by " + Math.round(tilt * 10) / 10.0 + " degrees from the venue's +Z (limit "
                + frameProps.maxDeviceGravityTiltDeg() + "); an anchor's digital orientation or the observation's axis convention ("
                + ArDeviceFrame.CONVENTION + ") is wrong");
        }
        CoordinateTransform.Blended blended = CoordinateTransform.blend(candidates);
        // One anchor gives nothing to compare against: its residual is unknown, not zero.
        Double residual = candidates.size() == 1 ? null : blended.residualMeters();
        return new RelocalizationResponse(blended.transform(), residual, candidates.size(),
            ArDeviceFrame.gravityTiltDegrees(blended.transform()), ArDeviceFrame.CONVENTION, current.id(), version.id());
    }

    private static final int MAX_OBSERVATIONS = 16;

    private FrameView requireCurrentFrame(UUID venueId, UUID floorId) {
        return frames.currentForFloor(venueId, floorId).filter(FrameView::canonical)
            .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, CoordinateFrameService.NOT_CALIBRATED,
                "floor " + floorId + " has no calibrated coordinate frame; anchor poses cannot be expressed in venue metres"));
    }

    private static void requireInCurrentFrame(Anchor anchor, FrameView current) {
        if (!current.id().equals(anchor.coordinateFrameId())) {
            throw new ApiException(HttpStatus.CONFLICT, "ANCHOR_FRAME_STALE", "anchor " + anchor.id() + " was placed in coordinate frame "
                + anchor.coordinateFrameId() + ", not the floor's current frame " + current.id() + "; re-enter its digital pose");
        }
    }

    /** The anchor's pose was entered against the published version or one of its ancestors (a re-scan keeps its parent's
     * reconstruction frame, so its anchors stay valid). */
    private void requireInCurrentVersion(Anchor anchor, ScanVersionService.Scope version) {
        if (anchor.scanVersionId() == null || anchor.poseId() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "ANCHOR_UNVERSIONED", "anchor " + anchor.id() + " was never entered against "
                + "a scan version, so nothing ties its pose to the reconstruction shown; re-enter its digital pose");
        }
        boolean inLineage = jdbc.sql("SELECT EXISTS (SELECT 1 FROM scan_version_lineage(:cur) WHERE id = :sv)")
            .param("cur", version.id()).param("sv", anchor.scanVersionId()).query(Boolean.class).single();
        if (!inLineage) {
            throw new ApiException(HttpStatus.CONFLICT, "ANCHOR_VERSION_MISMATCH", "anchor " + anchor.id() + " was entered against "
                + "scan version " + anchor.scanVersionId() + ", which is not (an ancestor of) the published version " + version.id()
                + "; re-enter its digital pose");
        }
    }

    private void requireFloor(UUID venueId, UUID floorId) {
        Integer count = jdbc.sql("SELECT count(*) FROM floor WHERE id = :f AND venue_id = :v AND deleted_at IS NULL")
            .param("f", floorId).param("v", venueId).query(Integer.class).single();
        if (count == 0) {
            throw new NotFoundException("floor not found");
        }
    }

    /** An IMAGE_TARGET is only trackable when its printed width is known (docs/ar.md); any given size must be physical. */
    private static void requireMarkerSize(AnchorRequest r) {
        Double size = r.markerSizeMeters();
        if ("IMAGE_TARGET".equals(r.markerType()) && size == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MARKER_SIZE_REQUIRED",
                "an IMAGE_TARGET anchor needs markerSizeMeters: the printed width of its target image, in metres");
        }
        if (size != null && !(size > 0 && size <= 5)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_MARKER_SIZE", "markerSizeMeters must be in (0, 5] metres");
        }
    }

    /** The printable, trackable target image of an IMAGE_TARGET anchor (ImageTarget). Other marker types have none. */
    @Transactional(readOnly = true)
    public byte[] targetImage(Actor actor, UUID venueId, UUID floorId, UUID anchorId) {
        Anchor anchor = get(actor, venueId, floorId, anchorId);
        if (!"IMAGE_TARGET".equals(anchor.markerType())) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_AN_IMAGE_TARGET",
                "anchor " + anchorId + " is a " + anchor.markerType() + "; only IMAGE_TARGET anchors have a generated target image");
        }
        return ImageTarget.png(anchor.id());
    }

    private static void requireMarkerType(String markerType) {
        if (!Set.of("QR_CODE", "ARUCO_MARKER", "IMAGE_TARGET", "APRILTAG").contains(markerType)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_MARKER_TYPE",
                "markerType must be one of QR_CODE, ARUCO_MARKER, IMAGE_TARGET, APRILTAG");
        }
    }
}
