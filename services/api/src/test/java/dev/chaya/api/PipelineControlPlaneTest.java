package dev.chaya.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import dev.chaya.api.pipeline.PipelineDefinition;
import dev.chaya.api.pipeline.PipelineService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The control plane's orchestration rules, exercised through the real HTTP API against real PostgreSQL
 * and real MinIO objects. The worker is played by the test, submitting the same reports a real worker does.
 */
class PipelineControlPlaneTest extends PipelineTestSupport {

    @Autowired PipelineService pipeline;

    // ---- success only when every stage succeeded --------------------------------------------

    @Test
    void aRunSucceedsAsFinalOnlyAfterEveryStageSucceeded() throws Exception {
        var s = startRun();
        List<JsonNode> orders = new java.util.ArrayList<>();
        for (var stage : PipelineDefinition.STAGES) {
            // Not final while stages remain.
            assertThat(processing(s).get("run").get("status").asText()).isEqualTo("RUNNING");
            assertThat(processing(s).get("run").get("quality").isNull()).isTrue();
            JsonNode order = claimExpecting(stage.name());
            orders.add(order);
            assertThat(order.get("runId").asText()).isEqualTo(s.run().toString());
            assertThat(order.get("outputPrefix").asText()).contains("/run/" + s.run() + "/" + stage + "/attempt-1/");
            succeed(order);
        }
        assertThat(claim()).as("nothing left to do").isNull();

        JsonNode p = processing(s);
        assertThat(p.get("run").get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(p.get("run").get("quality").asText()).isEqualTo("FINAL");
        assertThat(captureStatus(s)).isEqualTo("COMPLETED");
        for (JsonNode stage : p.get("run").get("stages")) {
            assertThat(stage.get("state").asText()).isEqualTo("SUCCEEDED");
            JsonNode last = stage.get("lastRun");
            assertThat(last.get("startedAt").isNull()).isFalse();
            assertThat(last.get("finishedAt").isNull()).isFalse();
            assertThat(last.get("exitStatus").asInt()).isZero();
            assertThat(last.get("command").get("argv").get(0).asText()).isEqualTo("test-stage");
            assertThat(last.get("outputSha256").asText()).matches("[0-9a-f]{64}");
            assertThat(last.get("artifacts").size()).isEqualTo(1);
        }
        assertThat(jdbc.sql("SELECT count(*) FROM pipeline_stage_run WHERE run_id = :r").param("r", s.run()).query(Integer.class).single())
            .isEqualTo(12);
        assertThat(auditCount(s.c().org(), "pipeline.stage_succeeded")).isEqualTo(12);
        assertThat(auditCount(s.c().org(), "pipeline.succeeded")).isEqualTo(1);
    }

    @Test
    void firstStageReadsTheAcceptedRawMedia() throws Exception {
        var s = startRun();
        JsonNode order = claimExpecting("INPUT_VALIDATION");
        assertThat(order.get("inputs")).hasSize(1);
        assertThat(order.get("inputs").get(0).get("kind").asText()).isEqualTo("RAW_VIDEO");
        assertThat(order.get("inputs").get(0).get("bucket").asText()).isEqualTo("chaya-raw-test");
        assertThat(order.get("derivedBucket").asText()).isEqualTo("chaya-derived-test");
        assertThat(order.get("deadlineAt").isNull()).isFalse();
    }

    // ---- failures: preserved artifacts, no false success, retry -----------------------------

    @Test
    void aFailedStageStopsTheRunKeepsEarlierArtifactsAndCanBeRetried() throws Exception {
        var s = startRun();
        List<JsonNode> done = runThrough("PRIVACY_PREPROCESS");
        String anonKey = outputsForKey(done.get(3));

        JsonNode pose = claimExpecting("POSE_ESTIMATION");
        // The privacy stage's clean output is offered as input, the raw frames are not.
        assertThat(pose.get("inputs").findValuesAsText("kind")).contains("FRAME_ANON").doesNotContain("FRAME", "FRAME_SELECTION");

        Map<String, Object> failure = report("FAILED", List.of(), "DEPENDENCY_UNAVAILABLE",
            "Neither GLOMAP nor COLMAP is installed on this worker.");
        failure.put("errorDetails", Map.of("missing", List.of("glomap", "colmap")));
        failure.put("exitStatus", null);
        send(pose, failure, svc).andExpect(status().isOk());

        JsonNode p = processing(s);
        assertThat(p.get("run").get("status").asText()).isEqualTo("FAILED");
        assertThat(p.get("run").get("quality").isNull()).as("a failed run has no quality").isTrue();
        assertThat(p.get("run").get("failureStage").asText()).isEqualTo("POSE_ESTIMATION");
        assertThat(p.get("run").get("failureCode").asText()).isEqualTo("DEPENDENCY_UNAVAILABLE");
        assertThat(p.get("run").get("retryable").asBoolean()).isTrue();
        assertThat(captureStatus(s)).as("processing did not succeed").isEqualTo("PROCESSING");
        JsonNode poseStage = p.get("run").get("stages").get(4);
        assertThat(poseStage.get("state").asText()).isEqualTo("FAILED");
        assertThat(poseStage.get("lastRun").get("errorDetails").get("missing")).hasSize(2);
        assertThat(p.get("run").get("stages").get(5).get("state").asText()).as("later stages never started").isEqualTo("PENDING");
        assertThat(claim()).as("nothing after the failed stage is queued").isNull();
        // Earlier valid artifacts are untouched, in storage and in the database.
        assertThat(existsInDerived(anonKey)).isTrue();
        assertThat(p.get("run").get("stages").get(3).get("state").asText()).isEqualTo("SUCCEEDED");

        // Retry: same stage, attempt 2, with the earlier outputs still available as inputs.
        post(capUrl(s.c(), s.capture()) + "/processing/retry", s.c().operator(), null).andExpect(status().isAccepted())
            .andExpect(jsonPath("$.run.status").value("RUNNING")).andExpect(jsonPath("$.run.failureCode").doesNotExist());
        JsonNode again = claimExpecting("POSE_ESTIMATION");
        assertThat(again.get("attempt").asInt()).isEqualTo(2);
        assertThat(again.get("outputPrefix").asText()).endsWith("/POSE_ESTIMATION/attempt-2/");
        assertThat(again.get("inputs").findValuesAsText("kind")).contains("FRAME_ANON");
        succeed(again);
        assertThat(claim().get("stage").asText()).isEqualTo("SPLAT_RECONSTRUCTION");
        assertThat(jdbc.sql("SELECT count(*) FROM pipeline_stage_run WHERE run_id = :r AND stage = 'POSE_ESTIMATION'").param("r", s.run())
            .query(Integer.class).single()).isEqualTo(2);
    }

    private String outputsForKey(JsonNode order) {
        return jdbc.sql("SELECT a.object_key FROM processing_artifact a WHERE a.job_id = :j AND a.kind = 'FRAME_ANON'")
            .param("j", UUID.fromString(order.get("id").asText())).query(String.class).single();
    }

    @Test
    void retriesAreBoundedAndOnlyFailedRunsCanBeRetried() throws Exception {
        var s = startRun();
        post(capUrl(s.c(), s.capture()) + "/processing/retry", s.c().operator(), null).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("RUN_NOT_RETRYABLE"));
        for (int attempt = 1; attempt <= 4; attempt++) { // initial run + 3 retries (max_retries = 3)
            JsonNode order = claimExpecting("INPUT_VALIDATION");
            send(order, report("FAILED", List.of(), "INPUT_INVALID", "no decodable video"), svc).andExpect(status().isOk());
            if (attempt < 4) {
                post(capUrl(s.c(), s.capture()) + "/processing/retry", s.c().operator(), null).andExpect(status().isAccepted());
            }
        }
        post(capUrl(s.c(), s.capture()) + "/processing/retry", s.c().operator(), null).andExpect(status().isConflict());
        assertThat(processing(s).get("run").get("status").asText()).isEqualTo("FAILED");
    }

    @Test
    void thePrivacyBoundaryHoldsOnTheServer() throws Exception {
        var s = startRun();
        succeed(claimExpecting("INPUT_VALIDATION"));
        JsonNode frames = claimExpecting("FFMPEG_PREPROCESS");
        assertThat(frames.get("inputs").findValuesAsText("kind")).contains("RAW_VIDEO");
        assertThat(frames.get("inputs").get(0).get("containsPii").asBoolean()).isTrue();
        Map<String, Object> pii = outputsFor(frames).get(0);
        succeed(frames);
        String piiKey = (String) pii.get("key");
        assertThat(existsInDerived(piiKey)).as("raw frames exist until the privacy stage has finished").isTrue();
        succeed(claimExpecting("FRAME_QUALITY_FILTER"));
        JsonNode privacy = claimExpecting("PRIVACY_PREPROCESS");
        assertThat(privacy.get("inputs").findValuesAsText("kind")).contains("FRAME");
        succeed(privacy);

        // The unblurred frames are deleted as soon as the privacy stage succeeds.
        assertThat(existsInDerived(piiKey)).as("PII staging objects are purged").isFalse();
        assertThat(auditCount(s.c().org(), "pipeline.pii_purged")).isEqualTo(1);

        // A later stage may not smuggle PII forward, and cannot register keys outside its prefix.
        JsonNode pose = claimExpecting("POSE_ESTIMATION");
        Map<String, Object> smuggled = artifact(pose, "frame.png", "FRAME", true, false, "raw-face");
        send(pose, report("SUCCEEDED", List.of(smuggled), null, null), svc).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PII_AFTER_PRIVACY"));
        Map<String, Object> outside = new java.util.LinkedHashMap<>(artifact(pose, "ok.json", "POSES", false, false, "{}"));
        outside.put("key", "org/other/tenant/poses.json");
        send(pose, report("SUCCEEDED", List.of(outside), null, null), svc).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ARTIFACT_INVALID"));
        // Rejected reports change nothing: the job is still running and can report properly.
        assertThat(jdbc.sql("SELECT status FROM processing_job WHERE id = :j").param("j", UUID.fromString(pose.get("id").asText()))
            .query(String.class).single()).isEqualTo("RUNNING");
        succeed(pose);
    }

    @Test
    void privacyPreprocessingCanOnlyBeSkippedByAnAdministratorAndIsAudited() throws Exception {
        var c = ctx();
        UUID capture = newCapture(c);
        var u = upload(c, capture, "VIDEO", "v.mp4", "video/mp4", mp4(4096));
        awaitSettled(c, capture, u.mediaId());
        post(capUrl(c, capture) + "/complete-upload", c.operator(), null).andExpect(status().isOk());
        post(capUrl(c, capture) + "/processing", c.operator(), "{\"privacyEnabled\":false}").andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("PRIVACY_OPT_OUT_REQUIRES_ADMIN"));
        String admin = TestJwt.user(c.org(), "admin").token();
        post(capUrl(c, capture) + "/processing", admin, "{\"privacyEnabled\":false}").andExpect(status().isAccepted())
            .andExpect(jsonPath("$.run.privacyEnabled").value(false)).andExpect(jsonPath("$.run.stages.length()").value(11));
    }

    @Test
    void reportedArtifactsMustReallyExistInStorageWithTheReportedSize() throws Exception {
        var s = startRun();
        JsonNode order = claimExpecting("INPUT_VALIDATION");
        Map<String, Object> real = artifact(order, "validation.json", "INPUT_REPORT", false, false, "{\"ok\":true}");

        Map<String, Object> missing = new java.util.LinkedHashMap<>(real);
        missing.put("key", order.get("outputPrefix").asText() + "never-uploaded.json");
        send(order, report("SUCCEEDED", List.of(missing), null, null), svc).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ARTIFACT_MISSING"));

        Map<String, Object> wrongSize = new java.util.LinkedHashMap<>(real);
        wrongSize.put("sizeBytes", 999);
        send(order, report("SUCCEEDED", List.of(wrongSize), null, null), svc).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ARTIFACT_SIZE_MISMATCH"));

        Map<String, Object> piiMislabelled = new java.util.LinkedHashMap<>(real);
        piiMislabelled.put("containsPii", true); // pii artifacts must live under pii/
        send(order, report("SUCCEEDED", List.of(piiMislabelled), null, null), svc).andExpect(status().isConflict());

        Map<String, Object> traversal = new java.util.LinkedHashMap<>(real);
        traversal.put("key", order.get("outputPrefix").asText() + "../escape.json");
        send(order, report("SUCCEEDED", List.of(traversal), null, null), svc).andExpect(status().isConflict());

        // Structurally invalid reports are refused.
        send(order, report("FAILED", List.of(), null, null), svc).andExpect(status().isBadRequest());
        send(order, report("MAYBE", List.of(), null, null), svc).andExpect(status().isBadRequest());

        send(order, report("SUCCEEDED", List.of(real), null, null), svc).andExpect(status().isOk());
        // A job that already reported cannot report again.
        send(order, report("SUCCEEDED", List.of(real), null, null), svc).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("JOB_NOT_RUNNING"));
    }

    @Test
    void legacyCompleteAndFailCannotBypassTheStageReport() throws Exception {
        startRun();
        JsonNode order = claimExpecting("INPUT_VALIDATION");
        post("/api/v1/internal/jobs/" + order.get("id").asText() + "/complete", svc, null).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("USE_STAGE_REPORT"));
        post("/api/v1/internal/jobs/" + order.get("id").asText() + "/fail", svc, "{\"errorCode\":\"X\",\"errorMessage\":\"y\"}")
            .andExpect(status().isConflict());
    }

    // ---- the time box ----------------------------------------------------------------------

    @Test
    void whenTheBudgetRunsOutAfterAReconstructionExistsTheResultIsExplicitlyPartial() throws Exception {
        var s = startRun();
        runThrough("SPLAT_RECONSTRUCTION");
        jdbc.sql("UPDATE pipeline_run SET deadline_at = now() - interval '1 second' WHERE id = :r").param("r", s.run()).update();
        assertThat(claim()).as("no new stage is handed out after the deadline").isNull();

        assertThat(pipeline.enforceLeasesAndDeadlines()).isPositive();

        JsonNode run = processing(s).get("run");
        assertThat(run.get("status").asText()).isEqualTo("PARTIAL");
        assertThat(run.get("quality").asText()).isEqualTo("PARTIAL");
        assertThat(run.get("failureCode").asText()).isEqualTo("TIME_LIMIT_EXCEEDED");
        assertThat(run.get("failureMessage").asText()).contains("partial quality").contains("not finalized");
        assertThat(run.get("stages").get(6).get("state").asText()).as("skipped stage is reported as not run").isEqualTo("CANCELLED");
        assertThat(captureStatus(s)).isEqualTo("COMPLETED");
        assertThat(auditCount(s.c().org(), "pipeline.partial")).isEqualTo(1);
    }

    @Test
    void aStageThatFinishesAfterTheDeadlineStopsTheRunInsteadOfStartingTheNext() throws Exception {
        var s = startRun();
        runThrough("SPLAT_RECONSTRUCTION");
        JsonNode next = claimExpecting("SEMANTIC_SEGMENTATION");
        jdbc.sql("UPDATE pipeline_run SET deadline_at = now() - interval '1 second' WHERE id = :r").param("r", s.run()).update();
        succeed(next); // this stage finished, but the budget is gone: SEMANTIC_SEGMENTATION output kept, next not started
        JsonNode run = processing(s).get("run");
        assertThat(run.get("status").asText()).isEqualTo("PARTIAL");
        assertThat(run.get("quality").asText()).isEqualTo("PARTIAL");
        assertThat(run.get("failureStage").asText()).isEqualTo("GEOMETRIC_CLEANUP");
    }

    @Test
    void withoutAReconstructionTheTimeBoxFailsTheRunInsteadOfFakingAResult() throws Exception {
        var s = startRun();
        runThrough("FRAME_QUALITY_FILTER");
        jdbc.sql("UPDATE pipeline_run SET deadline_at = now() - interval '1 second' WHERE id = :r").param("r", s.run()).update();
        pipeline.enforceLeasesAndDeadlines();
        JsonNode run = processing(s).get("run");
        assertThat(run.get("status").asText()).isEqualTo("FAILED");
        assertThat(run.get("quality").isNull()).isTrue();
        assertThat(run.get("failureCode").asText()).isEqualTo("TIME_LIMIT_EXCEEDED");
        assertThat(captureStatus(s)).isEqualTo("PROCESSING");
    }

    @Test
    void aWorkerMayReportAPartialReconstructionWhenItRunsOutOfTime() throws Exception {
        var s = startRun();
        runThrough("POSE_ESTIMATION");
        JsonNode splat = claimExpecting("SPLAT_RECONSTRUCTION");
        Map<String, Object> partial = artifact(splat, "checkpoint.ksplat", "SPLAT_PARTIAL", false, true, "checkpoint-bytes");
        send(splat, report("FAILED", List.of(partial), "TIME_LIMIT_EXCEEDED", "training stopped at the time limit"), svc).andExpect(status().isOk());
        JsonNode run = processing(s).get("run");
        assertThat(run.get("status").asText()).isEqualTo("PARTIAL");
        assertThat(run.get("quality").asText()).isEqualTo("PARTIAL");
        assertThat(run.get("stages").get(5).get("state").asText()).as("the stage itself is recorded as failed").isEqualTo("FAILED");
    }

    @Test
    void aTimeLimitFailureWithoutAPartialArtifactIsJustAFailure() throws Exception {
        var s = startRun();
        runThrough("POSE_ESTIMATION");
        JsonNode splat = claimExpecting("SPLAT_RECONSTRUCTION");
        send(splat, report("FAILED", List.of(), "TIME_LIMIT_EXCEEDED", "stopped"), svc).andExpect(status().isOk());
        assertThat(processing(s).get("run").get("status").asText()).isEqualTo("FAILED");
        // Only reconstruction artifacts may be marked partial.
        var s2 = startRun();
        JsonNode order = claimExpecting("INPUT_VALIDATION");
        Map<String, Object> bad = artifact(order, "x.json", "INPUT_REPORT", false, true, "{}");
        send(order, report("FAILED", List.of(bad), "TIME_LIMIT_EXCEEDED", "x"), svc).andExpect(status().isConflict());
    }

    // ---- lost workers, heartbeats, cancellation ---------------------------------------------

    @Test
    void aWorkerThatStopsHeartbeatingLosesTheJobAndTheStageCanBeRetried() throws Exception {
        var s = startRun();
        JsonNode order = claimExpecting("INPUT_VALIDATION");
        String jobId = order.get("id").asText();

        // Heartbeats keep the lease; a different worker id does not own it.
        post("/api/v1/internal/jobs/" + jobId + "/heartbeat", svc, "{\"workerId\":\"test-worker\"}").andExpect(status().isOk())
            .andExpect(jsonPath("$.keepGoing").value(true));
        post("/api/v1/internal/jobs/" + jobId + "/heartbeat", svc, "{\"workerId\":\"impostor\"}").andExpect(status().isOk())
            .andExpect(jsonPath("$.keepGoing").value(false));

        jdbc.sql("UPDATE processing_job SET lease_expires_at = now() - interval '1 minute' WHERE id = :j").param("j", UUID.fromString(jobId)).update();
        assertThat(pipeline.enforceLeasesAndDeadlines()).isPositive();

        JsonNode p = processing(s);
        assertThat(p.get("run").get("status").asText()).isEqualTo("FAILED");
        assertThat(p.get("run").get("failureCode").asText()).isEqualTo("WORKER_LOST");
        assertThat(p.get("run").get("stages").get(0).get("lastRun").get("command").get("synthetic").asText()).isEqualTo("enforced-by-control-plane");
        // The late worker's report is refused: the stage is no longer its to finish.
        send(order, report("SUCCEEDED", List.of(), null, null), svc).andExpect(status().isConflict());
        post(capUrl(s.c(), s.capture()) + "/processing/retry", s.c().operator(), null).andExpect(status().isAccepted());
        assertThat(claimExpecting("INPUT_VALIDATION").get("attempt").asInt()).isEqualTo(2);
    }

    @Test
    void aJobThatOverrunsTheDeadlinePlusGraceIsForcedToFail() throws Exception {
        var s = startRun();
        claimExpecting("INPUT_VALIDATION");
        jdbc.sql("UPDATE pipeline_run SET deadline_at = now() - interval '10 minutes' WHERE id = :r").param("r", s.run()).update();
        assertThat(pipeline.enforceLeasesAndDeadlines()).isPositive();
        assertThat(processing(s).get("run").get("failureCode").asText()).isEqualTo("TIME_LIMIT_EXCEEDED");
    }

    @Test
    void cancellingStopsTheRunAndTheWorkerIsToldToStop() throws Exception {
        var s = startRun();
        JsonNode order = claimExpecting("INPUT_VALIDATION");
        post(capUrl(s.c(), s.capture()) + "/processing/cancel", s.c().operator(), null).andExpect(status().isOk())
            .andExpect(jsonPath("$.run.status").value("CANCELLED"));
        post("/api/v1/internal/jobs/" + order.get("id").asText() + "/heartbeat", svc, "{\"workerId\":\"test-worker\"}")
            .andExpect(jsonPath("$.keepGoing").value(false));
        send(order, report("SUCCEEDED", List.of(), null, null), svc).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("JOB_NOT_RUNNING"));
        assertThat(captureStatus(s)).isEqualTo("FAILED");
        post(capUrl(s.c(), s.capture()) + "/processing/retry", s.c().operator(), null).andExpect(status().isConflict());
        post(capUrl(s.c(), s.capture()) + "/processing/cancel", s.c().operator(), null).andExpect(status().isConflict());
    }

    // ---- access control ----------------------------------------------------------------------

    @Test
    void onlyWorkersCanUseTheWorkerProtocolAndOnlyTheVenueTeamCanControlARun() throws Exception {
        var s = startRun();
        JsonNode order = claimExpecting("INPUT_VALIDATION");
        String admin = TestJwt.user(s.c().org(), "admin").token();
        post(CLAIM, admin, "{\"stages\":[\"INPUT_VALIDATION\"]}").andExpect(status().isForbidden());
        send(order, report("SUCCEEDED", List.of(), null, null), admin).andExpect(status().isForbidden());
        send(order, report("SUCCEEDED", List.of(), null, null), null).andExpect(status().isUnauthorized());
        post("/api/v1/internal/jobs/" + order.get("id").asText() + "/heartbeat", admin, "{\"workerId\":\"w\"}").andExpect(status().isForbidden());

        String viewer = TestJwt.user(s.c().org(), "viewer").venues(s.c().venue()).token();
        get(capUrl(s.c(), s.capture()) + "/processing", viewer).andExpect(status().isForbidden());
        post(capUrl(s.c(), s.capture()) + "/processing/retry", viewer, null).andExpect(status().isForbidden());
        post(capUrl(s.c(), s.capture()) + "/processing/cancel", viewer, null).andExpect(status().isForbidden());

        String otherOrg = TestJwt.user(fx.organization(), "admin").token();
        get(capUrl(s.c(), s.capture()) + "/processing", otherOrg).andExpect(status().isNotFound());
        post(capUrl(s.c(), s.capture()) + "/processing/retry", otherOrg, null).andExpect(status().isNotFound());
        post(capUrl(s.c(), s.capture()) + "/processing/cancel", otherOrg, null).andExpect(status().isNotFound());
        String otherVenueOp = TestJwt.user(s.c().org(), "operator").venues(fx.venue(s.c().org())).token();
        get(capUrl(s.c(), s.capture()) + "/processing", otherVenueOp).andExpect(status().isNotFound());
    }

    // ---- SEMANTIC_INDEXING ingestion (DETECTED_OBJECTS -> poi/poi_version rows) -------------------

    private static String detectedObjectsJson(double x, double y, double z, String label, double confidence) {
        StringBuilder embedding = new StringBuilder();
        for (int i = 0; i < 512; i++) {
            if (i > 0) embedding.append(',');
            embedding.append(String.format(java.util.Locale.ROOT, "%.4f", Math.sin(i * 0.017)));
        }
        return "{\"detector_model\":\"IDEA-Research/grounding-dino-tiny\",\"detector_fine_tuned\":false,"
            + "\"embedding_model\":\"open_clip:ViT-B-32:openai\",\"frames_processed\":4,\"raw_detection_count\":3,"
            + "\"objects\":[{\"label\":\"" + label + "\",\"confidence\":" + confidence + ",\"position\":[" + x + "," + y + "," + z + "],"
            + "\"embedding\":[" + embedding + "],"
            + "\"bbox_px\":{\"x\":10,\"y\":20,\"width\":50,\"height\":80,\"frameWidth\":640,\"frameHeight\":480},"
            + "\"source_frame\":\"000012.jpg\",\"detections_merged\":3,\"support_points\":9}]}";
    }

    @Test
    void semanticIndexingDetectionsAreIngestedAsAutoDetectedPoisWithProvenance() throws Exception {
        var s = startRun();
        for (var stage : PipelineDefinition.STAGES) {
            JsonNode order = claimExpecting(stage.name());
            if (stage.name().equals("SEMANTIC_INDEXING")) {
                Map<String, Object> detected = artifact(order, "detected-objects.json", "DETECTED_OBJECTS", false, false,
                    detectedObjectsJson(1.5, 2.5, 0.75, "reception chair", 0.87));
                send(order, report("SUCCEEDED", List.of(detected), null, null), svc).andExpect(status().isOk());
                break;
            }
            succeed(order);
        }

        List<Map<String, Object>> rows = jdbc.sql(
                "SELECT p.floor_id, v.label, v.x, v.y, v.z, v.source, v.detection_confidence, v.embedding_model, "
                    + "v.bounding_box, v.pipeline_run_id FROM poi p JOIN poi_version v ON v.poi_id = p.id "
                    + "WHERE p.venue_id = :v AND v.source = 'AUTO_DETECTED'")
            .param("v", s.c().venue())
            .query((rs, i) -> Map.<String, Object>of(
                "floorId", rs.getObject("floor_id", UUID.class), "label", rs.getString("label"), "x", rs.getDouble("x"),
                "y", rs.getDouble("y"), "z", rs.getDouble("z"), "source", rs.getString("source"),
                "confidence", rs.getDouble("detection_confidence"), "model", rs.getString("embedding_model"),
                "boundingBox", rs.getString("bounding_box"), "runId", rs.getObject("pipeline_run_id", UUID.class)))
            .list();

        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("floorId")).isEqualTo(s.c().floor());
        assertThat(row.get("label")).isEqualTo("reception chair");
        assertThat((Double) row.get("x")).isCloseTo(1.5, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(row.get("source")).isEqualTo("AUTO_DETECTED");
        assertThat((Double) row.get("confidence")).isCloseTo(0.87, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(row.get("model")).isEqualTo("open_clip:ViT-B-32:openai");
        assertThat((String) row.get("boundingBox")).contains("\"frameWidth\"");
        assertThat(row.get("runId")).isEqualTo(s.run());
    }
}
