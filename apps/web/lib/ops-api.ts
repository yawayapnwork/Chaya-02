"use client";

import { api } from "./capture-api";

// Mirrors dev.chaya.api.ops.OpsDtos. Every value is stored data or computed from it; null means "does not exist"
// and is rendered as such, never replaced by a default.

export type OpsSection =
  | "OVERVIEW"
  | "COVERAGE"
  | "JOBS"
  | "FAILURES"
  | "STORAGE"
  | "RESCANS"
  | "SEARCH_ANALYTICS"
  | "AUDIT";

export interface OpsAccess {
  venueId: string;
  roles: string[];
  sections: Record<OpsSection, boolean>;
  mayControlProcessing: boolean;
}

export interface CurrentVersion {
  id: string;
  versionNumber: number;
  parentVersionId: string | null;
  incremental: boolean;
  finalizedAt: string;
  alignmentConfidence: number | null;
  alignmentResidualM: number | null;
  changedArtifactKinds: string[];
}

export interface LatestReconstruction {
  runId: string;
  generatedAt: string;
  runStatus: string;
  runQuality: "FINAL" | "PARTIAL" | null;
  hasViewerAsset: boolean;
}

export interface Freshness {
  lastFullCaptureAt: string | null;
  lastFullCaptureAgeSeconds: number | null;
  lastReconstructedAt: string | null;
  lastReconstructedAgeSeconds: number | null;
  lastRegionRescanAt: string | null;
  lastRegionRescanAgeSeconds: number | null;
  lastCaptureStartedAt: string | null;
}

export interface FloorStatus {
  floorId: string;
  level: number;
  name: string;
  currentVersion: CurrentVersion | null;
  draftVersions: number;
  latestReconstruction: LatestReconstruction | null;
  freshness: Freshness;
}

export type JobStatus = "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED" | "CANCELLED";

export interface Overview {
  venueId: string;
  venueName: string;
  venueSlug: string;
  timezone: string;
  venueCreatedAt: string;
  floorCount: number;
  captureCount: number;
  poiCount: number;
  jobCounts: Record<JobStatus, number>;
  floors: FloorStatus[];
  asOf: string;
}

export interface UncoveredZone {
  centroidX: number;
  centroidY: number;
  areaM2: number;
  reason: string;
}

export interface MeasuredCoverage {
  captureId: string;
  captureStartedAt: string;
  captureStatus: string;
  coverageAvailable: boolean;
  coverageUnavailableReason: string | null;
  coveragePercent: number | null;
  weightedCoveragePercent: number | null;
  uncoveredAreaM2: number | null;
  uncoveredZones: UncoveredZone[];
}

export type CoverageGap = "NO_RECONSTRUCTION" | "NO_FINALIZED_VERSION" | "NO_MEASURED_COVERAGE" | "COVERAGE_UNAVAILABLE" | "UNCOVERED_ZONES";

export interface FloorCoverage {
  floorId: string;
  level: number;
  name: string;
  hasReconstruction: boolean;
  hasFinalizedVersion: boolean;
  measured: MeasuredCoverage | null;
  gaps: CoverageGap[];
}

export interface Coverage {
  floors: FloorCoverage[];
  asOf: string;
}

export interface JobAction {
  available: boolean;
  via: "PIPELINE_RUN" | "JOB" | null;
  captureId: string | null;
  reason: string | null;
}

export interface OpsJob {
  id: string;
  stage: string;
  status: JobStatus;
  scanId: string;
  captureId: string | null;
  runId: string | null;
  runStatus: string | null;
  retryCount: number;
  maxRetries: number;
  queuedAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  workerId: string | null;
  leaseExpiresAt: string | null;
  errorCode: string | null;
  errorMessage: string | null;
  retry: JobAction;
  cancel: JobAction;
}

export interface Jobs {
  counts: Record<JobStatus, number>;
  jobs: OpsJob[];
  limit: number;
  asOf: string;
}

export interface Failure {
  source: "STAGE_RUN" | "LEGACY_JOB";
  jobId: string;
  runId: string | null;
  captureId: string | null;
  stage: string;
  attempt: number;
  errorCode: string | null;
  errorMessage: string | null;
  errorDetails: Record<string, unknown> | null;
  exitStatus: number | null;
  workerId: string | null;
  failedAt: string;
  currentJobStatus: JobStatus;
  retry: JobAction;
}

export interface FailedRun {
  runId: string;
  captureId: string | null;
  failureStage: string | null;
  failureCode: string | null;
  failureMessage: string | null;
  finishedAt: string | null;
  retry: JobAction;
}

export interface Failures {
  days: number;
  failedRuns: FailedRun[];
  failures: Failure[];
  countsByCode: { stage: string; errorCode: string | null; count: number }[];
  asOf: string;
}

export interface Cost {
  available: boolean;
  currency: string | null;
  amount: number | null;
  reason: string | null;
}

export interface Storage {
  rawBucket: string;
  derivedBucket: string;
  rawMedia: { status: string; count: number; declaredBytes: number }[];
  artifacts: { kind: string; count: number; bytes: number; partialCount: number }[];
  artifactCount: number;
  artifactBytes: number;
  piiArtifactCount: number;
  piiArtifactBytes: number;
  piiPurgeIncompleteEvents: number;
  lastArtifactAt: string | null;
  stageTime: { stage: string; executions: number; totalSeconds: number; avgSeconds: number }[];
  cost: Cost;
  asOf: string;
}

export interface TermCount {
  term: string;
  count: number;
  avgResultCount: number | null;
  lastSearchedAt: string;
}

export interface SearchAnalytics {
  days: number;
  queryCount: number;
  zeroResultCount: number;
  avgLatencyMs: number | null;
  p95LatencyMs: number | null;
  distinctSearchers: number;
  firstQueryAt: string | null;
  lastQueryAt: string | null;
  topTerms: TermCount[];
  zeroResultTerms: TermCount[];
  pois: { total: number; manual: number; autoDetected: number; awaitingEmbedding: number };
  asOf: string;
}

export interface Rescan {
  captureId: string;
  floorId: string | null;
  floorName: string | null;
  operatorId: string;
  createdAt: string;
  captureStatus: string;
  parentVersionId: string;
  parentVersionNumber: number;
  regionAreaM2: number | null;
  runId: string | null;
  runStatus: string | null;
  runFailureStage: string | null;
  runFailureCode: string | null;
  runFinishedAt: string | null;
  resultVersionId: string | null;
  resultVersionNumber: number | null;
  resultVersionStatus: "DRAFT" | "FINALIZED" | null;
  alignmentConfidence: number | null;
  alignmentResidualM: number | null;
  changedArtifactKinds: string[];
  finalizedAt: string | null;
}

export interface Rescans {
  rescans: Rescan[];
  asOf: string;
}

export interface AuditEntry {
  id: string;
  actorId: string;
  actorType: string;
  action: string;
  resourceType: string;
  resourceId: string | null;
  outcome: "SUCCESS" | "DENIED" | "FAILURE";
  occurredAt: string;
}

export interface Audit {
  entries: AuditEntry[];
  limit: number;
  asOf: string;
}

const base = (venueId: string) => `/venues/${venueId}/ops`;

export const getOpsAccess = (venueId: string) => api<OpsAccess>(`${base(venueId)}/access`);
export const getOverview = (venueId: string) => api<Overview>(`${base(venueId)}/overview`);
export const getCoverage = (venueId: string) => api<Coverage>(`${base(venueId)}/coverage`);
export const getJobs = (venueId: string, status: JobStatus | null, limit = 50) =>
  api<Jobs>(`${base(venueId)}/jobs?limit=${limit}${status ? `&status=${status}` : ""}`);
export const getFailures = (venueId: string, days: number) => api<Failures>(`${base(venueId)}/failures?days=${days}`);
export const getStorage = (venueId: string) => api<Storage>(`${base(venueId)}/storage`);
export const getSearchAnalytics = (venueId: string, days: number) =>
  api<SearchAnalytics>(`${base(venueId)}/search-analytics?days=${days}`);
export const getRescans = (venueId: string) => api<Rescans>(`${base(venueId)}/rescans`);
export const getAudit = (venueId: string, limit = 100) => api<Audit>(`${base(venueId)}/audit?limit=${limit}`);

/** Retry/cancel through the endpoint the server named (JobAction.via): the pipeline run of the capture, or the
 * legacy job itself. The server re-checks everything; this only picks the URL. */
export function performJobAction(venueId: string, kind: "retry" | "cancel", jobId: string, action: JobAction): Promise<unknown> {
  if (!action.available) return Promise.reject(new Error(action.reason ?? `${kind} is not available`));
  if (action.via === "PIPELINE_RUN" && action.captureId) {
    return api(`/venues/${venueId}/captures/${action.captureId}/processing/${kind}`, { method: "POST" });
  }
  return api(`/venues/${venueId}/jobs/${jobId}/${kind}`, { method: "POST" });
}
