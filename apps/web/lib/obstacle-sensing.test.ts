import assert from "node:assert/strict";
import { test } from "node:test";
import type { Pose } from "./ar-anchor-math.ts";
import { DEVICE_TO_CANONICAL_AXES } from "./ar-frame-boundary.ts";
import { cameraPointToVenue } from "./ar-route.ts";
import type { Vec3 } from "./coordinate-frame.ts";
import {
  type CameraContext,
  type DepthFrame,
  type DepthSample,
  type Detection,
  MAX_UNCERTAINTY_METERS,
  ObstacleScheduler,
  UNKNOWN_RESIDUAL_METERS,
  corridorOccupancy,
  invert4,
  localizeDetection,
  projectNormalized,
  unprojectNormalized,
  visibilityAt,
} from "./obstacle-sensing.ts";
import { blockingObservations, initialTracker, updateTracks } from "./obstacle-tracker.ts";
import { CLEAR_HOLD_MS, type ObstacleObservation, clearedSince, rerouteDecision, toBlockedRegions } from "./obstacle-reroute.ts";

// SPATIAL LOCALIZATION ONLY. The depth here is ray-cast from synthetic boxes through the real camera -> AR world -> venue
// chain, and the "detector" boxes are the exact image boxes of those objects. These tests show what is done with depth
// and poses; they prove nothing about real cameras, real depth sensors or real detectors (docs/navigation.md).

// A 60-degree, 4:3 perspective projection (column-major, like XRView.projectionMatrix).
function perspective(fovY: number, aspect: number, near = 0.1, far = 100): Float64Array {
  const f = 1 / Math.tan(fovY / 2);
  const m = new Float64Array(16);
  m[0] = f / aspect; m[5] = f; m[10] = (far + near) / (near - far); m[11] = -1; m[14] = (2 * far * near) / (near - far);
  return m;
}
const PROJ = perspective(Math.PI / 3, 4 / 3);
const INV = invert4(PROJ)!;

// The device's AR world: +Y up. Localized with no offset: AR world -> canonical is just the axis change, so canonical
// (x, y, z) = device (x, -z, y). The camera stands 1.5 m above the floor at the venue origin and looks along canonical +Y.
const D2V: Pose = { x: 0, y: 0, z: 0, qx: DEVICE_TO_CANONICAL_AXES.x, qy: DEVICE_TO_CANONICAL_AXES.y, qz: DEVICE_TO_CANONICAL_AXES.z, qw: DEVICE_TO_CANONICAL_AXES.w };
const CAMERA: Pose = { x: 0, y: 1.5, z: 0, qx: 0, qy: 0, qz: 0, qw: 1 };
const ROUTE: Vec3[] = [[0, 0, 0], [0, 10, 0]];

interface Box { min: Vec3; max: Vec3 }
const person: Box = { min: [-0.25, 3.0, 0], max: [0.25, 3.3, 1.75] };

function ctx(over: Partial<CameraContext> = {}): CameraContext {
  return {
    frameTimeMs: 1000, wallClockMs: 1_700_000_000_000, camera: CAMERA, tracking: "NORMAL", deviceToVenue: D2V,
    floorId: "floor", coordinateFrameId: "frame", residualMeters: 0.05, ...over,
  };
}

function hitBox(o: Vec3, d: Vec3, b: Box): number | null {
  let t0 = -Infinity, t1 = Infinity;
  for (let i = 0; i < 3; i++) {
    if (d[i] === 0) { if (o[i] < b.min[i] || o[i] > b.max[i]) return null; continue; }
    const a = (b.min[i] - o[i]) / d[i], c = (b.max[i] - o[i]) / d[i];
    t0 = Math.max(t0, Math.min(a, c)); t1 = Math.min(t1, Math.max(a, c));
  }
  return t1 >= t0 && t0 > 0 ? t0 : null;
}

/** Ray-cast depth of a scene (boxes + the floor at z = 0) on a cols x rows grid, as a depth sensor would report it. */
function depthOf(boxes: Box[], opts: { cols?: number; rows?: number; camera?: Pose; d2v?: Pose; timeMs?: number; confidence?: number | null } = {}): DepthFrame {
  const cols = opts.cols ?? 40, rows = opts.rows ?? 30, camera = opts.camera ?? CAMERA, d2v = opts.d2v ?? D2V;
  const samples: DepthSample[] = [];
  const o = cameraPointToVenue(camera, d2v, [0, 0, 0]);
  for (let r = 0; r < rows; r++) for (let c = 0; c < cols; c++) {
    const u = (c + 0.5) / cols, v = (r + 0.5) / rows;
    const unit = unprojectNormalized(u, v, 1, INV)!; // z = -1
    const q = cameraPointToVenue(camera, d2v, unit);
    const d: Vec3 = [q[0] - o[0], q[1] - o[1], q[2] - o[2]];
    let t: number | null = d[2] < 0 ? -o[2] / d[2] : null; // the floor
    for (const b of boxes) { const h = hitBox(o, d, b); if (h !== null && (t === null || h < t)) t = h; }
    if (t === null || t > 8) continue;
    samples.push({ u, v, point: [unit[0] * t, unit[1] * t, -t], confidence: opts.confidence ?? null });
  }
  return { source: "LIDAR", frameTimeMs: opts.timeMs ?? 1000, samples };
}

/** The exact image box of a scene box, as a perfect detector would draw it. */
function imageBox(b: Box, camera = CAMERA, d2v = D2V): Detection["box"] {
  const toCam = (p: Vec3): Vec3 => {
    // canonical -> device world -> camera (camera has no rotation here)
    const dev: Vec3 = [p[0], p[2], -p[1]];
    void d2v;
    return [dev[0] - camera.x, dev[1] - camera.y, dev[2] - camera.z];
  };
  const us: number[] = [], vs: number[] = [];
  for (const x of [b.min[0], b.max[0]]) for (const y of [b.min[1], b.max[1]]) for (const z of [b.min[2], b.max[2]]) {
    const at = projectNormalized(toCam([x, y, z]), PROJ);
    if (at) { us.push(at.u); vs.push(at.v); }
  }
  return { u0: Math.min(...us), v0: Math.min(...vs), u1: Math.max(...us), v1: Math.max(...vs) };
}

const detection = (box: Detection["box"], over: Partial<Detection> = {}): Detection =>
  ({ category: "PERSON", detector: "test", score: 0.9, frameTimeMs: 1000, box, ...over });

// ---- the projection plumbing ---------------------------------------------------------------------------------------

test("unprojection and projection are inverse, and depth is distance along the view axis", () => {
  const p = unprojectNormalized(0.3, 0.7, 2.5, INV)!;
  assert.equal(p[2], -2.5);
  const back = projectNormalized(p, PROJ)!;
  assert.ok(Math.abs(back.u - 0.3) < 1e-9 && Math.abs(back.v - 0.7) < 1e-9);
  assert.ok(p[0] < 0 && p[1] < 0, "left of centre and below it (v grows downward)");
  assert.equal(unprojectNormalized(0.5, 0.5, 0, INV), null, "no depth is no point");
  assert.equal(projectNormalized([0, 0, 1], PROJ), null, "behind the camera");
});

// ---- static obstacle: placed from measured depth ------------------------------------------------------------------

test("a detection is placed from the depth measured inside its box, with its uncertainty", () => {
  const r = localizeDetection(detection(imageBox(person)), depthOf([person]), ctx(), ROUTE);
  assert.ok(r.ok, JSON.stringify(r));
  const o = r.obstacle;
  assert.ok(Math.abs(o.center[0]) < 0.05 && Math.abs(o.center[1] - 3.0) < 0.05, `the near surface is at y = 3.0: ${o.center}`);
  assert.ok(o.minY < 3.0 && o.maxY > 3.0 + 0.25, "the footprint extends behind the visible surface by the stated allowance");
  assert.ok(o.minX <= -0.25 && o.maxX >= 0.25);
  // LiDAR at ~3.3 m: 2 x (0.01 + 0.005 x 3.3^2) + 0.05 residual
  assert.ok(Math.abs(o.uncertaintyMeters - (2 * (0.01 + 0.005 * o.evidence.distanceMeters ** 2) + 0.05)) < 1e-9);
  assert.equal(o.evidence.residualMeasured, true);
  assert.equal(o.observedAtMs, 1_700_000_000_000, "stamped with wall-clock time for the server's age check");
  assert.ok(o.confidence > 0.8 && o.confidence <= 0.9);
});

test("an unknown localization residual is replaced by a stated allowance, never treated as zero", () => {
  const r = localizeDetection(detection(imageBox(person)), depthOf([person]), ctx({ residualMeters: null }), ROUTE);
  assert.ok(r.ok);
  assert.equal(r.obstacle.evidence.residualMeasured, false);
  assert.ok(r.obstacle.uncertaintyMeters >= UNKNOWN_RESIDUAL_METERS);
});

// ---- missing or unusable depth -------------------------------------------------------------------------------------

test("a box without depth is never placed: no depth, too little depth, or depth that is not one surface", () => {
  const box = imageBox(person);
  assert.deepEqual(pick(localizeDetection(detection(box), null, ctx(), ROUTE)), "NO_DEPTH");
  assert.deepEqual(pick(localizeDetection(detection(box), { source: "LIDAR", frameTimeMs: 1000, samples: [] }, ctx(), ROUTE)), "NO_DEPTH");
  assert.equal(pick(localizeDetection(detection(box), depthOf([person], { cols: 6, rows: 4 }), ctx(), ROUTE)), "TOO_FEW_DEPTH_SAMPLES");
  // the platform marks the depth there as unreliable
  assert.equal(pick(localizeDetection(detection(box), depthOf([person], { confidence: 0.2 }), ctx(), ROUTE)), "TOO_FEW_DEPTH_SAMPLES");
  // a box that is half background: a thin pole in front of a far wall
  const pole: Box = { min: [-0.05, 3.0, 0], max: [0.05, 3.1, 1.8] };
  const wall: Box = { min: [-3, 5.0, 0], max: [3, 5.1, 3] };
  const wide = imageBox({ min: [-0.35, 3.0, 0], max: [0.35, 3.1, 1.8] });
  assert.equal(pick(localizeDetection(detection(wide), depthOf([pole, wall], { cols: 120, rows: 90 }), ctx(), ROUTE)), "INCONSISTENT_DEPTH");
});

test("a low-score detection, a far one, and one too uncertain to route around are rejected", () => {
  const box = imageBox(person);
  assert.equal(pick(localizeDetection(detection(box, { score: 0.3 }), depthOf([person]), ctx(), ROUTE)), "LOW_SCORE");
  const far: Box = { min: [-0.3, 6, 0], max: [0.3, 6.3, 1.8] };
  assert.equal(pick(localizeDetection(detection(imageBox(far)), depthOf([far]), ctx(), ROUTE)), "OUT_OF_RANGE");
  // ARCore depth at 4.5 m: 2 x (0.03 + 0.02 x 4.5^2) = 0.87 m > MAX_UNCERTAINTY_METERS
  const mid: Box = { min: [-0.4, 4.5, 0], max: [0.4, 4.8, 1.8] };
  const d = { ...depthOf([mid]), source: "ARCORE_DEPTH" as const };
  const r = localizeDetection(detection(imageBox(mid)), d, ctx(), ROUTE);
  assert.equal(pick(r), "TOO_UNCERTAIN");
  assert.ok(MAX_UNCERTAINTY_METERS < 0.87);
});

// ---- tracking loss and pose association ----------------------------------------------------------------------------

test("nothing is placed while tracking is not normal, before localization, or from another frame's depth", () => {
  const box = imageBox(person);
  assert.equal(pick(localizeDetection(detection(box), depthOf([person]), ctx({ tracking: "LIMITED" }), ROUTE)), "TRACKING_NOT_NORMAL");
  assert.equal(pick(localizeDetection(detection(box), depthOf([person]), ctx({ tracking: "LOST" }), ROUTE)), "TRACKING_NOT_NORMAL");
  assert.equal(pick(localizeDetection(detection(box), depthOf([person]), ctx({ deviceToVenue: null }), ROUTE)), "NOT_LOCALIZED");
  assert.equal(pick(localizeDetection(detection(box), depthOf([person], { timeMs: 1033 }), ctx(), ROUTE)), "FRAME_MISMATCH");
  assert.equal(pick(localizeDetection(detection(box, { frameTimeMs: 966 }), depthOf([person]), ctx(), ROUTE)), "FRAME_MISMATCH");
});

// ---- incorrect coordinate transform --------------------------------------------------------------------------------

test("a wrong device -> venue transform puts the surface off the floor, and the observation is rejected", () => {
  const box = imageBox(person);
  const depth = depthOf([person]);
  // translated 2 m up: the person would float
  const lifted: Pose = { ...D2V, z: 2 };
  assert.equal(pick(localizeDetection(detection(box), depth, ctx({ deviceToVenue: lifted }), ROUTE)), "NOT_ON_FLOOR");
  // the axis change left out (device +Y taken as canonical +Y): the person lies along the floor below it
  const identity: Pose = { x: 0, y: 0, z: 0, qx: 0, qy: 0, qz: 0, qw: 1 };
  assert.equal(pick(localizeDetection(detection(box), depth, ctx({ deviceToVenue: identity }), ROUTE)), "NOT_ON_FLOOR");
  // an overhead sign is not a floor obstacle either
  const sign: Box = { min: [-0.5, 3, 2.1], max: [0.5, 3.05, 2.4] };
  assert.equal(pick(localizeDetection(detection(imageBox(sign), { category: "UNCLASSIFIED" }), depthOf([sign], { cols: 160, rows: 120 }), ctx(), ROUTE)), "NOT_ON_FLOOR");
});

// ---- corridor occupancy (depth only) -------------------------------------------------------------------------------

test("depth standing in the route corridor is an obstacle; a wall beside the route, the floor and overhead are not", () => {
  const cart: Box = { min: [-0.3, 2.5, 0], max: [0.3, 3.0, 1.0] };
  const wallBeside: Box = { min: [0.6, 1, 0], max: [0.8, 6, 2.5] }; // 0.6 m from the route: outside the corridor
  const beam: Box = { min: [-1, 1.8, 2.0], max: [1, 2.0, 2.3] };
  const r = corridorOccupancy(depthOf([cart, wallBeside, beam]), ctx(), ROUTE);
  assert.equal(r.obstacles.length, 1, JSON.stringify(r.obstacles.map((o) => o.center)));
  const o = r.obstacles[0];
  assert.equal(o.category, "UNCLASSIFIED");
  assert.ok(Math.abs(o.center[1] - 2.5) < 0.1);
  assert.equal(corridorOccupancy(depthOf([wallBeside, beam]), ctx(), ROUTE).obstacles.length, 0, "an empty corridor has none");
});

test("two objects in the corridor are two obstacles; occupancy needs localization and normal tracking", () => {
  const a: Box = { min: [-0.3, 1.8, 0], max: [0.3, 2.0, 1.0] };
  const b: Box = { min: [-0.15, 3.5, 0], max: [0.15, 3.7, 1.0] };
  // b is behind a: look from the side so both are seen
  const r = corridorOccupancy(depthOf([a, b], { cols: 80, rows: 60 }), ctx(), ROUTE);
  assert.ok(r.obstacles.length >= 1);
  const sideCamera: Pose = { x: 1.0, y: 1.5, z: 0, qx: 0, qy: 0, qz: 0, qw: 1 };
  const side = corridorOccupancy(depthOf([a, b], { cols: 80, rows: 60, camera: sideCamera }), ctx({ camera: sideCamera }), ROUTE);
  assert.equal(side.obstacles.length, 2);
  assert.equal(corridorOccupancy(depthOf([a]), ctx({ tracking: "LOST" }), ROUTE).obstacles.length, 0);
  assert.equal(corridorOccupancy(depthOf([a]), ctx({ deviceToVenue: null }), ROUTE).obstacles.length, 0);
});

// ---- visibility for occlusion handling -----------------------------------------------------------------------------

test("a tracked place is clear, still occupied, hidden behind something nearer, or out of view", () => {
  const place: Vec3 = [0, 3.0, 1.0];
  const c = ctx();
  const along = (scene: Box[]) => (p: Vec3): number | null => {
    const at = projectNormalized(p, PROJ);
    if (!at) return null;
    const d = depthOf(scene, { cols: 400, rows: 300 }).samples.reduce<DepthSample | null>((best, s) =>
      best === null || Math.hypot(s.u - at.u, s.v - at.v) < Math.hypot(best.u - at.u, best.v - at.v) ? s : best, null);
    return d ? Math.hypot(...d.point) : null;
  };
  assert.equal(visibilityAt(place, c, along([])), "CLEAR", "the floor far behind is seen through the place");
  assert.equal(visibilityAt(place, c, along([person])), "OCCUPIED");
  const nearer: Box = { min: [-0.5, 1.5, 0], max: [0.5, 1.6, 2.0] };
  assert.equal(visibilityAt(place, c, along([nearer])), "OCCLUDED");
  assert.equal(visibilityAt([0, -3, 1], c, along([])), "NOT_IN_VIEW", "behind the camera");
  assert.equal(visibilityAt(place, ctx({ tracking: "LIMITED" }), along([])), "NOT_IN_VIEW");
});

// ---- performance bounds --------------------------------------------------------------------------------------------

test("sensing runs at most every interval, one at a time, and backs off while it is slow", () => {
  const s = new ObstacleScheduler({ minIntervalMs: 250, maxIntervalMs: 2000, budgetMs: 6 });
  assert.equal(s.tryStart(0), true);
  assert.equal(s.tryStart(300), false, "the previous run has not finished");
  s.finish(2);
  assert.equal(s.tryStart(100), false, "too soon");
  assert.equal(s.tryStart(300), true);
  s.finish(2);
  let t = 300;
  for (let i = 0; i < 10; i++) { t += s.intervalMs; assert.ok(s.tryStart(t)); s.finish(20); }
  assert.equal(s.intervalMs, 2000, "a slow device runs sensing every 2 s at most");
  assert.equal(s.lowDetail, true);
  for (let i = 0; i < 40; i++) { t += s.intervalMs; assert.ok(s.tryStart(t)); s.finish(1); }
  assert.equal(s.intervalMs, 250, "and returns to 4 Hz when it is fast again");
  assert.ok((s.p90() ?? Infinity) <= 1);
});

function pick(r: ReturnType<typeof localizeDetection>): string {
  return r.ok ? "OK" : r.reason;
}

// ---- depth -> tracking -> reroute, end to end (still synthetic depth) -----------------------------------------------

test("a cart appearing in the corridor reroutes once it is confirmed, and the route is restored after it leaves", () => {
  const cart: Box = { min: [-0.3, 2.5, 0], max: [0.3, 3.0, 1.0] };
  let tracker = initialTracker();
  let routedAround: ObstacleObservation[] = [];
  let cleared: number | null = null;
  let lastReroute: number | null = null;
  const t0 = 1_700_000_000_000;
  const step = (i: number, scene: Box[]) => {
    const nowMs = t0 + i * 250;
    const c = ctx({ frameTimeMs: 1000 + i, wallClockMs: nowMs });
    const depth = { ...depthOf(scene), frameTimeMs: 1000 + i };
    const found = corridorOccupancy(depth, c, ROUTE);
    const along = (p: Vec3): number | null => {
      const at = projectNormalized(p, PROJ);
      if (!at) return null;
      const s = depth.samples.reduce<DepthSample | null>((b, x) => b === null || Math.hypot(x.u - at.u, x.v - at.v) < Math.hypot(b.u - at.u, b.v - at.v) ? x : b, null);
      return s ? Math.hypot(...s.point) : null;
    };
    tracker = updateTracks(tracker, found.obstacles, { nowMs, floorId: "floor", coordinateFrameId: "frame", visibility: (p) => visibilityAt(p, c, along) });
    const observed = blockingObservations(tracker);
    cleared = clearedSince(routedAround, observed, cleared, nowMs);
    const d = rerouteDecision({ floorId: "floor", remainingPath: [[0, 0, 1.5], ...ROUTE], routedAround, observed, nowMs,
      lastRerouteMs: lastReroute, clearedSinceMs: cleared });
    if (d.reroute) { routedAround = d.obstacles; lastReroute = nowMs; }
    return d;
  };
  assert.equal(step(0, [cart]).reroute, false, "one frame is not enough");
  const second = step(1, [cart]);
  assert.equal(second.reroute, true);
  assert.equal(second.reason, "OBSTACLE_ON_PATH");
  const [region] = toBlockedRegions(second.obstacles);
  assert.equal(region.coordinateFrameId, "frame");
  assert.equal(region.observedAt, new Date(t0 + 250).toISOString());
  assert.ok(region.minY < 2.5 && region.maxY > 2.5 && region.minX < 0 && region.maxX > 0, JSON.stringify(region));
  // it stays: no further reroutes
  for (let i = 2; i < 10; i++) assert.equal(step(i, [cart]).reroute, false);
  // it leaves: its place is measured clear three times, then held clear for CLEAR_HOLD_MS, then the route is restored
  const decisions = Array.from({ length: 60 }, (_, k) => step(10 + k, []));
  const restored = decisions.findIndex((d) => d.reroute);
  assert.ok(restored > 0, "the normal route comes back");
  assert.equal(decisions[restored].reason, "OBSTACLE_CLEARED");
  assert.ok((restored + 1) * 250 >= CLEAR_HOLD_MS, `not before the hold: ${restored}`);
});

test("a person in front of a wall is placed at the person, not at the wall", () => {
  const wall: Box = { min: [-3, 5.0, 0], max: [3, 5.1, 3] };
  const r = localizeDetection(detection(imageBox(person)), depthOf([person, wall]), ctx(), ROUTE);
  assert.ok(r.ok);
  assert.ok(Math.abs(r.obstacle.center[1] - 3.0) < 0.05);
});
