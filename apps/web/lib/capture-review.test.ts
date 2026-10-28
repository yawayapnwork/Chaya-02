import assert from "node:assert/strict";
import { test } from "node:test";
import { buildOperatorDepartureReview, summarizeFrameSamples } from "./capture-review.ts";
import type { HudStatus, QualitySample } from "./hud-types.ts";

const sample = (over: Partial<QualitySample> = {}): QualitySample => ({
  capturedAtMs: 1000,
  blurScore: 150,
  brightnessMean: 120,
  shadowClipFraction: 0.01,
  highlightClipFraction: 0.01,
  motionScore: 8,
  duplicateFrame: false,
  featureCount: 85,
  spacingMeters: 0.3,
  warnings: [],
  ...over,
});

const baseStatus = (over: Partial<HudStatus> = {}): HudStatus => ({
  trackingAvailable: true,
  trackingUnavailableReason: null,
  currentPosition: { x: 1, y: 2, headingDegrees: 90, asOfMs: 2000, kind: "TRACKED", source: "webxr" },
  pathSoFar: [],
  sceneSet: true,
  coverageAvailable: true,
  coverageUnavailableReason: null,
  coveragePercent: 94.5,
  weightedCoveragePercent: 95.0,
  uncoveredAreaM2: 0,
  uncoveredZones: [],
  plannedPath: [
    { order: 1, x: 1, y: 1, yawDegrees: 0, type: "CORNER", reason: "Corner", expectedCoverageGainM2: 4, status: "VISITED" },
    { order: 2, x: 3, y: 1, yawDegrees: 90, type: "WALL", reason: "Wall", expectedCoverageGainM2: 4, status: "VISITED" },
  ],
  quality: null,
  warnings: [],
  reshootRecommendations: [],
  computedAt: "2026-10-27T06:00:00Z",
  trackingState: "TRACKING",
  sceneSource: "ESTIMATED",
  coverageBasis: "LIVE_TRACKED",
  coverageLive: true,
  plannedPathKind: "PERSISTED_PLAN",
  completion: "COVERAGE_TARGET_REACHED",
  ...over,
});

test("summarizeFrameSamples: counts per-defect warnings and computes exact rejectedFrameRate", () => {
  const samples: QualitySample[] = [
    sample(),
    sample({ warnings: ["BLUR", "LOW_FEATURE_COUNT"] }),
    sample({ warnings: ["UNDEREXPOSED"] }),
    sample({ warnings: ["SPACING_TOO_FAR"] }), // spacing warning alone is an overlap warning, not a frame-filter drop
    sample({ warnings: ["FAST_MOTION"] }),
  ];
  const s = summarizeFrameSamples(samples, null);
  assert.equal(s.analyzedFrames, 5);
  assert.equal(s.defectiveFrames, 3);
  assert.equal(s.rejectedFrameRate, 0.6);
  assert.equal(s.counts.BLUR, 1);
  assert.equal(s.counts.LOW_FEATURE_COUNT, 1);
  assert.equal(s.counts.UNDEREXPOSED, 1);
  assert.equal(s.counts.FAST_MOTION, 1);
  assert.equal(s.counts.SPACING_TOO_FAR, 1);
});

test("buildOperatorDepartureReview: blocks departure when uploads are pending, failed, quarantined or missing", () => {
  const r = buildOperatorDepartureReview(
    [
      { id: "1", name: "walk.mp4", state: "ACCEPTED", attempts: 2 },
      { id: "2", name: "corridor.mp4", state: "QUARANTINED", attempts: 1, code: "SCANNER_UNAVAILABLE" },
      { id: "3", name: "room2.mp4", state: "FAILED", attempts: 8, error: "Network timeout" },
    ],
    [sample()],
    baseStatus(),
  );
  assert.equal(r.verdict, "ACTION_REQUIRED");
  assert.equal(r.media.acceptedFiles, 1);
  assert.equal(r.media.quarantinedFiles, 1);
  assert.equal(r.media.failedFiles, 1);
  assert.equal(r.media.retriedFiles, 2);
  assert.equal(r.media.totalRetryAttempts, 8);
  assert.equal(r.media.uploadRetryRate, 0.6667);
  assert.equal(r.media.problems.length, 2);
  assert.match(r.media.problems[0].reason, /quarantine/);
});

test("buildOperatorDepartureReview: recommends reshoots when coverage gaps, unvisited waypoints, or high frame defect rate exist", () => {
  const r = buildOperatorDepartureReview(
    [{ id: "1", name: "walk.mp4", state: "ACCEPTED", attempts: 1 }],
    [
      sample({ warnings: ["BLUR"] }),
      sample({ warnings: ["LOW_FEATURE_COUNT"] }),
      sample(),
    ],
    baseStatus({
      coveragePercent: 72,
      uncoveredAreaM2: 6.4,
      uncoveredZones: [{ centroidX: 4.5, centroidY: 2.1, areaM2: 6.4, reason: "NOT_OBSERVED" }],
      plannedPath: [
        { order: 1, x: 1, y: 1, yawDegrees: 0, type: "CORNER", reason: "Corner", expectedCoverageGainM2: 4, status: "VISITED" },
        { order: 2, x: 4.5, y: 2.1, yawDegrees: 90, type: "UNOBSERVED", reason: "Alcove", expectedCoverageGainM2: 6.4, status: "PENDING" },
      ],
      reshootRecommendations: [
        { reason: "REVISIT_WAYPOINT 1 (BLUR in 3/4 frames at the visit)", x: 1.0, y: 1.0 },
      ],
    }),
  );
  assert.equal(r.verdict, "RESHOOT_RECOMMENDED");
  assert.equal(r.coverage.pendingWaypoints, 1);
  assert.equal(r.coverage.uncoveredZones.length, 1);
  assert.equal(r.reshoots.length, 1);
  assert.match(r.reshoots[0].action, /at \(1\.0, 1\.0\)/);
  assert.equal(r.frameQuality.rejectedFrameRate, 0.6667);
});

test("buildOperatorDepartureReview: READY_TO_LEAVE when media is accepted and coverage/quality are clean, and honest when coverage was not measured", () => {
  const clean = buildOperatorDepartureReview(
    [{ id: "1", name: "walk.mp4", state: "ACCEPTED", attempts: 1 }],
    [sample(), sample(), sample()],
    baseStatus(),
  );
  assert.equal(clean.verdict, "READY_TO_LEAVE");
  assert.equal(clean.reshoots.length, 0);

  const unmeasured = buildOperatorDepartureReview(
    [{ id: "1", name: "walk.mp4", state: "ACCEPTED", attempts: 1 }],
    [],
    baseStatus({ coverageAvailable: false, coverageUnavailableReason: "room outline not set", coveragePercent: null }),
  );
  assert.equal(unmeasured.verdict, "READY_TO_LEAVE");
  assert.match(unmeasured.headline, /coverage was not measured/);
});
