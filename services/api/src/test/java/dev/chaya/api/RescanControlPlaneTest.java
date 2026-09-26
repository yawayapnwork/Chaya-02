package dev.chaya.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import dev.chaya.api.processing.JobStage;
import dev.chaya.api.reconstruction.ReconstructionService;
import dev.chaya.api.security.Actor;
import dev.chaya.api.security.Role;
import dev.chaya.api.storage.ObjectStore;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The incremental re-scan flow end to end through the real HTTP worker protocol (docs/rescan.md): select a version and
 * region, capture, and drive the incremental plan's stages exactly as a worker would. It proves what no unit test can:
 * <ul>
 *   <li>a rejected alignment (by the worker's gates, or by the control plane's own re-check) ends the run with
 *       ALIGNMENT_REJECTED, makes the version ALIGNMENT_REJECTED (terminal, immutable, not retryable), and leaves the
 *       parent version untouched;</li>
 *   <li>an accepted alignment finalizes a new immutable version carrying its full lineage: alignment report, splice
 *       record, operator, timestamps;</li>
 *   <li>POIs change only when the version finalizes, never for a re-scan that fails, and MANUAL POIs never;</li>
 *   <li>viewers are never served an unfinalized re-scan's model.</li>
 * </ul>
 * The worker's reports here are fixtures in the worker's real report shape; the algorithms are tested in the worker's own
 * suite. Skipped, not failed, without Docker (AbstractIntegrationTest, via PipelineTestSupport).
 */
class RescanControlPlaneTest extends PipelineTestSupport {

    @Autowired ReconstructionService reconstructions;

    private static final String REGION = "[[0,0],[0,3],[3,3],[3,0]]";

    /** The parent reconstruction's canonical frame (an identity fixture frame), set by finalizedParentWithGlobalCloud. */
    private UUID parentFrame;
    private UUID parentRun;

    /** A FINALIZED parent ScanVersion whose provenance names its run, and that run's real GEOMETRIC_CLEANUP output (kind
     * SPLAT_CLEAN) in the derived bucket: exactly what PipelineService#globalCloudInput resolves. */
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
        parentRun = run;
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
                VALUES (:o, :v, :s, :f, 1, 'FINALIZED', CAST(:p AS jsonb), now()) RETURNING id
                """)
            .param("o", c.org()).param("v", c.venue()).param("s", scan).param("f", c.floor())
            .param("p", "{\"bootstrap\":true,\"runId\":\"" + run + "\"}").query(UUID.class).single();
    }

    private Started startRescanRun(Ctx c, UUID parentVersionId) throws Exception {
        String body = "{\"parentVersionId\":\"" + parentVersionId + "\",\"region\":{\"points\":" + REGION + "}}";
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

    /** Drives claim/succeed through every stage up to (not including) REGION_ALIGNMENT. */
    private void runThroughGeometricCleanup() throws Exception {
        for (JobStage stage : List.of(JobStage.INPUT_VALIDATION, JobStage.FFMPEG_PREPROCESS, JobStage.FRAME_QUALITY_FILTER,
                JobStage.PRIVACY_PREPROCESS, JobStage.POSE_ESTIMATION, JobStage.SPLAT_RECONSTRUCTION, JobStage.GEOMETRIC_CLEANUP)) {
            succeed(claimExpecting(stage.name()));
        }
    }

    /** chaya_worker.stages.region_alignment's report, in its real shape. */
    private static Map<String, Object> alignment(double confidence, boolean gatesPassed, double scale) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("status", gatesPassed ? "ACCEPTED" : "REJECTED");
        a.put("mode", "DIRECT_CANONICAL");
        a.put("method", "DIRECT_CANONICAL_ICP");
        a.put("confidence", confidence);
        a.put("inlier_rmse_m", 0.012);
        a.put("scale_correction", scale);
        a.put("gates_passed", gatesPassed);
        a.put("failed_gates", gatesPassed ? List.of() : List.of("direct_translation_correction_m"));
        a.put("gates", List.of(Map.of("name", "direct_translation_correction_m", "value", gatesPassed ? 0.1 : 1.2, "threshold", 0.5,
            "comparison", "<=", "passed", gatesPassed, "unit", "m")));
        a.put("metrics", Map.of("correspondence_count", 5000, "inlier_ratio", 0.97, "scale", scale, "rotation_residual_deg", 0.05,
            "translation_residual_m", 0.002, "icp_residual_m", 0.012, "confidence", confidence));
        return a;
    }

    private Map<String, Object> acceptedAlignmentReport(JsonNode order, Map<String, Object> alignment) {
        Map<String, Object> a = artifact(order, "alignment-report.json", "ALIGNMENT_REPORT", false, false, "{}");
        Map<String, Object> r = report("SUCCEEDED", List.of(a), null, null);
        r.put("command", Map.of("argv", List.of("region-alignment"), "config", Map.of("alignment", alignment)));
        return r;
    }

    private UUID versionOf(Started s) {
        return jdbc.sql("SELECT scan_version_id FROM pipeline_run WHERE id = :r").param("r", s.run()).query(UUID.class).single();
    }

    private Map<String, Object> versionRow(UUID id) {
        return jdbc.sql("SELECT status, provenance::text AS provenance, updated_at, finalized_at, rejected_at, alignment_method, "
                + "alignment_confidence, alignment_report::text AS alignment_report, splice_report::text AS splice_report, created_by, "
                + "parent_version_id, changed_artifact_kinds::text AS changed_artifact_kinds FROM scan_version WHERE id = :v").param("v", id).query().listOfRows().get(0);
    }

    private void assertRejectedAndParentUntouched(Started s, UUID parent, Map<String, Object> parentBefore) throws Exception {
        JsonNode p = processing(s);
        assertThat(p.get("run").get("status").asText()).isEqualTo("FAILED");
        assertThat(p.get("run").get("failureCode").asText()).isEqualTo("ALIGNMENT_REJECTED");
        assertThat(claim()).as("REGION_SPLICE is never queued after a rejected alignment").isNull();

        UUID versionId = versionOf(s);
        Map<String, Object> version = versionRow(versionId);
        assertThat(version.get("status")).isEqualTo("ALIGNMENT_REJECTED");
        assertThat(version.get("rejected_at")).isNotNull();
        assertThat(version.get("finalized_at")).isNull();
        assertThat((String) version.get("alignment_report")).contains("\"gates\"");
        assertThat((String) version.get("provenance")).contains(s.run().toString()).contains("\"rejected\": true");
        // immutable, and not retryable
        assertThatThrownBy(() -> jdbc.sql("UPDATE scan_version SET status = 'DRAFT', rejected_at = NULL WHERE id = :v")
            .param("v", versionId).update()).hasMessageContaining("immutable");
        post(capUrl(s.c(), s.capture()) + "/processing/retry", s.c().operator(), null).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("RUN_NOT_RETRYABLE"));
        // the parent version is exactly as it was
        assertThat(versionRow(parent)).isEqualTo(parentBefore);
        assertThat(jdbc.sql("SELECT count(*) FROM processing_artifact a JOIN pipeline_stage_run sr ON sr.id = a.stage_run_id "
            + "WHERE sr.run_id = :r").param("r", parentRun).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void aWorkerRejectedAlignmentRejectsTheVersionAndLeavesTheParentUntouched() throws Exception {
        Ctx c = ctx();
        UUID parent = finalizedParentWithGlobalCloud(c);
        Map<String, Object> parentBefore = versionRow(parent);
        Started s = startRescanRun(c, parent);
        runThroughGeometricCleanup();

        JsonNode order = claimExpecting("REGION_ALIGNMENT");
        Map<String, Object> failed = report("FAILED", List.of(), "ALIGNMENT_REJECTED",
            "alignment rejected (DIRECT_CANONICAL): direct_translation_correction_m=1.2 (needs <= 0.5)");
        failed.put("errorDetails", alignment(0.95, false, 1.0));
        send(order, failed, svc).andExpect(status().isOk());

        assertRejectedAndParentUntouched(s, parent, parentBefore);
        Map<String, Object> version = versionRow(versionOf(s));
        assertThat(version.get("alignment_method")).isEqualTo("DIRECT_CANONICAL_ICP");
        assertThat((String) version.get("alignment_report")).contains("direct_translation_correction_m");
    }

    @Test
    void theControlPlaneRejectsAReportedSuccessThatFailsItsOwnChecks() throws Exception {
        record Case(String name, Map<String, Object> alignment) {}
        for (Case k : List.of(new Case("confidence below chaya.rescan.min-alignment-confidence", alignment(0.2, true, 1.0)),
                new Case("the worker's own gates did not pass", alignment(0.9, false, 1.0)),
                new Case("scale outside chaya.rescan.max-scale-correction", alignment(0.9, true, 1.2)))) {
            Ctx c = ctx();
            UUID parent = finalizedParentWithGlobalCloud(c);
            Map<String, Object> parentBefore = versionRow(parent);
            Started s = startRescanRun(c, parent);
            runThroughGeometricCleanup();
            JsonNode order = claimExpecting("REGION_ALIGNMENT");
            send(order, acceptedAlignmentReport(order, k.alignment()), svc).andExpect(status().isOk());
            assertRejectedAndParentUntouched(s, parent, parentBefore);
        }
    }

    @Test
    void anAcceptedAlignmentSplicesAndFinalizesAVersionWithItsFullLineage() throws Exception {
        Ctx c = ctx();
        UUID parent = finalizedParentWithGlobalCloud(c);
        Map<String, Object> parentBefore = versionRow(parent);
        Started s = startRescanRun(c, parent);
        runThroughGeometricCleanup();

        JsonNode alignOrder = claimExpecting("REGION_ALIGNMENT");
        // The GLOBAL_CLOUD is the parent version's own run's cloud (resolved through its provenance), never this run's.
        String globalKey = null;
        for (JsonNode in : alignOrder.get("inputs")) {
            if (in.get("kind").asText().equals("GLOBAL_CLOUD")) {
                globalKey = in.get("key").asText();
            }
        }
        assertThat(globalKey).contains("/run/" + parentRun + "/");
        assertThat(alignOrder.get("parentCoordinateFrame").get("id").asText()).isEqualTo(parentFrame.toString());
        send(alignOrder, acceptedAlignmentReport(alignOrder, alignment(0.9, true, 1.01)), svc).andExpect(status().isOk());

        JsonNode spliceOrder = claimExpecting("REGION_SPLICE");
        assertThat(spliceOrder.get("coordinateFrame").get("id").asText())
            .as("from the splice on, the run's geometry is in the parent reconstruction's frame").isEqualTo(parentFrame.toString());
        Map<String, Object> splice = new LinkedHashMap<>();
        splice.put("volume", Map.of("polygon_xy", List.of(List.of(0, 0), List.of(0, 3), List.of(3, 3), List.of(3, 0)), "z_min_m", -0.2, "z_max_m", 2.1));
        splice.put("removed_from_global", 120);
        splice.put("added_from_region", 140);
        splice.put("index", Map.of("artifact", "splice-index.npz", "sha256", "a".repeat(64)));
        Map<String, Object> spliceReport = report("SUCCEEDED", outputsFor(spliceOrder), null, null);
        spliceReport.put("command", Map.of("argv", List.of("region-splice"), "config", Map.of("splice", splice)));
        send(spliceOrder, spliceReport, svc).andExpect(status().isOk());

        JsonNode order;
        while ((order = claim()) != null) {
            succeed(order);
        }
        JsonNode p = processing(s);
        assertThat(p.get("run").get("status").asText()).isEqualTo("SUCCEEDED");
        List<String> stages = new ArrayList<>();
        p.get("run").get("stages").forEach(st -> stages.add(st.get("stage").asText()));
        assertThat(stages).as("no navigation graph existed for this floor, so nothing needed rebaking").doesNotContain("NAVIGATION_BAKING");

        Map<String, Object> version = versionRow(versionOf(s));
        assertThat(version.get("status")).isEqualTo("FINALIZED");
        assertThat(version.get("parent_version_id")).isEqualTo(parent);
        assertThat(((Number) version.get("alignment_confidence")).doubleValue()).isEqualTo(0.9);
        assertThat(version.get("alignment_method")).isEqualTo("DIRECT_CANONICAL_ICP");
        assertThat((String) version.get("alignment_report")).contains("\"gates_passed\": true");
        assertThat((String) version.get("splice_report")).contains("\"removed_from_global\": 120").contains("splice-index.npz");
        String operator = jdbc.sql("SELECT operator_id FROM capture_session WHERE id = :c").param("c", s.capture()).query(String.class).single();
        assertThat((String) version.get("created_by")).as("the operator who started the re-scan").isEqualTo(operator).startsWith("user-");
        assertThat(version.get("finalized_at")).isNotNull();
        assertThat((String) version.get("changed_artifact_kinds")).contains("SPLAT_MERGED");
        assertThat(versionRow(parent)).as("the parent is only ever read").isEqualTo(parentBefore);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE action = 'rescan.outcome' AND resource_id = :v")
            .param("v", versionOf(s)).query(Integer.class).single()).isEqualTo(1);
    }

    // ---- POIs, navigation and viewer assets: only when the version finalizes ----------------------------------------

    private UUID poi(Ctx c, String source, double x, double y) {
        UUID id = jdbc.sql("INSERT INTO poi (organization_id, venue_id, floor_id) VALUES (:o, :v, :f) RETURNING id")
            .param("o", c.org()).param("v", c.venue()).param("f", c.floor()).query(UUID.class).single();
        jdbc.sql("""
                INSERT INTO poi_version (organization_id, venue_id, poi_id, version_number, label, tags, x, y, z, source,
                    coordinate_frame_id, pipeline_run_id, created_by)
                VALUES (:o, :v, :p, 1, :label, '{}', :x, :y, 0, :src, :frame, :run, 'test')
                """)
            .param("o", c.org()).param("v", c.venue()).param("p", id).param("label", source.toLowerCase() + " poi")
            .param("x", x).param("y", y).param("src", source).param("frame", parentFrame)
            .param("run", source.equals("MANUAL") ? null : parentRun).update();
        return id;
    }

    /** An ACTIVE STANDARD graph with a node inside the region: the re-scan plan then includes NAVIGATION_BAKING. */
    private void activeGraphInRegion(Ctx c) {
        UUID graph = jdbc.sql("INSERT INTO navigation_graph (organization_id, venue_id, floor_id, profile, status, coordinate_frame_id) "
                + "VALUES (:o, :v, :f, 'STANDARD', 'DRAFT', :frame) RETURNING id")
            .param("o", c.org()).param("v", c.venue()).param("f", c.floor()).param("frame", parentFrame).query(UUID.class).single();
        jdbc.sql("INSERT INTO navigation_node (organization_id, venue_id, graph_id, floor_id, x, y, z) VALUES (:o, :v, :g, :f, 1, 1, 0)")
            .param("o", c.org()).param("v", c.venue()).param("g", graph).param("f", c.floor()).update();
        jdbc.sql("UPDATE navigation_graph SET status = 'ACTIVE' WHERE id = :g").param("g", graph).update();
    }

    private String detectedObjects(double x, double y) {
        StringBuilder embedding = new StringBuilder();
        for (int i = 0; i < 512; i++) {
            embedding.append(i > 0 ? "," : "").append(String.format(java.util.Locale.ROOT, "%.4f", Math.cos(i * 0.013)));
        }
        return "{\"coordinate_frame\":{\"id\":\"" + parentFrame + "\",\"units\":\"m\",\"up_axis\":\"+Z\"},"
            + "\"embedding_model\":\"open_clip:ViT-B-32:openai\",\"objects\":[{\"label\":\"new bench\",\"confidence\":0.8,"
            + "\"position\":[" + x + "," + y + ",0.4],\"embedding\":[" + embedding + "]}]}";
    }

    /** Runs a re-scan to NAVIGATION_BAKING: accepted alignment, a DETECTED_OBJECTS report from SEMANTIC_INDEXING. */
    private JsonNode rescanToNavigationBaking(Ctx c, UUID parent) throws Exception {
        startRescanRun(c, parent);
        runThroughGeometricCleanup();
        JsonNode align = claimExpecting("REGION_ALIGNMENT");
        send(align, acceptedAlignmentReport(align, alignment(0.9, true, 1.0)), svc).andExpect(status().isOk());
        for (String stage : List.of("REGION_SPLICE", "PLANE_FITTING", "ARTIFACT_GENERATION")) {
            JsonNode o = claimExpecting(stage);
            List<Map<String, Object>> outputs = new ArrayList<>(outputsFor(o));
            if (stage.equals("ARTIFACT_GENERATION")) {
                outputs.add(artifact(o, "scene.ksplat", "KSPLAT", false, false, "merged-model-bytes"));
            }
            send(o, report("SUCCEEDED", outputs, null, null), svc).andExpect(status().isOk());
        }
        JsonNode semantic = claimExpecting("SEMANTIC_INDEXING");
        send(semantic, report("SUCCEEDED", List.of(artifact(semantic, "detected-objects.json", "DETECTED_OBJECTS", false, false,
            detectedObjects(2.0, 2.0))), null, null), svc).andExpect(status().isOk());
        return claimExpecting("NAVIGATION_BAKING");
    }

    private List<String> livePois(Ctx c) {
        return jdbc.sql("SELECT v.source || ':' || v.label FROM poi p JOIN poi_version v ON v.poi_id = p.id "
                + "WHERE p.venue_id = :v AND p.deleted_at IS NULL ORDER BY 1").param("v", c.venue()).query(String.class).list();
    }

    @Test
    void poisChangeOnlyWhenTheRescanFinalizesAndManualPoisAlwaysSurvive() throws Exception {
        Ctx c = ctx();
        UUID parent = finalizedParentWithGlobalCloud(c);
        activeGraphInRegion(c);
        poi(c, "MANUAL", 1.5, 1.5);  // inside the region: staff-placed, must survive every re-scan
        poi(c, "AUTO_DETECTED", 1.0, 2.0);  // inside: replaced, but only by a re-scan that finalizes
        poi(c, "AUTO_DETECTED", 8.0, 8.0);  // outside: never touched
        List<String> before = livePois(c);
        Actor viewer = new Actor(Actor.Kind.USER, "viewer", c.org(), Set.of(c.venue()), Set.of(Role.VIEWER));

        // A re-scan that gets through SEMANTIC_INDEXING and then fails: nothing changes, and its model is never served.
        JsonNode nav = rescanToNavigationBaking(c, parent);
        send(nav, report("FAILED", List.of(), "NAVMESH_BUILD_FAILED", "Recast failed"), svc).andExpect(status().isOk());
        assertThat(livePois(c)).as("a failed re-scan never touches a POI").isEqualTo(before);
        assertThat(reconstructions.listForFloor(viewer, c.venue(), c.floor()))
            .as("the failed re-scan's merged model is not listed for viewers").isEmpty();

        // A re-scan that finalizes: the region's AUTO_DETECTED POI is replaced; the MANUAL one and the outside one survive.
        JsonNode nav2 = rescanToNavigationBaking(c, parent);
        succeed(nav2);
        assertThat(livePois(c)).containsExactly("AUTO_DETECTED:auto_detected poi", "AUTO_DETECTED:new bench", "MANUAL:manual poi");
        assertThat(reconstructions.listForFloor(viewer, c.venue(), c.floor())).hasSize(1);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE action = 'rescan.downstream_applied'").query(Integer.class).single())
            .isGreaterThanOrEqualTo(1);
    }
}
