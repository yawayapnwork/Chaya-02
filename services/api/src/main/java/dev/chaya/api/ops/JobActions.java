package dev.chaya.api.ops;

import java.util.UUID;

/**
 * Whether a job can be retried or cancelled right now, and through which existing endpoint. Pure rules that mirror
 * what the endpoints themselves will accept, so the dashboard never offers an action that is bound to be refused:
 *
 * <ul>
 *   <li>A job of a pipeline run is controlled through its run ({@code POST .../captures/{c}/processing/retry|cancel},
 *       PipelineService#retry/#cancel). The run is retryable only while it is FAILED, and the retry re-queues the
 *       latest job of the stage the run failed at -- bounded by that job's max_retries
 *       (ProcessingJobRepository#retry).</li>
 *   <li>A legacy job without a run is controlled directly ({@code POST .../jobs/{j}/retry|cancel}).</li>
 * </ul>
 */
public final class JobActions {

    public enum Via { PIPELINE_RUN, JOB }

    /** available=false carries the reason; via/captureId say which endpoint to call when available. */
    public record Action(boolean available, Via via, UUID captureId, String reason) {

        static Action no(String reason) {
            return new Action(false, null, null, reason);
        }
    }

    /** The job and (when it belongs to one) its run, as currently stored. */
    public record JobState(String status, String stage, int retryCount, int maxRetries, UUID runId, String runStatus,
                           String runFailureStage, boolean latestJobOfStage, UUID captureId) {}

    private JobActions() {}

    public static Action retry(JobState j) {
        if (!"FAILED".equals(j.status())) {
            return Action.no("only a FAILED job can be retried (job is " + j.status() + ")");
        }
        if (j.retryCount() >= j.maxRetries()) {
            return Action.no("retries exhausted (" + j.retryCount() + " of " + j.maxRetries() + " used)");
        }
        if (j.runId() == null) {
            return new Action(true, Via.JOB, null, null);
        }
        if (!"FAILED".equals(j.runStatus())) {
            return Action.no("its pipeline run is " + j.runStatus() + "; only a FAILED run can be retried");
        }
        if (!j.stage().equals(j.runFailureStage()) || !j.latestJobOfStage()) {
            return Action.no("superseded: the run failed at " + j.runFailureStage() + ", retry that stage instead");
        }
        if (j.captureId() == null) {
            return Action.no("the run has no capture session to retry through");
        }
        return new Action(true, Via.PIPELINE_RUN, j.captureId(), null);
    }

    /**
     * Retry of a FAILED pipeline run as a whole (PipelineService#retry): it re-queues the latest job of the failure
     * stage when that job FAILED -- bounded by its max_retries -- and otherwise enqueues a fresh job for the stage.
     * latestJobStatus/retryCount/maxRetries are null when the stage never had a job.
     */
    public static Action runRetry(String failureStage, String latestJobStatus, Integer retryCount, Integer maxRetries,
                                  UUID captureId) {
        if (failureStage == null) {
            return Action.no("the run has no recorded failure stage to retry");
        }
        if ("FAILED".equals(latestJobStatus) && retryCount != null && maxRetries != null && retryCount >= maxRetries) {
            return Action.no("retries exhausted (" + retryCount + " of " + maxRetries + " used)");
        }
        if (captureId == null) {
            return Action.no("the run has no capture session to retry through");
        }
        return new Action(true, Via.PIPELINE_RUN, captureId, null);
    }

    public static Action cancel(JobState j) {
        if (!"QUEUED".equals(j.status()) && !"RUNNING".equals(j.status())) {
            return Action.no("only a QUEUED or RUNNING job can be cancelled (job is " + j.status() + ")");
        }
        if (j.runId() == null) {
            return new Action(true, Via.JOB, null, null);
        }
        if (!"RUNNING".equals(j.runStatus())) {
            return Action.no("its pipeline run is " + j.runStatus());
        }
        if (j.captureId() == null) {
            return Action.no("the run has no capture session to cancel through");
        }
        return new Action(true, Via.PIPELINE_RUN, j.captureId(), null);
    }
}
