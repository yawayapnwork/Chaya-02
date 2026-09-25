package dev.chaya.api.frame;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class FrameDtos {

    private FrameDtos() {}

    /** Two points picked in the reconstruction (its own units) and the physically measured distance between them. */
    public record DistanceReference(String label, @NotNull List<Double> a, @NotNull List<Double> b, @NotNull Double measuredMetres) {}

    /** A reconstruction point whose canonical venue coordinates (metres, +Z up) were surveyed. */
    public record ControlPoint(String label, @NotNull List<Double> reconstruction, @NotNull List<Double> venue) {}

    /**
     * Where gravity comes from when calibrating with distance references (control points carry their own).
     * source: RECONSTRUCTED_FLOOR_PLANE (the run's GRAVITY_ESTIMATE, the default when one exists), OPERATOR_FLOOR_POINTS
     * (floorPoints: at least three reconstruction points on the floor; pointAboveFloor: any reconstruction point above
     * it, to orient the normal), or NONE (scale only).
     */
    public record GravitySpec(@NotBlank String source, List<List<Double>> floorPoints, List<Double> pointAboveFloor) {}

    public record CalibrationRequest(@Valid List<DistanceReference> distanceReferences, @Valid List<ControlPoint> controlPoints,
                                     @Valid GravitySpec gravity, String note) {}

    public record Rotation(double w, double x, double y, double z) {}

    public record Vec3(double x, double y, double z) {}

    /** One calibration version, as the API returns it. canonical = METRIC and ALIGNED: only then are rotation/translation set. */
    public record FrameView(UUID id, UUID sourceRunId, UUID floorId, int version, String status, boolean canonical,
                            String metricStatus, String gravityStatus, String horizontalDatum, Double scale, Rotation rotation,
                            Vec3 translation, String scaleSource, Double scaleRelativeSpread, String gravitySource,
                            List<Double> gravityUpReconstruction, Double gravityDisagreementDeg, Double controlPointRmsM,
                            String method, Map<String, Object> inputs, Map<String, Object> residuals, String calibratedBy,
                            Instant calibratedAt, String units, String upAxis) {

        /** The minimal shape a worker reads from its work order (chaya_worker.frames.CoordinateFrame.from_wire). */
        public Map<String, Object> wire() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id.toString());
            m.put("sourceRunId", sourceRunId.toString());
            m.put("version", version);
            m.put("metricStatus", metricStatus);
            m.put("gravityStatus", gravityStatus);
            m.put("horizontalDatum", horizontalDatum);
            m.put("scale", scale);
            m.put("rotation", rotation == null ? null : Map.of("w", rotation.w(), "x", rotation.x(), "y", rotation.y(), "z", rotation.z()));
            m.put("translation", translation == null ? null : Map.of("x", translation.x(), "y", translation.y(), "z", translation.z()));
            return m;
        }

        public java.util.Optional<Similarity> toCanonical() {
            if (!canonical) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(new Similarity(scale, new Quaternion(rotation.w(), rotation.x(), rotation.y(), rotation.z()),
                new double[]{translation.x(), translation.y(), translation.z()}));
        }
    }
}
