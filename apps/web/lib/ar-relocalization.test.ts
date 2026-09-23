import assert from "node:assert/strict";
import { test } from "node:test";
import { initialArSessionState, reduceArSession, type ArSessionState } from "./ar-relocalization.ts";

test("starts UNINITIALIZED with no pose", () => {
  const state = initialArSessionState();
  assert.equal(state.state, "UNINITIALIZED");
  assert.equal(state.lastKnownPose, null);
});

test("full happy path: detecting -> localized -> tracking lost -> relocalizing -> localized", () => {
  let state = initialArSessionState();
  state = reduceArSession(state, { type: "START_DETECTING" });
  assert.equal(state.state, "DETECTING");

  state = reduceArSession(state, { type: "ANCHOR_DETECTED", pose: "pose-1" });
  assert.equal(state.state, "LOCALIZED");
  assert.equal(state.lastKnownPose, "pose-1");

  state = reduceArSession(state, { type: "TRACKING_LOST" });
  assert.equal(state.state, "TRACKING_LOST");
  // Frozen: the last known pose must survive a tracking-loss event untouched.
  assert.equal(state.lastKnownPose, "pose-1");

  state = reduceArSession(state, { type: "BEGIN_RELOCALIZING" });
  assert.equal(state.state, "RELOCALIZING");
  assert.equal(state.lastKnownPose, "pose-1");

  state = reduceArSession(state, { type: "RELOCALIZED", pose: "pose-2" });
  assert.equal(state.state, "LOCALIZED");
  assert.equal(state.lastKnownPose, "pose-2");
});

test("tracking loss while relocalizing goes back to TRACKING_LOST without inventing a pose", () => {
  let state: ArSessionState = { state: "RELOCALIZING", lastKnownPose: "pose-1" };
  state = reduceArSession(state, { type: "TRACKING_LOST" });
  assert.equal(state.state, "TRACKING_LOST");
  assert.equal(state.lastKnownPose, "pose-1");
});

test("an event invalid for the current state is a no-op, not a crash", () => {
  const state = initialArSessionState(); // UNINITIALIZED
  const next = reduceArSession(state, { type: "RELOCALIZED", pose: "should-be-ignored" });
  assert.equal(next, state); // same reference: truly a no-op
});

test("stray TRACKING_LOST while already TRACKING_LOST does not move the marker or crash", () => {
  const state: ArSessionState = { state: "TRACKING_LOST", lastKnownPose: "pose-1" };
  const next = reduceArSession(state, { type: "TRACKING_LOST" });
  assert.equal(next, state);
});
