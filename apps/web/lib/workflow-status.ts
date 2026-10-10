// What a capture's run has really produced, for the capture page: processing, the viewer asset, calibration, search,
// navigation and publication. Every state is derived from persisted facts -- a stage run that SUCCEEDED and published the
// artifact, the run's status, the floor's listed (FINALIZED) versions, the capture's calibration status -- never from a
// stage having been queued or started. In particular a generated .ksplat is not "a reconstruction is ready": the viewer
// only lists versions that were published, which needs every stage, a canonical frame and the publication checks.
// Pure (no Next imports), so it is unit-tested.

export type ItemState = "AVAILABLE" | "IN_PROGRESS" | "WAITING" | "FAILED" | "NOT_AVAILABLE";

export interface WorkflowItem {
  key: "processing" | "viewer" | "calibration" | "search" | "navigation" | "publication";
  label: string;
  state: ItemState;
  detail: string;
}

interface ArtifactLike {
  kind: string;
}
interface StageLike {
  stage: string;
  state: string; // PENDING, QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED, NOT_RUN
  lastRun: { status: string; errorCode: string | null; errorMessage: string | null; artifacts: ArtifactLike[] } | null;
}
export interface RunLike {
  id: string;
  status: string; // RUNNING, SUCCEEDED, PARTIAL, FAILED, CANCELLED
  quality: string | null;
  failureStage: string | null;
  failureCode: string | null;
  failureMessage: string | null;
  stages: StageLike[];
}
export interface PublishedVersionLike {
  runId: string;
  versionNumber: number | null;
  current: boolean;
}

function stageOf(run: RunLike | null, name: string): StageLike | undefined {
  return run?.stages.find((s) => s.stage === name);
}

/** The stage succeeded and its successful run published `kind`. */
export function published(run: RunLike | null, stage: string, kind: string): boolean {
  const s = stageOf(run, stage);
  return s?.state === "SUCCEEDED" && s.lastRun?.status === "SUCCEEDED" && s.lastRun.artifacts.some((a) => a.kind === kind);
}

function stageItem(run: RunLike | null, stage: string, kind: string, label: string, key: WorkflowItem["key"],
                   isPublished: boolean, availableDetail: string): WorkflowItem {
  const s = stageOf(run, stage);
  if (!run || !s) return { key, label, state: "NOT_AVAILABLE", detail: "Processing has not reached this stage." };
  if (published(run, stage, kind)) {
    return isPublished
      ? { key, label, state: "AVAILABLE", detail: availableDetail }
      : { key, label, state: "WAITING", detail: `${kind} was produced; it becomes available when this run's version is published.` };
  }
  if (s.state === "FAILED" || s.lastRun?.status === "FAILED") {
    return { key, label, state: "FAILED", detail: `${stage} failed${s.lastRun?.errorCode ? ` (${s.lastRun.errorCode})` : ""}${s.lastRun?.errorMessage ? `: ${s.lastRun.errorMessage}` : ""}` };
  }
  if (s.state === "RUNNING") return { key, label, state: "IN_PROGRESS", detail: `${stage} is running on a worker.` };
  if (s.state === "QUEUED") return { key, label, state: "WAITING", detail: `${stage} is queued, waiting for a worker that can run it.` };
  if (run.status === "PARTIAL" || run.status === "FAILED" || run.status === "CANCELLED") {
    return { key, label, state: "NOT_AVAILABLE", detail: `The run ended ${run.status} before ${stage}.` };
  }
  return { key, label, state: "WAITING", detail: `${stage} has not started.` };
}

export function workflowStatus(run: RunLike | null, versions: readonly PublishedVersionLike[], calibrationState: string | null): WorkflowItem[] {
  const version = run ? versions.find((v) => v.runId === run.id) : undefined;
  const isPublished = !!version;
  const items: WorkflowItem[] = [];

  if (!run) {
    items.push({ key: "processing", label: "Processing", state: "NOT_AVAILABLE", detail: "Processing has not started." });
  } else if (run.status === "RUNNING") {
    const active = run.stages.find((s) => s.state === "RUNNING") ?? run.stages.find((s) => s.state === "QUEUED");
    items.push({
      key: "processing", label: "Processing", state: active?.state === "RUNNING" ? "IN_PROGRESS" : "WAITING",
      detail: !active ? "Between stages." : active.state === "RUNNING" ? `${active.stage} is running on a worker.`
        : `${active.stage} is queued; no worker that can run it has claimed it yet.`,
    });
  } else if (run.status === "SUCCEEDED") {
    items.push({ key: "processing", label: "Processing", state: "AVAILABLE", detail: "Every stage succeeded (final quality)." });
  } else if (run.status === "PARTIAL") {
    items.push({ key: "processing", label: "Processing", state: "FAILED",
      detail: `Partial result only: ${run.failureMessage ?? "the time budget ran out"}. A partial result is never published.` });
  } else {
    items.push({ key: "processing", label: "Processing", state: "FAILED",
      detail: `${run.status} at ${run.failureStage ?? "?"}${run.failureCode ? ` (${run.failureCode})` : ""}${run.failureMessage ? `: ${run.failureMessage}` : ""}` });
  }

  items.push(stageItem(run, "ARTIFACT_GENERATION", "KSPLAT", "3D viewer asset", "viewer", isPublished,
    "Published: open it in the viewer."));
  items.push(calibrationState === "CALIBRATED"
    ? { key: "calibration", label: "Metric calibration", state: "AVAILABLE", detail: "The reconstruction has a metric coordinate frame." }
    : calibrationState === "REJECTED"
      ? { key: "calibration", label: "Metric calibration", state: "FAILED", detail: "The last calibration was refused; see Measurements and calibration." }
      : { key: "calibration", label: "Metric calibration", state: "WAITING",
          detail: "Not calibrated: search, navigation and publication need a calibrated reconstruction." });
  items.push(stageItem(run, "SEMANTIC_INDEXING", "DETECTED_OBJECTS", "Object search", "search", isPublished,
    "Published: detected objects are POIs, searchable in the viewer."));
  items.push(stageItem(run, "NAVIGATION_BAKING", "NAVIGATION_GRAPH", "Navigation", "navigation", isPublished,
    "Published: routes can be planned on this floor."));

  if (version) {
    items.push({ key: "publication", label: "Published version", state: "AVAILABLE",
      detail: `Version ${version.versionNumber ?? "?"}${version.current ? " is the floor's current version" : " (not the floor's current version)"}.` });
  } else if (run?.status === "SUCCEEDED") {
    items.push({ key: "publication", label: "Published version", state: "WAITING",
      detail: "Every stage succeeded but the version is not published yet (it needs a calibrated reconstruction)." });
  } else {
    items.push({ key: "publication", label: "Published version", state: "NOT_AVAILABLE",
      detail: "A version is published only when every stage succeeded on a calibrated reconstruction." });
  }
  return items;
}
