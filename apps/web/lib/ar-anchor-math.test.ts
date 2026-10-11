import assert from "node:assert/strict";
import { test } from "node:test";
import { compose, deviceToVenueFromAnchor, interpolate, invert, type Pose, solve } from "./ar-anchor-math.ts";

const IDENTITY: Pose = { x: 0, y: 0, z: 0, qx: 0, qy: 0, qz: 0, qw: 1 };
const close = (a: number, b: number, eps = 1e-9) => assert.ok(Math.abs(a - b) <= eps, `${a} !~= ${b}`);

test("identity observation of an anchor at the origin yields identity transform", () => {
  const result = deviceToVenueFromAnchor(IDENTITY, IDENTITY);
  close(result.x, 0);
  close(result.y, 0);
  close(result.z, 0);
  close(result.qw, 1);
});

test("anchor offset in venue frame translates device origin to that offset", () => {
  const anchorInVenue: Pose = { x: 5, y: 0, z: 2, qx: 0, qy: 0, qz: 0, qw: 1 };
  const transform = deviceToVenueFromAnchor(anchorInVenue, IDENTITY);
  close(transform.x, 5);
  close(transform.y, 0);
  close(transform.z, 2);
});

test("device offset from anchor is subtracted out of the transform", () => {
  const observedByDevice: Pose = { x: 0, y: 0, z: -3, qx: 0, qy: 0, qz: 0, qw: 1 };
  const transform = deviceToVenueFromAnchor(IDENTITY, observedByDevice);
  close(transform.x, 0);
  close(transform.y, 0);
  close(transform.z, 3);
});

// ---- multi-anchor solve: the same layout and cases as CoordinateTransformTest.java and AnchorMathTests.swift ------------
// TRUE maps device (+Y up) -> venue (+Z up): +90 deg about X, 30 deg of heading about venue +Z, then (12, -4, 0).

const LIMIT_M = 0.3;
const LIMIT_DEG = 8;
const AXES: Pose = { x: 0, y: 0, z: 0, qx: Math.SQRT1_2, qy: 0, qz: 0, qw: Math.SQRT1_2 };
const HEADING: Pose = { x: 0, y: 0, z: 0, qx: 0, qy: 0, qz: Math.sin(Math.PI / 12), qw: Math.cos(Math.PI / 12) };
const TRUE = compose({ x: 12, y: -4, z: 0, qx: 0, qy: 0, qz: 0, qw: 1 }, compose(HEADING, AXES));
const MARKERS: Pose[] = [
  { x: 10, y: 0, z: 1.5, qx: 1, qy: 0, qz: 0, qw: 0 },
  { x: 16, y: 0, z: 1.5, qx: 1, qy: 0, qz: 0, qw: 0 },
  { x: 13, y: 7, z: 1.2, qx: 0, qy: 1, qz: 0, qw: 0 },
];

function observed(digital: Pose, headingErrorDeg: number): Pose {
  const exact = compose(invert(TRUE), digital);
  const h = (headingErrorDeg * Math.PI) / 360;
  const r = compose({ x: 0, y: 0, z: 0, qx: 0, qy: Math.sin(h), qz: 0, qw: Math.cos(h) }, { ...exact, x: 0, y: 0, z: 0 });
  return { ...exact, qx: r.qx, qy: r.qy, qz: r.qz, qw: r.qw };
}
const shifted = (p: Pose, dx: number, dy: number, dz: number): Pose => ({ ...p, x: p.x + dx, y: p.y + dy, z: p.z + dz });
function map(t: Pose, x: number, y: number, z: number): [number, number, number] {
  const p = compose(t, { x, y, z, qx: 0, qy: 0, qz: 0, qw: 1 });
  return [p.x, p.y, p.z];
}
const dist = (a: number[], b: number[]) => Math.hypot(a[0] - b[0], a[1] - b[1], a[2] - b[2]);

test("solve: agreeing anchors recover the true transform with zero residual", () => {
  const s = solve(MARKERS.map((m) => ({ digitalPose: m, observedPose: observed(m, 0) })), LIMIT_M, LIMIT_DEG);
  assert.ok(s.consistent);
  close(s.maxResidualMeters, 0);
  close(s.maxResidualDegrees, 0, 1e-4);
  assert.ok(dist(map(s.transform, 1, 1.6, -2), map(TRUE, 1, 1.6, -2)) < 1e-9);
});

test("solve: disagreeing anchors report their measured residual per anchor", () => {
  const s = solve([
    { digitalPose: MARKERS[0], observedPose: observed(MARKERS[0], 0) },
    { digitalPose: shifted(MARKERS[1], 0.2, 0, 0), observedPose: observed(MARKERS[1], 0) },
  ], LIMIT_M, LIMIT_DEG);
  assert.ok(s.consistent);
  s.residuals.forEach((r) => close(r.translationMeters, 0.1));
});

test("solve: the solution and its residuals do not depend on where the tracking session started", () => {
  const noise = [1, -1, 0.5];
  const near = MARKERS.map((m, i) => ({ digitalPose: m, observedPose: observed(m, noise[i]) }));
  const far = near.map((p) => ({ digitalPose: p.digitalPose, observedPose: shifted(p.observedPose, 25, 0, -25) }));
  const a = solve(near, LIMIT_M, LIMIT_DEG);
  const b = solve(far, LIMIT_M, LIMIT_DEG);
  assert.ok(dist(map(a.transform, 1, 1.6, -3), map(b.transform, 26, 1.6, -28)) < 1e-9);
  a.residuals.forEach((r, i) => close(b.residuals[i].translationMeters, r.translationMeters));
  assert.ok(b.maxResidualMeters < 0.09);
});

test("solve: a moved anchor among three is set aside and the others still localize", () => {
  const s = solve([
    { digitalPose: MARKERS[0], observedPose: observed(MARKERS[0], 0) },
    { digitalPose: MARKERS[1], observedPose: observed(MARKERS[1], 0) },
    { digitalPose: MARKERS[2], observedPose: observed(shifted(MARKERS[2], 1.2, 0, 0), 0) },
  ], LIMIT_M, LIMIT_DEG);
  assert.ok(s.consistent);
  assert.deepEqual(s.residuals.filter((r) => !r.used).map((r) => r.index), [2]);
  close(s.residuals[2].translationMeters, 1.2, 1e-6);
  assert.ok(dist(map(s.transform, 0, 1.5, 0), map(TRUE, 0, 1.5, 0)) < 1e-9);
});

test("solve: a misidentified anchor with the wrong orientation is set aside", () => {
  const turned = compose(MARKERS[0], { x: 0, y: 0, z: 0, qx: 0, qy: Math.SQRT1_2, qz: 0, qw: Math.SQRT1_2 });
  const s = solve([
    { digitalPose: MARKERS[0], observedPose: observed(turned, 0) },
    { digitalPose: MARKERS[1], observedPose: observed(MARKERS[1], 0) },
    { digitalPose: MARKERS[2], observedPose: observed(MARKERS[2], 0) },
  ], LIMIT_M, LIMIT_DEG);
  assert.deepEqual(s.residuals.filter((r) => !r.used).map((r) => r.index), [0]);
  assert.ok(s.residuals[0].rotationDegrees > 80);
});

test("solve: two anchors that disagree cannot be told apart and are returned inconsistent, not averaged into a fix", () => {
  const s = solve([
    { digitalPose: MARKERS[0], observedPose: observed(MARKERS[0], 0) },
    { digitalPose: MARKERS[1], observedPose: observed(shifted(MARKERS[1], 1, 0, 0), 0) },
  ], LIMIT_M, LIMIT_DEG);
  assert.equal(s.consistent, false);
  close(s.maxResidualMeters, 0.5, 1e-6);
});

test("interpolate at endpoints returns those same anchors", () => {
  const a: Pose = { x: 0, y: 0, z: 0, qx: 0, qy: 0, qz: 0, qw: 1 };
  const b: Pose = { x: 10, y: 0, z: 0, qx: 0, qy: 0, qz: 0, qw: 1 };
  close(interpolate(a, b, 0).x, 0);
  close(interpolate(a, b, 1).x, 10);
  close(interpolate(a, b, 0.5).x, 5);
});

test("interpolate clamps out-of-range t", () => {
  const a: Pose = { x: 0, y: 0, z: 0, qx: 0, qy: 0, qz: 0, qw: 1 };
  const b: Pose = { x: 10, y: 0, z: 0, qx: 0, qy: 0, qz: 0, qw: 1 };
  close(interpolate(a, b, -5).x, 0);
  close(interpolate(a, b, 5).x, 10);
});

test("compose then invert recovers the original point (the identity relocalization depends on)", () => {
  const anchorInVenue: Pose = { x: 3, y: -1, z: 4, qx: 0, qy: 0.7071, qz: 0, qw: 0.7071 };
  const observed: Pose = { x: 1, y: 2, z: -2, qx: 0, qy: 0, qz: 0.3827, qw: 0.9239 };
  const transform = deviceToVenueFromAnchor(anchorInVenue, observed);
  const recomposed = compose(transform, observed);
  close(recomposed.x, anchorInVenue.x, 1e-6);
  close(recomposed.y, anchorInVenue.y, 1e-6);
  close(recomposed.z, anchorInVenue.z, 1e-6);
});

test("invert of identity is identity", () => {
  const result = invert(IDENTITY);
  close(result.x, 0);
  close(result.qw, 1);
});
