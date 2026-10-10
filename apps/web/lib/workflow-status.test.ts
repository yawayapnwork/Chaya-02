import assert from "node:assert/strict";
import { test } from "node:test";
import { type RunLike, published, workflowStatus } from "./workflow-status.ts";

const ORDER = ["INPUT_VALIDATION", "FFMPEG_PREPROCESS", "FRAME_QUALITY_FILTER", "PRIVACY_PREPROCESS", "POSE_ESTIMATION",
  "SPLAT_RECONSTRUCTION", "SEMANTIC_SEGMENTATION", "GEOMETRIC_CLEANUP", "PLANE_FITTING", "ARTIFACT_GENERATION",
  "SEMANTIC_INDEXING", "NAVIGATION_BAKING"];
const OUT: Record<string, string> = { ARTIFACT_GENERATION: "KSPLAT", SEMANTIC_INDEXING: "DETECTED_OBJECTS", NAVIGATION_BAKING: "NAVIGATION_GRAPH" };

// Synthetic run states, as the API reports them (RunView); not a real run.
function run(status: string, upTo: number, current: { state: string; errorCode?: string } | null = null): RunLike {
  return {
    id: "run-1", status, quality: status === "SUCCEEDED" ? "FINAL" : null, failureStage: current?.state === "FAILED" ? ORDER[upTo] : null,
    failureCode: current?.errorCode ?? null, failureMessage: null,
    stages: ORDER.map((stage, i) => {
      if (i < upTo) return { stage, state: "SUCCEEDED", lastRun: { status: "SUCCEEDED", errorCode: null, errorMessage: null, artifacts: [{ kind: OUT[stage] ?? "X" }] } };
      if (i === upTo && current) {
        return { stage, state: current.state, lastRun: current.state === "FAILED"
          ? { status: "FAILED", errorCode: current.errorCode ?? null, errorMessage: "m", artifacts: [] } : null };
      }
      return { stage, state: "PENDING", lastRun: null };
    }),
  };
}
const byKey = (items: ReturnType<typeof workflowStatus>) => Object.fromEntries(items.map((i) => [i.key, i]));

test("a queued stage is waiting for a worker, not running", () => {
  const s = byKey(workflowStatus(run("RUNNING", 4, { state: "QUEUED" }), [], null));
  assert.equal(s.processing.state, "WAITING");
  assert.match(s.processing.detail, /POSE_ESTIMATION is queued/);
  assert.equal(s.viewer.state, "WAITING");
});

test("a generated .ksplat of an unpublished run is not offered as a viewable reconstruction", () => {
  const s = byKey(workflowStatus(run("RUNNING", 10, { state: "RUNNING" }), [], null));
  assert.equal(s.viewer.state, "WAITING");
  assert.match(s.viewer.detail, /when this run's version is published/);
  assert.equal(s.search.state, "IN_PROGRESS");
  assert.equal(s.publication.state, "NOT_AVAILABLE");
});

test("failure reasons are surfaced where they happened, and later capabilities are not available", () => {
  const s = byKey(workflowStatus(run("FAILED", 10, { state: "FAILED", errorCode: "NOT_CALIBRATED" }), [], null));
  assert.equal(s.processing.state, "FAILED");
  assert.match(s.processing.detail, /SEMANTIC_INDEXING \(NOT_CALIBRATED\)/);
  assert.equal(s.search.state, "FAILED");
  assert.equal(s.navigation.state, "NOT_AVAILABLE");
  assert.equal(s.calibration.state, "WAITING");
});

test("only a published version makes the viewer, search and navigation available", () => {
  const done = run("SUCCEEDED", 12);
  assert.equal(byKey(workflowStatus(done, [], "CALIBRATED")).publication.state, "WAITING");
  const s = byKey(workflowStatus(done, [{ runId: "run-1", versionNumber: 3, current: true }], "CALIBRATED"));
  for (const k of ["processing", "viewer", "calibration", "search", "navigation", "publication"]) assert.equal(s[k].state, "AVAILABLE", k);
  assert.match(s.publication.detail, /Version 3 is the floor's current version/);
});

test("a stage that succeeded without its artifact does not count as available", () => {
  const r = run("RUNNING", 11);
  r.stages[10].lastRun!.artifacts = [];
  assert.equal(published(r, "SEMANTIC_INDEXING", "DETECTED_OBJECTS"), false);
  assert.equal(byKey(workflowStatus(r, [], null)).search.state, "WAITING");
});
