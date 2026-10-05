// Unit tests of the AR navigation rules (lib/ar-navigation.ts). These are mathematical fixtures: poses are made up to
// exercise the transforms, not recorded from a device. They show the math and the rules are right; they say nothing
// about how a real WebXR session behaves (docs/ar-android-validation.md).
import assert from "node:assert/strict";
import { test } from "node:test";
import { type Pose, compose, deviceToVenueFromAnchor } from "./ar-anchor-math.ts";
import { gravityTiltDegrees } from "./ar-frame-boundary.ts";
import {
  AR_REQUIRED_FEATURES,
  BLEND_MS,
  DRIFT_LIMIT_METERS,
  HANDOFF_RADIUS_METERS,
  arSessionInit,
  blendedTransform,
  driftExceeded,
  floorHandoff,
  floorsOf,
  markerDrift,
  refreshDue,
  routeLegFor,
} from "./ar-navigation.ts";
import type { Localization } from "./ar-relocalization.ts";
import { devicePositionInVenue, progressAlong, routeToArWorld } from "./ar-route.ts";
import type { RouteResponse } from "./navigation-api.ts";

const S = Math.SQRT1_2;
/** A marker on a wall facing canonical -Y, upright (docs/ar.md "Marker pose convention"): 180 deg about X. */
const MARKER_DIGITAL: Pose = { x: 4, y: 6, z: 1.5, qx: 1, qy: 0, qz: 0, qw: 0 };
/** The same marker as a gravity-aligned (+Y up) device world frame sees it, 2 m in front of the session origin: its
 * normal (+Y) points back at the origin (+Z in the device world), its bottom edge (+Z) points down (-Y): +90 deg about X. */
const MARKER_OBSERVED: Pose = { x: 0.5, y: 0.2, z: -2, qx: S, qy: 0, qz: 0, qw: S };

const solved = (): Pose => deviceToVenueFromAnchor(MARKER_DIGITAL, MARKER_OBSERVED);

function close(actual: number[], expected: number[], eps = 1e-9) {
  actual.forEach((v, i) => assert.ok(Math.abs(v - expected[i]) < eps, `component ${i}: ${v} vs ${expected[i]}`));
}

const LOC: Localization = {
  deviceToVenue: solved(), anchorIds: ["a1"], residualMeters: null, coordinateFrameId: "frame-1", scanVersionId: "sv-1",
  floorId: "floor-1", solvedAtMs: 1000,
};

const ROUTE: RouteResponse = {
  waypoints: [
    { x: 4, y: 5, z: 0, floorId: "floor-1", kind: "START" },
    { x: 8, y: 5, z: 0, floorId: "floor-1", kind: "WAYPOINT" },
    { x: 8, y: 9, z: 0, floorId: "floor-1", kind: "TRANSITION" },
    { x: -2, y: 3, z: 0, floorId: "floor-2", kind: "TRANSITION" },
    { x: -2, y: 8, z: 0, floorId: "floor-2", kind: "DESTINATION" },
  ],
  distanceMeters: 13, estimatedDurationSeconds: 60, accessibilityProfile: "STANDARD", accessibilityConstraintsApplied: [],
  floorTransitions: [{ fromFloorId: "floor-1", toFloorId: "floor-2", connectorType: "ELEVATOR", poiId: "p1", connectionId: "c1",
    toPoiId: "p2", distanceMeters: 0, durationSeconds: 45 }],
  routingSources: [
    { floorId: "floor-1", graphId: "g1", source: "RECAST_NAVMESH", navmeshSha256: null, recastnavigationVersion: null,
      pathMethod: "STRING_PULLED", scanVersionId: "sv-1", coordinateFrameId: "frame-1" },
    { floorId: "floor-2", graphId: "g2", source: "RECAST_NAVMESH", navmeshSha256: null, recastnavigationVersion: null,
      pathMethod: "STRING_PULLED", scanVersionId: "sv-9", coordinateFrameId: "frame-9" },
  ],
};

// ---- the session request ------------------------------------------------------------------------------------------

test("the session requires image tracking and world anchors and never asks for a hit test", () => {
  const init = arSessionInit([], null) as { requiredFeatures: string[]; optionalFeatures: string[] };
  assert.deepEqual(init.requiredFeatures, ["image-tracking", "anchors"]);
  assert.deepEqual([...AR_REQUIRED_FEATURES], ["image-tracking", "anchors"]);
  assert.ok(![...init.requiredFeatures, ...init.optionalFeatures].includes("hit-test"),
    "a surface under a ray identifies no marker, so it can never localize");
});

// ---- the coordinate chain, end to end ------------------------------------------------------------------------------

test("scan frame -> marker -> device pose -> route: waypoints land where the canonical route says", () => {
  const deviceToVenue = solved();
  assert.ok(gravityTiltDegrees(deviceToVenue) < 1e-6, "the solved transform keeps gravity: AR-world +Y is canonical +Z");
  // The marker, mapped through the transform, is exactly its registered pose.
  const back = compose(deviceToVenue, MARKER_OBSERVED);
  close([back.x, back.y, back.z], [MARKER_DIGITAL.x, MARKER_DIGITAL.y, MARKER_DIGITAL.z]);
  // A route waypoint 1.5 m below the marker centre, on the floor in front of the wall (canonical z = 0), lands 1.5 m
  // below the observed marker in the AR world (-Y), and 1 m toward the viewer (+Z in this device world).
  const [onFloor] = routeToArWorld([[4, 5, 0]], deviceToVenue);
  close(onFloor, [0.5, 0.2 - 1.5, -2 + 1]);
  // A waypoint 3 m along canonical +X is 3 m along the AR world's +X (the wall runs left to right as the viewer sees it).
  const [along] = routeToArWorld([[7, 5, 0]], deviceToVenue);
  close(along, [3.5, -1.3, -1]);
  // The device at the session origin is 2 m in front of the wall (canonical y 4), 0.5 m left of the marker (x 3.5), and
  // 0.2 m below its centre (z 1.3).
  const device = devicePositionInVenue({ x: 0, y: 0, z: 0, qx: 0, qy: 0, qz: 0, qw: 1 }, deviceToVenue);
  close(device, [3.5, 4, 1.3]);
});

// ---- route vs localization -----------------------------------------------------------------------------------------

test("a route leg is drawn only when routed in the localization's scan version and frame", () => {
  const ok = routeLegFor(ROUTE, LOC);
  assert.equal(ok.ok, true);
  if (ok.ok) assert.equal(ok.points.length, 3, "this floor's leg ends at its TRANSITION");

  const other = routeLegFor(ROUTE, { ...LOC, scanVersionId: "sv-2" });
  assert.equal(other.ok, false);
  if (!other.ok) assert.equal(other.problem, "VERSION_MISMATCH");

  const recalibrated = routeLegFor(ROUTE, { ...LOC, coordinateFrameId: "frame-2" });
  assert.equal(recalibrated.ok, false);
  if (!recalibrated.ok) assert.equal(recalibrated.problem, "FRAME_MISMATCH");

  const unknown = routeLegFor({ ...ROUTE, routingSources: [] }, LOC);
  assert.equal(unknown.ok, false);
  if (!unknown.ok) assert.equal(unknown.problem, "ROUTE_SOURCE_MISSING", "a route that does not say its version is not drawn");

  const elsewhere = routeLegFor(ROUTE, { ...LOC, floorId: "floor-3" });
  assert.equal(elsewhere.ok, false);
  if (!elsewhere.ok) assert.equal(elsewhere.problem, "NO_LEG_ON_FLOOR");

  // The next floor's leg needs a localization on that floor, in its own version and frame.
  const second = routeLegFor(ROUTE, { ...LOC, floorId: "floor-2", scanVersionId: "sv-9", coordinateFrameId: "frame-9" });
  assert.equal(second.ok, true);
  assert.equal(routeLegFor(ROUTE, { ...LOC, floorId: "floor-2" }).ok, false, "floor 1's version never draws floor 2's leg");
});

// ---- drift -----------------------------------------------------------------------------------------------------------

test("a marker the transform still explains shows no drift; one the map moved under it does", () => {
  const exact = markerDrift(solved(), MARKER_OBSERVED, MARKER_DIGITAL);
  assert.ok(exact.translationMeters < 1e-9 && exact.rotationDegrees < 1e-4);
  assert.equal(driftExceeded(exact), false);

  // The device world shifted 0.1 m: a re-solve smooths over that.
  const small = markerDrift(solved(), { ...MARKER_OBSERVED, x: MARKER_OBSERVED.x + 0.1 }, MARKER_DIGITAL);
  assert.ok(Math.abs(small.translationMeters - 0.1) < 1e-9);
  assert.equal(driftExceeded(small), false);

  // Shifted beyond the limit, or turned 15 degrees about the vertical: the transform is dropped.
  const far = markerDrift(solved(), { ...MARKER_OBSERVED, z: MARKER_OBSERVED.z - (DRIFT_LIMIT_METERS + 0.05) }, MARKER_DIGITAL);
  assert.equal(driftExceeded(far), true);
  const yaw = 15 * Math.PI / 180;
  const turned = compose({ x: 0, y: 0, z: 0, qx: 0, qy: Math.sin(yaw / 2), qz: 0, qw: Math.cos(yaw / 2) }, MARKER_OBSERVED);
  const rotated = markerDrift(solved(), { ...MARKER_OBSERVED, qx: turned.qx, qy: turned.qy, qz: turned.qz, qw: turned.qw }, MARKER_DIGITAL);
  assert.ok(Math.abs(rotated.rotationDegrees - 15) < 1e-6);
  assert.equal(driftExceeded(rotated), true);
});

test("a re-solve is due only some time after the last one, and never while one is in flight", () => {
  assert.equal(refreshDue(LOC, 1000 + 4999, false), false);
  assert.equal(refreshDue(LOC, 1000 + 5000, false), true);
  assert.equal(refreshDue(LOC, 1000 + 5000, true), false);
  assert.equal(refreshDue(null, 1e9, false), false);
});

test("a refreshed transform is blended in for drawing, ending exactly on the new one", () => {
  const target: Pose = { ...solved(), x: solved().x + 0.2 };
  const from = solved();
  assert.deepEqual(blendedTransform(null, target, 0), target);
  const start = blendedTransform({ from, startedAtMs: 100 }, target, 100);
  close([start.x], [from.x]);
  const mid = blendedTransform({ from, startedAtMs: 100 }, target, 100 + BLEND_MS / 2);
  close([mid.x], [from.x + 0.1]);
  const end = blendedTransform({ from, startedAtMs: 100 }, target, 100 + BLEND_MS * 3);
  close([end.x, end.y, end.z], [target.x, target.y, target.z]);
});

// ---- floor handoff --------------------------------------------------------------------------------------------------

test("the floor is handed off only near the end of a leg that ends in a TRANSITION", () => {
  const leg = routeLegFor(ROUTE, LOC);
  assert.ok(leg.ok);
  if (!leg.ok) return;
  const far = progressAlong(leg.points, [6, 5, 1.3])!;
  assert.equal(floorHandoff(ROUTE, "floor-1", far.remainingMeters), null);
  const near = progressAlong(leg.points, [8, 7.5, 1.3])!;
  assert.ok(near.remainingMeters <= HANDOFF_RADIUS_METERS);
  assert.deepEqual(floorHandoff(ROUTE, "floor-1", near.remainingMeters), { toFloorId: "floor-2", connectorType: "ELEVATOR" });
  assert.equal(floorHandoff(ROUTE, "floor-2", 0), null, "the destination floor's leg ends at the DESTINATION");
  assert.equal(floorHandoff(ROUTE, "floor-1", null), null, "no progress (not localized): no handoff");
  assert.deepEqual(floorsOf(ROUTE), ["floor-1", "floor-2"]);
});
