// Camera observations -> obstacles placed in the venue frame (docs/navigation.md, "Camera obstacle sensing"). Pure.
//
// A detection is a box in the camera image. A box alone says nothing about where the thing is in 3-D, so it is placed
// only with depth measured in the same frame, inside the box, by the platform (LiDAR, ARKit person depth, ARCore depth).
// Nothing here estimates depth from the box's size or position, and an observation that cannot be placed is rejected
// with the reason, never guessed:
//
//   localizeDetection   a detector's box + the frame's depth samples + the camera pose + the device -> venue transform
//                       -> a footprint on the floor, its confidence and its uncertainty, or a rejection.
//   corridorOccupancy   depth samples alone: measured surfaces standing in the route corridor ahead, where the baked
//                       navmesh says the floor is free. The route was planned on the reconstruction's static geometry, so
//                       something standing there was not part of the scan (a moved or new object, or a person). Its
//                       category is UNCLASSIFIED: depth does not say what it is.
//   visibilityAt        whether the current depth frame shows a tracked obstacle's place clear, still occupied, hidden
//                       behind something nearer, or out of view (obstacle-tracker.ts uses it for occlusion).
//   ObstacleScheduler   bounds how often sensing runs, measures its latency and backs off on slow devices.
//
// Coordinates: camera-space points are metres, +X right, +Y up, -Z forward (WebXR view space and ARKit camera space
// agree). The camera pose is in the platform's world frame; deviceToVenue maps that to canonical venue metres (+Z up).

import type { Pose } from "./ar-anchor-math.ts";
import { cameraPointToVenue, worldPointToCamera } from "./ar-route.ts";
import { venuePointToDevice } from "./ar-frame-boundary.ts";
import type { Vec3 } from "./coordinate-frame.ts";

export type ObstacleCategory = "PERSON" | "UNCLASSIFIED";
/** Where depth came from. Their error grows with distance at different rates (DEPTH_ERROR). */
export type DepthSource = "LIDAR" | "ARKIT_PERSON_DEPTH" | "ARCORE_DEPTH";

/** One depth measurement: where it is in the image (normalized, 0..1, origin top-left), and the camera-space point the
 * platform's depth puts there. confidence: the platform's own per-pixel confidence, 0..1, when it reports one. */
export interface DepthSample {
  u: number;
  v: number;
  point: Vec3;
  confidence: number | null;
}

export interface DepthFrame {
  source: DepthSource;
  /** The platform timestamp of the camera frame the depth belongs to. */
  frameTimeMs: number;
  samples: readonly DepthSample[];
}

/** What a detector reported for one camera frame. box: normalized image coordinates, origin top-left. */
export interface Detection {
  category: ObstacleCategory;
  detector: string;
  score: number;
  frameTimeMs: number;
  box: { u0: number; v0: number; u1: number; v1: number };
}

/** The pose and localization state the frame was captured with. deviceToVenue is null when not localized. */
export interface CameraContext {
  /** The platform timestamp of the camera frame (matched against the depth's and the detection's). */
  frameTimeMs: number;
  /** Wall-clock time of the frame (epoch ms): what an observation is stamped with for the server's age check. */
  wallClockMs: number;
  camera: Pose;
  tracking: "NORMAL" | "LIMITED" | "LOST";
  deviceToVenue: Pose | null;
  floorId: string;
  coordinateFrameId: string | null;
  /** The localization's measured anchor residual (metres); null when unknown (one marker). */
  residualMeters: number | null;
}

export interface Footprint {
  minX: number;
  minY: number;
  maxX: number;
  maxY: number;
}

/** An obstacle placed in the venue frame from one frame's evidence. The footprint already includes `uncertaintyMeters`. */
export interface LocalizedObstacle extends Footprint {
  floorId: string;
  coordinateFrameId: string | null;
  category: ObstacleCategory;
  observedAtMs: number;
  /** Canonical centre of the measured surface, metres. */
  center: Vec3;
  /** 0..1: detector score x the share of depth samples that agree. Not a probability of being right. */
  confidence: number;
  uncertaintyMeters: number;
  evidence: {
    depthSource: DepthSource;
    depthSamples: number;
    distanceMeters: number;
    /** false: the localization residual was unknown and UNKNOWN_RESIDUAL_METERS was assumed instead. */
    residualMeasured: boolean;
    detector: string | null;
  };
}

export type Rejection =
  | "LOW_SCORE"
  | "NOT_LOCALIZED"
  | "TRACKING_NOT_NORMAL"
  | "NO_DEPTH"
  | "FRAME_MISMATCH"
  | "TOO_FEW_DEPTH_SAMPLES"
  | "INCONSISTENT_DEPTH"
  | "OUT_OF_RANGE"
  | "NOT_ON_FLOOR"
  | "TOO_UNCERTAIN";

export type Localized = { ok: true; obstacle: LocalizedObstacle } | { ok: false; reason: Rejection; detail: string };

export const MIN_DETECTION_SCORE = 0.5;
export const MIN_DEPTH_SAMPLES = 8;
export const MIN_DEPTH_CONFIDENCE = 0.5;
/** Depth and image must come from the same camera frame (platform timestamps, ms). */
export const FRAME_MATCH_TOLERANCE_MS = 1;
export const MIN_RANGE_METERS = 0.3;
export const MAX_RANGE_METERS = 5.0;
/** Samples within this distance of the median range belong to the detected surface (or 10% of the range, if larger). */
export const DEPTH_INLIER_METERS = 0.25;
export const MIN_INLIER_FRACTION = 0.5;
/** A box whose depth is this much nearer than its main surface holds two things; which one was detected is unknown. */
export const MAX_NEARER_FRACTION = 0.1;
/** The box's edges hold background; only its inner part is sampled. */
export const BOX_INSET = 0.15;
/** Depth error per metre squared of range, and its floor (1 sigma), per source. Conservative published orders of
 * magnitude for each sensor class, not a calibration of any one device. */
export const DEPTH_ERROR: Record<DepthSource, { base: number; perM2: number }> = {
  LIDAR: { base: 0.01, perM2: 0.005 },
  ARKIT_PERSON_DEPTH: { base: 0.03, perM2: 0.02 },
  ARCORE_DEPTH: { base: 0.03, perM2: 0.02 },
};
/** Assumed localization error when the residual is unknown (one marker); the rejection limit for anchors is 0.3 m. */
export const UNKNOWN_RESIDUAL_METERS = 0.25;
export const MAX_UNCERTAINTY_METERS = 0.75;
/** Depth sees only the near surface. The far side is unobserved; a footprint is extended this far behind it, away from
 * the camera (a person's body depth). This is a size allowance, not a measurement, and is documented as such. */
export const HIDDEN_DEPTH_ALLOWANCE_METERS = 0.25;
/** Something on the floor: its measured surface reaches within LOWEST_ABOVE_GROUND of the floor, rises at least
 * MIN_HEIGHT above it and never sits below it by more than BELOW_GROUND_TOLERANCE (which a wrong transform does). */
export const LOWEST_ABOVE_GROUND_METERS = 1.2;
export const MIN_HEIGHT_METERS = 0.3;
export const BELOW_GROUND_TOLERANCE_METERS = 0.4;
export const MAX_TOP_ABOVE_GROUND_METERS = 2.5;

// corridor occupancy
/** Half width of the corridor checked ahead: less than the walking body's radius (0.35 m), which Recast already keeps
 * between the route and the scanned static geometry, so walls and jambs beside the route stay outside it. */
export const CORRIDOR_HALF_WIDTH_METERS = 0.25;
export const CORRIDOR_MAX_RANGE_METERS = 4.0;
export const CORRIDOR_BAND_LOW_METERS = 0.3;
export const CORRIDOR_BAND_HIGH_METERS = 1.8;
export const CLUSTER_CELL_METERS = 0.25;

function quantile(sorted: readonly number[], q: number): number {
  const i = (sorted.length - 1) * q;
  const lo = Math.floor(i), hi = Math.ceil(i);
  return sorted[lo] + (sorted[hi] - sorted[lo]) * (i - lo);
}

const range = (p: Vec3) => Math.hypot(p[0], p[1], p[2]);

/** The floor height under (x, y): the nearest ground point of the route (route waypoints lie on the navmesh). */
export function groundZAt(ground: readonly Vec3[], x: number, y: number): number | null {
  let best: Vec3 | null = null, bestD = Infinity;
  for (const g of ground) {
    const d = Math.hypot(g[0] - x, g[1] - y);
    if (d < bestD) { bestD = d; best = g; }
  }
  return best ? best[2] : null;
}

function contextProblem(ctx: CameraContext, depth: DepthFrame | null, detectionTimeMs: number | null): Localized | null {
  if (!ctx.deviceToVenue) return { ok: false, reason: "NOT_LOCALIZED", detail: "the device is not localized in the venue" };
  if (ctx.tracking !== "NORMAL") return { ok: false, reason: "TRACKING_NOT_NORMAL", detail: `tracking is ${ctx.tracking}` };
  if (!depth || depth.samples.length === 0) return { ok: false, reason: "NO_DEPTH", detail: "the frame has no depth measurement" };
  const off = Math.max(Math.abs(depth.frameTimeMs - ctx.frameTimeMs),
    detectionTimeMs === null ? 0 : Math.abs(detectionTimeMs - ctx.frameTimeMs));
  if (off > FRAME_MATCH_TOLERANCE_MS) {
    return { ok: false, reason: "FRAME_MISMATCH", detail: `detection, depth and camera pose are ${off.toFixed(0)} ms apart` };
  }
  return null;
}

/** Places a set of camera-space surface points (already checked to be one surface) as a footprint. */
function place(points: readonly Vec3[], distance: number, inlierFraction: number, score: number, category: ObstacleCategory,
               detector: string | null, ctx: CameraContext, depth: DepthFrame, ground: readonly Vec3[]): Localized {
  const deviceToVenue = ctx.deviceToVenue as Pose;
  const venue = points.map((p) => cameraPointToVenue(ctx.camera, deviceToVenue, p));
  const xs = venue.map((p) => p[0]).sort((a, b) => a - b);
  const ys = venue.map((p) => p[1]).sort((a, b) => a - b);
  const zs = venue.map((p) => p[2]).sort((a, b) => a - b);
  const center: Vec3 = [quantile(xs, 0.5), quantile(ys, 0.5), quantile(zs, 0.5)];
  const groundZ = groundZAt(ground, center[0], center[1]);
  if (groundZ === null) return { ok: false, reason: "NOT_ON_FLOOR", detail: "no route ground on this floor to compare with" };
  const low = quantile(zs, 0.1) - groundZ, high = quantile(zs, 0.9) - groundZ;
  if (low < -BELOW_GROUND_TOLERANCE_METERS || low > LOWEST_ABOVE_GROUND_METERS || high < MIN_HEIGHT_METERS
      || high > MAX_TOP_ABOVE_GROUND_METERS) {
    return {
      ok: false, reason: "NOT_ON_FLOOR",
      detail: `the measured surface spans ${low.toFixed(2)}..${high.toFixed(2)} m above the route's floor: not something ` +
        "standing on it (overhead, or the device -> venue transform is wrong)",
    };
  }
  const err = DEPTH_ERROR[depth.source];
  const depthSigma = err.base + err.perM2 * distance * distance;
  const residualMeasured = ctx.residualMeters !== null;
  const uncertainty = 2 * depthSigma + (ctx.residualMeters ?? UNKNOWN_RESIDUAL_METERS);
  if (uncertainty > MAX_UNCERTAINTY_METERS) {
    return { ok: false, reason: "TOO_UNCERTAIN", detail: `position uncertain by ${uncertainty.toFixed(2)} m at ${distance.toFixed(1)} m` };
  }
  let minX = quantile(xs, 0.1), maxX = quantile(xs, 0.9), minY = quantile(ys, 0.1), maxY = quantile(ys, 0.9);
  // The unobserved far side: extend away from the camera.
  const cam = cameraPointToVenue(ctx.camera, deviceToVenue, [0, 0, 0]);
  const dx = center[0] - cam[0], dy = center[1] - cam[1], n = Math.hypot(dx, dy) || 1;
  const bx = (dx / n) * HIDDEN_DEPTH_ALLOWANCE_METERS, by = (dy / n) * HIDDEN_DEPTH_ALLOWANCE_METERS;
  minX += Math.min(0, bx); maxX += Math.max(0, bx);
  minY += Math.min(0, by); maxY += Math.max(0, by);
  return {
    ok: true,
    obstacle: {
      floorId: ctx.floorId, coordinateFrameId: ctx.coordinateFrameId, category, observedAtMs: ctx.wallClockMs, center,
      minX: minX - uncertainty, minY: minY - uncertainty, maxX: maxX + uncertainty, maxY: maxY + uncertainty,
      confidence: Math.max(0, Math.min(1, score * inlierFraction)), uncertaintyMeters: uncertainty,
      evidence: { depthSource: depth.source, depthSamples: points.length, distanceMeters: distance, residualMeasured, detector },
    },
  };
}

/**
 * A detector's box placed with the same frame's measured depth.
 * @param ground the route's points on this floor (canonical), for the floor height
 */
export function localizeDetection(det: Detection, depth: DepthFrame | null, ctx: CameraContext, ground: readonly Vec3[]): Localized {
  if (!(det.score >= MIN_DETECTION_SCORE)) return { ok: false, reason: "LOW_SCORE", detail: `detector score ${det.score}` };
  const problem = contextProblem(ctx, depth, det.frameTimeMs);
  if (problem) return problem;
  const d = depth as DepthFrame;
  const w = det.box.u1 - det.box.u0, h = det.box.v1 - det.box.v0;
  const u0 = det.box.u0 + w * BOX_INSET, u1 = det.box.u1 - w * BOX_INSET;
  const v0 = det.box.v0 + h * BOX_INSET, v1 = det.box.v1 - h * BOX_INSET;
  const inside = d.samples.filter((s) => s.u >= u0 && s.u <= u1 && s.v >= v0 && s.v <= v1
    && (s.confidence === null || s.confidence >= MIN_DEPTH_CONFIDENCE) && s.point[2] < 0 && Number.isFinite(range(s.point)));
  if (inside.length < MIN_DEPTH_SAMPLES) {
    return { ok: false, reason: "TOO_FEW_DEPTH_SAMPLES", detail: `${inside.length} confident depth sample(s) inside the box (need ${MIN_DEPTH_SAMPLES})` };
  }
  const ranges = inside.map((s) => range(s.point)).sort((a, b) => a - b);
  const median = quantile(ranges, 0.5);
  const tol = Math.max(DEPTH_INLIER_METERS, 0.1 * median);
  const inliers = inside.filter((s) => Math.abs(range(s.point) - median) <= tol);
  const fraction = inliers.length / inside.length;
  if (fraction < MIN_INLIER_FRACTION || inliers.length < MIN_DEPTH_SAMPLES) {
    return { ok: false, reason: "INCONSISTENT_DEPTH", detail: `only ${(fraction * 100).toFixed(0)}% of the depth inside the box is one surface` };
  }
  // Most of the box may be the background behind a thin or partly hidden object: then the detected thing is not the
  // majority surface, and which surface it is cannot be told from depth. Rejected rather than placed at the background.
  const nearer = inside.filter((s) => range(s.point) < median - tol).length / inside.length;
  if (nearer >= MAX_NEARER_FRACTION) {
    return { ok: false, reason: "INCONSISTENT_DEPTH", detail: `${(nearer * 100).toFixed(0)}% of the box is a nearer surface than most of its depth` };
  }
  if (median < MIN_RANGE_METERS || median > MAX_RANGE_METERS) {
    return { ok: false, reason: "OUT_OF_RANGE", detail: `${median.toFixed(2)} m away (placed only from ${MIN_RANGE_METERS} to ${MAX_RANGE_METERS} m)` };
  }
  return place(inliers.map((s) => s.point), median, fraction, det.score, det.category, det.detector, ctx, d, ground);
}

function distanceToPath2d(path: readonly Vec3[], x: number, y: number): number {
  if (path.length === 1) return Math.hypot(path[0][0] - x, path[0][1] - y);
  let best = Infinity;
  for (let i = 1; i < path.length; i++) {
    const a = path[i - 1], b = path[i];
    const dx = b[0] - a[0], dy = b[1] - a[1], len2 = dx * dx + dy * dy;
    const t = len2 === 0 ? 0 : Math.max(0, Math.min(1, ((x - a[0]) * dx + (y - a[1]) * dy) / len2));
    best = Math.min(best, Math.hypot(a[0] + t * dx - x, a[1] + t * dy - y));
  }
  return best;
}

/**
 * Measured surfaces standing in the route corridor ahead, one obstacle per connected cluster (UNCLASSIFIED).
 * @param pathAhead the route still ahead on this floor, from the ground point nearest the device (canonical)
 */
export function corridorOccupancy(depth: DepthFrame | null, ctx: CameraContext, pathAhead: readonly Vec3[]): { obstacles: LocalizedObstacle[]; rejected: Localized[] } {
  const problem = contextProblem(ctx, depth, null);
  if (problem || pathAhead.length === 0) return { obstacles: [], rejected: problem ? [problem] : [] };
  const d = depth as DepthFrame;
  const deviceToVenue = ctx.deviceToVenue as Pose;
  const cells = new Map<string, { cx: number; cy: number; points: Vec3[]; ranges: number[] }>();
  for (const s of d.samples) {
    if (s.confidence !== null && s.confidence < MIN_DEPTH_CONFIDENCE) continue;
    const r = range(s.point);
    if (!(s.point[2] < 0) || r < MIN_RANGE_METERS || r > CORRIDOR_MAX_RANGE_METERS) continue;
    const p = cameraPointToVenue(ctx.camera, deviceToVenue, s.point);
    const groundZ = groundZAt(pathAhead, p[0], p[1]);
    if (groundZ === null) continue;
    const z = p[2] - groundZ;
    if (z < CORRIDOR_BAND_LOW_METERS || z > CORRIDOR_BAND_HIGH_METERS) continue;
    if (distanceToPath2d(pathAhead, p[0], p[1]) > CORRIDOR_HALF_WIDTH_METERS) continue;
    const cx = Math.floor(p[0] / CLUSTER_CELL_METERS), cy = Math.floor(p[1] / CLUSTER_CELL_METERS);
    const key = `${cx},${cy}`;
    const cell = cells.get(key) ?? { cx, cy, points: [], ranges: [] };
    cell.points.push(s.point);
    cell.ranges.push(r);
    cells.set(key, cell);
  }
  // 8-connected clusters of occupied cells
  const seen = new Set<string>();
  const obstacles: LocalizedObstacle[] = [];
  const rejected: Localized[] = [];
  for (const [key, start] of cells) {
    if (seen.has(key)) continue;
    const stack = [start];
    seen.add(key);
    const points: Vec3[] = [], ranges: number[] = [];
    while (stack.length > 0) {
      const c = stack.pop()!;
      points.push(...c.points);
      ranges.push(...c.ranges);
      for (let ix = -1; ix <= 1; ix++) for (let iy = -1; iy <= 1; iy++) {
        const k = `${c.cx + ix},${c.cy + iy}`;
        const n = cells.get(k);
        if (n && !seen.has(k)) { seen.add(k); stack.push(n); }
      }
    }
    if (points.length < MIN_DEPTH_SAMPLES) continue; // a few stray samples are noise, not an obstacle
    const median = quantile([...ranges].sort((a, b) => a - b), 0.5);
    const placed = place(points, median, 1, Math.min(1, points.length / (3 * MIN_DEPTH_SAMPLES)), "UNCLASSIFIED", null, ctx, d,
      pathAhead);
    if (placed.ok) obstacles.push(placed.obstacle); else rejected.push(placed);
  }
  return { obstacles, rejected };
}

/** What the current depth frame says about a tracked obstacle's place. */
export type Visibility = "CLEAR" | "OCCUPIED" | "OCCLUDED" | "NOT_IN_VIEW";
/** Depth this much beyond (or short of) the expected distance means seen-through (or hidden behind something). */
export const VISIBILITY_MARGIN_METERS = 0.5;

/**
 * @param depthAlong the measured depth range along the camera-space direction of a point, or null when that direction
 *        is outside the depth image or has no confident depth (supplied by the platform adapter)
 */
export function visibilityAt(venuePoint: Vec3, ctx: CameraContext, depthAlong: (cameraPoint: Vec3) => number | null): Visibility {
  if (!ctx.deviceToVenue || ctx.tracking !== "NORMAL") return "NOT_IN_VIEW";
  const p = worldPointToCamera(ctx.camera, venuePointToDevice(ctx.deviceToVenue, venuePoint));
  if (!(p[2] < 0)) return "NOT_IN_VIEW";
  const expected = range(p);
  if (expected > MAX_RANGE_METERS) return "NOT_IN_VIEW";
  const measured = depthAlong(p);
  if (measured === null) return "NOT_IN_VIEW";
  if (measured > expected + VISIBILITY_MARGIN_METERS) return "CLEAR";
  if (measured < expected - VISIBILITY_MARGIN_METERS) return "OCCLUDED";
  return "OCCUPIED";
}

/**
 * A camera-space point from a normalized image position and a depth (distance along -Z), through the inverse of the
 * view's projection matrix (column-major 4x4, WebXR's XRView.projectionMatrix inverted). u, v: origin top-left.
 */
export function unprojectNormalized(u: number, v: number, depthMeters: number, inverseProjection: ArrayLike<number>): Vec3 | null {
  if (!(depthMeters > 0) || !Number.isFinite(depthMeters)) return null;
  const x = 2 * u - 1, y = 1 - 2 * v;
  const m = inverseProjection;
  const px = m[0] * x + m[4] * y + m[8] * -1 + m[12];
  const py = m[1] * x + m[5] * y + m[9] * -1 + m[13];
  const pz = m[2] * x + m[6] * y + m[10] * -1 + m[14];
  const pw = m[3] * x + m[7] * y + m[11] * -1 + m[15];
  if (pw === 0) return null;
  const dir: Vec3 = [px / pw, py / pw, pz / pw];
  if (!(dir[2] < 0)) return null;
  const s = depthMeters / -dir[2];
  return [dir[0] * s, dir[1] * s, -depthMeters];
}

/** The inverse: normalized image position of a camera-space point, or null when it is behind or outside the view. */
export function projectNormalized(p: Vec3, projection: ArrayLike<number>): { u: number; v: number } | null {
  const m = projection;
  const cx = m[0] * p[0] + m[4] * p[1] + m[8] * p[2] + m[12];
  const cy = m[1] * p[0] + m[5] * p[1] + m[9] * p[2] + m[13];
  const cw = m[3] * p[0] + m[7] * p[1] + m[11] * p[2] + m[15];
  if (!(cw > 0)) return null;
  const x = cx / cw, y = cy / cw;
  if (x < -1 || x > 1 || y < -1 || y > 1) return null;
  return { u: (x + 1) / 2, v: (1 - y) / 2 };
}

/** Inverse of a column-major 4x4 matrix, or null when singular. */
export function invert4(m: ArrayLike<number>): Float64Array | null {
  const a = Array.from({ length: 16 }, (_, i) => m[i]);
  const inv = new Float64Array(16);
  inv[0] = a[5] * a[10] * a[15] - a[5] * a[11] * a[14] - a[9] * a[6] * a[15] + a[9] * a[7] * a[14] + a[13] * a[6] * a[11] - a[13] * a[7] * a[10];
  inv[4] = -a[4] * a[10] * a[15] + a[4] * a[11] * a[14] + a[8] * a[6] * a[15] - a[8] * a[7] * a[14] - a[12] * a[6] * a[11] + a[12] * a[7] * a[10];
  inv[8] = a[4] * a[9] * a[15] - a[4] * a[11] * a[13] - a[8] * a[5] * a[15] + a[8] * a[7] * a[13] + a[12] * a[5] * a[11] - a[12] * a[7] * a[9];
  inv[12] = -a[4] * a[9] * a[14] + a[4] * a[10] * a[13] + a[8] * a[5] * a[14] - a[8] * a[6] * a[13] - a[12] * a[5] * a[10] + a[12] * a[6] * a[9];
  inv[1] = -a[1] * a[10] * a[15] + a[1] * a[11] * a[14] + a[9] * a[2] * a[15] - a[9] * a[3] * a[14] - a[13] * a[2] * a[11] + a[13] * a[3] * a[10];
  inv[5] = a[0] * a[10] * a[15] - a[0] * a[11] * a[14] - a[8] * a[2] * a[15] + a[8] * a[3] * a[14] + a[12] * a[2] * a[11] - a[12] * a[3] * a[10];
  inv[9] = -a[0] * a[9] * a[15] + a[0] * a[11] * a[13] + a[8] * a[1] * a[15] - a[8] * a[3] * a[13] - a[12] * a[1] * a[11] + a[12] * a[3] * a[9];
  inv[13] = a[0] * a[9] * a[14] - a[0] * a[10] * a[13] - a[8] * a[1] * a[14] + a[8] * a[2] * a[13] + a[12] * a[1] * a[10] - a[12] * a[2] * a[9];
  inv[2] = a[1] * a[6] * a[15] - a[1] * a[7] * a[14] - a[5] * a[2] * a[15] + a[5] * a[3] * a[14] + a[13] * a[2] * a[7] - a[13] * a[3] * a[6];
  inv[6] = -a[0] * a[6] * a[15] + a[0] * a[7] * a[14] + a[4] * a[2] * a[15] - a[4] * a[3] * a[14] - a[12] * a[2] * a[7] + a[12] * a[3] * a[6];
  inv[10] = a[0] * a[5] * a[15] - a[0] * a[7] * a[13] - a[4] * a[1] * a[15] + a[4] * a[3] * a[13] + a[12] * a[1] * a[7] - a[12] * a[3] * a[5];
  inv[14] = -a[0] * a[5] * a[14] + a[0] * a[6] * a[13] + a[4] * a[1] * a[14] - a[4] * a[2] * a[13] - a[12] * a[1] * a[6] + a[12] * a[2] * a[5];
  inv[3] = -a[1] * a[6] * a[11] + a[1] * a[7] * a[10] + a[5] * a[2] * a[11] - a[5] * a[3] * a[10] - a[9] * a[2] * a[7] + a[9] * a[3] * a[6];
  inv[7] = a[0] * a[6] * a[11] - a[0] * a[7] * a[10] - a[4] * a[2] * a[11] + a[4] * a[3] * a[10] + a[8] * a[2] * a[7] - a[8] * a[3] * a[6];
  inv[11] = -a[0] * a[5] * a[11] + a[0] * a[7] * a[9] + a[4] * a[1] * a[11] - a[4] * a[3] * a[9] - a[8] * a[1] * a[7] + a[8] * a[3] * a[5];
  inv[15] = a[0] * a[5] * a[10] - a[0] * a[6] * a[9] - a[4] * a[1] * a[10] + a[4] * a[2] * a[9] + a[8] * a[1] * a[6] - a[8] * a[2] * a[5];
  const det = a[0] * inv[0] + a[1] * inv[4] + a[2] * inv[8] + a[3] * inv[12];
  if (det === 0 || !Number.isFinite(det)) return null;
  for (let i = 0; i < 16; i++) inv[i] /= det;
  return inv;
}

// ---- scheduling -----------------------------------------------------------------------------------------------------

/**
 * How often sensing runs. One run at a time; at most every intervalMs, which starts at minIntervalMs and doubles (up to
 * maxIntervalMs) while the 90th-percentile latency of the last runs exceeds budgetMs, and halves back once it is under
 * half the budget. `lowDetail` tells the adapter to sample a coarser depth grid while backed off.
 */
export class ObstacleScheduler {
  readonly minIntervalMs: number;
  readonly maxIntervalMs: number;
  readonly budgetMs: number;
  intervalMs: number;
  private lastStartMs = -Infinity;
  private running = false;
  private readonly latencies: number[] = [];
  runs = 0;
  skippedBusy = 0;

  constructor(opts: { minIntervalMs: number; maxIntervalMs: number; budgetMs: number }) {
    this.minIntervalMs = opts.minIntervalMs;
    this.maxIntervalMs = opts.maxIntervalMs;
    this.budgetMs = opts.budgetMs;
    this.intervalMs = opts.minIntervalMs;
  }

  /** Whether to start a run now; if true, the caller must call finish(). */
  tryStart(nowMs: number): boolean {
    if (nowMs - this.lastStartMs < this.intervalMs) return false;
    if (this.running) { this.skippedBusy++; return false; }
    this.running = true;
    this.lastStartMs = nowMs;
    return true;
  }

  finish(latencyMs: number): void {
    this.running = false;
    this.runs++;
    this.latencies.push(latencyMs);
    if (this.latencies.length > 30) this.latencies.shift();
    if (this.latencies.length < 5) return;
    const p90 = this.p90() as number; // at least five latencies
    if (p90 > this.budgetMs) this.intervalMs = Math.min(this.maxIntervalMs, this.intervalMs * 2);
    else if (p90 < this.budgetMs / 2) this.intervalMs = Math.max(this.minIntervalMs, this.intervalMs / 2);
  }

  p90(): number | null {
    if (this.latencies.length === 0) return null;
    return quantile([...this.latencies].sort((a, b) => a - b), 0.9);
  }

  get lowDetail(): boolean {
    return this.intervalMs > this.minIntervalMs;
  }
}
