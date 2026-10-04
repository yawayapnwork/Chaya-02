package dev.chaya.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import dev.chaya.api.pipeline.PipelineDefinition;
import dev.chaya.api.poi.PoiService;
import dev.chaya.api.poi.PoiService.PoiData;
import dev.chaya.api.security.Actor;
import dev.chaya.api.security.Role;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/**
 * ScanVersion integrity end to end, through the real HTTP API and worker protocol (docs/rescan.md, "VERSIONING"):
 * <ol>
 *   <li>version 1: a full reconstruction, calibrated, with detections and a real Recast navmesh, formalized by
 *       finalize-current;</li>
 *   <li>version 2: a re-scan of version 1 whose region touches the navigation graph (so it re-bakes navigation), with a
 *       new detection in the region;</li>
 *   <li>version 3: a re-scan of version 2 whose region is away from every graph node. It still re-bakes navigation: its
 *       parent has a navmesh, and navigation is never inherited (a region can change navigation without containing a
 *       node of the old graph).</li>
 * </ol>
 * It then checks the lineage and numbering, which run owns every pinned artifact, that switching the viewer between
 * versions switches the model, the frame, the POIs and the routing graph together, and that finalized versions cannot be
 * changed. The worker's reports are fixtures in the worker's real report shape; the navmesh is the committed real Recast
 * output (packages/contracts/fixtures/navmesh). Skipped, not failed, without Docker (AbstractIntegrationTest).
 */
class ScanVersionLineageTest extends PipelineTestSupport {

    /** 9 m^2 in the left room of the navmesh fixture: four of its STANDARD polygons lie inside. */
    private static final String REGION_TOUCHING_GRAPH = "[[0,0],[0,3],[3,3],[3,0]]";
    /** 9 m^2 beyond the fixture's walkable area: no polygon or node inside. */
    private static final String REGION_AWAY_FROM_GRAPH = "[[8,6],[8,9],[11,9],[11,6]]";

    @Autowired PoiService pois;

    private Ctx c;
    private String frameId;

    // ---- worker outputs --------------------------------------------------------------------------------------------

    private String detectedObjects(JsonNode order, Object... labelXY) throws Exception {
        StringBuilder embedding = new StringBuilder();
        for (int i = 0; i < 512; i++) {
            embedding.append(i > 0 ? "," : "").append(String.format(Locale.ROOT, "%.4f", Math.sin(i * 0.011)));
        }
        List<String> objects = new ArrayList<>();
        for (int i = 0; i < labelXY.length; i += 3) {
            objects.add("{\"label\":\"" + labelXY[i] + "\",\"confidence\":0.8,\"position\":[" + labelXY[i + 1] + "," + labelXY[i + 2]
                + ",0.4],\"embedding\":[" + embedding + "]}");
        }
        return "{\"coordinate_frame\":{\"id\":\"" + frameId + "\",\"units\":\"m\",\"up_axis\":\"+Z\"},"
            + "\"source\":" + mapper.writeValueAsString(sourceOf(order)) + ","
            + "\"embedding_model\":\"open_clip:ViT-B-32:openai\",\"objects\":[" + String.join(",", objects) + "]}";
    }

    private List<Map<String, Object>> model(JsonNode order, String bytes) {
        return List.of(artifact(order, "scene.ksplat", "KSPLAT", false, false, bytes),
            artifact(order, "manifest.json", "ARTIFACT_MANIFEST", false, false, "{}"));
    }

    private void ok(JsonNode order, List<Map<String, Object>> outputs) throws Exception {
        send(order, report("SUCCEEDED", outputs, null, null), svc).andExpect(status().isOk());
    }

    // ---- versions --------------------------------------------------------------------------------------------------

    /** Version 1: a full reconstruction, calibrated before SEMANTIC_INDEXING, then finalize-current. */
    private JsonNode version1() throws Exception {
        Started s = startRun();
        c = s.c();
        for (var stage : PipelineDefinition.STAGES) {
            if (stage.name().equals("SEMANTIC_INDEXING")) {
                frameId = calibrateOk(s, controlPointCalibration(0)).get("id").asText();
            }
            JsonNode order = claimExpecting(stage.name());
            switch (stage.name()) {
                case "ARTIFACT_GENERATION" -> ok(order, model(order, "version-1 model"));
                case "SEMANTIC_INDEXING" -> ok(order, List.of(artifact(order, "detected-objects.json", "DETECTED_OBJECTS", false, false,
                    detectedObjects(order, "bench", 6.5, 1.0, "chair", 1.5, 1.5))));
                case "NAVIGATION_BAKING" -> ok(order, navigationOutputs(order, frameId));
                default -> succeed(order);
            }
        }
        assertThat(processing(s).get("run").get("status").asText()).isEqualTo("SUCCEEDED");
        return mapper.readTree(post(floorUrl() + "/scan-versions/finalize-current", c.operator(), null)
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private static Map<String, Object> acceptedAlignment() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("status", "ACCEPTED");
        a.put("mode", "DIRECT_CANONICAL");
        a.put("method", "DIRECT_CANONICAL_ICP");
        a.put("confidence", 0.9);
        a.put("inlier_rmse_m", 0.012);
        a.put("scale_correction", 1.0);
        a.put("gates_passed", true);
        a.put("failed_gates", List.of());
        return a;
    }

    /** A re-scan of `parent` over `region`, driven to a FINALIZED version. Returns {runId, navigationRebuildRequired}. */
    private Object[] rescan(UUID parent, String region, String modelBytes, Object... detections) throws Exception {
        String init = post(floorUrl() + "/rescan", c.operator(),
            "{\"parentVersionId\":\"" + parent + "\",\"region\":{\"points\":" + region + "}}")
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        JsonNode initiated = mapper.readTree(init);
        UUID capture = UUID.fromString(initiated.get("captureId").asText());
        var u = upload(c, capture, "VIDEO", "walk.mp4", "video/mp4", mp4(4096));
        awaitSettled(c, capture, u.mediaId());
        post(capUrl(c, capture) + "/complete-upload", c.operator(), null).andExpect(status().isOk());
        JsonNode started = mapper.readTree(post(capUrl(c, capture) + "/processing", c.operator(), null)
            .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
        UUID run = UUID.fromString(started.get("run").get("id").asText());

        JsonNode order;
        while ((order = claim()) != null) {
            switch (order.get("stage").asText()) {
                case "REGION_ALIGNMENT" -> {
                    Map<String, Object> r = report("SUCCEEDED", List.of(artifact(order, "alignment-report.json", "ALIGNMENT_REPORT",
                        false, false, "{}")), null, null);
                    r.put("command", Map.of("argv", List.of("region-alignment"), "config", Map.of("alignment", acceptedAlignment())));
                    send(order, r, svc).andExpect(status().isOk());
                }
                case "ARTIFACT_GENERATION" -> ok(order, model(order, modelBytes));
                case "SEMANTIC_INDEXING" -> ok(order, List.of(artifact(order, "detected-objects.json", "DETECTED_OBJECTS", false, false,
                    detectedObjects(order, detections))));
                case "NAVIGATION_BAKING" -> ok(order, navigationOutputs(order, frameId));
                default -> succeed(order);
            }
        }
        assertThat(jdbc.sql("SELECT status FROM pipeline_run WHERE id = :r").param("r", run).query(String.class).single())
            .isEqualTo("SUCCEEDED");
        return new Object[]{run, initiated.get("navigationRebuildRequired").asBoolean()};
    }

    private String floorUrl() {
        return "/api/v1/venues/" + c.venue() + "/floors/" + c.floor();
    }

    private String viewerToken() {
        return TestJwt.user(c.org(), "viewer").venues(c.venue()).token();
    }

    private JsonNode getJson(String url) throws Exception {
        return mapper.readTree(get(url, viewerToken()).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private Map<Integer, JsonNode> versionsByNumber() throws Exception {
        Map<Integer, JsonNode> byNumber = new HashMap<>();
        for (JsonNode v : mapper.readTree(get(floorUrl() + "/scan-versions", c.operator()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString())) {
            byNumber.put(v.get("versionNumber").asInt(), v);
        }
        return byNumber;
    }

    /** The run whose stage published the artifact. */
    private UUID runOfArtifact(UUID artifact) {
        return jdbc.sql("SELECT sr.run_id FROM processing_artifact a JOIN pipeline_stage_run sr ON sr.id = a.stage_run_id WHERE a.id = :a")
            .param("a", artifact).query(UUID.class).single();
    }

    private Map<String, List<UUID>> ownersByKind(JsonNode version) {
        Map<String, List<UUID>> owners = new HashMap<>();
        for (JsonNode a : version.get("artifacts")) {
            owners.computeIfAbsent(a.get("kind").asText(), k -> new ArrayList<>()).add(UUID.fromString(a.get("ownerVersionId").asText()));
        }
        return owners;
    }

    private List<String> poiLabels(UUID version) throws Exception {
        List<String> labels = new ArrayList<>();
        for (JsonNode p : getJson("/api/v1/venues/" + c.venue() + "/pois?scanVersionId=" + version)) {
            labels.add(p.get("label").asText());
            assertThat(p.get("frameStatus").asText()).as(p.get("label").asText() + " is in the version's frame").isEqualTo("CURRENT");
        }
        return labels;
    }

    private UUID poiId(String label) {
        return jdbc.sql("SELECT p.id FROM poi p JOIN poi_version v ON v.poi_id = p.id WHERE p.venue_id = :v AND v.label = :l LIMIT 1")
            .param("v", c.venue()).param("l", label).query(UUID.class).single();
    }

    private JsonNode route(UUID version, UUID destination, int expectedStatus) throws Exception {
        String body = "{\"venueId\":\"" + c.venue() + "\",\"floorId\":\"" + c.floor() + "\",\"start\":[1.0,3.0,0.05],"
            + "\"destinationPoiId\":\"" + destination + "\",\"scanVersionId\":\"" + version + "\"}";
        String json = post("/api/v1/navigation/routes", viewerToken(), body).andExpect(status().is(expectedStatus))
            .andReturn().getResponse().getContentAsString();
        return mapper.readTree(json);
    }

    private UUID standardGraphOf(UUID version) {
        return jdbc.sql("SELECT id FROM navigation_graph WHERE scan_version_id = :v AND profile = 'STANDARD'").param("v", version)
            .query(UUID.class).single();
    }

    private String download(String url) throws Exception {
        MvcResult started = get(url, viewerToken()).andExpect(request().asyncStarted()).andReturn();
        return mvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    // ---- the test ----------------------------------------------------------------------------------------------------

    @Test
    void versionsOwnTheirArtifactsAndTheViewerShowsExactlyTheSelectedOne() throws Exception {
        JsonNode v1 = version1();
        UUID v1Id = UUID.fromString(v1.get("id").asText());
        UUID run1 = UUID.fromString(v1.get("pipelineRunId").asText());
        // A MANUAL POI placed while version 1 is the floor's version: it belongs to version 1 and every descendant.
        Actor manager = new Actor(Actor.Kind.USER, "manager", c.org(), Set.of(c.venue()), Set.of(Role.VENUE_MANAGER));
        PoiService.Poi reception = pois.create(manager, c.venue(), new PoiData(c.floor(), null, "reception", null, null, List.of(), 6.0, 3.0, 0.05));
        assertThat(reception.scanVersionId()).isEqualTo(v1Id);

        Object[] r2 = rescan(v1Id, REGION_TOUCHING_GRAPH, "version-2 merged model", "kiosk", 2.0, 2.0);
        UUID run2 = (UUID) r2[0];
        assertThat((Boolean) r2[1]).as("the region touches the graph, so navigation is re-baked").isTrue();
        Map<Integer, JsonNode> afterV2 = versionsByNumber();
        UUID v2Id = UUID.fromString(afterV2.get(2).get("id").asText());
        // Version 1 exactly as it was when it finalized.
        assertThat(afterV2.get(1)).isEqualTo(v1);

        Object[] r3 = rescan(v2Id, REGION_AWAY_FROM_GRAPH, "version-3 merged model");
        UUID run3 = (UUID) r3[0];
        assertThat((Boolean) r3[1]).as("away from every graph node, but its parent has a navmesh: re-baked, never inherited").isTrue();
        Map<Integer, JsonNode> versions = versionsByNumber();
        JsonNode v2 = versions.get(2);
        JsonNode v3 = versions.get(3);
        UUID v3Id = UUID.fromString(v3.get("id").asText());

        // ---- lineage and numbering ------------------------------------------------------------------------------------
        assertThat(versions.keySet()).containsExactlyInAnyOrder(1, 2, 3);
        assertThat(versions.get(1)).isEqualTo(v1);
        assertThat(v1.get("parentVersionId").isNull()).isTrue();
        assertThat(v2.get("parentVersionId").asText()).isEqualTo(v1Id.toString());
        assertThat(v3.get("parentVersionId").asText()).isEqualTo(v2Id.toString());
        for (JsonNode v : versions.values()) {
            assertThat(v.get("status").asText()).isEqualTo("FINALIZED");
            assertThat(v.get("coordinateFrameId").asText()).as("every version names its frame").isEqualTo(frameId);
        }
        assertThat(v1.get("pipelineRunId").asText()).isEqualTo(run1.toString());
        assertThat(v2.get("pipelineRunId").asText()).isEqualTo(run2.toString());
        assertThat(v3.get("pipelineRunId").asText()).isEqualTo(run3.toString());

        // ---- artifact ownership -----------------------------------------------------------------------------------------
        Map<UUID, UUID> runOfVersion = Map.of(v1Id, run1, v2Id, run2, v3Id, run3);
        for (JsonNode v : versions.values()) {
            for (JsonNode a : v.get("artifacts")) {
                UUID owner = UUID.fromString(a.get("ownerVersionId").asText());
                assertThat(runOfArtifact(UUID.fromString(a.get("artifactId").asText())))
                    .as(a.get("kind").asText() + " of version " + v.get("versionNumber") + " was published by its owner's run")
                    .isEqualTo(runOfVersion.get(owner));
            }
        }
        assertThat(ownersByKind(v1)).containsOnlyKeys("KSPLAT", "ARTIFACT_MANIFEST", "SPLAT_CLEAN", "DETECTED_OBJECTS", "NAVMESH",
            "NAVMESH_MANIFEST", "NAVIGATION_GRAPH").allSatisfy((kind, owners) -> assertThat(owners).containsOnly(v1Id));
        Map<String, List<UUID>> v2Owners = ownersByKind(v2);
        assertThat(v2Owners).doesNotContainKey("SPLAT_CLEAN").containsKey("SPLAT_MERGED");
        for (String own : List.of("KSPLAT", "SPLAT_MERGED", "NAVMESH", "NAVMESH_MANIFEST", "NAVIGATION_GRAPH")) {
            assertThat(v2Owners.get(own)).as("version 2's own " + own).containsExactly(v2Id);
        }
        assertThat(v2Owners.get("DETECTED_OBJECTS")).as("its region's detections, and version 1's elsewhere").containsExactlyInAnyOrder(v2Id, v1Id);
        Map<String, List<UUID>> v3Owners = ownersByKind(v3);
        for (String own : List.of("KSPLAT", "SPLAT_MERGED", "NAVMESH", "NAVMESH_MANIFEST", "NAVIGATION_GRAPH")) {
            assertThat(v3Owners.get(own)).as("version 3's own " + own + ", never version 2's").containsExactly(v3Id);
        }
        assertThat(jdbc.sql("SELECT DISTINCT scan_version_id FROM processing_artifact a JOIN pipeline_stage_run sr ON sr.id = a.stage_run_id "
                + "WHERE sr.run_id = :r").param("r", run2).query(UUID.class).list())
            .as("every artifact of the re-scan's run names its version").containsExactly(v2Id);

        // ---- the viewer, switched between versions -----------------------------------------------------------------------
        JsonNode listed = getJson(floorUrl() + "/reconstructions");
        assertThat(listed).hasSize(3);
        Map<String, Integer> numberOfRun = new HashMap<>();
        listed.forEach(r -> numberOfRun.put(r.get("runId").asText(), r.get("versionNumber").asInt()));
        assertThat(numberOfRun).containsEntry(run1.toString(), 1).containsEntry(run2.toString(), 2).containsEntry(run3.toString(), 3);

        Map<UUID, String> modelOf = Map.of(v1Id, "version-1 model", v2Id, "version-2 merged model", v3Id, "version-3 merged model");
        for (var entry : Map.of(run1, v1Id, run2, v2Id, run3, v3Id).entrySet()) {
            JsonNode scene = getJson("/api/v1/venues/" + c.venue() + "/reconstructions/" + entry.getKey());
            UUID version = entry.getValue();
            assertThat(scene.get("scanVersionId").asText()).isEqualTo(version.toString());
            assertThat(scene.get("coordinateFrame").get("id").asText()).isEqualTo(frameId);
            String ksplatUrl = null;
            for (JsonNode a : scene.get("artifacts")) {
                assertThat(a.get("url").asText()).as("served from the version, not from whatever the run or floor has now")
                    .startsWith("/api/v1/venues/" + c.venue() + "/scan-versions/" + version + "/artifacts/");
                if (a.get("kind").asText().equals("KSPLAT")) {
                    ksplatUrl = a.get("url").asText();
                    assertThat(a.get("sha256").asText()).isEqualTo(sha256(modelOf.get(version).getBytes(StandardCharsets.UTF_8)));
                }
            }
            assertThat(download(ksplatUrl)).isEqualTo(modelOf.get(version));
            assertThat(getJson("/api/v1/venues/" + c.venue() + "/scan-versions/" + version + "/reconstruction"))
                .as("the version endpoint and the run endpoint agree").isEqualTo(scene);
        }

        // POIs: each version shows its own, never a newer version's.
        assertThat(poiLabels(v1Id)).containsExactlyInAnyOrder("bench", "chair", "reception");
        assertThat(poiLabels(v2Id)).as("the chair in the re-scanned region was replaced by the kiosk")
            .containsExactlyInAnyOrder("bench", "kiosk", "reception");
        assertThat(poiLabels(v3Id)).containsExactlyInAnyOrder("bench", "kiosk", "reception");
        for (JsonNode p : getJson("/api/v1/venues/" + c.venue() + "/pois?scanVersionId=" + v2Id)) {
            assertThat(p.get("scanVersionId").asText()).isEqualTo((p.get("label").asText().equals("kiosk") ? v2Id : v1Id).toString());
        }

        // Navigation: each version routes on its own pinned navmesh's graph.
        UUID v1Graph = standardGraphOf(v1Id);
        UUID v2Graph = standardGraphOf(v2Id);
        UUID v3Graph = standardGraphOf(v3Id);
        assertThat(Set.of(v1Graph, v2Graph, v3Graph)).hasSize(3);
        UUID receptionId = reception.id();
        assertThat(route(v1Id, receptionId, 200).get("routingSources").get(0).get("graphId").asText()).isEqualTo(v1Graph.toString());
        assertThat(route(v2Id, receptionId, 200).get("routingSources").get(0).get("graphId").asText()).isEqualTo(v2Graph.toString());
        assertThat(route(v3Id, receptionId, 200).get("routingSources").get(0).get("graphId").asText()).isEqualTo(v3Graph.toString());
        // A POI of a newer version is not a destination in an older one, and a superseded one is not in a newer one.
        assertThat(route(v1Id, poiId("kiosk"), 404).get("detail").asText()).contains("not part of scan version");
        assertThat(route(v2Id, poiId("chair"), 404).get("detail").asText()).contains("not part of scan version");
        route(v1Id, poiId("chair"), 200);

        // ---- finalized versions cannot change ------------------------------------------------------------------------------
        assertThatThrownBy(() -> jdbc.sql("UPDATE scan_version SET coordinate_frame_id = NULL WHERE id = :v").param("v", v1Id).update())
            .hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM scan_version_artifact WHERE scan_version_id = :v").param("v", v2Id).update())
            .hasMessageContaining("write-once");
        assertThatThrownBy(() -> jdbc.sql("UPDATE navigation_graph SET scan_version_id = :other WHERE id = :g")
            .param("other", v2Id).param("g", v1Graph).update()).hasMessageContaining("belongs to scan_version");
        assertThat(versionsByNumber().get(1)).isEqualTo(v1);
    }

    @Test
    void aVersionThatIsNotFinalizedCannotBeViewedOrRoutedOn() throws Exception {
        JsonNode v1 = version1();
        UUID v1Id = UUID.fromString(v1.get("id").asText());
        String init = post(floorUrl() + "/rescan", c.operator(),
            "{\"parentVersionId\":\"" + v1Id + "\",\"region\":{\"points\":" + REGION_TOUCHING_GRAPH + "}}")
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID capture = UUID.fromString(mapper.readTree(init).get("captureId").asText());
        var u = upload(c, capture, "VIDEO", "walk.mp4", "video/mp4", mp4(4096));
        awaitSettled(c, capture, u.mediaId());
        post(capUrl(c, capture) + "/complete-upload", c.operator(), null).andExpect(status().isOk());
        post(capUrl(c, capture) + "/processing", c.operator(), null).andExpect(status().isAccepted());
        UUID draft = jdbc.sql("SELECT id FROM scan_version WHERE parent_version_id = :p").param("p", v1Id).query(UUID.class).single();

        get("/api/v1/venues/" + c.venue() + "/scan-versions/" + draft + "/reconstruction", viewerToken())
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_NOT_FINALIZED"));
        get("/api/v1/venues/" + c.venue() + "/pois?scanVersionId=" + draft, viewerToken())
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_NOT_FINALIZED"));
        get("/api/v1/venues/" + c.venue() + "/scan-versions/" + draft + "/artifacts/KSPLAT", viewerToken())
            .andExpect(status().isConflict());
        // Another venue's viewer cannot reach it at all.
        Ctx other = ctx();
        get("/api/v1/venues/" + other.venue() + "/scan-versions/" + v1Id + "/reconstruction",
            TestJwt.user(other.org(), "viewer").venues(other.venue()).token()).andExpect(status().isNotFound());
        assertThat(jdbc.sql("SELECT version_number FROM scan_version WHERE id = :v").param("v", draft).query(Integer.class).single())
            .as("the floor's next number, not parent + 1 by coincidence only").isEqualTo(2);
        get("/api/v1/venues/" + c.venue() + "/scan-versions/" + v1Id + "/reconstruction", viewerToken())
            .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("application/json"));
    }
}
