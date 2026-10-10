package dev.chaya.api.capture;

import dev.chaya.api.capture.CaptureCalibrationDtos.AttemptView;
import dev.chaya.api.capture.CaptureCalibrationDtos.CalibrateCapture;
import dev.chaya.api.capture.CaptureCalibrationDtos.CalibrationStatus;
import dev.chaya.api.capture.CaptureCalibrationDtos.MeasurementInput;
import dev.chaya.api.capture.CaptureCalibrationDtos.MeasurementView;
import dev.chaya.api.capture.CaptureCalibrationDtos.WithdrawRequest;
import dev.chaya.api.security.ActorAuthentication;
import dev.chaya.api.web.ProblemResponse;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Calibration evidence recorded with a capture (docs/capture-calibration.md). Venue team only, like the capture itself. */
@RestController
@RequestMapping("/api/v1/venues/{venueId}/captures/{captureId}")
@PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER','OPERATOR')")
public class CaptureCalibrationController {

    private final CaptureCalibrationService calibration;

    public CaptureCalibrationController(CaptureCalibrationService calibration) {
        this.calibration = calibration;
    }

    @Operation(summary = "Record a measured distance or a surveyed control point",
            description = "DISTANCE: value and unit, points A and B. CONTROL_POINT: venue x, y, z in unit and a survey datum, "
                + "point P. Every point needs observations in at least two accepted images or video frames of this capture. "
                + "Immutable once recorded; withdraw to replace.")
    @ProblemResponse(status = 400, description = "INVALID_MEASUREMENT, MISSING_MEASUREMENT, MISSING_UNIT, INVALID_UNIT, "
            + "INCONSISTENT_UNITS, INCONSISTENT_DATUM, MISSING_DATUM, INCONSISTENT_MEASUREMENT, MEASUREMENT_OUT_OF_RANGE, "
            + "MEASUREMENT_TOO_UNCERTAIN, DEGENERATE_GEOMETRY, INSUFFICIENT_OBSERVATIONS, INVALID_OBSERVATION")
    @ProblemResponse(status = 403, description = "CAPTURE_NOT_OWNED: not the capture's operator, a venue manager or an admin")
    @ProblemResponse(status = 409, description = "FLOOR_REQUIRED, INVALID_CAPTURE_STATE, DUPLICATE_MEASUREMENT")
    @ProblemResponse(status = 422, description = "MEDIA_NOT_OBSERVABLE: the media is not an accepted image or video of this capture")
    @PostMapping("/measurements")
    @ResponseStatus(HttpStatus.CREATED)
    public MeasurementView record(@PathVariable UUID venueId, @PathVariable UUID captureId, @RequestBody MeasurementInput body) {
        return calibration.record(ActorAuthentication.currentActor(), venueId, captureId, body);
    }

    @Operation(summary = "Every measurement of this capture, active and withdrawn, with its observations")
    @GetMapping("/measurements")
    public List<MeasurementView> list(@PathVariable UUID venueId, @PathVariable UUID captureId) {
        return calibration.list(ActorAuthentication.currentActor(), venueId, captureId);
    }

    @Operation(summary = "Withdraw a measurement (body: reason)")
    @ProblemResponse(status = 403, description = "CAPTURE_NOT_OWNED")
    @ProblemResponse(status = 409, description = "MEASUREMENT_WITHDRAWN, MEASUREMENT_IN_USE: the active frame was computed from it")
    @PostMapping("/measurements/{measurementId}/withdraw")
    public MeasurementView withdraw(@PathVariable UUID venueId, @PathVariable UUID captureId, @PathVariable UUID measurementId,
                                    @RequestBody(required = false) WithdrawRequest body) {
        return calibration.withdraw(ActorAuthentication.currentActor(), venueId, captureId, measurementId,
            body == null ? null : body.reason());
    }

    @Operation(summary = "Calibration status of this capture and what the operator must still provide",
            description = "state: NO_EVIDENCE, EVIDENCE_INCOMPLETE, AWAITING_RECONSTRUCTION, READY_TO_CALIBRATE, CALIBRATED or "
                + "REJECTED. reconstructionFrame: NO_RECONSTRUCTION, ARBITRARY_SCALE, METRIC_NOT_ALIGNED or CANONICAL.")
    @GetMapping("/calibration")
    public CalibrationStatus status(@PathVariable UUID venueId, @PathVariable UUID captureId) {
        return calibration.status(ActorAuthentication.currentActor(), venueId, captureId);
    }

    @Operation(summary = "Calibrate the capture's reconstruction from its recorded measurements",
            description = "Body: resolvedPoints (measurementId, point A/B/P, reconstruction [x, y, z]) for every measurement to "
                + "use; optional gravity and note as for POST .../reconstructions/{runId}/coordinate-frames. Measured values come "
                + "from the recorded measurements, never from this request. Accepted and refused attempts are both recorded.")
    @ProblemResponse(status = 400, description = "UNRESOLVED_POINTS, INVALID_CALIBRATION")
    @ProblemResponse(status = 403, description = "CAPTURE_NOT_OWNED")
    @ProblemResponse(status = 409, description = "RECONSTRUCTION_FRAME_UNAVAILABLE, GRAVITY_UNAVAILABLE, MEASUREMENT_WITHDRAWN, "
            + "INVALID_CAPTURE_STATE")
    @ProblemResponse(status = 422, description = "CALIBRATION_INCONSISTENT: the measurements disagree beyond chaya.frames limits")
    @PostMapping("/calibration")
    @ResponseStatus(HttpStatus.CREATED)
    public CalibrationStatus calibrate(@PathVariable UUID venueId, @PathVariable UUID captureId, @RequestBody CalibrateCapture body) {
        return calibration.calibrate(ActorAuthentication.currentActor(), venueId, captureId, body);
    }

    @Operation(summary = "Calibration attempts of this capture, newest first (accepted and refused)")
    @GetMapping("/calibration/attempts")
    public List<AttemptView> attempts(@PathVariable UUID venueId, @PathVariable UUID captureId) {
        return calibration.attempts(ActorAuthentication.currentActor(), venueId, captureId);
    }
}
