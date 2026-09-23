package dev.chaya.api.processing;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence for processing jobs. Every transition is a compare-and-set on the current status, so
 * two workers can never both move the same job. Invalid transitions are rejected here and, as a
 * backstop, by the database trigger.
 */
@Repository
public class ProcessingJobRepository {

    private final JdbcClient jdbc;

    public ProcessingJobRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID enqueue(UUID organizationId, UUID venueId, UUID scanId, UUID scanVersionId, JobStage stage) {
        return jdbc.sql("""
                INSERT INTO processing_job (organization_id, venue_id, scan_id, scan_version_id, stage)
                VALUES (:org, :venue, :scan, :version, :stage) RETURNING id""")
            .param("org", organizationId)
            .param("venue", venueId)
            .param("scan", scanId)
            .param("version", scanVersionId)
            .param("stage", stage.name())
            .query(UUID.class)
            .single();
    }

    public record ClaimedJob(UUID id, UUID organizationId, UUID venueId, UUID scanId, UUID scanVersionId, JobStage stage,
                             UUID runId, int retryCount) {}

    public record JobScope(UUID organizationId, UUID venueId) {}

    /**
     * Atomically moves the oldest QUEUED job of one of the stages to RUNNING and leases it to the worker.
     * Concurrent workers never get the same job, and jobs of a run whose time budget is already spent are
     * left for the deadline enforcer instead of being handed out.
     */
    public Optional<ClaimedJob> claim(List<JobStage> stages, String workerId, int leaseSeconds) {
        return jdbc.sql("""
                UPDATE processing_job SET status = 'RUNNING', started_at = now(), worker_id = :worker,
                                          lease_expires_at = now() + make_interval(secs => :lease)
                 WHERE id = (SELECT j.id FROM processing_job j
                              WHERE j.status = 'QUEUED' AND j.stage IN (:stages)
                                AND NOT EXISTS (SELECT 1 FROM pipeline_run r WHERE r.id = j.run_id AND r.deadline_at < now())
                              ORDER BY j.queued_at FOR UPDATE SKIP LOCKED LIMIT 1)
                RETURNING id, organization_id, venue_id, scan_id, scan_version_id, stage, run_id, retry_count""")
            .param("stages", stages.stream().map(Enum::name).toList())
            .param("worker", workerId)
            .param("lease", leaseSeconds)
            .query((rs, i) -> new ClaimedJob(rs.getObject("id", UUID.class), rs.getObject("organization_id", UUID.class),
                rs.getObject("venue_id", UUID.class), rs.getObject("scan_id", UUID.class),
                rs.getObject("scan_version_id", UUID.class), JobStage.valueOf(rs.getString("stage")),
                rs.getObject("run_id", UUID.class), rs.getInt("retry_count")))
            .optional();
    }

    /** Extends the lease. False if the job is no longer RUNNING for this worker (finished, cancelled, expired). */
    public boolean heartbeat(UUID jobId, String workerId, int leaseSeconds) {
        return jdbc.sql("UPDATE processing_job SET lease_expires_at = now() + make_interval(secs => :lease) "
                + "WHERE id = :id AND status = 'RUNNING' AND worker_id = :worker")
            .param("id", jobId).param("worker", workerId).param("lease", leaseSeconds).update() == 1;
    }

    public UUID enqueueForRun(UUID organizationId, UUID venueId, UUID scanId, UUID runId, JobStage stage) {
        return enqueueForRun(organizationId, venueId, scanId, null, runId, stage);
    }

    /** Same, but tags the job with the ScanVersion an incremental re-scan run is building (null for an
     * ordinary full-venue run). Threaded onto the job so the worker's WorkOrder carries it. */
    public UUID enqueueForRun(UUID organizationId, UUID venueId, UUID scanId, UUID scanVersionId, UUID runId, JobStage stage) {
        return jdbc.sql("""
                INSERT INTO processing_job (organization_id, venue_id, scan_id, scan_version_id, run_id, stage)
                VALUES (:org, :venue, :scan, :version, :run, :stage) RETURNING id""")
            .param("org", organizationId).param("venue", venueId).param("scan", scanId).param("version", scanVersionId)
            .param("run", runId).param("stage", stage.name()).query(UUID.class).single();
    }

    public Optional<JobScope> scope(UUID jobId) {
        return jdbc.sql("SELECT organization_id, venue_id FROM processing_job WHERE id = :id")
            .param("id", jobId)
            .query((rs, i) -> new JobScope(rs.getObject("organization_id", UUID.class), rs.getObject("venue_id", UUID.class)))
            .optional();
    }

    public Optional<JobStatus> status(UUID jobId) {
        return jdbc.sql("SELECT status FROM processing_job WHERE id = :id")
            .param("id", jobId)
            .query(String.class)
            .optional()
            .map(JobStatus::valueOf);
    }

    public void start(UUID jobId) {
        transition(jobId, JobStatus.QUEUED, JobStatus.RUNNING, "started_at = now()");
    }

    public void succeed(UUID jobId) {
        transition(jobId, JobStatus.RUNNING, JobStatus.SUCCEEDED, "finished_at = now()");
    }

    public void cancel(UUID jobId) {
        JobStatus current = status(jobId).orElseThrow(() -> notFound(jobId));
        if (!current.canTransitionTo(JobStatus.CANCELLED)) {
            throw invalid(jobId, current, JobStatus.CANCELLED);
        }
        transition(jobId, current, JobStatus.CANCELLED, "finished_at = now()");
    }

    public void fail(UUID jobId, String errorCode, String errorMessage) {
        int rows = jdbc.sql("""
                UPDATE processing_job
                   SET status = 'FAILED', finished_at = now(), error_code = :code, error_message = :message
                 WHERE id = :id AND status = 'RUNNING'""")
            .param("id", jobId)
            .param("code", errorCode)
            .param("message", errorMessage)
            .update();
        requireUpdated(rows, jobId, JobStatus.FAILED);
    }

    /**
     * FAILED -> QUEUED, counting the retry. Fails once max_retries is exhausted. The job row forgets the last
     * error (a succeeded job cannot carry one); the failure stays on record in pipeline_stage_run.
     */
    public void retry(UUID jobId) {
        int rows = jdbc.sql("""
                UPDATE processing_job
                   SET status = 'QUEUED', retry_count = retry_count + 1, queued_at = now(),
                       started_at = NULL, finished_at = NULL, worker_id = NULL, lease_expires_at = NULL,
                       error_code = NULL, error_message = NULL
                 WHERE id = :id AND status = 'FAILED' AND retry_count < max_retries""")
            .param("id", jobId)
            .update();
        if (rows == 0) {
            throw new InvalidJobTransitionException("job " + jobId + " cannot be retried (not FAILED, or retries exhausted)");
        }
    }

    private void transition(UUID jobId, JobStatus from, JobStatus to, String extraSet) {
        if (!from.canTransitionTo(to)) {
            throw invalid(jobId, from, to);
        }
        int rows = jdbc.sql("UPDATE processing_job SET status = :to, " + extraSet + " WHERE id = :id AND status = :from")
            .param("id", jobId)
            .param("from", from.name())
            .param("to", to.name())
            .update();
        requireUpdated(rows, jobId, to);
    }

    private void requireUpdated(int rows, UUID jobId, JobStatus to) {
        if (rows == 0) {
            JobStatus current = status(jobId).orElseThrow(() -> notFound(jobId));
            throw new InvalidJobTransitionException("job " + jobId + " is " + current + "; cannot move to " + to);
        }
    }

    private static InvalidJobTransitionException invalid(UUID jobId, JobStatus from, JobStatus to) {
        return new InvalidJobTransitionException("job " + jobId + ": invalid transition " + from + " -> " + to);
    }

    private static InvalidJobTransitionException notFound(UUID jobId) {
        return new InvalidJobTransitionException("job " + jobId + " does not exist");
    }
}
