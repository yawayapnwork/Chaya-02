import assert from "node:assert/strict";
import { test } from "node:test";
import type { RouteResponse, RouteWaypoint } from "./navigation-api.ts";
import { classifyRouteError, describeRouteFailure, formatDistance, formatDuration, routeOverlay, routeSummary, runsOnFloor } from "./viewer-route.ts";

// Synthetic route responses in the API's shape: they exercise the presentation rules, they are not a venue.
const F1 = "floor-1";
const F2 = "floor-2";
const wp = (x: number, floorId: string, kind: RouteWaypoint["kind"] = "WAYPOINT"): RouteWaypoint => ({ x, y: 0, z: 0, floorId, kind });

function route(waypoints: RouteWaypoint[], extra: Partial<RouteResponse> = {}): RouteResponse {
  return {
    waypoints, distanceMeters: 12.5, estimatedDurationSeconds: 10, floorTransitions: [], accessibilityProfile: "STANDARD",
    accessibilityConstraintsApplied: [], ...extra,
  };
}
const source = (floorId: string, coordinateFrameId: string | null, scanVersionId: string | null = null) => ({
  floorId, graphId: "g", source: "RECAST_NAVMESH", navmeshSha256: null, recastnavigationVersion: null, pathMethod: null,
  scanVersionId, coordinateFrameId,
});

test("only this floor's leg is drawn, and the other floors the route passes through are named", () => {
  const r = route([wp(0, F1, "START"), wp(1, F1), wp(2, F1, "TRANSITION"), wp(0, F2, "TRANSITION"), wp(3, F2, "DESTINATION")]);
  const o = routeOverlay(r, { floorId: F1, frameId: "frame-a", scanVersionId: null });
  assert.deepEqual(o.waypoints?.map((w) => w.x), [0, 1, 2]);
  assert.deepEqual(o.otherFloorIds, [F2]);
  assert.equal(o.mismatch, null);
});

test("a leg routed in another coordinate frame is refused, never drawn", () => {
  const r = route([wp(0, F1), wp(1, F1)], { routingSources: [source(F1, "frame-b")] });
  const o = routeOverlay(r, { floorId: F1, frameId: "frame-a", scanVersionId: null });
  assert.equal(o.waypoints, null);
  assert.equal(o.mismatch, "FRAME_MISMATCH");
});

test("a leg routed on another scan version is refused, never drawn", () => {
  const r = route([wp(0, F1), wp(1, F1)], { routingSources: [source(F1, "frame-a", "v2")] });
  const o = routeOverlay(r, { floorId: F1, frameId: "frame-a", scanVersionId: "v1" });
  assert.equal(o.waypoints, null);
  assert.equal(o.mismatch, "VERSION_MISMATCH");
});

test("a leg routed in this frame and version is drawn", () => {
  const r = route([wp(0, F1), wp(1, F1)], { routingSources: [source(F1, "frame-a", "v1")] });
  assert.equal(routeOverlay(r, { floorId: F1, frameId: "frame-a", scanVersionId: "v1" }).waypoints?.length, 2);
});

test("nothing is drawn on an uncalibrated reconstruction, and that is not reported as a mismatch", () => {
  const o = routeOverlay(route([wp(0, F1), wp(1, F1)], { routingSources: [source(F1, "frame-a")] }), { floorId: F1, frameId: null, scanVersionId: null });
  assert.equal(o.waypoints, null);
  assert.equal(o.mismatch, null);
});

test("a route that never reaches this floor draws nothing here", () => {
  const o = routeOverlay(route([wp(0, F2), wp(1, F2)]), { floorId: F1, frameId: "frame-a", scanVersionId: null });
  assert.equal(o.waypoints, null);
  assert.deepEqual(o.otherFloorIds, [F2]);
});

test("distance and duration are shown only for a real path with finite values", () => {
  assert.deepEqual(routeSummary(route([wp(0, F1), wp(1, F1)])), { distanceMeters: 12.5, durationSeconds: 10 });
  assert.deepEqual(routeSummary(route([wp(0, F1)])), { distanceMeters: null, durationSeconds: null });
  assert.deepEqual(routeSummary(route([wp(0, F1), wp(1, F1)], { estimatedDurationSeconds: Number.NaN })), { distanceMeters: 12.5, durationSeconds: null });
});

test("formatting", () => {
  assert.equal(formatDistance(7.25), "7.3 m");
  assert.equal(formatDistance(42.4), "42 m");
  assert.equal(formatDistance(1520), "1.52 km");
  assert.equal(formatDuration(0.2), "1 s");
  assert.equal(formatDuration(150), "3 min");
});

test("each routing failure code is reported as what it means", () => {
  assert.equal(describeRouteFailure("NO_ROUTE", "d", "STANDARD").kind, "unreachable");
  assert.equal(describeRouteFailure("NO_ACCESSIBLE_ROUTE", "d", "STEP_FREE").kind, "no-accessible-route");
  assert.match(describeRouteFailure("FLOOR_CONNECTION_UNAVAILABLE", "d", "STEP_FREE").message, /step-free connection/);
  assert.equal(describeRouteFailure("NAVMESH_NOT_READY", "d", "STANDARD").kind, "not-routable");
  assert.equal(describeRouteFailure("NOT_CALIBRATED", "d", "STANDARD").kind, "not-routable");
  assert.equal(describeRouteFailure("METRIC_EVIDENCE_INSUFFICIENT", "d", "STANDARD").kind, "not-routable");
  assert.match(describeRouteFailure("METRIC_EVIDENCE_INSUFFICIENT", "d", "STANDARD").message, /hand-entered/);
  assert.deepEqual(describeRouteFailure("HTTP_500", "server said so", "STANDARD"), { kind: "other", message: "server said so" });
});

test("an expired sign-in and a lost connection are told apart from the API's routing failures", () => {
  const expired = classifyRouteError(Object.assign(new Error("401 Unauthorized"), { status: 401, code: "HTTP_401" }), "STANDARD");
  assert.equal(expired.kind, "session-expired");
  assert.equal(expired.retryable, false);
  const offline = classifyRouteError(new TypeError("Failed to fetch"), "STANDARD");
  assert.equal(offline.kind, "network");
  assert.equal(offline.retryable, true);
  const off = classifyRouteError(Object.assign(new Error("off the navmesh"), { status: 422, code: "START_OFF_NAVMESH" }), "STANDARD");
  assert.equal(off.kind, "bad-endpoint");
  assert.equal(off.code, "START_OFF_NAVMESH");
  const noStepFree = classifyRouteError(Object.assign(new Error("no"), { status: 404, code: "NO_ACCESSIBLE_ROUTE" }), "STEP_FREE");
  assert.equal(noStepFree.kind, "no-accessible-route");
});

test("a route that leaves this floor and comes back is drawn as two separate runs", () => {
  const r = route([wp(0, F1, "START"), wp(5, F1, "TRANSITION"), wp(5, F2), wp(9, F2, "STOP"), wp(9, F2, "TRANSITION"), wp(5, F1), wp(1, F1, "DESTINATION")]);
  assert.deepEqual(runsOnFloor(r, F1).map((run) => run.map((w) => w.x)), [[0, 5], [5, 1]]);
  assert.deepEqual(routeOverlay(r, { floorId: F1, frameId: "frame", scanVersionId: null }).runs.length, 2);
});
