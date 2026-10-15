package dev.chaya.api.capture;

import dev.chaya.api.capture.CaptureService.CaptureView;
import dev.chaya.api.capture.CaptureService.ProcessingStatus;
import dev.chaya.api.security.ActorAuthentication;
import dev.chaya.api.web.ProblemResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Capture ingestion API. Raw captures are for the venue team only: admin, venue-manager, operator. */
@RestController
@RequestMapping("/api/v1/venues/{venueId}/captures")
@PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER','OPERATOR')")
public class CaptureController {

    /** clientSessionId and expectedScanVersion: see CaptureService#create (offline capture recovery). */
    public record CreateCapture(UUID floorId, Map<String, Object> device, Instant startedAt, UUID clientSessionId,
                                CaptureService.ExpectedScanVersion expectedScanVersion) {

        public CreateCapture(UUID floorId, Map<String, Object> device, Instant startedAt) {
            this(floorId, device, startedAt, null, null);
        }
    }

    public record CompleteUpload(Instant endedAt, Double durationSeconds) {}

    private final CaptureService captures;
    private final MediaService media;
    private final ProcessingBroadcaster processing;

    public CaptureController(CaptureService captures, MediaService media, ProcessingBroadcaster processing) {
        this.captures = captures;
        this.media = media;
        this.processing = processing;
    }

    @Operation(summary = "Create a capture session (status CREATED)",
            description = "201 when created; 200 with the existing session when the same operator repeats a clientSessionId.")
    @ProblemResponse(status = 409, description = "CLIENT_SESSION_CONFLICT, SCAN_VERSION_CHANGED")
    @ProblemResponse(status = 410, description = "CAPTURE_ERASED: the session recorded under this clientSessionId was erased")
    @PostMapping
    public ResponseEntity<CaptureView> create(@PathVariable UUID venueId, @RequestBody(required = false) CreateCapture body) {
        CreateCapture b = body == null ? new CreateCapture(null, null, null) : body;
        CaptureService.Created c = captures.create(ActorAuthentication.currentActor(), venueId, b.floorId(), b.device(), b.startedAt(),
            b.clientSessionId(), b.expectedScanVersion());
        return ResponseEntity.status(c.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(c.capture());
    }

    @Operation(summary = "List capture sessions")
    @GetMapping
    public List<CaptureView> list(@PathVariable UUID venueId) {
        return captures.list(ActorAuthentication.currentActor(), venueId);
    }

    @Operation(summary = "Inspect a capture session")
    @GetMapping("/{captureId}")
    public CaptureView get(@PathVariable UUID venueId, @PathVariable UUID captureId) {
        return captures.get(ActorAuthentication.currentActor(), venueId, captureId);
    }

    @Operation(summary = "Open a resumable multipart upload for one file",
            description = "Returns mediaId, partSizeBytes and totalParts.")
    @ProblemResponse(status = 409, description = "CAPTURE_NOT_ACCEPTING_UPLOADS, TOO_MANY_FILES")
    @ProblemResponse(status = 413, description = "FILE_TOO_LARGE: larger than the limit for its kind, or "
            + "DEVICE_METADATA_TOO_LARGE")
    @ProblemResponse(status = 415, description = "UNSUPPORTED_MEDIA_TYPE: content type not allowed")
    @ProblemResponse(status = 409, description = "CLIENT_FILE_CONFLICT: clientFileId already names another file")
    @PostMapping("/{captureId}/media")
    public ResponseEntity<MediaService.InitResult> initMedia(@PathVariable UUID venueId, @PathVariable UUID captureId,
                                                             @RequestBody MediaService.InitRequest body) {
        MediaService.InitResult r = media.init(ActorAuthentication.currentActor(), venueId, captureId, body);
        return ResponseEntity.status(r.existing() ? HttpStatus.OK : HttpStatus.CREATED).body(r);
    }

    @Operation(summary = "List capture artifacts")
    @GetMapping("/{captureId}/media")
    public List<MediaService.MediaView> listMedia(@PathVariable UUID venueId, @PathVariable UUID captureId) {
        return media.list(ActorAuthentication.currentActor(), venueId, captureId);
    }

    @Operation(summary = "Media status and uploaded parts")
    @GetMapping("/{captureId}/media/{mediaId}")
    public MediaService.MediaView getMedia(@PathVariable UUID venueId, @PathVariable UUID captureId,
                                           @PathVariable UUID mediaId) {
        return media.get(ActorAuthentication.currentActor(), venueId, captureId, mediaId);
    }

    /** Raw body = the bytes of one part. Safe to repeat: this is how an interrupted upload resumes. */
    @Operation(summary = "Upload one part",
            description = "Raw body with an exact Content-Length; optional X-Part-Sha256 header. Idempotent: "
                + "repeating a part is how an interrupted upload resumes.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, description = "The bytes of this part",
            content = @Content(mediaType = "application/octet-stream", schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "204", description = "Stored")
    @ProblemResponse(status = 409, description = "MEDIA_NOT_UPLOADING")
    @ProblemResponse(status = 411, description = "LENGTH_REQUIRED")
    @ProblemResponse(status = 413, description = "PART_TOO_LARGE")
    @ProblemResponse(status = 422, description = "CHECKSUM_MISMATCH: the part does not hash to X-Part-Sha256")
    @PutMapping("/{captureId}/media/{mediaId}/parts/{partNumber}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void uploadPart(@PathVariable UUID venueId, @PathVariable UUID captureId, @PathVariable UUID mediaId,
                           @PathVariable int partNumber, HttpServletRequest request,
                           @RequestHeader(value = "X-Part-Sha256", required = false) String partSha256) throws IOException {
        media.uploadPart(ActorAuthentication.currentActor(), venueId, captureId, mediaId, partNumber,
            request.getInputStream(), request.getContentLengthLong(), partSha256);
    }

    @Operation(summary = "Assemble the parts and start asynchronous validation (also retries QUARANTINED)")
    @ApiResponse(responseCode = "202", description = "Validation started")
    @ProblemResponse(status = 409, description = "MISSING_PARTS, MEDIA_NOT_COMPLETABLE")
    @PostMapping("/{captureId}/media/{mediaId}/complete")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void completeMedia(@PathVariable UUID venueId, @PathVariable UUID captureId, @PathVariable UUID mediaId) {
        media.complete(ActorAuthentication.currentActor(), venueId, captureId, mediaId);
    }

    @Operation(summary = "Finish the upload phase (READY_FOR_PROCESSING)")
    @ProblemResponse(status = 409, description = "Not ready: UPLOADS_NOT_SETTLED, INSUFFICIENT_MEDIA, "
            + "INVALID_CAPTURE_TRANSITION, STORAGE_INTEGRITY")
    @PostMapping("/{captureId}/complete-upload")
    public CaptureView completeUpload(@PathVariable UUID venueId, @PathVariable UUID captureId,
                                      @RequestBody(required = false) CompleteUpload body) {
        CompleteUpload b = body == null ? new CompleteUpload(null, null) : body;
        return captures.completeUpload(ActorAuthentication.currentActor(), venueId, captureId, b.endedAt(), b.durationSeconds());
    }

    public record StartProcessing(Boolean privacyEnabled, Integer timeBudgetSeconds) {}

    @Operation(summary = "Start the reconstruction pipeline",
            description = "Body: optional timeBudgetSeconds, privacyEnabled (false is admin only). The first stage is queued.")
    @ProblemResponse(status = 409, description = "INVALID_CAPTURE_TRANSITION: the capture is not READY_FOR_PROCESSING")
    @PostMapping("/{captureId}/processing")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ProcessingStatus startProcessing(@PathVariable UUID venueId, @PathVariable UUID captureId,
                                            @RequestBody(required = false) StartProcessing body) {
        StartProcessing b = body == null ? new StartProcessing(null, null) : body;
        return captures.startProcessing(ActorAuthentication.currentActor(), venueId, captureId, b.privacyEnabled(), b.timeBudgetSeconds());
    }

    /** Re-queues the stage a failed run stopped at. */
    @Operation(summary = "Re-queue the stage a FAILED run stopped at (fresh time budget; bounded retries)")
    @ProblemResponse(status = 409, description = "The run is not FAILED, its retries are exhausted, or "
            + "PII_STAGING_PURGED")
    @PostMapping("/{captureId}/processing/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ProcessingStatus retryProcessing(@PathVariable UUID venueId, @PathVariable UUID captureId) {
        return captures.retryProcessing(ActorAuthentication.currentActor(), venueId, captureId);
    }

    @Operation(summary = "Cancel a RUNNING run")
    @PostMapping("/{captureId}/processing/cancel")
    public ProcessingStatus cancelProcessing(@PathVariable UUID venueId, @PathVariable UUID captureId) {
        return captures.cancelProcessing(ActorAuthentication.currentActor(), venueId, captureId);
    }

    @Operation(summary = "Live processing status over Server-Sent Events",
            description = "Each status event is the full ProcessingStatus, with the status revision as the event id. Send the "
                + "last id received as Last-Event-ID when reconnecting: the current status is sent only if it differs. Events "
                + "session-expired (the token used has expired: reconnect with a new one) and gone (the capture no longer "
                + "exists) end the stream. GET /processing is the snapshot for recovery.")
    @ApiResponse(responseCode = "200", description = "text/event-stream of ProcessingStatus",
        content = @Content(mediaType = "text/event-stream", schema = @Schema(implementation = ProcessingStatus.class)))
    @GetMapping(value = "/{captureId}/processing/stream", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter processingStream(
            @PathVariable UUID venueId, @PathVariable UUID captureId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        var actor = ActorAuthentication.currentActor();
        captures.get(actor, venueId, captureId); // venue-guarded: 404 for anything the caller cannot see
        Long last = null;
        if (lastEventId != null) {
            try {
                last = Long.parseLong(lastEventId.strip());
            } catch (NumberFormatException e) {
                last = null; // not one of ours: send the current status
            }
        }
        return processing.subscribe(venueId, actor.organizationId(), captureId, ActorAuthentication.currentExpiry().orElse(null), last);
    }

    @Operation(summary = "Run status, per-stage execution records and job history",
            description = "status RUNNING/SUCCEEDED/PARTIAL/FAILED/CANCELLED; quality FINAL, PARTIAL or null. revision orders "
                + "statuses; the snapshot to recover from after missed stream events.")
    @GetMapping("/{captureId}/processing")
    public ProcessingStatus processingStatus(@PathVariable UUID venueId, @PathVariable UUID captureId) {
        return captures.processingStatus(ActorAuthentication.currentActor(), venueId, captureId);
    }
}
