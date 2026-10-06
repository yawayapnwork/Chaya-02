package dev.chaya.api.erasure;

import dev.chaya.api.erasure.ErasureService.ErasureView;
import dev.chaya.api.erasure.ErasureService.Target;
import dev.chaya.api.security.ActorAuthentication;
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

    @DeleteMapping("/venues/{venueId}/captures/{captureId}")
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER','OPERATOR','VIEWER')")
    public ResponseEntity<ErasureView> eraseCapture(@PathVariable UUID venueId, @PathVariable UUID captureId,
                                                    @RequestParam(defaultValue = "false") boolean cascade) {
        return answer(erasure.erase(ActorAuthentication.currentActor(), venueId, Target.CAPTURE, captureId, cascade));
    }

    @DeleteMapping("/venues/{venueId}/scan-versions/{scanVersionId}")
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER','OPERATOR','VIEWER')")
    public ResponseEntity<ErasureView> eraseScanVersion(@PathVariable UUID venueId, @PathVariable UUID scanVersionId,
                                                        @RequestParam(defaultValue = "false") boolean cascade) {
        return answer(erasure.erase(ActorAuthentication.currentActor(), venueId, Target.SCAN_VERSION, scanVersionId, cascade));
    }

    @DeleteMapping("/venues/{venueId}")
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER','OPERATOR','VIEWER')")
    public ResponseEntity<ErasureView> eraseVenue(@PathVariable UUID venueId) {
        return answer(erasure.erase(ActorAuthentication.currentActor(), venueId, Target.VENUE, venueId, true));
    }

    @GetMapping("/erasures/{erasureId}")
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER')")
    public ErasureView get(@PathVariable UUID erasureId) {
        return erasure.get(ActorAuthentication.currentActor(), erasureId);
    }

    @GetMapping("/erasures")
    @PreAuthorize("hasRole('ADMIN')")
    public List<ErasureView> list(@RequestParam(defaultValue = "100") int limit) {
        return erasure.list(ActorAuthentication.currentActor(), limit);
    }

    private static ResponseEntity<ErasureView> answer(ErasureView v) {
        return ResponseEntity.status(v.completed() ? HttpStatus.OK : HttpStatus.ACCEPTED).body(v);
    }
}
