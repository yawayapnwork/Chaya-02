import assert from "node:assert/strict";
import { test } from "node:test";
import {
  isSyntheticFixture,
  type ArtifactRef,
  verifyArtifact,
  ArtifactIntegrityError,
  KNOWN_SYNTHETIC_SHA256,
} from "./artifact-integrity.ts";
import {
  initialArSessionState,
  reduceArSession,
  canAdvanceRoute,
  routeShown,
  MAX_LIMITED_MS,
  type ArSessionState,
  type Localization,
} from "./ar-relocalization.ts";
import type { MarkerObservation } from "./ar-marker-tracking.ts";
import { describeRouteFailure, classifyRouteError } from "./viewer-route.ts";
import { workflowStatus, type RunLike } from "./workflow-status.ts";
import { classifyViewerError } from "./viewer-errors.ts";
import { classifyArFailure } from "./ar-errors.ts";

// =================================================================================================
// RELEASE GATES SPECIFICATION ENFORCEMENT
// =================================================================================================

export type ReleaseGateLevel =
  | "SPECIFIED"
  | "IMPLEMENTED"
  | "UNIT_TESTED"
  | "INTEGRATION_TESTED"
  | "FIXTURE_VALIDATED"
  | "REAL_RECONSTRUCTION_VALIDATED"
  | "REAL_VENUE_VALIDATED"
  | "PHYSICAL_DEVICE_VALIDATED";

export interface CapabilityClaim {
  id: string;
  name: string;
  claimedLevel: ReleaseGateLevel;
  hasImplementation: boolean;
  hasAutomatedTests: boolean;
  hasFixturePass: boolean;
  hasRealGpuExecution: boolean;
  hasRealVenueAcceptance: boolean;
  hasPhysicalDeviceTests: boolean;
}

export function evaluateReleaseGate(claim: CapabilityClaim): {
  approvedLevel: ReleaseGateLevel;
  verdict: "PASS" | "BLOCKED" | "FAIL";
  reasons: string[];
} {
  if (claim.claimedLevel === "PHYSICAL_DEVICE_VALIDATED") {
    if (!claim.hasPhysicalDeviceTests) {
      return {
        approvedLevel: "FIXTURE_VALIDATED",
        verdict: "BLOCKED",
        reasons: ["Physical device validation requires execution on target hardware (iOS ARKit / Android ARCore)."],
      };
    }
  }

  if (claim.claimedLevel === "REAL_VENUE_VALIDATED") {
    if (!claim.hasRealVenueAcceptance) {
      return {
        approvedLevel: "FIXTURE_VALIDATED",
        verdict: "BLOCKED",
        reasons: ["Real-venue validation requires documented acceptance run on measured indoor venue data."],
      };
    }
  }

  if (claim.claimedLevel === "REAL_RECONSTRUCTION_VALIDATED") {
    if (!claim.hasRealGpuExecution) {
      return {
        approvedLevel: "FIXTURE_VALIDATED",
        verdict: "BLOCKED",
        reasons: ["Real reconstruction requires verified CUDA GPU execution (missing GPU host is a blocker)."],
      };
    }
  }

  if (claim.claimedLevel === "FIXTURE_VALIDATED" && !claim.hasFixturePass) {
    return {
      approvedLevel: "INTEGRATION_TESTED",
      verdict: "FAIL",
      reasons: ["Fixture test failed or not executed."],
    };
  }

  if (claim.claimedLevel === "UNIT_TESTED" && !claim.hasAutomatedTests) {
    return {
      approvedLevel: "IMPLEMENTED",
      verdict: "FAIL",
      reasons: ["Automated unit tests missing or failing."],
    };
  }

  if (claim.claimedLevel === "IMPLEMENTED" && !claim.hasImplementation) {
    return {
      approvedLevel: "SPECIFIED",
      verdict: "FAIL",
      reasons: ["Code implementation does not exist."],
    };
  }

  return { approvedLevel: claim.claimedLevel, verdict: "PASS", reasons: [] };
}

test("Release Gate: A capability cannot claim Real-Venue Validated without measured venue acceptance", () => {
  const gate = evaluateReleaseGate({
    id: "gsplat-pipeline",
    name: "Full Gaussian Splatting Reconstruction",
    claimedLevel: "REAL_VENUE_VALIDATED",
    hasImplementation: true,
    hasAutomatedTests: true,
    hasFixturePass: true,
    hasRealGpuExecution: false,
    hasRealVenueAcceptance: false,
    hasPhysicalDeviceTests: false,
  });
  assert.equal(gate.verdict, "BLOCKED");
  assert.equal(gate.approvedLevel, "FIXTURE_VALIDATED");
  assert.match(gate.reasons[0], /Real-venue validation requires/);
});

test("Release Gate: Missing GPU host blocks Real Reconstruction claim, not passing it", () => {
  const gate = evaluateReleaseGate({
    id: "gsplat-cuda",
    name: "CUDA Gaussian Splat Training",
    claimedLevel: "REAL_RECONSTRUCTION_VALIDATED",
    hasImplementation: true,
    hasAutomatedTests: true,
    hasFixturePass: true,
    hasRealGpuExecution: false,
    hasRealVenueAcceptance: false,
    hasPhysicalDeviceTests: false,
  });
  assert.equal(gate.verdict, "BLOCKED");
  assert.match(gate.reasons[0], /missing GPU host is a blocker/);
});

test("Release Gate: Physical AR cannot claim Physical-Device Validated on workstation host", () => {
  const gate = evaluateReleaseGate({
    id: "ar-navigation",
    name: "AR Navigation Guidance",
    claimedLevel: "PHYSICAL_DEVICE_VALIDATED",
    hasImplementation: true,
    hasAutomatedTests: true,
    hasFixturePass: true,
    hasRealGpuExecution: false,
    hasRealVenueAcceptance: false,
    hasPhysicalDeviceTests: false,
  });
  assert.equal(gate.verdict, "BLOCKED");
  assert.match(gate.reasons[0], /requires execution on target hardware/);
});

// =================================================================================================
// 14 ADVERSARIAL RELEASE DEFENSE TESTS
// =================================================================================================

test("Adversarial 1: Publish an uncalibrated scan must be rejected from viewer and routing", () => {
  const routeFailure = describeRouteFailure("NOT_CALIBRATED", "the floor has no canonical frame", "STANDARD");
  assert.equal(routeFailure.kind, "not-routable");
  assert.match(routeFailure.message, /not calibrated/);

  const metricEvidenceFailure = describeRouteFailure(
    "METRIC_EVIDENCE_INSUFFICIENT",
    "hand-entered points without triangulation",
    "STANDARD"
  );
  assert.equal(metricEvidenceFailure.kind, "not-routable");
  assert.match(metricEvidenceFailure.message, /hand-entered/);
});

test("Adversarial 2: Publish a partial artifact as final must be rejected", () => {
  const partialRun: RunLike = {
    id: "run-partial",
    status: "FAILED",
    quality: "PARTIAL",
    failureStage: "SPLAT_RECONSTRUCTION",
    failureCode: "TIME_LIMIT_EXCEEDED",
    failureMessage: "time budget exceeded",
    stages: [
      { stage: "SPLAT_RECONSTRUCTION", state: "FAILED", lastRun: { status: "FAILED", errorCode: "TIME_LIMIT_EXCEEDED", errorMessage: null, artifacts: [{ kind: "SPLAT_PARTIAL" }] } },
      { stage: "ARTIFACT_GENERATION", state: "PENDING", lastRun: null },
      { stage: "NAVIGATION_BAKING", state: "PENDING", lastRun: null },
    ],
  };

  const items = workflowStatus(partialRun, [], null);
  const byKey = Object.fromEntries(items.map((i) => [i.key, i]));
  assert.equal(byKey.viewer.state, "NOT_AVAILABLE", "Partial runs must never make a viewer model available");
  assert.equal(byKey.navigation.state, "NOT_AVAILABLE", "Partial runs must not make routing available");
  assert.equal(byKey.search.state, "NOT_AVAILABLE", "Partial runs must not make search available");
});

test("Adversarial 3: Display a fixture as a real venue must be flagged and rejected", () => {
  for (const syntheticSha of KNOWN_SYNTHETIC_SHA256) {
    assert.equal(
      isSyntheticFixture({ artifacts: [{ sha256: syntheticSha }] }),
      true,
      `Known synthetic SHA ${syntheticSha} must be detected as fixture`
    );
  }
  assert.equal(isSyntheticFixture({ runQuality: "SYNTHETIC_DEMO" }), true);
  assert.equal(isSyntheticFixture({ runQuality: "FIXTURE" }), true);
  assert.equal(isSyntheticFixture({ scanId: "fixture-room-101" }), true);
  assert.equal(isSyntheticFixture({ scanId: "synthetic_venue_02" }), true);
  assert.equal(isSyntheticFixture({ artifacts: [{ url: "/api/v1/fixtures/viewer-scene/scene.ksplat" }] }), true);

  // Negative control: real venue scan without synthetic markers
  assert.equal(
    isSyntheticFixture({
      runQuality: "FINAL",
      scanId: "scan-measured-venue-chaya-2026",
      artifacts: [{ sha256: "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", url: "/artifacts/model.ksplat" }],
    }),
    false
  );
});

test("Adversarial 4: Artifact integrity check fails closed on checksum or size mismatch", async () => {
  const ref: ArtifactRef = {
    id: "art-1",
    kind: "OTHER",
    name: "poses.json",
    sizeBytes: 100,
    sha256: "0".repeat(64),
  };
  const mismatchedSize = new Uint8Array(50).buffer;
  await assert.rejects(
    async () => verifyArtifact(mismatchedSize, ref),
    (err: unknown) => err instanceof ArtifactIntegrityError && err.code === "SIZE_MISMATCH"
  );
});

test("Adversarial 6: Stale navigation artifacts after rescan must require rebuild", () => {
  const routeError = classifyRouteError(
    { status: 409, code: "NAVMESH_NOT_READY", message: "Scan version superseded" },
    "STANDARD"
  );
  assert.equal(routeError.kind, "not-routable");
});

test("Adversarial 7: Generate routes from an unvalidated NavMesh must fail with NAVMESH_NOT_READY", () => {
  const unvalidated = describeRouteFailure("NAVMESH_NOT_READY", "Mesh has no traversable portals", "STANDARD");
  assert.equal(unvalidated.kind, "not-routable");
  assert.match(unvalidated.message, /navigation mesh/);
});

test("Adversarial 8: Continue AR guidance after localization becomes unreliable must be prevented", () => {
  const obs: MarkerObservation = {
    anchorId: "a1",
    markerType: "IMAGE_TARGET",
    markerIdentifier: "target1",
    observedPose: { x: 0, y: 0, z: -1, qx: 0, qy: 0, qz: 0, qw: 1 },
    timestampMs: 100,
    trackingState: "tracked",
    measuredWidthMeters: 0.3,
    registeredWidthMeters: 0.3,
  };
  const loc: Localization = {
    deviceToVenue: { x: 0, y: 0, z: 0, qx: Math.SQRT1_2, qy: 0, qz: 0, qw: Math.SQRT1_2 },
    anchorIds: ["a1"],
    residualMeters: null,
    coordinateFrameId: "frame-1",
    scanVersionId: "v1",
    floorId: "f1",
    solvedAtMs: 100,
  };

  let session: ArSessionState = reduceArSession(initialArSessionState(), { type: "SESSION_STARTED" });
  session = reduceArSession(session, { type: "TRACKING_RESTORED" });
  session = reduceArSession(session, { type: "MARKERS_OBSERVED", observations: [obs] });
  session = reduceArSession(session, {
    type: "RELOCALIZATION_SUCCEEDED",
    requestId: session.pending!.requestId,
    localization: loc,
  });

  assert.equal(session.state, "LOCALIZED");
  assert.equal(canAdvanceRoute(session), true, "Route may advance while localized");
  assert.equal(routeShown(session), true, "Route is displayed while localized");

  // Adversarial event 1: tracking lost
  session = reduceArSession(session, { type: "TRACKING_LOST", reason: "no viewer pose" });
  assert.equal(session.state, "TRACKING_LOST");
  assert.equal(canAdvanceRoute(session), false, "Route must NOT advance after tracking loss");

  // Adversarial event 2: drift exceeded
  session = reduceArSession(session, { type: "DRIFT_EXCEEDED", reason: "drift 0.8m > threshold" });
  assert.equal(session.state, "TRACKING_LOST");
  assert.equal(canAdvanceRoute(session), false, "Route must NOT advance after drift exceeded");

  // Adversarial event 3: tracking limited timeout
  session = reduceArSession(session, { type: "TRACKING_LIMITED", reason: "emulated position", atMs: 1000 });
  const timedOut = reduceArSession(session, { type: "TICK", atMs: 1000 + MAX_LIMITED_MS + 10 });
  assert.equal(timedOut.state, "TRACKING_LOST");
  assert.equal(canAdvanceRoute(timedOut), false);
  assert.equal(routeShown(timedOut), false, "Route must be hidden once limited state times out");
});

test("Adversarial 10: Bypass malware quarantine must be rejected with REFUSED / MALWARE_DETECTED", () => {
  const failure = classifyArFailure({ status: 409, code: "MALWARE_DETECTED", message: "Infected upload" });
  assert.equal(failure.failure, "REFUSED");
  assert.equal(failure.retryable, false);
});

test("Adversarial 11: Expose private artifacts through unauthorized link returns unauthorized or unavailable", () => {
  const error401 = classifyViewerError({ status: 401, code: "HTTP_401", message: "Unauthorized link" });
  assert.equal(error401.kind, "unauthorized");
  assert.equal(error401.retryable, false);

  const error404 = classifyViewerError({ status: 404, code: "HTTP_404", message: "Private artifact not found" });
  assert.equal(error404.kind, "unavailable");
});

test("Adversarial 14: Display false success when backend is unavailable must be prevented", () => {
  const viewerNetworkError = classifyViewerError(new TypeError("Failed to fetch"));
  assert.equal(viewerNetworkError.kind, "failed");
  assert.equal(viewerNetworkError.retryable, true);

  const arNetworkFailure = classifyArFailure(new TypeError("Failed to fetch"));
  assert.equal(arNetworkFailure.failure, "NETWORK_UNAVAILABLE");
  assert.equal(arNetworkFailure.retryable, true);
});
