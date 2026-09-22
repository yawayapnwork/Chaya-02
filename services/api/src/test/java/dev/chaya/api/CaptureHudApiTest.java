package dev.chaya.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.chaya.api.planning.PlannerFixtures;
import dev.chaya.api.planning.TrajectorySample;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

/**
 * The live capture HUD's REST surface: the room outline, appended pose/quality samples, and the status they
 * produce. The coverage math itself is CapturePathPlanner's (see CapturePathPlannerTest); this tests that the HUD
 * wires real posted data into it correctly and degrades honestly when data is missing.
 */
class CaptureHudApiTest extends CaptureTestSupport {

    @Autowired ObjectMapper mapper;

    private static Map<String, Object> pt(double x, double y) {
        return Map.of("x", x, "y", y);
    }

    private static List<Map<String, Object>> rect(double x0, double y0, double x1, double y1) {
        return List.of(pt(x0, y0), pt(x1, y0), pt(x1, y1), pt(x0, y1));
    }

    private String hudUrl(Ctx c, UUID capture) {
        return capUrl(c, capture) + "/hud";
    }

    private String sceneBody(double w, double h) {
        return "{\"scene\":{\"areas\":" + jsonList(List.of(rect(0, 0, w, h))) + "}}";
    }

    private String jsonList(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String poseBody(long baseMs, List<TrajectorySample> samples) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (TrajectorySample s : samples) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("capturedAtMs", baseMs + Math.round(s.t() * 1000));
            m.put("x", s.x());
            m.put("y", s.y());
            out.add(m);
        }
        return jsonList(Map.of("samples", out));
    }

    private String qualityBody(long baseMs, int count, List<String> warnings) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("capturedAtMs", baseMs + i * 500L);
            m.put("blurScore", 10.0);
            m.put("brightnessMean", 120.0);
            m.put("shadowClipFraction", 0.0);
            m.put("highlightClipFraction", 0.0);
            m.put("motionScore", 0.1);
            m.put("duplicateFrame", false);
            m.put("featureCount", 5);
            m.put("warnings", warnings);
            out.add(m);
        }
        return jsonList(Map.of("samples", out));
    }

    @Test
    void statusIsHonestlyUnavailableBeforeAnythingIsReported() throws Exception {
        var c = ctx();
        UUID capture = newCapture(c);
        get(hudUrl(c, capture) + "/status", c.operator()).andExpect(status().isOk())
            .andExpect(jsonPath("$.sceneSet").value(false))
            .andExpect(jsonPath("$.coverageAvailable").value(false))
            .andExpect(jsonPath("$.coverageUnavailableReason").value("room outline not set for this capture yet"))
            .andExpect(jsonPath("$.trackingAvailable").value(false))
            .andExpect(jsonPath("$.currentPosition").doesNotExist())
            .andExpect(jsonPath("$.quality").doesNotExist());
    }

    @Test
    void settingARoomOutlineWithNoPositionDataYetSucceeds() throws Exception {
        // Regression check: the scene is validated with an empty trajectory (none has been reported yet), and a
        // geometrically fine scene must not be rejected merely because there is no trajectory yet.
        var c = ctx();
        UUID capture = newCapture(c);
        call(HttpMethod.PUT, hudUrl(c, capture) + "/scene", c.operator(), sceneBody(8, 6)).andExpect(status().isNoContent());
        get(hudUrl(c, capture) + "/status", c.operator()).andExpect(status().isOk())
            .andExpect(jsonPath("$.sceneSet").value(true))
            .andExpect(jsonPath("$.coverageAvailable").value(false))
            .andExpect(jsonPath("$.coverageUnavailableReason", org.hamcrest.Matchers.containsString("no usable samples")));
    }

    @Test
    void aRealPostedPathProducesRealCoverageAndAPlannedPath() throws Exception {
        var c = ctx();
        UUID capture = newCapture(c);
        call(HttpMethod.PUT, hudUrl(c, capture) + "/scene", c.operator(), sceneBody(8, 6)).andExpect(status().isNoContent());

        long base = System.currentTimeMillis() - 5_000;
        List<TrajectorySample> lap = PlannerFixtures.lap(1.0, 1.5, 1.5, 6.5, 1.5, 6.5, 4.5, 4.5, 4.5);
        post(hudUrl(c, capture) + "/pose", c.operator(), poseBody(base, lap)).andExpect(status().isAccepted());

        String json = get(hudUrl(c, capture) + "/status", c.operator()).andExpect(status().isOk())
            .andExpect(jsonPath("$.sceneSet").value(true))
            .andExpect(jsonPath("$.trackingAvailable").value(true))
            .andExpect(jsonPath("$.coverageAvailable").value(true))
            .andExpect(jsonPath("$.pathSoFar.length()").value(lap.size()))
            .andReturn().getResponse().getContentAsString();
        JsonNode r = mapper.readTree(json);
        assertThat(r.get("coveragePercent").asDouble()).isGreaterThan(0);
        assertThat(r.get("plannedPath").size()).isGreaterThan(0); // a route to close the remaining gaps
        assertThat(r.get("currentPosition").get("x").asDouble()).isEqualTo(lap.get(lap.size() - 1).x());
    }

    @Test
    void repeatedQualityWarningsBecomeAReshootRecommendation() throws Exception {
        var c = ctx();
        UUID capture = newCapture(c);
        long base = System.currentTimeMillis();
        post(hudUrl(c, capture) + "/quality", c.operator(), qualityBody(base, 5, List.of("BLUR"))).andExpect(status().isAccepted());
        String json = get(hudUrl(c, capture) + "/status", c.operator()).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        JsonNode r = mapper.readTree(json);
        assertThat(r.get("quality").get("sampleCount").asInt()).isEqualTo(5);
        boolean flagged = false;
        for (JsonNode rec : r.get("reshootRecommendations")) {
            if (rec.get("reason").asText().startsWith("REPEATED_BLUR")) flagged = true;
        }
        assertThat(flagged).as("5 consecutive BLUR-flagged frames should surface a reshoot recommendation").isTrue();
    }

    @Test
    void writesAreRefusedOnceTheCaptureIsNoLongerBeingCapturedButStatusStaysReadable() throws Exception {
        var c = ctx();
        UUID capture = newCapture(c);
        jdbc.sql("UPDATE capture_session SET status = 'FAILED', failure_code = 'TEST' WHERE id = :c").param("c", capture).update();
        call(HttpMethod.PUT, hudUrl(c, capture) + "/scene", c.operator(), sceneBody(8, 6)).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CAPTURE_NOT_CAPTURING"));
        get(hudUrl(c, capture) + "/status", c.operator()).andExpect(status().isOk());
    }

    @Test
    void tooManySamplesInOneRequestIsRefused() throws Exception {
        var c = ctx();
        UUID capture = newCapture(c);
        List<Map<String, Object>> many = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            many.add(Map.of("capturedAtMs", System.currentTimeMillis() + i, "x", 1.0, "y", 1.0));
        }
        post(hudUrl(c, capture) + "/pose", c.operator(), jsonList(Map.of("samples", many)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("TOO_MANY_SAMPLES"));
    }

    @Test
    void onlyTheVenueTeamMayReadOrWriteAndTenantsAreIsolated() throws Exception {
        var c = ctx();
        UUID capture = newCapture(c);
        get(hudUrl(c, capture) + "/status", null).andExpect(status().isUnauthorized());
        get(hudUrl(c, capture) + "/status", TestJwt.user(c.org(), "viewer").venues(c.venue()).token()).andExpect(status().isForbidden());
        get(hudUrl(c, capture) + "/status", TestJwt.user(fx.organization(), "admin").token()).andExpect(status().isNotFound());
        get(hudUrl(c, UUID.randomUUID()) + "/status", c.operator()).andExpect(status().isNotFound());
    }
}
