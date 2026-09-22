import { test, expect, type Page } from "@playwright/test";

// Smoke coverage for the semantic search panel: a typed query reaches the real search endpoint (never a
// client-side keyword match) and results render with their reported similarity. Voice input is not
// exercised here (no real microphone in a headless browser); lib/voice-input.ts only ever fills the text
// box, so this test's typed-query path already covers what voice input feeds into.

const VENUE_ID = "11111111-1111-1111-1111-111111111111";
const FLOOR_ID = "22222222-2222-2222-2222-222222222222";

async function mockJson(page: Page, urlPattern: string, body: unknown, status = 200) {
  await page.route(urlPattern, (route) => route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) }));
}

test("a typed query calls the real search endpoint and renders ranked results", async ({ page }) => {
  const expiresAt = new Date(Date.now() + 60_000).toISOString();
  await mockJson(page, "**/mock-api/api/v1/public/viewer-token", { token: "cvt_test", expiresAt, venueId: VENUE_ID });
  await mockJson(page, `**/mock-api/api/v1/venues/${VENUE_ID}/floors`, [{ id: FLOOR_ID, level: 0, name: "Ground Floor" }]);
  await mockJson(page, `**/mock-api/api/v1/venues/${VENUE_ID}/floors/${FLOOR_ID}/reconstructions`, []);
  await mockJson(page, `**/mock-api/api/v1/venues/${VENUE_ID}/pois`, []);

  const searchRequest = page.waitForRequest((req) => req.url().includes(`/venues/${VENUE_ID}/search`) && req.url().includes("q=couch"));
  await page.route(`**/mock-api/api/v1/venues/${VENUE_ID}/search**`, (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        query: "couch",
        matchType: "embedding",
        results: [
          { poiId: "poi-1", floorId: FLOOR_ID, label: "Lobby sofa", category: "furniture", tags: [], x: 1, y: 2, z: 0,
            similarity: 0.91, detectionConfidence: 0.8, source: "AUTO_DETECTED", boundingBox: null },
        ],
      }),
    }),
  );

  await page.goto("/viewer?link=good-secret");
  await page.getByLabel("Search query").fill("couch");
  await page.getByRole("button", { name: "Go" }).click();

  const request = await searchRequest;
  expect(request.url()).toContain("q=couch");
  await expect(page.getByTestId("search-results")).toContainText("Lobby sofa");
  await expect(page.getByTestId("search-results")).toContainText("91% match");
  await expect(page.getByTestId("search-results")).toContainText("detected");
});

test("an empty search result set is shown honestly, not as an error", async ({ page }) => {
  const expiresAt = new Date(Date.now() + 60_000).toISOString();
  await mockJson(page, "**/mock-api/api/v1/public/viewer-token", { token: "cvt_test", expiresAt, venueId: VENUE_ID });
  await mockJson(page, `**/mock-api/api/v1/venues/${VENUE_ID}/floors`, [{ id: FLOOR_ID, level: 0, name: "Ground Floor" }]);
  await mockJson(page, `**/mock-api/api/v1/venues/${VENUE_ID}/floors/${FLOOR_ID}/reconstructions`, []);
  await mockJson(page, `**/mock-api/api/v1/venues/${VENUE_ID}/pois`, []);
  await mockJson(page, `**/mock-api/api/v1/venues/${VENUE_ID}/search**`, { query: "zzz", matchType: "embedding", results: [] });

  await page.goto("/viewer?link=good-secret");
  await page.getByLabel("Search query").fill("zzz");
  await page.getByRole("button", { name: "Go" }).click();

  await expect(page.getByTestId("search-results")).toContainText("No matches.");
});
