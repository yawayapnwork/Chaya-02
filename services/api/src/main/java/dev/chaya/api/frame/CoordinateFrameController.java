package dev.chaya.api.frame;

import dev.chaya.api.frame.FrameDtos.CalibrationRequest;
import dev.chaya.api.frame.FrameDtos.FrameView;
import dev.chaya.api.security.ActorAuthentication;
import dev.chaya.api.web.ProblemResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
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

/** Coordinate-frame calibration and reads (docs/coordinate-frames.md). */
@RestController
@RequestMapping("/api/v1/venues/{venueId}")
public class CoordinateFrameController {

    private final CoordinateFrameService frames;

    public CoordinateFrameController(CoordinateFrameService frames) {
        this.frames = frames;
    }

    /** Calibrates a reconstruction: a new immutable frame version from measured references. */
    @Operation(summary = "Calibrate a reconstruction's coordinate frame",
            description = "Body: distanceReferences (>= 2 measured distances between reconstruction points) and "
                + "optional gravity {source: RECONSTRUCTED_FLOOR_PLANE | OPERATOR_FLOOR_POINTS (floorPoints, "
                + "pointAboveFloor) | NONE}, or controlPoints (>= 3 reconstruction/venue pairs). Stores a new immutable "
                + "version X_canonical = s R X_reconstruction + t (metres, +Z up) and supersedes the previous one "
                + "(docs/coordinate-frames.md).")
    @ProblemResponse(status = 400, description = "INVALID_CALIBRATION")
    @ProblemResponse(status = 409, description = "RECONSTRUCTION_FRAME_UNAVAILABLE, GRAVITY_UNAVAILABLE")
    @ProblemResponse(status = 422, description = "CALIBRATION_INCONSISTENT: the references disagree beyond "
            + "chaya.frames limits")
    @PostMapping("/reconstructions/{runId}/coordinate-frames")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER','OPERATOR')")
    public FrameView calibrate(@PathVariable UUID venueId, @PathVariable UUID runId, @Valid @RequestBody CalibrationRequest body) {
        return frames.calibrate(ActorAuthentication.currentActor(), venueId, runId, body);
    }

    @Operation(summary = "Every calibration version of this reconstruction's coordinate frame, newest first")
    @GetMapping("/reconstructions/{runId}/coordinate-frames")
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER','OPERATOR','VIEWER')")
    public List<FrameView> list(@PathVariable UUID venueId, @PathVariable UUID runId) {
        return frames.listForRun(ActorAuthentication.currentActor(), venueId, runId);
    }

    /** The floor's current canonical frame; 404 NOT_CALIBRATED when there is none. */
    @Operation(summary = "The floor's current canonical coordinate frame",
            description = "The frame its POIs, anchors and navigation graphs are in.")
    @ProblemResponse(status = 404, description = "NOT_CALIBRATED, or the floor is not visible to the caller")
    @GetMapping("/floors/{floorId}/coordinate-frame")
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER','OPERATOR','VIEWER','PUBLIC_VIEWER')")
    public FrameView current(@PathVariable UUID venueId, @PathVariable UUID floorId) {
        return frames.currentForFloor(ActorAuthentication.currentActor(), venueId, floorId);
    }
}
