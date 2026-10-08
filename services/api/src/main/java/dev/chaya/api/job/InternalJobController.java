package dev.chaya.api.job;

import dev.chaya.api.audit.AuditService;
import dev.chaya.api.pipeline.PipelineDtos.Heartbeat;
import dev.chaya.api.pipeline.PipelineDtos.StageReport;
import dev.chaya.api.pipeline.PipelineDtos.WorkOrder;
import dev.chaya.api.pipeline.PipelineService;
import dev.chaya.api.processing.JobLease;
import dev.chaya.api.processing.JobStage;
import dev.chaya.api.processing.ProcessingJobRepository;
import dev.chaya.api.processing.ProcessingJobRepository.JobScope;
import dev.chaya.api.security.Actor;
import dev.chaya.api.security.ActorAuthentication;
import dev.chaya.api.web.ApiException;
import dev.chaya.api.web.NotFoundException;
import dev.chaya.api.web.ProblemResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Worker-facing API (control plane -> execution plane). Reachable only with a Keycloak service-account
 * token carrying the `service` realm role (URL rule in SecurityConfig plus @PreAuthorize here).
 *
 * <p>Pipeline jobs are driven with claim -> heartbeat* -> report. complete/fail exist only for legacy
 * jobs that are not part of a pipeline run.
 *
 * <p>The service role says a caller is <em>a</em> worker, not which job it may act on. The claim's work order carries a
 * lease token, and heartbeat, report, complete and fail act only for the caller presenting it ({@link JobLease#HEADER});
 * anything else is 409 LEASE_MISMATCH. So no worker can report on, extend or finish a job -- of any tenant -- that it did
 * not claim, or its own job after the lease expired and the job was handed to another worker.
 */
@RestController
@RequestMapping("/api/v1/internal/jobs")
@PreAuthorize("hasRole('SERVICE')")
public class InternalJobController {

    /** Either one stage or a list of stages the worker can execute. */
    public record ClaimRequest(JobStage stage, List<JobStage> stages, String workerId) {}

    public record FailRequest(@NotBlank String errorCode, @NotBlank String errorMessage) {}

    public record HeartbeatRequest(@NotBlank String workerId) {}

    @Service
    public static class LegacyJobService {
        private final ProcessingJobRepository jobs;
        private final AuditService audit;
        private final JdbcClient jdbc;

        LegacyJobService(ProcessingJobRepository jobs, AuditService audit, JdbcClient jdbc) {
            this.jobs = jobs;
            this.audit = audit;
            this.jdbc = jdbc;
        }

        private void requireLegacy(UUID jobId) {
            Boolean inRun = jdbc.sql("SELECT run_id IS NOT NULL FROM processing_job WHERE id = :id").param("id", jobId)
                .query(Boolean.class).optional().orElseThrow(() -> new NotFoundException("job not found"));
            if (inRun) {
                throw new ApiException(HttpStatus.CONFLICT, "USE_STAGE_REPORT",
                    "this job belongs to a pipeline run; submit a stage report instead");
            }
        }

        private void requireLease(UUID jobId, String leaseToken) {
            if (!jobs.holdsLease(jobId, JobLease.hash(leaseToken))) {
                throw new ApiException(HttpStatus.CONFLICT, "LEASE_MISMATCH",
                    "this worker does not hold the job's lease; only the worker that claimed it may finish it");
            }
        }

        @Transactional
        public void complete(Actor worker, UUID jobId, String leaseToken) {
            requireLegacy(jobId);
            requireLease(jobId, leaseToken);
            JobScope scope = jobs.scope(jobId).orElseThrow(() -> new NotFoundException("job not found"));
            jobs.succeed(jobId);
            audit.successInOrganization(worker, scope.organizationId(), scope.venueId(), "job.complete", "processing_job", jobId, Map.of());
        }

        @Transactional
        public void fail(Actor worker, UUID jobId, String leaseToken, String code, String message) {
            requireLegacy(jobId);
            requireLease(jobId, leaseToken);
            JobScope scope = jobs.scope(jobId).orElseThrow(() -> new NotFoundException("job not found"));
            jobs.fail(jobId, code, message);
            audit.successInOrganization(worker, scope.organizationId(), scope.venueId(), "job.fail", "processing_job", jobId,
                Map.of("errorCode", code));
        }
    }

    private final PipelineService pipeline;
    private final LegacyJobService legacy;

    public InternalJobController(PipelineService pipeline, LegacyJobService legacy) {
        this.pipeline = pipeline;
        this.legacy = legacy;
    }

    @Operation(summary = "Worker: claim the oldest queued job of the given stage(s)",
            description = "Returns a work order (inputs, output prefix, deadline, coordinateFrame and, for "
                + "REGION_ALIGNMENT/REGION_SPLICE, parentCoordinateFrame, each null when not calibrated) and the lease "
                + "token to send as X-Chaya-Lease-Token. Service role only.")
    @ApiResponse(responseCode = "200", description = "Work order")
    @ApiResponse(responseCode = "204", description = "Nothing queued")
    @ProblemResponse(status = 400, description = "STAGE_REQUIRED")
    @PostMapping("/claim")
    public ResponseEntity<WorkOrder> claim(@RequestBody ClaimRequest body) {
        List<JobStage> stages = new ArrayList<>();
        if (body.stage() != null) stages.add(body.stage());
        if (body.stages() != null) stages.addAll(body.stages());
        if (stages.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "STAGE_REQUIRED", "provide stage or stages");
        }
        Actor worker = ActorAuthentication.currentActor();
        String workerId = body.workerId() == null || body.workerId().isBlank() ? worker.subject() : body.workerId();
        return pipeline.claim(worker, stages, workerId).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @Operation(summary = "Worker: extend the lease",
            description = "keepGoing=false means stop (cancelled, or the lease was lost).")
    @PostMapping("/{jobId}/heartbeat")
    public Heartbeat heartbeat(@PathVariable UUID jobId, @Valid @RequestBody HeartbeatRequest body,
                               @RequestHeader(name = JobLease.HEADER, required = false) String leaseToken) {
        return pipeline.heartbeat(jobId, leaseToken); // keepGoing=false without the current lease: the worker stops
    }

    @Operation(summary = "Worker: submit the stage record",
            description = "Command, timings, exit status, checksums, log locations, artifacts, error. Verified before "
                + "it is applied.")
    @ApiResponse(responseCode = "200", description = "Applied")
    @ProblemResponse(status = 409, description = "Rejected (ARTIFACT_MISSING, PII_AFTER_PRIVACY, JOB_NOT_RUNNING, "
            + "ARTIFACT_FRAME_MISMATCH, LEASE_MISMATCH, ...)")
    @PostMapping("/{jobId}/report")
    public void report(@PathVariable UUID jobId, @RequestBody StageReport body,
                       @RequestHeader(name = JobLease.HEADER, required = false) String leaseToken) {
        pipeline.report(ActorAuthentication.currentActor(), jobId, leaseToken, body);
    }

    @Operation(summary = "Worker: complete a job outside a pipeline run (legacy scan job)")
    @ProblemResponse(status = 409, description = "USE_STAGE_REPORT (the job belongs to a run), LEASE_MISMATCH, "
            + "INVALID_JOB_TRANSITION")
    @PostMapping("/{jobId}/complete")
    public void complete(@PathVariable UUID jobId, @RequestHeader(name = JobLease.HEADER, required = false) String leaseToken) {
        legacy.complete(ActorAuthentication.currentActor(), jobId, leaseToken);
    }

    @Operation(summary = "Worker: fail a job outside a pipeline run (legacy scan job)")
    @ProblemResponse(status = 409, description = "USE_STAGE_REPORT (the job belongs to a run), LEASE_MISMATCH, "
            + "INVALID_JOB_TRANSITION")
    @PostMapping("/{jobId}/fail")
    public void fail(@PathVariable UUID jobId, @Valid @RequestBody FailRequest body,
                     @RequestHeader(name = JobLease.HEADER, required = false) String leaseToken) {
        legacy.fail(ActorAuthentication.currentActor(), jobId, leaseToken, body.errorCode(), body.errorMessage());
    }
}
