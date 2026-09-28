import { createHash } from "node:crypto";
import { test, expect } from "@playwright/test";
import { FIXTURE, KSPLAT_SHA256, SCENE_TIMEOUT, colourAbove, frameDifference, mockViewerApi, sampleCanvas } from "./viewer-fixture";

// FORMAT VALIDATION (not a real venue reconstruction): the production exporter's .ksplat of the synthetic viewer-scene
// fixture (e2e/viewer-fixture.ts), delivered byte for byte to the viewer's real download path and loaded, rendered and
// overlaid by the real GaussianSplats3D viewer in Chromium. The JSON API is mocked; nothing about the scene is.

const RUN_ID = "44444444-4444-4444-4444-444444444444";

test("FORMAT VALIDATION: the exported fixture downloads, loads every splat, renders, orbits, and carries its POI overlay", async ({ page }) => {
  test.setTimeout(3 * SCENE_TIMEOUT);
  const pageErrors: string[] = [];
  page.on("pageerror", (e) => pageErrors.push(e.message));
  const artifactPath = await mockViewerApi(page, RUN_ID, { calibrated: true });
  const artifactResponse = page.waitForResponse((r) => r.url().endsWith(artifactPath));

  await page.goto(`/viewer?link=good-secret`);

  // HTTP artifact retrieval: one authenticated request, and the browser received exactly the exporter's bytes.
  const response = await artifactResponse;
  const headers = response.request().headers();
  expect(headers["x-chaya-viewer-token"]).toBe("cvt_test");
  expect(headers["authorization"]).toBeUndefined();
  expect(createHash("sha256").update(await response.body()).digest("hex")).toBe(KSPLAT_SHA256);

  // Viewer initialisation and format compatibility: GaussianSplats3D reports every splat of the fixture.
  const splats = page.locator("dt", { hasText: /^Splats$/ }).locator("xpath=following-sibling::dd[1]");
  await expect(splats).toHaveText(FIXTURE.splat_count.toLocaleString("en-US"), { timeout: SCENE_TIMEOUT });
  await expect(page.getByText("Could not load the reconstruction")).toHaveCount(0);
  await expect(page.getByTestId("coordinate-frame-status")).toHaveText("Calibrated, metres (venue datum, v1)");

  // Rendered scene: the WebGL canvas holds drawn splats, not just the clear colour.
  await expect.poll(async () => (await sampleCanvas(page)).covered, { timeout: SCENE_TIMEOUT }).toBeGreaterThan(0.05);
  const before = await sampleCanvas(page);

  // POI overlay: one marker per fixture POI, and the red pillar's marker sits under red rendered splats.
  const markers = page.locator(".chaya-poi-marker");
  await expect(markers).toHaveCount(FIXTURE.pois_by_construction.length);
  const red = page.locator('.chaya-poi-marker[title="Red pillar"]');
  const box = (await red.boundingBox())!;
  const canvasBox = (await page.getByTestId("splat-canvas").boundingBox())!;
  expect(box.x > canvasBox.x && box.x < canvasBox.x + canvasBox.width && box.y > canvasBox.y && box.y < canvasBox.y + canvasBox.height).toBe(true);
  const [r, g, b] = await colourAbove(page, box.x + box.width / 2, box.y + box.height / 2);
  expect(r, `colour above the Red pillar marker: ${[r, g, b].map(Math.round)}`).toBeGreaterThan(g + 40);
  expect(r).toBeGreaterThan(b + 40);
  await red.click();
  await expect(page.getByTestId("poi-details")).toContainText("Red pillar");

  // Camera interaction: dragging on the canvas orbits the built-in controls; the next frame is a different view.
  const cx = canvasBox.x + canvasBox.width / 2;
  const cy = canvasBox.y + canvasBox.height * 0.75;
  await page.mouse.move(cx, cy);
  await page.mouse.down();
  await page.mouse.move(cx + 220, cy - 40, { steps: 12 });
  await page.mouse.up();
  await expect.poll(async () => frameDifference(before, await sampleCanvas(page)), { timeout: SCENE_TIMEOUT }).toBeGreaterThan(4);
  expect((await sampleCanvas(page)).covered).toBeGreaterThan(0.02);

  expect(pageErrors).toEqual([]);
});
