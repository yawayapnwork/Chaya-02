import assert from "node:assert/strict";
import { test } from "node:test";
import { arRouteRequest, routeLegFor } from "./ar-navigation.ts";
import {
  STEP_FREE_MAX_SLOPE_DEG,
  STEP_FREE_MIN_CLEARANCE_M,
  transitionsStillUsable,
} from "./floor-transitions.ts";
import { type SegmentPoint, type StopRef, joinSegments, planStops } from "./multi-stop.ts";
import type { FloorConnection, RouteResponse, RouteWaypoint } from "./navigation-api.ts";
import {
  OBSTACLE_MAX_AGE_MS,
  PATH_BUFFER_METERS,
  freshObstacles,
  obstacleSourceStatus,
  rerouteDecision,
  touchesPath,
} from "./obstacle-reroute.ts";
import { CORRIDOR_HALF_WIDTH_METERS, corridorOccupancy } from "./obstacle-sensing.ts";
import { obstacleMessages } from "./obstacle-status.ts";
import { blockingObservations, initialTracker, updateTracks } from "./obstacle-tracker.ts";
import { classifyRouteError, describeRouteFailure, routeOverlay, routeSummary } from "./viewer-route.ts";

/**
 * Deterministic Navigation & Routing Safety Suite.
 *
 * Verifies that all navigation consumers enforce safe movement constraints, strictly distinguish
 * step-free routes from standard routes, reject uncalibrated/stale/non-finalized geometry, validate
 * endpoint snapping, and never fabricate success for unreachable or invalid routes.
 */

const F0 = "floor-0";
const F1 = "floor-1";
const FRAME_CALIBRATED = "frame-measured-v1";
const SCAN_FINAL = "scan-final-v1";

const mkWaypoint = (x: number, y: number, z: number, floorId = F0, kind: RouteWaypoint["kind"] = "WAYPOINT"): RouteWaypoint => ({
  x, y, z, floorId, kind,
});

const mkRoute = (opts: {
  waypoints?: RouteWaypoint[];
  profile?: "STANDARD" | "STEP_FREE";
  distanceMeters?: number;
  durationSeconds?: number;
  floorTransitions?: RouteResponse["floorTransitions"];
  constraints?: string[];
  sources?: RouteResponse["routingSources"];
}): RouteResponse => ({
  waypoints: opts.waypoints ?? [mkWaypoint(0, 0, 0, F0, "START"), mkWaypoint(5, 0, 0, F0, "DESTINATION")],
  distanceMeters: opts.distanceMeters ?? 5.0,
  estimatedDurationSeconds: opts.durationSeconds ?? (5.0 / 1.3),
  floorTransitions: opts.floorTransitions ?? [],
  accessibilityProfile: opts.profile ?? "STANDARD",
  accessibilityConstraintsApplied: opts.constraints ?? [],
  routingSources: opts.sources ?? [{
    floorId: F0,
    graphId: "graph-f0",
    source: "RECAST_NAVMESH",
    navmeshSha256: "sha-navmesh-0",
    recastnavigationVersion: "1.6.0",
    pathMethod: "STRING_PULLED",
    scanVersionId: SCAN_FINAL,
    coordinateFrameId: FRAME_CALIBRATED,
  }],
});

// 1. Narrow corridors and doorways
test("narrow corridors: standard allows 0.70m agent diameter; step-free requires >=0.90m measured clearance", () => {
  assert.equal(PATH_BUFFER_METERS * 2, 0.70, "standard walking agent diameter is 2 * 0.35m = 0.70m");
  assert.equal(STEP_FREE_MIN_CLEARANCE_M, 0.90, "step-free requires >= 0.90m measured clearance");
  assert.ok(CORRIDOR_HALF_WIDTH_METERS < PATH_BUFFER_METERS, "corridor occupancy half-width stays inside eroded agent clearance");

  const baseConnector: FloorConnection = {
    id: "conn-door-ramp",
    connectorType: "RAMP",
    fromFloorId: F0,
    fromPoiId: "p0",
    toFloorId: F1,
    toPoiId: "p1",
    bidirectional: true,
    lengthM: 6.0,
    maxSlopeDeg: 4.1,
    minClearanceM: 0.82, // fits 0.70m standard body, narrower than 0.90m step-free clearance
    status: "IN_SERVICE",
    accessibilityVerifiedAt: "2026-10-20T00:00:00Z",
    accessibilityVerifiedBy: "inspector",
  };

  const transition = [{
    fromFloorId: F0, toFloorId: F1, connectorType: "RAMP" as const, poiId: "p0", toPoiId: "p1",
    connectionId: "conn-door-ramp", distanceMeters: 6.0, durationSeconds: 6.0 / 1.3,
  }];

  // Standard profile accepts the 0.82m connector
  assert.equal(transitionsStillUsable(mkRoute({ profile: "STANDARD", floorTransitions: transition }), [baseConnector]).usable, true);

  // Step-free profile rejects 0.82m (< 0.90m) and rejects unmeasured (null) clearance
  const narrowStepFree = transitionsStillUsable(mkRoute({ profile: "STEP_FREE", floorTransitions: transition }), [baseConnector]);
  assert.equal(narrowStepFree.usable, false);
  assert.match(narrowStepFree.problems[0], /0\.90 m step-free clearance/);

  const unmeasuredStepFree = transitionsStillUsable(
    mkRoute({ profile: "STEP_FREE", floorTransitions: transition }),
    [{ ...baseConnector, minClearanceM: null }],
  );
  assert.equal(unmeasuredStepFree.usable, false);

  // Step-free accepts >= 0.90m measured clearance
  const wideStepFree = transitionsStillUsable(
    mkRoute({ profile: "STEP_FREE", floorTransitions: transition }),
    [{ ...baseConnector, minClearanceM: 0.95 }],
  );
  assert.equal(wideStepFree.usable, true);
});

// 2. Dead ends and disconnected regions
test("disconnected regions and dead ends: refused with NO_ROUTE rather than crossing walls", () => {
  const err = classifyRouteError(Object.assign(new Error("Destination is in a disconnected region"), { status: 404, code: "NO_ROUTE" }), "STANDARD");
  assert.equal(err.kind, "unreachable");
  assert.equal(err.code, "NO_ROUTE");
  assert.match(err.message, /walkable area does not connect them/);

  const disconnectedRoute: RouteResponse = {
    waypoints: [],
    distanceMeters: 0,
    estimatedDurationSeconds: 0,
    floorTransitions: [],
    accessibilityProfile: "STANDARD",
    accessibilityConstraintsApplied: [],
  };

  const overlay = routeOverlay(disconnectedRoute, { floorId: F0, frameId: FRAME_CALIBRATED, scanVersionId: SCAN_FINAL });
  assert.equal(overlay.waypoints, null, "no route waypoints drawn for disconnected regions");
  assert.deepEqual(overlay.runs, []);

  const summary = routeSummary(disconnectedRoute);
  assert.equal(summary.distanceMeters, null, "no distance reported for unreachable route");
  assert.equal(summary.durationSeconds, null, "no duration reported for unreachable route");
});

// 3. Slopes and stairs
test("slopes and stairs: stairs are strictly non-accessible; ramps steeper than 4.76 deg are excluded for step-free", () => {
  assert.equal(STEP_FREE_MAX_SLOPE_DEG, 4.76, "step-free max slope is 1:12 ADA limit (4.76 deg)");

  const stairsConn: FloorConnection = {
    id: "stairs-1",
    connectorType: "STAIRS",
    fromFloorId: F0,
    fromPoiId: "p0",
    toFloorId: F1,
    toPoiId: "p1",
    bidirectional: true,
    lengthM: 8.0,
    maxSlopeDeg: null,
    minClearanceM: 1.2,
    status: "IN_SERVICE",
    accessibilityVerifiedAt: "2026-10-20T00:00:00Z", // even if stamped, stairs are never step-free
    accessibilityVerifiedBy: "inspector",
  };

  const stairsRoute = mkRoute({
    profile: "STEP_FREE",
    floorTransitions: [{
      fromFloorId: F0, toFloorId: F1, connectorType: "STAIRS", poiId: "p0", toPoiId: "p1", connectionId: "stairs-1",
      distanceMeters: 8.0, durationSeconds: 16.0,
    }],
  });

  const checkStairs = transitionsStillUsable(stairsRoute, [stairsConn]);
  assert.equal(checkStairs.usable, false, "stairs are never usable for a step-free route");
  assert.match(checkStairs.problems[0], /no longer verified as accessible/);

  const rampTransition = [{
    fromFloorId: F0, toFloorId: F1, connectorType: "RAMP" as const, poiId: "p0", toPoiId: "p1", connectionId: "ramp-1",
    distanceMeters: 6.0, durationSeconds: 6.0 / 1.3,
  }];
  const gentleRamp: FloorConnection = {
    ...stairsConn, id: "ramp-1", connectorType: "RAMP", maxSlopeDeg: 4.1, minClearanceM: 1.1,
  };
  const steepRamp: FloorConnection = {
    ...gentleRamp, maxSlopeDeg: 5.8, // > 4.76 deg
  };

  assert.equal(transitionsStillUsable(mkRoute({ profile: "STEP_FREE", floorTransitions: rampTransition }), [gentleRamp]).usable, true);
  const checkSteep = transitionsStillUsable(mkRoute({ profile: "STEP_FREE", floorTransitions: rampTransition }), [steepRamp]);
  assert.equal(checkSteep.usable, false, "steep ramp > 4.76 deg is rejected for step-free");
  assert.match(checkSteep.problems[0], /4\.76° step-free slope limit/);
});

// 4. Step-free alternatives
test("step-free alternatives: distinguishes STANDARD vs STEP_FREE and refuses unverified or inaccessible routes", () => {
  const noStepFree = describeRouteFailure("NO_ACCESSIBLE_ROUTE", "only stairs connect the levels", "STEP_FREE");
  assert.equal(noStepFree.kind, "no-accessible-route");
  assert.match(noStepFree.message, /No step-free route exists/);

  const localization = {
    deviceToVenue: { x: 0, y: 0, z: 0, qx: 0, qy: 0, qz: 0, qw: 1 },
    anchorIds: ["a1"],
    residualMeters: 0.02,
    coordinateFrameId: FRAME_CALIBRATED,
    scanVersionId: SCAN_FINAL,
    floorId: F0,
    solvedAtMs: 1000,
  };
  const stdReq = arRouteRequest({ venueId: "v1", localization, start: [1, 1, 0], destination: { id: "poi-1", floorId: F0 }, stepFree: false });
  const sfReq = arRouteRequest({ venueId: "v1", localization, start: [1, 1, 0], destination: { id: "poi-1", floorId: F0 }, stepFree: true });
  assert.equal(stdReq.accessibility, "STANDARD");
  assert.equal(sfReq.accessibility, "STEP_FREE");

  const accessibleLift: FloorConnection = {
    id: "lift-1",
    connectorType: "ELEVATOR",
    fromFloorId: F0,
    fromPoiId: "lp0",
    toFloorId: F1,
    toPoiId: "lp1",
    bidirectional: true,
    lengthM: null,
    maxSlopeDeg: null,
    minClearanceM: 1.2,
    status: "IN_SERVICE",
    accessibilityVerifiedAt: "2026-10-20T00:00:00Z",
    accessibilityVerifiedBy: "inspector",
  };

  const unverifiedLift = { ...accessibleLift, accessibilityVerifiedAt: null };
  const routeWithLift = mkRoute({
    profile: "STEP_FREE",
    floorTransitions: [{
      fromFloorId: F0, toFloorId: F1, connectorType: "ELEVATOR", poiId: "lp0", toPoiId: "lp1", connectionId: "lift-1",
      distanceMeters: 0, durationSeconds: 45.0,
    }],
  });

  const checkUnverified = transitionsStillUsable(routeWithLift, [unverifiedLift]);
  assert.equal(checkUnverified.usable, false, "unverified elevator cannot be used for step-free route");
  assert.match(checkUnverified.problems[0], /no longer verified/);

  const checkVerified = transitionsStillUsable(routeWithLift, [accessibleLift]);
  assert.equal(checkVerified.usable, true, "verified elevator with measured >= 0.90m clearance is accepted");
});

// 5. Incorrect floor transitions
test("incorrect floor transitions: out of service, reversed one-way, or missing connectors are rejected", () => {
  const lift: FloorConnection = {
    id: "lift-1",
    connectorType: "ELEVATOR",
    fromFloorId: F0,
    fromPoiId: "p0",
    toFloorId: F1,
    toPoiId: "p1",
    bidirectional: false, // only F0 -> F1
    lengthM: null,
    maxSlopeDeg: null,
    minClearanceM: 1.1,
    status: "IN_SERVICE",
    accessibilityVerifiedAt: "2026-10-01T00:00:00Z",
    accessibilityVerifiedBy: "staff",
  };

  const reverseRoute = mkRoute({
    floorTransitions: [{
      fromFloorId: F1, toFloorId: F0, connectorType: "ELEVATOR", poiId: "p1", toPoiId: "p0", connectionId: "lift-1",
      distanceMeters: 0, durationSeconds: 45,
    }],
  });
  assert.equal(transitionsStillUsable(reverseRoute, [lift]).usable, false, "cannot travel in reverse direction on one-way connector");

  const outOfServiceLift = { ...lift, status: "OUT_OF_SERVICE" as const };
  assert.equal(transitionsStillUsable(mkRoute({
    floorTransitions: [{
      fromFloorId: F0, toFloorId: F1, connectorType: "ELEVATOR", poiId: "p0", toPoiId: "p1", connectionId: "lift-1",
      distanceMeters: 0, durationSeconds: 45,
    }],
  }), [outOfServiceLift]).usable, false, "out of service connector cannot be used");

  assert.equal(describeRouteFailure("FLOOR_CONNECTION_UNAVAILABLE", "none", "STEP_FREE").kind, "no-floor-connection");
});

// 6. Obstacles intersecting smoothed routes & honest perception reporting
test("obstacles intersecting smoothed routes: detects path intrusion, tracks confirmations, and never fakes live sensing", () => {
  // Without device depth, live perception is explicitly reported as UNAVAILABLE
  assert.equal(obstacleSourceStatus(null), "UNAVAILABLE");
  const noDepthMsgs = obstacleMessages({
    sensing: { kind: "UNAVAILABLE", reason: "no depth sensor" },
    onPath: [],
    movingNearby: 0,
    outcome: { kind: "NONE" },
  });
  assert.equal(noDepthMsgs[0].testId, "ar-obstacles-unavailable");
  assert.match(noDepthMsgs[0].text, /does not adapt to people or objects/);

  const path: [number, number, number][] = [[0, 0, 0], [10, 0, 0]];
  // Depth-sensed obstacle on the corridor centerline at x=3m ahead (camera at origin looking along +X: camera -Z is +X)
  // Using tracker + rerouteDecision loop:
  const rawObs = {
    floorId: F0,
    coordinateFrameId: FRAME_CALIBRATED,
    category: "UNCLASSIFIED" as const,
    observedAtMs: 10_000,
    center: [5.0, 0.0, 0.8] as [number, number, number],
    minX: 4.8,
    minY: -0.2,
    maxX: 5.2,
    maxY: 0.2,
    confidence: 0.9,
    uncertaintyMeters: 0.1,
    evidence: { depthSource: "ARCORE_DEPTH" as const, depthSamples: 20, distanceMeters: 5.0, residualMeasured: true, detector: null },
  };

  let tracker = initialTracker();
  // First sighting: tentative, does not trigger blocking reroute yet
  tracker = updateTracks(tracker, [rawObs], { nowMs: 10_000, floorId: F0, coordinateFrameId: FRAME_CALIBRATED, visibility: () => "OCCUPIED" });
  assert.equal(blockingObservations(tracker).length, 0, "single frame is tentative, not yet blocking");

  // Second sighting: confirmed stationary obstacle
  tracker = updateTracks(tracker, [{ ...rawObs, observedAtMs: 10_300 }], {
    nowMs: 10_300, floorId: F0, coordinateFrameId: FRAME_CALIBRATED, visibility: () => "OCCUPIED",
  });
  const confirmed = blockingObservations(tracker);
  assert.equal(confirmed.length, 1);
  assert.equal(touchesPath(path, confirmed[0]), true, "confirmed obstacle intersects the smoothed route corridor");

  const decision = rerouteDecision({
    floorId: F0,
    remainingPath: path,
    routedAround: [],
    observed: confirmed,
    nowMs: 10_300,
    lastRerouteMs: null,
  });
  assert.equal(decision.reroute, true, "intrusion triggers reroute request");
  assert.equal(decision.reason, "OBSTACLE_ON_PATH");

  // Corridor occupancy rejects when unlocalized
  const unloc = corridorOccupancy(
    { source: "ARCORE_DEPTH", frameTimeMs: 100, samples: [{ u: 0.5, v: 0.5, point: [0, 0, -2], confidence: 1 }] },
    { frameTimeMs: 100, wallClockMs: 100, camera: { x: 0, y: 0, z: 0, qx: 0, qy: 0, qz: 0, qw: 1 }, tracking: "NORMAL",
      deviceToVenue: null, floorId: F0, coordinateFrameId: FRAME_CALIBRATED, residualMeters: 0.02 },
    path,
  );
  assert.equal(unloc.obstacles.length, 0);
  assert.equal(unloc.rejected[0]?.ok === false && unloc.rejected[0].reason, "NOT_LOCALIZED");
});

// 7. Endpoint snapping limits
test("endpoint snapping limits: out-of-bounds and off-navmesh endpoints map to bad-endpoint failure states", () => {
  for (const code of ["START_OUT_OF_BOUNDS", "START_OFF_NAVMESH", "DESTINATION_OUT_OF_BOUNDS", "DESTINATION_OFF_NAVMESH"]) {
    const classified = classifyRouteError(Object.assign(new Error(code), { status: 422, code }), "STANDARD");
    assert.equal(classified.kind, "bad-endpoint", `${code} must classify as bad-endpoint`);
    assert.equal(classified.code, code);
    assert.equal(classified.retryable, false, "invalid endpoint is not a transient network error");
  }
  assert.match(describeRouteFailure("START_OFF_NAVMESH", "too far", "STANDARD").message, /starting point is not on the walkable area/);
  assert.match(describeRouteFailure("DESTINATION_OFF_NAVMESH", "too far", "STANDARD").message, /destination is not on the walkable area/);
});

// 8. Uncalibrated geometry
test("uncalibrated geometry: refuses routing and refuses to draw routes on uncalibrated or unplaced frames", () => {
  const route = mkRoute({});

  const uncalibratedScene = {
    floorId: F0,
    frameId: null, // NOT_CALIBRATED
    scanVersionId: null,
  };

  const overlay = routeOverlay(route, uncalibratedScene);
  assert.equal(overlay.waypoints, null, "route is refused when scene has no calibrated frame");
  assert.deepEqual(overlay.runs, [], "no runs drawn for uncalibrated scene");

  assert.equal(describeRouteFailure("METRIC_CALIBRATION_REQUIRED", "not metric", "STANDARD").kind, "not-routable");
  assert.equal(describeRouteFailure("METRIC_EVIDENCE_INSUFFICIENT", "hand-entered", "STANDARD").kind, "not-routable");
  assert.match(describeRouteFailure("METRIC_EVIDENCE_INSUFFICIENT", "hand-entered", "STANDARD").message, /hand-entered points/);
});

// 9. Stale navigation artifacts
test("stale navigation artifacts: version mismatch, non-finalized version, frame mismatch, and stale obstacles are rejected", () => {
  const route = mkRoute({
    sources: [{
      floorId: F0,
      graphId: "g1",
      source: "RECAST_NAVMESH",
      navmeshSha256: "sha-0",
      recastnavigationVersion: "1.6.0",
      pathMethod: "STRING_PULLED",
      scanVersionId: "sv-v1", // older scan version
      coordinateFrameId: FRAME_CALIBRATED,
    }],
  });

  const currentScene = {
    floorId: F0,
    frameId: FRAME_CALIBRATED,
    scanVersionId: "sv-v2", // floor published v2
  };

  const overlay = routeOverlay(route, currentScene);
  assert.equal(overlay.mismatch, "VERSION_MISMATCH", "detects stale scan version");
  assert.equal(overlay.waypoints, null, "refuses to render stale route on new version");

  // Also reject when viewer is on finalized sv-v2 but route source has null scanVersionId
  const unversionedRoute = mkRoute({
    sources: [{
      floorId: F0, graphId: "g1", source: "RECAST_NAVMESH", navmeshSha256: "sha-0",
      recastnavigationVersion: "1.6.0", pathMethod: "STRING_PULLED", scanVersionId: null,
      coordinateFrameId: FRAME_CALIBRATED,
    }],
  });
  assert.equal(routeOverlay(unversionedRoute, currentScene).mismatch, "VERSION_MISMATCH");

  // AR leg check also refuses stale scanVersionId or mismatched coordinateFrameId
  const arLeg = routeLegFor(route, {
    deviceToVenue: { x: 0, y: 0, z: 0, qx: 0, qy: 0, qz: 0, qw: 1 },
    anchorIds: ["a1"],
    residualMeters: 0.01,
    coordinateFrameId: FRAME_CALIBRATED,
    scanVersionId: "sv-v2",
    floorId: F0,
    solvedAtMs: 1000,
  });
  assert.equal(arLeg.ok, false);
  if (!arLeg.ok) assert.equal(arLeg.problem, "VERSION_MISMATCH");

  assert.equal(describeRouteFailure("VERSION_NOT_FINALIZED", "DRAFT", "STANDARD").kind, "not-routable");
  assert.equal(describeRouteFailure("VERSION_WRONG_FLOOR", "wrong floor", "STANDARD").kind, "not-routable");
  assert.equal(describeRouteFailure("NAVMESH_NOT_READY", "no navmesh", "STANDARD").kind, "not-routable");

  // Stale obstacle observations (> 120s old)
  const staleObs = {
    floorId: F0,
    minX: 1, minY: 1, maxX: 2, maxY: 2,
    observedAtMs: 1_000,
    coordinateFrameId: FRAME_CALIBRATED,
  };
  const nowMs = 1_000 + OBSTACLE_MAX_AGE_MS + 10_000;
  assert.deepEqual(freshObstacles([staleObs], nowMs), [], "stale obstacle older than 120s is dropped");
});

// 10. No valid route
test("no valid route: surfaces actionable failure without papering over with false straight lines", async () => {
  const failedRoute: RouteResponse = {
    waypoints: [],
    distanceMeters: 0,
    estimatedDurationSeconds: 0,
    floorTransitions: [],
    accessibilityProfile: "STANDARD",
    accessibilityConstraintsApplied: [],
  };

  assert.equal(routeSummary(failedRoute).distanceMeters, null);
  assert.equal(routeSummary(failedRoute).durationSeconds, null);
  assert.equal(routeOverlay(failedRoute, { floorId: F0, frameId: FRAME_CALIBRATED, scanVersionId: SCAN_FINAL }).waypoints, null);

  // When all stops fail in planStops, route is null and stops are UNREACHABLE
  const plan = await planStops({
    scope: "s",
    profile: "STEP_FREE",
    start: { key: "START", floorId: F0, position: [0, 0, 0] },
    stops: [{ id: "stop-1", floorId: F0, position: [5, 0, 0] }],
    cache: new Map(),
    fetchSegment: async () => ({ ok: false, code: "NO_ACCESSIBLE_ROUTE", message: "Steps block path", retryable: false }),
  });
  assert.equal(plan.route, null);
  assert.equal(plan.stops[0].status, "UNREACHABLE");
  assert.equal(plan.stops[0].code, "NO_ACCESSIBLE_ROUTE");
});

// 11. Multi-destination waypoint chaining
test("multi-destination waypoint chaining: chains segments, skips and reports unreachable stops, preserves order", async () => {
  const startPt: SegmentPoint = { key: "START", floorId: F0, position: [0, 0, 0] };
  const stopA: StopRef = { id: "stop-a", floorId: F0, position: [3, 0, 0] };
  const stopUnreachable: StopRef = { id: "stop-isolated", floorId: F0, position: [99, 99, 0] };
  const stopB: StopRef = { id: "stop-b", floorId: F0, position: [7, 0, 0] };

  const fetchSegment = async (from: SegmentPoint, to: StopRef) => {
    if (to.id === "stop-isolated") {
      return { ok: false as const, code: "NO_ROUTE", message: "Isolated room", retryable: false };
    }
    const d = Math.abs(to.position[0] - from.position[0]);
    const route: RouteResponse = {
      waypoints: [
        mkWaypoint(from.position[0], 0, 0, F0, "START"),
        mkWaypoint(to.position[0], 0, 0, F0, "DESTINATION"),
      ],
      distanceMeters: d,
      estimatedDurationSeconds: d / 1.3,
      floorTransitions: [],
      accessibilityProfile: "STANDARD",
      accessibilityConstraintsApplied: [],
      routingSources: [{
        floorId: F0, graphId: "g1", source: "RECAST_NAVMESH", navmeshSha256: null,
        recastnavigationVersion: "1.6.0", pathMethod: "STRING_PULLED", scanVersionId: SCAN_FINAL,
        coordinateFrameId: FRAME_CALIBRATED,
      }],
    };
    return { ok: true as const, route };
  };

  const plan = await planStops({
    scope: "scope",
    profile: "STANDARD",
    start: startPt,
    stops: [stopA, stopUnreachable, stopB],
    cache: new Map(),
    fetchSegment,
  });

  assert.deepEqual(plan.stops.map((s) => `${s.id}:${s.status}`), [
    "stop-a:REACHED",
    "stop-isolated:UNREACHABLE",
    "stop-b:REACHED",
  ]);
  assert.equal(plan.route!.distanceMeters, 7.0);
  assert.deepEqual(plan.route!.waypoints.map((w) => `${w.x}:${w.kind}`), [
    "0:START", "3:STOP", "7:DESTINATION",
  ]);

  const joined = joinSegments(plan.segments.map((s) => s.route));
  assert.equal(joined.distanceMeters, 7.0);
});
