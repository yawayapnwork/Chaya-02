package dev.chaya.api.venue;

import dev.chaya.api.security.ActorAuthentication;
import dev.chaya.api.venue.FloorService.ConsistencyReport;
import dev.chaya.api.venue.FloorService.Floor;
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

/** Floors of a venue: what a capture session, scan version and floor connection is recorded against (FloorService). */
@RestController
@RequestMapping("/api/v1/venues/{venueId}/floors")
public class FloorController {

    public record CreateFloor(int level, @NotBlank String name) {}

    public record UpdateFloor(@NotNull Integer level, @NotBlank String name) {}

    /** source: SURVEYED or MEASURED_ON_SITE. reference: what 0 m is (free text). */
    public record SetElevation(@NotNull Double elevationM, @NotBlank String source, String reference) {}

    private final FloorService floors;

    public FloorController(FloorService floors) {
        this.floors = floors;
    }

    @Operation(summary = "List floors of a venue")
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER','OPERATOR','VIEWER','PUBLIC_VIEWER')")
    public List<Floor> list(@PathVariable UUID venueId) {
        return floors.list(ActorAuthentication.currentActor(), venueId);
    }

    @Operation(summary = "Create a floor (admin, venue-manager)")
    @ProblemResponse(status = 409, description = "FLOOR_LEVEL_TAKEN")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER')")
    public Floor create(@PathVariable UUID venueId, @Valid @RequestBody CreateFloor body) {
        return floors.create(ActorAuthentication.currentActor(), venueId, body.level(), body.name());
    }

    @Operation(summary = "Rename a floor or change its level (admin, venue-manager)")
    @ProblemResponse(status = 400, description = "FLOOR_ELEVATION_INCONSISTENT: the new level contradicts recorded elevations")
    @ProblemResponse(status = 409, description = "FLOOR_LEVEL_TAKEN")
    @PutMapping("/{floorId}")
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER')")
    public Floor update(@PathVariable UUID venueId, @PathVariable UUID floorId, @Valid @RequestBody UpdateFloor body) {
        return floors.update(ActorAuthentication.currentActor(), venueId, floorId, body.level(), body.name());
    }

    @Operation(summary = "Record a floor's measured or surveyed elevation (admin, venue-manager)",
            description = "Never estimated: an elevation is what someone measured or took from a survey. Refused when it "
                + "contradicts the order of levels or a registered stair or ramp.")
    @ProblemResponse(status = 400, description = "INVALID_ELEVATION, FLOOR_ELEVATION_INCONSISTENT")
    @PutMapping("/{floorId}/elevation")
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER')")
    public Floor setElevation(@PathVariable UUID venueId, @PathVariable UUID floorId, @Valid @RequestBody SetElevation body) {
        return floors.setElevation(ActorAuthentication.currentActor(), venueId, floorId, body.elevationM(), body.source(), body.reference());
    }

    @Operation(summary = "Withdraw a floor's recorded elevation (admin, venue-manager)")
    @DeleteMapping("/{floorId}/elevation")
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER')")
    public Floor clearElevation(@PathVariable UUID venueId, @PathVariable UUID floorId) {
        return floors.clearElevation(ActorAuthentication.currentActor(), venueId, floorId);
    }

    @Operation(summary = "Delete a floor that nothing relies on (admin, venue-manager)")
    @ProblemResponse(status = 409, description = "FLOOR_IN_USE: it publishes a scan version, has connections or open captures")
    @DeleteMapping("/{floorId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER')")
    public void delete(@PathVariable UUID venueId, @PathVariable UUID floorId) {
        floors.delete(ActorAuthentication.currentActor(), venueId, floorId);
    }

    @Operation(summary = "Check the venue's floors, elevations and floor connections against each other",
            description = "Where two floors are calibrated to surveyed venue control points, connection landings are compared "
                + "in that shared datum. Floors with local frames are reported as not comparable.")
    @GetMapping("/consistency")
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER','OPERATOR')")
    public ConsistencyReport consistency(@PathVariable UUID venueId) {
        return floors.consistency(ActorAuthentication.currentActor(), venueId);
    }
}
