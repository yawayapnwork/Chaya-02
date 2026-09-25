import { readFileSync } from "node:fs";
import path from "node:path";
import { test, expect, type Page } from "@playwright/test";

// Pipeline compatibility: the production encoder's .ksplat (packages/contracts/fixtures/ksplat/scene.ksplat -- the
// worker test tests/unit/test_ksplat_fixture.py fails unless it is byte-identical to what chaya_worker.ksplat writes)
// served as the artifact download, fetched by the viewer's real download path (lib/reconstruction-api.fetchArtifact,
// progress and Blob URL), and loaded by the real GaussianSplats3D Viewer in Chromium. The JSON API around it is mocked;
// the artifact download is NOT stubbed -- the browser receives and parses the actual bytes.

const VENUE_ID = "11111111-1111-1111-1111-111111111111";
const FLOOR_ID = "22222222-2222-2222-2222-222222222222";
const RUN_ID = "44444444-4444-4444-4444-444444444444";
// Resolved from the web app root (Playwright runs specs from there).
const KSPLAT = readFileSync(path.resolve(process.cwd(), "../../packages/contracts/fixtures/ksplat/scene.ksplat"));

async function mockJson(page: Page, urlPattern: string, body: unknown) {
  await page.route(urlPattern, (route) => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(body) }));
}

test("the viewer downloads the production .ksplat and GaussianSplats3D loads all of its splats", async ({ page }) => {
  const pageErrors: string[] = [];
  page.on("pageerror", (e) => pageErrors.push(e.message));
  const expiresAt = new Date(Date.now() + 60_000).toISOString();
  const generatedAt = new Date().toISOString();
  await mockJson(page, "**/mock-api/api/v1/public/viewer-token", { token: "cvt_test", expiresAt, venueId: VENUE_ID });
  await mockJson(page, `**/mock-api/api/v1/venues/${VENUE_ID}/floors`, [{ id: FLOOR_ID, level: 0, name: "Ground Floor" }]);
  await mockJson(page, `**/mock-api/api/v1/venues/${VENUE_ID}/floors/${FLOOR_ID}/reconstructions`, [
    { runId: RUN_ID, floorId: FLOOR_ID, generatedAt, runStatus: "SUCCEEDED", runQuality: "FINAL" },
  ]);
  await mockJson(page, `**/mock-api/api/v1/venues/${VENUE_ID}/pois`, []);
  await mockJson(page, `**/mock-api/api/v1/venues/${VENUE_ID}/reconstructions/${RUN_ID}`, {
    runId: RUN_ID, scanId: "scan-1", floorId: FLOOR_ID, generatedAt, runStatus: "SUCCEEDED", runQuality: "FINAL",
    artifacts: [{ kind: "KSPLAT", contentType: "application/octet-stream", sizeBytes: KSPLAT.length, sha256: "0".repeat(64),
      url: `/api/v1/venues/${VENUE_ID}/reconstructions/${RUN_ID}/artifacts/KSPLAT` }],
    coordinateFrame: null,
  });
  let served = 0;
  await page.route(`**/mock-api/api/v1/venues/${VENUE_ID}/reconstructions/${RUN_ID}/artifacts/KSPLAT`, (route) => {
    served++;
    return route.fulfill({ status: 200, contentType: "application/octet-stream", body: KSPLAT });
  });

  await page.goto(`/viewer?link=good-secret`);

  const splats = page.locator("dt", { hasText: /^Splats$/ }).locator("xpath=following-sibling::dd[1]");
  await expect(splats).toHaveText("3", { timeout: 30_000 });
  await expect(page.getByText("Could not load the reconstruction")).toHaveCount(0);
  expect(served).toBe(1);
  expect(pageErrors).toEqual([]);
});
