package dev.chaya.api.hud;

import dev.chaya.api.capture.CaptureService;
import dev.chaya.api.security.Actor;
import dev.chaya.api.security.ActorAuthentication;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Live push of the HUD status via Server-Sent Events. A plain GET (no custom headers needed after the initial
 * request) so it works with a hand-rolled fetch-based SSE reader that can still send the bearer token, unlike
 * the browser's EventSource, which cannot set an Authorization header at all.
 */
@RestController
@RequestMapping("/api/v1/venues/{venueId}/captures/{captureId}/hud")
@PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER','OPERATOR')")
public class CaptureHudStreamController {

    private final CaptureService captures;
    private final CaptureHudBroadcaster broadcaster;

    public CaptureHudStreamController(CaptureService captures, CaptureHudBroadcaster broadcaster) {
        this.captures = captures;
        this.broadcaster = broadcaster;
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable UUID venueId, @PathVariable UUID captureId) {
        Actor actor = ActorAuthentication.currentActor();
        captures.get(actor, venueId, captureId); // venue-guarded; 404 for anything not visible to the caller
        return broadcaster.subscribe(venueId, actor.organizationId(), captureId);
    }
}
