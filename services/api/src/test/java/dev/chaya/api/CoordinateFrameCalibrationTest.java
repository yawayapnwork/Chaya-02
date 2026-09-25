package dev.chaya.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import dev.chaya.api.frame.FrameDtos.FrameView;
import dev.chaya.api.frame.Quaternion;
import dev.chaya.api.frame.Similarity;
import dev.chaya.api.pipeline.PipelineDefinition;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Calibration of a reconstruction's coordinate frame through the real API, against real PostgreSQL and MinIO, with
 * the synthetic MATHEMATICAL fixture (packages/contracts/fixtures/synthetic-calibration.json) standing in for measured
 * references. Nothing here is venue data or a real measurement; it checks the math, the refusals, the versioning and
 * the lifecycle. Skipped, not failed, without Docker.
 */
class CoordinateFrameCalibrationTest extends PipelineTestSupport {

    private String framesUrl(Started s) {
        return "/api/v1/venues/" + s.c().venue() + "/reconstructions/" + s.run() + "/coordinate-frames";
    }

    private String floorFrameUrl(Started s) {
        return "/api/v1/venues/" + s.c().venue() + "/floors/" + s.c().floor() + "/coordinate-frame";
    }

    /** Runs every stage up to and including ARTIFACT_GENERATION; PLANE_FITTING publishes the fixture's gravity estimate
     * when `withGravity` (otherwise a gravity estimate that says NOT_ESTIMATED). */
    private Started reconstructed(boolean withGravity) throws Exception {
        Started s = startRun();
        JsonNode g = calibrationFixture().get("gravity");
        for (var stage : PipelineDefinition.STAGES) {
            JsonNode order = claimExpecting(stage.name());
            if (stage.name().equals("PLANE_FITTING")) {
                String gravity = withGravity
                    ? "{\"status\":\"ESTIMATED\",\"method\":\"RECONSTRUCTED_FLOOR_PLANE\",\"up_reconstruction\":" + g.get("upReconstruction")
                        + ",\"floor_point_reconstruction\":" + g.get("floorPointReconstruction")
                        + ",\"camera_centroid_reconstruction\":" + g.get("cameraCentroidReconstruction") + "}"
                    : "{\"status\":\"NOT_ESTIMATED\",\"reason\":\"fixture\"}";
                send(order, report("SUCCEEDED", List.of(artifact(order, "planes.json", "PLANE_MODEL", false, false, "{\"planes\":[]}"),
                    artifact(order, "gravity-estimate.json", "GRAVITY_ESTIMATE", false, false, gravity)), null, null), svc)
                    .andExpect(status().isOk());
            } else {
                succeed(order);
            }
            if (stage.name().equals("ARTIFACT_GENERATION")) {
                return s;
            }
        }
        throw new AssertionError("unreachable");
    }

    private String distances() throws Exception {
        return mapper.writeValueAsString(Map.of("distanceReferences", calibrationFixture().get("distanceReferences")));
    }

    private static double[] vec(JsonNode n) {
        return new double[]{n.get(0).asDouble(), n.get(1).asDouble(), n.get(2).asDouble()};
    }

    private static Similarity transformOf(JsonNode frame) {
        JsonNode r = frame.get("rotation");
        JsonNode t = frame.get("translation");
        return new Similarity(frame.get("scale").asDouble(), new Quaternion(r.get("w").asDouble(), r.get("x").asDouble(),
            r.get("y").asDouble(), r.get("z").asDouble()), new double[]{t.get("x").asDouble(), t.get("y").asDouble(), t.get("z").asDouble()});
    }

    @Test
    void measuredDistancesAndTheReconstructedFloorGiveAFloorLocalCanonicalFrameThatWorkersReceive() throws Exception {
        Started s = reconstructed(true);
        JsonNode frame = calibrateOk(s, distances());

        assertThat(frame.get("canonical").asBoolean()).isTrue();
        assertThat(frame.get("metricStatus").asText()).isEqualTo("METRIC");
        assertThat(frame.get("gravityStatus").asText()).isEqualTo("ALIGNED");
        assertThat(frame.get("horizontalDatum").asText()).isEqualTo("FLOOR_LOCAL");
        assertThat(frame.get("gravitySource").asText()).isEqualTo("RECONSTRUCTED_FLOOR_PLANE");
        assertThat(frame.get("scale").asDouble()).isCloseTo(calibrationFixture().get("truth").get("scale").asDouble(), within(1e-12));
        assertThat(frame.get("units").asText()).isEqualTo("m");
        assertThat(frame.get("upAxis").asText()).isEqualTo("+Z");

        Similarity t = transformOf(frame);
        JsonNode g = calibrationFixture().get("gravity");
        assertThat(t.apply(vec(g.get("floorPointReconstruction")))[2]).as("the floor is at z = 0").isCloseTo(0, within(1e-9));
        double[] up = t.applyDirection(vec(g.get("upReconstruction")));
        assertThat(up[2]).as("reconstruction up becomes +Z").isCloseTo(1, within(1e-12));
        JsonNode cps = calibrationFixture().get("controlPoints");
        assertThat(t.apply(vec(cps.get(4).get("reconstruction")))[2]).as("heights are metres above the floor").isCloseTo(2.4, within(1e-9));

        // It is the floor's current frame, and the next metric stage's work order carries it.
        get(floorFrameUrl(s), s.c().operator()).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(frame.get("id").asText()));
        JsonNode order = claimExpecting("SEMANTIC_INDEXING");
        assertThat(order.get("coordinateFrame").get("id").asText()).isEqualTo(frame.get("id").asText());
        assertThat(order.get("coordinateFrame").get("metricStatus").asText()).isEqualTo("METRIC");
        assertThat(order.get("coordinateFrame").get("rotation").get("w").isNumber()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE action = 'coordinate_frame.calibrate' AND resource_id = :f")
            .param("f", UUID.fromString(frame.get("id").asText())).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void withoutAGravitySourceTheFrameIsMetricButNotCanonicalAndNeverBecomesTheFloorFrame() throws Exception {
        Started s = reconstructed(false);
        JsonNode frame = calibrateOk(s, distances());
        assertThat(frame.get("metricStatus").asText()).isEqualTo("METRIC");
        assertThat(frame.get("gravityStatus").asText()).isEqualTo("NOT_ALIGNED");
        assertThat(frame.get("canonical").asBoolean()).isFalse();
        assertThat(frame.get("rotation").isNull()).isTrue();
        get(floorFrameUrl(s), s.c().operator()).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_CALIBRATED"));
        // Asking for the reconstructed floor plane explicitly, when there is none, is refused rather than skipped.
        String explicit = mapper.writeValueAsString(Map.of("distanceReferences", calibrationFixture().get("distanceReferences"),
            "gravity", Map.of("source", "RECONSTRUCTED_FLOOR_PLANE")));
        calibrate(s, explicit).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("GRAVITY_UNAVAILABLE"));
    }

    @Test
    void operatorFloorPointsAreAGravitySource() throws Exception {
        Started s = reconstructed(false);
        JsonNode cps = calibrationFixture().get("controlPoints");
        // cp0..cp3 lie on the synthetic floor (venue z = 0); cp4 is 2.4 m above it.
        String body = mapper.writeValueAsString(Map.of("distanceReferences", calibrationFixture().get("distanceReferences"),
            "gravity", Map.of("source", "OPERATOR_FLOOR_POINTS",
                "floorPoints", List.of(cps.get(0).get("reconstruction"), cps.get(1).get("reconstruction"), cps.get(2).get("reconstruction")),
                "pointAboveFloor", cps.get(4).get("reconstruction"))));
        JsonNode frame = calibrateOk(s, body);
        assertThat(frame.get("gravitySource").asText()).isEqualTo("OPERATOR_FLOOR_POINTS");
        Similarity t = transformOf(frame);
        assertThat(t.apply(vec(cps.get(3).get("reconstruction")))[2]).isCloseTo(0, within(1e-9));
        assertThat(t.apply(vec(cps.get(4).get("reconstruction")))[2]).isCloseTo(2.4, within(1e-9));
    }

    @Test
    void metresAreNeverAssumedAndDisagreeingReferencesAreRefused() throws Exception {
        Started s = reconstructed(true);
        JsonNode refs = calibrationFixture().get("distanceReferences");
        calibrate(s, mapper.writeValueAsString(Map.of("distanceReferences", List.of(refs.get(0)))))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CALIBRATION"));
        calibrate(s, "{}").andExpect(status().isBadRequest());

        Map<String, Object> off = mapper.convertValue(refs.get(1), Map.class);
        off.put("measuredMetres", refs.get(1).get("measuredMetres").asDouble() * 1.10); // 10 % off
        calibrate(s, mapper.writeValueAsString(Map.of("distanceReferences", List.of(refs.get(0), off))))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("CALIBRATION_INCONSISTENT"));
        assertThat(jdbc.sql("SELECT count(*) FROM coordinate_frame WHERE source_run_id = :r").param("r", s.run()).query(Integer.class).single())
            .as("a refused calibration stores nothing").isZero();
    }

    @Test
    void controlPointsGiveAVenueRegisteredFrameCheckedAgainstTheReconstructedFloor() throws Exception {
        Started s = reconstructed(true);
        JsonNode frame = calibrateOk(s, controlPointCalibration(0));
        assertThat(frame.get("horizontalDatum").asText()).isEqualTo("VENUE_CONTROL_POINTS");
        assertThat(frame.get("controlPointRmsM").asDouble()).isLessThan(1e-9);
        assertThat(frame.get("gravityDisagreementDeg").asDouble()).isLessThan(1e-6);
        JsonNode truth = calibrationFixture().get("truth");
        assertThat(frame.get("scale").asDouble()).isCloseTo(truth.get("scale").asDouble(), within(1e-12));
        assertThat(frame.get("translation").get("x").asDouble()).isCloseTo(truth.get("translation").get("x").asDouble(), within(1e-9));

        // One control point surveyed 1 m wrong: the points no longer fit one similarity transform.
        JsonNode body = mapper.readTree(controlPointCalibration(0));
        ((com.fasterxml.jackson.databind.node.ArrayNode) body.get("controlPoints").get(2).get("venue")).set(0, 9.0);
        calibrate(s, body.toString()).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("CALIBRATION_INCONSISTENT"));
    }

    @Test
    void recalibratingTheSameReconstructionSupersedesTheFrameAndReprojectsPoisAndAnchorsExactly() throws Exception {
        Started s = reconstructed(true);
        JsonNode v1 = calibrateOk(s, controlPointCalibration(0));
        String manager = TestJwt.user(s.c().org(), "venue-manager").venues(s.c().venue()).token();
        String poiJson = post("/api/v1/venues/" + s.c().venue() + "/pois", manager,
                "{\"floorId\":\"" + s.c().floor() + "\",\"label\":\"Reception\",\"x\":1.0,\"y\":2.0,\"z\":0.0}")
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        JsonNode poi = mapper.readTree(poiJson);
        assertThat(poi.get("coordinateFrameId").asText()).isEqualTo(v1.get("id").asText());
        assertThat(poi.get("frameStatus").asText()).isEqualTo("CURRENT");
        String anchorJson = post("/api/v1/venues/" + s.c().venue() + "/floors/" + s.c().floor() + "/anchors", manager,
                "{\"markerType\":\"QR_CODE\",\"markerIdentifier\":\"door-1\",\"physicalPose\":{\"x\":0,\"y\":0,\"z\":0,\"qx\":0,\"qy\":0,\"qz\":0,\"qw\":1},"
                    + "\"digitalPose\":{\"x\":3,\"y\":4,\"z\":1.5,\"qx\":0,\"qy\":0,\"qz\":0,\"qw\":1}}")
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String anchorId = mapper.readTree(anchorJson).get("id").asText();

        // The same reconstruction, re-surveyed against a venue datum 10 m further west: every canonical x moves by +10.
        JsonNode v2 = calibrateOk(s, controlPointCalibration(10));
        assertThat(v2.get("version").asInt()).isEqualTo(2);
        List<FrameView> versions = mapper.readerForListOf(FrameView.class).readValue(
            get(framesUrl(s), s.c().operator()).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(versions).extracting(FrameView::status).containsExactly("ACTIVE", "SUPERSEDED");

        JsonNode moved = mapper.readTree(get("/api/v1/venues/" + s.c().venue() + "/pois/" + poi.get("id").asText(), manager)
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(moved.get("version").asInt()).as("re-projection appends a POI version").isEqualTo(2);
        assertThat(moved.get("x").asDouble()).isCloseTo(11.0, within(1e-9));
        assertThat(moved.get("y").asDouble()).isCloseTo(2.0, within(1e-9));
        assertThat(moved.get("coordinateFrameId").asText()).isEqualTo(v2.get("id").asText());
        assertThat(moved.get("frameStatus").asText()).isEqualTo("CURRENT");

        JsonNode anchor = mapper.readTree(get("/api/v1/venues/" + s.c().venue() + "/floors/" + s.c().floor() + "/anchors/" + anchorId, manager)
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(anchor.get("digitalPose").get("x").asDouble()).isCloseTo(13.0, within(1e-9));
        assertThat(anchor.get("coordinateFrameId").asText()).isEqualTo(v2.get("id").asText());
    }

    @Test
    void aReconstructionWithoutPoseEstimationHasNoFrameToCalibrate() throws Exception {
        Started s = startRun();
        calibrate(s, controlPointCalibration(0)).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("RECONSTRUCTION_FRAME_UNAVAILABLE"));
    }

    @Test
    void viewersCannotCalibrate() throws Exception {
        Started s = reconstructed(true);
        String viewer = TestJwt.user(s.c().org(), "viewer").venues(s.c().venue()).token();
        post(framesUrl(s), viewer, controlPointCalibration(0)).andExpect(status().isForbidden());
    }
}
