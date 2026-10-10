import assert from "node:assert/strict";
import { test } from "node:test";
import { checkFileLocally, explainCode, formatBytes, kindForType, matchStoredMedia, planParts } from "./upload-plan.ts";

test("planParts splits into full parts plus a remainder", () => {
  const parts = planParts(12, 5);
  assert.deepEqual(parts, [
    { partNumber: 1, start: 0, end: 5 },
    { partNumber: 2, start: 5, end: 10 },
    { partNumber: 3, start: 10, end: 12 },
  ]);
  assert.equal(planParts(5, 5).length, 1);
  assert.equal(planParts(0, 5).length, 0);
});

test("kindForType classifies allowed types and rejects the rest", () => {
  assert.equal(kindForType("video/mp4"), "VIDEO");
  assert.equal(kindForType("IMAGE/PNG; charset=binary"), "IMAGE");
  assert.equal(kindForType("application/json"), "METADATA");
  assert.equal(kindForType("application/zip"), null);
  assert.equal(kindForType(""), null);
});

test("checkFileLocally reports unsupported, empty and oversized files", () => {
  assert.match(checkFileLocally({ name: "a.exe", type: "application/x-msdownload", size: 10 }) ?? "", /not supported/);
  assert.match(checkFileLocally({ name: "a.png", type: "image/png", size: 0 }) ?? "", /empty/);
  assert.match(checkFileLocally({ name: "a.png", type: "image/png", size: 60 * 1024 ** 2 }) ?? "", /exceeds/);
  assert.equal(checkFileLocally({ name: "a.png", type: "image/png", size: 1000 }), null);
});

test("formatBytes and explainCode", () => {
  assert.equal(formatBytes(512), "512 B");
  assert.equal(formatBytes(5 * 1024 ** 2), "5.0 MiB");
  assert.match(explainCode("MALWARE_DETECTED", "x"), /malware/i);
  assert.equal(explainCode("SOMETHING_NEW", "server said this"), "server said this");
});

test("matchStoredMedia resumes a partial upload of the same bytes after a reload", () => {
  const sha = "a".repeat(64);
  const stored = [
    { id: "rejected", status: "REJECTED" as const, sizeBytes: 10, sha256: sha },
    { id: "partial", status: "PENDING" as const, sizeBytes: 10, sha256: sha },
    { id: "other", status: "ACCEPTED" as const, sizeBytes: 10, sha256: "b".repeat(64) },
  ];
  assert.deepEqual(matchStoredMedia(stored, { size: 10, sha256: sha }), { action: "resume", mediaId: "partial" });
  // Same checksum but a different size is a different file.
  assert.equal(matchStoredMedia(stored, { size: 11, sha256: sha }), null);
  // A file the server already holds is not sent again; a rejected copy is never reused.
  assert.deepEqual(matchStoredMedia(stored, { size: 10, sha256: "b".repeat(64) }), { action: "existing", mediaId: "other" });
  assert.equal(matchStoredMedia([stored[0]], { size: 10, sha256: sha }), null);
});
