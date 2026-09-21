package dev.chaya.api.job;

import dev.chaya.api.audit.AuditService;
import dev.chaya.api.processing.JobStage;
import dev.chaya.api.processing.ProcessingJobRepository;
import dev.chaya.api.processing.ProcessingJobRepository.ClaimedJob;
import dev.chaya.api.processing.ProcessingJobRepository.JobScope;
import dev.chaya.api.security.Actor;
import dev.chaya.api.security.ActorAuthentication;
import dev.chaya.api.web.NotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Worker-facing API. Reachable only with a Keycloak service-account token carrying the `service`
 * realm role (URL rule in SecurityConfig plus @PreAuthorize here). Workers are never anonymous.
 */
@RestController
@RequestMapping("/api/v1/internal/jobs")
@PreAuthorize("hasRole('SERVICE')")
public class InternalJobController {

    public record ClaimRequest(@NotNull JobStage stage) {}

    public record FailRequest(@NotBlank String errorCode, @NotBlank String errorMessage) {}

    @Service
    public static class WorkerJobService {
        private final ProcessingJobRepository jobs;
        private final AuditService audit;

        WorkerJobService(ProcessingJobRepository jobs, AuditService audit) {
            this.jobs = jobs;
            this.audit = audit;
        }

        @Transactional
        public java.util.Optional<ClaimedJob> claim(Actor worker, JobStage stage) {
            var claimed = jobs.claim(stage);
            claimed.ifPresent(j -> audit.successInOrganization(worker, j.organizationId(), j.venueId(), "job.claim",
                "processing_job", j.id(), Map.of("stage", stage.name())));
            return claimed;
        }

        @Transactional
        public void complete(Actor worker, UUID jobId) {
            JobScope scope = jobs.scope(jobId).orElseThrow(() -> new NotFoundException("job not found"));
            jobs.succeed(jobId);
            audit.successInOrganization(worker, scope.organizationId(), scope.venueId(), "job.complete",
                "processing_job", jobId, Map.of());
        }

        @Transactional
        public void fail(Actor worker, UUID jobId, String code, String message) {
            JobScope scope = jobs.scope(jobId).orElseThrow(() -> new NotFoundException("job not found"));
            jobs.fail(jobId, code, message);
            audit.successInOrganization(worker, scope.organizationId(), scope.venueId(), "job.fail",
                "processing_job", jobId, Map.of("errorCode", code));
        }
    }

    private final WorkerJobService workers;

    public InternalJobController(WorkerJobService workers) {
        this.workers = workers;
    }

    @PostMapping("/claim")
    public ResponseEntity<ClaimedJob> claim(@Valid @RequestBody ClaimRequest body) {
        return workers.claim(ActorAuthentication.currentActor(), body.stage())
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/{jobId}/complete")
    public void complete(@PathVariable UUID jobId) {
        workers.complete(ActorAuthentication.currentActor(), jobId);
    }

    @PostMapping("/{jobId}/fail")
    public void fail(@PathVariable UUID jobId, @Valid @RequestBody FailRequest body) {
        workers.fail(ActorAuthentication.currentActor(), jobId, body.errorCode(), body.errorMessage());
    }
}
