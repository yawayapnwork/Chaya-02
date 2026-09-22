package dev.chaya.api.hud;

import dev.chaya.api.hud.CaptureHudDtos.HudStatus;
import dev.chaya.api.hud.CaptureHudDtos.PoseBatchRequest;
import dev.chaya.api.hud.CaptureHudDtos.QualityBatchRequest;
import dev.chaya.api.hud.CaptureHudDtos.SetSceneRequest;
import dev.chaya.api.security.ActorAuthentication;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Durable REST surface for the live capture HUD: the room outline, appended pose and quality samples, and a
 * poll-friendly snapshot of the current status. See CaptureHudStreamController for the live (SSE) push of the
 * same status, and docs/capture-hud.md for the whole feature.
 */
@RestController
@RequestMapping("/api/v1/venues/{venueId}/captures/{captureId}/hud")
@PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER','OPERATOR')")
public class CaptureHudController {

    private final CaptureHudService service;

    public CaptureHudController(CaptureHudService service) {
        this.service = service;
    }

    @PutMapping("/scene")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setScene(@PathVariable UUID venueId, @PathVariable UUID captureId, @Valid @RequestBody SetSceneRequest body) {
        service.setScene(ActorAuthentication.currentActor(), venueId, captureId, body);
    }

    @PostMapping("/pose")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void addPoses(@PathVariable UUID venueId, @PathVariable UUID captureId, @Valid @RequestBody PoseBatchRequest body) {
        service.addPoses(ActorAuthentication.currentActor(), venueId, captureId, body);
    }

    @PostMapping("/quality")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void addQuality(@PathVariable UUID venueId, @PathVariable UUID captureId, @Valid @RequestBody QualityBatchRequest body) {
        service.addQuality(ActorAuthentication.currentActor(), venueId, captureId, body);
    }

    @GetMapping("/status")
    public HudStatus status(@PathVariable UUID venueId, @PathVariable UUID captureId) {
        return service.status(ActorAuthentication.currentActor(), venueId, captureId);
    }
}
