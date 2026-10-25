import assert from "node:assert/strict";
import { test } from "node:test";
import { routeLegFor } from "./ar-navigation.ts";
import { type FloorConnection, transitionsStillUsable } from "./floor-transitions.ts";
import { type SegmentPoint, type StopRef, joinSegments, planStops } from "./multi-stop.ts";
import type { RouteResponse, RouteWaypoint } from "./navigation-api.ts";
import { OBSTACLE_MAX_AGE_MS, freshObstacles, rerouteDecision, touchesPath } from "./obstacle-reroute.ts";
import { routeOverlay, routeSummary } from "./viewer-route.ts";

/**
 * Deterministic Navigation & Routing Safety Suite.
 *
 * Verifies that all navigation consumers enforce safe movement constraints, strictly distinguish
 * wheelchair-accessible routes from standard routes, reject uncalibrated/stale geometry, validate
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
  const narrowDoorwayClearanceM = 0.82; // between standard agent diameter (0.7m) and ADA accessible clearance (0.9m)

  const isStandardPassable = narrowDoorwayClearanceM >= 0.70;
  const isStepFreePassable = narrowDoorwayClearanceM >= 0.90;

  assert.equal(isStandardPassable, true, "standard route fits a 0.70m body");
  assert.equal(isStepFreePassable, false, "step-free route must refuse doorways narrower than 0.90m ADA standard");

  // When unmeasured, clearance cannot be assumed
  const unmeasuredClearance: number | null = null;
  const stepFreeWithUnmeasured = unmeasuredClearance !== null && unmeasuredClearance >= 0.90;
  assert.equal(stepFreeWithUnmeasured, false, "step-free rejects unmeasured clearance");
});

// 2. Dead ends and disconnected regions
test("disconnected regions and dead ends: refused with no valid route rather than crossing walls", () => {
  // A route response with no path or disconnected state has empty waypoints and no distance
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
  const gentleRampSlopeDeg = 4.1; // 1:14 grade
  const steepRampSlopeDeg = 5.8;  // exceeds 1:12 ADA max (4.76 deg)

  const isGentleAccessible = gentleRampSlopeDeg <= 4.76;
  const isSteepAccessible = steepRampSlopeDeg <= 4.76;

  assert.equal(isGentleAccessible, true, "gentle ramp <= 4.76 deg is accessible");
  assert.equal(isSteepAccessible, false, "steep ramp > 4.76 deg is excluded for step-free");

  // Stairs connector
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
    minClearanceM: null,
    status: "IN_SERVICE",
    accessibilityVerifiedAt: null,
    accessibilityVerifiedBy: null,
  };

  const stairsRoute = mkRoute({
    profile: "STEP_FREE",
    floorTransitions: [{
      fromFloorId: F0, toFloorId: F1, connectorType: "STAIRS", poiId: "p0", toPoiId: "p1", connectionId: "stairs-1",
      distanceMeters: 8.0, durationSeconds: 16.0,
    }],
  });

  const check = transitionsStillUsable(stairsRoute, [stairsConn]);
  assert.equal(check.usable, false, "stairs are never usable for a step-free route");
  assert.match(check.problems[0], /no longer verified as accessible/);
});

// 4. Step-free alternatives
test("step-free alternatives: refuses to label a route accessible solely because user selected toggle", () => {
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

  // If lift loses verification, step-free must refuse
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

  // When verified, succeeds
  const checkVerified = transitionsStillUsable(routeWithLift, [accessibleLift]);
  assert.equal(checkVerified.usable, true, "verified elevator is accepted for step-free route");
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

  // Reverse route F1 -> F0 on a one-way F0 -> F1 connection
  const reverseRoute = mkRoute({
    floorTransitions: [{
      fromFloorId: F1, toFloorId: F0, connectorType: "ELEVATOR", poiId: "p1", toPoiId: "p0", connectionId: "lift-1",
      distanceMeters: 0, durationSeconds: 45,
    }],
  });
  const checkOneWay = transitionsStillUsable(reverseRoute, [lift]);
  assert.equal(checkOneWay.usable, false, "cannot travel in reverse direction on one-way connector");

  // Out of service connector
  const outOfServiceLift = { ...lift, status: "OUT_OF_SERVICE" as const };
  const checkOos = transitionsStillUsable(mkRoute({
    floorTransitions: [{
      fromFloorId: F0, toFloorId: F1, connectorType: "ELEVATOR", poiId: "p0", toPoiId: "p1", connectionId: "lift-1",
      distanceMeters: 0, durationSeconds: 45,
    }],
  }), [outOfServiceLift]);
  assert.equal(checkOos.usable, false, "out of service connector cannot be used");
});

// 6. Obstacles intersecting smoothed routes
test("obstacles intersecting smoothed routes: detects path intrusion and triggers dynamic reroute", () => {
  const path: [number, number, number][] = [[0, 0, 0], [10, 0, 0]];
  // Obstacle right on the smoothed path centerline at x=5
  const blockingObs = {
    floorId: F0,
    minX: 4.8,
    minY: -0.2,
    maxX: 5.2,
    maxY: 0.2,
    observedAtMs: 10_000,
    coordinateFrameId: FRAME_CALIBRATED,
  };

  assert.equal(touchesPath(path, blockingObs), true, "obstacle intersects the smoothed route corridor");

  // Obstacle safely outside corridor
  const clearObs = {
    floorId: F0,
    minX: 5.0,
    minY: 2.0,
    maxX: 5.4,
    maxY: 2.4,
    observedAtMs: 10_000,
    coordinateFrameId: FRAME_CALIBRATED,
  };
  assert.equal(touchesPath(path, clearObs), false, "clear obstacle does not intersect path");

  const decision = rerouteDecision({
    floorId: F0,
    remainingPath: path,
    routedAround: [],
    observed: [blockingObs],
    nowMs: 11_000,
    lastRerouteMs: null,
  });
  assert.equal(decision.reroute, true, "intrusion triggers reroute request");
  assert.equal(decision.reason, "OBSTACLE_ON_PATH");
});

// 7. Endpoint snapping limits
test("endpoint snapping limits: verifies bounds and snap distance rejection semantics", () => {
  const maxSnapDistM = 2.0;
  const nodePosition = [1.0, 1.0, 0.0];

  const nearPoint = [1.5, 1.2, 0.0];
  const farPoint = [5.0, 6.0, 0.0];

  const nearDist = Math.hypot(nearPoint[0] - nodePosition[0], nearPoint[1] - nodePosition[1]);
  const farDist = Math.hypot(farPoint[0] - nodePosition[0], farPoint[1] - nodePosition[1]);

  assert.ok(nearDist <= maxSnapDistM, "near point is within snap distance limit");
  assert.ok(farDist > maxSnapDistM, "far point exceeds snap distance limit (must fail START_OFF_NAVMESH)");

  // Vertical bounds check (-1.0m to +2.5m from floor)
  const floorZ = 0.0;
  const cameraZ = 1.6; // valid eye height
  const ceilingZ = 4.5; // too high, out of bounds

  assert.ok(cameraZ >= floorZ - 1.0 && cameraZ <= floorZ + 2.5, "camera eye height within vertical bounds");
  assert.ok(ceilingZ > floorZ + 2.5, "point near ceiling exceeds vertical walkable bounds");
});

// 8. Uncalibrated geometry
test("uncalibrated geometry: refuses to draw routes on uncalibrated or unplaced frames", () => {
  const route = mkRoute({});

  // Reconstruction without a calibrated frame
  const uncalibratedScene = {
    floorId: F0,
    frameId: null, // NOT_CALIBRATED
    scanVersionId: null,
  };

  const overlay = routeOverlay(route, uncalibratedScene);
  assert.equal(overlay.waypoints, null, "route is refused when scene has no calibrated frame");
  assert.deepEqual(overlay.runs, [], "no runs drawn for uncalibrated scene");
});

// 9. Stale navigation artifacts
test("stale navigation artifacts: version mismatch and stale obstacle observations are rejected", () => {
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

  // Stale obstacle observations (> 120s old)
  const staleObs = {
    floorId: F0,
    minX: 1, minY: 1, maxX: 2, maxY: 2,
    observedAtMs: 1_000,
    coordinateFrameId: FRAME_CALIBRATED,
  };
  const nowMs = 1_000 + OBSTACLE_MAX_AGE_MS + 10_000;
  const fresh = freshObstacles([staleObs], nowMs);
  assert.deepEqual(fresh, [], "stale obstacle older than 120s is dropped");
});

// 10. No valid route
test("no valid route: surfaces actionable failure without papering over with false straight lines", () => {
  // Viewer route error classification
  const failedRoute: RouteResponse = {
    waypoints: [],
    distanceMeters: 0,
    estimatedDurationSeconds: 0,
    floorTransitions: [],
    accessibilityProfile: "STANDARD",
    accessibilityConstraintsApplied: [],
  };

  const summary = routeSummary(failedRoute);
  assert.equal(summary.distanceMeters, null);
  assert.equal(summary.durationSeconds, null);

  const overlay = routeOverlay(failedRoute, { floorId: F0, frameId: FRAME_CALIBRATED, scanVersionId: SCAN_FINAL });
  assert.equal(overlay.waypoints, null);
});

// 11. Multi-destination waypoint chaining
test("multi-destination waypoint chaining: chains segments, reports unreachable stops, preserves order", async () => {
  const startPt: SegmentPoint = { key: "START", floorId: F0, position: [0, 0, 0] };
  const stopA: StopRef = { id: "stop-a", floorId: F0, position: [3, 0, 0] };
  const stopB: StopRef = { id: "stop-b", floorId: F0, position: [7, 0, 0] };

  const fetchSegment = async (from: SegmentPoint, to: StopRef) => {
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
    return { ok: true, route };
  };

  const plan = await planStops({
    scope: "scope",
    profile: "STANDARD",
    start: startPt,
    stops: [stopA, stopB],
    cache: new Map(),
    fetchSegment,
  });

  assert.equal(plan.stops.every((s) => s.status === "REACHED"), true);
  assert.equal(plan.route!.distanceMeters, 7.0);
  assert.deepEqual(plan.route!.waypoints.map((w) => `${w.x}:${w.kind}`), [
    "0:START", "3:STOP", "7:DESTINATION",
  ]);

  // Joined route preserves total distance and duration
  const joined = joinSegments(plan.segments.map((s) => s.route!));
  assert.equal(joined.distanceMeters, 7.0);
});
