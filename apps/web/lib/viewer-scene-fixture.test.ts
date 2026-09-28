// FORMAT VALIDATION of the viewer fixture scene (packages/contracts/fixtures/viewer-scene) -- a deterministic synthetic
// Gaussian scene exported by the worker's production ARTIFACT_GENERATION stage. NOT a reconstruction of any venue.
//
// The worker test tests/unit/test_viewer_scene_fixture.py fails unless scene.ksplat is exactly what that stage writes
// today for scene.ply. Here the pinned GaussianSplats3D reads it: the real KSplatLoader (count, every splat against the
// library's own PlyLoader on the same cloud), then the viewer's own placement math (lib/coordinate-frame) with the
// fixture's similarity puts the scene where it was built: a 6 m x 4 m floor at z = 0 and 2 m pillars.

import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import * as THREE from "three";
import { KSplatLoader, PlyLoader } from "@mkkellogg/gaussian-splats-3d/build/gaussian-splats-3d.module.js";
import { applySimilarity, similarity, type Vec3 } from "./coordinate-frame.ts";

(globalThis as { window?: unknown }).window ??= globalThis; // the loaders defer work through window.setTimeout

const FIXTURE = new URL("../../../packages/contracts/fixtures/viewer-scene/", import.meta.url);
const doc = JSON.parse(readFileSync(new URL("fixture.json", FIXTURE), "utf8"));

function fileBuffer(name: string): ArrayBuffer {
  const bytes = readFileSync(new URL(name, FIXTURE));
  return bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer;
}

test("the fixture is labelled as a format fixture, not a reconstruction", () => {
  assert.match(doc.note, /NOT a reconstruction/);
});

test("the pinned KSplatLoader reads every splat of the exported scene, identical to the library's own PLY route", async () => {
  const fromKsplat = await KSplatLoader.loadFromFileData(fileBuffer("scene.ksplat"));
  const fromPly = await PlyLoader.loadFromFileData(fileBuffer("scene.ply"), 0, 0, false, 0);
  assert.equal(fromKsplat.compressionLevel, 0);
  assert.equal(fromKsplat.getSplatCount(), doc.splat_count);
  assert.equal(fromPly.getSplatCount(), doc.splat_count);
  const [a, b, c, d] = [new THREE.Vector3(), new THREE.Vector3(), new THREE.Vector4(), new THREE.Vector4()];
  for (let i = 0; i < doc.splat_count; i++) {
    fromKsplat.getSplatCenter(i, a);
    fromPly.getSplatCenter(i, b);
    assert.deepEqual(a.toArray(), b.toArray(), `splat ${i} position`);
    fromKsplat.getSplatColor(i, c);
    fromPly.getSplatColor(i, d);
    assert.deepEqual(c.toArray(), d.toArray(), `splat ${i} colour and opacity`);
  }
});

test("placed with the fixture's similarity by the viewer's own math, the scene is the 6 x 4 x 2 m box it was built as", async () => {
  const buffer = await KSplatLoader.loadFromFileData(fileBuffer("scene.ksplat"));
  const t = doc.reconstruction_to_canonical;
  const [w, x, y, z] = t.rotation_wxyz;
  const toCanonical = similarity(t.scale, { w, x, y, z }, t.translation as Vec3);
  const min = [Infinity, Infinity, Infinity];
  const max = [-Infinity, -Infinity, -Infinity];
  const p = new THREE.Vector3();
  for (let i = 0; i < buffer.getSplatCount(); i++) {
    buffer.getSplatCenter(i, p);
    applySimilarity(toCanonical, p.toArray() as Vec3).forEach((v, k) => {
      min[k] = Math.min(min[k], v);
      max[k] = Math.max(max[k], v);
    });
  }
  min.forEach((v, k) => assert.ok(Math.abs(v - doc.canonical_bounds.min[k]) < 1e-4, `min[${k}] ${v}`));
  max.forEach((v, k) => assert.ok(Math.abs(v - doc.canonical_bounds.max[k]) < 1e-4, `max[${k}] ${v}`));
});
