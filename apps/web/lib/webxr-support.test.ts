import assert from "node:assert/strict";
import { test } from "node:test";
import { detectWebXrSupport } from "./webxr-support.ts";

test("no navigator.xr at all reports an explicit unsupported-device state (current dev machines/browsers)", async () => {
  const result = await detectWebXrSupport({});
  assert.equal(result.supported, false);
  if (!result.supported) assert.equal(result.reason, "NO_NAVIGATOR_XR");
});

test("navigator.xr present but immersive-ar unsupported is reported explicitly, not silently upgraded", async () => {
  const result = await detectWebXrSupport({ xr: { isSessionSupported: async () => false } });
  assert.equal(result.supported, false);
  if (!result.supported) assert.equal(result.reason, "SESSION_NOT_SUPPORTED");
});

test("navigator.xr reporting immersive-ar supported is passed through as supported", async () => {
  const result = await detectWebXrSupport({ xr: { isSessionSupported: async () => true } });
  assert.equal(result.supported, true);
});

test("a throwing isSessionSupported is reported as CHECK_FAILED, not thrown to the caller", async () => {
  const result = await detectWebXrSupport({ xr: { isSessionSupported: async () => { throw new Error("boom"); } } });
  assert.equal(result.supported, false);
  if (!result.supported) {
    assert.equal(result.reason, "CHECK_FAILED");
    assert.equal(result.detail, "boom");
  }
});
