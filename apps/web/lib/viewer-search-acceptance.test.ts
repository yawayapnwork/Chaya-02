import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import {
  ArtifactIntegrityError,
  KNOWN_SYNTHETIC_SHA256,
  isSyntheticFixture,
  verifyArtifact,
  verifyManifest,
} from "./artifact-integrity.ts";
import {
  IDENTITY,
  NotCalibratedError,
  isCanonical,
  similarity,
  toCanonical,
} from "./coordinate-frame.ts";
import { DEFAULT_FRAME_QUALITY, initialFrameQuality, recordFrame } from "./frame-quality.ts";
import { validateKsplat } from "./ksplat-validation.ts";
import { canNavigate, distanceNote, emptyMessage, interpretationNotes, matchNote, resultBadges } from "./search-view.ts";
import type { SearchResponse, SearchResult } from "./search-view.ts";
import { blendView, focusView, initialView, type Vec3 } from "./viewer-camera.ts";
import { GraphicsContextError, classifyViewerError } from "./viewer-errors.ts";
import { viewerResources } from "./viewer-resources.ts";
import { routeOverlay } from "./viewer-route.ts";
import type { RouteResponse } from "./navigation-api.ts";

const FIXTURE_DIR = new URL("../../../packages/contracts/fixtures/", import.meta.url);
const validFixtureBytes = readFileSync(new URL("ksplat/scene.ksplat", FIXTURE_DIR));
const validKsplatBuffer = validFixtureBytes.buffer.slice(
  validFixtureBytes.byteOffset,
  validFixtureBytes.byteOffset + validFixtureBytes.byteLength
) as ArrayBuffer;

function denseRoom(cx: number, cy: number, w: number, d: number): Vec3[] {
  const out: Vec3[] = [];
  for (let i = 0; i <= 20; i++) {
    for (let j = 0; j <= 20; j++) {
      out.push([cx - w / 2 + (w * i) / 20, cy - d / 2 + (d * j) / 20, (i % 5) * 0.5]);
    }
  }
  return out;
}

// =============================================================================
// 1. VIEWER REPAIR: CONTRACT, CHECKSUM & INTEGRITY VALIDATION
// =============================================================================

test("Viewer repair: valid Level-0 .ksplat satisfies the format contract", () => {
  const info = validateKsplat(validKsplatBuffer);
  assert.equal(info.format, "ksplat");
  assert.equal(info.compressionLevel, 0);
  assert.equal(info.sectionCount, 1);
  assert.equal(info.version, "0.1");
  assert.ok(info.splatCount > 0);
});

test("Viewer repair: corrupt or truncated .ksplat fails closed before reaching renderer", async () => {
  const validSha = createHash("sha256").update(Buffer.from(validKsplatBuffer)).digest("hex");

  const corruptBuffer = validKsplatBuffer.slice(0, validKsplatBuffer.byteLength - 20); // truncated
  const ref = {
    kind: "KSPLAT",
    contentType: "application/octet-stream",
    sizeBytes: validKsplatBuffer.byteLength,
    sha256: validSha,
    url: "/api/v1/venues/v/artifacts/KSPLAT",
    format: null,
  };

  await assert.rejects(
    async () => {
      await verifyArtifact(corruptBuffer, ref);
    },
    (err: unknown) => err instanceof ArtifactIntegrityError && err.code === "SIZE_MISMATCH",
    "must reject size mismatch on truncated payload"
  );

  // Flipped byte causing SHA-256 failure
  const tampered = new Uint8Array(validKsplatBuffer.slice(0));
  tampered[500] ^= 0xff;
  await assert.rejects(
    async () => {
      await verifyArtifact(tampered.buffer, ref);
    },
    (err: unknown) => err instanceof ArtifactIntegrityError && err.code === "CHECKSUM_MISMATCH",
    "must reject checksum mismatch on tampered payload"
  );
});

test("Viewer repair: manifest provenance fails closed on out-of-scope run", () => {
  const manifest = {
    manifestVersion: "1.0",
    coordinateSpace: "RECONSTRUCTION",
    runId: "run-target",
    scanId: "scan-target",
    artifacts: [{ kind: "KSPLAT", name: "scene.ksplat" }],
  };

  const ref = {
    kind: "KSPLAT",
    contentType: "application/octet-stream",
    sizeBytes: 1000,
    sha256: "0".repeat(64),
    url: "/artifact",
    format: null,
  };

  assert.throws(
    () => {
      verifyManifest(manifest, { runId: "run-different", ksplatRef: ref });
    },
    (err: unknown) => err instanceof ArtifactIntegrityError && err.code === "OUT_OF_SCOPE",
    "must reject manifest belonging to a different pipeline run"
  );
});

test("Viewer repair: synthetic demo datasets are identified and never passed as real venue scans", () => {
  for (const demoSha of KNOWN_SYNTHETIC_SHA256) {
    const record = {
      runQuality: "FINAL",
      scanId: "any-scan",
      artifacts: [{ sha256: demoSha }],
    };
    assert.equal(
      isSyntheticFixture(record),
      true,
      `fixture with known SHA ${demoSha} must be classified as synthetic demo data`
    );
  }

  const realRecord = {
    runQuality: "FINAL",
    scanId: "real-indoor-scan-2026",
    artifacts: [{ sha256: "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855" }],
  };
  assert.equal(
    isSyntheticFixture(realRecord),
    false,
    "authentic venue scan must not be flagged as synthetic"
  );
});

// =============================================================================
// 2. GEOMETRY & COORDINATE FRAMES: METRIC CALIBRATION INTEGRITY
// =============================================================================

test("Geometry: canonical transform maps reconstruction units to metric venue coordinates (+Z up)", () => {
  const frame = {
    id: "frame-metric-1",
    sourceRunId: "run-1",
    floorId: "floor-1",
    version: 1,
    status: "ACTIVE" as const,
    canonical: true,
    metricStatus: "METRIC" as const,
    gravityStatus: "ALIGNED" as const,
    horizontalDatum: "FLOOR_LOCAL" as const,
    scale: 0.25, // 1 reconstruction unit = 0.25 metres
    rotation: { w: 1, x: 0, y: 0, z: 0 },
    translation: { x: 10, y: 20, z: 0 },
    method: "CALIBRATION_STAGE",
    calibratedAt: "2026-11-01T12:00:00Z",
  };

  assert.equal(isCanonical(frame), true);
  const transform = toCanonical(frame);
  assert.equal(transform.scale, 0.25);
  assert.deepEqual(transform.translation, [10, 20, 0]);
});

test("Geometry: uncalibrated models refuse metric interpretation and withhold overlays", () => {
  const uncalibratedFrame = {
    id: "frame-raw-1",
    sourceRunId: "run-raw",
    floorId: "floor-1",
    version: 1,
    status: "ACTIVE" as const,
    canonical: false,
    metricStatus: "NOT_CALIBRATED" as const,
    gravityStatus: "NOT_ALIGNED" as const,
    horizontalDatum: "NONE" as const,
    scale: null,
    rotation: null,
    translation: null,
    method: "NONE",
    calibratedAt: "2026-11-01T12:00:00Z",
  };

  assert.equal(isCanonical(uncalibratedFrame), false);
  assert.throws(
    () => toCanonical(uncalibratedFrame),
    (err: unknown) => err instanceof NotCalibratedError
  );

  // Overlay refusal: routes on an uncalibrated reconstruction cannot be drawn
  const sampleRoute: RouteResponse = {
    waypoints: [
      { x: 0, y: 0, z: 0, floorId: "f1", kind: "START" },
      { x: 5, y: 0, z: 0, floorId: "f1", kind: "DESTINATION" },
    ],
    distanceMeters: 5,
    estimatedDurationSeconds: 4,
    floorTransitions: [],
    accessibilityProfile: "STANDARD",
    accessibilityConstraintsApplied: [],
  };

  const overlay = routeOverlay(sampleRoute, { floorId: "f1", frameId: null, scanVersionId: null });
  assert.equal(overlay.waypoints, null, "routes must not be drawn on an uncalibrated reconstruction");
});

// =============================================================================
// 3. CAMERA, VIEWING & PERFORMANCE
// =============================================================================

test("Camera & viewing: handles large survey datums and robust extent framing", () => {
  const pts = denseRoom(512.4, -230.1, 12, 8);
  const view = initialView(pts, true);
  assert.ok(view !== null);
  assert.deepEqual(view.up, [0, 0, 1], "canonical frame must use +Z up");
  assert.ok(Math.abs(view.target[0] - 512.4) < 0.7);
  assert.ok(Math.abs(view.target[1] + 230.1) < 0.5);

  // Focusing a POI
  const poiFocus = focusView(view.position, view.target, [515, -228, 1]);
  assert.deepEqual(poiFocus.target, [515, -228, 1]);

  // View transition interpolation
  const blended = blendView(view, poiFocus, 0.5);
  assert.ok(blended.position.every(Number.isFinite));
});

test("Rendering performance: adaptive quality scales pixel ratio based on frame budget", () => {
  let q = initialFrameQuality(2.0);
  assert.equal(q.pixelRatio, 2.0);

  // Record heavy frame times exceeding threshold (> 33ms) over full measurement window
  for (let i = 0; i < DEFAULT_FRAME_QUALITY.windowFrames; i++) {
    q = recordFrame(q, 45.0);
  }
  assert.ok(q.pixelRatio < 2.0, "render scale must step down when frames miss budget");
  assert.ok(q.pixelRatio >= DEFAULT_FRAME_QUALITY.minScale, "scale must not drop below minimum");

  // Fast frames restore pixel ratio up to device cap
  for (let i = 0; i < DEFAULT_FRAME_QUALITY.windowFrames * 10; i++) {
    q = recordFrame(q, 10.0);
  }
  assert.equal(q.pixelRatio, 2.0, "fast frames must restore full device pixel ratio");
});

test("Viewer errors & resources: WebGL failures and resource disposal are classified truthfully", () => {
  const webglErr = new GraphicsContextError("WebGL context lost", true);
  const classified = classifyViewerError(webglErr);
  assert.equal(classified.kind, "graphics");
  assert.equal(classified.title, "WebGL Unsupported");
  assert.equal(classified.retryable, false);

  const initialRenderers = viewerResources.renderers;
  viewerResources.renderers++;
  assert.equal(viewerResources.renderers, initialRenderers + 1);
  viewerResources.renderers--;
  assert.equal(viewerResources.renderers, initialRenderers);
});

// =============================================================================
// 4. SEMANTIC DISCOVERY & SEARCH INTEGRATION
// =============================================================================

test("Semantic discovery: spatial ranking gates navigation on canonical alignment", () => {
  const validResult: SearchResult = {
    poiId: "poi-valid",
    floorId: "floor-1",
    label: "Main Conference Room",
    category: "room",
    tags: ["meeting", "av"],
    x: 12.5,
    y: 8.0,
    z: 0.0,
    similarity: 0.94,
    detectionConfidence: null,
    source: "MANUAL",
    boundingBox: null,
    spatialStatus: "VALID",
  };
  assert.equal(canNavigate(validResult), true);
  assert.equal(matchNote(validResult, "embedding"), "94% match");

  const staleResult: SearchResult = {
    ...validResult,
    poiId: "poi-stale",
    spatialStatus: "STALE_FRAME",
  };
  assert.equal(canNavigate(staleResult), false, "results with stale coordinate frames must not be navigatable");

  const unboundResult: SearchResult = {
    ...validResult,
    poiId: "poi-unbound",
    spatialStatus: "UNBOUND",
  };
  assert.equal(canNavigate(unboundResult), false, "unbound spatial results cannot be navigated to");
});

test("Semantic discovery: deduplicated detections and multi-view badges are presented clearly", () => {
  const detectedObject: SearchResult = {
    poiId: "poi-dino-1",
    floorId: "floor-1",
    label: "leather sofa",
    category: "furniture",
    tags: ["seating"],
    x: 4.2,
    y: 6.1,
    z: 0.4,
    similarity: 0.82,
    detectionConfidence: 0.89,
    source: "AUTO_DETECTED",
    boundingBox: { x0: 100, y0: 120, x1: 300, y1: 250 },
    spatialStatus: "VALID",
    localizationStatus: "MULTI_VIEW",
    duplicateIds: ["poi-dino-2", "poi-dino-3"],
    distanceMeters: 6.5,
    distanceKind: "ROUTE",
  };

  const badges = resultBadges(detectedObject);
  assert.ok(badges.includes("detected"));
  assert.ok(badges.includes("3 detections merged"));
  assert.equal(distanceNote(detectedObject), "6.5 m walk");
});

test("Semantic discovery: truthful empty state and closest match separation without fabricating pins", () => {
  const emptyQueryResponse: SearchResponse = {
    query: "swimming pool",
    matchType: "embedding",
    results: [],
    relevance: "FILTERED",
    closestMatches: [
      {
        poiId: "poi-fountain",
        floorId: "floor-1",
        label: "Decorative Water Fountain",
        category: "decoration",
        tags: ["water"],
        x: 0,
        y: 0,
        z: 0,
        similarity: 0.54,
        detectionConfidence: null,
        source: "MANUAL",
        boundingBox: null,
      },
    ],
    interpretation: {
      normalized: "swimming pool",
      subject: "swimming pool",
      lexicalTerms: ["swimming", "pool"],
      synonymGroups: [],
      corrections: [],
      targetKind: "DESCRIPTION",
      spatialModifier: "NONE",
      spatialModifierApplied: false,
      spatialNote: null,
      accessibilityRequired: false,
    },
  };

  const message = emptyMessage(emptyQueryResponse);
  assert.equal(message, "Nothing matching “swimming pool” here. Closest:");
  assert.equal(emptyQueryResponse.results.length, 0, "must not inject fake results into the results array");
});
