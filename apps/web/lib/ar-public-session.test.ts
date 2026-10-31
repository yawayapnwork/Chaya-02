import assert from "node:assert/strict";
import { test } from "node:test";
import {
  clearPublicViewerSession,
  currentAuthHeaders,
  publicViewerVenueId,
  setPublicViewerSession,
} from "./session.ts";
import { arHref } from "./viewer-url.ts";
import { classifyArFailure } from "./ar-errors.ts";

test("public viewer session injects X-Chaya-Viewer-Token header without requiring operator login", async () => {
  clearPublicViewerSession();
  const future = new Date(Date.now() + 60_000).toISOString();
  setPublicViewerSession("tok-abc-123", future, "venue-xyz");

  assert.equal(publicViewerVenueId(), "venue-xyz");
  const headers = await currentAuthHeaders();
  assert.deepEqual(headers, { "X-Chaya-Viewer-Token": "tok-abc-123" });
  clearPublicViewerSession();
});

test("expired public viewer session is cleared and returns null venue", () => {
  clearPublicViewerSession();
  const past = new Date(Date.now() - 10_000).toISOString();
  setPublicViewerSession("tok-expired", past, "venue-xyz");

  assert.equal(publicViewerVenueId(), null);
  clearPublicViewerSession();
});

test("arHref constructs secure public viewer deep link carrying secret, omitting venue id", () => {
  const publicHref = arHref("venue-123", "floor-456", "secret-link-token");
  assert.equal(publicHref, "/ar?link=secret-link-token&floor=floor-456");

  const operatorHref = arHref("venue-123", "floor-456");
  assert.equal(operatorHref, "/ar?venue=venue-123&floor=floor-456");
});

test("expired or revoked public viewer token maps to SESSION_EXPIRED with prompt to reopen link", () => {
  const failure = classifyArFailure({ status: 401, code: "UNAUTHORIZED", message: "token expired" }, true);
  assert.equal(failure.failure, "SESSION_EXPIRED");
  assert.match(failure.action, /reopen the viewer link/i);
});
