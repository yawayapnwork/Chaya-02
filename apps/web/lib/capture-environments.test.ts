import assert from "node:assert/strict";
import { test } from "node:test";
import {
  type Frame,
  type QualityContext,
  type QualitySample,
  assessFrame,
} from "./capture-quality.ts";
import {
  type DepartureVerdict,
  type OperatorDepartureReview,
  type ReviewMediaItem,
  buildOperatorDepartureReview,
  summarizeFrameSamples,
} from "./capture-review.ts";
import {
  type LocalState,
  guidance,
} from "./capture-guidance.ts";
import {
  detectCaptureAr,
} from "./capture-xr-session.ts";
import type {
  HudStatus,
  PlannedWaypoint,
  ReconAssessment,
} from "./hud-types.ts";

/**
 * Capture Environment Acceptance Suite.
 *
 * Validates guided capture workflow, coverage tracking, live frame-quality evaluation,
 * and operator guidance across 5 indoor test environments:
 * 1. Small furnished room
 * 2. Long corridor
 * 3. Texture-poor room
 * 4. Room with reflective surfaces
 * 5. Room with doorway occlusions
 *
 * Verifies honest metrics reporting without fabricated coverage percentages,
 * graceful degradation on unsupported hardware, and departure review gating.
 */

const makeMockFrame = (width: number, height: number, fill: number, pattern?: (x: number, y: number) => number): Frame => {
  const data = new Uint8ClampedArray(width * height * 4);
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const idx = (y * width + x) * 4;
      const v = pattern ? pattern(x, y) : fill;
      data[idx] = v;
      data[idx + 1] = v;
      data[idx + 2] = v;
      data[idx + 3] = 255;
    }
  }
  return { width, height, data };
};

const makeQualitySample = (over: Partial<QualitySample> = {}): QualitySample => ({
  capturedAtMs: 1000,
  blurScore: 165,
  brightnessMean: 128,
  shadowClipFraction: 0.01,
  highlightClipFraction: 0.01,
  motionScore: 8,
  duplicateFrame: false,
  featureCount: 88,
  spacingMeters: 0.35,
  warnings: [],
  ...over,
});

const makeRecon = (over: Partial<ReconAssessment> = {}): ReconAssessment => ({
  verdict: "READY",
  positionBasis: "TRACKED",
  metric: true,
  samples: 50,
  durationSeconds: 12.0,
  pathLengthMeters: 8.5,
  segments: 1,
  headingShare: 1.0,
  trackingLosses: 0,
  lostSeconds: 0,
  longestGapSeconds: 0,
  frameId: "xr-env-session-1",
  excludedOtherFrame: 0,
  wallObservations: 140,
  boundsEstimable: true,
  issues: [],
  requirements: {
    minDurationSeconds: 8,
    minSamples: 10,
    minManualMarks: 3,
    minPathMeters: 3,
    minWallObservations: 12,
  },
  ...over,
});

const makeStatus = (over: Partial<HudStatus> = {}): HudStatus => ({
  trackingAvailable: true,
  trackingUnavailableReason: null,
  currentPosition: { x: 2.0, y: 1.5, headingDegrees: 45, asOfMs: 10_000, kind: "TRACKED", source: "webxr" },
  pathSoFar: [],
  sceneSet: true,
  coverageAvailable: true,
  coverageUnavailableReason: null,
  coveragePercent: 88.0,
  weightedCoveragePercent: 90.0,
  uncoveredAreaM2: 2.5,
  uncoveredZones: [],
  plannedPath: [
    { order: 1, x: 1.0, y: 1.0, yawDegrees: 0, type: "CORNER", reason: "Corner NW", expectedCoverageGainM2: 3.5, status: "VISITED" },
    { order: 2, x: 4.0, y: 1.0, yawDegrees: 90, type: "WALL", reason: "Wall North", expectedCoverageGainM2: 3.0, status: "VISITED" },
    { order: 3, x: 4.0, y: 3.5, yawDegrees: 180, type: "CORNER", reason: "Corner NE", expectedCoverageGainM2: 3.5, status: "VISITED" },
    { order: 4, x: 1.0, y: 3.5, yawDegrees: 270, type: "WALL", reason: "Wall West", expectedCoverageGainM2: 3.0, status: "VISITED" },
  ],
  quality: null,
  warnings: [],
  reshootRecommendations: [],
  computedAt: "2026-10-31T19:00:00Z",
  trackingState: "TRACKING",
  sceneSource: "ESTIMATED",
  coverageBasis: "LIVE_TRACKED",
  coverageLive: true,
  plannedPathKind: "PERSISTED_PLAN",
  completion: "COVERAGE_TARGET_REACHED",
  qualityFlags: [],
  nextWaypoint: null,
  ...over,
});

const makeLocalState = (over: Partial<LocalState> = {}): LocalState => ({
  mode: "AR",
  arSupport: "SUPPORTED",
  arSessionActive: true,
  cameraState: "LIVE",
  motionWarnings: [],
  headingCalibrated: true,
  nowMs: 10_000,
  ...over,
});

// 1. Small Furnished Room
test("environment 1: small furnished room (6m x 5m with desk, sofa, table)", () => {
  // Recon lap spans perimeter, observing walls around furniture
  const reconStatus = makeStatus({
    coveragePercent: 91.4,
    weightedCoveragePercent: 93.2,
    uncoveredAreaM2: 1.8,
    completion: "COVERAGE_TARGET_REACHED",
    reconnaissance: makeRecon({
      samples: 60,
      durationSeconds: 14.5,
      pathLengthMeters: 11.2,
      wallObservations: 168,
    }),
  });

  // 158 frames analyzed during session: 6 minor motion blurs on quick turns, 152 clean frames
  const samples: QualitySample[] = [];
  for (let i = 0; i < 152; i++) {
    samples.push(makeQualitySample({ capturedAtMs: 1000 + i * 500, spacingMeters: 0.32 }));
  }
  for (let i = 0; i < 6; i++) {
    samples.push(makeQualitySample({ capturedAtMs: 76000 + i * 500, warnings: ["FAST_MOTION"], motionScore: 32 }));
  }

  const review = buildOperatorDepartureReview(
    [{ id: "vid-1", name: "room_scan.mp4", state: "ACCEPTED", attempts: 1 }],
    samples,
    reconStatus,
  );

  assert.equal(review.verdict, "READY_TO_LEAVE");
  assert.equal(review.reshoots.length, 0);
  assert.equal(review.coverage.observedCoveragePercent, 91.4);
  // Registered-frame rate >= 96%
  const summary = summarizeFrameSamples(samples, null);
  assert.equal(summary.analyzedFrames, 158);
  assert.ok(summary.rejectedFrameRate <= 0.05, `Defect rate ${summary.rejectedFrameRate} must be <= 5%`);
});

// 2. Long Corridor
test("environment 2: long corridor (18m x 2.2m with linear perspective)", () => {
  const corridorStatus = makeStatus({
    coveragePercent: 88.5,
    weightedCoveragePercent: 89.8,
    uncoveredAreaM2: 2.8,
    completion: "COVERAGE_TARGET_REACHED",
    plannedPath: [
      { order: 1, x: 2.0, y: 1.1, yawDegrees: 0, type: "WALL", reason: "Start West", expectedCoverageGainM2: 5, status: "VISITED" },
      { order: 2, x: 6.0, y: 1.1, yawDegrees: 0, type: "WALL", reason: "Midway 1", expectedCoverageGainM2: 5, status: "VISITED" },
      { order: 3, x: 10.0, y: 1.1, yawDegrees: 0, type: "WALL", reason: "Midway 2", expectedCoverageGainM2: 5, status: "VISITED" },
      { order: 4, x: 14.0, y: 1.1, yawDegrees: 0, type: "WALL", reason: "Midway 3", expectedCoverageGainM2: 5, status: "VISITED" },
      { order: 5, x: 17.0, y: 1.1, yawDegrees: 180, type: "WALL", reason: "End East Loop Closure", expectedCoverageGainM2: 5, status: "VISITED" },
    ],
    reconnaissance: makeRecon({
      samples: 95,
      durationSeconds: 22.0,
      pathLengthMeters: 34.0, // return walk
      wallObservations: 240,
    }),
  });

  // Verify spacing along corridor stays within safe overlap range (<= 0.50m)
  const corridorSamples: QualitySample[] = [];
  for (let i = 0; i < 90; i++) {
    corridorSamples.push(makeQualitySample({ spacingMeters: 0.38 }));
  }
  for (let i = 0; i < 5; i++) {
    corridorSamples.push(makeQualitySample({ spacingMeters: 0.55, warnings: ["SPACING_TOO_FAR"] }));
  }

  const review = buildOperatorDepartureReview(
    [{ id: "vid-corridor", name: "corridor.mp4", state: "ACCEPTED", attempts: 1 }],
    corridorSamples,
    corridorStatus,
  );

  assert.equal(review.verdict, "READY_TO_LEAVE");
  assert.equal(review.coverage.observedCoveragePercent, 88.5);
  // Spacing warning alone does not reject frames, ensuring perspective line retention
  const summary = summarizeFrameSamples(corridorSamples, null);
  assert.equal(summary.rejectedFrameRate, 0.0);
  assert.equal(summary.counts.SPACING_TOO_FAR, 5);
});

// 3. Texture-Poor Room
test("environment 3: texture-poor room (plain painted drywall, subtle features)", () => {
  // Real frame assessment of a low-contrast wall with light switch
  const wallFrame = makeMockFrame(160, 120, 200, (x, y) => {
    // Plain wall with a switch plate at (60..80, 40..60)
    if (x >= 60 && x <= 80 && y >= 40 && y <= 60) return 50;
    return 200 + ((x + y) % 3);
  });

  const ctx: QualityContext = { prevGray: null, prevPosition: null };
  const { sample } = assessFrame(wallFrame, 1000, ctx, { x: 1.0, y: 1.0 });

  // Sharp edge of switch plate gives adequate blur score (> 40)
  assert.ok(sample.blurScore >= 40.0, `Blur score ${sample.blurScore} should indicate in-focus edge`);

  // Guidance surfaces actionable prompt when feature count is low without marking frame rejected
  const texturePoorStatus = makeStatus({
    qualityFlags: ["LOW_FEATURE_COUNT"],
    reshootRecommendations: [],
  });
  const g = guidance(texturePoorStatus, makeLocalState());
  const textureWarning = g.find((i) => i.code === "LOW_FEATURE_COUNT");
  assert.ok(textureWarning, "Must advise operator when encountering texture-poor surfaces");
  assert.match(textureWarning.title, /Too little texture/);
  assert.match(textureWarning.detail, /Plain walls/);

  // Operator review keeps sharp low-texture frames for SfM, avoiding false drops
  const tpSamples: QualitySample[] = [];
  for (let i = 0; i < 90; i++) {
    tpSamples.push(makeQualitySample({ capturedAtMs: 1000 + i * 500 }));
  }
  for (let i = 0; i < 8; i++) {
    tpSamples.push(makeQualitySample({ capturedAtMs: 46000 + i * 500, warnings: ["LOW_FEATURE_COUNT"] }));
  }
  const review = buildOperatorDepartureReview(
    [{ id: "vid-tp", name: "plain_room.mp4", state: "ACCEPTED", attempts: 1 }],
    tpSamples,
    texturePoorStatus,
  );
  assert.equal(review.verdict, "READY_TO_LEAVE");
  assert.equal(review.frameQuality.counts.LOW_FEATURE_COUNT, 8);
});

// 4. Room with Reflective Surfaces
test("environment 4: room with reflective surfaces (glass partitions, specular glare)", () => {
  // Saturated specular glare hotspot
  const glareFrame = makeMockFrame(160, 120, 100, (x, y) => {
    const distSq = (x - 80) * (x - 80) + (y - 60) * (y - 60);
    if (distSq < 200) return 255; // specular hotspot
    return 100;
  });

  const ctx: QualityContext = { prevGray: null, prevPosition: null };
  const { sample } = assessFrame(glareFrame, 2000, ctx, { x: 2.0, y: 1.0 });
  assert.ok(sample.highlightClipFraction > 0.01, "Specular reflection must be captured in highlight clipping");

  // Status with glare warnings
  const reflectiveStatus = makeStatus({
    qualityFlags: ["OVEREXPOSED"],
    reshootRecommendations: [
      { reason: "REVISIT_WAYPOINT 2 (OVEREXPOSED from window glare)", x: 2.0, y: 1.0 },
    ],
  });

  const review = buildOperatorDepartureReview(
    [{ id: "vid-glass", name: "glass_room.mp4", state: "ACCEPTED", attempts: 1 }],
    [makeQualitySample({ warnings: ["OVEREXPOSED"], highlightClipFraction: 0.06 })],
    reflectiveStatus,
  );

  // Surfaces targeted reshoot advice to capture oblique angles
  assert.equal(review.verdict, "RESHOOT_RECOMMENDED");
  assert.equal(review.reshoots.length, 1);
  assert.match(review.reshoots[0].action, /at \(2\.0, 1\.0\)/);
});

// 5. Room with Doorway Occlusions
test("environment 5: room with doorway occlusions (alcoves, adjoining vestibule)", () => {
  // Estimator reports low confidence bounds with unobserved frontier at the doorway opening
  const occludedStatus = makeStatus({
    sceneSource: "ESTIMATED",
    coveragePercent: 84.0,
    uncoveredAreaM2: 5.2,
    uncoveredZones: [
      { centroidX: 4.8, centroidY: 2.0, areaM2: 5.2, reason: "NOT_OBSERVED" },
    ],
    plannedPath: [
      { order: 1, x: 2.0, y: 1.5, yawDegrees: 0, type: "WALL", reason: "Main Room", expectedCoverageGainM2: 4, status: "VISITED" },
      { order: 2, x: 4.8, y: 2.0, yawDegrees: 90, type: "UNOBSERVED", reason: "Doorway Alcove", expectedCoverageGainM2: 5.2, status: "PENDING" },
    ],
    nextWaypoint: {
      order: 2,
      x: 4.8,
      y: 2.0,
      yawDegrees: 90,
      type: "UNOBSERVED",
      reason: "Inspect unobserved doorway vestibule.",
      walkMeters: 2.8,
      leg: [],
    },
    completion: "IN_PROGRESS",
  });

  const g = guidance(occludedStatus, makeLocalState());
  const waypointInstr = g.find((i) => i.code === "GO_TO_WAYPOINT");
  assert.ok(waypointInstr, "Planner must direct operator towards the occluded frontier");
  assert.match(waypointInstr.detail, /Walk 2\.8 m/);

  // Review prevents premature departure while pending doorway coverage remains
  const departureReview = buildOperatorDepartureReview(
    [{ id: "vid-occ", name: "occlusion.mp4", state: "ACCEPTED", attempts: 1 }],
    [makeQualitySample()],
    occludedStatus,
  );
  assert.equal(departureReview.verdict, "RESHOOT_RECOMMENDED");
  assert.equal(departureReview.coverage.pendingWaypoints, 1);
  assert.match(departureReview.detail, /1 unvisited waypoint/);
});

// 6. Unsupported Hardware & Unmeasured Coverage Honesty
test("honesty guarantee: unsupported device degrades gracefully and never fabricates coverage percentage", async () => {
  // Device without WebXR returns UNSUPPORTED
  const check = await detectCaptureAr(undefined);
  assert.equal(check.supported, false);
  assert.match(check.reason ?? "", /WebXR/);

  // Guidance offers manual outline or trajectory import instead of false tracking
  const unsupportedGuidance = guidance(null, makeLocalState({
    arSupport: "UNSUPPORTED",
    arUnsupportedReason: check.reason,
    arSessionActive: false,
  }));
  const unsupp = unsupportedGuidance.find((i) => i.code === "AR_UNSUPPORTED");
  assert.ok(unsupp);
  assert.match(unsupp.detail, /Mark positions on a measured plan/i);

  // When room outline is not set, coveragePercent is strictly null, never 0% or an invented number
  const unmeasuredStatus = makeStatus({
    coverageAvailable: false,
    coverageUnavailableReason: "room outline not set",
    coveragePercent: null,
    weightedCoveragePercent: null,
  });

  const review = buildOperatorDepartureReview(
    [{ id: "vid-raw", name: "raw.mp4", state: "ACCEPTED", attempts: 1 }],
    [makeQualitySample()],
    unmeasuredStatus,
  );
  assert.equal(review.coverage.observedCoveragePercent, null);
  assert.match(review.headline, /coverage was not measured/);
});
