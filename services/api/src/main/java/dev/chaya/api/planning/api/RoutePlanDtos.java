package dev.chaya.api.planning.api;

import dev.chaya.api.planning.CandidateViewpoint;
import dev.chaya.api.planning.ObservedRegion;
import dev.chaya.api.planning.PlanRequest;
import dev.chaya.api.planning.PlannerConfig;
import dev.chaya.api.planning.PlanningException;
import dev.chaya.api.planning.Scene;
import dev.chaya.api.planning.TrajectorySample;
import dev.chaya.api.planning.geometry.Point;
import dev.chaya.api.planning.geometry.Polygon;
import dev.chaya.api.planning.geometry.Segment;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

/** Wire format of POST .../route-plan. Angles are degrees; coordinates are metres in the plan view (x east, y north). */
public final class RoutePlanDtos {

    private RoutePlanDtos() {}

    public record PointDto(double x, double y) {
        Point toPoint() {
            return new Point(x, y);
        }
    }

    public record SegmentDto(@NotNull PointDto a, @NotNull PointDto b) {}

    public record DoorwayDto(double x, double y, double width, Double passageDegrees) {}

    public record SceneDto(@NotEmpty List<List<PointDto>> areas, List<SegmentDto> walls, List<List<PointDto>> obstacles,
                           List<List<PointDto>> noGoZones, List<DoorwayDto> doorways) {}

    public record SampleDto(double t, double x, double y, Double yawDegrees) {}

    public record ObservedRegionDto(@NotEmpty List<PointDto> polygon, double quality) {}

    public record CandidateDto(double x, double y, String label) {}

    /**
     * @param config optional overrides of PlannerConfig fields by name (for example {"targetDistance": 1.5}); unknown names are refused
     */
    public record RoutePlanRequest(@NotNull @Valid SceneDto scene, @NotEmpty List<SampleDto> trajectory,
                                   List<ObservedRegionDto> observedRegions, List<CandidateDto> candidates,
                                   Map<String, Double> config) {}

    static final int MAX_SAMPLES = 5000;
    static final int MAX_VERTICES = 2000;
    static final int MAX_LIST = 500;

    /** Validates size limits and converts to the planner's domain model. Throws PlanningException for unusable input. */
    static PlanRequest toDomain(RoutePlanRequest r) {
        if (r.trajectory().size() > MAX_SAMPLES) {
            throw new PlanningException("TOO_MANY_SAMPLES", "trajectory is limited to " + MAX_SAMPLES + " samples");
        }
        int vertices = 0;
        for (var group : List.of(r.scene().areas(), orEmpty(r.scene().obstacles()), orEmpty(r.scene().noGoZones()))) {
            for (var ring : group) {
                vertices += ring.size();
            }
        }
        for (var region : orEmpty(r.observedRegions())) {
            vertices += region.polygon().size();
        }
        if (vertices > MAX_VERTICES) {
            throw new PlanningException("TOO_MUCH_GEOMETRY", "the scene is limited to " + MAX_VERTICES + " polygon vertices in total");
        }
        if (orEmpty(r.scene().walls()).size() > MAX_LIST || orEmpty(r.candidates()).size() > MAX_LIST
            || orEmpty(r.scene().doorways()).size() > 100 || orEmpty(r.observedRegions()).size() > 100) {
            throw new PlanningException("TOO_MUCH_GEOMETRY", "too many walls, doorways, observed regions or candidates");
        }
        Scene scene = new Scene(
            r.scene().areas().stream().map(RoutePlanDtos::polygon).toList(),
            orEmpty(r.scene().walls()).stream().map(s -> new Segment(s.a().toPoint(), s.b().toPoint())).toList(),
            orEmpty(r.scene().obstacles()).stream().map(RoutePlanDtos::polygon).toList(),
            orEmpty(r.scene().noGoZones()).stream().map(RoutePlanDtos::polygon).toList(),
            orEmpty(r.scene().doorways()).stream().map(d -> new Scene.Doorway(d.x(), d.y(), d.width(),
                d.passageDegrees() == null ? null : Math.toRadians(d.passageDegrees()))).toList());
        return new PlanRequest(scene,
            r.trajectory().stream().map(s -> new TrajectorySample(s.t(), s.x(), s.y(), s.yawDegrees() == null ? null : Math.toRadians(s.yawDegrees()))).toList(),
            orEmpty(r.observedRegions()).stream().map(o -> new ObservedRegion(polygon(o.polygon()), o.quality())).toList(),
            orEmpty(r.candidates()).stream().map(c -> new CandidateViewpoint(c.x(), c.y(), c.label())).toList(),
            config(r.config()));
    }

    private static <T> List<T> orEmpty(List<T> l) {
        return l == null ? List.of() : l;
    }

    private static Polygon polygon(List<PointDto> ring) {
        return new Polygon(ring.stream().map(PointDto::toPoint).toList());
    }

    static PlannerConfig config(Map<String, Double> overrides) {
        PlannerConfig.Builder b = PlannerConfig.defaults().toBuilder();
        if (overrides != null) {
            for (var e : overrides.entrySet()) {
                double v = e.getValue() == null ? Double.NaN : e.getValue();
                switch (e.getKey()) {
                    case "resolution" -> b.resolution(v);
                    case "agentRadius" -> b.agentRadius(v);
                    case "targetDistance" -> b.targetDistance(v);
                    case "fovDegrees" -> b.fovDegrees(v);
                    case "minSeparation" -> b.minSeparation(v);
                    case "headingCount" -> b.headingCount(integer(e.getKey(), v));
                    case "maxWaypoints" -> b.maxWaypoints(integer(e.getKey(), v));
                    case "stopCoverage" -> b.stopCoverage(v);
                    case "minMarginalGain" -> b.minMarginalGain(v);
                    case "requiredObservationMass" -> b.requiredObservationMass(v);
                    case "minTriangulationDegrees" -> b.minTriangulationDegrees(v);
                    case "minObservationQuality" -> b.minObservationQuality(v);
                    case "redundancyPenalty" -> b.redundancyPenalty(v);
                    case "travelWeight" -> b.travelWeight(v);
                    case "walkSpeedMetersPerSecond" -> b.walkSpeedMetersPerSecond(v);
                    case "dwellSeconds" -> b.dwellSeconds(v);
                    case "poseSpacing" -> b.poseSpacing(v);
                    case "maxSpeedMetersPerSecond" -> b.maxSpeedMetersPerSecond(v);
                    case "cornerWeight" -> b.cornerWeight(v);
                    case "doorwayWeight" -> b.doorwayWeight(v);
                    case "boundaryWeight" -> b.boundaryWeight(v);
                    default -> throw new PlanningException("UNKNOWN_CONFIG_KEY", "config." + e.getKey() + " is not a planner setting");
                }
            }
        }
        // maxCandidates and maxGridCells are server limits and cannot be raised by a caller.
        return b.build();
    }

    private static int integer(String key, double v) {
        if (!Double.isFinite(v) || v != Math.rint(v)) {
            throw new PlanningException("INVALID_CONFIG", "config." + key + " must be a whole number");
        }
        return (int) v;
    }
}
