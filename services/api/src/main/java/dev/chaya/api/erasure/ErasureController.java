package dev.chaya.api.erasure;

import dev.chaya.api.erasure.ErasureService.ErasureView;
import dev.chaya.api.erasure.ErasureService.Target;
import dev.chaya.api.security.ActorAuthentication;
import dev.chaya.api.web.ProblemResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Data erasure (docs/privacy-erasure.md). Each DELETE removes the rows at once and answers with the erasure record:
 * 200 when every object is gone too, 202 while some objects are still being deleted (follow GET /erasures/{id}).
 * Repeating a DELETE returns the same record. Who may erase what is decided (and refusals audited) in ErasureService;
 * the role lists here only keep non-members and public links out early.
 */
@RestController
@RequestMapping("/api/v1")
public class ErasureController {

    private final ErasureService erasure;

    public ErasureController(ErasureService erasure) {
        this.erasure = erasure;
    }

    @Operation(summary = "Erase a capture and everything derived from it",
            description = "Raw media, frames, PII staging, reconstruction artifacts, versions, detections and their "
                + "embeddings (docs/privacy-erasure.md). Admin or the venue's manager. cascade=true also erases re-scans "
                + "derived from it. Idempotent.")
    @ApiResponse(responseCode = "200", description = "Erasure record, COMPLETED")
    @ApiResponse(responseCode = "202", description = "Erasure record, OBJECTS_PENDING: rows gone, objects still being deleted",
        content = @Content(schema = @Schema(implementation = ErasureView.class)))
    @ProblemResponse(status = 403, description = "ERASURE_NOT_PERMITTED")
    @ProblemResponse(status = 409, description = "ERASURE_HAS_DEPENDENTS (repeat with cascade=true)")
    @DeleteMapping("/venues/{venueId}/captures/{captureId}")
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER','OPERATOR','VIEWER')")
    public ResponseEntity<ErasureView> eraseCapture(@PathVariable UUID venueId, @PathVariable UUID captureId,
                                                    @RequestParam(defaultValue = "false") boolean cascade) {
        return answer(erasure.erase(ActorAuthentication.currentActor(), venueId, Target.CAPTURE, captureId, cascade));
    }

    @Operation(summary = "Erase a scan version, its run's outputs and every version derived from it",
            description = "The capture it was made from is kept (docs/privacy-erasure.md). Admin or the venue's "
                + "manager. cascade=true erases derived re-scans. Idempotent.")
    @ApiResponse(responseCode = "200", description = "Erasure record, COMPLETED")
    @ApiResponse(responseCode = "202", description = "Erasure record, OBJECTS_PENDING",
        content = @Content(schema = @Schema(implementation = ErasureView.class)))
    @ProblemResponse(status = 403, description = "ERASURE_NOT_PERMITTED")
    @ProblemResponse(status = 409, description = "ERASURE_HAS_DEPENDENTS")
    @DeleteMapping("/venues/{venueId}/scan-versions/{scanVersionId}")
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER','OPERATOR','VIEWER')")
    public ResponseEntity<ErasureView> eraseScanVersion(@PathVariable UUID venueId, @PathVariable UUID scanVersionId,
                                                        @RequestParam(defaultValue = "false") boolean cascade) {
        return answer(erasure.erase(ActorAuthentication.currentActor(), venueId, Target.SCAN_VERSION, scanVersionId, cascade));
    }

    @Operation(summary = "Erase the venue",
            description = "Every capture, version, artifact, POI, anchor, floor, search query and public link; the "
                + "venue is 404 afterwards (docs/privacy-erasure.md). Organization admin only. Idempotent.")
    @ApiResponse(responseCode = "200", description = "Erasure record, COMPLETED")
    @ApiResponse(responseCode = "202", description = "Erasure record, OBJECTS_PENDING",
        content = @Content(schema = @Schema(implementation = ErasureView.class)))
    @ProblemResponse(status = 403, description = "ERASURE_NOT_PERMITTED")
    @DeleteMapping("/venues/{venueId}")
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER','OPERATOR','VIEWER')")
    public ResponseEntity<ErasureView> eraseVenue(@PathVariable UUID venueId) {
        return answer(erasure.erase(ActorAuthentication.currentActor(), venueId, Target.VENUE, venueId, true));
    }

    @Operation(summary = "One erasure record",
            description = "An admin of its organization, or its requester. status OBJECTS_PENDING or COMPLETED, "
                + "attempts, last error, counts.")
    @GetMapping("/erasures/{erasureId}")
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER')")
    public ErasureView get(@PathVariable UUID erasureId) {
        return erasure.get(ActorAuthentication.currentActor(), erasureId);
    }

    @Operation(summary = "The organization's erasure records, newest first (query: limit<=500)")
    @GetMapping("/erasures")
    @PreAuthorize("hasRole('ADMIN')")
    public List<ErasureView> list(@RequestParam(defaultValue = "100") int limit) {
        return erasure.list(ActorAuthentication.currentActor(), limit);
    }

    private static ResponseEntity<ErasureView> answer(ErasureView v) {
        return ResponseEntity.status(v.completed() ? HttpStatus.OK : HttpStatus.ACCEPTED).body(v);
    }
}
