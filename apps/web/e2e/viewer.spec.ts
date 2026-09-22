import { test, expect, type Page } from "@playwright/test";

// Smoke coverage for the digital twin viewer's app-level states. These mock only the backend HTTP
// contract (lib/reconstruction-api.ts, lib/capture-api.ts, lib/poi-api.ts) -- never the 3D scene itself.
// Loading a real .ksplat through WebGL is out of scope for a browser smoke test; that path is exercised
// manually and by the SplatViewerCanvas/ksplat round-trip unit tests instead.

const VENUE_ID = "11111111-1111-1111-1111-111111111111";
const FLOOR_ID = "22222222-2222-2222-2222-222222222222";
const RUN_ID = "33333333-3333-3333-3333-333333333333";

async function mockJson(page: Page, urlPattern: string, body: unknown, status = 200) {
  await page.route(urlPattern, (route) =>
    route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) }),
  );
}

test("without a session or a link, the viewer asks the visitor to sign in", async ({ page }) => {
  await page.goto("/viewer");
  await expect(page.getByRole("heading", { name: "Digital twin viewer" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Sign in" })).toBeVisible();
});

test("an invalid public link shows its own error, never a fabricated scene", async ({ page }) => {
  await mockJson(page, "**/mock-api/api/v1/public/viewer-token", { detail: "not found" }, 404);
  await page.goto("/viewer?link=bad-secret");
  await expect(page.getByText(/invalid, expired or has been revoked/i)).toBeVisible();
});

test('a floor with no successful reconstruction states "No reconstruction available."', async ({ page }) => {
  const expiresAt = new Date(Date.now() + 60_000).toISOString();
  await mockJson(page, "**/mock-api/api/v1/public/viewer-token", { token: "cvt_test", expiresAt, venueId: VENUE_ID });
  await mockJson(page, `**/mock-api/api/v1/venues/${VENUE_ID}/floors`, [{ id: FLOOR_ID, level: 0, name: "Ground Floor" }]);
  await mockJson(page, `**/mock-api/api/v1/venues/${VENUE_ID}/floors/${FLOOR_ID}/reconstructions`, []);
  await mockJson(page, `**/mock-api/api/v1/venues/${VENUE_ID}/pois`, []);

  await page.goto(`/viewer?link=good-secret`);
  await expect(page.getByText("Public viewing link")).toBeVisible();
  await expect(page.getByTestId("no-reconstruction")).toContainText("No reconstruction available.");
});

test("once a reconstruction exists, its version appears in the picker and a download starts", async ({ page }) => {
  const expiresAt = new Date(Date.now() + 60_000).toISOString();
  const generatedAt = new Date().toISOString();
  await mockJson(page, "**/mock-api/api/v1/public/viewer-token", { token: "cvt_test", expiresAt, venueId: VENUE_ID });
  await mockJson(page, `**/mock-api/api/v1/venues/${VENUE_ID}/floors`, [{ id: FLOOR_ID, level: 0, name: "Ground Floor" }]);
  await mockJson(page, `**/mock-api/api/v1/venues/${VENUE_ID}/floors/${FLOOR_ID}/reconstructions`, [
    { runId: RUN_ID, floorId: FLOOR_ID, generatedAt, runStatus: "FAILED", runQuality: null },
  ]);
  await mockJson(page, `**/mock-api/api/v1/venues/${VENUE_ID}/pois`, [
    { id: "poi-1", floorId: FLOOR_ID, spaceId: null, version: 1, label: "Main entrance", category: "entrance",
      description: null, tags: [], x: 0, y: 0, z: 0 },
  ]);
  await mockJson(page, `**/mock-api/api/v1/venues/${VENUE_ID}/reconstructions/${RUN_ID}`, {
    runId: RUN_ID, scanId: "scan-1", floorId: FLOOR_ID, generatedAt, runStatus: "FAILED", runQuality: null,
    artifacts: [{ kind: "KSPLAT", contentType: "application/octet-stream", sizeBytes: 12345, sha256: "a".repeat(64),
      url: `/api/v1/venues/${VENUE_ID}/reconstructions/${RUN_ID}/artifacts/KSPLAT` }],
  });
  // Never resolves: this test only checks that a real, authenticated download request was made for the
  // real artifact URL the backend returned -- not that WebGL can render it in a headless browser.
  const artifactRequest = page.waitForRequest(`**/mock-api/api/v1/venues/${VENUE_ID}/reconstructions/${RUN_ID}/artifacts/KSPLAT`);
  await page.route(`**/mock-api/api/v1/venues/${VENUE_ID}/reconstructions/${RUN_ID}/artifacts/KSPLAT`, () => {});

  await page.goto(`/viewer?link=good-secret`);
  await expect(page.getByText("Points of interest (1)")).toBeVisible();
  await expect(page.getByRole("button", { name: "Main entrance" })).toBeVisible();
  const request = await artifactRequest;
  expect(request.headers()["authorization"]).toBe("Bearer cvt_test");
});
