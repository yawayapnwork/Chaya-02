package dev.chaya.api.navigation;

import dev.chaya.api.navigation.FloorConnectionService.Connection;
import dev.chaya.api.navigation.FloorConnectionService.ConnectionData;
import dev.chaya.api.security.ActorAuthentication;
import dev.chaya.api.web.ProblemResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/venues/{venueId}/floor-connections")
public class FloorConnectionController {

    public record StatusRequest(@NotBlank String status) {}

    /** verified: true records that the connection's registered accessibility was checked on site; false withdraws it. */
    public record AccessibilityVerificationRequest(@NotNull Boolean verified) {}

    private final FloorConnectionService connections;
    private final RouteService routes;

    public FloorConnectionController(FloorConnectionService connections, RouteService routes) {
        this.connections = connections;
        this.routes = routes;
    }

    @Operation(summary = "List the venue's floor connections (stairs, elevators, ramps between floors)")
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER','OPERATOR','VIEWER','PUBLIC_VIEWER')")
    public List<Connection> list(@PathVariable UUID venueId) {
        return connections.list(ActorAuthentication.currentActor(), venueId);
    }

    @Operation(summary = "One floor connection")
    @GetMapping("/{connectionId}")
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER','OPERATOR','VIEWER','PUBLIC_VIEWER')")
    public Connection get(@PathVariable UUID venueId, @PathVariable UUID connectionId) {
        return connections.get(ActorAuthentication.currentActor(), venueId, connectionId);
    }

    @Operation(summary = "Create a floor connection")
    @ProblemResponse(status = 400, description = "INVALID_FLOOR_CONNECTION, CONNECTION_GEOMETRY_INCONSISTENT")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER')")
    public Connection create(@PathVariable UUID venueId, @RequestBody ConnectionData body) {
        return connections.create(ActorAuthentication.currentActor(), venueId, body);
    }

    @Operation(summary = "The floor-transition graph routing uses for a profile",
            description = "Usable connections, unusable ones and why, and which floors are connected (STANDARD or STEP_FREE).")
    @GetMapping("/graph")
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER','OPERATOR','VIEWER','PUBLIC_VIEWER')")
    public RouteService.FloorTransitionGraph graph(@PathVariable UUID venueId,
                                                   @org.springframework.web.bind.annotation.RequestParam(required = false) String accessibility) {
        return routes.floorTransitionGraph(ActorAuthentication.currentActor(), venueId, accessibility);
    }

    @Operation(summary = "Correct a floor connection's registration",
            description = "Changing anything step-free routing relies on withdraws the accessibility verification.")
    @ProblemResponse(status = 400, description = "INVALID_FLOOR_CONNECTION, CONNECTION_GEOMETRY_INCONSISTENT")
    @PutMapping("/{connectionId}")
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER')")
    public Connection update(@PathVariable UUID venueId, @PathVariable UUID connectionId, @RequestBody ConnectionData body) {
        return connections.update(ActorAuthentication.currentActor(), venueId, connectionId, body);
    }

    @Operation(summary = "Set a floor connection's status (e.g. out of service)")
    @ProblemResponse(status = 400, description = "INVALID_FLOOR_CONNECTION: unknown status")
    @PutMapping("/{connectionId}/status")
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER','OPERATOR')")
    public Connection setStatus(@PathVariable UUID venueId, @PathVariable UUID connectionId, @Valid @RequestBody StatusRequest body) {
        return connections.setStatus(ActorAuthentication.currentActor(), venueId, connectionId, body.status());
    }

    @Operation(summary = "Record (or withdraw) on-site verification of a floor connection's accessibility",
            description = "STEP_FREE routes cross floors only over elevators and ramps whose registered clear width (and ramp "
                + "slope) were verified on site (docs/navigation.md).")
    @ProblemResponse(status = 400, description = "INVALID_FLOOR_CONNECTION: stairs, or nothing registered to verify")
    @PutMapping("/{connectionId}/accessibility-verification")
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER')")
    public Connection setAccessibilityVerification(@PathVariable UUID venueId, @PathVariable UUID connectionId,
                                                   @Valid @RequestBody AccessibilityVerificationRequest body) {
        return connections.setAccessibilityVerified(ActorAuthentication.currentActor(), venueId, connectionId, body.verified());
    }

    @Operation(summary = "Delete a floor connection")
    @DeleteMapping("/{connectionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER')")
    public void delete(@PathVariable UUID venueId, @PathVariable UUID connectionId) {
        connections.delete(ActorAuthentication.currentActor(), venueId, connectionId);
    }
}
