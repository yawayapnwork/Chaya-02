package dev.chaya.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import dev.chaya.api.processing.JobStage;
import dev.chaya.api.storage.ObjectStore;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The incremental re-scan flow end to end through the real HTTP worker protocol (docs/rescan.md): select
 * a version and region, capture, and drive the incremental plan's stages exactly as a worker would. The
 * two things this proves that no unit test can: (1) a low alignment confidence really fails the run and
 * leaves the ScanVersion DRAFT forever -- "never silently merge a badly aligned reconstruction" -- and
 * (2) a passing alignment really does finalize a new immutable ScanVersion, and only after REGION_SPLICE
 * has actually run. Skipped, not failed, without Docker (AbstractIntegrationTest, via PipelineTestSupport).
 */
class RescanControlPlaneTest extends PipelineTestSupport {

    /** Fabricates a FINALIZED parent ScanVersion with a real GEOMETRIC_CLEANUP output (kind SPLAT_CLEAN)
     * in the derived bucket, exactly the shape PipelineService#globalCloudInput looks for -- so the
     * REGION_ALIGNMENT/REGION_SPLICE work orders in this test get a real GLOBAL_CLOUD input, the same way
     * a version produced by an earlier real run would. */
    /** The parent reconstruction's canonical frame (an identity fixture frame), set by finalizedParentWithGlobalCloud. */
    private UUID parentFrame;

    private UUID finalizedParentWithGlobalCloud(Ctx c) {
        UUID session = jdbc.sql("INSERT INTO capture_session (organization_id, venue_id, floor_id, operator_id) "
                + "VALUES (:o, :v, :f, 'operator-sub') RETURNING id")
            .param("o", c.org()).param("v", c.venue()).param("f", c.floor()).query(UUID.class).single();
        UUID scan = fx.scan(c.org(), c.venue(), session);
        UUID run = jdbc.sql("""
                INSERT INTO pipeline_run (organization_id, venue_id, scan_id, capture_session_id, status, quality, stages,
                    time_budget_seconds, deadline_at, finished_at, requested_by)
                VALUES (:o, :v, :s, :cs, 'SUCCEEDED', 'FINAL', '{}', 3600, now(), now(), 'tester') RETURNING id
                """)
            .param("o", c.org()).param("v", c.venue()).param("s", scan).param("cs", session).query(UUID.class).single();
        jdbc.sql("UPDATE pipeline_run SET reconstruction_frame_run_id = id WHERE id = :r").param("r", run).update();
        parentFrame = fx.identityFrame(c.org(), c.venue(), c.floor(), run, "FLOOR_LOCAL");
        UUID job = jdbc.sql("""
                INSERT INTO processing_job (organization_id, venue_id, scan_id, run_id, stage, status, started_at, finished_at)
                VALUES (:o, :v, :s, :r, 'GEOMETRIC_CLEANUP', 'SUCCEEDED', now() - interval '1 minute', now()) RETURNING id
                """)
            .param("o", c.org()).param("v", c.venue()).param("s", scan).param("r", run).query(UUID.class).single();
        UUID stageRun = jdbc.sql("""
                INSERT INTO pipeline_stage_run (id, organization_id, venue_id, run_id, job_id, stage, attempt, status,
                    command, started_at, finished_at)
                VALUES (gen_random_uuid(), :o, :v, :r, :j, 'GEOMETRIC_CLEANUP', 1, 'SUCCEEDED', '{}'::jsonb,
                    now() - interval '1 minute', now()) RETURNING id
                """)
            .param("o", c.org()).param("v", c.venue()).param("r", run).param("j", job).query(UUID.class).single();

        String key = "org/%s/venue/%s/scan/%s/run/%s/GEOMETRIC_CLEANUP/attempt-1/splat-clean.ply".formatted(c.org(), c.venue(), scan, run);
        byte[] data = "fake-global-cloud-bytes".getBytes(StandardCharsets.UTF_8);
        String uploadId = derived.beginMultipart(key, "application/octet-stream");
        String etag = derived.uploadPart(key, uploadId, 1, data);
        derived.completeMultipart(key, uploadId, List.of(new ObjectStore.PartEtag(1, etag)));
        jdbc.sql("""
                INSERT INTO processing_artifact (id, organization_id, venue_id, scan_id, job_id, stage, bucket, object_key,
                    checksum_sha256, content_type, size_bytes, kind, stage_run_id, contains_pii, partial)
                VALUES (gen_random_uuid(), :o, :v, :s, :j, 'GEOMETRIC_CLEANUP', 'chaya-derived-test', :key, :sha,
                    'application/octet-stream', :sz, 'SPLAT_CLEAN', :sr, false, false)
                """)
            .param("o", c.org()).param("v", c.venue()).param("s", scan).param("j", job).param("key", key)
            .param("sha", sha256(data)).param("sz", data.length).param("sr", stageRun).update();

        return jdbc.sql("""
                INSERT INTO scan_version (organization_id, venue_id, scan_id, floor_id, version_number, status, provenance, finalized_at)
                VALUES (:o, :v, :s, :f, 1, 'FINALIZED', '{"bootstrap":true}'::jsonb, now()) RETURNING id
                """)
            .param("o", c.org()).param("v", c.venue()).param("s", scan).param("f", c.floor()).query(UUID.class).single();
    }

    private Started startRescanRun(Ctx c, UUID parentVersionId) throws Exception {
        String body = "{\"parentVersionId\":\"" + parentVersionId + "\",\"region\":{\"points\":[[0,0],[0,3],[3,3],[3,0]]}}";
        String initJson = post("/api/v1/venues/" + c.venue() + "/floors/" + c.floor() + "/rescan", c.operator(), body)
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID captureId = UUID.fromString(field(initJson, "captureId"));

        var u = upload(c, captureId, "VIDEO", "walk.mp4", "video/mp4", mp4(4096));
        awaitSettled(c, captureId, u.mediaId());
        post(capUrl(c, captureId) + "/complete-upload", c.operator(), null).andExpect(status().isOk());
        String json = post(capUrl(c, captureId) + "/processing", c.operator(), null).andExpect(status().isAccepted())
            .andReturn().getResponse().getContentAsString();
        JsonNode n = mapper.readTree(json);
        return new Started(c, captureId, UUID.fromString(n.get("scanId").asText()), UUID.fromString(n.get("run").get("id").asText()));
    }

    /** Drives claim/succeed through every stage up to (not including) REGION_ALIGNMENT, using the same
     * realistic per-stage outputs PipelineTestSupport already knows how to fabricate. */
    private void runThroughGeometricCleanup() throws Exception {
        for (JobStage stage : List.of(JobStage.INPUT_VALIDATION, JobStage.FFMPEG_PREPROCESS, JobStage.FRAME_QUALITY_FILTER,
                JobStage.PRIVACY_PREPROCESS, JobStage.POSE_ESTIMATION, JobStage.SPLAT_RECONSTRUCTION, JobStage.GEOMETRIC_CLEANUP)) {
            succeed(claimExpecting(stage.name()));
        }
    }

    private Map<String, Object> alignmentReport(JsonNode order, double confidence) {
        Map<String, Object> a = artifact(order, "alignment-report.json", "ALIGNMENT_REPORT", false, false,
            "{\"confidence\":" + confidence + "}");
        Map<String, Object> r = report("SUCCEEDED", List.of(a), null, null);
        Map<String, Object> alignment = new LinkedHashMap<>();
        alignment.put("method", "FEATURE_RANSAC_ICP");
        alignment.put("confidence", confidence);
        alignment.put("fitness", confidence);
        alignment.put("inlier_rmse_m", 0.02);
        alignment.put("translation_m", 0.1);
        alignment.put("rotation_deg", 2.0);
        r.put("command", Map.of("argv", List.of("region-alignment"), "config", Map.of("alignment", alignment)));
        return r;
    }

    @Test
    void lowAlignmentConfidenceFailsTheRunAndNeverFinalizesTheVersion() throws Exception {
        Ctx c = ctx();
        UUID parent = finalizedParentWithGlobalCloud(c);
        Started s = startRescanRun(c, parent);
        runThroughGeometricCleanup();

        JsonNode order = claimExpecting("REGION_ALIGNMENT");
        send(order, alignmentReport(order, 0.2), svc).andExpect(status().isOk());

        JsonNode p = processing(s);
        assertThat(p.get("run").get("status").asText()).isEqualTo("FAILED");
        assertThat(p.get("run").get("failureCode").asText()).isEqualTo("ALIGNMENT_CONFIDENCE_BELOW_THRESHOLD");
        assertThat(claim()).as("REGION_SPLICE must never be queued after a rejected alignment").isNull();

        UUID scanVersionId = jdbc.sql("SELECT scan_version_id FROM pipeline_run WHERE id = :r").param("r", s.run())
            .query(UUID.class).single();
        var version = jdbc.sql("SELECT status, alignment_confidence, alignment_method FROM scan_version WHERE id = :v")
            .param("v", scanVersionId).query().listOfRows().get(0);
        assertThat(version.get("status")).as("a rejected alignment leaves the version DRAFT forever, never promoted").isEqualTo("DRAFT");
        assertThat(((Number) version.get("alignment_confidence")).doubleValue()).isEqualTo(0.2);
        assertThat(version.get("alignment_method")).isEqualTo("FEATURE_RANSAC_ICP");
    }

    @Test
    void highAlignmentConfidenceProceedsToSpliceAndFinalizesANewVersion() throws Exception {
        Ctx c = ctx();
        UUID parent = finalizedParentWithGlobalCloud(c);
        Started s = startRescanRun(c, parent);
        runThroughGeometricCleanup();

        JsonNode alignOrder = claimExpecting("REGION_ALIGNMENT");
        // The GLOBAL_CLOUD input came from the parent version's own real GEOMETRIC_CLEANUP artifact, not
        // from this run -- proving the alignment stage actually had something real to align against.
        assertThat(alignOrder.get("inputs").findValuesAsText("kind")).contains("GLOBAL_CLOUD");
        assertThat(alignOrder.get("regionGeometry").get("points").size()).isEqualTo(4);
        // The region's own frame is uncalibrated here (a real worker would stop with NOT_CALIBRATED); the parent's frame,
        // in which the region polygon and the venue cloud are interpreted, is on the order.
        assertThat(alignOrder.get("parentCoordinateFrame").get("id").asText()).isEqualTo(parentFrame.toString());
        assertThat(alignOrder.get("coordinateFrame").isNull()).isTrue();
        send(alignOrder, alignmentReport(alignOrder, 0.9), svc).andExpect(status().isOk());

        JsonNode spliceOrder = claimExpecting("REGION_SPLICE");
        assertThat(spliceOrder.get("inputs").findValuesAsText("kind")).contains("GLOBAL_CLOUD");
        assertThat(spliceOrder.get("coordinateFrame").get("id").asText())
            .as("from the splice on, the run's geometry is in the parent reconstruction's frame").isEqualTo(parentFrame.toString());
        succeed(spliceOrder);

        JsonNode order;
        while ((order = claim()) != null) {
            succeed(order);
        }

        JsonNode p = processing(s);
        assertThat(p.get("run").get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(p.get("run").get("quality").asText()).isEqualTo("FINAL");
        List<String> stages = new java.util.ArrayList<>();
        p.get("run").get("stages").forEach(st -> stages.add(st.get("stage").asText()));
        assertThat(stages).as("no navigation graph existed yet for this floor, so nothing needed rebaking")
            .doesNotContain("NAVIGATION_BAKING");

        UUID scanVersionId = jdbc.sql("SELECT scan_version_id FROM pipeline_run WHERE id = :r").param("r", s.run())
            .query(UUID.class).single();
        var version = jdbc.sql("SELECT status, alignment_confidence, changed_artifact_kinds, parent_version_id "
                + "FROM scan_version WHERE id = :v").param("v", scanVersionId).query().listOfRows().get(0);
        assertThat(version.get("status")).isEqualTo("FINALIZED");
        assertThat(((Number) version.get("alignment_confidence")).doubleValue()).isEqualTo(0.9);
        assertThat(version.get("parent_version_id")).isEqualTo(parent);
        assertThat((String[]) ((java.sql.Array) version.get("changed_artifact_kinds")).getArray()).contains("SPLAT_MERGED");

        assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE action = 'rescan.outcome' AND resource_id = :v")
            .param("v", scanVersionId).query(Integer.class).single()).isEqualTo(1);
    }
}
