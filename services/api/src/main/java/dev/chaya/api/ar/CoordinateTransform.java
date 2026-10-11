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
 * <p>With several anchors visible at once ({@link #solve}), each anchor's observation is mapped through the solved
 * transform and compared with its registered pose; that per-anchor disagreement is reported as a measured residual,
 * never hidden behind an averaged number presented as exact, and an anchor that disagrees beyond the limits is set
 * aside or the solve refused. Never claim centimeter accuracy here -- report the measured residual and let the caller
 * decide what to trust.
 */
public final class CoordinateTransform {

    private CoordinateTransform() {}

    /** deviceToVenue = digitalPose (this anchor's known venue-frame pose) composed with the inverse of
     * observedPose (this anchor's pose as the device's own tracking frame currently reports it). */
    public static Pose deviceToVenueFromAnchor(Pose digitalPose, Pose observedPose) {
        Pose observedInverse = invert(observedPose);
        return compose(digitalPose, observedInverse);
    }

    /** One anchor's contribution: its registered canonical pose and its pose as the device observed it. */
    public record AnchorPair(Pose digitalPose, Pose observedPose) {}

    /**
     * How far one anchor lands from its registered pose when its observation is mapped through the solved transform.
     * Measured, never assumed. {@code used} is false for an anchor rejected as inconsistent with the others.
     */
    public record AnchorResidual(int index, double translationMeters, double rotationDegrees, boolean used) {}

    /**
     * @param transform        the device-frame -> venue-frame transform solved from the anchors marked used
     * @param residuals        one per input anchor, in input order
     * @param maxResidualMeters largest translation residual among the used anchors (0 with a single anchor, where there
     *                         is nothing to check it against)
     * @param consistent       false when the used anchors still disagree beyond the limits (only possible with two
     *                         anchors left: nothing tells which of them is wrong)
     */
    public record Solution(Pose transform, List<AnchorResidual> residuals, double maxResidualMeters, double maxResidualDegrees,
                           boolean consistent) {

        public List<Integer> rejected() {
            return residuals.stream().filter(r -> !r.used()).map(AnchorResidual::index).toList();
        }
    }

    /**
     * Solves the device -> venue transform from one or more anchors seen at once.
     *
     * <p>Rotation: the per-anchor candidate rotations ({@code digital * observed^-1}), hemisphere-aligned and averaged.
     * Translation: the least-squares one for that rotation, which maps the centroid of the observed marker positions onto
     * the centroid of their registered positions, {@code t = mean(d) - R mean(o)}.
     *
     * <p>Residual of an anchor: its observation mapped through the solved transform, compared with its registered pose
     * (translation in metres, rotation in degrees). Unlike the distance between candidate transforms' translations (the
     * residual reported before), it does not depend on where the device's tracking session started: a candidate's
     * translation carries its rotation error multiplied by the distance to the session origin. With three or more anchors, while the worst one exceeds the limits it
     * is set aside (moved, misidentified or mis-registered) and the rest re-solved, as long as two remain. Two anchors that
     * disagree beyond the limits cannot be told apart: the solution is returned as inconsistent and the caller refuses it.
     */
    public static Solution solve(List<AnchorPair> anchors, double maxResidualMeters, double maxResidualDegrees) {
        if (anchors.isEmpty()) {
            throw new IllegalArgumentException("at least one anchor observation is required");
        }
        boolean[] used = new boolean[anchors.size()];
        java.util.Arrays.fill(used, true);
        while (true) {
            Pose t = fit(anchors, used);
            double[][] err = residuals(anchors, t);
            int worst = -1;
            double worstScore = 0;
            int usedCount = 0;
            double maxM = 0, maxDeg = 0;
            for (int i = 0; i < anchors.size(); i++) {
                if (!used[i]) {
                    continue;
                }
                usedCount++;
                maxM = Math.max(maxM, err[i][0]);
                maxDeg = Math.max(maxDeg, err[i][1]);
                double score = Math.max(err[i][0] / maxResidualMeters, err[i][1] / maxResidualDegrees);
                if (score > worstScore) {
                    worstScore = score;
                    worst = i;
                }
            }
            boolean withinLimits = worstScore <= 1.0;
            if (withinLimits || usedCount <= 2) {
                List<AnchorResidual> out = new java.util.ArrayList<>();
                for (int i = 0; i < anchors.size(); i++) {
                    out.add(new AnchorResidual(i, err[i][0], err[i][1], used[i]));
                }
                return new Solution(t, List.copyOf(out), maxM, maxDeg, withinLimits);
            }
            used[worst] = false;
        }
    }

    private static Pose fit(List<AnchorPair> anchors, boolean[] used) {
        Pose reference = null;
        double sqx = 0, sqy = 0, sqz = 0, sqw = 0, dx = 0, dy = 0, dz = 0, ox = 0, oy = 0, oz = 0;
        int n = 0;
        for (int i = 0; i < anchors.size(); i++) {
            if (!used[i]) {
                continue;
            }
            AnchorPair a = anchors.get(i);
            Pose c = normalize(deviceToVenueFromAnchor(a.digitalPose(), a.observedPose()));
            if (reference == null) {
                reference = c;
            }
            // q and -q are the same rotation: bring every candidate into the reference's hemisphere before averaging.
            double sign = c.qx() * reference.qx() + c.qy() * reference.qy() + c.qz() * reference.qz() + c.qw() * reference.qw() < 0 ? -1 : 1;
            sqx += sign * c.qx();
            sqy += sign * c.qy();
            sqz += sign * c.qz();
            sqw += sign * c.qw();
            dx += a.digitalPose().x();
            dy += a.digitalPose().y();
            dz += a.digitalPose().z();
            ox += a.observedPose().x();
            oy += a.observedPose().y();
            oz += a.observedPose().z();
            n++;
        }
        Pose rotation = normalize(new Pose(0, 0, 0, sqx / n, sqy / n, sqz / n, sqw / n));
        double[] ro = rotate(rotation, ox / n, oy / n, oz / n);
        return new Pose(dx / n - ro[0], dy / n - ro[1], dz / n - ro[2], rotation.qx(), rotation.qy(), rotation.qz(), rotation.qw());
    }

    /** Per anchor: {translation metres, rotation degrees} between its mapped observation and its registered pose. */
    private static double[][] residuals(List<AnchorPair> anchors, Pose transform) {
        double[][] out = new double[anchors.size()][2];
        for (int i = 0; i < anchors.size(); i++) {
            Pose predicted = normalize(compose(transform, anchors.get(i).observedPose()));
            Pose digital = normalize(anchors.get(i).digitalPose());
            out[i][0] = translationDistance(predicted, digital);
            double dot = Math.abs(predicted.qx() * digital.qx() + predicted.qy() * digital.qy() + predicted.qz() * digital.qz()
                + predicted.qw() * digital.qw());
            out[i][1] = Math.toDegrees(2 * Math.acos(Math.min(1, dot)));
        }
        return out;
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
