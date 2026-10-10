package dev.chaya.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.chaya.api.navigation.NavigationDtos.RouteRequest;
import dev.chaya.api.navigation.NavigationDtos.RouteResponse;
import dev.chaya.api.navigation.RouteService;
import dev.chaya.api.pipeline.PipelineService;
import dev.chaya.api.search.SearchDtos.SearchResponse;
import dev.chaya.api.search.SemanticSearchService;
import dev.chaya.api.security.Actor;
import dev.chaya.api.security.Role;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The stage artifact contract (packages/contracts/pipeline/stage-artifacts.json) and the failure modes of the whole
 * capture-to-twin workflow, through the real worker protocol against real PostgreSQL and MinIO: a missing output, a checksum
 * error, an unreadable or malformed DETECTED_OBJECTS or NAVIGATION_GRAPH, a missing calibration, a lost worker, and search
 * and navigation once published (including search with the vision service down).
 *
 * <p>FIXTURE DATA ONLY: the stage outputs are PipelineTestSupport's fixture worker (real kinds and documents the control
 * plane reads, placeholder bytes otherwise) and the navmesh is the synthetic Recast venue
 * (packages/contracts/fixtures/navmesh). Nothing here is a real venue: it proves the contracts and the lifecycle, not
 * reconstruction quality. The real-venue run is scripts/acceptance/real_venue.py.
 */
class PipelineOutputContractTest extends PipelineTestSupport {

    @Autowired PipelineService pipeline;
    @Autowired RouteService routes;
    @Autowired SemanticSearchService search;
    @Autowired dev.chaya.api.search.PoiEmbeddingService embeddings;

    @AfterEach
    void visionBack() {
        TestEmbeddingConfig.UNAVAILABLE.set(false);
    }

    private JsonNode run(Started s) throws Exception {
        return processing(s).get("run");
    }

    private void retry(Started s) throws Exception {
        post(capUrl(s.c(), s.capture()) + "/processing/retry", s.c().operator(), null).andExpect(status().isAccepted());
    }

    private void assertFailedAt(Started s, String stage, String code) throws Exception {
        JsonNode r = run(s);
        assertThat(r.get("status").asText()).isEqualTo("FAILED");
        assertThat(r.get("failureStage").asText()).isEqualTo(stage);
        assertThat(r.get("failureCode").asText()).isEqualTo(code);
        assertThat(r.get("retryable").asBoolean()).as("a broken output is retryable").isTrue();
        assertThat(claim()).as("nothing after the failed stage is queued").isNull();
    }

    @Test
    void aSuccessWithoutARequiredOutputFailsThatStageAndARetryRecovers() throws Exception {
        Started s = startRun();
        runThrough("PRIVACY_PREPROCESS");
        JsonNode pose = claimExpecting("POSE_ESTIMATION");
        send(pose, report("SUCCEEDED", List.of(artifact(pose, "poses.json", "POSES", false, false, "{}")), null, null), svc)
            .andExpect(status().isOk());

        assertFailedAt(s, "POSE_ESTIMATION", "STAGE_OUTPUT_MISSING");
        JsonNode poseStage = run(s).get("stages").get(4);
        assertThat(poseStage.get("state").asText()).isEqualTo("FAILED");
        assertThat(poseStage.get("lastRun").get("status").asText()).as("the stage run itself is recorded FAILED").isEqualTo("FAILED");
        assertThat(poseStage.get("lastRun").get("errorDetails").get("missing")).extracting(JsonNode::asText).containsExactly("SPARSE_MODEL");

        retry(s);
        JsonNode again = claimExpecting("POSE_ESTIMATION");
        assertThat(again.get("attempt").asInt()).isEqualTo(2);
        succeed(again);
        JsonNode splat = claimExpecting("SPLAT_RECONSTRUCTION");
        List<String> kinds = splat.get("inputs").findValuesAsText("kind");
        assertThat(kinds).contains("SPARSE_MODEL", "POSES", "FRAME_ARCHIVE_ANON", "PRIVACY_MASKS");
        assertThat(kinds.stream().filter("POSES"::equals)).as("the failed attempt's outputs are never inputs").hasSize(1);
    }

    @Test
    void aChecksumThatDoesNotMatchTheStoredObjectIsRefusedAndNothingIsRecorded() throws Exception {
        Started s = startRun();
        JsonNode order = claimExpecting("INPUT_VALIDATION");
        Map<String, Object> a = new java.util.LinkedHashMap<>(artifact(order, "input-report.json", "INPUT_REPORT", false, false, "{\"ok\":true}"));
        a.put("sha256", "0".repeat(64));
        send(order, report("SUCCEEDED", List.of(a), null, null), svc).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ARTIFACT_CHECKSUM_MISMATCH"));
        assertThat(jdbc.sql("SELECT count(*) FROM pipeline_stage_run WHERE run_id = :r").param("r", s.run()).query(Integer.class).single())
            .isZero();
        assertThat(run(s).get("status").asText()).as("the job is still the worker's to report").isEqualTo("RUNNING");
        succeed(order);
        assertThat(claimExpecting("FFMPEG_PREPROCESS")).isNotNull();
    }

    @Test
    void aLostWorkersStageIsReclaimedAfterARetryAndItsLateReportIsRefused() throws Exception {
        Started s = startRun();
        runThrough("POSE_ESTIMATION");
        JsonNode lost = claimExpecting("SPLAT_RECONSTRUCTION");
        jdbc.sql("UPDATE processing_job SET lease_expires_at = now() - interval '1 minute' WHERE id = :j")
            .param("j", UUID.fromString(lost.get("id").asText())).update();
        assertThat(pipeline.enforceLeasesAndDeadlines()).isPositive();
        assertFailedAt(s, "SPLAT_RECONSTRUCTION", "WORKER_LOST");
        send(lost, report("SUCCEEDED", outputsFor(lost), null, null), svc).andExpect(status().isConflict());

        retry(s);
        JsonNode again = claimExpecting("SPLAT_RECONSTRUCTION");
        assertThat(again.get("attempt").asInt()).isEqualTo(2);
        assertThat(again.get("leaseToken").asText()).isNotEqualTo(lost.get("leaseToken").asText());
        succeed(again);
        assertThat(claimExpecting("SEMANTIC_SEGMENTATION")).isNotNull();
    }

    /**
     * The whole workflow from POSE_ESTIMATION on, with every failure the metric stages can meet on the way: no
     * calibration (recovered by calibrating), an unreadable and then a malformed DETECTED_OBJECTS, a NAVIGATION_GRAPH with nothing to route on. Each
     * fails its own stage; each retry continues from there; the run then succeeds, its version is published, its detected
     * objects are searchable (semantically, and lexically with the vision service down), and routes run on the navmesh
     * baked by this run.
     */
    @Test
    void theMetricStagesFailOnBrokenOutputsAndTheRecoveredRunIsSearchableAndRoutable() throws Exception {
        Started s = startRun();
        for (String stage : List.of("INPUT_VALIDATION", "FFMPEG_PREPROCESS", "FRAME_QUALITY_FILTER", "PRIVACY_PREPROCESS", "POSE_ESTIMATION",
                "SPLAT_RECONSTRUCTION", "SEMANTIC_SEGMENTATION", "GEOMETRIC_CLEANUP", "PLANE_FITTING", "ARTIFACT_GENERATION")) {
            succeed(claimExpecting(stage));
        }

        // 1. No calibration: the first metric stage gets no frame and fails NOT_CALIBRATED (the real worker's refusal).
        // Each later step uses one of the stage's bounded retries (max_retries 3): four attempts in all.
        JsonNode uncalibrated = claimExpecting("SEMANTIC_INDEXING");
        assertThat(uncalibrated.get("coordinateFrame").isNull()).isTrue();
        succeed(uncalibrated);
        assertFailedAt(s, "SEMANTIC_INDEXING", "NOT_CALIBRATED");

        String frameId = calibrateOk(s, controlPointCalibration(0)).get("id").asText();
        retry(s);

        // 2. DETECTED_OBJECTS that is not JSON at all.
        JsonNode semantic = claimExpecting("SEMANTIC_INDEXING");
        assertThat(semantic.get("coordinateFrame").get("id").asText()).isEqualTo(frameId);
        send(semantic, report("SUCCEEDED", List.of(artifact(semantic, "detected-objects.json", "DETECTED_OBJECTS", false, false,
            "not json")), null, null), svc).andExpect(status().isOk());
        assertFailedAt(s, "SEMANTIC_INDEXING", "ARTIFACT_UNREADABLE");

        // 3. DETECTED_OBJECTS whose embedding is not the 512-d space search compares against.
        retry(s);
        semantic = claimExpecting("SEMANTIC_INDEXING");
        Map<String, Object> good = detections(semantic, frameId, List.<Object[]>of(new Object[]{"exit sign", 6.0, 3.0}));
        ObjectNode wrongDim = (ObjectNode) mapper.readTree(objectContent(good));
        ((ObjectNode) wrongDim.get("objects").get(0)).putArray("embedding").add(0.1).add(0.2).add(0.3);
        send(semantic, report("SUCCEEDED", List.of(artifact(semantic, "detected-objects.json", "DETECTED_OBJECTS", false, false,
            mapper.writeValueAsString(wrongDim))), null, null), svc).andExpect(status().isOk());
        assertFailedAt(s, "SEMANTIC_INDEXING", "DETECTED_OBJECTS_INVALID");
        assertThat(run(s).get("failureMessage").asText()).contains("objects[0]").contains("512");

        // 4. Well-formed detections; then a NAVIGATION_GRAPH with no STANDARD graph.
        retry(s);
        semantic = claimExpecting("SEMANTIC_INDEXING");
        send(semantic, report("SUCCEEDED", List.of(detections(semantic, frameId, List.<Object[]>of(new Object[]{"exit sign", 6.0, 3.0}))), null, null),
            svc).andExpect(status().isOk());
        JsonNode nav = claimExpecting("NAVIGATION_BAKING");
        List<Map<String, Object>> navOutputs = new java.util.ArrayList<>(navigationOutputs(nav, frameId));
        Map<String, Object> graph = navOutputs.stream().filter(a -> a.get("kind").equals("NAVIGATION_GRAPH")).findFirst().orElseThrow();
        ObjectNode noStandard = (ObjectNode) mapper.readTree(objectContent(graph));
        ((ObjectNode) noStandard.get("graphs")).remove("STANDARD");
        navOutputs.set(navOutputs.indexOf(graph), artifact(nav, "navigation-graph.json", "NAVIGATION_GRAPH", false, false,
            mapper.writeValueAsString(noStandard)));
        send(nav, report("SUCCEEDED", navOutputs, null, null), svc).andExpect(status().isOk());
        assertFailedAt(s, "NAVIGATION_BAKING", "NAVIGATION_GRAPH_INVALID");
        assertThat(jdbc.sql("SELECT count(*) FROM navigation_graph WHERE pipeline_run_id = :r").param("r", s.run()).query(Integer.class).single())
            .as("nothing routable was ingested from the failed bake").isZero();

        // 5. The retried bake succeeds: the run SUCCEEDS and its version is published.
        retry(s);
        nav = claimExpecting("NAVIGATION_BAKING");
        succeed(nav);
        JsonNode done = run(s);
        assertThat(done.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(done.get("quality").asText()).isEqualTo("FINAL");
        UUID version = UUID.fromString(nav.get("scanVersionId").asText());
        assertThat(jdbc.sql("SELECT current_scan_version_id FROM floor WHERE id = :f").param("f", s.c().floor()).query(UUID.class).single())
            .isEqualTo(version);

        // 6. Search finds the detected object, semantically and -- vision down -- lexically, scoped to the version. The detection
        // carries its CLIP image embedding; its label's text embedding is the backfill's job (PoiEmbeddingService), run here as
        // its schedule would. (This test's text embedder is a stand-in model, so only the text embedding is comparable.)
        assertThat(embeddings.embedPending(10_000).stoppedBecause()).isNull();
        Actor viewer = new Actor(Actor.Kind.USER, "viewer", s.c().org(), Set.of(s.c().venue()), Set.of(Role.VIEWER));
        SearchResponse found = search.search(viewer, s.c().venue(), "exit sign", s.c().floor(), null, null);
        assertThat(found.results()).extracting(r -> r.label()).contains("exit sign");
        TestEmbeddingConfig.UNAVAILABLE.set(true);
        SearchResponse lexical = search.search(viewer, s.c().venue(), "exit sign", s.c().floor(), null, null);
        assertThat(lexical.matchType()).isEqualTo("lexical_fallback");
        assertThat(lexical.results()).extracting(r -> r.label()).contains("exit sign");
        TestEmbeddingConfig.UNAVAILABLE.set(false);

        // 7. A route to that detected POI runs on the navmesh this run baked, on its floor and version.
        UUID exitSign = jdbc.sql("SELECT p.id FROM poi p JOIN poi_version v ON v.poi_id = p.id WHERE p.floor_id = :f AND v.label = 'exit sign' "
                + "AND v.scan_version_id = :sv").param("f", s.c().floor()).param("sv", version).query(UUID.class).single();
        String bakedSha = jdbc.sql("SELECT a.checksum_sha256 FROM processing_artifact a JOIN pipeline_stage_run sr ON sr.id = a.stage_run_id "
                + "WHERE sr.run_id = :r AND sr.status = 'SUCCEEDED' AND a.kind = 'NAVMESH'").param("r", s.run()).query(String.class).single();
        for (String profile : List.of("STANDARD", "STEP_FREE")) {
            RouteResponse route = routes.route(viewer, new RouteRequest(s.c().venue(), s.c().floor(), List.of(1.0, 3.0, 0.05), exitSign, profile,
                null, version));
            assertThat(route.accessibilityProfile()).isEqualTo(profile);
            assertThat(route.routingSources()).singleElement().satisfies(src -> assertThat(src.navmeshSha256()).isEqualTo(bakedSha));
            assertThat(route.distanceMeters()).isBetween(5.0, 9.0);
        }
    }

    /** The bytes a fixture artifact entry was uploaded with. */
    private String objectContent(Map<String, Object> artifactEntry) throws Exception {
        try (var in = derived.open((String) artifactEntry.get("key"))) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
