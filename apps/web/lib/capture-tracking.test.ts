import assert from "node:assert/strict";
import { test } from "node:test";
import {
  LOST_AFTER_MS,
  PoseThrottle,
  TrackingMonitor,
  alignmentFromCorrespondence,
  applyAlignment,
  applyAlignmentToPose,
  cameraHeadingFromEuler,
  classifyHit,
  headingToPlanYaw,
  motionBetween,
  rotateVec,
  wrapDegrees,
  xrPoseToPlan,
} from "./capture-tracking.ts";

const close = (a: number, b: number, eps = 1e-9) => assert.ok(Math.abs(a - b) < eps, `${a} vs ${b}`);
/** Unit quaternion for a rotation of `deg` about the device +Y (up) axis. */
const yawQ = (deg: number) => ({ x: 0, y: Math.sin((deg * Math.PI) / 360), z: 0, w: Math.cos((deg * Math.PI) / 360) });
/** Rotation about +X (pitch). */
const pitchQ = (deg: number) => ({ x: Math.sin((deg * Math.PI) / 360), y: 0, z: 0, w: Math.cos((deg * Math.PI) / 360) });

test("rotateVec: a quarter turn about +Y sends -Z (forward) to -X", () => {
  const v = rotateVec(yawQ(90), { x: 0, y: 0, z: -1 });
  close(v.x, -1);
  close(v.y, 0);
  close(v.z, 0);
});

test("xrPoseToPlan: the session's initial forward is plan +y (90 deg), and turning left is counter-clockwise", () => {
  const p = xrPoseToPlan({ x: 1.5, y: 1.6, z: -2 }, yawQ(0));
  close(p.x, 1.5);
  close(p.y, 2);
  close(p.yawDegrees!, 90);
  close(xrPoseToPlan({ x: 0, y: 0, z: 0 }, yawQ(90)).yawDegrees!, 180); // turned left: facing plan -x
  close(xrPoseToPlan({ x: 0, y: 0, z: 0 }, yawQ(-90)).yawDegrees!, 0);
});

test("xrPoseToPlan: a camera pointing at the floor has no heading, rather than an arbitrary one", () => {
  assert.equal(xrPoseToPlan({ x: 0, y: 1, z: 0 }, pitchQ(-89)).yawDegrees, null);
  assert.notEqual(xrPoseToPlan({ x: 0, y: 1, z: 0 }, pitchQ(-30)).yawDegrees, null);
});

test("classifyHit: walls by a horizontal normal, the floor below the camera by an upward one, the rest is ignored", () => {
  const viewer = { x: 0, y: 1.5, z: 0 };
  // A wall 3 m ahead (device -Z) facing the camera: normal +Z, i.e. the hit pose's +Y rotated onto +Z (pitch +90).
  const wall = classifyHit(viewer, { x: 0, y: 1.2, z: -3 }, pitchQ(90));
  assert.equal(wall?.kind, "WALL");
  close(wall!.hitX, 0);
  close(wall!.hitY, 3);
  close(wall!.normalDegrees!, -90); // faces plan -y: back towards the camera
  const floor = classifyHit(viewer, { x: 0, y: 0, z: -2 }, { x: 0, y: 0, z: 0, w: 1 });
  assert.equal(floor?.kind, "FLOOR");
  assert.equal(classifyHit(viewer, { x: 0, y: 0.75, z: -1 }, pitchQ(45)), null, "slanted surface");
  assert.equal(classifyHit(viewer, { x: 0, y: 1.3, z: -1 }, { x: 0, y: 0, z: 0, w: 1 }), null, "a table top at camera height is not floor");
  assert.equal(classifyHit(viewer, { x: Number.NaN, y: 0, z: 0 }, pitchQ(90)), null);
});

const tracked = { position: { x: 0, y: 1.5, z: 0 }, orientation: yawQ(0), emulatedPosition: false };

test("TrackingMonitor: emits only transitions; one dropped frame is not a loss; emulated positions are LIMITED", () => {
  const m = new TrackingMonitor();
  assert.equal(m.observe({ timeMs: 0, pose: tracked, visibility: "visible" }).event, "TRACKING");
  assert.equal(m.observe({ timeMs: 16, pose: tracked, visibility: "visible" }).event, null);
  assert.equal(m.observe({ timeMs: 32, pose: null, visibility: "visible" }).event, null, "a single missing pose");
  assert.equal(m.observe({ timeMs: 32 + LOST_AFTER_MS, pose: null, visibility: "visible" }).event, "LOST");
  assert.equal(m.observe({ timeMs: 900, pose: { ...tracked, emulatedPosition: true }, visibility: "visible" }).event, "LIMITED");
  assert.equal(m.observe({ timeMs: 950, pose: tracked, visibility: "visible" }).event, "TRACKING");
  assert.equal(m.observe({ timeMs: 1000, pose: null, visibility: "hidden" }).event, "INTERRUPTED");
  assert.equal(m.observe({ timeMs: 1100, pose: tracked, visibility: "visible" }).event, "RESUMED");
  assert.equal(m.state, "TRACKING");
});

test("PoseThrottle: at most 5 Hz, and only when moved, turned, or a second has passed", () => {
  const t = new PoseThrottle();
  assert.ok(t.accept({ x: 0, y: 0, yawDegrees: 0 }, 0));
  assert.ok(!t.accept({ x: 1, y: 0, yawDegrees: 0 }, 100), "too soon");
  assert.ok(!t.accept({ x: 0.05, y: 0, yawDegrees: 2 }, 300), "neither moved nor turned");
  assert.ok(t.accept({ x: 0.2, y: 0, yawDegrees: 2 }, 400), "moved 20 cm");
  assert.ok(t.accept({ x: 0.2, y: 0, yawDegrees: 20 }, 650), "turned 18 deg");
  assert.ok(t.accept({ x: 0.2, y: 0, yawDegrees: 20 }, 1700), "a keep-alive after a second standing still");
});

test("motionBetween: flags walking and turning too fast, from tracked poses only", () => {
  const m = motionBetween({ x: 0, y: 0, yawDegrees: 0, timeMs: 0 }, { x: 0.6, y: 0, yawDegrees: 45, timeMs: 400 })!;
  close(m.speedMps, 1.5);
  close(m.turnRateDps!, 112.5);
  assert.deepEqual(m.warnings, ["MOVING_TOO_FAST", "TURNING_TOO_FAST"]);
  assert.deepEqual(motionBetween({ x: 0, y: 0, yawDegrees: null, timeMs: 0 }, { x: 0.2, y: 0, yawDegrees: 10, timeMs: 400 })!.warnings, []);
  assert.equal(motionBetween({ x: 0, y: 0, yawDegrees: 0, timeMs: 0 }, { x: 0, y: 0, yawDegrees: 0, timeMs: 10 }), null);
});

test("alignmentFromCorrespondence: maps the confirmed device pose exactly onto the plan pose, and moves others rigidly", () => {
  const plan = { x: 4, y: 2, yawDegrees: 90 };
  const device = { x: -1, y: 0.5, yawDegrees: 0 };
  const a = alignmentFromCorrespondence(plan, device);
  const at = applyAlignmentToPose(a, device);
  close(at.x, 4);
  close(at.y, 2);
  close(at.yawDegrees!, 90);
  // One metre "ahead" in the device frame (+x there) is one metre ahead in the plan (+y here).
  const ahead = applyAlignment(a, { x: 0, y: 0.5 });
  close(ahead.x, 4);
  close(ahead.y, 3);
  close(wrapDegrees(370), 10);
});

test("cameraHeadingFromEuler: the rear camera's direction, independent of how the screen is rotated", () => {
  close(cameraHeadingFromEuler(0, 90, 0)!, 0, 1e-6); // upright, facing the reference direction
  close(cameraHeadingFromEuler(90, 90, 0)!, 270, 1e-6); // alpha grows counter-clockwise
  close(cameraHeadingFromEuler(0, 90, 0)!, cameraHeadingFromEuler(0, 90, 0)!);
  assert.equal(cameraHeadingFromEuler(0, 0, 0), null, "flat on a table: the camera faces the floor");
});

test("headingToPlanYaw: needs a calibration against the plan's +x axis, never assumes one", () => {
  assert.equal(headingToPlanYaw(120, null), null);
  close(headingToPlanYaw(30, 30)!, 0);
  close(headingToPlanYaw(0, 30)!, 30); // turned 30 deg counter-clockwise from +x
  close(headingToPlanYaw(120, 30)!, -90);
});
