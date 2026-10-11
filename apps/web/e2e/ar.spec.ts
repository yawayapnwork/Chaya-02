import { test, expect, type Page } from "@playwright/test";

// The Android WebXR AR page on desktop Chromium (docs/ar.md, "Capability states"). Desktop Chrome must get an explicit
// unsupported state and nothing that looks like AR: no start button and no WebGL canvas. The last two tests replace
// the browser's WebXR globals before the page loads, to reach the image-tracking and anchors checks. They exercise
// capability detection only. No session is simulated; a real session is covered by docs/ar-android-validation.md.

async function openAr(page: Page) {
  await page.route("**/mock-api/api/v1/venues", (route) => route.fulfill({ status: 200, contentType: "application/json", body: "[]" }));
  await page.goto("/ar");
}

async function expectNoAr(page: Page) {
  await expect(page.getByRole("button", { name: "Start AR navigation" })).toHaveCount(0);
  await expect(page.locator("canvas")).toHaveCount(0);
}

test("desktop Chromium gets an explicit unsupported state, never a simulated AR view", async ({ page }) => {
  await openAr(page);
  await expect(page.getByTestId("ar-unavailable")).toContainText(/WEBXR_UNAVAILABLE|IMMERSIVE_AR_UNSUPPORTED/);
  await expectNoAr(page);
});

test("immersive-ar without WebXR image tracking is IMAGE_TRACKING_UNSUPPORTED", async ({ page }) => {
  await page.addInitScript(() => {
    Object.defineProperty(navigator, "xr", { configurable: true, value: { isSessionSupported: async () => true } });
    delete (window as unknown as Record<string, unknown>).XRImageTrackingResult;
  });
  await openAr(page);
  await expect(page.getByTestId("ar-unavailable")).toContainText("IMAGE_TRACKING_UNSUPPORTED");
  await expectNoAr(page);
});

test("image tracking without WebXR anchors is ANCHORS_UNSUPPORTED", async ({ page }) => {
  await page.addInitScript(() => {
    Object.defineProperty(navigator, "xr", { configurable: true, value: { isSessionSupported: async () => true } });
    (window as unknown as Record<string, unknown>).XRImageTrackingResult = function XRImageTrackingResult() {};
    delete (window as unknown as Record<string, unknown>).XRAnchor;
  });
  await openAr(page);
  await expect(page.getByTestId("ar-unavailable")).toContainText("ANCHORS_UNSUPPORTED");
  await expectNoAr(page);
});

test.describe("iPhone Safari", () => {
  test.use({ userAgent: "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1" });
  test("is told to use the native ARKit app, never offered a WebXR session", async ({ page }) => {
    // Desktop Chromium with an iPhone user agent still has navigator.xr on its prototype, so it reports either state;
    // real iOS Safari has none (WEBXR_UNAVAILABLE). What matters: the native app is named and no AR is offered.
    await openAr(page);
    await expect(page.getByTestId("ar-unavailable")).toContainText(/WEBXR_UNAVAILABLE|IMMERSIVE_AR_UNSUPPORTED/);
    await expect(page.getByTestId("ar-ios-native")).toContainText("Chaya AR app");
    await expectNoAr(page);
  });
});
