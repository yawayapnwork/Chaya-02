import assert from "node:assert/strict";
import { test } from "node:test";
import { DEFAULT_FRAME_QUALITY as O, initialFrameQuality, recordFrame } from "./frame-quality.ts";

const run = (state: ReturnType<typeof initialFrameQuality>, ms: number, n: number) => {
  for (let i = 0; i < n; i++) state = recordFrame(state, ms);
  return state;
};

test("starts at the device's pixel ratio and changes nothing before a full window is measured", () => {
  const s = run(initialFrameQuality(2), 80, O.windowFrames - 1);
  assert.equal(s.pixelRatio, 2);
  assert.equal(s.medianMs, null);
});

test("slow frames lower the render scale step by step, never below the floor", () => {
  let s = run(initialFrameQuality(2), 80, O.windowFrames);
  assert.equal(s.pixelRatio, 1.7);
  assert.equal(s.medianMs, 80);
  s = run(s, 80, O.windowFrames * 20);
  assert.equal(s.pixelRatio, O.minScale);
});

test("sustained fast frames raise it again, never above the device's pixel ratio", () => {
  let s = run(initialFrameQuality(2), 80, O.windowFrames * 3);
  assert.ok(s.pixelRatio < 2);
  s = run(s, 10, O.windowFrames * 20);
  assert.equal(s.pixelRatio, 2);
});

test("frame times between the thresholds hold the scale (hysteresis)", () => {
  const s = run({ ...initialFrameQuality(2), pixelRatio: 1.4 }, 30, O.windowFrames * 5);
  assert.equal(s.pixelRatio, 1.4);
});

test("a stall (hidden tab, pause) discards the window instead of counting as slow rendering", () => {
  let s = run(initialFrameQuality(1), 80, O.windowFrames - 1);
  s = recordFrame(s, 5000);
  assert.equal(s.frames.length, 0);
  assert.equal(s.pixelRatio, 1);
});

test("a device that ignores its pixel ratio (max 1) is never pushed above 1", () => {
  const s = run(initialFrameQuality(1), 5, O.windowFrames * 10);
  assert.equal(s.pixelRatio, 1);
});
