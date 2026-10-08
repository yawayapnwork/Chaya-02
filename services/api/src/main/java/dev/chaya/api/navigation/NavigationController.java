package dev.chaya.api.navigation;

import dev.chaya.api.navigation.NavigationDtos.RouteRequest;
import dev.chaya.api.navigation.NavigationDtos.RouteResponse;
import dev.chaya.api.security.Actor;
import dev.chaya.api.security.ActorAuthentication;
import dev.chaya.api.web.ProblemResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/navigation")
public class NavigationController {

    private final RouteService routes;

    public NavigationController(RouteService routes) {
        this.routes = routes;
    }

    @Operation(summary = "Route between two points of a venue on its baked navigation graph",
            description = "The venue is in the body. Accessibility profiles, blocked regions and floor connections "
                + "are honoured (docs/navigation.md).")
    @ProblemResponse(status = 400, description = "INVALID_START, INVALID_ACCESSIBILITY, INVALID_BLOCKED_REGION")
    @ProblemResponse(status = 404, description = "NO_ROUTE, NO_ACCESSIBLE_ROUTE, FLOOR_CONNECTION_UNAVAILABLE, or the "
            + "venue is not visible")
    @ProblemResponse(status = 409, description = "NAVMESH_NOT_READY, METRIC_CALIBRATION_REQUIRED, VERSION_MISMATCH, "
            + "VERSION_WRONG_FLOOR")
    @PostMapping("/routes")
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER','OPERATOR','VIEWER','PUBLIC_VIEWER')")
    public RouteResponse route(@Valid @RequestBody RouteRequest request) {
        // The venue is in the body, not the path, so VenueScopeFilter cannot narrow the actor: do it here. A member of the
        // venue must hold a role there (any role may route); a non-member is refused as 404 by the service's TenantGuard.
        Actor actor = ActorAuthentication.currentActor().atVenue(request.venueId());
        if (actor.isMemberOf(request.venueId()) && actor.rolesAt(request.venueId()).isEmpty()) {
            throw new AccessDeniedException("no role at this venue");
        }
        return routes.route(actor, request);
    }
}
