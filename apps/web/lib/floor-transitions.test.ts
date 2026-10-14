import assert from "node:assert/strict";
import { test } from "node:test";
import { FLOOR_WINDOW, identifyFloor, pushFloorWindow } from "./ar-floor-identification.ts";
import { floorLabel, routeFloors, transitionsStillUsable } from "./floor-transitions.ts";
import type { FloorConnection, RouteResponse } from "./navigation-api.ts";

// Synthetic floors, connections and routes in the API's shapes: they exercise the rules, they are not a building.

test("a floor's elevation is shown only as recorded, with its source", () => {
  assert.equal(floorLabel({ id: "f", level: 1, name: "First", elevation: { elevationM: 4.2, source: "SURVEYED" } }),
    "First (level 1 · +4.20 m, surveyed)");
  assert.equal(floorLabel({ id: "f", level: -1, name: "Basement", elevation: { elevationM: -3, source: "MEASURED_ON_SITE" } }),
    "Basement (level -1 · −3.00 m, measured on site)");
  assert.equal(floorLabel({ id: "f", level: 2, name: "Second", elevation: null }), "Second (level 2 · elevation not recorded)");
});

const lift: FloorConnection = {
  id: "lift", connectorType: "ELEVATOR", fromFloorId: "g", fromPoiId: "a", toFloorId: "u", toPoiId: "b", bidirectional: true,
  lengthM: null, maxSlopeDeg: null, minClearanceM: 1.1, status: "IN_SERVICE", accessibilityVerifiedAt: "2026-10-01T00:00:00Z",
  accessibilityVerifiedBy: "staff",
};
const route = (profile: "STANDARD" | "STEP_FREE", fromFloorId = "u", toFloorId = "g"): RouteResponse => ({
  waypoints: [], distanceMeters: 1, estimatedDurationSeconds: 1, accessibilityProfile: profile, accessibilityConstraintsApplied: [],
  floorTransitions: [{ fromFloorId, toFloorId, connectorType: "ELEVATOR", poiId: "b", connectionId: "lift", toPoiId: "a",
    distanceMeters: 0, durationSeconds: 45 }],
});

test("a route's transitions are re-checked: closed, removed, one-way the other way, or no longer verified", () => {
  assert.deepEqual(transitionsStillUsable(route("STEP_FREE"), [lift]), { usable: true, problems: [], connectionIds: [] });
  assert.deepEqual(transitionsStillUsable(route("STANDARD"), [{ ...lift, status: "OUT_OF_SERVICE" }]).connectionIds, ["lift"]);
  assert.match(transitionsStillUsable(route("STANDARD"), [{ ...lift, status: "OUT_OF_SERVICE" }]).problems[0], /out of service/);
  assert.match(transitionsStillUsable(route("STANDARD"), []).problems[0], /removed/);
  assert.match(transitionsStillUsable(route("STANDARD"), [{ ...lift, bidirectional: false }]).problems[0], /direction/,
    "the route goes u -> g, the lift now only g -> u");
  assert.equal(transitionsStillUsable(route("STANDARD", "g", "u"), [{ ...lift, bidirectional: false }]).usable, true);
  const unverified = { ...lift, accessibilityVerifiedAt: null };
  assert.match(transitionsStillUsable(route("STEP_FREE"), [unverified]).problems[0], /no longer verified/);
  assert.equal(transitionsStillUsable(route("STANDARD"), [unverified]).usable, true, "a standard route does not need the verification");
});

test("routeFloors lists the floors in order", () => {
  const w = (floorId: string) => ({ x: 0, y: 0, z: 0, floorId, kind: "WAYPOINT" as const });
  assert.deepEqual(routeFloors({ waypoints: [w("g"), w("g"), w("1"), w("2"), w("2")] }), ["g", "1", "2"]);
});

test("floor identification: confirmed, pending, unexpected and ambiguous", () => {
  let win: string[][] = [];
  win = pushFloorWindow(win, ["f2"]);
  assert.deepEqual(identifyFloor(win, "f2"), { status: "PENDING" }, "one sighting after a floor change is not enough");
  assert.deepEqual(identifyFloor(win, "f2", 1), { status: "CONFIRMED" });
  win = pushFloorWindow(win, ["f2", "f2"]);
  assert.deepEqual(identifyFloor(win, "f2"), { status: "CONFIRMED" });
  assert.deepEqual(identifyFloor([["f3"], ["f3"]], "f2"), { status: "UNEXPECTED", detectedFloorId: "f3" });
  assert.deepEqual(identifyFloor([["f3"]], "f2"), { status: "PENDING" });
  assert.deepEqual(identifyFloor([["f2", "f3"]], "f2"), { status: "AMBIGUOUS", floorIds: ["f2", "f3"] });
  let w2: string[][] = [["f3"]];
  for (let i = 0; i < FLOOR_WINDOW; i++) w2 = pushFloorWindow(w2, ["f2"]);
  assert.deepEqual(identifyFloor(w2, "f2"), { status: "CONFIRMED" }, "an old sighting of another floor leaves the window");
  assert.deepEqual(pushFloorWindow([["a"]], []), [["a"]], "a frame without markers is no evidence");
});
