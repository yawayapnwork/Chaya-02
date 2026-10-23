// Pure presentation rules for the processing panel. The important one: a PARTIAL-quality result is never
// presented like a finalized reconstruction, and a failed run is never presented as done.

export interface RunLike {
  status: "RUNNING" | "SUCCEEDED" | "PARTIAL" | "FAILED" | "CANCELLED";
  quality: "FINAL" | "PARTIAL" | null;
  failureStage: string | null;
  failureCode: string | null;
  failureMessage: string | null;
  retryable: boolean;
  retriesRemaining?: number | null;
  retryBlockedReason?: string | null;
}

export type Tone = "info" | "success" | "warning" | "error";

export interface RunSummary {
  badge: string;
  tone: Tone;
  headline: string;
  detail: string | null;
  canRetry: boolean;
  canCancel: boolean;
}

const STAGE_LABEL: Record<string, string> = {
  INPUT_VALIDATION: "Input validation",
  FFMPEG_PREPROCESS: "Frame extraction (FFmpeg)",
  FRAME_QUALITY_FILTER: "Frame quality filter",
  PRIVACY_PREPROCESS: "Privacy preprocessing",
  POSE_ESTIMATION: "Camera pose estimation",
  SPLAT_RECONSTRUCTION: "Gaussian splat reconstruction",
  SEMANTIC_SEGMENTATION: "Semantic segmentation",
  GEOMETRIC_CLEANUP: "Geometric cleanup",
  REGION_ALIGNMENT: "Region alignment",
  REGION_SPLICE: "Region splice",
  ROOM_REGISTRATION: "Room registration",
  ROOM_STITCH: "Room stitching",
  PLANE_FITTING: "Plane fitting",
  ARTIFACT_GENERATION: "Artifact generation",
  NAVIGATION_BAKING: "Navigation baking",
  SEMANTIC_INDEXING: "Semantic indexing",
};

export function stageLabel(stage: string): string {
  return STAGE_LABEL[stage] ?? stage;
}

/** An actionable explanation of a stage error code. `details` is the structured error_details from the worker. */
export function explainStageError(code: string | null, message: string | null, details: Record<string, unknown> | null): string {
  const missing = Array.isArray(details?.missing) ? (details?.missing as unknown[]).map(String) : [];
  switch (code) {
    case "DEPENDENCY_UNAVAILABLE":
      return `The processing worker is missing required software${missing.length ? `: ${missing.join(", ")}` : ""}. Install it on the worker, then retry.`;
    case "STAGE_NOT_IMPLEMENTED":
      return "This stage is not implemented yet, so the reconstruction cannot be completed. No output was produced.";
    case "TIME_LIMIT_EXCEEDED":
      return "The time budget for this run ran out.";
    case "WORKER_LOST":
      return "The worker stopped responding while running this stage. Retry to run it again.";
    case "INPUT_INVALID":
      return "One or more uploaded files could not be processed. Check the details and upload again.";
    case "INSUFFICIENT_QUALITY_FRAMES":
      return "Too few sharp, well-exposed frames were found. Capture again more slowly with better lighting.";
    case "PRIVACY_VERIFICATION_FAILED":
    case "PRIVACY_FRAME_UNREADABLE":
      return "Privacy preprocessing could not be completed, so nothing was passed on. Retry, or capture again.";
    case "POSE_ESTIMATION_INSUFFICIENT":
      return "Camera positions could not be recovered for enough frames. Capture again with more overlap between views.";
    case "NOT_CALIBRATED":
      return "The reconstruction has no metric coordinate frame yet. Calibrate it under Measurements and calibration, then retry.";
    case "STAGE_OUTPUT_MISSING":
      return `The stage reported success without the outputs the next stages need${Array.isArray(details?.missing) ? ` (${(details?.missing as unknown[]).map(String).join(", ")})` : ""}. The worker and the server disagree on the artifact contract; retry on an up-to-date worker.`;
    case "ARTIFACT_UNREADABLE":
      return "An output of this stage could not be read back from storage. Retry the stage.";
    case "DETECTED_OBJECTS_INVALID":
      return "Some detected objects could not become searchable points of interest (missing position or embedding). Nothing was dropped silently; retry on an up-to-date worker.";
    case "NAVIGATION_GRAPH_INVALID":
      return "Navigation baking produced no routable graph. Retry the stage; if it repeats, check the floor's walkable surface in the capture.";
    case "SPLAT_QUALITY_INSUFFICIENT":
      return "Training finished, but the result does not reproduce the captured views: it renders the views held out of training no better than its starting point, or its training loss did not fall. Nothing was published. Capture again with more overlap and steadier lighting, or train longer.";
    case "SPLAT_QUALITY_UNVERIFIED":
      return "Training finished, but its quality could not be measured against the captured frames, so it was not published. Check the details; retry on an up-to-date worker.";
    case "SPLAT_GPU_OUT_OF_MEMORY":
      return "The GPU ran out of memory while training. Retry on a GPU with more memory or with a lower Gaussian limit.";
    case "GPU_RUNTIME_ERROR":
      return "The GPU failed during this stage. Retry; if it repeats, the worker's GPU needs checking.";
    case "REPORT_REJECTED":
      return "The control plane refused the stage output. This is a bug; contact support with the run id.";
    case "REGISTRATION_REJECTED":
      return "Not every room could be placed: a room has no reliable registration linking it to the others. See which room and why below; re-capture it so it overlaps a neighbouring room through a door, or leave it out, and start a new stitch.";
    case "STITCH_REJECTED":
      return "The registered rooms do not meet on the same surfaces (a misaligned overlap or a visible seam), so they were not combined. Nothing was published; start a new stitch after re-capturing or re-calibrating the rooms involved.";
    case "STITCH_LINEAGE_INVALID":
      return "The stitch report does not prove which rooms were combined. Retry the stage on an up-to-date worker.";
    case "STITCH_FRAME_CHANGED":
      return "The anchor room was recalibrated while the stitch ran, so the other rooms were placed by its old calibration. Start a new stitch.";
    case "NAVMESH_ROOMS_DISCONNECTED":
      return "The stitched floor's navigation does not join every room: a doorway is blocked or closed, or a room is misplaced. Nothing was published.";
    default:
      return message ?? "The stage failed.";
  }
}

export function summarizeRun(run: RunLike): RunSummary {
  switch (run.status) {
    case "RUNNING":
      return { badge: "Processing", tone: "info", headline: "Processing is in progress.", detail: null, canRetry: false, canCancel: true };
    case "SUCCEEDED":
      // Only FINAL counts as a finalized reconstruction.
      return run.quality === "FINAL"
        ? { badge: "Finalized", tone: "success", headline: "Reconstruction finalized.", detail: null, canRetry: false, canCancel: false }
        : { badge: "Inconsistent", tone: "error", headline: "The server reported success without a final quality. Do not use this result.", detail: null, canRetry: false, canCancel: false };
    case "PARTIAL":
      return {
        badge: "Partial quality",
        tone: "warning",
        headline: "Partial-quality reconstruction. It is NOT finalized.",
        detail: run.failureMessage ?? "The time budget ran out before processing completed.",
        // The server reopens a PARTIAL run only to resume splat training the time budget stopped (PipelineService#retry).
        canRetry: run.retryable,
        canCancel: false,
      };
    case "FAILED":
      return {
        badge: "Failed",
        tone: "error",
        headline: `Processing stopped${run.failureStage ? ` at ${stageLabel(run.failureStage)}` : ""}. No reconstruction was produced.`,
        detail: run.failureCode,
        canRetry: run.retryable,
        canCancel: false,
      };
    case "CANCELLED":
      return { badge: "Cancelled", tone: "warning", headline: "Processing was cancelled.", detail: null, canRetry: false, canCancel: false };
  }
}

export function shortSha(sha: string | null): string {
  return sha ? sha.slice(0, 12) : "—";
}

export function durationSeconds(startedAt: string, finishedAt: string): number {
  return Math.max(0, (Date.parse(finishedAt) - Date.parse(startedAt)) / 1000);
}

// ---- live progress (docs/pipeline.md, "Live progress") ---------------------------------------------------------------

export interface StageLike {
  stage: string;
  state: "PENDING" | "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED" | "CANCELLED" | "NOT_RUN";
  attempts: number;
  lastRun: { artifacts: { kind: string; sizeBytes: number; partial: boolean }[] } | null;
}

export interface PipelineProgress {
  /** Stages with a successful record, and all stages. This is stage completion, not a share of the time the run takes:
   * stages differ by orders of magnitude in duration, so it is never shown as a percentage of the job. */
  completed: number;
  total: number;
  /** The stage running or waiting now, and whether it is running. Its own progress is unknown (indeterminate): no stage
   * reports progress within itself. */
  activeStage: string | null;
  activeRunning: boolean;
  /** The whole job is over (successfully or not); completed === total only for a fully succeeded run. */
  jobFinished: boolean;
  label: string;
}

export function pipelineProgress(run: { status: RunLike["status"]; stages: StageLike[] } | null): PipelineProgress {
  if (!run) return { completed: 0, total: 0, activeStage: null, activeRunning: false, jobFinished: false, label: "Not started" };
  const total = run.stages.length;
  const completed = run.stages.filter((s) => s.state === "SUCCEEDED").length;
  const active = run.stages.find((s) => s.state === "RUNNING") ?? run.stages.find((s) => s.state === "QUEUED") ?? null;
  const jobFinished = run.status !== "RUNNING";
  let label = `${completed} of ${total} stages completed`;
  if (active) label += active.state === "RUNNING" ? ` · now: ${stageLabel(active.stage)}` : ` · waiting for a worker: ${stageLabel(active.stage)}`;
  return { completed, total, activeStage: active?.stage ?? null, activeRunning: active?.state === "RUNNING", jobFinished, label };
}

export type FailureKind = "RETRYABLE" | "TERMINAL" | "INTERRUPTED" | "CANCELLED" | null;

/**
 * How a run ended badly: CANCELLED by someone; INTERRUPTED when the worker was lost or the time budget ran out (the
 * stage did not fail on its own); RETRYABLE when the server would re-queue it; TERMINAL when it would not
 * (retryBlockedText says why). null for a run that is running or succeeded.
 */
export function failureKind(run: RunLike | null): FailureKind {
  if (!run) return null;
  if (run.status === "CANCELLED") return "CANCELLED";
  if (run.status !== "FAILED") return null;
  if (run.failureCode === "WORKER_LOST" || run.failureCode === "TIME_LIMIT_EXCEEDED") return "INTERRUPTED";
  return run.retryable ? "RETRYABLE" : "TERMINAL";
}

/** Why a FAILED run cannot be retried, in words (null when it can, or when it did not fail). Mirrors the server's own
 * retry preconditions (PipelineService.retry), which it reports as retryBlockedReason. */
export function retryBlockedText(run: RunLike | null): string | null {
  if (!run || run.status !== "FAILED" || run.retryable) return null;
  switch (run.retryBlockedReason) {
    case "RETRIES_EXHAUSTED":
      return "This stage has used all its retries. Upload the capture again, or ask an administrator to look at the failure.";
    case "PII_STAGING_PURGED":
      return "The unanonymised frames this stage needs were deleted under the retention policy, so it can no longer be retried. Upload the capture again to reprocess it.";
    case "ALIGNMENT_REJECTED":
      return "This re-scan's alignment was rejected and its version is final. Start a new re-scan instead.";
    case "PARENT_NOT_CURRENT":
      return "The floor has published a newer version since this re-scan started, so it can never be published. Start a new re-scan.";
    case "VERSION_SUPERSEDED":
      return "The floor publishes a newer version than this run's, so this run can never be published. Reconstruct or re-scan again.";
    case "REGISTRATION_REJECTED":
    case "STITCH_REJECTED":
    case "STITCH_FRAME_CHANGED":
      return "This stitch's rooms and calibrations are fixed, so running it again gives the same answer. Re-capture or re-calibrate the rooms it names, or leave one out, and start a new stitch.";
    default:
      return "Retrying cannot fix this failure.";
  }
}

/** Which attempt a stage is on: its job's retry count + 1. A cancelled or still running attempt has no stage record yet,
 * so the records alone (`attempts`) can be one behind. */
export function stageAttempt(stage: Pick<StageLike, "stage" | "attempts">, jobs: { stage: string; retryCount: number }[]): number {
  const fromJobs = jobs.filter((j) => j.stage === stage.stage).reduce((n, j) => Math.max(n, j.retryCount + 1), 0);
  return Math.max(stage.attempts, fromJobs, 1);
}

/** Every output the run's stages have published so far, stage by stage. */
export function availableArtifacts(stages: StageLike[]): { stage: string; kind: string; sizeBytes: number; partial: boolean }[] {
  return stages.flatMap((s) => (s.state === "SUCCEEDED" && s.lastRun ? s.lastRun.artifacts.map((a) => ({ stage: s.stage, ...a })) : []));
}

/** "No heartbeat from worker-1 since 10:42:05": a running stage whose worker has gone quiet, before it is failed. */
export function staleLeaseMessage(jobs: { stage: string; workerId: string | null; leaseExpiresAt: string | null; leaseExpired: boolean }[]): string | null {
  const stale = jobs.find((j) => j.leaseExpired);
  if (!stale) return null;
  return `The worker${stale.workerId ? ` ${stale.workerId}` : ""} running ${stageLabel(stale.stage)} has stopped sending heartbeats` +
    `${stale.leaseExpiresAt ? ` (its lease ran out at ${new Date(stale.leaseExpiresAt).toLocaleTimeString()})` : ""}. ` +
    "The stage will be marked as lost shortly; it can then be retried. Nothing it may have produced is counted as done.";
}
