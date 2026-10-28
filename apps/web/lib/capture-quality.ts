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
  /** Mean absolute grayscale difference (0..1) above this, between consecutive analysed frames, flags visual FAST_MOTION. */
  maxMotionScore: number;
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
  maxMotionScore: 0.18,
  minFeatureCount: 25,
  minSpacingMeters: 0.15,
  maxSpacingMeters: 1.5,
};

export type Warning =
  | "BLUR"
  | "UNDEREXPOSED"
  | "OVEREXPOSED"
  | "DUPLICATE_FRAME"
  | "FAST_MOTION"
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
  const fastVisualMotion = motionScore !== null && motionScore > thresholds.maxMotionScore;
  const featureCount = fastFeatureCount(gray, frame.width, frame.height);
  const spacingMeters = frameSpacing(context.prevPosition, currentPosition);

  const warnings: Warning[] = [];
  if (blurScore < thresholds.minBlurVariance) warnings.push("BLUR");
  if (mean < thresholds.minBrightnessMean || shadowClipFraction > thresholds.maxShadowClipFraction) warnings.push("UNDEREXPOSED");
  if (mean > thresholds.maxBrightnessMean || highlightClipFraction > thresholds.maxHighlightClipFraction) warnings.push("OVEREXPOSED");
  if (duplicateFrame) warnings.push("DUPLICATE_FRAME");
  if (fastVisualMotion) warnings.push("FAST_MOTION");
  if (featureCount < thresholds.minFeatureCount) warnings.push("LOW_FEATURE_COUNT");
  if (spacingMeters !== null && spacingMeters < thresholds.minSpacingMeters) warnings.push("SPACING_TOO_CLOSE");
  if (spacingMeters !== null && spacingMeters > thresholds.maxSpacingMeters) warnings.push("SPACING_TOO_FAR");

  const sample: QualitySample = {
    capturedAtMs, blurScore, brightnessMean: mean, shadowClipFraction, highlightClipFraction, motionScore,
    duplicateFrame, featureCount, spacingMeters, warnings,
  };
  return { sample, gray };
}

// ---- Deterministic calibration & confusion-matrix evaluation (Requirement 6) ----------------------------------------

export interface ConfusionMatrix {
  truePositives: number;
  falsePositives: number;
  trueNegatives: number;
  falseNegatives: number;
  falsePositiveRate: number;
  falseNegativeRate: number;
  precision: number;
  recall: number;
  cases: {
    id: string;
    expected: boolean;
    predicted: boolean;
    outcome: "TP" | "FP" | "TN" | "FN";
    metricValue: number;
    note: string;
  }[];
}

export interface QualityClassifierBenchmark {
  thresholds: QualityThresholds;
  blur: ConfusionMatrix;
  fastMotion: ConfusionMatrix;
  duplicateFrame: ConfusionMatrix;
}

function summarizeConfusion(cases: ConfusionMatrix["cases"]): ConfusionMatrix {
  let tp = 0, fp = 0, tn = 0, fn = 0;
  for (const c of cases) {
    if (c.outcome === "TP") tp++;
    else if (c.outcome === "FP") fp++;
    else if (c.outcome === "TN") tn++;
    else fn++;
  }
  const fpr = fp + tn > 0 ? Math.round((fp / (fp + tn)) * 10000) / 10000 : 0;
  const fnr = fn + tp > 0 ? Math.round((fn / (fn + tp)) * 10000) / 10000 : 0;
  const precision = tp + fp > 0 ? Math.round((tp / (tp + fp)) * 10000) / 10000 : 1;
  const recall = tp + fn > 0 ? Math.round((tp / (tp + fn)) * 10000) / 10000 : 1;
  return {
    truePositives: tp,
    falsePositives: fp,
    trueNegatives: tn,
    falseNegatives: fn,
    falsePositiveRate: fpr,
    falseNegativeRate: fnr,
    precision,
    recall,
    cases,
  };
}

function makeFrame(width: number, height: number, pixel: (x: number, y: number) => number): Frame {
  const data = new Uint8ClampedArray(width * height * 4);
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const v = Math.max(0, Math.min(255, Math.round(pixel(x, y))));
      const i = (y * width + x) * 4;
      data[i] = v;
      data[i + 1] = v;
      data[i + 2] = v;
      data[i + 3] = 255;
    }
  }
  return { width, height, data };
}

function boxBlurFrame(f: Frame, radius: number): Frame {
  const gray = toGrayscale(f);
  const w = f.width, h = f.height;
  return makeFrame(w, h, (x, y) => {
    let sum = 0, n = 0;
    for (let dy = -radius; dy <= radius; dy++) {
      for (let dx = -radius; dx <= radius; dx++) {
        const nx = Math.min(w - 1, Math.max(0, x + dx));
        const ny = Math.min(h - 1, Math.max(0, y + dy));
        sum += gray[ny * w + nx];
        n++;
      }
    }
    return sum / n;
  });
}

/**
 * Evaluates the calibrated per-frame blur (`BLUR`), visual fast-motion (`FAST_MOTION`), and duplicate-frame
 * (`DUPLICATE_FRAME`) classifiers against a deterministic benchmark corpus per classifier, explicitly
 * recording true positives, false positives, true negatives, and false negatives (including known failure modes
 * of Laplacian variance and normalized L1 frame difference).
 */
export function evaluateBlurAndMotionThresholds(
  thresholds: QualityThresholds = DEFAULT_QUALITY_THRESHOLDS,
): QualityClassifierBenchmark {
  const w = 64, h = 48;
  const sharpChecker = makeFrame(w, h, (x, y) => ((Math.floor(x / 4) + Math.floor(y / 4)) % 2 === 0 ? 35 : 220));
  const sharpBrick = makeFrame(w, h, (x, y) => (x % 8 === 0 || y % 6 === 0 ? 40 : 185));
  const sharpSinusoid = makeFrame(w, h, (x, y) => 128 + 90 * Math.sin(x * 0.9) * Math.cos(y * 0.9));

  // Known false-positive case for Laplacian blur: an in-focus matte wall with only a single thin vertical door seam.
  // Because 98% of the frame is uniform, the global Laplacian variance is below 60 even though the seam is in focus.
  const sharpSparseWall = makeFrame(w, h, (x) => (x === 32 ? 140 : 120));

  // True positives for blur: defocus / motion-blurred indoor textures (repeated box blur simulates severe Gaussian/motion blur).
  const heavyBlurChecker = boxBlurFrame(boxBlurFrame(sharpChecker, 4), 3);
  const heavyBlurBrick = boxBlurFrame(boxBlurFrame(sharpBrick, 3), 3);
  const flatOutOfFocus = makeFrame(w, h, (x, y) => 110 + Math.round(4 * Math.sin(x * 0.1 + y * 0.1)));

  // Known false-negative case for Laplacian blur: high-contrast 1-pixel alternating shutter/blind pattern smoothed
  // with a mild 1px blur still retains high second-derivative variance (> 60) despite losing fine detail.
  const extremeContrastMildBlur = boxBlurFrame(
    makeFrame(w, h, (x, y) => ((Math.floor(x / 2) + Math.floor(y / 2)) % 2 === 0 ? 0 : 255)),
    1,
  );

  const blurFixtures: { id: string; frame: Frame; expectedBlur: boolean; note: string }[] = [
    { id: "sharp-checkerboard", frame: sharpChecker, expectedBlur: false, note: "In-focus high-contrast architectural tile" },
    { id: "sharp-brickwork", frame: sharpBrick, expectedBlur: false, note: "In-focus mortar grid lines" },
    { id: "sharp-textured-carpet", frame: sharpSinusoid, expectedBlur: false, note: "In-focus high-frequency carpet texture" },
    { id: "sharp-sparse-wall-seam", frame: sharpSparseWall, expectedBlur: false, note: "FP: in-focus plain wall with a single door seam has low global Laplacian variance" },
    { id: "defocused-checkerboard-r4", frame: heavyBlurChecker, expectedBlur: true, note: "Severely defocused tile" },
    { id: "defocused-brickwork-r3", frame: heavyBlurBrick, expectedBlur: true, note: "Severely motion/defocus-blurred brickwork" },
    { id: "out-of-focus-wash", frame: flatOutOfFocus, expectedBlur: true, note: "Low-gradient out-of-focus wall wash" },
    { id: "blurred-high-contrast-blinds", frame: extremeContrastMildBlur, expectedBlur: true, note: "FN: mildly blurred 0/255 blind stripes still exceed minBlurVariance" },
  ];

  const blurCases: ConfusionMatrix["cases"] = blurFixtures.map((f) => {
    const res = assessFrame(f.frame, 1000, { prevGray: null, prevPosition: null }, null, thresholds);
    const predicted = res.sample.warnings.includes("BLUR");
    const outcome = f.expectedBlur ? (predicted ? "TP" : "FN") : (predicted ? "FP" : "TN");
    return {
      id: f.id,
      expected: f.expectedBlur,
      predicted,
      outcome,
      metricValue: res.sample.blurScore,
      note: f.note,
    };
  });

  // Motion pairs for FAST_MOTION (motionScore > thresholds.maxMotionScore, normalized 0..1)
  const makeShiftedChecker = (dx: number, block = 8, lo = 50, hi = 200) =>
    makeFrame(w, h, (x, y) => ((Math.floor((x + dx) / block) + Math.floor(y / block)) % 2 === 0 ? lo : hi));

  const motionFixtures: { id: string; prev: Frame; curr: Frame; expectedFast: boolean; note: string }[] = [
    { id: "steady-walk-1px-shift", prev: makeShiftedChecker(0, 8), curr: makeShiftedChecker(1, 8), expectedFast: false, note: "Slow 1px shift on 8px blocks during steady walk" },
    { id: "moderate-walk-2px-shift", prev: makeShiftedChecker(0, 8), curr: makeShiftedChecker(2, 8), expectedFast: false, note: "Moderate 2px shift on 8px blocks" },
    { id: "stationary-sensor-noise", prev: sharpBrick, curr: makeFrame(w, h, (x, y) => (x % 8 === 0 || y % 6 === 0 ? 41 : 184)), expectedFast: false, note: "Stationary camera with 1-level sensor noise" },
    {
      id: "slow-shift-fine-high-contrast-stripes",
      prev: makeShiftedChecker(0, 2, 0, 255),
      curr: makeShiftedChecker(2, 2, 0, 255),
      expectedFast: false,
      note: "FP: a small 2px shift that aligns in anti-phase on a 2px 0/255 stripe pattern flips every pixel",
    },
    { id: "rapid-pan-half-period", prev: makeShiftedChecker(0, 8, 20, 235), curr: makeShiftedChecker(8, 8, 20, 235), expectedFast: true, note: "Rapid camera whip-pan inverting full 8px pattern" },
    { id: "abrupt-scene-cut-or-jerk", prev: sharpBrick, curr: makeFrame(w, h, (x, y) => (x % 8 === 0 || y % 6 === 0 ? 230 : 30)), expectedFast: true, note: "Abrupt camera jerk across contrasting wall/window" },
    { id: "large-rotation-step", prev: makeFrame(w, h, (x) => (x < w / 2 ? 20 : 230)), curr: makeFrame(w, h, (x) => (x < w / 2 ? 230 : 20)), expectedFast: true, note: "Fast yaw sweep swapping dark and bright halves" },
    {
      id: "fast-pan-over-low-contrast-wall",
      prev: makeFrame(w, h, (x) => 120 + (x % 16 < 8 ? 10 : -10)),
      curr: makeFrame(w, h, (x) => 120 + ((x + 8) % 16 < 8 ? 10 : -10)),
      expectedFast: true,
      note: "FN: rapid pan over a low-contrast painted wall produces small L1 pixel differences below maxMotionScore",
    },
  ];

  const fastMotionCases: ConfusionMatrix["cases"] = motionFixtures.map((f) => {
    const prevGray = toGrayscale(f.prev);
    const res = assessFrame(f.curr, 1600, { prevGray, prevPosition: null }, null, thresholds);
    const predicted = res.sample.warnings.includes("FAST_MOTION");
    const outcome = f.expectedFast ? (predicted ? "TP" : "FN") : (predicted ? "FP" : "TN");
    return {
      id: f.id,
      expected: f.expectedFast,
      predicted,
      outcome,
      metricValue: res.sample.motionScore ?? 0,
      note: f.note,
    };
  });

  // Duplicate-frame pairs (motionScore <= thresholds.duplicateMotionThreshold, normalized 0..1)
  const dupFixtures: { id: string; prev: Frame; curr: Frame; expectedDuplicate: boolean; note: string }[] = [
    { id: "identical-static-frame", prev: sharpChecker, curr: sharpChecker, expectedDuplicate: true, note: "Bit-identical consecutive frame" },
    { id: "static-1lsb-noise", prev: sharpBrick, curr: makeFrame(w, h, (x, y) => (x % 8 === 0 || y % 6 === 0 ? 41 : 184)), expectedDuplicate: true, note: "Static tripod/handheld pause with +/-1 LSB sensor noise" },
    { id: "static-autoexposure-step", prev: sharpBrick, curr: makeFrame(w, h, (x, y) => (x % 8 === 0 || y % 6 === 0 ? 45 : 190)), expectedDuplicate: true, note: "FN: static camera with +5 luma auto-exposure flicker exceeds duplicateMotionThreshold" },
    { id: "normal-translation-step", prev: makeShiftedChecker(0, 8), curr: makeShiftedChecker(2, 8), expectedDuplicate: false, note: "Normal walking baseline translation" },
    { id: "rapid-pan-step", prev: makeShiftedChecker(0, 8, 20, 235), curr: makeShiftedChecker(8, 8, 20, 235), expectedFast: false } as unknown as { id: string; prev: Frame; curr: Frame; expectedDuplicate: boolean; note: string },
    { id: "moving-along-featureless-wall", prev: makeFrame(w, h, () => 128), curr: makeFrame(w, h, () => 129), expectedDuplicate: false, note: "FP: camera translating past a completely uniform blank wall looks identical in pixel space" },
  ];
  dupFixtures[4] = { id: "rapid-pan-step", prev: makeShiftedChecker(0, 8, 20, 235), curr: makeShiftedChecker(8, 8, 20, 235), expectedDuplicate: false, note: "Large camera pan" };

  const dupCases: ConfusionMatrix["cases"] = dupFixtures.map((f) => {
    const prevGray = toGrayscale(f.prev);
    const res = assessFrame(f.curr, 1600, { prevGray, prevPosition: null }, null, thresholds);
    const predicted = res.sample.duplicateFrame;
    const outcome = f.expectedDuplicate ? (predicted ? "TP" : "FN") : (predicted ? "FP" : "TN");
    return {
      id: f.id,
      expected: f.expectedDuplicate,
      predicted,
      outcome,
      metricValue: res.sample.motionScore ?? 0,
      note: f.note,
    };
  });

  return {
    thresholds,
    blur: summarizeConfusion(blurCases),
    fastMotion: summarizeConfusion(fastMotionCases),
    duplicateFrame: summarizeConfusion(dupCases),
  };
}
