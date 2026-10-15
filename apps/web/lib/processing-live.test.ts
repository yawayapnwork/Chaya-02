import assert from "node:assert/strict";
import { test } from "node:test";
import type { ProcessingStatus } from "./capture-api.ts";
import { availableArtifacts, failureKind, pipelineProgress, retryBlockedText, stageAttempt, staleLeaseMessage } from "./pipeline-view.ts";
import { type LiveState, followProcessing, isNewer, parseEvents, reconnectDelayMs } from "./processing-live.ts";

// Synthetic statuses and streams in the API's shapes: they exercise the rules, they are not a run.

const status = (revision: number, observedAt = "2026-10-14T10:00:00Z", runStatus = "RUNNING"): ProcessingStatus => ({
  captureId: "c", captureStatus: "PROCESSING", scanId: "s", jobs: [], revision, observedAt,
  run: { status: runStatus } as ProcessingStatus["run"],
});

test("a status replaces the shown one only if it is newer", () => {
  assert.equal(isNewer(null, status(5)), true);
  assert.equal(isNewer(status(5), status(6)), true);
  assert.equal(isNewer(status(6), status(5)), false, "a late, older answer is dropped");
  assert.equal(isNewer(status(5), status(5)), false, "a duplicate is dropped");
  assert.equal(isNewer(status(5, "2026-10-14T10:00:00Z"), status(5, "2026-10-14T10:00:30Z")), true,
    "same revision read later (a lease expired meanwhile)");
});

test("stream frames are parsed with their event name and id; heartbeats are dropped", () => {
  const { events, rest } = parseEvents("event:status\nid:7\ndata:{\"revision\":7}\n\n:keep-alive\n\nevent:session-expired\ndata:{}\n\nevent:sta");
  assert.deepEqual(events, [
    { event: "status", id: "7", data: "{\"revision\":7}" },
    { event: "session-expired", id: null, data: "{}" },
  ]);
  assert.equal(rest, "event:sta");
  assert.equal(parseEvents("event:status\r\nid:1\r\ndata:{}\r\n\r\n").events.length, 1);
});

test("reconnect backoff is bounded", () => {
  assert.deepEqual([1, 2, 3, 6, 7, 20].map(reconnectDelayMs), [1000, 2000, 4000, 30000, 30000, 30000]);
});

/** A fake server: each open() answers with the next scripted connection. */
function harness(connections: (string[] | number)[], snapshots: ProcessingStatus[] = []) {
  const opened: { token: string; lastEventId: string | null }[] = [];
  let tokenCalls = 0;
  const shown: number[] = [];
  const states: LiveState[] = [];
  /** Every pause the client took, and how many connections it had opened by then. */
  const sleeps: { ms: number; opened: number }[] = [];
  let done!: () => void;
  const finished = new Promise<void>((r) => (done = r));
  const handle = followProcessing({
    token: async () => {
      tokenCalls++;
      if (tokenCalls > 10) throw new Error("not signed in");
      return `t${tokenCalls}`;
    },
    open: async (token, lastEventId) => {
      opened.push({ token, lastEventId });
      const next = connections.shift();
      if (next === undefined) {
        done();
        return new Promise<Response>(() => {}); // hang: the test is over
      }
      if (typeof next === "number") return new Response(null, { status: next });
      const enc = new TextEncoder();
      return new Response(new ReadableStream({ start(c) { next.forEach((chunk) => c.enqueue(enc.encode(chunk))); c.close(); } }),
        { status: 200, headers: { "content-type": "text/event-stream" } });
    },
    snapshot: async () => snapshots.shift() ?? Promise.reject(new Error("down")),
    sleep: async (ms) => {
      sleeps.push({ ms, opened: opened.length });
    },
  }, (s) => shown.push(s.revision!), (s) => {
    states.push(s);
    if (s === "SESSION_EXPIRED" || s === "GONE") done();
  });
  return { opened, shown, states, sleeps, finished, handle, tokens: () => tokenCalls };
}

const ev = (s: ProcessingStatus) => `event:status\nid:${s.revision}\ndata:${JSON.stringify(s)}\n\n`;

test("duplicates and out-of-order events are dropped; a reconnect resumes from the last revision", async () => {
  const h = harness([
    [ev(status(3)), ev(status(3)), ev(status(5)), ev(status(4))], // a duplicate, then an older one after a newer one
    [ev(status(5)), ev(status(8))], // after the reconnect the server sends the current status again (5), then 8
  ]);
  await h.finished;
  h.handle.close();
  assert.deepEqual(h.shown, [3, 5, 8]);
  assert.equal(h.opened[0].lastEventId, null);
  assert.equal(h.opened[1].lastEventId, "4", "the last id received is sent back as Last-Event-ID");
});

test("an event split across network chunks is still one event", async () => {
  const whole = ev(status(9));
  const h = harness([[whole.slice(0, 10), whole.slice(10, 40), whole.slice(40)]]);
  await h.finished;
  h.handle.close();
  assert.deepEqual(h.shown, [9]);
});

test("session-expired reopens the stream with a fresh token; a 401 stops live updates", async () => {
  const h = harness([[ev(status(1)), "event:session-expired\ndata:{}\n\n"], 401]);
  await h.finished;
  h.handle.close();
  assert.equal(h.opened[1].token, "t2", "a new token for the new connection");
  assert.ok(h.states.includes("SESSION_EXPIRED"));
});

test("a stream that cannot connect falls back to polling the snapshot, then recovers", async () => {
  const h = harness([500, 500, [ev(status(12))]], [status(10), status(11), status(10)]);
  await h.finished;
  h.handle.close();
  assert.deepEqual(h.shown, [10, 11, 12], "the stale poll answer (10 after 11) was dropped");
  assert.ok(h.states.includes("POLLING"));
  assert.ok(h.states.includes("LIVE"));
});

test("a capture that is gone, or not visible, stops updates", async () => {
  const gone = harness([[ev(status(1)), "event:gone\ndata:{}\n\n"]]);
  await gone.finished;
  assert.ok(gone.states.includes("GONE"));
  const hidden = harness([404]);
  await hidden.finished;
  assert.ok(hidden.states.includes("GONE"));
});

// ---- progress and failure presentation ----

const stage = (name: string, state: string, artifacts: string[] = []) => ({
  stage: name, state: state as never, attempts: state === "PENDING" ? 0 : 1,
  lastRun: artifacts.length ? { artifacts: artifacts.map((kind) => ({ kind, sizeBytes: 10, partial: false })) } : null,
});

test("progress counts completed stages and never claims a fraction of the running one", () => {
  const p = pipelineProgress({ status: "RUNNING", stages: [stage("INPUT_VALIDATION", "SUCCEEDED", ["INPUT_REPORT"]),
    stage("FFMPEG_PREPROCESS", "RUNNING"), stage("FRAME_QUALITY_FILTER", "PENDING")] });
  assert.deepEqual({ ...p, label: undefined }, { completed: 1, total: 3, activeStage: "FFMPEG_PREPROCESS", activeRunning: true, jobFinished: false, label: undefined });
  assert.equal(p.label, "1 of 3 stages completed · now: Frame extraction (FFmpeg)");
  assert.doesNotMatch(p.label, /%/);
  const waiting = pipelineProgress({ status: "RUNNING", stages: [stage("INPUT_VALIDATION", "QUEUED")] });
  assert.match(waiting.label, /waiting for a worker/);
  assert.equal(pipelineProgress({ status: "FAILED", stages: [stage("INPUT_VALIDATION", "FAILED")] }).jobFinished, true);
});

test("failures are told apart: retryable, terminal, interrupted, cancelled", () => {
  const run = (status: string, failureCode: string | null, retryable: boolean) =>
    ({ status, quality: null, failureStage: null, failureCode, failureMessage: null, retryable }) as never;
  assert.equal(failureKind(run("FAILED", "WORKER_LOST", true)), "INTERRUPTED");
  assert.equal(failureKind(run("FAILED", "TIME_LIMIT_EXCEEDED", false)), "INTERRUPTED");
  assert.equal(failureKind(run("FAILED", "GPU_RUNTIME_ERROR", true)), "RETRYABLE");
  assert.equal(failureKind(run("FAILED", "INPUT_INVALID", false)), "TERMINAL");
  assert.equal(failureKind(run("CANCELLED", null, false)), "CANCELLED");
  assert.equal(failureKind(run("RUNNING", null, false)), null);
});

test("a connection that went live resets the backoff: the next drop reconnects after the base delay", async () => {
  // Two failed attempts (backoff 1 s, then 2 s), a live connection the server ends, then a third failure.
  const h = harness([500, 500, [ev(status(3))], 500]);
  await h.finished;
  h.handle.close();
  assert.deepEqual(h.shown, [3]);
  const afterLive = h.sleeps.filter((s) => s.opened === 4);
  assert.ok(afterLive.length > 0);
  assert.ok(Math.max(...afterLive.map((s) => s.ms)) <= 1_000, `waited ${JSON.stringify(afterLive)} after one failure`);
  assert.ok(h.sleeps.some((s) => s.opened === 2 && s.ms > 1_000), "two failures in a row do back off");
});

test("a failed run that cannot be retried says why, in the server's own terms", () => {
  const run = (retryable: boolean, retryBlockedReason: string | null, status = "FAILED") =>
    ({ status, quality: null, failureStage: "POSE_ESTIMATION", failureCode: "WORKER_LOST", failureMessage: null, retryable,
      retryBlockedReason }) as never;
  assert.equal(retryBlockedText(run(true, null)), null);
  assert.equal(retryBlockedText(run(false, null, "CANCELLED")), null);
  assert.match(retryBlockedText(run(false, "RETRIES_EXHAUSTED"))!, /used all its retries/);
  assert.match(retryBlockedText(run(false, "PII_STAGING_PURGED"))!, /retention policy/);
  assert.match(retryBlockedText(run(false, "VERSION_SUPERSEDED"))!, /newer version/);
  assert.equal(failureKind(run(false, "RETRIES_EXHAUSTED")), "INTERRUPTED", "still interrupted; only the retry is gone");
});

test("a stage's attempt counts its job's retries, which a cancelled attempt has no record of", () => {
  const jobs = [{ stage: "FFMPEG_PREPROCESS", retryCount: 0 }, { stage: "FFMPEG_PREPROCESS", retryCount: 1 }, { stage: "X", retryCount: 2 }];
  assert.equal(stageAttempt({ stage: "FFMPEG_PREPROCESS", attempts: 1 }, jobs), 2);
  assert.equal(stageAttempt({ stage: "INPUT_VALIDATION", attempts: 1 }, jobs), 1);
  assert.equal(stageAttempt({ stage: "INPUT_VALIDATION", attempts: 0 }, []), 1);
});

test("available artifacts are those of succeeded stages; a quiet worker is called out", () => {
  assert.deepEqual(availableArtifacts([stage("INPUT_VALIDATION", "SUCCEEDED", ["INPUT_REPORT"]), stage("FFMPEG_PREPROCESS", "FAILED", ["X"])])
    .map((a) => `${a.stage}:${a.kind}`), ["INPUT_VALIDATION:INPUT_REPORT"]);
  assert.equal(staleLeaseMessage([{ stage: "POSE_ESTIMATION", workerId: "w1", leaseExpiresAt: null, leaseExpired: false }]), null);
  assert.match(staleLeaseMessage([{ stage: "POSE_ESTIMATION", workerId: "w1", leaseExpiresAt: null, leaseExpired: true }])!,
    /w1 running Camera pose estimation has stopped sending heartbeats/);
});
