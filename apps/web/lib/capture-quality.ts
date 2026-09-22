/**
 * Real, measurable frame-quality signals for the live capture HUD. Every function here is a pure computation
 * over pixel data (the same shape `CanvasRenderingContext2D.getImageData()` returns) or over plain numbers;
 * nothing is randomised and nothing is invented when a signal cannot be computed (see the `null` cases below).
 *
 * Analysis is meant to run on a small, downsampled copy of the live camera frame (for example 320x240), not the
 * full-resolution video: that keeps every function here fast enough to run every few hundred milliseconds in the
 * browser without a backend round trip per frame. Only the resulting numbers are ever sent to the server.
 */

/** The subset of `ImageData` these functions need, so tests can build fixtures without a DOM or a canvas. */
export interface Frame {
  readonly width: number;
  readonly height: number;
  /** RGBA, 4 bytes per pixel, row-major, same layout as `ImageData.data`. */
  readonly data: Uint8ClampedArray;
}

export interface QualityThresholds {
  /** Variance of the Laplacian below this is treated as out of focus. Tuned for a ~320-wide analysis frame. */
  minBlurVariance: number;
  minBrightnessMean: number;
  maxBrightnessMean: number;
  maxShadowClipFraction: number;
  maxHighlightClipFraction: number;
  /** Mean absolute grayscale difference (0..1) at or below this, between consecutive analysed frames, is a duplicate. */
  duplicateMotionThreshold: number;
  minFeatureCount: number;
  minSpacingMeters: number;
  maxSpacingMeters: number;
}

export const DEFAULT_QUALITY_THRESHOLDS: QualityThresholds = {
  minBlurVariance: 60,
  minBrightnessMean: 40,
  maxBrightnessMean: 215,
  maxShadowClipFraction: 0.35,
  maxHighlightClipFraction: 0.35,
  duplicateMotionThreshold: 0.006,
  minFeatureCount: 25,
  minSpacingMeters: 0.15,
  maxSpacingMeters: 1.5,
};

export type Warning =
  | "BLUR"
  | "UNDEREXPOSED"
  | "OVEREXPOSED"
  | "DUPLICATE_FRAME"
  | "LOW_FEATURE_COUNT"
  | "SPACING_TOO_CLOSE"
  | "SPACING_TOO_FAR";

export interface QualitySample {
  capturedAtMs: number;
  blurScore: number;
  brightnessMean: number;
  shadowClipFraction: number;
  highlightClipFraction: number;
  /** null for the first analysed frame, when there is nothing yet to compare against. */
  motionScore: number | null;
  duplicateFrame: boolean;
  featureCount: number;
  /** null when no position was supplied for this sample. */
  spacingMeters: number | null;
  warnings: Warning[];
}

// ---- grayscale + brightness -------------------------------------------------------------------------------

/** ITU-R BT.601 luma weights: the standard grayscale conversion used by blur- and feature-detection literature. */
export function toGrayscale(frame: Frame): Float32Array {
  const { width, height, data } = frame;
  const gray = new Float32Array(width * height);
  for (let p = 0, i = 0; p < gray.length; p++, i += 4) {
    gray[p] = 0.299 * data[i] + 0.587 * data[i + 1] + 0.114 * data[i + 2];
  }
  return gray;
}

export interface BrightnessStats {
  mean: number;
  shadowClipFraction: number;
  highlightClipFraction: number;
}

/** Exposure sanity check: mean brightness plus the share of near-black and near-white pixels (clipping). */
export function brightnessStats(gray: Float32Array): BrightnessStats {
  if (gray.length === 0) return { mean: 0, shadowClipFraction: 0, highlightClipFraction: 0 };
  let sum = 0;
  let shadows = 0;
  let highlights = 0;
  for (const v of gray) {
    sum += v;
    if (v <= 16) shadows++;
    else if (v >= 240) highlights++;
  }
  return { mean: sum / gray.length, shadowClipFraction: shadows / gray.length, highlightClipFraction: highlights / gray.length };
}

// ---- blur: variance of the Laplacian -----------------------------------------------------------------------

/**
 * The standard "variance of the Laplacian" blur metric: convolve with the discrete Laplacian kernel
 * [[0,1,0],[1,-4,1],[0,1,0]] and take the variance of the response. A sharp image has strong edges and a high
 * variance; a blurred image has weak, smeared edges and a low one. Higher is sharper.
 */
export function laplacianVariance(gray: Float32Array, width: number, height: number): number {
  if (width < 3 || height < 3) return 0;
  const responses = new Float32Array((width - 2) * (height - 2));
  let idx = 0;
  let sum = 0;
  for (let y = 1; y < height - 1; y++) {
    for (let x = 1; x < width - 1; x++) {
      const c = y * width + x;
      const lap = gray[c - width] + gray[c + width] + gray[c - 1] + gray[c + 1] - 4 * gray[c];
      responses[idx++] = lap;
      sum += lap;
    }
  }
  const mean = sum / responses.length;
  let variance = 0;
  for (const r of responses) {
    const d = r - mean;
    variance += d * d;
  }
  return variance / responses.length;
}

// ---- motion / duplicate-frame detection --------------------------------------------------------------------

/**
 * Mean absolute grayscale difference between two frames of the same size, normalised to 0..1. Near zero means
 * the frames are effectively identical (a duplicate/stalled frame); high values mean fast motion (which itself
 * often means motion blur is likely, even before the blur score is checked).
 */
export function frameDifference(prevGray: Float32Array, currGray: Float32Array): number | null {
  if (prevGray.length === 0 || prevGray.length !== currGray.length) return null;
  let sum = 0;
  for (let i = 0; i < currGray.length; i++) {
    sum += Math.abs(currGray[i] - prevGray[i]);
  }
  return sum / currGray.length / 255;
}

// ---- feature richness: FAST corner count (the keypoint stage of ORB) ----------------------------------------

// The 16 offsets of the Bresenham circle of radius 3 around a pixel, in order, as used by the FAST detector.
const FAST_CIRCLE: ReadonlyArray<readonly [number, number]> = [
  [0, -3], [1, -3], [2, -2], [3, -1], [3, 0], [3, 1], [2, 2], [1, 3],
  [0, 3], [-1, 3], [-2, 2], [-3, 1], [-3, 0], [-3, -1], [-2, -2], [-1, -3],
];

/**
 * ORB's keypoint stage is FAST-9 corner detection followed by a Harris-style ranking and BRIEF descriptors for
 * matching between frames. Matching is not needed for a richness signal, only a count, so this implements FAST-9
 * (a pixel is a corner if at least 9 of the 16 points on the surrounding radius-3 circle are all consistently
 * brighter, or all consistently darker, than the centre by more than `threshold`) and stops there: an "ORB-style
 * keypoint count". A blank wall has almost none; a textured, detailed surface has many.
 */
export function fastFeatureCount(gray: Float32Array, width: number, height: number, threshold = 20): number {
  const minRun = 9;
  let count = 0;
  for (let y = 3; y < height - 3; y++) {
    for (let x = 3; x < width - 3; x++) {
      const c = y * width + x;
      if (isFastCorner(gray, width, c, gray[c], threshold, minRun)) count++;
    }
  }
  return count;
}

function isFastCorner(gray: Float32Array, width: number, c: number, center: number, threshold: number, minRun: number): boolean {
  const n = FAST_CIRCLE.length;
  const cls = new Int8Array(n); // 1 = brighter, -1 = darker, 0 = neither
  for (let k = 0; k < n; k++) {
    const [dx, dy] = FAST_CIRCLE[k];
    const v = gray[c + dy * width + dx];
    cls[k] = v - center > threshold ? 1 : center - v > threshold ? -1 : 0;
  }
  return longestCircularRun(cls, 1) >= minRun || longestCircularRun(cls, -1) >= minRun;
}

function longestCircularRun(cls: Int8Array, target: number): number {
  const n = cls.length;
  let best = 0;
  let run = 0;
  // Walk the array twice around so a run that wraps past the end is counted correctly, capped at n.
  for (let i = 0; i < n * 2; i++) {
    if (cls[i % n] === target) {
      run++;
      best = Math.max(best, run);
    } else {
      run = 0;
    }
    if (best >= n) break;
  }
  return Math.min(best, n);
}

// ---- frame spacing ------------------------------------------------------------------------------------------

export interface PlanPosition {
  x: number;
  y: number;
}

/** Straight-line distance since the previous analysed frame's position, or null when either position is unknown. */
export function frameSpacing(prev: PlanPosition | null, current: PlanPosition | null): number | null {
  if (!prev || !current) return null;
  return Math.hypot(current.x - prev.x, current.y - prev.y);
}

// ---- putting it together --------------------------------------------------------------------------------

export interface QualityContext {
  prevGray: Float32Array | null;
  prevPosition: PlanPosition | null;
}

/**
 * Computes every quality signal for one analysed frame and the warnings that follow from `thresholds`. Returns
 * the frame's grayscale buffer too, so the caller can pass it back as `prevGray` for the next frame without
 * recomputing it.
 */
export function assessFrame(
  frame: Frame,
  capturedAtMs: number,
  context: QualityContext,
  currentPosition: PlanPosition | null = null,
  thresholds: QualityThresholds = DEFAULT_QUALITY_THRESHOLDS,
): { sample: QualitySample; gray: Float32Array } {
  const gray = toGrayscale(frame);
  const blurScore = laplacianVariance(gray, frame.width, frame.height);
  const { mean, shadowClipFraction, highlightClipFraction } = brightnessStats(gray);
  const motionScore = context.prevGray ? frameDifference(context.prevGray, gray) : null;
  const duplicateFrame = motionScore !== null && motionScore <= thresholds.duplicateMotionThreshold;
  const featureCount = fastFeatureCount(gray, frame.width, frame.height);
  const spacingMeters = frameSpacing(context.prevPosition, currentPosition);

  const warnings: Warning[] = [];
  if (blurScore < thresholds.minBlurVariance) warnings.push("BLUR");
  if (mean < thresholds.minBrightnessMean || shadowClipFraction > thresholds.maxShadowClipFraction) warnings.push("UNDEREXPOSED");
  if (mean > thresholds.maxBrightnessMean || highlightClipFraction > thresholds.maxHighlightClipFraction) warnings.push("OVEREXPOSED");
  if (duplicateFrame) warnings.push("DUPLICATE_FRAME");
  if (featureCount < thresholds.minFeatureCount) warnings.push("LOW_FEATURE_COUNT");
  if (spacingMeters !== null && spacingMeters < thresholds.minSpacingMeters) warnings.push("SPACING_TOO_CLOSE");
  if (spacingMeters !== null && spacingMeters > thresholds.maxSpacingMeters) warnings.push("SPACING_TOO_FAR");

  const sample: QualitySample = {
    capturedAtMs, blurScore, brightnessMean: mean, shadowClipFraction, highlightClipFraction, motionScore,
    duplicateFrame, featureCount, spacingMeters, warnings,
  };
  return { sample, gray };
}
