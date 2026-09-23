import assert from "node:assert/strict";
import { test } from "node:test";
import { blend, compose, deviceToVenueFromAnchor, interpolate, invert, type Pose } from "./ar-anchor-math.ts";

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

test("blend of agreeing anchors has zero residual", () => {
  const a: Pose = { x: 1, y: 2, z: 3, qx: 0, qy: 0, qz: 0, qw: 1 };
  const b: Pose = { x: 1, y: 2, z: 3, qx: 0, qy: 0, qz: 0, qw: 1 };
  const result = blend([a, b]);
  close(result.residualMeters, 0);
  close(result.transform.x, 1);
});

test("blend of disagreeing anchors reports real residual, not one arbitrarily picked anchor", () => {
  const a: Pose = { x: 0, y: 0, z: 0, qx: 0, qy: 0, qz: 0, qw: 1 };
  const b: Pose = { x: 0.2, y: 0, z: 0, qx: 0, qy: 0, qz: 0, qw: 1 };
  const result = blend([a, b]);
  close(result.residualMeters, 0.2);
  close(result.transform.x, 0.1);
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
