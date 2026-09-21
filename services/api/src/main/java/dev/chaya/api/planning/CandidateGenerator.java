package dev.chaya.api.planning;

import dev.chaya.api.planning.Scene.Doorway;
import dev.chaya.api.planning.PlanResult.RejectedCandidate;
import dev.chaya.api.planning.geometry.Point;
import java.util.ArrayList;
import java.util.List;

/**
 * Step 3: candidate viewpoints. Fully deterministic; no sampling noise.
 *
 * <p>In priority order: caller-supplied viewpoints (validated), a viewpoint in each doorway and one target
 * distance to either side, a viewpoint about one target distance from each corner, a ring of viewpoints about one
 * target distance from the walls, and finally a regular lattice over the walkable floor. A candidate only ever
 * stands on a cell that is standable and reachable on foot from the recon lap; duplicates of a cell are dropped.
 */
final class CandidateGenerator {

    static final String USER = "USER";
    static final String DOORWAY = "DOORWAY";
    static final String CORNER = "CORNER";
    static final String BOUNDARY = "BOUNDARY";
    static final String GRID = "GRID";

    record Candidate(int cell, double x, double y, String origin, String label) {}

    record Output(List<Candidate> candidates, List<RejectedCandidate> rejected, int generated) {}

    private final Grid g;
    private final PlannerConfig cfg;
    private final boolean[] reachable;
    private final Visibility vis;
    private final boolean[] taken;
    private final List<Candidate> out = new ArrayList<>();
    private final List<RejectedCandidate> rejected = new ArrayList<>();

    CandidateGenerator(Grid g, PlannerConfig cfg, boolean[] reachable, Visibility vis) {
        this.g = g;
        this.cfg = cfg;
        this.reachable = reachable;
        this.vis = vis;
        this.taken = new boolean[g.n];
    }

    Output generate(List<CandidateViewpoint> supplied) {
        for (CandidateViewpoint u : supplied) {
            validateAndAdd(u);
        }
        double d = cfg.targetDistance();
        for (Doorway door : g.doorways) {
            int c = g.snapToStandable(door.x(), door.y(), 2);
            if (usable(c)) {
                add(c, DOORWAY, "doorway");
            }
            double[] dirs = door.passageRadians() != null
                ? new double[] {door.passageRadians(), door.passageRadians() + Math.PI}
                : new double[] {0, Math.PI / 2, Math.PI, 3 * Math.PI / 2};
            for (double a : dirs) {
                int s = g.snapToStandable(door.x() + d * Math.cos(a), door.y() + d * Math.sin(a), 2);
                if (usable(s)) {
                    add(s, DOORWAY, "doorway approach");
                }
            }
        }
        for (Point corner : g.corners) {
            int best = -1;
            double bestErr = Double.MAX_VALUE;
            int cc = g.cellOf(corner.x(), corner.y());
            if (cc < 0) {
                continue;
            }
            int span = (int) Math.ceil(1.5 * d / g.res);
            int ci = cc % g.nx, cj = cc / g.nx;
            for (int j = cj - span; j <= cj + span; j++) {
                for (int i = ci - span; i <= ci + span; i++) {
                    if (!g.walkable(i, j) || !reachable[j * g.nx + i]) {
                        continue;
                    }
                    double dist = Math.hypot(g.colX(i) - corner.x(), g.rowY(j) - corner.y());
                    if (dist < 0.5 * d || dist > 1.5 * d || !vis.clear(i, j, ci, cj)) {
                        continue;
                    }
                    double err = Math.abs(dist - d);
                    if (err < bestErr - 1e-12) {
                        bestErr = err;
                        best = j * g.nx + i;
                    }
                }
            }
            if (best >= 0) {
                add(best, CORNER, "corner");
            }
        }
        // Wall ring: standable cells whose clearance is about one target distance, thinned to the minimum separation.
        List<Candidate> ring = new ArrayList<>();
        for (int c = 0; c < g.n; c++) {
            if (usable(c) && Math.abs(g.clearance[c] - d) <= g.res) {
                double x = g.centerX(c), y = g.centerY(c);
                boolean farEnough = true;
                for (Candidate r : ring) {
                    if (Math.hypot(r.x() - x, r.y() - y) < Math.max(cfg.minSeparation(), g.res)) {
                        farEnough = false;
                        break;
                    }
                }
                if (farEnough) {
                    ring.add(new Candidate(c, x, y, BOUNDARY, "wall ring"));
                }
            }
        }
        for (Candidate r : ring) {
            add(r.cell(), BOUNDARY, r.label());
        }
        // Lattice over the floor, evenly subsampled if it would exceed the candidate budget.
        int stride = Math.max(1, (int) Math.round(Math.max(cfg.minSeparation(), 2 * g.res) / g.res));
        List<Integer> lattice = new ArrayList<>();
        for (int j = 0; j < g.ny; j += stride) {
            for (int i = 0; i < g.nx; i += stride) {
                int c = j * g.nx + i;
                if (usable(c) && !taken[c]) {
                    lattice.add(c);
                }
            }
        }
        int room = Math.max(0, cfg.maxCandidates() - out.size());
        if (lattice.size() <= room) {
            lattice.forEach(c -> add(c, GRID, "lattice"));
        } else {
            for (int k = 0; k < room; k++) {
                add(lattice.get((int) ((long) k * lattice.size() / room)), GRID, "lattice");
            }
        }
        return new Output(List.copyOf(out), List.copyOf(rejected), out.size());
    }

    private boolean usable(int c) {
        return c >= 0 && g.standable[c] && reachable[c];
    }

    private void add(int cell, String origin, String label) {
        if (taken[cell] || out.size() >= cfg.maxCandidates()) {
            return;
        }
        taken[cell] = true;
        out.add(new Candidate(cell, g.centerX(cell), g.centerY(cell), origin, label));
    }

    private void validateAndAdd(CandidateViewpoint u) {
        String label = u.label() == null ? "supplied" : u.label();
        if (!Double.isFinite(u.x()) || !Double.isFinite(u.y())) {
            rejected.add(new RejectedCandidate(u.x(), u.y(), label, "NOT_FINITE"));
            return;
        }
        int c = g.cellOf(u.x(), u.y());
        if (c < 0 || g.type[c] == Grid.OUTSIDE) {
            rejected.add(new RejectedCandidate(u.x(), u.y(), label, "OUTSIDE_BOUNDS"));
        } else if (g.type[c] != Grid.FREE) {
            rejected.add(new RejectedCandidate(u.x(), u.y(), label, "IN_OBSTACLE"));
        } else if (!g.standable[c]) {
            rejected.add(new RejectedCandidate(u.x(), u.y(), label, g.clearance[c] < cfg.agentRadius() ? "TOO_CLOSE_TO_WALL" : "IN_NO_GO_ZONE"));
        } else if (!reachable[c]) {
            rejected.add(new RejectedCandidate(u.x(), u.y(), label, "UNREACHABLE"));
        } else if (taken[c]) {
            rejected.add(new RejectedCandidate(u.x(), u.y(), label, "DUPLICATE"));
        } else {
            add(c, USER, label);
        }
    }
}
