import { test, expect, type Page } from "@playwright/test";
import { FIXTURE, SCENE_TIMEOUT, mockViewerApi, sampleCanvas } from "./viewer-fixture";

// Smoke coverage for the digital twin viewer's app-level states. These mock only the backend HTTP
// contract (lib/reconstruction-api.ts, lib/capture-api.ts, lib/poi-api.ts) -- never the 3D scene itself. Where a scene
// is loaded, it is the production exporter's .ksplat of the synthetic FORMAT VALIDATION fixture (e2e/viewer-fixture.ts),
// delivered byte for byte; e2e/ksplat-viewer.spec.ts checks its rendering, camera and overlay in depth.

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

  const floorsRequest = page.waitForRequest(`**/mock-api/api/v1/venues/${VENUE_ID}/floors`);
  await page.goto(`/viewer?link=good-secret`);
  await expect(page.getByText("Public viewing link")).toBeVisible();
  await expect(page.getByTestId("no-reconstruction")).toContainText("No reconstruction available.");
  // The backend accepts a public-viewer token only in X-Chaya-Viewer-Token (PublicViewerTokenFilter); sent
  // as a bearer token it is parsed as a JWT and rejected with 401.
  const headers = (await floorsRequest).headers();
  expect(headers["x-chaya-viewer-token"]).toBe("cvt_test");
  expect(headers["authorization"]).toBeUndefined();
});


// The two tests below deliver the FORMAT VALIDATION fixture's real exported bytes (e2e/viewer-fixture.ts; not a venue
// reconstruction) as the artifact download, instead of a request that never resolves.

test("an uncalibrated reconstruction downloads and renders, but draws no POIs on it", async ({ page }) => {
  test.setTimeout(2 * SCENE_TIMEOUT);
  const artifactPath = await mockViewerApi(page, RUN_ID, { calibrated: false, runStatus: "FAILED" });
  const artifactRequest = page.waitForRequest((r) => r.url().endsWith(artifactPath));

  await page.goto(`/viewer?link=good-secret`);
  await expect(page.getByText("Points of interest (3)")).toBeVisible();
  const request = await artifactRequest;
  expect(request.headers()["x-chaya-viewer-token"]).toBe("cvt_test");
  expect(request.headers()["authorization"]).toBeUndefined();
  const splats = page.locator("dt", { hasText: /^Splats$/ }).locator("xpath=following-sibling::dd[1]");
  await expect(splats).toHaveText(FIXTURE.splat_count.toLocaleString("en-US"), { timeout: SCENE_TIMEOUT });
  await expect.poll(async () => (await sampleCanvas(page)).covered, { timeout: SCENE_TIMEOUT }).toBeGreaterThan(0.01);
  await expect(page.getByTestId("coordinate-frame-status")).toHaveText(/Not calibrated: POIs and routes are not drawn/);
  await expect(page.locator(".chaya-poi-marker")).toHaveCount(0); // canonical POIs have no place on an uncalibrated splat
});

test("a calibrated reconstruction says so, with its datum and version, and places its POIs", async ({ page }) => {
  test.setTimeout(2 * SCENE_TIMEOUT);
  await mockViewerApi(page, RUN_ID, { calibrated: true });

  await page.goto(`/viewer?link=good-secret`);
  await expect(page.getByTestId("coordinate-frame-status")).toHaveText("Calibrated, metres (venue datum, v1)");
  const splats = page.locator("dt", { hasText: /^Splats$/ }).locator("xpath=following-sibling::dd[1]");
  await expect(splats).toHaveText(FIXTURE.splat_count.toLocaleString("en-US"), { timeout: SCENE_TIMEOUT });
  await expect(page.locator(".chaya-poi-marker")).toHaveCount(FIXTURE.pois_by_construction.length);
});
