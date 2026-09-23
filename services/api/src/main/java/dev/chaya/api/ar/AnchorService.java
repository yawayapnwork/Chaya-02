package dev.chaya.api.ar;

import dev.chaya.api.ar.ArDtos.Anchor;
import dev.chaya.api.ar.ArDtos.AnchorObservation;
import dev.chaya.api.ar.ArDtos.AnchorRequest;
import dev.chaya.api.ar.ArDtos.Pose;
import dev.chaya.api.ar.ArDtos.RelocalizationResponse;
import dev.chaya.api.audit.AuditService;
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
 */
@Service
public class AnchorService {

    private static final String SELECT = """
            SELECT id, venue_id, floor_id, marker_type, marker_identifier,
                   physical_x, physical_y, physical_z, physical_qx, physical_qy, physical_qz, physical_qw,
                   digital_x, digital_y, digital_z, digital_qx, digital_qy, digital_qz, digital_qw,
                   calibration_status, last_calibrated_at
              FROM ar_anchor
             WHERE venue_id = :v AND organization_id = :o AND floor_id = :f AND deleted_at IS NULL
            """;

    private final JdbcClient jdbc;
    private final TenantGuard guard;
    private final AuditService audit;

    public AnchorService(JdbcClient jdbc, TenantGuard guard, AuditService audit) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
    }

    private static Anchor map(ResultSet rs, int i) throws SQLException {
        Pose physical = new Pose(rs.getDouble("physical_x"), rs.getDouble("physical_y"), rs.getDouble("physical_z"),
            rs.getDouble("physical_qx"), rs.getDouble("physical_qy"), rs.getDouble("physical_qz"), rs.getDouble("physical_qw"));
        Pose digital = new Pose(rs.getDouble("digital_x"), rs.getDouble("digital_y"), rs.getDouble("digital_z"),
            rs.getDouble("digital_qx"), rs.getDouble("digital_qy"), rs.getDouble("digital_qz"), rs.getDouble("digital_qw"));
        Instant lastCalibratedAt = rs.getObject("last_calibrated_at", Instant.class);
        return new Anchor(rs.getObject("id", UUID.class), rs.getObject("venue_id", UUID.class),
            rs.getObject("floor_id", UUID.class), rs.getString("marker_type"), rs.getString("marker_identifier"),
            physical, digital, rs.getString("calibration_status"), lastCalibratedAt);
    }

    @Transactional(readOnly = true)
    public List<Anchor> list(Actor actor, UUID venueId, UUID floorId) {
        guard.requireVenue(actor, venueId);
        requireFloor(venueId, floorId);
        return jdbc.sql(SELECT + " ORDER BY marker_identifier").param("v", venueId).param("o", actor.organizationId())
            .param("f", floorId).query(AnchorService::map).list();
    }

    @Transactional(readOnly = true)
    public Anchor get(Actor actor, UUID venueId, UUID floorId, UUID anchorId) {
        guard.requireVenue(actor, venueId);
        return jdbc.sql(SELECT + " AND id = :a").param("v", venueId).param("o", actor.organizationId())
            .param("f", floorId).param("a", anchorId).query(AnchorService::map).optional()
            .orElseThrow(() -> new NotFoundException("anchor not found"));
    }

    @Transactional
    public Anchor create(Actor actor, UUID venueId, UUID floorId, AnchorRequest r) {
        guard.requireVenue(actor, venueId);
        requireFloor(venueId, floorId);
        requireMarkerType(r.markerType());
        UUID id = jdbc.sql("""
                INSERT INTO ar_anchor (organization_id, venue_id, floor_id, marker_type, marker_identifier,
                    physical_x, physical_y, physical_z, physical_qx, physical_qy, physical_qz, physical_qw,
                    digital_x, digital_y, digital_z, digital_qx, digital_qy, digital_qz, digital_qw)
                VALUES (:o, :v, :f, :mt, :mi, :px, :py, :pz, :pqx, :pqy, :pqz, :pqw, :dx, :dy, :dz, :dqx, :dqy, :dqz, :dqw)
                RETURNING id
                """)
            .param("o", actor.organizationId()).param("v", venueId).param("f", floorId)
            .param("mt", r.markerType()).param("mi", r.markerIdentifier())
            .param("px", r.physicalPose().x()).param("py", r.physicalPose().y()).param("pz", r.physicalPose().z())
            .param("pqx", r.physicalPose().qx()).param("pqy", r.physicalPose().qy()).param("pqz", r.physicalPose().qz()).param("pqw", r.physicalPose().qw())
            .param("dx", r.digitalPose().x()).param("dy", r.digitalPose().y()).param("dz", r.digitalPose().z())
            .param("dqx", r.digitalPose().qx()).param("dqy", r.digitalPose().qy()).param("dqz", r.digitalPose().qz()).param("dqw", r.digitalPose().qw())
            .query(UUID.class).single();
        audit.success(actor, venueId, "ar_anchor.create", "ar_anchor", id, Map.of("markerIdentifier", r.markerIdentifier()));
        return get(actor, venueId, floorId, id);
    }

    /** Any pose edit invalidates the previous calibration -- it is reset to UNCALIBRATED, never carried
     * forward, so a stale calibration can never be reported as current. */
    @Transactional
    public Anchor update(Actor actor, UUID venueId, UUID floorId, UUID anchorId, AnchorRequest r) {
        guard.requireVenue(actor, venueId);
        requireMarkerType(r.markerType());
        int rows = jdbc.sql("""
                UPDATE ar_anchor SET marker_type = :mt, marker_identifier = :mi,
                    physical_x = :px, physical_y = :py, physical_z = :pz,
                    physical_qx = :pqx, physical_qy = :pqy, physical_qz = :pqz, physical_qw = :pqw,
                    digital_x = :dx, digital_y = :dy, digital_z = :dz,
                    digital_qx = :dqx, digital_qy = :dqy, digital_qz = :dqz, digital_qw = :dqw,
                    calibration_status = 'UNCALIBRATED', last_calibrated_at = NULL
                WHERE id = :a AND venue_id = :v AND floor_id = :f AND organization_id = :o AND deleted_at IS NULL
                """)
            .param("mt", r.markerType()).param("mi", r.markerIdentifier())
            .param("px", r.physicalPose().x()).param("py", r.physicalPose().y()).param("pz", r.physicalPose().z())
            .param("pqx", r.physicalPose().qx()).param("pqy", r.physicalPose().qy()).param("pqz", r.physicalPose().qz()).param("pqw", r.physicalPose().qw())
            .param("dx", r.digitalPose().x()).param("dy", r.digitalPose().y()).param("dz", r.digitalPose().z())
            .param("dqx", r.digitalPose().qx()).param("dqy", r.digitalPose().qy()).param("dqz", r.digitalPose().qz()).param("dqw", r.digitalPose().qw())
            .param("a", anchorId).param("v", venueId).param("f", floorId).param("o", actor.organizationId())
            .update();
        if (rows == 0) {
            throw new NotFoundException("anchor not found");
        }
        audit.success(actor, venueId, "ar_anchor.update", "ar_anchor", anchorId, Map.of());
        return get(actor, venueId, floorId, anchorId);
    }

    /** Marks an anchor CALIBRATED after an operator has physically verified its digital pose against the
     * built reconstruction (a workflow step, not something this call computes) -- see docs/ar.md. */
    @Transactional
    public Anchor calibrate(Actor actor, UUID venueId, UUID floorId, UUID anchorId) {
        guard.requireVenue(actor, venueId);
        int rows = jdbc.sql("""
                UPDATE ar_anchor SET calibration_status = 'CALIBRATED', last_calibrated_at = now()
                WHERE id = :a AND venue_id = :v AND floor_id = :f AND organization_id = :o AND deleted_at IS NULL
                """)
            .param("a", anchorId).param("v", venueId).param("f", floorId).param("o", actor.organizationId()).update();
        if (rows == 0) {
            throw new NotFoundException("anchor not found");
        }
        audit.success(actor, venueId, "ar_anchor.calibrate", "ar_anchor", anchorId, Map.of());
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

    /**
     * Solves the device-frame -> venue-frame transform from the marker observations an AR client reports
     * during relocalization. Every anchor referenced must belong to this venue/floor (loaded through the
     * same tenant-scoped query as everything else here) and must be CALIBRATED -- an uncalibrated anchor's
     * digital pose has not been verified against the reconstruction, so it is refused rather than silently
     * trusted. See {@link CoordinateTransform} for the actual math and why the reported residual is a real
     * measurement, not a claimed accuracy figure.
     */
    @Transactional(readOnly = true)
    public RelocalizationResponse relocalize(Actor actor, UUID venueId, UUID floorId, List<AnchorObservation> observations) {
        guard.requireVenue(actor, venueId);
        if (observations == null || observations.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NO_OBSERVATIONS", "at least one anchor observation is required");
        }
        List<Pose> candidates = new ArrayList<>();
        for (AnchorObservation obs : observations) {
            Anchor anchor = get(actor, venueId, floorId, obs.anchorId());
            if (!"CALIBRATED".equals(anchor.calibrationStatus())) {
                throw new ApiException(HttpStatus.CONFLICT, "ANCHOR_NOT_CALIBRATED",
                    "anchor " + anchor.id() + " has not been calibrated and cannot be used for relocalization");
            }
            candidates.add(CoordinateTransform.deviceToVenueFromAnchor(anchor.digitalPose(), obs.observedPose()));
        }
        CoordinateTransform.Blended blended = CoordinateTransform.blend(candidates);
        return new RelocalizationResponse(blended.transform(), blended.residualMeters(), candidates.size());
    }

    private void requireFloor(UUID venueId, UUID floorId) {
        Integer count = jdbc.sql("SELECT count(*) FROM floor WHERE id = :f AND venue_id = :v AND deleted_at IS NULL")
            .param("f", floorId).param("v", venueId).query(Integer.class).single();
        if (count == 0) {
            throw new NotFoundException("floor not found");
        }
    }

    private static void requireMarkerType(String markerType) {
        if (!Set.of("QR_CODE", "ARUCO_MARKER", "IMAGE_TARGET", "APRILTAG").contains(markerType)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_MARKER_TYPE",
                "markerType must be one of QR_CODE, ARUCO_MARKER, IMAGE_TARGET, APRILTAG");
        }
    }
}
