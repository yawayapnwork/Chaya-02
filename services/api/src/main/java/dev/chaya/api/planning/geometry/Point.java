package dev.chaya.api.planning.geometry;

/** A point in the plan view, metres. x is east, y is north; headings are radians counter-clockwise from +x. */
public record Point(double x, double y) {

    public boolean isFinite() {
        return Double.isFinite(x) && Double.isFinite(y);
    }

    public double distanceTo(Point o) {
        return Math.hypot(x - o.x, y - o.y);
    }
}
