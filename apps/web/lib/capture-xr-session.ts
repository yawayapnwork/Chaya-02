// The capture HUD's AR tracking session (docs/capture-hud.md section 2): an immersive-ar WebXR session whose only job is
// to report where the device is, which surfaces it sees, how well it is tracking, and (when the browser grants raw camera
// access) the camera frames the quality checks run on. Nothing is drawn into the scene.
//
// It is written against small structural interfaces, not the DOM's WebXR classes, so the whole flow (permission denial,
// unsupported devices, lost tracking, interruptions, re-alignment) is exercised in node with a scripted fake runtime
// (capture-xr-session.test.ts). The WebGL parts that only exist on a device are injected (lib/capture-xr-gl.ts).

import {
  type Alignment,
  type LiveTracking,
  type MotionReading,
  type PlanPose,
  type QuatLike,
  type TrackingEventState,
  type Vec3Like,
  PoseThrottle,
  TrackingMonitor,
  alignmentFromCorrespondence,
  applyAlignment,
  applyAlignmentToPose,
  classifyHit,
  motionBetween,
  wrapDegrees,
  xrPoseToPlan,
} from "./capture-tracking.ts";
import type { Frame } from "./capture-quality.ts";
import type { PoseSample, SurfaceSampleWire, TrackingEventWire } from "./hud-types.ts";

export interface RigidTransformLike { position: Vec3Like; orientation: QuatLike }
export interface ViewerPoseLike { transform: RigidTransformLike; emulatedPosition: boolean; views?: readonly unknown[] }
export interface HitResultLike { getPose(space: unknown): { transform: RigidTransformLike } | null | undefined }
export interface FrameLike {
  getViewerPose(space: unknown): ViewerPoseLike | null | undefined;
  getHitTestResults?(source: unknown): readonly HitResultLike[];
}
export interface HitSourceLike { cancel(): void }
export interface SessionLike {
  enabledFeatures?: readonly string[];
  visibilityState?: "visible" | "visible-blurred" | "hidden";
  requestReferenceSpace(type: string): Promise<unknown>;
  requestHitTestSource?(options: { space: unknown; offsetRay?: unknown }): Promise<HitSourceLike> | undefined;
  updateRenderState(state: { baseLayer?: unknown }): unknown;
  requestAnimationFrame(cb: (time: number, frame: FrameLike) => void): number;
  addEventListener(type: "end" | "visibilitychange", cb: () => void): void;
  end(): Promise<void>;
}
export interface XrLike {
  isSessionSupported(mode: string): Promise<boolean>;
  requestSession(mode: string, init: Record<string, unknown>): Promise<SessionLike>;
}
export interface BaseLayer {
  layer: unknown;
  /** Called once per frame before anything else: clears the layer (nothing is drawn). */
  beginFrame(): void;
  /** A downsampled camera image for the view, when raw camera access was granted. */
  readCamera?(frame: FrameLike, view: unknown): Frame | null;
  dispose(): void;
}
export interface CaptureXrDeps {
  xr: XrLike | undefined;
  createBaseLayer(session: SessionLike): Promise<BaseLayer | null>;
  /** An XRRay in viewer space with a horizontal direction (x, z); absent when the browser has no XRRay. */
  makeRay?(dirX: number, dirZ: number): unknown;
  now(): number;
  newFrameId(): string;
}
export interface CaptureXrCallbacks {
  onPose(sample: PoseSample): void;
  onSurface(sample: SurfaceSampleWire): void;
  onEvent(event: TrackingEventWire): void;
  onState(state: LiveTracking, detail?: string): void;
  onCameraFrame?(frame: Frame, timeMs: number): void;
  onMotion?(motion: MotionReading): void;
  /** The session is over (ended by the operator, the browser or the platform). */
  onEnded?(): void;
}

export type StartProblem = "WEBXR_UNAVAILABLE" | "IMMERSIVE_AR_UNSUPPORTED" | "PERMISSION_DENIED" | "SESSION_FAILED" | "NO_BASE_LAYER";
export type StartResult = { ok: true; frameId: string; features: string[] } | { ok: false; problem: StartProblem; detail: string };

/** Horizontal fan of hit-test rays (degrees from the view direction), cast in angular order so neighbours are consecutive. */
export const HIT_RAY_DEGREES = [-24, -12, 0, 12, 24];
export const HIT_INTERVAL_MS = 150;
export const CAMERA_INTERVAL_MS = 600;

/** Capability check for capture tracking: immersive AR is all that is required (hit testing and camera access are optional). */
export async function detectCaptureAr(xr: XrLike | undefined): Promise<{ supported: boolean; reason: string | null }> {
  if (!xr) return { supported: false, reason: "This browser does not expose WebXR (navigator.xr). AR tracking needs Chrome on an ARCore device, over HTTPS." };
  try {
    return (await xr.isSessionSupported("immersive-ar"))
      ? { supported: true, reason: null }
      : { supported: false, reason: "This device or browser reports immersive AR sessions as unsupported, so it cannot track its own position." };
  } catch (e) {
    return { supported: false, reason: `The browser failed while reporting its WebXR capabilities: ${e instanceof Error ? e.message : String(e)}` };
  }
}

export class CaptureXrSession {
  /** The tracking frame positions are reported in: this session's own, or the plan frame it was aligned to. */
  frameId = "";
  private sessionFrameId = "";
  private session: SessionLike | null = null;
  private refSpace: unknown = null;
  private hitSources: HitSourceLike[] = [];
  private layer: BaseLayer | null = null;
  private readonly monitor = new TrackingMonitor();
  private readonly throttle = new PoseThrottle();
  private alignment: Alignment | null = null;
  private lastHitAt = -Infinity;
  private lastCameraAt = -Infinity;
  private lastSent: (PlanPose & { timeMs: number }) | null = null;
  /** The newest tracked pose in the session's native frame (for re-alignment). */
  lastNative: PlanPose | null = null;
  private ended = false;
  features: string[] = [];

  private readonly deps: CaptureXrDeps;
  private readonly cb: CaptureXrCallbacks;

  constructor(deps: CaptureXrDeps, cb: CaptureXrCallbacks) {
    this.deps = deps;
    this.cb = cb;
  }

  private event(state: TrackingEventState, detail?: string, frameId: string | null = this.frameId || null) {
    this.cb.onEvent({ capturedAtMs: this.deps.now(), state, source: "webxr", frameId, detail: detail ?? null });
  }

  async start(overlayRoot?: unknown): Promise<StartResult> {
    const xr = this.deps.xr;
    if (!xr) {
      this.event("UNSUPPORTED", "navigator.xr is not available", null);
      return { ok: false, problem: "WEBXR_UNAVAILABLE", detail: "This browser does not expose WebXR." };
    }
    let session: SessionLike;
    try {
      session = await xr.requestSession("immersive-ar", {
        requiredFeatures: ["local"],
        optionalFeatures: ["local-floor", "hit-test", "dom-overlay", "camera-access"],
        ...(overlayRoot ? { domOverlay: { root: overlayRoot } } : {}),
      });
    } catch (e) {
      const name = (e as { name?: string } | null)?.name;
      const message = e instanceof Error ? e.message : String(e);
      if (name === "NotAllowedError" || name === "SecurityError") {
        this.event("PERMISSION_DENIED", message, null);
        return { ok: false, problem: "PERMISSION_DENIED", detail: `Camera or AR permission was refused (${message}). Allow it for this site and retry.` };
      }
      if (name === "NotSupportedError") {
        this.event("UNSUPPORTED", message, null);
        return { ok: false, problem: "IMMERSIVE_AR_UNSUPPORTED", detail: `The device refused an immersive AR session (${message}).` };
      }
      this.event("UNSUPPORTED", message, null);
      return { ok: false, problem: "SESSION_FAILED", detail: `The AR session could not be started: ${message}` };
    }
    this.session = session;
    this.features = [...(session.enabledFeatures ?? [])];
    this.sessionFrameId = this.deps.newFrameId();
    this.frameId = this.alignment ? this.frameId : this.sessionFrameId;
    try {
      this.layer = await this.deps.createBaseLayer(session);
      if (!this.layer) {
        await session.end();
        return { ok: false, problem: "NO_BASE_LAYER", detail: "WebGL could not be made XR-compatible on this device, so the session delivers no frames." };
      }
      await session.updateRenderState({ baseLayer: this.layer.layer });
      try {
        this.refSpace = await session.requestReferenceSpace("local-floor");
      } catch {
        this.refSpace = await session.requestReferenceSpace("local");
      }
      if (session.requestHitTestSource) {
        const viewer = await session.requestReferenceSpace("viewer");
        for (const deg of this.deps.makeRay ? HIT_RAY_DEGREES : [0]) {
          const r = (deg * Math.PI) / 180;
          const offsetRay = this.deps.makeRay?.(-Math.sin(r), -Math.cos(r));
          const src = await session.requestHitTestSource(offsetRay ? { space: viewer, offsetRay } : { space: viewer });
          if (src) this.hitSources.push(src);
        }
      }
    } catch (e) {
      await session.end().catch(() => undefined);
      return { ok: false, problem: "SESSION_FAILED", detail: `The AR session could not be set up: ${e instanceof Error ? e.message : String(e)}` };
    }
    session.addEventListener("end", () => this.onEnd());
    session.addEventListener("visibilitychange", () => {
      // The frame loop reports the transition; nothing arrives while hidden, so an explicit event is sent here too.
      if (session.visibilityState && session.visibilityState !== "visible") {
        const r = this.monitor.observe({ timeMs: this.deps.now(), pose: null, visibility: session.visibilityState });
        if (r.event) {
          this.event(r.event, r.detail);
          this.cb.onState(r.state, r.detail);
        }
      }
    });
    this.event("SESSION_STARTED", `features: ${this.features.join(", ") || "none reported"}`);
    session.requestAnimationFrame((t, f) => this.onFrame(t, f));
    return { ok: true, frameId: this.frameId, features: this.features };
  }

  private onFrame(_time: number, frame: FrameLike): void {
    const session = this.session;
    if (!session || this.ended) return;
    session.requestAnimationFrame((t, f) => this.onFrame(t, f));
    this.layer?.beginFrame();
    const now = this.deps.now();
    const viewer = frame.getViewerPose(this.refSpace) ?? null;
    const r = this.monitor.observe({
      timeMs: now,
      pose: viewer ? { position: viewer.transform.position, orientation: viewer.transform.orientation, emulatedPosition: viewer.emulatedPosition } : null,
      visibility: session.visibilityState ?? "visible",
    });
    if (r.event) {
      this.event(r.event, r.detail);
      this.cb.onState(r.state, r.detail);
      if (r.state !== "TRACKING") this.throttle.reset();
    }
    if (r.state !== "TRACKING" || !viewer) return; // never a position from an untracked or emulated pose

    const native = xrPoseToPlan(viewer.transform.position, viewer.transform.orientation);
    this.lastNative = native;
    const pose = this.alignment ? applyAlignmentToPose(this.alignment, native) : native;
    if (this.throttle.accept(pose, now)) {
      const timed = { ...pose, timeMs: now };
      if (this.lastSent) {
        const m = motionBetween(this.lastSent, timed);
        if (m) this.cb.onMotion?.(m);
      }
      this.lastSent = timed;
      this.cb.onPose({ capturedAtMs: now, x: pose.x, y: pose.y, yawDegrees: pose.yawDegrees, source: "webxr", frameId: this.frameId,
        frameAlignment: this.alignment ? "OPERATOR_ALIGNED" : "NATIVE" });
    }
    if (now - this.lastHitAt >= HIT_INTERVAL_MS && frame.getHitTestResults) {
      this.lastHitAt = now;
      for (const src of this.hitSources) {
        const hit = frame.getHitTestResults(src)[0]?.getPose(this.refSpace);
        if (!hit) continue;
        const s = classifyHit(viewer.transform.position, hit.transform.position, hit.transform.orientation);
        if (!s) continue;
        const o = this.alignment ? applyAlignment(this.alignment, { x: s.originX, y: s.originY }) : { x: s.originX, y: s.originY };
        const h = this.alignment ? applyAlignment(this.alignment, { x: s.hitX, y: s.hitY }) : { x: s.hitX, y: s.hitY };
        const n = s.normalDegrees === null ? null : this.alignment ? wrapDegrees(s.normalDegrees + this.alignment.thetaDegrees) : s.normalDegrees;
        this.cb.onSurface({ capturedAtMs: now, originX: o.x, originY: o.y, hitX: h.x, hitY: h.y, kind: s.kind, normalDegrees: n,
          source: "webxr", frameId: this.frameId });
      }
    }
    if (this.cb.onCameraFrame && this.layer?.readCamera && now - this.lastCameraAt >= CAMERA_INTERVAL_MS && viewer.views?.length) {
      this.lastCameraAt = now;
      const img = this.layer.readCamera(frame, viewer.views[0]);
      if (img) this.cb.onCameraFrame(img, now);
    }
  }

  /**
   * Aligns this session to a plan frame: the operator stands on a known plan pose (for example the last tracked position
   * of the previous session) and confirms. From then on positions and surfaces are reported in `planFrameId`, marked
   * OPERATOR_ALIGNED. Returns false when there is no current tracked pose to align.
   */
  realign(planFrameId: string, planPose: { x: number; y: number; yawDegrees: number }): boolean {
    const native = this.lastNative;
    if (!native || native.yawDegrees === null || this.monitor.state !== "TRACKING") return false;
    this.alignment = alignmentFromCorrespondence(planPose, { x: native.x, y: native.y, yawDegrees: native.yawDegrees });
    this.frameId = planFrameId;
    this.throttle.reset();
    this.lastSent = null;
    this.event("REALIGNED", `session ${this.sessionFrameId} aligned to ${planFrameId}: rotation ${this.alignment.thetaDegrees.toFixed(1)} deg, ` +
      `translation (${this.alignment.tx.toFixed(2)}, ${this.alignment.ty.toFixed(2)}) m from one operator-confirmed pose`);
    return true;
  }

  /** Prepares a session that is about to start to report in an existing frame (alignment is then set by realign()). */
  get aligned(): boolean {
    return this.alignment !== null;
  }

  get tracking(): LiveTracking {
    return this.monitor.state;
  }

  async stop(): Promise<void> {
    await this.session?.end().catch(() => undefined);
  }

  private onEnd(): void {
    if (this.ended) return;
    this.ended = true;
    for (const s of this.hitSources) {
      try {
        s.cancel();
      } catch {
        /* already gone with the session */
      }
    }
    this.hitSources = [];
    this.layer?.dispose();
    this.layer = null;
    this.session = null;
    this.event("SESSION_ENDED");
    this.cb.onState("INTERRUPTED", "the AR session ended");
    this.cb.onEnded?.();
  }
}
