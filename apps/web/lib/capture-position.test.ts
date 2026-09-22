import assert from "node:assert/strict";
import { test } from "node:test";
import { boundingBoxOf, fitViewport, pixelToPlan, planToPixel } from "./capture-position.ts";

test("boundingBoxOf: the union of all rings, or null when there are none", () => {
  assert.deepEqual(boundingBoxOf([[{ x: 0, y: 0 }, { x: 4, y: 3 }], [{ x: -1, y: 5 }]]), { minX: -1, minY: 0, maxX: 4, maxY: 5 });
  assert.equal(boundingBoxOf([]), null);
  assert.equal(boundingBoxOf([[]]), null);
});

test("planToPixel / pixelToPlan round-trip for points inside the room", () => {
  const box = { minX: 0, minY: 0, maxX: 8, maxY: 6 };
  const vp = fitViewport(box, 400, 300, 20);
  for (const p of [{ x: 0, y: 0 }, { x: 8, y: 6 }, { x: 4, y: 3 }, { x: 1.5, y: 5.2 }]) {
    const px = planToPixel(vp, p);
    const back = pixelToPlan(vp, px.x, px.y);
    assert.ok(Math.abs(back.x - p.x) < 1e-9, `x round-trip: ${back.x} vs ${p.x}`);
    assert.ok(Math.abs(back.y - p.y) < 1e-9, `y round-trip: ${back.y} vs ${p.y}`);
  }
});

test("planToPixel: plan north (+y) is up on screen (a smaller pixel y)", () => {
  const vp = fitViewport({ minX: 0, minY: 0, maxX: 10, maxY: 10 }, 200, 200);
  const south = planToPixel(vp, { x: 5, y: 0 });
  const north = planToPixel(vp, { x: 5, y: 10 });
  assert.ok(north.y < south.y);
});

test("fitViewport: the room's aspect ratio is preserved (no stretching) and it fits within padding", () => {
  const vp = fitViewport({ minX: 0, minY: 0, maxX: 20, maxY: 5 }, 400, 300, 20);
  const corners = [{ x: 0, y: 0 }, { x: 20, y: 0 }, { x: 20, y: 5 }, { x: 0, y: 5 }].map((p) => planToPixel(vp, p));
  for (const c of corners) {
    assert.ok(c.x >= 20 - 1e-6 && c.x <= 380 + 1e-6, `x within padding: ${c.x}`);
    assert.ok(c.y >= 20 - 1e-6 && c.y <= 280 + 1e-6, `y within padding: ${c.y}`);
  }
});
