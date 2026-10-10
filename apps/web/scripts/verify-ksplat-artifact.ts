// Checks one .ksplat produced by the worker with the REAL loaders of the pinned viewer library
// (@mkkellogg/gaussian-splats-3d 0.4.7, the ES module build the viewer bundles), the same way lib/ksplat-compat.test.ts
// checks the committed fixture. That test proves the encoder on a fixed cloud; this script lets the worker's smoke and
// GPU acceptance tests (services/reconstruction/tests) apply the same proof to the cloud a training run actually produced.
//
//   node --experimental-strip-types scripts/verify-ksplat-artifact.ts <dir>
//
// <dir> holds scene.ksplat (the ARTIFACT_GENERATION output) and, when the cloud it was made from is available,
// splat.ply (that cloud) and cloud.json (its fields: positions, scales_log, rotations_wxyz, opacity_logit, colors_dc).
// With the cloud, every splat is compared with it and with the library's own PLY route. Without it (an artifact downloaded
// from the API, which serves only the viewer's files), the library must accept the file and read every splat with finite
// values. Prints a JSON summary and exits 1 on the first problem. Nothing here decodes the format itself.

import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import * as THREE from "three";
import { KSplatLoader, PlyLoader, type SplatBuffer } from "@mkkellogg/gaussian-splats-3d/build/gaussian-splats-3d.module.js";

(globalThis as { window?: unknown }).window ??= globalThis; // the loaders defer work through window.setTimeout

const SH_C0 = 0.28209479177387814;
const f32 = Math.fround;
const byte = (v: number) => Math.min(255, Math.max(0, Math.floor(v)));

interface Cloud {
  positions: number[][];
  scales_log: number[][];
  rotations_wxyz: number[][];
  opacity_logit: number[];
  colors_dc: number[][];
}

function buffer(path: string): ArrayBuffer {
  const bytes = readFileSync(path);
  return bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer;
}

function read(b: SplatBuffer, i: number) {
  const center = new THREE.Vector3();
  const scale = new THREE.Vector3();
  const rotation = new THREE.Quaternion();
  const color = new THREE.Vector4();
  b.getSplatCenter(i, center);
  b.getSplatScaleAndRotation(i, scale, rotation);
  b.getSplatColor(i, color);
  return { center: center.toArray(), scale: scale.toArray(), rotationXYZW: rotation.toArray(), rgba: color.toArray() };
}

function fail(message: string): never {
  console.log(JSON.stringify({ ok: false, error: message }));
  process.exit(1);
}

function close(actual: number[], wanted: number[], relTol: number, what: string) {
  actual.forEach((v, k) => {
    if (!(Math.abs(v - wanted[k]) <= relTol * Math.max(1, Math.abs(wanted[k])))) fail(`${what}[${k}]: ${v} != ${wanted[k]}`);
  });
}

const dir = process.argv[2];
if (!dir) fail("usage: verify-ksplat-artifact.ts <dir with scene.ksplat [, splat.ply, cloud.json]>");
const ksplatBytes = buffer(join(dir, "scene.ksplat"));
if (KSplatLoader.checkVersion(ksplatBytes) !== true) fail("the pinned KSplatLoader rejects the version");
const fromKsplat = await KSplatLoader.loadFromFileData(ksplatBytes);
if (!existsSync(join(dir, "cloud.json"))) {
  const count = fromKsplat.getSplatCount();
  if (count < 1) fail("the file holds no splats");
  if (ksplatBytes.byteLength !== 4096 + 1024 + 44 * count) fail(`${ksplatBytes.byteLength} bytes for ${count} splats`);
  for (let i = 0; i < count; i++) {
    const s = read(fromKsplat, i);
    if (![...s.center, ...s.scale, ...s.rotationXYZW, ...s.rgba].every(Number.isFinite)) fail(`splat ${i} has a non-finite value`);
  }
  console.log(JSON.stringify({ ok: true, mode: "library-load-only", library: "@mkkellogg/gaussian-splats-3d@0.4.7", splats: count,
    ksplatBytes: ksplatBytes.byteLength }));
  process.exit(0);
}
const cloud: Cloud = JSON.parse(readFileSync(join(dir, "cloud.json"), "utf8"));
const fromPly = await PlyLoader.loadFromFileData(buffer(join(dir, "splat.ply")), 0, 0, false, 0);
const n = cloud.positions.length;
if (fromKsplat.compressionLevel !== 0) fail(`compression level ${fromKsplat.compressionLevel}`);
if (fromKsplat.getSplatCount() !== n) fail(`KSplatLoader reads ${fromKsplat.getSplatCount()} splats, the trained cloud has ${n}`);
if (fromPly.getSplatCount() !== n) fail(`PlyLoader reads ${fromPly.getSplatCount()} splats, the trained cloud has ${n}`);
for (let i = 0; i < n; i++) {
  const got = read(fromKsplat, i);
  const q = cloud.rotations_wxyz[i].map(f32);
  const norm = Math.hypot(...q);
  const center = cloud.positions[i].map(f32);
  if (got.center.some((v, k) => v !== center[k])) fail(`splat ${i} position ${got.center} != ${center}`);
  close(got.scale, cloud.scales_log[i].map((s) => Math.exp(f32(s))), 1e-6, `splat ${i} scale`);
  // q and -q are the same rotation; the library normalises without fixing the sign
  const want = [q[1] / norm, q[2] / norm, q[3] / norm, q[0] / norm];
  const sign = Math.sign(got.rotationXYZW[3] || 1) === Math.sign(want[3] || 1) ? 1 : -1;
  close(got.rotationXYZW, want.map((v) => v * sign), 1e-6, `splat ${i} rotation`);
  const rgba = [...cloud.colors_dc[i].map((c) => byte((0.5 + SH_C0 * f32(c)) * 255)),
    byte((1 / (1 + Math.exp(-f32(cloud.opacity_logit[i])))) * 255)];
  if (got.rgba.some((v, k) => v !== rgba[k])) fail(`splat ${i} colour/opacity bytes ${got.rgba} != ${rgba}`);
  const viaPly = read(fromPly, i);
  if (viaPly.center.some((v, k) => v !== got.center[k])) fail(`splat ${i}: the .ksplat and the library's PLY route disagree on position`);
  if (viaPly.rgba.some((v, k) => v !== got.rgba[k])) fail(`splat ${i}: the .ksplat and the library's PLY route disagree on colour`);
}
console.log(JSON.stringify({ ok: true, library: "@mkkellogg/gaussian-splats-3d@0.4.7", splats: n, ksplatBytes: ksplatBytes.byteLength }));
