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

    @Test
    void blendOfAgreeingAnchorsHasZeroResidual() {
        Pose a = new Pose(1, 2, 3, 0, 0, 0, 1);
        Pose b = new Pose(1, 2, 3, 0, 0, 0, 1);
        CoordinateTransform.Blended blended = CoordinateTransform.blend(List.of(a, b));
        assertThat(blended.residualMeters()).isCloseTo(0, within(1e-9));
        assertThat(blended.transform().x()).isCloseTo(1, within(1e-9));
    }

    @Test
    void blendOfDisagreeingAnchorsReportsRealResidual() {
        Pose a = new Pose(0, 0, 0, 0, 0, 0, 1);
        Pose b = new Pose(0.2, 0, 0, 0, 0, 0, 1); // 20cm apart -- real, measured disagreement
        CoordinateTransform.Blended blended = CoordinateTransform.blend(List.of(a, b));
        assertThat(blended.residualMeters()).isCloseTo(0.2, within(1e-9));
        assertThat(blended.transform().x()).isCloseTo(0.1, within(1e-9)); // averaged, not one anchor picked arbitrarily
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
