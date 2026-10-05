import assert from "node:assert/strict";
import { test } from "node:test";
import { forScene, initialVersion, sceneKey, versionLabel } from "./version-scope.ts";

test("a finalized version is keyed by the version, a plain reconstruction by its run", () => {
  assert.equal(sceneKey({ runId: "run-1", scanVersionId: "sv-1" }), "version:sv-1");
  assert.equal(sceneKey({ runId: "run-1", scanVersionId: null }), "run:run-1");
  assert.equal(sceneKey({ runId: "run-1" }), "run:run-1");
  assert.equal(sceneKey(null), null);
});

test("switching versions: what was loaded for the previous version is never used for the new one", () => {
  const v1 = sceneKey({ runId: "run-1", scanVersionId: "sv-1" });
  const v2 = sceneKey({ runId: "run-2", scanVersionId: "sv-2" });
  const poisOfV2 = { key: v2!, value: ["kiosk"] };
  const poisOfV1 = { key: v1!, value: ["chair"] };

  // Version 2 on screen, its POIs loaded.
  assert.deepEqual(forScene(poisOfV2, v2), ["kiosk"]);
  // The user switches to version 1: until version 1's own POIs arrive, nothing is drawn -- not version 2's.
  assert.equal(forScene(poisOfV2, v1), null);
  assert.deepEqual(forScene(poisOfV1, v1), ["chair"]);
  // A late response for version 2 arriving after the switch is not used either.
  assert.equal(forScene(poisOfV2, v1), null);
  // Nothing loaded / nothing on screen.
  assert.equal(forScene(null, v1), null);
  assert.equal(forScene(poisOfV1, null), null);
});

test("two runs that share no version never share overlays", () => {
  assert.equal(forScene({ key: sceneKey({ runId: "run-1" })!, value: 1 }, sceneKey({ runId: "run-2" })), null);
});

test("the viewer opens on the version the floor publishes, not the newest one", () => {
  const v3 = { runId: "run-3", versionNumber: 3, current: false }; // a newer version that is not published
  const v2 = { runId: "run-2", versionNumber: 2, current: true };
  const v1 = { runId: "run-1", versionNumber: 1, current: false };
  assert.equal(initialVersion([v3, v2, v1]), v2);
  assert.equal(initialVersion([v3, v1]), v3, "nothing published: the newest finalized version");
  assert.equal(initialVersion([]), null);
});

test("the picker names versions by number and says which one is current", () => {
  assert.equal(versionLabel({ versionNumber: 2, generatedAt: "x" }, "29 Sep", true), "v2 · 29 Sep (current)");
  assert.equal(versionLabel({ versionNumber: 1, generatedAt: "x" }, "28 Sep", false), "v1 · 28 Sep");
  assert.equal(versionLabel({ versionNumber: null, generatedAt: "x" }, "27 Sep", false), "27 Sep (not a finalized version)");
});
