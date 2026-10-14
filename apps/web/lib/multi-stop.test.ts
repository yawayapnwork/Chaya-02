import assert from "node:assert/strict";
import { test } from "node:test";
import { type SegmentOutcome, type SegmentPoint, type StopRef, consistentSources, joinSegments, moveStop, planStops } from "./multi-stop.ts";
import type { RouteResponse } from "./navigation-api.ts";

// A synthetic one-floor world on the x axis: a segment's route is the straight line between the two points, routed on
// graph `graph`. Stops listed in `unreachable` fail with NO_ROUTE. Not a venue; it exercises the planning rules.
const F = "floor";
const stop = (id: string, x: number): StopRef => ({ id, floorId: F, position: [x, 0, 0] });
const start: SegmentPoint = { key: "START", floorId: F, position: [0, 0, 0] };

function world(opts: { unreachable?: string[]; graph?: () => string; failWith?: SegmentOutcome } = {}) {
  const calls: string[] = [];
  const fetchSegment = async (from: SegmentPoint, to: StopRef): Promise<SegmentOutcome> => {
    calls.push(`${from.key}>${to.id}`);
    if (opts.failWith) return opts.failWith;
    if (opts.unreachable?.includes(to.id)) return { ok: false, code: "NO_ROUTE", message: "disconnected", retryable: false };
    const d = Math.abs(to.position[0] - from.position[0]);
    const route: RouteResponse = {
      waypoints: [
        { x: from.position[0], y: 0, z: 0, floorId: F, kind: "START" },
        { x: to.position[0], y: 0, z: 0, floorId: F, kind: "DESTINATION" },
      ],
      distanceMeters: d, estimatedDurationSeconds: d / 1.3, floorTransitions: [], accessibilityProfile: "STANDARD",
      accessibilityConstraintsApplied: [],
      routingSources: [{ floorId: F, graphId: opts.graph?.() ?? "g1", source: "RECAST_NAVMESH", navmeshSha256: null,
        recastnavigationVersion: "1.6.0", pathMethod: "STRING_PULLED", scanVersionId: "v1", coordinateFrameId: "f1" }],
    };
    return { ok: true, route };
  };
  return { calls, fetchSegment };
}

test("stops are visited in the given order and joined into one continuous route", async () => {
  const w = world();
  const plan = await planStops({ scope: "s", profile: "STANDARD", start, stops: [stop("a", 10), stop("b", 4)], cache: new Map(), fetchSegment: w.fetchSegment });
  assert.deepEqual(w.calls, ["START>a", "POI:a>b"]);
  assert.equal(plan.route!.distanceMeters, 16);
  assert.deepEqual(plan.route!.waypoints.map((p) => `${p.x}:${p.kind}`), ["0:START", "10:STOP", "4:DESTINATION"]);
});

test("changing one destination recalculates only the segments into and out of it", async () => {
  const cache = new Map();
  const first = world();
  await planStops({ scope: "s", profile: "STANDARD", start, stops: [stop("a", 2), stop("b", 4), stop("c", 6)], cache, fetchSegment: first.fetchSegment });
  assert.equal(first.calls.length, 3);
  const second = world();
  const plan = await planStops({ scope: "s", profile: "STANDARD", start, stops: [stop("a", 2), stop("x", 5), stop("c", 6)], cache, fetchSegment: second.fetchSegment });
  assert.deepEqual(second.calls, ["POI:a>x", "POI:x>c"], "START>a is reused");
  assert.equal(plan.fetched, 2);
  // Appending a stop costs one segment.
  const third = world();
  await planStops({ scope: "s", profile: "STANDARD", start, stops: [stop("a", 2), stop("x", 5), stop("c", 6), stop("d", 9)], cache, fetchSegment: third.fetchSegment });
  assert.deepEqual(third.calls, ["POI:c>d"]);
});

test("another profile, scene or obstacle set never reuses a segment", async () => {
  const cache = new Map();
  await planStops({ scope: "s", profile: "STANDARD", start, stops: [stop("a", 2)], cache, fetchSegment: world().fetchSegment });
  for (const args of [{ scope: "s", profile: "STEP_FREE" }, { scope: "s2", profile: "STANDARD" }, { scope: "s", profile: "STANDARD", obstaclesKey: "o" }]) {
    const w = world();
    await planStops({ ...args, start, stops: [stop("a", 2)], cache, fetchSegment: w.fetchSegment });
    assert.equal(w.calls.length, 1, JSON.stringify(args));
  }
});

test("an unreachable intermediate stop is reported and the route continues from the last stop reached", async () => {
  const w = world({ unreachable: ["b"] });
  const plan = await planStops({ scope: "s", profile: "STANDARD", start, stops: [stop("a", 2), stop("b", 4), stop("c", 6)], cache: new Map(), fetchSegment: w.fetchSegment });
  assert.deepEqual(w.calls, ["START>a", "POI:a>b", "POI:a>c"]);
  assert.deepEqual(plan.stops.map((s) => `${s.id}:${s.status}:${s.code}`), ["a:REACHED:null", "b:UNREACHABLE:NO_ROUTE", "c:REACHED:null"]);
  assert.equal(plan.route!.distanceMeters, 6);
});

test("an expired session interrupts the plan: later stops are not called unreachable and nothing is cached", async () => {
  const cache = new Map();
  const expired = world({ failWith: { ok: false, code: "SESSION_EXPIRED", message: "401", retryable: false } });
  const plan = await planStops({ scope: "s", profile: "STANDARD", start, stops: [stop("a", 2), stop("b", 4)], cache, fetchSegment: expired.fetchSegment });
  assert.deepEqual(plan.interrupted, { code: "SESSION_EXPIRED", message: "401" });
  assert.deepEqual(plan.stops.map((s) => s.code), ["SESSION_EXPIRED", "NOT_TRIED"]);
  assert.equal(cache.size, 0);
  assert.equal(plan.route, null);
});

test("segments routed on two different graphs of one floor are never joined: the plan is redone", async () => {
  const cache = new Map();
  await planStops({ scope: "s", profile: "STANDARD", start, stops: [stop("a", 2)], cache, fetchSegment: world({ graph: () => "old" }).fetchSegment });
  const w = world({ graph: () => "new" });
  const plan = await planStops({ scope: "s", profile: "STANDARD", start, stops: [stop("a", 2), stop("b", 4)], cache, fetchSegment: w.fetchSegment });
  assert.deepEqual(w.calls, ["POI:a>b", "START>a", "POI:a>b"], "the cached old-graph segment was dropped and everything replanned");
  assert.ok(consistentSources(plan.segments.map((s) => s.route)));
  assert.deepEqual(plan.route!.routingSources!.map((s) => s.graphId), ["new"]);
});

test("joinSegments sums distance and time and keeps each transition", () => {
  const leg = (x0: number, x1: number): RouteResponse => ({
    waypoints: [{ x: x0, y: 0, z: 0, floorId: F, kind: "START" }, { x: x1, y: 0, z: 0, floorId: F, kind: "DESTINATION" }],
    distanceMeters: Math.abs(x1 - x0), estimatedDurationSeconds: 1, floorTransitions: [], accessibilityProfile: "STEP_FREE",
    accessibilityConstraintsApplied: ["c"],
  });
  const joined = joinSegments([leg(0, 1), leg(1, 3)]);
  assert.equal(joined.distanceMeters, 3);
  assert.equal(joined.estimatedDurationSeconds, 2);
  assert.equal(joined.accessibilityProfile, "STEP_FREE");
  assert.deepEqual(joined.accessibilityConstraintsApplied, ["c"]);
});

test("moveStop reorders, and ignores moves past either end", () => {
  assert.deepEqual(moveStop(["a", "b", "c"], 2, -1), ["a", "c", "b"]);
  assert.deepEqual(moveStop(["a", "b", "c"], 0, -1), ["a", "b", "c"]);
});
