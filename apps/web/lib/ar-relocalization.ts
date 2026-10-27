// The Android AR session's localization/tracking state machine (docs/ar.md, "Tracking failure"). Pure: the WebXR
// frame loop and the relocalization request feed it events; the UI and route renderer read it.
//
//   IDLE --SESSION_STARTED--> SEARCHING --MARKERS_OBSERVED--> RELOCALIZING --RELOCALIZATION_SUCCEEDED--> LOCALIZED
//                                               (RELOCALIZATION_FAILED or TRACKING_LOST: back to where it came from)
//   LOCALIZED --TRACKING_LOST or DRIFT_EXCEEDED--> TRACKING_LOST --MARKERS_OBSERVED--> RELOCALIZING --...--> LOCALIZED
//   LOCALIZED --MARKERS_OBSERVED--> LOCALIZED with a refresh pending (re-solve from a marker seen again; the current
//                                   transform is kept until the answer, and kept if the answer is refused)
//   LOCALIZED --FLOOR_HANDOFF--> FLOOR_TRANSITION --MARKERS_OBSERVED (the floor identified, lib/ar-floor-identification)-->
//                                RELOCALIZING --> LOCALIZED
//   SEARCHING / FLOOR_TRANSITION with another floor's markers only (UNEXPECTED) --ACCEPT_DETECTED_FLOOR--> the same state on
//                                the detected floor (the route is then planned again from there)
//   LOCALIZED --TRACKING_LIMITED--> LIMITED --TRACKING_RESTORED (recovery validated)--> LOCALIZED
//   LIMITED --TICK after MAX_LIMITED_MS, TRACKING_LOST, or TRACKING_RESTORED with a failed check--> TRACKING_LOST
//   anything --SESSION_ENDED--> ENDED
//
// `deviceTracking` is the platform's own per-frame verdict (TRACKING_LOST / TRACKING_RESTORED). Before the first
// localization there is nothing to lose, so a loss in SEARCHING (ARCore reports emulated positions while it initializes,
// at every session start) only records that the device is not tracking yet; it is not shown as a lost localization.
//
// Rules this encodes:
//   * Observed marker poses and the device->venue transform are different things with different types:
//     `pending.observations` holds what the camera saw; `localization.deviceToVenue` holds only what the server solved
//     from those observations.
//   * The route advances only in LOCALIZED (`canAdvanceRoute`).
//   * Limited tracking (the platform reports an emulated position: it still tracks orientation, but not position) is held
//     briefly, as on iOS: the route stays drawn where it was (frozen), progress stops, and the user is told to stand still.
//     It returns to LOCALIZED only when 6-DoF tracking returns within MAX_LIMITED_MS AND the recovery is validated: the
//     world anchor the transform is attached to is tracked again and moved less than RECOVERY_MAX_JUMP_METERS during the
//     spell (a larger move means the platform re-anchored its map, and the old transform is no longer trusted).
//   * Tracking loss is sticky. When the platform's tracking comes back, the state stays TRACKING_LOST until a registered
//     marker is observed again and the server relocalizes from it. The device's AR world frame may have been reset or
//     re-anchored by the platform during the loss, so the old transform is not trusted again.
//   * A relocalization answer for a request that is no longer pending (tracking was lost meanwhile, or a newer request
//     replaced it) is ignored.
//   * A localization belongs to one floor and one scan version. Each floor's version is pinned at its first
//     localization; an answer in another version (the floor was republished mid-session), or for another floor, is
//     refused (VERSION_MISMATCH / FLOOR_MISMATCH) and never replaces the transform.

import type { Pose } from "./ar-anchor-math.ts";
import { type FloorCheck, MIN_FLOOR_SIGHTINGS, identifyFloor, pushFloorWindow } from "./ar-floor-identification.ts";
import { gravityTiltDegrees } from "./ar-frame-boundary.ts";
import type { MarkerObservation } from "./ar-marker-tracking.ts";

export type ArState = "IDLE" | "SEARCHING" | "RELOCALIZING" | "LOCALIZED" | "LIMITED" | "TRACKING_LOST" | "FLOOR_TRANSITION" | "ENDED";

/** How long a localized session may stay LIMITED (position emulated) before the localization is considered lost. Same as
 * iOS (RelocalizationStateMachine.maxLimitedSeconds). */
export const MAX_LIMITED_MS = 3000;
/** How long a pending relocalization request may stay in flight before timing out on TICK. */
export const RELOCALIZATION_TIMEOUT_MS = 8000;
/** Maximum allowed tilt (degrees) between device +Y up and canonical +Z up in a solved deviceToVenue transform. */
export const MAX_GRAVITY_TILT_DEGREES = 15;
/** A recovery from LIMITED in which the world anchor moved more than this is not trusted (the platform re-anchored). */
export const RECOVERY_MAX_JUMP_METERS = 0.3;

export function isValidTransform(pose: Pose): { ok: true } | { ok: false; reason: string } {
  if (
    !Number.isFinite(pose.x) || !Number.isFinite(pose.y) || !Number.isFinite(pose.z) ||
    !Number.isFinite(pose.qx) || !Number.isFinite(pose.qy) || !Number.isFinite(pose.qz) || !Number.isFinite(pose.qw)
  ) {
    return { ok: false, reason: "transform contains non-finite numbers" };
  }
  const norm = Math.hypot(pose.qx, pose.qy, pose.qz, pose.qw);
  if (norm < 1e-6) {
    return { ok: false, reason: "transform orientation has zero quaternion norm" };
  }
  const tilt = gravityTiltDegrees(pose);
  if (!Number.isFinite(tilt) || tilt > MAX_GRAVITY_TILT_DEGREES) {
    return {
      ok: false,
      reason: `transform tilts device up (+Y) by ${tilt.toFixed(1)}° from canonical +Z (limit ${MAX_GRAVITY_TILT_DEGREES}°)`,
    };
  }
  return { ok: true };
}

/** A server-solved localization (POST .../anchors/relocalize). It is only valid on `floorId` (every floor has its own
 * canonical frame) and in the scan version and coordinate frame the server solved it in. */
export interface Localization {
  deviceToVenue: Pose;
  anchorIds: string[];
  residualMeters: number | null;
  coordinateFrameId: string;
  scanVersionId: string;
  floorId: string;
  solvedAtMs: number;
}

type ResumeTo = "SEARCHING" | "TRACKING_LOST" | "FLOOR_TRANSITION" | "LOCALIZED";

export interface ArSessionState {
  state: ArState;
  /** The floor the session is localizing on now. Observations of another floor's markers are ignored. */
  floorId: string | null;
  /** The scan version each floor was first localized in. A later answer in another version is refused: the route and the
   * anchors this session holds belong to the pinned one (the floor was republished mid-session). */
  pinnedVersions: Record<string, string>;
  /** The current localization, or the last confirmed one while TRACKING_LOST (kept for display, never used to advance). */
  localization: Localization | null;
  /** An outstanding relocalization. resumeTo LOCALIZED is a refresh: the session stays LOCALIZED on the current transform
   * while a re-solve from a newly seen marker is in flight. */
  pending: { requestId: number; observations: MarkerObservation[]; resumeTo: ResumeTo; startedAtMs?: number | null } | null;
  /** Whether the platform reported 6-DoF device tracking on the last frame. */
  deviceTracking: boolean;
  /** Why device tracking was last reported lost (kept while TRACKING_LOST, cleared on a new localization). */
  lostReason: string | null;
  /** When LIMITED began (ms, the frame clock), and why. */
  limitedSinceMs: number | null;
  limitedReason: string | null;
  lastError: string | null;
  nextRequestId: number;
  /** Floors whose markers were seen in recent frames, while the session is establishing which floor it is on. */
  floorWindow: string[][];
  /** Why the session has not localized yet although markers were seen: another floor's markers (UNEXPECTED), several
   * floors' markers (AMBIGUOUS), or not enough sightings (PENDING). null once identified. */
  floorCheck: FloorCheck | null;
}

export type ArEvent =
  | { type: "SESSION_STARTED"; floorId?: string }
  | { type: "MARKERS_OBSERVED"; observations: MarkerObservation[] }
  | { type: "RELOCALIZATION_SUCCEEDED"; requestId: number; localization: Localization }
  | { type: "RELOCALIZATION_FAILED"; requestId: number; error: string }
  | { type: "TRACKING_LOST"; reason: string }
  | { type: "TRACKING_LIMITED"; reason: string; atMs: number }
  /** 6-DoF tracking is back. From LIMITED, `anchorJumpMeters` is how far the world anchor moved during the spell (null: the
   * anchor is not tracked, so the recovery cannot be validated). */
  | { type: "TRACKING_RESTORED"; anchorJumpMeters?: number | null }
  | { type: "TICK"; atMs: number }
  | { type: "DRIFT_EXCEEDED"; reason: string }
  | { type: "FLOOR_HANDOFF"; toFloorId: string }
  /** The user confirmed being on the floor whose markers the camera sees (floorCheck UNEXPECTED). */
  | { type: "ACCEPT_DETECTED_FLOOR" }
  | { type: "SESSION_ENDED" };

export function initialArSessionState(): ArSessionState {
  return {
    state: "IDLE", floorId: null, pinnedVersions: {}, localization: null, pending: null, deviceTracking: false,
    lostReason: null, limitedSinceMs: null, limitedReason: null, lastError: null, nextRequestId: 1, floorWindow: [], floorCheck: null,
  };
}

export function canAdvanceRoute(s: ArSessionState): boolean {
  return s.state === "LOCALIZED" && s.localization !== null;
}

/** The route is drawn while localized, and held (frozen, not advancing) through a short LIMITED spell. */
export function routeShown(s: ArSessionState): boolean {
  return (s.state === "LOCALIZED" || s.state === "LIMITED") && s.localization !== null;
}

const lostFromLimited = (s: ArSessionState, reason: string): ArSessionState =>
  ({ ...s, state: "TRACKING_LOST", pending: null, deviceTracking: false, lostReason: reason, limitedSinceMs: null, limitedReason: null });

/** Why a server answer cannot be used in this session, or null. */
function refusal(s: ArSessionState, l: Localization): string | null {
  const valid = isValidTransform(l.deviceToVenue);
  if (!valid.ok) {
    return `INVALID_TRANSFORM: ${valid.reason}`;
  }
  if (s.floorId !== null && l.floorId !== s.floorId) {
    return `FLOOR_MISMATCH: solved on floor ${l.floorId}, but the session is on floor ${s.floorId}`;
  }
  const pinned = s.pinnedVersions[l.floorId];
  if (pinned !== undefined && pinned !== l.scanVersionId) {
    return `VERSION_MISMATCH: floor ${l.floorId} was localized in scan version ${pinned}, but the server now answers in ` +
      `${l.scanVersionId} (the floor was republished); restart AR to load the new version`;
  }
  return null;
}

/** Pure reducer. An event that does not apply in the current state returns the same object: a stray platform event must
 * not crash a live session or skip a state. */
export function reduceArSession(s: ArSessionState, e: ArEvent): ArSessionState {
  if (s.state === "ENDED") return s;
  switch (e.type) {
    case "SESSION_STARTED":
      return s.state === "IDLE" ? { ...s, state: "SEARCHING", deviceTracking: false, floorId: e.floorId ?? null } : s;
    case "MARKERS_OBSERVED": {
      // Establishing the floor (session start, or after a floor change): localize only once the floor is identified.
      if ((s.state === "SEARCHING" || s.state === "FLOOR_TRANSITION") && s.floorId !== null) {
        if (!s.deviceTracking || e.observations.length === 0) return s;
        const expected = s.floorId;
        const floorWindow = pushFloorWindow(s.floorWindow, e.observations.map((o) => o.floorId ?? expected));
        const check = identifyFloor(floorWindow, expected, s.state === "FLOOR_TRANSITION" ? MIN_FLOOR_SIGHTINGS : 1);
        if (check.status !== "CONFIRMED") return { ...s, floorWindow, floorCheck: check };
        const observations = e.observations.filter((o) => (o.floorId ?? expected) === expected);
        const startedAtMs = observations[0]?.timestampMs ?? null;
        return {
          ...s,
          state: "RELOCALIZING",
          floorWindow: [],
          floorCheck: null,
          pending: { requestId: s.nextRequestId, observations, resumeTo: s.state, startedAtMs },
          nextRequestId: s.nextRequestId + 1,
        };
      }
      // only this floor's markers, and an observation is only a measurement while the device itself is tracked
      const observations = e.observations.filter((o) => s.floorId === null || o.floorId === undefined || o.floorId === s.floorId);
      if (!s.deviceTracking || observations.length === 0) return s;
      const startedAtMs = observations[0]?.timestampMs ?? null;
      if (s.state === "LOCALIZED") {
        if (s.pending) return s; // one refresh at a time
        return { ...s, pending: { requestId: s.nextRequestId, observations, resumeTo: "LOCALIZED", startedAtMs }, nextRequestId: s.nextRequestId + 1 };
      }
      if (s.state !== "SEARCHING" && s.state !== "TRACKING_LOST" && s.state !== "FLOOR_TRANSITION") return s;
      return {
        ...s,
        state: "RELOCALIZING",
        pending: { requestId: s.nextRequestId, observations, resumeTo: s.state, startedAtMs },
        nextRequestId: s.nextRequestId + 1,
      };
    }
    case "RELOCALIZATION_SUCCEEDED": {
      if (s.pending?.requestId !== e.requestId || (s.state !== "RELOCALIZING" && s.state !== "LOCALIZED")) return s;
      const refused = refusal(s, e.localization);
      if (refused) {
        return s.state === "LOCALIZED"
          ? { ...s, pending: null, lastError: refused }
          : { ...s, state: s.pending.resumeTo, pending: null, lastError: refused };
      }
      return {
        ...s, state: "LOCALIZED", localization: e.localization, pending: null, lostReason: null, lastError: null,
        floorId: e.localization.floorId,
        pinnedVersions: { ...s.pinnedVersions, [e.localization.floorId]: e.localization.scanVersionId },
      };
    }
    case "RELOCALIZATION_FAILED":
      if (s.pending?.requestId !== e.requestId) return s;
      if (s.state === "LOCALIZED") return { ...s, pending: null, lastError: e.error }; // a failed refresh keeps the transform
      if (s.state !== "RELOCALIZING") return s;
      return { ...s, state: s.pending.resumeTo, pending: null, lastError: e.error };
    case "TRACKING_LIMITED":
      if (s.state === "LOCALIZED") {
        return { ...s, state: "LIMITED", pending: null, deviceTracking: false, limitedSinceMs: e.atMs, limitedReason: e.reason };
      }
      if (s.state === "LIMITED") return s;
      // Not localized: a limited device is simply not tracking yet (or still lost).
      return reduceArSession(s, { type: "TRACKING_LOST", reason: e.reason });
    case "TICK":
      if (s.state === "LIMITED" && s.limitedSinceMs !== null && e.atMs - s.limitedSinceMs > MAX_LIMITED_MS) {
        return lostFromLimited(s, `tracking was limited for more than ${MAX_LIMITED_MS / 1000} s (${s.limitedReason})`);
      }
      if (s.pending && s.pending.startedAtMs != null && e.atMs - s.pending.startedAtMs > RELOCALIZATION_TIMEOUT_MS) {
        const timeoutError = `RELOCALIZATION_TIMEOUT: relocalization request timed out after ${RELOCALIZATION_TIMEOUT_MS / 1000} s`;
        return s.state === "RELOCALIZING"
          ? { ...s, state: s.pending.resumeTo, pending: null, lastError: timeoutError }
          : { ...s, pending: null, lastError: timeoutError };
      }
      return s;
    case "TRACKING_LOST":
      if (s.state === "LIMITED") return lostFromLimited(s, e.reason);
      if (s.state === "IDLE" || (!s.deviceTracking && s.lostReason === e.reason)) return s;
      if (s.state === "LOCALIZED") return { ...s, state: "TRACKING_LOST", pending: null, deviceTracking: false, lostReason: e.reason };
      if (s.state === "RELOCALIZING") {
        // the observation that request was made from is no longer in a trustworthy frame
        return { ...s, state: s.pending?.resumeTo ?? "SEARCHING", pending: null, deviceTracking: false, lostReason: e.reason };
      }
      return { ...s, deviceTracking: false, lostReason: e.reason };
    case "TRACKING_RESTORED":
      if (s.state === "LIMITED") {
        const jump = e.anchorJumpMeters;
        if (jump === undefined || jump === null) {
          return lostFromLimited(s, "tracking returned, but the world anchor the route is attached to is not tracked: relocalize");
        }
        if (jump > RECOVERY_MAX_JUMP_METERS) {
          return lostFromLimited(s, `tracking returned, but the platform moved the route's world anchor ${jump.toFixed(2)} m meanwhile ` +
            "(it re-anchored its map): relocalize");
        }
        return { ...s, state: "LOCALIZED", deviceTracking: true, limitedSinceMs: null, limitedReason: null };
      }
      // Device tracking is back, but TRACKING_LOST stays until a marker is seen again and the server relocalizes.
      return s.deviceTracking || s.state === "IDLE" ? s : { ...s, deviceTracking: true };
    case "DRIFT_EXCEEDED":
      // The platform still tracks, but a registered marker is no longer where the transform puts it: the transform has
      // drifted. It is dropped like a loss; the marker in view relocalizes the session from scratch.
      if (s.state === "LIMITED") return lostFromLimited(s, e.reason);
      if (s.state !== "LOCALIZED") return s;
      return { ...s, state: "TRACKING_LOST", pending: null, lostReason: e.reason };
    case "FLOOR_HANDOFF":
      // A transform is valid on one floor only (each floor has its own canonical frame). The next floor starts unlocalized
      // and is localized from one of its own markers.
      if (s.state !== "LOCALIZED" || e.toFloorId === s.floorId) return s; // never from LIMITED: progress is frozen there
      return { ...s, state: "FLOOR_TRANSITION", floorId: e.toFloorId, localization: null, pending: null, lostReason: null, lastError: null,
        floorWindow: [], floorCheck: null };
    case "ACCEPT_DETECTED_FLOOR":
      // The user is where the markers say. The session localizes on that floor; the caller plans the route again.
      if (s.floorCheck?.status !== "UNEXPECTED" || (s.state !== "SEARCHING" && s.state !== "FLOOR_TRANSITION")) return s;
      return { ...s, floorId: s.floorCheck.detectedFloorId, floorCheck: null };
    case "SESSION_ENDED":
      return { ...s, state: "ENDED", pending: null };
    default:
      return s;
  }
}

/** The platform's own verdict on device tracking for one frame. No viewer pose, or a hidden session, is a loss. A viewer
 * pose whose position is emulated (the platform has fallen back to orientation-only tracking) is LIMITED: 6-DoF tracking
 * is not available this frame, but the platform still holds its world, so a localized session may survive it briefly. */
export function frameTrackingSignal(viewerPose: { emulatedPosition: boolean } | null, visibility: string = "visible"): {
  ok: boolean;
  kind: "OK" | "LIMITED" | "LOST";
  reason: string | null;
} {
  if (visibility !== "visible") return { ok: false, kind: "LOST", reason: `the AR session is ${visibility}` };
  if (!viewerPose) return { ok: false, kind: "LOST", reason: "the platform reports no device pose" };
  if (viewerPose.emulatedPosition) return { ok: false, kind: "LIMITED", reason: "the platform lost positional tracking (position is emulated)" };
  return { ok: true, kind: "OK", reason: null };
}
