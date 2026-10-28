// Pre-departure operator review for a capture session (Requirement 15).
// Pure: synthesizes rejected/quarantined media, per-frame quality defects, coverage gaps, unobserved room
// boundaries, and reshoot recommendations so the operator can verify everything before leaving the venue.
// Never invents coverage or frame-quality numbers when they were not measured.

import type { HudStatus, QualitySample, ReshootRecommendation, UncoveredZone } from "./hud-types.ts";
import { explainCode } from "./upload-plan.ts";

export interface ReviewMediaItem {
  id: string;
  name: string;
  state: "QUEUED" | "UPLOADING" | "VALIDATING" | "RETRY_WAIT" | "RETRY_WAITING" | "ACCEPTED" | "REJECTED" | "QUARANTINED" | "FAILED" | "CORRUPTED" | "CONFLICT" | "PENDING";
  attempts?: number;
  code?: string | null;
  error?: string | null;
}

export interface RejectedMediaReview {
  id: string;
  name: string;
  state: ReviewMediaItem["state"];
  code: string | null;
  reason: string;
  action: string;
}

export interface FrameDefectSummary {
  analyzedFrames: number;
  defectiveFrames: number;
  rejectedFrameRate: number | null;
  counts: {
    BLUR: number;
    LOW_FEATURE_COUNT: number;
    UNDEREXPOSED: number;
    OVEREXPOSED: number;
    FAST_MOTION: number;
    DUPLICATE_FRAME: number;
    SPACING_TOO_FAR: number;
    SPACING_TOO_CLOSE: number;
  };
}

export interface CoverageGapReview {
  coverageAvailable: boolean;
  coverageUnavailableReason: string | null;
  coverageBasis: string;
  coverageLive: boolean;
  observedCoveragePercent: number | null;
  predictedCoveragePercent: number | null;
  uncoveredAreaM2: number | null;
  uncoveredZones: UncoveredZone[];
  unobservedFrontierMeters: number;
  plannedWaypoints: number;
  visitedWaypoints: number;
  pendingWaypoints: number;
  unreachableWaypoints: number;
}

export interface OperatorReshootItem {
  reason: string;
  x: number | null;
  y: number | null;
  action: string;
}

export type DepartureVerdict = "READY_TO_LEAVE" | "RESHOOT_RECOMMENDED" | "ACTION_REQUIRED";

export interface OperatorDepartureReview {
  verdict: DepartureVerdict;
  headline: string;
  detail: string;
  media: {
    totalFiles: number;
    acceptedFiles: number;
    pendingFiles: number;
    rejectedFiles: number;
    quarantinedFiles: number;
    failedFiles: number;
    corruptedFiles: number;
    retriedFiles: number;
    totalRetryAttempts: number;
    uploadRetryRate: number;
    problems: RejectedMediaReview[];
  };
  frameQuality: FrameDefectSummary;
  coverage: CoverageGapReview;
  reshoots: OperatorReshootItem[];
}

const DEFECT_CODES = [
  "BLUR",
  "LOW_FEATURE_COUNT",
  "UNDEREXPOSED",
  "OVEREXPOSED",
  "FAST_MOTION",
  "DUPLICATE_FRAME",
  "SPACING_TOO_FAR",
  "SPACING_TOO_CLOSE",
] as const;

const REJECTABLE_FRAME_DEFECTS = new Set([
  "BLUR",
  "LOW_FEATURE_COUNT",
  "UNDEREXPOSED",
  "OVEREXPOSED",
  "FAST_MOTION",
  "DUPLICATE_FRAME",
]);

function explainReshoot(r: ReshootRecommendation): string {
  const where = r.x != null && r.y != null ? ` at (${r.x.toFixed(1)}, ${r.y.toFixed(1)})` : "";
  if (r.reason.startsWith("REVISIT_WAYPOINT")) {
    return `Walk back to${where || " that waypoint"} and re-capture slowly with steady lighting and framing.`;
  }
  if (r.reason.startsWith("REPEATED_BLUR") || r.reason.startsWith("REPEATED_FAST_MOTION")) {
    return `Re-capture the stretch${where} moving under 1 m/s and turning under 60°/s.`;
  }
  if (r.reason.startsWith("REPEATED_LOW_FEATURE_COUNT")) {
    return `Re-capture${where} framing doorways, corners or floor edges so camera poses can lock on.`;
  }
  if (r.reason.startsWith("REPEATED_UNDEREXPOSED") || r.reason.startsWith("REPEATED_OVEREXPOSED")) {
    return `Adjust room lighting or viewing angle${where} and re-record that view.`;
  }
  if (r.reason.startsWith("UNCOVERED_AREA")) {
    return `Walk towards${where} and sweep the camera across the unvisited surface.`;
  }
  return `Re-capture${where}: ${r.reason}`;
}

export function summarizeFrameSamples(samples: readonly QualitySample[], status: HudStatus | null): FrameDefectSummary {
  const counts = {
    BLUR: 0,
    LOW_FEATURE_COUNT: 0,
    UNDEREXPOSED: 0,
    OVEREXPOSED: 0,
    FAST_MOTION: 0,
    DUPLICATE_FRAME: 0,
    SPACING_TOO_FAR: 0,
    SPACING_TOO_CLOSE: 0,
  };

  if (samples.length > 0) {
    let defective = 0;
    for (const s of samples) {
      let hasRejectable = false;
      for (const code of DEFECT_CODES) {
        if (s.warnings.includes(code)) {
          counts[code]++;
          if (REJECTABLE_FRAME_DEFECTS.has(code)) hasRejectable = true;
        }
      }
      if (hasRejectable) defective++;
    }
    return {
      analyzedFrames: samples.length,
      defectiveFrames: defective,
      rejectedFrameRate: Math.round((defective / samples.length) * 10000) / 10000,
      counts,
    };
  }

  // Fallback when only the server's summary of recent samples is available (e.g. after a page reload).
  const q = status?.quality ?? null;
  if (!q || q.sampleCount === 0) {
    return { analyzedFrames: 0, defectiveFrames: 0, rejectedFrameRate: null, counts };
  }
  const dup = Math.round(q.duplicateFrameRate * q.sampleCount);
  counts.DUPLICATE_FRAME = dup;
  for (const flag of status?.qualityFlags ?? []) {
    if (flag in counts && counts[flag as keyof typeof counts] === 0) {
      counts[flag as keyof typeof counts] = 3; // at least 3 of the last 5 frames had this warning
    }
  }
  const estDefective = Math.min(
    q.sampleCount,
    Math.max(dup, (status?.qualityFlags?.length ?? 0) > 0 ? 3 : 0),
  );
  return {
    analyzedFrames: q.sampleCount,
    defectiveFrames: estDefective,
    rejectedFrameRate: Math.round((estDefective / q.sampleCount) * 10000) / 10000,
    counts,
  };
}

export function buildOperatorDepartureReview(
  files: readonly ReviewMediaItem[],
  qualitySamples: readonly QualitySample[],
  status: HudStatus | null,
): OperatorDepartureReview {
  let acceptedFiles = 0;
  let pendingFiles = 0;
  let rejectedFiles = 0;
  let quarantinedFiles = 0;
  let failedFiles = 0;
  let corruptedFiles = 0;
  let retriedFiles = 0;
  let totalRetryAttempts = 0;
  let attemptedFiles = 0;
  const problems: RejectedMediaReview[] = [];

  for (const f of files) {
    const attempts = f.attempts ?? (f.state === "QUEUED" ? 0 : 1);
    if (attempts > 0) {
      attemptedFiles++;
      if (attempts > 1) {
        retriedFiles++;
        totalRetryAttempts += attempts - 1;
      }
    }
    switch (f.state) {
      case "ACCEPTED":
        acceptedFiles++;
        break;
      case "QUEUED":
      case "UPLOADING":
      case "VALIDATING":
      case "RETRY_WAIT":
      case "RETRY_WAITING":
      case "PENDING":
        pendingFiles++;
        break;
      case "CONFLICT":
        failedFiles++;
        problems.push({
          id: f.id,
          name: f.name,
          state: f.state,
          code: f.code ?? null,
          reason: f.error ?? "Server holds another file under this client identifier.",
          action: "Discard the conflicting entry and re-add the file.",
        });
        break;
      case "REJECTED":
        rejectedFiles++;
        problems.push({
          id: f.id,
          name: f.name,
          state: f.state,
          code: f.code ?? null,
          reason: explainCode(f.code, f.error ?? "Rejected by server validation."),
          action: "Record or select a valid replacement file and remove the rejected entry.",
        });
        break;
      case "QUARANTINED":
        quarantinedFiles++;
        problems.push({
          id: f.id,
          name: f.name,
          state: f.state,
          code: f.code ?? null,
          reason: explainCode(f.code, f.error ?? "Held in quarantine during validation."),
          action: "Click \"Retry validation\" once the scanner or storage service is reachable.",
        });
        break;
      case "FAILED":
        failedFiles++;
        problems.push({
          id: f.id,
          name: f.name,
          state: f.state,
          code: f.code ?? null,
          reason: f.error ?? "Upload failed after maximum retry attempts.",
          action: "Click \"Retry now\" on a stable connection before leaving.",
        });
        break;
      case "CORRUPTED":
        corruptedFiles++;
        problems.push({
          id: f.id,
          name: f.name,
          state: f.state,
          code: f.code ?? null,
          reason: f.error ?? "Local copy was missing or altered before upload.",
          action: "Choose the original file from the device again or re-capture.",
        });
        break;
    }
  }

  const uploadRetryRate = attemptedFiles > 0 ? Math.round((retriedFiles / attemptedFiles) * 10000) / 10000 : 0;
  const frameQuality = summarizeFrameSamples(qualitySamples, status);

  const plannedPath = status?.plannedPath ?? [];
  const persistedPlan = status?.plannedPathKind === "PERSISTED_PLAN";
  const plannedWaypoints = persistedPlan ? plannedPath.length : (status?.plan?.waypoints ?? 0);
  const visitedWaypoints = persistedPlan
    ? plannedPath.filter((w) => w.status === "VISITED").length
    : (status?.plan?.visited ?? 0);
  const unreachableWaypoints = persistedPlan
    ? plannedPath.filter((w) => w.status === "UNREACHABLE").length
    : (status?.plan?.unreachable ?? 0);
  const pendingWaypoints = Math.max(0, plannedWaypoints - visitedWaypoints - unreachableWaypoints);

  const coverage: CoverageGapReview = {
    coverageAvailable: status?.coverageAvailable ?? false,
    coverageUnavailableReason: status?.coverageUnavailableReason ?? (status ? null : "Capture HUD status has not been loaded."),
    coverageBasis: status?.coverageBasis ?? "NONE",
    coverageLive: status?.coverageLive ?? false,
    observedCoveragePercent: status?.coveragePercent ?? null,
    predictedCoveragePercent: status?.plan?.predictedCoveragePercent ?? null,
    uncoveredAreaM2: status?.uncoveredAreaM2 ?? null,
    uncoveredZones: status?.uncoveredZones ?? [],
    unobservedFrontierMeters: status?.bounds?.frontierMeters ?? 0,
    plannedWaypoints,
    visitedWaypoints,
    pendingWaypoints,
    unreachableWaypoints,
  };

  const reshoots: OperatorReshootItem[] = (status?.reshootRecommendations ?? []).map((r) => ({
    reason: r.reason,
    x: r.x,
    y: r.y,
    action: explainReshoot(r),
  }));

  if (frameQuality.rejectedFrameRate !== null && frameQuality.rejectedFrameRate >= 0.35 && reshoots.length === 0) {
    reshoots.push({
      reason: `HIGH_DEFECT_FRAME_RATE (${frameQuality.defectiveFrames}/${frameQuality.analyzedFrames} analyzed frames flagged)`,
      x: status?.currentPosition?.x ?? null,
      y: status?.currentPosition?.y ?? null,
      action: "Over 35% of analyzed frames had blur, exposure, low-texture or motion defects. Record a steadier pass before leaving the venue.",
    });
  }

  let verdict: DepartureVerdict = "READY_TO_LEAVE";
  let headline = "Ready to finish and leave the venue";
  let detail = "Uploaded media is accepted by the server and no blocking coverage gaps or reshoot recommendations remain.";

  if (acceptedFiles === 0 || pendingFiles > 0 || failedFiles > 0 || quarantinedFiles > 0 || corruptedFiles > 0) {
    verdict = "ACTION_REQUIRED";
    const blockers: string[] = [];
    if (acceptedFiles === 0) blockers.push("no media file has been accepted by the server yet");
    if (pendingFiles > 0) blockers.push(`${pendingFiles} file(s) are still uploading or validating`);
    if (failedFiles > 0) blockers.push(`${failedFiles} upload(s) failed`);
    if (quarantinedFiles > 0) blockers.push(`${quarantinedFiles} file(s) are quarantined`);
    if (corruptedFiles > 0) blockers.push(`${corruptedFiles} local file(s) are corrupted`);
    headline = "Do not leave yet — media uploads are not settled";
    detail = `Resolve before leaving: ${blockers.join("; ")}.`;
  } else if (
    reshoots.length > 0 ||
    rejectedFiles > 0 ||
    coverage.uncoveredZones.length > 0 ||
    coverage.pendingWaypoints > 0 ||
    coverage.unreachableWaypoints > 0 ||
    coverage.unobservedFrontierMeters > 0.8 ||
    (frameQuality.rejectedFrameRate !== null && frameQuality.rejectedFrameRate >= 0.35)
  ) {
    verdict = "RESHOOT_RECOMMENDED";
    const notes: string[] = [];
    if (rejectedFiles > 0) notes.push(`${rejectedFiles} rejected file(s)`);
    if (reshoots.length > 0) notes.push(`${reshoots.length} reshoot recommendation(s)`);
    if (coverage.uncoveredZones.length > 0) notes.push(`${coverage.uncoveredZones.length} uncovered zone(s) (${coverage.uncoveredAreaM2?.toFixed(1) ?? "?"} m²)`);
    if (coverage.pendingWaypoints > 0) notes.push(`${coverage.pendingWaypoints} unvisited waypoint(s)`);
    if (coverage.unobservedFrontierMeters > 0.8) notes.push(`${coverage.unobservedFrontierMeters.toFixed(1)} m of unobserved boundary frontier`);
    if (frameQuality.rejectedFrameRate !== null && frameQuality.rejectedFrameRate >= 0.35) {
      notes.push(`${Math.round(frameQuality.rejectedFrameRate * 100)}% of analyzed frames flagged for quality defects`);
    }
    headline = "Review recommended reshoots and coverage gaps before leaving";
    detail = `Media can be submitted, but ${notes.join(", ")} may degrade reconstruction quality if not addressed on site.`;
  } else if (!coverage.coverageAvailable) {
    headline = "Media accepted — coverage was not measured on this device";
    detail = `Server accepted ${acceptedFiles} file(s). Live spatial coverage was not measured (${coverage.coverageUnavailableReason ?? "no room bounds or poses"}). Verify visually that every wall and doorway was covered before leaving.`;
  }

  return {
    verdict,
    headline,
    detail,
    media: {
      totalFiles: files.length,
      acceptedFiles,
      pendingFiles,
      rejectedFiles,
      quarantinedFiles,
      failedFiles,
      corruptedFiles,
      retriedFiles,
      totalRetryAttempts,
      uploadRetryRate,
      problems,
    },
    frameQuality,
    coverage,
    reshoots,
  };
}
