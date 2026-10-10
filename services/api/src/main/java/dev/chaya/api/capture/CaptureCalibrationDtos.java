package dev.chaya.api.capture;

import dev.chaya.api.frame.FrameDtos.FrameView;
import dev.chaya.api.frame.FrameDtos.GravitySpec;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Wire types of capture-time calibration evidence (docs/capture-calibration.md). */
public final class CaptureCalibrationDtos {

    private CaptureCalibrationDtos() {}

    /**
     * Where one physical point is seen: a pixel in an accepted image or video of this capture. point: A or B (the two ends
     * of a distance) or P (a control point). frameTimeSeconds: seconds from the start of a video; omitted for an image.
     * u, v: pixel coordinates, (0, 0) at the top-left corner of the top-left pixel.
     */
    public record ObservationInput(String point, UUID mediaId, Double frameTimeSeconds, Double u, Double v) {}

    /**
     * One measurement as the operator made it. kind: DISTANCE (value, the measured length between physical points A and
     * B) or CONTROL_POINT (venue: x, y, z of physical point P in the survey datum `datum`, +Z up). unit: m, cm, mm, ft or
     * in, for value, venue and uncertainty alike. method: TAPE, LASER_DISTANCE_METER, TOTAL_STATION, SURVEY_PLAN or
     * OTHER. Every point needs at least two observations in different images or video frames.
     */
    public record MeasurementInput(String kind, String label, String method, String unit, Double value, Double uncertainty,
                                   String datum, List<Double> venue, List<ObservationInput> observations) {}

    public record WithdrawRequest(String reason) {}

    public record ObservationView(UUID id, String point, UUID mediaId, Double frameTimeSeconds, double u, double v) {}

    /** usedByActiveFrame: an ACTIVE coordinate frame was computed from it (it cannot be withdrawn until recalibrated). */
    public record MeasurementView(UUID id, UUID captureId, UUID floorId, String kind, String label, String method, String unit,
                                  Double value, Double measuredMetres, String datum, List<Double> venue, List<Double> venueMetres,
                                  Double uncertaintyMetres, String status, String withdrawnReason, String withdrawnBy,
                                  Instant withdrawnAt, String createdBy, Instant createdAt, boolean usedByActiveFrame,
                                  List<ObservationView> observations) {}

    /** The reconstruction coordinates (reconstruction units) of one measured point, located by the operator. */
    public record ResolvedPoint(UUID measurementId, String point, List<Double> reconstruction) {}

    /**
     * Calibrates the capture's reconstruction from its recorded measurements. Only measurements named in resolvedPoints
     * are used, and each of them needs all its points (A and B, or P). gravity and note as for the reconstruction
     * calibration endpoint.
     */
    public record CalibrateCapture(List<ResolvedPoint> resolvedPoints, GravitySpec gravity, String note) {}

    public record AttemptView(UUID id, UUID runId, String outcome, UUID coordinateFrameId, String errorCode, String errorMessage,
                              List<UUID> measurementIds, String createdBy, Instant createdAt) {}

    /**
     * Where a capture stands. captureStage: INCOMPLETE_CAPTURE, VALIDATING_UPLOAD, MEDIA_UPLOADED, PROCESSING, COMPLETED or
     * FAILED. state: NO_EVIDENCE, EVIDENCE_INCOMPLETE, AWAITING_RECONSTRUCTION, READY_TO_CALIBRATE (all "calibration
     * pending"), CALIBRATED or REJECTED. reconstructionFrame: NO_RECONSTRUCTION, ARBITRARY_SCALE (an SfM reconstruction
     * with no metric calibration), METRIC_NOT_ALIGNED or CANONICAL. requirements: what the operator must still provide.
     */
    public record CalibrationStatus(UUID captureId, UUID floorId, String captureStatus, String captureStage, String state,
                                    String reconstructionFrame, UUID runId, int activeDistances, int activeControlPoints,
                                    int requiredDistances, int requiredControlPoints, FrameView activeFrame,
                                    AttemptView lastAttempt, List<String> requirements) {}
}
