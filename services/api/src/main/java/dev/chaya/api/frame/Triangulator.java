package dev.chaya.api.frame;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Locates one physical point in a reconstruction from the pixels where it was marked in several registered views
 * (docs/coordinate-frames.md, "Triangulated measurement points").
 *
 * <p>Conventions are COLMAP's, as POSE_ESTIMATION publishes them (poses.json): a view's pose maps world (reconstruction)
 * coordinates to camera coordinates, {@code X_c = R X + t}, with R from the unit quaternion (w, x, y, z); the camera looks
 * along +Z_c; pixel (0, 0) is the top-left corner of the top-left pixel. Camera models PINHOLE, SIMPLE_PINHOLE,
 * SIMPLE_RADIAL, RADIAL and OPENCV, with COLMAP's distortion equations.
 *
 * <p>Method: each marked pixel is undistorted to normalised camera coordinates (x, y) by fixed-point iteration of the
 * distortion model. Every view contributes the two linear equations {@code x P3 - P1} and {@code y P3 - P2} of
 * P = [R | t] (the direct linear transform, each row scaled to unit norm); the point is the eigenvector of the smallest
 * eigenvalue of {@code A^T A}, dehomogenised. Then every condition that makes such a point untrustworthy is checked:
 * fewer than two views, a point at infinity, a point behind a camera, rays that meet at too small an angle (depth is then
 * ill-conditioned), and a reprojection error above the limit, measured back through the full camera model in pixels.
 */
public final class Triangulator {

    private Triangulator() {}

    /** A camera as poses.json records it: model, image size and COLMAP's parameter vector. */
    public record Camera(String model, int width, int height, double[] params) {

        public Camera {
            model = model == null ? "" : model.toUpperCase(Locale.ROOT);
            int needed = switch (model) {
                case "SIMPLE_PINHOLE" -> 3;
                case "PINHOLE", "SIMPLE_RADIAL" -> 4;
                case "RADIAL" -> 5;
                case "OPENCV" -> 8;
                default -> throw new IllegalArgumentException("camera model " + model + " is not supported for triangulation "
                    + "(PINHOLE, SIMPLE_PINHOLE, SIMPLE_RADIAL, RADIAL, OPENCV)");
            };
            if (params == null || params.length != needed) {
                throw new IllegalArgumentException("camera model " + model + " needs " + needed + " parameters");
            }
            for (double p : params) {
                if (!Double.isFinite(p)) {
                    throw new IllegalArgumentException("camera parameters must be finite");
                }
            }
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("camera size must be positive");
            }
        }

        double fx() { return params[0]; }

        double fy() { return model.equals("PINHOLE") || model.equals("OPENCV") ? params[1] : params[0]; }

        double cx() { return model.equals("PINHOLE") || model.equals("OPENCV") ? params[2] : params[1]; }

        double cy() { return model.equals("PINHOLE") || model.equals("OPENCV") ? params[3] : params[2]; }

        /** COLMAP's distortion of normalised coordinates: returns (dx, dy) to add. */
        double[] distortion(double u, double v) {
            double k1 = 0, k2 = 0, p1 = 0, p2 = 0;
            switch (model) {
                case "SIMPLE_RADIAL" -> k1 = params[3];
                case "RADIAL" -> { k1 = params[3]; k2 = params[4]; }
                case "OPENCV" -> { k1 = params[4]; k2 = params[5]; p1 = params[6]; p2 = params[7]; }
                default -> { }
            }
            double r2 = u * u + v * v;
            double radial = k1 * r2 + k2 * r2 * r2;
            return new double[]{u * radial + 2 * p1 * u * v + p2 * (r2 + 2 * u * u),
                                v * radial + 2 * p2 * u * v + p1 * (r2 + 2 * v * v)};
        }

        /** Pixel -> undistorted normalised coordinates, or null when the iteration does not converge (far outside the model). */
        double[] undistort(double px, double py) {
            double xd = (px - cx()) / fx();
            double yd = (py - cy()) / fy();
            double x = xd;
            double y = yd;
            for (int i = 0; i < 200; i++) {
                double[] d = distortion(x, y);
                double nx = xd - d[0];
                double ny = yd - d[1];
                if (Math.abs(nx - x) < 1e-14 && Math.abs(ny - y) < 1e-14) {
                    x = nx;
                    y = ny;
                    break;
                }
                x = nx;
                y = ny;
            }
            double[] back = distortion(x, y);
            if (!Double.isFinite(x) || !Double.isFinite(y) || Math.abs(x + back[0] - xd) > 1e-9 || Math.abs(y + back[1] - yd) > 1e-9) {
                return null;
            }
            return new double[]{x, y};
        }

        /** Undistorted normalised coordinates -> pixel (the forward model). */
        double[] project(double x, double y) {
            double[] d = distortion(x, y);
            return new double[]{fx() * (x + d[0]) + cx(), fy() * (y + d[1]) + cy()};
        }
    }

    /** A registered view: its camera and world-to-camera pose. */
    public record View(String name, Camera camera, Quaternion rotation, double[] translation) {}

    /** One marked pixel in one view (in that view's frame pixels). */
    public record Observation(View view, double u, double v) {}

    /** Limits a triangulated point must meet. */
    public record Limits(int minViews, double minAngleDeg, double maxRmsReprojectionPx, double maxReprojectionPx) {}

    /**
     * The result: {@code point} is null when the point was refused, and {@code failure} then says why (a stable code and a
     * sentence). The diagnostics are filled in as far as they could be computed.
     */
    public record Result(double[] point, String failure, String message, int views, Double maxAngleDeg, Double rmsReprojectionPx,
                         Double maxReprojectionPx, List<String> viewNames) {

        public boolean ok() {
            return point != null;
        }
    }

    public static Result triangulate(List<Observation> observations, Limits limits) {
        List<String> names = observations.stream().map(o -> o.view().name()).toList();
        if (observations.size() < limits.minViews()) {
            return refused("INSUFFICIENT_VIEWS", "the point is marked in " + observations.size() + " registered view(s); at least "
                + limits.minViews() + " are needed to locate it", observations.size(), null, null, null, names);
        }
        double[][] ata = new double[4][4];
        List<double[]> normalised = new ArrayList<>();
        for (Observation o : observations) {
            if (!Double.isFinite(o.u()) || !Double.isFinite(o.v())) {
                return refused("NON_FINITE_OBSERVATION", "a marked pixel is not finite", observations.size(), null, null, null, names);
            }
            double[] xy = o.view().camera().undistort(o.u(), o.v());
            if (xy == null) {
                return refused("UNDISTORTION_FAILED", "pixel (" + o.u() + ", " + o.v() + ") in view " + o.view().name()
                    + " lies outside the range where its camera's distortion model can be inverted", observations.size(), null, null,
                    null, names);
            }
            normalised.add(xy);
            double[][] r = o.view().rotation().toMatrix();
            double[] t = o.view().translation();
            double[] p1 = {r[0][0], r[0][1], r[0][2], t[0]};
            double[] p2 = {r[1][0], r[1][1], r[1][2], t[1]};
            double[] p3 = {r[2][0], r[2][1], r[2][2], t[2]};
            for (double[] row : new double[][]{sub(scale(p3, xy[0]), p1), sub(scale(p3, xy[1]), p2)}) {
                double n = Math.sqrt(dot(row, row));
                for (int i = 0; i < 4; i++) {
                    for (int j = 0; j < 4; j++) {
                        ata[i][j] += row[i] * row[j] / (n * n);
                    }
                }
            }
        }
        SymmetricEigen eig = SymmetricEigen.of(ata);
        double[] h = eig.vectors()[3]; // the eigenvector of the smallest eigenvalue (values sorted descending)
        double norm = Math.sqrt(dot(h, h));
        if (!(Math.abs(h[3]) > 1e-12 * norm)) {
            return refused("POINT_AT_INFINITY", "the rays through the marked pixels are parallel: the point is at infinity in this "
                + "reconstruction, so it cannot be located", observations.size(), null, null, null, names);
        }
        double[] x = {h[0] / h[3], h[1] / h[3], h[2] / h[3]};
        if (!Double.isFinite(x[0]) || !Double.isFinite(x[1]) || !Double.isFinite(x[2])) {
            return refused("NON_FINITE_POINT", "the triangulated point is not finite", observations.size(), null, null, null, names);
        }

        double sumSq = 0;
        double maxErr = 0;
        List<double[]> centres = new ArrayList<>();
        for (int k = 0; k < observations.size(); k++) {
            Observation o = observations.get(k);
            double[][] r = o.view().rotation().toMatrix();
            double[] t = o.view().translation();
            double[] xc = add(mul(r, x), t);
            if (!(xc[2] > 0)) {
                return refused("POINT_BEHIND_CAMERA", "the triangulated point is behind the camera of view " + o.view().name()
                    + ": the marks do not show the same physical point", observations.size(), null, null, null, names);
            }
            double[] px = o.view().camera().project(xc[0] / xc[2], xc[1] / xc[2]);
            double err = Math.hypot(px[0] - o.u(), px[1] - o.v());
            sumSq += err * err;
            maxErr = Math.max(maxErr, err);
            centres.add(centre(r, t));
        }
        double maxAngle = 0;
        for (int i = 0; i < centres.size(); i++) {
            for (int j = i + 1; j < centres.size(); j++) {
                maxAngle = Math.max(maxAngle, angleDeg(sub3(x, centres.get(i)), sub3(x, centres.get(j))));
            }
        }
        double rms = Math.sqrt(sumSq / observations.size());
        if (maxAngle < limits.minAngleDeg()) {
            return refused("TRIANGULATION_ANGLE_TOO_SMALL", "the views' rays meet at " + round(maxAngle) + " degrees at most (minimum "
                + limits.minAngleDeg() + "): its depth is ill-conditioned; mark it in views taken farther apart", observations.size(),
                maxAngle, rms, maxErr, names);
        }
        if (rms > limits.maxRmsReprojectionPx() || maxErr > limits.maxReprojectionPx()) {
            return refused("REPROJECTION_ERROR_TOO_HIGH", "the located point reprojects " + round(rms) + " px RMS, " + round(maxErr)
                + " px at worst, from the marks (limits " + limits.maxRmsReprojectionPx() + " and " + limits.maxReprojectionPx()
                + " px): the marks do not show one physical point, or a pose is wrong", observations.size(), maxAngle, rms, maxErr, names);
        }
        return new Result(x, null, null, observations.size(), maxAngle, rms, maxErr, names);
    }

    private static Result refused(String code, String message, int views, Double angle, Double rms, Double max, List<String> names) {
        return new Result(null, code, message, views, angle, rms, max, names);
    }

    /** The camera centre in world coordinates: C = -R^T t. */
    static double[] centre(double[][] r, double[] t) {
        return new double[]{-(r[0][0] * t[0] + r[1][0] * t[1] + r[2][0] * t[2]),
                            -(r[0][1] * t[0] + r[1][1] * t[1] + r[2][1] * t[2]),
                            -(r[0][2] * t[0] + r[1][2] * t[1] + r[2][2] * t[2])};
    }

    private static double angleDeg(double[] a, double[] b) {
        double c = dot3(a, b) / Math.sqrt(dot3(a, a) * dot3(b, b));
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, c))));
    }

    private static double[] mul(double[][] r, double[] x) {
        return new double[]{r[0][0] * x[0] + r[0][1] * x[1] + r[0][2] * x[2], r[1][0] * x[0] + r[1][1] * x[1] + r[1][2] * x[2],
                            r[2][0] * x[0] + r[2][1] * x[1] + r[2][2] * x[2]};
    }

    private static double[] add(double[] a, double[] b) {
        return new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]};
    }

    private static double[] sub3(double[] a, double[] b) {
        return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static double dot3(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double[] scale(double[] a, double s) {
        return new double[]{a[0] * s, a[1] * s, a[2] * s, a[3] * s};
    }

    private static double[] sub(double[] a, double[] b) {
        return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2], a[3] - b[3]};
    }

    private static double dot(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            s += a[i] * b[i];
        }
        return s;
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
