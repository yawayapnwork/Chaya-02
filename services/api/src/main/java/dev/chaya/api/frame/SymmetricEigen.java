package dev.chaya.api.frame;

/**
 * Eigen-decomposition of a small real symmetric matrix by cyclic Jacobi rotations -- robust and exact enough for the
 * 3x3 and 4x4 matrices the frame calibration needs, without pulling in a linear-algebra library.
 *
 * @param values  eigenvalues, sorted descending
 * @param vectors vectors[k] is the unit eigenvector of values[k]
 */
record SymmetricEigen(double[] values, double[][] vectors) {

    static SymmetricEigen of(double[][] matrix) {
        int n = matrix.length;
        double[][] a = new double[n][n];
        double[][] v = new double[n][n];
        for (int i = 0; i < n; i++) {
            if (matrix[i].length != n) {
                throw new IllegalArgumentException("matrix must be square");
            }
            for (int j = 0; j < n; j++) {
                if (Math.abs(matrix[i][j] - matrix[j][i]) > 1e-9 * (1 + Math.abs(matrix[i][j]))) {
                    throw new IllegalArgumentException("matrix must be symmetric");
                }
                a[i][j] = matrix[i][j];
            }
            v[i][i] = 1;
        }
        for (int sweep = 0; sweep < 100; sweep++) {
            double off = 0;
            for (int p = 0; p < n; p++) {
                for (int q = p + 1; q < n; q++) {
                    off += a[p][q] * a[p][q];
                }
            }
            if (off < 1e-30) {
                break;
            }
            for (int p = 0; p < n; p++) {
                for (int q = p + 1; q < n; q++) {
                    if (Math.abs(a[p][q]) < 1e-300) {
                        continue;
                    }
                    double theta = (a[q][q] - a[p][p]) / (2 * a[p][q]);
                    double t = Math.signum(theta) / (Math.abs(theta) + Math.sqrt(theta * theta + 1));
                    if (theta == 0) {
                        t = 1;
                    }
                    double c = 1 / Math.sqrt(t * t + 1);
                    double s = t * c;
                    for (int k = 0; k < n; k++) {
                        double akp = a[k][p];
                        double akq = a[k][q];
                        a[k][p] = c * akp - s * akq;
                        a[k][q] = s * akp + c * akq;
                    }
                    for (int k = 0; k < n; k++) {
                        double apk = a[p][k];
                        double aqk = a[q][k];
                        a[p][k] = c * apk - s * aqk;
                        a[q][k] = s * apk + c * aqk;
                    }
                    for (int k = 0; k < n; k++) {
                        double vkp = v[k][p];
                        double vkq = v[k][q];
                        v[k][p] = c * vkp - s * vkq;
                        v[k][q] = s * vkp + c * vkq;
                    }
                }
            }
        }
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (i, j) -> Double.compare(a[j][j], a[i][i]));
        double[] values = new double[n];
        double[][] vectors = new double[n][n];
        for (int k = 0; k < n; k++) {
            values[k] = a[order[k]][order[k]];
            for (int i = 0; i < n; i++) {
                vectors[k][i] = v[i][order[k]];
            }
        }
        return new SymmetricEigen(values, vectors);
    }
}
