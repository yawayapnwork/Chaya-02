import assert from "node:assert/strict";
import { test } from "node:test";
import { EvidenceQueue } from "./evidence-queue.ts";

test("batches in order, keeps a failed batch, and resends it first", async () => {
  const sent: number[][] = [];
  let fail = true;
  const q = new EvidenceQueue<number>(async (b) => {
    if (fail) throw new Error("offline");
    sent.push(b);
  }, 2, 100);
  q.push(1, 2, 3);
  await q.flush();
  assert.equal(q.pending, 3);
  assert.equal(q.lastError, "offline");
  fail = false;
  q.push(4);
  await q.flush();
  assert.deepEqual(sent, [[1, 2], [3, 4]]);
  assert.equal(q.sent, 4);
  assert.equal(q.lastError, null);
});

test("over the cap the oldest evidence is dropped and counted, never silently", async () => {
  const q = new EvidenceQueue<number>(async () => { throw new Error("offline"); }, 10, 3);
  q.push(1, 2, 3, 4, 5);
  assert.equal(q.pending, 3);
  assert.equal(q.dropped, 2);
  await q.flush();
  assert.equal(q.pending, 3);
  assert.equal(q.dropped, 2);
});

test("a flush while one is running does not send twice", async () => {
  let calls = 0;
  let release: () => void = () => undefined;
  const q = new EvidenceQueue<number>(() => { calls++; return new Promise<void>((r) => { release = r; }); }, 10, 10);
  q.push(1);
  const first = q.flush();
  await q.flush();
  release();
  await first;
  assert.equal(calls, 1);
});
