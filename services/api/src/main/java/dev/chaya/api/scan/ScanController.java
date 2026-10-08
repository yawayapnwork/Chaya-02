package dev.chaya.api.scan;

import dev.chaya.api.processing.JobStage;
import dev.chaya.api.scan.ScanService.CreatedScan;
import dev.chaya.api.security.ActorAuthentication;
import dev.chaya.api.web.ProblemResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/venues/{venueId}")
@PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER','OPERATOR')")
public class ScanController {

    public record CreateScan(UUID floorId) {}

    public record EnqueueJob(@NotNull JobStage stage) {}

    private final ScanService scans;

    public ScanController(ScanService scans) {
        this.scans = scans;
    }

    @Operation(summary = "Create a scan (legacy job API)")
    @PostMapping("/scans")
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedScan createScan(@PathVariable UUID venueId, @RequestBody(required = false) CreateScan body) {
        return scans.createScan(ActorAuthentication.currentActor(), venueId, body == null ? null : body.floorId());
    }

    @Operation(summary = "Enqueue one processing stage for a scan (legacy job API)")
    @PostMapping("/scans/{scanId}/jobs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, UUID> enqueue(@PathVariable UUID venueId, @PathVariable UUID scanId,
                                     @Valid @RequestBody EnqueueJob body) {
        return Map.of("jobId", scans.enqueueJob(ActorAuthentication.currentActor(), venueId, scanId, body.stage()));
    }

    @Operation(summary = "Cancel a job")
    @ProblemResponse(status = 409, description = "INVALID_JOB_TRANSITION")
    @PostMapping("/jobs/{jobId}/cancel")
    public void cancel(@PathVariable UUID venueId, @PathVariable UUID jobId) {
        scans.cancelJob(ActorAuthentication.currentActor(), venueId, jobId);
    }

    @Operation(summary = "Retry a failed job")
    @ProblemResponse(status = 409, description = "INVALID_JOB_TRANSITION")
    @PostMapping("/jobs/{jobId}/retry")
    public void retry(@PathVariable UUID venueId, @PathVariable UUID jobId) {
        scans.retryJob(ActorAuthentication.currentActor(), venueId, jobId);
    }
}
