package dev.chaya.api.job;

import dev.chaya.api.audit.AuditService;
import dev.chaya.api.pipeline.PipelineDtos.Heartbeat;
import dev.chaya.api.pipeline.PipelineDtos.StageReport;
import dev.chaya.api.pipeline.PipelineDtos.WorkOrder;
import dev.chaya.api.pipeline.PipelineService;
import dev.chaya.api.processing.JobStage;
import dev.chaya.api.processing.ProcessingJobRepository;
import dev.chaya.api.processing.ProcessingJobRepository.JobScope;
import dev.chaya.api.security.Actor;
import dev.chaya.api.security.ActorAuthentication;
import dev.chaya.api.web.ApiException;
import dev.chaya.api.web.NotFoundException;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Worker-facing API (control plane -> execution plane). Reachable only with a Keycloak service-account
 * token carrying the `service` realm role (URL rule in SecurityConfig plus @PreAuthorize here).
 *
 * <p>Pipeline jobs are driven with claim -> heartbeat* -> report. complete/fail exist only for legacy
 * jobs that are not part of a pipeline run.
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

        @Transactional
        public void complete(Actor worker, UUID jobId) {
            requireLegacy(jobId);
            JobScope scope = jobs.scope(jobId).orElseThrow(() -> new NotFoundException("job not found"));
            jobs.succeed(jobId);
            audit.successInOrganization(worker, scope.organizationId(), scope.venueId(), "job.complete", "processing_job", jobId, Map.of());
        }

        @Transactional
        public void fail(Actor worker, UUID jobId, String code, String message) {
            requireLegacy(jobId);
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

    @PostMapping("/{jobId}/heartbeat")
    public Heartbeat heartbeat(@PathVariable UUID jobId, @Valid @RequestBody HeartbeatRequest body) {
        return pipeline.heartbeat(jobId, body.workerId());
    }

    @PostMapping("/{jobId}/report")
    public void report(@PathVariable UUID jobId, @RequestBody StageReport body) {
        pipeline.report(ActorAuthentication.currentActor(), jobId, body);
    }

    @PostMapping("/{jobId}/complete")
    public void complete(@PathVariable UUID jobId) {
        legacy.complete(ActorAuthentication.currentActor(), jobId);
    }

    @PostMapping("/{jobId}/fail")
    public void fail(@PathVariable UUID jobId, @Valid @RequestBody FailRequest body) {
        legacy.fail(ActorAuthentication.currentActor(), jobId, body.errorCode(), body.errorMessage());
    }
}
