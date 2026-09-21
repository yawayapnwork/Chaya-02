package dev.chaya.api.processing;

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

    /** FAILED -> QUEUED, counting the retry. Fails once max_retries is exhausted. */
    public void retry(UUID jobId) {
        int rows = jdbc.sql("""
                UPDATE processing_job
                   SET status = 'QUEUED', retry_count = retry_count + 1, queued_at = now(),
                       started_at = NULL, finished_at = NULL
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
