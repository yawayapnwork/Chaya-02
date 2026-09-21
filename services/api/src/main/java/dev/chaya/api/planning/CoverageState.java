package dev.chaya.api.planning;

import dev.chaya.api.planning.geometry.Geometry;
import java.util.BitSet;

/**
 * The coverage model (see docs/route-planning.md).
 *
 * <p>Per target cell c the state keeps A(c), the accumulated observation quality, and gamma(c), the widest angle
 * between any two directions the cell has been seen from. The cell coverage score is
 *
 * <pre>f(c) = min(1, A / A0) * min(1, gamma / gamma0)</pre>
 *
 * A cell is covered when f(c) = 1: it has been observed well enough, and from far enough apart, to triangulate.
 * f is monotone: adding an observation never lowers it. Up to MAX_BEARINGS view directions are remembered per
 * cell, so for cells seen more often gamma is a lower bound.
 */
final class CoverageState {

    private static final int MAX_BEARINGS = 6;
    private static final double EPS = 1e-5; // float storage: comparisons must tolerate float rounding

    /** Marginal effect of adding a set of observations, computed without changing the state. */
    record Gain(double weighted, double coveredAreaM2, double doorway, double corner, double boundary, double plain, double shadow,
                double redundancy, double newlyObservedShare) {}

    /** Coverage figures for the current state. */
    record Raw(double targetAreaM2, double coveredAreaM2, double weightedFraction, double redundantFraction) {}

    private final Grid g;
    private final double a0;
    private final double gamma0;
    private final float[] a;
    private final float[] gamma;
    private final float[] bearings;
    private final byte[] count;

    CoverageState(Grid g, PlannerConfig cfg) {
        this.g = g;
        this.a0 = cfg.requiredObservationMass();
        this.gamma0 = cfg.minTriangulationRadians();
        this.a = new float[g.n];
        this.gamma = new float[g.n];
        this.bearings = new float[g.n * MAX_BEARINGS];
        this.count = new byte[g.n];
    }

    private CoverageState(CoverageState o) {
        this.g = o.g;
        this.a0 = o.a0;
        this.gamma0 = o.gamma0;
        this.a = o.a.clone();
        this.gamma = o.gamma.clone();
        this.bearings = o.bearings.clone();
        this.count = o.count.clone();
    }

    CoverageState copy() {
        return new CoverageState(this);
    }

    private double f(double obsMass, double angle) {
        double m = Math.min(1, obsMass / a0);
        double t = gamma0 <= 0 ? 1 : Math.min(1, angle / gamma0);
        return m * t;
    }

    boolean covered(int c) {
        return g.target[c] && a[c] >= a0 - EPS && (gamma0 <= 0 || gamma[c] >= gamma0 - EPS);
    }

    double observationMass(int c) {
        return a[c];
    }

    /** Credits a cell as already observed by an external source (an ObservedRegion). */
    void credit(int c, double quality) {
        if (!g.target[c] || quality <= 0) {
            return;
        }
        a[c] += (float) (quality * a0);
        gamma[c] = Math.max(gamma[c], (float) gamma0);
    }

    private double widest(int c, float back, double current) {
        double best = current;
        int base = c * MAX_BEARINGS;
        for (int k = 0; k < count[c]; k++) {
            best = Math.max(best, Geometry.angleDiff(back, bearings[base + k]));
        }
        return best;
    }

    /** What adding these observations would change. shadow marks cells hidden from the recon lap (occlusion zones). */
    Gain gain(Visibility.Obs o, BitSet shadow) {
        double weighted = 0, covered = 0, doorway = 0, corner = 0, boundary = 0, plain = 0, shadowGain = 0;
        double saturatedMass = 0, totalMass = 0;
        int fresh = 0, positive = 0;
        for (int k = 0; k < o.n; k++) {
            int c = o.cell[k];
            double f0 = f(a[c], gamma[c]);
            double f1 = f(a[c] + o.w[k], widest(c, o.back[k], gamma[c]));
            totalMass += o.w[k];
            if (f0 >= 1 - EPS) {
                saturatedMass += o.w[k];
            }
            double dg = (f1 - f0) * g.importance[c] * g.cellArea;
            if (dg <= 0) {
                continue;
            }
            positive++;
            if (a[c] == 0) {
                fresh++;
            }
            weighted += dg;
            if (f1 >= 1 - EPS && f0 < 1 - EPS) {
                covered += g.cellArea;
            }
            int cls = g.cls[c];
            if ((cls & Grid.DOORWAY) != 0) doorway += dg;
            else if ((cls & Grid.CORNER) != 0) corner += dg;
            else if ((cls & Grid.BOUNDARY) != 0) boundary += dg;
            else plain += dg;
            if (shadow != null && shadow.get(c)) {
                shadowGain += dg;
            }
        }
        return new Gain(weighted, covered, doorway, corner, boundary, plain, shadowGain,
            totalMass == 0 ? 0 : saturatedMass / totalMass, positive == 0 ? 0 : (double) fresh / positive);
    }

    void add(Visibility.Obs o) {
        for (int k = 0; k < o.n; k++) {
            int c = o.cell[k];
            gamma[c] = (float) widest(c, o.back[k], gamma[c]);
            if (count[c] < MAX_BEARINGS) {
                bearings[c * MAX_BEARINGS + count[c]] = o.back[k];
                count[c]++;
            }
            a[c] += o.w[k];
        }
    }

    Raw raw() {
        double target = 0, covered = 0, wNum = 0, wDen = 0, mass = 0, needed = 0;
        for (int c = 0; c < g.n; c++) {
            if (!g.target[c]) {
                continue;
            }
            target += g.cellArea;
            if (covered(c)) {
                covered += g.cellArea;
            }
            wNum += g.importance[c] * f(a[c], gamma[c]);
            wDen += g.importance[c];
            mass += a[c];
            needed += Math.min(a[c], a0);
        }
        return new Raw(target, covered, wDen == 0 ? 0 : wNum / wDen, mass == 0 ? 0 : (mass - needed) / mass);
    }
}
