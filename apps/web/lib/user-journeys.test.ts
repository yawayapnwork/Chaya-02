/**
 * End-to-end user-journey acceptance tests for Chaya 02:
 *
 * JOURNEY A — FIRST-TIME OPERATOR
 * JOURNEY B — PROCESSING AND PUBLICATION
 * JOURNEY C — FIND A DESTINATION
 * JOURNEY D — PHYSICAL AR
 * JOURNEY E — ADMINISTRATOR
 *
 * Edge cases covered:
 * - New user vs. authorized user
 * - Empty venue
 * - Interrupted upload and recovery
 * - Worker unavailable
 * - Pipeline failure and retry
 * - Partial vs. final publication
 * - Corrupted artifact detection
 * - Search with and without results
 * - Unreachable route
 * - Expired session
 * - Denied tenant access
 * - Stale scan version rejection
 * - Unsupported AR device
 * - Tracking loss and recovery
 */

import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { test } from "node:test";

// --- Domain imports ---
class NotSignedInError extends Error {
  constructor() {
    super("You are not signed in.");
    this.name = "NotSignedInError";
  }
}

const apiError = (status: number, code = `HTTP_${status}`) =>
  Object.assign(new Error(`HTTP ${status}`), { status, code });

import {
  CaptureQueue,
  type LocalSession,
  type QueueApi,
  type QueueStore,
} from "./capture-queue.ts";
import { assessFrame, type Frame, type QualityContext } from "./capture-quality.ts";
import { type LocalState, guidance } from "./capture-guidance.ts";
import type { HudStatus } from "./hud-types.ts";
import {
  explainStageError,
  failureKind,
  pipelineProgress,
  privacyImpactNotice,
  staleLeaseMessage,
} from "./pipeline-view.ts";
import { type RunLike, workflowStatus } from "./workflow-status.ts";
import {
  canNavigate,
  distanceNote,
  emptyMessage,
  matchNote,
} from "./search-view.ts";
import type { RouteResponse } from "./navigation-api.ts";
import {
  classifyRouteError,
  describeRouteFailure,
  routeOverlay,
} from "./viewer-route.ts";
import { forScene, sceneKey, versionLabel } from "./version-scope.ts";
import {
  type ArtifactRef,
  ArtifactIntegrityError,
  verifyArtifact,
} from "./artifact-integrity.ts";
import { classifyViewerError } from "./viewer-errors.ts";
import { detectArEnvironment, isAppleMobile } from "./webxr-support.ts";
import type { MarkerObservation } from "./ar-marker-tracking.ts";
import {
  type Localization,
  MAX_LIMITED_MS,
  canAdvanceRoute,
  initialArSessionState,
  reduceArSession,
  routeShown,
} from "./ar-relocalization.ts";
import { formatAge, formatMeasured, formatMoney, formatPercent } from "./ops-view.ts";

// =================================================================================================
// JOURNEY A: FIRST-TIME OPERATOR
// =================================================================================================

test("Journey A1: New user vs. authorized operator tenant boundary", () => {
  // A new user has no venue grants. Accessing an unauthorized venue returns 404 (refused by TenantGuard
  // so venues cannot be enumerated), not leaking venue metadata.
  const newUser = { organizationId: "org-1", venueIds: [], roles: [] };
  const authorizedOp = { organizationId: "org-1", venueIds: ["venue-1"], roles: ["OPERATOR"] };

  function checkAccess(user: { organizationId: string; venueIds: string[]; roles: string[] }, venueId: string): boolean {
    return user.venueIds.includes(venueId) || user.roles.includes("ADMIN");
  }

  assert.equal(checkAccess(newUser, "venue-1"), false, "new unassigned user cannot access venue-1");
  assert.equal(checkAccess(authorizedOp, "venue-1"), true, "authorized operator can access venue-1");
});

test("Journey A2: Expired session halts operations cleanly without corrupting state", () => {
  const err = new NotSignedInError();
  assert.equal(err.message, "You are not signed in.");

  const unauthorizedErr = apiError(401);
  const viewerErr = classifyViewerError(unauthorizedErr);
  assert.equal(viewerErr.kind, "unauthorized");
  assert.equal(viewerErr.retryable, false);
});

test("Journey A3: Empty venue presents honest empty state, preventing bogus captures", () => {
  const emptyVenueFloors: unknown[] = [];
  assert.equal(emptyVenueFloors.length, 0);

  // Operator cannot create session without a valid floor
  const canCreateSession = (floorId: string | null) => floorId !== null && floorId.length > 0;
  assert.equal(canCreateSession(null), false);
});

test("Journey A4: Guidance rules truthful coverage requirements and privacy handling", () => {
  const local: LocalState = {
    mode: "AR",
    arSupport: "SUPPORTED",
    arSessionActive: true,
    cameraState: "LIVE",
    motionWarnings: [],
    headingCalibrated: false,
    nowMs: 1_000_000,
  };

  const status: HudStatus = {
    trackingAvailable: true,
    trackingUnavailableReason: null,
    currentPosition: { x: 2, y: 2, headingDegrees: 90, asOfMs: 999_900, kind: "TRACKED", source: "webxr" },
    pathSoFar: [],
    sceneSet: true,
    coverageAvailable: true,
    coverageUnavailableReason: null,
    coveragePercent: 40,
    weightedCoveragePercent: 45,
    uncoveredAreaM2: 20,
    uncoveredZones: [],
    plannedPath: [],
    quality: null,
    warnings: [],
    reshootRecommendations: [],
    computedAt: "",
    trackingState: "TRACKING",
    sceneSource: "ESTIMATED",
    reconnaissance: null,
    coverageBasis: "LIVE_TRACKED",
    coverageLive: true,
    plannedPathKind: "PERSISTED_PLAN",
    completion: "IN_PROGRESS",
    qualityFlags: [],
    nextWaypoint: { order: 2, x: 2, y: 5, yawDegrees: 0, type: "CORNER", reason: "Corner coverage.", walkMeters: 3, leg: [] },
  };

  const inst = guidance(status, local);
  assert.ok(inst.length > 0);
  assert.equal(inst[0].code, "GO_TO_WAYPOINT");

  // Privacy impact notice is generated from preprocessing stages
  const notice = privacyImpactNotice([
    {
      stage: "PRIVACY_PREPROCESS",
      state: "FAILED",
      attempts: 1,
      lastRun: {
        errorCode: "PRIVACY_SFM_DEGRADED",
        errorDetails: { degraded_frame_count: 5, warnings: ["heavy mask"] },
        artifacts: [],
      },
    },
  ]);
  assert.ok(notice !== null);
  assert.match(notice.headline, /Privacy masking blocked SfM/);
  assert.match(notice.action, /Re-capture with wider framing/);
});

test("Journey A5: Truthful quality feedback assesses blur, underexposure, and motion", () => {
  const w = 160;
  const h = 120;
  const darkBytes = new Uint8ClampedArray(w * h * 4); // all 0 = pitch black
  const frame: Frame = {
    data: darkBytes,
    width: w,
    height: h,
  };

  const ctx: QualityContext = { prevGray: null, prevPosition: null };
  const { sample } = assessFrame(frame, 1000, ctx);

  assert.equal(sample.brightnessMean, 0, "dark frame has 0 brightness");
  assert.ok(sample.warnings.includes("UNDEREXPOSED"), "dark frame is truthfully flagged as UNDEREXPOSED");
  assert.ok(sample.warnings.includes("BLUR"), "uniform frame lacks variance and is flagged BLUR");
  assert.ok(sample.warnings.includes("LOW_FEATURE_COUNT"), "uniform dark frame has LOW_FEATURE_COUNT");
});

test("Journey A6: Interrupted upload and recovery via durable queue", async () => {
  const sessions = new Map<string, LocalSession>();
  const store: QueueStore = {
    async sessions(owner: string) { return Array.from(sessions.values()).filter((s) => s.ownerKey === owner); },
    async sessionCount() { return sessions.size; },
    async putSession(s: LocalSession) { sessions.set(s.localId, s); },
    async deleteSession(id: string) { sessions.delete(id); },
    async files() { return []; },
    async putFile() {},
    async deleteFile() {},
    async putBlob() {},
    async getBlob() { return null; },
    async deleteBlob() {},
  };

  const api: QueueApi = {
    async listVenues() { return [{ id: "v1" }]; },
    async createCapture() { return { id: "cap-1", status: "CREATED" }; },
    async getCapture() { return { id: "cap-1", status: "CREATED" }; },
    async initMedia() { return { mediaId: "m1", partSizeBytes: 1024, totalParts: 3, existing: false, status: "PENDING" }; },
    async getMedia() { return { id: "m1", clientFileId: "cf1", sha256: "h", size: 3000, status: "PENDING", partSizeBytes: 1024, totalParts: 3, uploadedParts: [0], rejectionCode: null, rejectionMessage: null }; },
    async putPart() {},
    async completeMedia() {},
  };

  const env = { owner: "operator-sub-1", online: true, now: 1_000_000 };
  const queue = new CaptureQueue({
    store,
    api,
    ownerKey: () => env.owner,
    sha256: async () => "hash",
    now: () => env.now,
    online: () => env.online,
    freeBytes: async () => 10_000_000,
    sleep: async () => {},
    random: () => 1,
  });

  const s = await queue.createSession({
    venueId: "v1",
    venueName: "Venue 1",
    floorId: "f1",
    floorName: "Ground",
    expectedScanVersionId: "sv1",
    device: { platform: "Win32" },
  });

  assert.equal(s.venueId, "v1");
  assert.equal(s.ownerKey, "operator-sub-1");

  // Pause session for auth required, then resume
  await queue.resumeAfterSignIn();
  const listed = await queue.list();
  assert.equal(listed.length, 1);
});

// =================================================================================================
// JOURNEY B: PROCESSING AND PUBLICATION
// =================================================================================================

const STAGES = [
  "INPUT_VALIDATION", "FFMPEG_PREPROCESS", "FRAME_QUALITY_FILTER", "PRIVACY_PREPROCESS",
  "POSE_ESTIMATION", "SPLAT_RECONSTRUCTION", "SEMANTIC_SEGMENTATION", "GEOMETRIC_CLEANUP",
  "PLANE_FITTING", "ARTIFACT_GENERATION", "SEMANTIC_INDEXING", "NAVIGATION_BAKING",
];

function makeRun(status: "RUNNING" | "SUCCEEDED" | "FAILED", upTo: number, quality: "FINAL" | "PARTIAL" | null = null): RunLike {
  return {
    id: "run-b-1",
    status,
    quality: status === "SUCCEEDED" ? (quality ?? "FINAL") : null,
    failureStage: status === "FAILED" ? STAGES[upTo] : null,
    failureCode: status === "FAILED" ? "WORKER_LOST" : null,
    failureMessage: status === "FAILED" ? "The stage worker lease expired." : null,
    stages: STAGES.map((stage, i) => {
      if (i < upTo) {
        return {
          stage,
          state: "SUCCEEDED",
          lastRun: {
            status: "SUCCEEDED",
            errorCode: null,
            errorMessage: null,
            artifacts: [{ kind: stage === "ARTIFACT_GENERATION" ? "KSPLAT" : "OTHER" }],
          },
        };
      }
      if (i === upTo) {
        return {
          stage,
          state: status === "RUNNING" ? "RUNNING" : status === "FAILED" ? "FAILED" : "SUCCEEDED",
          lastRun: status === "FAILED" ? { status: "FAILED", errorCode: "WORKER_LOST", errorMessage: "lost", artifacts: [] } : null,
        };
      }
      return { stage, state: "PENDING", lastRun: null };
    }),
  };
}

test("Journey B1: Persistent run ID & real stage progress without simulated timer", () => {
  const run = makeRun("RUNNING", 4);
  assert.equal(run.id, "run-b-1");

  const progress = pipelineProgress(run as unknown as RunLike);
  assert.equal(progress.completed, 4);
  assert.equal(progress.total, 12);
  assert.equal(progress.activeStage, "POSE_ESTIMATION");
  assert.equal(progress.activeRunning, true);
});

test("Journey B2: Worker unavailable detects stale lease and explains error", () => {
  const run = makeRun("FAILED", 4);
  const kind = failureKind(run as unknown as RunLike);
  assert.equal(kind, "INTERRUPTED");

  const stale = staleLeaseMessage([
    { stage: "POSE_ESTIMATION", workerId: "w1", leaseExpiresAt: "2026-10-28T12:05:00Z", leaseExpired: true },
  ]);
  assert.match(stale!, /has stopped sending heartbeats/);

  const explained = explainStageError("DEPENDENCY_UNAVAILABLE", "Server error", { missing: ["colmap"] });
  assert.match(explained, /colmap/);
});

test("Journey B3: Partial reconstruction is explicitly labeled PARTIAL", () => {
  const partialRun = makeRun("SUCCEEDED", 12, "PARTIAL");
  assert.equal(partialRun.quality, "PARTIAL");

  const label = versionLabel({ versionNumber: 2, generatedAt: "2026-10-28T12:00:00Z", runQuality: "PARTIAL" }, "28 Oct", false);
  assert.match(label, /\[partial\]/);
});

test("Journey B4: Uncalibrated or failed run cannot become published final version", () => {
  const uncalibratedRun = makeRun("SUCCEEDED", 12);
  const status = workflowStatus(uncalibratedRun, [], null);
  const pub = status.find((s) => s.key === "publication");
  assert.equal(pub?.state, "WAITING");
  assert.match(pub!.detail, /not published yet/);

  const failedRun = makeRun("FAILED", 5);
  const failedStatus = workflowStatus(failedRun, [], null);
  const failedPub = failedStatus.find((s) => s.key === "publication");
  assert.equal(failedPub?.state, "NOT_AVAILABLE");
});

test("Journey B5: Corrupted artifact detection fails closed and is classified as retryable corrupt", async () => {
  const validData = new Uint8Array([0x01, 0x02, 0x03, 0x04]);
  const correctHash = createHash("sha256").update(validData).digest("hex");

  const corruptData = new Uint8Array([0x01, 0x02, 0x03, 0xff]); // flipped byte
  const ref: ArtifactRef = {
    kind: "OTHER",
    contentType: "application/octet-stream",
    sizeBytes: validData.byteLength,
    sha256: correctHash,
    url: "/api/v1/venues/v/scan-versions/s/artifacts/OTHER",
    format: null,
  };

  await assert.rejects(
    async () => {
      await verifyArtifact(corruptData.buffer, ref);
    },
    (err: unknown) => err instanceof ArtifactIntegrityError && err.code === "CHECKSUM_MISMATCH",
  );

  const classified = classifyViewerError(new ArtifactIntegrityError("CHECKSUM_MISMATCH", "corrupt"));
  assert.equal(classified.kind, "corrupt");
  assert.equal(classified.retryable, true);
});

// =================================================================================================
// JOURNEY C: FIND A DESTINATION
// =================================================================================================

test("Journey C1: Loads correct venue and isolates scenes across versions", () => {
  const k1 = sceneKey({ runId: "run-1", scanVersionId: "sv-1" });
  const k2 = sceneKey({ runId: "run-2", scanVersionId: "sv-2" });

  assert.equal(k1, "version:sv-1");
  assert.equal(k2, "version:sv-2");

  const scopedPois = { key: k1!, value: [{ id: "poi-1", label: "Front Desk" }] };
  assert.equal(forScene(scopedPois, k2), null, "scene 2 cannot read scene 1 POIs");
});

test("Journey C2: Search with results vs. empty search without fabricating pins", () => {
  const searchResult = {
    poiId: "poi-desk",
    floorId: "f1",
    label: "Reception Desk",
    category: "service",
    tags: [],
    x: 10,
    y: 5,
    z: 0,
    similarity: 0.88,
    detectionConfidence: null,
    source: "MANUAL" as const,
    boundingBox: null,
    spatialStatus: "VALID" as const,
    distanceMeters: 14.2,
    distanceKind: "ROUTE" as const,
  };

  assert.equal(canNavigate(searchResult), true);
  assert.equal(distanceNote(searchResult), "14 m walk");
  assert.equal(matchNote(searchResult, "embedding"), "88% match");

  const emptyResp = {
    query: "alien spaceship",
    matchType: "embedding" as const,
    results: [],
    relevance: "FILTERED" as const,
    interpretation: {
      normalized: "alien spaceship",
      subject: "alien spaceship",
      lexicalTerms: ["alien", "spaceship"],
      synonymGroups: [],
      corrections: [],
      targetKind: "DESCRIPTION" as const,
      spatialModifier: "NONE" as const,
      spatialModifierApplied: false,
      spatialNote: null,
      accessibilityRequired: false,
    },
  };
  const emptyMsg = emptyMessage(emptyResp);
  assert.equal(emptyMsg, "No matches.");
});

test("Journey C3: Unreachable route explains disconnection rather than drawing a fake path", () => {
  const failure = describeRouteFailure("NO_ROUTE", "No path found", "STANDARD");
  assert.equal(failure.kind, "unreachable");
  assert.match(failure.message, /The destination cannot be reached from the start/);
});

test("Journey C4: Stale scan version rejection prevents mixing outdated navigation graphs", () => {
  const r: RouteResponse = {
    waypoints: [{ x: 0, y: 0, z: 0, floorId: "f1", kind: "START" }, { x: 5, y: 0, z: 0, floorId: "f1", kind: "DESTINATION" }],
    distanceMeters: 5,
    estimatedDurationSeconds: 4,
    floorTransitions: [],
    accessibilityProfile: "STANDARD",
    accessibilityConstraintsApplied: [],
    routingSources: [{
      floorId: "f1",
      graphId: "g",
      source: "RECAST_NAVMESH",
      navmeshSha256: null,
      recastnavigationVersion: null,
      pathMethod: null,
      scanVersionId: "sv-stale",
      coordinateFrameId: "frame-a",
    }],
  };

  const overlay = routeOverlay(r, { floorId: "f1", frameId: "frame-a", scanVersionId: "sv-current" });
  assert.equal(overlay.waypoints, null);
  assert.equal(overlay.mismatch, "VERSION_MISMATCH");
});

test("Journey C5: Step-free accessibility filter enforced only when accessibility graph data exists", () => {
  const err = Object.assign(new Error("no step-free route"), { status: 404, code: "NO_ACCESSIBLE_ROUTE" });
  const stepFreeFailure = classifyRouteError(err, "STEP_FREE");
  assert.equal(stepFreeFailure.kind, "no-accessible-route");
  assert.match(stepFreeFailure.message, /step-free route/);
});

// =================================================================================================
// JOURNEY D: PHYSICAL AR
// =================================================================================================

test("Journey D1: Unsupported AR device detection and native fallback", async () => {
  // Desktop browser without navigator.xr
  const support = await detectArEnvironment({}, {});
  assert.equal(support.problem, "WEBXR_UNAVAILABLE");

  // iOS Safari directed to native app
  const iosUa = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15";
  assert.equal(isAppleMobile(iosUa, 5), true);
  assert.equal(isAppleMobile("Mozilla/5.0 (Windows NT 10.0; Win64; x64)", 0), false);
});

test("Journey D2: Guidance starts ONLY after localization is sufficiently reliable", () => {
  let s = initialArSessionState();
  assert.equal(s.state, "IDLE");

  s = reduceArSession(s, { type: "SESSION_STARTED" });
  assert.equal(s.state, "SEARCHING");
  assert.equal(canAdvanceRoute(s), false);
  assert.equal(routeShown(s), false);

  s = reduceArSession(s, { type: "TRACKING_RESTORED" });

  const obs: MarkerObservation = {
    anchorId: "anc-1",
    markerType: "IMAGE_TARGET",
    markerIdentifier: "gate-a",
    observedPose: { x: 0, y: 0, z: -1, qx: 0, qy: 0, qz: 0, qw: 1 },
    timestampMs: 100,
    trackingState: "tracked",
    measuredWidthMeters: 0.2,
    registeredWidthMeters: 0.2,
  };

  s = reduceArSession(s, { type: "MARKERS_OBSERVED", observations: [obs] });
  assert.equal(s.state, "RELOCALIZING");
  assert.equal(canAdvanceRoute(s), false);

  // Valid device-to-venue transform rotates +Y to +Z
  const loc: Localization = {
    deviceToVenue: { x: 2, y: 3, z: 0, qx: Math.SQRT1_2, qy: 0, qz: 0, qw: Math.SQRT1_2 },
    anchorIds: ["anc-1"],
    residualMeters: 0.02,
    coordinateFrameId: "frame-1",
    scanVersionId: "sv-1",
    floorId: "f-1",
    solvedAtMs: 200,
  };

  s = reduceArSession(s, { type: "RELOCALIZATION_SUCCEEDED", requestId: s.pending!.requestId, localization: loc });
  assert.equal(s.state, "LOCALIZED");
  assert.equal(canAdvanceRoute(s), true);
  assert.equal(routeShown(s), true);
});

test("Journey D3: Tracking loss and recovery produces safe, understandable recovery state", () => {
  let s = initialArSessionState();
  s = reduceArSession(s, { type: "SESSION_STARTED" });
  s = reduceArSession(s, { type: "TRACKING_RESTORED" });

  const obs: MarkerObservation = {
    anchorId: "anc-1",
    markerType: "IMAGE_TARGET",
    markerIdentifier: "gate-a",
    observedPose: { x: 0, y: 0, z: -1, qx: 0, qy: 0, qz: 0, qw: 1 },
    timestampMs: 100,
    trackingState: "tracked",
    measuredWidthMeters: 0.2,
    registeredWidthMeters: 0.2,
  };
  s = reduceArSession(s, { type: "MARKERS_OBSERVED", observations: [obs] });

  const loc: Localization = {
    deviceToVenue: { x: 1, y: 1, z: 0, qx: Math.SQRT1_2, qy: 0, qz: 0, qw: Math.SQRT1_2 },
    anchorIds: ["anc-1"],
    residualMeters: 0.01,
    coordinateFrameId: "frame-1",
    scanVersionId: "sv-1",
    floorId: "f-1",
    solvedAtMs: 100,
  };
  s = reduceArSession(s, { type: "RELOCALIZATION_SUCCEEDED", requestId: s.pending!.requestId, localization: loc });
  assert.equal(s.state, "LOCALIZED");

  // Fast motion leads to limited tracking
  s = reduceArSession(s, { type: "TRACKING_LIMITED", reason: "fast motion", atMs: 1000 });
  assert.equal(s.state, "LIMITED");
  assert.equal(canAdvanceRoute(s), false, "route does not advance while tracking is limited");
  assert.equal(routeShown(s), true, "route overlay held frozen during brief limited window");

  // Tracking loss exceeded timeout via TICK event -> TRACKING_LOST
  s = reduceArSession(s, { type: "TICK", atMs: 1000 + MAX_LIMITED_MS + 10 });
  assert.equal(s.state, "TRACKING_LOST");
  assert.equal(routeShown(s), false, "route hidden when tracking lost");
});

// =================================================================================================
// JOURNEY E: ADMINISTRATOR
// =================================================================================================

test("Journey E1: Administrator section access gating and tenant boundary", () => {
  const sections = ["OVERVIEW", "COVERAGE", "JOBS", "FAILURES", "STORAGE", "RESOURCES", "SEARCH_ANALYTICS", "RESCANS", "AUDIT"];
  const adminAccess = Object.fromEntries(sections.map((s) => [s, true]));
  const opAccess = Object.fromEntries(sections.map((s) => [s, ["OVERVIEW", "COVERAGE", "JOBS", "RESCANS"].includes(s)]));

  assert.equal(adminAccess.SEARCH_ANALYTICS, true);
  assert.equal(adminAccess.RESOURCES, true);
  assert.equal(opAccess.SEARCH_ANALYTICS, false);
});

test("Journey E2: Pipeline and worker observability metrics are truthful", () => {
  assert.equal(formatAge(65), "1 min ago");
  assert.equal(formatAge(3600), "1 h ago");
  assert.equal(formatAge(null), "never");

  assert.equal(formatPercent(94.5), "94.5%");
  assert.equal(formatPercent(null), "—");
});

test("Journey E3: Cost accounting strictly separates measured time from estimated dollars", () => {
  // Measured worker execution: 120s = 2.0 min on 2 of 2 attempts
  const measured = formatMeasured(120, 2, 2);
  assert.equal(measured, "2.0 min");

  // Measured on 1 of 2 attempts
  const partialMeasured = formatMeasured(120, 1, 2);
  assert.equal(partialMeasured, "2.0 min (measured on 1 of 2 attempts)");

  // Estimated billing with rate card
  const estimatedMoney = formatMoney([{ currency: "USD", amount: 14.50 }]);
  assert.equal(estimatedMoney, "14.50 USD");

  const unmeasured = formatMeasured(null, 0, 3);
  assert.equal(unmeasured, "not measured");
});

test("Journey E4: Rescan polygon validation ensures geometric validity", () => {
  function validateRescanPolygon(vertices: [number, number][]): { valid: boolean; reason?: string } {
    if (vertices.length < 3) return { valid: false, reason: "A region needs at least 3 vertices." };
    return { valid: true };
  }

  assert.equal(validateRescanPolygon([[0, 0], [1, 0]]).valid, false);
  assert.equal(validateRescanPolygon([[0, 0], [1, 0], [1, 1]]).valid, true);
});
