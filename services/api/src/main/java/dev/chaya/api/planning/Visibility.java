package dev.chaya.api.planning;

import dev.chaya.api.planning.geometry.Geometry;
import java.util.BitSet;
import java.util.List;

/**
 * Step 4: which target cells a camera at a given position can observe, and how well.
 *
 * <p>A cell is visible from a point if (a) its distance d lies in the usable range, (b) the straight line to it is
 * not interrupted by a blocked cell (a discrete line of sight that also refuses to slip diagonally between two
 * blocked cells), and, for an oriented view, (c) the bearing is inside the field of view.
 *
 * <p>Quality of one view: w = wd(d) * wf(a), with wd(d) = max(0, 1 - |d - D| / D) (best at the target distance D)
 * and wf(a) = 1 - 0.5 (a / (FOV/2))^2 (mild preference for the image centre). Views below the minimum quality are dropped.
 */
final class Visibility {

    /** Observations of many cells from one position. Arrays are parallel; bearing is view point to cell, back is cell to view point. */
    static final class Obs {
        final int[] cell;
        final float[] w;
        final float[] bearing;
        final float[] back;
        final int n;

        Obs(int[] cell, float[] w, float[] bearing, float[] back, int n) {
            this.cell = cell;
            this.w = w;
            this.bearing = bearing;
            this.back = back;
            this.n = n;
        }

        static final Obs EMPTY = new Obs(new int[0], new float[0], new float[0], new float[0], 0);
    }

    private final Grid g;
    private final PlannerConfig cfg;
    private final double dmin;
    private final double dmax;
    private final int reach;

    Visibility(Grid g, PlannerConfig cfg) {
        this.g = g;
        this.cfg = cfg;
        this.dmin = cfg.minRange();
        this.dmax = cfg.maxRange();
        this.reach = (int) Math.ceil(dmax / g.res);
    }

    /**
     * All target cells within range with a clear line of sight from (px, py), in any direction.
     *
     * @param shadowOut if not null, cells that are in range but hidden behind geometry are recorded here
     */
    Obs around(double px, double py, BitSet shadowOut) {
        int src = g.cellOf(px, py);
        if (src < 0) {
            return Obs.EMPTY;
        }
        int si = src % g.nx, sj = src / g.nx;
        float[] wBuf = new float[(2 * reach + 1) * (2 * reach + 1)];
        float[] bBuf = new float[wBuf.length];
        float[] kBuf = new float[wBuf.length];
        int[] cBuf = new int[wBuf.length];
        int m = 0;
        for (int j = Math.max(0, sj - reach); j <= Math.min(g.ny - 1, sj + reach); j++) {
            for (int i = Math.max(0, si - reach); i <= Math.min(g.nx - 1, si + reach); i++) {
                int c = j * g.nx + i;
                if (!g.target[c] || c == src) {
                    continue;
                }
                double dx = g.colX(i) - px, dy = g.rowY(j) - py;
                double d = Math.hypot(dx, dy);
                if (d < dmin || d > dmax) {
                    continue;
                }
                if (!clear(si, sj, i, j)) {
                    if (shadowOut != null) {
                        shadowOut.set(c);
                    }
                    continue;
                }
                double wd = 1 - Math.abs(d - cfg.targetDistance()) / cfg.targetDistance();
                if (wd < cfg.minObservationQuality()) {
                    continue;
                }
                cBuf[m] = c;
                wBuf[m] = (float) wd;
                double bearing = Math.atan2(dy, dx);
                bBuf[m] = (float) bearing;
                kBuf[m] = (float) Geometry.wrapAngle(bearing + Math.PI);
                m++;
            }
        }
        return new Obs(java.util.Arrays.copyOf(cBuf, m), java.util.Arrays.copyOf(wBuf, m), java.util.Arrays.copyOf(bBuf, m),
            java.util.Arrays.copyOf(kBuf, m), m);
    }

    /** Restricts an all-around observation to a camera pointing at yaw and applies the field-of-view falloff. */
    Obs facing(Obs omni, double yaw) {
        double half = cfg.fovRadians() / 2;
        int[] c = new int[omni.n];
        float[] w = new float[omni.n];
        float[] b = new float[omni.n];
        float[] k = new float[omni.n];
        int m = 0;
        for (int x = 0; x < omni.n; x++) {
            double off = Geometry.angleDiff(omni.bearing[x], yaw);
            if (off > half) {
                continue;
            }
            double q = omni.w[x] * (1 - 0.5 * (off / half) * (off / half));
            if (q < cfg.minObservationQuality()) {
                continue;
            }
            c[m] = omni.cell[x];
            w[m] = (float) q;
            b[m] = omni.bearing[x];
            k[m] = omni.back[x];
            m++;
        }
        return new Obs(java.util.Arrays.copyOf(c, m), java.util.Arrays.copyOf(w, m), java.util.Arrays.copyOf(b, m),
            java.util.Arrays.copyOf(k, m), m);
    }

    /** Discrete line of sight between two cell centres, excluding both end cells. */
    boolean clear(int x0, int y0, int x1, int y1) {
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
            if (stepX && stepY && g.blocked(x + sx, y) && g.blocked(x, y + sy)) {
                return false; // would slip between two blocked cells that touch at a corner
            }
            x = nx;
            y = ny;
            if (!(x == x1 && y == y1) && g.blocked(x, y)) {
                return false;
            }
        }
        return true;
    }
}
