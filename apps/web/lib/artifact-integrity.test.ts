// Download verification and scene scope (lib/artifact-integrity.ts) on the committed production .ksplat, with the real
// Web Crypto SHA-256 and real ReadableStreams: tampered, truncated, oversized and out-of-scope inputs all fail closed.

import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { type ArtifactRef, ArtifactIntegrityError, checkSceneScope, readExactly, sha256Hex, verifyArtifact } from "./artifact-integrity.ts";
import { KsplatInvalidError, validateKsplat } from "./ksplat-validation.ts";

const bytes = readFileSync(new URL("../../../packages/contracts/fixtures/viewer-scene/scene.ksplat", import.meta.url));
const ab = (b: Uint8Array) => b.buffer.slice(b.byteOffset, b.byteOffset + b.byteLength) as ArrayBuffer;
const VENUE = "v-1";
const RUN = "r-1";
const VERSION = "sv-1";

function ref(over: Partial<ArtifactRef> = {}): ArtifactRef {
  return {
    kind: "KSPLAT", contentType: "application/octet-stream", sizeBytes: bytes.length,
    sha256: createHash("sha256").update(bytes).digest("hex"),
    url: `/api/v1/venues/${VENUE}/scan-versions/${VERSION}/artifacts/KSPLAT`, format: validateKsplat(ab(bytes)), ...over,
  };
}

const code = (c: string) => (e: unknown) => e instanceof ArtifactIntegrityError && e.code === c;

function stream(chunks: Uint8Array[]): ReadableStream<Uint8Array> {
  return new ReadableStream({ start(c) { chunks.forEach((x) => c.enqueue(x)); c.close(); } });
}

test("sha256Hex is the standard SHA-256", async () => {
  assert.equal(await sha256Hex(ab(bytes)), createHash("sha256").update(bytes).digest("hex"));
});

test("the registered bytes pass; their format is returned", async () => {
  assert.equal((await verifyArtifact(ab(bytes), ref()))?.splatCount, 3701);
  assert.equal((await verifyArtifact(ab(bytes), ref({ format: null })))?.splatCount, 3701, "a pre-V31 artifact has no format: headers still checked");
});

test("one changed byte, a different size, or a wrong registered hash is refused", async () => {
  const flipped = Buffer.from(bytes);
  flipped[6000] ^= 0xff;
  await assert.rejects(verifyArtifact(ab(flipped), ref()), code("CHECKSUM_MISMATCH"));
  await assert.rejects(verifyArtifact(ab(bytes.subarray(0, bytes.length - 44)), ref()), code("SIZE_MISMATCH"));
  await assert.rejects(verifyArtifact(ab(bytes), ref({ sha256: "0".repeat(64) })), code("CHECKSUM_MISMATCH"));
  await assert.rejects(verifyArtifact(ab(bytes), ref({ sha256: "not-a-hash" })), code("CHECKSUM_MISMATCH"));
});

test("bytes that hash as registered but break the contract, or disagree with the published format, are refused", async () => {
  const padded = Buffer.concat([bytes, Buffer.alloc(8)]);
  const paddedRef = ref({ sizeBytes: padded.length, sha256: createHash("sha256").update(padded).digest("hex") });
  await assert.rejects(verifyArtifact(ab(padded), paddedRef), (e: unknown) => e instanceof KsplatInvalidError && e.reason === "TRAILING_BYTES");
  await assert.rejects(verifyArtifact(ab(bytes), ref({ format: { ...ref().format!, splatCount: 3700 } })), code("FORMAT_MISMATCH"));
});

test("an oversized advertised artifact is refused before any download", async () => {
  await assert.rejects(verifyArtifact(ab(bytes), ref({ sizeBytes: 600 * 1024 * 1024 })), KsplatInvalidError);
});

test("readExactly cuts off a body larger than registered and refuses one that ends short", async () => {
  const half = bytes.length / 2;
  const whole = await readExactly(stream([bytes.subarray(0, half), bytes.subarray(half)]), bytes.length);
  assert.deepEqual(Buffer.from(whole), bytes);
  await assert.rejects(readExactly(stream([bytes, Buffer.alloc(1)]), bytes.length), code("SIZE_MISMATCH"));
  await assert.rejects(readExactly(stream([bytes.subarray(0, half)]), bytes.length), code("SIZE_MISMATCH"));
  const progress: number[] = [];
  await readExactly(stream([bytes.subarray(0, half), bytes.subarray(half)]), bytes.length, (n) => progress.push(n));
  assert.deepEqual(progress, [half, bytes.length]);
});

const frame = { floorId: "f-1", canonical: true, scale: 0.5, rotation: { x: 0, y: 0, z: Math.SQRT1_2, w: Math.SQRT1_2 }, translation: { x: 1, y: 2, z: 0 } };
const scene = (over: object = {}) => ({ runId: RUN, floorId: "f-1", scanVersionId: VERSION, artifacts: [ref()], coordinateFrame: frame, ...over });
const expected = { venueId: VENUE, runId: RUN, scanVersionId: VERSION };

test("a scene that is what was requested passes and names its KSPLAT", () => {
  assert.equal(checkSceneScope(scene(), expected).url, ref().url);
  const runUrl = `/api/v1/venues/${VENUE}/reconstructions/${RUN}/artifacts/KSPLAT`;
  assert.equal(checkSceneScope(scene({ scanVersionId: null, coordinateFrame: null, artifacts: [ref({ url: runUrl })] }),
    { ...expected, scanVersionId: null }).url, runUrl, "a run that is not a version may be uncalibrated");
});

test("another run, another version, another version's asset or frame, or a broken frame is refused", () => {
  const out = code("OUT_OF_SCOPE");
  assert.throws(() => checkSceneScope(scene({ runId: "r-2" }), expected), out);
  assert.throws(() => checkSceneScope(scene({ scanVersionId: "sv-2" }), expected), out);
  assert.throws(() => checkSceneScope(scene(), { ...expected, scanVersionId: null }), out);
  assert.throws(() => checkSceneScope(scene({ artifacts: [ref({ url: `/api/v1/venues/${VENUE}/scan-versions/sv-2/artifacts/KSPLAT` })] }), expected), out);
  assert.throws(() => checkSceneScope(scene({ artifacts: [ref({ url: `/api/v1/venues/${VENUE}/reconstructions/${RUN}/artifacts/KSPLAT` })] }), expected), out,
    "a version's asset must be the one it pinned, not whatever the run has");
  assert.throws(() => checkSceneScope(scene({ artifacts: [ref({ url: `/api/v1/venues/v-2/scan-versions/${VERSION}/artifacts/KSPLAT` })] }), expected), out);
  assert.throws(() => checkSceneScope(scene({ artifacts: [] }), expected), out);
  assert.throws(() => checkSceneScope(scene({ artifacts: [ref(), ref()] }), expected), out);
  assert.throws(() => checkSceneScope(scene({ coordinateFrame: null }), expected), out, "a version always has its recorded frame");
  assert.throws(() => checkSceneScope(scene({ coordinateFrame: { ...frame, floorId: "f-2" } }), expected), out);
  assert.throws(() => checkSceneScope(scene({ coordinateFrame: { ...frame, scale: 0 } }), expected), out);
  assert.throws(() => checkSceneScope(scene({ coordinateFrame: { ...frame, scale: Number.NaN } }), expected), out);
  assert.throws(() => checkSceneScope(scene({ coordinateFrame: { ...frame, rotation: { x: 0, y: 0, z: 1, w: 1 } } }), expected), out);
  assert.throws(() => checkSceneScope(scene({ coordinateFrame: { ...frame, translation: null } }), expected), out);
});
