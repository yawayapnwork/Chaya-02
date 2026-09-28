import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import path from "node:path";
import type { Page } from "@playwright/test";

// FORMAT VALIDATION fixture for the viewer specs: packages/contracts/fixtures/viewer-scene, a deterministic synthetic
// Gaussian scene (a 6 m x 4 m checkerboard floor, red/green/blue 2 m pillars) exported by the worker's production
// ARTIFACT_GENERATION stage (tests/unit/test_viewer_scene_fixture.py pins the bytes to that stage). It is NOT a
// reconstruction of any venue; real venue reconstruction validation needs a GPU worker (docs/E2E_VALIDATION.md).
//
// These specs run without a backend: the JSON API is mocked with page.route. The .ksplat response carries the
// fixture's exact bytes, which the viewer's real download path (lib/reconstruction-api.fetchArtifact) receives and the
// real GaussianSplats3D parses and renders. Real HTTP retrieval, storage and authorization of the same bytes through
// the running API are checked separately against the full stack (scripts/e2e/viewer_format_check.cjs).

const DIR = path.resolve(process.cwd(), "../../packages/contracts/fixtures/viewer-scene");
export const KSPLAT = readFileSync(path.join(DIR, "scene.ksplat"));
export const KSPLAT_SHA256 = createHash("sha256").update(KSPLAT).digest("hex");
export const FIXTURE = JSON.parse(readFileSync(path.join(DIR, "fixture.json"), "utf8")) as {
  note: string;
  splat_count: number;
  reconstruction_to_canonical: { scale: number; rotation_wxyz: [number, number, number, number]; translation: [number, number, number] };
  pois_by_construction: { label: string; canonical: [number, number, number] }[];
};

/** Loading, sorting and drawing ~3,700 splats is CPU work in headless Chromium (software WebGL): about 5 s alone, but
 * well over a minute when several such pages run in parallel. Scene waits use this budget, never a shorter one. */
export const SCENE_TIMEOUT = 60_000;

export const VENUE_ID = "11111111-1111-1111-1111-111111111111";
export const FLOOR_ID = "22222222-2222-2222-2222-222222222222";
export const FRAME_ID = "55555555-5555-5555-5555-555555555555";

export async function mockJson(page: Page, urlPattern: string, body: unknown, status = 200) {
  await page.route(urlPattern, (route) => route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) }));
}

/** The fixture's own reconstruction -> canonical similarity, in the API's FrameView shape. It is exact by construction
 * of the synthetic scene -- contract data for the viewer, not a calibration of anything real. */
export function fixtureFrame(runId: string) {
  const t = FIXTURE.reconstruction_to_canonical;
  const [w, x, y, z] = t.rotation_wxyz;
  return {
    id: FRAME_ID, sourceRunId: runId, floorId: FLOOR_ID, version: 1, status: "ACTIVE", canonical: true, metricStatus: "METRIC",
    gravityStatus: "ALIGNED", horizontalDatum: "VENUE_CONTROL_POINTS", scale: t.scale, rotation: { w, x, y, z },
    translation: { x: t.translation[0], y: t.translation[1], z: t.translation[2] }, method: "CONTROL_POINTS",
    calibratedAt: new Date().toISOString(),
  };
}

export function fixturePois(frameId: string | null) {
  return FIXTURE.pois_by_construction.map((p, i) => ({
    id: `poi-${i + 1}`, floorId: FLOOR_ID, spaceId: null, version: 1, label: p.label, category: "fixture",
    description: "format-fixture POI at a pillar base", tags: [], x: p.canonical[0], y: p.canonical[1], z: p.canonical[2],
    coordinateFrameId: frameId, frameStatus: frameId ? "CURRENT" : "UNBOUND",
  }));
}

/** Mocks the public-link viewer API for one reconstruction whose KSPLAT is the fixture. Returns the artifact path. */
export async function mockViewerApi(page: Page, runId: string, opts: { calibrated: boolean; runStatus?: string }) {
  const expiresAt = new Date(Date.now() + 60_000).toISOString();
  const generatedAt = new Date().toISOString();
  const artifactPath = `/api/v1/venues/${VENUE_ID}/reconstructions/${runId}/artifacts/KSPLAT`;
  const runStatus = opts.runStatus ?? "SUCCEEDED";
  const runQuality = runStatus === "SUCCEEDED" ? "FINAL" : null;
  await mockJson(page, "**/mock-api/api/v1/public/viewer-token", { token: "cvt_test", expiresAt, venueId: VENUE_ID });
  await mockJson(page, `**/mock-api/api/v1/venues/${VENUE_ID}/floors`, [{ id: FLOOR_ID, level: 0, name: "Ground Floor" }]);
  await mockJson(page, `**/mock-api/api/v1/venues/${VENUE_ID}/floors/${FLOOR_ID}/reconstructions`, [
    { runId, floorId: FLOOR_ID, generatedAt, runStatus, runQuality },
  ]);
  await mockJson(page, `**/mock-api/api/v1/venues/${VENUE_ID}/pois`, fixturePois(opts.calibrated ? FRAME_ID : null));
  await mockJson(page, `**/mock-api/api/v1/venues/${VENUE_ID}/reconstructions/${runId}`, {
    runId, scanId: "scan-1", floorId: FLOOR_ID, generatedAt, runStatus, runQuality,
    artifacts: [{ kind: "KSPLAT", contentType: "application/octet-stream", sizeBytes: KSPLAT.length, sha256: KSPLAT_SHA256, url: artifactPath }],
    coordinateFrame: opts.calibrated ? fixtureFrame(runId) : null,
  });
  await page.route(`**/mock-api${artifactPath}`, (route) =>
    route.fulfill({ status: 200, contentType: "application/octet-stream", body: KSPLAT, headers: { "content-length": String(KSPLAT.length) } }));
  return artifactPath;
}

export interface CanvasSample {
  width: number;
  height: number;
  /** Fraction of sampled pixels that differ from the empty clear colour. */
  covered: number;
  /** Coarse per-cell mean colour, to compare two frames. */
  cells: number[];
}

/** Reads the viewer's WebGL canvas from inside the page, right after the viewer's own frame rendered (a
 * requestAnimationFrame callback registered after its render loop's), so the drawing buffer still holds that frame. */
export async function sampleCanvas(page: Page): Promise<CanvasSample> {
  return page.evaluate(
    () =>
      new Promise<CanvasSample>((resolve, reject) => {
        requestAnimationFrame(() => {
          const gl = document.querySelector<HTMLCanvasElement>('[data-testid="splat-canvas"] canvas');
          if (!gl) return reject(new Error("no WebGL canvas"));
          const w = gl.width;
          const h = gl.height;
          const c = document.createElement("canvas");
          c.width = w;
          c.height = h;
          const ctx = c.getContext("2d")!;
          ctx.drawImage(gl, 0, 0);
          const data = ctx.getImageData(0, 0, w, h).data;
          const bg = [data[0], data[1], data[2]]; // corner pixel: the clear colour (the scene sits in the middle)
          let covered = 0;
          let n = 0;
          const G = 8;
          const sums = new Array(G * G * 3).fill(0);
          const counts = new Array(G * G).fill(0);
          for (let y = 0; y < h; y += 4) {
            for (let x = 0; x < w; x += 4) {
              const i = (y * w + x) * 4;
              n++;
              if (Math.abs(data[i] - bg[0]) + Math.abs(data[i + 1] - bg[1]) + Math.abs(data[i + 2] - bg[2]) > 24) covered++;
              const cell = Math.floor((y / h) * G) * G + Math.floor((x / w) * G);
              counts[cell]++;
              for (let k = 0; k < 3; k++) sums[cell * 3 + k] += data[i + k];
            }
          }
          resolve({ width: w, height: h, covered: covered / n, cells: sums.map((s, k) => s / Math.max(1, counts[Math.floor(k / 3)])) });
        });
      }),
  );
}

/** Mean absolute per-channel difference between two samples' cell colours. */
export const frameDifference = (a: CanvasSample, b: CanvasSample) =>
  a.cells.reduce((acc, v, k) => acc + Math.abs(v - b.cells[k]), 0) / a.cells.length;

/** Mean colour of the rendered pixels in a small box just above a screen point (page coordinates). */
export async function colourAbove(page: Page, px: number, py: number, rise = 40, half = 6): Promise<[number, number, number]> {
  return page.evaluate(
    ({ px, py, rise, half }) =>
      new Promise<[number, number, number]>((resolve, reject) => {
        requestAnimationFrame(() => {
          const gl = document.querySelector<HTMLCanvasElement>('[data-testid="splat-canvas"] canvas');
          if (!gl) return reject(new Error("no WebGL canvas"));
          const r = gl.getBoundingClientRect();
          const sx = gl.width / r.width;
          const sy = gl.height / r.height;
          const c = document.createElement("canvas");
          c.width = gl.width;
          c.height = gl.height;
          const ctx = c.getContext("2d")!;
          ctx.drawImage(gl, 0, 0);
          const x0 = Math.round((px - r.left - half) * sx);
          const y0 = Math.round((py - r.top - rise) * sy);
          const d = ctx.getImageData(Math.max(0, x0), Math.max(0, y0), Math.round(2 * half * sx), Math.round((rise - 10) * sy)).data;
          const m = [0, 0, 0];
          for (let i = 0; i < d.length; i += 4) for (let k = 0; k < 3; k++) m[k] += d[i + k];
          const n = d.length / 4;
          resolve([m[0] / n, m[1] / n, m[2] / n]);
        });
      }),
    { px, py, rise, half },
  );
}
