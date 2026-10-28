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
  assert.equal(s.canRetry, false);
});

test("a partial result offers a retry only when the server says its training can be resumed", () => {
  const partial = { ...base, status: "PARTIAL", quality: "PARTIAL", failureStage: "SPLAT_RECONSTRUCTION", failureCode: "TIME_LIMIT_EXCEEDED" } as const;
  const resumable = summarizeRun({ ...partial, retryable: true });
  assert.equal(resumable.canRetry, true);
  assert.match(resumable.headline, /NOT finalized/);
  assert.equal(summarizeRun({ ...partial, retryable: false }).canRetry, false);
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

test("a refused or unmeasured reconstruction says nothing was published", () => {
  assert.match(explainStageError("SPLAT_QUALITY_INSUFFICIENT", "x", null), /held out of training.*Nothing was published/);
  assert.match(explainStageError("SPLAT_QUALITY_UNVERIFIED", "x", null), /could not be measured.*not published/);
  assert.match(explainStageError("SPLAT_IO_FAILED", "x", null), /file operation.*Nothing was published/);
});

test("labels and formatting helpers", () => {
  assert.equal(stageLabel("PRIVACY_PREPROCESS"), "Privacy preprocessing");
  assert.equal(stageLabel("UNKNOWN"), "UNKNOWN");
  assert.equal(shortSha("a".repeat(64)), "aaaaaaaaaaaa");
  assert.equal(shortSha(null), "—");
  assert.equal(durationSeconds("2026-01-01T00:00:00Z", "2026-01-01T00:00:02.500Z"), 2.5);
});

test("contract and calibration failures are explained with what to do next", () => {
  assert.match(explainStageError("STAGE_OUTPUT_MISSING", "m", { missing: ["KSPLAT"] }), /\(KSPLAT\).*retry/);
  assert.match(explainStageError("NOT_CALIBRATED", "m", null), /Calibrate it/);
  assert.match(explainStageError("DETECTED_OBJECTS_INVALID", "m", null), /Nothing was dropped silently/);
  assert.match(explainStageError("NAVIGATION_GRAPH_INVALID", "m", null), /no routable graph/);
});

import { privacyImpactNotice } from "./pipeline-view.ts";

test("PRIVACY_SFM_DEGRADED error and privacyImpactNotice warn when privacy masking degrades SfM features", () => {
  const errText = explainStageError("PRIVACY_SFM_DEGRADED", null, {
    usable_frames_before_mask: 8,
    usable_frames_after_mask: 1,
  });
  assert.match(errText, /1 of 8 textured frames remained usable/);
  assert.match(errText, /passing destroyed input to SfM/);

  const warnNotice = privacyImpactNotice([
    {
      stage: "PRIVACY_PREPROCESS",
      state: "SUCCEEDED",
      attempts: 1,
      lastRun: {
        artifacts: [],
        command: {
          config: {
            privacy_review_required: true,
            privacy_degraded_frames: 2,
            privacy_degraded_frame_names: ["000004.jpg", "000009.jpg"],
            mean_retained_feature_ratio: 0.42,
            mean_sfm_masked_fraction: 0.51,
            privacy_warnings: [
              "Heuristic quadrilateral screen/document detection masked large rectangular surfaces.",
            ],
          },
        },
      },
    },
  ]);
  assert.ok(warnNotice);
  assert.equal(warnNotice.severity, "warning");
  assert.equal(warnNotice.degradedFrameCount, 2);
  assert.deepEqual(warnNotice.degradedFrameNames, ["000004.jpg", "000009.jpg"]);
  assert.equal(warnNotice.meanRetainedFeatureRatio, 0.42);
  assert.equal(warnNotice.warnings.length, 1);

  const failNotice = privacyImpactNotice([
    {
      stage: "PRIVACY_PREPROCESS",
      state: "FAILED",
      attempts: 1,
      lastRun: {
        artifacts: [],
        errorCode: "PRIVACY_SFM_DEGRADED",
        errorDetails: {
          degraded_frame_count: 4,
          degraded_frames: ["000001.jpg", "000002.jpg", "000003.jpg", "000004.jpg"],
          mean_retained_feature_ratio: 0.08,
          mean_sfm_masked_fraction: 0.68,
          warnings: ["Privacy masking materially degraded SfM feature coverage in 4 of 4 frame(s)."],
        },
      },
    },
  ]);
  assert.ok(failNotice);
  assert.equal(failNotice.severity, "error");
  assert.equal(failNotice.degradedFrameCount, 4);
});
