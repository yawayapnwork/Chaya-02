package dev.chaya.api.ops;

import dev.chaya.api.ops.OpsDtos.Access;
import dev.chaya.api.ops.OpsDtos.Audit;
import dev.chaya.api.ops.OpsDtos.Coverage;
import dev.chaya.api.ops.OpsDtos.Failures;
import dev.chaya.api.ops.OpsDtos.Jobs;
import dev.chaya.api.ops.OpsDtos.Overview;
import dev.chaya.api.ops.OpsDtos.Rescans;
import dev.chaya.api.ops.OpsDtos.SearchAnalytics;
import dev.chaya.api.ops.OpsDtos.Storage;
import dev.chaya.api.security.ActorAuthentication;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only operations dashboard for venue staff. The class-level rule keeps out public viewers and service
 * accounts; the per-section role rule (OpsSection) and the venue check are applied by OpsDashboardService.
 * Actions (retry, cancel) are not duplicated here: the dashboard calls the existing processing endpoints.
 */
@RestController
@RequestMapping("/api/v1/venues/{venueId}/ops")
@PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER','OPERATOR','VIEWER')")
public class OpsDashboardController {

    private final OpsDashboardService ops;

    public OpsDashboardController(OpsDashboardService ops) {
        this.ops = ops;
    }

    /** Which sections the caller may read and whether they may retry/cancel processing. */
    @GetMapping("/access")
    public Access access(@PathVariable UUID venueId) {
        return ops.access(ActorAuthentication.currentActor(), venueId);
    }

    @GetMapping("/overview")
    public Overview overview(@PathVariable UUID venueId) {
        return ops.overview(ActorAuthentication.currentActor(), venueId);
    }

    @GetMapping("/coverage")
    public Coverage coverage(@PathVariable UUID venueId) {
        return ops.coverage(ActorAuthentication.currentActor(), venueId);
    }

    /** status: optional filter (QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED). */
    @GetMapping("/jobs")
    public Jobs jobs(@PathVariable UUID venueId, @RequestParam(required = false) String status,
                     @RequestParam(defaultValue = "50") int limit) {
        return ops.jobs(ActorAuthentication.currentActor(), venueId, status, limit);
    }

    @GetMapping("/failures")
    public Failures failures(@PathVariable UUID venueId, @RequestParam(defaultValue = "" + OpsDashboardService.DEFAULT_DAYS) int days) {
        return ops.failures(ActorAuthentication.currentActor(), venueId, days);
    }

    @GetMapping("/storage")
    public Storage storage(@PathVariable UUID venueId) {
        return ops.storage(ActorAuthentication.currentActor(), venueId);
    }

    @GetMapping("/search-analytics")
    public SearchAnalytics searchAnalytics(@PathVariable UUID venueId,
                                           @RequestParam(defaultValue = "" + OpsDashboardService.DEFAULT_DAYS) int days) {
        return ops.searchAnalytics(ActorAuthentication.currentActor(), venueId, days);
    }

    @GetMapping("/rescans")
    public Rescans rescans(@PathVariable UUID venueId) {
        return ops.rescans(ActorAuthentication.currentActor(), venueId);
    }

    @GetMapping("/audit")
    public Audit audit(@PathVariable UUID venueId, @RequestParam(defaultValue = "100") int limit) {
        return ops.audit(ActorAuthentication.currentActor(), venueId, limit);
    }
}
