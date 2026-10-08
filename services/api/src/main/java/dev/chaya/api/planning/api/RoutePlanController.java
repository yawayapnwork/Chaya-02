package dev.chaya.api.planning.api;

import dev.chaya.api.planning.PlanResult;
import dev.chaya.api.planning.api.RoutePlanDtos.RoutePlanRequest;
import dev.chaya.api.security.ActorAuthentication;
import dev.chaya.api.web.ProblemResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Plans the secondary capture route after the reconnaissance lap. Stateless: nothing is stored except an audit record. */
@RestController
@RequestMapping("/api/v1/venues/{venueId}/captures/{captureId}/route-plan")
@PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER','OPERATOR')")
public class RoutePlanController {

    private final RoutePlanService service;

    public RoutePlanController(RoutePlanService service) {
        this.service = service;
    }

    @Operation(summary = "Plan the secondary capture route after the recon lap",
            description = "Deterministic. Request: scene, trajectory, optional observedRegions/candidates/config. "
                + "Response: ordered waypoints, route, distance, time, confidence, baseline vs planned coverage, "
                + "uncovered regions. See docs/route-planning.md.")
    @ProblemResponse(status = 409, description = "CAPTURE_NOT_PLANNABLE: the capture is no longer being captured")
    @ProblemResponse(status = 422, description = "Input cannot be planned from (TRAJECTORY_OUTSIDE_SCENE, "
            + "INVALID_CONFIG, ...)")
    @ProblemResponse(status = 503, description = "PLANNER_BUSY, or a dependency is unavailable")
    @PostMapping
    public PlanResult plan(@PathVariable UUID venueId, @PathVariable UUID captureId, @Valid @RequestBody RoutePlanRequest body) {
        return service.plan(ActorAuthentication.currentActor(), venueId, captureId, body);
    }
}
