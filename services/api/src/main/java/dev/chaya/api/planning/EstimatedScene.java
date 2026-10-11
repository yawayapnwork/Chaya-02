package dev.chaya.api.planning;

import dev.chaya.api.planning.RoomBoundsEstimator.Estimate;
import dev.chaya.api.planning.RoomBoundsEstimator.Frontier;
import dev.chaya.api.planning.geometry.Polygon;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a bounds estimate into planner input: the scene to plan in, and the insufficiently observed areas the planner
 * should prioritise. Observed obstacle faces block sight and walking; unobserved space inside the room is a no-go zone
 * (not standable, does not block sight) and a priority region; every frontier stretch is a priority band.
 */
public final class EstimatedScene {

    /** Cells within this distance of an unobserved boundary stretch are prioritised. */
    public static final double FRONTIER_BAND_METERS = 0.75;
    public static final double FRONTIER_WEIGHT = 2.5;
    public static final double UNOBSERVED_INTERIOR_WEIGHT = 2.0;

    private EstimatedScene() {}

    public static Scene scene(Estimate e) {
        if (e.area() == null) {
            throw new PlanningException("BOUNDS_NOT_ESTIMABLE", "the room bounds could not be estimated, so there is no area to plan in");
        }
        return new Scene(List.of(e.area()), List.of(), e.obstacles(), e.unobservedInterior(), List.of());
    }

    public static List<PriorityRegion> priorities(Estimate e) {
        List<PriorityRegion> out = new ArrayList<>();
        for (Frontier f : e.frontier()) {
            out.add(PriorityRegion.around(f.polyline(), FRONTIER_BAND_METERS, FRONTIER_WEIGHT, "UNOBSERVED_BOUNDARY"));
        }
        for (Polygon p : e.unobservedInterior()) {
            out.add(PriorityRegion.inside(p, UNOBSERVED_INTERIOR_WEIGHT, "UNOBSERVED_INTERIOR"));
        }
        return List.copyOf(out);
    }
}
