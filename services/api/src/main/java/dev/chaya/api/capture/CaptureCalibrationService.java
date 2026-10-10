package dev.chaya.api.capture;

import dev.chaya.api.audit.AuditService;
import dev.chaya.api.capture.CaptureCalibrationDtos.AttemptView;
import dev.chaya.api.capture.CaptureCalibrationDtos.CalibrateCapture;
import dev.chaya.api.capture.CaptureCalibrationDtos.CalibrationStatus;
import dev.chaya.api.capture.CaptureCalibrationDtos.MeasurementInput;
import dev.chaya.api.capture.CaptureCalibrationDtos.MeasurementView;
import dev.chaya.api.capture.CaptureCalibrationDtos.ObservationInput;
import dev.chaya.api.capture.CaptureCalibrationDtos.ObservationView;
import dev.chaya.api.capture.CaptureCalibrationDtos.ResolvedPoint;
import dev.chaya.api.capture.CaptureService.CaptureView;
import dev.chaya.api.frame.CoordinateFrameService;
import dev.chaya.api.frame.FrameDtos.CalibrationRequest;
import dev.chaya.api.frame.FrameDtos.ControlPoint;
import dev.chaya.api.frame.FrameDtos.DistanceReference;
import dev.chaya.api.frame.FrameDtos.FrameView;
import dev.chaya.api.frame.FrameProperties;
import dev.chaya.api.security.Actor;
import dev.chaya.api.security.Role;
import dev.chaya.api.web.ApiException;
import dev.chaya.api.web.NotFoundException;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Calibration evidence recorded with a capture (docs/capture-calibration.md): measured distances and surveyed control
 * points, each tied to the capture, its floor, its unit and the pixels where its physical points are seen.
 *
 * <p>Evidence is not a calibration. A capture's reconstruction has an arbitrary SfM scale until a coordinate frame has
 * been computed from the evidence and the reconstruction coordinates of the measured points (CoordinateFrameService).
 * Locating those points in the reconstruction from the recorded pixel observations (triangulation through the
 * reconstructed camera poses) is not implemented: the operator supplies the reconstruction coordinates, and the status
 * says so.
 */
@Service
public class CaptureCalibrationService {

    /** A measured reference shorter than this cannot constrain scale to a few per cent with a tape or laser meter. */
    static final double MIN_DISTANCE_M = 0.05;
    /** Longer than any single indoor measurement; a larger value is a unit mistake. */
    static final double MAX_DISTANCE_M = 200.0;
    /** Survey coordinates further than this from their datum origin are not a building survey. */
    static final double MAX_VENUE_COORDINATE_M = 100_000.0;
    /** Two control points closer than this are the same mark measured twice. */
    static final double MIN_CONTROL_POINT_SEPARATION_M = 0.1;
    /** Control points within this distance of one line do not fix a rotation about it. */
    static final double MIN_CONTROL_POINT_SPREAD_M = 0.5;
    /** The two ends of a distance this close in the same picture cannot both be located. */
    static final double MIN_ENDPOINT_PIXEL_SEPARATION = 2.0;
    static final int MIN_VIEWS_PER_POINT = 2;
    static final int MAX_OBSERVATIONS = 50;
    static final int REQUIRED_CONTROL_POINTS = 3;
    private static final Map<String, Double> UNIT_METRES = Map.of("m", 1.0, "cm", 0.01, "mm", 0.001, "ft", 0.3048, "in", 0.0254);
    private static final Set<String> METHODS = Set.of("TAPE", "LASER_DISTANCE_METER", "TOTAL_STATION", "SURVEY_PLAN", "OTHER");

    private static final String MEASUREMENT_COLUMNS = """
        m.id, m.capture_session_id, m.floor_id, m.kind, m.label, m.method, m.unit, m.measured_value, m.measured_metres, m.datum,
        m.venue_x, m.venue_y, m.venue_z, m.venue_x_m, m.venue_y_m, m.venue_z_m, m.uncertainty_metres, m.status,
        m.withdrawn_reason, m.withdrawn_by, m.withdrawn_at, m.created_by, m.created_at,
        EXISTS (SELECT 1 FROM coordinate_frame_measurement fm JOIN coordinate_frame f ON f.id = fm.coordinate_frame_id
                 WHERE fm.measurement_id = m.id AND f.status = 'ACTIVE') AS used""";

    private final JdbcClient jdbc;
    private final CaptureService captures;
    private final CoordinateFrameService frames;
    private final FrameProperties frameProps;
    private final AuditService audit;
    private final TransactionTemplate tx;

    public CaptureCalibrationService(JdbcClient jdbc, CaptureService captures, CoordinateFrameService frames,
                                     FrameProperties frameProps, AuditService audit, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.captures = captures;
        this.frames = frames;
        this.frameProps = frameProps;
        this.audit = audit;
        this.tx = tx;
    }

    // =========================================================================================
    // Measurements
    // =========================================================================================

    public MeasurementView record(Actor actor, UUID venueId, UUID captureId, MeasurementInput in) {
        CaptureView c = captures.get(actor, venueId, captureId);
        requireWriter(actor, venueId, c);
        if (c.status() == CaptureStatus.FAILED) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_CAPTURE_STATE",
                "capture has FAILED (" + c.failureCode() + "); it takes no calibration evidence. Record it on a new capture.");
        }
        if (c.floorId() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "FLOOR_REQUIRED",
                "this capture was created without a floor; calibration evidence belongs to one floor's coordinate frame. "
                    + "Create the capture for its floor and record the measurements there.");
        }
        if (in == null) {
            throw invalid("INVALID_MEASUREMENT", "a measurement body is required");
        }
        String kind = upper(in.kind());
        if (!"DISTANCE".equals(kind) && !"CONTROL_POINT".equals(kind)) {
            throw invalid("INVALID_MEASUREMENT", "kind must be DISTANCE or CONTROL_POINT");
        }
        String label = in.label() == null ? "" : in.label().strip();
        if (label.isEmpty() || label.length() > 120) {
            throw invalid("INVALID_MEASUREMENT", "label is required (1-120 characters), e.g. \"door 2.14 width\" or \"CP-3\"");
        }
        String method = upper(in.method());
        if (!METHODS.contains(method)) {
            throw invalid("INVALID_MEASUREMENT", "method must be one of " + new java.util.TreeSet<>(METHODS) + " (how it was measured)");
        }
        if (in.unit() == null || in.unit().isBlank()) {
            throw invalid("MISSING_UNIT", "unit is required: m, cm, mm, ft or in. Values are never assumed to be metres.");
        }
        String unit = in.unit().strip().toLowerCase(Locale.ROOT);
        Double perUnit = UNIT_METRES.get(unit);
        if (perUnit == null) {
            throw invalid("INVALID_UNIT", "unit '" + in.unit() + "' is not supported; use m, cm, mm, ft or in");
        }
        Double uncertaintyM = null;
        if (in.uncertainty() != null) {
            if (!Double.isFinite(in.uncertainty()) || in.uncertainty() <= 0) {
                throw invalid("INVALID_MEASUREMENT", "uncertainty, when given, must be a positive number in " + unit);
            }
            uncertaintyM = in.uncertainty() * perUnit;
        }
        boolean labelTaken = jdbc.sql("SELECT count(*) FROM capture_measurement WHERE capture_session_id = :c AND status = 'ACTIVE' "
                + "AND lower(label) = lower(:l)").param("c", captureId).param("l", label).query(Integer.class).single() > 0;
        if (labelTaken) {
            throw new ApiException(HttpStatus.CONFLICT, "DUPLICATE_MEASUREMENT",
                "this capture already has an active measurement labelled \"" + label + "\"; withdraw it or use another label");
        }

        Double valueM = null;
        double[] venueM = null;
        String datum = null;
        if (kind.equals("DISTANCE")) {
            if (in.venue() != null || in.datum() != null) {
                throw invalid("INCONSISTENT_MEASUREMENT", "a DISTANCE has a value, not venue coordinates or a datum");
            }
            if (in.value() == null) {
                throw invalid("MISSING_MEASUREMENT", "value is required: the measured length between points A and B, in " + unit);
            }
            if (!Double.isFinite(in.value()) || in.value() <= 0) {
                throw invalid("INVALID_MEASUREMENT", "value must be a positive, finite length");
            }
            valueM = in.value() * perUnit;
            if (valueM < MIN_DISTANCE_M || valueM > MAX_DISTANCE_M) {
                throw invalid("MEASUREMENT_OUT_OF_RANGE", in.value() + " " + unit + " is " + round(valueM) + " m; a measured "
                    + "indoor reference must be between " + MIN_DISTANCE_M + " m and " + MAX_DISTANCE_M + " m. Check the unit.");
            }
            if (uncertaintyM != null && uncertaintyM > frameProps.maxScaleRelativeSpread() * valueM) {
                throw invalid("MEASUREMENT_TOO_UNCERTAIN", "an uncertainty of " + round(uncertaintyM) + " m is more than "
                    + round(100 * frameProps.maxScaleRelativeSpread()) + " % of " + round(valueM) + " m, the most a calibration "
                    + "tolerates; measure a longer reference or measure more precisely");
            }
        } else {
            if (in.value() != null) {
                throw invalid("INCONSISTENT_MEASUREMENT", "a CONTROL_POINT has venue coordinates, not a value");
            }
            if (in.venue() == null) {
                throw invalid("MISSING_MEASUREMENT", "venue is required: the surveyed x, y, z of point P in " + unit + ", +Z up");
            }
            if (in.venue().size() != 3 || in.venue().stream().anyMatch(d -> d == null || !Double.isFinite(d))) {
                throw invalid("INVALID_MEASUREMENT", "venue must be three finite numbers (x, y, z; +Z up)");
            }
            if (in.datum() == null || in.datum().isBlank() || in.datum().strip().length() > 120) {
                throw invalid("MISSING_DATUM", "datum is required: the name of the survey the coordinates are in (1-120 characters)");
            }
            datum = in.datum().strip();
            venueM = new double[]{in.venue().get(0) * perUnit, in.venue().get(1) * perUnit, in.venue().get(2) * perUnit};
            for (double v : venueM) {
                if (Math.abs(v) > MAX_VENUE_COORDINATE_M) {
                    throw invalid("MEASUREMENT_OUT_OF_RANGE", "venue coordinates must be within " + MAX_VENUE_COORDINATE_M
                        + " m of the survey origin; check the unit");
                }
            }
            checkAgainstOtherControlPoints(captureId, unit, datum, venueM);
        }

        List<Obs> observations = validateObservations(captureId, kind, in.observations());

        final Double value = valueM;
        final double[] venueMetres = venueM;
        final String datumName = datum;
        final Double uncertainty = uncertaintyM;
        UUID id = tx.execute(s -> {
            UUID created = jdbc.sql("""
                    INSERT INTO capture_measurement (organization_id, venue_id, capture_session_id, floor_id, kind, label, method, unit,
                        measured_value, measured_metres, datum, venue_x, venue_y, venue_z, venue_x_m, venue_y_m, venue_z_m,
                        uncertainty_metres, created_by)
                    VALUES (:o, :v, :c, :f, :k, :l, :m, :u, :mv, :mm, :d, :vx, :vy, :vz, :vxm, :vym, :vzm, :unc, :by)
                    RETURNING id
                    """)
                .param("o", actor.organizationId()).param("v", venueId).param("c", captureId).param("f", c.floorId())
                .param("k", kind).param("l", label).param("m", method).param("u", unit)
                .param("mv", kind.equals("DISTANCE") ? in.value() : null).param("mm", value).param("d", datumName)
                .param("vx", venueMetres == null ? null : in.venue().get(0)).param("vy", venueMetres == null ? null : in.venue().get(1))
                .param("vz", venueMetres == null ? null : in.venue().get(2))
                .param("vxm", venueMetres == null ? null : venueMetres[0]).param("vym", venueMetres == null ? null : venueMetres[1])
                .param("vzm", venueMetres == null ? null : venueMetres[2]).param("unc", uncertainty).param("by", actor.subject())
                .query(UUID.class).single();
            for (Obs o : observations) {
                jdbc.sql("""
                        INSERT INTO capture_measurement_observation (organization_id, venue_id, capture_session_id, measurement_id, point,
                            media_id, frame_time_seconds, pixel_u, pixel_v)
                        VALUES (:o, :v, :c, :m, :p, :media, :t, :u, :pv)
                        """)
                    .param("o", actor.organizationId()).param("v", venueId).param("c", captureId).param("m", created)
                    .param("p", o.point()).param("media", o.mediaId()).param("t", o.time()).param("u", o.u()).param("pv", o.v())
                    .update();
            }
            audit.success(actor, venueId, "capture.measurement.record", "capture_measurement", created,
                Map.of("captureId", captureId.toString(), "kind", kind, "observations", observations.size()));
            return created;
        });
        return measurement(captureId, id);
    }

    public List<MeasurementView> list(Actor actor, UUID venueId, UUID captureId) {
        captures.get(actor, venueId, captureId);
        return measurements(captureId, false);
    }

    public MeasurementView withdraw(Actor actor, UUID venueId, UUID captureId, UUID measurementId, String reason) {
        CaptureView c = captures.get(actor, venueId, captureId);
        requireWriter(actor, venueId, c);
        MeasurementView m = measurement(captureId, measurementId);
        if (!m.status().equals("ACTIVE")) {
            throw new ApiException(HttpStatus.CONFLICT, "MEASUREMENT_WITHDRAWN", "measurement is already withdrawn");
        }
        if (m.usedByActiveFrame()) {
            throw new ApiException(HttpStatus.CONFLICT, "MEASUREMENT_IN_USE",
                "the active coordinate frame was computed from this measurement; recalibrate without it first");
        }
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw invalid("INVALID_MEASUREMENT", "a reason (1-500 characters) is required to withdraw a measurement");
        }
        tx.executeWithoutResult(s -> {
            int rows = jdbc.sql("UPDATE capture_measurement SET status = 'WITHDRAWN', withdrawn_reason = :r, withdrawn_by = :by, "
                    + "withdrawn_at = now() WHERE id = :id AND capture_session_id = :c AND status = 'ACTIVE'")
                .param("r", reason.strip()).param("by", actor.subject()).param("id", measurementId).param("c", captureId).update();
            if (rows == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "MEASUREMENT_WITHDRAWN", "measurement changed concurrently");
            }
            audit.success(actor, venueId, "capture.measurement.withdraw", "capture_measurement", measurementId,
                Map.of("captureId", captureId.toString()));
        });
        return measurement(captureId, measurementId);
    }

    private record Obs(String point, UUID mediaId, Double time, double u, double v) {}

    private record MediaInfo(String kind, String status, Integer width, Integer height) {}

    private List<Obs> validateObservations(UUID captureId, String kind, List<ObservationInput> input) {
        Set<String> points = kind.equals("DISTANCE") ? Set.of("A", "B") : Set.of("P");
        String pointNames = kind.equals("DISTANCE") ? "A and B" : "P";
        if (input == null || input.isEmpty()) {
            throw invalid("INSUFFICIENT_OBSERVATIONS", "observations are required: each of " + pointNames + " must be marked in at "
                + "least " + MIN_VIEWS_PER_POINT + " different images or video frames of this capture, so it can be located");
        }
        if (input.size() > MAX_OBSERVATIONS) {
            throw invalid("INVALID_OBSERVATION", "at most " + MAX_OBSERVATIONS + " observations per measurement");
        }
        Map<UUID, MediaInfo> media = new HashMap<>();
        List<Obs> out = new ArrayList<>();
        for (ObservationInput o : input) {
            if (o == null) {
                throw invalid("INVALID_OBSERVATION", "an observation is null");
            }
            String point = upper(o.point());
            if (!points.contains(point)) {
                throw invalid("INVALID_OBSERVATION", "a " + kind + " observation's point must be " + pointNames.replace(" and ", " or "));
            }
            if (o.mediaId() == null) {
                throw invalid("INVALID_OBSERVATION", "every observation names the mediaId it was marked in");
            }
            MediaInfo m = media.computeIfAbsent(o.mediaId(), id -> jdbc.sql(
                    "SELECT kind, status, pixel_width, pixel_height FROM capture_media WHERE id = :m AND capture_session_id = :c")
                .param("m", id).param("c", captureId)
                .query((rs, i) -> new MediaInfo(rs.getString(1), rs.getString(2), (Integer) rs.getObject(3), (Integer) rs.getObject(4)))
                .optional().orElse(null));
            if (m == null) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "MEDIA_NOT_OBSERVABLE",
                    "media " + o.mediaId() + " is not part of this capture");
            }
            if (!m.status().equals("ACCEPTED") || m.kind().equals("METADATA")) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "MEDIA_NOT_OBSERVABLE",
                    "media " + o.mediaId() + " is a " + m.status() + " " + m.kind() + "; points are marked in ACCEPTED images or videos only");
            }
            if (o.u() == null || o.v() == null || !Double.isFinite(o.u()) || !Double.isFinite(o.v()) || o.u() < 0 || o.v() < 0) {
                throw invalid("INVALID_OBSERVATION", "u and v must be non-negative pixel coordinates");
            }
            if (m.width() != null && (o.u() >= m.width() || o.v() >= m.height())) {
                throw invalid("INVALID_OBSERVATION", "pixel (" + o.u() + ", " + o.v() + ") is outside the " + m.width() + "x"
                    + m.height() + " image " + o.mediaId());
            }
            Double time = o.frameTimeSeconds();
            if (m.kind().equals("VIDEO")) {
                if (time == null || !Double.isFinite(time) || time < 0) {
                    throw invalid("INVALID_OBSERVATION", "an observation in video " + o.mediaId()
                        + " needs frameTimeSeconds, the non-negative time of the frame it was marked in");
                }
            } else if (time != null) {
                throw invalid("INVALID_OBSERVATION", "frameTimeSeconds applies to videos only; " + o.mediaId() + " is an image");
            }
            out.add(new Obs(point, o.mediaId(), time, o.u(), o.v()));
        }
        // Views: one image, or one frame of one video.
        Map<String, Map<String, Obs>> viewsByPoint = new LinkedHashMap<>();
        for (Obs o : out) {
            String view = o.mediaId() + "@" + (o.time() == null ? "" : o.time());
            if (viewsByPoint.computeIfAbsent(o.point(), p -> new LinkedHashMap<>()).put(view, o) != null) {
                throw invalid("INVALID_OBSERVATION", "point " + o.point() + " is marked twice in the same view " + view);
            }
        }
        for (String p : points.stream().sorted().toList()) {
            int views = viewsByPoint.getOrDefault(p, Map.of()).size();
            if (views < MIN_VIEWS_PER_POINT) {
                throw invalid("INSUFFICIENT_OBSERVATIONS", "point " + p + " is marked in " + views + " view(s); a point seen in fewer "
                    + "than " + MIN_VIEWS_PER_POINT + " different images or video frames cannot be located in 3D");
            }
        }
        if (kind.equals("DISTANCE")) {
            for (var e : viewsByPoint.get("A").entrySet()) {
                Obs b = viewsByPoint.get("B").get(e.getKey());
                if (b != null && Math.hypot(e.getValue().u() - b.u(), e.getValue().v() - b.v()) < MIN_ENDPOINT_PIXEL_SEPARATION) {
                    throw invalid("DEGENERATE_GEOMETRY", "points A and B are marked at the same pixel in view " + e.getKey()
                        + "; a distance needs two distinct physical points");
                }
            }
        }
        return out;
    }

    /** All control points of one capture are in one survey: same datum and unit, and distinct points. */
    private void checkAgainstOtherControlPoints(UUID captureId, String unit, String datum, double[] venueM) {
        List<MeasurementView> cps = measurements(captureId, true).stream().filter(m -> m.kind().equals("CONTROL_POINT")).toList();
        for (MeasurementView cp : cps) {
            if (!cp.unit().equals(unit)) {
                throw invalid("INCONSISTENT_UNITS", "control point \"" + cp.label() + "\" of this capture is in " + cp.unit()
                    + "; every control point of one survey must use the same unit (got " + unit + ")");
            }
            if (!cp.datum().equals(datum)) {
                throw invalid("INCONSISTENT_DATUM", "control point \"" + cp.label() + "\" is in datum \"" + cp.datum()
                    + "\"; every control point of a capture must be in the same survey datum (got \"" + datum + "\")");
            }
            double d = distance(venueM, toArray(cp.venueMetres()));
            if (d < MIN_CONTROL_POINT_SEPARATION_M) {
                throw invalid("DEGENERATE_GEOMETRY", "this point is " + round(d) + " m from control point \"" + cp.label()
                    + "\"; control points must be at least " + MIN_CONTROL_POINT_SEPARATION_M + " m apart");
            }
        }
    }

    // =========================================================================================
    // Status and calibration
    // =========================================================================================

    public CalibrationStatus status(Actor actor, UUID venueId, UUID captureId) {
        return status(captures.get(actor, venueId, captureId));
    }

    private CalibrationStatus status(CaptureView c) {
        List<MeasurementView> active = measurements(c.id(), true);
        List<MeasurementView> distances = active.stream().filter(m -> m.kind().equals("DISTANCE")).toList();
        List<MeasurementView> cps = active.stream().filter(m -> m.kind().equals("CONTROL_POINT")).toList();
        int requiredDistances = frameProps.minDistanceReferences();
        UUID run = runOf(c.id()).orElse(null);
        boolean reconstructed = run != null && hasPoses(run);
        FrameView frame = run == null ? null : frames.activeForRun(run).orElse(null);
        AttemptView last = attempts(c.id()).stream().findFirst().orElse(null);
        String collinear = cps.size() >= REQUIRED_CONTROL_POINTS ? collinearity(cps) : null;
        boolean distancesSuffice = distances.size() >= requiredDistances;
        boolean controlPointsSuffice = cps.size() >= REQUIRED_CONTROL_POINTS && collinear == null;

        List<String> needs = new ArrayList<>();
        String reconstructionFrame = !reconstructed ? "NO_RECONSTRUCTION"
            : frame == null || !"METRIC".equals(frame.metricStatus()) ? "ARBITRARY_SCALE"
            : frame.canonical() ? "CANONICAL" : "METRIC_NOT_ALIGNED";
        String state;
        if (frame != null && "METRIC".equals(frame.metricStatus())) {
            state = "CALIBRATED";
            if (!frame.canonical()) {
                needs.add("The frame is metric but not gravity-aligned, so POIs, navigation and AR cannot use it. Recalibrate with "
                    + "gravity OPERATOR_FLOOR_POINTS (at least three reconstruction points on the floor and one above it) or with "
                    + "three surveyed control points.");
            }
        } else if (last != null && last.outcome().equals("REJECTED")) {
            state = "REJECTED";
            needs.add("The last calibration was refused (" + last.errorCode() + "): " + last.errorMessage());
            needs.add("Re-measure or withdraw the measurement that disagrees, or correct the reconstruction coordinates you "
                + "resolved for its points, then calibrate again.");
        } else if (!distancesSuffice && !controlPointsSuffice) {
            state = active.isEmpty() ? "NO_EVIDENCE" : "EVIDENCE_INCOMPLETE";
        } else if (!reconstructed) {
            state = "AWAITING_RECONSTRUCTION";
        } else {
            state = "READY_TO_CALIBRATE";
        }

        if (c.floorId() == null) {
            needs.add("This capture has no floor. Calibration evidence belongs to one floor's coordinate frame: record it on a "
                + "capture created for that floor.");
        }
        if (!state.equals("CALIBRATED") && !distancesSuffice && !controlPointsSuffice) {
            needs.add("Record at least " + requiredDistances + " measured distances (have " + distances.size() + ") or at least "
                + REQUIRED_CONTROL_POINTS + " surveyed control points not on one line (have " + cps.size() + "). Mark each "
                + "measured point in at least " + MIN_VIEWS_PER_POINT + " different images or video frames.");
        }
        if (collinear != null) {
            needs.add(collinear);
        }
        if (!state.equals("CALIBRATED") && (distancesSuffice || controlPointsSuffice)) {
            if (!reconstructed) {
                needs.add("Finish the upload and start processing: measured points can only be located once POSE_ESTIMATION has "
                    + "produced a reconstruction.");
            } else {
                List<String> points = new ArrayList<>();
                for (MeasurementView m : controlPointsSuffice ? cps : distances) {
                    if (m.kind().equals("DISTANCE")) {
                        points.add(m.label() + " (A)");
                        points.add(m.label() + " (B)");
                    } else {
                        points.add(m.label() + " (P)");
                    }
                }
                needs.add("Automatic location of the marked pixels in the reconstruction (triangulation through the reconstructed "
                    + "camera poses) is not implemented. Pick each measured point in the reconstruction and submit its "
                    + "reconstruction coordinates: " + String.join(", ", points) + ".");
            }
        }
        if (reconstructed && !"CANONICAL".equals(reconstructionFrame)) {
            needs.add("Until then the reconstruction keeps its arbitrary SfM scale: semantic indexing, navigation baking, POIs and "
                + "AR anchors refuse it with NOT_CALIBRATED.");
        }
        return new CalibrationStatus(c.id(), c.floorId(), c.status().name(), captureStage(c.status()), state, reconstructionFrame,
            run, distances.size(), cps.size(), requiredDistances, REQUIRED_CONTROL_POINTS, frame, last, needs);
    }

    public CalibrationStatus calibrate(Actor actor, UUID venueId, UUID captureId, CalibrateCapture body) {
        CaptureView c = captures.get(actor, venueId, captureId);
        requireWriter(actor, venueId, c);
        if (c.status() == CaptureStatus.FAILED) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_CAPTURE_STATE", "capture has FAILED; it cannot be calibrated");
        }
        UUID run = runOf(captureId).orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "RECONSTRUCTION_FRAME_UNAVAILABLE",
            "processing has not been started for this capture; there is no reconstruction to calibrate yet"));
        if (body == null || body.resolvedPoints() == null || body.resolvedPoints().isEmpty()) {
            throw invalid("UNRESOLVED_POINTS", "resolvedPoints is required: the reconstruction coordinates of each measured point "
                + "(see GET .../calibration for the list)");
        }
        Map<UUID, Map<String, List<Double>>> resolved = new LinkedHashMap<>();
        for (ResolvedPoint p : body.resolvedPoints()) {
            if (p == null || p.measurementId() == null || p.point() == null) {
                throw invalid("UNRESOLVED_POINTS", "every resolved point names a measurementId and a point (A, B or P)");
            }
            if (resolved.computeIfAbsent(p.measurementId(), k -> new HashMap<>()).put(upper(p.point()), p.reconstruction()) != null) {
                throw invalid("UNRESOLVED_POINTS", "point " + p.point() + " of measurement " + p.measurementId() + " is given twice");
            }
        }
        List<DistanceReference> distances = new ArrayList<>();
        List<ControlPoint> controlPoints = new ArrayList<>();
        List<UUID> used = new ArrayList<>();
        for (var e : resolved.entrySet()) {
            MeasurementView m = measurement(captureId, e.getKey());
            if (!m.status().equals("ACTIVE")) {
                throw new ApiException(HttpStatus.CONFLICT, "MEASUREMENT_WITHDRAWN", "measurement \"" + m.label() + "\" is withdrawn");
            }
            Set<String> expected = m.kind().equals("DISTANCE") ? Set.of("A", "B") : Set.of("P");
            if (!e.getValue().keySet().equals(expected)) {
                throw invalid("UNRESOLVED_POINTS", "measurement \"" + m.label() + "\" needs reconstruction coordinates for exactly "
                    + String.join(" and ", expected.stream().sorted().toList()) + " (got " + e.getValue().keySet() + ")");
            }
            if (m.kind().equals("DISTANCE")) {
                distances.add(new DistanceReference(m.label(), e.getValue().get("A"), e.getValue().get("B"), m.measuredMetres()));
            } else {
                controlPoints.add(new ControlPoint(m.label(), e.getValue().get("P"), m.venueMetres()));
            }
            used.add(m.id());
        }
        CalibrationRequest request = new CalibrationRequest(distances, controlPoints, body.gravity(), body.note());
        try {
            tx.executeWithoutResult(s -> {
                FrameView frame = frames.calibrate(actor, venueId, run, request);
                UUID attempt = insertAttempt(actor, venueId, captureId, run, "ACCEPTED", frame.id(), null, null, used);
                for (UUID m : used) {
                    jdbc.sql("INSERT INTO coordinate_frame_measurement (coordinate_frame_id, measurement_id) VALUES (:f, :m)")
                        .param("f", frame.id()).param("m", m).update();
                }
                audit.success(actor, venueId, "capture.calibrate", "capture_session", captureId,
                    Map.of("attemptId", attempt.toString(), "coordinateFrameId", frame.id().toString(), "measurements", used.size()));
            });
        } catch (ApiException e) {
            if (e.status().is4xxClientError() && e.status() != HttpStatus.NOT_FOUND && e.status() != HttpStatus.FORBIDDEN) {
                tx.executeWithoutResult(s -> {
                    UUID attempt = insertAttempt(actor, venueId, captureId, run, "REJECTED", null, e.code(), e.getMessage(), used);
                    audit.success(actor, venueId, "capture.calibrate_rejected", "capture_session", captureId,
                        Map.of("attemptId", attempt.toString(), "code", e.code()));
                });
            }
            throw e;
        }
        return status(captures.get(actor, venueId, captureId));
    }

    private UUID insertAttempt(Actor actor, UUID venueId, UUID captureId, UUID run, String outcome, UUID frame, String code,
                               String message, List<UUID> measurementIds) {
        return jdbc.sql("""
                INSERT INTO capture_calibration_attempt (organization_id, venue_id, capture_session_id, run_id, outcome,
                    coordinate_frame_id, error_code, error_message, measurement_ids, created_by)
                VALUES (:o, :v, :c, :r, :out, :f, :code, :msg, CAST(:ids AS uuid[]), :by) RETURNING id
                """)
            .param("o", actor.organizationId()).param("v", venueId).param("c", captureId).param("r", run).param("out", outcome)
            .param("f", frame).param("code", code).param("msg", message)
            .param("ids", "{" + measurementIds.stream().map(UUID::toString).collect(Collectors.joining(",")) + "}")
            .param("by", actor.subject()).query(UUID.class).single();
    }

    public List<AttemptView> attempts(Actor actor, UUID venueId, UUID captureId) {
        captures.get(actor, venueId, captureId);
        return attempts(captureId);
    }

    // =========================================================================================
    // helpers
    // =========================================================================================

    /** The capture's operator, or a venue manager or admin at the venue, may change its evidence. */
    private static void requireWriter(Actor actor, UUID venueId, CaptureView c) {
        Set<Role> roles = actor.rolesAt(venueId);
        if (actor.subject().equals(c.operatorId()) || roles.contains(Role.ADMIN) || roles.contains(Role.VENUE_MANAGER)) {
            return;
        }
        throw new ApiException(HttpStatus.FORBIDDEN, "CAPTURE_NOT_OWNED",
            "only the operator who recorded this capture, a venue manager or an admin may change its calibration evidence");
    }

    static String captureStage(CaptureStatus s) {
        return switch (s) {
            case CREATED, UPLOADING -> "INCOMPLETE_CAPTURE";
            case UPLOADED, VALIDATING -> "VALIDATING_UPLOAD";
            case READY_FOR_PROCESSING -> "MEDIA_UPLOADED";
            case PROCESSING -> "PROCESSING";
            case COMPLETED -> "COMPLETED";
            case FAILED -> "FAILED";
        };
    }

    /** A message when the control points (venue metres) lie within MIN_CONTROL_POINT_SPREAD_M of one line, else null. */
    static String collinearity(List<MeasurementView> cps) {
        List<double[]> pts = cps.stream().map(m -> toArray(m.venueMetres())).toList();
        double best = -1;
        double[] p = null;
        double[] q = null;
        for (int i = 0; i < pts.size(); i++) {
            for (int j = i + 1; j < pts.size(); j++) {
                double d = distance(pts.get(i), pts.get(j));
                if (d > best) {
                    best = d;
                    p = pts.get(i);
                    q = pts.get(j);
                }
            }
        }
        double[] dir = {q[0] - p[0], q[1] - p[1], q[2] - p[2]};
        double len = Math.sqrt(dir[0] * dir[0] + dir[1] * dir[1] + dir[2] * dir[2]);
        double spread = 0;
        for (double[] r : pts) {
            double[] w = {r[0] - p[0], r[1] - p[1], r[2] - p[2]};
            double[] x = {dir[1] * w[2] - dir[2] * w[1], dir[2] * w[0] - dir[0] * w[2], dir[0] * w[1] - dir[1] * w[0]};
            spread = Math.max(spread, Math.sqrt(x[0] * x[0] + x[1] * x[1] + x[2] * x[2]) / len);
        }
        if (spread >= MIN_CONTROL_POINT_SPREAD_M) {
            return null;
        }
        return "The control points " + cps.stream().map(MeasurementView::label).toList() + " lie within " + round(spread)
            + " m of one line, which does not fix the rotation about it: add a control point at least "
            + MIN_CONTROL_POINT_SPREAD_M + " m off that line.";
    }

    private Optional<UUID> runOf(UUID captureId) {
        return jdbc.sql("SELECT id FROM pipeline_run WHERE capture_session_id = :c ORDER BY created_at DESC LIMIT 1")
            .param("c", captureId).query(UUID.class).optional();
    }

    private boolean hasPoses(UUID runId) {
        return jdbc.sql("""
                SELECT count(*) FROM processing_artifact a JOIN pipeline_stage_run sr ON sr.id = a.stage_run_id
                 WHERE sr.run_id = :r AND sr.stage = 'POSE_ESTIMATION' AND sr.status = 'SUCCEEDED' AND a.kind = 'POSES'
                """).param("r", runId).query(Integer.class).single() > 0;
    }

    private List<AttemptView> attempts(UUID captureId) {
        return jdbc.sql("""
                SELECT id, run_id, outcome, coordinate_frame_id, error_code, error_message, measurement_ids, created_by, created_at
                  FROM capture_calibration_attempt WHERE capture_session_id = :c ORDER BY created_at DESC, id LIMIT 50
                """).param("c", captureId).query((rs, i) -> {
                    Array ids = rs.getArray("measurement_ids");
                    List<UUID> list = new ArrayList<>();
                    for (Object o : (Object[]) ids.getArray()) {
                        list.add((UUID) o);
                    }
                    return new AttemptView(rs.getObject("id", UUID.class), rs.getObject("run_id", UUID.class), rs.getString("outcome"),
                        rs.getObject("coordinate_frame_id", UUID.class), rs.getString("error_code"), rs.getString("error_message"),
                        list, rs.getString("created_by"), rs.getTimestamp("created_at").toInstant());
                }).list();
    }

    private MeasurementView measurement(UUID captureId, UUID id) {
        return jdbc.sql("SELECT " + MEASUREMENT_COLUMNS + " FROM capture_measurement m WHERE m.id = :id AND m.capture_session_id = :c")
            .param("id", id).param("c", captureId).query(this::mapMeasurement).optional()
            .map(m -> withObservations(captureId, List.of(m)).get(0))
            .orElseThrow(() -> new NotFoundException("measurement not found"));
    }

    private List<MeasurementView> measurements(UUID captureId, boolean activeOnly) {
        List<MeasurementView> rows = jdbc.sql("SELECT " + MEASUREMENT_COLUMNS + " FROM capture_measurement m WHERE m.capture_session_id = :c"
                + (activeOnly ? " AND m.status = 'ACTIVE'" : "") + " ORDER BY m.created_at, m.id")
            .param("c", captureId).query(this::mapMeasurement).list();
        return withObservations(captureId, rows);
    }

    private List<MeasurementView> withObservations(UUID captureId, List<MeasurementView> rows) {
        if (rows.isEmpty()) {
            return rows;
        }
        Map<UUID, List<ObservationView>> obs = new HashMap<>();
        Set<UUID> ids = new HashSet<>(rows.stream().map(MeasurementView::id).toList());
        jdbc.sql("SELECT id, measurement_id, point, media_id, frame_time_seconds, pixel_u, pixel_v FROM capture_measurement_observation "
                + "WHERE capture_session_id = :c ORDER BY point, created_at, id").param("c", captureId)
            .query((rs, i) -> Map.entry(rs.getObject("measurement_id", UUID.class), new ObservationView(rs.getObject("id", UUID.class),
                rs.getString("point"), rs.getObject("media_id", UUID.class), (Double) rs.getObject("frame_time_seconds"),
                rs.getDouble("pixel_u"), rs.getDouble("pixel_v"))))
            .list().stream().filter(e -> ids.contains(e.getKey()))
            .forEach(e -> obs.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue()));
        List<MeasurementView> out = new ArrayList<>();
        for (MeasurementView m : rows) {
            out.add(new MeasurementView(m.id(), m.captureId(), m.floorId(), m.kind(), m.label(), m.method(), m.unit(), m.value(),
                m.measuredMetres(), m.datum(), m.venue(), m.venueMetres(), m.uncertaintyMetres(), m.status(), m.withdrawnReason(),
                m.withdrawnBy(), m.withdrawnAt(), m.createdBy(), m.createdAt(), m.usedByActiveFrame(),
                obs.getOrDefault(m.id(), List.of())));
        }
        return out;
    }

    private MeasurementView mapMeasurement(ResultSet rs, int i) throws SQLException {
        boolean cp = rs.getString("kind").equals("CONTROL_POINT");
        Timestamp withdrawn = rs.getTimestamp("withdrawn_at");
        return new MeasurementView(rs.getObject("id", UUID.class), rs.getObject("capture_session_id", UUID.class),
            rs.getObject("floor_id", UUID.class), rs.getString("kind"), rs.getString("label"), rs.getString("method"),
            rs.getString("unit"), (Double) rs.getObject("measured_value"), (Double) rs.getObject("measured_metres"), rs.getString("datum"),
            cp ? List.of(rs.getDouble("venue_x"), rs.getDouble("venue_y"), rs.getDouble("venue_z")) : null,
            cp ? List.of(rs.getDouble("venue_x_m"), rs.getDouble("venue_y_m"), rs.getDouble("venue_z_m")) : null,
            (Double) rs.getObject("uncertainty_metres"), rs.getString("status"), rs.getString("withdrawn_reason"),
            rs.getString("withdrawn_by"), withdrawn == null ? null : withdrawn.toInstant(), rs.getString("created_by"),
            rs.getTimestamp("created_at").toInstant(), rs.getBoolean("used"), List.of());
    }

    private static double[] toArray(List<Double> v) {
        return new double[]{v.get(0), v.get(1), v.get(2)};
    }

    private static double distance(double[] a, double[] b) {
        return Math.sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]));
    }

    private static String upper(String s) {
        return s == null ? "" : s.strip().toUpperCase(Locale.ROOT);
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static ApiException invalid(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }
}
