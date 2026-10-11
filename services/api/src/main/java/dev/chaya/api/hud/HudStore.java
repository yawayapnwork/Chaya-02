package dev.chaya.api.hud;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.chaya.api.hud.CaptureHudDtos.BoundsView;
import dev.chaya.api.planning.api.RoutePlanDtos.SceneDto;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Reads the HUD's stored evidence, scene and plans. Every query is scoped by capture, venue and organization. */
@Component
class HudStore {

    record SceneRow(SceneDto scene, Map<String, Double> config, String source, String frameId, BoundsView bounds) {}

    record PoseRow(long id, long capturedAtMs, double x, double y, Double yawDegrees, String source, String frameId, String alignment) {}

    record SurfaceRow(long id, long capturedAtMs, double originX, double originY, double hitX, double hitY, String kind,
                      Double normalDegrees, String source, String frameId) {}

    record EventRow(long id, long capturedAtMs, String state, String source, String frameId, String detail) {}

    record QualityRow(long id, long capturedAtMs, double blurScore, double brightnessMean, boolean duplicateFrame,
                      int featureCount, Double spacingMeters, List<String> warnings) {}

    record PlanRow(UUID id, int version, String sceneSource, String frameId, String sceneJson, String boundsJson, String reconJson,
                   String priorityJson, String configJson, String evidenceJson, String plannedJson, String observedJson,
                   long plannedAfterMs, String createdBy, Instant createdAt, Instant supersededAt) {}

    static final int MAX_SURFACES_CONSIDERED = 20_000;
    static final int MAX_EVENTS_CONSIDERED = 2_000;

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    HudStore(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    SceneRow scene(UUID venueId, UUID organizationId, UUID captureId) {
        return jdbc.sql("""
                SELECT scene_json, config_json, scene_source, frame_id, bounds_estimate_json
                  FROM capture_hud_scene WHERE capture_session_id = :c AND venue_id = :v AND organization_id = :o
                """)
            .param("c", captureId).param("v", venueId).param("o", organizationId)
            .query((ResultSet rs, int i) -> new SceneRow(read(rs.getString("scene_json"), SceneDto.class),
                read(rs.getString("config_json"), new TypeReference<Map<String, Double>>() {}), rs.getString("scene_source"),
                rs.getString("frame_id"), rs.getString("bounds_estimate_json") == null ? null
                    : read(rs.getString("bounds_estimate_json"), BoundsView.class)))
            .optional().orElse(null);
    }

    /** The newest {@code limit} positions, in time order. */
    List<PoseRow> poses(UUID venueId, UUID organizationId, UUID captureId, int limit) {
        List<PoseRow> rows = new ArrayList<>(jdbc.sql("""
                SELECT id, captured_at_ms, x, y, yaw_degrees, source, frame_id, frame_alignment FROM capture_hud_pose_sample
                 WHERE capture_session_id = :c AND venue_id = :v AND organization_id = :o
                 ORDER BY captured_at_ms DESC, id DESC LIMIT :lim
                """)
            .param("c", captureId).param("v", venueId).param("o", organizationId).param("lim", limit)
            .query((rs, i) -> new PoseRow(rs.getLong("id"), rs.getLong("captured_at_ms"), rs.getDouble("x"), rs.getDouble("y"),
                (Double) rs.getObject("yaw_degrees"), rs.getString("source"), rs.getString("frame_id"), rs.getString("frame_alignment")))
            .list());
        rows.sort(Comparator.comparingLong(PoseRow::capturedAtMs).thenComparingLong(PoseRow::id));
        return rows;
    }

    List<SurfaceRow> surfaces(UUID venueId, UUID organizationId, UUID captureId) {
        List<SurfaceRow> rows = new ArrayList<>(jdbc.sql("""
                SELECT id, captured_at_ms, origin_x, origin_y, hit_x, hit_y, kind, normal_degrees, source, frame_id
                  FROM capture_hud_surface_sample
                 WHERE capture_session_id = :c AND venue_id = :v AND organization_id = :o
                 ORDER BY captured_at_ms DESC, id DESC LIMIT :lim
                """)
            .param("c", captureId).param("v", venueId).param("o", organizationId).param("lim", MAX_SURFACES_CONSIDERED)
            .query((rs, i) -> new SurfaceRow(rs.getLong("id"), rs.getLong("captured_at_ms"), rs.getDouble("origin_x"), rs.getDouble("origin_y"),
                rs.getDouble("hit_x"), rs.getDouble("hit_y"), rs.getString("kind"), (Double) rs.getObject("normal_degrees"),
                rs.getString("source"), rs.getString("frame_id")))
            .list());
        rows.sort(Comparator.comparingLong(SurfaceRow::capturedAtMs).thenComparingLong(SurfaceRow::id)); // hit order matters (fan fill)
        return rows;
    }

    List<EventRow> events(UUID venueId, UUID organizationId, UUID captureId) {
        List<EventRow> rows = new ArrayList<>(jdbc.sql("""
                SELECT id, captured_at_ms, state, source, frame_id, detail FROM capture_hud_tracking_event
                 WHERE capture_session_id = :c AND venue_id = :v AND organization_id = :o
                 ORDER BY captured_at_ms DESC, id DESC LIMIT :lim
                """)
            .param("c", captureId).param("v", venueId).param("o", organizationId).param("lim", MAX_EVENTS_CONSIDERED)
            .query((rs, i) -> new EventRow(rs.getLong("id"), rs.getLong("captured_at_ms"), rs.getString("state"), rs.getString("source"),
                rs.getString("frame_id"), rs.getString("detail")))
            .list());
        rows.sort(Comparator.comparingLong(EventRow::capturedAtMs).thenComparingLong(EventRow::id));
        return rows;
    }

    List<QualityRow> quality(UUID venueId, UUID organizationId, UUID captureId, int limit) {
        List<QualityRow> rows = new ArrayList<>(jdbc.sql("""
                SELECT id, captured_at_ms, blur_score, brightness_mean, duplicate_frame, feature_count, spacing_meters, warnings
                  FROM capture_hud_quality_sample
                 WHERE capture_session_id = :c AND venue_id = :v AND organization_id = :o
                 ORDER BY captured_at_ms DESC, id DESC LIMIT :lim
                """)
            .param("c", captureId).param("v", venueId).param("o", organizationId).param("lim", limit)
            .query((rs, i) -> new QualityRow(rs.getLong("id"), rs.getLong("captured_at_ms"), rs.getDouble("blur_score"),
                rs.getDouble("brightness_mean"), rs.getBoolean("duplicate_frame"), rs.getInt("feature_count"),
                (Double) rs.getObject("spacing_meters"), List.of((String[]) rs.getArray("warnings").getArray())))
            .list());
        rows.sort(Comparator.comparingLong(QualityRow::capturedAtMs).thenComparingLong(QualityRow::id));
        return rows;
    }

    /** Wall observations usable in a frame (rows without a frame count everywhere, like positions without one). */
    int wallCount(UUID venueId, UUID organizationId, UUID captureId, String frameId) {
        return jdbc.sql("""
                SELECT count(*) FROM capture_hud_surface_sample
                 WHERE capture_session_id = :c AND venue_id = :v AND organization_id = :o AND kind = 'WALL'
                   AND (frame_id IS NULL OR frame_id = :f)
                """)
            .param("c", captureId).param("v", venueId).param("o", organizationId).param("f", frameId == null ? "" : frameId)
            .query(Integer.class).single();
    }

    boolean hasVideoMedia(UUID venueId, UUID captureId) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM capture_media WHERE capture_session_id = :c AND venue_id = :v AND kind = 'VIDEO'
                                  AND status <> 'REJECTED')
                """).param("c", captureId).param("v", venueId).query(Boolean.class).single();
    }

    /** Counts of all stored evidence for the capture (not only the newest rows the live view considers). */
    Map<String, Map<String, Integer>> evidenceCounts(UUID venueId, UUID organizationId, UUID captureId) {
        Map<String, Map<String, Integer>> out = new java.util.LinkedHashMap<>();
        out.put("pose", group("SELECT source AS k, count(*) AS n FROM capture_hud_pose_sample", venueId, organizationId, captureId));
        out.put("surface", group("SELECT kind AS k, count(*) AS n FROM capture_hud_surface_sample", venueId, organizationId, captureId));
        out.put("event", group("SELECT state AS k, count(*) AS n FROM capture_hud_tracking_event", venueId, organizationId, captureId));
        out.put("quality", group("SELECT 'all' AS k, count(*) AS n FROM capture_hud_quality_sample", venueId, organizationId, captureId));
        out.put("frame", group("SELECT coalesce(frame_id, '(unspecified)') AS k, count(*) AS n FROM capture_hud_pose_sample", venueId,
            organizationId, captureId));
        return out;
    }

    private Map<String, Integer> group(String selectFrom, UUID venueId, UUID organizationId, UUID captureId) {
        Map<String, Integer> out = new java.util.TreeMap<>();
        jdbc.sql(selectFrom + " WHERE capture_session_id = :c AND venue_id = :v AND organization_id = :o GROUP BY 1")
            .param("c", captureId).param("v", venueId).param("o", organizationId)
            .query((rs, i) -> Map.entry(rs.getString("k"), rs.getInt("n"))).list()
            .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    long maxId(String table, UUID captureId) {
        return jdbc.sql("SELECT coalesce(max(id), 0) FROM " + table + " WHERE capture_session_id = :c").param("c", captureId)
            .query(Long.class).single();
    }

    List<PlanRow> plans(UUID venueId, UUID organizationId, UUID captureId) {
        return jdbc.sql("""
                SELECT id, version, scene_source, frame_id, scene_json, bounds_estimate_json, recon_json, priority_regions_json,
                       config_json, evidence_json, planned_json, observed_at_plan_json, planned_after_ms, created_by, created_at,
                       superseded_at
                  FROM capture_plan WHERE capture_session_id = :c AND venue_id = :v AND organization_id = :o ORDER BY version
                """)
            .param("c", captureId).param("v", venueId).param("o", organizationId)
            .query((rs, i) -> new PlanRow(rs.getObject("id", UUID.class), rs.getInt("version"), rs.getString("scene_source"),
                rs.getString("frame_id"), rs.getString("scene_json"), rs.getString("bounds_estimate_json"), rs.getString("recon_json"),
                rs.getString("priority_regions_json"), rs.getString("config_json"), rs.getString("evidence_json"),
                rs.getString("planned_json"), rs.getString("observed_at_plan_json"), rs.getLong("planned_after_ms"),
                rs.getString("created_by"), ts(rs, "created_at"), ts(rs, "superseded_at")))
            .list();
    }

    PlanRow currentPlan(UUID venueId, UUID organizationId, UUID captureId) {
        List<PlanRow> all = plans(venueId, organizationId, captureId);
        return all.stream().filter(p -> p.supersededAt() == null).reduce((a, b) -> b).orElse(null);
    }

    private static Instant ts(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialise HUD payload", e);
        }
    }

    <T> T read(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("corrupt HUD data", e);
        }
    }

    <T> T read(String json, TypeReference<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("corrupt HUD data", e);
        }
    }
}
