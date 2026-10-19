import assert from "node:assert/strict";
import { test } from "node:test";
import type { Vec3 } from "./coordinate-frame.ts";
import type { LocalizedObstacle, ObstacleCategory, Visibility } from "./obstacle-sensing.ts";
import {
  CLEAR_AFTER_MISSES,
  TTL_MS,
  type TrackerState,
  blockingObservations,
  expireTracks,
  initialTracker,
  movingPeople,
  updateTracks,
} from "./obstacle-tracker.ts";

// TRACKING ONLY: obstacles already placed in the venue frame (synthetic), fed frame by frame. What a real detector
// and real depth produce is not exercised here.

const T0 = 1_700_000_000_000;
function obs(x: number, y: number, category: ObstacleCategory = "UNCLASSIFIED", over: Partial<LocalizedObstacle> = {}): LocalizedObstacle {
  return {
    floorId: "floor", coordinateFrameId: "frame", category, observedAtMs: T0, center: [x, y, 0.9] as Vec3, confidence: 0.8,
    uncertaintyMeters: 0.1, minX: x - 0.4, maxX: x + 0.4, minY: y - 0.4, maxY: y + 0.4,
    evidence: { depthSource: "LIDAR", depthSamples: 40, distanceMeters: 3, residualMeasured: true, detector: null }, ...over,
  };
}
const frame = (nowMs: number, visibility: Visibility = "NOT_IN_VIEW", over: { floorId?: string; coordinateFrameId?: string | null } = {}) =>
  ({ nowMs, floorId: over.floorId ?? "floor", coordinateFrameId: over.coordinateFrameId === undefined ? "frame" : over.coordinateFrameId, visibility: () => visibility });

function feed(state: TrackerState, steps: [number, LocalizedObstacle[], Visibility?][]): TrackerState {
  return steps.reduce((s, [t, o, v]) => updateTracks(s, o, frame(T0 + t, v)), state);
}

test("a static obstacle is reported once it was seen twice, never from one frame", () => {
  let s = updateTracks(initialTracker(), [obs(0, 3)], frame(T0));
  assert.equal(blockingObservations(s).length, 0, "one sighting is tentative");
  s = updateTracks(s, [obs(0.05, 3.02)], frame(T0 + 250));
  const b = blockingObservations(s);
  assert.equal(b.length, 1);
  assert.equal(s.tracks.length, 1, "repeated sightings are one obstacle, not two");
  assert.equal(b[0].observedAtMs, T0 + 250, "observedAt is the latest sighting");
  assert.equal(b[0].coordinateFrameId, "frame");
});

test("a person walking across is a warning, not a blockage; a person who stops blocks", () => {
  // 1 m/s across the corridor
  let s = feed(initialTracker(), [[0, [obs(-1, 3, "PERSON")]], [500, [obs(-0.5, 3, "PERSON")]], [1000, [obs(0, 3, "PERSON")]]]);
  assert.equal(blockingObservations(s).length, 0);
  assert.equal(movingPeople(s).length, 1);
  // stops
  s = feed(s, [[1500, [obs(0.02, 3, "PERSON")]], [2000, [obs(0.02, 3, "PERSON")]], [2500, [obs(0.02, 3, "PERSON")]]]);
  assert.equal(movingPeople(s).length, 0);
  assert.equal(blockingObservations(s).length, 1);
  assert.equal(blockingObservations(s)[0].category, "PERSON");
});

test("a person is not a permanent obstacle: unseen for its TTL it is dropped, an object lasts longer", () => {
  let s = feed(initialTracker(), [[0, [obs(0, 3, "PERSON"), obs(2, 6)]], [300, [obs(0, 3, "PERSON"), obs(2, 6)]]]);
  assert.equal(blockingObservations(s).length, 2);
  s = updateTracks(s, [], frame(T0 + 300 + TTL_MS.PERSON + 1)); // nothing in view says otherwise
  assert.deepEqual(blockingObservations(s).map((o) => o.category), ["UNCLASSIFIED"]);
  s = expireTracks(s, T0 + 300 + TTL_MS.UNCLASSIFIED + 1);
  assert.equal(s.tracks.length, 0, "expired observations are dropped without a new frame too (sensing paused)");
});

test("a temporary obstruction: kept while hidden or out of view, dropped once its place is measured clear", () => {
  let s = feed(initialTracker(), [[0, [obs(0, 3)]], [250, [obs(0, 3)]]]);
  for (let i = 1; i <= 6; i++) s = updateTracks(s, [], frame(T0 + 250 + i * 250, "OCCLUDED"));
  assert.equal(blockingObservations(s).length, 1, "something walked in front of it: still there");
  s = updateTracks(s, [], frame(T0 + 2000, "OCCUPIED"));
  assert.equal(blockingObservations(s).length, 1, "depth still shows it although the detector missed it");
  for (let i = 1; i < CLEAR_AFTER_MISSES; i++) s = updateTracks(s, [], frame(T0 + 2000 + i * 250, "CLEAR"));
  assert.equal(blockingObservations(s).length, 1, "one clear look is not enough");
  s = updateTracks(s, [], frame(T0 + 3000, "CLEAR"));
  assert.equal(s.tracks.length, 0);
});

test("multiple obstacles are tracked apart, and two detectors on one person are one obstacle", () => {
  let s = feed(initialTracker(), [[0, [obs(0, 3), obs(0, 6), obs(1.5, 4)]], [250, [obs(0, 3), obs(0, 6), obs(1.5, 4)]]]);
  assert.equal(blockingObservations(s).length, 3);
  // a person detection and a depth cluster at the same place, in the same frame and the next
  s = feed(initialTracker(), [[0, [obs(0, 3, "PERSON"), obs(0.1, 3.05)]], [250, [obs(0.02, 3, "PERSON"), obs(0.08, 3.1)]]]);
  assert.equal(s.tracks.length, 1);
  assert.equal(blockingObservations(s)[0].category, "PERSON");
});

test("a new floor or coordinate frame drops every track; observations of another frame are ignored", () => {
  let s = feed(initialTracker(), [[0, [obs(0, 3)]], [250, [obs(0, 3)]]]);
  s = updateTracks(s, [], frame(T0 + 500, "NOT_IN_VIEW", { coordinateFrameId: "frame-2" }));
  assert.equal(s.tracks.length, 0);
  s = updateTracks(s, [obs(0, 3)], frame(T0 + 750, "NOT_IN_VIEW", { coordinateFrameId: "frame-2" }));
  assert.equal(s.tracks.length, 0, "an observation made in the old frame is not placed in the new one");
});
