package dev.chaya.api.planning;

import dev.chaya.api.planning.RoomBoundsEstimator.SurfaceObservation;
import dev.chaya.api.planning.geometry.Point;
import dev.chaya.api.planning.geometry.Polygon;
import dev.chaya.api.planning.geometry.Segment;
import java.util.ArrayList;
import java.util.List;

/**
 * TEST ONLY. A deterministic stand-in for a device's surface hit test: from a walked position it casts plan-view rays
 * against hand-drawn ground-truth geometry and reports the nearest surface point with its normal, exactly the shape of
 * observation the HUD receives from WebXR hit tests. It exists so the bounds estimator and the planner can be checked
 * against a known answer; it is not used by the product and says nothing about real sensor noise.
 */
public final class SyntheticRangeSensor {

    private final List<Segment> surfaces = new ArrayList<>();
    private final double maxRange;

    /** Room outlines, interior walls and obstacles of the true scene all reflect rays. */
    public SyntheticRangeSensor(Scene truth, double maxRange) {
        this.maxRange = maxRange;
        for (Polygon p : truth.areas()) {
            addRing(p);
        }
        for (Polygon p : truth.obstacles()) {
            addRing(p);
        }
        surfaces.addAll(truth.walls());
    }

    private void addRing(Polygon p) {
        List<Point> v = p.vertices();
        for (int i = 0; i < v.size(); i++) {
            surfaces.add(new Segment(v.get(i), v.get((i + 1) % v.size())));
        }
    }

    /** The nearest surface along the ray, or null within maxRange. Doorways (gaps between walls) let the ray through. */
    public SurfaceObservation cast(double x, double y, double headingRadians) {
        double dx = Math.cos(headingRadians), dy = Math.sin(headingRadians);
        double best = maxRange;
        Segment hit = null;
        for (Segment s : surfaces) {
            double ex = s.b().x() - s.a().x(), ey = s.b().y() - s.a().y();
            double den = dx * ey - dy * ex;
            if (Math.abs(den) < 1e-12) {
                continue;
            }
            double wx = s.a().x() - x, wy = s.a().y() - y;
            double t = (wx * ey - wy * ex) / den;
            double u = (wx * dy - wy * dx) / den;
            if (t > 1e-6 && t < best && u >= 0 && u <= 1) {
                best = t;
                hit = s;
            }
        }
        if (hit == null) {
            return null;
        }
        double ex = hit.b().x() - hit.a().x(), ey = hit.b().y() - hit.a().y();
        double nx = -ey, ny = ex; // either normal; flip it to face the camera
        if (nx * dx + ny * dy > 0) {
            nx = -nx;
            ny = -ny;
        }
        return new SurfaceObservation(x, y, x + best * dx, y + best * dy, "WALL", Math.toDegrees(Math.atan2(ny, nx)));
    }

    /**
     * A recon walk's observations: at every sample the camera looks in `rays` directions spread `spreadDegrees` apart
     * around a heading that turns at `turnDegreesPerSecond` (an operator panning while walking), restricted to headings
     * within `allowed` (null: all). Rays are emitted in order, so consecutive observations are neighbours.
     */
    public List<SurfaceObservation> sweep(List<TrajectorySample> walk, int rays, double spreadDegrees, double turnDegreesPerSecond,
                                          double[] allowed) {
        List<SurfaceObservation> out = new ArrayList<>();
        for (TrajectorySample s : walk) {
            double center = Math.toRadians(s.t() * turnDegreesPerSecond);
            for (int k = 0; k < rays; k++) {
                double h = center + Math.toRadians((k - (rays - 1) / 2.0) * spreadDegrees);
                if (allowed != null && !within(h, allowed)) {
                    continue;
                }
                SurfaceObservation o = cast(s.x(), s.y(), h);
                if (o != null) {
                    out.add(o);
                }
            }
        }
        return out;
    }

    /** Full sweeps: the camera covers every direction during the walk. */
    public List<SurfaceObservation> sweep(List<TrajectorySample> walk) {
        List<SurfaceObservation> out = new ArrayList<>();
        for (TrajectorySample s : walk) {
            for (int k = 0; k < 72; k++) {
                SurfaceObservation o = cast(s.x(), s.y(), Math.toRadians(k * 5.0));
                if (o != null) {
                    out.add(o);
                }
            }
        }
        return out;
    }

    private static boolean within(double h, double[] range) {
        double a = Math.toRadians(range[0]), b = Math.toRadians(range[1]);
        double x = ((h - a) % (2 * Math.PI) + 2 * Math.PI) % (2 * Math.PI);
        double w = ((b - a) % (2 * Math.PI) + 2 * Math.PI) % (2 * Math.PI);
        return x <= w;
    }

    /** Intersection over union of two plan-view regions, by point sampling at `step` over the union's bounding box. */
    public static double iou(Polygon a, List<Polygon> aHoles, Polygon b, double step) {
        double minX = Math.min(a.minX(), b.minX()), maxX = Math.max(a.maxX(), b.maxX());
        double minY = Math.min(a.minY(), b.minY()), maxY = Math.max(a.maxY(), b.maxY());
        int inter = 0, union = 0;
        for (double y = minY + step / 2; y < maxY; y += step) {
            for (double x = minX + step / 2; x < maxX; x += step) {
                double px = x, py = y;
                boolean inA = a.contains(x, y) && aHoles.stream().noneMatch(h -> h.contains(px, py));
                boolean inB = b.contains(x, y);
                if (inA && inB) inter++;
                if (inA || inB) union++;
            }
        }
        return union == 0 ? 0 : (double) inter / union;
    }

    /** Share of region a (sampled) that lies inside b. */
    public static double containedShare(Polygon a, Polygon b, double step) {
        int in = 0, all = 0;
        for (double y = a.minY() + step / 2; y < a.maxY(); y += step) {
            for (double x = a.minX() + step / 2; x < a.maxX(); x += step) {
                if (a.contains(x, y)) {
                    all++;
                    if (b.contains(x, y)) in++;
                }
            }
        }
        return all == 0 ? 0 : (double) in / all;
    }
}
