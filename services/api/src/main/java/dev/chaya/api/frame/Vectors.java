package dev.chaya.api.frame;

import java.util.List;

/** Small 3-vector helpers for the frame math. Package-private by intent: the public surface is Similarity. */
final class Vectors {

    private Vectors() {}

    static double[] of(List<Double> v, String what) {
        if (v == null || v.size() != 3 || v.stream().anyMatch(d -> d == null || !Double.isFinite(d))) {
            throw new IllegalArgumentException(what + " must be three finite numbers");
        }
        return new double[]{v.get(0), v.get(1), v.get(2)};
    }

    static double[] sub(double[] a, double[] b) {
        return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    static double[] add(double[] a, double[] b) {
        return new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]};
    }

    static double[] scale(double[] a, double s) {
        return new double[]{a[0] * s, a[1] * s, a[2] * s};
    }

    static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    static double norm(double[] a) {
        return Math.sqrt(dot(a, a));
    }

    static double[] normalize(double[] a) {
        double n = norm(a);
        if (!(n > 1e-12) || !Double.isFinite(n)) {
            throw new IllegalArgumentException("cannot normalise a zero or non-finite vector");
        }
        return scale(a, 1.0 / n);
    }

    static double distance(double[] a, double[] b) {
        return norm(sub(a, b));
    }

    static double angleDegrees(double[] a, double[] b) {
        double c = dot(normalize(a), normalize(b));
        return Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, c))));
    }

    static double[] centroid(List<double[]> points) {
        double[] c = new double[3];
        for (double[] p : points) {
            c = add(c, p);
        }
        return scale(c, 1.0 / points.size());
    }
}
