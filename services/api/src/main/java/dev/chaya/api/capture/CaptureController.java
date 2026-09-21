package dev.chaya.api.capture;

import dev.chaya.api.capture.CaptureService.CaptureView;
import dev.chaya.api.capture.CaptureService.ProcessingStatus;
import dev.chaya.api.security.ActorAuthentication;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Capture ingestion API. Raw captures are for the venue team only: admin, venue-manager, operator. */
@RestController
@RequestMapping("/api/v1/venues/{venueId}/captures")
@PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER','OPERATOR')")
public class CaptureController {

    public record CreateCapture(UUID floorId, Map<String, Object> device, Instant startedAt) {}

    public record CompleteUpload(Instant endedAt, Double durationSeconds) {}

    private final CaptureService captures;
    private final MediaService media;

    public CaptureController(CaptureService captures, MediaService media) {
        this.captures = captures;
        this.media = media;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CaptureView create(@PathVariable UUID venueId, @RequestBody(required = false) CreateCapture body) {
        CreateCapture b = body == null ? new CreateCapture(null, null, null) : body;
        return captures.create(ActorAuthentication.currentActor(), venueId, b.floorId(), b.device(), b.startedAt());
    }

    @GetMapping
    public List<CaptureView> list(@PathVariable UUID venueId) {
        return captures.list(ActorAuthentication.currentActor(), venueId);
    }

    @GetMapping("/{captureId}")
    public CaptureView get(@PathVariable UUID venueId, @PathVariable UUID captureId) {
        return captures.get(ActorAuthentication.currentActor(), venueId, captureId);
    }

    @PostMapping("/{captureId}/media")
    @ResponseStatus(HttpStatus.CREATED)
    public MediaService.InitResult initMedia(@PathVariable UUID venueId, @PathVariable UUID captureId,
                                             @RequestBody MediaService.InitRequest body) {
        return media.init(ActorAuthentication.currentActor(), venueId, captureId, body);
    }

    @GetMapping("/{captureId}/media")
    public List<MediaService.MediaView> listMedia(@PathVariable UUID venueId, @PathVariable UUID captureId) {
        return media.list(ActorAuthentication.currentActor(), venueId, captureId);
    }

    @GetMapping("/{captureId}/media/{mediaId}")
    public MediaService.MediaView getMedia(@PathVariable UUID venueId, @PathVariable UUID captureId,
                                           @PathVariable UUID mediaId) {
        return media.get(ActorAuthentication.currentActor(), venueId, captureId, mediaId);
    }

    /** Raw body = the bytes of one part. Safe to repeat: this is how an interrupted upload resumes. */
    @PutMapping("/{captureId}/media/{mediaId}/parts/{partNumber}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void uploadPart(@PathVariable UUID venueId, @PathVariable UUID captureId, @PathVariable UUID mediaId,
                           @PathVariable int partNumber, HttpServletRequest request,
                           @RequestHeader(value = "X-Part-Sha256", required = false) String partSha256) throws IOException {
        media.uploadPart(ActorAuthentication.currentActor(), venueId, captureId, mediaId, partNumber,
            request.getInputStream(), request.getContentLengthLong(), partSha256);
    }

    @PostMapping("/{captureId}/media/{mediaId}/complete")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void completeMedia(@PathVariable UUID venueId, @PathVariable UUID captureId, @PathVariable UUID mediaId) {
        media.complete(ActorAuthentication.currentActor(), venueId, captureId, mediaId);
    }

    @PostMapping("/{captureId}/complete-upload")
    public CaptureView completeUpload(@PathVariable UUID venueId, @PathVariable UUID captureId,
                                      @RequestBody(required = false) CompleteUpload body) {
        CompleteUpload b = body == null ? new CompleteUpload(null, null) : body;
        return captures.completeUpload(ActorAuthentication.currentActor(), venueId, captureId, b.endedAt(), b.durationSeconds());
    }

    public record StartProcessing(Boolean privacyEnabled, Integer timeBudgetSeconds) {}

    @PostMapping("/{captureId}/processing")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ProcessingStatus startProcessing(@PathVariable UUID venueId, @PathVariable UUID captureId,
                                            @RequestBody(required = false) StartProcessing body) {
        StartProcessing b = body == null ? new StartProcessing(null, null) : body;
        return captures.startProcessing(ActorAuthentication.currentActor(), venueId, captureId, b.privacyEnabled(), b.timeBudgetSeconds());
    }

    /** Re-queues the stage a failed run stopped at. */
    @PostMapping("/{captureId}/processing/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ProcessingStatus retryProcessing(@PathVariable UUID venueId, @PathVariable UUID captureId) {
        return captures.retryProcessing(ActorAuthentication.currentActor(), venueId, captureId);
    }

    @PostMapping("/{captureId}/processing/cancel")
    public ProcessingStatus cancelProcessing(@PathVariable UUID venueId, @PathVariable UUID captureId) {
        return captures.cancelProcessing(ActorAuthentication.currentActor(), venueId, captureId);
    }

    @GetMapping("/{captureId}/processing")
    public ProcessingStatus processingStatus(@PathVariable UUID venueId, @PathVariable UUID captureId) {
        return captures.processingStatus(ActorAuthentication.currentActor(), venueId, captureId);
    }
}
