import assert from "node:assert/strict";
import { test } from "node:test";
import { blendView, focusView, initialView, sampleIndices, type Vec3 } from "./viewer-camera.ts";

// Synthetic point sets: they exercise the framing rule, they are not a venue.
function room(cx: number, cy: number, w: number, d: number): Vec3[] {
  const out: Vec3[] = [];
  for (let i = 0; i <= 20; i++) for (let j = 0; j <= 20; j++) out.push([cx - w / 2 + (w * i) / 20, cy - d / 2 + (d * j) / 20, (i % 5) * 0.5]);
  return out;
}

test("a venue far from the canonical origin (a survey datum) is framed where it is, not at the origin", () => {
  const v = initialView(room(512.4, -230.1, 12, 8), true)!;
  assert.ok(Math.abs(v.target[0] - 512.4) < 0.7 && Math.abs(v.target[1] + 230.1) < 0.5, `target ${v.target}`);
  assert.deepEqual(v.up, [0, 0, 1]);
  assert.ok(v.position[1] < v.target[1] && v.position[2] > v.target[2], "stands back and looks slightly down");
  const dist = Math.hypot(v.position[0] - v.target[0], v.position[1] - v.target[1]);
  assert.ok(dist >= 0.9 * 9.6 * 0.9, "far enough back for the robust extent");
});

test("floaters do not move the target or stretch the view", () => {
  const pts = room(0, 0, 6, 6);
  const withFloaters = [...pts, [900, 900, 900] as Vec3, [-800, 50, -400] as Vec3];
  const a = initialView(pts, true)!;
  const b = initialView(withFloaters, true)!;
  assert.ok(Math.abs(a.target[0] - b.target[0]) < 0.4 && Math.abs(a.extent - b.extent) < 0.6);
});

test("an uncalibrated scene gets the SfM viewing default around its own content", () => {
  const v = initialView(room(3, 4, 2, 2), false)!;
  assert.deepEqual(v.up, [0, -1, 0]);
  assert.ok(v.position[2] < v.target[2], "behind the content along +Z, as SfM cameras look");
});

test("no finite centres, no view; sampling spans the scene", () => {
  assert.equal(initialView([], true), null);
  assert.equal(initialView([[NaN, 0, 0]], true), null);
  const idx = sampleIndices(1_000_000, 1000);
  assert.equal(idx.length, 1000);
  assert.equal(idx[0], 0);
  assert.ok(idx[999] > 990_000);
  assert.deepEqual(sampleIndices(3), [0, 1, 2]);
});

test("focusing a POI keeps the viewing direction and frames the point at a usable distance", () => {
  const f = focusView([0, -10, 5], [0, 0, 0], [20, 30, 1]);
  assert.deepEqual(f.target, [20, 30, 1]);
  const d = [f.position[0] - 20, f.position[1] - 30, f.position[2] - 1];
  const len = Math.hypot(d[0], d[1], d[2]);
  assert.ok(Math.abs(len - 4) < 1e-9, `distance ${len}`);
  const was = [0, -10, 5].map((v) => v / Math.hypot(10, 5));
  assert.ok(d.every((v, k) => Math.abs(v / len - was[k]) < 1e-9), "same bearing and elevation");
});

test("focusing from straight above tilts back so the point stays readable", () => {
  const f = focusView([0, 0, 10], [0, 0, 0], [1, 1, 0]);
  const elevation = Math.asin((f.position[2] - 0) / 4);
  assert.ok(Math.abs(elevation - (35 * Math.PI) / 180) < 1e-9);
});

test("a camera transition starts and ends exactly at its two views", () => {
  const a = { position: [0, 0, 0] as Vec3, target: [1, 1, 1] as Vec3 };
  const b = { position: [10, 0, 0] as Vec3, target: [2, 2, 2] as Vec3 };
  assert.deepEqual(blendView(a, b, 0), a);
  assert.deepEqual(blendView(a, b, 1), b);
  assert.deepEqual(blendView(a, b, 5), b);
  assert.equal(blendView(a, b, 0.5).position[0], 5);
});
