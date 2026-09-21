package dev.chaya.api.planning.geometry;

import java.util.List;

/** A simple polygon given by its vertices in order (either winding). */
public record Polygon(List<Point> vertices) {

    public Polygon {
        vertices = List.copyOf(vertices);
    }

    public double area() {
        double s = 0;
        for (int i = 0; i < vertices.size(); i++) {
            Point p = vertices.get(i);
            Point q = vertices.get((i + 1) % vertices.size());
            s += p.x() * q.y() - q.x() * p.y();
        }
        return Math.abs(s) / 2;
    }

    /** Even-odd ray casting. Points exactly on an edge may fall either way. */
    public boolean contains(double x, double y) {
        boolean inside = false;
        int n = vertices.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            Point a = vertices.get(i);
            Point b = vertices.get(j);
            if ((a.y() > y) != (b.y() > y) && x < (b.x() - a.x()) * (y - a.y()) / (b.y() - a.y()) + a.x()) {
                inside = !inside;
            }
        }
        return inside;
    }

    public double minX() {
        return vertices.stream().mapToDouble(Point::x).min().orElse(0);
    }

    public double maxX() {
        return vertices.stream().mapToDouble(Point::x).max().orElse(0);
    }

    public double minY() {
        return vertices.stream().mapToDouble(Point::y).min().orElse(0);
    }

    public double maxY() {
        return vertices.stream().mapToDouble(Point::y).max().orElse(0);
    }

    public boolean isValid() {
        return vertices.size() >= 3 && vertices.stream().allMatch(Point::isFinite) && area() > 1e-9;
    }
}
