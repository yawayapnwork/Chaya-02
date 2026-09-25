import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import {
  type CoordinateFrame,
  IDENTITY,
  NotCalibratedError,
  type Vec3,
  applySimilarity,
  composeSimilarity,
  invertSimilarity,
  isCanonical,
  similarity,
  toCanonical,
} from "./coordinate-frame.ts";

// The synthetic MATHEMATICAL fixture shared with the worker and API tests. Not venue data.
const fixture = JSON.parse(
  readFileSync(new URL("../../../packages/contracts/fixtures/synthetic-calibration.json", import.meta.url), "utf8"),
);
const truth = similarity(fixture.truth.scale, fixture.truth.rotation, [
  fixture.truth.translation.x, fixture.truth.translation.y, fixture.truth.translation.z,
]);

const closeVec = (a: Vec3, b: Vec3, eps = 1e-9) =>
  a.forEach((v, i) => assert.ok(Math.abs(v - b[i]) <= eps, `${a} !~= ${b}`));

const P: Vec3 = [1, 2, 3];

test("identity leaves points unchanged", () => closeVec(applySimilarity(IDENTITY, P), P, 0));

test("pure translation", () => closeVec(applySimilarity(similarity(1, IDENTITY.rotation, [1, -2, 0.5]), P), [2, 0, 3.5], 1e-15));

test("pure rotation (90 degrees about +Z)", () => {
  const rz90 = similarity(1, { w: Math.SQRT1_2, x: 0, y: 0, z: Math.SQRT1_2 }, [0, 0, 0]);
  closeVec(applySimilarity(rz90, [1, 0, 0]), [0, 1, 0], 1e-15);
});

test("pure scale", () => closeVec(applySimilarity(similarity(2.5, IDENTITY.rotation, [0, 0, 0]), P), [2.5, 5, 7.5], 1e-15));

test("reconstruction to metric and back with the synthetic fixture", () => {
  const inverse = invertSimilarity(truth);
  for (const cp of fixture.controlPoints) {
    closeVec(applySimilarity(truth, cp.reconstruction), cp.venue);
    closeVec(applySimilarity(inverse, cp.venue), cp.reconstruction);
  }
});

test("round trip error stays at double precision", () => {
  const inverse = invertSimilarity(truth);
  let worst = 0;
  for (let i = 0; i < 500; i++) {
    const p: Vec3 = [Math.sin(i) * 400, Math.cos(i * 1.3) * 400, (i % 17) * 3];
    const back = applySimilarity(inverse, applySimilarity(truth, p));
    worst = Math.max(worst, Math.hypot(back[0] - p[0], back[1] - p[1], back[2] - p[2]));
  }
  assert.ok(worst < 1e-9, `round-trip error ${worst}`);
});

test("compose applies the inner transform first", () => {
  const a = similarity(2, { w: Math.SQRT1_2, x: 0, y: 0, z: Math.SQRT1_2 }, [1, 0, 0]);
  const b = similarity(0.5, { w: 0.9, x: 0.1, y: -0.3, z: 0.2 }, [0, 3, 0]);
  closeVec(applySimilarity(composeSimilarity(a, b), P), applySimilarity(a, applySimilarity(b, P)), 1e-12);
});

test("gravity: the fixture's reconstruction up maps to canonical +Z", () => {
  const up = fixture.gravity.upReconstruction as Vec3;
  const moved = applySimilarity(similarity(1, truth.rotation, [0, 0, 0]), up);
  closeVec(moved, [0, 0, 1], 1e-12);
});

const frame = (canonical: boolean): CoordinateFrame => ({
  id: "f1", sourceRunId: "r1", floorId: null, version: 1, status: "ACTIVE", canonical,
  metricStatus: "METRIC", gravityStatus: canonical ? "ALIGNED" : "NOT_ALIGNED",
  horizontalDatum: canonical ? "FLOOR_LOCAL" : "NONE", scale: 0.37,
  rotation: canonical ? fixture.truth.rotation : null, translation: canonical ? fixture.truth.translation : null,
  method: "TEST", calibratedAt: "2026-01-01T00:00:00Z",
});

test("a canonical frame yields its transform; anything else is NotCalibrated, never assumed metres", () => {
  assert.ok(isCanonical(frame(true)));
  closeVec(applySimilarity(toCanonical(frame(true)), fixture.controlPoints[1].reconstruction), fixture.controlPoints[1].venue);
  assert.equal(isCanonical(frame(false)), false);
  assert.throws(() => toCanonical(frame(false)), NotCalibratedError);
  assert.throws(() => toCanonical(null), NotCalibratedError);
});

test("rejects a non-positive scale and a zero quaternion", () => {
  assert.throws(() => similarity(0, IDENTITY.rotation, [0, 0, 0]));
  assert.throws(() => similarity(1, { w: 0, x: 0, y: 0, z: 0 }, [0, 0, 0]));
});
