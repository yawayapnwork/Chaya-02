// What the viewer says about a floor whose model is not ready yet: the newest capture of that floor still on its way to a
// reconstruction, and how far its processing run has got. Pure, so it is unit-tested. The facts come from the venue
// team's capture API (GET /venues/{v}/captures, GET .../captures/{c}/processing); viewers without access to raw captures
// (VIEWER, public links) are simply not shown this, never shown a guess.

import { stageLabel } from "./pipeline-view.ts";

export interface CaptureLike {
  id: string;
  floorId: string | null;
  status: string;
  startedAt: string;
}

export interface StageLike {
  stage: string;
  state: "PENDING" | "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED" | "CANCELLED" | "NOT_RUN";
}

export interface RunProgressLike {
  status: "RUNNING" | "SUCCEEDED" | "PARTIAL" | "FAILED" | "CANCELLED";
  failureStage: string | null;
  failureMessage: string | null;
  stages: StageLike[];
}

/** A capture that is still becoming a reconstruction: uploaded, being validated, queued or processing. */
const IN_PROGRESS = new Set(["UPLOADED", "VALIDATING", "READY_FOR_PROCESSING", "PROCESSING"]);

/** The newest capture of the floor if it is still in progress (or failed, so the failure is said, not hidden). A newer
 * finished capture means there is nothing pending to report. */
export function pendingCapture<T extends CaptureLike>(captures: readonly T[], floorId: string): T | null {
  const onFloor = captures.filter((c) => c.floorId === floorId).sort((a, b) => b.startedAt.localeCompare(a.startedAt));
  const newest = onFloor[0];
  return newest && (IN_PROGRESS.has(newest.status) || newest.status === "FAILED") ? newest : null;
}

export interface ProcessingProgress {
  state: "waiting" | "processing" | "failed" | "finished";
  /** Stages finished, of the stages the run will execute (NOT_RUN ones excluded). */
  done: number;
  total: number;
  /** The stage running now (or next queued), by its label. */
  currentStage: string | null;
  detail: string | null;
}

export function processingProgress(captureStatus: string, run: RunProgressLike | null): ProcessingProgress {
  if (!run) {
    return captureStatus === "FAILED"
      ? { state: "failed", done: 0, total: 0, currentStage: null, detail: "The capture failed before processing started." }
      : { state: "waiting", done: 0, total: 0, currentStage: null, detail: null };
  }
  const stages = run.stages.filter((s) => s.state !== "NOT_RUN");
  const done = stages.filter((s) => s.state === "SUCCEEDED").length;
  const current = stages.find((s) => s.state === "RUNNING") ?? stages.find((s) => s.state === "QUEUED" || s.state === "PENDING");
  if (run.status === "FAILED" || run.status === "CANCELLED") {
    return {
      state: "failed", done, total: stages.length, currentStage: run.failureStage ? stageLabel(run.failureStage) : null,
      detail: run.failureMessage ?? (run.status === "CANCELLED" ? "Processing was cancelled." : null),
    };
  }
  if (run.status !== "RUNNING") return { state: "finished", done, total: stages.length, currentStage: null, detail: null };
  return { state: "processing", done, total: stages.length, currentStage: current ? stageLabel(current.stage) : null, detail: null };
}
