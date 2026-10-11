import assert from "node:assert/strict";
import { test } from "node:test";
import { minimapBox } from "./capture-minimap.ts";
import type { HudStatus } from "./hud-types.ts";

const base = { pathSoFar: [], plannedPath: [], currentPosition: null } as unknown as HudStatus;

test("minimapBox: covers the bounds, the path, the waypoints and the position, with a margin", () => {
  const s = {
    ...base,
    scene: { areas: [[{ x: 0, y: 0 }, { x: 8, y: 0 }, { x: 8, y: 6 }, { x: 0, y: 6 }]] },
    pathSoFar: [{ x: 9, y: 1 }],
    plannedPath: [{ order: 1, x: -1, y: 3, yawDegrees: 0, type: "CORNER", reason: "", expectedCoverageGainM2: 0 }],
    currentPosition: { x: 4, y: 7, headingDegrees: null, asOfMs: 0 },
  } as HudStatus;
  assert.deepEqual(minimapBox(s), { minX: -1.5, minY: -0.5, maxX: 9.5, maxY: 7.5 });
});

test("minimapBox: nothing known yet gives the fallback outline, or nothing at all", () => {
  assert.equal(minimapBox(null), null);
  assert.deepEqual(minimapBox(base, [[{ x: 0, y: 0 }, { x: 4, y: 3 }]]), { minX: -0.5, minY: -0.5, maxX: 4.5, maxY: 3.5 });
});
