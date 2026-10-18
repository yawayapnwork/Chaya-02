package dev.chaya.api.stitch;

import dev.chaya.api.security.ActorAuthentication;
import dev.chaya.api.stitch.StitchDtos.CreateRoom;
import dev.chaya.api.stitch.StitchDtos.RoomView;
import dev.chaya.api.stitch.StitchDtos.StitchRequest;
import dev.chaya.api.stitch.StitchDtos.StitchView;
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

/**
 * Rooms of a floor and multi-room stitching (docs/multi-room.md). A room is captured with the ordinary capture endpoints
 * (POST /venues/{v}/captures with its floorId and roomId); a stitch is followed, retried and cancelled through its capture
 * ({@code captureId} in the view) with the ordinary processing endpoints and stream.
 */
@RestController
@RequestMapping("/api/v1/venues/{venueId}/floors/{floorId}")
public class StitchController {

    private final StitchService stitches;

    public StitchController(StitchService stitches) {
        this.stitches = stitches;
    }

    @Operation(summary = "The floor's rooms, each with its latest room version and capture")
    @GetMapping("/rooms")
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER','OPERATOR','VIEWER')")
    public List<RoomView> rooms(@PathVariable UUID venueId, @PathVariable UUID floorId) {
        return stitches.rooms(ActorAuthentication.currentActor(), venueId, floorId);
    }

    @Operation(summary = "Add a room to the floor (admin, venue-manager, operator)")
    @ProblemResponse(status = 400, description = "ROOM_KIND_INVALID")
    @ProblemResponse(status = 409, description = "ROOM_NAME_TAKEN")
    @PostMapping("/rooms")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER','OPERATOR')")
    public RoomView createRoom(@PathVariable UUID venueId, @PathVariable UUID floorId, @Valid @RequestBody CreateRoom body) {
        return stitches.createRoom(ActorAuthentication.currentActor(), venueId, floorId, body);
    }

    @Operation(summary = "Finalize the room's latest reconstruction once it is calibrated",
            description = "A room version is never published as the floor's; it can be viewed on its own and stitched.")
    @ProblemResponse(status = 409, description = "NOT_CALIBRATED, VERSION_INCOMPLETE, RUN_NOT_SUCCEEDED")
    @PostMapping("/rooms/{roomId}/versions/finalize-current")
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER','OPERATOR')")
    public RoomView finalizeRoom(@PathVariable UUID venueId, @PathVariable UUID floorId, @PathVariable UUID roomId) {
        return stitches.finalizeRoom(ActorAuthentication.currentActor(), venueId, floorId, roomId);
    }

    @Operation(summary = "Stitch finalized room versions into the floor's reconstruction",
            description = "Registers the rooms into the anchor room's frame, combines their Gaussians, re-derives the floor's "
                + "planes, viewer asset and navigation, and publishes the result only when every stage succeeded and every "
                + "room was placed consistently. See docs/multi-room.md.")
    @ProblemResponse(status = 400, description = "STITCH_ROOMS_INVALID")
    @ProblemResponse(status = 409, description = "NOT_CALIBRATED, ROOM_NOT_RECONSTRUCTED, VERSION_NOT_FINALIZED, VERSION_INCOMPLETE")
    @PostMapping("/stitches")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER','OPERATOR')")
    public StitchView startStitch(@PathVariable UUID venueId, @PathVariable UUID floorId, @Valid @RequestBody StitchRequest body) {
        return stitches.initiate(ActorAuthentication.currentActor(), venueId, floorId, body);
    }

    @Operation(summary = "The floor's stitches, newest first")
    @GetMapping("/stitches")
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER','OPERATOR','VIEWER')")
    public List<StitchView> listStitches(@PathVariable UUID venueId, @PathVariable UUID floorId) {
        return stitches.list(ActorAuthentication.currentActor(), venueId, floorId);
    }

    @Operation(summary = "One stitch: its rooms, how each was registered, and what the stitch measured")
    @GetMapping("/stitches/{stitchId}")
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER','OPERATOR','VIEWER')")
    public StitchView getStitch(@PathVariable UUID venueId, @PathVariable UUID floorId, @PathVariable UUID stitchId) {
        return stitches.get(ActorAuthentication.currentActor(), venueId, floorId, stitchId);
    }
}
