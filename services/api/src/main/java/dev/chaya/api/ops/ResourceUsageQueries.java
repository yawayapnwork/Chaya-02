package dev.chaya.api.ops;

import dev.chaya.api.cost.RateCard;
import dev.chaya.api.cost.ResourceUsageRecorder;
import dev.chaya.api.ops.OpsDtos.Bill;
import dev.chaya.api.ops.OpsDtos.BilledCost;
import dev.chaya.api.ops.OpsDtos.Cost;
import dev.chaya.api.ops.OpsDtos.CostEstimate;
import dev.chaya.api.ops.OpsDtos.DayUsage;
import dev.chaya.api.ops.OpsDtos.Money;
import dev.chaya.api.ops.OpsDtos.OutcomeUsage;
import dev.chaya.api.ops.OpsDtos.PerScan;
import dev.chaya.api.ops.OpsDtos.Resources;
import dev.chaya.api.ops.OpsDtos.StorageUsage;
import dev.chaya.api.ops.OpsDtos.UsageTotals;
import dev.chaya.api.ops.OpsDtos.VersionUsage;
import dev.chaya.api.ops.OpsDtos.WorkerUsage;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The read side of job_attempt_usage (V42) for the operations dashboard, section 7a "Resources and cost". Every query
 * filters by venue and organization; the caller (OpsDashboardService) has already checked the section's roles and the
 * venue. Sums of measured values stay null when nothing was measured. Estimated amounts are grouped by currency and never
 * added to billed amounts.
 */
@Component
class ResourceUsageQueries {

    static final String FAILED_OUTCOMES = "('FAILED', 'INTERRUPTED', 'CANCELLED')";
    static final double GIB = 1024.0 * 1024 * 1024;

    private final JdbcClient jdbc;
    private final ResourceUsageRecorder recorder;

    ResourceUsageQueries(JdbcClient jdbc, ResourceUsageRecorder recorder) {
        this.jdbc = jdbc;
        this.recorder = recorder;
    }

    Resources resources(UUID venueId, UUID org, int days, Instant asOf) {
        Instant since = asOf.minusSeconds(days * 86400L);
        String window = " AND u.ended_at >= :since";
        UsageTotals windowTotals = totals(venueId, org, window, since);
        UsageTotals allTime = totals(venueId, org, "", since);

        List<OutcomeUsage> byOutcome = new ArrayList<>();
        Map<String, List<Money>> outcomeMoney = money(venueId, org, "u.outcome", window, since);
        jdbc.sql("""
                SELECT u.outcome, count(*) AS n, COALESCE(sum(u.wall_seconds), 0) AS wall, sum(u.gpu_seconds) AS gpu,
                       count(*) FILTER (WHERE u.estimated_cost IS NULL) AS unpriced
                  FROM job_attempt_usage u WHERE u.venue_id = :v AND u.organization_id = :o""" + " " + window + " " + """
                 GROUP BY u.outcome ORDER BY u.outcome""")
            .param("v", venueId).param("o", org).param("since", Timestamp.from(since))
            .query((rs, i) -> new OutcomeUsage(rs.getString("outcome"), rs.getLong("n"), rs.getDouble("wall"), dbl(rs, "gpu"),
                outcomeMoney.getOrDefault(rs.getString("outcome"), List.of()), rs.getLong("unpriced")))
            .list().forEach(byOutcome::add);

        List<VersionUsage> versions = versions(venueId, org);
        List<VersionUsage> counted = versions.stream().filter(v -> v.attempts() > 0).toList();
        List<VersionUsage> priced = counted.stream().filter(v -> v.unpricedAttempts() == 0).toList();
        Map<String, BigDecimal> avgSums = new TreeMap<>();
        Map<String, Integer> avgCounts = new HashMap<>();
        for (VersionUsage v : priced) {
            for (Money m : v.estimated()) {
                avgSums.merge(m.currency(), BigDecimal.valueOf(m.amount()), BigDecimal::add);
                avgCounts.merge(m.currency(), 1, Integer::sum);
            }
        }
        List<Money> averages = avgSums.entrySet().stream().map(e -> new Money(e.getKey(),
            e.getValue().divide(BigDecimal.valueOf(avgCounts.get(e.getKey())), 6, RoundingMode.HALF_UP).doubleValue())).toList();
        PerScan perScan = new PerScan(counted.size(), priced.size(), averages,
            counted.isEmpty() ? null : counted.stream().mapToDouble(VersionUsage::wallSeconds).average().orElse(0));

        Map<String, List<Money>> dayMoney = money(venueId, org, "to_char(u.ended_at AT TIME ZONE 'UTC', 'YYYY-MM-DD')", window, since);
        List<DayUsage> trend = jdbc.sql("""
                SELECT to_char(u.ended_at AT TIME ZONE 'UTC', 'YYYY-MM-DD') AS day, count(*) AS n, COALESCE(sum(u.wall_seconds), 0) AS wall,
                       sum(u.gpu_seconds) AS gpu,
                       COALESCE(sum(u.wall_seconds) FILTER (WHERE u.outcome IN """ + " " + FAILED_OUTCOMES + " " + """
                       ), 0) AS failed_wall, count(*) FILTER (WHERE u.estimated_cost IS NULL) AS unpriced
                  FROM job_attempt_usage u WHERE u.venue_id = :v AND u.organization_id = :o""" + " " + window + " " + """
                 GROUP BY 1 ORDER BY 1""")
            .param("v", venueId).param("o", org).param("since", Timestamp.from(since))
            .query((rs, i) -> new DayUsage(LocalDate.parse(rs.getString("day")), rs.getLong("n"), rs.getDouble("wall"), dbl(rs, "gpu"),
                rs.getDouble("failed_wall"), dayMoney.getOrDefault(rs.getString("day"), List.of()), rs.getLong("unpriced")))
            .list();

        List<WorkerUsage> workers = jdbc.sql("""
                SELECT COALESCE(u.worker_id, '(unknown)') AS worker, max(u.resource_class) AS rc,
                       string_agg(DISTINCT u.gpu_model, ', ') AS models, count(*) AS n, COALESCE(sum(u.wall_seconds), 0) AS wall,
                       sum(u.gpu_seconds) AS gpu, max(u.ended_at) AS last_at
                  FROM job_attempt_usage u WHERE u.venue_id = :v AND u.organization_id = :o""" + " " + window + " " + """
                 GROUP BY 1 ORDER BY wall DESC, 1 LIMIT 50""")
            .param("v", venueId).param("o", org).param("since", Timestamp.from(since))
            .query((rs, i) -> new WorkerUsage(rs.getString("worker"), rs.getString("rc"), rs.getString("models"), rs.getLong("n"),
                rs.getDouble("wall"), dbl(rs, "gpu"), ts(rs, "last_at")))
            .list();

        long withoutUsage = jdbc.sql("""
                SELECT count(*) FROM pipeline_stage_run sr
                 WHERE sr.venue_id = :v AND sr.organization_id = :o
                   AND NOT EXISTS (SELECT 1 FROM job_attempt_usage u WHERE u.job_id = sr.job_id AND u.attempt = sr.attempt)
                """).param("v", venueId).param("o", org).query(Long.class).single();

        RateCard card = recorder.card();
        return new Resources(days, since, windowTotals, allTime, byOutcome, estimate(venueId, org, window, since),
            estimate(venueId, org, "", since), billed(venueId, org), storage(venueId, org, card), perScan, versions, workers, trend,
            card == null ? null : card.describe(), withoutUsage, asOf);
    }

    /** The all-time estimate as the storage section's `cost` summary (OpsDtos.Cost). */
    Cost costSummary(UUID venueId, UUID org) {
        CostEstimate e = estimate(venueId, org, "", Instant.EPOCH);
        String kind = ResourceUsageRecorder.KIND;
        if (e.pricedAttempts() == 0) {
            String why = e.unpricedAttempts() == 0 ? "No processing attempt has been recorded for this venue yet."
                : "No attempt could be priced: " + String.join("; ", e.unpricedReasons().keySet()) + ". Measured time is shown in "
                    + "Resources and cost.";
            return new Cost(false, null, null, why, kind, false, 0, e.unpricedAttempts());
        }
        if (e.amounts().size() > 1) {
            return new Cost(false, null, null, "Estimated amounts are in more than one currency; see Resources and cost.", kind,
                e.complete(), e.pricedAttempts(), e.unpricedAttempts());
        }
        Money m = e.amounts().get(0);
        String why = "Estimated from measured worker time and the configured rates; not a provider bill."
            + (e.complete() ? "" : " " + e.unpricedAttempts() + " attempt(s) could not be priced, so this is a lower bound.");
        return new Cost(true, m.currency(), m.amount(), why, kind, e.complete(), e.pricedAttempts(), e.unpricedAttempts());
    }

    private UsageTotals totals(UUID venueId, UUID org, String filter, Instant since) {
        return jdbc.sql("""
                SELECT count(*) AS n, COALESCE(sum(u.wall_seconds), 0) AS wall, sum(u.cpu_seconds) AS cpu, count(u.cpu_seconds) AS cpu_n,
                       sum(u.gpu_seconds) AS gpu, count(u.gpu_seconds) AS gpu_n, max(u.gpu_peak_memory_bytes) AS peak,
                       COALESCE(sum(u.artifact_bytes), 0) AS bytes
                  FROM job_attempt_usage u WHERE u.venue_id = :v AND u.organization_id = :o""" + " " + filter)
            .param("v", venueId).param("o", org).param("since", Timestamp.from(since))
            .query((rs, i) -> new UsageTotals(rs.getLong("n"), rs.getDouble("wall"), dbl(rs, "cpu"), rs.getLong("cpu_n"), dbl(rs, "gpu"),
                rs.getLong("gpu_n"), rs.getLong("n") - rs.getLong("gpu_n"), lng(rs, "peak"), rs.getLong("bytes")))
            .single();
    }

    private CostEstimate estimate(UUID venueId, UUID org, String filter, Instant since) {
        List<Money> amounts = money(venueId, org, "'all'", filter, since).getOrDefault("all", List.of());
        record Counts(long priced, long unpriced, double unpricedWall) {}
        Counts c = jdbc.sql("""
                SELECT count(*) FILTER (WHERE u.estimated_cost IS NOT NULL) AS priced, count(*) FILTER (WHERE u.estimated_cost IS NULL) AS unpriced,
                       COALESCE(sum(u.wall_seconds) FILTER (WHERE u.estimated_cost IS NULL), 0) AS unpriced_wall
                  FROM job_attempt_usage u WHERE u.venue_id = :v AND u.organization_id = :o""" + " " + filter)
            .param("v", venueId).param("o", org).param("since", Timestamp.from(since))
            .query((rs, i) -> new Counts(rs.getLong("priced"), rs.getLong("unpriced"), rs.getDouble("unpriced_wall"))).single();
        Map<String, Long> reasons = new TreeMap<>();
        jdbc.sql("""
                SELECT split_part(u.cost_unavailable_reason, ':', 1) AS code, count(*) AS n
                  FROM job_attempt_usage u WHERE u.venue_id = :v AND u.organization_id = :o AND u.estimated_cost IS NULL""" + " " + filter + " " + """
                 GROUP BY 1""")
            .param("v", venueId).param("o", org).param("since", Timestamp.from(since))
            .query((rs, i) -> Map.entry(rs.getString("code"), rs.getLong("n"))).list().forEach(e -> reasons.put(e.getKey(), e.getValue()));
        return new CostEstimate(ResourceUsageRecorder.KIND, amounts, c.priced(), c.unpriced(), c.unpricedWall(), reasons,
            c.unpriced() == 0 && c.priced() > 0);
    }

    /** Estimated amounts per `key` expression and currency, over priced attempts only. */
    private Map<String, List<Money>> money(UUID venueId, UUID org, String key, String filter, Instant since) {
        Map<String, List<Money>> out = new HashMap<>();
        jdbc.sql("SELECT " + key + " AS k, u.cost_currency AS cur, sum(u.estimated_cost) AS amount FROM job_attempt_usage u "
                + "WHERE u.venue_id = :v AND u.organization_id = :o AND u.estimated_cost IS NOT NULL" + filter
                + " GROUP BY 1, 2 ORDER BY 2")
            .param("v", venueId).param("o", org).param("since", Timestamp.from(since))
            .query((rs, i) -> Map.entry(rs.getString("k"), new Money(rs.getString("cur").trim(), rs.getBigDecimal("amount").doubleValue())))
            .list().forEach(e -> out.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue()));
        return out;
    }

    private List<VersionUsage> versions(UUID venueId, UUID org) {
        Map<String, List<Money>> versionMoney = money(venueId, org, "u.scan_version_id::text", " AND u.scan_version_id IS NOT NULL", Instant.EPOCH);
        return jdbc.sql("""
                SELECT u.scan_version_id AS id, sv.floor_id, sv.version_number, sv.status, count(*) AS n,
                       COALESCE(sum(u.wall_seconds), 0) AS wall, sum(u.gpu_seconds) AS gpu,
                       count(*) FILTER (WHERE u.outcome IN """ + " " + FAILED_OUTCOMES + " " + """
                       ) AS failed_n,
                       COALESCE(sum(u.wall_seconds) FILTER (WHERE u.outcome IN """ + " " + FAILED_OUTCOMES + " " + """
                       ), 0) AS failed_wall,
                       count(*) FILTER (WHERE u.estimated_cost IS NULL) AS unpriced, max(u.ended_at) AS last_at
                  FROM job_attempt_usage u LEFT JOIN scan_version sv ON sv.id = u.scan_version_id
                 WHERE u.venue_id = :v AND u.organization_id = :o AND u.scan_version_id IS NOT NULL
                 GROUP BY u.scan_version_id, sv.floor_id, sv.version_number, sv.status
                 ORDER BY last_at DESC LIMIT 50""")
            .param("v", venueId).param("o", org)
            .query((rs, i) -> new VersionUsage(rs.getObject("id", UUID.class), rs.getObject("floor_id", UUID.class),
                (Integer) rs.getObject("version_number"), rs.getString("status"), rs.getLong("n"), rs.getDouble("wall"), dbl(rs, "gpu"),
                rs.getLong("failed_n"), rs.getDouble("failed_wall"), versionMoney.getOrDefault(rs.getString("id"), List.of()),
                rs.getLong("unpriced"), ts(rs, "last_at")))
            .list();
    }

    private BilledCost billed(UUID venueId, UUID org) {
        List<Bill> bills = jdbc.sql("""
                SELECT id, provider, period_start, period_end, amount, currency, evidence_reference, description, recorded_by, recorded_at
                  FROM infrastructure_bill WHERE venue_id = :v AND organization_id = :o ORDER BY period_start DESC, recorded_at DESC LIMIT 100
                """).param("v", venueId).param("o", org)
            .query((rs, i) -> new Bill(rs.getObject("id", UUID.class), rs.getString("provider"), rs.getDate("period_start").toLocalDate(),
                rs.getDate("period_end").toLocalDate(), rs.getBigDecimal("amount").doubleValue(), rs.getString("currency").trim(),
                rs.getString("evidence_reference"), rs.getString("description"), rs.getString("recorded_by"), ts(rs, "recorded_at")))
            .list();
        List<Money> totals = jdbc.sql("""
                SELECT currency, sum(amount) AS total FROM infrastructure_bill WHERE venue_id = :v AND organization_id = :o
                 GROUP BY currency ORDER BY currency""").param("v", venueId).param("o", org)
            .query((rs, i) -> new Money(rs.getString("currency").trim(), rs.getBigDecimal("total").doubleValue())).list();
        return new BilledCost(!bills.isEmpty(), totals, bills, bills.isEmpty()
            ? "No provider bill has been recorded for this venue. Estimates are not bills."
            : "Recorded by an admin with the provider's evidence reference. Not computed, and not divided across scans.");
    }

    private StorageUsage storage(UUID venueId, UUID org, RateCard card) {
        long artifacts = jdbc.sql("SELECT COALESCE(sum(size_bytes), 0) FROM processing_artifact WHERE venue_id = :v AND organization_id = :o "
            + "AND NOT contains_pii").param("v", venueId).param("o", org).query(Long.class).single();
        long media = jdbc.sql("SELECT COALESCE(sum(declared_size_bytes), 0) FROM capture_media WHERE venue_id = :v AND organization_id = :o "
            + "AND status = 'ACCEPTED'").param("v", venueId).param("o", org).query(Long.class).single();
        if (card == null || card.storage() == null) {
            return new StorageUsage(artifacts, media, null, null, card == null ? "NO_RATES_CONFIGURED: chaya.cost.rates is not set"
                : "NO_STORAGE_RATE: the rate card has no storagePerGbMonth");
        }
        BigDecimal amount = BigDecimal.valueOf((artifacts + media) / GIB).multiply(card.storage().perGbMonth())
            .setScale(6, RoundingMode.HALF_UP);
        return new StorageUsage(artifacts, media, new Money(card.currency(), amount.doubleValue()),
            "ESTIMATED: (derived artifacts + accepted raw media) / 2^30 x " + card.storage().perGbMonth() + " " + card.currency()
                + " per GiB-month (" + card.storage().source() + "), at the sizes recorded now", null);
    }

    private static Double dbl(ResultSet rs, String col) throws SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : v;
    }

    private static Long lng(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private static Instant ts(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }
}
