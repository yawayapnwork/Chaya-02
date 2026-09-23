package dev.chaya.api.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Pipeline and storage gauges for Prometheus, recomputed from the database every refresh interval (docs/monitoring.md).
 * Reading the durable records rather than counting in memory means the figures survive restarts and agree with the
 * operations dashboard. API latency is Spring's own http.server.requests histogram (application.yml).
 *
 * <p>If a refresh fails (e.g. the database is down) every series below is removed and chaya_metrics_refresh_ok drops to
 * 0: a stale number is never left looking current.
 */
@Component
public class PlatformMetrics {

    private static final Logger log = LoggerFactory.getLogger(PlatformMetrics.class);

    private final JdbcClient jdbc;
    private final MultiGauge jobs;
    private final MultiGauge stageRuns;
    private final MultiGauge stageDuration;
    private final MultiGauge runsFinished;
    private final MultiGauge storageBytes;
    private final AtomicReference<Double> oldestQueuedAge = new AtomicReference<>(Double.NaN);
    private final AtomicReference<Double> refreshOk = new AtomicReference<>(0.0);
    private final AtomicReference<Double> refreshedAt = new AtomicReference<>(Double.NaN);

    public PlatformMetrics(JdbcClient jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.jobs = MultiGauge.builder("chaya.jobs").description("Processing jobs by current status").register(registry);
        this.stageRuns = MultiGauge.builder("chaya.stage.runs.24h")
            .description("Stage execution attempts finished in the last 24 h, by stage and outcome").register(registry);
        this.stageDuration = MultiGauge.builder("chaya.stage.duration.24h").baseUnit("seconds")
            .description("Stage execution time over the last 24 h (quantile tag: 0.5, 0.95, max)").register(registry);
        this.runsFinished = MultiGauge.builder("chaya.pipeline.runs.finished.24h")
            .description("Reconstruction pipeline runs that finished in the last 24 h, by final status").register(registry);
        this.storageBytes = MultiGauge.builder("chaya.storage.recorded").baseUnit("bytes")
            .description("Bytes recorded in the database for stored objects (store tag: raw_media, derived_artifacts)").register(registry);
        Gauge.builder("chaya.jobs.queued.oldest.age", oldestQueuedAge, AtomicReference::get).baseUnit("seconds")
            .description("Age of the oldest QUEUED job (NaN when none is queued)").register(registry);
        Gauge.builder("chaya.metrics.refresh.ok", refreshOk, AtomicReference::get)
            .description("1 when the last refresh of the chaya_* gauges succeeded, 0 otherwise").register(registry);
        Gauge.builder("chaya.metrics.refreshed", refreshedAt, AtomicReference::get).baseUnit("seconds")
            .description("Unix time of the last successful refresh").register(registry);
    }

    @Scheduled(fixedDelayString = "${chaya.metrics.refresh-interval:PT30S}", initialDelayString = "PT10S")
    public void refresh() {
        try {
            jobs.register(jdbc.sql("SELECT status, count(*) AS n FROM processing_job GROUP BY status")
                .query((rs, i) -> MultiGauge.Row.of(Tags.of("status", rs.getString("status")), rs.getLong("n"))).list(), true);

            stageRuns.register(jdbc.sql("""
                    SELECT stage, status, count(*) AS n FROM pipeline_stage_run
                     WHERE finished_at >= now() - interval '24 hours' GROUP BY stage, status
                    """)
                .query((rs, i) -> MultiGauge.Row.of(Tags.of("stage", rs.getString("stage"), "outcome", rs.getString("status")),
                    rs.getLong("n"))).list(), true);

            stageDuration.register(jdbc.sql("""
                    SELECT stage,
                           percentile_cont(0.5) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM finished_at - started_at)) AS p50,
                           percentile_cont(0.95) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM finished_at - started_at)) AS p95,
                           max(EXTRACT(EPOCH FROM finished_at - started_at)) AS mx
                      FROM pipeline_stage_run WHERE finished_at >= now() - interval '24 hours' GROUP BY stage
                    """)
                .query((rs, i) -> List.of(
                    MultiGauge.Row.of(Tags.of("stage", rs.getString("stage"), "quantile", "0.5"), rs.getDouble("p50")),
                    MultiGauge.Row.of(Tags.of("stage", rs.getString("stage"), "quantile", "0.95"), rs.getDouble("p95")),
                    MultiGauge.Row.of(Tags.of("stage", rs.getString("stage"), "quantile", "max"), rs.getDouble("mx"))))
                .list().stream().flatMap(List::stream).toList(), true);

            runsFinished.register(jdbc.sql("""
                    SELECT status, count(*) AS n FROM pipeline_run
                     WHERE finished_at >= now() - interval '24 hours' GROUP BY status
                    """)
                .query((rs, i) -> MultiGauge.Row.of(Tags.of("status", rs.getString("status")), rs.getLong("n"))).list(), true);

            long raw = jdbc.sql("SELECT COALESCE(sum(declared_size_bytes), 0) FROM capture_media WHERE status IN ('ACCEPTED', 'VALIDATING', 'QUARANTINED')")
                .query(Long.class).single();
            long derived = jdbc.sql("SELECT COALESCE(sum(size_bytes), 0) FROM processing_artifact WHERE NOT contains_pii")
                .query(Long.class).single();
            storageBytes.register(List.of(
                MultiGauge.Row.of(Tags.of("store", "raw_media"), raw),
                MultiGauge.Row.of(Tags.of("store", "derived_artifacts"), derived)), true);

            Double oldest = jdbc.sql("SELECT CAST(EXTRACT(EPOCH FROM now() - min(queued_at)) AS double precision) FROM processing_job WHERE status = 'QUEUED'")
                .query(Double.class).optional().orElse(null);
            oldestQueuedAge.set(oldest == null ? Double.NaN : oldest);
            refreshOk.set(1.0);
            refreshedAt.set(System.currentTimeMillis() / 1000.0);
        } catch (RuntimeException e) {
            log.warn("platform metrics refresh failed; series cleared: {}", e.getMessage());
            for (MultiGauge g : List.of(jobs, stageRuns, stageDuration, runsFinished, storageBytes)) {
                g.register(List.of(), true);
            }
            oldestQueuedAge.set(Double.NaN);
            refreshOk.set(0.0);
        }
    }
}
