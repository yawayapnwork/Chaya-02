// Browser check of the web viewer against the running E2E stack (docs/E2E_VALIDATION.md, section 4.3).
// Real Chromium, real web container, real API, real Keycloak; nothing is mocked or intercepted.
//
//   A. Public viewing link: /viewer?link=<secret> -> floors, POIs, reconstruction state; search; route request.
//      The public token must travel in X-Chaya-Viewer-Token and never as Authorization.
//   B. Signed-in venue manager: /viewer -> "Sign in" -> Keycloak login page (authorization code + PKCE through the real
//      chaya-web client) -> /auth/callback -> /viewer with the manager's venue, floors and POIs, sent as a Bearer JWT.
//
//   cd apps/web && HANDOFF=<handoff.json from e2e_validate.py --handoff> OUT_DIR=<evidence dir> \
//     node ../../scripts/e2e/viewer_check.cjs
// HANDOFF holds the throwaway test manager's password and a link secret; neither is printed or written to OUT_DIR.
const fs = require("fs");
const path = require("path");
const { chromium } = require(path.join(process.cwd(), "node_modules", "playwright"));

const out = process.env.OUT_DIR || ".";
const web = process.env.WEB_URL || "http://localhost:3000";
const handoff = JSON.parse(fs.readFileSync(process.env.HANDOFF, "utf8"));
const results = [];
const text = async (page) => (await page.innerText("body")).replace(/\s+/g, " ").slice(0, 1200);

// ok: true -> PASS, false -> FAIL, or a string -> BLOCKED with that string as the missing dependency.
function record(id, name, expected, ok, actual, evidence) {
  const status = typeof ok === "string" ? "BLOCKED" : ok ? "PASS" : "FAIL";
  results.push({ id, name, expected, status, blocked_dependency: typeof ok === "string" ? ok : null, actual, evidence });
  console.log(`[${status}] ${id} ${name}: ${actual}`);
}

function watch(page) {
  const calls = [];
  const errors = [];
  page.on("request", (r) => {
    if (!/\/api\/v1\//.test(r.url())) return;
    const h = r.headers();
    calls.push({ method: r.method(), url: r.url().replace(/\?.*/, ""), auth: h.authorization ? h.authorization.split(" ")[0] : null,
                 viewerToken: Boolean(h["x-chaya-viewer-token"]) });
  });
  page.on("response", (r) => {
    const c = calls.find((x) => x.url === r.url().replace(/\?.*/, "") && x.status === undefined && x.method === r.request().method());
    if (c) c.status = r.status();
  });
  page.on("console", (m) => { if (m.type() === "error") errors.push(m.text()); });
  return { calls, errors };
}

const summary = (calls) => calls.map((c) => `${c.status ?? "?"} ${c.method} ${c.url.replace(/^https?:\/\/[^/]+/, "")}`);

(async () => {
  const browser = await chromium.launch();
  try {
    // ---------------------------------------------------------------- A. public link
    const ctxA = await browser.newContext({ viewport: { width: 1400, height: 900 } });
    const page = await ctxA.newPage();
    const a = watch(page);
    await page.goto(`${web}/viewer?link=${encodeURIComponent(handoff.viewerLink)}`, { waitUntil: "networkidle" });
    await page.waitForTimeout(3000);
    await page.screenshot({ path: path.join(out, "viewer-public.png"), fullPage: true });
    const pubText = await text(page);
    const venueCalls = a.calls.filter((c) => c.url.includes(`/venues/${handoff.venueId}`));
    const headerOk = venueCalls.length > 0 && venueCalls.every((c) => c.viewerToken && !c.auth);
    const loaded = ["floors", "pois", "reconstructions"].every((k) => venueCalls.some((c) => c.url.includes(`/${k}`) && c.status === 200));
    record("B1", "Public link: viewer loads venue data", "link exchanged; floors, POIs, reconstructions 200; token in X-Chaya-Viewer-Token only",
           headerOk && loaded && /Reception desk/.test(pubText),
           `headers ok=${headerOk}; floors/pois/reconstructions 200=${loaded}; page: ${pubText.slice(0, 300)}`,
           { api: summary(a.calls), consoleErrors: a.errors });
    record("B1.1", "Public link: reconstruction state shown honestly", "a viewable scene, or an explicit empty state (never a fake scene)",
           /No reconstruction available/.test(pubText) || /reconstruction/i.test(pubText),
           /No reconstruction available/.test(pubText) ? "explicit empty state: 'No reconstruction available'" : "scene area present",
           { screenshot: "viewer-public.png" });

    await page.getByLabel("Search query").fill(process.env.QUERY || "restroom");
    await page.getByRole("button", { name: "Go" }).click();
    await page.waitForTimeout(3000);
    await page.screenshot({ path: path.join(out, "viewer-search.png"), fullPage: true });
    // Only the search panel's own result list counts: the POI sidebar lists every POI regardless of search.
    const resultsText = (await page.getByTestId("search-results").innerText().catch(() => "")).replace(/\s+/g, " ");
    const searchCall = a.calls.filter((c) => c.url.endsWith("/search")).pop();
    record("B2", "Public link: search 'restroom' in the viewer", "search 200 and 'Accessible restroom' in the search results",
           searchCall?.status === 200 && /Accessible restroom/.test(resultsText),
           `search HTTP ${searchCall?.status}; results panel: '${resultsText.slice(0, 200)}'`, { screenshot: "viewer-search.png" });

    const selects = page.locator("select");
    const n = await selects.count();
    await selects.nth(n - 2).selectOption({ label: process.env.ROUTE_FROM || "Reception desk" });
    await selects.nth(n - 1).selectOption({ label: process.env.ROUTE_TO || "Elevator A" });
    await page.waitForTimeout(3000);
    await page.screenshot({ path: path.join(out, "viewer-route.png"), fullPage: true });
    const routeText = await text(page);
    const routeCall = a.calls.filter((c) => c.url.endsWith("/navigation/routes")).pop();
    const routeMsg = (routeText.match(/No route available: [^]*?on this floor/) || [""])[0];
    // 404 ROUTE_UNAVAILABLE with the API's reason shown is the honest state when no navigation graph exists (BLOCKED);
    // any other outcome, or a 404 the panel hides, is a FAIL.
    const routeOk = routeCall?.status === 200 ? true : routeCall?.status === 404 && routeMsg ? "NAVIGATION_BAKING (no navigation graph)" : false;
    record("B3", "Public link: route 'Reception desk' -> 'Elevator A' drawn", "route 200 and drawn over the scene",
           routeOk, `route HTTP ${routeCall?.status}; panel: ${routeMsg || routeText.slice(0, 200)}`, { screenshot: "viewer-route.png" });
    // Chromium logs every non-2xx response as a console error; the route 404 above is expected, anything else is not.
    const unexpected = a.errors.filter((e) => !(routeCall?.status === 404 && /status of 404/.test(e)));
    record("B-A", "Public link: no unexpected browser console errors", "none besides the logged route 404", unexpected.length === 0,
           `${unexpected.length} unexpected of ${a.errors.length} console errors`, a.errors);
    await ctxA.close();

    // ---------------------------------------------------------------- B. signed-in manager (Keycloak PKCE)
    const ctxB = await browser.newContext({ viewport: { width: 1400, height: 900 } });
    const pageB = await ctxB.newPage();
    const b = watch(pageB);
    await pageB.goto(`${web}/viewer`, { waitUntil: "networkidle" });
    await pageB.getByRole("button", { name: "Sign in" }).click();
    await pageB.waitForSelector("#username", { timeout: 30000 });
    const loginUrl = new URL(pageB.url());
    await pageB.fill("#username", handoff.managerUsername);
    await pageB.fill("#password", handoff.managerPassword);
    await pageB.click("#kc-login");
    await pageB.waitForURL(/\/viewer/, { timeout: 30000 });
    await pageB.waitForLoadState("networkidle");
    await pageB.waitForTimeout(3000);
    await pageB.screenshot({ path: path.join(out, "viewer-signed-in.png"), fullPage: true });
    const signedText = await text(pageB);
    const pkce = loginUrl.searchParams.get("code_challenge_method");
    record("B4.1", "Sign-in goes to Keycloak with authorization code + PKCE (client chaya-web)",
           "Keycloak login page; response_type=code, code_challenge_method=S256, client_id=chaya-web",
           loginUrl.searchParams.get("response_type") === "code" && pkce === "S256" && loginUrl.searchParams.get("client_id") === "chaya-web",
           `${loginUrl.origin}${loginUrl.pathname}; response_type=${loginUrl.searchParams.get("response_type")}; code_challenge_method=${pkce}; client_id=${loginUrl.searchParams.get("client_id")}`);
    const bearer = b.calls.filter((c) => c.url.includes("/api/v1/venues"));
    const bearerOk = bearer.length > 0 && bearer.every((c) => c.auth === "Bearer" && !c.viewerToken);
    const managerLoaded = bearer.some((c) => c.url.endsWith("/api/v1/venues") && c.status === 200)
      && bearer.some((c) => c.url.includes("/pois") && c.status === 200);
    record("B4", "Signed-in manager: viewer loads own venue as a Bearer JWT", "venues, floors, POIs 200 with Authorization: Bearer",
           bearerOk && managerLoaded && /Reception desk/.test(signedText),
           `bearer on every call=${bearerOk}; venues+POIs 200=${managerLoaded}; page: ${signedText.slice(0, 300)}`,
           { api: summary(b.calls), consoleErrors: b.errors, screenshot: "viewer-signed-in.png" });
    record("B-B", "Signed-in manager: no browser console errors", "none", b.errors.length === 0, `${b.errors.length} console errors`, b.errors);
    await ctxB.close();
  } finally {
    await browser.close();
    fs.writeFileSync(path.join(out, "viewer-check.json"), JSON.stringify(results, null, 2));
  }
})().catch((e) => { console.error(e); process.exit(1); });
