package dev.chaya.api.ar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import dev.chaya.api.ar.ArDtos.Pose;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure coordinate-transformation and anchor math. No database, no Spring context, no device -- these run
 * on every commit and are the contract the WebXR (apps/web/lib/ar-anchor-math.test.ts) and iOS
 * (ChayaARCoreTests/AnchorMathTests.swift) equivalents each implement independently.
 */
class CoordinateTransformTest {

    private static final Pose IDENTITY = new Pose(0, 0, 0, 0, 0, 0, 1);

    @Test
    void identityObservationOfAnAnchorAtTheOriginYieldsIdentityTransform() {
        Pose result = CoordinateTransform.deviceToVenueFromAnchor(IDENTITY, IDENTITY);
        assertThat(result.x()).isCloseTo(0, within(1e-9));
        assertThat(result.y()).isCloseTo(0, within(1e-9));
        assertThat(result.z()).isCloseTo(0, within(1e-9));
        assertThat(result.qw()).isCloseTo(1, within(1e-9));
    }

    @Test
    void anchorOffsetInVenueFrameTranslatesDeviceOriginToThatOffset() {
        // The anchor sits at (5, 0, 2) in the venue frame, and the device sees it exactly at its own
        // origin with no rotation -- so the device's origin, mapped into the venue frame, must be that
        // same point: the device is standing exactly where the anchor is.
        Pose anchorInVenue = new Pose(5, 0, 2, 0, 0, 0, 1);
        Pose observedByDevice = IDENTITY;
        Pose transform = CoordinateTransform.deviceToVenueFromAnchor(anchorInVenue, observedByDevice);
        assertThat(transform.x()).isCloseTo(5, within(1e-9));
        assertThat(transform.y()).isCloseTo(0, within(1e-9));
        assertThat(transform.z()).isCloseTo(2, within(1e-9));
    }

    @Test
    void deviceOffsetFromAnchorIsSubtractedOutOfTheTransform() {
        // The anchor is at the venue origin. The device observes the anchor 3m in front of it (its own
        // -z axis, say), meaning the device itself is actually 3m further along +z from the anchor.
        Pose anchorInVenue = IDENTITY;
        Pose observedByDevice = new Pose(0, 0, -3, 0, 0, 0, 1);
        Pose transform = CoordinateTransform.deviceToVenueFromAnchor(anchorInVenue, observedByDevice);
        assertThat(transform.x()).isCloseTo(0, within(1e-9));
        assertThat(transform.y()).isCloseTo(0, within(1e-9));
        assertThat(transform.z()).isCloseTo(3, within(1e-9));
    }

    // ---- multi-anchor solve ----------------------------------------------------------------------------------------
    // A known layout: the device world is +Y up, the venue +Z up. TRUE maps device -> venue: +90 deg about X (the axis
    // change), then 30 deg of heading about the venue's +Z, then (12, -4, 0). Three wall markers 6-8 m apart.

    private static final double LIMIT_M = 0.3;
    private static final double LIMIT_DEG = 8;
    private static final Pose AXES = new Pose(0, 0, 0, Math.sin(Math.PI / 4), 0, 0, Math.cos(Math.PI / 4));
    private static final Pose HEADING = new Pose(0, 0, 0, 0, 0, Math.sin(Math.toRadians(15)), Math.cos(Math.toRadians(15)));
    private static final Pose TRUE = CoordinateTransform.compose(new Pose(12, -4, 0, 0, 0, 0, 1), CoordinateTransform.compose(HEADING, AXES));
    private static final List<Pose> MARKERS = List.of(
        new Pose(10, 0, 1.5, 1, 0, 0, 0),          // on a wall facing -Y (180 deg about X)
        new Pose(16, 0, 1.5, 1, 0, 0, 0),
        new Pose(13, 7, 1.2, 0, 1, 0, 0));          // on the opposite wall (180 deg about Y)

    /** What the device reports for a marker: its registered pose seen through TRUE^-1, optionally with heading noise. */
    private static Pose observed(Pose digital, double headingErrorDeg) {
        Pose exact = CoordinateTransform.compose(CoordinateTransform.invert(TRUE), digital);
        double h = Math.toRadians(headingErrorDeg) / 2; // about the device's +Y (gravity), at the marker
        Pose noise = new Pose(0, 0, 0, 0, Math.sin(h), 0, Math.cos(h));
        Pose rotated = CoordinateTransform.compose(noise, new Pose(0, 0, 0, exact.qx(), exact.qy(), exact.qz(), exact.qw()));
        return new Pose(exact.x(), exact.y(), exact.z(), rotated.qx(), rotated.qy(), rotated.qz(), rotated.qw());
    }

    private static Pose shifted(Pose p, double dx, double dy, double dz) {
        return new Pose(p.x() + dx, p.y() + dy, p.z() + dz, p.qx(), p.qy(), p.qz(), p.qw());
    }

    private static double[] map(Pose transform, double x, double y, double z) {
        Pose p = CoordinateTransform.compose(transform, new Pose(x, y, z, 0, 0, 0, 1));
        return new double[] {p.x(), p.y(), p.z()};
    }

    private static double distance(double[] a, double[] b) {
        return Math.sqrt(Math.pow(a[0] - b[0], 2) + Math.pow(a[1] - b[1], 2) + Math.pow(a[2] - b[2], 2));
    }

    private static List<CoordinateTransform.AnchorPair> exact() {
        return MARKERS.stream().map(m -> new CoordinateTransform.AnchorPair(m, observed(m, 0))).toList();
    }

    @Test
    void agreeingAnchorsRecoverTheTrueTransformWithZeroResidual() {
        var s = CoordinateTransform.solve(exact(), LIMIT_M, LIMIT_DEG);
        assertThat(s.consistent()).isTrue();
        assertThat(s.maxResidualMeters()).isCloseTo(0, within(1e-9));
        assertThat(s.maxResidualDegrees()).isCloseTo(0, within(1e-4));
        assertThat(distance(map(s.transform(), 1, 1.6, -2), map(TRUE, 1, 1.6, -2))).isLessThan(1e-9);
        assertThat(s.rejected()).isEmpty();
    }

    @Test
    void disagreeingAnchorsReportTheirMeasuredResidualPerAnchor() {
        // The second marker is registered 20 cm from where the device sees it: each lands 10 cm from its registered
        // position under the best common transform. Reported, not hidden.
        Pose a = MARKERS.get(0);
        Pose b = MARKERS.get(1);
        var pairs = List.of(new CoordinateTransform.AnchorPair(a, observed(a, 0)),
            new CoordinateTransform.AnchorPair(shifted(b, 0.2, 0, 0), observed(b, 0)));
        var s = CoordinateTransform.solve(pairs, LIMIT_M, LIMIT_DEG);
        assertThat(s.consistent()).isTrue();
        assertThat(s.residuals()).allSatisfy(r -> assertThat(r.translationMeters()).isCloseTo(0.1, within(1e-9)));
        assertThat(s.maxResidualMeters()).isCloseTo(0.1, within(1e-9));
    }

    @Test
    void theSolutionAndItsResidualDoNotDependOnWhereTheTrackingSessionStarted() {
        // Each marker's orientation measured with a different small heading error (+1, -1, +0.5 deg). The same physical
        // situation is reported twice: from a session that started near the markers, and from one that started 35 m away
        // (every observation shifted by the same offset).
        double[] noise = {1.0, -1.0, 0.5};
        var near = new java.util.ArrayList<CoordinateTransform.AnchorPair>();
        for (int i = 0; i < MARKERS.size(); i++) {
            near.add(new CoordinateTransform.AnchorPair(MARKERS.get(i), observed(MARKERS.get(i), noise[i])));
        }
        var far = near.stream().map(p -> new CoordinateTransform.AnchorPair(p.digitalPose(), shifted(p.observedPose(), 25, 0, -25))).toList();
        var solvedNear = CoordinateTransform.solve(near, LIMIT_M, LIMIT_DEG);
        var solvedFar = CoordinateTransform.solve(far, LIMIT_M, LIMIT_DEG);
        // The same physical point lands in the same venue place, and every anchor has the same residual, in both sessions.
        assertThat(distance(map(solvedNear.transform(), 1, 1.6, -3), map(solvedFar.transform(), 26, 1.6, -28))).isLessThan(1e-9);
        for (int i = 0; i < MARKERS.size(); i++) {
            assertThat(solvedFar.residuals().get(i).translationMeters())
                .isCloseTo(solvedNear.residuals().get(i).translationMeters(), within(1e-9));
        }
        // 1 deg of heading error, over markers at most ~5 m from their centroid: a few centimetres at each marker.
        assertThat(solvedFar.maxResidualMeters()).isLessThan(0.09);

        // The residual this replaced (largest pairwise distance between the candidates' translations) mostly measured how
        // far the session origin was: each candidate's translation carries its rotation error times that distance.
        // Here it grows by more than half a metre between the two sessions while nothing about the markers changed.
        assertThat(pairwiseCandidateSpread(far) - pairwiseCandidateSpread(near)).isGreaterThan(0.5);
    }

    private static double pairwiseCandidateSpread(List<CoordinateTransform.AnchorPair> pairs) {
        double worst = 0;
        for (int i = 0; i < pairs.size(); i++) {
            for (int j = i + 1; j < pairs.size(); j++) {
                Pose a = CoordinateTransform.deviceToVenueFromAnchor(pairs.get(i).digitalPose(), pairs.get(i).observedPose());
                Pose b = CoordinateTransform.deviceToVenueFromAnchor(pairs.get(j).digitalPose(), pairs.get(j).observedPose());
                worst = Math.max(worst, distance(new double[] {a.x(), a.y(), a.z()}, new double[] {b.x(), b.y(), b.z()}));
            }
        }
        return worst;
    }

    @Test
    void aMovedAnchorAmongThreeIsSetAsideAndTheOthersStillLocalize() {
        // The third marker was moved 1.2 m along its wall after registration.
        var pairs = List.of(
            new CoordinateTransform.AnchorPair(MARKERS.get(0), observed(MARKERS.get(0), 0)),
            new CoordinateTransform.AnchorPair(MARKERS.get(1), observed(MARKERS.get(1), 0)),
            new CoordinateTransform.AnchorPair(MARKERS.get(2), observed(shifted(MARKERS.get(2), 1.2, 0, 0), 0)));
        var s = CoordinateTransform.solve(pairs, LIMIT_M, LIMIT_DEG);
        assertThat(s.consistent()).isTrue();
        assertThat(s.rejected()).containsExactly(2);
        assertThat(s.residuals().get(2).translationMeters()).isCloseTo(1.2, within(1e-6));
        assertThat(s.maxResidualMeters()).isCloseTo(0, within(1e-9));
        assertThat(distance(map(s.transform(), 0, 1.5, 0), map(TRUE, 0, 1.5, 0))).isLessThan(1e-9);
    }

    @Test
    void aMisidentifiedAnchorWithTheWrongOrientationIsSetAside() {
        // The device reports marker 0's image, but the print on the wall is turned 90 degrees in its plane.
        Pose turned = CoordinateTransform.compose(MARKERS.get(0), new Pose(0, 0, 0, 0, Math.sin(Math.PI / 4), 0, Math.cos(Math.PI / 4)));
        var pairs = List.of(
            new CoordinateTransform.AnchorPair(MARKERS.get(0), observed(turned, 0)),
            new CoordinateTransform.AnchorPair(MARKERS.get(1), observed(MARKERS.get(1), 0)),
            new CoordinateTransform.AnchorPair(MARKERS.get(2), observed(MARKERS.get(2), 0)));
        var s = CoordinateTransform.solve(pairs, LIMIT_M, LIMIT_DEG);
        assertThat(s.rejected()).containsExactly(0);
        assertThat(s.residuals().get(0).rotationDegrees()).isGreaterThan(80);
        assertThat(s.consistent()).isTrue();
    }

    @Test
    void twoAnchorsThatDisagreeCannotBeToldApartAndAreRefusedNotAveraged() {
        var pairs = List.of(
            new CoordinateTransform.AnchorPair(MARKERS.get(0), observed(MARKERS.get(0), 0)),
            new CoordinateTransform.AnchorPair(MARKERS.get(1), observed(shifted(MARKERS.get(1), 1.0, 0, 0), 0)));
        var s = CoordinateTransform.solve(pairs, LIMIT_M, LIMIT_DEG);
        assertThat(s.consistent()).isFalse();
        assertThat(s.rejected()).isEmpty();
        assertThat(s.maxResidualMeters()).isCloseTo(0.5, within(1e-6));
    }

    @Test
    void aSingleAnchorIsItsOwnCandidate() {
        Pose m = MARKERS.get(2);
        var s = CoordinateTransform.solve(List.of(new CoordinateTransform.AnchorPair(m, observed(m, 0))), LIMIT_M, LIMIT_DEG);
        Pose candidate = CoordinateTransform.deviceToVenueFromAnchor(m, observed(m, 0));
        assertThat(distance(map(s.transform(), 2, 0, 1), map(candidate, 2, 0, 1))).isLessThan(1e-9);
        assertThat(s.consistent()).isTrue();
    }

    @Test
    void interpolateAtEndpointsReturnsThoseSameAnchors() {
        Pose a = new Pose(0, 0, 0, 0, 0, 0, 1);
        Pose b = new Pose(10, 0, 0, 0, 0, 0, 1);
        assertThat(CoordinateTransform.interpolate(a, b, 0).x()).isCloseTo(0, within(1e-9));
        assertThat(CoordinateTransform.interpolate(a, b, 1).x()).isCloseTo(10, within(1e-9));
        assertThat(CoordinateTransform.interpolate(a, b, 0.5).x()).isCloseTo(5, within(1e-9));
    }

    @Test
    void interpolateClampsOutOfRangeT() {
        Pose a = new Pose(0, 0, 0, 0, 0, 0, 1);
        Pose b = new Pose(10, 0, 0, 0, 0, 0, 1);
        assertThat(CoordinateTransform.interpolate(a, b, -5).x()).isCloseTo(0, within(1e-9));
        assertThat(CoordinateTransform.interpolate(a, b, 5).x()).isCloseTo(10, within(1e-9));
    }

    @Test
    void composeThenInvertRecoversTheOriginalPointExactly() {
        Pose anchorInVenue = new Pose(3, -1, 4, 0, 0.7071, 0, 0.7071); // ~90deg about Y, unnormalized on purpose
        Pose observed = new Pose(1, 2, -2, 0, 0, 0.3827, 0.9239); // ~45deg about Z
        Pose transform = CoordinateTransform.deviceToVenueFromAnchor(anchorInVenue, observed);
        // Applying the transform to the device-frame observation must land back on the anchor's known
        // venue-frame pose -- the algebraic identity the whole relocalization approach depends on.
        Pose recomposed = CoordinateTransform.compose(transform, observed);
        assertThat(recomposed.x()).isCloseTo(anchorInVenue.x(), within(1e-6));
        assertThat(recomposed.y()).isCloseTo(anchorInVenue.y(), within(1e-6));
        assertThat(recomposed.z()).isCloseTo(anchorInVenue.z(), within(1e-6));
    }
}
