package dev.chaya.api.pipeline;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Wire records of the pipeline API (worker-facing and operator-facing). */
public final class PipelineDtos {

    private PipelineDtos() {}

    // ---- worker-facing ---------------------------------------------------------------------

    /** An input the stage may read. Raw capture media for the first stage, earlier stage outputs after that. */
    public record InputRef(UUID artifactId, String kind, String stage, String bucket, String key, String sha256,
                           String contentType, long sizeBytes, boolean containsPii) {}

    /** Everything a worker needs to execute one stage. `regionGeometry` is set only for an incremental
     * re-scan run (see dev.chaya.api.rescan.RescanService, chaya_worker.stages.region_alignment): the
     * selected region's polygon, {@code {"points": [[x, y], ...]}} in the floor's venue frame. */
    public record WorkOrder(UUID id, UUID organizationId, UUID venueId, UUID scanId, UUID scanVersionId, String stage,
                            UUID runId, int attempt, Instant deadlineAt, boolean privacyEnabled, String derivedBucket,
                            String outputPrefix, List<InputRef> inputs, Map<String, Object> regionGeometry) {}

    public record ArtifactReport(String kind, String key, String sha256, String contentType, long sizeBytes,
                                 boolean containsPii, boolean partial) {}

    /** The stage record the worker submits once, when the stage ends (successfully or not). */
    public record StageReport(String status, Instant startedAt, Instant finishedAt, Map<String, Object> command,
                              List<UUID> inputArtifactIds, Integer exitStatus, ArtifactReport stdout,
                              ArtifactReport stderr, List<ArtifactReport> artifacts, String errorCode,
                              String errorMessage, Map<String, Object> errorDetails) {}

    public record Heartbeat(boolean keepGoing, Instant deadlineAt) {}

    // ---- operator-facing -------------------------------------------------------------------

    public record ArtifactView(UUID id, String kind, String bucket, String key, long sizeBytes, String sha256,
                               String contentType, boolean partial, boolean containsPii) {}

    public record StageRunView(int attempt, String status, Instant startedAt, Instant finishedAt, Integer exitStatus,
                               String errorCode, String errorMessage, Map<String, Object> errorDetails,
                               String outputSha256, String workerId, Map<String, Object> command,
                               ArtifactView stdout, ArtifactView stderr, List<ArtifactView> artifacts) {}

    /** state: PENDING, QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED or NOT_RUN (run ended before it started). */
    public record StageView(String stage, String state, int attempts, StageRunView lastRun) {}

    /**
     * quality is FINAL only for a fully completed run, PARTIAL only for an explicitly time-boxed result that
     * contains a reconstruction, and null otherwise. A PARTIAL result must never be presented as finalized.
     */
    public record RunView(UUID id, String status, String quality, boolean privacyEnabled, int timeBudgetSeconds,
                          Instant startedAt, Instant deadlineAt, Instant finishedAt, String failureStage,
                          String failureCode, String failureMessage, boolean retryable, List<StageView> stages) {}
}
