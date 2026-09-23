package dev.chaya.api.rescan;

import java.util.List;

/**
 * Pure 2D polygon math for the region an operator selects for an incremental re-scan. Mirrors (in intent,
 * not by codegen) the same primitives chaya_worker.region_splice implements for the actual splice --
 * {@link #pointInPolygon} is the same ray-casting test, {@link #area} the same shoelace formula. Used here
 * only for request-time validation and for deciding whether navigation needs to be rebaked; the worker
 * does the real geometric splice on the point cloud itself.
 */
public final class PolygonGeometry {

    private PolygonGeometry() {}

    /** Accepts {@code List<? extends Number>} vertices rather than {@code List<Double>}: a polygon parsed
     * back from stored JSON via a generic {@code Map<String, Object>} (see PipelineService#regionGeometryOf)
     * carries plain {@code Integer}s for whole-number coordinates, not {@code Double}s -- a declared
     * {@code List<Double>} parameter would compile but throw ClassCastException at runtime for those. */
    public static double area(List<? extends List<? extends Number>> polygon) {
        int n = polygon.size();
        double sum = 0;
        for (int i = 0; i < n; i++) {
            double ax = polygon.get(i).get(0).doubleValue(), ay = polygon.get(i).get(1).doubleValue();
            List<? extends Number> b = polygon.get((i + 1) % n);
            double bx = b.get(0).doubleValue(), by = b.get(1).doubleValue();
            sum += ax * by - bx * ay;
        }
        return Math.abs(sum) / 2.0;
    }

    /** Ray-casting point-in-polygon test against one simple polygon (not required to be convex). */
    public static boolean pointInPolygon(double x, double y, List<? extends List<? extends Number>> polygon) {
        int n = polygon.size();
        boolean inside = false;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = polygon.get(i).get(0).doubleValue(), yi = polygon.get(i).get(1).doubleValue();
            double xj = polygon.get(j).get(0).doubleValue(), yj = polygon.get(j).get(1).doubleValue();
            boolean intersects = ((yi > y) != (yj > y)) && (x < (xj - xi) * (y - yi) / (yj - yi + 1e-30) + xi);
            if (intersects) {
                inside = !inside;
            }
        }
        return inside;
    }
}
