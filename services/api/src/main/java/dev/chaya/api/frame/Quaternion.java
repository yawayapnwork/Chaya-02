package dev.chaya.api.frame;

/**
 * A rotation as a unit quaternion (w, x, y, z) -- the order Chaya uses everywhere on the wire and in storage.
 * Constructing one normalises it; a zero or non-finite quaternion is rejected rather than turned into identity.
 */
public record Quaternion(double w, double x, double y, double z) {

    public static final Quaternion IDENTITY = new Quaternion(1, 0, 0, 0);

    public Quaternion {
        double norm = Math.sqrt(w * w + x * x + y * y + z * z);
        if (!Double.isFinite(norm) || norm < 1e-12) {
            throw new IllegalArgumentException("a rotation quaternion must be finite and non-zero");
        }
        w /= norm;
        x /= norm;
        y /= norm;
        z /= norm;
    }

    /** Hamilton product this * other: apply `other` first, then this. */
    public Quaternion multiply(Quaternion o) {
        return new Quaternion(
            w * o.w - x * o.x - y * o.y - z * o.z,
            w * o.x + x * o.w + y * o.z - z * o.y,
            w * o.y - x * o.z + y * o.w + z * o.x,
            w * o.z + x * o.y - y * o.x + z * o.w);
    }

    public Quaternion conjugate() {
        return new Quaternion(w, -x, -y, -z);
    }

    public double[][] toMatrix() {
        return new double[][]{
            {1 - 2 * (y * y + z * z), 2 * (x * y - w * z), 2 * (x * z + w * y)},
            {2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x)},
            {2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y)}};
    }

    public double[] rotate(double[] v) {
        double[][] m = toMatrix();
        return new double[]{
            m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2],
            m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2],
            m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2]};
    }

    /** Rotation matrix to quaternion (Shepperd's method), with w >= 0. The matrix must be a proper rotation. */
    public static Quaternion fromMatrix(double[][] m) {
        double trace = m[0][0] + m[1][1] + m[2][2];
        double w;
        double x;
        double y;
        double z;
        if (trace > 0) {
            double s = Math.sqrt(trace + 1.0) * 2;
            w = 0.25 * s;
            x = (m[2][1] - m[1][2]) / s;
            y = (m[0][2] - m[2][0]) / s;
            z = (m[1][0] - m[0][1]) / s;
        } else if (m[0][0] > m[1][1] && m[0][0] > m[2][2]) {
            double s = Math.sqrt(1.0 + m[0][0] - m[1][1] - m[2][2]) * 2;
            w = (m[2][1] - m[1][2]) / s;
            x = 0.25 * s;
            y = (m[0][1] + m[1][0]) / s;
            z = (m[0][2] + m[2][0]) / s;
        } else if (m[1][1] > m[2][2]) {
            double s = Math.sqrt(1.0 + m[1][1] - m[0][0] - m[2][2]) * 2;
            w = (m[0][2] - m[2][0]) / s;
            x = (m[0][1] + m[1][0]) / s;
            y = 0.25 * s;
            z = (m[1][2] + m[2][1]) / s;
        } else {
            double s = Math.sqrt(1.0 + m[2][2] - m[0][0] - m[1][1]) * 2;
            w = (m[1][0] - m[0][1]) / s;
            x = (m[0][2] + m[2][0]) / s;
            y = (m[1][2] + m[2][1]) / s;
            z = 0.25 * s;
        }
        return w < 0 ? new Quaternion(-w, -x, -y, -z) : new Quaternion(w, x, y, z);
    }

    /** Rotation angle between this and another rotation, in degrees (q and -q are the same rotation). */
    public double angleToDegrees(Quaternion o) {
        double dot = Math.abs(w * o.w + x * o.x + y * o.y + z * o.z);
        return Math.toDegrees(2 * Math.acos(Math.min(1.0, dot)));
    }
}
