// Browser half of the viewer FORMAT VALIDATION (docs/E2E_VALIDATION.md, section V): real Chromium, the real web container,
// the real API, MinIO and Keycloak. Nothing is mocked or intercepted. The scene is the synthetic viewer-scene fixture
// exported by the real worker's ARTIFACT_GENERATION (scripts/e2e/viewer_format_validation.py); NOT a venue reconstruction.
//
// Evidence is measured, not pictured: the artifact response's status, headers and SHA-256 as the browser received them;
// the splat count GaussianSplats3D reports; pixels read back from the WebGL canvas (coverage, the colour above a POI
// marker, the change after a camera drag). No screenshot is taken.
//
//   cd apps/web && HANDOFF=<viewer-handoff.json> OUT=<evidence json> node ../../scripts/e2e/viewer_format_check.cjs
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");
const { chromium } = require(path.join(process.cwd(), "node_modules", "playwright"));

const web = process.env.WEB_URL || "http://localhost:3000";
const handoff = JSON.parse(fs.readFileSync(process.env.HANDOFF, "utf8"));
const results = [];
const SCENE_TIMEOUT = 90_000;

function record(id, name, expected, ok, actual, evidence) {
  const status = typeof ok === "string" ? "BLOCKED" : ok ? "PASS" : "FAIL";
  results.push({ id, name, expected, status, blocked_dependency: typeof ok === "string" ? ok : null, actual, evidence });
  console.log(`[${status}] ${id} ${name}: ${actual}`);
}

// Reads the WebGL canvas right after the viewer's own frame (a rAF callback registered after its render loop's).
async function sampleCanvas(page) {
  return page.evaluate(() => new Promise((resolve, reject) => requestAnimationFrame(() => {
    const gl = document.querySelector('[data-testid="splat-canvas"] canvas');
    if (!gl) return reject(new Error("no WebGL canvas"));
    const c = document.createElement("canvas");
    c.width = gl.width; c.height = gl.height;
    const ctx = c.getContext("2d");
    ctx.drawImage(gl, 0, 0);
    const d = ctx.getImageData(0, 0, c.width, c.height).data;
    const bg = [d[0], d[1], d[2]];
    let covered = 0, n = 0;
    const G = 8, sums = new Array(G * G * 3).fill(0), counts = new Array(G * G).fill(0);
    for (let y = 0; y < c.height; y += 4) for (let x = 0; x < c.width; x += 4) {
      const i = (y * c.width + x) * 4; n++;
      if (Math.abs(d[i] - bg[0]) + Math.abs(d[i + 1] - bg[1]) + Math.abs(d[i + 2] - bg[2]) > 24) covered++;
      const cell = Math.floor((y / c.height) * G) * G + Math.floor((x / c.width) * G);
      counts[cell]++;
      for (let k = 0; k < 3; k++) sums[cell * 3 + k] += d[i + k];
    }
    resolve({ width: c.width, height: c.height, covered: covered / n, cells: sums.map((s, k) => s / Math.max(1, counts[Math.floor(k / 3)])) });
  })));
}

async function colourAbove(page, px, py, rise = 40, half = 6) {
  return page.evaluate(({ px, py, rise, half }) => new Promise((resolve) => requestAnimationFrame(() => {
    const gl = document.querySelector('[data-testid="splat-canvas"] canvas');
    const r = gl.getBoundingClientRect(), sx = gl.width / r.width, sy = gl.height / r.height;
    const c = document.createElement("canvas");
    c.width = gl.width; c.height = gl.height;
    const ctx = c.getContext("2d");
    ctx.drawImage(gl, 0, 0);
    const d = ctx.getImageData(Math.max(0, Math.round((px - r.left - half) * sx)), Math.max(0, Math.round((py - r.top - rise) * sy)),
      Math.round(2 * half * sx), Math.round((rise - 10) * sy)).data;
    const m = [0, 0, 0];
    for (let i = 0; i < d.length; i += 4) for (let k = 0; k < 3; k++) m[k] += d[i + k];
    resolve(m.map((v) => Math.round(v / (d.length / 4))));
  })), { px, py, rise, half });
}

const diff = (a, b) => a.cells.reduce((acc, v, k) => acc + Math.abs(v - b.cells[k]), 0) / a.cells.length;

async function poll(fn, until, timeout) {
  const t0 = Date.now();
  let v = await fn();
  while (!until(v) && Date.now() - t0 < timeout) { await new Promise((r) => setTimeout(r, 500)); v = await fn(); }
  return v;
}

async function splatCount(page) {
  const dd = page.locator("dt", { hasText: /^Splats$/ }).locator("xpath=following-sibling::dd[1]");
  await dd.waitFor({ timeout: SCENE_TIMEOUT });
  return dd.innerText();
}

// Everything checked on one loaded viewer page. prefix: "A" (public link) or "B" (signed-in manager).
async function checkScene(page, prefix, artifact, auth, consoleErrors) {
  const received = artifact.body ? crypto.createHash("sha256").update(artifact.body).digest("hex") : null;
  record(`${prefix}1`, "HTTP artifact retrieval by the viewer (real API)", `200 application/octet-stream; ${auth}; bytes == stored fixture`,
    artifact.status === 200 && artifact.contentType === "application/octet-stream" && received === handoff.ksplatSha256 && artifact.authOk,
    `HTTP ${artifact.status} ${artifact.contentType} ${artifact.length} B; request auth: ${artifact.authSeen}; sha256=${received}`,
    { url: artifact.url.replace(/^https?:\/\/[^/]+/, ""), expectedSha256: handoff.ksplatSha256 });

  const count = await splatCount(page).catch(() => null);
  const error = await page.getByText("Could not load the reconstruction").count();
  record(`${prefix}2`, "GaussianSplats3D initialises and reports every splat (format compatibility)",
    `splat count ${handoff.splatCount.toLocaleString("en-US")}, no load error`,
    count === handoff.splatCount.toLocaleString("en-US") && error === 0, `Splats: ${count}; load errors shown: ${error}`);

  const first = await poll(() => sampleCanvas(page), (s) => s.covered > 0.05, SCENE_TIMEOUT);
  record(`${prefix}3`, "Rendered scene (pixels read back from the WebGL canvas)", "> 5 % of sampled pixels differ from the clear colour",
    first.covered > 0.05, `${(first.covered * 100).toFixed(1)} % covered on a ${first.width}x${first.height} canvas`);

  const frame = await page.getByTestId("coordinate-frame-status").innerText();
  const markers = await page.locator(".chaya-poi-marker").count();
  const red = page.locator('.chaya-poi-marker[title="Red pillar"]');
  let colour = null;
  if (await red.count()) {
    const b = await red.boundingBox();
    colour = await colourAbove(page, b.x + b.width / 2, b.y + b.height / 2);
  }
  record(`${prefix}4`, "POI overlay on the calibrated scene", "3 markers; the Red pillar marker sits under red rendered splats",
    markers === handoff.pois.length && colour && colour[0] > colour[1] + 40 && colour[0] > colour[2] + 40,
    `frame: '${frame}'; markers=${markers}; mean colour above the Red pillar marker rgb(${colour})`);
  if (await red.count()) {
    await red.click();
    const details = await page.getByTestId("poi-details").innerText().catch(() => "");
    record(`${prefix}4.1`, "Clicking a POI marker selects it", "POI details show 'Red pillar'", /Red pillar/.test(details), details.replace(/\s+/g, " ").slice(0, 80));
  }

  const box = await page.getByTestId("splat-canvas").boundingBox();
  const before = await sampleCanvas(page);
  const cx = box.x + box.width / 2, cy = box.y + box.height * 0.75;
  await page.mouse.move(cx, cy);
  await page.mouse.down();
  await page.mouse.move(cx + 220, cy - 40, { steps: 12 });
  await page.mouse.up();
  const after = await poll(() => sampleCanvas(page), (s) => diff(before, s) > 4, SCENE_TIMEOUT);
  record(`${prefix}5`, "Camera interaction (drag orbits the built-in controls)", "the next frames differ (mean cell difference > 4/255); scene still drawn",
    diff(before, after) > 4 && after.covered > 0.02, `mean cell difference ${diff(before, after).toFixed(1)}; ${(after.covered * 100).toFixed(1)} % covered after`);

  record(`${prefix}6`, "No unexpected browser console errors", "none (a logged route 404/409 is expected)",
    consoleErrors.filter((e) => !/status of 40[49]/.test(e)).length === 0, `${consoleErrors.length} console errors`, consoleErrors);
}

function watchArtifact(page, expectAuth) {
  const artifact = { status: null };
  page.on("response", async (r) => {
    if (!/\/reconstructions\/[^/]+\/artifacts\/KSPLAT$/.test(r.url())) return;
    const h = r.request().headers();
    artifact.url = r.url();
    artifact.status = r.status();
    artifact.contentType = r.headers()["content-type"];
    artifact.length = r.headers()["content-length"];
    artifact.authSeen = h.authorization ? `Authorization: ${h.authorization.split(" ")[0]}` : h["x-chaya-viewer-token"] ? "X-Chaya-Viewer-Token" : "none";
    artifact.authOk = expectAuth === "viewer-token" ? Boolean(h["x-chaya-viewer-token"]) && !h.authorization
      : /^Bearer /.test(h.authorization || "") && !h["x-chaya-viewer-token"];
    artifact.body = await r.body().catch(() => null);
  });
  return artifact;
}

(async () => {
  const browser = await chromium.launch();
  try {
    // ---------------------------------------------------------------- A. public viewing link
    const ctxA = await browser.newContext({ viewport: { width: 1400, height: 900 } });
    const page = await ctxA.newPage();
    const errorsA = [];
    page.on("console", (m) => { if (m.type() === "error") errorsA.push(m.text()); });
    const artA = watchArtifact(page, "viewer-token");
    await page.goto(`${web}/viewer?link=${encodeURIComponent(handoff.viewerLink)}`);
    await splatCount(page).catch(() => null);
    await checkScene(page, "A", artA, "X-Chaya-Viewer-Token only", errorsA);

    // Route overlay: only a real, navmesh-backed route may be drawn. None exists for the fixture (no NAVIGATION_BAKING).
    const routeResponse = page.waitForResponse((r) => r.url().endsWith("/navigation/routes"), { timeout: 30_000 }).catch(() => null);
    const selects = page.locator("select");
    const n = await selects.count();
    await selects.nth(n - 2).selectOption({ label: "Red pillar" });
    await selects.nth(n - 1).selectOption({ label: "Blue pillar" });
    const rr = await routeResponse;
    // The refusal is rendered after the response is handled; wait for it rather than reading the panel mid-update.
    await page.locator('[role="alert"]', { hasText: /No route available/ }).first().waitFor({ timeout: 15_000 }).catch(() => null);
    const panel = (await page.locator('[role="alert"]').allInnerTexts()).join(" ");
    record("A7", "Route overlay", "drawn only for a real route; otherwise the API's refusal is shown and nothing is drawn",
      rr && rr.status() === 200 ? true : rr && /No route available/.test(panel) ? "NAVIGATION_BAKING (no navmesh-backed graph for the fixture run)" : false,
      `route HTTP ${rr && rr.status()}; panel: ${panel.slice(0, 220)}`);
    await ctxA.close();

    // ---------------------------------------------------------------- B. signed-in venue manager (Keycloak PKCE)
    const ctxB = await browser.newContext({ viewport: { width: 1400, height: 900 } });
    const pageB = await ctxB.newPage();
    const errorsB = [];
    pageB.on("console", (m) => { if (m.type() === "error") errorsB.push(m.text()); });
    const artB = watchArtifact(pageB, "bearer");
    await pageB.goto(`${web}/viewer`);
    await pageB.getByRole("button", { name: "Sign in" }).click();
    await pageB.waitForSelector("#username", { timeout: 30_000 });
    await pageB.fill("#username", handoff.managerUsername);
    await pageB.fill("#password", handoff.managerPassword);
    await pageB.click("#kc-login");
    await pageB.waitForURL(/\/viewer/, { timeout: 30_000 });
    await splatCount(pageB).catch(() => null);
    await checkScene(pageB, "B", artB, "Authorization: Bearer <Keycloak JWT> only", errorsB);
    await ctxB.close();
  } finally {
    await browser.close();
    fs.writeFileSync(process.env.OUT, JSON.stringify({ kind: "FORMAT VALIDATION (synthetic fixture; not a venue reconstruction)", results }, null, 2));
  }
})().catch((e) => { console.error(e); process.exit(1); });
