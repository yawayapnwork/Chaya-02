package dev.chaya.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import dev.chaya.api.pipeline.PipelineDefinition;
import dev.chaya.api.pipeline.PipelineService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Resuming a time-boxed SPLAT_RECONSTRUCTION from its checkpoint (gaussian-splatting-audit D-3; docs/pipeline.md,
 * "Resuming training"), through the real HTTP API against real PostgreSQL and MinIO. The worker is played by the test with
 * the reports the real one sends (chaya_worker.stages.splat_reconstruction; its half is tests/splat/test_checkpoint_resume.py
 * in the worker): a time-boxed attempt is FAILED with TIME_LIMIT_EXCEEDED and publishes SPLAT_PARTIAL (partial) and
 * SPLAT_CHECKPOINT; a refused checkpoint is FAILED with CHECKPOINT_CORRUPT / CHECKPOINT_MISMATCH naming it in
 * errorDetails.checkpoint, or INPUT_CHECKSUM_MISMATCH naming it in errorDetails.artifact_id. FIXTURE CONTENT: the bytes are
 * not a torch checkpoint; the control plane only registers, seals, offers and checks them.
 */
class SplatResumeControlPlaneTest extends PipelineTestSupport {

    @Autowired PipelineService pipeline;

    // ---- what a time-boxed attempt leaves, and how a retry resumes it ------------------------------------------------

    @Test
    void aTimeBoxedTrainingIsRegisteredPartialAndARetryResumesItsCheckpointToAFinalRun() throws Exception {
        var s = startRun();
        runThrough("POSE_ESTIMATION");
        JsonNode first = claimExpecting("SPLAT_RECONSTRUCTION");
        assertThat(kinds(first)).as("a first attempt has nothing to resume").doesNotContain("SPLAT_CHECKPOINT");
        Map<String, Object> checkpoint = timeBoxed(first, "checkpoint-at-1200");

        JsonNode run = processing(s).get("run");
        assertThat(run.get("status").asText()).isEqualTo("PARTIAL");
        assertThat(run.get("quality").asText()).isEqualTo("PARTIAL");
        assertThat(run.get("retryable").asBoolean()).as("the time box stopped training with a checkpoint to resume").isTrue();
        assertThat(captureStatus(s)).isEqualTo("COMPLETED");
        // The partial checkpoint is registered: sealed, on the FAILED stage run, with the checksum the worker reported.
        Map<String, Object> row = jdbc.sql("""
                SELECT a.id, a.object_key, a.checksum_sha256, a.sealed, a.contains_pii, sr.status, sr.error_code
                  FROM processing_artifact a JOIN pipeline_stage_run sr ON sr.id = a.stage_run_id
                 WHERE sr.run_id = :r AND a.kind = 'SPLAT_CHECKPOINT'
                """).param("r", s.run()).query().singleRow();
        assertThat(row.get("sealed")).isEqualTo(true);
        assertThat(row.get("contains_pii")).isEqualTo(false);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("error_code")).isEqualTo("TIME_LIMIT_EXCEEDED");
        assertThat(row.get("checksum_sha256").toString().trim()).isEqualTo(checkpoint.get("sha256"));
        assertThat((String) row.get("object_key")).startsWith("sealed/").isNotEqualTo(checkpoint.get("key"));
        assertThat(existsInDerived((String) row.get("object_key"))).isTrue();

        retry(s).andExpect(status().isAccepted()).andExpect(jsonPath("$.run.status").value("RUNNING"))
            .andExpect(jsonPath("$.run.quality").doesNotExist()).andExpect(jsonPath("$.run.failureCode").doesNotExist());
        JsonNode second = claimExpecting("SPLAT_RECONSTRUCTION");
        assertThat(second.get("attempt").asInt()).isEqualTo(2);
        assertThat(second.get("outputPrefix").asText()).endsWith("/SPLAT_RECONSTRUCTION/attempt-2/");
        // Exactly the registered checkpoint, by its sealed key and recorded checksum, and nothing else of the failed attempt.
        List<JsonNode> offered = inputsOf(second, "SPLAT_CHECKPOINT");
        assertThat(offered).hasSize(1);
        assertThat(offered.get(0).get("artifactId").asText()).isEqualTo(row.get("id").toString());
        assertThat(offered.get(0).get("key").asText()).isEqualTo(row.get("object_key"));
        assertThat(offered.get(0).get("sha256").asText()).isEqualTo(checkpoint.get("sha256"));
        assertThat(offered.get(0).get("containsPii").asBoolean()).isFalse();
        assertThat(kinds(second)).doesNotContain("SPLAT_PARTIAL", "SPLAT_TRAINING_REPORT", "KEYFRAME_RENDERS")
            .contains("SPARSE_MODEL", "POSES", "FRAME_ARCHIVE_ANON");
        assertThat(auditMeta(s.run()).get("resumeCheckpointId").asText()).isEqualTo(row.get("id").toString());
        assertThat(auditMeta(s.run()).get("reopenedPartial").asBoolean()).isTrue();

        succeed(second); // the resumed training completed: SPLAT
        JsonNode next = claimExpecting("SEMANTIC_SEGMENTATION");
        assertThat(kinds(next)).contains("SPLAT").as("a partial cloud never reaches a later stage").doesNotContain("SPLAT_PARTIAL");
        finishFrom(next);

        JsonNode done = processing(s).get("run");
        assertThat(done.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(done.get("quality").asText()).isEqualTo("FINAL");
        assertThat(done.get("retryable").asBoolean()).isFalse();
        JsonNode splatStage = done.get("stages").get(5);
        assertThat(splatStage.get("state").asText()).isEqualTo("SUCCEEDED");
        assertThat(splatStage.get("attempts").asInt()).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM processing_artifact a JOIN pipeline_stage_run sr ON sr.id = a.stage_run_id "
            + "WHERE sr.run_id = :r AND a.kind = 'SPLAT_CHECKPOINT'", s.run())).as("one per attempt, none duplicated").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM processing_artifact a JOIN pipeline_stage_run sr ON sr.id = a.stage_run_id "
            + "WHERE sr.run_id = :r AND a.kind = 'SPLAT' AND sr.status = 'SUCCEEDED'", s.run())).isEqualTo(1);
        assertThat(auditCount(s.c().org(), "pipeline.partial")).isEqualTo(1);
        assertThat(auditCount(s.c().org(), "pipeline.succeeded")).isEqualTo(1);
    }

    @Test
    void repeatedTimeBoxedAttemptsEachResumeTheLatestCheckpointAndRetriesStayBounded() throws Exception {
        var s = startRun();
        runThrough("POSE_ESTIMATION");
        List<String> registered = new ArrayList<>();
        JsonNode order = claimExpecting("SPLAT_RECONSTRUCTION");
        for (int attempt = 1; attempt <= 4; attempt++) { // the first attempt + max_retries (3)
            if (attempt > 1) {
                assertThat(inputsOf(order, "SPLAT_CHECKPOINT")).extracting(i -> i.get("artifactId").asText())
                    .as("attempt %d resumes the checkpoint the previous attempt left", attempt).containsExactly(registered.get(attempt - 2));
            }
            timeBoxed(order, "checkpoint-of-attempt-" + attempt);
            registered.add(latestCheckpointId(s.run()));
            assertThat(processing(s).get("run").get("status").asText()).isEqualTo("PARTIAL");
            if (attempt < 4) {
                retry(s).andExpect(status().isAccepted());
                order = claimExpecting("SPLAT_RECONSTRUCTION");
                assertThat(order.get("attempt").asInt()).isEqualTo(attempt + 1);
            }
        }
        assertThat(registered).doesNotHaveDuplicates();
        JsonNode run = processing(s).get("run");
        assertThat(run.get("retryable").asBoolean()).isFalse();
        assertThat(run.get("retryBlockedReason").asText()).isEqualTo("RETRIES_EXHAUSTED");
        retry(s).andExpect(status().isConflict());
        assertThat(processing(s).get("run").get("status").asText()).as("a refused retry leaves the run as it was").isEqualTo("PARTIAL");
    }

    // ---- retries without a checkpoint ---------------------------------------------------------------------------------

    @Test
    void aTimeLimitWithoutACheckpointFailsAndItsRetryStartsFromTheSfmSeed() throws Exception {
        var s = startRun();
        runThrough("POSE_ESTIMATION");
        JsonNode first = claimExpecting("SPLAT_RECONSTRUCTION");
        // The control plane ended the attempt (deadline plus grace), or no iteration ran: no splat and no checkpoint.
        send(first, report("FAILED", List.of(), "TIME_LIMIT_EXCEEDED", "stopped before any iteration"), svc).andExpect(status().isOk());
        assertThat(processing(s).get("run").get("status").asText()).isEqualTo("FAILED");
        retry(s).andExpect(status().isAccepted());
        JsonNode second = claimExpecting("SPLAT_RECONSTRUCTION");
        assertThat(kinds(second)).doesNotContain("SPLAT_CHECKPOINT").contains("SPARSE_MODEL", "POSES");
        assertThat(auditMeta(s.run()).has("resumeCheckpointId")).isFalse();
    }

    @Test
    void aPartialRunWithoutACheckpointToResumeIsNotRetryable() throws Exception {
        var s = startRun();
        runThrough("POSE_ESTIMATION");
        JsonNode splat = claimExpecting("SPLAT_RECONSTRUCTION");
        send(splat, report("FAILED", List.of(artifact(splat, "splat-partial.ply", "SPLAT_PARTIAL", false, true, "partial-cloud")),
            "TIME_LIMIT_EXCEEDED", "the time budget stopped training"), svc).andExpect(status().isOk());
        JsonNode run = processing(s).get("run");
        assertThat(run.get("status").asText()).isEqualTo("PARTIAL");
        assertThat(run.get("retryable").asBoolean()).isFalse();
        retry(s).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RUN_NOT_RETRYABLE"));
        assertThat(processing(s).get("run").get("status").asText()).isEqualTo("PARTIAL");
    }

    @Test
    void aRunThatRanOutOfTimeBetweenStagesStaysTerminalEvenInTheDatabase() throws Exception {
        var s = startRun();
        runThrough("SPLAT_RECONSTRUCTION");
        jdbc.sql("UPDATE pipeline_run SET deadline_at = now() - interval '1 second' WHERE id = :r").param("r", s.run()).update();
        pipeline.enforceLeasesAndDeadlines();
        assertThat(processing(s).get("run").get("status").asText()).isEqualTo("PARTIAL");
        assertThat(processing(s).get("run").get("retryable").asBoolean()).isFalse();
        retry(s).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RUN_NOT_RETRYABLE"));
        // The run guard (V43) reopens only a PARTIAL run whose splat training the time budget stopped.
        assertThatThrownBy(() -> jdbc.sql("UPDATE pipeline_run SET status = 'RUNNING', quality = NULL WHERE id = :r")
            .param("r", s.run()).update()).hasMessageContaining("invalid pipeline_run transition PARTIAL -> RUNNING");
    }

    // ---- refused checkpoints are never offered again --------------------------------------------------------------------

    @Test
    void aCheckpointAWorkerFoundCorruptIsNotOfferedAgainAndTheRetryFallsBackToThePreviousOne() throws Exception {
        var s = startRun();
        runThrough("POSE_ESTIMATION");
        timeBoxed(claimExpecting("SPLAT_RECONSTRUCTION"), "checkpoint-a");
        String a = latestCheckpointId(s.run());
        retry(s).andExpect(status().isAccepted());
        timeBoxed(claimExpecting("SPLAT_RECONSTRUCTION"), "checkpoint-b");
        String b = latestCheckpointId(s.run());
        retry(s).andExpect(status().isAccepted());

        JsonNode third = claimExpecting("SPLAT_RECONSTRUCTION");
        assertThat(inputsOf(third, "SPLAT_CHECKPOINT")).extracting(i -> i.get("artifactId").asText()).containsExactly(b);
        refuse(third, "CHECKPOINT_CORRUPT", Map.of("checkpoint", b));
        assertThat(processing(s).get("run").get("status").asText()).as("a refused resume is an ordinary failure").isEqualTo("FAILED");
        retry(s).andExpect(status().isAccepted());

        JsonNode fourth = claimExpecting("SPLAT_RECONSTRUCTION");
        assertThat(inputsOf(fourth, "SPLAT_CHECKPOINT")).extracting(i -> i.get("artifactId").asText())
            .as("the latest checkpoint no attempt refused").containsExactly(a);
    }

    @Test
    void aCheckpointWhoseObjectNoLongerMatchesItsChecksumIsNotOfferedAgain() throws Exception {
        var s = startRun();
        runThrough("POSE_ESTIMATION");
        timeBoxed(claimExpecting("SPLAT_RECONSTRUCTION"), "checkpoint-a");
        String a = latestCheckpointId(s.run());
        retry(s).andExpect(status().isAccepted());
        // chaya_worker.orchestrator._download_inputs: the downloaded bytes are not what was registered.
        refuse(claimExpecting("SPLAT_RECONSTRUCTION"), "INPUT_CHECKSUM_MISMATCH", Map.of("artifact_id", a, "kind", "SPLAT_CHECKPOINT"));
        retry(s).andExpect(status().isAccepted());
        assertThat(kinds(claimExpecting("SPLAT_RECONSTRUCTION"))).as("no valid checkpoint is left: from the SfM seed")
            .doesNotContain("SPLAT_CHECKPOINT").contains("SPARSE_MODEL");
    }

    @Test
    void aMismatchRefusalOnlyRetiresTheCheckpointItNames() throws Exception {
        var s = startRun();
        runThrough("POSE_ESTIMATION");
        timeBoxed(claimExpecting("SPLAT_RECONSTRUCTION"), "checkpoint-a");
        String a = latestCheckpointId(s.run());
        retry(s).andExpect(status().isAccepted());
        // A refusal that names some other artifact does not retire this run's checkpoint.
        refuse(claimExpecting("SPLAT_RECONSTRUCTION"), "CHECKPOINT_MISMATCH", Map.of("checkpoint", UUID.randomUUID().toString()));
        retry(s).andExpect(status().isAccepted());
        JsonNode third = claimExpecting("SPLAT_RECONSTRUCTION");
        assertThat(inputsOf(third, "SPLAT_CHECKPOINT")).extracting(i -> i.get("artifactId").asText()).containsExactly(a);
        // The worker found it is of other inputs or settings (chaya_worker.splat_training.load_checkpoint).
        refuse(third, "CHECKPOINT_MISMATCH", Map.of("checkpoint", a));
        retry(s).andExpect(status().isAccepted());
        assertThat(kinds(claimExpecting("SPLAT_RECONSTRUCTION"))).doesNotContain("SPLAT_CHECKPOINT");
    }

    // ---- ownership: only this run's own, time-boxed checkpoints ------------------------------------------------------------

    @Test
    void aCheckpointIsNeverOfferedToAnotherRunOrScanOrTenant() throws Exception {
        var mine = startRun();
        runThrough("POSE_ESTIMATION");
        timeBoxed(claimExpecting("SPLAT_RECONSTRUCTION"), "checkpoint-of-mine");
        String checkpoint = latestCheckpointId(mine.run());

        // Another run (another capture and scan) in the same venue, then one in another organization: each started only
        // once the one before is idle, so the shared claim queue holds only its jobs.
        for (Ctx c : List.of(mine.c(), ctx())) {
            var theirs = startRun(c, null);
            runThrough("POSE_ESTIMATION").forEach(o -> assertThat(o.get("runId").asText()).isEqualTo(theirs.run().toString()));
            JsonNode splat = claimExpecting("SPLAT_RECONSTRUCTION");
            assertThat(splat.get("runId").asText()).isEqualTo(theirs.run().toString());
            assertThat(kinds(splat)).doesNotContain("SPLAT_CHECKPOINT");
            send(splat, report("FAILED", List.of(), "TIME_LIMIT_EXCEEDED", "stopped"), svc).andExpect(status().isOk());
            retry(theirs).andExpect(status().isAccepted());
            JsonNode again = claimExpecting("SPLAT_RECONSTRUCTION");
            assertThat(again.get("inputs").findValuesAsText("artifactId")).doesNotContain(checkpoint);
            assertThat(kinds(again)).doesNotContain("SPLAT_CHECKPOINT");
            send(again, report("FAILED", List.of(), "GPU_RUNTIME_ERROR", "stopped"), svc).andExpect(status().isOk());
        }
        // Everything the resumed attempt is offered lives under its own tenant's prefix (TenantKeys, checked at claim too).
        retry(mine).andExpect(status().isAccepted());
        JsonNode resumed = claimExpecting("SPLAT_RECONSTRUCTION");
        assertThat(resumed.get("runId").asText()).isEqualTo(mine.run().toString());
        String tenant = "org/" + mine.c().org() + "/venue/" + mine.c().venue() + "/";
        assertThat(inputsOf(resumed, "SPLAT_CHECKPOINT")).extracting(i -> i.get("artifactId").asText()).containsExactly(checkpoint);
        for (JsonNode in : resumed.get("inputs")) {
            assertThat(in.get("key").asText()).startsWith("sealed/" + tenant);
        }
    }

    @Test
    void onlyTheCheckpointOfAnAttemptTheTimeBudgetStoppedIsOffered() throws Exception {
        var s = startRun();
        runThrough("POSE_ESTIMATION");
        JsonNode splat = claimExpecting("SPLAT_RECONSTRUCTION");
        // A failure of another kind that (unlike the real worker) still reported a checkpoint: not a state to resume.
        send(splat, report("FAILED", List.of(artifact(splat, "splat-checkpoint.pt", "SPLAT_CHECKPOINT", false, false, "oom-state")),
            "SPLAT_GPU_OUT_OF_MEMORY", "the GPU ran out of memory"), svc).andExpect(status().isOk());
        retry(s).andExpect(status().isAccepted());
        assertThat(kinds(claimExpecting("SPLAT_RECONSTRUCTION"))).doesNotContain("SPLAT_CHECKPOINT");
    }

    @Test
    void theResumeRetryKeepsTheRunsAccessControl() throws Exception {
        var s = startRun();
        runThrough("POSE_ESTIMATION");
        timeBoxed(claimExpecting("SPLAT_RECONSTRUCTION"), "checkpoint");
        String viewer = TestJwt.user(s.c().org(), "viewer").venues(s.c().venue()).token();
        post(capUrl(s.c(), s.capture()) + "/processing/retry", viewer, null).andExpect(status().isForbidden());
        String otherOrg = TestJwt.user(fx.organization(), "admin").token();
        post(capUrl(s.c(), s.capture()) + "/processing/retry", otherOrg, null).andExpect(status().isNotFound());
        assertThat(auditCount(s.c().org(), "pipeline.retry")).as("a refused retry is not recorded as one").isZero();
        assertThat(processing(s).get("run").get("status").asText()).isEqualTo("PARTIAL");
        retry(s).andExpect(status().isAccepted());
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------------

    /** What the real worker reports when the time budget stops training after some iterations. Returns the checkpoint entry. */
    private Map<String, Object> timeBoxed(JsonNode order, String checkpointBytes) throws Exception {
        Map<String, Object> checkpoint = artifact(order, "splat-checkpoint.pt", "SPLAT_CHECKPOINT", false, false, checkpointBytes);
        Map<String, Object> r = report("FAILED", List.of(
                artifact(order, "splat-partial.ply", "SPLAT_PARTIAL", false, true, "partial-cloud:" + checkpointBytes), checkpoint,
                artifact(order, "splat-training-report.json", "SPLAT_TRAINING_REPORT", false, false, "{\"status\":\"PARTIAL\"}")),
            "TIME_LIMIT_EXCEEDED", "the time budget stopped training after 1200 of 7000 iterations");
        r.put("errorDetails", Map.of("completed_iterations", 1200, "target_iterations", 7000, "checkpoint", "splat-checkpoint.pt"));
        send(order, r, svc).andExpect(status().isOk());
        return checkpoint;
    }

    private void refuse(JsonNode order, String code, Map<String, Object> details) throws Exception {
        Map<String, Object> r = report("FAILED", List.of(), code, "the offered checkpoint was refused");
        r.put("errorDetails", details);
        send(order, r, svc).andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions retry(Started s) throws Exception {
        return post(capUrl(s.c(), s.capture()) + "/processing/retry", s.c().operator(), null);
    }

    private static List<String> kinds(JsonNode order) {
        return order.get("inputs").findValuesAsText("kind");
    }

    private static List<JsonNode> inputsOf(JsonNode order, String kind) {
        List<JsonNode> out = new ArrayList<>();
        order.get("inputs").forEach(i -> {
            if (i.get("kind").asText().equals(kind)) {
                out.add(i);
            }
        });
        return out;
    }

    private String latestCheckpointId(UUID run) {
        return jdbc.sql("SELECT a.id FROM processing_artifact a JOIN pipeline_stage_run sr ON sr.id = a.stage_run_id "
                + "WHERE sr.run_id = :r AND a.kind = 'SPLAT_CHECKPOINT' ORDER BY sr.created_at DESC LIMIT 1")
            .param("r", run).query(UUID.class).single().toString();
    }

    private int count(String sql, UUID run) {
        return jdbc.sql(sql).param("r", run).query(Integer.class).single();
    }

    private JsonNode auditMeta(UUID run) throws Exception {
        String meta = jdbc.sql("SELECT CAST(metadata AS text) FROM audit_log WHERE action = 'pipeline.retry' AND resource_id = :r "
            + "ORDER BY occurred_at DESC LIMIT 1").param("r", run).query(String.class).single();
        return mapper.readTree(meta);
    }

    /** Succeeds `first` and every later stage, calibrating the run before its first metric stage as runThrough does. */
    private void finishFrom(JsonNode first) throws Exception {
        JsonNode order = first;
        int idx = PipelineDefinition.STAGES.stream().map(Enum::name).toList().indexOf(first.get("stage").asText());
        for (int i = idx; i < PipelineDefinition.STAGES.size(); i++) {
            String stage = PipelineDefinition.STAGES.get(i).name();
            if (i > idx) {
                order = claimExpecting(stage);
            }
            if (METRIC_STAGES.contains(stage) && frameOf(order) == null) {
                send(order, report("FAILED", List.of(), "NOT_CALIBRATED", "the reconstruction has no canonical coordinate frame"), svc)
                    .andExpect(status().isOk());
                calibrateRun(order);
                retryRun(order);
                order = claimExpecting(stage);
            }
            succeed(order);
        }
        assertThat(claim()).as("nothing left to do").isNull();
    }
}
