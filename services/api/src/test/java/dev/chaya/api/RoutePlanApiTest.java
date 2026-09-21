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

/** The planner behind its HTTP endpoint: authorization, tenancy, validation, and the same numbers the library gives. */
class RoutePlanApiTest extends CaptureTestSupport {

    @Autowired ObjectMapper mapper;

    private static Map<String, Object> pt(double x, double y) {
        return Map.of("x", x, "y", y);
    }

    private static List<Map<String, Object>> rect(double x0, double y0, double x1, double y1) {
        return List.of(pt(x0, y0), pt(x1, y0), pt(x1, y1), pt(x0, y1));
    }

    private String body(Map<String, Object> config) throws Exception {
        List<Map<String, Object>> samples = new ArrayList<>();
        for (TrajectorySample s : PlannerFixtures.lap(1.0, 1.5, 1.5, 6.5, 1.5, 6.5, 4.5, 4.5, 4.5)) {
            samples.add(Map.of("t", s.t(), "x", s.x(), "y", s.y()));
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("scene", Map.of("areas", List.of(rect(0, 0, 8, 6))));
        root.put("trajectory", samples);
        if (config != null) {
            root.put("config", config);
        }
        return mapper.writeValueAsString(root);
    }

    private String url(Ctx c, UUID capture) {
        return capUrl(c, capture) + "/route-plan";
    }

    @Test
    void anOperatorGetsAMeasuredRouteAndTheCallIsAudited() throws Exception {
        var c = ctx();
        UUID capture = newCapture(c);
        String json = post(url(c, capture), c.operator(), body(null)).andExpect(status().isOk())
            .andExpect(jsonPath("$.waypoints.length()").isNotEmpty())
            .andExpect(jsonPath("$.baseline.waypointCount").value(0))
            .andExpect(jsonPath("$.uncoveredRegions").isArray())
            .andExpect(jsonPath("$.confidence.note").isNotEmpty())
            .andReturn().getResponse().getContentAsString();
        JsonNode r = mapper.readTree(json);
        assertThat(r.get("planned").get("coveragePercent").asDouble()).isGreaterThan(r.get("baseline").get("coveragePercent").asDouble() + 30);
        assertThat(r.get("estimatedDistanceMeters").asDouble()).isPositive();
        assertThat(r.get("estimatedCaptureSeconds").asDouble()).isPositive();
        assertThat(r.get("waypoints").get(0).get("reason").asText()).isNotBlank();
        assertThat(auditCount(c.org(), "capture.route_plan")).isEqualTo(1);
        // Deterministic over the wire, too.
        assertThat(post(url(c, capture), c.operator(), body(null)).andReturn().getResponse().getContentAsString()).isEqualTo(json);
    }

    @Test
    void configurationOverridesApply() throws Exception {
        var c = ctx();
        UUID capture = newCapture(c);
        post(url(c, capture), c.operator(), body(Map.of("maxWaypoints", 2, "stopCoverage", 1.0))).andExpect(status().isOk())
            .andExpect(jsonPath("$.waypoints.length()").value(2)).andExpect(jsonPath("$.diagnostics.stopReason").value("MAX_WAYPOINTS"));
    }

    @Test
    void onlyTheVenueTeamMayPlanAndTenantsAreIsolated() throws Exception {
        var c = ctx();
        UUID capture = newCapture(c);
        post(url(c, capture), null, body(null)).andExpect(status().isUnauthorized());
        post(url(c, capture), TestJwt.user(c.org(), "viewer").venues(c.venue()).token(), body(null)).andExpect(status().isForbidden());
        post(url(c, capture), TestJwt.service().token(), body(null)).andExpect(status().isForbidden());
        post(url(c, capture), TestJwt.user(fx.organization(), "admin").token(), body(null)).andExpect(status().isNotFound());
        post(url(c, capture), TestJwt.user(c.org(), "operator").venues(fx.venue(c.org())).token(), body(null)).andExpect(status().isNotFound());
        post(url(c, UUID.randomUUID()), c.operator(), body(null)).andExpect(status().isNotFound());
        assertThat(auditCount(c.org(), "capture.route_plan")).as("refused calls are not audited as plans").isZero();
    }

    @Test
    void badInputIsRefusedWithActionableCodes() throws Exception {
        var c = ctx();
        UUID capture = newCapture(c);
        post(url(c, capture), c.operator(), "{\"scene\":{\"areas\":[]},\"trajectory\":[]}").andExpect(status().isBadRequest());
        post(url(c, capture), c.operator(), body(Map.of("noSuchSetting", 1))).andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("UNKNOWN_CONFIG_KEY"));
        post(url(c, capture), c.operator(), body(Map.of("fovDegrees", 5))).andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("INVALID_CONFIG"));
        post(url(c, capture), c.operator(), body(Map.of("maxWaypoints", 2.5))).andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("INVALID_CONFIG"));

        Map<String, Object> outside = new LinkedHashMap<>();
        outside.put("scene", Map.of("areas", List.of(rect(0, 0, 8, 6))));
        outside.put("trajectory", List.of(Map.of("t", 0, "x", 100, "y", 100), Map.of("t", 1, "x", 101, "y", 100)));
        post(url(c, capture), c.operator(), mapper.writeValueAsString(outside)).andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("TRAJECTORY_OUTSIDE_SCENE"));

        List<Map<String, Object>> many = new ArrayList<>();
        for (int i = 0; i < 5001; i++) {
            many.add(Map.of("t", i, "x", 1.0, "y", 1.0));
        }
        Map<String, Object> tooMany = new LinkedHashMap<>(outside);
        tooMany.put("trajectory", many);
        post(url(c, capture), c.operator(), mapper.writeValueAsString(tooMany)).andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("TOO_MANY_SAMPLES"));
    }

    @Test
    void aCaptureThatIsNoLongerBeingCapturedCannotBePlanned() throws Exception {
        var c = ctx();
        UUID capture = newCapture(c);
        jdbc.sql("UPDATE capture_session SET status = 'FAILED', failure_code = 'TEST' WHERE id = :c").param("c", capture).update();
        post(url(c, capture), c.operator(), body(null)).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CAPTURE_NOT_PLANNABLE"));
    }
}
