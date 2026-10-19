package dev.chaya.api.cost;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes job_attempt_usage (V42): one row per job attempt, when the attempt ends, inside the transaction that ends it
 * (PipelineService). The primary key (job_id, attempt) makes a second record of the same attempt a no-op, so a duplicate
 * report, a report racing the lease enforcer or a retried settlement can never count an attempt twice.
 *
 * <p>Pricing: attempt wall seconds / 3600 x the hourly rate of the worker class it reported (RateCard), with the rate
 * set, the entry and the formula stored on the row. No rate card, no reported class, or a class the card does not
 * list leaves the row unpriced with that reason. It is never priced as 0.
 */
@Service
public class ResourceUsageRecorder {

    private static final Logger log = LoggerFactory.getLogger(ResourceUsageRecorder.class);
    public static final String KIND = "ESTIMATED";
    static final String FORMULA = "wall_seconds / 3600 x hourly rate of the worker's resource class";

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final RateCard card;
    private final TransactionTemplate own;
    private final AtomicReference<UUID> rateSetId = new AtomicReference<>();

    public ResourceUsageRecorder(JdbcClient jdbc, ObjectMapper mapper, CostProperties props, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.card = RateCard.parse(props.rates(), mapper); // fails startup on an invalid card
        this.own = new TransactionTemplate(txm);
        this.own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        if (card != null) {
            log.info("cost rate card {} loaded: {} worker class(es), currency {}", card.sha256().substring(0, 12),
                card.workerClasses().size(), card.currency());
        }
    }

    /** The configured rate card, or null. */
    public RateCard card() {
        return card;
    }

    /**
     * How an attempt ended. outcome: SUCCEEDED, FAILED (as the stage run records it), INTERRUPTED (the control plane ended
     * it: lease lost, time budget) or CANCELLED. endBasis: see V42.
     */
    public record AttemptEnd(UUID jobId, int attempt, UUID orgId, UUID venueId, UUID scanId, UUID runId, UUID scanVersionId,
                             String stage, UUID stageRunId, String outcome, String workerId, Instant startedAt, Instant endedAt,
                             String endBasis, long artifactBytes, ResourceMeasurements measured) {}

    /** True if this call recorded the attempt; false if it already was. */
    public boolean record(AttemptEnd a) {
        Instant ended = a.endedAt().isBefore(a.startedAt()) ? a.startedAt() : a.endedAt();
        double wall = Duration.between(a.startedAt(), ended).toNanos() / 1e9;
        ResourceMeasurements m = a.measured();
        Price p = price(m.resourceClass(), wall);
        int n = jdbc.sql("""
                INSERT INTO job_attempt_usage (job_id, attempt, organization_id, venue_id, scan_id, run_id, scan_version_id, stage,
                    stage_run_id, outcome, worker_id, resource_class, started_at, ended_at, end_basis, wall_seconds, cpu_seconds,
                    gpu_seconds, gpu_model, gpu_peak_memory_bytes, gpu_unmeasured_reason, artifact_bytes, measurements,
                    cost_rate_set_id, cost_currency, estimated_cost, cost_basis, cost_unavailable_reason)
                VALUES (:job, :att, :o, :v, :scan, :run, :sv, :stage, :sr, :outcome, :worker, :rc, :sa, :ea, :basis, :wall, :cpu,
                    :gpu, :model, :peak, :why, :bytes, CAST(:m AS jsonb), :rs, :cur, :cost, CAST(:cb AS jsonb), :cwhy)
                ON CONFLICT (job_id, attempt) DO NOTHING
                """)
            .param("job", a.jobId()).param("att", a.attempt()).param("o", a.orgId()).param("v", a.venueId()).param("scan", a.scanId())
            .param("run", a.runId()).param("sv", a.scanVersionId()).param("stage", a.stage()).param("sr", a.stageRunId())
            .param("outcome", a.outcome()).param("worker", a.workerId()).param("rc", m.resourceClass())
            .param("sa", Timestamp.from(a.startedAt())).param("ea", Timestamp.from(ended)).param("basis", a.endBasis())
            .param("wall", wall).param("cpu", m.cpuSeconds()).param("gpu", m.gpuSeconds()).param("model", m.gpuModel())
            .param("peak", m.gpuPeakMemoryBytes()).param("why", m.gpuUnmeasuredReason()).param("bytes", Math.max(0, a.artifactBytes()))
            .param("m", json(m.stored())).param("rs", p.rateSetId()).param("cur", p.currency()).param("cost", p.amount())
            .param("cb", p.basis() == null ? null : json(p.basis())).param("cwhy", p.unavailable())
            .update();
        return n == 1;
    }

    /**
     * Records the current attempt of a RUNNING job that is being cancelled directly (ScanService#cancelJob), ending it now:
     * the worker sends no report for a cancelled attempt. Call it with the job row locked, before the cancel. A job that is
     * not RUNNING used nothing and is skipped (false).
     */
    public boolean recordDirectCancel(UUID jobId, String why) {
        AttemptEnd a = jdbc.sql("""
                SELECT j.retry_count, j.organization_id, j.venue_id, j.scan_id, j.run_id,
                       COALESCE(r.scan_version_id, j.scan_version_id) AS sv, j.stage, j.worker_id, j.started_at, now() AS ended
                  FROM processing_job j LEFT JOIN pipeline_run r ON r.id = j.run_id
                 WHERE j.id = :j AND j.status = 'RUNNING'
                """).param("j", jobId)
            .query((rs, i) -> {
                Instant ended = rs.getTimestamp("ended").toInstant();
                Instant started = rs.getTimestamp("started_at") == null ? ended : rs.getTimestamp("started_at").toInstant();
                return new AttemptEnd(jobId, rs.getInt("retry_count") + 1, rs.getObject("organization_id", UUID.class),
                    rs.getObject("venue_id", UUID.class), rs.getObject("scan_id", UUID.class), rs.getObject("run_id", UUID.class),
                    rs.getObject("sv", UUID.class), rs.getString("stage"), null, "CANCELLED", rs.getString("worker_id"), started, ended,
                    "CANCEL_REQUEST", 0, ResourceMeasurements.controlPlane(why));
            })
            .optional().orElse(null);
        return a != null && record(a);
    }

    record Price(UUID rateSetId, String currency, BigDecimal amount, Map<String, Object> basis, String unavailable) {}

    Price price(String resourceClass, double wallSeconds) {
        if (card == null) {
            return new Price(null, null, null, null, "NO_RATES_CONFIGURED: chaya.cost.rates (CHAYA_COST_RATES) is not set");
        }
        if (resourceClass == null) {
            return new Price(null, null, null, null, "RESOURCE_CLASS_NOT_REPORTED: the worker did not report its resource class "
                + "(WORKER_RESOURCE_CLASS)");
        }
        RateCard.WorkerRate rate = card.workerClasses().get(resourceClass);
        if (rate == null) {
            return new Price(null, null, null, null, "NO_RATE_FOR_CLASS: the rate card has no rate for worker class " + resourceClass);
        }
        BigDecimal amount = BigDecimal.valueOf(wallSeconds).multiply(rate.hourly()).divide(BigDecimal.valueOf(3600), 6, RoundingMode.HALF_UP);
        Map<String, Object> basis = new LinkedHashMap<>();
        basis.put("kind", KIND);
        basis.put("formula", FORMULA);
        basis.put("wallSeconds", wallSeconds);
        basis.put("resourceClass", resourceClass);
        basis.put("hourlyRate", rate.hourly());
        basis.put("rateKind", rate.kind());
        basis.put("rateSource", rate.source());
        basis.put("currency", card.currency());
        basis.put("rateSetSha256", card.sha256());
        return new Price(rateSetId(), card.currency(), amount, basis, null);
    }

    /** The cost_rate_set row of the configured card, created on first use in its own transaction (so a rolled-back
     * attempt record never leaves this cache pointing at a row that does not exist). */
    UUID rateSetId() {
        UUID id = rateSetId.get();
        if (id != null) {
            return id;
        }
        id = own.execute(s -> {
            jdbc.sql("INSERT INTO cost_rate_set (sha256, currency, config) VALUES (:sha, :cur, CAST(:c AS jsonb)) "
                    + "ON CONFLICT (sha256) DO NOTHING")
                .param("sha", card.sha256()).param("cur", card.currency()).param("c", card.canonicalJson()).update();
            return jdbc.sql("SELECT id FROM cost_rate_set WHERE sha256 = :sha").param("sha", card.sha256()).query(UUID.class).single();
        });
        rateSetId.set(id);
        return id;
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
