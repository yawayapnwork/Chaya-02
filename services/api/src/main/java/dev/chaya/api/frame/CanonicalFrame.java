package dev.chaya.api.frame;

/**
 * The Chaya canonical venue coordinate system. docs/coordinate-frames.md is the normative description; the worker's
 * chaya_worker.frames and the web client's lib/coordinate-frame.ts state the same convention.
 *
 * <ul>
 *   <li>Units: metres.</li>
 *   <li>Up: +Z, opposite to gravity. X and Y span the horizontal plane.</li>
 *   <li>Handedness: right-handed (X x Y = Z).</li>
 *   <li>Origin: fixed by the frame's horizontal datum. {@code FLOOR_LOCAL}: z = 0 on the floor plane, x = y = 0 at the
 *       calibration's floor reference point, +X along the horizontal projection of the reconstruction's own +X axis
 *       (its +Y if +X is within 25 degrees of vertical); it is not shared between floors. {@code VENUE_CONTROL_POINTS}:
 *       the venue datum the operator's surveyed control points are expressed in, shared by every floor calibrated
 *       against it.</li>
 *   <li>Transform: a similarity X_canonical = s * R * X_reconstruction + t, with R as a unit quaternion (w, x, y, z).</li>
 *   <li>Precision: double precision for every transform and stored coordinate.</li>
 * </ul>
 */
public final class CanonicalFrame {

    public static final String UNITS = "m";
    public static final String UP_AXIS = "+Z";
    public static final String HANDEDNESS = "right";
    /** Canonical up, +Z. A fresh array each call: callers may not mutate a shared constant. */
    public static double[] up() {
        return new double[]{0, 0, 1};
    }

    /** When the reconstruction's +X is within this angle of vertical, the FLOOR_LOCAL heading uses its +Y instead. */
    static final double HEADING_AXIS_MIN_ANGLE_FROM_VERTICAL_DEG = 25.0;

    private CanonicalFrame() {}
}
