import assert from "node:assert/strict";
import { test } from "node:test";
import { type LocalState, compass, directionsTo, guidance, relativeDirection } from "./capture-guidance.ts";
import type { HudStatus, ReconAssessment } from "./hud-types.ts";

const local = (over: Partial<LocalState> = {}): LocalState => ({
  mode: "AR", arSupport: "SUPPORTED", arSessionActive: true, cameraState: "LIVE", motionWarnings: [], headingCalibrated: false,
  nowMs: 1_000_000, ...over,
});

const recon = (over: Partial<ReconAssessment> = {}): ReconAssessment => ({
  verdict: "READY", positionBasis: "TRACKED", metric: true, samples: 40, durationSeconds: 12, pathLengthMeters: 9, segments: 1,
  headingShare: 1, trackingLosses: 0, lostSeconds: 0, longestGapSeconds: 0, frameId: "xr-1", excludedOtherFrame: 0, wallObservations: 120,
  boundsEstimable: true, issues: [], requirements: { minDurationSeconds: 8, minSamples: 10, minManualMarks: 3, minPathMeters: 3, minWallObservations: 12 },
  ...over,
});

const status = (over: Partial<HudStatus> = {}): HudStatus => ({
  trackingAvailable: true, trackingUnavailableReason: null,
  currentPosition: { x: 2, y: 2, headingDegrees: 90, asOfMs: 999_900, kind: "TRACKED", source: "webxr" },
  pathSoFar: [], sceneSet: true, coverageAvailable: true, coverageUnavailableReason: null, coveragePercent: 40, weightedCoveragePercent: 45,
  uncoveredAreaM2: 20, uncoveredZones: [], plannedPath: [], quality: null, warnings: [], reshootRecommendations: [], computedAt: "",
  trackingState: "TRACKING", sceneSource: "ESTIMATED", reconnaissance: recon(), coverageBasis: "LIVE_TRACKED", coverageLive: true,
  plannedPathKind: "PERSISTED_PLAN", completion: "IN_PROGRESS", qualityFlags: [],
  nextWaypoint: { order: 2, x: 2, y: 5, yawDegrees: 0, type: "CORNER", reason: "Corner coverage.", walkMeters: 3, leg: [] },
  ...over,
});

const codes = (s: ReturnType<typeof guidance>) => s.map((i) => i.code);

test("relativeDirection / compass / directionsTo: plain-language directions from real geometry", () => {
  assert.equal(relativeDirection(5), "straight ahead");
  assert.equal(relativeDirection(45), "ahead to your left");
  assert.equal(relativeDirection(-90), "to your right");
  assert.equal(relativeDirection(170), "behind you");
  assert.equal(compass(90), "north (up)");
  assert.equal(compass(-45), "south-east");
  const d = directionsTo({ x: 0, y: 0, headingDegrees: 90 }, { x: 0, y: 3 }, null);
  assert.equal(d.turnDegrees, 0);
  assert.match(d.text, /^Walk 3\.0 m straight ahead\.$/);
  assert.match(directionsTo({ x: 0, y: 0, headingDegrees: null }, { x: 1, y: 1 }, null).text, /towards \(1\.0, 1\.0\) on the map/);
  assert.match(directionsTo({ x: 0, y: 0, headingDegrees: 0 }, { x: 2, y: 0 }, 4.5).text, /goes around an obstacle/);
});

test("the next waypoint is the main instruction while tracking, with distance and turn", () => {
  const g = guidance(status(), local());
  assert.equal(g[0].code, "GO_TO_WAYPOINT");
  assert.match(g[0].detail, /Walk 3\.0 m straight ahead/);
  assert.match(g[0].detail, /face east/);
});

test("at the waypoint: turn to its heading and hold", () => {
  const g = guidance(status({ currentPosition: { x: 2, y: 4.8, headingDegrees: 60, asOfMs: 0, kind: "TRACKED" } }), local());
  const at = g.find((i) => i.code === "AT_WAYPOINT")!;
  assert.match(at.detail, /Turn 60° right and hold still/);
});

test("lost tracking blocks guidance: no directions are given from a position that is not live", () => {
  const g = guidance(status({ trackingState: "LOST", trackingAvailable: false, qualityFlags: ["LOW_FEATURE_COUNT"] }), local());
  assert.equal(g[0].code, "TRACKING_LOST");
  assert.match(g[0].detail, /too little texture/);
  assert.match(g[0].detail, /near \(2\.0, 2\.0\)/);
  assert.ok(!codes(g).includes("GO_TO_WAYPOINT"));
  assert.ok(codes(g).includes("WAYPOINT_WAITING"));
});

test("a restarted AR session must be re-aligned before positions count", () => {
  const g = guidance(status({ trackingState: "FRAME_MISMATCH", currentPosition: null, excludedOtherFramePositions: 7 }), local());
  assert.equal(g[0].code, "REALIGN");
  assert.match(g[0].detail, /7 position/);
});

test("camera permission denial and unsupported devices are explained with a way forward", () => {
  const denied = guidance(status(), local({ cameraState: "DENIED" }));
  assert.equal(denied[0].code, "CAMERA_DENIED");
  const unsupported = guidance(null, local({ arSupport: "UNSUPPORTED", arUnsupportedReason: "No WebXR." }));
  assert.equal(unsupported[0].code, "AR_UNSUPPORTED");
  assert.match(unsupported[0].detail, /Mark positions on a measured plan/);
  assert.ok(codes(unsupported).includes("CONNECTING"));
});

test("before bounds exist: what the reconnaissance walk still needs, then estimating", () => {
  const incomplete = guidance(status({
    sceneSet: false, plannedPathKind: "NONE", nextWaypoint: null,
    reconnaissance: recon({ verdict: "INCOMPLETE", issues: [{ code: "WALK_TOO_SHORT", severity: "BLOCKING", message: "the walk lasted 4.0 s" }] }),
  }), local());
  assert.ok(codes(incomplete).includes("RECON_WALK_TOO_SHORT"));
  const ready = guidance(status({ sceneSet: false, plannedPathKind: "NONE", nextWaypoint: null }), local());
  assert.ok(codes(ready).includes("ESTIMATE_BOUNDS"));
  const videoOnly = guidance(status({
    sceneSet: false, plannedPathKind: "NONE", nextWaypoint: null, trackingState: "NOT_STARTED", currentPosition: null,
    reconnaissance: recon({ verdict: "NOT_STARTED", issues: [{ code: "VIDEO_ONLY", severity: "BLOCKING", message: "no metric scale" }] }),
  }), local());
  assert.equal(videoOnly.find((i) => i.code === "RECON_VIDEO_ONLY")?.title, "Video cannot position the walk");
});

test("low-confidence bounds point at the unobserved edge; no plan yet asks for one", () => {
  const g = guidance(status({
    plannedPathKind: "LIVE_SUGGESTION", nextWaypoint: null, completion: "NO_PLAN",
    bounds: {
      status: "LOW_CONFIDENCE", basis: "SENSOR_EVIDENCE", frameId: "xr-1", area: [], obstacles: [], unobservedInterior: [],
      frontier: [{ polyline: [], lengthMeters: 6, midX: 4, midY: 5.5, outwardDegrees: 90 }], confidence: 0.3,
      confidenceParts: { boundaryObserved: 0.6, interiorObserved: 1, density: 1 }, areaM2: 20, observedBoundaryMeters: 14, frontierMeters: 6,
      assumptions: [], issues: [],
    },
  }), local());
  assert.ok(codes(g).includes("BOUNDS_UNOBSERVED"));
  assert.match(g.find((i) => i.code === "BOUNDS_UNOBSERVED")!.detail, /near \(4\.0, 5\.5\)/);
  assert.ok(codes(g).includes("MAKE_PLAN"));
});

test("completion, unreachable waypoints and sustained quality problems are reported", () => {
  const done = guidance(status({ completion: "COVERAGE_TARGET_REACHED", coveragePercent: 96, nextWaypoint: null }), local());
  assert.ok(codes(done).includes("COVERAGE_REACHED"));
  const notLive = guidance(status({ completion: "COVERAGE_TARGET_REACHED", coveragePercent: 96, coverageLive: false, nextWaypoint: null }), local());
  assert.match(notLive.find((i) => i.code === "COVERAGE_REACHED")!.detail, /not live/);
  const unreachable = guidance(status({ plannedPath: [{ order: 1, x: 0, y: 0, yawDegrees: 0, type: "CORNER", reason: "", expectedCoverageGainM2: 1, status: "UNREACHABLE" }] }), local());
  assert.ok(codes(unreachable).includes("UNREACHABLE_WAYPOINTS"));
  const dark = guidance(status({ qualityFlags: ["UNDEREXPOSED", "BLUR"] }), local({ motionWarnings: ["TURNING_TOO_FAST"] }));
  assert.deepEqual(codes(dark).slice(0, 3), ["TURNING_TOO_FAST", "UNDEREXPOSED", "BLUR"]);
});

test("manual marks without a heading calibration say so instead of inventing a facing direction", () => {
  const g = guidance(status({ trackingState: "MANUAL", currentPosition: { x: 2, y: 2, headingDegrees: null, asOfMs: 0, kind: "MANUAL" } }),
    local({ mode: "MANUAL" }));
  assert.ok(codes(g).includes("HEADING_UNCALIBRATED"));
  assert.match(g.find((i) => i.code === "GO_TO_WAYPOINT")!.detail, /towards \(2\.0, 5\.0\) on the map/);
});

test("actionable guidance for upload failure, quarantine, retry backpressure, missing overlap and incomplete coverage", () => {
  const uploadProblem = guidance(status({
    qualityFlags: ["LOW_FEATURE_COUNT", "SPACING_TOO_FAR"],
    reconnaissance: recon({ segments: 3, trackingLosses: 2, lostSeconds: 4.5 }),
    nextWaypoint: null,
    uncoveredZones: [{ centroidX: 6.2, centroidY: 1.8, areaM2: 8.4, reason: "NOT_OBSERVED" }],
  }), local({
    uploadFailedCount: 1,
    uploadQuarantinedCount: 1,
    uploadRetryingCount: 2,
    uploadLastError: "Connection reset during part 3.",
  }));

  assert.equal(uploadProblem[0].code, "UPLOAD_FAILURE");
  assert.equal(uploadProblem[0].severity, "BLOCKING");
  assert.match(uploadProblem[0].detail, /Connection reset during part 3/);
  assert.ok(codes(uploadProblem).includes("UPLOAD_QUARANTINED"));
  assert.ok(codes(uploadProblem).includes("UPLOAD_RETRYING"));
  assert.ok(codes(uploadProblem).includes("LOW_FEATURE_COUNT"));
  assert.ok(codes(uploadProblem).includes("SPACING_TOO_FAR"));
  assert.ok(codes(uploadProblem).includes("MISSING_OVERLAP_GAPS"));
  assert.ok(codes(uploadProblem).includes("INCOMPLETE_ROOM_COVERAGE"));
  assert.match(uploadProblem.find((i) => i.code === "INCOMPLETE_ROOM_COVERAGE")!.detail, /near \(6\.2, 1\.8\)/);
});
