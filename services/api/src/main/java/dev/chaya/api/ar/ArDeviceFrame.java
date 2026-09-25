package dev.chaya.api.ar;

import dev.chaya.api.ar.ArDtos.Pose;
import dev.chaya.api.frame.CanonicalFrame;
import dev.chaya.api.frame.Quaternion;

/**
 * The boundary between an AR device's tracking frame and the Chaya canonical venue frame (docs/coordinate-frames.md,
 * "AR boundary"). The one place the device axis convention is written down.
 *
 * <p>Both ARKit (world tracking) and WebXR ("local" reference space on ARCore) report poses in a right-handed,
 * metre-scaled, gravity-aligned frame with <b>+Y up</b>. The canonical frame is right-handed, metres, <b>+Z up</b>. The
 * fixed rotation between the two conventions is +90 degrees about X: device (x, y, z) -> canonical axes (x, -z, y).
 *
 * <p>A relocalization transform T maps device coordinates to canonical ones. Because both frames are gravity-aligned
 * and metric, T must be a rigid motion whose rotation takes device up (+Y) onto canonical up (+Z), i.e. a rotation about
 * the vertical only once the axis convention is accounted for. {@link #gravityTiltDegrees} measures how far a solved T
 * is from that; a large tilt means the anchors' digital poses and the device's detections disagree about which way is
 * up (a mis-entered anchor orientation or a marker-axis convention mismatch), and AnchorService refuses it.
 */
public final class ArDeviceFrame {

    public static final String CONVENTION = "DEVICE_Y_UP_RIGHT_HANDED_METRES";

    /** Rotation taking the device axis convention onto the canonical one: +90 degrees about X. */
    public static final Quaternion DEVICE_TO_CANONICAL_AXES = new Quaternion(Math.cos(Math.PI / 4), Math.sin(Math.PI / 4), 0, 0);

    private static final double[] DEVICE_UP = {0, 1, 0};

    private ArDeviceFrame() {}

    /** A device-frame vector expressed with canonical axes (no translation): (x, y, z) -> (x, -z, y). */
    public static double[] deviceAxesToCanonical(double[] v) {
        return DEVICE_TO_CANONICAL_AXES.rotate(v);
    }

    /** A canonical-axes vector expressed with device axes: (x, y, z) -> (x, z, -y). */
    public static double[] canonicalAxesToDevice(double[] v) {
        return DEVICE_TO_CANONICAL_AXES.conjugate().rotate(v);
    }

    /** Angle between where a device-to-venue transform sends the device's up and canonical +Z, in degrees. */
    public static double gravityTiltDegrees(Pose deviceToVenue) {
        Quaternion r = new Quaternion(deviceToVenue.qw(), deviceToVenue.qx(), deviceToVenue.qy(), deviceToVenue.qz());
        double[] up = r.rotate(DEVICE_UP);
        double[] z = CanonicalFrame.up();
        double c = (up[0] * z[0] + up[1] * z[1] + up[2] * z[2]) / Math.sqrt(up[0] * up[0] + up[1] * up[1] + up[2] * up[2]);
        return Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, c))));
    }
}
