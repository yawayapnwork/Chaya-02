package dev.chaya.api.planning;

import dev.chaya.api.planning.geometry.Point;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;

/** Shortest walking paths on the standable grid: Dijkstra for distances, line-of-sight smoothing for the polyline. */
final class PathFinder {

    static final double UNREACHABLE = Double.POSITIVE_INFINITY;
    private static final double DIAG = Math.sqrt(2);

    record Field(double[] dist, int[] parent) {}

    private PathFinder() {}

    /** Geodesic walking distance in metres from src to every cell (UNREACHABLE where there is no path). */
    static Field from(Grid g, int src) {
        double[] dist = new double[g.n];
        int[] parent = new int[g.n];
        java.util.Arrays.fill(dist, UNREACHABLE);
        java.util.Arrays.fill(parent, -1);
        if (src < 0 || !g.standable[src]) {
            return new Field(dist, parent);
        }
        PriorityQueue<double[]> queue = new PriorityQueue<>((p, q) -> p[0] != q[0] ? Double.compare(p[0], q[0]) : Double.compare(p[1], q[1]));
        dist[src] = 0;
        queue.add(new double[] {0, src});
        while (!queue.isEmpty()) {
            double[] top = queue.poll();
            int c = (int) top[1];
            if (top[0] > dist[c]) {
                continue;
            }
            int ci = c % g.nx, cj = c / g.nx;
            for (int k = 0; k < 8; k++) {
                int ni = ci + Grid.DI[k], nj = cj + Grid.DJ[k];
                if (!g.walkable(ni, nj)) {
                    continue;
                }
                if (k >= 4 && !(g.walkable(ci + Grid.DI[k], cj) && g.walkable(ci, cj + Grid.DJ[k]))) {
                    continue;
                }
                int nc = nj * g.nx + ni;
                double nd = dist[c] + (k < 4 ? 1 : DIAG) * g.res;
                if (nd < dist[nc] - 1e-12) {
                    dist[nc] = nd;
                    parent[nc] = c;
                    queue.add(new double[] {nd, nc});
                }
            }
        }
        return new Field(dist, parent);
    }

    /** A smoothed polyline from (from) to (to) through the grid path src -> dst. Empty if dst is unreachable. */
    static List<Point> path(Grid g, Field field, int src, int dst, Point from, Point to) {
        if (dst < 0 || field.dist[dst] == UNREACHABLE) {
            return List.of();
        }
        List<Integer> cells = new ArrayList<>();
        for (int c = dst; c != -1; c = field.parent[c]) {
            cells.add(c);
        }
        Collections.reverse(cells);
        List<Point> out = new ArrayList<>();
        out.add(from);
        int anchor = 0;
        while (anchor < cells.size() - 1) {
            int far = anchor + 1;
            for (int k = cells.size() - 1; k > anchor + 1; k--) {
                if (walkLine(g, cells.get(anchor), cells.get(k))) {
                    far = k;
                    break;
                }
            }
            if (far == cells.size() - 1) {
                break;
            }
            out.add(new Point(g.centerX(cells.get(far)), g.centerY(cells.get(far))));
            anchor = far;
        }
        out.add(to);
        return out;
    }

    static double length(List<Point> polyline) {
        double s = 0;
        for (int i = 0; i + 1 < polyline.size(); i++) {
            s += polyline.get(i).distanceTo(polyline.get(i + 1));
        }
        return s;
    }

    /** True if every cell on the straight line between the two cells is standable (no corner cutting). */
    private static boolean walkLine(Grid g, int a, int b) {
        int x0 = a % g.nx, y0 = a / g.nx, x1 = b % g.nx, y1 = b / g.nx;
        int dx = Math.abs(x1 - x0), dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1;
        int err = dx - dy;
        int x = x0, y = y0;
        while (!(x == x1 && y == y1)) {
            int e2 = 2 * err;
            int nx = x, ny = y;
            boolean stepX = false, stepY = false;
            if (e2 > -dy) {
                err -= dy;
                nx += sx;
                stepX = true;
            }
            if (e2 < dx) {
                err += dx;
                ny += sy;
                stepY = true;
            }
            if (stepX && stepY && !(g.walkable(x + sx, y) && g.walkable(x, y + sy))) {
                return false;
            }
            x = nx;
            y = ny;
            if (!g.walkable(x, y)) {
                return false;
            }
        }
        return true;
    }
}
