// The Android AR session's localization/tracking state machine (docs/ar.md, "Tracking failure"). Pure: the WebXR
// frame loop and the relocalization request feed it events; the UI and route renderer read it.
//
//   IDLE --SESSION_STARTED--> SEARCHING --MARKERS_OBSERVED--> RELOCALIZING --RELOCALIZATION_SUCCEEDED--> LOCALIZED
//                                               (RELOCALIZATION_FAILED or TRACKING_LOST: back to where it came from)
//   LOCALIZED --TRACKING_LOST or DRIFT_EXCEEDED--> TRACKING_LOST --MARKERS_OBSERVED--> RELOCALIZING --...--> LOCALIZED
//   LOCALIZED --MARKERS_OBSERVED--> LOCALIZED with a refresh pending (re-solve from a marker seen again; the current
//                                   transform is kept until the answer, and kept if the answer is refused)
//   LOCALIZED --FLOOR_HANDOFF--> FLOOR_TRANSITION --MARKERS_OBSERVED (new floor's markers)--> RELOCALIZING --> LOCALIZED
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
//   * Tracking loss is sticky. When the platform's tracking comes back, the state stays TRACKING_LOST until a registered
//     marker is observed again and the server relocalizes from it. The device's AR world frame may have been reset or
//     re-anchored by the platform during the loss, so the old transform is not trusted again.
//   * A relocalization answer for a request that is no longer pending (tracking was lost meanwhile, or a newer request
//     replaced it) is ignored.
//   * A localization belongs to one floor and one scan version. Each floor's version is pinned at its first
//     localization; an answer in another version (the floor was republished mid-session), or for another floor, is
//     refused (VERSION_MISMATCH / FLOOR_MISMATCH) and never replaces the transform.

import type { Pose } from "./ar-anchor-math.ts";
import type { MarkerObservation } from "./ar-marker-tracking.ts";

export type ArState = "IDLE" | "SEARCHING" | "RELOCALIZING" | "LOCALIZED" | "TRACKING_LOST" | "FLOOR_TRANSITION" | "ENDED";

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
  pending: { requestId: number; observations: MarkerObservation[]; resumeTo: ResumeTo } | null;
  /** Whether the platform reported 6-DoF device tracking on the last frame. */
  deviceTracking: boolean;
  /** Why device tracking was last reported lost (kept while TRACKING_LOST, cleared on a new localization). */
  lostReason: string | null;
  lastError: string | null;
  nextRequestId: number;
}

export type ArEvent =
  | { type: "SESSION_STARTED"; floorId?: string }
  | { type: "MARKERS_OBSERVED"; observations: MarkerObservation[] }
  | { type: "RELOCALIZATION_SUCCEEDED"; requestId: number; localization: Localization }
  | { type: "RELOCALIZATION_FAILED"; requestId: number; error: string }
  | { type: "TRACKING_LOST"; reason: string }
  | { type: "TRACKING_RESTORED" }
  | { type: "DRIFT_EXCEEDED"; reason: string }
  | { type: "FLOOR_HANDOFF"; toFloorId: string }
  | { type: "SESSION_ENDED" };

export function initialArSessionState(): ArSessionState {
  return {
    state: "IDLE", floorId: null, pinnedVersions: {}, localization: null, pending: null, deviceTracking: false,
    lostReason: null, lastError: null, nextRequestId: 1,
  };
}

export function canAdvanceRoute(s: ArSessionState): boolean {
  return s.state === "LOCALIZED" && s.localization !== null;
}

/** Why a server answer cannot be used in this session, or null. */
function refusal(s: ArSessionState, l: Localization): string | null {
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
      // only this floor's markers, and an observation is only a measurement while the device itself is tracked
      const observations = e.observations.filter((o) => s.floorId === null || o.floorId === undefined || o.floorId === s.floorId);
      if (!s.deviceTracking || observations.length === 0) return s;
      if (s.state === "LOCALIZED") {
        if (s.pending) return s; // one refresh at a time
        return { ...s, pending: { requestId: s.nextRequestId, observations, resumeTo: "LOCALIZED" }, nextRequestId: s.nextRequestId + 1 };
      }
      if (s.state !== "SEARCHING" && s.state !== "TRACKING_LOST" && s.state !== "FLOOR_TRANSITION") return s;
      return {
        ...s,
        state: "RELOCALIZING",
        pending: { requestId: s.nextRequestId, observations, resumeTo: s.state },
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
    case "TRACKING_LOST":
      if (s.state === "IDLE" || (!s.deviceTracking && s.lostReason === e.reason)) return s;
      if (s.state === "LOCALIZED") return { ...s, state: "TRACKING_LOST", pending: null, deviceTracking: false, lostReason: e.reason };
      if (s.state === "RELOCALIZING") {
        // the observation that request was made from is no longer in a trustworthy frame
        return { ...s, state: s.pending?.resumeTo ?? "SEARCHING", pending: null, deviceTracking: false, lostReason: e.reason };
      }
      return { ...s, deviceTracking: false, lostReason: e.reason };
    case "TRACKING_RESTORED":
      // Device tracking is back, but TRACKING_LOST stays until a marker is seen again and the server relocalizes.
      return s.deviceTracking || s.state === "IDLE" ? s : { ...s, deviceTracking: true };
    case "DRIFT_EXCEEDED":
      // The platform still tracks, but a registered marker is no longer where the transform puts it: the transform has
      // drifted. It is dropped like a loss; the marker in view relocalizes the session from scratch.
      if (s.state !== "LOCALIZED") return s;
      return { ...s, state: "TRACKING_LOST", pending: null, lostReason: e.reason };
    case "FLOOR_HANDOFF":
      // A transform is valid on one floor only (each floor has its own canonical frame). The next floor starts unlocalized
      // and is localized from one of its own markers.
      if (s.state !== "LOCALIZED" || e.toFloorId === s.floorId) return s;
      return { ...s, state: "FLOOR_TRANSITION", floorId: e.toFloorId, localization: null, pending: null, lostReason: null, lastError: null };
    case "SESSION_ENDED":
      return { ...s, state: "ENDED", pending: null };
    default:
      return s;
  }
}

/** The platform's own verdict on device tracking for one frame. No viewer pose, or a viewer pose whose position is
 * emulated (the platform has fallen back to orientation-only tracking), means 6-DoF tracking is lost. */
export function frameTrackingSignal(viewerPose: { emulatedPosition: boolean } | null, visibility: string = "visible"): {
  ok: boolean;
  reason: string | null;
} {
  if (visibility !== "visible") return { ok: false, reason: `the AR session is ${visibility}` };
  if (!viewerPose) return { ok: false, reason: "the platform reports no device pose" };
  if (viewerPose.emulatedPosition) return { ok: false, reason: "the platform lost positional tracking (position is emulated)" };
  return { ok: true, reason: null };
}
