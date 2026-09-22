import assert from "node:assert/strict";
import { test } from "node:test";
import {
  DEFAULT_QUALITY_THRESHOLDS,
  assessFrame,
  brightnessStats,
  fastFeatureCount,
  frameDifference,
  frameSpacing,
  laplacianVariance,
  toGrayscale,
  type Frame,
} from "./capture-quality.ts";

/** Builds a grayscale RGBA fixture frame (R=G=B=value(x,y), alpha 255) — the real shape getImageData() returns. */
function frame(width: number, height: number, value: (x: number, y: number) => number): Frame {
  const data = new Uint8ClampedArray(width * height * 4);
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const v = value(x, y);
      const i = (y * width + x) * 4;
      data[i] = v;
      data[i + 1] = v;
      data[i + 2] = v;
      data[i + 3] = 255;
    }
  }
  return { width, height, data };
}

const uniform = (w: number, h: number, v: number) => frame(w, h, () => v);
const checkerboard = (w: number, h: number, block = 4) => frame(w, h, (x, y) => (Math.floor(x / block) + Math.floor(y / block)) % 2 === 0 ? 20 : 235);

/**
 * Isolated bright squares on a dark background, spaced apart. Unlike a checkerboard's 4-way junctions (a known
 * FAST "saddle point" blind spot: the circle around a junction sees two colours in four alternating arcs, none
 * of them a single contiguous run), each square's corner is a real, isolated right-angle turn against a uniform
 * background, which is exactly what FAST is designed to find.
 */
const blobs = (w: number, h: number, size = 6, period = 14) =>
  frame(w, h, (x, y) => (x % period < size && y % period < size ? 235 : 20));

test("toGrayscale applies the BT.601 luma weights", () => {
  const red: Frame = { width: 1, height: 1, data: new Uint8ClampedArray([255, 0, 0, 255]) };
  const white: Frame = { width: 1, height: 1, data: new Uint8ClampedArray([255, 255, 255, 255]) };
  assert.ok(Math.abs(toGrayscale(red)[0] - 76.245) < 0.01);
  assert.equal(toGrayscale(white)[0], 255);
});

test("laplacianVariance: a checkerboard is sharp, a flat frame is not", () => {
  const flat = uniform(32, 32, 128);
  const sharp = checkerboard(32, 32);
  const flatScore = laplacianVariance(toGrayscale(flat), 32, 32);
  const sharpScore = laplacianVariance(toGrayscale(sharp), 32, 32);
  assert.equal(flatScore, 0);
  assert.ok(sharpScore > DEFAULT_QUALITY_THRESHOLDS.minBlurVariance * 10, `expected a strongly textured frame to score high, got ${sharpScore}`);
});

test("laplacianVariance treats a smoothed (blurred) checkerboard as softer than the original", () => {
  const w = 32, h = 32;
  const sharp = toGrayscale(checkerboard(w, h));
  // A 3x3 box blur: a cheap, real approximation of what an out-of-focus camera does to the same scene.
  const blurred = new Float32Array(sharp.length);
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      let sum = 0, n = 0;
      for (let dy = -1; dy <= 1; dy++) {
        for (let dx = -1; dx <= 1; dx++) {
          const nx = x + dx, ny = y + dy;
          if (nx >= 0 && ny >= 0 && nx < w && ny < h) {
            sum += sharp[ny * w + nx];
            n++;
          }
        }
      }
      blurred[y * w + x] = sum / n;
    }
  }
  assert.ok(laplacianVariance(blurred, w, h) < laplacianVariance(sharp, w, h));
});

test("brightnessStats: black, white and mid-grey frames", () => {
  assert.deepEqual(brightnessStats(toGrayscale(uniform(4, 4, 0))), { mean: 0, shadowClipFraction: 1, highlightClipFraction: 0 });
  assert.deepEqual(brightnessStats(toGrayscale(uniform(4, 4, 255))), { mean: 255, shadowClipFraction: 0, highlightClipFraction: 1 });
  const mid = brightnessStats(toGrayscale(uniform(4, 4, 128)));
  assert.equal(mid.mean, 128);
  assert.equal(mid.shadowClipFraction, 0);
  assert.equal(mid.highlightClipFraction, 0);
});

test("frameDifference: identical frames are zero, differing frames are not, mismatched sizes are null", () => {
  const a = toGrayscale(checkerboard(16, 16));
  const b = toGrayscale(checkerboard(16, 16));
  const invertedA = toGrayscale(uniform(16, 16, 235));
  assert.equal(frameDifference(a, b), 0);
  assert.ok(frameDifference(a, invertedA)! > 0);
  assert.equal(frameDifference(a, toGrayscale(checkerboard(8, 8))), null);
});

test("fastFeatureCount: a frame with real corners has far more keypoints than a flat one", () => {
  const flat = fastFeatureCount(toGrayscale(uniform(64, 64, 128)), 64, 64);
  const textured = fastFeatureCount(toGrayscale(blobs(64, 64)), 64, 64);
  assert.equal(flat, 0);
  assert.ok(textured > 10, `expected several corners on isolated squares, got ${textured}`);
});

test("frameSpacing: distance between positions, or null when either is unknown", () => {
  assert.equal(frameSpacing(null, { x: 0, y: 0 }), null);
  assert.equal(frameSpacing({ x: 0, y: 0 }, null), null);
  assert.equal(frameSpacing({ x: 0, y: 0 }, { x: 3, y: 4 }), 5);
});

test("assessFrame: first frame has no motion score and is never flagged a duplicate", () => {
  const { sample } = assessFrame(checkerboard(64, 64), 1000, { prevGray: null, prevPosition: null });
  assert.equal(sample.motionScore, null);
  assert.equal(sample.duplicateFrame, false);
});

test("assessFrame: an identical second frame is flagged as a duplicate", () => {
  const first = assessFrame(checkerboard(64, 64), 1000, { prevGray: null, prevPosition: null });
  const second = assessFrame(checkerboard(64, 64), 1100, { prevGray: first.gray, prevPosition: null });
  assert.equal(second.sample.duplicateFrame, true);
  assert.ok(second.sample.warnings.includes("DUPLICATE_FRAME"));
});

test("assessFrame: a flat, low-texture frame is flagged for blur and low feature count, not a detailed one", () => {
  const flat = assessFrame(uniform(64, 64, 128), 1000, { prevGray: null, prevPosition: null });
  assert.ok(flat.sample.warnings.includes("BLUR"));
  assert.ok(flat.sample.warnings.includes("LOW_FEATURE_COUNT"));

  const sharp = assessFrame(checkerboard(64, 64, 6), 1000, { prevGray: null, prevPosition: null });
  assert.ok(!sharp.sample.warnings.includes("BLUR"));

  const detailed = assessFrame(blobs(64, 64), 1000, { prevGray: null, prevPosition: null });
  assert.ok(!detailed.sample.warnings.includes("LOW_FEATURE_COUNT"));
});

test("assessFrame: exposure warnings for very dark and very bright frames", () => {
  const dark = assessFrame(uniform(32, 32, 5), 1000, { prevGray: null, prevPosition: null });
  assert.ok(dark.sample.warnings.includes("UNDEREXPOSED"));
  const bright = assessFrame(uniform(32, 32, 250), 1000, { prevGray: null, prevPosition: null });
  assert.ok(bright.sample.warnings.includes("OVEREXPOSED"));
});

test("assessFrame: spacing warnings only fire when a position is supplied", () => {
  const noPosition = assessFrame(checkerboard(32, 32), 1000, { prevGray: null, prevPosition: null }, null);
  assert.equal(noPosition.sample.spacingMeters, null);
  assert.ok(!noPosition.sample.warnings.some((w) => w.startsWith("SPACING")));

  const tooClose = assessFrame(checkerboard(32, 32), 1000, { prevGray: null, prevPosition: { x: 0, y: 0 } }, { x: 0.01, y: 0 });
  assert.ok(tooClose.sample.warnings.includes("SPACING_TOO_CLOSE"));

  const tooFar = assessFrame(checkerboard(32, 32), 1000, { prevGray: null, prevPosition: { x: 0, y: 0 } }, { x: 5, y: 0 });
  assert.ok(tooFar.sample.warnings.includes("SPACING_TOO_FAR"));
});
