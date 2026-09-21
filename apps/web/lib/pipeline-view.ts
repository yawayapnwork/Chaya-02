// Pure presentation rules for the processing panel. The important one: a PARTIAL-quality result is never
// presented like a finalized reconstruction, and a failed run is never presented as done.

export interface RunLike {
  status: "RUNNING" | "SUCCEEDED" | "PARTIAL" | "FAILED" | "CANCELLED";
  quality: "FINAL" | "PARTIAL" | null;
  failureStage: string | null;
  failureCode: string | null;
  failureMessage: string | null;
  retryable: boolean;
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
    case "REPORT_REJECTED":
      return "The control plane refused the stage output. This is a bug; contact support with the run id.";
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
        canRetry: false,
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
