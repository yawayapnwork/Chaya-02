// Pure presentation rules for the operations dashboard. No value is ever invented here: a missing value is
// rendered as missing ("never", "—", "unavailable"), and a PARTIAL or unfinished reconstruction is never labelled
// final.

export type JobStatusKey = "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED" | "CANCELLED";

/** Display order and labels for the job lifecycle (SUCCEEDED is shown as "Completed"). */
export const JOB_STATUSES: { key: JobStatusKey; label: string }[] = [
  { key: "QUEUED", label: "Queued" },
  { key: "RUNNING", label: "Running" },
  { key: "SUCCEEDED", label: "Completed" },
  { key: "FAILED", label: "Failed" },
  { key: "CANCELLED", label: "Cancelled" },
];

export function jobStatusLabel(status: string): string {
  return JOB_STATUSES.find((s) => s.key === status)?.label ?? status;
}

/** Age in seconds (from the server, measured against its own clock) as a short human string. */
export function formatAge(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined) return "never";
  if (seconds < 0) return "timestamp is ahead of the server clock";
  if (seconds < 60) return `${seconds} s ago`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 48) return `${hours} h ago`;
  return `${Math.floor(hours / 24)} days ago`;
}

/** A duration in seconds, for measured worker time. */
export function formatDuration(seconds: number): string {
  if (seconds < 60) return `${seconds.toFixed(1)} s`;
  if (seconds < 3600) return `${(seconds / 60).toFixed(1)} min`;
  return `${(seconds / 3600).toFixed(2)} h`;
}

export function formatMs(ms: number | null | undefined): string {
  return ms === null || ms === undefined ? "—" : `${Math.round(ms)} ms`;
}

export function formatPercent(value: number | null | undefined): string {
  return value === null || value === undefined ? "—" : `${value.toFixed(1)}%`;
}

/** Share of zero-result queries; null (not 0%) when there were no queries at all. */
export function zeroResultRate(zero: number, total: number): number | null {
  return total === 0 ? null : (zero / total) * 100;
}

/**
 * How to label a reconstruction's quality. Only FINAL is "Final"; PARTIAL is explicitly not final; anything else
 * (null quality: the run did not finish every stage) says so with the run status.
 */
export function reconstructionQualityLabel(quality: string | null, runStatus: string): { label: string; final: boolean } {
  if (quality === "FINAL") return { label: "Final", final: true };
  if (quality === "PARTIAL") return { label: "Partial — not final", final: false };
  return { label: `Not final (run ${runStatus})`, final: false };
}

const GAP_LABEL: Record<string, string> = {
  NO_RECONSTRUCTION: "No reconstruction has been produced for this floor",
  NO_FINALIZED_VERSION: "No finalized scan version",
  NO_MEASURED_COVERAGE: "No capture with a room outline, so coverage has never been measured",
  COVERAGE_UNAVAILABLE: "Coverage could not be computed for the latest measured capture",
  UNCOVERED_ZONES: "Uncovered zones remain in the latest measured capture",
};

export function gapLabel(gap: string): string {
  return GAP_LABEL[gap] ?? gap;
}

export type LoadFailure = "signed-out" | "unauthorized" | "not-found" | "error";

/** Maps an HTTP status from a failed section request to the state the section shows. */
export function classifyFailure(status: number | null | undefined): LoadFailure {
  if (status === 401) return "signed-out";
  if (status === 403) return "unauthorized";
  if (status === 404) return "not-found";
  return "error";
}

/** A count the server may not have been able to measure: null or undefined is "unavailable", never 0. */
export function formatCount(value: number | null | undefined): string {
  return value === null || value === undefined ? "unavailable" : String(value);
}

/**
 * Whether a section keeps showing its last good data after a refresh fails. Only for transient failures (network,
 * 5xx, a dependency outage): after a 401, 403 or 404 the caller may no longer see this data, so it is dropped.
 */
export function keepsStaleDataAfter(kind: LoadFailure): boolean {
  return kind === "error";
}

export interface WorkerLike {
  workerId: string;
  stages: string[];
  lastSeenAgeSeconds: number;
  live: boolean;
}

export interface QueueLike {
  claimableQueued: number;
  oldestQueuedAgeSeconds: number | null;
  running: number;
  staleRunning: number;
  overdueRunning: number;
  unservedStages: string[];
  workers: WorkerLike[];
  workerLiveAfterSeconds: number;
}

export type QueueProblem = { level: "error" | "warning"; text: string };

/**
 * What is wrong with a venue's queue, worst first, from measured values only. An empty list means nothing measured is
 * wrong; it does not claim processing is healthy beyond what was measured.
 */
export function queueProblems(q: QueueLike): QueueProblem[] {
  const out: QueueProblem[] = [];
  const live = q.workers.filter((w) => w.live).length;
  if (live === 0) {
    out.push({
      level: "error",
      text: q.workers.length === 0
        ? "No worker has contacted the API in the last 24 hours. Nothing can be processed."
        : `No worker is live: none has polled for work or sent a heartbeat in the last ${Math.round(q.workerLiveAfterSeconds / 60)} min.`,
    });
  }
  if (q.unservedStages.length > 0) {
    out.push({
      level: "error",
      text: `Queued work cannot start: no live worker runs ${q.unservedStages.join(", ")}.`,
    });
  }
  if (q.staleRunning > 0) {
    out.push({
      level: "warning",
      text: `${q.staleRunning} running job${q.staleRunning === 1 ? " has" : "s have"} an expired lease (the worker stopped). The lease enforcer fails ${q.staleRunning === 1 ? "it" : "them"} within about 30 s; if this persists, it is not running.`,
    });
  }
  if (q.overdueRunning > 0) {
    out.push({
      level: "warning",
      text: `${q.overdueRunning} running job${q.overdueRunning === 1 ? " is" : "s are"} past the run's time budget and will be stopped after the grace period.`,
    });
  }
  return out;
}

/** A RUNNING job whose lease expired before the server's asOf: its worker is gone and recovery is pending. */
export function leaseExpired(status: string, leaseExpiresAt: string | null, asOf: string): boolean {
  return status === "RUNNING" && leaseExpiresAt !== null && Date.parse(leaseExpiresAt) <= Date.parse(asOf);
}
