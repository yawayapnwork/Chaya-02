package dev.chaya.api.rescan;

import dev.chaya.api.rescan.RescanDtos.RescanInitiated;
import dev.chaya.api.rescan.RescanDtos.RescanRequest;
import dev.chaya.api.rescan.RescanDtos.ScanVersionView;
import dev.chaya.api.security.ActorAuthentication;
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
 * Steps 1-2 of the incremental re-scan flow (docs/rescan.md): select an existing version and the changed
 * region. The rest (upload, start processing) reuses the ordinary capture endpoints under
 * {@code /venues/{venueId}/captures/{captureId}/...} exactly as they are -- this controller only creates
 * the region-scoped capture_session those endpoints then operate on.
 */
@RestController
@RequestMapping("/api/v1/venues/{venueId}/floors/{floorId}")
public class RescanController {

    private final RescanService rescan;

    public RescanController(RescanService rescan) {
        this.rescan = rescan;
    }

    @Operation(summary = "Start a region re-scan of the floor from a FINALIZED source version",
            description = "See docs/incremental-rescan.md.")
    @ProblemResponse(status = 400, description = "REGION_TOO_SMALL, REGION_TOO_LARGE, INVALID_JSON")
    @ProblemResponse(status = 409, description = "VERSION_NOT_FINALIZED, VERSION_WRONG_FLOOR, NOT_CALIBRATED")
    @PostMapping("/rescan")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER','OPERATOR')")
    public RescanInitiated initiate(@PathVariable UUID venueId, @PathVariable UUID floorId, @Valid @RequestBody RescanRequest body) {
        return rescan.initiate(ActorAuthentication.currentActor(), venueId, floorId, body);
    }

    @Operation(summary = "The floor's scan versions, newest first")
    @GetMapping("/scan-versions")
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER','OPERATOR','VIEWER')")
    public List<ScanVersionView> listVersions(@PathVariable UUID venueId, @PathVariable UUID floorId) {
        return rescan.listVersions(ActorAuthentication.currentActor(), venueId, floorId);
    }

    /** Bootstraps version 1 from the floor's latest successful full-venue reconstruction, so there is
     * something to select as the source of the first re-scan (see RescanService#finalizeCurrent). */
    @Operation(summary = "Bootstrap version 1 from the floor's latest successful full-venue reconstruction")
    @ProblemResponse(status = 409, description = "VERSION_INCOMPLETE, VERSION_INCONSISTENT, NOT_CALIBRATED, or "
            + "nothing to finalize")
    @PostMapping("/scan-versions/finalize-current")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER','OPERATOR')")
    public ScanVersionView finalizeCurrent(@PathVariable UUID venueId, @PathVariable UUID floorId) {
        return rescan.finalizeCurrent(ActorAuthentication.currentActor(), venueId, floorId);
    }
}
