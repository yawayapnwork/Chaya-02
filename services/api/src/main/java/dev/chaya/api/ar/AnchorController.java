package dev.chaya.api.ar;

import dev.chaya.api.ar.ArDtos.Anchor;
import dev.chaya.api.ar.ArDtos.AnchorObservation;
import dev.chaya.api.ar.ArDtos.AnchorRequest;
import dev.chaya.api.ar.ArDtos.RelocalizationRequest;
import dev.chaya.api.ar.ArDtos.RelocalizationResponse;
import dev.chaya.api.security.ActorAuthentication;
import jakarta.validation.Valid;
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

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER','OPERATOR','VIEWER','PUBLIC_VIEWER')")
    public List<Anchor> list(@PathVariable UUID venueId, @PathVariable UUID floorId) {
        return anchors.list(ActorAuthentication.currentActor(), venueId, floorId);
    }

    @GetMapping("/{anchorId}")
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER','OPERATOR','VIEWER','PUBLIC_VIEWER')")
    public Anchor get(@PathVariable UUID venueId, @PathVariable UUID floorId, @PathVariable UUID anchorId) {
        return anchors.get(ActorAuthentication.currentActor(), venueId, floorId, anchorId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER','OPERATOR')")
    public Anchor create(@PathVariable UUID venueId, @PathVariable UUID floorId, @Valid @RequestBody AnchorRequest body) {
        return anchors.create(ActorAuthentication.currentActor(), venueId, floorId, body);
    }

    @PutMapping("/{anchorId}")
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER','OPERATOR')")
    public Anchor update(@PathVariable UUID venueId, @PathVariable UUID floorId, @PathVariable UUID anchorId,
                         @Valid @RequestBody AnchorRequest body) {
        return anchors.update(ActorAuthentication.currentActor(), venueId, floorId, anchorId, body);
    }

    @PostMapping("/{anchorId}/calibrate")
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER','OPERATOR')")
    public Anchor calibrate(@PathVariable UUID venueId, @PathVariable UUID floorId, @PathVariable UUID anchorId) {
        return anchors.calibrate(ActorAuthentication.currentActor(), venueId, floorId, anchorId);
    }

    @DeleteMapping("/{anchorId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER')")
    public void delete(@PathVariable UUID venueId, @PathVariable UUID floorId, @PathVariable UUID anchorId) {
        anchors.delete(ActorAuthentication.currentActor(), venueId, floorId, anchorId);
    }

    /** Relocalization never trusts a client-computed transform -- the client reports raw marker
     * observations and the server solves and returns the transform (see AnchorService#relocalize), so the
     * math is auditable and consistent regardless of which client (Android WebXR or iOS ARKit) called it. */
    @PostMapping("/relocalize")
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER','OPERATOR','VIEWER','PUBLIC_VIEWER')")
    public RelocalizationResponse relocalize(@PathVariable UUID venueId, @PathVariable UUID floorId,
                                             @Valid @RequestBody RelocalizationRequest body) {
        List<AnchorObservation> observations = body.observations();
        return anchors.relocalize(ActorAuthentication.currentActor(), venueId, floorId, observations);
    }
}
