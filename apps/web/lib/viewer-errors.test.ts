import assert from "node:assert/strict";
import { test } from "node:test";
import { ArtifactIntegrityError, KsplatInvalidError } from "./artifact-integrity.ts";
import { GraphicsContextError, classifyViewerError } from "./viewer-errors.ts";

// An error shaped like lib/capture-api's ApiError (status + code), which node cannot import (it pulls in Next modules).
const apiError = (status: number, code = `HTTP_${status}`) => Object.assign(new Error(`HTTP ${status}`), { status, code });

test("401/403 and a refused viewing link are unauthorized, and retrying does not help", () => {
  for (const e of [apiError(401), apiError(403), apiError(404, "LINK_INVALID")]) {
    const f = classifyViewerError(e);
    assert.equal(f.kind, "unauthorized");
    assert.equal(f.retryable, false);
  }
});

test("a missing model or asset is unavailable", () => {
  assert.equal(classifyViewerError(apiError(404)).kind, "unavailable");
});

test("bytes that differ from the registration are corrupt and may be downloaded again", () => {
  for (const code of ["SIZE_MISMATCH", "CHECKSUM_MISMATCH"] as const) {
    const f = classifyViewerError(new ArtifactIntegrityError(code, "x"));
    assert.deepEqual([f.kind, f.retryable], ["corrupt", true]);
  }
  assert.equal(classifyViewerError(new KsplatInvalidError("TRUNCATED", "x")).kind, "corrupt");
});

test("an intact file this viewer cannot render is incompatible, not retried", () => {
  for (const e of [new KsplatInvalidError("UNSUPPORTED_VERSION", "x"), new ArtifactIntegrityError("FORMAT_MISMATCH", "x"),
    new ArtifactIntegrityError("OUT_OF_SCOPE", "x")]) {
    const f = classifyViewerError(e);
    assert.deepEqual([f.kind, f.retryable], ["incompatible", false]);
  }
});

test("server, network and graphics failures are retryable; other client errors are not", () => {
  assert.deepEqual([classifyViewerError(apiError(503)).kind, classifyViewerError(apiError(503)).retryable], ["failed", true]);
  assert.equal(classifyViewerError(new TypeError("Failed to fetch")).retryable, true);
  assert.equal(classifyViewerError(apiError(429)).retryable, true);
  assert.equal(classifyViewerError(apiError(400)).retryable, false);
  assert.deepEqual([classifyViewerError(new GraphicsContextError("lost")).kind], ["graphics"]);
});
