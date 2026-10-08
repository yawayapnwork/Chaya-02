package dev.chaya.api.ar;

import dev.chaya.api.ar.ArDtos.Anchor;
import dev.chaya.api.ar.ArDtos.AnchorObservation;
import dev.chaya.api.ar.ArDtos.AnchorRequest;
import dev.chaya.api.ar.ArDtos.RelocalizationRequest;
import dev.chaya.api.ar.ArDtos.RelocalizationResponse;
import dev.chaya.api.security.ActorAuthentication;
import dev.chaya.api.web.ProblemResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Anchor CRUD and relocalization, scoped to one venue and floor by the URL and re-checked against the
 * caller's token by every {@link AnchorService} method (see docs/security.md). Reads are open to
 * PUBLIC_VIEWER exactly like POIs and navigation routes -- a public AR viewer link is already bound to one
 * venue by the token that authenticated it, so this grants no broader access than the rest of the public
 * viewer surface; it is never a route that takes a venue id from an unauthenticated caller.
 */
@RestController
@RequestMapping("/api/v1/venues/{venueId}/floors/{floorId}/anchors")
public class AnchorController {

    private final AnchorService anchors;

    public AnchorController(AnchorService anchors) {
        this.anchors = anchors;
    }

    @Operation(summary = "List the floor's AR anchors",
            description = "scanVersionId: only the anchors of that FINALIZED scan version.")
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER','OPERATOR','VIEWER','PUBLIC_VIEWER')")
    public List<Anchor> list(@PathVariable UUID venueId, @PathVariable UUID floorId,
                             @RequestParam(required = false) UUID scanVersionId) {
        return anchors.list(ActorAuthentication.currentActor(), venueId, floorId, scanVersionId);
    }

    @Operation(summary = "One AR anchor")
    @GetMapping("/{anchorId}")
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER','OPERATOR','VIEWER','PUBLIC_VIEWER')")
    public Anchor get(@PathVariable UUID venueId, @PathVariable UUID floorId, @PathVariable UUID anchorId) {
        return anchors.get(ActorAuthentication.currentActor(), venueId, floorId, anchorId);
    }

    /** The IMAGE_TARGET's target image: print it at markerSizeMeters wide; the WebXR client tracks this exact image. */
    @Operation(summary = "The IMAGE_TARGET anchor's target image",
            description = "Print it at markerSizeMeters wide; clients track this exact image.")
    @ApiResponse(responseCode = "200", description = "PNG image",
        content = @Content(mediaType = "image/png", schema = @Schema(type = "string", format = "binary")))
    @ProblemResponse(status = 409, description = "NOT_AN_IMAGE_TARGET")
    @GetMapping(value = "/{anchorId}/target.png", produces = MediaType.IMAGE_PNG_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER','OPERATOR','VIEWER','PUBLIC_VIEWER')")
    public ResponseEntity<byte[]> targetImage(@PathVariable UUID venueId, @PathVariable UUID floorId, @PathVariable UUID anchorId) {
        byte[] png = anchors.targetImage(ActorAuthentication.currentActor(), venueId, floorId, anchorId);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).header("Cache-Control", "private, max-age=86400").body(png);
    }

    @Operation(summary = "Create an AR anchor",
            description = "digitalPose is in the floor's current canonical frame (metres, +Z up).")
    @ProblemResponse(status = 409, description = "NOT_CALIBRATED: the floor has no canonical frame")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER','OPERATOR')")
    public Anchor create(@PathVariable UUID venueId, @PathVariable UUID floorId, @Valid @RequestBody AnchorRequest body) {
        return anchors.create(ActorAuthentication.currentActor(), venueId, floorId, body);
    }

    @Operation(summary = "Replace an AR anchor")
    @ProblemResponse(status = 409, description = "NOT_CALIBRATED: the floor has no canonical frame")
    @PutMapping("/{anchorId}")
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER','OPERATOR')")
    public Anchor update(@PathVariable UUID venueId, @PathVariable UUID floorId, @PathVariable UUID anchorId,
                         @Valid @RequestBody AnchorRequest body) {
        return anchors.update(ActorAuthentication.currentActor(), venueId, floorId, anchorId, body);
    }

    @Operation(summary = "Mark an anchor calibrated against the floor's current frame")
    @PostMapping("/{anchorId}/calibrate")
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER','OPERATOR')")
    public Anchor calibrate(@PathVariable UUID venueId, @PathVariable UUID floorId, @PathVariable UUID anchorId) {
        return anchors.calibrate(ActorAuthentication.currentActor(), venueId, floorId, anchorId);
    }

    @Operation(summary = "Delete an AR anchor")
    @DeleteMapping("/{anchorId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER')")
    public void delete(@PathVariable UUID venueId, @PathVariable UUID floorId, @PathVariable UUID anchorId) {
        anchors.delete(ActorAuthentication.currentActor(), venueId, floorId, anchorId);
    }

    /** Relocalization never trusts a client-computed transform -- the client reports raw marker
     * observations and the server solves and returns the transform (see AnchorService#relocalize), so the
     * math is auditable and consistent regardless of which client (Android WebXR or iOS ARKit) called it. */
    @Operation(summary = "Relocalize a device from raw marker observations",
            description = "The client reports what it observed; the server solves and returns the device-to-venue transform.")
    @ProblemResponse(status = 409, description = "ANCHOR_NOT_CALIBRATED, ANCHOR_FRAME_STALE, ANCHOR_UNVERSIONED, "
            + "ANCHOR_VERSION_MISMATCH, VERSION_MISMATCH, VERSION_WRONG_FLOOR, RELOCALIZATION_GRAVITY_MISMATCH")
    @PostMapping("/relocalize")
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER','OPERATOR','VIEWER','PUBLIC_VIEWER')")
    public RelocalizationResponse relocalize(@PathVariable UUID venueId, @PathVariable UUID floorId,
                                             @Valid @RequestBody RelocalizationRequest body) {
        List<AnchorObservation> observations = body.observations();
        return anchors.relocalize(ActorAuthentication.currentActor(), venueId, floorId, observations, body.scanVersionId());
    }
}
