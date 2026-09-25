// Viewer-compatibility proof for the worker's .ksplat encoder (services/reconstruction/chaya_worker/ksplat.py).
//
// Loads the committed production-encoder output (packages/contracts/fixtures/ksplat/scene.ksplat; the worker test
// tests/unit/test_ksplat_fixture.py fails if it no longer matches what the encoder writes) with the REAL KSplatLoader of
// the pinned @mkkellogg/gaussian-splats-3d -- its ES module build, the same file bundlers give the viewer -- and checks
// every splat against values derived here, independently of the Python code, from the input cloud (cloud.json).
// It then cross-checks against the library's own supported route: its PlyLoader reading the same cloud as a standard
// 3DGS PLY. Nothing in this file decodes the format itself.

import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import * as THREE from "three";
import { KSplatLoader, PlyLoader, type SplatBuffer } from "@mkkellogg/gaussian-splats-3d/build/gaussian-splats-3d.module.js";

// The library's loaders defer their work through window.setTimeout (its internal delayedExecute). Node has
// setTimeout on the global object; aliasing `window` to it is the only environment shim, and no loader code is replaced.
(globalThis as { window?: unknown }).window ??= globalThis;

const FIXTURE = new URL("../../../packages/contracts/fixtures/ksplat/", import.meta.url);
const SH_C0 = 0.28209479177387814;

interface Cloud {
  positions: number[][];
  scales_log: number[][];
  rotations_wxyz: number[][];
  opacity_logit: number[];
  colors_dc: number[][];
}

const cloud: Cloud = JSON.parse(readFileSync(new URL("cloud.json", FIXTURE), "utf8"));
const f32 = Math.fround;
const byte = (v: number) => Math.min(255, Math.max(0, Math.floor(v)));

/** What a correct viewer must see for splat i, from the input cloud's definitions (float32 inputs, as the PLY stores them). */
function expected(i: number) {
  const q = cloud.rotations_wxyz[i].map(f32);
  const n = Math.hypot(...q);
  return {
    center: cloud.positions[i].map(f32),
    scale: cloud.scales_log[i].map((s) => Math.exp(f32(s))),
    rotationXYZW: [q[1] / n, q[2] / n, q[3] / n, q[0] / n],
    rgba: [
      ...cloud.colors_dc[i].map((c) => byte((0.5 + SH_C0 * f32(c)) * 255)),
      byte((1 / (1 + Math.exp(-f32(cloud.opacity_logit[i])))) * 255),
    ],
  };
}

function read(buffer: SplatBuffer, i: number) {
  const center = new THREE.Vector3();
  const scale = new THREE.Vector3();
  const rotation = new THREE.Quaternion();
  const color = new THREE.Vector4();
  buffer.getSplatCenter(i, center);
  buffer.getSplatScaleAndRotation(i, scale, rotation);
  buffer.getSplatColor(i, color);
  return { center: center.toArray(), scale: scale.toArray(), rotationXYZW: rotation.toArray(), rgba: color.toArray() };
}

function fileBuffer(name: string): ArrayBuffer {
  const bytes = readFileSync(new URL(name, FIXTURE));
  return bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer;
}

const close = (actual: number[], wanted: number[], relTol: number, what: string) =>
  actual.forEach((v, k) =>
    assert.ok(Math.abs(v - wanted[k]) <= relTol * Math.max(1, Math.abs(wanted[k])), `${what}[${k}]: ${v} != ${wanted[k]}`));

test("the pinned KSplatLoader reads the production .ksplat: count, positions, scales, rotations, colours, opacity", async () => {
  const buffer = await KSplatLoader.loadFromFileData(fileBuffer("scene.ksplat"));
  assert.equal(buffer.compressionLevel, 0);
  assert.equal(buffer.getSplatCount(), cloud.positions.length);
  for (let i = 0; i < cloud.positions.length; i++) {
    const got = read(buffer, i);
    const want = expected(i);
    assert.deepEqual(got.center, want.center, `splat ${i} position`);
    close(got.scale, want.scale, 1e-6, `splat ${i} scale`); // float32 of exp(); 1e-6 relative is below float32 precision
    close(got.rotationXYZW, want.rotationXYZW, 1e-6, `splat ${i} rotation`);
    assert.deepEqual(got.rgba, want.rgba, `splat ${i} colour and opacity bytes`);
  }
});

test("known fixture values survive: the unnormalised quaternion is unit length, 90 degrees about +Y reads back as such", async () => {
  const buffer = await KSplatLoader.loadFromFileData(fileBuffer("scene.ksplat"));
  const yRot = read(buffer, 1).rotationXYZW;
  close(yRot, [0, Math.SQRT1_2, 0, Math.SQRT1_2], 1e-6, "90 degrees about +Y");
  const q = read(buffer, 2).rotationXYZW;
  assert.ok(Math.abs(Math.hypot(...q) - 1) < 1e-6, "stored rotation is normalised");
  assert.deepEqual(read(buffer, 0).rgba, [199, 127, 55, 224]); // colour (1, 0, -1) SH-DC, opacity logit 2
});

test("the .ksplat matches what the library itself makes of the same cloud as a standard 3DGS PLY", async () => {
  const fromKsplat = await KSplatLoader.loadFromFileData(fileBuffer("scene.ksplat"));
  // Library route: INRIA PLY -> uncompressed (level 0) SplatBuffer, no alpha filtering, SH degree 0.
  const fromPly = await PlyLoader.loadFromFileData(fileBuffer("scene.ply"), 0, 0, false, 0);
  assert.equal(fromPly.getSplatCount(), fromKsplat.getSplatCount());
  for (let i = 0; i < fromPly.getSplatCount(); i++) {
    const a = read(fromKsplat, i);
    const b = read(fromPly, i);
    assert.deepEqual(a.center, b.center, `splat ${i} position`);
    close(a.scale, b.scale, 1e-6, `splat ${i} scale`);
    close(a.rotationXYZW, b.rotationXYZW, 1e-6, `splat ${i} rotation`);
    assert.deepEqual(a.rgba, b.rgba, `splat ${i} colour and opacity bytes`);
  }
});

test("negative control: the pinned loader's version check accepts the fixture and rejects an unsupported header", () => {
  // loadFromFileData runs exactly this check first, inside a setTimeout callback, where a throw escapes uncaught
  // instead of rejecting -- so the check is exercised directly.
  assert.equal(KSplatLoader.checkVersion(fileBuffer("scene.ksplat")), true);
  const bad = fileBuffer("scene.ksplat");
  new Uint8Array(bad)[1] = 0; // version 0.0 < required 0.1
  assert.throws(() => KSplatLoader.checkVersion(bad), /KSplat version not supported/);
});
