package dev.chaya.api.ops;

import static org.assertj.core.api.Assertions.assertThat;

import dev.chaya.api.ops.JobActions.Action;
import dev.chaya.api.ops.JobActions.JobState;
import dev.chaya.api.ops.JobActions.Via;
import dev.chaya.api.ops.OpsDtos.MeasuredCoverage;
import dev.chaya.api.ops.OpsDtos.UncoveredZone;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The dashboard only offers an action the endpoint behind it would accept (PipelineService, ProcessingJobRepository). */
class JobActionsTest {

    private static final UUID RUN = UUID.randomUUID();
    private static final UUID CAPTURE = UUID.randomUUID();

    private static JobState runJob(String status, int retries, int max, String runStatus, String failureStage, boolean latest) {
        return new JobState(status, "POSE_ESTIMATION", retries, max, RUN, runStatus, failureStage, latest, CAPTURE);
    }

    private static JobState legacyJob(String status, int retries, int max) {
        return new JobState(status, "MEDIA_FILTER", retries, max, null, null, null, false, CAPTURE);
    }

    @Test
    void failedJobOfAFailedRunIsRetriedThroughTheRun() {
        Action a = JobActions.retry(runJob("FAILED", 0, 3, "FAILED", "POSE_ESTIMATION", true));
        assertThat(a.available()).isTrue();
        assertThat(a.via()).isEqualTo(Via.PIPELINE_RUN);
        assertThat(a.captureId()).isEqualTo(CAPTURE);
    }

    @Test
    void exhaustedRetriesAreNotOffered() {
        Action a = JobActions.retry(runJob("FAILED", 3, 3, "FAILED", "POSE_ESTIMATION", true));
        assertThat(a.available()).isFalse();
        assertThat(a.reason()).contains("3 of 3");
        assertThat(JobActions.retry(legacyJob("FAILED", 1, 1)).available()).isFalse();
    }

    @Test
    void onlyTheLatestJobOfTheStageTheRunFailedAtIsRetryable() {
        assertThat(JobActions.retry(runJob("FAILED", 0, 3, "FAILED", "POSE_ESTIMATION", false)).available()).isFalse();
        assertThat(JobActions.retry(runJob("FAILED", 0, 3, "FAILED", "SPLAT_RECONSTRUCTION", true)).available()).isFalse();
        assertThat(JobActions.retry(runJob("FAILED", 0, 3, "RUNNING", null, true)).available()).isFalse();
    }

    @Test
    void nonFailedJobsAreNotRetryable() {
        for (String s : List.of("QUEUED", "RUNNING", "SUCCEEDED", "CANCELLED")) {
            assertThat(JobActions.retry(runJob(s, 0, 3, "FAILED", "POSE_ESTIMATION", true)).available()).as(s).isFalse();
        }
    }

    @Test
    void legacyJobsAreControlledDirectly() {
        assertThat(JobActions.retry(legacyJob("FAILED", 0, 3)).via()).isEqualTo(Via.JOB);
        assertThat(JobActions.cancel(legacyJob("QUEUED", 0, 3)).via()).isEqualTo(Via.JOB);
    }

    @Test
    void cancelOnlyForActiveJobsOfARunningRun() {
        assertThat(JobActions.cancel(runJob("RUNNING", 0, 3, "RUNNING", null, true)).via()).isEqualTo(Via.PIPELINE_RUN);
        assertThat(JobActions.cancel(runJob("QUEUED", 0, 3, "CANCELLED", null, true)).available()).isFalse();
        assertThat(JobActions.cancel(runJob("SUCCEEDED", 0, 3, "RUNNING", null, true)).available()).isFalse();
    }

    @Test
    void runRetryMirrorsPipelineServiceRetry() {
        assertThat(JobActions.runRetry("POSE_ESTIMATION", "FAILED", 0, 3, CAPTURE).available()).isTrue();
        // A stage whose last job was cancelled (e.g. the time box) gets a fresh job: retry is available.
        assertThat(JobActions.runRetry("POSE_ESTIMATION", "CANCELLED", 3, 3, CAPTURE).available()).isTrue();
        assertThat(JobActions.runRetry("POSE_ESTIMATION", "FAILED", 3, 3, CAPTURE).available()).isFalse();
        assertThat(JobActions.runRetry(null, null, null, null, CAPTURE).available()).isFalse();
    }

    @Test
    void coverageGapsAreDerivedFromStoredStateOnly() {
        assertThat(OpsDashboardService.gaps(false, false, null))
            .containsExactly("NO_RECONSTRUCTION", "NO_FINALIZED_VERSION", "NO_MEASURED_COVERAGE");
        MeasuredCoverage unavailable = new MeasuredCoverage(CAPTURE, Instant.now(), "UPLOADING", false, "no usable trajectory",
            null, null, null, List.of());
        assertThat(OpsDashboardService.gaps(true, true, unavailable)).containsExactly("COVERAGE_UNAVAILABLE");
        MeasuredCoverage zones = new MeasuredCoverage(CAPTURE, Instant.now(), "COMPLETED", true, null, 80.0, 78.0, 4.0,
            List.of(new UncoveredZone(1, 1, 4.0, "BELOW_COVERAGE_TARGET")));
        assertThat(OpsDashboardService.gaps(true, true, zones)).containsExactly("UNCOVERED_ZONES");
    }

    @Test
    void costIsNeverInvented() {
        OpsDtos.Cost cost = OpsDashboardService.costUnavailable();
        assertThat(cost.available()).isFalse();
        assertThat(cost.amount()).isNull();
        assertThat(cost.currency()).isNull();
    }

    @Test
    void agesAreMeasuredFromTimestampsAndNullWhenNothingHappened() {
        Instant asOf = Instant.parse("2026-09-23T12:00:00Z");
        assertThat(OpsDashboardService.age(Instant.parse("2026-09-22T12:00:00Z"), asOf)).isEqualTo(86_400L);
        assertThat(OpsDashboardService.age(null, asOf)).isNull();
        assertThat(OpsDashboardService.clampDays(0)).isEqualTo(1);
        assertThat(OpsDashboardService.clampDays(10_000)).isEqualTo(OpsDashboardService.MAX_DAYS);
    }
}
