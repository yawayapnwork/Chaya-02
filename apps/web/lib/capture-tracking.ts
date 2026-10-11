// Live positioning for capture planning: turning what the device's own tracker reports into plan-view positions,
// surface observations and tracking-state events (docs/capture-hud.md section 2). Pure: no DOM, no WebXR objects, so
// every rule is unit tested (capture-tracking.test.ts). lib/capture-xr-session.ts feeds it real WebXR frames.
//
// What WebXR on an ARCore device reports, and the only things used here:
//   * the viewer pose in a "local-floor" or "local" reference space: metric, gravity-aligned (+Y up), right-handed, origin
//     where the session started. It exists only for that session, so every position carries the session's frameId.
//   * XRPose.emulatedPosition: true when the position is not tracked (orientation-only). Such a pose is never a position.
//   * hit-test results: a point on a surface the platform detected, with the surface normal as the pose's +Y axis.
// Plan view: x = device x, y = -device z (so the session's initial forward is plan +y), headings counter-clockwise from +x.

export interface Vec3Like { x: number; y: number; z: number }
export interface QuatLike { x: number; y: number; z: number; w: number }

export interface PlanPose {
  x: number;
  y: number;
  /** null when the camera points (almost) straight up or down, so it has no meaningful plan-view heading. */
  yawDegrees: number | null;
}

/** Rotates v by unit quaternion q. */
export function rotateVec(q: QuatLike, v: Vec3Like): Vec3Like {
  const { x, y, z, w } = q;
  // t = 2 * cross(q.xyz, v); v' = v + w t + cross(q.xyz, t)
  const tx = 2 * (y * v.z - z * v.y);
  const ty = 2 * (z * v.x - x * v.z);
  const tz = 2 * (x * v.y - y * v.x);
  return {
    x: v.x + w * tx + (y * tz - z * ty),
    y: v.y + w * ty + (z * tx - x * tz),
    z: v.z + w * tz + (x * ty - y * tx),
  };
}

export function wrapDegrees(a: number): number {
  const r = ((a % 360) + 360) % 360;
  return r > 180 ? r - 360 : r;
}

const MAX_FORWARD_TILT = 0.95; // |forward.y| above this: pointing at the floor or ceiling, heading undefined

/** WebXR viewer pose (reference-space position + orientation) to a plan-view pose. */
export function xrPoseToPlan(position: Vec3Like, orientation: QuatLike): PlanPose {
  const f = rotateVec(orientation, { x: 0, y: 0, z: -1 }); // the camera looks down its -Z axis
  const horizontal = Math.hypot(f.x, f.z);
  const yaw = Math.abs(f.y) > MAX_FORWARD_TILT || horizontal < 1e-6 ? null : (Math.atan2(-f.z, f.x) * 180) / Math.PI;
  return { x: position.x, y: -position.z, yawDegrees: yaw };
}

export interface SurfaceSample {
  originX: number;
  originY: number;
  hitX: number;
  hitY: number;
  kind: "WALL" | "FLOOR";
  normalDegrees: number | null;
}

/** Normal within this of horizontal (|n.y| below it): a wall. Above FLOOR_NORMAL_Y and below the camera: floor. */
const WALL_MAX_NORMAL_Y = 0.3;
const FLOOR_MIN_NORMAL_Y = 0.85;
const FLOOR_MIN_DROP_METERS = 0.5;

/**
 * Classifies a hit-test result. The hit pose's +Y axis is the surface normal. Anything that is neither a wall nor the
 * floor (a table top, a ceiling, a slanted surface) is not used: null.
 */
export function classifyHit(viewer: Vec3Like, hitPosition: Vec3Like, hitOrientation: QuatLike): SurfaceSample | null {
  const n = rotateVec(hitOrientation, { x: 0, y: 1, z: 0 });
  const len = Math.hypot(n.x, n.y, n.z);
  if (!(len > 0) || ![viewer.x, viewer.y, viewer.z, hitPosition.x, hitPosition.y, hitPosition.z].every(Number.isFinite)) return null;
  const ny = n.y / len;
  const base = { originX: viewer.x, originY: -viewer.z, hitX: hitPosition.x, hitY: -hitPosition.z };
  if (Math.abs(ny) < WALL_MAX_NORMAL_Y) {
    return { ...base, kind: "WALL", normalDegrees: (Math.atan2(-n.z, n.x) * 180) / Math.PI };
  }
  if (ny > FLOOR_MIN_NORMAL_Y && viewer.y - hitPosition.y > FLOOR_MIN_DROP_METERS) {
    return { ...base, kind: "FLOOR", normalDegrees: null };
  }
  return null;
}

// ---- tracking state -----------------------------------------------------------------------------------------------

export type TrackingEventState =
  | "SESSION_STARTED" | "TRACKING" | "LIMITED" | "LOST" | "INTERRUPTED" | "RESUMED" | "SESSION_ENDED"
  | "PERMISSION_DENIED" | "UNSUPPORTED" | "ORIENTATION_CHANGED" | "REALIGNED";

export interface FrameObservation {
  timeMs: number;
  /** null: the platform returned no viewer pose this frame. */
  pose: { position: Vec3Like; orientation: QuatLike; emulatedPosition: boolean } | null;
  visibility: "visible" | "visible-blurred" | "hidden";
}

export type LiveTracking = "NOT_STARTED" | "TRACKING" | "LIMITED" | "LOST" | "INTERRUPTED";

/** A missing pose is only a loss after this long: a single dropped frame is not. */
export const LOST_AFTER_MS = 400;

/**
 * Turns per-frame observations into tracking-state transitions. Emits an event only when the state changes, so a
 * session produces a handful of events, not one per frame.
 */
export class TrackingMonitor {
  state: LiveTracking = "NOT_STARTED";
  private missingSince: number | null = null;

  observe(f: FrameObservation): { state: LiveTracking; event: TrackingEventState | null; detail?: string } {
    let next: LiveTracking;
    let detail: string | undefined;
    if (f.visibility !== "visible") {
      next = "INTERRUPTED";
      detail = `the AR session is ${f.visibility}`;
    } else if (!f.pose) {
      this.missingSince ??= f.timeMs;
      next = f.timeMs - this.missingSince >= LOST_AFTER_MS ? "LOST" : this.state === "NOT_STARTED" ? "NOT_STARTED" : this.state;
      if (next === "LOST") detail = "the platform reports no device pose";
    } else if (f.pose.emulatedPosition) {
      this.missingSince = null;
      next = "LIMITED";
      detail = "position is not tracked (orientation only): too little texture or light, or moving too fast";
    } else {
      this.missingSince = null;
      next = "TRACKING";
    }
    if (next === this.state) return { state: next, event: null };
    const prev = this.state;
    this.state = next;
    let event: TrackingEventState | null;
    if (next === "TRACKING") event = prev === "INTERRUPTED" ? "RESUMED" : "TRACKING";
    else if (next === "NOT_STARTED") event = null;
    else event = next;
    return { state: next, event, detail };
  }
}

// ---- how often to send ----------------------------------------------------------------------------------------------

export interface ThrottleRules { minIntervalMs: number; maxIntervalMs: number; minMoveMeters: number; minTurnDegrees: number }
export const POSE_THROTTLE: ThrottleRules = { minIntervalMs: 200, maxIntervalMs: 1000, minMoveMeters: 0.1, minTurnDegrees: 10 };

/** Keeps a position if it is new enough: moved or turned enough, or the last one sent is getting old. */
export class PoseThrottle {
  private last: (PlanPose & { timeMs: number }) | null = null;
  private readonly rules: ThrottleRules;

  constructor(rules: ThrottleRules = POSE_THROTTLE) {
    this.rules = rules;
  }

  accept(p: PlanPose, timeMs: number): boolean {
    const l = this.last;
    if (l && timeMs - l.timeMs < this.rules.minIntervalMs) return false;
    const moved = !l || Math.hypot(p.x - l.x, p.y - l.y) >= this.rules.minMoveMeters;
    const turned = !l || (p.yawDegrees !== null && l.yawDegrees !== null && Math.abs(wrapDegrees(p.yawDegrees - l.yawDegrees)) >= this.rules.minTurnDegrees);
    const due = !l || timeMs - l.timeMs >= this.rules.maxIntervalMs;
    if (!(moved || turned || due)) return false;
    this.last = { ...p, timeMs };
    return true;
  }

  reset(): void {
    this.last = null;
  }
}

// ---- motion: blur risk from the tracked pose itself ----------------------------------------------------------------

export const MAX_WALK_SPEED_MPS = 1.0;
export const MAX_TURN_RATE_DPS = 60;

export interface MotionReading { speedMps: number; turnRateDps: number | null; warnings: ("MOVING_TOO_FAST" | "TURNING_TOO_FAST")[] }

/** Speed and turn rate between two tracked poses; null when they are too close in time to say. */
export function motionBetween(a: PlanPose & { timeMs: number }, b: PlanPose & { timeMs: number }): MotionReading | null {
  const dt = (b.timeMs - a.timeMs) / 1000;
  if (!(dt >= 0.05)) return null;
  const speedMps = Math.hypot(b.x - a.x, b.y - a.y) / dt;
  const turnRateDps = a.yawDegrees === null || b.yawDegrees === null ? null : Math.abs(wrapDegrees(b.yawDegrees - a.yawDegrees)) / dt;
  const warnings: MotionReading["warnings"] = [];
  if (speedMps > MAX_WALK_SPEED_MPS) warnings.push("MOVING_TOO_FAST");
  if (turnRateDps !== null && turnRateDps > MAX_TURN_RATE_DPS) warnings.push("TURNING_TOO_FAST");
  return { speedMps, turnRateDps, warnings };
}

// ---- re-alignment of a new tracking session ------------------------------------------------------------------------

/** A plan-view rigid transform: rotate by thetaDegrees (counter-clockwise), then translate. */
export interface Alignment { thetaDegrees: number; tx: number; ty: number }

/**
 * The transform that puts a device pose (in a new session's frame) onto a known plan pose: the operator stands at a
 * spot whose plan position and facing are known (for example the last tracked position before the session was lost)
 * and confirms. One correspondence fixes a 2D rigid transform; its error is the operator's placement error, which the
 * HUD states rather than hides.
 */
export function alignmentFromCorrespondence(plan: { x: number; y: number; yawDegrees: number },
                                            device: { x: number; y: number; yawDegrees: number }): Alignment {
  const theta = wrapDegrees(plan.yawDegrees - device.yawDegrees);
  const t = (theta * Math.PI) / 180;
  const rx = Math.cos(t) * device.x - Math.sin(t) * device.y;
  const ry = Math.sin(t) * device.x + Math.cos(t) * device.y;
  return { thetaDegrees: theta, tx: plan.x - rx, ty: plan.y - ry };
}

export function applyAlignment(a: Alignment, p: { x: number; y: number }): { x: number; y: number } {
  const t = (a.thetaDegrees * Math.PI) / 180;
  return { x: Math.cos(t) * p.x - Math.sin(t) * p.y + a.tx, y: Math.sin(t) * p.x + Math.cos(t) * p.y + a.ty };
}

export function applyAlignmentToPose(a: Alignment, p: PlanPose): PlanPose {
  const q = applyAlignment(a, p);
  return { x: q.x, y: q.y, yawDegrees: p.yawDegrees === null ? null : wrapDegrees(p.yawDegrees + a.thetaDegrees) };
}

// ---- manual mode: the camera's heading from device orientation ------------------------------------------------------

/**
 * Heading of the rear camera's viewing direction, in degrees clockwise from the DeviceOrientation frame's reference
 * (true north when `absolute`, else wherever the browser started), from the W3C DeviceOrientation Euler angles. The
 * camera looks along the device's -Z axis, so the result does not depend on how the screen is rotated. null when the
 * camera points (almost) straight up or down.
 */
export function cameraHeadingFromEuler(alphaDeg: number, betaDeg: number, gammaDeg: number): number | null {
  const d = Math.PI / 180;
  const a = alphaDeg * d, b = betaDeg * d, g = gammaDeg * d;
  const ca = Math.cos(a), sa = Math.sin(a), cb = Math.cos(b), sb = Math.sin(b), cg = Math.cos(g), sg = Math.sin(g);
  // R = Rz(alpha) Rx(beta) Ry(gamma); the camera direction is R * (0, 0, -1) in the earth frame (x east, y north, z up).
  const x = -(ca * sg + sa * sb * cg);
  const y = -(sa * sg - ca * sb * cg);
  const z = -(cb * cg);
  if (Math.hypot(x, y) < Math.abs(z) * 0.3) return null;
  const heading = (Math.atan2(x, y) * 180) / Math.PI; // clockwise from +y (north)
  return ((heading % 360) + 360) % 360;
}

/**
 * A device heading turned into a plan yaw, given a calibration: the heading measured while the operator faced plan +x.
 * Without a calibration there is no relation between the device's heading reference and the drawn plan: null.
 */
export function headingToPlanYaw(heading: number | null, headingAtPlusX: number | null): number | null {
  if (heading === null || headingAtPlusX === null) return null;
  return wrapDegrees(headingAtPlusX - heading); // clockwise headings, counter-clockwise plan yaw
}
