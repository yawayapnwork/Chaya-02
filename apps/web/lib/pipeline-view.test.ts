import assert from "node:assert/strict";
import { test } from "node:test";
import { durationSeconds, explainStageError, shortSha, stageLabel, summarizeRun } from "./pipeline-view.ts";

const base = { quality: null, failureStage: null, failureCode: null, failureMessage: null, retryable: false } as const;

test("a partial-quality result is never presented as finalized", () => {
  const s = summarizeRun({ ...base, status: "PARTIAL", quality: "PARTIAL", failureMessage: "time budget ran out before X" });
  assert.equal(s.badge, "Partial quality");
  assert.equal(s.tone, "warning");
  assert.match(s.headline, /NOT finalized/);
  assert.notEqual(s.badge, "Finalized");
});

test("only a FINAL success is presented as finalized; success without FINAL quality is flagged as inconsistent", () => {
  assert.equal(summarizeRun({ ...base, status: "SUCCEEDED", quality: "FINAL" }).badge, "Finalized");
  const odd = summarizeRun({ ...base, status: "SUCCEEDED", quality: null });
  assert.equal(odd.tone, "error");
  assert.notEqual(odd.badge, "Finalized");
});

test("a failed run says where it stopped, offers retry only when retryable, and never claims a result", () => {
  const s = summarizeRun({ ...base, status: "FAILED", failureStage: "POSE_ESTIMATION", failureCode: "DEPENDENCY_UNAVAILABLE", retryable: true });
  assert.match(s.headline, /Camera pose estimation/);
  assert.match(s.headline, /No reconstruction was produced/);
  assert.equal(s.canRetry, true);
  assert.equal(summarizeRun({ ...base, status: "FAILED", retryable: false }).canRetry, false);
});

test("running runs can be cancelled, finished ones cannot", () => {
  assert.equal(summarizeRun({ ...base, status: "RUNNING" }).canCancel, true);
  for (const status of ["SUCCEEDED", "PARTIAL", "FAILED", "CANCELLED"] as const) {
    assert.equal(summarizeRun({ ...base, status, quality: status === "SUCCEEDED" ? "FINAL" : status === "PARTIAL" ? "PARTIAL" : null }).canCancel, false);
  }
});

test("dependency errors name what is missing", () => {
  const text = explainStageError("DEPENDENCY_UNAVAILABLE", "x", { missing: ["colmap", "glomap"] });
  assert.match(text, /colmap, glomap/);
  assert.match(text, /retry/i);
  assert.equal(explainStageError("SOMETHING_NEW", "server text", null), "server text");
  assert.match(explainStageError("STAGE_NOT_IMPLEMENTED", null, null), /not implemented/);
});

test("labels and formatting helpers", () => {
  assert.equal(stageLabel("PRIVACY_PREPROCESS"), "Privacy preprocessing");
  assert.equal(stageLabel("UNKNOWN"), "UNKNOWN");
  assert.equal(shortSha("a".repeat(64)), "aaaaaaaaaaaa");
  assert.equal(shortSha(null), "—");
  assert.equal(durationSeconds("2026-01-01T00:00:00Z", "2026-01-01T00:00:02.500Z"), 2.5);
});
