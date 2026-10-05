package dev.chaya.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import dev.chaya.api.ar.AnchorService;
import dev.chaya.api.ar.ArDeviceFrame;
import dev.chaya.api.ar.ArDtos.Anchor;
import dev.chaya.api.ar.ArDtos.AnchorObservation;
import dev.chaya.api.ar.ArDtos.AnchorRequest;
import dev.chaya.api.ar.ArDtos.Pose;
import dev.chaya.api.frame.Quaternion;
import dev.chaya.api.navigation.NavigationDtos.RouteRequest;
import dev.chaya.api.navigation.NavigationDtos.RouteResponse;
import dev.chaya.api.navigation.RouteService;
import dev.chaya.api.pipeline.PipelineDefinition;
import dev.chaya.api.poi.PoiService;
import dev.chaya.api.reconstruction.ReconstructionService;
import dev.chaya.api.rescan.ScanVersionService;
import dev.chaya.api.security.Actor;
import dev.chaya.api.security.Role;
import dev.chaya.api.web.ApiException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * One coherent current scan version per floor (V28; docs/rescan.md, "Publication"; review N-4, V-1, V-2): every run is a
 * DRAFT version from the start, nothing it produces is current until the version is promoted, promotion switches the
 * floor's graphs, POIs, frame and pointer in one transaction, a refused promotion publishes nothing, and readers never mix
 * two versions of one floor. Driven through the real worker protocol with the committed real Recast navmesh fixture.
 */
class ScanVersionPublicationTest extends PipelineTestSupport {

    @Autowired RouteService routes;
    @Autowired ReconstructionService reconstructions;
    @Autowired PoiService pois;
    @Autowired AnchorService anchors;
    @Autowired ScanVersionService versions;

    private static final Pose ORIGIN = new Pose(0, 0, 0, 0, 0, 0, 1);
    private static final Pose SEEN_FROM_DEVICE_ORIGIN;

    static {
        Quaternion q = ArDeviceFrame.DEVICE_TO_CANONICAL_AXES.conjugate();
        SEEN_FROM_DEVICE_ORIGIN = new Pose(0, 0, 0, q.x(), q.y(), q.z(), q.w());
    }

    private Started startRunOn(Ctx c) throws Exception {
        UUID capture = newCapture(c);
        var u = upload(c, capture, "VIDEO", "walk.mp4", "video/mp4", mp4(4096));
        awaitSettled(c, capture, u.mediaId());
        post(capUrl(c, capture) + "/complete-upload", c.operator(), null).andExpect(status().isOk());
        JsonNode n = mapper.readTree(post(capUrl(c, capture) + "/processing", c.operator(), null).andExpect(status().isAccepted())
            .andReturn().getResponse().getContentAsString());
        return new Started(c, capture, UUID.fromString(n.get("scanId").asText()), UUID.fromString(n.get("run").get("id").asText()));
    }

    /** DETECTED_OBJECTS in the worker's shape: one object, in `frameId`, naming the order's run and version. */
    private Map<String, Object> detections(JsonNode order, String frameId, String label, double x, double y) throws Exception {
        List<Double> embedding = new java.util.ArrayList<>();
        for (int i = 0; i < 512; i++) {
            embedding.add(Math.sin(i * 0.017));
        }
        Map<String, Object> doc = Map.of(
            "coordinate_frame", Map.of("id", frameId, "units", "m", "up_axis", "+Z"),
            "embedding_model", "open_clip:ViT-B-32:openai",
            "source", sourceOf(order),
            "objects", List.of(Map.of("label", label, "confidence", 0.9, "position", List.of(x, y, 0.05), "embedding", embedding,
                "localization", Map.of("status", "MULTI_VIEW", "uncertainty_m", 0.1))));
        return artifact(order, "detected-objects.json", "DETECTED_OBJECTS", false, false, mapper.writeValueAsString(doc));
    }

    /**
     * Drives every stage of `s` through `lastStage` (null: all of them), calibrating the run just before SEMANTIC_INDEXING
     * (the first stage whose output is in canonical metres) and reporting a detection named `label` and the real navmesh
     * (NAVIGATION_BAKING, the last stage). `withKsplat` false makes ARTIFACT_GENERATION publish no viewer asset. Returns
     * the frame the run was calibrated in.
     */
    private String drive(Started s, String lastStage, String label, boolean withKsplat) throws Exception {
        String frameId = null;
        for (var stage : PipelineDefinition.STAGES) {
            if (stage.name().equals("SEMANTIC_INDEXING")) {
                frameId = calibrateOk(s, controlPointCalibration(0)).get("id").asText();
            }
            JsonNode order = claimExpecting(stage.name());
            List<Map<String, Object>> outputs = switch (stage.name()) {
                case "NAVIGATION_BAKING" -> navigationOutputs(order, frameId);
                case "SEMANTIC_INDEXING" -> List.of(detections(order, frameId, label, 6.0, 3.0));
                case "ARTIFACT_GENERATION" -> withKsplat ? outputsFor(order)
                    : List.of(artifact(order, "manifest.json", "ARTIFACT_MANIFEST", false, false, "{}"));
                default -> outputsFor(order);
            };
            send(order, report("SUCCEEDED", outputs, null, null), svc).andExpect(status().isOk());
            if (stage.name().equals(lastStage)) {
                break;
            }
        }
        return frameId;
    }

    private Actor viewer(Ctx c) {
        return new Actor(Actor.Kind.USER, "viewer", c.org(), Set.of(c.venue()), Set.of(Role.ADMIN));
    }

    private UUID versionOfRun(UUID run) {
        return jdbc.sql("SELECT scan_version_id FROM pipeline_run WHERE id = :r").param("r", run).query(UUID.class).single();
    }

    private UUID currentVersion(UUID floor) {
        return jdbc.sql("SELECT current_scan_version_id FROM floor WHERE id = :f").param("f", floor).query(UUID.class).optional().orElse(null);
    }

    private UUID floorFrame(UUID floor) {
        return jdbc.sql("SELECT current_coordinate_frame_id FROM floor WHERE id = :f").param("f", floor).query(UUID.class).single();
    }

    private List<String> activeGraphVersions(UUID floor) {
        return jdbc.sql("SELECT scan_version_id::text FROM navigation_graph WHERE floor_id = :f AND status = 'ACTIVE' ORDER BY profile")
            .param("f", floor).query(String.class).list();
    }

    private UUID poiLabelled(UUID venue, String label) {
        return jdbc.sql("SELECT p.id FROM poi p JOIN poi_version v ON v.poi_id = p.id WHERE p.venue_id = :v AND v.label = :l")
            .param("v", venue).param("l", label).query(UUID.class).single();
    }

    private RouteResponse routeTo(Ctx c, UUID poi, UUID version) {
        return routes.route(viewer(c), new RouteRequest(c.venue(), c.floor(), List.of(1.0, 3.0, 0.05), poi, null, null, version));
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    // ---- a run's version is published only when the run succeeds -----------------------------------------------------

    @Test
    void aSucceededRunIsPublishedAsTheFloorsCurrentVersionWithEverythingItProduced() throws Exception {
        var s = startRunOn(ctx());
        UUID version = versionOfRun(s.run());
        assertThat(version).as("every run is a DRAFT version from the start").isNotNull();
        assertThat(jdbc.sql("SELECT status FROM scan_version WHERE id = :v").param("v", version).query(String.class).single())
            .isEqualTo("DRAFT");

        String frame = drive(s, "SEMANTIC_INDEXING", "reception desk", true);
        assertThat(currentVersion(s.c().floor())).as("nothing is current while the run is still processing").isNull();
        assertThat(pois.listAll(viewer(s.c()), s.c().venue())).as("detections become POIs only when published").isEmpty();
        assertThatThrownBy(() -> reconstructions.latestForFloor(viewer(s.c()), s.c().venue(), s.c().floor()))
            .isInstanceOf(dev.chaya.api.web.NotFoundException.class);
        assertThat(reconstructions.listForFloor(viewer(s.c()), s.c().venue(), s.c().floor())).isEmpty();

        JsonNode last = claimExpecting("NAVIGATION_BAKING");
        send(last, report("SUCCEEDED", navigationOutputs(last, frame), null, null), svc).andExpect(status().isOk());

        assertThat(processing(s).get("run").get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(currentVersion(s.c().floor())).isEqualTo(version);
        assertThat(floorFrame(s.c().floor())).isEqualTo(UUID.fromString(frame));
        assertThat(activeGraphVersions(s.c().floor())).containsExactly(version.toString(), version.toString());
        assertThat(reconstructions.latestForFloor(viewer(s.c()), s.c().venue(), s.c().floor()).scanVersionId()).isEqualTo(version);
        assertThat(reconstructions.listForFloor(viewer(s.c()), s.c().venue(), s.c().floor()))
            .singleElement().satisfies(v -> assertThat(v.current()).isTrue());
        assertThat(pois.list(viewer(s.c()), s.c().venue())).singleElement().satisfies(p -> {
            assertThat(p.label()).isEqualTo("reception desk");
            assertThat(p.scanVersionId()).isEqualTo(version);
            assertThat(p.current()).isTrue();
        });
        UUID desk = poiLabelled(s.c().venue(), "reception desk");
        assertThat(routeTo(s.c(), desk, null).routingSources()).allSatisfy(src -> {
            assertThat(src.scanVersionId()).isEqualTo(version);
            // what an AR client checks before drawing a leg with its transform (apps/web/lib/ar-navigation.ts routeLegFor)
            assertThat(src.coordinateFrameId()).isEqualTo(UUID.fromString(frame));
        });
        assertThat(auditCount(s.c().org(), "scan_version.promoted")).isEqualTo(1);
    }

    // ---- old artifacts vs a new scan ---------------------------------------------------------------------------------

    @Test
    void aNewScanThatFailsLeavesThePublishedVersionUntouched() throws Exception {
        Ctx c = ctx();
        var first = startRunOn(c);
        String frame1 = drive(first, null, "reception desk", true);
        UUID v1 = versionOfRun(first.run());
        UUID desk = poiLabelled(c.venue(), "reception desk");
        assertThat(currentVersion(c.floor())).isEqualTo(v1);

        var second = startRunOn(c);
        UUID v2 = versionOfRun(second.run());
        String frame2 = drive(second, "SEMANTIC_INDEXING", "vending machine", true);
        // Mid-run: the new reconstruction was calibrated and its objects detected, yet the floor still publishes v1 entirely.
        assertThat(frame2).isNotEqualTo(frame1);
        assertThat(floorFrame(c.floor())).as("calibrating an unpublished reconstruction never moves the floor's frame")
            .isEqualTo(UUID.fromString(frame1));
        assertThat(currentVersion(c.floor())).isEqualTo(v1);
        assertThat(activeGraphVersions(c.floor())).containsOnly(v1.toString());
        assertThat(pois.listAll(viewer(c), c.venue())).extracting(PoiService.Poi::label).containsExactly("reception desk");
        assertThat(reconstructions.latestForFloor(viewer(c), c.venue(), c.floor()).scanVersionId()).isEqualTo(v1);
        assertThat(reconstructions.listForFloor(viewer(c), c.venue(), c.floor())).extracting(v -> v.scanVersionId()).containsExactly(v1);
        assertThat(routeTo(c, desk, null).routingSources()).allSatisfy(src -> assertThat(src.scanVersionId()).isEqualTo(v1));

        // NAVIGATION_BAKING fails: the run fails, and nothing of it was ever live.
        JsonNode last = claimExpecting("NAVIGATION_BAKING");
        send(last, report("FAILED", List.of(), "STAGE_CRASHED", "the stage crashed"), svc).andExpect(status().isOk());
        assertThat(processing(second).get("run").get("status").asText()).isEqualTo("FAILED");
        assertThat(currentVersion(c.floor())).isEqualTo(v1);
        assertThat(activeGraphVersions(c.floor())).containsOnly(v1.toString());
        assertThat(floorFrame(c.floor())).isEqualTo(UUID.fromString(frame1));
        assertThat(jdbc.sql("SELECT status FROM scan_version WHERE id = :v").param("v", v2).query(String.class).single()).isEqualTo("DRAFT");
        assertThat(pois.list(viewer(c), c.venue())).extracting(PoiService.Poi::label).containsExactly("reception desk");
        assertThat(routeTo(c, desk, null).routingSources()).allSatisfy(src -> assertThat(src.scanVersionId()).isEqualTo(v1));
    }

    // ---- a refused promotion publishes nothing -------------------------------------------------------------------------

    @Test
    void aRunWhosePromotionIsRefusedFailsAndPublishesNothing() throws Exception {
        Ctx c = ctx();
        var first = startRunOn(c);
        String frame1 = drive(first, null, "reception desk", true);
        UUID v1 = versionOfRun(first.run());

        // Every stage succeeds, but ARTIFACT_GENERATION published no viewer asset: the version cannot be finalized.
        var second = startRunOn(c);
        UUID v2 = versionOfRun(second.run());
        drive(second, null, "vending machine", false);

        JsonNode run = processing(second).get("run");
        assertThat(run.get("status").asText()).isEqualTo("FAILED");
        assertThat(jdbc.sql("SELECT failure_code FROM pipeline_run WHERE id = :r").param("r", second.run()).query(String.class).single())
            .isEqualTo("VERSION_INCOMPLETE");
        assertThat(captureStatus(second)).isNotEqualTo("COMPLETED");
        // Nothing of the refused promotion survived its savepoint: no pins, no POIs, no live graph, no frame change.
        assertThat(jdbc.sql("SELECT status FROM scan_version WHERE id = :v").param("v", v2).query(String.class).single()).isEqualTo("DRAFT");
        assertThat(jdbc.sql("SELECT count(*) FROM scan_version_artifact WHERE scan_version_id = :v").param("v", v2)
            .query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM poi_version WHERE label = 'vending machine' AND venue_id = :v").param("v", c.venue())
            .query(Integer.class).single()).isZero();
        assertThat(currentVersion(c.floor())).isEqualTo(v1);
        assertThat(activeGraphVersions(c.floor())).containsOnly(v1.toString());
        assertThat(floorFrame(c.floor())).isEqualTo(UUID.fromString(frame1));
        assertThat(auditCount(c.org(), "scan_version.promotion_refused")).isEqualTo(1);
        // Its navigation graphs were ingested from the last report, and stayed DRAFT (review N-4).
        assertThat(jdbc.sql("SELECT count(*) FROM navigation_graph WHERE scan_version_id = :v AND status = 'DRAFT'")
            .param("v", v2).query(Integer.class).single()).isEqualTo(2);
    }

    // ---- a successful promotion switches everything at once, and nothing mixes versions ------------------------------

    @Test
    void aSuccessfulPromotionSwitchesTheWholeFloorAndReadersNeverMixVersions() throws Exception {
        Ctx c = ctx();
        var first = startRunOn(c);
        drive(first, null, "reception desk", true);
        UUID v1 = versionOfRun(first.run());
        UUID desk = poiLabelled(c.venue(), "reception desk");
        Anchor anchor = anchors.calibrate(viewer(c), c.venue(), c.floor(), anchors.create(viewer(c), c.venue(), c.floor(),
            new AnchorRequest("ARUCO_MARKER", "entrance", null, ORIGIN, new Pose(2, 2, 0, 0, 0, 0, 1))).id());
        assertThat(anchor.scanVersionId()).isEqualTo(v1);

        var second = startRunOn(c);
        String frame2 = drive(second, null, "vending machine", true);
        UUID v2 = versionOfRun(second.run());

        assertThat(currentVersion(c.floor())).isEqualTo(v2);
        assertThat(floorFrame(c.floor())).isEqualTo(UUID.fromString(frame2));
        assertThat(activeGraphVersions(c.floor())).containsExactly(v2.toString(), v2.toString());
        assertThat(jdbc.sql("SELECT count(*) FROM navigation_graph WHERE scan_version_id = :v AND status = 'RETIRED'")
            .param("v", v1).query(Integer.class).single()).isEqualTo(2);
        assertThat(reconstructions.latestForFloor(viewer(c), c.venue(), c.floor()).scanVersionId()).isEqualTo(v2);
        assertThat(reconstructions.listForFloor(viewer(c), c.venue(), c.floor()))
            .extracting(v -> v.scanVersionId() + ":" + v.current()).containsExactly(v2 + ":true", v1 + ":false");

        // POIs: the current view is v2's; v1's detection is no longer current, but still part of v1.
        assertThat(pois.list(viewer(c), c.venue())).extracting(PoiService.Poi::label).containsExactly("vending machine");
        assertThat(pois.list(viewer(c), c.venue(), v1)).extracting(PoiService.Poi::label).containsExactly("reception desk");
        assertThat(pois.listAll(viewer(c), c.venue())).extracting(p -> p.label() + ":" + p.current())
            .containsExactlyInAnyOrder("reception desk:false", "vending machine:true");

        // Routes: never v2's graph to v1's POI; v1's own route is still served on v1's graph.
        assertCode(() -> routeTo(c, desk, null), ScanVersionService.VERSION_MISMATCH);
        assertThat(routeTo(c, desk, v1).routingSources()).allSatisfy(src -> assertThat(src.scanVersionId()).isEqualTo(v1));
        UUID machine = poiLabelled(c.venue(), "vending machine");
        assertThat(routeTo(c, machine, null).routingSources()).allSatisfy(src -> assertThat(src.scanVersionId()).isEqualTo(v2));
        assertThatThrownBy(() -> routeTo(c, machine, v1)).as("v2's POI is not part of v1")
            .isInstanceOf(dev.chaya.api.web.NotFoundException.class);

        // Anchors: an anchor of the replaced reconstruction is never used to place a device in the new one.
        assertThat(anchors.get(viewer(c), c.venue(), c.floor(), anchor.id()).calibrationStatus()).isEqualTo("STALE");
        assertCode(() -> anchors.relocalize(viewer(c), c.venue(), c.floor(),
            List.of(new AnchorObservation(anchor.id(), SEEN_FROM_DEVICE_ORIGIN))), "ANCHOR_NOT_CALIBRATED");
        assertCode(() -> anchors.calibrate(viewer(c), c.venue(), c.floor(), anchor.id()), "ANCHOR_FRAME_STALE");
    }

    // ---- versioned anchor poses (review V-1) -----------------------------------------------------------------------------

    @Test
    void anAnchorAsOfItsVersionKeepsItsOriginalPoseAfterARecalibration() throws Exception {
        Ctx c = ctx();
        var s = startRunOn(c);
        String frame1 = drive(s, null, "reception desk", true);
        UUID v1 = versionOfRun(s.run());
        Anchor placed = anchors.calibrate(viewer(c), c.venue(), c.floor(), anchors.create(viewer(c), c.venue(), c.floor(),
            new AnchorRequest("ARUCO_MARKER", "entrance", null, ORIGIN, new Pose(2, 2, 0, 0, 0, 0, 1))).id());
        assertThat(placed.poseRevision()).isEqualTo(1);
        assertThat(placed.coordinateFrameId()).isEqualTo(UUID.fromString(frame1));

        // The same reconstruction is recalibrated 1 m along x: the anchor is re-projected as a new pose revision.
        String frame1b = calibrateOk(s, controlPointCalibration(1.0)).get("id").asText();
        Anchor now = anchors.get(viewer(c), c.venue(), c.floor(), placed.id());
        assertThat(now.coordinateFrameId()).isEqualTo(UUID.fromString(frame1b));
        assertThat(now.poseRevision()).isEqualTo(2);
        assertThat(now.scanVersionId()).as("still entered against the same version").isEqualTo(v1);
        assertThat(now.calibrationStatus()).isEqualTo("CALIBRATED");
        assertThat(now.digitalPose().x()).isCloseTo(3.0, within(1e-6));

        Anchor asOfV1 = anchors.list(viewer(c), c.venue(), c.floor(), v1).get(0);
        assertThat(asOfV1.poseId()).isEqualTo(placed.poseId());
        assertThat(asOfV1.coordinateFrameId()).as("v1 recorded frame " + frame1).isEqualTo(UUID.fromString(frame1));
        assertThat(asOfV1.digitalPose().x()).as("the anchor as of v1 keeps its original pose").isCloseTo(2.0, within(1e-9));
        assertThat(asOfV1.calibrationStatus()).isEqualTo("CALIBRATED");

        // Pose history is immutable.
        assertThatThrownBy(() -> jdbc.sql("UPDATE ar_anchor_pose SET digital_x = 9 WHERE id = :p").param("p", placed.poseId()).update())
            .hasMessageContaining("immutable");
        // And a head that disagrees with its pose is refused at commit.
        assertThatThrownBy(() -> jdbc.sql("UPDATE ar_anchor SET digital_x = 9 WHERE id = :a").param("a", placed.id()).update())
            .hasMessageContaining("does not match its current versioned pose");

        // Relocalization is solved in the published version; a client showing another version is refused.
        var solved = anchors.relocalize(viewer(c), c.venue(), c.floor(), List.of(new AnchorObservation(placed.id(), SEEN_FROM_DEVICE_ORIGIN)), v1);
        assertThat(solved.scanVersionId()).isEqualTo(v1);
        assertCode(() -> anchors.relocalize(viewer(c), c.venue(), c.floor(),
            List.of(new AnchorObservation(placed.id(), SEEN_FROM_DEVICE_ORIGIN)), UUID.randomUUID()), ScanVersionService.VERSION_MISMATCH);
    }

    // ---- version mismatch: artifacts must name the run's own version ---------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void aFullRunsVersionScopedArtifactMustNameItsVersion() throws Exception {
        var s = startRunOn(ctx());
        String frame = drive(s, "SEMANTIC_INDEXING", "x", true);
        JsonNode order = claimExpecting("NAVIGATION_BAKING");
        List<Map<String, Object>> outputs = navigationOutputs(order, frame);
        // The same graph, naming no version (the worker's shape before V28) and naming another version.
        for (Object claimed : java.util.Arrays.asList(null, UUID.randomUUID().toString())) {
            Map<String, Object> graph = mapper.readValue(derived.open((String) outputs.get(2).get("key")), Map.class);
            ((Map<String, Object>) graph.get("source")).put("scan_version_id", claimed);
            var renamed = artifact(order, "graph-" + claimed + ".json", "NAVIGATION_GRAPH", false, false, mapper.writeValueAsString(graph));
            send(order, report("SUCCEEDED", List.of(outputs.get(0), outputs.get(1), renamed), null, null), svc)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ARTIFACT_VERSION_MISMATCH"));
        }
        send(order, report("SUCCEEDED", outputs, null, null), svc).andExpect(status().isOk());
    }

    // ---- the database refuses mixed or version-less publication --------------------------------------------------------

    @Test
    void theDatabaseRefusesAMixedOrVersionlessPublication() {
        var t = fx.tree();
        fx.calibratedFloor(t.org(), t.venue(), t.floor(), "FLOOR_LOCAL");
        UUID v1 = fx.publishCurrentReconstruction(t.org(), t.venue(), t.floor());

        UUID stray = jdbc.sql("INSERT INTO navigation_graph (organization_id, venue_id, floor_id, profile, status, coordinate_frame_id) "
                + "VALUES (:o, :v, :f, 'STEP_FREE', 'DRAFT', (SELECT current_coordinate_frame_id FROM floor WHERE id = :f)) RETURNING id")
            .param("o", t.org()).param("v", t.venue()).param("f", t.floor()).query(UUID.class).single();
        assertThatThrownBy(() -> jdbc.sql("UPDATE navigation_graph SET status = 'ACTIVE' WHERE id = :g").param("g", stray).update())
            .as("a version-less graph is never live").hasMessageContaining("is not its current scan_version");
        assertThatThrownBy(() -> jdbc.sql("UPDATE floor SET current_scan_version_id = :v WHERE id = :f")
                .param("v", t.version()).param("f", t.floor()).update())
            .as("a DRAFT version is never current").hasMessageContaining("only a FINALIZED version");
        assertThatThrownBy(() -> jdbc.sql("UPDATE floor SET current_scan_version_id = NULL WHERE id = :f").param("f", t.floor()).update())
            .hasMessageContaining("never unpublished");
        UUID otherReconstruction = fx.calibratedFloor(t.org(), t.venue(), t.floor(), "FLOOR_LOCAL");
        assertThatThrownBy(() -> jdbc.sql("UPDATE floor SET current_coordinate_frame_id = :c WHERE id = :f")
                .param("c", otherReconstruction).param("f", t.floor()).update())
            .as("another reconstruction's frame never becomes the floor's under the published version")
            .hasMessageContaining("is not a calibration of its current scan_version");
        UUID poi = jdbc.sql("INSERT INTO poi (organization_id, venue_id, floor_id) VALUES (:o, :v, :f) RETURNING id")
            .param("o", t.org()).param("v", t.venue()).param("f", t.floor()).query(UUID.class).single();
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO poi_version (organization_id, venue_id, poi_id, version_number, label, tags, x, y, z, source,
                    pipeline_run_id, created_by)
                VALUES (:o, :v, :p, 1, 'chair', '{}', 0, 0, 0, 'AUTO_DETECTED', (SELECT pipeline_run_id FROM scan_version WHERE id = :sv),
                    'test')""")
                .param("o", t.org()).param("v", t.venue()).param("p", poi).param("sv", v1).update())
            .as("a detection always names its version").hasMessageContaining("poi_version_detected_has_version");
        assertThat(versions.currentOf(t.floor())).contains(v1);
    }

    @Test
    void aRescanOfAnOlderVersionIsNeverPublishedOverANewerOne() {
        var t = fx.tree();
        fx.calibratedFloor(t.org(), t.venue(), t.floor(), "FLOOR_LOCAL");
        UUID v1 = fx.publishCurrentReconstruction(t.org(), t.venue(), t.floor());
        UUID v2 = fx.publishNewReconstruction(t.org(), t.venue(), t.floor());
        UUID rescanOfV1 = jdbc.sql("""
                INSERT INTO scan_version (organization_id, venue_id, scan_id, floor_id, version_number, parent_version_id)
                VALUES (:o, :v, :s, :f, 99, :p) RETURNING id""")
            .param("o", t.org()).param("v", t.venue()).param("s", t.scan()).param("f", t.floor()).param("p", v1)
            .query(UUID.class).single();
        assertCode(() -> versions.promote(rescanOfV1, Map.of()), "PARENT_NOT_CURRENT");
        assertThat(versions.currentOf(t.floor())).contains(v2);
    }
}
