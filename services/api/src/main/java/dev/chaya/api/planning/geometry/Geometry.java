package dev.chaya.api.planning.geometry;

public final class Geometry {

    private Geometry() {}

    /** Wraps an angle into (-pi, pi]. */
    public static double wrapAngle(double a) {
        double r = a % (2 * Math.PI);
        if (r > Math.PI) {
            r -= 2 * Math.PI;
        } else if (r <= -Math.PI) {
            r += 2 * Math.PI;
        }
        return r;
    }

    /** Smallest absolute difference between two angles, in [0, pi]. */
    public static double angleDiff(double a, double b) {
        return Math.abs(wrapAngle(a - b));
    }

    public static double distancePointSegment(double px, double py, Segment s) {
        double ax = s.a().x(), ay = s.a().y(), bx = s.b().x(), by = s.b().y();
        double dx = bx - ax, dy = by - ay;
        double len2 = dx * dx + dy * dy;
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / len2));
        return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
    }

    private static double cross(double ax, double ay, double bx, double by, double cx, double cy) {
        return (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
    }

    /** True if the closed segments p and q share at least one point. */
    public static boolean segmentsIntersect(Segment p, Segment q) {
        double d1 = cross(q.a().x(), q.a().y(), q.b().x(), q.b().y(), p.a().x(), p.a().y());
        double d2 = cross(q.a().x(), q.a().y(), q.b().x(), q.b().y(), p.b().x(), p.b().y());
        double d3 = cross(p.a().x(), p.a().y(), p.b().x(), p.b().y(), q.a().x(), q.a().y());
        double d4 = cross(p.a().x(), p.a().y(), p.b().x(), p.b().y(), q.b().x(), q.b().y());
        if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) {
            return true;
        }
        return (d1 == 0 && onSegment(q, p.a())) || (d2 == 0 && onSegment(q, p.b()))
            || (d3 == 0 && onSegment(p, q.a())) || (d4 == 0 && onSegment(p, q.b()));
    }

    private static boolean onSegment(Segment s, Point p) {
        return Math.min(s.a().x(), s.b().x()) <= p.x() && p.x() <= Math.max(s.a().x(), s.b().x())
            && Math.min(s.a().y(), s.b().y()) <= p.y() && p.y() <= Math.max(s.a().y(), s.b().y());
    }
}
