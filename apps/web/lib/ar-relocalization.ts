// The Android AR session's localization/tracking state machine (docs/ar.md, "Tracking failure"). Pure: the WebXR
// frame loop and the relocalization request feed it events; the UI and route renderer read it.
//
//   IDLE --SESSION_STARTED--> SEARCHING --MARKERS_OBSERVED--> RELOCALIZING --RELOCALIZATION_SUCCEEDED--> LOCALIZED
//                                               (RELOCALIZATION_FAILED or TRACKING_LOST: back to where it came from)
//   LOCALIZED --TRACKING_LOST--> TRACKING_LOST --MARKERS_OBSERVED--> RELOCALIZING --...--> LOCALIZED
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

import type { Pose } from "./ar-anchor-math.ts";
import type { MarkerObservation } from "./ar-marker-tracking.ts";

export type ArState = "IDLE" | "SEARCHING" | "RELOCALIZING" | "LOCALIZED" | "TRACKING_LOST" | "ENDED";

/** A server-solved localization (POST .../anchors/relocalize). */
export interface Localization {
  deviceToVenue: Pose;
  anchorIds: string[];
  residualMeters: number | null;
  coordinateFrameId: string;
  solvedAtMs: number;
}

export interface ArSessionState {
  state: ArState;
  /** The current localization, or the last confirmed one while TRACKING_LOST (kept for display, never used to advance). */
  localization: Localization | null;
  pending: { requestId: number; observations: MarkerObservation[]; resumeTo: "SEARCHING" | "TRACKING_LOST" } | null;
  /** Whether the platform reported 6-DoF device tracking on the last frame. */
  deviceTracking: boolean;
  /** Why device tracking was last reported lost (kept while TRACKING_LOST, cleared on a new localization). */
  lostReason: string | null;
  lastError: string | null;
  nextRequestId: number;
}

export type ArEvent =
  | { type: "SESSION_STARTED" }
  | { type: "MARKERS_OBSERVED"; observations: MarkerObservation[] }
  | { type: "RELOCALIZATION_SUCCEEDED"; requestId: number; localization: Localization }
  | { type: "RELOCALIZATION_FAILED"; requestId: number; error: string }
  | { type: "TRACKING_LOST"; reason: string }
  | { type: "TRACKING_RESTORED" }
  | { type: "SESSION_ENDED" };

export function initialArSessionState(): ArSessionState {
  return { state: "IDLE", localization: null, pending: null, deviceTracking: false, lostReason: null, lastError: null, nextRequestId: 1 };
}

export function canAdvanceRoute(s: ArSessionState): boolean {
  return s.state === "LOCALIZED" && s.localization !== null;
}

/** Pure reducer. An event that does not apply in the current state returns the same object: a stray platform event must
 * not crash a live session or skip a state. */
export function reduceArSession(s: ArSessionState, e: ArEvent): ArSessionState {
  if (s.state === "ENDED") return s;
  switch (e.type) {
    case "SESSION_STARTED":
      return s.state === "IDLE" ? { ...s, state: "SEARCHING", deviceTracking: false } : s;
    case "MARKERS_OBSERVED": {
      // an observation is only a measurement while the device itself is tracked
      if ((s.state !== "SEARCHING" && s.state !== "TRACKING_LOST") || !s.deviceTracking || e.observations.length === 0) return s;
      return {
        ...s,
        state: "RELOCALIZING",
        pending: { requestId: s.nextRequestId, observations: e.observations, resumeTo: s.state },
        nextRequestId: s.nextRequestId + 1,
      };
    }
    case "RELOCALIZATION_SUCCEEDED":
      if (s.state !== "RELOCALIZING" || s.pending?.requestId !== e.requestId) return s;
      return { ...s, state: "LOCALIZED", localization: e.localization, pending: null, lostReason: null, lastError: null };
    case "RELOCALIZATION_FAILED":
      if (s.state !== "RELOCALIZING" || s.pending?.requestId !== e.requestId) return s;
      return { ...s, state: s.pending.resumeTo, pending: null, lastError: e.error };
    case "TRACKING_LOST":
      if (s.state === "IDLE" || (!s.deviceTracking && s.lostReason === e.reason)) return s;
      if (s.state === "LOCALIZED") return { ...s, state: "TRACKING_LOST", deviceTracking: false, lostReason: e.reason };
      if (s.state === "RELOCALIZING") {
        // the observation that request was made from is no longer in a trustworthy frame
        return { ...s, state: s.pending?.resumeTo ?? "SEARCHING", pending: null, deviceTracking: false, lostReason: e.reason };
      }
      return { ...s, deviceTracking: false, lostReason: e.reason };
    case "TRACKING_RESTORED":
      // Device tracking is back, but TRACKING_LOST stays until a marker is seen again and the server relocalizes.
      return s.deviceTracking || s.state === "IDLE" ? s : { ...s, deviceTracking: true };
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
