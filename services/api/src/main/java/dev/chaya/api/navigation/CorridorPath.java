package dev.chaya.api.navigation;

import java.util.ArrayList;
import java.util.List;

/**
 * The straight path through a corridor of convex navmesh polygons: the "simple stupid funnel" string-pulling algorithm
 * (Mononen), which is what Detour's {@code dtNavMeshQuery::findStraightPath} does over a {@code findPath} corridor (review
 * N-3). Pure, canonical metres, +Z up.
 *
 * <p>The corridor is the sequence of portals between consecutive polygons, each a segment of the polygons' shared edge.
 * The funnel runs on the horizontal (x, y) projection, which is where walls and obstacles are; each corner the path
 * bends at is a portal endpoint and keeps that endpoint's height, so the path follows a ramp or a step where it turns.
 * Because every segment stays inside the funnel of the portals it passes, it never leaves the union of the corridor's
 * polygons; the polygon-centroid polyline it replaces could cut a corner through a wall.
 */
final class CorridorPath {

    private CorridorPath() {}

    /** A portal as crossed in the travel direction: its endpoint on the traveller's left and on their right. */
    record Portal(double[] left, double[] right) {}

    /**
     * Orients each portal for travel along {@code centres} (the corridor's polygon centres, one more than portals):
     * "left" is the endpoint counter-clockwise of the direction from centre i to centre i+1, seen from +Z.
     */
    static List<Portal> orient(List<double[]> centres, List<double[][]> portals) {
        if (centres.size() != portals.size() + 1) {
            throw new IllegalArgumentException("a corridor of n polygons has n - 1 portals");
        }
        List<Portal> out = new ArrayList<>(portals.size());
        for (int i = 0; i < portals.size(); i++) {
            double[] a = portals.get(i)[0];
            double[] b = portals.get(i)[1];
            double[] from = centres.get(i);
            double[] to = centres.get(i + 1);
            double dx = to[0] - from[0];
            double dy = to[1] - from[1];
            double mx = (a[0] + b[0]) / 2;
            double my = (a[1] + b[1]) / 2;
            double side = dx * (a[1] - my) - dy * (a[0] - mx);  // > 0: a is counter-clockwise (left) of the travel direction
            out.add(side >= 0 ? new Portal(a, b) : new Portal(b, a));
        }
        return out;
    }

    /** Twice the signed area of triangle (a, b, c) in Mononen's convention: positive when c is clockwise of a->b. */
    private static double triarea2(double[] a, double[] b, double[] c) {
        double ax = b[0] - a[0];
        double ay = b[1] - a[1];
        double bx = c[0] - a[0];
        double by = c[1] - a[1];
        return bx * ay - ax * by;
    }

    private static boolean same(double[] a, double[] b) {
        return Math.abs(a[0] - b[0]) < 1e-9 && Math.abs(a[1] - b[1]) < 1e-9;
    }

    /** The string-pulled path from {@code start} to {@code end} through the oriented portals: start, every corner, end. */
    static List<double[]> pull(double[] start, double[] end, List<Portal> portals) {
        List<Portal> all = new ArrayList<>(portals.size() + 2);
        all.add(new Portal(start, start));
        all.addAll(portals);
        all.add(new Portal(end, end));

        List<double[]> path = new ArrayList<>();
        path.add(start);
        double[] apex = start;
        double[] left = start;
        double[] right = start;
        int apexIndex = 0;
        int leftIndex = 0;
        int rightIndex = 0;
        for (int i = 1; i < all.size(); i++) {
            double[] l = all.get(i).left();
            double[] r = all.get(i).right();
            if (triarea2(apex, right, r) <= 0.0) {  // the right side can tighten
                if (same(apex, right) || triarea2(apex, left, r) > 0.0) {
                    right = r;
                    rightIndex = i;
                } else {  // it crosses the left side: the left corner is a turn of the path
                    path.add(left);
                    apex = left;
                    apexIndex = leftIndex;
                    left = apex;
                    right = apex;
                    leftIndex = apexIndex;
                    rightIndex = apexIndex;
                    i = apexIndex;
                    continue;
                }
            }
            if (triarea2(apex, left, l) >= 0.0) {  // the left side can tighten
                if (same(apex, left) || triarea2(apex, right, l) < 0.0) {
                    left = l;
                    leftIndex = i;
                } else {  // it crosses the right side: the right corner is a turn of the path
                    path.add(right);
                    apex = right;
                    apexIndex = rightIndex;
                    left = apex;
                    right = apex;
                    leftIndex = apexIndex;
                    rightIndex = apexIndex;
                    i = apexIndex;
                }
            }
        }
        if (!same(path.get(path.size() - 1), end) || path.size() == 1) {
            path.add(end);
        }
        return path;
    }

    /** The polyline through every portal's midpoint: inside the corridor too (each segment joins two points on the boundary
     * of one convex polygon), but not shortest. Used when the string-pulled path crosses a reported obstacle. */
    static List<double[]> midpoints(double[] start, double[] end, List<Portal> portals) {
        List<double[]> path = new ArrayList<>(portals.size() + 2);
        path.add(start);
        for (Portal p : portals) {
            path.add(new double[]{(p.left()[0] + p.right()[0]) / 2, (p.left()[1] + p.right()[1]) / 2, (p.left()[2] + p.right()[2]) / 2});
        }
        path.add(end);
        return path;
    }
}
