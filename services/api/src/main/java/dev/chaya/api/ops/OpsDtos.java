package dev.chaya.api.ops;

import dev.chaya.api.ops.JobActions.Action;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Wire records of the operations dashboard. Every number here is read from, or computed from, stored rows; ages are
 * measured against the database clock ({@code asOf}) at read time. Nothing is scored or defaulted: a value that does not
 * exist is null, and the UI says so. The one kind of estimate is processing and storage cost, computed from measured
 * time and configured rates, always labelled ESTIMATED and kept apart from provider-billed amounts (Resources).
 */
public final class OpsDtos {

    private OpsDtos() {}

    // ---- access ------------------------------------------------------------------------------

    public record Access(UUID venueId, List<String> roles, Map<OpsSection, Boolean> sections, boolean mayControlProcessing) {}

    // ---- overview, current version, freshness ---------------------------------------------------

    /** Latest FINALIZED scan_version of a floor. incremental = it was produced by a region re-scan. */
    public record CurrentVersion(UUID id, int versionNumber, UUID parentVersionId, boolean incremental, Instant finalizedAt,
                                 Double alignmentConfidence, Double alignmentResidualM, List<String> changedArtifactKinds) {}

    /**
     * Latest pipeline run whose ARTIFACT_GENERATION stage succeeded (the same definition the viewer uses, see
     * ReconstructionService). quality is FINAL, PARTIAL or null and must never be shown as final unless FINAL.
     */
    public record LatestReconstruction(UUID runId, Instant generatedAt, String runStatus, String runQuality,
                                       boolean hasViewerAsset) {}

    /**
     * Freshness from timestamps only. lastFullCapture* is the newest capture of the whole floor that produced a
     * reconstruction; lastRegionRescan* is the newest region re-scan whose version was finalized (it refreshes only
     * that region). Ages are seconds between the timestamp and asOf; null when the event never happened.
     */
    public record Freshness(Instant lastFullCaptureAt, Long lastFullCaptureAgeSeconds, Instant lastReconstructedAt,
                            Long lastReconstructedAgeSeconds, Instant lastRegionRescanAt, Long lastRegionRescanAgeSeconds,
                            Instant lastCaptureStartedAt) {}

    public record FloorStatus(UUID floorId, int level, String name, CurrentVersion currentVersion, int draftVersions,
                              LatestReconstruction latestReconstruction, Freshness freshness) {}

    public record Overview(UUID venueId, String venueName, String venueSlug, String timezone, Instant venueCreatedAt,
                           int floorCount, int captureCount, int poiCount, Map<String, Long> jobCounts,
                           List<FloorStatus> floors, Instant asOf) {}

    // ---- coverage ------------------------------------------------------------------------------

    public record UncoveredZone(double centroidX, double centroidY, double areaM2, String reason) {}

    /**
     * Measured coverage of the most recent capture of a floor that has a HUD room outline, recomputed by the capture
     * planner (docs/capture-hud.md). coverageAvailable=false carries the planner's reason; no percentage is invented.
     */
    public record MeasuredCoverage(UUID captureId, Instant captureStartedAt, String captureStatus, boolean coverageAvailable,
                                   String coverageUnavailableReason, Double coveragePercent, Double weightedCoveragePercent,
                                   Double uncoveredAreaM2, List<UncoveredZone> uncoveredZones) {}

    /** gaps: stable codes (NO_RECONSTRUCTION, NO_FINALIZED_VERSION, NO_MEASURED_COVERAGE, COVERAGE_UNAVAILABLE,
     * UNCOVERED_ZONES) derived from the fields beside them. */
    public record FloorCoverage(UUID floorId, int level, String name, boolean hasReconstruction, boolean hasFinalizedVersion,
                                MeasuredCoverage measured, List<String> gaps) {}

    public record Coverage(List<FloorCoverage> floors, Instant asOf) {}

    // ---- processing jobs ------------------------------------------------------------------------

    public record Job(UUID id, String stage, String status, UUID scanId, UUID captureId, UUID runId, String runStatus,
                      int retryCount, int maxRetries, Instant queuedAt, Instant startedAt, Instant finishedAt, String workerId,
                      Instant leaseExpiresAt, String errorCode, String errorMessage, Action retry, Action cancel) {}

    /**
     * A worker that polled for work or heartbeated in the last 24 hours (worker_presence). Workers are shared
     * infrastructure serving every tenant; nothing here names a job or tenant. live: seen within workerLiveAfterSeconds.
     */
    public record WorkerStatus(String workerId, List<String> stages, Instant lastSeenAt, long lastSeenAgeSeconds,
                               Instant lastClaimedAt, boolean live) {}

    /**
     * This venue's queue, measured now.
     *
     * @param claimableQueued    queued jobs a worker may claim (their run's time budget is not spent)
     * @param oldestQueuedAgeSeconds age of the oldest claimable queued job; null when none is queued
     * @param staleRunning       RUNNING jobs whose lease expired: the worker is gone and the lease enforcer (every 30 s)
     *                           has not failed them yet. A value that persists means the enforcer is not running.
     * @param overdueRunning     RUNNING jobs past their run's deadline (the enforcer fails them after the grace period)
     * @param unservedStages     stages of this venue's claimable queued jobs that no live worker offers to run
     */
    public record JobQueue(long claimableQueued, Long oldestQueuedAgeSeconds, long running, long staleRunning, long overdueRunning,
                        List<String> unservedStages, List<WorkerStatus> workers, long workerLiveAfterSeconds) {}

    /** counts: every job status (QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED), zero included. */
    public record Jobs(Map<String, Long> counts, JobQueue queue, List<Job> jobs, int limit, Instant asOf) {}

    // ---- failures -------------------------------------------------------------------------------

    /**
     * One failed execution attempt. Stage-run failures come from the immutable pipeline_stage_run record, so they stay
     * visible after the job is retried; legacy jobs outside a pipeline run come from processing_job.
     * currentJobStatus is what the job is now (e.g. SUCCEEDED after a successful retry).
     */
    public record Failure(String source, UUID jobId, UUID runId, UUID captureId, String stage, int attempt, String errorCode,
                          String errorMessage, Map<String, Object> errorDetails, Integer exitStatus, String workerId,
                          Instant failedAt, String currentJobStatus, Action retry) {}

    public record FailedRun(UUID runId, UUID captureId, String failureStage, String failureCode, String failureMessage,
                            Instant finishedAt, Action retry) {}

    public record CodeCount(String stage, String errorCode, long count) {}

    public record Failures(int days, List<FailedRun> failedRuns, List<Failure> failures, List<CodeCount> countsByCode,
                           Instant asOf) {}

    // ---- storage, processing time and cost -------------------------------------------------------

    public record MediaBucket(String status, long count, long declaredBytes) {}

    public record ArtifactKind(String kind, long count, long bytes, long partialCount) {}

    public record StageTime(String stage, long executions, double totalSeconds, double avgSeconds) {}

    /**
     * The venue's estimated processing cost, all time (the summary of Resources.estimateAllTime). kind is always
     * ESTIMATED: measured worker time x configured rates, never a bill. available=false with a reason when nothing could
     * be priced, or when amounts are in more than one currency; complete=false when some attempts could not be priced
     * (unpricedAttempts), so the amount is a lower bound. No amount is ever invented.
     */
    public record Cost(boolean available, String currency, Double amount, String reason, String kind, boolean complete,
                       long pricedAttempts, long unpricedAttempts) {}

    /**
     * Totals are sums of sizes recorded in the database when each object was verified (checksum and size checked
     * against the object store at registration), not a live bucket listing. PII staging artifacts are counted
     * separately: their objects are deleted after privacy preprocessing while the record is kept.
     */
    public record Storage(String rawBucket, String derivedBucket, List<MediaBucket> rawMedia, List<ArtifactKind> artifacts,
                          long artifactCount, long artifactBytes, long piiArtifactCount, long piiArtifactBytes,
                          long piiPurgeIncompleteEvents, Instant lastArtifactAt, List<StageTime> stageTime, Cost cost,
                          Instant asOf) {}

    // ---- resources and cost (V42 job_attempt_usage) ----------------------------------------------

    public record Money(String currency, double amount) {}

    /** Sums over attempt records. cpuSeconds / gpuSeconds are null when no attempt measured them (never 0), and cover only
     * the cpuMeasuredAttempts / gpuMeasuredAttempts that did. peakGpuMemoryBytes: the largest single-attempt peak. */
    public record UsageTotals(long attempts, double wallSeconds, Double cpuSeconds, long cpuMeasuredAttempts, Double gpuSeconds,
                              long gpuMeasuredAttempts, long gpuUnmeasuredAttempts, Long peakGpuMemoryBytes, long artifactBytes) {}

    /** outcome: SUCCEEDED, FAILED, INTERRUPTED (lease lost, time budget) or CANCELLED. */
    public record OutcomeUsage(String outcome, long attempts, double wallSeconds, Double gpuSeconds, List<Money> estimated,
                               long unpricedAttempts) {}

    /** kind: always ESTIMATED. amounts per currency over the priced attempts; complete only when every attempt was priced.
     * unpricedReasons: why the others were not, by reason code (NO_RATES_CONFIGURED, RESOURCE_CLASS_NOT_REPORTED,
     * NO_RATE_FOR_CLASS). */
    public record CostEstimate(String kind, List<Money> amounts, long pricedAttempts, long unpricedAttempts, double unpricedWallSeconds,
                               Map<String, Long> unpricedReasons, boolean complete) {}

    /** A provider-billed amount an admin recorded with its evidence (an invoice or billing-export reference). */
    public record Bill(UUID id, String provider, LocalDate periodStart, LocalDate periodEnd, double amount, String currency,
                       String evidenceReference, String description, String recordedBy, Instant recordedAt) {}

    /** available=false when no bill has been recorded for the venue. Bills are never computed or split across scans. */
    public record BilledCost(boolean available, List<Money> totals, List<Bill> bills, String note) {}

    /** bytes as recorded at registration; estimatedPerMonth: (artifactBytes + rawMediaBytes) / 2^30 x the configured
     * per-GiB-month rate, or null with unavailableReason. */
    public record StorageUsage(long artifactBytes, long rawMediaBytes, Money estimatedPerMonth, String estimateBasis,
                               String unavailableReason) {}

    /** One scan version's processing: every attempt of every run that built it. failed*: FAILED, INTERRUPTED and
     * CANCELLED attempts (retried work included). */
    public record VersionUsage(UUID scanVersionId, UUID floorId, Integer versionNumber, String status, long attempts, double wallSeconds,
                               Double gpuSeconds, long failedAttempts, double failedWallSeconds, List<Money> estimated,
                               long unpricedAttempts, Instant lastEndedAt) {}

    /** Averages over scan versions with at least one attempt; averageEstimated only over the fullyPricedVersions. */
    public record PerScan(long versions, long fullyPricedVersions, List<Money> averageEstimated, Double averageWallSeconds) {}

    public record WorkerUsage(String workerId, String resourceClass, String gpuModels, long attempts, double wallSeconds, Double gpuSeconds,
                              Instant lastEndedAt) {}

    /** day: UTC date of the attempts' end. */
    public record DayUsage(LocalDate day, long attempts, double wallSeconds, Double gpuSeconds, double failedWallSeconds,
                           List<Money> estimated, long unpricedAttempts) {}

    /**
     * Section 7a. window/byOutcome/estimate/trend/workers cover the last `days`; allTime/estimateAllTime (the venue's
     * cost), scanVersions and perScan cover every recorded attempt. rateCard: the configured card (null: none).
     * stageRunsWithoutUsage: stage records with no attempt record (run before resource tracking existed), not counted.
     */
    public record Resources(int days, Instant since, UsageTotals window, UsageTotals allTime, List<OutcomeUsage> byOutcome,
                            CostEstimate estimate, CostEstimate estimateAllTime, BilledCost billed, StorageUsage storage, PerScan perScan,
                            List<VersionUsage> scanVersions, List<WorkerUsage> workers, List<DayUsage> trend,
                            Map<String, Object> rateCard, long stageRunsWithoutUsage, Instant asOf) {}

    // ---- POI and search analytics ---------------------------------------------------------------

    /** term = normalized query text as logged (search results themselves are not logged). */
    public record TermCount(String term, long count, Double avgResultCount, Instant lastSearchedAt) {}

    public record PoiStats(long total, long manual, long autoDetected, long awaitingEmbedding) {}

    public record SearchAnalytics(int days, long queryCount, long zeroResultCount, Double avgLatencyMs, Double p95LatencyMs,
                                  long distinctSearchers, Instant firstQueryAt, Instant lastQueryAt, List<TermCount> topTerms,
                                  List<TermCount> zeroResultTerms, PoiStats pois, Instant asOf) {}

    // ---- re-scan history --------------------------------------------------------------------------

    public record Rescan(UUID captureId, UUID floorId, String floorName, String operatorId, Instant createdAt,
                         String captureStatus, UUID parentVersionId, int parentVersionNumber, Double regionAreaM2,
                         UUID runId, String runStatus, String runFailureStage, String runFailureCode, Instant runFinishedAt,
                         UUID resultVersionId, Integer resultVersionNumber, String resultVersionStatus,
                         Double alignmentConfidence, Double alignmentResidualM, List<String> changedArtifactKinds,
                         Instant finalizedAt, boolean resultVersionCurrent, Instant rejectedAt, String rejectionReason,
                         List<String> failedAlignmentGates, int stagesCompleted, int stagesTotal, String currentStage) {}

    public record Rescans(List<Rescan> rescans, Instant asOf) {}

    // ---- audit ------------------------------------------------------------------------------------

    public record AuditEntry(UUID id, String actorId, String actorType, String action, String resourceType, UUID resourceId,
                             String outcome, Instant occurredAt) {}

    public record Audit(List<AuditEntry> entries, int limit, Instant asOf) {}
}
