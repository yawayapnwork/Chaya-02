// AR relocalization/tracking-loss state machine. Mirrors
// apps/ios-ar/Sources/ChayaARCore/RelocalizationStateMachine.swift so both clients behave identically on
// the same signals; see docs/ar.md "Tracking failure" for the required behavior this encodes:
//   - freeze the last known route state on tracking loss (never mutate `route`/`lastKnownPose` there)
//   - show a tracking-loss state (`state === "TRACKING_LOST"`)
//   - prompt for re-localization (`state === "RELOCALIZING"`)
//   - never silently move the user marker: `lastKnownPose` only changes on a LOCALIZED transition.

export type ArState = "UNINITIALIZED" | "DETECTING" | "LOCALIZED" | "TRACKING_LOST" | "RELOCALIZING";

export type ArEvent =
  | { type: "START_DETECTING" }
  | { type: "ANCHOR_DETECTED"; pose: unknown }
  | { type: "TRACKING_LOST" }
  | { type: "BEGIN_RELOCALIZING" }
  | { type: "RELOCALIZED"; pose: unknown };

export interface ArSessionState {
  state: ArState;
  /** The last confirmed device-to-venue transform. Only ever set by a LOCALIZED transition -- never
   * interpolated or guessed into existence while tracking is lost. */
  lastKnownPose: unknown | null;
}

const VALID_TRANSITIONS: Record<ArState, ArEvent["type"][]> = {
  UNINITIALIZED: ["START_DETECTING"],
  DETECTING: ["ANCHOR_DETECTED", "TRACKING_LOST"],
  LOCALIZED: ["TRACKING_LOST"],
  TRACKING_LOST: ["BEGIN_RELOCALIZING"],
  RELOCALIZING: ["RELOCALIZED", "TRACKING_LOST"],
};

export function initialArSessionState(): ArSessionState {
  return { state: "UNINITIALIZED", lastKnownPose: null };
}

/** Pure reducer: an event not valid for the current state is a no-op (returns the same reference), never
 * a thrown error -- a stray platform event (e.g. a second TRACKING_LOST while already TRACKING_LOST)
 * should not crash a live AR session. */
export function reduceArSession(current: ArSessionState, event: ArEvent): ArSessionState {
  if (!VALID_TRANSITIONS[current.state].includes(event.type)) {
    return current;
  }
  switch (event.type) {
    case "START_DETECTING":
      return { ...current, state: "DETECTING" };
    case "ANCHOR_DETECTED":
      return { state: "LOCALIZED", lastKnownPose: event.pose };
    case "TRACKING_LOST":
      // Freeze: state moves to TRACKING_LOST, lastKnownPose is untouched.
      return { ...current, state: "TRACKING_LOST" };
    case "BEGIN_RELOCALIZING":
      return { ...current, state: "RELOCALIZING" };
    case "RELOCALIZED":
      return { state: "LOCALIZED", lastKnownPose: event.pose };
    default:
      return current;
  }
}
