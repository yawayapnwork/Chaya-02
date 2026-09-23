package dev.chaya.api.ops;

import dev.chaya.api.ops.JobActions.Action;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Wire records of the operations dashboard. Every number here is read from, or computed from, stored rows; ages are
 * measured against the database clock ({@code asOf}) at read time. Nothing is estimated, scored or defaulted: a value
 * that does not exist is null, and the UI says so.
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

    /** counts: every job status (QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED), zero included. */
    public record Jobs(Map<String, Long> counts, List<Job> jobs, int limit, Instant asOf) {}

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

    /** Always present; available=false with a reason when no real cost source exists. No amount is ever invented. */
    public record Cost(boolean available, String currency, Double amount, String reason) {}

    /**
     * Totals are sums of sizes recorded in the database when each object was verified (checksum and size checked
     * against the object store at registration), not a live bucket listing. PII staging artifacts are counted
     * separately: their objects are deleted after privacy preprocessing while the record is kept.
     */
    public record Storage(String rawBucket, String derivedBucket, List<MediaBucket> rawMedia, List<ArtifactKind> artifacts,
                          long artifactCount, long artifactBytes, long piiArtifactCount, long piiArtifactBytes,
                          long piiPurgeIncompleteEvents, Instant lastArtifactAt, List<StageTime> stageTime, Cost cost,
                          Instant asOf) {}

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
                         Instant finalizedAt) {}

    public record Rescans(List<Rescan> rescans, Instant asOf) {}

    // ---- audit ------------------------------------------------------------------------------------

    public record AuditEntry(UUID id, String actorId, String actorType, String action, String resourceType, UUID resourceId,
                             String outcome, Instant occurredAt) {}

    public record Audit(List<AuditEntry> entries, int limit, Instant asOf) {}
}
