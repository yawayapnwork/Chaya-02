package dev.chaya.api.hud;

import dev.chaya.api.hud.CaptureHudDtos.BoundsView;
import dev.chaya.api.hud.CaptureHudDtos.FrontierView;
import dev.chaya.api.hud.CaptureHudDtos.PathPointView;
import dev.chaya.api.hud.HudStore.EventRow;
import dev.chaya.api.hud.HudStore.PoseRow;
import dev.chaya.api.hud.HudStore.SurfaceRow;
import dev.chaya.api.planning.EstimatedScene;
import dev.chaya.api.planning.PlanRequest;
import dev.chaya.api.planning.PriorityRegion;
import dev.chaya.api.planning.Reconnaissance;
import dev.chaya.api.planning.Reconnaissance.PoseEvidence;
import dev.chaya.api.planning.Reconnaissance.TrackingEvent;
import dev.chaya.api.planning.RoomBoundsEstimator;
import dev.chaya.api.planning.RoomBoundsEstimator.Estimate;
import dev.chaya.api.planning.RoomBoundsEstimator.SurfaceObservation;
import dev.chaya.api.planning.Scene;
import dev.chaya.api.planning.TrajectorySample;
import dev.chaya.api.planning.api.RoutePlanDtos;
import dev.chaya.api.planning.api.RoutePlanDtos.PointDto;
import dev.chaya.api.planning.api.RoutePlanDtos.RoutePlanRequest;
import dev.chaya.api.planning.api.RoutePlanDtos.SceneDto;
import dev.chaya.api.planning.geometry.Point;
import dev.chaya.api.planning.geometry.Polygon;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Conversions between the HUD's stored rows and the planning package's inputs. Pure. */
final class HudPlanning {

    static final Reconnaissance.Requirements REQUIREMENTS = Reconnaissance.Requirements.defaults();

    private HudPlanning() {}

    /** Time origin of a capture's evidence: the earliest stored position or event (epoch ms). */
    static long base(List<PoseRow> poses, List<EventRow> events, long fallback) {
        long b = Long.MAX_VALUE;
        for (PoseRow p : poses) b = Math.min(b, p.capturedAtMs());
        for (EventRow e : events) b = Math.min(b, e.capturedAtMs());
        return b == Long.MAX_VALUE ? fallback : b;
    }

    static double seconds(long ms, long base) {
        return (ms - base) / 1000.0;
    }

    static List<PoseEvidence> evidence(List<PoseRow> poses, long base) {
        List<PoseEvidence> out = new ArrayList<>(poses.size());
        for (PoseRow p : poses) {
            out.add(new PoseEvidence(seconds(p.capturedAtMs(), base), p.x(), p.y(),
                p.yawDegrees() == null ? null : Math.toRadians(p.yawDegrees()), p.source(), p.frameId()));
        }
        return out;
    }

    static List<TrackingEvent> events(List<EventRow> events, long base) {
        return events.stream().map(e -> new TrackingEvent(seconds(e.capturedAtMs(), base), e.state())).toList();
    }

    static List<SurfaceObservation> surfaces(List<SurfaceRow> rows, String frameId) {
        List<SurfaceObservation> out = new ArrayList<>();
        for (SurfaceRow r : rows) {
            if (r.frameId() == null || Objects.equals(r.frameId(), frameId)) {
                out.add(new SurfaceObservation(r.originX(), r.originY(), r.hitX(), r.hitY(), r.kind(), r.normalDegrees()));
            }
        }
        return out;
    }

    /** The recorded path for display: segment numbers from the trajectory, and how each stretch is known. */
    static List<PathPointView> path(Reconnaissance.Trajectory tr) {
        List<PathPointView> out = new ArrayList<>(tr.samples().size());
        int piece = 0;
        TrajectorySample prev = null;
        for (TrajectorySample s : tr.samples()) {
            String kind = "START";
            if (prev != null && prev.segment() == s.segment() && piece < tr.pieces().size()) {
                kind = tr.pieces().get(piece++).kind();
            }
            out.add(new PathPointView(s.x(), s.y(), s.segment(), kind));
            prev = s;
        }
        return out;
    }

    /** A planner request for the scene, the segmented trajectory and the scene's priority regions. */
    static PlanRequest request(SceneDto scene, Map<String, Double> config, List<TrajectorySample> trajectory, List<PriorityRegion> priorities) {
        PlanRequest shape = RoutePlanDtos.toDomain(new RoutePlanRequest(scene, List.of(), List.of(), List.of(), config));
        return new PlanRequest(shape.scene(), trajectory, List.of(), List.of(), shape.config(), priorities);
    }

    static Scene domainScene(SceneDto scene) {
        return RoutePlanDtos.toDomain(new RoutePlanRequest(scene, List.of(), List.of(), List.of(), Map.of())).scene();
    }

    static Polygon polygon(List<PointDto> ring) {
        return new Polygon(ring.stream().map(p -> new Point(p.x(), p.y())).toList());
    }

    static List<PointDto> ring(Polygon p) {
        return p.vertices().stream().map(v -> new PointDto(v.x(), v.y())).toList();
    }

    static SceneDto sceneDto(Scene s) {
        return new SceneDto(s.areas().stream().map(HudPlanning::ring).toList(), List.of(),
            s.obstacles().stream().map(HudPlanning::ring).toList(), s.noGoZones().stream().map(HudPlanning::ring).toList(), List.of());
    }

    static BoundsView view(Estimate e, String frameId) {
        return new BoundsView(e.status(), e.basis(), frameId, e.area() == null ? null : ring(e.area()),
            e.obstacles().stream().map(HudPlanning::ring).toList(), e.unobservedInterior().stream().map(HudPlanning::ring).toList(),
            e.frontier().stream().map(f -> new FrontierView(f.polyline().stream().map(p -> new PointDto(p.x(), p.y())).toList(),
                f.lengthMeters(), f.midX(), f.midY(), f.outwardDegrees())).toList(),
            e.confidence(), e.confidenceParts(), e.areaM2(), e.observedBoundaryMeters(), e.frontierMeters(), e.evidence(), e.assumptions(),
            e.issues());
    }

    /** The insufficiently observed areas of a bounds estimate, as planner priority regions. */
    static List<PriorityRegion> priorities(BoundsView b) {
        if (b == null) {
            return List.of();
        }
        List<PriorityRegion> out = new ArrayList<>();
        for (FrontierView f : b.frontier()) {
            List<Point> line = f.polyline().stream().map(p -> new Point(p.x(), p.y())).toList();
            if (!line.isEmpty()) {
                out.add(PriorityRegion.around(line, EstimatedScene.FRONTIER_BAND_METERS, EstimatedScene.FRONTIER_WEIGHT, "UNOBSERVED_BOUNDARY"));
            }
        }
        for (List<PointDto> u : b.unobservedInterior()) {
            out.add(PriorityRegion.inside(polygon(u), EstimatedScene.UNOBSERVED_INTERIOR_WEIGHT, "UNOBSERVED_INTERIOR"));
        }
        return List.copyOf(out);
    }

    /** A measured outline checked against the evidence in its frame (RoomBoundsEstimator, MEASURED mode). */
    static BoundsView measuredCheck(SceneDto scene, List<TrajectorySample> walk, List<SurfaceObservation> surfaces, String frameId) {
        Scene s = domainScene(scene);
        Polygon outline = s.areas().get(0);
        if (s.areas().size() > 1) {
            // Several measured areas: checked as one outline per area is not supported; the first is checked, the rest reported.
            Estimate e = new RoomBoundsEstimator().estimate(walk, surfaces, outline);
            List<RoomBoundsEstimator.Issue> issues = new ArrayList<>(e.issues());
            issues.add(new RoomBoundsEstimator.Issue("ONLY_FIRST_AREA_CHECKED", "the outline has " + s.areas().size()
                + " areas; only the first is checked against the evidence"));
            return view(new Estimate(e.status(), e.basis(), e.area(), e.obstacles(), e.unobservedInterior(), e.frontier(), e.confidence(),
                e.confidenceParts(), e.areaM2(), e.observedBoundaryMeters(), e.frontierMeters(), e.evidence(), e.assumptions(),
                List.copyOf(issues)), frameId);
        }
        return view(new RoomBoundsEstimator().estimate(walk, surfaces, outline), frameId);
    }
}
