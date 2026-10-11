package dev.chaya.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.chaya.api.planning.PlannerFixtures;
import dev.chaya.api.planning.RoomBoundsEstimator.SurfaceObservation;
import dev.chaya.api.planning.Scene;
import dev.chaya.api.planning.SyntheticRangeSensor;
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
 * Automatic capture planning through the REST surface: reconnaissance evidence in, estimated bounds, a persisted plan,
 * progress along it, and the audit view that keeps PREDICTED and OBSERVED coverage apart. The geometry is the planning
 * package's job (CapturePlanningPipelineTest); this checks the wiring, persistence and honest degradation. Surface points
 * come from SyntheticRangeSensor, a deterministic test-only ray caster standing in for the device's hit test.
 */
class CapturePlanApiTest extends CaptureTestSupport {

    @Autowired ObjectMapper mapper;

    private static final Scene ROOM = new Scene(List.of(PlannerFixtures.rect(0, 0, 8, 6)), List.of(), List.of(), List.of(), List.of());
    private static final List<TrajectorySample> WALK = PlannerFixtures.lap(1.0, 2, 2, 6, 2, 6, 4, 2, 4, 2, 2.5);

    private String hud(Ctx c, UUID capture) {
        return capUrl(c, capture) + "/hud";
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean absent(JsonNode n) {
        return n == null || n.isNull() || n.isMissingNode();
    }

    private JsonNode read(String s) throws Exception {
        return mapper.readTree(s);
    }

    private String poses(long baseMs, List<TrajectorySample> samples, String source, String frame, boolean withYaw) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (TrajectorySample s : samples) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("capturedAtMs", baseMs + Math.round(s.t() * 1000));
            m.put("x", s.x());
            m.put("y", s.y());
            if (withYaw && s.yawRadians() != null) m.put("yawDegrees", Math.toDegrees(s.yawRadians()));
            m.put("source", source);
            if (frame != null) m.put("frameId", frame);
            out.add(m);
        }
        return json(Map.of("samples", out));
    }

    private String surfaces(long baseMs, List<SurfaceObservation> obs, String frame) {
        List<Map<String, Object>> out = new ArrayList<>();
        int i = 0;
        for (SurfaceObservation o : obs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("capturedAtMs", baseMs + i++);
            m.put("originX", o.originX());
            m.put("originY", o.originY());
            m.put("hitX", o.hitX());
            m.put("hitY", o.hitY());
            m.put("kind", o.kind());
            m.put("normalDegrees", o.normalDegrees());
            m.put("source", "webxr");
            m.put("frameId", frame);
            out.add(m);
        }
        return json(Map.of("samples", out));
    }

    /** A tracked 2 Hz reconnaissance walk with its surface hits, ending now, in AR session `frame`. */
    private long reconWalk(Ctx c, UUID capture, String frame) throws Exception {
        long base = System.currentTimeMillis() - Math.round(WALK.get(WALK.size() - 1).t() * 1000) - 200;
        post(hud(c, capture) + "/tracking-events", c.operator(),
            json(Map.of("events", List.of(Map.of("capturedAtMs", base - 100, "state", "SESSION_STARTED", "source", "webxr", "frameId", frame)))))
            .andExpect(status().isAccepted());
        post(hud(c, capture) + "/pose", c.operator(), poses(base, WALK, "webxr", frame, false)).andExpect(status().isAccepted());
        List<SurfaceObservation> obs = new SyntheticRangeSensor(ROOM, 8).sweep(WALK, 7, 6, 40, null);
        for (int i = 0; i < obs.size(); i += 400) {
            post(hud(c, capture) + "/surface", c.operator(), surfaces(base, obs.subList(i, Math.min(obs.size(), i + 400)), frame))
                .andExpect(status().isAccepted());
        }
        return base;
    }

    @Test
    void reconnaissanceToEstimatedBoundsToAPersistedPlanAndItsProgress() throws Exception {
        var c = ctx();
        UUID capture = newCapture(c);
        reconWalk(c, capture, "xr-1");

        get(hud(c, capture) + "/reconnaissance", c.operator()).andExpect(status().isOk())
            .andExpect(jsonPath("$.verdict").value("READY"))
            .andExpect(jsonPath("$.positionBasis").value("TRACKED"))
            .andExpect(jsonPath("$.boundsEstimable").value(true))
            .andExpect(jsonPath("$.frameId").value("xr-1"));

        JsonNode est = read(post(hud(c, capture) + "/scene/estimate", c.operator(), null).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
        assertThat(est.get("stored").asBoolean()).isTrue();
        assertThat(est.at("/bounds/status").asText()).isEqualTo("ESTIMATED");
        assertThat(est.at("/bounds/frameId").asText()).isEqualTo("xr-1");
        assertThat(est.at("/bounds/areaM2").asDouble()).isBetween(40.0, 50.0);
        assertThat(est.at("/bounds/assumptions").size()).isGreaterThan(0);

        JsonNode st = read(get(hud(c, capture) + "/status", c.operator()).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(st.get("sceneSource").asText()).isEqualTo("ESTIMATED");
        assertThat(st.get("sceneFrameId").asText()).isEqualTo("xr-1");
        assertThat(st.get("trackingState").asText()).isEqualTo("TRACKING");
        assertThat(st.at("/currentPosition/kind").asText()).isEqualTo("TRACKED");
        assertThat(st.get("coverageBasis").asText()).isEqualTo("LIVE_TRACKED");
        assertThat(st.get("coverageLive").asBoolean()).isTrue();
        assertThat(st.get("coverageAvailable").asBoolean()).isTrue();
        assertThat(st.get("plannedPathKind").asText()).isEqualTo("LIVE_SUGGESTION");
        assertThat(st.get("completion").asText()).isEqualTo("NO_PLAN");
        assertThat(st.at("/scene/areas/0").size()).isGreaterThanOrEqualTo(4);

        JsonNode plan = read(post(hud(c, capture) + "/plan", c.operator(), null).andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString());
        assertThat(plan.get("version").asInt()).isEqualTo(1);
        assertThat(plan.get("sceneSource").asText()).isEqualTo("ESTIMATED");
        assertThat(plan.at("/predicted/waypoints").size()).isGreaterThan(0);
        assertThat(plan.at("/predicted/planned/coveragePercent").asDouble()).isGreaterThan(plan.at("/observedAtPlan/coveragePercent").asDouble());
        assertThat(plan.at("/evidence/poseSamplesBySource/webxr").asInt()).isEqualTo(WALK.size());
        assertThat(plan.at("/evidence/surfaceSamples").asInt()).isGreaterThan(50);

        JsonNode progress = read(get(hud(c, capture) + "/plan", c.operator()).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(progress.at("/progress/completion").asText()).isEqualTo("NOT_STARTED");
        assertThat(progress.at("/progress/nextOrder").asInt()).isEqualTo(1);

        // Walk to waypoint 1 and face its heading.
        JsonNode w1 = plan.at("/predicted/waypoints/0");
        long now = System.currentTimeMillis();
        post(hud(c, capture) + "/pose", c.operator(), json(Map.of("samples", List.of(Map.of("capturedAtMs", now, "x", w1.get("x").asDouble(),
            "y", w1.get("y").asDouble(), "yawDegrees", w1.get("yawDegrees").asDouble(), "source", "webxr", "frameId", "xr-1")))))
            .andExpect(status().isAccepted());
        JsonNode after = read(get(hud(c, capture) + "/status", c.operator()).andReturn().getResponse().getContentAsString());
        assertThat(after.get("plannedPathKind").asText()).isEqualTo("PERSISTED_PLAN");
        assertThat(after.at("/plannedPath/0/status").asText()).isEqualTo("VISITED");
        assertThat(after.get("completion").asText()).isIn("IN_PROGRESS", "ROUTE_COMPLETE", "COVERAGE_TARGET_REACHED");
        if (plan.at("/predicted/waypoints").size() > 1) {
            assertThat(after.at("/nextWaypoint/order").asInt()).isEqualTo(2);
            assertThat(after.at("/nextWaypoint/leg").size()).isGreaterThan(1);
        }
        assertThat(after.at("/plan/coverageKind").asText()).isEqualTo("PREDICTED");

        // Re-planning supersedes, and the audit view keeps every version and both kinds of coverage apart.
        post(hud(c, capture) + "/plan", c.operator(), null).andExpect(status().isCreated()).andExpect(jsonPath("$.version").value(2));
        JsonNode ev = read(get(hud(c, capture) + "/evidence", c.operator()).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(ev.get("plans").size()).isEqualTo(2);
        assertThat(absent(ev.at("/plans/0/supersededAt"))).isFalse();
        assertThat(absent(ev.at("/plans/1/supersededAt"))).isTrue();
        assertThat(ev.at("/plans/0/coverageKind").asText()).isEqualTo("PREDICTED");
        assertThat(ev.at("/observedNow/coverageKind").asText()).isEqualTo("OBSERVED");
        assertThat(ev.at("/poseSamplesBySource/webxr").asInt()).isEqualTo(WALK.size() + 1);
        assertThat(ev.at("/surfaceSamplesByKind/WALL").asInt()).isGreaterThan(50);
        assertThat(ev.at("/trackingEventsByState/SESSION_STARTED").asInt()).isEqualTo(1);
        assertThat(ev.at("/definitions/PREDICTED").asText()).contains("prediction");
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE action = 'capture.scan_plan_created' AND resource_id = :c")
            .param("c", capture).query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void videoIsNotAPoseSourceAndAVideoOnlyCaptureSaysSo() throws Exception {
        var c = ctx();
        UUID capture = newCapture(c);
        byte[] video = mp4(4096);
        init(c, capture, "VIDEO", "walk.mp4", "video/mp4", video, sha256(video));
        get(hud(c, capture) + "/reconnaissance", c.operator()).andExpect(status().isOk())
            .andExpect(jsonPath("$.verdict").value("NOT_STARTED"))
            .andExpect(jsonPath("$.issues[0].code").value("VIDEO_ONLY"));
        post(hud(c, capture) + "/pose", c.operator(), poses(System.currentTimeMillis(), WALK.subList(0, 2), "video", null, false))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("VIDEO_IS_NOT_A_POSE_SOURCE"));
        post(hud(c, capture) + "/pose", c.operator(), poses(System.currentTimeMillis(), WALK.subList(0, 2), "slam-lite", null, false))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNSUPPORTED_POSE_SOURCE"));
        post(hud(c, capture) + "/pose", c.operator(), poses(System.currentTimeMillis(), WALK.subList(0, 2), "webxr", null, false))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("FRAME_ID_REQUIRED"));
    }

    @Test
    void planningIsRefusedUntilTheReconnaissanceIsUsableAndTheBoundsExist() throws Exception {
        var c = ctx();
        UUID capture = newCapture(c);
        post(hud(c, capture) + "/plan", c.operator(), null).andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("SCENE_NOT_SET"));
        call(HttpMethod.PUT, hud(c, capture) + "/scene", c.operator(),
            "{\"scene\":{\"areas\":[[{\"x\":0,\"y\":0},{\"x\":8,\"y\":0},{\"x\":8,\"y\":6},{\"x\":0,\"y\":6}]]}}").andExpect(status().isNoContent());
        // Two marks are not a walk.
        post(hud(c, capture) + "/pose", c.operator(), poses(System.currentTimeMillis(), WALK.subList(0, 2), "manual", null, false))
            .andExpect(status().isAccepted());
        post(hud(c, capture) + "/plan", c.operator(), null).andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("RECONNAISSANCE_INCOMPLETE"));
        // Manual marks never estimate walls.
        JsonNode est = read(post(hud(c, capture) + "/scene/estimate", c.operator(), null).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
        assertThat(est.get("stored").asBoolean()).isFalse();
        assertThat(est.at("/bounds/status").asText()).isEqualTo("NOT_ESTIMABLE");
        get(hud(c, capture) + "/status", c.operator()).andExpect(jsonPath("$.sceneSource").value("MEASURED"));
        get(hud(c, capture) + "/plan", c.operator()).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NO_PLAN"));
    }

    @Test
    void trackingLossAndANewArSessionAreNeverShownAsLiveTracking() throws Exception {
        var c = ctx();
        UUID capture = newCapture(c);
        reconWalk(c, capture, "xr-1");
        post(hud(c, capture) + "/scene/estimate", c.operator(), null).andExpect(status().isOk()).andExpect(jsonPath("$.stored").value(true));

        long now = System.currentTimeMillis();
        post(hud(c, capture) + "/tracking-events", c.operator(), json(Map.of("events", List.of(
            Map.of("capturedAtMs", now, "state", "LOST", "source", "webxr", "frameId", "xr-1", "detail", "insufficient features")))))
            .andExpect(status().isAccepted());
        JsonNode lost = read(get(hud(c, capture) + "/status", c.operator()).andReturn().getResponse().getContentAsString());
        assertThat(lost.get("trackingState").asText()).isEqualTo("LOST");
        assertThat(lost.get("trackingAvailable").asBoolean()).isFalse();
        assertThat(lost.get("trackingUnavailableReason").asText()).contains("insufficient features");
        assertThat(lost.get("coverageLive").asBoolean()).isFalse();
        assertThat(lost.get("coverageBasis").asText()).isEqualTo("TRACKED_NOT_LIVE");

        // The AR session restarted: a new frame that has not been aligned to the plan.
        post(hud(c, capture) + "/pose", c.operator(), json(Map.of("samples", List.of(
            Map.of("capturedAtMs", now + 500, "x", 0.1, "y", 0.2, "source", "webxr", "frameId", "xr-2")))))
            .andExpect(status().isAccepted());
        JsonNode moved = read(get(hud(c, capture) + "/status", c.operator()).andReturn().getResponse().getContentAsString());
        assertThat(moved.get("trackingState").asText()).isEqualTo("FRAME_MISMATCH");
        assertThat(absent(moved.get("currentPosition"))).isTrue();
        assertThat(moved.get("excludedOtherFramePositions").asInt()).isEqualTo(1);
        assertThat(moved.get("warnings").toString()).contains("FRAME_MISMATCH");

        // Aligned by the operator into the plan's frame: usable again.
        post(hud(c, capture) + "/pose", c.operator(), json(Map.of("samples", List.of(Map.of("capturedAtMs", now + 1000, "x", 3.0, "y", 3.0,
            "source", "webxr", "frameId", "xr-1", "frameAlignment", "OPERATOR_ALIGNED"))))).andExpect(status().isAccepted());
        get(hud(c, capture) + "/status", c.operator()).andExpect(jsonPath("$.trackingState").value("TRACKING"))
            .andExpect(jsonPath("$.currentPosition.x").value(3.0));
    }

    @Test
    void anImportedTrajectoryIsUsedForCoverageButNeverAsALivePosition() throws Exception {
        var c = ctx();
        UUID capture = newCapture(c);
        call(HttpMethod.PUT, hud(c, capture) + "/scene", c.operator(),
            "{\"scene\":{\"areas\":[[{\"x\":0,\"y\":0},{\"x\":8,\"y\":0},{\"x\":8,\"y\":6},{\"x\":0,\"y\":6}]]},\"frameId\":\"survey-1\"}")
            .andExpect(status().isNoContent());
        post(hud(c, capture) + "/pose", c.operator(), poses(System.currentTimeMillis() - 60_000, WALK, "operator-trajectory", "survey-1", false))
            .andExpect(status().isAccepted());
        JsonNode st = read(get(hud(c, capture) + "/status", c.operator()).andReturn().getResponse().getContentAsString());
        assertThat(st.get("trackingState").asText()).isEqualTo("IMPORTED_ONLY");
        assertThat(absent(st.get("currentPosition"))).isTrue();
        assertThat(st.get("coverageAvailable").asBoolean()).isTrue();
        assertThat(st.get("coverageBasis").asText()).isEqualTo("IMPORTED_TRAJECTORY");
        assertThat(st.get("coverageLive").asBoolean()).isFalse();
        assertThat(st.at("/pathSoFar/1/kind").asText()).isEqualTo("IMPORTED");
        assertThat(st.at("/reconnaissance/verdict").asText()).isEqualTo("READY");
    }

    @Test
    void aWaypointVisitedWhileFramesWereBlurredIsRecommendedForAReshoot() throws Exception {
        var c = ctx();
        UUID capture = newCapture(c);
        reconWalk(c, capture, "xr-1");
        post(hud(c, capture) + "/scene/estimate", c.operator(), null).andExpect(status().isOk());
        JsonNode plan = read(post(hud(c, capture) + "/plan", c.operator(), null).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        JsonNode w1 = plan.at("/predicted/waypoints/0");
        long now = System.currentTimeMillis();
        post(hud(c, capture) + "/pose", c.operator(), json(Map.of("samples", List.of(Map.of("capturedAtMs", now, "x", w1.get("x").asDouble(),
            "y", w1.get("y").asDouble(), "source", "webxr", "frameId", "xr-1"))))).andExpect(status().isAccepted());
        List<Map<String, Object>> q = new ArrayList<>();
        for (int i = -2; i <= 2; i++) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("capturedAtMs", now + i * 300L);
            m.put("blurScore", 12.0);
            m.put("brightnessMean", 120.0);
            m.put("shadowClipFraction", 0.0);
            m.put("highlightClipFraction", 0.0);
            m.put("motionScore", 0.2);
            m.put("duplicateFrame", false);
            m.put("featureCount", 80);
            m.put("warnings", List.of("BLUR", "FAST_MOTION"));
            q.add(m);
        }
        post(hud(c, capture) + "/quality", c.operator(), json(Map.of("samples", q))).andExpect(status().isAccepted());
        JsonNode st = read(get(hud(c, capture) + "/status", c.operator()).andReturn().getResponse().getContentAsString());
        assertThat(st.at("/plannedPath/0/status").asText()).isEqualTo("VISITED");
        assertThat(st.get("reshootRecommendations").toString()).contains("REVISIT_WAYPOINT 1");
        assertThat(st.get("qualityFlags").toString()).contains("BLUR");
    }

    @Test
    void newEvidenceEndpointsAreVenueScopedAndRefuseWritesAfterCapture() throws Exception {
        var c = ctx();
        UUID capture = newCapture(c);
        get(hud(c, capture) + "/evidence", TestJwt.user(fx.organization(), "admin").token()).andExpect(status().isNotFound());
        get(hud(c, capture) + "/reconnaissance", TestJwt.user(c.org(), "viewer").venues(c.venue()).token()).andExpect(status().isForbidden());
        jdbc.sql("UPDATE capture_session SET status = 'FAILED', failure_code = 'TEST' WHERE id = :c").param("c", capture).update();
        post(hud(c, capture) + "/plan", c.operator(), null).andExpect(status().isConflict());
        post(hud(c, capture) + "/scene/estimate", c.operator(), null).andExpect(status().isConflict());
        post(hud(c, capture) + "/tracking-events", c.operator(),
            json(Map.of("events", List.of(Map.of("capturedAtMs", 1, "state", "LOST", "source", "webxr"))))).andExpect(status().isConflict());
        get(hud(c, capture) + "/evidence", c.operator()).andExpect(status().isOk());
        post(hud(c, capture) + "/tracking-events", c.operator(), "{\"events\":[]}").andExpect(status().isBadRequest());
    }
}
