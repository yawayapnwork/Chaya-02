import assert from "node:assert/strict";
import { test } from "node:test";
import { type Pose, compose, deviceToVenueFromAnchor } from "./ar-anchor-math.ts";
import { gravityTiltDegrees } from "./ar-frame-boundary.ts";
import {
  advanceProgress,
  anchorCorrectedDeviceToVenue,
  cameraPointToVenue,
  cameraPointToWorld,
  devicePositionInVenue,
  progressAlong,
  routeLegOnFloor,
  routeToArWorld,
  venueToArWorld,
  worldPointToCamera,
} from "./ar-route.ts";

const close = (a: number[], b: number[], eps = 1e-9) => a.forEach((v, i) => assert.ok(Math.abs(v - b[i]) < eps, `${a} vs ${b}`));
const S = Math.SQRT1_2;
// A marker lying flat on the floor at canonical (5, 2, 0), image axes = canonical axes rotated so its normal (+Y) is +Z.
const DIGITAL: Pose = { x: 5, y: 2, z: 0, qx: S, qy: 0, qz: 0, qw: S };
// The same marker as the device sees it in its AR world (+Y up): 1.5 m below and 2 m in front of the session origin.
const OBSERVED: Pose = { x: 0, y: -1.5, z: -2, qx: 0, qy: 0, qz: 0, qw: 1 };

test("AR world -> canonical, solved from one observation, maps the marker onto its registered pose", () => {
  const T = deviceToVenueFromAnchor(DIGITAL, OBSERVED);
  close(cameraPointToVenue({ x: 0, y: 0, z: 0, qx: 0, qy: 0, qz: 0, qw: 1 }, T, [0, -1.5, -2]), [5, 2, 0]);
  // device -Z (forward) is canonical +Y here, so the session origin, 2 m behind the marker, is at y = 2 - 2 = 0, 1.5 m up
  close(devicePositionInVenue({ x: 0, y: 0, z: 0, qx: 0, qy: 0, qz: 0, qw: 1 }, T), [5, 0, 1.5]);
});

test("camera frame -> AR world -> camera round-trips, and route points round-trip through the AR frame", () => {
  const viewer: Pose = { x: 0.3, y: 1.4, z: -0.5, qx: 0, qy: Math.sin(0.4), qz: 0, qw: Math.cos(0.4) };
  close(worldPointToCamera(viewer, cameraPointToWorld(viewer, [0.1, -0.2, -1])), [0.1, -0.2, -1]);
  const T = deviceToVenueFromAnchor(DIGITAL, OBSERVED);
  const route: [number, number, number][] = [[5, 4, 0], [5, 8, 0], [9, 8, 0]];
  const ar = routeToArWorld(route, T);
  ar.forEach((p, i) => close(cameraPointToVenue({ x: 0, y: 0, z: 0, qx: 0, qy: 0, qz: 0, qw: 1 }, T, p), route[i]));
  ar.forEach((p) => assert.ok(Math.abs(p[1] + 1.5) < 1e-9, "floor-level route points sit on the AR floor (y = -1.5)"));
});

test("the route leg on a floor stops at the floor transition", () => {
  const w = (floorId: string, kind: "START" | "WAYPOINT" | "TRANSITION" | "DESTINATION", x: number) => ({ x, y: 0, z: 0, floorId, kind });
  const leg = routeLegOnFloor({ waypoints: [w("f1", "START", 0), w("f1", "TRANSITION", 3), w("f2", "WAYPOINT", 3), w("f2", "DESTINATION", 6)] }, "f1");
  assert.deepEqual(leg.points, [[0, 0, 0], [3, 0, 0]]);
});

test("progress projects the device onto the route, and is frozen when not localized", () => {
  const route: [number, number, number][] = [[0, 0, 0], [10, 0, 0], [10, 10, 0]];
  const p = progressAlong(route, [4, 1, 1.5])!;
  assert.equal(p.nextIndex, 1);
  assert.ok(Math.abs(p.remainingMeters - 16) < 1e-9 && Math.abs(p.offRouteMeters - 1) < 1e-9);
  assert.equal(advanceProgress(p, false, route, [10, 5, 1.5]), p, "tracking invalid: position does not advance");
  assert.equal(advanceProgress(p, true, route, [10, 5, 1.5])!.nextIndex, 2);
});

const IDENTITY: Pose = { x: 0, y: 0, z: 0, qx: 0, qy: 0, qz: 0, qw: 1 };
const at = (p: Pose, v: [number, number, number]) => { const r = compose(p, { ...IDENTITY, x: v[0], y: v[1], z: v[2] }); return [r.x, r.y, r.z]; };

test("the solved transform is gravity-aligned: AR-world +Y up becomes canonical +Z up", () => {
  assert.ok(gravityTiltDegrees(deviceToVenueFromAnchor(DIGITAL, OBSERVED)) < 1e-6);
});

test("a camera-frame point reaches the venue through the viewer pose and the solved transform", () => {
  const T = deviceToVenueFromAnchor(DIGITAL, OBSERVED);
  // the camera 1.5 m above the session origin, turned 90 degrees left (about +Y): its forward (-Z) is AR-world -X
  const viewer: Pose = { x: 0, y: 0, z: 0, qx: 0, qy: Math.SQRT1_2, qz: 0, qw: Math.SQRT1_2 };
  // 1 m in front of the camera: AR world (-1, 0, 0). AR -X is canonical -X here (T only swaps y/z axes and translates)
  close(cameraPointToVenue(viewer, T, [0, 0, -1]), [4, 0, 1.5]);
});

test("the renderer's venue -> AR world pose places route points exactly where routeToArWorld does", () => {
  const T = deviceToVenueFromAnchor(DIGITAL, { x: 0.4, y: -1.2, z: -2, qx: 0, qy: Math.sin(0.3), qz: 0, qw: Math.cos(0.3) });
  const route: [number, number, number][] = [[5, 4, 0], [7.5, 8, 0.2]];
  const pose = venueToArWorld(T);
  routeToArWorld(route, T).forEach((p, i) => close(at(pose, route[i]), p));
});

test("with no map correction the world anchor leaves the transform unchanged", () => {
  const T = deviceToVenueFromAnchor(DIGITAL, OBSERVED);
  const corrected = anchorCorrectedDeviceToVenue(T, OBSERVED, OBSERVED);
  close([corrected.x, corrected.y, corrected.z], [T.x, T.y, T.z]);
  close(routeToArWorld([[5, 4, 0]], corrected)[0], routeToArWorld([[5, 4, 0]], T)[0]);
});

test("when the platform moves the world anchor, the route moves with it", () => {
  const T = deviceToVenueFromAnchor(DIGITAL, OBSERVED);
  // the platform refines its map: the anchor (and the physical room around it) is now 0.2 m further along +X and
  // rotated 5 degrees about the vertical
  const a = (5 * Math.PI) / 180 / 2;
  const correction: Pose = { x: 0.2, y: 0, z: 0, qx: 0, qy: Math.sin(a), qz: 0, qw: Math.cos(a) };
  const anchorNow = compose(correction, OBSERVED);
  const corrected = anchorCorrectedDeviceToVenue(T, OBSERVED, anchorNow);
  // the marker's registered venue position lands on the anchor's new position
  close(routeToArWorld([[5, 2, 0]], corrected)[0], [anchorNow.x, anchorNow.y, anchorNow.z]);
  // and every route point moves by the same rigid correction
  const point: [number, number, number] = [5, 6, 0];
  close(routeToArWorld([point], corrected)[0], at(correction, routeToArWorld([point], T)[0] as [number, number, number]));
  assert.ok(gravityTiltDegrees(corrected) < 1e-6, "a correction about the vertical keeps the transform gravity-aligned");
});
