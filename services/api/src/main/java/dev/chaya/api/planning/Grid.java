package dev.chaya.api.planning;

import dev.chaya.api.planning.Scene.Doorway;
import dev.chaya.api.planning.geometry.Geometry;
import dev.chaya.api.planning.geometry.Point;
import dev.chaya.api.planning.geometry.Polygon;
import dev.chaya.api.planning.geometry.Segment;
import java.util.ArrayList;
import java.util.List;

/**
 * Step 2: the plan-view scene rasterised onto a uniform grid, and the walkable region derived from it.
 *
 * <p>Cell types: OUTSIDE (beyond the room bounds), WALL, OBSTACLE and FREE (floor). Anything not FREE blocks
 * both sight and walking. The target set T (what must be reconstructed) is every FREE cell plus every blocked cell
 * that touches a FREE cell (the wall and obstacle faces). Cells carry an importance weight and class flags.
 */
final class Grid {

    static final byte OUTSIDE = 0;
    static final byte WALL = 1;
    static final byte OBSTACLE = 2;
    static final byte FREE = 3;

    static final int CORNER = 1;
    static final int DOORWAY = 2;
    static final int BOUNDARY = 4;

    static final int[] DI = {1, -1, 0, 0, 1, 1, -1, -1};
    static final int[] DJ = {0, 0, 1, -1, 1, -1, 1, -1};

    private static final double CORNER_RADIUS = 0.75;
    private static final double MIN_CORNER_TURN = Math.toRadians(15);
    private static final double BOUNDARY_BAND = 0.5;

    final double res;
    final double ox;
    final double oy;
    final int nx;
    final int ny;
    final int n;
    final double cellArea;
    final byte[] type;
    final boolean[] target;
    final float[] importance;
    final byte[] cls;
    final float[] clearance; // metres from the cell centre to the nearest blocked face
    final boolean[] standable;
    final List<Point> corners;
    final List<Doorway> doorways;
    final List<String> warnings = new ArrayList<>();

    private Grid(double res, double ox, double oy, int nx, int ny, List<Point> corners, List<Doorway> doorways) {
        this.res = res;
        this.ox = ox;
        this.oy = oy;
        this.nx = nx;
        this.ny = ny;
        this.n = nx * ny;
        this.cellArea = res * res;
        this.type = new byte[n];
        this.target = new boolean[n];
        this.importance = new float[n];
        this.cls = new byte[n];
        this.clearance = new float[n];
        this.standable = new boolean[n];
        this.corners = corners;
        this.doorways = doorways;
    }

    static Grid build(Scene scene, PlannerConfig cfg) {
        if (scene.areas().isEmpty()) {
            throw new PlanningException("NO_AREAS", "scene.areas must contain at least one room polygon");
        }
        for (Polygon p : scene.areas()) {
            if (!p.isValid()) {
                throw new PlanningException("INVALID_GEOMETRY", "scene.areas contains a polygon that is degenerate or has non-finite vertices");
            }
        }
        for (Polygon p : concat(scene.obstacles(), scene.noGoZones())) {
            if (!p.isValid()) {
                throw new PlanningException("INVALID_GEOMETRY", "obstacles and noGoZones must be valid polygons");
            }
        }
        for (Segment s : scene.walls()) {
            if (!s.a().isFinite() || !s.b().isFinite()) {
                throw new PlanningException("INVALID_GEOMETRY", "scene.walls contains non-finite coordinates");
            }
        }
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (Polygon p : scene.areas()) {
            minX = Math.min(minX, p.minX());
            minY = Math.min(minY, p.minY());
            maxX = Math.max(maxX, p.maxX());
            maxY = Math.max(maxY, p.maxY());
        }
        double res = cfg.resolution();
        long nxL = (long) Math.ceil((maxX - minX) / res) + 3;
        long nyL = (long) Math.ceil((maxY - minY) / res) + 3;
        if (nxL * nyL > cfg.maxGridCells()) {
            throw new PlanningException("SCENE_TOO_LARGE", "the scene needs " + nxL * nyL + " grid cells at " + res
                + " m resolution; the limit is " + cfg.maxGridCells() + ". Use a coarser resolution or a smaller area.");
        }

        List<Point> corners = findCorners(scene);
        Grid g = new Grid(res, minX - 1.5 * res, minY - 1.5 * res, (int) nxL, (int) nyL, corners, scene.doorways());
        g.rasterise(scene);
        g.computeClearance();
        g.computeStandable(scene, cfg);
        g.computeTargetsAndImportance(scene, cfg);
        return g;
    }

    private static List<Polygon> concat(List<Polygon> a, List<Polygon> b) {
        List<Polygon> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    // ---- rasterisation -----------------------------------------------------------------------

    private void rasterise(Scene scene) {
        for (int j = 0; j < ny; j++) {
            for (int i = 0; i < nx; i++) {
                double cx = colX(i), cy = rowY(j);
                boolean inside = false;
                for (Polygon a : scene.areas()) {
                    if (a.contains(cx, cy)) {
                        inside = true;
                        break;
                    }
                }
                type[j * nx + i] = inside ? FREE : OUTSIDE;
            }
        }
        for (Polygon o : scene.obstacles()) {
            for (int j = 0; j < ny; j++) {
                for (int i = 0; i < nx; i++) {
                    if (type[j * nx + i] == FREE && o.contains(colX(i), rowY(j))) {
                        type[j * nx + i] = OBSTACLE;
                    }
                }
            }
            List<Point> v = o.vertices();
            for (int k = 0; k < v.size(); k++) {
                markAlong(new Segment(v.get(k), v.get((k + 1) % v.size())), OBSTACLE);
            }
        }
        for (Segment w : scene.walls()) {
            markAlong(w, WALL);
        }
    }

    private void markAlong(Segment s, byte as) {
        double len = s.length();
        int steps = Math.max(1, (int) Math.ceil(len / (res / 2)));
        for (int k = 0; k <= steps; k++) {
            double f = (double) k / steps;
            int c = cellOf(s.a().x() + (s.b().x() - s.a().x()) * f, s.a().y() + (s.b().y() - s.a().y()) * f);
            if (c >= 0 && type[c] == FREE) {
                type[c] = as;
            }
        }
    }

    private void computeClearance() {
        final float inf = 1e9f;
        final float diag = (float) Math.sqrt(2);
        float[] d = new float[n];
        for (int c = 0; c < n; c++) {
            d[c] = type[c] == FREE ? inf : 0;
        }
        for (int j = 0; j < ny; j++) {
            for (int i = 0; i < nx; i++) {
                int c = j * nx + i;
                float v = d[c];
                if (i > 0) v = Math.min(v, d[c - 1] + 1);
                if (j > 0) {
                    v = Math.min(v, d[c - nx] + 1);
                    if (i > 0) v = Math.min(v, d[c - nx - 1] + diag);
                    if (i < nx - 1) v = Math.min(v, d[c - nx + 1] + diag);
                }
                d[c] = v;
            }
        }
        for (int j = ny - 1; j >= 0; j--) {
            for (int i = nx - 1; i >= 0; i--) {
                int c = j * nx + i;
                float v = d[c];
                if (i < nx - 1) v = Math.min(v, d[c + 1] + 1);
                if (j < ny - 1) {
                    v = Math.min(v, d[c + nx] + 1);
                    if (i < nx - 1) v = Math.min(v, d[c + nx + 1] + diag);
                    if (i > 0) v = Math.min(v, d[c + nx - 1] + diag);
                }
                d[c] = v;
            }
        }
        for (int c = 0; c < n; c++) {
            clearance[c] = type[c] == FREE ? (float) Math.max(0, d[c] * res - res / 2) : 0;
        }
    }

    private void computeStandable(Scene scene, PlannerConfig cfg) {
        for (int c = 0; c < n; c++) {
            boolean ok = type[c] == FREE && clearance[c] >= cfg.agentRadius();
            if (ok) {
                double cx = colX(c % nx), cy = rowY(c / nx);
                for (Polygon z : scene.noGoZones()) {
                    if (z.contains(cx, cy)) {
                        ok = false;
                        break;
                    }
                }
            }
            standable[c] = ok;
        }
    }

    private void computeTargetsAndImportance(Scene scene, PlannerConfig cfg) {
        for (int j = 0; j < ny; j++) {
            for (int i = 0; i < nx; i++) {
                int c = j * nx + i;
                if (type[c] == FREE) {
                    target[c] = true;
                } else {
                    for (int k = 0; k < 8; k++) {
                        int ni = i + DI[k], nj = j + DJ[k];
                        if (ni >= 0 && nj >= 0 && ni < nx && nj < ny && type[nj * nx + ni] == FREE) {
                            target[c] = true;
                            break;
                        }
                    }
                }
            }
        }
        for (int c = 0; c < n; c++) {
            if (!target[c]) {
                continue;
            }
            importance[c] = 1f;
            if (type[c] != FREE || clearance[c] < BOUNDARY_BAND) {
                importance[c] = (float) cfg.boundaryWeight();
                cls[c] |= BOUNDARY;
            }
        }
        for (Point p : corners) {
            forCellsWithin(p.x(), p.y(), CORNER_RADIUS, c -> {
                if (target[c]) {
                    importance[c] = Math.max(importance[c], (float) cfg.cornerWeight());
                    cls[c] |= CORNER;
                }
            });
        }
        for (Doorway d : doorways) {
            int c0 = cellOf(d.x(), d.y());
            if (c0 < 0 || type[c0] != FREE) {
                warnings.add("doorway at (" + d.x() + ", " + d.y() + ") is not on free floor and was ignored");
                continue;
            }
            forCellsWithin(d.x(), d.y(), d.width() / 2 + 0.5, c -> {
                if (target[c] && type[c] == FREE) {
                    importance[c] = Math.max(importance[c], (float) cfg.doorwayWeight());
                    cls[c] |= DOORWAY;
                }
            });
        }
    }

    private interface CellAction {
        void apply(int cell);
    }

    private void forCellsWithin(double x, double y, double radius, CellAction action) {
        int r = (int) Math.ceil(radius / res);
        int ci = (int) Math.floor((x - ox) / res);
        int cj = (int) Math.floor((y - oy) / res);
        for (int j = cj - r; j <= cj + r; j++) {
            for (int i = ci - r; i <= ci + r; i++) {
                if (i >= 0 && j >= 0 && i < nx && j < ny && Math.hypot(colX(i) - x, rowY(j) - y) <= radius) {
                    action.apply(j * nx + i);
                }
            }
        }
    }

    private static List<Point> findCorners(Scene scene) {
        List<Point> out = new ArrayList<>();
        for (Polygon p : scene.areas()) {
            addCorners(p, out);
        }
        for (Polygon p : scene.obstacles()) {
            addCorners(p, out);
        }
        for (Segment s : scene.walls()) {
            addUnique(out, s.a());
            addUnique(out, s.b());
        }
        return List.copyOf(out);
    }

    private static void addCorners(Polygon poly, List<Point> out) {
        List<Point> v = poly.vertices();
        int m = v.size();
        for (int i = 0; i < m; i++) {
            Point prev = v.get((i + m - 1) % m), cur = v.get(i), next = v.get((i + 1) % m);
            double turn = Geometry.angleDiff(Math.atan2(cur.y() - prev.y(), cur.x() - prev.x()), Math.atan2(next.y() - cur.y(), next.x() - cur.x()));
            if (turn >= MIN_CORNER_TURN) {
                addUnique(out, cur);
            }
        }
    }

    private static void addUnique(List<Point> out, Point p) {
        for (Point q : out) {
            if (q.distanceTo(p) < 0.3) {
                return;
            }
        }
        out.add(p);
    }

    // ---- lookups ------------------------------------------------------------------------------

    double colX(int i) {
        return ox + (i + 0.5) * res;
    }

    double rowY(int j) {
        return oy + (j + 0.5) * res;
    }

    double centerX(int cell) {
        return colX(cell % nx);
    }

    double centerY(int cell) {
        return rowY(cell / nx);
    }

    /** The cell containing the point, or -1 if it lies outside the grid. */
    int cellOf(double x, double y) {
        int i = (int) Math.floor((x - ox) / res);
        int j = (int) Math.floor((y - oy) / res);
        return i < 0 || j < 0 || i >= nx || j >= ny ? -1 : j * nx + i;
    }

    boolean blocked(int i, int j) {
        return i < 0 || j < 0 || i >= nx || j >= ny || type[j * nx + i] != FREE;
    }

    boolean walkable(int i, int j) {
        return i >= 0 && j >= 0 && i < nx && j < ny && standable[j * nx + i];
    }

    /** The nearest standable cell within maxRadiusCells (ties broken by lower index), or -1. */
    int snapToStandable(double x, double y, int maxRadiusCells) {
        int c0 = cellOf(x, y);
        if (c0 < 0) {
            return -1;
        }
        if (standable[c0]) {
            return c0;
        }
        int ci = c0 % nx, cj = c0 / nx;
        int best = -1;
        double bestD = Double.MAX_VALUE;
        for (int j = cj - maxRadiusCells; j <= cj + maxRadiusCells; j++) {
            for (int i = ci - maxRadiusCells; i <= ci + maxRadiusCells; i++) {
                if (walkable(i, j)) {
                    double d = Math.hypot(colX(i) - x, rowY(j) - y);
                    if (d < bestD - 1e-12) {
                        bestD = d;
                        best = j * nx + i;
                    }
                }
            }
        }
        return best;
    }

    /** Cells the operator can reach from any seed by walking (8-connected, no corner cutting). */
    boolean[] reachableFrom(List<Integer> seeds) {
        boolean[] seen = new boolean[n];
        int[] queue = new int[n];
        int head = 0, tail = 0;
        for (int s : seeds) {
            if (s >= 0 && standable[s] && !seen[s]) {
                seen[s] = true;
                queue[tail++] = s;
            }
        }
        while (head < tail) {
            int c = queue[head++];
            int ci = c % nx, cj = c / nx;
            for (int k = 0; k < 8; k++) {
                int ni = ci + DI[k], nj = cj + DJ[k];
                if (!walkable(ni, nj) || seen[nj * nx + ni]) {
                    continue;
                }
                if (k >= 4 && !(walkable(ci + DI[k], cj) && walkable(ci, cj + DJ[k]))) {
                    continue;
                }
                seen[nj * nx + ni] = true;
                queue[tail++] = nj * nx + ni;
            }
        }
        return seen;
    }

    double standableArea() {
        int count = 0;
        for (boolean s : standable) {
            if (s) count++;
        }
        return count * cellArea;
    }
}
