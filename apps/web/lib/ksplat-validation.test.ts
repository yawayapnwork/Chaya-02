// The viewer's .ksplat contract (lib/ksplat-validation.ts) on the committed production-encoder files, against the REAL
// pinned @mkkellogg/gaussian-splats-3d: every file the validator accepts loads in the library with the splat count the
// validator reports, and the broken files it refuses include ones the library itself would accept silently. No parsing
// is mocked: the library's own SplatBuffer reads the same bytes.

import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import * as THREE from "three";
import { KSplatLoader, SplatBuffer } from "@mkkellogg/gaussian-splats-3d/build/gaussian-splats-3d.module.js";
import { KSPLAT_CONTRACT, KsplatInvalidError, type KsplatInvalidReason, checkKsplatSize, validateKsplat } from "./ksplat-validation.ts";

(globalThis as { window?: unknown }).window ??= globalThis; // the library's loaders defer through window.setTimeout

const FIXTURES = new URL("../../../packages/contracts/fixtures/", import.meta.url);
const CONTRACT = new URL("../../../packages/contracts/viewer/ksplat-contract.json", import.meta.url);
const small = readFileSync(new URL("ksplat/scene.ksplat", FIXTURES));
const scene = readFileSync(new URL("viewer-scene/scene.ksplat", FIXTURES));

const ab = (b: Uint8Array) => b.buffer.slice(b.byteOffset, b.byteOffset + b.byteLength) as ArrayBuffer;
const mutate = (change: (b: Buffer) => void) => {
  const b = Buffer.from(small);
  change(b);
  return b;
};

function refused(bytes: Uint8Array, reason: KsplatInvalidReason) {
  assert.throws(() => validateKsplat(ab(bytes)), (e: unknown) => e instanceof KsplatInvalidError && e.reason === reason);
}

/** What the real library makes of the bytes, synchronously (its loader would throw from inside a timer instead). */
function library(bytes: Uint8Array): { count: number; last: number[] } | Error {
  try {
    KSplatLoader.checkVersion(ab(bytes));
    // The library's typings omit the constructor's data argument; its source (SplatBuffer.constructFromBuffer) takes it.
    const buffer = new (SplatBuffer as unknown as new (data: ArrayBuffer) => InstanceType<typeof SplatBuffer>)(ab(bytes));
    const v = new THREE.Vector3();
    buffer.getSplatCenter(buffer.getSplatCount() - 1, v);
    return { count: buffer.getSplatCount(), last: v.toArray() };
  } catch (e) {
    return e as Error;
  }
}

test("the constants are the shared contract file", () => {
  const file = JSON.parse(readFileSync(CONTRACT, "utf8"));
  delete file.description;
  assert.deepEqual({ ...KSPLAT_CONTRACT }, file);
});

test("both committed production files meet the contract and load in the real library with the same splat count", async () => {
  for (const [bytes, splats] of [[small, 3], [scene, 3701]] as const) {
    const info = validateKsplat(ab(bytes));
    assert.deepEqual(info, {
      format: "ksplat", contract: "@mkkellogg/gaussian-splats-3d@0.4.7", version: "0.1", compressionLevel: 0,
      sphericalHarmonicsDegree: 0, sectionCount: 1, splatCount: splats,
    });
    const loaded = await KSplatLoader.loadFromFileData(ab(bytes));
    assert.equal(loaded.getSplatCount(), info.splatCount);
  }
});

test("broken files the real library accepts silently are refused", () => {
  const cases: [string, Uint8Array, KsplatInvalidReason][] = [
    ["one byte short (the last splat's opacity is gone)", small.subarray(0, small.length - 1), "TRUNCATED"],
    ["trailing bytes", Buffer.concat([small, Buffer.alloc(100)]), "TRAILING_BYTES"],
    ["file splat count disagrees with the section", mutate((b) => b.writeUInt32LE(2, 16)), "INCONSISTENT_COUNTS"],
    ["section claims more splats than the file holds", mutate((b) => b.writeUInt32LE(4, 4096 + 4)), "INCONSISTENT_COUNTS"],
    ["version 0.2, whose layout nothing here has proven", mutate((b) => (b[1] = 2)), "UNSUPPORTED_VERSION"],
  ];
  for (const [name, bytes, reason] of cases) {
    assert.ok(!(library(bytes) instanceof Error), `${name}: the library itself accepts it`);
    refused(bytes, reason);
  }
});

test("broken files the real library chokes on are refused before it sees them", () => {
  const cases: [string, Uint8Array, KsplatInvalidReason][] = [
    ["a whole splat missing", small.subarray(0, small.length - 44), "TRUNCATED"],
    ["compression level 1 header on level-0 data", mutate((b) => b.writeUInt16LE(1, 20)), "UNSUPPORTED_COMPRESSION"],
    ["version 0.0", mutate((b) => (b[1] = 0)), "UNSUPPORTED_VERSION"],
  ];
  for (const [name, bytes, reason] of cases) {
    assert.ok(library(bytes) instanceof Error, `${name}: the library throws`);
    refused(bytes, reason);
  }
});

test("every other way out of the contract is refused", () => {
  refused(mutate((b) => (b[0] = 1)), "UNSUPPORTED_VERSION");
  refused(mutate((b) => b.writeUInt16LE(1, 4096 + 40)), "UNSUPPORTED_SH_DEGREE");
  refused(mutate((b) => b.writeUInt32LE(2, 4)), "UNSUPPORTED_LAYOUT");
  refused(mutate((b) => b.writeUInt32LE(1, 4096 + 12)), "UNSUPPORTED_LAYOUT");
  refused(mutate((b) => b.writeUInt32LE(0, 4096 + 28)), "INCONSISTENT_COUNTS");
  refused(small.subarray(0, 5120), "TRUNCATED");
  refused(new Uint8Array(0), "TRUNCATED");
  refused(readFileSync(new URL("ksplat/scene.ply", FIXTURES)), "TRUNCATED");
  // A header-complete file declaring no splats: refused as EMPTY, not handed to the library.
  const empty = mutate((b) => { for (const o of [12, 16, 4096, 4100, 4096 + 28]) b.writeUInt32LE(0, o); });
  assert.throws(() => validateKsplat(ab(Buffer.concat([empty.subarray(0, 5120), Buffer.alloc(44)]))), /INCONSISTENT_COUNTS|EMPTY/);
});

test("sizes outside the contract are refused before downloading", () => {
  assert.throws(() => checkKsplatSize(KSPLAT_CONTRACT.maxBytes + 1), (e: unknown) => e instanceof KsplatInvalidError && e.reason === "TOO_LARGE");
  assert.throws(() => checkKsplatSize(5163), (e: unknown) => e instanceof KsplatInvalidError && e.reason === "TRUNCATED");
  assert.throws(() => checkKsplatSize(Number.NaN), KsplatInvalidError);
  checkKsplatSize(small.length);
});

test("the contract's library version is the one declared exactly and installed", () => {
  const pkg = JSON.parse(readFileSync(new URL("../package.json", import.meta.url), "utf8"));
  const installed = JSON.parse(readFileSync(new URL("../node_modules/@mkkellogg/gaussian-splats-3d/package.json", import.meta.url), "utf8"));
  assert.equal(pkg.dependencies["@mkkellogg/gaussian-splats-3d"], KSPLAT_CONTRACT.libraryVersion, "no range: the contract is for one version");
  assert.equal(installed.version, KSPLAT_CONTRACT.libraryVersion);
});
