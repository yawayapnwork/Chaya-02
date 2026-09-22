package dev.chaya.api.hud;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.chaya.api.audit.AuditService;
import dev.chaya.api.capture.CaptureService;
import dev.chaya.api.capture.CaptureService.CaptureView;
import dev.chaya.api.hud.CaptureHudDtos.HudStatus;
import dev.chaya.api.hud.CaptureHudDtos.PathPointView;
import dev.chaya.api.hud.CaptureHudDtos.PlannedWaypointView;
import dev.chaya.api.hud.CaptureHudDtos.PoseBatchRequest;
import dev.chaya.api.hud.CaptureHudDtos.PoseSampleDto;
import dev.chaya.api.hud.CaptureHudDtos.PositionView;
import dev.chaya.api.hud.CaptureHudDtos.QualityBatchRequest;
import dev.chaya.api.hud.CaptureHudDtos.QualitySampleDto;
import dev.chaya.api.hud.CaptureHudDtos.QualitySummaryView;
import dev.chaya.api.hud.CaptureHudDtos.ReshootRecommendationView;
import dev.chaya.api.hud.CaptureHudDtos.SetSceneRequest;
import dev.chaya.api.hud.CaptureHudDtos.UncoveredZoneView;
import dev.chaya.api.planning.CapturePathPlanner;
import dev.chaya.api.planning.PlanRequest;
import dev.chaya.api.planning.PlanResult;
import dev.chaya.api.planning.PlanningException;
import dev.chaya.api.planning.api.RoutePlanDtos;
import dev.chaya.api.planning.api.RoutePlanDtos.RoutePlanRequest;
import dev.chaya.api.planning.api.RoutePlanDtos.SampleDto;
import dev.chaya.api.planning.api.RoutePlanDtos.SceneDto;
import dev.chaya.api.security.Actor;
import dev.chaya.api.web.ApiException;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Live capture-quality and coverage HUD. Scene, pose and quality samples are durable (Postgres); the coverage
 * estimate, uncovered zones and planned path are never stored, they are recomputed on every read from the same
 * deterministic {@link CapturePathPlanner} that route-plan uses, fed with the trajectory reported so far instead
 * of a 10 s reconnaissance lap. See docs/capture-hud.md.
 */
@Service
public class CaptureHudService {

    private record SceneRow(SceneDto scene, Map<String, Double> config) {}

    private record PoseRow(long capturedAtMs, double x, double y, Double yawDegrees) {}

    private final JdbcClient jdbc;
    private final CaptureService captures;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final HudProperties props;
    private final CapturePathPlanner planner = new CapturePathPlanner();

    /** Bumped on every write so the SSE broadcaster knows a capture's status may have changed without recomputing it itself. */
    private final Map<UUID, AtomicLong> versions = new ConcurrentHashMap<>();

    public CaptureHudService(JdbcClient jdbc, CaptureService captures, AuditService audit, TransactionTemplate tx,
                             ObjectMapper mapper, HudProperties props) {
        this.jdbc = jdbc;
        this.captures = captures;
        this.audit = audit;
        this.tx = tx;
        this.mapper = mapper;
        this.props = props;
    }

    public long version(UUID captureId) {
        AtomicLong v = versions.get(captureId);
        return v == null ? 0 : v.get();
    }

    private void touch(UUID captureId) {
        versions.computeIfAbsent(captureId, k -> new AtomicLong()).incrementAndGet();
    }

    // ---- writes ---------------------------------------------------------------------------------------------

    public void setScene(Actor actor, UUID venueId, UUID captureId, SetSceneRequest body) {
        CaptureView capture = requireCapturing(actor, venueId, captureId);
        // Validate immediately (with no trajectory yet, since none has been reported) so a bad room outline is
        // rejected at draw time, not discovered later as "coverage unavailable" while the operator is mid-capture.
        // The planner builds the walkable grid from the scene before it looks at the trajectory at all, so
        // NO_USABLE_TRAJECTORY here means the scene itself was fine; any other code is a real geometry problem.
        try {
            PlanRequest probe = RoutePlanDtos.toDomain(new RoutePlanRequest(body.scene(), List.of(), List.of(), List.of(), body.config()));
            planner.plan(probe);
        } catch (PlanningException e) {
            if (!"NO_USABLE_TRAJECTORY".equals(e.code())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, e.code(), e.getMessage());
            }
        }
        String sceneJson = toJson(body.scene());
        String configJson = toJson(body.config() == null ? Map.of() : body.config());
        tx.executeWithoutResult(s -> {
            jdbc.sql("""
                    INSERT INTO capture_hud_scene (capture_session_id, venue_id, organization_id, scene_json, config_json)
                    VALUES (:c, :v, :o, CAST(:s AS jsonb), CAST(:cfg AS jsonb))
                    ON CONFLICT (capture_session_id)
                    DO UPDATE SET scene_json = EXCLUDED.scene_json, config_json = EXCLUDED.config_json, updated_at = now()
                    """)
                .param("c", capture.id()).param("v", venueId).param("o", actor.organizationId())
                .param("s", sceneJson).param("cfg", configJson)
                .update();
            audit.success(actor, venueId, "capture.hud_scene_set", "capture_session", capture.id(), Map.of("areas", body.scene().areas().size()));
        });
        touch(captureId);
    }

    public void addPoses(Actor actor, UUID venueId, UUID captureId, PoseBatchRequest body) {
        CaptureView capture = requireCapturing(actor, venueId, captureId);
        if (body.samples().size() > props.maxPoseSamplesPerRequest()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TOO_MANY_SAMPLES",
                "at most " + props.maxPoseSamplesPerRequest() + " pose samples per request");
        }
        for (PoseSampleDto s : body.samples()) {
            if (!finite(s.x()) || !finite(s.y()) || (s.yawDegrees() != null && !finite(s.yawDegrees()))) {
                continue; // dropped, same as a raw trajectory sample the normaliser would discard
            }
            String source = s.source() == null || s.source().isBlank() ? "manual" : s.source();
            jdbc.sql("""
                    INSERT INTO capture_hud_pose_sample (capture_session_id, venue_id, organization_id, captured_at_ms, x, y, yaw_degrees, source)
                    VALUES (:c, :v, :o, :t, :x, :y, :yaw, :src)
                    """)
                .param("c", capture.id()).param("v", venueId).param("o", actor.organizationId())
                .param("t", s.capturedAtMs()).param("x", s.x()).param("y", s.y())
                .param("yaw", s.yawDegrees()).param("src", source)
                .update();
        }
        touch(captureId);
    }

    public void addQuality(Actor actor, UUID venueId, UUID captureId, QualityBatchRequest body) {
        CaptureView capture = requireCapturing(actor, venueId, captureId);
        if (body.samples().size() > props.maxQualitySamplesPerRequest()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TOO_MANY_SAMPLES",
                "at most " + props.maxQualitySamplesPerRequest() + " quality samples per request");
        }
        for (QualitySampleDto s : body.samples()) {
            if (!finite(s.blurScore()) || !finite(s.brightnessMean())) {
                continue;
            }
            jdbc.sql("""
                    INSERT INTO capture_hud_quality_sample
                        (capture_session_id, venue_id, organization_id, captured_at_ms, blur_score, brightness_mean,
                         shadow_clip_fraction, highlight_clip_fraction, motion_score, duplicate_frame, feature_count,
                         spacing_meters, warnings)
                    VALUES (:c, :v, :o, :t, :blur, :bright, :shadow, :highlight, :motion, :dup, :feat, :spacing,
                        ARRAY(SELECT jsonb_array_elements_text(CAST(:warn AS jsonb))))
                    """)
                .param("c", capture.id()).param("v", venueId).param("o", actor.organizationId())
                .param("t", s.capturedAtMs()).param("blur", s.blurScore()).param("bright", clamp01to255(s.brightnessMean()))
                .param("shadow", clamp01(s.shadowClipFraction())).param("highlight", clamp01(s.highlightClipFraction()))
                .param("motion", s.motionScore()).param("dup", s.duplicateFrame()).param("feat", Math.max(0, s.featureCount()))
                .param("spacing", s.spacingMeters())
                .param("warn", toJson(s.warnings() == null ? List.of() : s.warnings()))
                .update();
        }
        touch(captureId);
    }

    private CaptureView requireCapturing(Actor actor, UUID venueId, UUID captureId) {
        CaptureView capture = captures.get(actor, venueId, captureId); // venue-guarded; 404 for anything not visible
        if (!capture.status().acceptsMedia()) {
            throw new ApiException(HttpStatus.CONFLICT, "CAPTURE_NOT_CAPTURING",
                "capture is " + capture.status() + "; the HUD only accepts data while it is CREATED or UPLOADING");
        }
        return capture;
    }

    private static boolean finite(double v) {
        return Double.isFinite(v);
    }

    private static double clamp01(double v) {
        return Double.isFinite(v) ? Math.max(0, Math.min(1, v)) : 0;
    }

    private static double clamp01to255(double v) {
        return Double.isFinite(v) ? Math.max(0, Math.min(255, v)) : 0;
    }

    // ---- status ---------------------------------------------------------------------------------------------

    private static final int RECENT_QUALITY_WINDOW = 60;
    private static final int WARNING_STREAK_WINDOW = 5;
    private static final int WARNING_STREAK_THRESHOLD = 3;

    public HudStatus status(Actor actor, UUID venueId, UUID captureId) {
        CaptureView capture = captures.get(actor, venueId, captureId); // venue-guarded; readable in any status
        return computeStatus(venueId, actor.organizationId(), capture.id());
    }

    /**
     * Same computation, for the SSE broadcaster's periodic tick: the subscribing actor was already tenant-checked
     * once when the stream was opened (see CaptureHudStreamController), so the scheduled tick does not repeat a
     * per-actor authorization query for every capture on every tick.
     */
    HudStatus statusForBroadcast(UUID venueId, UUID organizationId, UUID captureId) {
        return computeStatus(venueId, organizationId, captureId);
    }

    private HudStatus computeStatus(UUID venueId, UUID organizationId, UUID captureId) {
        long now = System.currentTimeMillis();

        SceneRow scene = loadScene(venueId, organizationId, captureId);
        List<PoseRow> poses = loadPoses(venueId, organizationId, captureId);
        List<QualityRow> quality = loadQuality(venueId, organizationId, captureId);

        boolean trackingAvailable = false;
        String trackingReason = "no position sample has been reported for this capture yet";
        PositionView position = null;
        List<PathPointView> pathSoFar = poses.stream().map(p -> new PathPointView(p.x(), p.y())).toList();
        if (!poses.isEmpty()) {
            PoseRow last = poses.get(poses.size() - 1);
            position = new PositionView(last.x(), last.y(), last.yawDegrees(), last.capturedAtMs());
            long age = now - last.capturedAtMs();
            if (age <= props.positionStaleAfterMs()) {
                trackingAvailable = true;
                trackingReason = null;
            } else {
                trackingReason = "last position sample is " + (age / 1000) + " s old (over " + (props.positionStaleAfterMs() / 1000) + " s)";
            }
        }

        boolean coverageAvailable = false;
        String coverageReason;
        Double coveragePercent = null;
        Double weightedCoveragePercent = null;
        Double uncoveredAreaM2 = null;
        List<UncoveredZoneView> uncoveredZones = List.of();
        List<PlannedWaypointView> plannedPath = List.of();
        List<String> planWarnings = List.of();

        if (scene == null) {
            coverageReason = "room outline not set for this capture yet";
        } else {
            try {
                PlanResult result = replan(scene, poses);
                coverageAvailable = true;
                coverageReason = null;
                coveragePercent = result.baseline().coveragePercent();
                weightedCoveragePercent = result.baseline().weightedCoveragePercent();
                uncoveredAreaM2 = result.baseline().uncoveredAreaM2();
                uncoveredZones = result.uncoveredRegions().stream()
                    .map(r -> new UncoveredZoneView(r.centroidX(), r.centroidY(), r.areaM2(), r.reason())).toList();
                plannedPath = result.waypoints().stream()
                    .map(w -> new PlannedWaypointView(w.order(), w.x(), w.y(), w.yawDegrees(), w.type(), w.reason(), w.expectedCoverageGainM2()))
                    .toList();
                planWarnings = result.diagnostics().warnings();
            } catch (PlanningException e) {
                coverageReason = e.getMessage();
            }
        }

        QualitySummaryView qualitySummary = summarizeQuality(quality);
        List<String> warnings = new ArrayList<>(planWarnings);
        if (!trackingAvailable) {
            warnings.add("TRACKING_UNAVAILABLE: " + trackingReason);
        }
        if (!coverageAvailable) {
            warnings.add("COVERAGE_UNAVAILABLE: " + coverageReason);
        }
        List<ReshootRecommendationView> reshoots = reshootRecommendations(quality, position, uncoveredZones);

        return new HudStatus(trackingAvailable, trackingReason, position, pathSoFar,
            scene != null, coverageAvailable, coverageReason, coveragePercent, weightedCoveragePercent, uncoveredAreaM2,
            uncoveredZones, plannedPath, qualitySummary, warnings, reshoots, Instant.ofEpochMilli(now));
    }

    /** Reruns the deterministic planner against the room outline and the trajectory reported so far. */
    private PlanResult replan(SceneRow scene, List<PoseRow> poses) {
        List<SampleDto> trajectory = new ArrayList<>(poses.size());
        long t0 = poses.isEmpty() ? 0 : poses.get(0).capturedAtMs();
        for (PoseRow p : poses) {
            trajectory.add(new SampleDto((p.capturedAtMs() - t0) / 1000.0, p.x(), p.y(), p.yawDegrees()));
        }
        PlanRequest request = RoutePlanDtos.toDomain(new RoutePlanRequest(scene.scene(), trajectory, List.of(), List.of(), scene.config()));
        return planner.plan(request);
    }

    private QualitySummaryView summarizeQuality(List<QualityRow> quality) {
        if (quality.isEmpty()) {
            return null;
        }
        List<QualityRow> recent = quality.size() <= RECENT_QUALITY_WINDOW
            ? quality : quality.subList(quality.size() - RECENT_QUALITY_WINDOW, quality.size());
        double blur = 0, bright = 0, feat = 0, spacingSum = 0;
        int spacingCount = 0, dupCount = 0;
        for (QualityRow q : recent) {
            blur += q.blurScore();
            bright += q.brightnessMean();
            feat += q.featureCount();
            if (q.duplicateFrame()) dupCount++;
            if (q.spacingMeters() != null) {
                spacingSum += q.spacingMeters();
                spacingCount++;
            }
        }
        int n = recent.size();
        QualityRow last = quality.get(quality.size() - 1);
        return new QualitySummaryView(n, blur / n, bright / n, (double) dupCount / n, feat / n,
            spacingCount == 0 ? null : spacingSum / spacingCount, last.capturedAtMs());
    }

    /**
     * Two independent sources of "what needs attention": target areas the plan could not confidently cover, and
     * spots where several recent frames in a row shared the same quality warning (a sustained problem, not a
     * single bad frame). Positions are attached when a pose sample near the same time is known; otherwise the
     * recommendation is reason-only rather than inventing a location.
     */
    private List<ReshootRecommendationView> reshootRecommendations(List<QualityRow> quality, PositionView currentPosition,
                                                                    List<UncoveredZoneView> uncoveredZones) {
        List<ReshootRecommendationView> out = new ArrayList<>();
        for (UncoveredZoneView z : uncoveredZones) {
            out.add(new ReshootRecommendationView("UNCOVERED_AREA (" + z.reason() + ")", z.centroidX(), z.centroidY()));
        }
        if (quality.size() >= WARNING_STREAK_WINDOW) {
            List<QualityRow> tail = quality.subList(quality.size() - WARNING_STREAK_WINDOW, quality.size());
            Map<String, Long> counts = new java.util.HashMap<>();
            for (QualityRow q : tail) {
                for (String w : q.warnings()) {
                    counts.merge(w, 1L, Long::sum);
                }
            }
            counts.entrySet().stream()
                .filter(e -> e.getValue() >= WARNING_STREAK_THRESHOLD)
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(e -> out.add(new ReshootRecommendationView(
                    "REPEATED_" + e.getKey() + " (" + e.getValue() + "/" + WARNING_STREAK_WINDOW + " recent frames)",
                    currentPosition == null ? null : currentPosition.x(), currentPosition == null ? null : currentPosition.y())));
        }
        return out;
    }

    // ---- persistence helpers ----------------------------------------------------------------------------------

    private record QualityRow(long capturedAtMs, double blurScore, double brightnessMean, boolean duplicateFrame,
                              int featureCount, Double spacingMeters, List<String> warnings) {}

    private SceneRow loadScene(UUID venueId, UUID organizationId, UUID captureId) {
        return jdbc.sql("SELECT scene_json, config_json FROM capture_hud_scene WHERE capture_session_id = :c AND venue_id = :v AND organization_id = :o")
            .param("c", captureId).param("v", venueId).param("o", organizationId)
            .query((ResultSet rs, int i) -> {
                SceneDto sceneDto = readValue(rs.getString("scene_json"), SceneDto.class);
                @SuppressWarnings("unchecked")
                Map<String, Double> cfg = readValue(rs.getString("config_json"), Map.class);
                return new SceneRow(sceneDto, cfg);
            }).optional().orElse(null);
    }

    private List<PoseRow> loadPoses(UUID venueId, UUID organizationId, UUID captureId) {
        List<PoseRow> rows = jdbc.sql("""
                SELECT captured_at_ms, x, y, yaw_degrees FROM capture_hud_pose_sample
                 WHERE capture_session_id = :c AND venue_id = :v AND organization_id = :o
                 ORDER BY captured_at_ms DESC LIMIT :lim
                """)
            .param("c", captureId).param("v", venueId).param("o", organizationId).param("lim", props.maxPoseSamplesConsidered())
            .query((rs, i) -> new PoseRow(rs.getLong("captured_at_ms"), rs.getDouble("x"), rs.getDouble("y"),
                (Double) rs.getObject("yaw_degrees")))
            .list();
        List<PoseRow> chronological = new ArrayList<>(rows);
        chronological.sort(Comparator.comparingLong(PoseRow::capturedAtMs));
        return chronological;
    }

    private List<QualityRow> loadQuality(UUID venueId, UUID organizationId, UUID captureId) {
        List<QualityRow> rows = jdbc.sql("""
                SELECT captured_at_ms, blur_score, brightness_mean, duplicate_frame, feature_count, spacing_meters, warnings
                  FROM capture_hud_quality_sample
                 WHERE capture_session_id = :c AND venue_id = :v AND organization_id = :o
                 ORDER BY captured_at_ms DESC LIMIT :lim
                """)
            .param("c", captureId).param("v", venueId).param("o", organizationId).param("lim", RECENT_QUALITY_WINDOW * 4)
            .query((rs, i) -> new QualityRow(rs.getLong("captured_at_ms"), rs.getDouble("blur_score"), rs.getDouble("brightness_mean"),
                rs.getBoolean("duplicate_frame"), rs.getInt("feature_count"), (Double) rs.getObject("spacing_meters"),
                List.of((String[]) rs.getArray("warnings").getArray())))
            .list();
        List<QualityRow> chronological = new ArrayList<>(rows);
        chronological.sort(Comparator.comparingLong(QualityRow::capturedAtMs));
        return chronological;
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialise HUD payload", e);
        }
    }

    private <T> T readValue(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("corrupt HUD data", e);
        }
    }
}
