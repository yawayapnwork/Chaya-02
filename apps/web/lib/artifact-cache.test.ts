import assert from "node:assert/strict";
import { test } from "node:test";
import { ArtifactCache } from "./artifact-cache.ts";

const ref = (n: number, sizeBytes = 10) => ({ sha256: String(n).padStart(64, "0"), sizeBytes });
const bytes = (size: number) => ({ size });

test("a verified artifact is downloaded once and served from memory afterwards", async () => {
  const cache = new ArtifactCache<{ size: number }>();
  let loads = 0;
  const load = async () => (loads++, bytes(10));
  const a = await cache.get(ref(1), load);
  const progress: number[] = [];
  const b = await cache.get(ref(1), load, (l) => progress.push(l));
  assert.equal(loads, 1);
  assert.equal(a, b);
  assert.deepEqual(progress, [10]); // a cache hit reports complete
});

test("concurrent requests share one download, and each requester sees its progress", async () => {
  const cache = new ArtifactCache<{ size: number }>();
  let loads = 0;
  let finish!: (v: { size: number }) => void;
  let report!: (l: number, t: number) => void;
  const load = (onProgress: (l: number, t: number) => void) => {
    loads++;
    report = onProgress;
    return new Promise<{ size: number }>((r) => (finish = r));
  };
  const seenA: number[] = [];
  const seenB: number[] = [];
  const pa = cache.get(ref(1), load, (l) => seenA.push(l));
  const pb = cache.get(ref(1), load, (l) => seenB.push(l));
  report(5, 10);
  finish(bytes(10));
  assert.equal(await pa, await pb);
  assert.equal(loads, 1);
  assert.deepEqual([seenA, seenB], [[5], [5]]);
});

test("a failed download is not cached; the next request downloads again", async () => {
  const cache = new ArtifactCache<{ size: number }>();
  await assert.rejects(cache.get(ref(1), async () => { throw new Error("checksum"); }));
  assert.equal(cache.has(ref(1)), false);
  assert.equal((await cache.get(ref(1), async () => bytes(10))).size, 10);
});

test("bounded by entry count and bytes, least recently used out first", async () => {
  const cache = new ArtifactCache<{ size: number }>(2, 100);
  await cache.get(ref(1), async () => bytes(10));
  await cache.get(ref(2), async () => bytes(10));
  await cache.get(ref(1), async () => bytes(10)); // 1 is now the most recent
  await cache.get(ref(3), async () => bytes(10));
  assert.deepEqual([cache.has(ref(1)), cache.has(ref(2)), cache.has(ref(3))], [true, false, true]);
  await cache.get(ref(4, 95), async () => bytes(95));
  assert.deepEqual([cache.has(ref(1)), cache.has(ref(3)), cache.has(ref(4, 95))], [false, false, true]);
  await cache.get(ref(5, 500), async () => bytes(500)); // over the whole budget: served, not kept
  assert.equal(cache.has(ref(5, 500)), false);
  assert.equal(cache.has(ref(4, 95)), true);
});

test("the key is digest and size: a different registration is a different artifact", async () => {
  const cache = new ArtifactCache<{ size: number }>();
  await cache.get(ref(1, 10), async () => bytes(10));
  assert.equal(cache.has(ref(1, 11)), false);
});
