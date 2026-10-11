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

    /** At most this many vertices: the simplicity check is quadratic, and an operator draws a region, not a survey. */
    public static final int MAX_VERTICES = 512;

    /**
     * Why the polygon is not a simple polygon the splice can use, or null when it is: every coordinate finite, at most
     * {@link #MAX_VERTICES} vertices, no two consecutive vertices equal, and no two edges crossing or touching except
     * consecutive edges at their shared vertex. A self-intersecting ("bow-tie") region has no well-defined inside: the
     * worker's even-odd test (chaya_worker.region_splice.point_in_polygon) would replace a different area than the one
     * the shoelace area was validated for.
     */
    public static String simplicityProblem(List<? extends List<? extends Number>> polygon) {
        int n = polygon.size();
        if (n < 3) {
            return "a region needs at least 3 vertices";
        }
        if (n > MAX_VERTICES) {
            return "a region may have at most " + MAX_VERTICES + " vertices";
        }
        double[] x = new double[n];
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            List<? extends Number> p = polygon.get(i);
            if (p == null || p.size() != 2 || p.get(0) == null || p.get(1) == null) {
                return "vertex " + i + " is not an [x, y] pair";
            }
            x[i] = p.get(0).doubleValue();
            y[i] = p.get(1).doubleValue();
            if (!Double.isFinite(x[i]) || !Double.isFinite(y[i])) {
                return "vertex " + i + " is not finite";
            }
        }
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            if (x[i] == x[j] && y[i] == y[j]) {
                return "vertices " + i + " and " + j + " are the same point";
            }
        }
        for (int i = 0; i < n; i++) {
            for (int k = i + 1; k < n; k++) {
                boolean adjacent = k == i + 1 || (i == 0 && k == n - 1);
                if (!adjacent && segmentsTouch(x[i], y[i], x[(i + 1) % n], y[(i + 1) % n], x[k], y[k], x[(k + 1) % n], y[(k + 1) % n])) {
                    return "edges " + i + " and " + k + " cross: the region must be a simple polygon";
                }
            }
        }
        return null;
    }

    private static boolean segmentsTouch(double ax, double ay, double bx, double by, double cx, double cy, double dx, double dy) {
        double d1 = cross(cx, cy, dx, dy, ax, ay);
        double d2 = cross(cx, cy, dx, dy, bx, by);
        double d3 = cross(ax, ay, bx, by, cx, cy);
        double d4 = cross(ax, ay, bx, by, dx, dy);
        if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) {
            return true;
        }
        return (d1 == 0 && onSegment(cx, cy, dx, dy, ax, ay)) || (d2 == 0 && onSegment(cx, cy, dx, dy, bx, by))
            || (d3 == 0 && onSegment(ax, ay, bx, by, cx, cy)) || (d4 == 0 && onSegment(ax, ay, bx, by, dx, dy));
    }

    private static double cross(double ax, double ay, double bx, double by, double px, double py) {
        return (bx - ax) * (py - ay) - (by - ay) * (px - ax);
    }

    private static boolean onSegment(double ax, double ay, double bx, double by, double px, double py) {
        return Math.min(ax, bx) <= px && px <= Math.max(ax, bx) && Math.min(ay, by) <= py && py <= Math.max(ay, by);
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
