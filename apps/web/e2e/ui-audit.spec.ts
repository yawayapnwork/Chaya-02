import { expect, type Page, test } from "@playwright/test";
import { FLOOR, MockCaptureServer, VENUE } from "./capture-fixture";
import { mockOidc } from "./oidc-fixture";
import { FLOOR_ID, VENUE_ID, mockJson, mockViewerApi } from "./viewer-fixture";

// The functional UI audit's checks (2026-10-17), in a real browser against the stateful mock capture API and the mock OIDC
// provider: the app's navigation and sign-out, session restore after a reload, deep links through sign-in, the re-scan start,
// floor management validation, the AR page signed out, horizontal overflow, touch target sizes and keyboard reachability.

const SESSION_RESTORE_MS = 15_000;

async function signedInCapture(page: Page, server: MockCaptureServer) {
  await mockOidc(page, { session: false });
  await server.install(page);
  await page.goto("/capture");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByTestId("capture-queue")).toBeVisible();
  await expect(page.locator(`option[value="${FLOOR}"]`)).toBeAttached();
}

test("navigation bar: every section is reachable, the current one is marked, and it is absent on the sign-in callback", async ({ page }) => {
  await page.goto("/status");
  const nav = page.getByRole("navigation", { name: "Main" });
  await expect(nav.getByRole("link", { name: "Status" })).toHaveAttribute("aria-current", "page");
  for (const [name, path] of [["Capture", "/capture"], ["Viewer", "/viewer"], ["AR", "/ar"], ["Operations", "/ops"], ["Status", "/status"]]) {
    await nav.getByRole("link", { name, exact: true }).click();
    await expect(page).toHaveURL(new RegExp(`${path}$`));
    await expect(nav.getByRole("link", { name, exact: true })).toHaveAttribute("aria-current", "page");
  }
  await page.goto("/auth/callback");
  await expect(page.getByRole("navigation", { name: "Main" })).toHaveCount(0);
});

test("a reload keeps the user signed in (silent restore of the identity-provider session); without one the page offers Sign in", async ({ page }) => {
  const server = new MockCaptureServer();
  await mockOidc(page); // a provider with a session
  await server.install(page);
  await page.goto("/capture");
  const t0 = Date.now();
  await expect(page.getByTestId("capture-queue")).toBeVisible({ timeout: SESSION_RESTORE_MS });
  console.log(`[audit] signed in without a click after ${Date.now() - t0} ms`);
  await expect(page.getByRole("button", { name: "Sign in" })).toHaveCount(0);
  await page.reload();
  await expect(page.getByTestId("capture-queue")).toBeVisible({ timeout: SESSION_RESTORE_MS });
  await expect(page.getByRole("navigation", { name: "Main" }).getByRole("button", { name: "Sign out" })).toBeVisible();

  const other = await page.context().newPage();
  await mockOidc(other, { session: false });
  await server.install(other);
  await other.goto("/capture");
  await expect(other.getByRole("button", { name: "Sign in" })).toBeVisible({ timeout: SESSION_RESTORE_MS });
  await expect(other.getByRole("navigation", { name: "Main" }).getByRole("button", { name: "Sign out" })).toHaveCount(0);
});

test("sign out ends the provider session and returns to the start page signed out", async ({ page }) => {
  const server = new MockCaptureServer();
  await signedInCapture(page, server);
  let logout: URL | null = null;
  await page.route("**/mock-oidc/logout?**", (route) => {
    logout = new URL(route.request().url());
    return route.fulfill({ status: 302, headers: { location: logout.searchParams.get("post_logout_redirect_uri") ?? "/" } });
  });
  await page.getByRole("navigation", { name: "Main" }).getByRole("button", { name: "Sign out" }).click();
  await expect(page).toHaveURL("http://localhost:3100/");
  expect(logout, "the provider's end-session endpoint was called").not.toBeNull();
  await expect(page.getByRole("navigation", { name: "Main" }).getByRole("button", { name: "Sign out" })).toHaveCount(0);
});

test("signing in returns to the exact page, query included", async ({ page }) => {
  const server = new MockCaptureServer();
  await mockOidc(page, { session: false });
  await server.install(page);
  await page.goto(`/ar?venue=${VENUE}&floor=${FLOOR}`);
  await expect(page.getByTestId("ar-signed-out").or(page.getByTestId("ar-unavailable")).first()).toBeVisible({ timeout: SESSION_RESTORE_MS });
  const signIn = page.getByTestId("ar-signed-out").getByRole("button", { name: "Sign in" });
  if (await signIn.count()) {
    await signIn.click();
    await expect(page).toHaveURL(new RegExp(`/ar\\?venue=${VENUE}&floor=${FLOOR}$`));
  } else {
    // Chromium without WebXR: the page blocks before sign-in matters; the capture page carries the same rule.
    await page.goto(`/capture?from=deep-link`);
    await page.getByRole("button", { name: "Sign in" }).click();
    await expect(page).toHaveURL(/\/capture\?from=deep-link$/);
  }
});

test("re-scan: started on the floor's published version with the typed region, then uploaded like any capture", async ({ page }) => {
  const server = new MockCaptureServer();
  await signedInCapture(page, server);
  await page.getByRole("combobox").first().selectOption({ label: "Test Hall" });
  await page.getByRole("combobox").nth(1).selectOption(FLOOR);
  const rescan = page.getByTestId("rescan-start");
  await rescan.locator("summary").click();
  const region = rescan.getByRole("textbox");
  const start = rescan.getByRole("button", { name: "Start re-scan" });
  await expect(start).toBeDisabled();
  await region.fill("4.4, -0.1\n6.1, -0.1");
  await expect(rescan.getByText("A region needs at least 3 vertices.")).toBeVisible();
  await expect(start).toBeDisabled();
  await region.fill("4.4, -0.1\n6.1, -0.1\n6.1, 2.7\n4.4, 2.7");
  await expect(rescan.getByText("4 corners, 4.76 m².")).toBeVisible();
  // The server refuses once (an uncalibrated floor): its code is shown and nothing is created.
  server.faults.push({ match: "POST /rescan", kind: 409, code: "NOT_CALIBRATED", times: 1 });
  await start.click();
  await expect(rescan.getByRole("alert")).toContainText("NOT_CALIBRATED");
  expect(server.captures.size).toBe(0);
  await start.dblclick();
  await expect(page.getByTestId("active-session-status")).toContainText("Created on the server", { timeout: 15_000 });
  // The injected refusal is answered before the mock records a body: what is recorded is the double click, once.
  expect(server.rescanRequests.length, "the double click is one submission").toBe(1);
  expect(server.rescanRequests[0]).toMatchObject({
    parentVersionId: server.floorVersion, region: { points: [[4.4, -0.1], [6.1, -0.1], [6.1, 2.7], [4.4, 2.7]] },
  });
  expect(server.captures.size).toBe(1);
  await expect(page.getByLabel("Choose files to upload")).toBeVisible();
});

test("re-scan is not offered as available on a floor without a published version", async ({ page }) => {
  const server = new MockCaptureServer();
  server.floorVersion = null;
  await signedInCapture(page, server);
  await page.getByRole("combobox").first().selectOption({ label: "Test Hall" });
  await page.getByRole("combobox").nth(1).selectOption(FLOOR);
  await page.getByTestId("rescan-start").locator("summary").click();
  await expect(page.getByTestId("rescan-unavailable")).toContainText("no published version");
  await expect(page.getByRole("button", { name: "Start re-scan" })).toHaveCount(0);
});

test("floor management: a refused floor keeps what was typed, a double click adds one floor, and an empty level cannot be saved", async ({ page }) => {
  const server = new MockCaptureServer();
  await signedInCapture(page, server);
  await page.getByRole("combobox").first().selectOption({ label: "Test Hall" });
  const fm = page.getByTestId("floor-manager");
  await fm.locator("summary").click();
  const form = fm.locator("form");
  await form.getByLabel("Level").fill("3");
  await form.getByLabel("Name").fill("Mezzanine");
  server.faults.push({ match: "POST /floors", kind: 409, code: "FLOOR_LEVEL_TAKEN", times: 1 });
  await form.getByRole("button", { name: "Add floor" }).click();
  await expect(fm.getByRole("alert")).toContainText("FLOOR_LEVEL_TAKEN");
  await expect(form.getByLabel("Level")).toHaveValue("3");
  await expect(form.getByLabel("Name")).toHaveValue("Mezzanine");
  await form.getByRole("button", { name: "Add floor" }).dblclick();
  await expect(form.getByLabel("Name")).toHaveValue("");
  // The injected refusal is answered before the mock records a body: what is recorded is the double click, once.
  expect(server.floorCreates.length, "the double click adds one floor").toBe(1);

  const row = fm.getByTestId("floor-row").first();
  await row.getByLabel("Level").fill("");
  await expect(row.getByText("Level must be a whole number.")).toBeVisible();
  await expect(row.getByRole("button", { name: "Save" })).toBeDisabled();
});

test("AR page signed out: it says so and offers a working Sign in, not a bare error", async ({ page }) => {
  await mockOidc(page, { session: false });
  await page.route("**/mock-api/**", (route) => route.fulfill({ status: 200, contentType: "application/json", body: "[]" }));
  await page.goto("/ar");
  const out = page.getByTestId("ar-signed-out");
  const blocked = page.getByTestId("ar-unavailable");
  await expect(out.or(blocked).first()).toBeVisible({ timeout: SESSION_RESTORE_MS });
  // Without WebXR (headless Chromium) the page shows why AR is unavailable; the sign-in state is still reported.
  await expect(out).toBeVisible();
  await expect(out.getByRole("button", { name: "Sign in" })).toBeEnabled();
});

const PAGES = ["/", "/status", "/capture", "/viewer", "/ops", "/ar"];
for (const width of [375, 768, 1280]) {
  test(`no horizontal overflow at ${width}px, signed in`, async ({ page }) => {
    test.setTimeout(120_000);
    await page.setViewportSize({ width, height: 800 });
    const server = new MockCaptureServer();
    await mockOidc(page);
    await server.install(page);
    for (const path of PAGES) {
      await page.goto(path);
      await page.waitForLoadState("networkidle");
      const { sw, vw } = await page.evaluate(() => ({ sw: document.documentElement.scrollWidth, vw: document.documentElement.clientWidth }));
      expect(sw, `${path} is ${sw}px wide in a ${vw}px viewport`).toBeLessThanOrEqual(vw + 1);
    }
  });
}

test.describe("touch screen", () => {
  test.use({ hasTouch: true, isMobile: true, viewport: { width: 390, height: 844 } });
  test("capture controls are at least 44 px tall", async ({ page }) => {
    const server = new MockCaptureServer();
    await signedInCapture(page, server);
    await page.getByRole("combobox").first().selectOption({ label: "Test Hall" });
    await page.getByTestId("floor-manager").locator("summary").click();
    const small = await page.evaluate(() => Array.from(document.querySelectorAll("main button, main select, main summary"))
      .filter((el) => { const r = el.getBoundingClientRect(); return r.width > 0 && r.height < 43.5; })
      .map((el) => `${(el.textContent ?? "").trim().slice(0, 30)} ${Math.round(el.getBoundingClientRect().height)}px`));
    expect(small).toEqual([]);
  });
});

test("keyboard: the navigation and the capture form are reached with Tab, in order, with a visible focus", async ({ page }) => {
  const server = new MockCaptureServer();
  await signedInCapture(page, server);
  const order: string[] = [];
  for (let i = 0; i < 12; i++) {
    await page.keyboard.press("Tab");
    order.push(await page.evaluate(() => {
      const el = document.activeElement as HTMLElement | null;
      if (!el) return "";
      const style = getComputedStyle(el);
      const visible = style.outlineStyle !== "none" || style.boxShadow !== "none";
      return `${el.tagName.toLowerCase()}:${(el.getAttribute("aria-label") ?? el.textContent ?? "").trim().slice(0, 20)}:${visible ? "focus-visible" : "NO-FOCUS-STYLE"}`;
    }));
  }
  console.log(`[audit] tab order: ${order.join(" > ")}`);
  // next dev's own overlay (nextjs-portal) takes a tab stop in development only; it is not part of the app.
  order.splice(0, order.length, ...order.filter((x) => !x.startsWith("nextjs-portal")));
  expect(order[0]).toMatch(/^a:Chaya 02/);
  expect(order.some((x) => x.startsWith("a:Capture"))).toBe(true);
  expect(order.some((x) => x.startsWith("select:"))).toBe(true);
  expect(order.filter((x) => x.endsWith("NO-FOCUS-STYLE"))).toEqual([]);
});

test("viewer: the selection is kept in the URL across a reload, and 'Open in AR' opens the same venue and floor", async ({ page }) => {
  const RUN = "44444444-4444-4444-4444-444444444444";
  await mockOidc(page);
  await mockViewerApi(page, RUN, { calibrated: true });
  await mockJson(page, "**/mock-api/api/v1/venues", [{ id: VENUE_ID, name: "Fixture Hall", slug: "fixture-hall" }]);
  await mockJson(page, `**/mock-api/api/v1/venues/${VENUE_ID}/captures`, []);
  await page.goto("/viewer");
  await expect(page).toHaveURL(new RegExp(`/viewer[?]venue=${VENUE_ID}&floor=${FLOOR_ID}&run=${RUN}$`), { timeout: SESSION_RESTORE_MS });
  await expect(page.getByTestId("open-ar")).toHaveAttribute("href", `/ar?venue=${VENUE_ID}&floor=${FLOOR_ID}`);
  await page.reload();
  await expect(page.getByTestId("current-floor")).toBeVisible({ timeout: SESSION_RESTORE_MS });
  await expect(page).toHaveURL(new RegExp(`run=${RUN}$`));
  await expect(page.locator("select").filter({ has: page.locator(`option[value="${RUN}"]`) })).toHaveValue(RUN);
});

test("viewer on a public link: the link stays in the URL, no venue is written, and AR (sign-in only) is not offered", async ({ page }) => {
  const RUN = "44444444-4444-4444-4444-444444444444";
  await mockViewerApi(page, RUN, { calibrated: true });
  await page.goto("/viewer?link=good-secret");
  await expect(page).toHaveURL(new RegExp(`/viewer[?]link=good-secret&floor=${FLOOR_ID}&run=${RUN}$`), { timeout: SESSION_RESTORE_MS });
  await expect(page.getByTestId("open-ar")).toHaveCount(0);
});
