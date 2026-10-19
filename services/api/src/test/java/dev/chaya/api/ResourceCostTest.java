package dev.chaya.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import dev.chaya.api.cost.ResourceMeasurements;
import dev.chaya.api.cost.ResourceUsageRecorder;
import dev.chaya.api.cost.ResourceUsageRecorder.AttemptEnd;
import dev.chaya.api.pipeline.PipelineService;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * Resource and cost tracking (V42 job_attempt_usage; docs/operations-dashboard.md, "Resources and cost") through the real
 * worker protocol: every attempt ends in exactly one usage record, as the worker measured it or as the control plane
 * ended it; estimates come only from configured rates and say so; billed cost only from recorded bills.
 *
 * <p>The rate card is a TEST card: gpu-test at 3.6 per hour (so 10 s cost 0.01), local-gpu as LOCAL_HARDWARE.
 */
@TestPropertySource(properties = "chaya.cost.rates={\"currency\":\"USD\",\"workerClasses\":{"
    + "\"gpu-test\":{\"hourly\":3.6,\"kind\":\"CLOUD_LIST_PRICE\",\"source\":\"test card\"},"
    + "\"local-gpu\":{\"hourly\":0.36,\"kind\":\"LOCAL_HARDWARE\",\"source\":\"test card: power only\"}},"
    + "\"storagePerGbMonth\":{\"rate\":0.02,\"source\":\"test card\"}}")
class ResourceCostTest extends PipelineTestSupport {

    @Autowired PipelineService pipeline;
    @Autowired ResourceUsageRecorder recorder;

    static Map<String, Object> resources(String resourceClass, Double gpuSeconds) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("schema", 1);
        r.put("resourceClass", resourceClass);
        r.put("host", Map.of("cpuCount", 4, "platform", "Linux x86_64"));
        r.put("cpu", Map.of("processSeconds", 6.0, "childrenSeconds", 2.0, "childrenMeasured", true, "method", "os.times"));
        if (gpuSeconds == null) {
            r.put("gpu", null);
            r.put("gpuUnmeasuredReason", "torch is not loaded in this worker process");
        } else {
            r.put("gpu", Map.of("seconds", gpuSeconds, "basis", "STAGE_WALL_TIME_WITH_CUDA_ALLOCATIONS",
                "devices", List.of(Map.of("index", 0, "name", "NVIDIA Test GPU", "totalMemoryBytes", 24L << 30, "peakAllocatedBytes", 3_000_000_000L))));
        }
        return r;
    }

    /** A report with exact times (the worker's clock) and the given resources (null: an old worker that sends none). */
    Map<String, Object> timed(JsonNode order, String status, Instant started, Instant finished, Map<String, Object> resources) {
        Map<String, Object> r = report(status, status.equals("SUCCEEDED") ? outputsFor(order) : List.of(),
            status.equals("SUCCEEDED") ? null : "TEST_FAILURE", status.equals("SUCCEEDED") ? null : "the test failed it");
        r.put("startedAt", started.toString());
        r.put("finishedAt", finished.toString());
        if (resources != null) {
            r.put("resources", resources);
        }
        return r;
    }

    Map<String, Object> usage(JsonNode order, int attempt) {
        return jdbc.sql("SELECT * FROM job_attempt_usage WHERE job_id = :j AND attempt = :a")
            .param("j", UUID.fromString(order.get("id").asText())).param("a", attempt).query().singleRow();
    }

    int usageRows(JsonNode order) {
        return jdbc.sql("SELECT count(*) FROM job_attempt_usage WHERE job_id = :j").param("j", UUID.fromString(order.get("id").asText()))
            .query(Integer.class).single();
    }

    String resourcesUrl(Ctx c, int days) {
        return "/api/v1/venues/" + c.venue() + "/ops/resources?days=" + days;
    }

    @Test
    void aSuccessfulAttemptIsRecordedAsMeasuredAndPricedFromTheConfiguredRate() throws Exception {
        Started s = startRun();
        JsonNode order = claimExpecting("INPUT_VALIDATION");
        Instant t0 = Instant.now().minusSeconds(20);
        send(order, timed(order, "SUCCEEDED", t0, t0.plusSeconds(10), resources("gpu-test", 9.5)), svc).andExpect(status().isOk());

        Map<String, Object> u = usage(order, 1);
        assertThat(u.get("outcome")).isEqualTo("SUCCEEDED");
        assertThat(u.get("end_basis")).isEqualTo("WORKER_REPORT");
        assertThat(u.get("worker_id")).isEqualTo("test-worker");
        assertThat((Double) u.get("wall_seconds")).isEqualTo(10.0);
        assertThat((Double) u.get("cpu_seconds")).isEqualTo(8.0);
        assertThat((Double) u.get("gpu_seconds")).isEqualTo(9.5);
        assertThat(u.get("gpu_model")).isEqualTo("NVIDIA Test GPU");
        assertThat(u.get("gpu_peak_memory_bytes")).isEqualTo(3_000_000_000L);
        assertThat(((Number) u.get("artifact_bytes")).longValue()).isPositive();
        assertThat(u.get("stage_run_id")).isNotNull();
        assertThat(new java.math.BigDecimal(u.get("estimated_cost").toString())).isEqualByComparingTo("0.01");
        assertThat(u.get("cost_currency").toString().trim()).isEqualTo("USD");
        assertThat(u.get("cost_basis").toString()).contains("ESTIMATED").contains("gpu-test").contains("test card");
        assertThat(u.get("cost_rate_set_id")).isNotNull();

        get(resourcesUrl(s.c(), 30), s.c().operator()).andExpect(status().isOk())
            .andExpect(jsonPath("$.window.attempts").value(1))
            .andExpect(jsonPath("$.window.wallSeconds").value(10.0))
            .andExpect(jsonPath("$.window.gpuSeconds").value(9.5))
            .andExpect(jsonPath("$.window.peakGpuMemoryBytes").value(3_000_000_000L))
            .andExpect(jsonPath("$.estimate.kind").value("ESTIMATED"))
            .andExpect(jsonPath("$.estimate.amounts[0].currency").value("USD"))
            .andExpect(jsonPath("$.estimate.amounts[0].amount").value(0.01))
            .andExpect(jsonPath("$.estimate.complete").value(true))
            .andExpect(jsonPath("$.billed.available").value(false))
            .andExpect(jsonPath("$.workers[0].workerId").value("test-worker"))
            .andExpect(jsonPath("$.workers[0].gpuModels").value("NVIDIA Test GPU"))
            .andExpect(jsonPath("$.scanVersions[0].attempts").value(1))
            .andExpect(jsonPath("$.perScan.fullyPricedVersions").value(1))
            .andExpect(jsonPath("$.perScan.averageEstimated[0].amount").value(0.01))
            .andExpect(jsonPath("$.rateCard.workerClasses.gpu-test.kind").value("CLOUD_LIST_PRICE"))
            .andExpect(jsonPath("$.storage.estimatedPerMonth.currency").value("USD"));
        get("/api/v1/venues/" + s.c().venue() + "/ops/storage", s.c().operator()).andExpect(status().isOk())
            .andExpect(jsonPath("$.cost.available").value(true))
            .andExpect(jsonPath("$.cost.kind").value("ESTIMATED"))
            .andExpect(jsonPath("$.cost.amount").value(0.01))
            .andExpect(jsonPath("$.cost.complete").value(true));
    }

    @Test
    void aFailedAttemptAndItsRetryAreTwoRecordsAndTheFailedTimeIsReportedSeparately() throws Exception {
        Started s = startRun();
        JsonNode first = claimExpecting("INPUT_VALIDATION");
        Instant t0 = Instant.now().minusSeconds(60);
        send(first, timed(first, "FAILED", t0, t0.plusSeconds(20), resources("gpu-test", null)), svc).andExpect(status().isOk());
        retryRun(first);
        JsonNode second = claimExpecting("INPUT_VALIDATION");
        assertThat(second.get("id").asText()).isEqualTo(first.get("id").asText());
        send(second, timed(second, "SUCCEEDED", t0.plusSeconds(30), t0.plusSeconds(40), resources("gpu-test", null)), svc)
            .andExpect(status().isOk());

        assertThat(usageRows(first)).isEqualTo(2);
        assertThat(usage(first, 1).get("outcome")).isEqualTo("FAILED");
        assertThat(usage(first, 2).get("outcome")).isEqualTo("SUCCEEDED");
        assertThat(usage(first, 1).get("gpu_seconds")).isNull();
        assertThat(usage(first, 1).get("gpu_unmeasured_reason")).isEqualTo("torch is not loaded in this worker process");

        get(resourcesUrl(s.c(), 30), s.c().operator()).andExpect(status().isOk())
            .andExpect(jsonPath("$.window.attempts").value(2))
            .andExpect(jsonPath("$.window.wallSeconds").value(30.0))
            .andExpect(jsonPath("$.window.gpuSeconds").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.window.gpuUnmeasuredAttempts").value(2))
            .andExpect(jsonPath("$.byOutcome[?(@.outcome == 'FAILED')].wallSeconds").value(20.0))
            .andExpect(jsonPath("$.scanVersions[0].failedAttempts").value(1))
            .andExpect(jsonPath("$.scanVersions[0].failedWallSeconds").value(20.0))
            .andExpect(jsonPath("$.estimate.amounts[0].amount").value(0.03));
    }

    @Test
    void aDuplicateReportOfTheSameAttemptIsRefusedAndNeverCountedTwice() throws Exception {
        startRun();
        JsonNode order = claimExpecting("INPUT_VALIDATION");
        Instant t0 = Instant.now().minusSeconds(20);
        Map<String, Object> report = timed(order, "SUCCEEDED", t0, t0.plusSeconds(10), resources("gpu-test", 1.0));
        send(order, report, svc).andExpect(status().isOk());
        send(order, report, svc).andExpect(status().isConflict());
        assertThat(usageRows(order)).isEqualTo(1);

        Map<String, Object> row = usage(order, 1);
        boolean again = recorder.record(new AttemptEnd(UUID.fromString(order.get("id").asText()), 1, (UUID) row.get("organization_id"),
            (UUID) row.get("venue_id"), (UUID) row.get("scan_id"), (UUID) row.get("run_id"), (UUID) row.get("scan_version_id"),
            "INPUT_VALIDATION", null, "FAILED", "another-worker", t0, t0.plusSeconds(500), "WORKER_REPORT", 0,
            ResourceMeasurements.fromWorker(null, 500)));
        assertThat(again).isFalse();
        assertThat(usage(order, 1).get("outcome")).isEqualTo("SUCCEEDED");
        assertThat((Double) usage(order, 1).get("wall_seconds")).isEqualTo(10.0);
    }

    @Test
    void aCancelledAttemptIsRecordedUpToTheCancelAndTheLateReportIsRefused() throws Exception {
        Started s = startRun();
        JsonNode order = claimExpecting("INPUT_VALIDATION");
        post(capUrl(s.c(), s.capture()) + "/processing/cancel", s.c().operator(), null).andExpect(status().isOk());

        Map<String, Object> u = usage(order, 1);
        assertThat(u.get("outcome")).isEqualTo("CANCELLED");
        assertThat(u.get("end_basis")).isEqualTo("CANCEL_REQUEST");
        assertThat(u.get("cpu_seconds")).isNull();
        assertThat(u.get("gpu_seconds")).isNull();
        assertThat((String) u.get("gpu_unmeasured_reason")).contains("without a worker report");
        assertThat((Double) u.get("wall_seconds")).isGreaterThanOrEqualTo(0.0);

        Instant t0 = Instant.now().minusSeconds(5);
        send(order, timed(order, "SUCCEEDED", t0, Instant.now(), resources("gpu-test", 1.0)), svc).andExpect(status().isConflict());
        assertThat(usageRows(order)).isEqualTo(1);
        get(resourcesUrl(s.c(), 30), s.c().operator()).andExpect(status().isOk())
            .andExpect(jsonPath("$.byOutcome[0].outcome").value("CANCELLED"))
            .andExpect(jsonPath("$.estimate.unpricedReasons.RESOURCE_CLASS_NOT_REPORTED").value(1))
            .andExpect(jsonPath("$.estimate.complete").value(false));
    }

    @Test
    void aRunningJobCancelledDirectlyIsRecordedOnceAndAQueuedOneIsNot() throws Exception {
        Started s = startRun();
        JsonNode order = claimExpecting("INPUT_VALIDATION");
        post("/api/v1/venues/" + s.c().venue() + "/jobs/" + order.get("id").asText() + "/cancel", s.c().operator(), null)
            .andExpect(status().is2xxSuccessful());
        Map<String, Object> u = usage(order, 1);
        assertThat(u.get("outcome")).isEqualTo("CANCELLED");
        assertThat(u.get("end_basis")).isEqualTo("CANCEL_REQUEST");
        assertThat(u.get("worker_id")).isEqualTo("test-worker");
        assertThat(u.get("scan_version_id")).isNotNull();
        assertThat(u.get("gpu_seconds")).isNull();
        assertThat(usageRows(order)).isEqualTo(1);

        UUID queued = recorderSkipsQueuedJob();
        assertThat(recorder.recordDirectCancel(queued, "test")).isFalse();
    }

    /** A queued job of a fresh run: it never started, so a cancel has nothing to record. */
    UUID recorderSkipsQueuedJob() throws Exception {
        startRun();
        return jdbc.sql("SELECT id FROM processing_job WHERE status = 'QUEUED' ORDER BY queued_at DESC LIMIT 1").query(UUID.class).single();
    }

    @Test
    void aCrashedWorkersAttemptEndsAtItsLastHeartbeatAndIsInterrupted() throws Exception {
        startRun();
        JsonNode order = claimExpecting("INPUT_VALIDATION");
        UUID job = UUID.fromString(order.get("id").asText());
        jdbc.sql("UPDATE processing_job SET lease_expires_at = now() - interval '1 second' WHERE id = :j").param("j", job).update();
        pipeline.enforceLeasesAndDeadlines();

        Map<String, Object> u = usage(order, 1);
        assertThat(u.get("outcome")).isEqualTo("INTERRUPTED");
        assertThat(u.get("end_basis")).isEqualTo("LAST_HEARTBEAT");
        assertThat(u.get("stage_run_id")).isNotNull(); // the control plane's synthetic WORKER_LOST stage record
        Instant started = ((java.sql.Timestamp) u.get("started_at")).toInstant();
        Instant ended = ((java.sql.Timestamp) u.get("ended_at")).toInstant();
        assertThat(ended).isBetween(started, Instant.now());
        assertThat(u.get("cpu_seconds")).isNull();

        // the retry is a new attempt with its own record
        retryRun(order);
        JsonNode again = claimExpecting("INPUT_VALIDATION");
        Instant t0 = Instant.now().minusSeconds(4);
        send(again, timed(again, "SUCCEEDED", t0, t0.plusSeconds(2), resources("gpu-test", null)), svc).andExpect(status().isOk());
        assertThat(usageRows(order)).isEqualTo(2);
    }

    @Test
    void reportsWithoutMeasurementsOrWithAnUnknownClassAreRecordedButNotPriced() throws Exception {
        Started s = startRun();
        JsonNode order = claimExpecting("INPUT_VALIDATION");
        Instant t0 = Instant.now().minusSeconds(20);
        send(order, timed(order, "SUCCEEDED", t0, t0.plusSeconds(5), null), svc).andExpect(status().isOk());
        Map<String, Object> u = usage(order, 1);
        assertThat(u.get("estimated_cost")).isNull();
        assertThat((String) u.get("cost_unavailable_reason")).startsWith("RESOURCE_CLASS_NOT_REPORTED");
        assertThat(u.get("gpu_unmeasured_reason")).isEqualTo(ResourceMeasurements.NOT_REPORTED);

        JsonNode next = claimExpecting("FFMPEG_PREPROCESS");
        send(next, timed(next, "SUCCEEDED", t0.plusSeconds(6), t0.plusSeconds(8), resources("mystery-box", null)), svc)
            .andExpect(status().isOk());
        assertThat((String) usage(next, 1).get("cost_unavailable_reason")).startsWith("NO_RATE_FOR_CLASS");

        get(resourcesUrl(s.c(), 30), s.c().operator()).andExpect(status().isOk())
            .andExpect(jsonPath("$.estimate.pricedAttempts").value(0))
            .andExpect(jsonPath("$.estimate.unpricedAttempts").value(2))
            .andExpect(jsonPath("$.estimate.amounts").isEmpty())
            .andExpect(jsonPath("$.estimate.unpricedReasons.NO_RATE_FOR_CLASS").value(1))
            .andExpect(jsonPath("$.estimate.unpricedReasons.RESOURCE_CLASS_NOT_REPORTED").value(1));
        get("/api/v1/venues/" + s.c().venue() + "/ops/storage", s.c().operator()).andExpect(status().isOk())
            .andExpect(jsonPath("$.cost.available").value(false))
            .andExpect(jsonPath("$.cost.amount").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void impossibleWorkerFiguresAreDroppedNamedAndTheStageStillCounts() throws Exception {
        startRun();
        JsonNode order = claimExpecting("INPUT_VALIDATION");
        Instant t0 = Instant.now().minusSeconds(20);
        Map<String, Object> bad = resources("gpu-test", 900.0); // GPU held 900 s in a 10 s attempt
        bad.put("cpu", Map.of("processSeconds", -1, "childrenMeasured", false));
        send(order, timed(order, "SUCCEEDED", t0, t0.plusSeconds(10), bad), svc).andExpect(status().isOk());
        Map<String, Object> u = usage(order, 1);
        assertThat(u.get("cpu_seconds")).isNull();
        assertThat(u.get("gpu_seconds")).isNull();
        assertThat(u.get("measurements").toString()).contains("gpu.seconds").contains("cpu.processSeconds");
        assertThat(u.get("estimated_cost")).isNotNull(); // the wall time is the worker's own and valid; it is still priced
    }

    @Test
    void attemptsOfDifferentWorkersAreAttributedToEach() throws Exception {
        Started s = startRun();
        JsonNode a = claimAs("gpu-host-1");
        Instant t0 = Instant.now().minusSeconds(60);
        send(a, timed(a, "SUCCEEDED", t0, t0.plusSeconds(10), resources("gpu-test", 9.0)), svc).andExpect(status().isOk());
        JsonNode b = claimAs("local-box");
        send(b, timed(b, "SUCCEEDED", t0.plusSeconds(20), t0.plusSeconds(40), resources("local-gpu", null)), svc).andExpect(status().isOk());

        assertThat(usage(a, 1).get("worker_id")).isEqualTo("gpu-host-1");
        assertThat(usage(b, 1).get("worker_id")).isEqualTo("local-box");
        assertThat(usage(b, 1).get("cost_basis").toString()).contains("LOCAL_HARDWARE");
        get(resourcesUrl(s.c(), 30), s.c().operator()).andExpect(status().isOk())
            .andExpect(jsonPath("$.workers.length()").value(2))
            .andExpect(jsonPath("$.workers[0].workerId").value("local-box")) // most wall time first
            .andExpect(jsonPath("$.workers[0].resourceClass").value("local-gpu"))
            .andExpect(jsonPath("$.workers[1].gpuSeconds").value(9.0))
            .andExpect(jsonPath("$.estimate.amounts[0].amount").value(0.012)); // 10 s x 3.6/h + 20 s x 0.36/h
    }

    @Test
    void historyIsAggregatedPerDayAndTheWindowOnlyCoversItsDays() throws Exception {
        Started s = startRun();
        JsonNode old = claimExpecting("INPUT_VALIDATION");
        Instant threeDaysAgo = Instant.now().minus(Duration.ofDays(3));
        send(old, timed(old, "SUCCEEDED", threeDaysAgo.minusSeconds(10), threeDaysAgo, resources("gpu-test", null)), svc)
            .andExpect(status().isOk());
        JsonNode recent = claimExpecting("FFMPEG_PREPROCESS");
        Instant t0 = Instant.now().minusSeconds(30);
        send(recent, timed(recent, "FAILED", t0, t0.plusSeconds(20), resources("gpu-test", null)), svc).andExpect(status().isOk());

        get(resourcesUrl(s.c(), 7), s.c().operator()).andExpect(status().isOk())
            .andExpect(jsonPath("$.trend.length()").value(2))
            .andExpect(jsonPath("$.trend[0].wallSeconds").value(10.0))
            .andExpect(jsonPath("$.trend[1].failedWallSeconds").value(20.0))
            .andExpect(jsonPath("$.window.attempts").value(2));
        get(resourcesUrl(s.c(), 1), s.c().operator()).andExpect(status().isOk())
            .andExpect(jsonPath("$.window.attempts").value(1))
            .andExpect(jsonPath("$.trend.length()").value(1))
            .andExpect(jsonPath("$.allTime.attempts").value(2))
            .andExpect(jsonPath("$.estimateAllTime.amounts[0].amount").value(0.03));
    }

    @Test
    void providerBillsAreRecordedByAnAdminWithEvidenceAndKeptApartFromEstimates() throws Exception {
        Ctx c = ctx();
        String admin = TestJwt.user(c.org(), "admin").venues(c.venue()).token();
        String url = "/api/v1/venues/" + c.venue() + "/ops/billed-costs";
        String bill = """
            {"provider": "AWS", "periodStart": "2026-09-01", "periodEnd": "2026-09-30", "amount": 812.40, "currency": "USD",
             "evidenceReference": "invoice 4417-2026-09", "description": "g5.xlarge GPU workers"}""";
        post(url, c.operator(), bill).andExpect(status().isForbidden());
        post(url, admin, bill.replace("\"invoice 4417-2026-09\"", "\" \"")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("BILL_INVALID"));
        post(url, admin, bill.replace("812.40", "-1")).andExpect(status().isBadRequest());
        post(url, admin, bill.replace("2026-09-30", "2026-08-01")).andExpect(status().isBadRequest());
        post(url, admin, bill).andExpect(status().isCreated()).andExpect(jsonPath("$.evidenceReference").value("invoice 4417-2026-09"));

        get(resourcesUrl(c, 30), c.operator()).andExpect(status().isOk())
            .andExpect(jsonPath("$.billed.available").value(true))
            .andExpect(jsonPath("$.billed.totals[0].amount").value(812.4))
            .andExpect(jsonPath("$.billed.bills[0].provider").value("AWS"))
            .andExpect(jsonPath("$.estimate.amounts").isEmpty()); // a bill is never turned into an estimate
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE action = 'cost.bill_recorded' AND venue_id = :v")
            .param("v", c.venue()).query(Integer.class).single()).isEqualTo(1);

        // another organization's admin cannot record a bill on this venue
        Ctx other = ctx();
        post(url, TestJwt.user(other.org(), "admin").venues(other.venue()).token(), bill).andExpect(status().isNotFound());
    }

    @Test
    void usageRecordsAreWriteOnceAndRefuseInvalidValues() throws Exception {
        startRun();
        JsonNode order = claimExpecting("INPUT_VALIDATION");
        Instant t0 = Instant.now().minusSeconds(20);
        send(order, timed(order, "SUCCEEDED", t0, t0.plusSeconds(10), resources("gpu-test", 1.0)), svc).andExpect(status().isOk());
        UUID job = UUID.fromString(order.get("id").asText());
        assertThatThrownBy(() -> jdbc.sql("UPDATE job_attempt_usage SET wall_seconds = 0 WHERE job_id = :j").param("j", job).update())
            .hasMessageContaining("write-once");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM job_attempt_usage WHERE job_id = :j").param("j", job).update())
            .hasMessageContaining("write-once");
        Map<String, Object> row = usage(order, 1);
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO job_attempt_usage (job_id, attempt, organization_id, venue_id, scan_id, stage, outcome, started_at, ended_at,
                    end_basis, wall_seconds, gpu_unmeasured_reason, measurements, cost_unavailable_reason)
                VALUES (:j, 9, :o, :v, :s, 'INPUT_VALIDATION', 'FAILED', now(), now(), 'WORKER_REPORT', -1, 'x', '{}', 'x')""")
            .param("j", job).param("o", row.get("organization_id")).param("v", row.get("venue_id")).param("s", row.get("scan_id")).update())
            .hasMessageContaining("wall_seconds");
    }

    /** Claims the next queued job as the named worker. */
    JsonNode claimAs(String workerId) throws Exception {
        var res = post(CLAIM, svc, "{\"stages\":" + allStages() + ",\"workerId\":\"" + workerId + "\"}").andExpect(status().isOk())
            .andReturn().getResponse();
        return mapper.readTree(res.getContentAsString());
    }
}
