import assert from "node:assert/strict";
import { test } from "node:test";
import { arHref, viewerQuery } from "./viewer-url.ts";

const sel = { venueId: "v1", floorId: "f1", runId: "r1", publicLink: false };

test("writes venue, floor and run", () => {
  assert.equal(viewerQuery("", sel), "?venue=v1&floor=f1&run=r1");
});

test("an emptied selection is removed, not written empty", () => {
  assert.equal(viewerQuery("?venue=v1&floor=f1&run=r1", { ...sel, floorId: "", runId: "" }), "?venue=v1");
  assert.equal(viewerQuery("?venue=v1", { ...sel, venueId: "", floorId: "", runId: "" }), "");
});

test("a public link keeps its secret and never writes a venue", () => {
  assert.equal(viewerQuery("?link=abc&venue=x", { ...sel, publicLink: true }), "?link=abc&floor=f1&run=r1");
});

test("unrelated parameters are kept", () => {
  assert.equal(viewerQuery("?debug=1", sel), "?debug=1&venue=v1&floor=f1&run=r1");
});

test("the AR link carries the venue and, when chosen, the floor", () => {
  assert.equal(arHref("v1", "f1"), "/ar?venue=v1&floor=f1");
  assert.equal(arHref("v1", ""), "/ar?venue=v1");
  assert.equal(arHref("", "f1"), null);
});
