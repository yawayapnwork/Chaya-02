package dev.chaya.api.frame;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Triangulator} and {@link Triangulator.Camera}:
 * - Multi-view triangulation from registered camera poses (DLT eigenvector method).
 * - Camera models and distortion / undistortion round-tripping (PINHOLE, SIMPLE_PINHOLE, SIMPLE_RADIAL, RADIAL, OPENCV).
 * - Rigorous refusal of unphysical or ill-conditioned rays (insufficient views, non-finite inputs,
 *   parallel rays, behind camera, small triangulation angle, high reprojection error).
 *
 * Mathematical and synthetic tests -- not real venue data.
 */
class TriangulatorTest {

    private static final Triangulator.Limits DEFAULT_LIMITS =
        new Triangulator.Limits(2, 2.0, 4.0, 8.0);

    // =========================================================================================
    // Camera models and distortion
    // =========================================================================================

    @Test
    void cameraConstructorsValidateParameters() {
        // PINHOLE: fx, fy, cx, cy
        new Triangulator.Camera("PINHOLE", 1920, 1080, new double[]{1000, 1000, 960, 540});
        // SIMPLE_PINHOLE: f, cx, cy
        new Triangulator.Camera("SIMPLE_PINHOLE", 1920, 1080, new double[]{1000, 960, 540});
        // SIMPLE_RADIAL: f, cx, cy, k1
        new Triangulator.Camera("SIMPLE_RADIAL", 1920, 1080, new double[]{1000, 960, 540, -0.05});
        // RADIAL: f, cx, cy, k1, k2
        new Triangulator.Camera("RADIAL", 1920, 1080, new double[]{1000, 960, 540, -0.05, 0.001});
        // OPENCV: fx, fy, cx, cy, k1, k2, p1, p2
        new Triangulator.Camera("OPENCV", 1920, 1080, new double[]{1000, 1000, 960, 540, -0.05, 0.001, 0.0001, -0.0001});

        // Unsupported model
        assertThatThrownBy(() -> new Triangulator.Camera("FOVIUM", 1920, 1080, new double[]{1000, 960, 540}))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not supported");

        // Wrong parameter length
        assertThatThrownBy(() -> new Triangulator.Camera("PINHOLE", 1920, 1080, new double[]{1000, 960, 540}))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("needs 4 parameters");

        // Non-positive dimensions
        assertThatThrownBy(() -> new Triangulator.Camera("PINHOLE", 0, 1080, new double[]{1000, 1000, 960, 540}))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("positive");

        // Non-finite parameter
        assertThatThrownBy(() -> new Triangulator.Camera("PINHOLE", 1920, 1080, new double[]{1000, Double.NaN, 960, 540}))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("finite");
    }

    @Test
    void cameraModelsRoundTripProjectAndUndistort() {
        List<Triangulator.Camera> cameras = List.of(
            new Triangulator.Camera("PINHOLE", 1920, 1080, new double[]{1200, 1100, 960, 540}),
            new Triangulator.Camera("SIMPLE_PINHOLE", 1920, 1080, new double[]{1150, 960, 540}),
            new Triangulator.Camera("SIMPLE_RADIAL", 1920, 1080, new double[]{1150, 960, 540, -0.02}),
            new Triangulator.Camera("RADIAL", 1920, 1080, new double[]{1150, 960, 540, -0.02, 0.005}),
            new Triangulator.Camera("OPENCV", 1920, 1080, new double[]{1200, 1150, 960, 540, -0.03, 0.002, 0.0005, -0.0002})
        );

        double[][] testPixels = {
            {960.0, 540.0}, // optical centre
            {500.0, 300.0},
            {1400.0, 800.0},
            {100.0, 100.0},
            {1800.0, 1000.0}
        };

        for (Triangulator.Camera cam : cameras) {
            for (double[] px : testPixels) {
                double[] xy = cam.undistort(px[0], px[1]);
                assertThat(xy).as("undistort failed for " + cam.model()).isNotNull();
                double[] back = cam.project(xy[0], xy[1]);
                assertThat(back[0]).as("px X for " + cam.model()).isCloseTo(px[0], within(1e-7));
                assertThat(back[1]).as("px Y for " + cam.model()).isCloseTo(px[1], within(1e-7));
            }
        }
    }

    // =========================================================================================
    // Triangulation: exact recovery and camera centre
    // =========================================================================================

    @Test
    void cameraCentreMatchesNegativeTransposeRtimesT() {
        // Camera at (5, -3, 2), rotated 90 deg about Z
        // R maps world to camera: R = rotZ(90).
        // X_c = R X + t => when X = C, X_c = 0 => t = -R C.
        Quaternion q = new Quaternion(Math.cos(Math.PI / 4), 0, 0, Math.sin(Math.PI / 4));
        double[][] r = q.toMatrix();
        double[] cTrue = {5.0, -3.0, 2.0};
        double[] t = {
            -(r[0][0] * cTrue[0] + r[0][1] * cTrue[1] + r[0][2] * cTrue[2]),
            -(r[1][0] * cTrue[0] + r[1][1] * cTrue[1] + r[1][2] * cTrue[2]),
            -(r[2][0] * cTrue[0] + r[2][1] * cTrue[1] + r[2][2] * cTrue[2])
        };
        double[] cCalc = Triangulator.centre(r, t);
        assertThat(cCalc[0]).isCloseTo(cTrue[0], within(1e-12));
        assertThat(cCalc[1]).isCloseTo(cTrue[1], within(1e-12));
        assertThat(cCalc[2]).isCloseTo(cTrue[2], within(1e-12));
    }

    @Test
    void exact3DPointRecoveredFromMultipleRegisteredViews() {
        Triangulator.Camera cam = new Triangulator.Camera("PINHOLE", 1920, 1080, new double[]{1000, 1000, 960, 540});
        double[] xTarget = {2.5, 1.8, 6.0};

        // Three cameras observing xTarget:
        // View 1 at (0, 0, 0), looking along +Z
        Triangulator.View v1 = new Triangulator.View("view1", cam, Quaternion.IDENTITY, new double[]{0, 0, 0});
        // View 2 at (3, 0, 0), looking along +Z -> t = [-3, 0, 0]
        Triangulator.View v2 = new Triangulator.View("view2", cam, Quaternion.IDENTITY, new double[]{-3, 0, 0});
        // View 3 at (0, 3, 0), looking along +Z -> t = [0, -3, 0]
        Triangulator.View v3 = new Triangulator.View("view3", cam, Quaternion.IDENTITY, new double[]{0, -3, 0});

        List<Triangulator.Observation> obs = List.of(
            observe(v1, xTarget),
            observe(v2, xTarget),
            observe(v3, xTarget)
        );

        Triangulator.Result result = Triangulator.triangulate(obs, DEFAULT_LIMITS);
        assertThat(result.ok()).isTrue();
        assertThat(result.failure()).isNull();
        assertThat(result.views()).isEqualTo(3);
        assertThat(result.point()[0]).isCloseTo(xTarget[0], within(1e-9));
        assertThat(result.point()[1]).isCloseTo(xTarget[1], within(1e-9));
        assertThat(result.point()[2]).isCloseTo(xTarget[2], within(1e-9));
        assertThat(result.rmsReprojectionPx()).isLessThan(1e-6);
        assertThat(result.maxReprojectionPx()).isLessThan(1e-6);
        assertThat(result.maxAngleDeg()).isGreaterThan(20.0);
    }

    @Test
    void triangulateWithDistortedCamerasRecoversExactPoint() {
        Triangulator.Camera cam = new Triangulator.Camera("OPENCV", 1920, 1080,
            new double[]{1200, 1150, 960, 540, -0.04, 0.002, 0.0003, -0.0002});
        double[] xTarget = {1.2, -0.8, 4.5};

        Triangulator.View v1 = new Triangulator.View("v1", cam, Quaternion.IDENTITY, new double[]{0, 0, 0});
        Triangulator.View v2 = new Triangulator.View("v2", cam, Quaternion.IDENTITY, new double[]{-2, 0, 0});

        List<Triangulator.Observation> obs = List.of(
            observe(v1, xTarget),
            observe(v2, xTarget)
        );

        Triangulator.Result result = Triangulator.triangulate(obs, DEFAULT_LIMITS);
        assertThat(result.ok()).isTrue();
        assertThat(result.point()[0]).isCloseTo(xTarget[0], within(1e-7));
        assertThat(result.point()[1]).isCloseTo(xTarget[1], within(1e-7));
        assertThat(result.point()[2]).isCloseTo(xTarget[2], within(1e-7));
        assertThat(result.rmsReprojectionPx()).isLessThan(1e-4);
    }

    // =========================================================================================
    // Rejection of invalid, degenerate, or inconsistent rays
    // =========================================================================================

    @Test
    void refusesWhenFewerThanMinViews() {
        Triangulator.Camera cam = new Triangulator.Camera("PINHOLE", 1920, 1080, new double[]{1000, 1000, 960, 540});
        Triangulator.View v1 = new Triangulator.View("view1", cam, Quaternion.IDENTITY, new double[]{0, 0, 0});
        List<Triangulator.Observation> obs = List.of(new Triangulator.Observation(v1, 960, 540));

        Triangulator.Result result = Triangulator.triangulate(obs, DEFAULT_LIMITS);
        assertThat(result.ok()).isFalse();
        assertThat(result.failure()).isEqualTo("INSUFFICIENT_VIEWS");
        assertThat(result.message()).contains("at least 2 are needed");
    }

    @Test
    void refusesNonFiniteObservations() {
        Triangulator.Camera cam = new Triangulator.Camera("PINHOLE", 1920, 1080, new double[]{1000, 1000, 960, 540});
        Triangulator.View v1 = new Triangulator.View("view1", cam, Quaternion.IDENTITY, new double[]{0, 0, 0});
        Triangulator.View v2 = new Triangulator.View("view2", cam, Quaternion.IDENTITY, new double[]{-2, 0, 0});
        List<Triangulator.Observation> obs = List.of(
            new Triangulator.Observation(v1, Double.NaN, 540),
            new Triangulator.Observation(v2, 960, 540)
        );

        Triangulator.Result result = Triangulator.triangulate(obs, DEFAULT_LIMITS);
        assertThat(result.ok()).isFalse();
        assertThat(result.failure()).isEqualTo("NON_FINITE_OBSERVATION");
    }

    @Test
    void refusesParallelRaysAtInfinity() {
        // Two cameras with a horizontal baseline, both observing pixel (960, 540) at optical centre
        // The rays both point straight forward along +Z in parallel -> intersection at infinity
        Triangulator.Camera cam = new Triangulator.Camera("PINHOLE", 1920, 1080, new double[]{1000, 1000, 960, 540});
        Triangulator.View v1 = new Triangulator.View("view1", cam, Quaternion.IDENTITY, new double[]{0, 0, 0});
        Triangulator.View v2 = new Triangulator.View("view2", cam, Quaternion.IDENTITY, new double[]{-2, 0, 0});

        List<Triangulator.Observation> obs = List.of(
            new Triangulator.Observation(v1, 960, 540),
            new Triangulator.Observation(v2, 960, 540)
        );

        Triangulator.Result result = Triangulator.triangulate(obs, DEFAULT_LIMITS);
        assertThat(result.ok()).isFalse();
        assertThat(result.failure()).isEqualTo("POINT_AT_INFINITY");
    }

    @Test
    void refusesPointBehindCamera() {
        // Rays converging behind the camera optical plane (Z < 0)
        Triangulator.Camera cam = new Triangulator.Camera("PINHOLE", 1920, 1080, new double[]{1000, 1000, 960, 540});
        Triangulator.View v1 = new Triangulator.View("view1", cam, Quaternion.IDENTITY, new double[]{0, 0, 0});
        Triangulator.View v2 = new Triangulator.View("view2", cam, Quaternion.IDENTITY, new double[]{-2, 0, 0});

        // For v1 (cam at 0), pixel at u=900 means ray diverges to -X.
        // For v2 (cam at 2), pixel at u=1020 means ray diverges to +X.
        // They converge at negative Z (behind the cameras).
        List<Triangulator.Observation> obs = List.of(
            new Triangulator.Observation(v1, 900, 540),
            new Triangulator.Observation(v2, 1020, 540)
        );

        Triangulator.Result result = Triangulator.triangulate(obs, DEFAULT_LIMITS);
        assertThat(result.ok()).isFalse();
        assertThat(result.failure()).isEqualTo("POINT_BEHIND_CAMERA");
        assertThat(result.message()).contains("behind the camera");
    }

    @Test
    void refusesRayAngleBelowThreshold() {
        // Two cameras with tiny baseline (e.g. 0.01 m) observing a point at 10 m depth -> ray angle ~ 0.05 deg (< 2.0 deg limit)
        Triangulator.Camera cam = new Triangulator.Camera("PINHOLE", 1920, 1080, new double[]{1000, 1000, 960, 540});
        double[] xTarget = {0.0, 0.0, 10.0};
        Triangulator.View v1 = new Triangulator.View("v1", cam, Quaternion.IDENTITY, new double[]{0, 0, 0});
        Triangulator.View v2 = new Triangulator.View("v2", cam, Quaternion.IDENTITY, new double[]{-0.01, 0, 0});

        List<Triangulator.Observation> obs = List.of(
            observe(v1, xTarget),
            observe(v2, xTarget)
        );

        Triangulator.Result result = Triangulator.triangulate(obs, DEFAULT_LIMITS);
        assertThat(result.ok()).isFalse();
        assertThat(result.failure()).isEqualTo("TRIANGULATION_ANGLE_TOO_SMALL");
        assertThat(result.maxAngleDeg()).isLessThan(2.0);
    }

    @Test
    void refusesInconsistentObservationsWithExcessiveReprojectionError() {
        Triangulator.Camera cam = new Triangulator.Camera("PINHOLE", 1920, 1080, new double[]{1000, 1000, 960, 540});
        double[] xTarget = {1.0, 1.0, 5.0};
        Triangulator.View v1 = new Triangulator.View("v1", cam, Quaternion.IDENTITY, new double[]{0, 0, 0});
        Triangulator.View v2 = new Triangulator.View("v2", cam, Quaternion.IDENTITY, new double[]{-2, 0, 0});
        Triangulator.View v3 = new Triangulator.View("v3", cam, Quaternion.IDENTITY, new double[]{0, -2, 0});

        Triangulator.Observation o1 = observe(v1, xTarget);
        Triangulator.Observation o2 = observe(v2, xTarget);
        // Perturb view 3 observation by 25 pixels (inconsistent ray / mis-click)
        Triangulator.Observation o3Correct = observe(v3, xTarget);
        Triangulator.Observation o3Corrupt = new Triangulator.Observation(v3, o3Correct.u() + 25.0, o3Correct.v() + 25.0);

        Triangulator.Result result = Triangulator.triangulate(List.of(o1, o2, o3Corrupt), DEFAULT_LIMITS);
        assertThat(result.ok()).isFalse();
        assertThat(result.failure()).isEqualTo("REPROJECTION_ERROR_TOO_HIGH");
        assertThat(result.rmsReprojectionPx()).isGreaterThan(DEFAULT_LIMITS.maxRmsReprojectionPx());
        assertThat(result.maxReprojectionPx()).isGreaterThan(DEFAULT_LIMITS.maxReprojectionPx());
    }

    // =========================================================================================
    // Helpers
    // =========================================================================================

    private static Triangulator.Observation observe(Triangulator.View v, double[] worldPoint) {
        double[][] r = v.rotation().toMatrix();
        double[] t = v.translation();
        double xc = r[0][0] * worldPoint[0] + r[0][1] * worldPoint[1] + r[0][2] * worldPoint[2] + t[0];
        double yc = r[1][0] * worldPoint[0] + r[1][1] * worldPoint[1] + r[1][2] * worldPoint[2] + t[1];
        double zc = r[2][0] * worldPoint[0] + r[2][1] * worldPoint[1] + r[2][2] * worldPoint[2] + t[2];
        double[] px = v.camera().project(xc / zc, yc / zc);
        return new Triangulator.Observation(v, px[0], px[1]);
    }
}
