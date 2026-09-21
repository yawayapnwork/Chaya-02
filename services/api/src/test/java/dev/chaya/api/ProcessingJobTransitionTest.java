package dev.chaya.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.chaya.api.processing.InvalidJobTransitionException;
import dev.chaya.api.processing.JobStage;
import dev.chaya.api.processing.JobStatus;
import dev.chaya.api.processing.ProcessingJobRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

class ProcessingJobTransitionTest extends AbstractIntegrationTest {

    @Autowired ProcessingJobRepository jobs;

    private UUID newJob(JobStage stage) {
        var t = fx.tree();
        return jobs.enqueue(t.org(), t.venue(), t.scan(), t.version(), stage);
    }

    @Test
    void happyPathQueuedRunningSucceeded() {
        UUID job = newJob(JobStage.MEDIA_FILTER);
        assertThat(jobs.status(job)).contains(JobStatus.QUEUED);
        jobs.start(job);
        assertThat(jobs.status(job)).contains(JobStatus.RUNNING);
        jobs.succeed(job);
        assertThat(jobs.status(job)).contains(JobStatus.SUCCEEDED);
    }

    @Test
    void failureRecordsErrorAndRetryIsCountedAndBounded() {
        UUID job = newJob(JobStage.POSE_ESTIMATION);
        for (int attempt = 1; attempt <= 3; attempt++) {
            jobs.start(job);
            jobs.fail(job, "POSE_FAILED", "GLOMAP did not converge");
            jobs.retry(job);
            int retries = jdbc.sql("SELECT retry_count FROM processing_job WHERE id = :id").param("id", job)
                .query(Integer.class).single();
            assertThat(retries).isEqualTo(attempt);
        }
        jobs.start(job);
        jobs.fail(job, "POSE_FAILED", "GLOMAP did not converge");
        assertThatThrownBy(() -> jobs.retry(job)).isInstanceOf(InvalidJobTransitionException.class).hasMessageContaining("exhausted");
        assertThat(jdbc.sql("SELECT error_code FROM processing_job WHERE id = :id").param("id", job)
            .query(String.class).single()).isEqualTo("POSE_FAILED");
    }

    @Test
    void invalidTransitionsAreRejectedByTheRepository() {
        UUID job = newJob(JobStage.SEGMENTATION);
        assertThatThrownBy(() -> jobs.succeed(job)).isInstanceOf(InvalidJobTransitionException.class);
        jobs.start(job);
        assertThatThrownBy(() -> jobs.start(job)).isInstanceOf(InvalidJobTransitionException.class);
        jobs.succeed(job);
        assertThatThrownBy(() -> jobs.cancel(job)).isInstanceOf(InvalidJobTransitionException.class);
    }

    @Test
    void databaseRejectsInvalidTransitionsEvenWhenBypassingTheRepository() {
        UUID job = newJob(JobStage.OBJECT_DETECTION);
        // QUEUED -> SUCCEEDED skips RUNNING.
        assertThatThrownBy(() -> jdbc.sql("UPDATE processing_job SET status = 'SUCCEEDED', finished_at = now() WHERE id = :id")
            .param("id", job).update())
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("invalid processing_job transition");

        jobs.start(job);
        jobs.succeed(job);
        // Terminal state cannot be reopened.
        assertThatThrownBy(() -> jdbc.sql("UPDATE processing_job SET status = 'QUEUED', finished_at = NULL, started_at = NULL WHERE id = :id")
            .param("id", job).update())
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("terminal");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM processing_job WHERE id = :id").param("id", job).update())
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void failedJobRequiresAnError() {
        UUID job = newJob(JobStage.GEOMETRY_CLEANUP);
        jobs.start(job);
        assertThatThrownBy(() -> jdbc.sql("UPDATE processing_job SET status = 'FAILED', finished_at = now() WHERE id = :id")
            .param("id", job).update()).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void onlyOneActiveJobPerScanAndStage() {
        var t = fx.tree();
        UUID first = jobs.enqueue(t.org(), t.venue(), t.scan(), t.version(), JobStage.SPLAT_TRAINING);
        assertThatThrownBy(() -> jobs.enqueue(t.org(), t.venue(), t.scan(), t.version(), JobStage.SPLAT_TRAINING))
            .isInstanceOf(DataIntegrityViolationException.class);
        jobs.cancel(first);
        assertThat(jobs.enqueue(t.org(), t.venue(), t.scan(), t.version(), JobStage.SPLAT_TRAINING)).isNotNull();
    }

    @Test
    void jobCannotReferenceAScanVersionOfAnotherScan() {
        var a = fx.tree();
        var b = fx.tree();
        assertThatThrownBy(() -> jobs.enqueue(a.org(), a.venue(), a.scan(), b.version(), JobStage.MEDIA_FILTER))
            .isInstanceOf(DataIntegrityViolationException.class);
    }
}
