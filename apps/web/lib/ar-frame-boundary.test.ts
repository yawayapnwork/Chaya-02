import assert from "node:assert/strict";
import { test } from "node:test";
import type { Pose } from "./ar-anchor-math.ts";
import {
  DEVICE_TO_CANONICAL_AXES,
  canonicalAxesToDevice,
  deviceAxesToCanonical,
  devicePointToVenue,
  gravityTiltDegrees,
  venuePointToDevice,
} from "./ar-frame-boundary.ts";
import { type Vec3, multiplyQuaternions } from "./coordinate-frame.ts";

const closeVec = (a: Vec3, b: Vec3, eps = 1e-12) =>
  a.forEach((v, i) => assert.ok(Math.abs(v - b[i]) <= eps, `${a} !~= ${b}`));

test("device +Y (up) is canonical +Z; the conversion is a proper rotation", () => {
  closeVec(deviceAxesToCanonical([0, 1, 0]), [0, 0, 1]);
  closeVec(deviceAxesToCanonical([1, 0, 0]), [1, 0, 0]);
  closeVec(deviceAxesToCanonical([0, 0, 1]), [0, -1, 0]);
  closeVec(canonicalAxesToDevice(deviceAxesToCanonical([0.3, -2, 7])), [0.3, -2, 7]);
});

function transform(headingDeg: number, t: Vec3): Pose {
  const h = (headingDeg * Math.PI) / 360;
  const q = multiplyQuaternions({ w: Math.cos(h), x: 0, y: 0, z: Math.sin(h) }, DEVICE_TO_CANONICAL_AXES);
  return { x: t[0], y: t[1], z: t[2], qx: q.x, qy: q.y, qz: q.z, qw: q.w };
}

test("a gravity-respecting device-to-venue transform has no tilt; ignoring the convention is 90 degrees off", () => {
  assert.ok(gravityTiltDegrees(transform(40, [3, -1, 0.2])) < 1e-9);
  assert.ok(Math.abs(gravityTiltDegrees({ x: 0, y: 0, z: 0, qx: 0, qy: 0, qz: 0, qw: 1 }) - 90) < 1e-9);
});

test("venue points round-trip through device coordinates, and device height becomes venue z", () => {
  const t = transform(-25, [10, 4, 0]);
  const p: Vec3 = [12.5, 3, 1.6];
  closeVec(devicePointToVenue(t, venuePointToDevice(t, p)), p, 1e-12);
  // A point 1.6 m above the device origin (device +Y) is 1.6 m above the venue origin's height.
  closeVec(devicePointToVenue(t, [0, 1.6, 0]), [10, 4, 1.6], 1e-12);
});
