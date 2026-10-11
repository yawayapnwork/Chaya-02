package dev.chaya.api.planning;

import dev.chaya.api.planning.geometry.Point;
import dev.chaya.api.planning.geometry.Polygon;
import java.util.List;

/**
 * An area whose observation is known to be insufficient: the stretch of an estimated room boundary the reconnaissance
 * walk never saw (a RoomBoundsEstimator frontier), or unobserved floor inside the room. Every target cell in the region
 * gets at least {@code weight} importance and is flagged, so viewpoints that look at it score higher and a waypoint
 * whose gain is mostly there is typed UNOBSERVED.
 *
 * <p>The region is either the inside of {@code polygon}, or every cell within {@code radius} of {@code polyline}
 * (exactly one of the two is set; use the factories).
 *
 * @param kind UNOBSERVED_BOUNDARY or UNOBSERVED_INTERIOR
 */
public record PriorityRegion(Polygon polygon, List<Point> polyline, double radius, double weight, String kind) {

    public PriorityRegion {
        polyline = polyline == null ? null : List.copyOf(polyline);
    }

    public static PriorityRegion inside(Polygon polygon, double weight, String kind) {
        return new PriorityRegion(polygon, null, 0, weight, kind);
    }

    public static PriorityRegion around(List<Point> polyline, double radius, double weight, String kind) {
        return new PriorityRegion(null, polyline, radius, weight, kind);
    }

    boolean isValid() {
        boolean shape = polygon != null
            ? polyline == null && polygon.isValid()
            : polyline != null && !polyline.isEmpty() && polyline.stream().allMatch(Point::isFinite) && radius > 0 && radius <= 5;
        return shape && weight >= 1 && weight <= 10;
    }
}
