package dev.chaya.api.frame;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.chaya.api.audit.AuditService;
import dev.chaya.api.frame.FrameDtos.CalibrationRequest;
import dev.chaya.api.frame.FrameDtos.ControlPoint;
import dev.chaya.api.frame.FrameDtos.DistanceReference;
import dev.chaya.api.frame.FrameDtos.FrameView;
import dev.chaya.api.frame.FrameDtos.Rotation;
import dev.chaya.api.frame.FrameDtos.Vec3;
import dev.chaya.api.security.Actor;
import dev.chaya.api.security.TenantGuard;
import dev.chaya.api.storage.ObjectStore;
import dev.chaya.api.storage.StorageException;
import dev.chaya.api.web.ApiException;
import dev.chaya.api.web.NotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Calibration and lifecycle of coordinate frames (docs/coordinate-frames.md). The control plane owns every frame:
 * workers receive them on work orders, readers (viewer, routing, AR) through this service.
 *
 * <p>Calibration turns measured references into a similarity transform from one reconstruction's frame into the
 * canonical venue frame and stores it, with its inputs and residuals, as a new immutable version. Metres are never
 * assumed: without at least two agreeing measured distances, or at least three surveyed control points, there is no
 * metric frame. Gravity comes from the reconstructed floor plane (the run's GRAVITY_ESTIMATE), operator floor points,
 * or the control points themselves -- never from a reconstruction axis.
 *
 * <p>Lifecycle: a canonical frame of a full reconstruction becomes its floor's <em>current</em> frame, which is what POI,
 * anchor and navigation coordinates on that floor must be in. When the current frame is replaced by a recalibration of
 * the same reconstruction, POIs and anchors are re-projected exactly (new = T_new(T_old^-1(old))). When it is replaced
 * by a different reconstruction's frame, there is no known relation between the two: anchors become STALE, POIs keep
 * their old frame (and are reported STALE, and refused by routing) until they are re-placed.
 */
@Service
public class CoordinateFrameService {

    public static final String NOT_CALIBRATED = "NOT_CALIBRATED";
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private static final String FRAME_COLUMNS = """
        id, floor_id, source_run_id, version, status, metric_status, gravity_status, horizontal_datum, scale,
        rotation_w, rotation_x, rotation_y, rotation_z, translation_x, translation_y, translation_z, scale_source,
        scale_relative_spread, gravity_source, gravity_up_reconstruction, gravity_disagreement_deg, control_point_rms_m,
        method, inputs, residuals, calibrated_by, calibrated_at""";

    private record RunInfo(UUID id, UUID orgId, UUID venueId, UUID floorId, UUID reconstructionFrameRunId) {}

    /** The run's own reconstructed-structure gravity evidence (PLANE_FITTING's GRAVITY_ESTIMATE), reconstruction frame. */
    private record GravityEvidence(double[] up, double[] floorPoint, double[] cameraCentroid) {}

    private record Result(String metric, String gravity, String datum, Similarity transform, Double scale, String scaleSource,
                          Double spread, String gravitySource, double[] upReconstruction, Double disagreementDeg,
                          Double controlPointRms, String method, Map<String, Object> residuals) {}

    private final JdbcClient jdbc;
    private final TenantGuard guard;
    private final AuditService audit;
    private final ObjectStore derived;
    private final ObjectMapper mapper;
    private final FrameProperties props;

    public CoordinateFrameService(JdbcClient jdbc, TenantGuard guard, AuditService audit, @Qualifier("derived") ObjectStore derived,
                                  ObjectMapper mapper, FrameProperties props) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
        this.derived = derived;
        this.mapper = mapper;
        this.props = props;
    }

    // =========================================================================================
    // Calibration
    // =========================================================================================

    @Transactional
    public FrameView calibrate(Actor actor, UUID venueId, UUID runId, CalibrationRequest request) {
        guard.requireVenue(actor, venueId);
        RunInfo run = jdbc.sql("""
                SELECT r.id, r.organization_id, r.venue_id, c.floor_id, r.reconstruction_frame_run_id
                  FROM pipeline_run r JOIN capture_session c ON c.id = r.capture_session_id
                 WHERE r.id = :r AND r.venue_id = :v AND r.organization_id = :o FOR UPDATE OF r
                """)
            .param("r", runId).param("v", venueId).param("o", actor.organizationId())
            .query((rs, i) -> new RunInfo(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                rs.getObject(4, UUID.class), rs.getObject(5, UUID.class)))
            .optional().orElseThrow(() -> new NotFoundException("reconstruction run not found"));
        UUID posesArtifact = latestArtifact(runId, "POSE_ESTIMATION", "POSES")
            .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "RECONSTRUCTION_FRAME_UNAVAILABLE",
                "this run has no successful POSE_ESTIMATION, so there is no reconstruction frame to calibrate"));
        if (request == null) {
            throw invalid("a calibration needs distanceReferences or controlPoints");
        }

        Result result = request.controlPoints() != null && !request.controlPoints().isEmpty()
            ? fromControlPoints(run, request) : fromDistances(run, request);

        int version = jdbc.sql("SELECT coalesce(max(version), 0) + 1 FROM coordinate_frame WHERE source_run_id = :r")
            .param("r", runId).query(Integer.class).single();
        jdbc.sql("UPDATE coordinate_frame SET status = 'SUPERSEDED' WHERE source_run_id = :r AND status = 'ACTIVE'")
            .param("r", runId).update();
        Similarity t = result.transform();
        Quaternion q = t == null ? null : t.rotation();
        double[] tr = t == null ? null : t.translation();
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("distanceReferences", request.distanceReferences());
        inputs.put("controlPoints", request.controlPoints());
        inputs.put("gravity", request.gravity());
        inputs.put("note", request.note());
        UUID id = jdbc.sql("""
                INSERT INTO coordinate_frame (organization_id, venue_id, floor_id, source_run_id, source_artifact_id, version,
                    metric_status, gravity_status, horizontal_datum, scale, rotation_w, rotation_x, rotation_y, rotation_z,
                    translation_x, translation_y, translation_z, scale_source, scale_relative_spread, gravity_source,
                    gravity_up_reconstruction, gravity_disagreement_deg, control_point_rms_m, method, inputs, residuals, calibrated_by)
                VALUES (:o, :v, :f, :r, :a, :ver, :ms, :gs, :hd, :s, :qw, :qx, :qy, :qz, :tx, :ty, :tz, :ss, :spread, :gsrc,
                    CAST(:up AS double precision[]), :dis, :rms, :m, CAST(:in AS jsonb), CAST(:res AS jsonb), :by)
                RETURNING id
                """)
            .param("o", run.orgId()).param("v", venueId).param("f", run.floorId()).param("r", runId).param("a", posesArtifact)
            .param("ver", version).param("ms", result.metric()).param("gs", result.gravity()).param("hd", result.datum())
            .param("s", result.scale()).param("qw", q == null ? null : q.w()).param("qx", q == null ? null : q.x())
            .param("qy", q == null ? null : q.y()).param("qz", q == null ? null : q.z())
            .param("tx", tr == null ? null : tr[0]).param("ty", tr == null ? null : tr[1]).param("tz", tr == null ? null : tr[2])
            .param("ss", result.scaleSource()).param("spread", result.spread()).param("gsrc", result.gravitySource())
            .param("up", result.upReconstruction() == null ? null : sqlArray(result.upReconstruction()))
            .param("dis", result.disagreementDeg()).param("rms", result.controlPointRms()).param("m", result.method())
            .param("in", json(inputs)).param("res", json(result.residuals())).param("by", actor.subject())
            .query(UUID.class).single();

        FrameView view = load(id).orElseThrow();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("runId", runId.toString());
        meta.put("version", version);
        meta.put("metricStatus", result.metric());
        meta.put("gravityStatus", result.gravity());
        meta.put("horizontalDatum", result.datum());
        meta.put("method", result.method());
        boolean frameRoot = runId.equals(run.reconstructionFrameRunId());
        if (view.canonical() && frameRoot && run.floorId() != null) {
            meta.putAll(makeCurrentForFloor(run.floorId(), view));
        }
        audit.success(actor, venueId, "coordinate_frame.calibrate", "coordinate_frame", id, meta);
        return view;
    }

    private Result fromControlPoints(RunInfo run, CalibrationRequest request) {
        List<ControlPoint> cps = request.controlPoints();
        if (cps.size() < 3) {
            throw invalid("control-point calibration needs at least three surveyed points");
        }
        List<double[]> src = new ArrayList<>();
        List<double[]> dst = new ArrayList<>();
        for (ControlPoint cp : cps) {
            src.add(vec(cp.reconstruction(), "controlPoints[].reconstruction"));
            dst.add(vec(cp.venue(), "controlPoints[].venue"));
        }
        SimilarityEstimator.Fit fit;
        try {
            fit = SimilarityEstimator.fromCorrespondences(src, dst);
        } catch (IllegalArgumentException e) {
            throw invalid(e.getMessage());
        }
        Map<String, Object> residuals = new LinkedHashMap<>();
        residuals.put("controlPointResidualsM", fit.residuals());
        if (fit.rmsResidual() > props.maxControlPointRmsM()) {
            throw inconsistent("control points do not fit one similarity transform: RMS residual " + round(fit.rmsResidual())
                + " m exceeds " + props.maxControlPointRmsM() + " m", residuals);
        }
        Double spread = null;
        List<DistanceReference> refs = request.distanceReferences() == null ? List.of() : request.distanceReferences();
        if (!refs.isEmpty()) {
            double[] errors = new double[refs.size()];
            for (int i = 0; i < refs.size(); i++) {
                double[] lm = lengthAndMeasured(refs.get(i));
                errors[i] = Math.abs(fit.transform().scale() * lm[0] - lm[1]) / lm[1];
                spread = spread == null ? errors[i] : Math.max(spread, errors[i]);
            }
            residuals.put("distanceCheckRelativeErrors", errors);
            if (spread > props.maxScaleRelativeSpread()) {
                throw inconsistent("measured distances disagree with the control-point scale by " + round(100 * spread) + " %", residuals);
            }
        }
        Double disagreement = null;
        Optional<GravityEvidence> gravity = gravityEvidence(run.id());
        if (gravity.isPresent()) {
            disagreement = Vectors.angleDegrees(fit.transform().applyDirection(gravity.get().up()), CanonicalFrame.up());
            residuals.put("gravityDisagreementDeg", disagreement);
            if (disagreement > props.maxGravityDisagreementDeg()) {
                throw inconsistent("the control points' up direction is " + round(disagreement)
                    + " degrees from the reconstructed floor plane's", residuals);
            }
        }
        double[] up = fit.transform().rotation().conjugate().rotate(CanonicalFrame.up());
        return new Result("METRIC", "ALIGNED", "VENUE_CONTROL_POINTS", fit.transform(), fit.transform().scale(), "CONTROL_POINTS",
            spread, "CONTROL_POINTS", up, disagreement, fit.rmsResidual(), "CONTROL_POINTS_HORN_SIMILARITY", residuals);
    }

    private Result fromDistances(RunInfo run, CalibrationRequest request) {
        List<DistanceReference> refs = request.distanceReferences() == null ? List.of() : request.distanceReferences();
        if (refs.size() < props.minDistanceReferences()) {
            throw invalid("a metric calibration needs at least " + props.minDistanceReferences()
                + " independent measured distances (or at least three control points); reconstruction units are never assumed to be metres");
        }
        List<double[]> lm = refs.stream().map(this::lengthAndMeasured).toList();
        SimilarityEstimator.ScaleFit sf = SimilarityEstimator.scaleFromDistances(lm);
        Map<String, Object> residuals = new LinkedHashMap<>();
        residuals.put("perReferenceScale", sf.perReferenceScale());
        residuals.put("scaleRelativeSpread", sf.relativeSpread());
        if (sf.relativeSpread() > props.maxScaleRelativeSpread()) {
            throw inconsistent("the measured distances imply scales that differ by up to " + round(100 * sf.relativeSpread())
                + " % (limit " + round(100 * props.maxScaleRelativeSpread()) + " %); re-measure or re-pick the points", residuals);
        }
        String source = request.gravity() == null ? null : request.gravity().source().toUpperCase(Locale.ROOT);
        double[] up = null;
        double[] origin = null;
        String gravitySource = "NONE";
        if (source == null || source.equals("RECONSTRUCTED_FLOOR_PLANE")) {
            Optional<GravityEvidence> g = gravityEvidence(run.id());
            if (g.isPresent()) {
                up = Vectors.normalize(g.get().up());
                origin = SimilarityEstimator.projectOntoPlane(g.get().cameraCentroid(), g.get().floorPoint(), up);
                gravitySource = "RECONSTRUCTED_FLOOR_PLANE";
            } else if (source != null) {
                throw new ApiException(HttpStatus.CONFLICT, "GRAVITY_UNAVAILABLE",
                    "this run has no GRAVITY_ESTIMATE with an estimated floor plane; provide OPERATOR_FLOOR_POINTS or NONE");
            }
        } else if (source.equals("OPERATOR_FLOOR_POINTS")) {
            List<List<Double>> floor = request.gravity().floorPoints();
            if (floor == null || floor.size() < 3 || request.gravity().pointAboveFloor() == null) {
                throw invalid("OPERATOR_FLOOR_POINTS needs at least three floorPoints and a pointAboveFloor");
            }
            SimilarityEstimator.FittedPlane plane;
            try {
                plane = SimilarityEstimator.plane(floor.stream().map(p -> vec(p, "gravity.floorPoints[]")).toList(),
                    vec(request.gravity().pointAboveFloor(), "gravity.pointAboveFloor"));
            } catch (IllegalArgumentException e) {
                throw invalid(e.getMessage());
            }
            double rmsMetres = plane.rmsDistance() * sf.scale();
            residuals.put("floorPointsPlaneRmsM", rmsMetres);
            if (rmsMetres > props.maxFloorPointsRmsM()) {
                throw inconsistent("the floor points are not coplanar: RMS " + round(rmsMetres) + " m from their plane", residuals);
            }
            up = plane.normal();
            origin = plane.point();
            gravitySource = "OPERATOR_FLOOR_POINTS";
        } else if (!source.equals("NONE")) {
            throw invalid("gravity.source must be RECONSTRUCTED_FLOOR_PLANE, OPERATOR_FLOOR_POINTS or NONE");
        }
        if (up == null) {
            return new Result("METRIC", "NOT_ALIGNED", "NONE", null, sf.scale(), "MEASURED_DISTANCES", sf.relativeSpread(),
                "NONE", null, null, null, "MEASURED_DISTANCES", residuals);
        }
        Similarity t = SimilarityEstimator.floorLocal(sf.scale(), up, origin);
        return new Result("METRIC", "ALIGNED", "FLOOR_LOCAL", t, sf.scale(), "MEASURED_DISTANCES", sf.relativeSpread(),
            gravitySource, up, null, null, "MEASURED_DISTANCES+" + gravitySource, residuals);
    }

    // =========================================================================================
    // Lifecycle: the floor's current frame
    // =========================================================================================

    private Map<String, Object> makeCurrentForFloor(UUID floorId, FrameView next) {
        UUID previousId = jdbc.sql("SELECT current_coordinate_frame_id FROM floor WHERE id = :f FOR UPDATE")
            .param("f", floorId).query((rs, i) -> rs.getObject(1, UUID.class)).optional().orElse(null);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("previousCurrentFrameId", previousId == null ? null : previousId.toString());
        if (previousId != null && !previousId.equals(next.id())) {
            FrameView previous = load(previousId).orElseThrow();
            Optional<Similarity> oldT = previous.toCanonical();
            if (previous.sourceRunId().equals(next.sourceRunId()) && oldT.isPresent()) {
                Similarity change = next.toCanonical().orElseThrow().compose(oldT.get().inverse());
                meta.put("poisReprojected", reprojectPois(floorId, previousId, next.id(), change));
                meta.put("anchorsReprojected", reprojectAnchors(floorId, previousId, next.id(), change));
            } else {
                int stale = jdbc.sql("""
                        UPDATE ar_anchor SET calibration_status = 'STALE'
                         WHERE floor_id = :f AND deleted_at IS NULL AND calibration_status = 'CALIBRATED'
                           AND coordinate_frame_id IS DISTINCT FROM :n
                        """).param("f", floorId).param("n", next.id()).update();
                meta.put("anchorsMarkedStale", stale);
            }
        }
        jdbc.sql("UPDATE floor SET current_coordinate_frame_id = :n WHERE id = :f").param("n", next.id()).param("f", floorId).update();
        return meta;
    }

    private int reprojectPois(UUID floorId, UUID oldFrame, UUID newFrame, Similarity change) {
        List<Map<String, Object>> rows = jdbc.sql("""
                SELECT v.poi_id, v.version_number, v.x, v.y, v.z FROM poi p JOIN poi_version v ON v.poi_id = p.id
                 WHERE p.floor_id = :f AND p.deleted_at IS NULL AND v.coordinate_frame_id = :old
                   AND v.version_number = (SELECT max(version_number) FROM poi_version WHERE poi_id = p.id)
                """).param("f", floorId).param("old", oldFrame).query().listOfRows();
        for (Map<String, Object> row : rows) {
            double[] p = change.apply(new double[]{((Number) row.get("x")).doubleValue(), ((Number) row.get("y")).doubleValue(),
                ((Number) row.get("z")).doubleValue()});
            jdbc.sql("""
                    INSERT INTO poi_version (organization_id, venue_id, poi_id, version_number, label, category, description, tags,
                        x, y, z, attributes, scan_version_id, embedding, embedding_model, source, detection_confidence, bounding_box,
                        pipeline_run_id, coordinate_frame_id, created_by)
                    SELECT organization_id, venue_id, poi_id, version_number + 1, label, category, description, tags,
                        :x, :y, :z, attributes, scan_version_id, embedding, embedding_model, source, detection_confidence, bounding_box,
                        pipeline_run_id, :new, 'system:coordinate-frame-recalibration'
                      FROM poi_version WHERE poi_id = :p AND version_number = :n
                    """)
                .param("x", p[0]).param("y", p[1]).param("z", p[2]).param("new", newFrame)
                .param("p", row.get("poi_id")).param("n", row.get("version_number")).update();
        }
        return rows.size();
    }

    private int reprojectAnchors(UUID floorId, UUID oldFrame, UUID newFrame, Similarity change) {
        List<Map<String, Object>> rows = jdbc.sql("""
                SELECT id, digital_x, digital_y, digital_z, digital_qx, digital_qy, digital_qz, digital_qw FROM ar_anchor
                 WHERE floor_id = :f AND deleted_at IS NULL AND coordinate_frame_id = :old
                """).param("f", floorId).param("old", oldFrame).query().listOfRows();
        for (Map<String, Object> r : rows) {
            double[] p = change.apply(new double[]{num(r, "digital_x"), num(r, "digital_y"), num(r, "digital_z")});
            Quaternion q = change.applyOrientation(new Quaternion(num(r, "digital_qw"), num(r, "digital_qx"), num(r, "digital_qy"),
                num(r, "digital_qz")));
            jdbc.sql("""
                    UPDATE ar_anchor SET digital_x = :x, digital_y = :y, digital_z = :z, digital_qx = :qx, digital_qy = :qy,
                        digital_qz = :qz, digital_qw = :qw, coordinate_frame_id = :new WHERE id = :id
                    """)
                .param("x", p[0]).param("y", p[1]).param("z", p[2]).param("qx", q.x()).param("qy", q.y()).param("qz", q.z())
                .param("qw", q.w()).param("new", newFrame).param("id", r.get("id")).update();
        }
        return rows.size();
    }

    // =========================================================================================
    // Reads
    // =========================================================================================

    @Transactional(readOnly = true)
    public List<FrameView> listForRun(Actor actor, UUID venueId, UUID runId) {
        guard.requireVenue(actor, venueId);
        return jdbc.sql("SELECT " + FRAME_COLUMNS + " FROM coordinate_frame WHERE source_run_id = :r AND venue_id = :v "
                + "AND organization_id = :o ORDER BY version DESC")
            .param("r", runId).param("v", venueId).param("o", actor.organizationId()).query(this::map).list();
    }

    @Transactional(readOnly = true)
    public FrameView currentForFloor(Actor actor, UUID venueId, UUID floorId) {
        guard.requireVenue(actor, venueId);
        return currentForFloor(venueId, floorId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, NOT_CALIBRATED,
            "this floor has no calibrated coordinate frame yet"));
    }

    /** The floor's current canonical frame, if any. Callers have already checked tenancy. */
    public Optional<FrameView> currentForFloor(UUID venueId, UUID floorId) {
        return jdbc.sql("SELECT current_coordinate_frame_id FROM floor WHERE id = :f AND venue_id = :v AND deleted_at IS NULL")
            .param("f", floorId).param("v", venueId).query((rs, i) -> rs.getObject(1, UUID.class)).optional()
            .flatMap(id -> id == null ? Optional.empty() : load(id));
    }

    /** The ACTIVE frame calibrated for a run's reconstruction frame, if any (canonical or not). */
    public Optional<FrameView> activeForRun(UUID runId) {
        if (runId == null) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT " + FRAME_COLUMNS + " FROM coordinate_frame WHERE source_run_id = :r AND status = 'ACTIVE'")
            .param("r", runId).query(this::map).optional();
    }

    public Optional<FrameView> load(UUID frameId) {
        return jdbc.sql("SELECT " + FRAME_COLUMNS + " FROM coordinate_frame WHERE id = :id").param("id", frameId).query(this::map).optional();
    }

    // =========================================================================================
    // helpers
    // =========================================================================================

    private Optional<UUID> latestArtifact(UUID runId, String stage, String kind) {
        return jdbc.sql("""
                SELECT a.id FROM processing_artifact a JOIN pipeline_stage_run sr ON sr.id = a.stage_run_id
                 WHERE sr.run_id = :r AND sr.stage = :s AND sr.status = 'SUCCEEDED' AND a.kind = :k
                 ORDER BY sr.finished_at DESC LIMIT 1
                """).param("r", runId).param("s", stage).param("k", kind).query(UUID.class).optional();
    }

    @SuppressWarnings("unchecked")
    private Optional<GravityEvidence> gravityEvidence(UUID runId) {
        Optional<String> key = jdbc.sql("""
                SELECT a.object_key FROM processing_artifact a JOIN pipeline_stage_run sr ON sr.id = a.stage_run_id
                 WHERE sr.run_id = :r AND sr.status = 'SUCCEEDED' AND a.kind = 'GRAVITY_ESTIMATE'
                 ORDER BY sr.finished_at DESC LIMIT 1
                """).param("r", runId).query(String.class).optional();
        if (key.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> doc;
        try (InputStream in = derived.open(key.get())) {
            doc = mapper.readValue(in, MAP);
        } catch (IOException | StorageException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "GRAVITY_ESTIMATE_UNREADABLE",
                "the run's GRAVITY_ESTIMATE could not be read: " + e.getMessage());
        }
        if (!"ESTIMATED".equals(doc.get("status"))) {
            return Optional.empty();
        }
        try {
            return Optional.of(new GravityEvidence(vec((List<Double>) (List<?>) doc.get("up_reconstruction"), "up_reconstruction"),
                vec((List<Double>) (List<?>) doc.get("floor_point_reconstruction"), "floor_point_reconstruction"),
                vec((List<Double>) (List<?>) doc.get("camera_centroid_reconstruction"), "camera_centroid_reconstruction")));
        } catch (ClassCastException e) {
            throw new ApiException(HttpStatus.CONFLICT, "GRAVITY_ESTIMATE_INVALID", "the run's GRAVITY_ESTIMATE is malformed");
        }
    }

    private double[] lengthAndMeasured(DistanceReference ref) {
        double[] a = vec(ref.a(), "distanceReferences[].a");
        double[] b = vec(ref.b(), "distanceReferences[].b");
        double length = Vectors.distance(a, b);
        if (!(length > 1e-12) || ref.measuredMetres() == null || !(ref.measuredMetres() > 0) || !Double.isFinite(ref.measuredMetres())) {
            throw invalid("every distance reference needs two distinct reconstruction points and a positive measuredMetres");
        }
        return new double[]{length, ref.measuredMetres()};
    }

    private static double[] vec(List<? extends Number> v, String what) {
        if (v == null || v.size() != 3 || v.stream().anyMatch(d -> d == null || !Double.isFinite(d.doubleValue()))) {
            throw invalid(what + " must be three finite numbers");
        }
        return new double[]{v.get(0).doubleValue(), v.get(1).doubleValue(), v.get(2).doubleValue()};
    }

    private static double num(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).doubleValue();
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CALIBRATION", message);
    }

    private ApiException inconsistent(String message, Map<String, Object> residuals) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CALIBRATION_INCONSISTENT", message + " -- residuals: " + json(residuals));
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static String sqlArray(double[] v) {
        return "{" + v[0] + "," + v[1] + "," + v[2] + "}";
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Object> parse(Object jsonb) {
        try {
            return jsonb == null ? Map.of() : mapper.readValue(jsonb.toString(), MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private FrameView map(ResultSet rs, int i) throws SQLException {
        boolean rotated = rs.getObject("rotation_w") != null;
        Array up = rs.getArray("gravity_up_reconstruction");
        List<Double> upList = null;
        if (up != null) {
            upList = new ArrayList<>();
            for (Object o : (Object[]) up.getArray()) {
                upList.add(((Number) o).doubleValue());
            }
        }
        String metric = rs.getString("metric_status");
        String gravity = rs.getString("gravity_status");
        return new FrameView(rs.getObject("id", UUID.class), rs.getObject("source_run_id", UUID.class),
            rs.getObject("floor_id", UUID.class), rs.getInt("version"), rs.getString("status"),
            "METRIC".equals(metric) && "ALIGNED".equals(gravity), metric, gravity, rs.getString("horizontal_datum"),
            (Double) rs.getObject("scale"),
            rotated ? new Rotation(rs.getDouble("rotation_w"), rs.getDouble("rotation_x"), rs.getDouble("rotation_y"), rs.getDouble("rotation_z")) : null,
            rotated ? new Vec3(rs.getDouble("translation_x"), rs.getDouble("translation_y"), rs.getDouble("translation_z")) : null,
            rs.getString("scale_source"), (Double) rs.getObject("scale_relative_spread"), rs.getString("gravity_source"), upList,
            (Double) rs.getObject("gravity_disagreement_deg"), (Double) rs.getObject("control_point_rms_m"), rs.getString("method"),
            parse(rs.getObject("inputs")), parse(rs.getObject("residuals")), rs.getString("calibrated_by"),
            rs.getTimestamp("calibrated_at").toInstant(), CanonicalFrame.UNITS, CanonicalFrame.UP_AXIS);
    }
}
