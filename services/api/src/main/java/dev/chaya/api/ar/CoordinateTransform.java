package dev.chaya.api.ar;

import dev.chaya.api.ar.ArDtos.Pose;
import java.util.List;

/**
 * Rigid-transform math for AR relocalization. Pure, side-effect-free, and shared in spirit (not in code --
 * there is no cross-language build here) with the equivalent client-side implementations in
 * apps/web/lib/ar-anchor-math.ts (WebXR/Android) and the iOS ChayaARCore Swift package: all three solve the
 * same problem from the same inputs, so the unit tests in each language are the contract, not this class.
 *
 * <p>The transform from a single anchor comes from composing two known poses: the anchor's fixed pose in
 * the venue's reconstruction frame ({@code digitalPose}, set once at venue calibration) and the same
 * anchor's pose as the device's live tracking session currently observes it ({@code observedPose}, real
 * sensor/vision output from the client). Composing {@code digitalPose} with the inverse of
 * {@code observedPose} gives the device-frame -> venue-frame transform.
 *
 * <p>With several anchors visible at once, each gives its own candidate transform; disagreement between
 * them (their translations do not exactly coincide, because tracking and marker detection both carry real
 * error) is reported as a measured residual, never hidden behind an averaged number presented as exact.
 * Never claim centimeter accuracy here -- report the measured residual and let the caller decide what to
 * trust.
 */
public final class CoordinateTransform {

    private CoordinateTransform() {}

    /** deviceToVenue = digitalPose (this anchor's known venue-frame pose) composed with the inverse of
     * observedPose (this anchor's pose as the device's own tracking frame currently reports it). */
    public static Pose deviceToVenueFromAnchor(Pose digitalPose, Pose observedPose) {
        Pose observedInverse = invert(observedPose);
        return compose(digitalPose, observedInverse);
    }

    /**
     * Blends the per-anchor candidate transforms into one, and reports the residual: the largest distance
     * between any two candidates' translation component, in meters. That number is real disagreement
     * between the anchors that took part, computed from their actual poses -- it is not a fabricated or
     * assumed accuracy bound.
     */
    public record Blended(Pose transform, double residualMeters) {}

    public static Blended blend(List<Pose> candidates) {
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("at least one anchor observation is required");
        }
        if (candidates.size() == 1) {
            return new Blended(normalize(candidates.get(0)), 0.0);
        }
        double residual = 0.0;
        for (int i = 0; i < candidates.size(); i++) {
            for (int j = i + 1; j < candidates.size(); j++) {
                residual = Math.max(residual, translationDistance(candidates.get(i), candidates.get(j)));
            }
        }
        double sx = 0, sy = 0, sz = 0, sqx = 0, sqy = 0, sqz = 0, sqw = 0;
        Pose reference = normalize(candidates.get(0));
        for (Pose c : candidates) {
            Pose n = normalize(c);
            // Average unit quaternions in a small-angle-disagreement setting: flip to the same hemisphere as
            // the reference first (q and -q represent the same rotation), then average and renormalize.
            double dot = n.qx() * reference.qx() + n.qy() * reference.qy() + n.qz() * reference.qz() + n.qw() * reference.qw();
            double sign = dot < 0 ? -1.0 : 1.0;
            sx += n.x();
            sy += n.y();
            sz += n.z();
            sqx += sign * n.qx();
            sqy += sign * n.qy();
            sqz += sign * n.qz();
            sqw += sign * n.qw();
        }
        int count = candidates.size();
        Pose averaged = new Pose(sx / count, sy / count, sz / count, sqx / count, sqy / count, sqz / count, sqw / count);
        return new Blended(normalize(averaged), residual);
    }

    /** Interpolates between two anchor-derived transforms by a real-space proximity weight `t` in [0, 1]
     * (0 = fully `a`, 1 = fully `b`) -- linear translation blend and normalized-linear (nlerp) quaternion
     * blend, adequate for the small rotational differences between nearby anchors on the same floor. */
    public static Pose interpolate(Pose a, Pose b, double t) {
        double clamped = Math.max(0.0, Math.min(1.0, t));
        Pose na = normalize(a);
        Pose nb = normalize(b);
        double dot = na.qx() * nb.qx() + na.qy() * nb.qy() + na.qz() * nb.qz() + na.qw() * nb.qw();
        double sign = dot < 0 ? -1.0 : 1.0;
        double x = lerp(na.x(), nb.x(), clamped);
        double y = lerp(na.y(), nb.y(), clamped);
        double z = lerp(na.z(), nb.z(), clamped);
        double qx = lerp(na.qx(), sign * nb.qx(), clamped);
        double qy = lerp(na.qy(), sign * nb.qy(), clamped);
        double qz = lerp(na.qz(), sign * nb.qz(), clamped);
        double qw = lerp(na.qw(), sign * nb.qw(), clamped);
        return normalize(new Pose(x, y, z, qx, qy, qz, qw));
    }

    // ---- quaternion/pose primitives -------------------------------------------------------------------

    static Pose compose(Pose outer, Pose inner) {
        double[] rotated = rotate(outer, inner.x(), inner.y(), inner.z());
        double[] q = multiply(outer.qx(), outer.qy(), outer.qz(), outer.qw(), inner.qx(), inner.qy(), inner.qz(), inner.qw());
        return new Pose(outer.x() + rotated[0], outer.y() + rotated[1], outer.z() + rotated[2], q[0], q[1], q[2], q[3]);
    }

    static Pose invert(Pose p) {
        Pose n = normalize(p);
        // Unit-quaternion inverse is its conjugate.
        double iqx = -n.qx();
        double iqy = -n.qy();
        double iqz = -n.qz();
        double iqw = n.qw();
        Pose rotationOnly = new Pose(0, 0, 0, iqx, iqy, iqz, iqw);
        double[] rotatedNegatedTranslation = rotate(rotationOnly, -n.x(), -n.y(), -n.z());
        return new Pose(rotatedNegatedTranslation[0], rotatedNegatedTranslation[1], rotatedNegatedTranslation[2], iqx, iqy, iqz, iqw);
    }

    static double[] rotate(Pose byQuaternion, double x, double y, double z) {
        double qx = byQuaternion.qx();
        double qy = byQuaternion.qy();
        double qz = byQuaternion.qz();
        double qw = byQuaternion.qw();
        // v' = q * v * q^-1, expanded (v as a pure quaternion).
        double[] qv = multiply(qx, qy, qz, qw, x, y, z, 0);
        double[] result = multiply(qv[0], qv[1], qv[2], qv[3], -qx, -qy, -qz, qw);
        return new double[]{result[0], result[1], result[2]};
    }

    private static double[] multiply(double ax, double ay, double az, double aw, double bx, double by, double bz, double bw) {
        return new double[]{
            aw * bx + ax * bw + ay * bz - az * by,
            aw * by - ax * bz + ay * bw + az * bx,
            aw * bz + ax * by - ay * bx + az * bw,
            aw * bw - ax * bx - ay * by - az * bz,
        };
    }

    private static Pose normalize(Pose p) {
        double norm = Math.sqrt(p.qx() * p.qx() + p.qy() * p.qy() + p.qz() * p.qz() + p.qw() * p.qw());
        if (norm == 0.0) {
            return new Pose(p.x(), p.y(), p.z(), 0, 0, 0, 1);
        }
        return new Pose(p.x(), p.y(), p.z(), p.qx() / norm, p.qy() / norm, p.qz() / norm, p.qw() / norm);
    }

    private static double translationDistance(Pose a, Pose b) {
        return Math.sqrt(Math.pow(a.x() - b.x(), 2) + Math.pow(a.y() - b.y(), 2) + Math.pow(a.z() - b.z(), 2));
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
}
