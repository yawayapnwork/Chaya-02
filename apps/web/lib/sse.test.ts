import assert from "node:assert/strict";
import { test } from "node:test";
import { sseFrameData, splitSseFrames } from "./sse.ts";

test("splitSseFrames: complete frames are extracted, a partial one is kept as the remainder", () => {
  const { frames, rest } = splitSseFrames("event: status\ndata: {\"a\":1}\n\nevent: status\ndata: {\"a\":2}\n\ndata: parti");
  assert.deepEqual(frames, ["event: status\ndata: {\"a\":1}", "event: status\ndata: {\"a\":2}"]);
  assert.equal(rest, "data: parti");
});

test("splitSseFrames: no complete frame yet returns everything as the remainder", () => {
  const { frames, rest } = splitSseFrames("data: still-buffering");
  assert.deepEqual(frames, []);
  assert.equal(rest, "data: still-buffering");
});

test("sseFrameData: joins multi-line data, and comments/heartbeats carry no data", () => {
  assert.equal(sseFrameData("event: status\ndata: {\"a\":1}"), '{"a":1}');
  assert.equal(sseFrameData(":keep-alive"), null);
  assert.equal(sseFrameData("data: line1\ndata: line2"), "line1\nline2");
});
