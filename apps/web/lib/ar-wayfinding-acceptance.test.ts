import assert from "node:assert/strict";
import { test } from "node:test";
import { type Pose, compose, invert, solve } from "./ar-anchor-math.ts";
import { classifyArFailure } from "./ar-errors.ts";
import { type TrackedMarker, observeImages, relocalizationEligibility } from "./ar-marker-tracking.ts";
import {
  arRouteRequest,
  driftExceeded,
  floorHandoff,
  markerDrift,
  routeLegFor,
} from "./ar-navigation.ts";
import {
  type ArEvent,
  type ArSessionState,
  type Localization,
  canAdvanceRoute,
  initialArSessionState,
  reduceArSession,
  routeShown,
} from "./ar-relocalization.ts";
import {
  type RouteProgress,
  advanceProgress,
  devicePositionInVenue,
} from "./ar-route.ts";
import {
  clearPublicViewerSession,
  currentAuthHeaders,
  publicViewerVenueId,
  setPublicViewerSession,
} from "./session.ts";
import { arHref } from "./viewer-url.ts";
import type {
  RouteResponse,
  RouteWaypoint,
} from "./navigation-api.ts";
import {
  locateOnRoute,
  nextInstruction,
  routeInstructions,
  updateArrival,
  initialArrival,
} from "./route-instructions.ts";

const S = Math.SQRT1_2;
// Standard camera axes conversion: device +Y up -> canonical venue +Z up
const AXES: Pose = { x: 0, y: 0, z: 0, qx: S, qy: 0, qz: 0, qw: S };
const yaw = (deg: number): Pose => ({
  x: 0,
  y: 0,
  z: 0,
  qx: 0,
  qy: 0,
  qz: Math.sin((deg * Math.PI) / 360),
  qw: Math.cos((deg * Math.PI) / 360),
});
const at = (x: number, y: number, z: number): Pose => ({ x, y, z, qx: 0, qy: 0, qz: 0, qw: 1 });

// Ground truth for two distinct floor coordinate frames
const TRUE_F1 = compose(at(5, -3, 0), compose(yaw(15), AXES));
const TRUE_F2 = compose(at(-10, 4, 0), compose(yaw(-45), AXES));

const WALL_S: Pose = { x: 0, y: 0, z: 1.5, qx: 1, qy: 0, qz: 0, qw: 0 };
const marker = (x: number, y: number): Pose => ({ ...WALL_S, x, y });

const F1_MARKERS = {
  m1: marker(2, 0),
  m2: marker(8, 0),
  m3: marker(5, 8),
};

const F2_MARKERS = {
  n1: marker(-5, 2),
  n2: marker(0, 2),
  n3: marker(-2, 7),
};

const TRACKED: TrackedMarker[] = [
  ...Object.keys(F1_MARKERS).map((id, i) => ({
    index: i,
    anchorId: id,
    floorId: "floor-1",
    markerIdentifier: id,
    widthInMeters: 0.3,
  })),
  ...Object.keys(F2_MARKERS).map((id, i) => ({
    index: 3 + i,
    anchorId: id,
    floorId: "floor-2",
    markerIdentifier: id,
    widthInMeters: 0.3,
  })),
];

const DIGITAL: Record<string, Pose> = { ...F1_MARKERS, ...F2_MARKERS };

function observe(truth: Pose, ids: string[], moved: Record<string, Pose> = {}) {
  const results = ids.map((id) => ({
    index: TRACKED.find((m) => m.anchorId === id)!.index,
    imageSpace: id,
    trackingState: "tracked" as const,
    measuredWidthInMeters: 0.3,
  }));
  return observeImages(
    results,
    TRACKED,
    (space) => compose(invert(truth), moved[space as string] ?? DIGITAL[space as string]),
    0
  ).filter((o) => relocalizationEligibility(o).eligible);
}

function relocalize(
  observations: ReturnType<typeof observe>,
  floorId: string,
  scanVersionId: string,
  frameId: string,
  nowMs: number
): Localization {
  const s = solve(
    observations.map((o) => ({ digitalPose: DIGITAL[o.anchorId], observedPose: o.observedPose })),
    0.3,
    8.0
  );
  if (!s.consistent) {
    throw Object.assign(new Error("the observed anchors disagree"), {
      status: 409,
      code: "RELOCALIZATION_INCONSISTENT",
    });
  }
  const used = s.residuals.filter((r) => r.used);
  return {
    deviceToVenue: s.transform,
    anchorIds: used.map((r) => observations[r.index].anchorId),
    residualMeters: used.length === 1 ? null : s.maxResidualMeters,
    coordinateFrameId: frameId,
    scanVersionId,
    floorId,
    solvedAtMs: nowMs,
  };
}

function viewerAt(truth: Pose, x: number, y: number, z = 1.4): Pose {
  return compose(invert(truth), at(x, y, z));
}

const WAYPOINTS_F1: RouteWaypoint[] = [
  { x: 2, y: 1, z: 0, floorId: "floor-1", kind: "START" },
  { x: 5, y: 1, z: 0, floorId: "floor-1", kind: "WAYPOINT" },
  { x: 5, y: 6, z: 0, floorId: "floor-1", kind: "WAYPOINT" },
  { x: 7, y: 6, z: 0, floorId: "floor-1", kind: "TRANSITION" },
];

const WAYPOINTS_F2: RouteWaypoint[] = [
  { x: -5, y: 3, z: 0, floorId: "floor-2", kind: "TRANSITION" },
  { x: -2, y: 3, z: 0, floorId: "floor-2", kind: "WAYPOINT" },
  { x: -2, y: 6, z: 0, floorId: "floor-2", kind: "DESTINATION" },
];

const ROUTE: RouteResponse = {
  waypoints: [...WAYPOINTS_F1, ...WAYPOINTS_F2],
  distanceMeters: 17.0,
  estimatedDurationSeconds: 75,
  accessibilityProfile: "STEP_FREE",
  accessibilityConstraintsApplied: [
    "uses only edges of the STEP_FREE graph baked from the navmesh",
    "measured slope <= 4.76 deg (1:12 slope)",
    "measured clear width >= 0.90 m",
  ],
  floorTransitions: [
    {
      fromFloorId: "floor-1",
      toFloorId: "floor-2",
      connectorType: "ELEVATOR",
      poiId: "poi-elev-1",
      connectionId: "conn-elev",
      toPoiId: "poi-elev-2",
      distanceMeters: 0,
      durationSeconds: 30,
    },
  ],
  routingSources: [
    {
      floorId: "floor-1",
      graphId: "g1",
      source: "RECAST_NAVMESH",
      navmeshSha256: "abc123sha",
      recastnavigationVersion: "v1.6.0",
      pathMethod: "detour-funnel",
      scanVersionId: "sv-f1-01",
      coordinateFrameId: "frame-f1",
    },
    {
      floorId: "floor-2",
      graphId: "g2",
      source: "RECAST_NAVMESH",
      navmeshSha256: "def456sha",
      recastnavigationVersion: "v1.6.0",
      pathMethod: "detour-funnel",
      scanVersionId: "sv-f2-01",
      coordinateFrameId: "frame-f2",
    },
  ],
};

test("AR Wayfinding: Waypoint progression, corridor tracking and monotone distance decrease", () => {
  let session = reduceArSession(initialArSessionState(), { type: "SESSION_STARTED", floorId: "floor-1" });
  session = reduceArSession(session, { type: "TRACKING_RESTORED" });

  const obs = observe(TRUE_F1, ["m1", "m2"]);
  for (let i = 0; i < 4 && session.state === "SEARCHING"; i++) {
    session = reduceArSession(session, { type: "MARKERS_OBSERVED", observations: obs });
  }
  assert.equal(session.state, "RELOCALIZING");

  const loc = relocalize(session.pending!.observations, "floor-1", "sv-f1-01", "frame-f1", 1000);
  session = reduceArSession(session, {
    type: "RELOCALIZATION_SUCCEEDED",
    requestId: session.pending!.requestId,
    localization: loc,
  });
  assert.equal(session.state, "LOCALIZED");

  const leg = routeLegFor(ROUTE, session.localization!);
  assert.ok(leg.ok);
  assert.equal(leg.points.length, 4);

  const corridorSteps: [number, number][] = [
    [2.0, 1.0], // Start
    [3.5, 1.0], // Mid segment 1
    [5.0, 1.0], // Turn waypoint
    [5.0, 3.5], // Mid segment 2
    [5.0, 6.0], // Turn waypoint
    [6.5, 6.0], // Near elevator transition
  ];

  let progress: RouteProgress | null = null;
  let lastRemaining = Infinity;

  corridorSteps.forEach(([x, y], stepIndex) => {
    const camPose = viewerAt(TRUE_F1, x, y);
    const venuePos = devicePositionInVenue(camPose, session.localization!.deviceToVenue);
    progress = advanceProgress(progress, canAdvanceRoute(session), leg.ok ? leg.points : [], venuePos);

    assert.ok(progress !== null);
    assert.ok(progress.offRouteMeters < 0.01, `Step ${stepIndex} stays within corridor tolerance`);
    assert.ok(
      progress.remainingMeters <= lastRemaining + 1e-6,
      `Step ${stepIndex} monotonically decreases remaining distance`
    );
    lastRemaining = progress.remainingMeters;
  });

  // Verify instruction generation along the route
  const instructions = routeInstructions(ROUTE, {
    floorName: (id) => (id === "floor-2" ? "Upper Floor" : "Ground Floor"),
    stopName: () => "Target Room",
  });
  assert.ok(instructions.length >= 3);
  const firstTurn = instructions.find((i) => i.kind === "TURN_LEFT");
  assert.ok(firstTurn !== undefined, "Includes left turn instruction at (5, 1)");
});

test("AR Wayfinding: Insufficient tracking freezes overlay and requires validated recovery", () => {
  let session = reduceArSession(initialArSessionState(), { type: "SESSION_STARTED", floorId: "floor-1" });
  session = reduceArSession(session, { type: "TRACKING_RESTORED" });

  const obs = observe(TRUE_F1, ["m1", "m2"]);
  for (let i = 0; i < 4 && session.state === "SEARCHING"; i++) {
    session = reduceArSession(session, { type: "MARKERS_OBSERVED", observations: obs });
  }
  const loc = relocalize(session.pending!.observations, "floor-1", "sv-f1-01", "frame-f1", 1000);
  session = reduceArSession(session, {
    type: "RELOCALIZATION_SUCCEEDED",
    requestId: session.pending!.requestId,
    localization: loc,
  });
  assert.equal(session.state, "LOCALIZED");

  // Limited tracking (e.g. fast shake or partial occlusion)
  session = reduceArSession(session, {
    type: "TRACKING_LIMITED",
    reason: "excessive motion",
    atMs: 2000,
  });
  assert.equal(session.state, "LIMITED");
  assert.equal(canAdvanceRoute(session), false, "Route progression must freeze while tracking is limited");
  assert.equal(routeShown(session), true, "Last valid route overlay remains displayed during short limited spell");

  // Validated recovery within tolerance (jump <= 0.30 m)
  session = reduceArSession(session, {
    type: "TRACKING_RESTORED",
    anchorJumpMeters: 0.12,
  });
  assert.equal(session.state, "LOCALIZED");
  assert.equal(canAdvanceRoute(session), true, "Route progression resumes after valid recovery");

  // Severe tracking loss (e.g. camera covered or AR tracking lost)
  session = reduceArSession(session, { type: "TRACKING_LOST" });
  assert.equal(session.state, "TRACKING_LOST");
  assert.equal(canAdvanceRoute(session), false);
  assert.equal(routeShown(session), false, "Route overlay must be hidden on tracking loss");
});

test("AR Wayfinding: Multi-marker consistency, outlier rejection and drift gating", () => {
  // Scenario 1: Two markers disagree because one was moved 0.80 m
  const movedM2 = { m2: { ...F1_MARKERS.m2, x: F1_MARKERS.m2.x + 0.8 } };
  const obsInconsistent = observe(TRUE_F1, ["m1", "m2"], movedM2);

  assert.throws(
    () => relocalize(obsInconsistent, "floor-1", "sv-f1-01", "frame-f1", 1000),
    (err: any) => err.code === "RELOCALIZATION_INCONSISTENT",
    "Refuses to average two disagreeing markers into a fake pose"
  );

  // Scenario 2: Three markers observed with one moved -> outlier isolation
  const obsThree = observe(TRUE_F1, ["m1", "m2", "m3"], movedM2);
  const locSolved = relocalize(obsThree, "floor-1", "sv-f1-01", "frame-f1", 1000);
  assert.deepEqual(
    locSolved.anchorIds.sort(),
    ["m1", "m3"].sort(),
    "Outlier rejection sets aside moved marker m2 and solves from m1 and m3"
  );
  assert.ok(locSolved.residualMeters! < 1e-6);

  // Scenario 3: Drift detection when re-observing moved marker while localized
  const [obsMovedM2] = observe(TRUE_F1, ["m2"], movedM2);
  const drift = markerDrift(locSolved.deviceToVenue, obsMovedM2.observedPose, DIGITAL.m2);
  assert.ok(Math.abs(drift.translationMeters - 0.8) < 1e-4);
  assert.ok(driftExceeded(drift), "Drift of 0.80 m exceeds 0.30 m tolerance threshold");
});

test("AR Wayfinding: Public viewer token session and cross-floor handoff", () => {
  clearPublicViewerSession();
  const futureExpiry = new Date(Date.now() + 3600_000).toISOString();
  setPublicViewerSession("public-viewer-jwt-secret", futureExpiry, "venue-uuid-123");

  assert.equal(publicViewerVenueId(), "venue-uuid-123");
  const link = arHref("venue-uuid-123", "floor-1", "public-viewer-jwt-secret");
  assert.equal(link, "/ar?link=public-viewer-jwt-secret&floor=floor-1");

  // Simulate floor-1 route completion near elevator
  const handoffDecision = floorHandoff(ROUTE, "floor-1", 0.8);
  assert.deepEqual(handoffDecision, {
    toFloorId: "floor-2",
    connectorType: "ELEVATOR",
  });

  // Handoff drops floor-1 localization transform
  let session = reduceArSession(initialArSessionState(), { type: "SESSION_STARTED", floorId: "floor-1" });
  session = reduceArSession(session, { type: "TRACKING_RESTORED" });
  const f1Obs = observe(TRUE_F1, ["m1", "m2"]);
  for (let i = 0; i < 4 && session.state === "SEARCHING"; i++) {
    session = reduceArSession(session, { type: "MARKERS_OBSERVED", observations: f1Obs });
  }
  const f1Loc = relocalize(session.pending!.observations, "floor-1", "sv-f1-01", "frame-f1", 1000);
  session = reduceArSession(session, {
    type: "RELOCALIZATION_SUCCEEDED",
    requestId: session.pending!.requestId,
    localization: f1Loc,
  });
  assert.equal(session.state, "LOCALIZED");

  session = reduceArSession(session, { type: "FLOOR_HANDOFF", toFloorId: handoffDecision!.toFloorId });
  assert.equal(session.state, "FLOOR_TRANSITION");
  assert.equal(session.localization, null, "Canonical transform is dropped during floor transition");

  // Re-localization on floor-2 in floor-2's canonical coordinate frame
  session = reduceArSession(session, { type: "TRACKING_RESTORED" });
  const f2Obs = observe(TRUE_F2, ["n1", "n2"]);
  for (let i = 0; i < 4 && session.state === "FLOOR_TRANSITION"; i++) {
    session = reduceArSession(session, { type: "MARKERS_OBSERVED", observations: f2Obs });
  }
  const f2Loc = relocalize(session.pending!.observations, "floor-2", "sv-f2-01", "frame-f2", 5000);
  session = reduceArSession(session, {
    type: "RELOCALIZATION_SUCCEEDED",
    requestId: session.pending!.requestId,
    localization: f2Loc,
  });
  assert.equal(session.state, "LOCALIZED");
  assert.equal(session.localization!.floorId, "floor-2");

  const f2Leg = routeLegFor(ROUTE, session.localization!);
  assert.ok(f2Leg.ok);
  assert.equal(f2Leg.points.length, 3);

  clearPublicViewerSession();
});
