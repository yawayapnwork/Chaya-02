import assert from "node:assert/strict";
import { test } from "node:test";
import type { RouteResponse, RouteWaypoint } from "./navigation-api.ts";
import {
  ARRIVAL_DWELL_MS,
  cumulativeMeters,
  initialArrival,
  locateOnRoute,
  nextInstruction,
  routeInstructions,
  turnDegrees,
  updateArrival,
} from "./route-instructions.ts";

// Synthetic routes in the API's shape (canonical metres, +Z up): they exercise the rules, they are not a venue.
const G = "ground";
const U = "upper";
const w = (x: number, y: number, floorId = G, kind: RouteWaypoint["kind"] = "WAYPOINT", z = 0): RouteWaypoint => ({ x, y, z, floorId, kind });
const route = (waypoints: RouteWaypoint[], extra: Partial<RouteResponse> = {}): RouteResponse => ({
  waypoints, distanceMeters: 0, estimatedDurationSeconds: 0, floorTransitions: [], accessibilityProfile: "STANDARD",
  accessibilityConstraintsApplied: [], ...extra,
});

// East 10 m, then a left turn north 5 m to the destination.
const ell = route([w(0, 0, G, "START"), w(10, 0), w(10, 5, G, "DESTINATION")]);

test("turns are signed about canonical +Z: counter-clockwise seen from above is left", () => {
  assert.equal(Math.round(turnDegrees(w(0, 0), w(10, 0), w(10, 5))!), 90);
  assert.equal(Math.round(turnDegrees(w(0, 0), w(10, 0), w(10, -5))!), -90);
  assert.equal(turnDegrees(w(0, 0), w(0, 0.01), w(10, 5)), null, "a segment too short has no direction");
});

test("instructions: depart, the turn with the distance to it, and arrival", () => {
  const ins = routeInstructions(ell, { stopName: () => "Reception" });
  assert.deepEqual(ins.map((i) => i.kind), ["DEPART", "TURN_LEFT", "ARRIVE"]);
  assert.equal(ins[0].toNextMeters, 10);
  assert.equal(ins[1].atMeters, 10);
  assert.equal(ins[1].toNextMeters, 5);
  assert.equal(ins[2].text, "Arrive at Reception");
});

test("gentle bends of a string-pulled path are not announced; slight and sharp turns are named", () => {
  const bends = route([w(0, 0, G, "START"), w(5, 0.5), w(10, 0), w(12, 1.5), w(8, 3), w(9, 3, G, "DESTINATION")]);
  const kinds = routeInstructions(bends).map((i) => i.kind);
  // (5, 0.5): an 11 degree bend, not announced; (10, 0): 43 degrees left; (12, 1.5): 123 left; (8, 3): 159 right.
  assert.deepEqual(kinds, ["DEPART", "SLIGHT_LEFT", "TURN_LEFT", "SHARP_RIGHT", "ARRIVE"]);
});

test("a floor change is an instruction, and its distance is the registered connector length, never a cross-floor coordinate", () => {
  const r = route([w(0, 0, G, "START"), w(4, 0, G, "TRANSITION"), w(-40, 73, U), w(-40, 76, U, "DESTINATION")], {
    floorTransitions: [{ fromFloorId: G, toFloorId: U, connectorType: "STAIRS", poiId: "a", connectionId: "c", toPoiId: "b", distanceMeters: 6, durationSeconds: 12 }],
  });
  assert.deepEqual(cumulativeMeters(r), [0, 4, 10, 13]);
  const ins = routeInstructions(r, { floorName: (id) => (id === U ? "Level 1" : id) });
  const take = ins.find((i) => i.kind === "TAKE_CONNECTION")!;
  assert.equal(take.text, "Take the stairs to Level 1");
  assert.equal(take.toFloorId, U);
  assert.equal(take.connectorType, "STAIRS");
});

test("a multi-stop route announces each intermediate stop", () => {
  const r = route([w(0, 0, G, "START"), w(5, 0, G, "STOP"), w(5, 5, G, "DESTINATION")]);
  const names = ["Cafe", "Exit"];
  const ins = routeInstructions(r, { stopName: (i) => names[i] });
  assert.deepEqual(ins.map((i) => i.text), ["Start along the highlighted route", "Arrive at stop: Cafe", "Arrive at Exit"]);
});

test("remaining distance uses the route's 3-D length (a ramp is longer than its plan view)", () => {
  const ramp = route([w(0, 0, G, "START"), w(10, 0, G, "DESTINATION", 1)]);
  const p = locateOnRoute(ramp, G, [5, 0.2, 1.5], null)!;
  assert.ok(Math.abs(p.remainingMeters - Math.hypot(10, 1) / 2) < 1e-9);
  assert.ok(Math.abs(p.offRouteMeters - 0.2) < 1e-9);
});

test("progress is monotone: an out-and-back route is not jumped ahead on where it passes the same spot", () => {
  const outAndBack = route([w(0, 0, G, "START"), w(10, 0, G, "STOP"), w(10, 0.5), w(0, 0.5, G, "DESTINATION")]);
  let p = locateOnRoute(outAndBack, G, [1, 0.25, 0], null)!;
  assert.equal(p.segment, 0, "starting out: the first segment");
  p = locateOnRoute(outAndBack, G, [2, 0.3, 0], p)!;
  assert.equal(p.segment, 0, "still going out, although the return leg is nearer");
  p = locateOnRoute(outAndBack, G, [9.9, 0.3, 0], p)!;
  p = locateOnRoute(outAndBack, G, [5, 0.45, 0], p)!;
  assert.equal(p.segment, 2, "after the turn, on the way back");
});

test("nextInstruction is the first one still ahead", () => {
  const ins = routeInstructions(ell);
  assert.equal(nextInstruction(ins, null)!.kind, "DEPART");
  assert.equal(nextInstruction(ins, { alongMeters: 3 })!.kind, "TURN_LEFT");
  assert.equal(nextInstruction(ins, { alongMeters: 12 })!.kind, "ARRIVE");
});

// ---- arrival ----

function walk(r: RouteResponse, floorId: string, positions: [number, number][], stepMs = 500) {
  let state = initialArrival(r);
  let pos = null as ReturnType<typeof locateOnRoute>;
  positions.forEach(([x, y], i) => {
    pos = locateOnRoute(r, floorId, [x, y, 1.5], pos);
    state = updateArrival(state, r, floorId, [x, y, 1.5], pos, i * stepMs);
  });
  return state;
}

test("arrival needs the device near the destination continuously for the dwell time", () => {
  const near: [number, number][] = [[10, 4], [10, 4.2], [10, 4.4], [10, 4.5], [10, 4.6]];
  assert.equal(walk(ell, G, near.slice(0, 2)).arrived, false, "500 ms is not enough");
  assert.equal(walk(ell, G, near).arrived, true, `${(near.length - 1) * 500} ms >= ${ARRIVAL_DWELL_MS} ms`);
});

test("a lost fix or leaving the radius resets the dwell", () => {
  let state = initialArrival(ell);
  let pos = locateOnRoute(ell, G, [10, 4.5, 1.5], null);
  state = updateArrival(state, ell, G, [10, 4.5, 1.5], pos, 0);
  state = updateArrival(state, ell, G, null, null, 1500); // tracking lost
  pos = locateOnRoute(ell, G, [10, 4.5, 1.5], pos);
  state = updateArrival(state, ell, G, [10, 4.5, 1.5], pos, 2500);
  assert.equal(state.arrived, false, "the dwell restarted at 2500 ms");
  state = updateArrival(state, ell, G, [10, 4.5, 1.5], pos, 4600);
  assert.equal(state.arrived, true);
});

test("no premature arrival: near the destination in a straight line but not along the route", () => {
  // A U-shaped route: the destination is 1 m from the start through a wall, 21 m along the route.
  const u = route([w(0, 0, G, "START"), w(0, 10), w(1, 10), w(1, 0, G, "DESTINATION")]);
  assert.equal(walk(u, G, [[0, 0.2], [0, 0.4], [0, 0.6], [0, 0.8], [0, 0.9], [0.1, 0.9]]).arrived, false);
});

test("no premature arrival on the wrong floor, or on another floor's leg ending at the same coordinates", () => {
  const twoFloors = route([w(0, 0, G, "START"), w(5, 0, G, "TRANSITION"), w(0, 0, U), w(5, 0, U, "DESTINATION")]);
  const positions: [number, number][] = [[4.5, 0], [4.6, 0], [4.7, 0], [4.8, 0], [4.9, 0], [5, 0]];
  assert.equal(walk(twoFloors, G, positions).arrived, false, "the ground-floor leg ends at the transition, not the destination");
  assert.equal(walk(twoFloors, U, positions).arrived, true);
});

test("a stop is reached first, then the destination", () => {
  const r = route([w(0, 0, G, "START"), w(5, 0, G, "STOP"), w(5, 5, G, "DESTINATION")]);
  let state = initialArrival(r);
  assert.equal(state.targetIndex, 1);
  let pos = null as ReturnType<typeof locateOnRoute>;
  for (let t = 0; t <= 2500; t += 500) {
    pos = locateOnRoute(r, G, [4.8, 0, 1.5], pos);
    state = updateArrival(state, r, G, [4.8, 0, 1.5], pos, t);
  }
  assert.deepEqual(state.reachedStops, [1]);
  assert.equal(state.targetIndex, 2);
  assert.equal(state.arrived, false);
});
