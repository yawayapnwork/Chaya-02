import assert from "node:assert/strict";
import { test } from "node:test";
import { apiBaseUrl } from "./config.ts";

test("apiBaseUrl throws an actionable error when unset", () => {
  assert.throws(() => apiBaseUrl({}), /CHAYA_API_BASE_URL is not set/);
});

test("apiBaseUrl strips trailing slashes", () => {
  assert.equal(apiBaseUrl({ CHAYA_API_BASE_URL: "http://x:8080//" }), "http://x:8080");
});
