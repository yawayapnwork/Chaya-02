package dev.chaya.api.navigation;

import dev.chaya.api.navigation.FloorConnectionService.Connection;
import dev.chaya.api.navigation.FloorConnectionService.ConnectionData;
import dev.chaya.api.security.ActorAuthentication;
import dev.chaya.api.web.ProblemResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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

    private final FloorConnectionService connections;

    public FloorConnectionController(FloorConnectionService connections) {
        this.connections = connections;
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
    @ProblemResponse(status = 400, description = "INVALID_FLOOR_CONNECTION")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER')")
    public Connection create(@PathVariable UUID venueId, @RequestBody ConnectionData body) {
        return connections.create(ActorAuthentication.currentActor(), venueId, body);
    }

    @Operation(summary = "Set a floor connection's status (e.g. out of service)")
    @ProblemResponse(status = 400, description = "INVALID_FLOOR_CONNECTION: unknown status")
    @PutMapping("/{connectionId}/status")
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER','OPERATOR')")
    public Connection setStatus(@PathVariable UUID venueId, @PathVariable UUID connectionId, @Valid @RequestBody StatusRequest body) {
        return connections.setStatus(ActorAuthentication.currentActor(), venueId, connectionId, body.status());
    }

    @Operation(summary = "Delete a floor connection")
    @DeleteMapping("/{connectionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER')")
    public void delete(@PathVariable UUID venueId, @PathVariable UUID connectionId) {
        connections.delete(ActorAuthentication.currentActor(), venueId, connectionId);
    }
}
