package dev.chaya.api.ar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import dev.chaya.api.ar.ArDtos.Pose;
import dev.chaya.api.frame.Quaternion;
import org.junit.jupiter.api.Test;

/** The AR device-frame boundary: device +Y-up metres to canonical +Z-up metres. */
class ArDeviceFrameTest {

    private static void assertVec(double[] v, double x, double y, double z) {
        assertThat(v[0]).isCloseTo(x, within(1e-12));
        assertThat(v[1]).isCloseTo(y, within(1e-12));
        assertThat(v[2]).isCloseTo(z, within(1e-12));
    }

    @Test
    void deviceUpIsCanonicalUpAndTheConversionIsAProperRotation() {
        assertVec(ArDeviceFrame.deviceAxesToCanonical(new double[]{0, 1, 0}), 0, 0, 1);
        assertVec(ArDeviceFrame.deviceAxesToCanonical(new double[]{1, 0, 0}), 1, 0, 0);
        assertVec(ArDeviceFrame.deviceAxesToCanonical(new double[]{0, 0, 1}), 0, -1, 0); // device +Z (towards the viewer) is canonical -Y
        assertVec(ArDeviceFrame.canonicalAxesToDevice(ArDeviceFrame.deviceAxesToCanonical(new double[]{0.3, -2, 7})), 0.3, -2, 7);
    }

    @Test
    void aTransformThatRespectsGravityHasNoTilt() {
        // Device axes -> canonical axes, then any rotation about canonical +Z (heading) and any translation.
        double h = Math.toRadians(40) / 2;
        Quaternion heading = new Quaternion(Math.cos(h), 0, 0, Math.sin(h));
        Quaternion r = heading.multiply(ArDeviceFrame.DEVICE_TO_CANONICAL_AXES);
        Pose t = new Pose(3, -1, 0.2, r.x(), r.y(), r.z(), r.w());
        assertThat(ArDeviceFrame.gravityTiltDegrees(t)).isLessThan(1e-9);
    }

    @Test
    void anIdentityTransformIgnoringTheAxisConventionIsNinetyDegreesOff() {
        assertThat(ArDeviceFrame.gravityTiltDegrees(new Pose(0, 0, 0, 0, 0, 0, 1))).isCloseTo(90, within(1e-9));
    }
}
