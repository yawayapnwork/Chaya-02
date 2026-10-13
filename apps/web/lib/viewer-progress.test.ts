import assert from "node:assert/strict";
import { test } from "node:test";
import { pendingCapture, processingProgress, type StageLike } from "./viewer-progress.ts";

const cap = (id: string, floorId: string | null, status: string, startedAt: string) => ({ id, floorId, status, startedAt });

test("the newest capture of the floor is reported while it is in progress or failed, never one of another floor", () => {
  const caps = [
    cap("old", "f1", "COMPLETED", "2026-10-01T00:00:00Z"),
    cap("new", "f1", "PROCESSING", "2026-10-02T00:00:00Z"),
    cap("other", "f2", "PROCESSING", "2026-10-03T00:00:00Z"),
  ];
  assert.equal(pendingCapture(caps, "f1")?.id, "new");
  assert.equal(pendingCapture([...caps, cap("newer", "f1", "COMPLETED", "2026-10-04T00:00:00Z")], "f1"), null);
  assert.equal(pendingCapture([cap("x", "f1", "FAILED", "2026-10-01T00:00:00Z")], "f1")?.id, "x");
  assert.equal(pendingCapture([cap("x", "f1", "UPLOADING", "2026-10-01T00:00:00Z")], "f1"), null); // not submitted yet
});

const stages = (...states: StageLike["state"][]): StageLike[] =>
  states.map((state, i) => ({ stage: ["INPUT_VALIDATION", "POSE_ESTIMATION", "SPLAT_RECONSTRUCTION", "X"][i], state }));

test("a running run reports the stages it has finished of the ones it will run, and the one running now", () => {
  const p = processingProgress("PROCESSING", { status: "RUNNING", failureStage: null, failureMessage: null,
    stages: stages("SUCCEEDED", "RUNNING", "PENDING", "NOT_RUN") });
  assert.deepEqual(p, { state: "processing", done: 1, total: 3, currentStage: "Camera pose estimation", detail: null });
});

test("a failed run says where and why", () => {
  const p = processingProgress("FAILED", { status: "FAILED", failureStage: "SPLAT_RECONSTRUCTION", failureMessage: "out of GPU memory",
    stages: stages("SUCCEEDED", "SUCCEEDED", "FAILED") });
  assert.equal(p.state, "failed");
  assert.equal(p.currentStage, "Gaussian splat reconstruction");
  assert.equal(p.detail, "out of GPU memory");
});

test("no run yet: waiting, or failed before processing", () => {
  assert.equal(processingProgress("READY_FOR_PROCESSING", null).state, "waiting");
  assert.equal(processingProgress("FAILED", null).state, "failed");
});
