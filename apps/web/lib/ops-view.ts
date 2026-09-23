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
