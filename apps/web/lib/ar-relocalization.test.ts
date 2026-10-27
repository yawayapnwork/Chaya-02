import assert from "node:assert/strict";
import { test } from "node:test";
import type { MarkerObservation } from "./ar-marker-tracking.ts";
import {
  type ArSessionState,
  type Localization,
  MAX_LIMITED_MS,
  RECOVERY_MAX_JUMP_METERS,
  RELOCALIZATION_TIMEOUT_MS,
  canAdvanceRoute,
  frameTrackingSignal,
  initialArSessionState,
  isValidTransform,
  reduceArSession,
  routeShown,
} from "./ar-relocalization.ts";
import { classifyArFailure } from "./ar-errors.ts";
import { routeLegFor } from "./ar-navigation.ts";

const OBS: MarkerObservation = {
  anchorId: "anchor-entrance", markerType: "IMAGE_TARGET", markerIdentifier: "entrance",
  observedPose: { x: 0.2, y: 0.1, z: -1.5, qx: 0, qy: 0, qz: 0, qw: 1 }, timestampMs: 1234,
  trackingState: "tracked", measuredWidthMeters: 0.3, registeredWidthMeters: 0.3,
};
const LOC: Localization = {
  deviceToVenue: { x: 3, y: 4, z: 0, qx: Math.SQRT1_2, qy: 0, qz: 0, qw: Math.SQRT1_2 },
  anchorIds: ["anchor-entrance"], residualMeters: null, coordinateFrameId: "frame-1", scanVersionId: "sv-1", floorId: "floor-1",
  solvedAtMs: 10,
};

/** A started session whose device tracking the platform has confirmed. */
function started(): ArSessionState {
  const s = reduceArSession(initialArSessionState(), { type: "SESSION_STARTED" });
  return reduceArSession(s, { type: "TRACKING_RESTORED" });
}

function localized(): ArSessionState {
  let s = started();
  s = reduceArSession(s, { type: "MARKERS_OBSERVED", observations: [OBS] });
  return reduceArSession(s, { type: "RELOCALIZATION_SUCCEEDED", requestId: s.pending!.requestId, localization: LOC });
}

test("a session localizes only through an observed marker and a server relocalization", () => {
  let s = reduceArSession(initialArSessionState(), { type: "SESSION_STARTED" });
  assert.equal(s.state, "SEARCHING");
  assert.equal(s.deviceTracking, false);
  assert.equal(canAdvanceRoute(s), false);
  assert.equal(reduceArSession(s, { type: "MARKERS_OBSERVED", observations: [OBS] }), s,
    "no observation counts before the platform reports device tracking");
  s = reduceArSession(s, { type: "TRACKING_RESTORED" });

  s = reduceArSession(s, { type: "MARKERS_OBSERVED", observations: [OBS] });
  assert.equal(s.state, "RELOCALIZING");
  assert.deepEqual(s.pending?.observations, [OBS], "the observations are kept as observations");
  assert.equal(s.localization, null, "an observation is not a device-to-venue transform");

  s = reduceArSession(s, { type: "RELOCALIZATION_SUCCEEDED", requestId: s.pending!.requestId, localization: LOC });
  assert.equal(s.state, "LOCALIZED");
  assert.deepEqual(s.localization, LOC);
  assert.equal(s.pending, null);
  assert.equal(canAdvanceRoute(s), true);
});

test("no observations means no relocalization", () => {
  const s = started();
  assert.equal(reduceArSession(s, { type: "MARKERS_OBSERVED", observations: [] }), s);
});

test("a failed relocalization returns to searching, keeping no transform", () => {
  let s = started();
  s = reduceArSession(s, { type: "MARKERS_OBSERVED", observations: [OBS] });
  s = reduceArSession(s, { type: "RELOCALIZATION_FAILED", requestId: s.pending!.requestId, error: "ANCHOR_NOT_CALIBRATED" });
  assert.equal(s.state, "SEARCHING");
  assert.equal(s.localization, null);
  assert.equal(s.lastError, "ANCHOR_NOT_CALIBRATED");
});

test("tracking loss freezes the route and keeps the last localization only for display", () => {
  const s = reduceArSession(localized(), { type: "TRACKING_LOST", reason: "no viewer pose" });
  assert.equal(s.state, "TRACKING_LOST");
  assert.equal(s.lostReason, "no viewer pose");
  assert.equal(canAdvanceRoute(s), false, "the route must not advance while tracking is invalid");
  assert.deepEqual(s.localization, LOC);
});

test("recovery happens only after a new valid observation and a new relocalization", () => {
  let s = reduceArSession(localized(), { type: "TRACKING_LOST", reason: "position emulated" });
  // repeated loss signals, or anything but an observation, change nothing
  assert.equal(reduceArSession(s, { type: "TRACKING_LOST", reason: "position emulated" }), s);
  assert.equal(reduceArSession(s, { type: "RELOCALIZATION_SUCCEEDED", requestId: 99, localization: LOC }), s);
  assert.equal(reduceArSession(s, { type: "MARKERS_OBSERVED", observations: [OBS] }), s, "no observation while the device is untracked");
  // the platform tracking again is not a localization: the pre-loss transform may belong to a reset world frame
  s = reduceArSession(s, { type: "TRACKING_RESTORED" });
  assert.equal(s.state, "TRACKING_LOST");
  assert.equal(canAdvanceRoute(s), false);
  s = reduceArSession(s, { type: "MARKERS_OBSERVED", observations: [OBS] });
  assert.equal(s.state, "RELOCALIZING");
  const failed = reduceArSession(s, { type: "RELOCALIZATION_FAILED", requestId: s.pending!.requestId, error: "boom" });
  assert.equal(failed.state, "TRACKING_LOST", "a failed attempt after a loss stays lost");
  const newLoc = { ...LOC, solvedAtMs: 99 };
  s = reduceArSession(s, { type: "RELOCALIZATION_SUCCEEDED", requestId: s.pending!.requestId, localization: newLoc });
  assert.equal(s.state, "LOCALIZED");
  assert.equal(s.localization?.solvedAtMs, 99, "the new transform replaces the pre-loss one");
});

test("an answer to a request made before tracking was lost is ignored", () => {
  let s = started();
  s = reduceArSession(s, { type: "MARKERS_OBSERVED", observations: [OBS] });
  const staleId = s.pending!.requestId;
  s = reduceArSession(s, { type: "TRACKING_LOST", reason: "no viewer pose" });
  assert.equal(s.pending, null);
  assert.equal(s.state, "SEARCHING", "never localized, so there is no localization to call lost");
  assert.equal(reduceArSession(s, { type: "RELOCALIZATION_SUCCEEDED", requestId: staleId, localization: LOC }), s);
  s = reduceArSession(s, { type: "TRACKING_RESTORED" });
  s = reduceArSession(s, { type: "MARKERS_OBSERVED", observations: [OBS] });
  assert.notEqual(s.pending!.requestId, staleId);
  assert.equal(reduceArSession(s, { type: "RELOCALIZATION_SUCCEEDED", requestId: staleId, localization: LOC }), s);
});

test("tracking loss while relocalizing after a loss cancels the request and stays lost", () => {
  let s = reduceArSession(localized(), { type: "TRACKING_LOST", reason: "no viewer pose" });
  s = reduceArSession(s, { type: "TRACKING_RESTORED" });
  s = reduceArSession(s, { type: "MARKERS_OBSERVED", observations: [OBS] });
  const id = s.pending!.requestId;
  s = reduceArSession(s, { type: "TRACKING_LOST", reason: "world anchor no longer tracked" });
  assert.equal(s.state, "TRACKING_LOST");
  assert.equal(reduceArSession(s, { type: "RELOCALIZATION_SUCCEEDED", requestId: id, localization: LOC }), s);
});

test("start-up tracking initialisation is not reported as a lost localization", () => {
  let s = reduceArSession(initialArSessionState(), { type: "SESSION_STARTED" });
  s = reduceArSession(s, { type: "TRACKING_LOST", reason: "the platform lost positional tracking (position is emulated)" });
  assert.equal(s.state, "SEARCHING");
  assert.equal(s.deviceTracking, false);
  s = reduceArSession(s, { type: "TRACKING_RESTORED" });
  assert.equal(s.state, "SEARCHING");
  assert.equal(s.deviceTracking, true);
});

test("while localized, a marker seen again refreshes the transform without leaving LOCALIZED", () => {
  const s = localized();
  const refreshing = reduceArSession(s, { type: "MARKERS_OBSERVED", observations: [OBS] });
  assert.equal(refreshing.state, "LOCALIZED", "the route keeps being drawn on the current transform");
  assert.equal(refreshing.localization, LOC);
  assert.equal(refreshing.pending?.resumeTo, "LOCALIZED");
  assert.equal(reduceArSession(refreshing, { type: "MARKERS_OBSERVED", observations: [OBS] }), refreshing, "one refresh at a time");

  const newer: Localization = { ...LOC, deviceToVenue: { ...LOC.deviceToVenue, x: 3.05 }, solvedAtMs: 20 };
  const refreshed = reduceArSession(refreshing, { type: "RELOCALIZATION_SUCCEEDED", requestId: refreshing.pending!.requestId, localization: newer });
  assert.equal(refreshed.state, "LOCALIZED");
  assert.equal(refreshed.localization, newer);

  const failed = reduceArSession(refreshing, { type: "RELOCALIZATION_FAILED", requestId: refreshing.pending!.requestId, error: "network" });
  assert.equal(failed.state, "LOCALIZED", "a failed refresh keeps the transform it had");
  assert.equal(failed.localization, LOC);
  assert.equal(failed.lastError, "network");
});

test("a floor's scan version is pinned at its first localization; an answer in another version never replaces it", () => {
  const s = localized();
  assert.deepEqual(s.pinnedVersions, { "floor-1": "sv-1" });
  const refreshing = reduceArSession(s, { type: "MARKERS_OBSERVED", observations: [OBS] });
  const republished: Localization = { ...LOC, scanVersionId: "sv-2", solvedAtMs: 20 };
  const after = reduceArSession(refreshing, { type: "RELOCALIZATION_SUCCEEDED", requestId: refreshing.pending!.requestId, localization: republished });
  assert.equal(after.localization, LOC, "the old version's transform is kept, never mixed with the new one");
  assert.match(after.lastError!, /^VERSION_MISMATCH/);

  // From TRACKING_LOST too: the answer is refused and the session stays lost.
  let lost = reduceArSession(s, { type: "TRACKING_LOST", reason: "covered" });
  lost = reduceArSession(lost, { type: "TRACKING_RESTORED" });
  lost = reduceArSession(lost, { type: "MARKERS_OBSERVED", observations: [OBS] });
  const refused = reduceArSession(lost, { type: "RELOCALIZATION_SUCCEEDED", requestId: lost.pending!.requestId, localization: republished });
  assert.equal(refused.state, "TRACKING_LOST");
  assert.match(refused.lastError!, /^VERSION_MISMATCH/);
});

test("an answer solved on another floor is refused", () => {
  const s = reduceArSession(started(), { type: "MARKERS_OBSERVED", observations: [OBS] });
  const pinnedToFloor = { ...s, floorId: "floor-1" };
  const other = reduceArSession(pinnedToFloor, { type: "RELOCALIZATION_SUCCEEDED", requestId: s.pending!.requestId,
    localization: { ...LOC, floorId: "floor-2" } });
  assert.equal(other.state, "SEARCHING");
  assert.match(other.lastError!, /^FLOOR_MISMATCH/);
});

test("another floor's markers never localize the session: twice seen alone they say the user is on that floor", () => {
  const s = reduceArSession(reduceArSession(initialArSessionState(), { type: "SESSION_STARTED", floorId: "floor-1" }), { type: "TRACKING_RESTORED" });
  const once = reduceArSession(s, { type: "MARKERS_OBSERVED", observations: [{ ...OBS, floorId: "floor-2" }] });
  assert.equal(once.state, "SEARCHING");
  assert.deepEqual(once.floorCheck, { status: "PENDING" });
  const twice = reduceArSession(once, { type: "MARKERS_OBSERVED", observations: [{ ...OBS, floorId: "floor-2" }] });
  assert.equal(twice.state, "SEARCHING", "no localization, so no route overlay, on an unexpected floor");
  assert.deepEqual(twice.floorCheck, { status: "UNEXPECTED", detectedFloorId: "floor-2" });
  // The user confirms: the session localizes on floor 2 (the caller plans the route again from there).
  const accepted = reduceArSession(twice, { type: "ACCEPT_DETECTED_FLOOR" });
  assert.equal(accepted.floorId, "floor-2");
  assert.equal(reduceArSession(accepted, { type: "MARKERS_OBSERVED", observations: [{ ...OBS, floorId: "floor-2" }] }).state, "RELOCALIZING");
  // On the chosen floor, at the start of a session, its own marker localizes at once.
  assert.equal(reduceArSession(s, { type: "MARKERS_OBSERVED", observations: [{ ...OBS, floorId: "floor-1" }] }).state, "RELOCALIZING");
});

test("markers of two floors in view are ambiguous: nothing is decided until one floor's markers are seen alone", () => {
  const s = reduceArSession(reduceArSession(initialArSessionState(), { type: "SESSION_STARTED", floorId: "floor-1" }), { type: "TRACKING_RESTORED" });
  let t = reduceArSession(s, { type: "MARKERS_OBSERVED", observations: [{ ...OBS, floorId: "floor-1" }, { ...OBS, anchorId: "x", floorId: "floor-2" }] });
  assert.equal(t.state, "SEARCHING");
  assert.deepEqual(t.floorCheck, { status: "AMBIGUOUS", floorIds: ["floor-1", "floor-2"] });
  assert.equal(reduceArSession(t, { type: "ACCEPT_DETECTED_FLOOR" }), t, "an ambiguous floor cannot be accepted");
  for (let i = 0; i < 3; i++) t = reduceArSession(t, { type: "MARKERS_OBSERVED", observations: [{ ...OBS, floorId: "floor-1" }] });
  assert.equal(t.floorCheck?.status, "AMBIGUOUS", "still within the window");
  t = reduceArSession(t, { type: "MARKERS_OBSERVED", observations: [{ ...OBS, floorId: "floor-1" }] });
  assert.equal(t.state, "RELOCALIZING");
});

test("drift drops the transform; the marker in view relocalizes the session", () => {
  const s = localized();
  const drifted = reduceArSession(s, { type: "DRIFT_EXCEEDED", reason: "drift" });
  assert.equal(drifted.state, "TRACKING_LOST");
  assert.equal(canAdvanceRoute(drifted), false);
  assert.equal(drifted.deviceTracking, true, "the platform still tracks: the next observation can relocalize at once");
  assert.equal(reduceArSession(drifted, { type: "MARKERS_OBSERVED", observations: [OBS] }).state, "RELOCALIZING");
  assert.equal(reduceArSession(started(), { type: "DRIFT_EXCEEDED", reason: "drift" }).state, "SEARCHING", "only a localization can drift");
});

test("a floor handoff drops the transform and localizes the next floor from its own markers", () => {
  const s = localized();
  const handed = reduceArSession(s, { type: "FLOOR_HANDOFF", toFloorId: "floor-2" });
  assert.equal(handed.state, "FLOOR_TRANSITION");
  assert.equal(handed.floorId, "floor-2");
  assert.equal(handed.localization, null, "a transform is never carried to another floor");
  assert.equal(canAdvanceRoute(handed), false);
  assert.equal(reduceArSession(handed, { type: "MARKERS_OBSERVED", observations: [{ ...OBS, floorId: "floor-1" }] }).state,
    "FLOOR_TRANSITION", "the floor left behind cannot localize the session");
  // Right after a floor change one sighting is not enough to be sure of the floor: two are.
  const once = reduceArSession(handed, { type: "MARKERS_OBSERVED", observations: [{ ...OBS, floorId: "floor-2" }] });
  assert.equal(once.state, "FLOOR_TRANSITION");
  assert.deepEqual(once.floorCheck, { status: "PENDING" });
  const relocalizing = reduceArSession(once, { type: "MARKERS_OBSERVED", observations: [{ ...OBS, floorId: "floor-2" }] });
  assert.equal(relocalizing.state, "RELOCALIZING");
  const second: Localization = { ...LOC, floorId: "floor-2", scanVersionId: "sv-9", coordinateFrameId: "frame-9" };
  const there = reduceArSession(relocalizing, { type: "RELOCALIZATION_SUCCEEDED", requestId: relocalizing.pending!.requestId, localization: second });
  assert.equal(there.state, "LOCALIZED");
  assert.deepEqual(there.pinnedVersions, { "floor-1": "sv-1", "floor-2": "sv-9" }, "each floor pins its own version");
  // Tracking loss in an elevator is expected, and is not a lost localization.
  const elevator = reduceArSession(handed, { type: "TRACKING_LOST", reason: "no pose" });
  assert.equal(elevator.state, "FLOOR_TRANSITION");
});

test("an ended session accepts nothing", () => {
  const ended = reduceArSession(localized(), { type: "SESSION_ENDED" });
  assert.equal(ended.state, "ENDED");
  assert.equal(reduceArSession(ended, { type: "MARKERS_OBSERVED", observations: [OBS] }), ended);
});

test("frame tracking signal: no pose or a hidden session is lost; an emulated position is limited", () => {
  assert.deepEqual(frameTrackingSignal({ emulatedPosition: false }), { ok: true, kind: "OK", reason: null });
  assert.equal(frameTrackingSignal(null).kind, "LOST");
  assert.equal(frameTrackingSignal({ emulatedPosition: true }).kind, "LIMITED");
  assert.equal(frameTrackingSignal({ emulatedPosition: true }).ok, false);
  assert.match(frameTrackingSignal({ emulatedPosition: true }).reason!, /emulated/);
  assert.equal(frameTrackingSignal({ emulatedPosition: false }, "hidden").kind, "LOST");
  assert.match(frameTrackingSignal({ emulatedPosition: false }, "hidden").reason!, /hidden/);
});

test("limited tracking while localized holds the route frozen, and returns only after a validated recovery", () => {
  const limited = reduceArSession(localized(), { type: "TRACKING_LIMITED", reason: "position is emulated", atMs: 1000 });
  assert.equal(limited.state, "LIMITED");
  assert.ok(routeShown(limited), "the last valid overlay stays drawn");
  assert.ok(!canAdvanceRoute(limited), "but progress is frozen");
  assert.notEqual(limited.localization, null);
  // Markers are not measurements while the device itself is not tracked.
  assert.equal(reduceArSession(limited, { type: "MARKERS_OBSERVED", observations: [OBS] }), limited);
  // Back within the limit, with the world anchor where it was: localized again on the same transform.
  const back = reduceArSession(reduceArSession(limited, { type: "TICK", atMs: 2500 }), { type: "TRACKING_RESTORED", anchorJumpMeters: 0.05 });
  assert.equal(back.state, "LOCALIZED");
  assert.equal(back.localization, limited.localization);
  assert.ok(canAdvanceRoute(back));
});

test("a limited spell becomes a loss: too long, a real loss, an anchor that jumped, or an anchor no longer tracked", () => {
  const limited = reduceArSession(localized(), { type: "TRACKING_LIMITED", reason: "position is emulated", atMs: 1000 });
  const timedOut = reduceArSession(limited, { type: "TICK", atMs: 1000 + MAX_LIMITED_MS + 1 });
  assert.equal(timedOut.state, "TRACKING_LOST");
  assert.match(timedOut.lostReason!, /limited for more than 3 s/);
  assert.ok(!routeShown(timedOut));
  assert.equal(reduceArSession(limited, { type: "TRACKING_LOST", reason: "no pose" }).state, "TRACKING_LOST");
  const jumped = reduceArSession(limited, { type: "TRACKING_RESTORED", anchorJumpMeters: RECOVERY_MAX_JUMP_METERS + 0.2 });
  assert.equal(jumped.state, "TRACKING_LOST");
  assert.match(jumped.lostReason!, /re-anchored/);
  const untracked = reduceArSession(limited, { type: "TRACKING_RESTORED", anchorJumpMeters: null });
  assert.equal(untracked.state, "TRACKING_LOST");
  // After such a loss only a marker and a server solve bring the route back, as for any loss.
  assert.equal(reduceArSession(untracked, { type: "TRACKING_RESTORED" }).state, "TRACKING_LOST");
  assert.equal(reduceArSession(limited, { type: "FLOOR_HANDOFF", toFloorId: "f2" }).state, "LIMITED", "no handoff while frozen");
});

test("limited tracking before the first localization is just 'not tracking yet', never a held route", () => {
  const searching = reduceArSession(initialArSessionState(), { type: "SESSION_STARTED", floorId: "f1" });
  const s = reduceArSession(searching, { type: "TRACKING_LIMITED", reason: "initializing", atMs: 0 });
  assert.equal(s.state, "SEARCHING");
  assert.equal(s.deviceTracking, false);
  assert.ok(!routeShown(s));
});

test("invalid transforms (NaN, zero quaternion norm, or gravity tilt > 15 deg) are refused and never localize", () => {
  assert.equal(isValidTransform(LOC.deviceToVenue).ok, true);
  assert.equal(isValidTransform({ ...LOC.deviceToVenue, x: Number.NaN }).ok, false);
  assert.equal(isValidTransform({ x: 0, y: 0, z: 0, qx: 0, qy: 0, qz: 0, qw: 0 }).ok, false);
  // Identity quaternion maps device +Y to canonical +Y (90 deg tilt from canonical +Z)
  assert.equal(isValidTransform({ x: 0, y: 0, z: 0, qx: 0, qy: 0, qz: 0, qw: 1 }).ok, false);

  const s = reduceArSession(started(), { type: "MARKERS_OBSERVED", observations: [OBS] });
  const badTilt = reduceArSession(s, {
    type: "RELOCALIZATION_SUCCEEDED",
    requestId: s.pending!.requestId,
    localization: { ...LOC, deviceToVenue: { x: 1, y: 2, z: 0, qx: 0, qy: 0, qz: 0, qw: 1 } },
  });
  assert.equal(badTilt.state, "SEARCHING");
  assert.equal(badTilt.localization, null);
  assert.match(badTilt.lastError!, /^INVALID_TRANSFORM/);

  const badNan = reduceArSession(s, {
    type: "RELOCALIZATION_SUCCEEDED",
    requestId: s.pending!.requestId,
    localization: { ...LOC, deviceToVenue: { ...LOC.deviceToVenue, z: Number.POSITIVE_INFINITY } },
  });
  assert.equal(badNan.state, "SEARCHING");
  assert.match(badNan.lastError!, /^INVALID_TRANSFORM/);
});

test("relocalization timeout cancels a hung request on TICK and returns to the previous state", () => {
  const s = reduceArSession(started(), { type: "MARKERS_OBSERVED", observations: [OBS] });
  assert.equal(s.state, "RELOCALIZING");
  // Before timeout: stays RELOCALIZING
  const stillPending = reduceArSession(s, { type: "TICK", atMs: OBS.timestampMs + RELOCALIZATION_TIMEOUT_MS - 10 });
  assert.equal(stillPending.state, "RELOCALIZING");
  // After timeout: returns to SEARCHING and records RELOCALIZATION_TIMEOUT
  const timedOut = reduceArSession(s, { type: "TICK", atMs: OBS.timestampMs + RELOCALIZATION_TIMEOUT_MS + 1 });
  assert.equal(timedOut.state, "SEARCHING");
  assert.equal(timedOut.pending, null);
  assert.match(timedOut.lastError!, /^RELOCALIZATION_TIMEOUT/);

  // A timed-out refresh while LOCALIZED keeps the current localization and clears pending
  const refreshing = reduceArSession(localized(), { type: "MARKERS_OBSERVED", observations: [OBS] });
  const refreshTimedOut = reduceArSession(refreshing, { type: "TICK", atMs: OBS.timestampMs + RELOCALIZATION_TIMEOUT_MS + 1 });
  assert.equal(refreshTimedOut.state, "LOCALIZED");
  assert.equal(refreshTimedOut.localization, LOC);
  assert.equal(refreshTimedOut.pending, null);
  assert.match(refreshTimedOut.lastError!, /^RELOCALIZATION_TIMEOUT/);
});

test("stale routes and non-finite waypoint coordinates are refused before rendering", () => {
  const validRoute = {
    waypoints: [
      { x: 0, y: 0, z: 0, floorId: "floor-1", kind: "START" as const },
      { x: 5, y: 0, z: 0, floorId: "floor-1", kind: "DESTINATION" as const },
    ],
    distanceMeters: 5,
    estimatedDurationSeconds: 4,
    floorTransitions: [],
    accessibilityProfile: "STANDARD" as const,
    accessibilityConstraintsApplied: [],
    routingSources: [{
      floorId: "floor-1", graphId: "g1", source: "RECAST_NAVMESH", navmeshSha256: null,
      recastnavigationVersion: null, pathMethod: null, scanVersionId: "sv-1", coordinateFrameId: "frame-1",
    }],
  };
  assert.equal(routeLegFor(validRoute, LOC).ok, true);
  const nonFinite = {
    ...validRoute,
    waypoints: [
      { x: 0, y: 0, z: 0, floorId: "floor-1", kind: "START" as const },
      { x: Number.NaN, y: 0, z: 0, floorId: "floor-1", kind: "DESTINATION" as const },
    ],
  };
  const check = routeLegFor(nonFinite, LOC);
  assert.equal(check.ok, false);
  if (!check.ok) assert.equal(check.problem, "FRAME_MISMATCH");
});

test("classifyArFailure covers expired sessions, anchor mismatch, deleted destinations, unreachable routes and failed API requests", () => {
  const expired = classifyArFailure({ status: 401, code: "HTTP_401", message: "token expired" });
  assert.equal(expired.failure, "SESSION_EXPIRED");
  assert.equal(expired.retryable, false);

  const deletedPoi = classifyArFailure({ status: 404, code: "POI_NOT_FOUND", message: "destination POI not found" });
  assert.equal(deletedPoi.failure, "REFUSED");
  assert.equal(deletedPoi.retryable, false);
  assert.match(deletedPoi.action, /no longer exists/);

  const noRoute = classifyArFailure({ status: 409, code: "NO_ROUTE", message: "no route" });
  assert.equal(noRoute.failure, "REFUSED");
  assert.equal(noRoute.retryable, false);
  assert.match(noRoute.action, /cannot be reached/);

  const noStepFree = classifyArFailure({ status: 409, code: "NO_ACCESSIBLE_ROUTE", message: "stairs only" });
  assert.equal(noStepFree.failure, "REFUSED");
  assert.equal(noStepFree.retryable, false);
  assert.match(noStepFree.action, /step-free/);

  const offNavmesh = classifyArFailure({ status: 422, code: "DESTINATION_OFF_NAVMESH", message: "too far" });
  assert.equal(offNavmesh.failure, "REFUSED");
  assert.equal(offNavmesh.retryable, false);
  assert.match(offNavmesh.action, /walkable area/);

  const gravity = classifyArFailure({ status: 409, code: "RELOCALIZATION_GRAVITY_MISMATCH", message: "tilted" });
  assert.equal(gravity.failure, "REFUSED");
  assert.equal(gravity.retryable, false);
  assert.match(gravity.action, /gravity/);
});
