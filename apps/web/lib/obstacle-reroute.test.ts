import assert from "node:assert/strict";
import { test } from "node:test";
import type { Vec3 } from "./coordinate-frame.ts";
import {
  CLEAR_HOLD_MS,
  OBSTACLE_MAX_AGE_MS,
  REROUTE_MIN_INTERVAL_MS,
  type ObstacleObservation,
  clearedSince,
  freshObstacles,
  obstacleSourceStatus,
  rerouteDecision,
  sameObstacle,
  toBlockedRegions,
} from "./obstacle-reroute.ts";

// Synthetic observations in canonical metres: they exercise the rules, they are not device data.
const F = "floor";
const box = (minX: number, minY: number, maxX: number, maxY: number, observedAtMs = 0): ObstacleObservation =>
  ({ floorId: F, minX, minY, maxX, maxY, observedAtMs, coordinateFrameId: "frame" });
const path: Vec3[] = [[0, 0, 0], [10, 0, 0]];

test("without an obstacle source nothing is reported and no live detection is claimed", () => {
  const d = rerouteDecision({ floorId: F, remainingPath: path, routedAround: [], observed: null, nowMs: 0, lastRerouteMs: null });
  assert.deepEqual(d, { reroute: false, reason: "NO_OBSTACLE_SOURCE", obstacles: [] });
  assert.equal(obstacleSourceStatus(null), "UNAVAILABLE");
  assert.equal(obstacleSourceStatus([]), "LIVE");
});

test("a new obstacle on the path ahead asks for a reroute; one beside the path does not", () => {
  const onPath = box(4, -0.2, 5, 0.2, 1000);
  const beside = box(4, 2, 5, 3, 1000);
  assert.equal(rerouteDecision({ floorId: F, remainingPath: path, routedAround: [], observed: [beside], nowMs: 2000, lastRerouteMs: null }).reason,
    "NOTHING_NEW_ON_PATH");
  const d = rerouteDecision({ floorId: F, remainingPath: path, routedAround: [], observed: [beside, onPath], nowMs: 2000, lastRerouteMs: null });
  assert.equal(d.reroute, true);
  assert.equal(d.obstacles.length, 2, "every fresh observation is reported with the new request");
});

test("an obstacle within the walking body's radius of the path counts as on it", () => {
  const grazing = box(4, 0.3, 5, 1, 0);
  assert.equal(rerouteDecision({ floorId: F, remainingPath: path, routedAround: [], observed: [grazing], nowMs: 0, lastRerouteMs: null }).reroute, true);
});

test("an obstacle already routed around, or one that only shifted slightly, does not trigger another reroute", () => {
  const first = box(4, -0.2, 5, 0.2, 0);
  const jitter = box(4.1, -0.15, 5.1, 0.25, 3000);
  assert.ok(sameObstacle(first, jitter));
  assert.equal(rerouteDecision({ floorId: F, remainingPath: path, routedAround: [first], observed: [jitter], nowMs: 3000, lastRerouteMs: 0 }).reason,
    "NOTHING_NEW_ON_PATH");
  const moved = box(6, -0.2, 7, 0.2, 9000);
  assert.ok(!sameObstacle(first, moved));
  assert.equal(rerouteDecision({ floorId: F, remainingPath: path, routedAround: [first], observed: [moved], nowMs: 9000, lastRerouteMs: 0 }).reroute, true);
});

test("reroutes are rate-limited", () => {
  const onPath = box(4, -0.2, 5, 0.2, 1000);
  const soon = rerouteDecision({ floorId: F, remainingPath: path, routedAround: [], observed: [onPath], nowMs: 2000, lastRerouteMs: 1000 });
  assert.equal(soon.reason, "TOO_SOON");
  const later = rerouteDecision({ floorId: F, remainingPath: path, routedAround: [], observed: [onPath], nowMs: 1000 + REROUTE_MIN_INTERVAL_MS, lastRerouteMs: 1000 });
  assert.equal(later.reroute, true);
});

test("stale observations, ones from another floor, and ones behind the traveller are ignored", () => {
  const stale = box(4, -0.2, 5, 0.2, 0);
  assert.deepEqual(freshObstacles([stale], OBSTACLE_MAX_AGE_MS + 1), []);
  assert.equal(rerouteDecision({ floorId: F, remainingPath: path, routedAround: [], observed: [stale], nowMs: OBSTACLE_MAX_AGE_MS + 1, lastRerouteMs: null }).reroute, false);
  const elsewhere = { ...box(4, -0.2, 5, 0.2, 0), floorId: "other" };
  assert.equal(rerouteDecision({ floorId: F, remainingPath: path, routedAround: [], observed: [elsewhere], nowMs: 0, lastRerouteMs: null }).reroute, false);
  const behind = box(1, -0.2, 2, 0.2, 0);
  assert.equal(rerouteDecision({ floorId: F, remainingPath: [[5, 0, 0], [10, 0, 0]], routedAround: [], observed: [behind], nowMs: 0, lastRerouteMs: null }).reroute, false);
});

test("observations are reported with their observation time and frame", () => {
  assert.deepEqual(toBlockedRegions([box(1, 2, 3, 4, Date.UTC(2026, 9, 14, 12, 0, 0))]), [
    { floorId: F, minX: 1, minY: 2, maxX: 3, maxY: 4, observedAt: "2026-10-14T12:00:00.000Z", coordinateFrameId: "frame" },
  ]);
});

// ---- restoring the route, unreachable reroutes ---------------------------------------------------------------------

test("an obstacle outside the route never asks for a reroute", () => {
  const far = box(3, 4, 4, 5, 1000);
  const d = rerouteDecision({ floorId: F, remainingPath: path, routedAround: [], observed: [far], nowMs: 2000, lastRerouteMs: null });
  assert.equal(d.reroute, false);
  assert.equal(d.reason, "NOTHING_NEW_ON_PATH");
});

test("once everything the route avoided is gone for CLEAR_HOLD_MS, the normal route is restored", () => {
  const cart = box(4, -0.2, 5, 0.2, 1000);
  // still there: nothing to do
  let since = clearedSince([cart], [cart], null, 2000);
  assert.equal(since, null);
  // gone: the hold starts, and a person stepping back in resets it
  since = clearedSince([cart], [], since, 10_000);
  assert.equal(since, 10_000);
  const early = rerouteDecision({ floorId: F, remainingPath: path, routedAround: [cart], observed: [], nowMs: 10_000 + CLEAR_HOLD_MS - 1,
    lastRerouteMs: null, clearedSinceMs: since });
  assert.equal(early.reroute, false);
  const restored = rerouteDecision({ floorId: F, remainingPath: path, routedAround: [cart], observed: [], nowMs: 10_000 + CLEAR_HOLD_MS,
    lastRerouteMs: null, clearedSinceMs: since });
  assert.deepEqual(restored, { reroute: true, reason: "OBSTACLE_CLEARED", obstacles: [] });
  assert.equal(clearedSince([cart], [cart], since, 11_000), null);
  assert.equal(clearedSince([], [], 5, 11_000), null, "a route planned around nothing has nothing to restore");
});

test("when a reroute found no way around, the same obstacle never asks again; a new one does", () => {
  const blocker = box(4, -0.5, 5, 0.5, 1000);
  const d = rerouteDecision({ floorId: F, remainingPath: path, routedAround: [], observed: [blocker], nowMs: 20_000,
    lastRerouteMs: 0, failedAround: [blocker] });
  assert.equal(d.reroute, false);
  assert.equal(d.reason, "NO_ALTERNATIVE");
  const another = box(7, -0.2, 7.5, 0.2, 1000);
  const again = rerouteDecision({ floorId: F, remainingPath: path, routedAround: [], observed: [blocker, another], nowMs: 20_000,
    lastRerouteMs: 0, failedAround: [blocker] });
  assert.equal(again.reroute, true);
});

test("an obstacle around the traveller's own position is never sent: there is no route from inside a box", () => {
  const atMe = box(-0.5, -0.5, 0.5, 0.5, 1000);
  const d = rerouteDecision({ floorId: F, remainingPath: path, routedAround: [], observed: [atMe], nowMs: 2000, lastRerouteMs: null });
  assert.equal(d.reroute, false);
  assert.deepEqual(d.obstacles, []);
});
