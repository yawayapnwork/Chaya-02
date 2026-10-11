import assert from "node:assert/strict";
import { test } from "node:test";
import {
  type BaseLayer,
  type CaptureXrCallbacks,
  type FrameLike,
  type SessionLike,
  type XrLike,
  CAMERA_INTERVAL_MS,
  CaptureXrSession,
  HIT_RAY_DEGREES,
  detectCaptureAr,
} from "./capture-xr-session.ts";
import type { PoseSample, SurfaceSampleWire, TrackingEventWire } from "./hud-types.ts";

// The browser capture flow against a scripted fake WebXR runtime. Nothing here is a simulation of tracking quality: the
// fake only replays exact poses and hit results so that the session's handling of each platform answer can be checked.

const yawQ = (deg: number) => ({ x: 0, y: Math.sin((deg * Math.PI) / 360), z: 0, w: Math.cos((deg * Math.PI) / 360) });
const wallQ = { x: Math.SQRT1_2, y: 0, z: 0, w: Math.SQRT1_2 }; // hit pose +Y rotated onto +Z: a wall facing the camera

class FakeSession implements SessionLike {
  enabledFeatures = ["local", "local-floor", "hit-test"];
  visibilityState: "visible" | "visible-blurred" | "hidden" = "visible";
  private raf: ((t: number, f: FrameLike) => void) | null = null;
  private listeners: Record<string, (() => void)[]> = {};
  hitRequests: unknown[] = [];
  cancelled = 0;
  ended = false;
  refSpaces: string[] = [];
  private readonly noLocalFloor: boolean;
  constructor(noLocalFloor = false) { this.noLocalFloor = noLocalFloor; }
  async requestReferenceSpace(type: string) {
    if (type === "local-floor" && this.noLocalFloor) throw new Error("NotSupportedError");
    this.refSpaces.push(type);
    return { type };
  }
  requestHitTestSource(options: { space: unknown; offsetRay?: unknown }) {
    this.hitRequests.push(options.offsetRay ?? null);
    const index = this.hitRequests.length - 1;
    return Promise.resolve({ index, cancel: () => { this.cancelled++; } });
  }
  updateRenderState() { return undefined; }
  requestAnimationFrame(cb: (t: number, f: FrameLike) => void) { this.raf = cb; return 1; }
  addEventListener(type: string, cb: () => void) { (this.listeners[type] ??= []).push(cb); }
  async end() { if (!this.ended) { this.ended = true; this.listeners.end?.forEach((l) => l()); } }
  step(frame: FrameLike) { const cb = this.raf; this.raf = null; cb?.(0, frame); }
  fire(type: string) { this.listeners[type]?.forEach((l) => l()); }
}

interface Script { pose: { x: number; z: number; yaw: number; emulated?: boolean } | null; hit?: boolean }

function frameOf(s: Script): FrameLike {
  return {
    getViewerPose: () => s.pose ? {
      transform: { position: { x: s.pose.x, y: 1.5, z: s.pose.z }, orientation: yawQ(s.pose.yaw) },
      emulatedPosition: s.pose.emulated ?? false,
      views: [{ camera: { width: 640, height: 480 } }],
    } : null,
    getHitTestResults: (src) => s.hit && s.pose ? [{
      getPose: () => ({ transform: { position: { x: s.pose!.x + ((src as { index: number }).index - 2) * 0.4, y: 1.2, z: s.pose!.z - 3 }, orientation: wallQ } }),
    }] : [],
  };
}

function harness(opts: { xr?: XrLike | undefined; session?: FakeSession; layer?: Partial<BaseLayer> | null; ray?: boolean } = {}) {
  const session = opts.session ?? new FakeSession();
  let now = 10_000;
  const poses: PoseSample[] = [], surfaces: SurfaceSampleWire[] = [], events: TrackingEventWire[] = [], states: string[] = [];
  const cameraFrames: number[] = [];
  let disposed = false;
  const xr: XrLike | undefined = "xr" in opts ? opts.xr : {
    isSessionSupported: async () => true,
    requestSession: async () => session,
  };
  const cb: CaptureXrCallbacks = {
    onPose: (p) => poses.push(p), onSurface: (s) => surfaces.push(s), onEvent: (e) => events.push(e), onState: (s) => states.push(s),
    onCameraFrame: (_f, t) => cameraFrames.push(t),
  };
  const s = new CaptureXrSession({
    xr,
    createBaseLayer: async () => opts.layer === null ? null : {
      layer: {}, beginFrame: () => undefined, dispose: () => { disposed = true; },
      readCamera: () => ({ width: 2, height: 2, data: new Uint8ClampedArray(16) }), ...opts.layer,
    },
    makeRay: opts.ray === false ? undefined : (x, z) => ({ x, z }),
    now: () => now,
    newFrameId: () => "xr-test",
  }, cb);
  return {
    s, session, poses, surfaces, events, states, cameraFrames,
    advance: (ms: number) => { now += ms; },
    disposed: () => disposed,
    frame: (script: Script, dtMs = 100) => { now += dtMs; session.step(frameOf(script)); },
  };
}

test("detectCaptureAr: no WebXR, unsupported, and a failing capability check are explicit", async () => {
  assert.equal((await detectCaptureAr(undefined)).supported, false);
  assert.match((await detectCaptureAr({ isSessionSupported: async () => false, requestSession: async () => { throw new Error(); } })).reason!, /unsupported/);
  assert.match((await detectCaptureAr({ isSessionSupported: async () => { throw new Error("boom"); }, requestSession: async () => { throw new Error(); } })).reason!, /boom/);
  assert.equal((await detectCaptureAr({ isSessionSupported: async () => true, requestSession: async () => { throw new Error(); } })).supported, true);
});

test("no WebXR at all: UNSUPPORTED is reported, nothing is simulated", async () => {
  const h = harness({ xr: undefined });
  const r = await h.s.start();
  assert.deepEqual(r.ok ? null : r.problem, "WEBXR_UNAVAILABLE");
  assert.deepEqual(h.events.map((e) => e.state), ["UNSUPPORTED"]);
  assert.equal(h.poses.length, 0);
});

test("camera/AR permission denied and an unsupported device are named states", async () => {
  const denied = harness({ xr: { isSessionSupported: async () => true, requestSession: async () => { throw Object.assign(new Error("denied"), { name: "NotAllowedError" }); } } });
  const r = await denied.s.start();
  assert.equal(r.ok ? null : r.problem, "PERMISSION_DENIED");
  assert.equal(denied.events[0].state, "PERMISSION_DENIED");
  const unsupported = harness({ xr: { isSessionSupported: async () => true, requestSession: async () => { throw Object.assign(new Error("no"), { name: "NotSupportedError" }); } } });
  assert.equal(((await unsupported.s.start()) as { problem: string }).problem, "IMMERSIVE_AR_UNSUPPORTED");
  const noLayer = harness({ layer: null });
  assert.equal(((await noLayer.s.start()) as { problem: string }).problem, "NO_BASE_LAYER");
  assert.ok(noLayer.session.ended);
});

test("a tracked walk becomes throttled webxr positions and wall observations in the session's frame", async () => {
  const h = harness();
  const r = await h.s.start();
  assert.ok(r.ok);
  assert.deepEqual(h.session.refSpaces, ["local-floor", "viewer"]);
  assert.equal(h.session.hitRequests.length, HIT_RAY_DEGREES.length, "one hit-test ray per fan direction");
  for (let i = 0; i < 20; i++) h.frame({ pose: { x: i * 0.05, z: 0, yaw: 0 }, hit: true }, 100);
  assert.deepEqual(h.events.map((e) => e.state), ["SESSION_STARTED", "TRACKING"]);
  assert.ok(h.poses.length >= 5 && h.poses.length <= 10, `throttled to about 5 Hz (got ${h.poses.length})`);
  assert.ok(h.poses.every((p) => p.source === "webxr" && p.frameId === "xr-test" && p.frameAlignment === "NATIVE"));
  assert.equal(h.poses[0].yawDegrees, 90, "initial forward is plan +y");
  assert.ok(h.surfaces.length >= 5);
  assert.ok(h.surfaces.every((s) => s.kind === "WALL" && s.frameId === "xr-test" && Math.abs(s.hitY - 3) < 1e-9));
  // Within one sampled frame the fan is reported left to right.
  const firstFrame = h.surfaces.filter((s) => s.capturedAtMs === h.surfaces[0].capturedAtMs);
  assert.deepEqual(firstFrame.map((s) => s.hitX), [...firstFrame.map((s) => s.hitX)].sort((a, b) => a - b));
});

test("local-floor is optional: the session falls back to local", async () => {
  const h = harness({ session: new FakeSession(true) });
  assert.ok((await h.s.start()).ok);
  assert.deepEqual(h.session.refSpaces, ["local", "viewer"]);
});

test("lost and limited tracking: reported once each, and no position is sent while they last", async () => {
  const h = harness();
  await h.s.start();
  h.frame({ pose: { x: 0, z: 0, yaw: 0 } });
  const before = h.poses.length;
  for (let i = 0; i < 6; i++) h.frame({ pose: null }, 100);
  for (let i = 0; i < 4; i++) h.frame({ pose: { x: 1, z: 0, yaw: 0, emulated: true }, hit: true }, 300);
  assert.equal(h.poses.length, before, "nothing from a missing or emulated pose");
  assert.equal(h.surfaces.length, 0);
  h.frame({ pose: { x: 1, z: 0, yaw: 0 } }, 300);
  assert.deepEqual(h.events.map((e) => e.state), ["SESSION_STARTED", "TRACKING", "LOST", "LIMITED", "TRACKING"]);
  assert.equal(h.poses.length, before + 1);
  assert.match(h.events.find((e) => e.state === "LIMITED")!.detail!, /orientation only/);
});

test("an interrupted session (app switch, notification) and the session ending are reported", async () => {
  const h = harness();
  await h.s.start();
  h.frame({ pose: { x: 0, z: 0, yaw: 0 } });
  h.session.visibilityState = "hidden";
  h.session.fire("visibilitychange");
  h.session.visibilityState = "visible";
  h.frame({ pose: { x: 0, z: 0, yaw: 0 } });
  await h.s.stop();
  assert.deepEqual(h.events.map((e) => e.state), ["SESSION_STARTED", "TRACKING", "INTERRUPTED", "RESUMED", "SESSION_ENDED"]);
  assert.equal(h.session.cancelled, HIT_RAY_DEGREES.length);
  assert.ok(h.disposed());
  const n = h.poses.length;
  h.frame({ pose: { x: 3, z: 0, yaw: 0 } });
  assert.equal(h.poses.length, n, "no frames are handled after the end");
});

test("re-alignment: positions of a new session are mapped onto the plan frame and marked OPERATOR_ALIGNED", async () => {
  const h = harness();
  await h.s.start();
  assert.equal(h.s.realign("plan-frame", { x: 5, y: 5, yawDegrees: 0 }), false, "nothing tracked yet: cannot align");
  h.frame({ pose: { x: 2, z: -1, yaw: 0 } }); // native plan pose (2, 1), heading 90
  assert.ok(h.s.realign("plan-frame", { x: 5, y: 5, yawDegrees: 0 }));
  assert.equal(h.events.at(-1)!.state, "REALIGNED");
  h.frame({ pose: { x: 2, z: -2, yaw: 0 } }, 300); // one metre forward in the session
  const p = h.poses.at(-1)!;
  assert.equal(p.frameId, "plan-frame");
  assert.equal(p.frameAlignment, "OPERATOR_ALIGNED");
  assert.ok(Math.abs(p.x - 6) < 1e-9 && Math.abs(p.y - 5) < 1e-9, `forward in the session is plan +x after alignment (${p.x}, ${p.y})`);
  assert.ok(Math.abs(p.yawDegrees!) < 1e-9);
});

test("camera frames for quality checks are sampled no faster than the analysis interval", async () => {
  const h = harness();
  await h.s.start();
  for (let i = 0; i < 30; i++) h.frame({ pose: { x: 0, z: 0, yaw: 0 } }, 100);
  assert.ok(h.cameraFrames.length >= 4 && h.cameraFrames.length <= Math.ceil(3000 / CAMERA_INTERVAL_MS) + 1);
  for (let i = 1; i < h.cameraFrames.length; i++) assert.ok(h.cameraFrames[i] - h.cameraFrames[i - 1] >= CAMERA_INTERVAL_MS);
});

test("without XRRay the session still hit-tests straight ahead", async () => {
  const h = harness({ ray: false });
  await h.s.start();
  assert.equal(h.session.hitRequests.length, 1);
});
