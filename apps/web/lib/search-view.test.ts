import { strict as assert } from "node:assert";
import { test } from "node:test";
import {
  type SearchResponse,
  type SearchResult,
  canNavigate,
  distanceNote,
  emptyMessage,
  interpretationNotes,
  matchNote,
  resultBadges,
} from "./search-view.ts";

const base: SearchResult = {
  poiId: "p1", floorId: "f1", label: "Staircase B", category: "stairs", tags: [], x: 7, y: 0, z: 0, similarity: 0.91,
  detectionConfidence: null, source: "MANUAL", boundingBox: null, spatialStatus: "VALID",
};

function response(over: Partial<SearchResponse>): SearchResponse {
  return {
    query: "q", matchType: "embedding", results: [], relevance: "FILTERED",
    interpretation: {
      normalized: "q", subject: "q", lexicalTerms: ["q"], synonymGroups: [], corrections: [], targetKind: "DESCRIPTION",
      spatialModifier: "NONE", spatialModifierApplied: false, spatialNote: null, accessibilityRequired: false,
    },
    ...over,
  };
}

test("a walking distance is shown as such, a straight-line one is never passed off as a walk", () => {
  assert.equal(distanceNote({ ...base, distanceMeters: 7, distanceKind: "ROUTE" }), "7.0 m walk");
  assert.equal(distanceNote({ ...base, distanceMeters: 13.4, distanceKind: "ROUTE" }), "13 m walk");
  assert.match(distanceNote({ ...base, distanceMeters: 3, distanceKind: "STRAIGHT_LINE" }) ?? "", /straight line \(no walking route/);
  assert.equal(distanceNote({ ...base, routeStatus: "NO_ACCESSIBLE_ROUTE" }), "no step-free route from your position");
  assert.equal(distanceNote({ ...base, routeStatus: "NOT_EVALUATED" }), null);
  assert.equal(distanceNote(base), null);
});

test("badges say what kind of evidence a result is", () => {
  assert.deepEqual(resultBadges(base), []);
  assert.deepEqual(resultBadges({ ...base, source: "AUTO_DETECTED", lowConfidence: true, duplicateIds: ["p2"] }),
    ["detected", "low confidence", "2 detections merged"]);
  assert.deepEqual(resultBadges({ ...base, matchedBy: "IMAGE", source: "AUTO_DETECTED" }), ["detected", "matched by appearance"]);
  assert.deepEqual(resultBadges({ ...base, indexStatus: "PENDING_EMBEDDING", accessible: true }), ["name match only", "accessible"]);
  assert.deepEqual(resultBadges({ ...base, accessible: false }), [], "not accessible is not advertised as a badge");
});

test("a percentage is only shown for a similarity, never for a name match", () => {
  assert.equal(matchNote(base, "embedding"), "91% match");
  assert.equal(matchNote({ ...base, matchedBy: "SYNONYM", matchedTerm: "elevator" }, "embedding"), "name match (“elevator”)");
  assert.equal(matchNote({ ...base, matchedBy: "LEXICAL" }, "embedding"), "name match");
  assert.equal(matchNote({ ...base, indexStatus: "PENDING_EMBEDDING", similarity: 0 }, "embedding"), "name match");
  assert.equal(matchNote(base, "lexical_fallback"), "text match");
});

test("navigation is offered only to a position in the frame being viewed", () => {
  assert.equal(canNavigate(base), true);
  assert.equal(canNavigate({ ...base, spatialStatus: "UNBOUND" }), false);
  assert.equal(canNavigate({ ...base, spatialStatus: "STALE_FRAME" }), false);
  assert.equal(canNavigate({ ...base, floorId: null }), false);
});

test("the reading of the query is explained: corrections, accessibility and whether nearest was applied", () => {
  const i = response({}).interpretation!;
  const corrected = response({ interpretation: { ...i, subject: "staircase", corrections: [{ from: "staircse", to: "staircase" }] } });
  assert.deepEqual(interpretationNotes(corrected, null), ["Showing results for “staircase” (you typed “staircse”)."]);

  const nearestNoPosition = response({ interpretation: { ...i, spatialModifier: "NEAREST", spatialNote: "NO_POSITION" } });
  assert.match(interpretationNotes(nearestNoPosition, null)[0], /Choose where you are/);

  const position = { label: "Reception desk", floorId: "f1", x: 0, y: 0, z: 0 };
  const routed = response({
    interpretation: { ...i, spatialModifier: "NEAREST", spatialModifierApplied: true, accessibilityRequired: true },
    results: [{ ...base, distanceMeters: 7, distanceKind: "ROUTE" }],
  });
  assert.deepEqual(interpretationNotes(routed, position), [
    "Only places staff recorded as wheelchair accessible.",
    "Nearest first, by walking distance from Reception desk.",
  ]);
  const straight = response({
    interpretation: { ...i, spatialModifier: "NEAREST", spatialModifierApplied: true },
    results: [{ ...base, distanceMeters: 3, distanceKind: "STRAIGHT_LINE" }],
  });
  assert.match(interpretationNotes(straight, position)[0], /straight-line distance from Reception desk/);
  assert.deepEqual(interpretationNotes({ ...routed, interpretation: undefined }, position), [], "an older API without it");
});

test("an empty result says why, and never presents candidates as matches", () => {
  assert.equal(emptyMessage(response({ results: [base] })), null);
  assert.equal(emptyMessage(response({ query: "zzz" })), "No matches.");
  assert.equal(emptyMessage(response({ query: "swimming pool", closestMatches: [base] })), "Nothing matching “swimming pool” here. Closest:");
  assert.match(emptyMessage(response({ query: "accessible cafe", accessibilityUnconfirmed: [base], closestMatches: [base] })) ?? "",
    /recorded as wheelchair accessible/);
});
