import assert from "node:assert/strict";
import { test } from "node:test";
import type { MarkerObservation } from "./ar-marker-tracking.ts";
import {
  type ArSessionState,
  type Localization,
  canAdvanceRoute,
  frameTrackingSignal,
  initialArSessionState,
  reduceArSession,
} from "./ar-relocalization.ts";

const OBS: MarkerObservation = {
  anchorId: "anchor-entrance", markerType: "IMAGE_TARGET", markerIdentifier: "entrance",
  observedPose: { x: 0.2, y: 0.1, z: -1.5, qx: 0, qy: 0, qz: 0, qw: 1 }, timestampMs: 1234,
  trackingState: "tracked", measuredWidthMeters: 0.3, registeredWidthMeters: 0.3,
};
const LOC: Localization = {
  deviceToVenue: { x: 3, y: 4, z: 0, qx: Math.SQRT1_2, qy: 0, qz: 0, qw: Math.SQRT1_2 },
  anchorIds: ["anchor-entrance"], residualMeters: null, coordinateFrameId: "frame-1", solvedAtMs: 10,
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

test("while localized, further observations do not restart localization", () => {
  const s = localized();
  assert.equal(reduceArSession(s, { type: "MARKERS_OBSERVED", observations: [OBS] }), s);
});

test("an ended session accepts nothing", () => {
  const ended = reduceArSession(localized(), { type: "SESSION_ENDED" });
  assert.equal(ended.state, "ENDED");
  assert.equal(reduceArSession(ended, { type: "MARKERS_OBSERVED", observations: [OBS] }), ended);
});

test("frame tracking signal: no pose, emulated position or a hidden session is lost tracking", () => {
  assert.deepEqual(frameTrackingSignal({ emulatedPosition: false }), { ok: true, reason: null });
  assert.equal(frameTrackingSignal(null).ok, false);
  assert.match(frameTrackingSignal({ emulatedPosition: true }).reason!, /emulated/);
  assert.match(frameTrackingSignal({ emulatedPosition: false }, "hidden").reason!, /hidden/);
});
