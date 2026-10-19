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
  | "RESOURCES"
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

/** A worker seen in the last 24 h (shared infrastructure, not tenant data). live: seen within workerLiveAfterSeconds. */
export interface WorkerStatus {
  workerId: string;
  stages: string[];
  lastSeenAt: string;
  lastSeenAgeSeconds: number;
  lastClaimedAt: string | null;
  live: boolean;
}

/** This venue's queue, measured at asOf (OpsDtos.JobQueue). */
export interface JobQueue {
  claimableQueued: number;
  oldestQueuedAgeSeconds: number | null;
  running: number;
  staleRunning: number;
  overdueRunning: number;
  unservedStages: string[];
  workers: WorkerStatus[];
  workerLiveAfterSeconds: number;
}

export interface Jobs {
  counts: Record<JobStatus, number>;
  /** Absent from an API older than the worker-presence migration (V34): shown as unavailable, never as zero. */
  queue?: JobQueue;
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

/** The venue's processing cost, all time. kind is always ESTIMATED (measured worker time x configured rates), never a
 * bill; complete=false means some attempts could not be priced, so amount is a lower bound. */
export interface Cost {
  available: boolean;
  currency: string | null;
  amount: number | null;
  reason: string | null;
  kind: "ESTIMATED";
  complete: boolean;
  pricedAttempts: number;
  unpricedAttempts: number;
}

// ---- resources and cost (ops/resources; docs/operations-dashboard.md "Resources and cost") ----

export interface Money {
  currency: string;
  amount: number;
}

/** cpuSeconds / gpuSeconds are null when no attempt measured them, never 0. */
export interface UsageTotals {
  attempts: number;
  wallSeconds: number;
  cpuSeconds: number | null;
  cpuMeasuredAttempts: number;
  gpuSeconds: number | null;
  gpuMeasuredAttempts: number;
  gpuUnmeasuredAttempts: number;
  peakGpuMemoryBytes: number | null;
  artifactBytes: number;
}

export type AttemptOutcome = "SUCCEEDED" | "FAILED" | "INTERRUPTED" | "CANCELLED";

export interface OutcomeUsage {
  outcome: AttemptOutcome;
  attempts: number;
  wallSeconds: number;
  gpuSeconds: number | null;
  estimated: Money[];
  unpricedAttempts: number;
}

export interface CostEstimate {
  kind: "ESTIMATED";
  amounts: Money[];
  pricedAttempts: number;
  unpricedAttempts: number;
  unpricedWallSeconds: number;
  unpricedReasons: Record<string, number>;
  complete: boolean;
}

export interface Bill {
  id: string;
  provider: string;
  periodStart: string;
  periodEnd: string;
  amount: number;
  currency: string;
  evidenceReference: string;
  description: string | null;
  recordedBy: string;
  recordedAt: string;
}

export interface BilledCost {
  available: boolean;
  totals: Money[];
  bills: Bill[];
  note: string;
}

export interface StorageUsage {
  artifactBytes: number;
  rawMediaBytes: number;
  estimatedPerMonth: Money | null;
  estimateBasis: string | null;
  unavailableReason: string | null;
}

export interface VersionUsage {
  scanVersionId: string;
  floorId: string | null;
  versionNumber: number | null;
  status: string | null;
  attempts: number;
  wallSeconds: number;
  gpuSeconds: number | null;
  failedAttempts: number;
  failedWallSeconds: number;
  estimated: Money[];
  unpricedAttempts: number;
  lastEndedAt: string | null;
}

export interface Resources {
  days: number;
  since: string;
  window: UsageTotals;
  allTime: UsageTotals;
  byOutcome: OutcomeUsage[];
  estimate: CostEstimate;
  estimateAllTime: CostEstimate;
  billed: BilledCost;
  storage: StorageUsage;
  perScan: { versions: number; fullyPricedVersions: number; averageEstimated: Money[]; averageWallSeconds: number | null };
  scanVersions: VersionUsage[];
  workers: { workerId: string; resourceClass: string | null; gpuModels: string | null; attempts: number; wallSeconds: number;
    gpuSeconds: number | null; lastEndedAt: string | null }[];
  trend: { day: string; attempts: number; wallSeconds: number; gpuSeconds: number | null; failedWallSeconds: number;
    estimated: Money[]; unpricedAttempts: number }[];
  rateCard: { currency: string; sha256: string; workerClasses: Record<string, { hourly: number; kind: string; source: string }>;
    storagePerGbMonth: { rate: number; source: string } | null } | null;
  stageRunsWithoutUsage: number;
  asOf: string;
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
  resultVersionStatus: "DRAFT" | "FINALIZED" | "ALIGNMENT_REJECTED" | null;
  alignmentConfidence: number | null;
  alignmentResidualM: number | null;
  changedArtifactKinds: string[];
  finalizedAt: string | null;
  /** Whether the result version is the one its floor publishes now. */
  resultVersionCurrent: boolean;
  rejectedAt: string | null;
  /** Why the alignment was rejected (the version's provenance reason). */
  rejectionReason: string | null;
  /** The alignment quality gates that failed, by name (e.g. constraint_ratio, inlier_ratio). */
  failedAlignmentGates: string[];
  stagesCompleted: number;
  stagesTotal: number;
  /** The stage queued or running now, if any. */
  currentStage: string | null;
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
export const getResources = (venueId: string, days: number) => api<Resources>(`${base(venueId)}/resources?days=${days}`);
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
