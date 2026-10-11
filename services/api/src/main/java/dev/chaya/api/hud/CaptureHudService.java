package dev.chaya.api.hud;

import dev.chaya.api.audit.AuditService;
import dev.chaya.api.capture.CaptureService;
import dev.chaya.api.capture.CaptureService.CaptureView;
import dev.chaya.api.hud.CaptureHudDtos.BoundsView;
import dev.chaya.api.hud.CaptureHudDtos.HudStatus;
import dev.chaya.api.hud.CaptureHudDtos.NextWaypointView;
import dev.chaya.api.hud.CaptureHudDtos.PathPointView;
import dev.chaya.api.hud.CaptureHudDtos.PlanStatusView;
import dev.chaya.api.hud.CaptureHudDtos.PlannedWaypointView;
import dev.chaya.api.hud.CaptureHudDtos.PoseBatchRequest;
import dev.chaya.api.hud.CaptureHudDtos.PoseSampleDto;
import dev.chaya.api.hud.CaptureHudDtos.PositionView;
import dev.chaya.api.hud.CaptureHudDtos.QualityBatchRequest;
import dev.chaya.api.hud.CaptureHudDtos.QualitySampleDto;
import dev.chaya.api.hud.CaptureHudDtos.QualitySummaryView;
import dev.chaya.api.hud.CaptureHudDtos.ReshootRecommendationView;
import dev.chaya.api.hud.CaptureHudDtos.SetSceneRequest;
import dev.chaya.api.hud.CaptureHudDtos.SurfaceBatchRequest;
import dev.chaya.api.hud.CaptureHudDtos.SurfaceSampleDto;
import dev.chaya.api.hud.CaptureHudDtos.TrackingEventBatchRequest;
import dev.chaya.api.hud.CaptureHudDtos.TrackingEventDto;
import dev.chaya.api.hud.CaptureHudDtos.UncoveredZoneView;
import dev.chaya.api.hud.HudStore.EventRow;
import dev.chaya.api.hud.HudStore.PlanRow;
import dev.chaya.api.hud.HudStore.PoseRow;
import dev.chaya.api.hud.HudStore.QualityRow;
import dev.chaya.api.hud.HudStore.SceneRow;
import dev.chaya.api.planning.CapturePathPlanner;
import dev.chaya.api.planning.PlanRequest;
import dev.chaya.api.planning.PlanResult;
import dev.chaya.api.planning.PlanningException;
import dev.chaya.api.planning.Reconnaissance;
import dev.chaya.api.planning.TrajectorySample;
import dev.chaya.api.planning.api.RoutePlanDtos;
import dev.chaya.api.planning.api.RoutePlanDtos.RoutePlanRequest;
import dev.chaya.api.security.Actor;
import dev.chaya.api.web.ApiException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Live capture-quality and coverage HUD. Scene, positions, surface observations, tracking events and quality samples
 * are durable (Postgres); coverage, uncovered zones and plan progress are never stored, they are recomputed on every
 * read from the same deterministic {@link CapturePathPlanner} that route-plan uses. Persisted scan plans live in
 * capture_plan (CapturePlanService). See docs/capture-hud.md.
 */
@Service
public class CaptureHudService {

    static final Set<String> TRACKING_STATES = Set.of("SESSION_STARTED", "TRACKING", "LIMITED", "LOST", "INTERRUPTED", "RESUMED",
        "SESSION_ENDED", "PERMISSION_DENIED", "UNSUPPORTED", "ORIENTATION_CHANGED", "REALIGNED");
    static final Set<String> EVENT_SOURCES = Set.of("manual", "webxr", "arkit", "camera", "operator-trajectory");
    /** A planned waypoint counts as visited within this distance (and, with headings, facing within half the field of view). */
    static final double VISIT_RADIUS_METERS = 0.5;

    private final JdbcClient jdbc;
    private final CaptureService captures;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final HudProperties props;
    private final HudStore store;
    private final CapturePathPlanner planner = new CapturePathPlanner();

    /** Bumped on every write so the SSE broadcaster knows a capture's status may have changed without recomputing it itself. */
    private final Map<UUID, AtomicLong> versions = new ConcurrentHashMap<>();

    public CaptureHudService(JdbcClient jdbc, CaptureService captures, AuditService audit, TransactionTemplate tx,
                             HudProperties props, HudStore store) {
        this.jdbc = jdbc;
        this.captures = captures;
        this.audit = audit;
        this.tx = tx;
        this.props = props;
        this.store = store;
    }

    public long version(UUID captureId) {
        AtomicLong v = versions.get(captureId);
        return v == null ? 0 : v.get();
    }

    void touch(UUID captureId) {
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
            planner.plan(RoutePlanDtos.toDomain(new RoutePlanRequest(body.scene(), List.of(), List.of(), List.of(), body.config())));
        } catch (PlanningException e) {
            if (!"NO_USABLE_TRAJECTORY".equals(e.code())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, e.code(), e.getMessage());
            }
        }
        String frameId = body.frameId() == null || body.frameId().isBlank() ? "plan-" + UUID.randomUUID() : checkFrame(body.frameId());
        storeScene(actor, venueId, capture.id(), body.scene(), body.config(), "MEASURED", frameId, null);
    }

    void storeScene(Actor actor, UUID venueId, UUID captureId, RoutePlanDtos.SceneDto scene, Map<String, Double> config, String source,
                    String frameId, BoundsView bounds) {
        String sceneJson = store.json(scene);
        String configJson = store.json(config == null ? Map.of() : config);
        String boundsJson = bounds == null ? null : store.json(bounds);
        tx.executeWithoutResult(s -> {
            jdbc.sql("""
                    INSERT INTO capture_hud_scene (capture_session_id, venue_id, organization_id, scene_json, config_json, scene_source,
                                                   frame_id, bounds_estimate_json)
                    VALUES (:c, :v, :o, CAST(:s AS jsonb), CAST(:cfg AS jsonb), :src, :f, CAST(:b AS jsonb))
                    ON CONFLICT (capture_session_id)
                    DO UPDATE SET scene_json = EXCLUDED.scene_json, config_json = EXCLUDED.config_json, scene_source = EXCLUDED.scene_source,
                                  frame_id = EXCLUDED.frame_id, bounds_estimate_json = EXCLUDED.bounds_estimate_json, updated_at = now()
                    """)
                .param("c", captureId).param("v", venueId).param("o", actor.organizationId())
                .param("s", sceneJson).param("cfg", configJson).param("src", source).param("f", frameId).param("b", boundsJson)
                .update();
            Map<String, Object> meta = new HashMap<>();
            meta.put("areas", scene.areas().size());
            meta.put("sceneSource", source);
            if (bounds != null) {
                meta.put("boundsStatus", bounds.status());
                meta.put("boundsConfidence", bounds.confidence());
            }
            audit.success(actor, venueId, "capture.hud_scene_set", "capture_session", captureId, meta);
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
            String source = s.source() == null || s.source().isBlank() ? Reconnaissance.MANUAL_SOURCE : s.source();
            checkPoseSource(source);
            if (Reconnaissance.TRACKED_SOURCES.contains(source) && (s.frameId() == null || s.frameId().isBlank())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "FRAME_ID_REQUIRED",
                    "positions from " + source + " are only meaningful in the tracking session's own frame: frameId is required");
            }
            String alignment = s.frameAlignment() == null ? "NATIVE" : s.frameAlignment();
            if (!Set.of("NATIVE", "OPERATOR_ALIGNED").contains(alignment)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_FRAME_ALIGNMENT", "frameAlignment must be NATIVE or OPERATOR_ALIGNED");
            }
        }
        for (PoseSampleDto s : body.samples()) {
            if (!finite(s.x()) || !finite(s.y()) || (s.yawDegrees() != null && !finite(s.yawDegrees()))) {
                continue; // dropped, same as a raw trajectory sample the normaliser would discard
            }
            String source = s.source() == null || s.source().isBlank() ? Reconnaissance.MANUAL_SOURCE : s.source();
            jdbc.sql("""
                    INSERT INTO capture_hud_pose_sample (capture_session_id, venue_id, organization_id, captured_at_ms, x, y, yaw_degrees,
                                                         source, frame_id, frame_alignment)
                    VALUES (:c, :v, :o, :t, :x, :y, :yaw, :src, :f, :al)
                    """)
                .param("c", capture.id()).param("v", venueId).param("o", actor.organizationId())
                .param("t", s.capturedAtMs()).param("x", s.x()).param("y", s.y())
                .param("yaw", s.yawDegrees()).param("src", source).param("f", s.frameId() == null ? null : checkFrame(s.frameId()))
                .param("al", s.frameAlignment() == null ? "NATIVE" : s.frameAlignment())
                .update();
        }
        touch(captureId);
    }

    public void addSurfaces(Actor actor, UUID venueId, UUID captureId, SurfaceBatchRequest body) {
        CaptureView capture = requireCapturing(actor, venueId, captureId);
        if (body.samples().size() > props.maxPoseSamplesPerRequest()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TOO_MANY_SAMPLES",
                "at most " + props.maxPoseSamplesPerRequest() + " surface samples per request");
        }
        for (SurfaceSampleDto s : body.samples()) {
            if (!Set.of("WALL", "FLOOR").contains(s.kind())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SURFACE_KIND", "kind must be WALL or FLOOR");
            }
            String source = s.source() == null ? "" : s.source();
            if (!Reconnaissance.TRACKED_SOURCES.contains(source) && !Reconnaissance.IMPORTED_SOURCE.equals(source)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_SURFACE_SOURCE", "surface observations come from a device "
                    + "tracker (webxr, arkit) or an imported trajectory (operator-trajectory); a manual position carries no surface");
            }
            if (Reconnaissance.TRACKED_SOURCES.contains(source) && (s.frameId() == null || s.frameId().isBlank())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "FRAME_ID_REQUIRED", "surface observations from " + source + " need frameId");
            }
        }
        for (SurfaceSampleDto s : body.samples()) {
            if (!finite(s.originX()) || !finite(s.originY()) || !finite(s.hitX()) || !finite(s.hitY())
                || (s.normalDegrees() != null && !finite(s.normalDegrees()))) {
                continue;
            }
            jdbc.sql("""
                    INSERT INTO capture_hud_surface_sample (capture_session_id, venue_id, organization_id, captured_at_ms, origin_x, origin_y,
                                                            hit_x, hit_y, kind, normal_degrees, source, frame_id)
                    VALUES (:c, :v, :o, :t, :ox, :oy, :hx, :hy, :k, :n, :src, :f)
                    """)
                .param("c", capture.id()).param("v", venueId).param("o", actor.organizationId()).param("t", s.capturedAtMs())
                .param("ox", s.originX()).param("oy", s.originY()).param("hx", s.hitX()).param("hy", s.hitY()).param("k", s.kind())
                .param("n", s.normalDegrees()).param("src", s.source()).param("f", s.frameId() == null ? null : checkFrame(s.frameId()))
                .update();
        }
        touch(captureId);
    }

    public void addTrackingEvents(Actor actor, UUID venueId, UUID captureId, TrackingEventBatchRequest body) {
        CaptureView capture = requireCapturing(actor, venueId, captureId);
        if (body.events().size() > props.maxQualitySamplesPerRequest()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TOO_MANY_SAMPLES",
                "at most " + props.maxQualitySamplesPerRequest() + " tracking events per request");
        }
        for (TrackingEventDto e : body.events()) {
            if (!TRACKING_STATES.contains(e.state())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TRACKING_STATE", "state must be one of " + new java.util.TreeSet<>(TRACKING_STATES));
            }
            if (!EVENT_SOURCES.contains(e.source())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_EVENT_SOURCE", "source must be one of " + new java.util.TreeSet<>(EVENT_SOURCES));
            }
        }
        for (TrackingEventDto e : body.events()) {
            String detail = e.detail() == null ? null : e.detail().length() > 500 ? e.detail().substring(0, 500) : e.detail();
            jdbc.sql("""
                    INSERT INTO capture_hud_tracking_event (capture_session_id, venue_id, organization_id, captured_at_ms, state, source,
                                                            frame_id, detail)
                    VALUES (:c, :v, :o, :t, :s, :src, :f, :d)
                    """)
                .param("c", capture.id()).param("v", venueId).param("o", actor.organizationId()).param("t", e.capturedAtMs())
                .param("s", e.state()).param("src", e.source()).param("f", e.frameId() == null ? null : checkFrame(e.frameId()))
                .param("d", detail)
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
                .param("warn", store.json(s.warnings() == null ? List.of() : s.warnings()))
                .update();
        }
        touch(captureId);
    }

    CaptureView requireCapturing(Actor actor, UUID venueId, UUID captureId) {
        CaptureView capture = captures.get(actor, venueId, captureId); // venue-guarded; 404 for anything not visible
        if (!capture.status().acceptsMedia()) {
            throw new ApiException(HttpStatus.CONFLICT, "CAPTURE_NOT_CAPTURING",
                "capture is " + capture.status() + "; the HUD only accepts data while it is CREATED or UPLOADING");
        }
        return capture;
    }

    private static void checkPoseSource(String source) {
        if (Reconnaissance.SOURCES.contains(source)) {
            return;
        }
        if (source.toLowerCase(Locale.ROOT).contains("video")) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VIDEO_IS_NOT_A_POSE_SOURCE", Reconnaissance.VIDEO_ONLY);
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_POSE_SOURCE",
            "source must be one of " + new java.util.TreeSet<>(Reconnaissance.SOURCES));
    }

    private static String checkFrame(String frameId) {
        if (frameId.isBlank() || frameId.length() > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_FRAME_ID", "frameId must be 1 to 100 characters");
        }
        return frameId;
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
    private static final int MAX_ZONE_RECOMMENDATIONS = 5;
    private static final long VISIT_QUALITY_WINDOW_MS = 1500;
    private static final Set<String> CAPTURE_DEFECTS = Set.of("BLUR", "UNDEREXPOSED", "OVEREXPOSED", "LOW_FEATURE_COUNT", "FAST_MOTION");
    private static final Set<String> LOSS_STATES = Set.of("LOST", "LIMITED", "INTERRUPTED", "SESSION_ENDED", "PERMISSION_DENIED", "UNSUPPORTED");

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

        SceneRow scene = store.scene(venueId, organizationId, captureId);
        List<PoseRow> poses = store.poses(venueId, organizationId, captureId, props.maxPoseSamplesConsidered());
        List<EventRow> events = store.events(venueId, organizationId, captureId);
        List<QualityRow> quality = store.quality(venueId, organizationId, captureId, RECENT_QUALITY_WINDOW * 4);
        long base = HudPlanning.base(poses, events, now);
        Reconnaissance.Trajectory tr = Reconnaissance.trajectory(HudPlanning.evidence(poses, base), HudPlanning.events(events, base),
            scene == null ? null : scene.frameId(), HudPlanning.REQUIREMENTS);
        String frame = tr.frameId();
        int walls = store.wallCount(venueId, organizationId, captureId, frame);
        Reconnaissance.Assessment recon = Reconnaissance.assess(HudPlanning.evidence(poses, base), HudPlanning.events(events, base), frame,
            walls, scene != null && "MEASURED".equals(scene.source()), store.hasVideoMedia(venueId, captureId), HudPlanning.REQUIREMENTS);

        // ---- position and tracking state: only live sources (never an imported trajectory) say where the operator is now
        PoseRow lastLive = null;
        for (int i = poses.size() - 1; i >= 0 && lastLive == null; i--) {
            if (!Reconnaissance.IMPORTED_SOURCE.equals(poses.get(i).source())) {
                lastLive = poses.get(i);
            }
        }
        EventRow lastEvent = events.isEmpty() ? null : events.get(events.size() - 1);
        boolean lossAfterLastPosition = lastEvent != null && LOSS_STATES.contains(lastEvent.state())
            && (lastLive == null || lastEvent.capturedAtMs() >= lastLive.capturedAtMs());
        String trackingState;
        String trackingReason = null;
        PositionView position = null;
        if (lossAfterLastPosition) {
            trackingState = lastEvent.state().equals("SESSION_ENDED") ? "SESSION_ENDED" : lastEvent.state();
            trackingReason = "tracking " + lastEvent.state().toLowerCase(Locale.ROOT).replace('_', ' ')
                + (lastEvent.detail() == null ? "" : ": " + lastEvent.detail());
        } else if (lastLive == null) {
            boolean imported = poses.stream().anyMatch(p -> Reconnaissance.IMPORTED_SOURCE.equals(p.source()));
            trackingState = imported ? "IMPORTED_ONLY" : "NOT_STARTED";
            trackingReason = imported ? "only an imported trajectory has been reported; it is not a live position"
                : "no position sample has been reported for this capture yet";
        } else if (lastLive.frameId() != null && !Objects.equals(lastLive.frameId(), frame)) {
            trackingState = "FRAME_MISMATCH";
            trackingReason = "the latest positions come from a new tracking session whose frame is not aligned with this plan; re-align to continue";
        } else {
            boolean manual = Reconnaissance.MANUAL_SOURCE.equals(lastLive.source());
            long age = now - lastLive.capturedAtMs();
            if (age <= props.positionStaleAfterMs()) {
                trackingState = manual ? "MANUAL" : "TRACKING";
            } else {
                trackingState = manual ? "MANUAL" : "STALE";
                trackingReason = "last position sample is " + (age / 1000) + " s old (over " + (props.positionStaleAfterMs() / 1000) + " s)";
            }
        }
        if (lastLive != null && !"FRAME_MISMATCH".equals(trackingState)) {
            boolean manual = Reconnaissance.MANUAL_SOURCE.equals(lastLive.source());
            position = new PositionView(lastLive.x(), lastLive.y(), lastLive.yawDegrees(), lastLive.capturedAtMs(), lastLive.source(),
                manual ? "MANUAL" : "TRACKED");
        }
        boolean trackingAvailable = trackingReason == null;
        List<PathPointView> pathSoFar = HudPlanning.path(tr);

        // ---- coverage, plan and progress ----------------------------------------------------------------------------
        boolean coverageAvailable = false;
        String coverageReason;
        Double coveragePercent = null, weightedCoveragePercent = null, uncoveredAreaM2 = null;
        List<UncoveredZoneView> uncoveredZones = List.of();
        List<PlannedWaypointView> plannedPath = List.of();
        List<String> planWarnings = List.of();
        String plannedPathKind = "NONE";
        PlanStatusView planStatus = null;
        NextWaypointView next = null;
        String completion = "NO_PLAN";
        CapturePathPlanner.Progress progress = null;
        PlanResult persisted = null;
        BoundsView bounds = scene == null ? null : scene.bounds();
        if (scene == null) {
            coverageReason = "room outline not set for this capture yet";
        } else {
            try {
                PlanRequest req = HudPlanning.request(scene.scene(), scene.config(), tr.samples(), HudPlanning.priorities(scene.bounds()));
                CapturePathPlanner.Assessment observed = planner.assess(req);
                coverageAvailable = true;
                coverageReason = null;
                coveragePercent = observed.observed().coveragePercent();
                weightedCoveragePercent = observed.observed().weightedCoveragePercent();
                uncoveredAreaM2 = observed.observed().uncoveredAreaM2();
                uncoveredZones = observed.uncoveredRegions().stream()
                    .map(r -> new UncoveredZoneView(r.centroidX(), r.centroidY(), r.areaM2(), r.reason())).toList();
                planWarnings = observed.warnings();
                PlanRow plan = store.currentPlan(venueId, organizationId, captureId);
                if (plan != null) {
                    persisted = store.read(plan.plannedJson(), PlanResult.class);
                    PlanResult.CoverageMetrics atPlan = store.read(plan.observedJson(), PlanResult.CoverageMetrics.class);
                    double after = HudPlanning.seconds(plan.plannedAfterMs(), base);
                    List<TrajectorySample> since = tr.samples().stream().filter(s -> s.t() > after).toList();
                    progress = planner.progress(req, persisted.waypoints(), since, VISIT_RADIUS_METERS);
                    plannedPathKind = "PERSISTED_PLAN";
                    Map<Integer, PlanResult.Waypoint> byOrder = new HashMap<>();
                    persisted.waypoints().forEach(w -> byOrder.put(w.order(), w));
                    plannedPath = progress.waypoints().stream().map(w -> {
                        PlanResult.Waypoint pw = byOrder.get(w.order());
                        return new PlannedWaypointView(w.order(), w.x(), w.y(), w.yawDegrees(), w.type(), pw.reason(), pw.expectedCoverageGainM2(), w.status());
                    }).toList();
                    if (progress.nextOrder() != null) {
                        PlanResult.Waypoint w = byOrder.get(progress.nextOrder());
                        next = new NextWaypointView(w.order(), w.x(), w.y(), w.yawDegrees(), w.type(), w.reason(), progress.nextWalkMeters(),
                            progress.nextLeg().stream().map(p -> new PathPointView(p.x(), p.y(), 0, "PLANNED")).toList());
                    }
                    completion = coveragePercent >= 100 * req.config().stopCoverage() ? "COVERAGE_TARGET_REACHED" : progress.completion();
                    planStatus = new PlanStatusView(plan.version(), plan.createdAt(), "PREDICTED", persisted.planned().coveragePercent(),
                        atPlan.coveragePercent(), persisted.waypoints().size(), progress.visited(), progress.unreachable());
                } else if ("READY".equals(recon.verdict())) {
                    // No plan has been made yet: show what the planner would suggest now, clearly labelled as a suggestion.
                    PlanResult suggestion = planner.plan(req);
                    plannedPathKind = "LIVE_SUGGESTION";
                    plannedPath = suggestion.waypoints().stream().map(w -> new PlannedWaypointView(w.order(), w.x(), w.y(), w.yawDegrees(),
                        w.type(), w.reason(), w.expectedCoverageGainM2(), "SUGGESTED")).toList();
                }
            } catch (PlanningException e) {
                coverageReason = e.getMessage();
            }
        }

        // ---- what the coverage figure is computed from ------------------------------------------------------------
        String basis;
        int kinds = (tr.trackedMeters() > 0 ? 1 : 0) + (tr.interpolatedMeters() > 0 ? 1 : 0) + (tr.importedMeters() > 0 ? 1 : 0);
        if (tr.samples().isEmpty()) {
            basis = "NONE";
        } else if (kinds > 1) {
            basis = "MIXED";
        } else if (tr.importedMeters() > 0) {
            basis = "IMPORTED_TRAJECTORY";
        } else if (tr.interpolatedMeters() > 0 || (lastLive != null && Reconnaissance.MANUAL_SOURCE.equals(lastLive.source()))) {
            basis = "MANUAL_MARKS";
        } else {
            basis = "TRACKING".equals(trackingState) ? "LIVE_TRACKED" : "TRACKED_NOT_LIVE";
        }
        boolean coverageLive = "LIVE_TRACKED".equals(basis);
        String basisDetail = String.format(Locale.ROOT, "%d positions in %d segment(s): %.1f m tracked, %.1f m interpolated between manual "
            + "marks, %.1f m imported%s", tr.samples().size(), tr.samples().isEmpty() ? 0 : tr.samples().get(tr.samples().size() - 1).segment() + 1,
            tr.trackedMeters(), tr.interpolatedMeters(), tr.importedMeters(),
            tr.excludedOtherFrame() > 0 ? "; " + tr.excludedOtherFrame() + " position(s) from another tracking frame ignored" : "");

        QualitySummaryView qualitySummary = summarizeQuality(quality);
        List<String> qualityFlags = qualityFlags(quality);
        List<String> warnings = new ArrayList<>(planWarnings);
        if (!trackingAvailable) {
            warnings.add("TRACKING_UNAVAILABLE: " + trackingReason);
        }
        if (!coverageAvailable) {
            warnings.add("COVERAGE_UNAVAILABLE: " + coverageReason);
        }
        if (tr.excludedOtherFrame() > 0) {
            warnings.add("FRAME_MISMATCH: " + tr.excludedOtherFrame() + " position(s) were recorded in another tracking frame and are not used");
        }
        List<ReshootRecommendationView> reshoots = reshootRecommendations(quality, position, uncoveredZones, progress, base);

        return new HudStatus(trackingAvailable, trackingReason, position, pathSoFar,
            scene != null, coverageAvailable, coverageReason, coveragePercent, weightedCoveragePercent, uncoveredAreaM2,
            uncoveredZones, plannedPath, qualitySummary, warnings, reshoots, Instant.ofEpochMilli(now),
            trackingState, scene == null ? null : scene.source(), scene == null ? null : scene.frameId(), scene == null ? null : scene.scene(),
            bounds, recon, basis, coverageLive, basisDetail, plannedPathKind, planStatus, next, completion, tr.excludedOtherFrame(),
            qualityFlags, lastEvent == null ? null : lastEvent.capturedAtMs(), lastEvent == null ? null : lastEvent.state());
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

    /** Warnings shared by at least WARNING_STREAK_THRESHOLD of the last WARNING_STREAK_WINDOW frames, most frequent first. */
    private static List<String> qualityFlags(List<QualityRow> quality) {
        if (quality.size() < WARNING_STREAK_WINDOW) {
            return List.of();
        }
        Map<String, Long> counts = new HashMap<>();
        for (QualityRow q : quality.subList(quality.size() - WARNING_STREAK_WINDOW, quality.size())) {
            for (String w : q.warnings()) {
                counts.merge(w, 1L, Long::sum);
            }
        }
        return counts.entrySet().stream().filter(e -> e.getValue() >= WARNING_STREAK_THRESHOLD)
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
            .map(Map.Entry::getKey).toList();
    }

    /**
     * What needs to be captured again, from real observations only: a planned waypoint that was visited while most frames
     * around the visit had a capture defect (blur, exposure, low texture, fast motion); a sustained quality problem right
     * now; and the largest areas still not covered. Positions are attached when known; otherwise the recommendation is
     * reason-only rather than inventing a location.
     */
    private List<ReshootRecommendationView> reshootRecommendations(List<QualityRow> quality, PositionView currentPosition,
                                                                    List<UncoveredZoneView> uncoveredZones,
                                                                    CapturePathPlanner.Progress progress, long base) {
        List<ReshootRecommendationView> out = new ArrayList<>();
        if (progress != null) {
            for (CapturePathPlanner.WaypointStatus w : progress.waypoints()) {
                if (w.visitedAtT() == null) {
                    continue;
                }
                long at = base + Math.round(w.visitedAtT() * 1000);
                List<QualityRow> around = quality.stream().filter(q -> Math.abs(q.capturedAtMs() - at) <= VISIT_QUALITY_WINDOW_MS).toList();
                Map<String, Long> defects = new HashMap<>();
                for (QualityRow q : around) {
                    q.warnings().stream().filter(CAPTURE_DEFECTS::contains).distinct().forEach(d -> defects.merge(d, 1L, Long::sum));
                }
                defects.entrySet().stream().filter(e -> 2 * e.getValue() > around.size())
                    .max(Map.Entry.<String, Long>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                    .ifPresent(e -> out.add(new ReshootRecommendationView("REVISIT_WAYPOINT " + w.order() + " (" + e.getKey() + " in " + e.getValue()
                        + "/" + around.size() + " frames at the visit)", w.x(), w.y())));
            }
        }
        for (String flag : qualityFlags(quality)) {
            long n = quality.subList(quality.size() - WARNING_STREAK_WINDOW, quality.size()).stream().filter(q -> q.warnings().contains(flag)).count();
            out.add(new ReshootRecommendationView("REPEATED_" + flag + " (" + n + "/" + WARNING_STREAK_WINDOW + " recent frames)",
                currentPosition == null ? null : currentPosition.x(), currentPosition == null ? null : currentPosition.y()));
        }
        uncoveredZones.stream().limit(MAX_ZONE_RECOMMENDATIONS)
            .forEach(z -> out.add(new ReshootRecommendationView("UNCOVERED_AREA (" + z.reason() + ")", z.centroidX(), z.centroidY())));
        return out;
    }
}
