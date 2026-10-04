import { strict as assert } from "node:assert";
import { test } from "node:test";
import { locationNote } from "./search-location.ts";

test("a position that cannot be trusted says so", () => {
  assert.match(locationNote({ spatialStatus: "UNVERIFIED" }) ?? "", /unverified/);
  assert.match(locationNote({ spatialStatus: "STALE_FRAME" }) ?? "", /older reconstruction/);
  assert.match(locationNote({ spatialStatus: "UNBOUND" }) ?? "", /not calibrated/);
});

test("a depth-verified detection reports its views and its spread, rounded up, never as accuracy", () => {
  assert.equal(locationNote({ spatialStatus: "VALID", localizationStatus: "MULTI_VIEW", localizationUncertaintyM: 0.12 }),
    "seen from several views, placement spread ~0.2 m");
  assert.equal(locationNote({ spatialStatus: "VALID", localizationStatus: "SINGLE_VIEW", localizationUncertaintyM: null }),
    "seen from one view");
});

test("a valid manual POI needs no note", () => {
  assert.equal(locationNote({ spatialStatus: "VALID", localizationStatus: null }), null);
});
