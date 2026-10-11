package dev.chaya.api.hud;

import dev.chaya.api.hud.CaptureHudDtos.CreatePlanRequest;
import dev.chaya.api.hud.CaptureHudDtos.EstimateBoundsRequest;
import dev.chaya.api.hud.CaptureHudDtos.EstimateBoundsResponse;
import dev.chaya.api.hud.CaptureHudDtos.EvidenceReport;
import dev.chaya.api.hud.CaptureHudDtos.PlanView;
import dev.chaya.api.hud.CaptureHudDtos.PlanWithProgress;
import dev.chaya.api.hud.CaptureHudDtos.SurfaceBatchRequest;
import dev.chaya.api.hud.CaptureHudDtos.TrackingEventBatchRequest;
import dev.chaya.api.planning.Reconnaissance;
import dev.chaya.api.security.ActorAuthentication;
import dev.chaya.api.web.ProblemResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
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

/**
 * Automatic capture planning for one capture: the reconnaissance evidence (surface observations, tracking events), the
 * room bounds estimated from it, and persisted scan plans with their progress. Positions and quality samples are posted
 * through CaptureHudController. See docs/capture-hud.md.
 */
@RestController
@RequestMapping("/api/v1/venues/{venueId}/captures/{captureId}/hud")
@PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER','OPERATOR')")
public class CapturePlanController {

    private final CaptureHudService hud;
    private final CapturePlanService plans;

    public CapturePlanController(CaptureHudService hud, CapturePlanService plans) {
        this.hud = hud;
        this.plans = plans;
    }

    @Operation(summary = "Append surface points the device's tracker reported (hit tests against detected planes)",
            description = "Each carries the camera position it was observed from and the tracking frame. The only evidence walls "
                + "are estimated from.")
    @ApiResponse(responseCode = "202", description = "Accepted")
    @ProblemResponse(status = 400, description = "TOO_MANY_SAMPLES, INVALID_SURFACE_KIND, UNSUPPORTED_SURFACE_SOURCE, FRAME_ID_REQUIRED")
    @ProblemResponse(status = 409, description = "CAPTURE_NOT_CAPTURING")
    @PostMapping("/surface")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void addSurfaces(@PathVariable UUID venueId, @PathVariable UUID captureId, @Valid @RequestBody SurfaceBatchRequest body) {
        hud.addSurfaces(ActorAuthentication.currentActor(), venueId, captureId, body);
    }

    @Operation(summary = "Append tracking state changes and session problems the client observed",
            description = "Lost tracking, limited tracking, interrupted or ended AR sessions, camera permission denied, unsupported "
                + "device, orientation changes, re-alignment. Positions are never joined across a loss.")
    @ApiResponse(responseCode = "202", description = "Accepted")
    @ProblemResponse(status = 400, description = "TOO_MANY_SAMPLES, INVALID_TRACKING_STATE, INVALID_EVENT_SOURCE")
    @ProblemResponse(status = 409, description = "CAPTURE_NOT_CAPTURING")
    @PostMapping("/tracking-events")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void addTrackingEvents(@PathVariable UUID venueId, @PathVariable UUID captureId,
                                  @Valid @RequestBody TrackingEventBatchRequest body) {
        hud.addTrackingEvents(ActorAuthentication.currentActor(), venueId, captureId, body);
    }

    @Operation(summary = "Whether the reconnaissance walk recorded so far can be planned from, and what is missing")
    @GetMapping("/reconnaissance")
    public Reconnaissance.Assessment reconnaissance(@PathVariable UUID venueId, @PathVariable UUID captureId) {
        return plans.reconnaissance(ActorAuthentication.currentActor(), venueId, captureId);
    }

    @Operation(summary = "Estimate the room's observable bounds from the reconnaissance evidence",
            description = "Uses tracked positions and surface observations in one tracking frame. When bounds can be estimated they "
                + "replace the capture's room outline (scene source ESTIMATED); otherwise nothing is stored and the response says why. "
                + "The report includes confidence, the unobserved boundary (frontier), assumptions and missing observations.")
    @ProblemResponse(status = 409, description = "CAPTURE_NOT_CAPTURING")
    @PostMapping("/scene/estimate")
    public EstimateBoundsResponse estimate(@PathVariable UUID venueId, @PathVariable UUID captureId,
                                           @RequestBody(required = false) EstimateBoundsRequest body) {
        return plans.estimateBounds(ActorAuthentication.currentActor(), venueId, captureId, body);
    }

    @Operation(summary = "Make (and persist) a scan plan from the current bounds and reconnaissance walk",
            description = "Deterministic; the same planner as route-plan. Supersedes the previous plan. The plan's coverage is "
                + "PREDICTED; the coverage of positions recorded before it is OBSERVED.")
    @ApiResponse(responseCode = "201", description = "Created")
    @ProblemResponse(status = 409, description = "CAPTURE_NOT_CAPTURING")
    @ProblemResponse(status = 422, description = "SCENE_NOT_SET, RECONNAISSANCE_INCOMPLETE, RECONNAISSANCE_NOT_STARTED, or planner input errors")
    @ProblemResponse(status = 503, description = "PLANNER_BUSY")
    @PostMapping("/plan")
    @ResponseStatus(HttpStatus.CREATED)
    public PlanView createPlan(@PathVariable UUID venueId, @PathVariable UUID captureId,
                               @RequestBody(required = false) CreatePlanRequest body) {
        return plans.createPlan(ActorAuthentication.currentActor(), venueId, captureId, body);
    }

    @Operation(summary = "The current scan plan and the progress made along it")
    @ProblemResponse(status = 404, description = "NO_PLAN")
    @GetMapping("/plan")
    public PlanWithProgress currentPlan(@PathVariable UUID venueId, @PathVariable UUID captureId) {
        return plans.currentPlan(ActorAuthentication.currentActor(), venueId, captureId);
    }

    @Operation(summary = "Audit view: evidence, every plan, and PREDICTED vs OBSERVED coverage kept apart",
            description = "Readable in any capture state.")
    @GetMapping("/evidence")
    public EvidenceReport evidence(@PathVariable UUID venueId, @PathVariable UUID captureId) {
        return plans.evidence(ActorAuthentication.currentActor(), venueId, captureId);
    }
}
