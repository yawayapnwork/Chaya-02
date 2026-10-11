// A known anchor layout and route, walked through the same functions components/ArWorkspace.tsx wires to WebXR: marker
// observation, the multi-anchor solve (the server's algorithm, mirrored in ar-anchor-math.ts), the state machine, the
// route request and leg check, progress, a limited-tracking spell, the floor handoff, a moved marker and drift.
//
// The device poses are computed from a chosen ground-truth transform: this checks the coordinate chain and the session
// logic against a known answer. It is not a simulation of tracking quality and says nothing about accuracy on a device
// (docs/ar-android-validation.md is that test).

import assert from "node:assert/strict";
import { test } from "node:test";
import { type Pose, compose, invert, solve } from "./ar-anchor-math.ts";
import { classifyArFailure } from "./ar-errors.ts";
import { type TrackedMarker, observeImages, relocalizationEligibility } from "./ar-marker-tracking.ts";
import { arRouteRequest, driftExceeded, floorHandoff, markerDrift, routeLegFor } from "./ar-navigation.ts";
import { type ArEvent, type ArSessionState, type Localization, canAdvanceRoute, initialArSessionState, reduceArSession, routeShown } from "./ar-relocalization.ts";
import { advanceProgress, devicePositionInVenue, type RouteProgress } from "./ar-route.ts";
import type { RouteResponse } from "./navigation-api.ts";

const S = Math.SQRT1_2;
const AXES: Pose = { x: 0, y: 0, z: 0, qx: S, qy: 0, qz: 0, qw: S }; // device +Y up -> canonical +Z up
const yaw = (deg: number): Pose => ({ x: 0, y: 0, z: 0, qx: 0, qy: 0, qz: Math.sin((deg * Math.PI) / 360), qw: Math.cos((deg * Math.PI) / 360) });
const at = (x: number, y: number, z: number): Pose => ({ x, y, z, qx: 0, qy: 0, qz: 0, qw: 1 });

/** Ground truth for this test: the AR session's world, as each floor's canonical frame sees it. */
const TRUE_F1 = compose(at(3, -2, 0), compose(yaw(25), AXES));
const TRUE_F2 = compose(at(-6, 1, 0), compose(yaw(-70), AXES)); // floor 2 has its own canonical frame

const WALL_S: Pose = { x: 0, y: 0, z: 1.5, qx: 1, qy: 0, qz: 0, qw: 0 }; // facing canonical -Y
const marker = (x: number, y: number): Pose => ({ ...WALL_S, x, y });
const F1_MARKERS = { a1: marker(2, 0), a2: marker(8, 0), a3: marker(5, 10) };
const F2_MARKERS = { b1: marker(-4, 2), b2: marker(2, 2), b3: marker(0, 9) };

const TRACKED: TrackedMarker[] = [
  ...Object.keys(F1_MARKERS).map((id, i) => ({ index: i, anchorId: id, floorId: "floor-1", markerIdentifier: id, widthInMeters: 0.3 })),
  ...Object.keys(F2_MARKERS).map((id, i) => ({ index: 3 + i, anchorId: id, floorId: "floor-2", markerIdentifier: id, widthInMeters: 0.3 })),
];
const DIGITAL: Record<string, Pose> = { ...F1_MARKERS, ...F2_MARKERS };

/** What WebXR image tracking would report for these markers, seen from a session whose world is `truth`. */
function observe(truth: Pose, ids: string[], moved: Record<string, Pose> = {}) {
  const results = ids.map((id) => ({
    index: TRACKED.find((m) => m.anchorId === id)!.index, imageSpace: id, trackingState: "tracked" as const, measuredWidthInMeters: 0.3,
  }));
  return observeImages(results, TRACKED, (space) => compose(invert(truth), moved[space as string] ?? DIGITAL[space as string]), 0)
    .filter((o) => relocalizationEligibility(o).eligible);
}

/** What the server answers (AnchorService#relocalize, the same solve), or the refusal it would send. */
function relocalize(observations: ReturnType<typeof observe>, floorId: string, scanVersionId: string, frameId: string, nowMs: number): Localization {
  const s = solve(observations.map((o) => ({ digitalPose: DIGITAL[o.anchorId], observedPose: o.observedPose })), 0.3, 8);
  if (!s.consistent) throw Object.assign(new Error("the observed anchors disagree"), { status: 409, code: "RELOCALIZATION_INCONSISTENT" });
  const used = s.residuals.filter((r) => r.used);
  return {
    deviceToVenue: s.transform, anchorIds: used.map((r) => observations[r.index].anchorId),
    residualMeters: used.length === 1 ? null : s.maxResidualMeters, coordinateFrameId: frameId, scanVersionId, floorId, solvedAtMs: nowMs,
  };
}

const ROUTE: RouteResponse = {
  waypoints: [
    { x: 3, y: 2, z: 0, floorId: "floor-1", kind: "START" },
    { x: 8, y: 2, z: 0, floorId: "floor-1", kind: "WAYPOINT" },
    { x: 8, y: 8, z: 0, floorId: "floor-1", kind: "TRANSITION" },
    { x: -3, y: 4, z: 0, floorId: "floor-2", kind: "TRANSITION" },
    { x: 1, y: 7, z: 0, floorId: "floor-2", kind: "DESTINATION" },
  ],
  distanceMeters: 16, estimatedDurationSeconds: 90, accessibilityProfile: "STEP_FREE",
  accessibilityConstraintsApplied: ["uses only edges of the STEP_FREE graph baked from the navmesh"],
  floorTransitions: [{ fromFloorId: "floor-1", toFloorId: "floor-2", connectorType: "ELEVATOR", poiId: "p1", connectionId: "c1",
    toPoiId: "p2", distanceMeters: 0, durationSeconds: 45 }],
  routingSources: [
    { floorId: "floor-1", graphId: "g1", source: "RECAST_NAVMESH", navmeshSha256: null, recastnavigationVersion: null, pathMethod: null,
      scanVersionId: "sv-1", coordinateFrameId: "frame-1" },
    { floorId: "floor-2", graphId: "g2", source: "RECAST_NAVMESH", navmeshSha256: null, recastnavigationVersion: null, pathMethod: null,
      scanVersionId: "sv-2", coordinateFrameId: "frame-2" },
  ],
};

/** The camera, held 1.4 m above the canonical point (x, y) on the floor, in the session world `truth` describes. */
function viewerAt(truth: Pose, x: number, y: number): Pose {
  return compose(invert(truth), at(x, y, 1.4));
}

test("a known two-floor layout and step-free route, walked through the session chain", () => {
  let s: ArSessionState = initialArSessionState();
  const dispatch = (e: ArEvent) => { s = reduceArSession(s, e); };
  dispatch({ type: "SESSION_STARTED", floorId: "floor-1" });
  dispatch({ type: "TRACKING_RESTORED" });

  // Two markers of floor 1 in view (and one of floor 2, which is registered for the session but ignored on this floor).
  const seen = observe(TRUE_F1, ["a1", "a2", "b1"]);
  dispatch({ type: "MARKERS_OBSERVED", observations: seen });
  assert.equal(s.state, "RELOCALIZING");
  assert.deepEqual(s.pending!.observations.map((o) => o.anchorId), ["a1", "a2"]);
  dispatch({ type: "RELOCALIZATION_SUCCEEDED", requestId: s.pending!.requestId,
    localization: relocalize(s.pending!.observations, "floor-1", "sv-1", "frame-1", 0) });
  assert.equal(s.state, "LOCALIZED");
  assert.ok(s.localization!.residualMeters! < 1e-9, "agreeing markers: zero measured residual");

  // The route request: the same contract the viewer uses; the destination is on floor 2, so it routes on current versions.
  const start = devicePositionInVenue(viewerAt(TRUE_F1, 3, 2), s.localization!.deviceToVenue);
  assert.ok(Math.hypot(start[0] - 3, start[1] - 2, start[2] - 1.4) < 1e-9, "the device is where it stands");
  const request = arRouteRequest({ venueId: "v1", localization: s.localization!, start, destination: { id: "poi-dest", floorId: "floor-2" }, stepFree: true });
  assert.equal(request.accessibility, "STEP_FREE");
  assert.equal(request.scanVersionId, undefined);
  const leg = routeLegFor(ROUTE, s.localization!);
  assert.ok(leg.ok);

  // Walk floor 1's leg; halfway along, a limited spell (position emulated) that recovers in place.
  let progress: RouteProgress | null = null;
  const walk: [number, number][] = [[3, 2], [5, 2], [7, 2], [8, 3], [8, 5], [8, 6.5]];
  let previousRemaining = Infinity;
  walk.forEach(([x, y], i) => {
    if (i === 3) {
      dispatch({ type: "TRACKING_LIMITED", reason: "position is emulated", atMs: 1000 });
      const frozen = advanceProgress(progress, canAdvanceRoute(s), leg.ok ? leg.points : [], devicePositionInVenue(viewerAt(TRUE_F1, x, y), s.localization!.deviceToVenue));
      assert.equal(frozen, progress, "progress is frozen while limited");
      assert.ok(routeShown(s), "the last valid overlay stays drawn");
      dispatch({ type: "TRACKING_RESTORED", anchorJumpMeters: 0.02 });
      assert.equal(s.state, "LOCALIZED");
    }
    const device = devicePositionInVenue(viewerAt(TRUE_F1, x, y), s.localization!.deviceToVenue);
    progress = advanceProgress(progress, canAdvanceRoute(s), leg.ok ? leg.points : [], device);
    assert.ok(progress!.offRouteMeters < 1e-6, `on the route at step ${i}`);
    assert.ok(progress!.remainingMeters < previousRemaining, "waypoint progression only moves forward along the route");
    previousRemaining = progress!.remainingMeters;
  });

  // Within 2 m of the elevator: hand off to floor 2. The floor-1 transform is dropped, never carried over.
  const handoff = floorHandoff(ROUTE, "floor-1", progress!.remainingMeters);
  assert.deepEqual(handoff, { toFloorId: "floor-2", connectorType: "ELEVATOR" });
  dispatch({ type: "FLOOR_HANDOFF", toFloorId: handoff!.toFloorId });
  assert.equal(s.state, "FLOOR_TRANSITION");
  assert.equal(s.localization, null);

  // On floor 2 a floor-1 marker identifies nothing; a floor-2 marker localizes in floor 2's own version and frame.
  dispatch({ type: "MARKERS_OBSERVED", observations: observe(TRUE_F2, ["a3"]) });
  assert.equal(s.state, "FLOOR_TRANSITION");
  dispatch({ type: "MARKERS_OBSERVED", observations: observe(TRUE_F2, ["b1"]) });
  dispatch({ type: "RELOCALIZATION_SUCCEEDED", requestId: s.pending!.requestId,
    localization: relocalize(s.pending!.observations, "floor-2", "sv-2", "frame-2", 6000) });
  assert.equal(s.state, "LOCALIZED");
  assert.equal(s.localization!.residualMeters, null, "one marker: residual unknown, not zero");
  const leg2 = routeLegFor(ROUTE, s.localization!);
  assert.ok(leg2.ok && leg2.points.length === 2);
  const there = devicePositionInVenue(viewerAt(TRUE_F2, -3, 4), s.localization!.deviceToVenue);
  assert.ok(Math.hypot(there[0] + 3, there[1] - 4) < 1e-9, "floor 2 positions are in floor 2's frame");

  // A same-floor destination is routed on the localization's scan version.
  const local = arRouteRequest({ venueId: "v1", localization: s.localization!, start: there, destination: { id: "poi-2", floorId: "floor-2" }, stepFree: false });
  assert.equal(local.scanVersionId, "sv-2");
  assert.equal(local.accessibility, "STANDARD");
});

test("a moved marker: the server refuses two that disagree, sets one aside among three, and drift catches it while localized", () => {
  const moved = { b2: { ...F2_MARKERS.b2, x: F2_MARKERS.b2.x + 1.0 } };
  // Two in view, one moved: refused, never averaged; the session stays where it was and retries.
  let s = reduceArSession(reduceArSession(initialArSessionState(), { type: "SESSION_STARTED", floorId: "floor-2" }), { type: "TRACKING_RESTORED" });
  s = reduceArSession(s, { type: "MARKERS_OBSERVED", observations: observe(TRUE_F2, ["b1", "b2"], moved) });
  let error: unknown = null;
  try {
    relocalize(s.pending!.observations, "floor-2", "sv-2", "frame-2", 0);
  } catch (e) {
    error = e;
  }
  const failure = classifyArFailure(error);
  assert.equal(failure.failure, "ANCHORS_INCONSISTENT");
  assert.ok(failure.retryable);
  s = reduceArSession(s, { type: "RELOCALIZATION_FAILED", requestId: s.pending!.requestId, error: failure.detail });
  assert.equal(s.state, "SEARCHING");

  // Three in view: the moved one is set aside, the others localize exactly.
  s = reduceArSession(s, { type: "MARKERS_OBSERVED", observations: observe(TRUE_F2, ["b1", "b2", "b3"], moved) });
  const loc = relocalize(s.pending!.observations, "floor-2", "sv-2", "frame-2", 0);
  assert.deepEqual(loc.anchorIds, ["b1", "b3"]);
  s = reduceArSession(s, { type: "RELOCALIZATION_SUCCEEDED", requestId: s.pending!.requestId, localization: loc });
  assert.equal(s.state, "LOCALIZED");

  // Seen again later while localized, the moved print is caught by the drift check and the transform is dropped.
  const [again] = observe(TRUE_F2, ["b2"], moved);
  const drift = markerDrift(s.localization!.deviceToVenue, again.observedPose, DIGITAL.b2);
  assert.ok(Math.abs(drift.translationMeters - 1.0) < 1e-9);
  assert.ok(driftExceeded(drift));
  s = reduceArSession(s, { type: "DRIFT_EXCEEDED", reason: "b2 is 1.00 m from where the transform puts it" });
  assert.equal(s.state, "TRACKING_LOST");
});
