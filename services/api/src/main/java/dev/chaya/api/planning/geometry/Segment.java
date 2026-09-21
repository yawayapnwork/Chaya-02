package dev.chaya.api.planning.geometry;

public record Segment(Point a, Point b) {

    public double length() {
        return a.distanceTo(b);
    }
}
