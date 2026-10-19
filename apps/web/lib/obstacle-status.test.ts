import assert from "node:assert/strict";
import { test } from "node:test";
import type { ObstacleObservation } from "./obstacle-reroute.ts";
import { obstacleMessages } from "./obstacle-status.ts";

const cart: ObstacleObservation = { floorId: "f", minX: 0, minY: 3, maxX: 1, maxY: 4, observedAtMs: 0, coordinateFrameId: "fr",
  category: "UNCLASSIFIED", uncertaintyMeters: 0.32 };
const live = { kind: "LIVE" as const, detail: "ARCore depth" };

test("a position is always an estimate with its uncertainty, never shown as verified", () => {
  const m = obstacleMessages({ sensing: live, onPath: [{ obstacle: cart, aheadMeters: 3.4 }], movingNearby: 0, outcome: { kind: "NONE" } });
  assert.equal(m.length, 1);
  assert.equal(m[0].text, "Something is in the way about 3 m ahead (estimated position, ±0.3 m).");
  assert.ok(!/verified|exact/i.test(m[0].text));
  const person = obstacleMessages({ sensing: live, onPath: [{ obstacle: { ...cart, category: "PERSON" }, aheadMeters: null }], movingNearby: 0,
    outcome: { kind: "NONE" } });
  assert.match(person[0].text, /^A person is in the way on the route/);
});

test("rerouting, rerouted, restored, and why a route could not be recalculated", () => {
  const text = (outcome: Parameters<typeof obstacleMessages>[0]["outcome"]) =>
    obstacleMessages({ sensing: live, onPath: [], movingNearby: 0, outcome }).map((m) => m.text).join(" | ");
  assert.equal(text({ kind: "REROUTING" }), "Finding a way around…");
  assert.equal(text({ kind: "REROUTED", avoided: 2 }), "Route changed to avoid 2 obstructions.");
  assert.equal(text({ kind: "RESTORED" }), "The way is clear again: back on the usual route.");
  assert.match(text({ kind: "NO_ALTERNATIVE", stepFree: true }), /No step-free way around .* the route is unchanged/);
  assert.match(text({ kind: "NO_ALTERNATIVE", stepFree: false }), /No way around .* the route is unchanged/);
  assert.match(text({ kind: "FAILED", detail: "NETWORK_UNAVAILABLE" }), /could not be recalculated \(NETWORK_UNAVAILABLE\); the current route is kept/);
});

test("without depth, or while tracking is lost, the overlay never implies the way is clear", () => {
  const none = obstacleMessages({ sensing: { kind: "UNAVAILABLE", reason: "no depth" }, onPath: [], movingNearby: 0, outcome: { kind: "NONE" } });
  assert.equal(none[0].testId, "ar-obstacles-unavailable");
  assert.match(none[0].text, /does not adapt to people or objects/);
  const paused = obstacleMessages({ sensing: { kind: "PAUSED", reason: "tracking is limited" }, onPath: [], movingNearby: 0, outcome: { kind: "NONE" } });
  assert.equal(paused[0].testId, "ar-obstacles-paused");
  assert.match(paused[0].text, /paused \(tracking is limited\)\. Watch for people/);
});

test("people walking by are mentioned, not treated as a blockage", () => {
  const m = obstacleMessages({ sensing: live, onPath: [], movingNearby: 2, outcome: { kind: "NONE" } });
  assert.deepEqual(m.map((x) => x.testId), ["ar-moving-people"]);
  assert.match(m[0].text, /2 people are moving near the route\. The route is not changed/);
});
