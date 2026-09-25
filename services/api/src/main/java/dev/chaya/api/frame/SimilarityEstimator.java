package dev.chaya.api.frame;

import java.util.ArrayList;
import java.util.List;

/**
 * The calibration math: every way the control plane turns measured references into a similarity transform. Pure
 * functions, no I/O; the numbers they produce are stored with their residuals, never rounded or clamped.
 */
public final class SimilarityEstimator {

    private SimilarityEstimator() {}

    /** A similarity fitted to point correspondences, with each correspondence's residual (output units). */
    public record Fit(Similarity transform, double rmsResidual, double[] residuals) {}

    /** Scale fitted to measured distances: the least-squares scale, and each reference's own implied scale. */
    public record ScaleFit(double scale, double relativeSpread, double[] perReferenceScale) {}

    /** A plane through reconstruction points with an oriented unit normal, and the points' RMS distance from it. */
    public record FittedPlane(double[] normal, double[] point, double rmsDistance) {}

    /**
     * Least-squares similarity dst ~ s R src + t from at least three non-collinear correspondences: Horn's closed-form
     * absolute orientation (the rotation is the dominant eigenvector of his 4x4 matrix, so it is always a proper
     * rotation -- never a reflection), then Umeyama's scale given that rotation, then the translation between centroids.
     */
    public static Fit fromCorrespondences(List<double[]> src, List<double[]> dst) {
        if (src.size() != dst.size() || src.size() < 3) {
            throw new IllegalArgumentException("at least three correspondences are needed");
        }
        double[] cs = Vectors.centroid(src);
        double[] cd = Vectors.centroid(dst);
        List<double[]> a = new ArrayList<>();
        List<double[]> b = new ArrayList<>();
        for (int i = 0; i < src.size(); i++) {
            a.add(Vectors.sub(src.get(i), cs));
            b.add(Vectors.sub(dst.get(i), cd));
        }
        requireNonCollinear(a, "source points");
        requireNonCollinear(b, "destination points");

        double[][] m = new double[3][3];
        double srcSpread = 0;
        for (int i = 0; i < a.size(); i++) {
            for (int r = 0; r < 3; r++) {
                for (int c = 0; c < 3; c++) {
                    m[r][c] += a.get(i)[r] * b.get(i)[c];
                }
            }
            srcSpread += Vectors.dot(a.get(i), a.get(i));
        }
        double sxx = m[0][0], sxy = m[0][1], sxz = m[0][2];
        double syx = m[1][0], syy = m[1][1], syz = m[1][2];
        double szx = m[2][0], szy = m[2][1], szz = m[2][2];
        double[][] n = {
            {sxx + syy + szz, syz - szy, szx - sxz, sxy - syx},
            {syz - szy, sxx - syy - szz, sxy + syx, szx + sxz},
            {szx - sxz, sxy + syx, -sxx + syy - szz, syz + szy},
            {sxy - syx, szx + sxz, syz + szy, -sxx - syy + szz}};
        double[] q = SymmetricEigen.of(n).vectors()[0];
        Quaternion rotation = new Quaternion(q[0], q[1], q[2], q[3]);

        double numerator = 0;
        for (int i = 0; i < a.size(); i++) {
            numerator += Vectors.dot(b.get(i), rotation.rotate(a.get(i)));
        }
        double scale = numerator / srcSpread;
        if (!(scale > 0)) {
            throw new IllegalArgumentException("correspondences do not determine a positive scale");
        }
        double[] rc = rotation.rotate(cs);
        Similarity t = new Similarity(scale, rotation, Vectors.sub(cd, Vectors.scale(rc, scale)));

        double[] residuals = new double[src.size()];
        double sq = 0;
        for (int i = 0; i < src.size(); i++) {
            residuals[i] = Vectors.distance(t.apply(src.get(i)), dst.get(i));
            sq += residuals[i] * residuals[i];
        }
        return new Fit(t, Math.sqrt(sq / src.size()), residuals);
    }

    /**
     * The scale s minimising sum (s * l_i - m_i)^2 over reference lengths l_i (reconstruction units) and their
     * measured lengths m_i (metres), and the largest relative deviation of any single reference's m_i / l_i from it.
     */
    public static ScaleFit scaleFromDistances(List<double[]> reconstructionLengthsAndMeasured) {
        double num = 0;
        double den = 0;
        double[] per = new double[reconstructionLengthsAndMeasured.size()];
        for (int i = 0; i < per.length; i++) {
            double l = reconstructionLengthsAndMeasured.get(i)[0];
            double m = reconstructionLengthsAndMeasured.get(i)[1];
            if (!(l > 1e-12) || !(m > 0) || !Double.isFinite(l) || !Double.isFinite(m)) {
                throw new IllegalArgumentException("every reference needs two distinct reconstruction points and a positive measured length");
            }
            num += l * m;
            den += l * l;
            per[i] = m / l;
        }
        double scale = num / den;
        double spread = 0;
        for (double s : per) {
            spread = Math.max(spread, Math.abs(s - scale) / scale);
        }
        return new ScaleFit(scale, spread, per);
    }

    /**
     * The rotation taking the reconstruction's up direction onto canonical +Z, with the FLOOR_LOCAL heading convention
     * (canonical +X along the horizontal projection of reconstruction +X, or of +Y when +X is near vertical).
     */
    public static Quaternion levelling(double[] upReconstruction) {
        double[] up = Vectors.normalize(upReconstruction);
        double[] ref = {1, 0, 0};
        double fromVertical = Vectors.angleDegrees(ref, up);
        if (fromVertical < CanonicalFrame.HEADING_AXIS_MIN_ANGLE_FROM_VERTICAL_DEG
                || fromVertical > 180 - CanonicalFrame.HEADING_AXIS_MIN_ANGLE_FROM_VERTICAL_DEG) {
            ref = new double[]{0, 1, 0};
        }
        double[] xAxis = Vectors.normalize(Vectors.sub(ref, Vectors.scale(up, Vectors.dot(ref, up))));
        double[] yAxis = Vectors.cross(up, xAxis);
        // Rows are the canonical axes expressed in reconstruction coordinates: canonical = R * reconstruction.
        return Quaternion.fromMatrix(new double[][]{xAxis, yAxis, up});
    }

    /** Least-squares plane through at least three non-collinear points, its normal oriented towards `above`. */
    public static FittedPlane plane(List<double[]> points, double[] above) {
        if (points.size() < 3) {
            throw new IllegalArgumentException("a plane needs at least three points");
        }
        double[] c = Vectors.centroid(points);
        List<double[]> centred = points.stream().map(p -> Vectors.sub(p, c)).toList();
        requireNonCollinear(centred, "floor points");
        double[][] cov = new double[3][3];
        for (double[] p : centred) {
            for (int r = 0; r < 3; r++) {
                for (int k = 0; k < 3; k++) {
                    cov[r][k] += p[r] * p[k];
                }
            }
        }
        double[] normal = Vectors.normalize(SymmetricEigen.of(cov).vectors()[2]);
        double side = Vectors.dot(Vectors.sub(above, c), normal);
        if (Math.abs(side) < 1e-12) {
            throw new IllegalArgumentException("the point above the floor lies on the floor plane");
        }
        if (side < 0) {
            normal = Vectors.scale(normal, -1);
        }
        double sq = 0;
        for (double[] p : centred) {
            double d = Vectors.dot(p, normal);
            sq += d * d;
        }
        return new FittedPlane(normal, c, Math.sqrt(sq / points.size()));
    }

    /**
     * The FLOOR_LOCAL canonical frame: scale s, levelling rotation from `up`, and the translation putting `origin` (a
     * reconstruction point on the floor plane) at canonical (0, 0, 0).
     */
    public static Similarity floorLocal(double scale, double[] upReconstruction, double[] originOnFloor) {
        Quaternion r = levelling(upReconstruction);
        double[] ro = r.rotate(originOnFloor);
        return new Similarity(scale, r, new double[]{-scale * ro[0], -scale * ro[1], -scale * ro[2]});
    }

    /** Projects `p` along `up` onto the plane through `planePoint` perpendicular to `up`. */
    public static double[] projectOntoPlane(double[] p, double[] planePoint, double[] up) {
        double[] u = Vectors.normalize(up);
        return Vectors.sub(p, Vectors.scale(u, Vectors.dot(Vectors.sub(p, planePoint), u)));
    }

    private static void requireNonCollinear(List<double[]> centred, String what) {
        double[][] cov = new double[3][3];
        for (double[] p : centred) {
            for (int r = 0; r < 3; r++) {
                for (int k = 0; k < 3; k++) {
                    cov[r][k] += p[r] * p[k];
                }
            }
        }
        double[] ev = SymmetricEigen.of(cov).values();
        if (!(ev[0] > 1e-18) || ev[1] < 1e-9 * ev[0]) {
            throw new IllegalArgumentException(what + " are coincident or collinear; they do not determine a frame");
        }
    }
}
