package dev.chaya.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import dev.chaya.api.pipeline.PipelineService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Operational recovery of processing jobs against the real database and object store: lease expiry, a job the enforcer
 * cannot settle, a job left RUNNING in an ended run, duplicate report submissions, late reports for cancelled work,
 * retries that must keep the earlier failure and must not duplicate artifacts, and measured worker liveness.
 */
class JobRecoveryTest extends PipelineTestSupport {

    @Autowired PipelineService pipeline;

    private String jobStatus(String jobId) {
        return jdbc.sql("SELECT status FROM processing_job WHERE id = :j").param("j", UUID.fromString(jobId)).query(String.class).single();
    }

    private String jobError(String jobId) {
        return jdbc.sql("SELECT error_code || ': ' || error_message FROM processing_job WHERE id = :j")
            .param("j", UUID.fromString(jobId)).query(String.class).single();
    }

    private void expireLease(String jobId, String ago) {
        jdbc.sql("UPDATE processing_job SET lease_expires_at = now() - CAST(:ago AS interval) WHERE id = :j")
            .param("ago", ago).param("j", UUID.fromString(jobId)).update();
    }

    private int count(String sql, Object id) {
        return jdbc.sql(sql).param("id", id).query(Integer.class).single();
    }

    @Test
    void aJobTheEnforcerCannotSettleNormallyIsStillFailedWithItsReasonAndDoesNotStrandTheOthers() throws Exception {
        var first = startRun();
        JsonNode poisoned = claimExpecting("INPUT_VALIDATION");
        var second = startRun();
        JsonNode healthy = claimExpecting("INPUT_VALIDATION");
        // The poisoned job's lease expired first, so the enforcer reaches it first. Writing its stage record is made to throw.
        expireLease(poisoned.get("id").asText(), "2 minutes");
        expireLease(healthy.get("id").asText(), "1 minute");
        jdbc.sql("CREATE FUNCTION test_poison_stage_run() RETURNS trigger AS $$ BEGIN "
            + "IF NEW.job_id = '" + poisoned.get("id").asText() + "' THEN RAISE EXCEPTION 'simulated failure'; END IF; "
            + "RETURN NEW; END $$ LANGUAGE plpgsql").update();
        jdbc.sql("CREATE TRIGGER test_poison_stage_run BEFORE INSERT ON pipeline_stage_run "
            + "FOR EACH ROW EXECUTE FUNCTION test_poison_stage_run()").update();
        try {
            pipeline.enforceLeasesAndDeadlines(); // must not throw
        } finally {
            jdbc.sql("DROP TRIGGER IF EXISTS test_poison_stage_run ON pipeline_stage_run").update();
            jdbc.sql("DROP FUNCTION IF EXISTS test_poison_stage_run()").update();
        }
        // The job behind the broken one is settled normally, with its stage record.
        assertThat(jobStatus(healthy.get("id").asText())).isEqualTo("FAILED");
        assertThat(processing(second).get("run").get("failureCode").asText()).isEqualTo("WORKER_LOST");
        assertThat(count("SELECT count(*) FROM pipeline_stage_run WHERE job_id = :id", UUID.fromString(healthy.get("id").asText())))
            .isEqualTo(1);
        // The broken one is not left RUNNING: job and run are failed directly, and the reason is kept on both.
        assertThat(jobStatus(poisoned.get("id").asText())).isEqualTo("FAILED");
        assertThat(jobError(poisoned.get("id").asText())).startsWith("WORKER_LOST: ").contains("stage record could not be written");
        JsonNode run = processing(first).get("run");
        assertThat(run.get("status").asText()).isEqualTo("FAILED");
        assertThat(run.get("failureCode").asText()).isEqualTo("WORKER_LOST");
        assertThat(count("SELECT count(*) FROM pipeline_stage_run WHERE job_id = :id", UUID.fromString(poisoned.get("id").asText())))
            .isZero();
        // ... and it can be retried like any other failed run.
        post(capUrl(first.c(), first.capture()) + "/processing/retry", first.c().operator(), null).andExpect(status().isAccepted());
        assertThat(claimExpecting("INPUT_VALIDATION").get("id").asText()).isEqualTo(poisoned.get("id").asText());
    }

    @Test
    void aJobLeftRunningInARunThatAlreadyEndedIsFailedWithItsReasonAndTheRunIsUntouched() throws Exception {
        var s = startRun();
        JsonNode order = claimExpecting("INPUT_VALIDATION");
        // A leftover: the run ended without settling this job (no code path does this today; the enforcer must cope anyway).
        jdbc.sql("UPDATE pipeline_run SET status = 'CANCELLED', finished_at = now() WHERE id = :r").param("r", s.run()).update();
        expireLease(order.get("id").asText(), "1 minute");

        pipeline.enforceLeasesAndDeadlines();

        assertThat(jobStatus(order.get("id").asText())).isEqualTo("FAILED");
        assertThat(jobError(order.get("id").asText())).startsWith("WORKER_LOST: ").contains("already ended CANCELLED");
        assertThat(processing(s).get("run").get("status").asText()).isEqualTo("CANCELLED");
        heartbeat(order, order.get("leaseToken").asText()).andExpect(jsonPath("$.keepGoing").value(false));
        send(order, report("SUCCEEDED", outputsFor(order), null, null), svc).andExpect(status().isConflict());
        assertThat(count("SELECT count(*) FROM processing_artifact WHERE job_id = :id", UUID.fromString(order.get("id").asText()))).isZero();
    }

    @Test
    void aDuplicateReportSubmissionIsRefusedAndRecordsNothingTwice() throws Exception {
        var s = startRun();
        JsonNode order = claimExpecting("INPUT_VALIDATION");
        UUID job = UUID.fromString(order.get("id").asText());
        var body = report("SUCCEEDED", outputsFor(order), null, null);
        send(order, body, svc).andExpect(status().isOk());
        int artifacts = count("SELECT count(*) FROM processing_artifact WHERE job_id = :id", job);
        assertThat(artifacts).isPositive();

        // A worker retrying its report after a timeout sends the same report again.
        send(order, body, svc).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("JOB_NOT_RUNNING"));

        assertThat(count("SELECT count(*) FROM pipeline_stage_run WHERE job_id = :id", job)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM processing_artifact WHERE job_id = :id", job)).isEqualTo(artifacts);
        assertThat(count("SELECT count(*) FROM processing_job WHERE run_id = :id AND stage = 'FFMPEG_PREPROCESS'", s.run()))
            .as("the next stage is queued exactly once").isEqualTo(1);
    }

    @Test
    void aLateReportForCancelledWorkPublishesNothing() throws Exception {
        var s = startRun();
        JsonNode order = claimExpecting("INPUT_VALIDATION");
        UUID job = UUID.fromString(order.get("id").asText());
        post(capUrl(s.c(), s.capture()) + "/processing/cancel", s.c().operator(), null).andExpect(status().isOk());

        send(order, report("SUCCEEDED", outputsFor(order), null, null), svc).andExpect(status().isConflict());

        assertThat(count("SELECT count(*) FROM pipeline_stage_run WHERE job_id = :id", job)).isZero();
        assertThat(count("SELECT count(*) FROM processing_artifact WHERE job_id = :id", job)).isZero();
        assertThat(count("SELECT count(*) FROM processing_job WHERE run_id = :id AND status IN ('QUEUED', 'RUNNING')", s.run())).isZero();
        assertThat(count("SELECT count(*) FROM scan_version WHERE pipeline_run_id = :id AND status = 'FINALIZED'", s.run())).isZero();
    }

    @Test
    void aRetryAfterWorkerLossKeepsTheFailureRecordAndProducesOneSetOfArtifacts() throws Exception {
        var s = startRun();
        JsonNode lost = claimExpecting("INPUT_VALIDATION");
        expireLease(lost.get("id").asText(), "1 minute");
        pipeline.enforceLeasesAndDeadlines();
        post(capUrl(s.c(), s.capture()) + "/processing/retry", s.c().operator(), null).andExpect(status().isAccepted());

        JsonNode again = claimExpecting("INPUT_VALIDATION");
        assertThat(again.get("id").asText()).as("the same job is re-queued, not a duplicate").isEqualTo(lost.get("id").asText());
        assertThat(again.get("attempt").asInt()).isEqualTo(2);
        assertThat(again.get("leaseToken").asText()).isNotEqualTo(lost.get("leaseToken").asText());
        // The lost worker comes back and reports with its old lease: refused, nothing recorded.
        send(lost, report("SUCCEEDED", outputsFor(lost), null, null), svc).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("LEASE_MISMATCH"));
        succeed(again);

        UUID job = UUID.fromString(again.get("id").asText());
        assertThat(jdbc.sql("SELECT attempt || ':' || status || ':' || coalesce(error_code, '-') FROM pipeline_stage_run "
                + "WHERE job_id = :j ORDER BY attempt").param("j", job).query(String.class).list())
            .as("the failure survives the retry").containsExactly("1:FAILED:WORKER_LOST", "2:SUCCEEDED:-");
        assertThat(jdbc.sql("SELECT DISTINCT sr.attempt FROM processing_artifact a JOIN pipeline_stage_run sr ON sr.id = a.stage_run_id "
                + "WHERE a.job_id = :j").param("j", job).query(Integer.class).list())
            .as("only the successful attempt has artifacts").containsExactly(2);
        // The operations dashboard still lists the attempt that was lost.
        get("/api/v1/venues/" + s.c().venue() + "/ops/failures", s.c().operator()).andExpect(status().isOk())
            .andExpect(jsonPath("$.failures[?(@.jobId == '" + job + "')].errorCode").value(hasItem("WORKER_LOST")));
    }

    @Test
    void workerLivenessIsMeasuredFromPollsAndUnservedStagesAreNamed() throws Exception {
        String workerId = "liveness-" + UUID.randomUUID();
        post(CLAIM, svc, "{\"stages\":[\"PLANE_FITTING\"],\"workerId\":\"" + workerId + "\"}");
        assertThat(jdbc.sql("SELECT array_to_string(stages, ',') FROM worker_presence WHERE worker_id = :w").param("w", workerId)
            .query(String.class).single()).isEqualTo("PLANE_FITTING");

        String health = get("/api/v1/health", null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode h = mapper.readTree(health);
        assertThat(h.get("worker").asText()).isEqualTo("UP");
        assertThat(h.get("processing").get("liveWorkers").asLong()).isPositive();

        Fixtures.Tree t = fx.tree();
        // A legacy stage no worker implements (BASELINE_AUDIT F6): nobody can ever claim it, and the dashboard says so.
        UUID queued = jdbc.sql("INSERT INTO processing_job (organization_id, venue_id, scan_id, stage) VALUES (:o, :v, :s, 'SEGMENTATION') "
                + "RETURNING id").param("o", t.org()).param("v", t.venue()).param("s", t.scan()).query(UUID.class).single();
        String manager = TestJwt.user(t.org(), "venue-manager").venues(t.venue()).token();
        try {
            get("/api/v1/venues/" + t.venue() + "/ops/jobs", manager).andExpect(status().isOk())
                .andExpect(jsonPath("$.queue.claimableQueued").value(1))
                .andExpect(jsonPath("$.queue.oldestQueuedAgeSeconds").isNumber())
                .andExpect(jsonPath("$.queue.unservedStages").value(org.hamcrest.Matchers.contains("SEGMENTATION")))
                .andExpect(jsonPath("$.queue.workers[?(@.workerId == '" + workerId + "')].live").value(hasItem(true)));

            jdbc.sql("UPDATE worker_presence SET last_seen_at = now() - interval '10 minutes' WHERE worker_id = :w").param("w", workerId).update();
            get("/api/v1/venues/" + t.venue() + "/ops/jobs", manager).andExpect(status().isOk())
                .andExpect(jsonPath("$.queue.workers[?(@.workerId == '" + workerId + "')].live").value(hasItem(false)));
        } finally {
            jdbc.sql("UPDATE processing_job SET status = 'CANCELLED', finished_at = now() WHERE id = :j").param("j", queued).update();
            jdbc.sql("DELETE FROM worker_presence WHERE worker_id = :w").param("w", workerId).update();
        }
    }

    @Test
    void aBusyWorkersHeartbeatKeepsItLive() throws Exception {
        startRun();
        JsonNode order = claimExpecting("INPUT_VALIDATION");
        jdbc.sql("UPDATE worker_presence SET last_seen_at = now() - interval '1 hour' WHERE worker_id = 'test-worker'").update();
        heartbeat(order, order.get("leaseToken").asText()).andExpect(jsonPath("$.keepGoing").value(true));
        assertThat(jdbc.sql("SELECT last_seen_at > now() - interval '1 minute' FROM worker_presence WHERE worker_id = 'test-worker'")
            .query(Boolean.class).single()).isTrue();
        // A heartbeat without the lease proves nothing and refreshes nothing.
        jdbc.sql("UPDATE worker_presence SET last_seen_at = now() - interval '1 hour' WHERE worker_id = 'test-worker'").update();
        heartbeat(order, "cjl_not-the-lease").andExpect(jsonPath("$.keepGoing").value(false));
        assertThat(jdbc.sql("SELECT last_seen_at < now() - interval '30 minutes' FROM worker_presence WHERE worker_id = 'test-worker'")
            .query(Boolean.class).single()).isTrue();
    }
}
