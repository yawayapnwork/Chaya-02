import { test, expect, type Page } from "@playwright/test";
import { FIXTURE, FLOOR_ID, FRAME_ID, SCENE_TIMEOUT, frameDifference, mockViewerApi, sampleCanvas } from "./viewer-fixture";

// Viewer interactions on the FORMAT VALIDATION fixture (e2e/viewer-fixture.ts: the production exporter's .ksplat of a
// synthetic scene, not a venue). The JSON API -- including POST /navigation/routes -- is mocked with the response shapes
// the API returns; the scene, camera and overlay are the real GaussianSplats3D viewer's.

const RUN_ID = "66666666-6666-6666-6666-666666666666";

async function loadScene(page: Page) {
  await page.goto(`/viewer?link=good-secret`);
  const splats = page.locator("dt", { hasText: /^Splats$/ }).locator("xpath=following-sibling::dd[1]");
  await expect(splats).toHaveText(FIXTURE.splat_count.toLocaleString("en-US"), { timeout: SCENE_TIMEOUT });
  await expect.poll(async () => (await sampleCanvas(page)).covered, { timeout: SCENE_TIMEOUT }).toBeGreaterThan(0.05);
}

/** A frame once the view has stopped changing (the scene's reveal and any camera transition have finished). */
async function settledFrame(page: Page) {
  let last = await sampleCanvas(page);
  await expect.poll(async () => {
    await page.waitForTimeout(500);
    const next = await sampleCanvas(page);
    const d = frameDifference(last, next);
    last = next;
    return d;
  }, { timeout: SCENE_TIMEOUT }).toBeLessThan(0.5);
  return last;
}

/** Answers POST /navigation/routes per accessibility profile: a route body, or an API problem (status, code). */
async function mockRoutes(page: Page, byProfile: Record<string, { body: unknown } | { status: number; code: string; detail: string }>) {
  const requests: Record<string, unknown>[] = [];
  await page.route("**/mock-api/api/v1/navigation/routes", async (route) => {
    const req = route.request().postDataJSON() as Record<string, unknown>;
    requests.push(req);
    const answer = byProfile[String(req.accessibility)];
    if ("body" in answer) {
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(answer.body) });
    } else {
      await route.fulfill({
        status: answer.status, contentType: "application/problem+json",
        body: JSON.stringify({ status: answer.status, code: answer.code, detail: answer.detail }),
      });
    }
  });
  return requests;
}

const [red, green] = FIXTURE.pois_by_construction;
const fixtureRoute = (profile: string, frameId = FRAME_ID) => ({
  waypoints: [
    { x: red.canonical[0], y: red.canonical[1], z: red.canonical[2], floorId: FLOOR_ID, kind: "START" },
    { x: green.canonical[0], y: green.canonical[1], z: green.canonical[2], floorId: FLOOR_ID, kind: "DESTINATION" },
  ],
  distanceMeters: 3.2, estimatedDurationSeconds: 2.7, floorTransitions: [], accessibilityProfile: profile,
  accessibilityConstraintsApplied: profile === "STEP_FREE" ? ["max slope 5°"] : [],
  routingSources: [{ floorId: FLOOR_ID, graphId: "g", source: "RECAST_NAVMESH", navmeshSha256: null, recastnavigationVersion: null,
    pathMethod: null, scanVersionId: null, coordinateFrameId: frameId }],
});

test("camera: zoom, reset and focusing a POI move the real view, and a drag does not clear the selection", async ({ page }) => {
  test.setTimeout(3 * SCENE_TIMEOUT);
  await mockViewerApi(page, RUN_ID, { calibrated: true });
  await loadScene(page);
  const home = await settledFrame(page);

  await page.getByRole("button", { name: "Zoom in" }).click();
  await expect.poll(async () => frameDifference(home, await sampleCanvas(page)), { timeout: SCENE_TIMEOUT }).toBeGreaterThan(4);
  await page.getByRole("button", { name: "Reset view" }).click();
  expect(frameDifference(home, await settledFrame(page))).toBeLessThan(2);

  // Selecting a POI in the list moves the camera to it; its marker ends up near the canvas centre with its name shown.
  await page.locator("aside").getByRole("button", { name: red.label, exact: true }).click();
  await expect(page.getByTestId("poi-details")).toContainText(red.label);
  const canvas = (await page.getByTestId("splat-canvas").boundingBox())!;
  await expect.poll(async () => {
    const b = (await page.locator(`.chaya-poi-marker[title="${red.label}"]`).boundingBox())!;
    return Math.hypot(b.x + b.width / 2 - (canvas.x + canvas.width / 2), b.y + b.height / 2 - (canvas.y + canvas.height / 2));
  }, { timeout: SCENE_TIMEOUT }).toBeLessThan(30);
  await expect(page.locator(".chaya-poi-label", { hasText: red.label })).toBeVisible();

  // An orbit drag is not a background click: the selection survives it.
  const cx = canvas.x + canvas.width / 2;
  const cy = canvas.y + canvas.height * 0.8;
  await page.mouse.move(cx, cy);
  await page.mouse.down();
  await page.mouse.move(cx + 150, cy - 20, { steps: 8 });
  await page.mouse.up();
  await expect(page.getByTestId("poi-details")).toContainText(red.label);
});

test("navigation: both profiles are requested; the standard route is drawn and the missing step-free one is stated", async ({ page }) => {
  test.setTimeout(3 * SCENE_TIMEOUT);
  await mockViewerApi(page, RUN_ID, { calibrated: true });
  const requests = await mockRoutes(page, {
    STANDARD: { body: fixtureRoute("STANDARD") },
    STEP_FREE: { status: 404, code: "NO_ACCESSIBLE_ROUTE", detail: "no step-free route" },
  });
  await loadScene(page);
  const before = await settledFrame(page);

  await page.getByRole("combobox", { name: "From" }).selectOption({ label: red.label });
  await page.getByRole("combobox", { name: "Add a destination" }).selectOption({ label: green.label });
  await expect(page.getByTestId("route-STANDARD")).toContainText("3.2 m · ~3 s (estimate)");
  await expect(page.getByTestId("route-STEP_FREE")).toHaveAttribute("data-failure", "no-accessible-route");
  await expect(page.getByTestId("route-STEP_FREE")).toContainText("No step-free route exists");
  expect(requests.map((r) => r.accessibility).sort()).toEqual(["STANDARD", "STEP_FREE"]);
  expect(requests.every((r) => r.destinationPoiId === "poi-2")).toBe(true);
  // The returned path is drawn on the scene.
  await expect.poll(async () => frameDifference(before, await sampleCanvas(page)), { timeout: SCENE_TIMEOUT }).toBeGreaterThan(0.3);
});

test("navigation: an unreachable destination is reported as such, and nothing is drawn", async ({ page }) => {
  test.setTimeout(3 * SCENE_TIMEOUT);
  await mockViewerApi(page, RUN_ID, { calibrated: true });
  await mockRoutes(page, {
    STANDARD: { status: 404, code: "NO_ROUTE", detail: "no route could be found between the start point and the destination on this floor" },
    STEP_FREE: { status: 404, code: "NO_ACCESSIBLE_ROUTE", detail: "no step-free route" },
  });
  await loadScene(page);

  await page.getByRole("combobox", { name: "From" }).selectOption({ label: red.label });
  await page.getByRole("combobox", { name: "Add a destination" }).selectOption({ label: green.label });
  await expect(page.getByTestId("route-STANDARD")).toHaveAttribute("data-failure", "unreachable");
  await expect(page.getByTestId("route-STANDARD")).toContainText("cannot be reached");
  await expect(page.getByTestId("route-STANDARD")).toContainText("on this floor"); // the API's own reason stays visible
  await expect(page.getByText(/ m · ~/)).toHaveCount(0);
});

test("navigation: a route planned in another coordinate frame is refused, not drawn", async ({ page }) => {
  test.setTimeout(3 * SCENE_TIMEOUT);
  await mockViewerApi(page, RUN_ID, { calibrated: true });
  await mockRoutes(page, {
    STANDARD: { body: fixtureRoute("STANDARD", "77777777-7777-7777-7777-777777777777") },
    STEP_FREE: { status: 404, code: "NO_ACCESSIBLE_ROUTE", detail: "no step-free route" },
  });
  await loadScene(page);
  await page.getByRole("combobox", { name: "From" }).selectOption({ label: red.label });
  await page.getByRole("combobox", { name: "Add a destination" }).selectOption({ label: green.label });
  await expect(page.getByTestId("route-mismatch")).toContainText("different coordinate frame");
});

test("navigation: several destinations are routed in order, highlighted, and cancelled; only changed segments are re-requested", async ({ page }) => {
  test.setTimeout(3 * SCENE_TIMEOUT);
  await mockViewerApi(page, RUN_ID, { calibrated: true });
  const requests = await mockRoutes(page, {
    STANDARD: { body: fixtureRoute("STANDARD") },
    STEP_FREE: { status: 404, code: "NO_ACCESSIBLE_ROUTE", detail: "no step-free route" },
  });
  await loadScene(page);
  const third = FIXTURE.pois_by_construction[2];

  await page.getByRole("combobox", { name: "From" }).selectOption({ label: red.label });
  await page.getByRole("combobox", { name: "Add a destination" }).selectOption({ label: green.label });
  await page.getByRole("combobox", { name: "Add a destination" }).selectOption({ label: third.label });
  await expect(page.getByTestId("route-stop")).toHaveCount(2);
  await expect(page.getByTestId("route-STANDARD")).toContainText("6.4 m"); // two segments of 3.2 m
  await expect(page.getByTestId("route-instructions")).toContainText(`Arrive at stop: ${green.label}`);
  await expect(page.getByTestId("route-instructions")).toContainText(`Arrive at ${third.label}`);
  await expect(page.locator('.chaya-poi-marker[data-destination="true"]')).toHaveCount(2);
  const afterTwo = requests.length;

  // Removing the last destination re-requests nothing: the first segment is reused for both profiles.
  await page.getByTestId("route-stop").nth(1).getByRole("button", { name: "Remove destination" }).click();
  await expect(page.getByTestId("route-STANDARD")).toContainText("3.2 m");
  expect(requests.length).toBe(afterTwo);

  // Recalculate asks again; cancel clears the route and its highlight.
  await page.getByTestId("route-recalculate").click();
  await expect.poll(() => requests.length).toBe(afterTwo + 2);
  await page.getByTestId("route-cancel").click();
  await expect(page.getByTestId("route-STANDARD")).toHaveCount(0);
  await expect(page.locator('.chaya-poi-marker[data-destination="true"]')).toHaveCount(0);
});

test("navigation: an expired sign-in is reported as such, not as an unreachable destination", async ({ page }) => {
  test.setTimeout(3 * SCENE_TIMEOUT);
  await mockViewerApi(page, RUN_ID, { calibrated: true });
  await mockRoutes(page, {
    STANDARD: { status: 401, code: "HTTP_401", detail: "token expired" },
    STEP_FREE: { status: 401, code: "HTTP_401", detail: "token expired" },
  });
  await loadScene(page);
  await page.getByRole("combobox", { name: "From" }).selectOption({ label: red.label });
  await page.getByRole("combobox", { name: "Add a destination" }).selectOption({ label: green.label });
  await expect(page.getByTestId("route-STANDARD")).toHaveAttribute("data-failure", "session-expired");
  await expect(page.getByTestId("route-STANDARD")).toContainText("expired");
});

test("keyboard: the focused scene zooms and resets from the keyboard, and typing elsewhere never changes it", async ({ page }) => {
  test.setTimeout(3 * SCENE_TIMEOUT);
  await mockViewerApi(page, RUN_ID, { calibrated: true });
  await loadScene(page);
  const home = await settledFrame(page);

  // The library used to bind debug keys (= / -: splat scale, F/G: focal length, arrows: camera roll) and the orbit
  // controls' WASD panning to the whole window. Typing them into the search box must leave the scene as it was.
  const search = page.getByRole("textbox", { name: "Search query" });
  await search.pressSequentially("======gggggg"); // splat scale and focal length, each pushed one way
  for (let i = 0; i < 12; i++) await search.press("ArrowLeft"); // the library rolled the camera on this key
  expect(frameDifference(home, await settledFrame(page))).toBeLessThan(2);

  const scene = page.getByRole("application");
  await scene.focus();
  await page.keyboard.press("+");
  expect(frameDifference(home, await settledFrame(page))).toBeGreaterThan(4);
  await page.keyboard.press("0");
  expect(frameDifference(home, await settledFrame(page))).toBeLessThan(2);
});

test("mobile: the scene keeps most of the screen, the panel scrolls below it, and controls are touch-sized", async ({ browser }) => {
  test.setTimeout(3 * SCENE_TIMEOUT);
  const context = await browser.newContext({ viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true });
  const page = await context.newPage();
  await mockViewerApi(page, RUN_ID, { calibrated: true });
  await loadScene(page);
  const canvas = (await page.getByTestId("splat-canvas").boundingBox())!;
  const aside = (await page.locator("aside").boundingBox())!;
  expect(canvas.height).toBeGreaterThan(844 * 0.5);
  expect(aside.y).toBeGreaterThanOrEqual(canvas.y + canvas.height - 1); // below the scene, not over it
  for (const name of ["Zoom in", "Zoom out", "Reset view"]) {
    const b = (await page.getByRole("button", { name }).boundingBox())!;
    expect(Math.min(b.width, b.height)).toBeGreaterThanOrEqual(40);
  }
  await expect(page.getByText("One finger orbits")).toBeVisible();
  await context.close();
});
