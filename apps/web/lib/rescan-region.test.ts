import assert from "node:assert/strict";
import { test } from "node:test";
import { parseRegion, polygonArea } from "./rescan-region.ts";

test("a rectangle typed one vertex per line", () => {
  const r = parseRegion("4.4, -0.1\n6.1, -0.1\n6.1 2.7\n4.4;2.7\n");
  assert.ok("points" in r);
  assert.deepEqual(r.points, [[4.4, -0.1], [6.1, -0.1], [6.1, 2.7], [4.4, 2.7]]);
  assert.ok(Math.abs(r.areaSquareMeters - 1.7 * 2.8) < 1e-9);
});

test("too few vertices, a non-number and a repeated vertex are refused with the reason", () => {
  assert.deepEqual(parseRegion("0,0\n1,0"), { error: "A region needs at least 3 vertices." });
  const bad = parseRegion("0,0\n1,x\n1,1");
  assert.ok("error" in bad && bad.error.startsWith("Line 2"));
  const rep = parseRegion("0,0\n1,0\n0,0\n0,1");
  assert.ok("error" in rep && rep.error.includes("repeated"));
  const three = parseRegion("0,0,0\n1,0\n0,1");
  assert.ok("error" in three && three.error.startsWith("Line 1"));
});

test("area does not depend on winding order", () => {
  assert.equal(polygonArea([[0, 0], [2, 0], [2, 3], [0, 3]]), 6);
  assert.equal(polygonArea([[0, 0], [0, 3], [2, 3], [2, 0]]), 6);
});
