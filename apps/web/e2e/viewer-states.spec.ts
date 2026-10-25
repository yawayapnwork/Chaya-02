import { createHash } from "node:crypto";
import { test, expect, type Page } from "@playwright/test";
import {
  FIXTURE, FLOOR_ID, KSPLAT, KSPLAT_FORMAT, SCENE_TIMEOUT, VENUE_ID, colourAbove, fixtureFrame, fixturePois, mockJson, mockViewerApi, sampleCanvas,
} from "./viewer-fixture";
import { mockOidc } from "./oidc-fixture";

// Viewer states, scan-version switching and resource release, on the FORMAT VALIDATION fixture (e2e/viewer-fixture.ts:
// the production exporter's .ksplat of a synthetic scene, not a venue). The JSON API is mocked with the shapes the API
// returns; every scene is loaded, rendered and disposed by the real GaussianSplats3D viewer in Chromium.

const V1 = "aaaaaaaa-0000-0000-0000-000000000001";
const V2 = "aaaaaaaa-0000-0000-0000-000000000002";
const R1 = "bbbbbbbb-0000-0000-0000-000000000001";
const R2 = "bbbbbbbb-0000-0000-0000-000000000002";
const F2 = "cccccccc-0000-0000-0000-000000000002";
/** Version 2's frame places the model 1.5 m further along +X than version 1's; its POIs are recorded in that frame. */
const SHIFT_X = 1.5;

/** Version 2's model: the fixture with one byte of one splat changed, so it is a different, still valid .ksplat. */
const KSPLAT_V2 = (() => {
  const b = Buffer.from(KSPLAT);
  b[6000] ^= 0x10;
  return b;
})();
const sha = (b: Buffer) => createHash("sha256").update(b).digest("hex");

const splatsCell = (page: Page) => page.locator("dt", { hasText: /^Splats$/ }).locator("xpath=following-sibling::dd[1]");

async function startPublicLink(page: Page) {
  const expiresAt = new Date(Date.now() + 60_000).toISOString();
  await mockJson(page, "**/mock-api/api/v1/public/viewer-token", { token: "cvt_test", expiresAt, venueId: VENUE_ID });
  await mockJson(page, `**/mock-api/api/v1/venues/${VENUE_ID}/floors`, [{ id: FLOOR_ID, level: 0, name: "Ground Floor" }]);
}

/** Two finalized scan versions of the floor, each pinning its own .ksplat and recording its own frame and POIs. */
async function mockTwoVersions(page: Page) {
  await startPublicLink(page);
  const generatedAt = new Date().toISOString();
  const version = (runId: string, scanVersionId: string, versionNumber: number, current: boolean, parentVersionId: string | null) => ({
    runId, floorId: FLOOR_ID, generatedAt, runStatus: "SUCCEEDED", runQuality: "FINAL", scanVersionId, versionNumber, parentVersionId, current,
  });
  await mockJson(page, `**/mock-api/api/v1/venues/${VENUE_ID}/floors/${FLOOR_ID}/reconstructions`, [
    version(R2, V2, 2, true, V1), version(R1, V1, 1, false, null),
  ]);
  const frame1 = fixtureFrame(R1);
  const frame2 = { ...fixtureFrame(R2), id: F2, version: 2, translation: { ...frame1.translation, x: frame1.translation.x + SHIFT_X } };
  const downloads: string[] = [];
  for (const [runId, versionId, n, bytes, frame] of [[R1, V1, 1, KSPLAT, frame1], [R2, V2, 2, KSPLAT_V2, frame2]] as const) {
    const url = `/api/v1/venues/${VENUE_ID}/scan-versions/${versionId}/artifacts/KSPLAT`;
    await mockJson(page, `**/mock-api/api/v1/venues/${VENUE_ID}/reconstructions/${runId}`, {
      ...version(runId, versionId, n, versionId === V2, versionId === V2 ? V1 : null), scanId: `scan-${n}`,
      artifacts: [{ kind: "KSPLAT", contentType: "application/octet-stream", sizeBytes: bytes.length, sha256: sha(bytes), url, format: KSPLAT_FORMAT }],
      coordinateFrame: frame,
    });
    await page.route(`**/mock-api${url}`, (route) => {
      downloads.push(versionId);
      return route.fulfill({ status: 200, contentType: "application/octet-stream", body: bytes, headers: { "content-length": String(bytes.length) } });
    });
    const pois = fixturePois(frame.id).map((p) => ({
      ...p, id: `${p.id}-v${n}`, x: p.x + (versionId === V2 ? SHIFT_X : 0), scanVersionId: versionId, current: true,
    }));
    await page.route((u) => u.pathname.endsWith(`/venues/${VENUE_ID}/pois`) && u.searchParams.get("scanVersionId") === versionId,
      (route) => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(pois) }));
  }
  return downloads;
}

async function expectRendered(page: Page) {
  await expect(splatsCell(page)).toHaveText(FIXTURE.splat_count.toLocaleString("en-US"), { timeout: SCENE_TIMEOUT });
  // "Loaded" is reported only after a frame with the splats was drawn: the canvas already shows them.
  expect((await sampleCanvas(page)).covered).toBeGreaterThan(0.01);
}

/** The Red pillar's marker sits on red rendered splats: the POI and the model are in the same frame. */
async function expectRedPillarAligned(page: Page) {
  const red = page.locator('.chaya-poi-marker[title="Red pillar"]');
  await expect(red).toHaveCount(1);
  await expect.poll(async () => {
    const box = (await red.boundingBox())!;
    const [r, g, b] = await colourAbove(page, box.x + box.width / 2, box.y + box.height / 2);
    return r - Math.max(g, b);
  }, { timeout: SCENE_TIMEOUT }).toBeGreaterThan(40);
}

test("switching scan versions loads each version's own model, frame and POIs, and keeps them aligned", async ({ page }) => {
  test.setTimeout(4 * SCENE_TIMEOUT);
  const downloads = await mockTwoVersions(page);
  await page.goto("/viewer?link=good-secret");

  await expect(page.getByTestId("scan-version")).toHaveText("v2 (re-scan of v1)");
  await expectRendered(page);
  await expectRedPillarAligned(page);
  expect(downloads).toEqual([V2]);

  await page.getByRole("combobox", { name: "Reconstruction" }).selectOption(R1);
  await expect(page.getByTestId("scan-version")).toHaveText("v1");
  await expectRendered(page);
  await expectRedPillarAligned(page);
  expect(downloads).toEqual([V2, V1]);
  await expect(page.locator(".chaya-poi-marker")).toHaveCount(FIXTURE.pois_by_construction.length); // only v1's POIs
});

test("switching models repeatedly releases every renderer, viewer and object URL, and does not download again", async ({ page }) => {
  test.setTimeout(6 * SCENE_TIMEOUT);
  const downloads = await mockTwoVersions(page);
  await page.goto("/viewer?link=good-secret");
  await expectRendered(page);
  const picker = page.getByRole("combobox", { name: "Reconstruction" });
  for (let i = 0; i < 6; i++) {
    await picker.selectOption(i % 2 === 0 ? R1 : R2);
    await expect(page.getByTestId("scan-version")).toHaveText(i % 2 === 0 ? "v1" : "v2 (re-scan of v1)");
    await expectRendered(page);
  }
  // Disposal is asynchronous (the viewer waits for its sort worker first): poll until only the scene on screen remains.
  await expect.poll(() => page.evaluate(() => (window as unknown as { __chayaViewerResources: unknown }).__chayaViewerResources),
    { timeout: SCENE_TIMEOUT }).toEqual({ renderers: 1, viewers: 1, objectUrls: 1 });
  await expect(page.locator('[data-testid="splat-canvas"] canvas')).toHaveCount(1);
  expect(downloads.sort()).toEqual([V1, V2]); // each verified model was downloaded once
});

test("a lost graphics context is reported, and Retry rebuilds the scene without downloading the model again", async ({ page }) => {
  test.setTimeout(3 * SCENE_TIMEOUT);
  const artifactPath = await mockViewerApi(page, "dddddddd-0000-0000-0000-000000000001", { calibrated: true });
  let downloads = 0;
  page.on("request", (r) => {
    if (r.url().endsWith(artifactPath)) downloads++;
  });
  await page.goto("/viewer?link=good-secret");
  await expectRendered(page);

  await page.evaluate(() => {
    const canvas = document.querySelector<HTMLCanvasElement>('[data-testid="splat-canvas"] canvas')!;
    const gl = (canvas.getContext("webgl2") ?? canvas.getContext("webgl")) as WebGLRenderingContext;
    gl.getExtension("WEBGL_lose_context")!.loseContext();
  });
  const state = page.getByTestId("viewer-state");
  await expect(state).toHaveAttribute("data-state", "graphics");
  await expect(page.getByText("Splats", { exact: true })).toHaveCount(0); // not "loaded" while nothing can be drawn
  await state.getByRole("button", { name: "Retry" }).click();
  await expectRendered(page);
  expect(downloads).toBe(1);
});

test("a refused artifact download is shown as unauthorized, without a retry and without a scene", async ({ page }) => {
  test.setTimeout(SCENE_TIMEOUT);
  const artifactPath = await mockViewerApi(page, "dddddddd-0000-0000-0000-000000000002", { calibrated: true });
  await page.route(`**/mock-api${artifactPath}`, (route) => route.fulfill({ status: 403, body: "" }));
  await page.goto("/viewer?link=good-secret");
  const state = page.getByTestId("viewer-state");
  await expect(state).toHaveAttribute("data-state", "unauthorized");
  await expect(state).toContainText("Not authorized");
  await expect(state.getByRole("button")).toHaveCount(0);
  await expect(page.getByTestId("splat-canvas")).toHaveCount(0);
});

test("a venue the session may not see is rejected as unauthorized, and nothing is loaded", async ({ page }) => {
  await startPublicLink(page);
  await mockJson(page, `**/mock-api/api/v1/venues/${VENUE_ID}/floors/${FLOOR_ID}/reconstructions`,
    { status: 403, code: "FORBIDDEN", detail: "no access to this venue" }, 403);
  let captures = 0;
  page.on("request", (r) => {
    if (r.url().includes("/captures")) captures++;
  });
  await page.goto("/viewer?link=good-secret");
  const state = page.getByTestId("viewer-state");
  await expect(state).toHaveAttribute("data-state", "unauthorized");
  await expect(state).toContainText("no access to this venue");
  await expect(page.getByTestId("splat-canvas")).toHaveCount(0);
  expect(captures).toBe(0); // a public link never asks for raw captures
});

test("a floor still processing shows its pipeline progress, not just 'no reconstruction'", async ({ page }) => {
  await mockOidc(page, { session: false }); // the explicit sign-in; silent restore: ui-audit.spec.ts
  await mockJson(page, "**/mock-api/api/v1/venues", [{ id: VENUE_ID, name: "Test Hall", slug: "test-hall" }]);
  await mockJson(page, `**/mock-api/api/v1/venues/${VENUE_ID}/floors`, [{ id: FLOOR_ID, level: 0, name: "Ground Floor" }]);
  await mockJson(page, `**/mock-api/api/v1/venues/${VENUE_ID}/floors/${FLOOR_ID}/reconstructions`, []);
  const startedAt = new Date().toISOString();
  await mockJson(page, `**/mock-api/api/v1/venues/${VENUE_ID}/captures`, [
    { id: "cap-1", venueId: VENUE_ID, floorId: FLOOR_ID, operatorId: "op", status: "PROCESSING", device: {}, startedAt, endedAt: null,
      durationSeconds: null, qualityState: "OK", failureCode: null, failureMessage: null, mediaCount: 3, acceptedMediaCount: 3 },
  ]);
  const stage = (s: string, state: string) => ({ stage: s, state, attempts: state === "PENDING" ? 0 : 1, lastRun: null });
  await mockJson(page, `**/mock-api/api/v1/venues/${VENUE_ID}/captures/cap-1/processing`, {
    captureId: "cap-1", captureStatus: "PROCESSING", scanId: "scan-1", jobs: [],
    run: { id: "run-1", status: "RUNNING", quality: null, privacyEnabled: true, timeBudgetSeconds: 3600, startedAt, deadlineAt: startedAt,
      finishedAt: null, failureStage: null, failureCode: null, failureMessage: null, retryable: false,
      stages: [stage("INPUT_VALIDATION", "SUCCEEDED"), stage("POSE_ESTIMATION", "RUNNING"), stage("SPLAT_RECONSTRUCTION", "PENDING"),
        stage("SEMANTIC_INDEXING", "NOT_RUN")] },
  });
  await page.goto("/viewer");
  await page.getByRole("button", { name: "Sign in" }).click();
  const panel = page.getByTestId("no-reconstruction");
  await expect(panel).toHaveAttribute("data-state", "processing");
  await expect(panel.getByTestId("processing-progress")).toContainText("Camera pose estimation (1 of 3 stages)");
  await expect(panel.getByRole("progressbar")).toHaveAttribute("aria-valuenow", "33");
});

test("a partial reconstruction is prominently labeled as partial and never presented as final", async ({ page }) => {
  test.setTimeout(SCENE_TIMEOUT);
  await startPublicLink(page);
  const generatedAt = new Date().toISOString();
  const runId = "partial-run-0001";
  const scanVersionId = "partial-sv-0001";
  const artifactPath = `/api/v1/venues/${VENUE_ID}/scan-versions/${scanVersionId}/artifacts/KSPLAT`;

  await mockJson(page, `**/mock-api/api/v1/venues/${VENUE_ID}/floors/${FLOOR_ID}/reconstructions`, [
    {
      runId,
      floorId: FLOOR_ID,
      generatedAt,
      runStatus: "SUCCEEDED",
      runQuality: "PARTIAL",
      scanVersionId,
      versionNumber: 1,
      parentVersionId: null,
      current: true,
    },
  ]);

  const frame = fixtureFrame(runId);
  await mockJson(page, `**/mock-api/api/v1/venues/${VENUE_ID}/reconstructions/${runId}`, {
    runId,
    scanId: "scan-partial",
    floorId: FLOOR_ID,
    generatedAt,
    runStatus: "SUCCEEDED",
    runQuality: "PARTIAL",
    scanVersionId,
    versionNumber: 1,
    parentVersionId: null,
    artifacts: [
      {
        kind: "KSPLAT",
        contentType: "application/octet-stream",
        sizeBytes: KSPLAT.length,
        sha256: sha(KSPLAT),
        url: artifactPath,
        format: KSPLAT_FORMAT,
      },
    ],
    coordinateFrame: frame,
  });

  await page.route(`**/mock-api${artifactPath}`, (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/octet-stream",
      body: KSPLAT,
      headers: { "content-length": String(KSPLAT.length) },
    })
  );

  await mockJson(page, `**/mock-api/api/v1/venues/${VENUE_ID}/pois`, []);

  await page.goto("/viewer?link=good-secret");

  // Partial notice must be prominently visible
  const partialNotice = page.getByTestId("reconstruction-partial");
  await expect(partialNotice).toBeVisible({ timeout: SCENE_TIMEOUT });
  await expect(partialNotice).toContainText("Partial Reconstruction");
  await expect(partialNotice).toContainText("Reduced coverage");

  // Must NEVER present as final
  await expect(page.getByTestId("reconstruction-final")).toHaveCount(0);

  // Quality dl field shows PARTIAL
  const quality = page.getByTestId("reconstruction-quality");
  await expect(quality).toContainText("PARTIAL");
});

test("a synthetic demonstration fixture is prominently identified and not disguised as a venue", async ({ page }) => {
  test.setTimeout(SCENE_TIMEOUT);
  await mockViewerApi(page, "synthetic-run-0001", { calibrated: true });
  await page.goto("/viewer?link=good-secret");

  const demoNotice = page.getByTestId("synthetic-demonstration-notice");
  await expect(demoNotice).toBeVisible({ timeout: SCENE_TIMEOUT });
  await expect(demoNotice).toContainText("Synthetic Demonstration Data");
  await expect(demoNotice).toContainText("not an actual venue reconstruction");

  const provenance = page.getByTestId("reconstruction-provenance");
  await expect(provenance).toContainText("Synthetic Fixture (Demo)");
});
