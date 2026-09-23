package dev.chaya.api.pipeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.chaya.api.audit.AuditService;
import dev.chaya.api.pipeline.PipelineDtos.ArtifactReport;
import dev.chaya.api.pipeline.PipelineDtos.ArtifactView;
import dev.chaya.api.pipeline.PipelineDtos.Heartbeat;
import dev.chaya.api.pipeline.PipelineDtos.InputRef;
import dev.chaya.api.pipeline.PipelineDtos.RunView;
import dev.chaya.api.pipeline.PipelineDtos.StageReport;
import dev.chaya.api.pipeline.PipelineDtos.StageRunView;
import dev.chaya.api.pipeline.PipelineDtos.StageView;
import dev.chaya.api.pipeline.PipelineDtos.WorkOrder;
import dev.chaya.api.processing.JobStage;
import dev.chaya.api.processing.ProcessingJobRepository;
import dev.chaya.api.processing.ProcessingJobRepository.ClaimedJob;
import dev.chaya.api.rescan.PolygonGeometry;
import dev.chaya.api.rescan.RescanProperties;
import dev.chaya.api.security.Actor;
import dev.chaya.api.security.Role;
import dev.chaya.api.storage.ObjectStore;
import dev.chaya.api.storage.StorageProperties;
import dev.chaya.api.storage.StorageException;
import dev.chaya.api.web.ApiException;
import dev.chaya.api.web.NotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The control plane of the reconstruction pipeline. It owns every piece of durable state (runs, jobs,
 * stage records, artifact metadata, leases); the Python worker is a stateless executor that claims a
 * stage job, does the work, and submits one stage report.
 *
 * <p>Invariants enforced here (and by database constraints):
 * <ul>
 *   <li>A run is SUCCEEDED/FINAL only when every planned stage succeeded.</li>
 *   <li>A run is PARTIAL only when the time budget ran out after a reconstruction artifact existed;
 *       it is stored and reported as PARTIAL, never as success.</li>
 *   <li>A failed stage never advances the run; earlier artifacts stay untouched (artifacts are write-once)
 *       and the failed stage can be retried.</li>
 *   <li>With privacy enabled, artifacts that may contain PII are never handed to stages after
 *       PRIVACY_PREPROCESS and their objects are deleted once that stage succeeds.</li>
 * </ul>
 */
@Service
public class PipelineService {

    private static final Logger log = LoggerFactory.getLogger(PipelineService.class);
    private static final Actor SYSTEM = new Actor(Actor.Kind.SERVICE, "system:pipeline", null, Set.of(), Set.of(Role.SERVICE));
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern KIND = Pattern.compile("^[A-Z][A-Z0-9_]{1,39}$");
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private record RunRow(UUID id, UUID orgId, UUID venueId, UUID scanId, UUID scanVersionId, UUID captureId, String status,
                          List<JobStage> stages, boolean privacy, int budgetSeconds, Instant deadline,
                          String failureStage) {}

    private record JobRow(UUID id, UUID orgId, UUID venueId, UUID scanId, UUID runId, JobStage stage, String status,
                          int retryCount, String workerId, Instant startedAt) {}

    private final JdbcClient jdbc;
    private final ProcessingJobRepository jobs;
    private final AuditService audit;
    private final ObjectStore derived;
    private final PipelineProperties props;
    private final RescanProperties rescanProps;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final String derivedBucketName;

    public PipelineService(JdbcClient jdbc, ProcessingJobRepository jobs, AuditService audit,
                           @Qualifier("derived") ObjectStore derived, PipelineProperties props,
                           RescanProperties rescanProps,
                           TransactionTemplate tx, ObjectMapper mapper, StorageProperties storage) {
        this.derivedBucketName = storage.derivedBucket();
        this.jdbc = jdbc;
        this.jobs = jobs;
        this.audit = audit;
        this.derived = derived;
        this.props = props;
        this.rescanProps = rescanProps;
        this.tx = tx;
        this.mapper = mapper;
    }

    // =========================================================================================
    // Starting, retrying, cancelling (operator actions)
    // =========================================================================================

    /** Creates the run and queues its first stage. Must be called inside the caller's transaction. */
    public UUID start(Actor actor, UUID venueId, UUID captureId, UUID scanId, Boolean privacyEnabled, Integer timeBudgetSeconds) {
        return start(actor, venueId, captureId, scanId, null, null, privacyEnabled, timeBudgetSeconds);
    }

    /**
     * Same as the four-stage-agnostic {@link #start(Actor, UUID, UUID, UUID, Boolean, Integer)}, but for an
     * incremental re-scan: {@code scanVersionId} is the DRAFT ScanVersion this run will finalize (or leave
     * DRAFT, on failure -- see {@link #advance}), and {@code plan} is
     * {@link PipelineDefinition#incrementalPlan} rather than the full-venue plan. Called by
     * {@link dev.chaya.api.rescan.RescanService}; ordinary captures go through the other overload with
     * {@code scanVersionId} null and {@code plan} defaulted from {@link PipelineDefinition#plan}.
     */
    public UUID start(Actor actor, UUID venueId, UUID captureId, UUID scanId, UUID scanVersionId, List<JobStage> plan,
                      Boolean privacyEnabled, Integer timeBudgetSeconds) {
        boolean privacy = privacyEnabled == null || privacyEnabled;
        if (!privacy && !actor.roles().contains(Role.ADMIN)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PRIVACY_OPT_OUT_REQUIRES_ADMIN",
                "Only an administrator may run the pipeline without privacy preprocessing.");
        }
        int budget = timeBudgetSeconds == null ? props.timeBudgetSeconds() : timeBudgetSeconds;
        if (budget <= 0 || budget > props.maxTimeBudgetSeconds()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TIME_BUDGET",
                "timeBudgetSeconds must be between 1 and " + props.maxTimeBudgetSeconds());
        }
        derived.ensureBucket();
        List<JobStage> resolvedPlan = plan != null ? plan : PipelineDefinition.plan(privacy);
        UUID runId = jdbc.sql("INSERT INTO pipeline_run (organization_id, venue_id, scan_id, scan_version_id, capture_session_id, "
                + "stages, privacy_enabled, time_budget_seconds, deadline_at, requested_by) "
                + "VALUES (:o, :v, :s, :sv, :c, ARRAY(SELECT jsonb_array_elements_text(CAST(:stages AS jsonb))), :p, :b, "
                + "now() + make_interval(secs => :b), :by) RETURNING id")
            .param("o", actor.organizationId()).param("v", venueId).param("s", scanId).param("sv", scanVersionId).param("c", captureId)
            .param("stages", json(resolvedPlan.stream().map(Enum::name).toList())).param("p", privacy).param("b", budget)
            .param("by", actor.subject()).query(UUID.class).single();
        UUID first = jobs.enqueueForRun(actor.organizationId(), venueId, scanId, scanVersionId, runId, resolvedPlan.get(0));
        audit.success(actor, venueId, "pipeline.start", "pipeline_run", runId,
            Map.of("privacyEnabled", privacy, "timeBudgetSeconds", budget, "firstJobId", first.toString()));
        return runId;
    }

    /** Re-queues the stage a FAILED run stopped at, with a fresh time budget. */
    public RunView retry(Actor actor, UUID venueId, UUID scanId) {
        tx.executeWithoutResult(s -> {
            RunRow run = lockRunByScan(scanId, venueId);
            if (!run.status().equals("FAILED")) {
                throw new ApiException(HttpStatus.CONFLICT, "RUN_NOT_RETRYABLE", "the pipeline run is " + run.status() + "; only a FAILED run can be retried");
            }
            JobStage stage = JobStage.valueOf(run.failureStage());
            Optional<JobRow> last = latestJob(run.id(), stage);
            if (last.isPresent() && last.get().status().equals("FAILED")) {
                jobs.retry(last.get().id()); // bounded by max_retries; 409 when exhausted
            } else {
                jobs.enqueueForRun(run.orgId(), run.venueId(), run.scanId(), run.scanVersionId(), run.id(), stage);
            }
            jdbc.sql("UPDATE pipeline_run SET status = 'RUNNING', finished_at = NULL, failure_stage = NULL, failure_code = NULL, "
                    + "failure_message = NULL, deadline_at = now() + make_interval(secs => time_budget_seconds) WHERE id = :r")
                .param("r", run.id()).update();
            audit.success(actor, venueId, "pipeline.retry", "pipeline_run", run.id(), Map.of("stage", stage.name()));
        });
        return runView(scanId).orElseThrow();
    }

    /** Stops a RUNNING run: cancels its active job, removes PII staging objects and fails the capture. */
    public RunView cancel(Actor actor, UUID venueId, UUID scanId) {
        RunRow run = tx.execute(s -> {
            RunRow r = lockRunByScan(scanId, venueId);
            if (!r.status().equals("RUNNING")) {
                throw new ApiException(HttpStatus.CONFLICT, "RUN_NOT_CANCELLABLE", "the pipeline run is " + r.status());
            }
            jdbc.sql("SELECT id FROM processing_job WHERE run_id = :r AND status IN ('QUEUED', 'RUNNING')").param("r", r.id())
                .query(UUID.class).list().forEach(jobs::cancel);
            jdbc.sql("UPDATE pipeline_run SET status = 'CANCELLED', finished_at = now() WHERE id = :r").param("r", r.id()).update();
            jdbc.sql("UPDATE capture_session SET status = 'FAILED', failure_code = 'PROCESSING_CANCELLED', "
                    + "failure_message = 'processing was cancelled by an operator' WHERE id = :c AND status = 'PROCESSING'")
                .param("c", r.captureId()).update();
            audit.success(actor, venueId, "pipeline.cancel", "pipeline_run", r.id(), Map.of());
            return r;
        });
        purgePii(run, "cancel");
        return runView(scanId).orElseThrow();
    }

    // =========================================================================================
    // Worker protocol: claim, heartbeat, report
    // =========================================================================================

    public Optional<WorkOrder> claim(Actor worker, List<JobStage> stages, String workerId) {
        return tx.execute(s -> jobs.claim(stages, workerId, props.leaseSeconds()).map(j -> {
            audit.successInOrganization(worker, j.organizationId(), j.venueId(), "job.claim", "processing_job", j.id(),
                Map.of("stage", j.stage().name(), "worker", workerId));
            return workOrder(j);
        }));
    }

    private WorkOrder workOrder(ClaimedJob j) {
        if (j.runId() == null) { // legacy job: no run, no inputs
            return new WorkOrder(j.id(), j.organizationId(), j.venueId(), j.scanId(), j.scanVersionId(), j.stage().name(),
                null, j.retryCount() + 1, null, false, null, null, List.of(), null);
        }
        RunRow run = loadRun(j.runId());
        int attempt = j.retryCount() + 1;
        Map<String, Object> regionGeometry = run.scanVersionId() == null ? null : regionGeometryOf(run.scanVersionId());
        return new WorkOrder(j.id(), j.organizationId(), j.venueId(), j.scanId(), j.scanVersionId(), j.stage().name(),
            run.id(), attempt, run.deadline(), run.privacy(), derivedBucketName, prefix(run, j.stage(), attempt),
            inputs(run, j.stage()), regionGeometry);
    }

    private Map<String, Object> regionGeometryOf(UUID scanVersionId) {
        return jdbc.sql("SELECT region_geometry FROM scan_version WHERE id = :v").param("v", scanVersionId)
            .query((rs, i) -> rs.getString("region_geometry")).optional().map(this::parse).orElse(null);
    }

    /** Keys a stage attempt may write: everything under this prefix, and nothing else. */
    static String prefix(RunRow run, JobStage stage, int attempt) {
        return "org/%s/venue/%s/scan/%s/run/%s/%s/attempt-%d/".formatted(run.orgId(), run.venueId(), run.scanId(), run.id(), stage, attempt);
    }

    /**
     * What a stage may read. The first two stages (validation and frame extraction) read the accepted raw
     * capture media. Every later stage reads the outputs of earlier successful stages; once privacy
     * preprocessing has run, anything that may contain PII is withheld.
     */
    private List<InputRef> inputs(RunRow run, JobStage stage) {
        int idx = run.stages().indexOf(stage);
        List<InputRef> inputs = new ArrayList<>();
        if (idx <= 1) {
            inputs.addAll(jdbc.sql("SELECT id, kind, bucket, object_key, verified_sha256, detected_content_type, declared_size_bytes "
                    + "FROM capture_media WHERE capture_session_id = :c AND status = 'ACCEPTED' ORDER BY created_at")
                .param("c", run.captureId())
                .query((rs, i) -> new InputRef(rs.getObject("id", UUID.class), "RAW_" + rs.getString("kind"), null,
                    rs.getString("bucket"), rs.getString("object_key"), rs.getString("verified_sha256"),
                    rs.getString("detected_content_type"), rs.getLong("declared_size_bytes"), true)).list());
        }
        if (idx > 0) {
            boolean afterPrivacy = run.privacy() && run.stages().indexOf(JobStage.PRIVACY_PREPROCESS) < idx;
            inputs.addAll(jdbc.sql("SELECT a.id, a.kind, a.stage, a.bucket, a.object_key, a.checksum_sha256, a.content_type, a.size_bytes, "
                    + "a.contains_pii FROM processing_artifact a JOIN pipeline_stage_run sr ON sr.id = a.stage_run_id "
                    + "WHERE sr.run_id = :r AND sr.status = 'SUCCEEDED' AND left(a.kind, 4) <> 'LOG_' "
                    + (afterPrivacy ? "AND NOT a.contains_pii " : "") + "ORDER BY sr.created_at, a.created_at")
                .param("r", run.id())
                .query((rs, i) -> new InputRef(rs.getObject("id", UUID.class), rs.getString("kind"), rs.getString("stage"),
                    rs.getString("bucket"), rs.getString("object_key"), rs.getString("checksum_sha256"),
                    rs.getString("content_type"), rs.getLong("size_bytes"), rs.getBoolean("contains_pii"))).list());
        }
        if (run.scanVersionId() != null && (stage == JobStage.REGION_ALIGNMENT || stage == JobStage.REGION_SPLICE)) {
            globalCloudInput(run.scanVersionId()).ifPresent(inputs::add);
        }
        return inputs;
    }

    /**
     * The venue's existing reconstruction to align the new region against and splice into: the most
     * recent successful GEOMETRIC_CLEANUP/REGION_SPLICE output (kind SPLAT_MERGED, else SPLAT_CLEAN) of
     * the selected parent ScanVersion's own scan. Never the current (still-building) run's own output --
     * that would be aligning the region against itself. Returned as kind GLOBAL_CLOUD so
     * chaya_worker.stages.region_alignment/region_splice can tell it apart from this run's own SPLAT_CLEAN.
     */
    private Optional<InputRef> globalCloudInput(UUID incrementalScanVersionId) {
        return jdbc.sql("""
                SELECT a.id, a.bucket, a.object_key, a.checksum_sha256, a.content_type, a.size_bytes
                  FROM scan_version incoming
                  JOIN scan_version parent ON parent.id = incoming.parent_version_id
                  JOIN processing_artifact a ON a.scan_id = parent.scan_id
                  JOIN pipeline_stage_run sr ON sr.id = a.stage_run_id
                 WHERE incoming.id = :v AND sr.status = 'SUCCEEDED' AND a.kind IN ('SPLAT_MERGED', 'SPLAT_CLEAN')
                 ORDER BY CASE a.kind WHEN 'SPLAT_MERGED' THEN 0 ELSE 1 END, a.created_at DESC
                 LIMIT 1
                """)
            .param("v", incrementalScanVersionId)
            .query((rs, i) -> new InputRef(rs.getObject("id", UUID.class), "GLOBAL_CLOUD", null,
                rs.getString("bucket"), rs.getString("object_key"), rs.getString("checksum_sha256"),
                rs.getString("content_type"), rs.getLong("size_bytes"), false))
            .optional();
    }

    public Heartbeat heartbeat(UUID jobId, String workerId) {
        boolean alive = jobs.heartbeat(jobId, workerId, props.leaseSeconds());
        Instant deadline = jdbc.sql("SELECT r.deadline_at FROM processing_job j JOIN pipeline_run r ON r.id = j.run_id WHERE j.id = :j")
            .param("j", jobId).query((rs, i) -> rs.getTimestamp(1).toInstant()).optional().orElse(null);
        return new Heartbeat(alive, deadline);
    }

    /** Applies a worker's stage report: verifies artifacts, records the stage, moves the job and the run. */
    public void report(Actor worker, UUID jobId, StageReport r) {
        Runnable afterCommit = tx.execute(s -> {
            JobRow job = lockJob(jobId);
            if (job.runId() == null) {
                throw new ApiException(HttpStatus.CONFLICT, "NOT_A_PIPELINE_JOB", "this job is not part of a pipeline run; use complete/fail");
            }
            if (!job.status().equals("RUNNING")) {
                throw new ApiException(HttpStatus.CONFLICT, "JOB_NOT_RUNNING", "job is " + job.status() + "; the report was not applied");
            }
            RunRow run = lockRun(job.runId());
            if (!run.status().equals("RUNNING")) {
                throw new ApiException(HttpStatus.CONFLICT, "RUN_NOT_ACTIVE", "the pipeline run is " + run.status());
            }
            return applyReport(worker, job, run, r, true);
        });
        afterCommit.run();
    }

    // =========================================================================================
    // Applying a report (shared by workers and the system enforcer)
    // =========================================================================================

    private Runnable applyReport(Actor actor, JobRow job, RunRow run, StageReport r, boolean verifyArtifacts) {
        boolean ok = "SUCCEEDED".equals(r.status());
        if (!ok && !"FAILED".equals(r.status())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REPORT", "status must be SUCCEEDED or FAILED");
        }
        if (ok && r.errorCode() != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REPORT", "a succeeded stage cannot carry an error");
        }
        if (!ok && (blank(r.errorCode()) || blank(r.errorMessage()))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REPORT", "a failed stage needs errorCode and errorMessage");
        }
        if (r.startedAt() == null || r.finishedAt() == null || r.finishedAt().isBefore(r.startedAt()) || r.command() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REPORT", "startedAt, finishedAt (not before startedAt) and command are required");
        }
        int attempt = job.retryCount() + 1;
        String prefix = prefix(run, job.stage(), attempt);
        List<ArtifactReport> outputs = r.artifacts() == null ? List.of() : r.artifacts();
        List<ArtifactReport> all = new ArrayList<>(outputs);
        if (r.stdout() != null) all.add(r.stdout());
        if (r.stderr() != null) all.add(r.stderr());
        boolean afterPrivacy = run.privacy() && run.stages().indexOf(JobStage.PRIVACY_PREPROCESS) < run.stages().indexOf(job.stage());
        boolean beforePrivacy = run.privacy() && run.stages().contains(JobStage.PRIVACY_PREPROCESS)
            && run.stages().indexOf(job.stage()) < run.stages().indexOf(JobStage.PRIVACY_PREPROCESS);
        for (ArtifactReport a : all) {
            validateArtifact(a, prefix, afterPrivacy, beforePrivacy);
            if (verifyArtifacts) {
                verifyStored(a);
            }
        }

        UUID stageRunId = UUID.randomUUID();
        UUID stdoutId = r.stdout() == null ? null : UUID.randomUUID();
        UUID stderrId = r.stderr() == null ? null : UUID.randomUUID();
        String outputSha = outputs.isEmpty() ? null : outputChecksum(outputs);
        jdbc.sql("INSERT INTO pipeline_stage_run (id, organization_id, venue_id, run_id, job_id, stage, attempt, status, command, "
                + "input_artifact_ids, started_at, finished_at, exit_status, stdout_artifact_id, stderr_artifact_id, output_sha256, "
                + "error_code, error_message, error_details, worker_id) VALUES (:id, :o, :v, :run, :job, :stage, :att, :st, "
                + "CAST(:cmd AS jsonb), CAST(:inputs AS uuid[]), :sa, :fa, :exit, :so, :se, :sha, :ec, :em, CAST(:ed AS jsonb), :w)")
            .param("id", stageRunId).param("o", job.orgId()).param("v", job.venueId()).param("run", run.id()).param("job", job.id())
            .param("stage", job.stage().name()).param("att", attempt).param("st", r.status()).param("cmd", json(r.command()))
            .param("inputs", uuidArray(r.inputArtifactIds())).param("sa", Timestamp.from(r.startedAt()))
            .param("fa", Timestamp.from(r.finishedAt())).param("exit", r.exitStatus()).param("so", stdoutId).param("se", stderrId)
            .param("sha", outputSha).param("ec", r.errorCode()).param("em", r.errorMessage())
            .param("ed", r.errorDetails() == null ? null : json(r.errorDetails())).param("w", job.workerId()).update();
        for (ArtifactReport a : outputs) {
            insertArtifact(UUID.randomUUID(), job, stageRunId, a);
        }
        if (r.stdout() != null) insertArtifact(stdoutId, job, stageRunId, forceLog(r.stdout(), "LOG_STDOUT"));
        if (r.stderr() != null) insertArtifact(stderrId, job, stageRunId, forceLog(r.stderr(), "LOG_STDERR"));

        String failCode = r.errorCode();
        String failMessage = r.errorMessage();
        if (ok && job.stage() == JobStage.REGION_ALIGNMENT && run.scanVersionId() != null) {
            String rejection = recordAlignmentAndCheckGate(run, r.command());
            if (rejection != null) {
                ok = false;
                failCode = "ALIGNMENT_CONFIDENCE_BELOW_THRESHOLD";
                failMessage = rejection;
            }
        }
        if (ok && job.stage() == JobStage.REGION_SPLICE && run.scanVersionId() != null) {
            appendChangedArtifactKind(run.scanVersionId(), "SPLAT_MERGED");
        }
        if (ok && job.stage() == JobStage.SEMANTIC_INDEXING) {
            ingestDetectedObjects(job, run, outputs);
        }
        if (ok && job.stage() == JobStage.NAVIGATION_BAKING) {
            ingestNavigationGraph(run, outputs);
        }

        if (ok) {
            jobs.succeed(job.id());
        } else {
            jobs.fail(job.id(), failCode, failMessage);
        }
        audit.successInOrganization(actor, job.orgId(), job.venueId(), ok ? "pipeline.stage_succeeded" : "pipeline.stage_failed",
            "processing_job", job.id(), ok ? Map.of("stage", job.stage().name(), "attempt", attempt)
                : Map.of("stage", job.stage().name(), "attempt", attempt, "code", String.valueOf(failCode)));

        boolean purge = ok && job.stage() == JobStage.PRIVACY_PREPROCESS && run.privacy();
        advance(actor, run, job.stage(), ok, failCode, failMessage, r);
        return () -> {
            if (purge) {
                purgePii(run, "privacy-stage-complete");
            }
        };
    }

    /**
     * REGION_ALIGNMENT's own report already carries the real, measured numbers (see
     * chaya_worker.stages.region_alignment); this records them on the ScanVersion being built regardless
     * of outcome (docs/rescan.md "VERSIONING": a version records its alignment method and confidence even
     * when the run goes on to fail) and independently re-checks confidence against
     * {@link RescanProperties#minAlignmentConfidence()} -- the control plane's own threshold, not merely
     * trusting the worker's. Returns null when the gate passes, or a rejection message when it does not.
     */
    private String recordAlignmentAndCheckGate(RunRow run, Map<String, Object> command) {
        Map<String, Object> config = asMap(command == null ? null : command.get("config"));
        Map<String, Object> alignment = asMap(config == null ? null : config.get("alignment"));
        if (alignment == null || !(alignment.get("confidence") instanceof Number confidenceNumber)) {
            return "REGION_ALIGNMENT reported success without a usable alignment report";
        }
        double confidence = confidenceNumber.doubleValue();
        String method = alignment.get("method") == null ? null : String.valueOf(alignment.get("method"));
        Double residual = alignment.get("inlier_rmse_m") instanceof Number n ? n.doubleValue() : null;
        jdbc.sql("UPDATE scan_version SET alignment_method = :m, alignment_confidence = :c, alignment_residual_m = :r WHERE id = :v")
            .param("m", method).param("c", confidence).param("r", residual).param("v", run.scanVersionId()).update();
        if (confidence < rescanProps.minAlignmentConfidence()) {
            return "alignment confidence " + confidence + " is below the configured threshold "
                + rescanProps.minAlignmentConfidence() + "; refusing to splice a badly aligned region";
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    /** Appends `kind` to changed_artifact_kinds exactly once (docs/rescan.md "VERSIONING": a version
     * records which artifacts it actually changed). No-op for a full-venue run (scanVersionId null). */
    private void appendChangedArtifactKind(UUID scanVersionId, String kind) {
        if (scanVersionId == null) {
            return;
        }
        jdbc.sql("UPDATE scan_version SET changed_artifact_kinds = array_append(changed_artifact_kinds, :k) "
                + "WHERE id = :v AND NOT (:k = ANY(changed_artifact_kinds))")
            .param("k", kind).param("v", scanVersionId).update();
    }

    private void advance(Actor actor, RunRow run, JobStage stage, boolean ok, String failCode, String failMessage, StageReport r) {
        List<JobStage> plan = run.stages();
        int idx = plan.indexOf(stage);
        if (ok) {
            if (idx == plan.size() - 1) {
                finishRun(actor, run, "SUCCEEDED", "FINAL", null, null, null);
                jdbc.sql("UPDATE capture_session SET status = 'COMPLETED' WHERE id = :c AND status = 'PROCESSING'")
                    .param("c", run.captureId()).update();
            } else if (Instant.now().isAfter(run.deadline())) {
                finalizeTimeBoxed(actor, run, plan.get(idx + 1));
            } else {
                jobs.enqueueForRun(run.orgId(), run.venueId(), run.scanId(), run.scanVersionId(), run.id(), plan.get(idx + 1));
            }
            return;
        }
        boolean partialOutput = PipelineDefinition.TIME_LIMIT_EXCEEDED.equals(failCode) && stage == JobStage.SPLAT_RECONSTRUCTION
            && r.artifacts() != null && r.artifacts().stream().anyMatch(a -> a.partial() && PipelineDefinition.RECONSTRUCTION_KINDS.contains(a.kind()));
        if (partialOutput) {
            finishRun(actor, run, "PARTIAL", "PARTIAL", stage.name(), PipelineDefinition.TIME_LIMIT_EXCEEDED,
                "The time budget ran out during " + stage + ". A partial-quality reconstruction was kept; it is not a finalized reconstruction.");
            completeCapture(run);
        } else {
            finishRun(actor, run, "FAILED", null, stage.name(), failCode, failMessage);
        }
    }

    /** The budget is spent before the next stage: PARTIAL if a reconstruction exists, otherwise FAILED. */
    private void finalizeTimeBoxed(Actor actor, RunRow run, JobStage nextStage) {
        boolean hasReconstruction = jdbc.sql("SELECT count(*) FROM processing_artifact a JOIN pipeline_stage_run sr ON sr.id = a.stage_run_id "
                + "WHERE sr.run_id = :r AND sr.status = 'SUCCEEDED' AND a.kind IN (:kinds)").param("r", run.id())
            .param("kinds", PipelineDefinition.RECONSTRUCTION_KINDS)
            .query(Integer.class).single() > 0;
        if (hasReconstruction) {
            finishRun(actor, run, "PARTIAL", "PARTIAL", nextStage.name(), PipelineDefinition.TIME_LIMIT_EXCEEDED,
                "The time budget ran out before " + nextStage + ". The reconstruction is partial quality, not finalized.");
            completeCapture(run);
        } else {
            finishRun(actor, run, "FAILED", null, nextStage.name(), PipelineDefinition.TIME_LIMIT_EXCEEDED,
                "The time budget ran out before " + nextStage + " and no reconstruction exists yet.");
        }
    }

    private void completeCapture(RunRow run) {
        jdbc.sql("UPDATE capture_session SET status = 'COMPLETED' WHERE id = :c AND status = 'PROCESSING'").param("c", run.captureId()).update();
    }

    private void finishRun(Actor actor, RunRow run, String status, String quality, String failStage, String failCode, String failMessage) {
        jdbc.sql("UPDATE pipeline_run SET status = :s, quality = :q, finished_at = now(), failure_stage = :fs, failure_code = :fc, "
                + "failure_message = :fm WHERE id = :r AND status = 'RUNNING'")
            .param("s", status).param("q", quality).param("fs", failStage).param("fc", failCode).param("fm", failMessage)
            .param("r", run.id()).update();
        audit.successInOrganization(actor, run.orgId(), run.venueId(), "pipeline." + status.toLowerCase(), "pipeline_run", run.id(),
            failCode == null ? Map.of() : Map.of("stage", String.valueOf(failStage), "code", failCode));
        if (run.scanVersionId() != null) {
            recordRescanOutcome(actor, run, status, failCode);
        }
    }

    /**
     * The AUDIT record docs/rescan.md requires: who initiated the re-scan (the run's own audit trail
     * already has that, from RescanService's "rescan.initiate"), which region and source version (read
     * from the ScanVersion itself, which recorded them at creation and never changes), the resulting
     * version, and the outcome. Only a true SUCCEEDED run finalizes the ScanVersion -- FAILED, PARTIAL and
     * CANCELLED all leave it DRAFT forever (an abandoned attempt, never silently promoted).
     */
    private void recordRescanOutcome(Actor actor, RunRow run, String status, String failCode) {
        UUID parentVersionId = jdbc.sql("SELECT parent_version_id FROM scan_version WHERE id = :v").param("v", run.scanVersionId())
            .query((rs, i) -> rs.getObject("parent_version_id", UUID.class)).optional().orElse(null);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("sourceVersionId", String.valueOf(parentVersionId));
        meta.put("resultingVersionId", run.scanVersionId().toString());
        meta.put("outcome", status);
        if (failCode != null) {
            meta.put("failureCode", failCode);
        }
        audit.successInOrganization(actor, run.orgId(), run.venueId(), "rescan.outcome", "scan_version", run.scanVersionId(), meta);
        if ("SUCCEEDED".equals(status)) {
            Map<String, Object> provenance = Map.of("runId", run.id().toString(), "stages",
                run.stages().stream().map(Enum::name).toList());
            jdbc.sql("UPDATE scan_version SET status = 'FINALIZED', finalized_at = now(), provenance = CAST(:p AS jsonb) "
                    + "WHERE id = :v AND status = 'DRAFT'")
                .param("p", json(provenance)).param("v", run.scanVersionId()).update();
        }
    }

    // =========================================================================================
    // Validation of what a worker claims to have produced
    // =========================================================================================

    /** Content types a stage before privacy preprocessing may publish WITHOUT the PII flag: reports and logs. */
    static final Set<String> NON_PII_BEFORE_PRIVACY_TYPES = Set.of("application/json", "text/plain");

    private void validateArtifact(ArtifactReport a, String prefix, boolean afterPrivacy, boolean beforePrivacy) {
        if (a.key() == null || !a.key().startsWith(prefix) || a.key().length() == prefix.length()
            || a.key().contains("..") || a.key().contains("//")) {
            throw new ApiException(HttpStatus.CONFLICT, "ARTIFACT_INVALID", "artifact key must be a path under " + prefix);
        }
        if (a.kind() == null || !KIND.matcher(a.kind()).matches() || a.sha256() == null || !SHA256.matcher(a.sha256()).matches()
            || blank(a.contentType()) || a.sizeBytes() < 0) {
            throw new ApiException(HttpStatus.CONFLICT, "ARTIFACT_INVALID", "artifact needs a kind, a lowercase hex sha256, a content type and a size");
        }
        boolean underPii = a.key().startsWith(prefix + "pii/");
        if (a.containsPii() != underPii) {
            throw new ApiException(HttpStatus.CONFLICT, "ARTIFACT_INVALID", "artifacts that may contain PII must live under " + prefix + "pii/ and only those");
        }
        // Defence in depth: the worker's own flag is not trusted for image-bearing output produced before faces,
        // screens and documents were anonymised. Anything but a report or log must be flagged (and so withheld from
        // later stages and purged), whatever the worker says.
        if (beforePrivacy && !a.containsPii() && !NON_PII_BEFORE_PRIVACY_TYPES.contains(normalizedType(a.contentType()))) {
            throw new ApiException(HttpStatus.CONFLICT, "PII_FLAG_REQUIRED",
                "stages before privacy preprocessing must flag " + a.contentType() + " output as possibly containing PII");
        }
        if (a.containsPii() && afterPrivacy) {
            throw new ApiException(HttpStatus.CONFLICT, "PII_AFTER_PRIVACY", "stages after privacy preprocessing must not produce artifacts that contain PII");
        }
        if (a.partial() && !PipelineDefinition.RECONSTRUCTION_KINDS.contains(a.kind())) {
            throw new ApiException(HttpStatus.CONFLICT, "ARTIFACT_INVALID", "only reconstruction artifacts can be marked partial");
        }
    }

    /** The object must really exist in the derived bucket with the claimed size. */
    private void verifyStored(ArtifactReport a) {
        long size;
        try {
            size = derived.size(a.key());
        } catch (dev.chaya.api.storage.StorageException e) {
            throw new ApiException(HttpStatus.CONFLICT, "ARTIFACT_MISSING", "artifact " + a.key() + " was not found in storage");
        }
        if (size != a.sizeBytes()) {
            throw new ApiException(HttpStatus.CONFLICT, "ARTIFACT_SIZE_MISMATCH",
                "artifact " + a.key() + " is " + size + " bytes in storage but was reported as " + a.sizeBytes());
        }
    }

    private static ArtifactReport forceLog(ArtifactReport a, String kind) {
        return new ArtifactReport(kind, a.key(), a.sha256(), a.contentType(), a.sizeBytes(), false, false);
    }

    private void insertArtifact(UUID id, JobRow job, UUID stageRunId, ArtifactReport a) {
        jdbc.sql("INSERT INTO processing_artifact (id, organization_id, venue_id, scan_id, job_id, stage, bucket, object_key, "
                + "checksum_sha256, content_type, size_bytes, kind, stage_run_id, contains_pii, partial) "
                + "VALUES (:id, :o, :v, :s, :j, :st, :b, :k, :sha, :ct, :sz, :kind, :sr, :pii, :part)")
            .param("id", id).param("o", job.orgId()).param("v", job.venueId()).param("s", job.scanId()).param("j", job.id())
            .param("st", job.stage().name()).param("b", derivedBucketName).param("k", a.key()).param("sha", a.sha256())
            .param("ct", a.contentType()).param("sz", a.sizeBytes()).param("kind", a.kind()).param("sr", stageRunId)
            .param("pii", a.containsPii()).param("part", a.partial()).update();
    }

    private static String outputChecksum(List<ArtifactReport> outputs) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            outputs.stream().sorted(Comparator.comparing(ArtifactReport::key)).forEach(a ->
                md.update((a.kind() + "|" + a.key() + "|" + a.sha256() + "\n").getBytes(StandardCharsets.UTF_8)));
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Deletes objects of artifacts flagged as possibly containing PII. Best effort, audited, idempotent. */
    private void purgePii(RunRow run, String reason) {
        List<String> keys = jdbc.sql("SELECT a.object_key FROM processing_artifact a JOIN pipeline_stage_run sr ON sr.id = a.stage_run_id "
                + "WHERE sr.run_id = :r AND a.contains_pii").param("r", run.id()).query(String.class).list();
        int deleted = 0;
        int failed = 0;
        for (String key : keys) {
            try {
                derived.delete(key);
                deleted++;
            } catch (RuntimeException e) {
                failed++;
                log.error("could not delete PII staging object {}: {}", key, e.getMessage());
            }
        }
        final int d = deleted;
        final int f = failed;
        tx.executeWithoutResult(s -> audit.successInOrganization(SYSTEM, run.orgId(), run.venueId(),
            f == 0 ? "pipeline.pii_purged" : "pipeline.pii_purge_incomplete", "pipeline_run", run.id(),
            Map.of("reason", reason, "deleted", d, "failed", f)));
    }

    /**
     * SEMANTIC_INDEXING (chaya_worker.stages.semantic_indexing) has no database access itself -- workers
     * never touch the database, see ARCHITECTURE.md -- so it publishes a DETECTED_OBJECTS artifact and
     * this is where those detections become real poi/poi_version rows with pgvector embeddings
     * (source = 'AUTO_DETECTED'). A missing or unparseable artifact is logged and skipped, never treated
     * as a reason to fail the stage retroactively: the stage itself already succeeded and reported its
     * output; this is best-effort ingestion of that output.
     */
    private void ingestDetectedObjects(JobRow job, RunRow run, List<ArtifactReport> outputs) {
        ArtifactReport artifact = outputs.stream().filter(a -> a.kind().equals("DETECTED_OBJECTS")).findFirst().orElse(null);
        if (artifact == null) {
            return;
        }
        Map<String, Object> doc;
        try (InputStream in = derived.open(artifact.key())) {
            doc = mapper.readValue(in, MAP);
        } catch (IOException | StorageException e) {
            log.error("could not read DETECTED_OBJECTS artifact {}: {}", artifact.key(), e.getMessage());
            return;
        }
        UUID floorId = jdbc.sql("SELECT floor_id FROM capture_session WHERE id = :c")
            .param("c", run.captureId()).query(UUID.class).optional().orElse(null);
        if (floorId == null) {
            log.warn("run {} has no floor on its capture session; skipping AUTO_DETECTED POI ingestion", run.id());
            return;
        }
        int superseded = 0;
        if (run.scanVersionId() != null) {
            // Incremental re-scan: SEMANTIC_INDEXING only ever saw the captured region (the worker has no
            // way to detect objects outside frames it was never given), so only that region's POIs are
            // stale. Everything outside it is left completely untouched -- "only affected semantic objects
            // need re-indexing" (docs/rescan.md "SEARCH") is a direct consequence of that, not extra logic.
            superseded = supersedePoisInRegion(run, floorId);
        }
        String embeddingModel = (String) doc.get("embedding_model");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> objects = (List<Map<String, Object>>) doc.getOrDefault("objects", List.of());
        int inserted = 0;
        for (Map<String, Object> obj : objects) {
            if (insertDetectedPoi(job, run, floorId, obj, embeddingModel)) {
                inserted++;
            }
        }
        if (inserted > 0) {
            appendChangedArtifactKind(run.scanVersionId(), "DETECTED_OBJECTS");
        }
        log.info("ingested {} of {} auto-detected objects as POIs for run {} ({} superseded in the changed region)",
            inserted, objects.size(), run.id(), superseded);
        audit.successInOrganization(SYSTEM, run.orgId(), run.venueId(), "semantic_indexing.ingested", "pipeline_run", run.id(),
            Map.of("objectsDetected", objects.size(), "poisCreated", inserted, "poisSuperseded", superseded));
    }

    /** Soft-deletes every non-deleted POI on this floor whose latest position falls inside the ScanVersion's
     * region_geometry polygon -- the "affected" set that SEMANTIC_INDEXING's fresh region-only detections
     * are about to replace. A POI outside the polygon is never touched. */
    @SuppressWarnings("unchecked")
    private int supersedePoisInRegion(RunRow run, UUID floorId) {
        Map<String, Object> geometry = regionGeometryOf(run.scanVersionId());
        List<List<Number>> points = geometry == null ? null : (List<List<Number>>) geometry.get("points");
        if (points == null || points.size() < 3) {
            log.warn("run {} is an incremental re-scan but its ScanVersion has no usable region_geometry; "
                + "no existing POIs will be superseded", run.id());
            return 0;
        }
        List<Map<String, Object>> candidates = jdbc.sql("""
                SELECT p.id, v.x, v.y FROM poi p JOIN poi_version v ON v.poi_id = p.id
                 WHERE p.venue_id = :venue AND p.floor_id = :floor AND p.deleted_at IS NULL
                   AND v.version_number = (SELECT max(version_number) FROM poi_version WHERE poi_id = p.id)
                """).param("venue", run.venueId()).param("floor", floorId).query().listOfRows();
        int superseded = 0;
        for (Map<String, Object> row : candidates) {
            double x = ((Number) row.get("x")).doubleValue();
            double y = ((Number) row.get("y")).doubleValue();
            if (PolygonGeometry.pointInPolygon(x, y, points)) {
                jdbc.sql("UPDATE poi SET deleted_at = now() WHERE id = :p").param("p", row.get("id")).update();
                superseded++;
            }
        }
        return superseded;
    }

    @SuppressWarnings("unchecked")
    private boolean insertDetectedPoi(JobRow job, RunRow run, UUID floorId, Map<String, Object> obj, String embeddingModel) {
        List<Number> position = obj.get("position") instanceof List<?> p ? (List<Number>) p : null;
        List<Number> embedding = obj.get("embedding") instanceof List<?> e ? (List<Number>) e : null;
        if (position == null || position.size() != 3 || embedding == null || embedding.isEmpty()) {
            log.warn("job {}: skipping a detected object with a malformed position/embedding", job.id());
            return false;
        }
        String label = String.valueOf(obj.getOrDefault("label", "object"));
        double confidence = obj.get("confidence") instanceof Number n ? n.doubleValue() : 0.0;
        String bboxJson = null;
        if (obj.get("bbox_px") != null) {
            try {
                bboxJson = mapper.writeValueAsString(obj.get("bbox_px"));
            } catch (JsonProcessingException e) {
                bboxJson = null;
            }
        }
        UUID poiId = jdbc.sql("INSERT INTO poi (organization_id, venue_id, floor_id) VALUES (:o, :v, :f) RETURNING id")
            .param("o", run.orgId()).param("v", run.venueId()).param("f", floorId).query(UUID.class).single();
        jdbc.sql("INSERT INTO poi_version (organization_id, venue_id, poi_id, version_number, label, tags, x, y, z, "
                + "embedding, embedding_model, source, detection_confidence, bounding_box, pipeline_run_id, created_by) "
                + "VALUES (:o, :v, :p, 1, :label, '{}', :x, :y, :z, CAST(:emb AS vector), :model, 'AUTO_DETECTED', :conf, "
                + "CAST(:bbox AS jsonb), :run, 'system:semantic-indexing')")
            .param("o", run.orgId()).param("v", run.venueId()).param("p", poiId).param("label", label)
            .param("x", position.get(0).doubleValue()).param("y", position.get(1).doubleValue()).param("z", position.get(2).doubleValue())
            .param("emb", vectorLiteral(embedding)).param("model", embeddingModel).param("conf", confidence)
            .param("bbox", bboxJson).param("run", run.id()).update();
        return true;
    }

    private static String vectorLiteral(List<Number> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(values.get(i).doubleValue());
        }
        return sb.append(']').toString();
    }

    /**
     * NAVIGATION_BAKING (chaya_worker.stages.navigation_baking) publishes a NAVIGATION_GRAPH artifact
     * with two profile graphs (STANDARD, STEP_FREE -- see chaya_worker.navmesh.build_routing_graphs);
     * this turns each into a navigation_graph/navigation_node/navigation_edge DRAFT, then promotes it to
     * ACTIVE (retiring whichever graph was previously ACTIVE for that venue/floor/profile), so
     * dev.chaya.api.navigation.RouteService always finds at most one ACTIVE graph to route against. A
     * missing or unparseable artifact is logged and skipped, same as ingestDetectedObjects.
     */
    private void ingestNavigationGraph(RunRow run, List<ArtifactReport> outputs) {
        ArtifactReport artifact = outputs.stream().filter(a -> a.kind().equals("NAVIGATION_GRAPH")).findFirst().orElse(null);
        if (artifact == null) {
            return;
        }
        Map<String, Object> doc;
        try (InputStream in = derived.open(artifact.key())) {
            doc = mapper.readValue(in, MAP);
        } catch (IOException | StorageException e) {
            log.error("could not read NAVIGATION_GRAPH artifact {}: {}", artifact.key(), e.getMessage());
            return;
        }
        UUID floorId = jdbc.sql("SELECT floor_id FROM capture_session WHERE id = :c")
            .param("c", run.captureId()).query(UUID.class).optional().orElse(null);
        if (floorId == null) {
            log.warn("run {} has no floor on its capture session; skipping navigation graph ingestion", run.id());
            return;
        }
        Object graphsRaw = doc.get("graphs");
        if (!(graphsRaw instanceof Map<?, ?> graphs)) {
            return;
        }
        int ingested = 0;
        for (String profile : List.of("STANDARD", "STEP_FREE")) {
            Object graphRaw = graphs.get(profile);
            if (graphRaw instanceof Map<?, ?> graph && ingestOneNavigationGraph(run, floorId, profile, graph)) {
                ingested++;
            }
        }
        if (ingested > 0) {
            appendChangedArtifactKind(run.scanVersionId(), "NAVIGATION_GRAPH");
        }
        log.info("ingested {} navigation graph profile(s) for run {}", ingested, run.id());
        audit.successInOrganization(SYSTEM, run.orgId(), run.venueId(), "navigation_baking.ingested", "pipeline_run", run.id(),
            Map.of("profilesIngested", ingested));
    }

    @SuppressWarnings("unchecked")
    private boolean ingestOneNavigationGraph(RunRow run, UUID floorId, String profile, Map<?, ?> graph) {
        Object nodesRaw = graph.get("nodes");
        Object edgesRaw = graph.get("edges");
        List<Map<String, Object>> nodesDoc = nodesRaw instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
        List<Map<String, Object>> edgesDoc = edgesRaw instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
        if (nodesDoc.isEmpty()) {
            return false;
        }

        UUID previousActive = jdbc.sql("SELECT id FROM navigation_graph WHERE venue_id = :v AND floor_id = :f AND profile = :p AND status = 'ACTIVE'")
            .param("v", run.venueId()).param("f", floorId).param("p", profile).query(UUID.class).optional().orElse(null);
        if (previousActive != null) {
            jdbc.sql("UPDATE navigation_graph SET status = 'RETIRED' WHERE id = :g").param("g", previousActive).update();
        }

        UUID graphId = jdbc.sql("INSERT INTO navigation_graph (organization_id, venue_id, floor_id, profile, status) "
                + "VALUES (:o, :v, :f, :p, 'DRAFT') RETURNING id")
            .param("o", run.orgId()).param("v", run.venueId()).param("f", floorId).param("p", profile).query(UUID.class).single();

        Map<String, UUID> nodeIds = new LinkedHashMap<>();
        for (Map<String, Object> n : nodesDoc) {
            String localId = String.valueOf(n.get("local_id"));
            String kind = String.valueOf(n.getOrDefault("kind", "WAYPOINT"));
            String connectorType = n.get("connector_type") == null ? null : String.valueOf(n.get("connector_type"));
            UUID nodeId = jdbc.sql("INSERT INTO navigation_node (organization_id, venue_id, graph_id, floor_id, kind, x, y, z, connector_type) "
                    + "VALUES (:o, :v, :g, :f, :kind, :x, :y, :z, :ct) RETURNING id")
                .param("o", run.orgId()).param("v", run.venueId()).param("g", graphId).param("f", floorId).param("kind", kind)
                .param("x", ((Number) n.get("x")).doubleValue()).param("y", ((Number) n.get("y")).doubleValue())
                .param("z", ((Number) n.get("z")).doubleValue()).param("ct", connectorType).query(UUID.class).single();
            nodeIds.put(localId, nodeId);
        }
        for (Map<String, Object> e : edgesDoc) {
            UUID from = nodeIds.get(String.valueOf(e.get("from")));
            UUID to = nodeIds.get(String.valueOf(e.get("to")));
            Object lengthRaw = e.get("length_m");
            if (from == null || to == null || !(lengthRaw instanceof Number lengthNumber) || lengthNumber.doubleValue() <= 0) {
                continue; // length_m has a CHECK (> 0); a degenerate/unmapped edge is skipped, not fatal
            }
            boolean stepFree = Boolean.TRUE.equals(e.get("step_free"));
            Object clearanceRaw = e.get("min_clearance_m");
            // min_clearance_m has a CHECK (> 0): a non-positive value means "no real clearance measurement",
            // stored as NULL (unknown) rather than violating the constraint or claiming a false measurement.
            Double clearance = clearanceRaw instanceof Number n && n.doubleValue() > 0 ? n.doubleValue() : null;
            jdbc.sql("INSERT INTO navigation_edge (organization_id, venue_id, graph_id, from_node_id, to_node_id, length_m, step_free, "
                    + "bidirectional, min_clearance_m) VALUES (:o, :v, :g, :from, :to, :len, :sf, true, :clear)")
                .param("o", run.orgId()).param("v", run.venueId()).param("g", graphId).param("from", from).param("to", to)
                .param("len", lengthNumber.doubleValue()).param("sf", stepFree).param("clear", clearance).update();
        }
        jdbc.sql("UPDATE navigation_graph SET status = 'ACTIVE' WHERE id = :g").param("g", graphId).update();
        return true;
    }

    // =========================================================================================
    // Enforcement: lost workers and the time box
    // =========================================================================================

    /** Fails jobs whose worker stopped heartbeating, and jobs that overran the run deadline by the grace period. */
    public int enforceLeasesAndDeadlines() {
        int handled = 0;
        List<UUID> expired = jdbc.sql("SELECT id FROM processing_job WHERE status = 'RUNNING' AND lease_expires_at < now()")
            .query(UUID.class).list();
        for (UUID id : expired) {
            handled += failSystem(id, PipelineDefinition.WORKER_LOST, "The worker stopped sending heartbeats; the stage was abandoned. It can be retried.") ? 1 : 0;
        }
        List<UUID> overrun = jdbc.sql("SELECT j.id FROM processing_job j JOIN pipeline_run r ON r.id = j.run_id WHERE j.status = 'RUNNING' "
                + "AND r.deadline_at + make_interval(secs => :grace) < now()").param("grace", props.deadlineGraceSeconds())
            .query(UUID.class).list();
        for (UUID id : overrun) {
            handled += failSystem(id, PipelineDefinition.TIME_LIMIT_EXCEEDED, "The stage was still running after the run's time budget and grace period.") ? 1 : 0;
        }
        List<UUID> stale = jdbc.sql("SELECT j.id FROM processing_job j JOIN pipeline_run r ON r.id = j.run_id WHERE j.status = 'QUEUED' "
                + "AND r.status = 'RUNNING' AND r.deadline_at < now()").query(UUID.class).list();
        for (UUID id : stale) {
            handled += tx.execute(s -> {
                JobRow job = lockJob(id);
                if (!job.status().equals("QUEUED")) return 0;
                RunRow run = lockRun(job.runId());
                if (!run.status().equals("RUNNING")) return 0;
                jobs.cancel(id);
                finalizeTimeBoxed(SYSTEM, run, job.stage());
                return 1;
            });
        }
        return handled;
    }

    private boolean failSystem(UUID jobId, String code, String message) {
        Runnable after = tx.execute(s -> {
            JobRow job = lockJob(jobId);
            if (!job.status().equals("RUNNING")) return null;
            Instant started = job.startedAt() == null ? Instant.now() : job.startedAt();
            if (job.runId() == null) { // legacy job without a run
                jobs.fail(jobId, code, message);
                return () -> { };
            }
            RunRow run = lockRun(job.runId());
            if (!run.status().equals("RUNNING")) return null;
            StageReport report = new StageReport("FAILED", started, Instant.now(), Map.of("synthetic", "enforced-by-control-plane", "reason", code),
                List.of(), null, null, null, List.of(), code, message, Map.of());
            return applyReport(SYSTEM, job, run, report, false);
        });
        if (after == null) return false;
        after.run();
        return true;
    }

    // =========================================================================================
    // Reads
    // =========================================================================================

    public Optional<RunView> runView(UUID scanId) {
        Optional<Map<String, Object>> row = jdbc.sql("SELECT * FROM pipeline_run WHERE scan_id = :s").param("s", scanId).query().listOfRows().stream().findFirst();
        if (row.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> r = row.get();
        UUID runId = (UUID) r.get("id");
        List<String> plan = stringList(r.get("stages"));
        List<StageView> stages = new ArrayList<>();
        for (String stage : plan) {
            List<Map<String, Object>> runs = jdbc.sql("SELECT * FROM pipeline_stage_run WHERE run_id = :r AND stage = :s ORDER BY attempt")
                .param("r", runId).param("s", stage).query().listOfRows();
            Optional<String> jobStatus = jdbc.sql("SELECT status FROM processing_job WHERE run_id = :r AND stage = :s ORDER BY created_at DESC LIMIT 1")
                .param("r", runId).param("s", stage).query(String.class).optional();
            StageRunView last = runs.isEmpty() ? null : stageRunView(runs.get(runs.size() - 1));
            String state;
            if (last != null && last.status().equals("SUCCEEDED")) {
                state = "SUCCEEDED";
            } else if (jobStatus.isPresent()) {
                state = jobStatus.get();
            } else {
                // A FAILED run can be retried, so its remaining stages are still pending; a finished run will never start them.
                state = List.of("RUNNING", "FAILED").contains(r.get("status")) ? "PENDING" : "NOT_RUN";
            }
            stages.add(new StageView(stage, state, runs.size(), last));
        }
        String status = (String) r.get("status");
        return Optional.of(new RunView(runId, status, (String) r.get("quality"), (Boolean) r.get("privacy_enabled"),
            ((Number) r.get("time_budget_seconds")).intValue(), instant(r.get("started_at")), instant(r.get("deadline_at")),
            instant(r.get("finished_at")), (String) r.get("failure_stage"), (String) r.get("failure_code"),
            (String) r.get("failure_message"), status.equals("FAILED"), stages));
    }

    private StageRunView stageRunView(Map<String, Object> sr) {
        UUID id = (UUID) sr.get("id");
        List<ArtifactView> arts = jdbc.sql("SELECT id, kind, bucket, object_key, size_bytes, checksum_sha256, content_type, partial, contains_pii "
                + "FROM processing_artifact WHERE stage_run_id = :s ORDER BY created_at").param("s", id)
            .query((rs, i) -> new ArtifactView(rs.getObject("id", UUID.class), rs.getString("kind"), rs.getString("bucket"),
                rs.getString("object_key"), rs.getLong("size_bytes"), rs.getString("checksum_sha256"), rs.getString("content_type"),
                rs.getBoolean("partial"), rs.getBoolean("contains_pii"))).list();
        ArtifactView out = arts.stream().filter(a -> a.kind().equals("LOG_STDOUT")).findFirst().orElse(null);
        ArtifactView err = arts.stream().filter(a -> a.kind().equals("LOG_STDERR")).findFirst().orElse(null);
        return new StageRunView(((Number) sr.get("attempt")).intValue(), (String) sr.get("status"), instant(sr.get("started_at")),
            instant(sr.get("finished_at")), sr.get("exit_status") == null ? null : ((Number) sr.get("exit_status")).intValue(),
            (String) sr.get("error_code"), (String) sr.get("error_message"), parse(sr.get("error_details")),
            sr.get("output_sha256") == null ? null : sr.get("output_sha256").toString().trim(), (String) sr.get("worker_id"),
            parse(sr.get("command")), out, err, arts.stream().filter(a -> !a.kind().startsWith("LOG_")).toList());
    }

    // =========================================================================================
    // helpers
    // =========================================================================================

    private RunRow lockRunByScan(UUID scanId, UUID venueId) {
        return jdbc.sql("SELECT * FROM pipeline_run WHERE scan_id = :s AND venue_id = :v FOR UPDATE").param("s", scanId).param("v", venueId)
            .query(this::mapRun).optional().orElseThrow(() -> new NotFoundException("no pipeline run for this capture"));
    }

    private RunRow lockRun(UUID runId) {
        return jdbc.sql("SELECT * FROM pipeline_run WHERE id = :r FOR UPDATE").param("r", runId).query(this::mapRun).single();
    }

    private RunRow loadRun(UUID runId) {
        return jdbc.sql("SELECT * FROM pipeline_run WHERE id = :r").param("r", runId).query(this::mapRun).single();
    }

    private RunRow mapRun(ResultSet rs, int i) throws SQLException {
        Array arr = rs.getArray("stages");
        List<JobStage> stages = new ArrayList<>();
        for (Object o : (Object[]) arr.getArray()) {
            stages.add(JobStage.valueOf(o.toString()));
        }
        return new RunRow(rs.getObject("id", UUID.class), rs.getObject("organization_id", UUID.class), rs.getObject("venue_id", UUID.class),
            rs.getObject("scan_id", UUID.class), rs.getObject("scan_version_id", UUID.class), rs.getObject("capture_session_id", UUID.class),
            rs.getString("status"), stages, rs.getBoolean("privacy_enabled"), rs.getInt("time_budget_seconds"),
            rs.getTimestamp("deadline_at").toInstant(), rs.getString("failure_stage"));
    }

    private JobRow lockJob(UUID jobId) {
        return jdbc.sql("SELECT id, organization_id, venue_id, scan_id, run_id, stage, status, retry_count, worker_id, started_at "
                + "FROM processing_job WHERE id = :j FOR UPDATE").param("j", jobId)
            .query((rs, i) -> new JobRow(rs.getObject("id", UUID.class), rs.getObject("organization_id", UUID.class),
                rs.getObject("venue_id", UUID.class), rs.getObject("scan_id", UUID.class), rs.getObject("run_id", UUID.class),
                JobStage.valueOf(rs.getString("stage")), rs.getString("status"), rs.getInt("retry_count"), rs.getString("worker_id"),
                rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toInstant()))
            .optional().orElseThrow(() -> new NotFoundException("job not found"));
    }

    private Optional<JobRow> latestJob(UUID runId, JobStage stage) {
        return jdbc.sql("SELECT id FROM processing_job WHERE run_id = :r AND stage = :s ORDER BY created_at DESC LIMIT 1")
            .param("r", runId).param("s", stage.name()).query(UUID.class).optional().map(this::lockJob);
    }

    static String normalizedType(String contentType) {
        int semicolon = contentType.indexOf(';');
        return (semicolon >= 0 ? contentType.substring(0, semicolon) : contentType).trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static Instant instant(Object ts) {
        return ts == null ? null : ((Timestamp) ts).toInstant();
    }

    private static String uuidArray(List<UUID> ids) {
        return ids == null ? "{}" : "{" + String.join(",", ids.stream().map(UUID::toString).toList()) + "}";
    }

    private static List<String> stringList(Object sqlArray) {
        try {
            List<String> out = new ArrayList<>();
            for (Object o : (Object[]) ((Array) sqlArray).getArray()) {
                out.add(o.toString());
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Object> parse(Object jsonb) {
        if (jsonb == null) {
            return null;
        }
        try {
            return mapper.readValue(jsonb.toString(), MAP);
        } catch (JsonProcessingException e) {
            return new LinkedHashMap<>(Map.of("unreadable", true));
        }
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_JSON", "value is not serializable");
        }
    }
}
