package dev.chaya.api.hud;

import dev.chaya.api.audit.AuditService;
import dev.chaya.api.capture.CaptureService;
import dev.chaya.api.capture.CaptureService.CaptureView;
import dev.chaya.api.hud.CaptureHudDtos.BoundsView;
import dev.chaya.api.hud.CaptureHudDtos.CreatePlanRequest;
import dev.chaya.api.hud.CaptureHudDtos.EstimateBoundsRequest;
import dev.chaya.api.hud.CaptureHudDtos.EstimateBoundsResponse;
import dev.chaya.api.hud.CaptureHudDtos.EvidenceReport;
import dev.chaya.api.hud.CaptureHudDtos.ObservedCoverage;
import dev.chaya.api.hud.CaptureHudDtos.PlanEvidence;
import dev.chaya.api.hud.CaptureHudDtos.PlanSummary;
import dev.chaya.api.hud.CaptureHudDtos.PlanView;
import dev.chaya.api.hud.CaptureHudDtos.PlanWithProgress;
import dev.chaya.api.hud.HudStore.EventRow;
import dev.chaya.api.hud.HudStore.PlanRow;
import dev.chaya.api.hud.HudStore.PoseRow;
import dev.chaya.api.hud.HudStore.SceneRow;
import dev.chaya.api.hud.HudStore.SurfaceRow;
import dev.chaya.api.planning.CapturePathPlanner;
import dev.chaya.api.planning.EstimatedScene;
import dev.chaya.api.planning.PlanRequest;
import dev.chaya.api.planning.PlanResult;
import dev.chaya.api.planning.PlanningException;
import dev.chaya.api.planning.Reconnaissance;
import dev.chaya.api.planning.RoomBoundsEstimator;
import dev.chaya.api.planning.RoomBoundsEstimator.Estimate;
import dev.chaya.api.planning.RoomBoundsEstimator.SurfaceObservation;
import dev.chaya.api.planning.TrajectorySample;
import dev.chaya.api.planning.api.RoutePlanDtos.SceneDto;
import dev.chaya.api.security.Actor;
import dev.chaya.api.web.ApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reconnaissance -> room bounds -> persisted scan plan, for one capture. Uses the same deterministic planning engine as
 * route-plan and the live HUD (dev.chaya.api.planning); nothing here is a second planner. Every plan is stored with the
 * evidence it was computed from, and its coverage is labelled PREDICTED; coverage of recorded positions is OBSERVED.
 */
@Service
public class CapturePlanService {

    private static final int MAX_CONCURRENT = 4;
    static final Map<String, String> DEFINITIONS = Map.of(
        "PREDICTED", "What a plan expects the reconnaissance walk plus its route to cover, by the planner's visibility and "
            + "triangulation model (docs/route-planning.md). A prediction, made before the route was walked.",
        "OBSERVED", "The same model applied to the positions actually recorded for the capture. It describes where the camera "
            + "was and which way it faced, not whether a reconstruction succeeded.",
        "ESTIMATED", "Room bounds estimated from tracked positions and surface observations (RoomBoundsEstimator); frontier "
            + "stretches are where observation stopped, not walls.",
        "MEASURED", "Room bounds the operator measured and entered, checked against the evidence but never changed by it.");

    private final CaptureService captures;
    private final CaptureHudService hud;
    private final HudStore store;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final JdbcClient jdbc;
    private final HudProperties props;
    private final CapturePathPlanner planner = new CapturePathPlanner();
    private final RoomBoundsEstimator estimator = new RoomBoundsEstimator();
    private final Semaphore permits = new Semaphore(MAX_CONCURRENT);

    public CapturePlanService(CaptureService captures, CaptureHudService hud, HudStore store, AuditService audit, TransactionTemplate tx,
                              JdbcClient jdbc, HudProperties props) {
        this.captures = captures;
        this.hud = hud;
        this.store = store;
        this.audit = audit;
        this.tx = tx;
        this.jdbc = jdbc;
        this.props = props;
    }

    private record Evidence(SceneRow scene, List<PoseRow> poses, List<EventRow> events, List<SurfaceRow> surfaces, long base,
                            Reconnaissance.Trajectory trajectory, List<SurfaceObservation> observations, Reconnaissance.Assessment recon) {}

    /**
     * Everything stored for the capture, in one tracking frame: {@code frameOverride} if given; else, when
     * {@code latestSensorFrame}, the frame of the most recent tracked or imported position (estimating bounds starts from
     * the current AR session); else the scene's frame.
     */
    private Evidence load(UUID venueId, UUID org, UUID captureId, String frameOverride, boolean latestSensorFrame) {
        SceneRow scene = store.scene(venueId, org, captureId);
        List<PoseRow> poses = store.poses(venueId, org, captureId, props.maxPoseSamplesConsidered());
        List<EventRow> events = store.events(venueId, org, captureId);
        List<SurfaceRow> surfaces = store.surfaces(venueId, org, captureId);
        long base = HudPlanning.base(poses, events, System.currentTimeMillis());
        String frame = frameOverride;
        if (frame == null && latestSensorFrame) {
            for (int i = poses.size() - 1; i >= 0 && frame == null; i--) {
                if (!"MANUAL".equals(sourceClass(poses.get(i).source()))) {
                    frame = poses.get(i).frameId();
                }
            }
        }
        if (frame == null && scene != null) {
            frame = scene.frameId();
        }
        Reconnaissance.Trajectory tr = Reconnaissance.trajectory(HudPlanning.evidence(poses, base), HudPlanning.events(events, base), frame,
            HudPlanning.REQUIREMENTS);
        List<SurfaceObservation> obs = HudPlanning.surfaces(surfaces, tr.frameId());
        int walls = (int) obs.stream().filter(o -> "WALL".equals(o.kind())).count();
        Reconnaissance.Assessment recon = Reconnaissance.assess(HudPlanning.evidence(poses, base), HudPlanning.events(events, base),
            tr.frameId(), walls, scene != null && "MEASURED".equals(scene.source()) && Objects.equals(scene.frameId(), tr.frameId()),
            store.hasVideoMedia(venueId, captureId), HudPlanning.REQUIREMENTS);
        return new Evidence(scene, poses, events, surfaces, base, tr, obs, recon);
    }

    public Reconnaissance.Assessment reconnaissance(Actor actor, UUID venueId, UUID captureId) {
        CaptureView capture = captures.get(actor, venueId, captureId);
        return load(venueId, actor.organizationId(), capture.id(), null, false).recon();
    }

    // ---- bounds -----------------------------------------------------------------------------------------------------

    public EstimateBoundsResponse estimateBounds(Actor actor, UUID venueId, UUID captureId, EstimateBoundsRequest body) {
        CaptureView capture = hud.requireCapturing(actor, venueId, captureId);
        String frameOverride = body == null || body.frameId() == null || body.frameId().isBlank() ? null : body.frameId();
        Evidence ev = load(venueId, actor.organizationId(), capture.id(), frameOverride, true);
        if (ev.trajectory().frameId() == null && ev.trajectory().samples().isEmpty()) {
            Estimate none = estimator.estimate(List.of(), List.of(), null);
            return new EstimateBoundsResponse(false, HudPlanning.view(none, null), ev.recon());
        }
        boolean anySensor = ev.poses().stream().anyMatch(p -> !"MANUAL".equals(sourceClass(p.source()))
            && (p.frameId() == null || Objects.equals(p.frameId(), ev.trajectory().frameId())));
        Estimate e;
        if (!anySensor) {
            // Manual marks prove where the operator stood, on a plan someone drew; they are not spatial evidence of walls.
            Estimate base = estimator.estimate(List.of(), List.of(), null);
            e = new Estimate("NOT_ESTIMABLE", "SENSOR_EVIDENCE", null, List.of(), List.of(), List.of(), 0, base.confidenceParts(), 0, 0, 0,
                base.evidence(), List.of(), List.of(new RoomBoundsEstimator.Issue("NO_TRACKED_POSITIONS", "bounds are estimated only from "
                    + "tracked or imported positions with surface observations; manual marks cannot estimate walls. Enter a measured outline.")));
        } else {
            e = estimator.estimate(ev.trajectory().samples(), ev.observations(), null);
        }
        BoundsView view = HudPlanning.view(e, ev.trajectory().frameId());
        boolean stored = e.area() != null;
        if (stored) {
            SceneDto scene = HudPlanning.sceneDto(EstimatedScene.scene(e));
            try {
                // The same validation a measured outline gets: the grid must be buildable.
                planner.assess(HudPlanning.request(scene, ev.scene() == null ? null : ev.scene().config(), ev.trajectory().samples(), List.of()));
            } catch (PlanningException ex) {
                if (!"NO_USABLE_TRAJECTORY".equals(ex.code()) && !"TRAJECTORY_OUTSIDE_SCENE".equals(ex.code())) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ex.code(), ex.getMessage());
                }
            }
            hud.storeScene(actor, venueId, capture.id(), scene, ev.scene() == null ? null : ev.scene().config(), "ESTIMATED",
                ev.trajectory().frameId(), view);
        } else {
            tx.executeWithoutResult(s -> audit.success(actor, venueId, "capture.bounds_not_estimable", "capture_session", capture.id(),
                Map.of("issues", e.issues().stream().map(RoomBoundsEstimator.Issue::code).toList())));
        }
        return new EstimateBoundsResponse(stored, view, ev.recon());
    }

    private static String sourceClass(String source) {
        if (source == null || Reconnaissance.MANUAL_SOURCE.equals(source)) return "MANUAL";
        return Reconnaissance.TRACKED_SOURCES.contains(source) ? "TRACKED" : "IMPORTED";
    }

    // ---- plans ------------------------------------------------------------------------------------------------------

    public PlanView createPlan(Actor actor, UUID venueId, UUID captureId, CreatePlanRequest body) {
        CaptureView capture = hud.requireCapturing(actor, venueId, captureId);
        UUID org = actor.organizationId();
        Evidence ev = load(venueId, org, capture.id(), null, false);
        if (ev.scene() == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SCENE_NOT_SET",
                "no room bounds yet: estimate them from the reconnaissance walk or enter a measured outline");
        }
        if (!"READY".equals(ev.recon().verdict())) {
            String blocking = ev.recon().issues().stream().filter(i -> "BLOCKING".equals(i.severity()))
                .map(i -> i.code() + ": " + i.message()).collect(Collectors.joining("; "));
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "RECONNAISSANCE_" + ev.recon().verdict(),
                "the reconnaissance walk cannot be planned from yet: " + blocking);
        }
        Map<String, Double> config = body != null && body.config() != null ? body.config()
            : ev.scene().config() == null ? Map.of() : ev.scene().config();
        BoundsView bounds = ev.scene().bounds();
        if ("MEASURED".equals(ev.scene().source())) {
            bounds = HudPlanning.measuredCheck(ev.scene().scene(), ev.trajectory().samples(), ev.observations(), ev.scene().frameId());
        }
        List<dev.chaya.api.planning.PriorityRegion> priorities = HudPlanning.priorities(bounds);
        PlanRequest req;
        PlanResult result;
        if (!permits.tryAcquire()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PLANNER_BUSY", "the planner is busy; retry in a moment");
        }
        try {
            req = HudPlanning.request(ev.scene().scene(), config, ev.trajectory().samples(), priorities);
            result = planner.plan(req);
        } catch (PlanningException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, e.code(), e.getMessage());
        } finally {
            permits.release();
        }
        long plannedAfter = ev.poses().isEmpty() ? System.currentTimeMillis() : ev.poses().get(ev.poses().size() - 1).capturedAtMs();
        Map<String, Integer> bySource = new java.util.TreeMap<>();
        for (PoseRow p : ev.poses()) {
            if (p.frameId() == null || Objects.equals(p.frameId(), ev.trajectory().frameId())) {
                bySource.merge(p.source(), 1, Integer::sum);
            }
        }
        PlanEvidence evidence = new PlanEvidence(bySource, ev.observations().size(), ev.events().size(),
            store.evidenceCounts(venueId, org, capture.id()).get("quality").getOrDefault("all", 0),
            store.maxId("capture_hud_pose_sample", capture.id()), store.maxId("capture_hud_surface_sample", capture.id()),
            store.maxId("capture_hud_tracking_event", capture.id()), store.maxId("capture_hud_quality_sample", capture.id()),
            ev.poses().isEmpty() ? null : ev.poses().get(0).capturedAtMs(), ev.poses().isEmpty() ? null : plannedAfter,
            ev.trajectory().frameId(), ev.trajectory().excludedOtherFrame());
        String createdBy = actor.subject();
        BoundsView finalBounds = bounds;
        int version = tx.execute(s -> {
            // Serialises plan creation per capture: the session row is the lock, so versions never collide.
            jdbc.sql("SELECT id FROM capture_session WHERE id = :c FOR UPDATE").param("c", capture.id()).query(UUID.class).single();
            int v = jdbc.sql("SELECT coalesce(max(version), 0) + 1 FROM capture_plan WHERE capture_session_id = :c")
                .param("c", capture.id()).query(Integer.class).single();
            jdbc.sql("UPDATE capture_plan SET superseded_at = now() WHERE capture_session_id = :c AND superseded_at IS NULL")
                .param("c", capture.id()).update();
            jdbc.sql("""
                    INSERT INTO capture_plan (organization_id, venue_id, capture_session_id, version, scene_source, frame_id, scene_json,
                                              bounds_estimate_json, recon_json, priority_regions_json, config_json, evidence_json,
                                              planned_json, observed_at_plan_json, planned_after_ms, created_by)
                    VALUES (:o, :v, :c, :ver, :src, :f, CAST(:scene AS jsonb), CAST(:bounds AS jsonb), CAST(:recon AS jsonb),
                            CAST(:prio AS jsonb), CAST(:cfg AS jsonb), CAST(:ev AS jsonb), CAST(:planned AS jsonb), CAST(:obs AS jsonb),
                            :after, :by)
                    """)
                .param("o", org).param("v", venueId).param("c", capture.id()).param("ver", v).param("src", ev.scene().source())
                .param("f", ev.scene().frameId()).param("scene", store.json(ev.scene().scene()))
                .param("bounds", finalBounds == null ? null : store.json(finalBounds)).param("recon", store.json(ev.recon()))
                .param("prio", store.json(priorities.stream().map(p -> Map.of("kind", p.kind(), "weight", p.weight())).toList()))
                .param("cfg", store.json(config)).param("ev", store.json(evidence)).param("planned", store.json(result))
                .param("obs", store.json(result.baseline())).param("after", plannedAfter).param("by", createdBy)
                .update();
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("version", v);
            meta.put("sceneSource", ev.scene().source());
            meta.put("waypoints", result.waypoints().size());
            meta.put("observedCoveragePercent", result.baseline().coveragePercent());
            meta.put("predictedCoveragePercent", result.planned().coveragePercent());
            audit.success(actor, venueId, "capture.scan_plan_created", "capture_session", capture.id(), meta);
            return v;
        });
        hud.touch(capture.id());
        return view(store.plans(venueId, org, capture.id()).stream().filter(p -> p.version() == version).findFirst().orElseThrow());
    }

    public PlanWithProgress currentPlan(Actor actor, UUID venueId, UUID captureId) {
        CaptureView capture = captures.get(actor, venueId, captureId);
        UUID org = actor.organizationId();
        PlanRow row = store.currentPlan(venueId, org, capture.id());
        if (row == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NO_PLAN", "no scan plan has been made for this capture");
        }
        PlanView plan = view(row);
        return new PlanWithProgress(plan, progress(venueId, org, capture.id(), row, plan.predicted()));
    }

    private CapturePathPlanner.Progress progress(UUID venueId, UUID org, UUID captureId, PlanRow row, PlanResult predicted) {
        Evidence ev = load(venueId, org, captureId, null, false);
        if (ev.scene() == null) {
            return null;
        }
        try {
            PlanRequest req = HudPlanning.request(ev.scene().scene(), ev.scene().config(), ev.trajectory().samples(),
                HudPlanning.priorities(ev.scene().bounds()));
            double after = HudPlanning.seconds(row.plannedAfterMs(), ev.base());
            List<TrajectorySample> since = ev.trajectory().samples().stream().filter(s -> s.t() > after).toList();
            return planner.progress(req, predicted.waypoints(), since, CaptureHudService.VISIT_RADIUS_METERS);
        } catch (PlanningException e) {
            return null;
        }
    }

    private PlanView view(PlanRow r) {
        return new PlanView(r.version(), r.createdAt(), r.createdBy(), r.supersededAt(), r.sceneSource(), r.frameId(),
            store.read(r.sceneJson(), SceneDto.class), r.boundsJson() == null ? null : store.read(r.boundsJson(), BoundsView.class),
            store.read(r.reconJson(), Reconnaissance.Assessment.class), store.read(r.evidenceJson(), PlanEvidence.class),
            store.read(r.plannedJson(), PlanResult.class), store.read(r.observedJson(), PlanResult.CoverageMetrics.class), r.plannedAfterMs());
    }

    // ---- audit --------------------------------------------------------------------------------------------------------

    public EvidenceReport evidence(Actor actor, UUID venueId, UUID captureId) {
        CaptureView capture = captures.get(actor, venueId, captureId); // readable in any capture state
        UUID org = actor.organizationId();
        Evidence ev = load(venueId, org, capture.id(), null, false);
        Map<String, Map<String, Integer>> counts = store.evidenceCounts(venueId, org, capture.id());
        ObservedCoverage observed = null;
        if (ev.scene() != null) {
            try {
                PlanRequest req = HudPlanning.request(ev.scene().scene(), ev.scene().config(), ev.trajectory().samples(),
                    HudPlanning.priorities(ev.scene().bounds()));
                CapturePathPlanner.Assessment a = planner.assess(req);
                String basis = ev.recon().positionBasis();
                observed = new ObservedCoverage("OBSERVED", basis, a.observed(), a.trajectorySegments(), ev.trajectory().trackedMeters(),
                    ev.trajectory().interpolatedMeters(), ev.trajectory().importedMeters());
            } catch (PlanningException e) {
                observed = null;
            }
        }
        List<PlanRow> rows = store.plans(venueId, org, capture.id());
        List<PlanSummary> plans = rows.stream().map(r -> {
            PlanResult p = store.read(r.plannedJson(), PlanResult.class);
            PlanResult.CoverageMetrics o = store.read(r.observedJson(), PlanResult.CoverageMetrics.class);
            return new PlanSummary(r.version(), r.createdAt(), r.createdBy(), r.supersededAt(), r.sceneSource(), "PREDICTED",
                p.planned().coveragePercent(), o.coveragePercent(), p.waypoints().size(), p.estimatedDistanceMeters());
        }).toList();
        PlanRow current = rows.stream().filter(r -> r.supersededAt() == null).reduce((a, b) -> b).orElse(null);
        CapturePathPlanner.Progress progress = current == null ? null
            : progress(venueId, org, capture.id(), current, store.read(current.plannedJson(), PlanResult.class));
        return new EvidenceReport(counts.get("pose"), counts.get("surface"), counts.get("event"),
            counts.get("quality").getOrDefault("all", 0), List.copyOf(counts.get("frame").keySet()),
            ev.scene() == null ? null : ev.scene().source(), ev.scene() == null ? null : ev.scene().frameId(),
            ev.scene() == null ? null : ev.scene().bounds(), ev.recon(), observed, plans, progress, DEFINITIONS);
    }
}
