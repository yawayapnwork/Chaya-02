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
import java.nio.file.Files;
import java.nio.file.Path;
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
    private UUID parentLabels;

    /** A FINALIZED parent ScanVersion of a full run: the run's real GEOMETRIC_CLEANUP output (kind SPLAT_CLEAN) in the
     * derived bucket and a KSPLAT row, both pinned by the version, and the run's canonical frame recorded on it --
     * exactly what PipelineService#globalCloudInput resolves (the parent's pinned cloud). */
    private UUID finalizedParentWithGlobalCloud(Ctx c) {
        return finalizedParentWithGlobalCloud(c, false);
    }

    /** As above; `withNavmesh` also pins a NAVMESH, NAVMESH_MANIFEST and NAVIGATION_GRAPH of the parent's run, so a re-scan
     * of it must re-bake navigation (RescanService#parentHasNavigation). */
    private UUID finalizedParentWithGlobalCloud(Ctx c, boolean withNavmesh) {
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
        UUID cloud = jdbc.sql("""
                INSERT INTO processing_artifact (id, organization_id, venue_id, scan_id, job_id, stage, bucket, object_key,
                    checksum_sha256, content_type, size_bytes, kind, stage_run_id, contains_pii, partial)
                VALUES (gen_random_uuid(), :o, :v, :s, :j, 'GEOMETRIC_CLEANUP', 'chaya-derived-test', :key, :sha,
                    'application/octet-stream', :sz, 'SPLAT_CLEAN', :sr, false, false) RETURNING id
                """)
            .param("o", c.org()).param("v", c.venue()).param("s", scan).param("j", job).param("key", key)
            .param("sha", sha256(data)).param("sz", data.length).param("sr", stageRun).query(UUID.class).single();
        // GEOMETRIC_CLEANUP publishes the cleaned labels with the cloud, in the same stage run (chaya_worker.stages.geometric_cleanup)
        String labelsKey = key.replace("splat-clean.ply", "semantic-labels-clean.json");
        byte[] labels = "{\"labels\":[\"floor\"]}".getBytes(StandardCharsets.UTF_8);
        String labelsUpload = derived.beginMultipart(labelsKey, "application/json");
        String labelsEtag = derived.uploadPart(labelsKey, labelsUpload, 1, labels);
        derived.completeMultipart(labelsKey, labelsUpload, List.of(new ObjectStore.PartEtag(1, labelsEtag)));
        parentLabels = jdbc.sql("""
                INSERT INTO processing_artifact (id, organization_id, venue_id, scan_id, job_id, stage, bucket, object_key,
                    checksum_sha256, content_type, size_bytes, kind, stage_run_id, contains_pii, partial)
                VALUES (gen_random_uuid(), :o, :v, :s, :j, 'GEOMETRIC_CLEANUP', 'chaya-derived-test', :key, :sha,
                    'application/json', :sz, 'SEMANTIC_LABELS_CLEAN', :sr, false, false) RETURNING id
                """)
            .param("o", c.org()).param("v", c.venue()).param("s", scan).param("j", job).param("key", labelsKey)
            .param("sha", sha256(labels)).param("sz", labels.length).param("sr", stageRun).query(UUID.class).single();
        UUID ksplat = fx.publishedArtifact(c.org(), c.venue(), scan, run, "ARTIFACT_GENERATION", "KSPLAT");

        UUID version = jdbc.sql("""
                INSERT INTO scan_version (organization_id, venue_id, scan_id, floor_id, version_number, pipeline_run_id)
                VALUES (:o, :v, :s, :f, 1, :r) RETURNING id
                """)
            .param("o", c.org()).param("v", c.venue()).param("s", scan).param("f", c.floor()).param("r", run)
            .query(UUID.class).single();
        Map<UUID, String> pins = new java.util.HashMap<>(Map.of(cloud, "SPLAT_CLEAN", ksplat, "KSPLAT"));
        if (withNavmesh) {
            for (String kind : List.of("NAVMESH", "NAVMESH_MANIFEST", "NAVIGATION_GRAPH")) {
                pins.put(fx.publishedArtifact(c.org(), c.venue(), scan, run, "NAVIGATION_BAKING", kind), kind);
            }
        }
        for (var pin : pins.entrySet()) {
            jdbc.sql("INSERT INTO scan_version_artifact (scan_version_id, artifact_id, kind, owner_version_id) VALUES (:v, :a, :k, :v)")
                .param("v", version).param("a", pin.getKey()).param("k", pin.getValue()).update();
        }
        jdbc.sql("UPDATE scan_version SET status = 'FINALIZED', finalized_at = now(), coordinate_frame_id = :f, "
                + "provenance = CAST(:p AS jsonb) WHERE id = :v")
            .param("f", parentFrame).param("p", "{\"bootstrap\":true,\"runId\":\"" + run + "\"}").param("v", version).update();
        return version;
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
                JobStage.PRIVACY_PREPROCESS, JobStage.POSE_ESTIMATION, JobStage.SPLAT_RECONSTRUCTION, JobStage.SEMANTIC_SEGMENTATION,
                JobStage.GEOMETRIC_CLEANUP)) {
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
            + "WHERE sr.run_id = :r").param("r", parentRun).query(Integer.class).single())
            .as("the parent's cloud, its labels and its KSPLAT, nothing added").isEqualTo(3);
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

        assertThat(alignOrder.get("inputs").findValuesAsText("kind")).doesNotContain("GLOBAL_LABELS");
        JsonNode spliceOrder = claimExpecting("REGION_SPLICE");
        // Review N-1: the splice gets the parent's labels -- exactly those published with the pinned cloud -- so it can
        // carry every wall and piece of furniture outside the region into SEMANTIC_LABELS_MERGED for NAVIGATION_BAKING.
        List<String> globalLabels = new java.util.ArrayList<>();
        for (JsonNode in : spliceOrder.get("inputs")) {
            if (in.get("kind").asText().equals("GLOBAL_LABELS")) {
                globalLabels.add(in.get("artifactId").asText());
            }
        }
        assertThat(globalLabels).containsExactly(parentLabels.toString());
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
        assertThat(stages).as("the parent version has no navmesh, so there is none to re-bake").doesNotContain("NAVIGATION_BAKING");

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
                    coordinate_frame_id, pipeline_run_id, scan_version_id, created_by)
                VALUES (:o, :v, :p, 1, :label, '{}', :x, :y, 0, :src, :frame, :run,
                    (SELECT id FROM scan_version WHERE pipeline_run_id = :parentRun), 'test')
                """)
            .param("o", c.org()).param("v", c.venue()).param("p", id).param("label", source.toLowerCase() + " poi")
            .param("x", x).param("y", y).param("src", source).param("frame", parentFrame)
            .param("run", source.equals("MANUAL") ? null : parentRun).param("parentRun", parentRun).update();
        return id;
    }

    private String detectedObjects(JsonNode order, double x, double y) throws Exception {
        StringBuilder embedding = new StringBuilder();
        for (int i = 0; i < 512; i++) {
            embedding.append(i > 0 ? "," : "").append(String.format(java.util.Locale.ROOT, "%.4f", Math.cos(i * 0.013)));
        }
        return "{\"coordinate_frame\":{\"id\":\"" + parentFrame + "\",\"units\":\"m\",\"up_axis\":\"+Z\"},"
            + "\"source\":" + mapper.writeValueAsString(sourceOf(order)) + ","
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
            succeed(claimExpecting(stage)); // SPLAT_MERGED, ..., KSPLAT of the merged model (PipelineTestSupport#outputsFor)
        }
        JsonNode semantic = claimExpecting("SEMANTIC_INDEXING");
        send(semantic, report("SUCCEEDED", List.of(artifact(semantic, "detected-objects.json", "DETECTED_OBJECTS", false, false,
            detectedObjects(semantic, 2.0, 2.0))), null, null), svc).andExpect(status().isOk());
        return claimExpecting("NAVIGATION_BAKING");
    }

    private List<String> livePois(Ctx c) {
        return jdbc.sql("SELECT v.source || ':' || v.label FROM poi p JOIN poi_version v ON v.poi_id = p.id "
                + "WHERE p.venue_id = :v AND p.deleted_at IS NULL ORDER BY 1").param("v", c.venue()).query(String.class).list();
    }

    @Test
    void poisChangeOnlyWhenTheRescanFinalizesAndManualPoisAlwaysSurvive() throws Exception {
        Ctx c = ctx();
        UUID parent = finalizedParentWithGlobalCloud(c, true);
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
            .as("the failed re-scan's merged model is not listed for viewers; the parent version's is")
            .extracting(ReconstructionService.ReconstructionVersion::runId).containsExactly(parentRun);

        // A re-scan that finalizes: the region's AUTO_DETECTED POI is replaced; the MANUAL one and the outside one survive.
        JsonNode nav2 = rescanToNavigationBaking(c, parent);
        send(nav2, report("SUCCEEDED", navigationOutputs(nav2, parentFrame), null, null), svc).andExpect(status().isOk());
        assertThat(livePois(c)).containsExactly("AUTO_DETECTED:auto_detected poi", "AUTO_DETECTED:new bench", "MANUAL:manual poi");
        assertThat(reconstructions.listForFloor(viewer, c.venue(), c.floor()))
            .extracting(ReconstructionService.ReconstructionVersion::versionNumber)
            .as("the failed attempt kept number 2; numbers are never reused").containsExactly(3, 1);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE action = 'scan_version.promoted' AND resource_id = "
                + "(SELECT current_scan_version_id FROM floor WHERE id = :f)").param("f", c.floor()).query(Integer.class).single())
            .as("the re-scan's version was published, with its detections").isEqualTo(1);
    }

    // ---- navigation: re-baked from the merged scene, never inherited; artifacts name their version -------------------------

    @Test
    void aRescanOfAVersionWithANavmeshReBakesItAgainstTheParentSceneAndPinsOnlyItsOwn() throws Exception {
        Ctx c = ctx();
        UUID parent = finalizedParentWithGlobalCloud(c, true);
        JsonNode nav = rescanToNavigationBaking(c, parent);
        // The worker re-bakes the whole floor from the merged scene and proves it against the parent scene outside the
        // region, so it is given the parent's pinned cloud and labels (chaya_worker.stages.navigation_baking).
        Map<String, String> inputs = new java.util.HashMap<>();
        nav.get("inputs").forEach(in -> inputs.put(in.get("kind").asText(), in.get("artifactId").asText()));
        UUID parentCloud = jdbc.sql("SELECT artifact_id FROM scan_version_artifact WHERE scan_version_id = :v AND kind = 'SPLAT_CLEAN'")
            .param("v", parent).query(UUID.class).single();
        assertThat(inputs).containsEntry("GLOBAL_CLOUD", parentCloud.toString()).containsEntry("GLOBAL_LABELS", parentLabels.toString())
            .containsKey("SPLAT_MERGED");
        assertThat(nav.get("regionGeometry").get("points")).hasSize(4);
        send(nav, report("SUCCEEDED", navigationOutputs(nav, parentFrame), null, null), svc).andExpect(status().isOk());

        UUID version = UUID.fromString(nav.get("scanVersionId").asText());
        assertThat(versionRow(version).get("status")).isEqualTo("FINALIZED");
        List<Map<String, Object>> navigationPins = jdbc.sql("SELECT kind, owner_version_id FROM scan_version_artifact "
                + "WHERE scan_version_id = :v AND kind IN ('NAVMESH', 'NAVMESH_MANIFEST', 'NAVIGATION_GRAPH')")
            .param("v", version).query().listOfRows();
        assertThat(navigationPins).hasSize(3).allSatisfy(p -> assertThat(p.get("owner_version_id")).isEqualTo(version));
        assertThat(jdbc.sql("SELECT count(*) FROM navigation_graph WHERE scan_version_id = :v AND status = 'ACTIVE'")
            .param("v", version).query(Integer.class).single()).as("its own graphs went live with it").isEqualTo(2);
    }

    @Test
    void aRescanThatPublishesNoNavmeshIsNotFinalizedWhenItsParentHadOne() throws Exception {
        Ctx c = ctx();
        UUID parent = finalizedParentWithGlobalCloud(c, true);
        JsonNode nav = rescanToNavigationBaking(c, parent);
        // A "successful" NAVIGATION_BAKING with no navmesh: the version would otherwise have had no navigation, or (before
        // V26) silently inherited the parent's, baked for geometry the re-scan replaced.
        // The stage report is kept; publishing the version is refused, so the run ends FAILED and nothing is published.
        send(nav, report("SUCCEEDED", List.of(artifact(nav, "navigation-baking-report.json", "NAVIGATION_BAKING_REPORT", false, false,
            "{}")), null, null), svc).andExpect(status().isOk());
        UUID version = UUID.fromString(nav.get("scanVersionId").asText());
        assertThat(jdbc.sql("SELECT status || ':' || coalesce(failure_code, '-') FROM pipeline_run WHERE scan_version_id = :v")
            .param("v", version).query(String.class).single()).isEqualTo("FAILED:VERSION_INCOMPLETE");
        assertThat(jdbc.sql("SELECT current_scan_version_id IS NULL FROM floor WHERE id = :f").param("f", c.floor())
            .query(Boolean.class).single()).as("nothing was published").isTrue();
        assertThat(versionRow(version).get("status")).isEqualTo("DRAFT");
        assertThat(jdbc.sql("SELECT count(*) FROM scan_version_artifact WHERE scan_version_id = :v").param("v", version)
            .query(Integer.class).single()).as("nothing pinned").isZero();
    }

    @Test
    void artifactsThatNameAnotherVersionOrNoneAreRefused() throws Exception {
        Ctx c = ctx();
        UUID parent = finalizedParentWithGlobalCloud(c, true);
        JsonNode nav = rescanToNavigationBaking(c, parent);
        UUID version = UUID.fromString(nav.get("scanVersionId").asText());

        // A navigation graph produced for the parent version (or any other) is never stored under this one.
        List<Map<String, Object>> outputs = navigationOutputs(nav, parentFrame);
        Map<String, Object> graph = mapper.readValue(Files.readString(Path.of("../../packages/contracts/fixtures/navmesh/navigation-graph.json"))
            .replace("FIXTURE_FRAME_ID", parentFrame.toString()), Map.class);
        for (Object claimed : java.util.Arrays.asList(parent.toString(), null)) {
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("scan_version_id", claimed);
            graph.put("source", source);
            List<Map<String, Object>> wrong = new ArrayList<>(outputs.subList(0, 2));
            wrong.add(artifact(nav, "navigation-graph-" + claimed + ".json", "NAVIGATION_GRAPH", false, false, mapper.writeValueAsString(graph)));
            send(nav, report("SUCCEEDED", wrong, null, null), svc).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ARTIFACT_VERSION_MISMATCH"));
        }
        graph.remove("source");
        List<Map<String, Object>> unnamed = new ArrayList<>(outputs.subList(0, 2));
        unnamed.add(artifact(nav, "navigation-graph-unnamed.json", "NAVIGATION_GRAPH", false, false, mapper.writeValueAsString(graph)));
        send(nav, report("SUCCEEDED", unnamed, null, null), svc).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ARTIFACT_VERSION_MISMATCH"));
        assertThat(jdbc.sql("SELECT count(*) FROM navigation_graph WHERE scan_version_id = :v").param("v", version)
            .query(Integer.class).single()).isZero();

        // The graph naming this version is accepted.
        send(nav, report("SUCCEEDED", outputs, null, null), svc).andExpect(status().isOk());
        assertThat(versionRow(version).get("status")).isEqualTo("FINALIZED");
    }
}
