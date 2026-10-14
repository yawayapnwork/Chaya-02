import { test, expect, type Page } from "@playwright/test";
import { FIXTURE, FLOOR_ID, FRAME_ID, SCENE_TIMEOUT, VENUE_ID, mockViewerApi } from "./viewer-fixture";

// Search to navigation on the FORMAT VALIDATION fixture scene (e2e/viewer-fixture.ts: a synthetic scene, not a venue).
// The search and routing APIs are mocked with the response shapes the API returns (dev.chaya.api.search.SearchDtos,
// NavigationDtos); what is tested is the viewer: that it sends the searcher's position, shows the server's reading of the
// query and its distances as given, focuses and highlights the chosen result, and plans the route to it.

const RUN_ID = "77777777-7777-7777-7777-777777777777";
const [red, green, blue] = FIXTURE.pois_by_construction;

const interpretation = {
  normalized: "find the nearest pillar", subject: "pillar", lexicalTerms: ["pillar"], synonymGroups: [], corrections: [],
  targetKind: "CATEGORY", spatialModifier: "NEAREST", spatialModifierApplied: true, spatialNote: null, accessibilityRequired: false,
};

function result(i: number, label: string, xyz: number[], distance: number) {
  return {
    poiId: `poi-${i}`, floorId: FLOOR_ID, label, category: "fixture", tags: [], x: xyz[0], y: xyz[1], z: xyz[2], similarity: 0.93,
    detectionConfidence: null, source: "MANUAL", boundingBox: null, relevanceMargin: 0.12, imageSimilarity: null, matchedBy: "TEXT",
    sourceFrame: null, spatialStatus: "VALID", localizationStatus: null, localizationUncertaintyM: null, lexicalSimilarity: 0.4,
    rankScore: 0.95, scanVersionId: null, pipelineRunId: null, detectionArtifactId: null, provenance: null, exactMatch: false,
    matchedTerm: "pillar", distanceMeters: distance, distanceKind: "ROUTE", routeStatus: null, lowConfidence: null, duplicateIds: [],
    accessible: null, indexStatus: "INDEXED",
  };
}

async function mockSearch(page: Page) {
  const urls: string[] = [];
  await page.route(`**/mock-api/api/v1/venues/${VENUE_ID}/search**`, async (route) => {
    urls.push(route.request().url());
    await route.fulfill({
      status: 200, contentType: "application/json",
      body: JSON.stringify({
        query: "find the nearest pillar", matchType: "embedding", relevance: "FILTERED", interpretation, lowConfidence: false,
        accessibilityUnconfirmed: [], suggestions: [], closestMatches: [],
        results: [result(3, blue.label, blue.canonical, 4.1), result(2, green.label, green.canonical, 5.4)],
      }),
    });
  });
  return urls;
}

async function mockRoutes(page: Page) {
  const requests: Record<string, unknown>[] = [];
  await page.route("**/mock-api/api/v1/navigation/routes", async (route) => {
    const req = route.request().postDataJSON() as Record<string, unknown>;
    requests.push(req);
    await route.fulfill({
      status: 200, contentType: "application/json",
      body: JSON.stringify({
        waypoints: [
          { x: red.canonical[0], y: red.canonical[1], z: red.canonical[2], floorId: FLOOR_ID, kind: "START" },
          { x: blue.canonical[0], y: blue.canonical[1], z: blue.canonical[2], floorId: FLOOR_ID, kind: "DESTINATION" },
        ],
        distanceMeters: 4.1, estimatedDurationSeconds: 3.2, floorTransitions: [], accessibilityProfile: req.accessibility,
        accessibilityConstraintsApplied: [],
        routingSources: [{ floorId: FLOOR_ID, graphId: "g", source: "RECAST_NAVMESH", navmeshSha256: null, recastnavigationVersion: null,
          pathMethod: null, scanVersionId: null, coordinateFrameId: FRAME_ID }],
      }),
    });
  });
  return requests;
}

test("nearest search from the From point shows walking distances, focuses the result and navigates to it", async ({ page }) => {
  test.setTimeout(3 * SCENE_TIMEOUT);
  await mockViewerApi(page, RUN_ID, { calibrated: true });
  const searches = await mockSearch(page);
  const routes = await mockRoutes(page);
  await page.goto("/viewer?link=good-secret");
  await expect(page.locator("dt", { hasText: /^Splats$/ })).toBeVisible({ timeout: SCENE_TIMEOUT });

  // Without a position the panel says so; with the From point chosen it is sent with the query.
  await expect(page.getByTestId("search-position")).toHaveText("Position: not set");
  await page.getByRole("combobox", { name: "From" }).selectOption({ label: red.label });
  await expect(page.getByTestId("search-position")).toHaveText(`From: ${red.label}`);

  await page.getByLabel("Search query").fill("Find the nearest pillar");
  await page.getByRole("button", { name: "Go" }).click();
  await expect(page.getByTestId("search-results")).toContainText(blue.label);
  const url = new URL(searches[searches.length - 1]);
  expect(url.searchParams.getAll("position").map(Number)).toEqual(red.canonical);
  expect(url.searchParams.get("positionFloorId")).toBe(FLOOR_ID);

  await expect(page.getByTestId("search-interpretation")).toContainText(`Nearest first, by walking distance from ${red.label}.`);
  await expect(page.getByTestId("search-distance").first()).toHaveText("4.1 m walk");

  // Selecting a result selects (and so highlights and focuses) its POI.
  const first = page.getByTestId("search-result").first();
  await first.getByRole("button", { name: new RegExp(`^${blue.label}`) }).click();
  await expect(page.getByTestId("poi-details")).toContainText(blue.label);
  await expect(first).toHaveAttribute("aria-current", "true");

  // Navigate plans the route to it from the From point, both accessibility profiles.
  await first.getByRole("button", { name: `Navigate to ${blue.label}` }).click();
  await expect(page.getByTestId("route-stop")).toHaveCount(1);
  await expect(page.getByTestId("route-stops")).toContainText(blue.label);
  await expect.poll(() => routes.filter((r) => r.destinationPoiId === "poi-3").map((r) => r.accessibility).sort())
    .toEqual(["STANDARD", "STEP_FREE"]);
  expect(routes.find((r) => r.destinationPoiId === "poi-3")?.start).toEqual(red.canonical);
});

test("a low-confidence answer and a misspelling correction are stated, and suggestions are real searches", async ({ page }) => {
  test.setTimeout(3 * SCENE_TIMEOUT);
  await mockViewerApi(page, RUN_ID, { calibrated: true });
  const urls: string[] = [];
  await page.route(`**/mock-api/api/v1/venues/${VENUE_ID}/search**`, async (route) => {
    urls.push(route.request().url());
    const q = new URL(route.request().url()).searchParams.get("q");
    const detected = { ...result(2, "chair", green.canonical, 0), source: "AUTO_DETECTED", detectionConfidence: 0.38,
      lowConfidence: true, distanceMeters: null, distanceKind: null, matchedBy: "TEXT" };
    const body = q === "chiar"
      ? { query: "chiar", matchType: "embedding", relevance: "FILTERED", lowConfidence: true, results: [detected],
          interpretation: { ...interpretation, subject: "chair", spatialModifier: "NONE", spatialModifierApplied: false,
            corrections: [{ from: "chiar", to: "chair" }] }, closestMatches: [], accessibilityUnconfirmed: [], suggestions: [] }
      : { query: q, matchType: "embedding", relevance: "FILTERED", lowConfidence: false, results: [],
          interpretation: { ...interpretation, subject: q, spatialModifier: "NONE", spatialModifierApplied: false },
          closestMatches: [result(1, red.label, red.canonical, 0)], accessibilityUnconfirmed: [], suggestions: [red.label] };
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(body) });
  });
  await page.goto("/viewer?link=good-secret");

  await page.getByLabel("Search query").fill("chiar");
  await page.getByRole("button", { name: "Go" }).click();
  await expect(page.getByTestId("search-interpretation")).toContainText("Showing results for “chair” (you typed “chiar”).");
  await expect(page.getByTestId("search-low-confidence")).toBeVisible();
  await expect(page.getByTestId("search-results")).toContainText("low confidence");

  await page.getByLabel("Search query").fill("swimming pool");
  await page.getByRole("button", { name: "Go" }).click();
  await expect(page.getByTestId("search-closest")).toContainText("Nothing matching “swimming pool” here. Closest:");
  await page.getByTestId("search-suggestions").getByRole("button", { name: red.label }).click();
  await expect.poll(() => urls.map((u) => new URL(u).searchParams.get("q"))).toContain(red.label);
  await expect(page.getByLabel("Search query")).toHaveValue(red.label);
});
