package dev.chaya.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import dev.chaya.api.pipeline.PipelineDefinition;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Capture-time calibration evidence (docs/capture-calibration.md) through the real API, against real PostgreSQL and MinIO.
 *
 * <p>SYNTHETIC DATA. The media are 16x16 generated PNGs and an MP4-signature container fixture; the pixel observations are
 * arbitrary positions inside them; the measured values and reconstruction coordinates come from the mathematical fixture
 * packages/contracts/fixtures/synthetic-calibration.json, and the "reconstruction" is a pipeline driven by this test as a
 * worker would. Nothing here is a real indoor capture or a real measurement: it checks persistence, validation, isolation,
 * lifecycle and that only a calibration produces a metric frame.
 */
class CaptureCalibrationTest extends PipelineTestSupport {

    record Cap(Ctx c, UUID capture, UUID video, UUID img1, UUID img2) {
        String url() {
            return "/api/v1/venues/" + c.venue() + "/captures/" + capture;
        }
    }

    /** A capture with one accepted video and two accepted 16x16 images, still UPLOADING. */
    private Cap capture() throws Exception {
        var c = ctx();
        UUID capture = newCapture(c);
        var v = upload(c, capture, "VIDEO", "walk.mp4", "video/mp4", mp4(4096));
        var a = upload(c, capture, "IMAGE", "door-1.png", "image/png", png(11));
        var b = upload(c, capture, "IMAGE", "door-2.png", "image/png", png(12));
        for (var u : List.of(v, a, b)) {
            assertThat(field(awaitSettled(c, capture, u.mediaId()), "status")).isEqualTo("ACCEPTED");
        }
        return new Cap(c, capture, v.mediaId(), a.mediaId(), b.mediaId());
    }

    private static Map<String, Object> obs(String point, UUID media, Double time, double u, double v) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("point", point);
        o.put("mediaId", media);
        o.put("frameTimeSeconds", time);
        o.put("u", u);
        o.put("v", v);
        return o;
    }

    /** A and B each seen in both images and in one video frame. */
    private static List<Map<String, Object>> distanceObservations(Cap k) {
        return List.of(obs("A", k.img1(), null, 2, 3), obs("B", k.img1(), null, 13, 4), obs("A", k.img2(), null, 3, 3),
            obs("B", k.img2(), null, 14, 5), obs("A", k.video(), 1.5, 40, 50), obs("B", k.video(), 1.5, 400, 52));
    }

    private static List<Map<String, Object>> pointObservations(Cap k) {
        return List.of(obs("P", k.img1(), null, 7, 8), obs("P", k.img2(), null, 6, 9));
    }

    private Map<String, Object> distance(Cap k, String label, Double value, String unit) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", "DISTANCE");
        m.put("label", label);
        m.put("method", "TAPE");
        m.put("unit", unit);
        m.put("value", value);
        m.put("observations", distanceObservations(k));
        return m;
    }

    private Map<String, Object> controlPoint(Cap k, String label, List<Double> venue, String unit, String datum) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", "CONTROL_POINT");
        m.put("label", label);
        m.put("method", "TOTAL_STATION");
        m.put("unit", unit);
        m.put("datum", datum);
        m.put("venue", venue);
        m.put("observations", pointObservations(k));
        return m;
    }

    private org.springframework.test.web.servlet.ResultActions record(Cap k, String token, Map<String, Object> body) throws Exception {
        return post(k.url() + "/measurements", token, mapper.writeValueAsString(body));
    }

    private JsonNode recordOk(Cap k, Map<String, Object> body) throws Exception {
        return mapper.readTree(record(k, k.c().operator(), body).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode calibration(Cap k) throws Exception {
        return mapper.readTree(get(k.url() + "/calibration", k.c().operator()).andExpect(status().isOk()).andReturn().getResponse()
            .getContentAsString());
    }

    private org.springframework.test.web.servlet.ResultActions calibrateCapture(Cap k, Object body) throws Exception {
        return post(k.url() + "/calibration", k.c().operator(), mapper.writeValueAsString(body));
    }

    private static Map<String, Object> resolved(JsonNode measurement, String point, JsonNode reconstruction) {
        return Map.of("measurementId", measurement.get("id").asText(), "point", point, "reconstruction", reconstruction);
    }

    /** Finishes the upload, starts processing and plays the worker up to ARTIFACT_GENERATION; PLANE_FITTING publishes the
     * fixture's gravity estimate. Returns the run. */
    private UUID reconstruct(Cap k) throws Exception {
        post(k.url() + "/complete-upload", k.c().operator(), null).andExpect(status().isOk());
        JsonNode started = mapper.readTree(post(k.url() + "/processing", k.c().operator(), null).andExpect(status().isAccepted())
            .andReturn().getResponse().getContentAsString());
        JsonNode g = calibrationFixture().get("gravity");
        for (var stage : PipelineDefinition.STAGES) {
            JsonNode order = claimExpecting(stage.name());
            if (stage.name().equals("PLANE_FITTING")) {
                String gravity = "{\"status\":\"ESTIMATED\",\"method\":\"RECONSTRUCTED_FLOOR_PLANE\",\"up_reconstruction\":"
                    + g.get("upReconstruction") + ",\"floor_point_reconstruction\":" + g.get("floorPointReconstruction")
                    + ",\"camera_centroid_reconstruction\":" + g.get("cameraCentroidReconstruction") + "}";
                send(order, report("SUCCEEDED", List.of(artifact(order, "planes.json", "PLANE_MODEL", false, false, "{\"planes\":[]}"),
                    artifact(order, "gravity-estimate.json", "GRAVITY_ESTIMATE", false, false, gravity)), null, null), svc)
                    .andExpect(status().isOk());
            } else {
                succeed(order);
            }
            if (stage.name().equals("ARTIFACT_GENERATION")) {
                return UUID.fromString(started.get("run").get("id").asText());
            }
        }
        throw new AssertionError("unreachable");
    }

    private int count(String table, UUID capture) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE capture_session_id = :c").param("c", capture).query(Integer.class).single();
    }

    // ---- valid evidence, end to end ------------------------------------------------------------------------------------

    @Test
    void measuredDistancesAreKeptWithTheCaptureAndOnlyACalibrationMakesTheReconstructionMetric() throws Exception {
        Cap k = capture();
        JsonNode s = calibration(k);
        assertThat(s.get("state").asText()).isEqualTo("NO_EVIDENCE");
        assertThat(s.get("captureStage").asText()).isEqualTo("INCOMPLETE_CAPTURE");
        assertThat(s.get("reconstructionFrame").asText()).isEqualTo("NO_RECONSTRUCTION");
        assertThat(s.get("requirements").toString()).contains("at least 2 measured distances");
        // The server read the images' own size, which bounds-checks observations.
        get(k.url() + "/media/" + k.img1(), k.c().operator()).andExpect(jsonPath("$.pixelWidth").value(16))
            .andExpect(jsonPath("$.pixelHeight").value(16));
        get(k.url() + "/media/" + k.video(), k.c().operator()).andExpect(jsonPath("$.pixelWidth").isEmpty());

        JsonNode refs = calibrationFixture().get("distanceReferences");
        // Entered in centimetres, as measured; kept verbatim and normalised to metres.
        JsonNode wall = recordOk(k, distance(k, refs.get(0).get("label").asText(), refs.get(0).get("measuredMetres").asDouble() * 100, "cm"));
        assertThat(wall.get("unit").asText()).isEqualTo("cm");
        assertThat(wall.get("value").asDouble()).isEqualTo(800.0);
        assertThat(wall.get("measuredMetres").asDouble()).isCloseTo(8.0, within(1e-12));
        assertThat(wall.get("floorId").asText()).isEqualTo(k.c().floor().toString());
        assertThat(wall.get("observations")).hasSize(6);
        assertThat(calibration(k).get("state").asText()).isEqualTo("EVIDENCE_INCOMPLETE");

        JsonNode jamb = recordOk(k, distance(k, refs.get(1).get("label").asText(), refs.get(1).get("measuredMetres").asDouble(), "m"));
        assertThat(calibration(k).get("state").asText()).isEqualTo("AWAITING_RECONSTRUCTION");

        UUID run = reconstruct(k);
        s = calibration(k);
        assertThat(s.get("state").asText()).isEqualTo("READY_TO_CALIBRATE");
        assertThat(s.get("reconstructionFrame").asText()).as("evidence alone is not a metric frame").isEqualTo("ARBITRARY_SCALE");
        assertThat(s.get("activeFrame").isNull()).isTrue();
        assertThat(s.get("requirements").toString()).contains("not implemented").contains("wall A (A)").contains("door jamb (B)");
        // The next metric stage gets no frame: processing cannot proceed as metric on recorded evidence alone.
        JsonNode order = claimExpecting("SEMANTIC_INDEXING");
        assertThat(order.get("coordinateFrame").isNull()).isTrue();
        send(order, report("FAILED", List.of(), "NOT_CALIBRATED", "no canonical frame"), svc).andExpect(status().isOk());

        // Calibrate: the operator gives the reconstruction coordinates of the measured points; the lengths come from the
        // recorded measurements, not from this request.
        List<Map<String, Object>> points = List.of(resolved(wall, "A", refs.get(0).get("a")), resolved(wall, "B", refs.get(0).get("b")),
            resolved(jamb, "A", refs.get(1).get("a")), resolved(jamb, "B", refs.get(1).get("b")));
        s = mapper.readTree(calibrateCapture(k, Map.of("resolvedPoints", points)).andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString());
        assertThat(s.get("state").asText()).isEqualTo("CALIBRATED");
        assertThat(s.get("reconstructionFrame").asText()).isEqualTo("CANONICAL");
        JsonNode frame = s.get("activeFrame");
        assertThat(frame.get("scale").asDouble()).isCloseTo(calibrationFixture().get("truth").get("scale").asDouble(), within(1e-12));
        assertThat(frame.get("scaleSource").asText()).isEqualTo("MEASURED_DISTANCES");
        assertThat(frame.get("sourceRunId").asText()).isEqualTo(run.toString());
        assertThat(frame.get("floorId").asText()).isEqualTo(k.c().floor().toString());
        assertThat(s.get("lastAttempt").get("outcome").asText()).isEqualTo("ACCEPTED");
        assertThat(s.get("requirements")).isEmpty();

        // Persisted links: frame <- measurements, and the floor's current frame.
        UUID frameId = UUID.fromString(frame.get("id").asText());
        assertThat(jdbc.sql("SELECT count(*) FROM coordinate_frame_measurement WHERE coordinate_frame_id = :f").param("f", frameId)
            .query(Integer.class).single()).isEqualTo(2);
        get("/api/v1/venues/" + k.c().venue() + "/floors/" + k.c().floor() + "/coordinate-frame", k.c().operator())
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(frameId.toString()));
        JsonNode list = mapper.readTree(get(k.url() + "/measurements", k.c().operator()).andReturn().getResponse().getContentAsString());
        assertThat(list).allMatch(m -> m.get("usedByActiveFrame").asBoolean());
        // Evidence an active frame rests on cannot be withdrawn under it.
        post(k.url() + "/measurements/" + wall.get("id").asText() + "/withdraw", k.c().operator(), "{\"reason\":\"re-measure\"}")
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MEASUREMENT_IN_USE"));
        assertThat(auditCount(k.c().org(), "capture.measurement.record")).isEqualTo(2);
        assertThat(auditCount(k.c().org(), "capture.calibrate")).isEqualTo(1);
    }

    @Test
    void surveyedControlPointsInAnyUnitRegisterTheCaptureToTheVenueDatum() throws Exception {
        Cap k = capture();
        List<JsonNode> cps = new ArrayList<>();
        for (JsonNode cp : calibrationFixture().get("controlPoints")) {
            List<Double> mm = List.of(cp.get("venue").get(0).asDouble() * 1000, cp.get("venue").get(1).asDouble() * 1000,
                cp.get("venue").get(2).asDouble() * 1000);
            cps.add(recordOk(k, controlPoint(k, cp.get("label").asText(), mm, "mm", "Building survey 2026")));
        }
        assertThat(cps.get(1).get("venueMetres").get(0).asDouble()).isCloseTo(8.0, within(1e-12));
        assertThat(calibration(k).get("state").asText()).isEqualTo("AWAITING_RECONSTRUCTION");
        reconstruct(k);
        List<Map<String, Object>> points = new ArrayList<>();
        for (int i = 0; i < cps.size(); i++) {
            points.add(resolved(cps.get(i), "P", calibrationFixture().get("controlPoints").get(i).get("reconstruction")));
        }
        JsonNode s = mapper.readTree(calibrateCapture(k, Map.of("resolvedPoints", points)).andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString());
        assertThat(s.get("state").asText()).isEqualTo("CALIBRATED");
        assertThat(s.get("activeFrame").get("horizontalDatum").asText()).isEqualTo("VENUE_CONTROL_POINTS");
        assertThat(s.get("activeFrame").get("controlPointRmsM").asDouble()).isLessThan(1e-9);
        assertThat(s.get("activeFrame").get("scale").asDouble()).isCloseTo(0.37, within(1e-9));
    }

    // ---- invalid evidence ----------------------------------------------------------------------------------------------

    @Test
    void missingInconsistentDegenerateAndPhysicallyInvalidMeasurementsAreRefusedAndNothingIsStored() throws Exception {
        Cap k = capture();
        // A second capture's media cannot be observed in this one.
        Cap other = capture();
        String op = k.c().operator();

        Map<String, Map<String, Object>> cases = new LinkedHashMap<>();
        Map<String, Object> d;
        d = distance(k, "no unit", 2.0, null);
        cases.put("MISSING_UNIT", d);
        cases.put("INVALID_UNIT", distance(k, "furlongs", 2.0, "furlong"));
        cases.put("MISSING_MEASUREMENT", distance(k, "no value", null, "m"));
        cases.put("INVALID_MEASUREMENT", distance(k, "negative", -2.0, "m"));
        cases.put("MEASUREMENT_OUT_OF_RANGE", distance(k, "metres for millimetres", 2100.0, "m"));
        d = distance(k, "too vague", 1.0, "m");
        d.put("uncertainty", 10.0);
        d.put("unit", "cm");
        d.put("value", 100.0);
        cases.put("MEASUREMENT_TOO_UNCERTAIN", d);
        d = distance(k, "with coordinates", 2.0, "m");
        d.put("venue", List.of(1.0, 2.0, 3.0));
        cases.put("INCONSISTENT_MEASUREMENT", d);
        cases.put("MISSING_DATUM", controlPoint(k, "cp no datum", List.of(1.0, 2.0, 0.0), "m", null));
        d = distance(k, "same pixel", 2.0, "m");
        d.put("observations", List.of(obs("A", k.img1(), null, 5, 5), obs("B", k.img1(), null, 5, 5), obs("A", k.img2(), null, 3, 3),
            obs("B", k.img2(), null, 9, 3)));
        cases.put("DEGENERATE_GEOMETRY", d);
        d = distance(k, "one view", 2.0, "m");
        d.put("observations", List.of(obs("A", k.img1(), null, 1, 1), obs("B", k.img1(), null, 9, 9), obs("A", k.img2(), null, 1, 1)));
        cases.put("INSUFFICIENT_OBSERVATIONS", d);
        d = distance(k, "no observations", 2.0, "m");
        d.put("observations", List.of());
        cases.put("INSUFFICIENT_OBSERVATIONS ", d);

        for (var e : cases.entrySet()) {
            record(k, op, e.getValue()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(e.getKey().strip()));
        }

        Map<String, List<Map<String, Object>>> badObservations = new LinkedHashMap<>();
        badObservations.put("outside the 16x16 image", List.of(obs("P", k.img1(), null, 16, 2), obs("P", k.img2(), null, 1, 1)));
        badObservations.put("video without a frame time", List.of(obs("P", k.video(), null, 1, 1), obs("P", k.img2(), null, 1, 1)));
        badObservations.put("image with a frame time", List.of(obs("P", k.img1(), 2.0, 1, 1), obs("P", k.img2(), null, 1, 1)));
        badObservations.put("same view twice", List.of(obs("P", k.img1(), null, 1, 1), obs("P", k.img1(), null, 2, 2)));
        badObservations.put("wrong point name", List.of(obs("A", k.img1(), null, 1, 1), obs("A", k.img2(), null, 2, 2)));
        for (var e : badObservations.entrySet()) {
            Map<String, Object> cp = controlPoint(k, e.getKey(), List.of(1.0, 2.0, 0.0), "m", "survey");
            cp.put("observations", e.getValue());
            record(k, op, cp).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_OBSERVATION"));
        }
        Map<String, Object> foreign = controlPoint(k, "foreign media", List.of(1.0, 2.0, 0.0), "m", "survey");
        foreign.put("observations", List.of(obs("P", other.img1(), null, 1, 1), obs("P", k.img2(), null, 1, 1)));
        record(k, op, foreign).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("MEDIA_NOT_OBSERVABLE"));

        // Control points of one capture are one survey: one unit, one datum, distinct points.
        recordOk(k, controlPoint(k, "CP-1", List.of(0.0, 0.0, 0.0), "m", "survey"));
        record(k, op, controlPoint(k, "CP-2", List.of(8000.0, 0.0, 0.0), "mm", "survey"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INCONSISTENT_UNITS"));
        record(k, op, controlPoint(k, "CP-2", List.of(8.0, 0.0, 0.0), "m", "another survey"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INCONSISTENT_DATUM"));
        record(k, op, controlPoint(k, "CP-2", List.of(0.05, 0.0, 0.0), "m", "survey"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DEGENERATE_GEOMETRY"));
        record(k, op, controlPoint(k, "cp-1", List.of(5.0, 5.0, 0.0), "m", "survey"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_MEASUREMENT"));

        assertThat(count("capture_measurement", k.capture())).as("only CP-1 was stored").isEqualTo(1);
        assertThat(count("capture_measurement_observation", k.capture())).isEqualTo(2);
    }

    @Test
    void tooFewOrCollinearMeasurementsKeepCalibrationPendingAndSayWhatIsMissing() throws Exception {
        Cap k = capture();
        recordOk(k, distance(k, "door", 0.9, "m"));
        recordOk(k, controlPoint(k, "CP-1", List.of(0.0, 0.0, 0.0), "m", "survey"));
        recordOk(k, controlPoint(k, "CP-2", List.of(4.0, 0.0, 0.0), "m", "survey"));
        recordOk(k, controlPoint(k, "CP-3", List.of(8.0, 0.1, 0.0), "m", "survey"));
        JsonNode s = calibration(k);
        assertThat(s.get("state").asText()).isEqualTo("EVIDENCE_INCOMPLETE");
        assertThat(s.get("activeDistances").asInt()).isEqualTo(1);
        assertThat(s.get("activeControlPoints").asInt()).isEqualTo(3);
        assertThat(s.get("requirements").toString()).contains("of one line").contains("have 1").contains("have 3");
        // A point off the line completes the evidence.
        recordOk(k, controlPoint(k, "CP-4", List.of(8.0, 6.0, 0.0), "m", "survey"));
        assertThat(calibration(k).get("state").asText()).isEqualTo("AWAITING_RECONSTRUCTION");
    }

    @Test
    void disagreeingMeasurementsAreRejectedAndTheRejectionIsRecordedUntilTheyAreFixed() throws Exception {
        Cap k = capture();
        JsonNode refs = calibrationFixture().get("distanceReferences");
        JsonNode wall = recordOk(k, distance(k, "wall", 8.0, "m"));
        // 20 % off: misread tape.
        JsonNode jamb = recordOk(k, distance(k, "jamb", 2.1 * 1.2, "m"));
        reconstruct(k);

        // Every point of a used measurement must be resolved.
        calibrateCapture(k, Map.of("resolvedPoints", List.of(resolved(wall, "A", refs.get(0).get("a")))))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNRESOLVED_POINTS"));
        // A single distance cannot be checked against anything.
        calibrateCapture(k, Map.of("resolvedPoints", List.of(resolved(wall, "A", refs.get(0).get("a")), resolved(wall, "B", refs.get(0).get("b")))))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CALIBRATION"));

        List<Map<String, Object>> both = List.of(resolved(wall, "A", refs.get(0).get("a")), resolved(wall, "B", refs.get(0).get("b")),
            resolved(jamb, "A", refs.get(1).get("a")), resolved(jamb, "B", refs.get(1).get("b")));
        calibrateCapture(k, Map.of("resolvedPoints", both))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("CALIBRATION_INCONSISTENT"));
        JsonNode s = calibration(k);
        assertThat(s.get("state").asText()).isEqualTo("REJECTED");
        assertThat(s.get("reconstructionFrame").asText()).isEqualTo("ARBITRARY_SCALE");
        assertThat(s.get("lastAttempt").get("errorCode").asText()).isEqualTo("CALIBRATION_INCONSISTENT");
        assertThat(s.get("requirements").toString()).contains("Re-measure or withdraw");
        assertThat(jdbc.sql("SELECT count(*) FROM coordinate_frame f JOIN pipeline_run r ON r.id = f.source_run_id "
            + "WHERE r.capture_session_id = :c").param("c", k.capture()).query(Integer.class).single()).as("no frame was stored").isZero();
        assertThat(count("capture_calibration_attempt", k.capture())).isEqualTo(2);

        // Fix it: withdraw the misread one, record the re-measurement, calibrate again.
        post(k.url() + "/measurements/" + jamb.get("id").asText() + "/withdraw", k.c().operator(), "{\"reason\":\"misread tape\"}")
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("WITHDRAWN"));
        calibrateCapture(k, Map.of("resolvedPoints", both))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MEASUREMENT_WITHDRAWN"));
        JsonNode jamb2 = recordOk(k, distance(k, "jamb", 2.1, "m"));
        List<Map<String, Object>> fixed = List.of(both.get(0), both.get(1), resolved(jamb2, "A", refs.get(1).get("a")),
            resolved(jamb2, "B", refs.get(1).get("b")));
        calibrateCapture(k, Map.of("resolvedPoints", fixed)).andExpect(status().isCreated())
            .andExpect(jsonPath("$.state").value("CALIBRATED"));
        get(k.url() + "/calibration/attempts", k.c().operator()).andExpect(jsonPath("$.length()").value(3))
            .andExpect(jsonPath("$[0].outcome").value("ACCEPTED"));
    }

    // ---- ownership, isolation, lifecycle ---------------------------------------------------------------------------------

    @Test
    void onlyTheCapturesOwnTeamMayRecordEvidenceAndNothingCrossesCapturesVenuesOrOrganizations() throws Exception {
        Cap k = capture();
        JsonNode wall = recordOk(k, distance(k, "wall", 8.0, "m"));
        Map<String, Object> body = distance(k, "door", 0.9, "m");

        // Another operator of the same venue can read, but not change, someone else's capture evidence.
        String colleague = TestJwt.user(k.c().org(), "operator").venues(k.c().venue()).token(); // a different subject
        get(k.url() + "/measurements", colleague).andExpect(status().isOk());
        record(k, colleague, body).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CAPTURE_NOT_OWNED"));
        post(k.url() + "/measurements/" + wall.get("id").asText() + "/withdraw", colleague, "{\"reason\":\"x\"}")
            .andExpect(status().isForbidden());
        // A venue manager may.
        record(k, TestJwt.user(k.c().org(), "venue-manager").venues(k.c().venue()).token(), body).andExpect(status().isCreated());
        // Viewers never see raw capture evidence.
        get(k.url() + "/calibration", TestJwt.user(k.c().org(), "viewer").venues(k.c().venue()).token()).andExpect(status().isForbidden());

        // Another organization, and another venue of the same organization: not found.
        UUID otherOrg = fx.organization();
        UUID otherOrgVenue = fx.venue(otherOrg);
        String outsider = TestJwt.user(otherOrg, "operator").venues(otherOrgVenue).token();
        get(k.url() + "/measurements", outsider).andExpect(status().isNotFound());
        record(k, outsider, body).andExpect(status().isNotFound());
        UUID siblingVenue = fx.venue(k.c().org());
        String sibling = TestJwt.user(k.c().org(), "operator").venues(siblingVenue).token();
        get(k.url() + "/calibration", sibling).andExpect(status().isNotFound());
        // The capture through another venue's path does not exist.
        get("/api/v1/venues/" + siblingVenue + "/captures/" + k.capture() + "/measurements",
            TestJwt.user(k.c().org(), "operator").venues(siblingVenue, k.c().venue()).token()).andExpect(status().isNotFound());

        // A measurement of one capture is not found through another.
        Cap other = capture();
        post(other.url() + "/measurements/" + wall.get("id").asText() + "/withdraw", other.c().operator(), "{\"reason\":\"x\"}")
            .andExpect(status().isNotFound());
        reconstruct(other);
        calibrateCapture(other, Map.of("resolvedPoints", List.of(Map.of("measurementId", wall.get("id").asText(), "point", "A",
            "reconstruction", List.of(1, 2, 3))))).andExpect(status().isNotFound());
    }

    @Test
    void evidenceFollowsTheCaptureLifecycleAndTheDatabaseKeepsItImmutable() throws Exception {
        Cap k = capture();
        // No reconstruction yet: nothing to calibrate.
        JsonNode wall = recordOk(k, distance(k, "wall", 8.0, "m"));
        calibrateCapture(k, Map.of("resolvedPoints", List.of(Map.of("measurementId", wall.get("id").asText(), "point", "A",
            "reconstruction", List.of(1, 2, 3))))).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("RECONSTRUCTION_FRAME_UNAVAILABLE"));

        // A capture without a floor has no frame for evidence to belong to.
        String json = post("/api/v1/venues/" + k.c().venue() + "/captures", k.c().operator(), "{\"device\":{}}")
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID floorless = UUID.fromString(field(json, "id"));
        post("/api/v1/venues/" + k.c().venue() + "/captures/" + floorless + "/measurements", k.c().operator(),
            mapper.writeValueAsString(distance(k, "x", 1.0, "m")))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FLOOR_REQUIRED"));

        // Rows are immutable; the only change is a withdrawal, and nothing is deleted.
        UUID id = UUID.fromString(wall.get("id").asText());
        assertThatThrownBy(() -> jdbc.sql("UPDATE capture_measurement SET measured_metres = 9 WHERE id = :id").param("id", id).update())
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM capture_measurement WHERE id = :id").param("id", id).update())
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("UPDATE capture_measurement_observation SET pixel_u = 1 WHERE measurement_id = :id")
            .param("id", id).update()).isInstanceOf(DataIntegrityViolationException.class);
        // The floor is the capture's floor.
        UUID otherFloor = fx.floor(k.c().org(), k.c().venue(), 1);
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO capture_measurement (organization_id, venue_id, capture_session_id, floor_id, kind, label, method, unit,
                    measured_value, measured_metres, created_by) VALUES (:o, :v, :c, :f, 'DISTANCE', 'x', 'TAPE', 'm', 1, 1, 'test')
                """).param("o", k.c().org()).param("v", k.c().venue()).param("c", k.capture()).param("f", otherFloor).update())
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("is not the floor of capture");
        // An observation cannot point at another capture's media.
        Cap other = capture();
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO capture_measurement_observation (organization_id, venue_id, capture_session_id, measurement_id, point,
                    media_id, pixel_u, pixel_v) VALUES (:o, :v, :c, :m, 'A', :media, 1, 1)
                """).param("o", k.c().org()).param("v", k.c().venue()).param("c", k.capture()).param("m", id)
            .param("media", other.img1()).update()).isInstanceOf(DataIntegrityViolationException.class);

        // A failed capture takes no more evidence and cannot be calibrated.
        jdbc.sql("UPDATE capture_session SET status = 'FAILED', failure_code = 'TEST' WHERE id = :c").param("c", k.capture()).update();
        record(k, k.c().operator(), distance(k, "late", 1.0, "m"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_CAPTURE_STATE"));
        calibrateCapture(k, Map.of("resolvedPoints", List.of()))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_CAPTURE_STATE"));
        assertThat(calibration(k).get("captureStage").asText()).isEqualTo("FAILED");
    }
}
