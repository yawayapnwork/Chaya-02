import XCTest
@testable import ChayaARCore

/// Pure coordinate-transformation and anchor math. No ARKit, no device -- these run with plain `swift
/// test` anywhere Swift runs. Mirrors CoordinateTransformTest.java and ar-anchor-math.test.ts; see
/// docs/ar.md.
final class AnchorMathTests: XCTestCase {

    let identity = Pose.identity

    func testIdentityObservationOfAnchorAtOriginYieldsIdentityTransform() {
        let result = AnchorMath.deviceToVenue(fromAnchorDigitalPose: identity, observedPose: identity)
        XCTAssertEqual(result.x, 0, accuracy: 1e-9)
        XCTAssertEqual(result.y, 0, accuracy: 1e-9)
        XCTAssertEqual(result.z, 0, accuracy: 1e-9)
        XCTAssertEqual(result.qw, 1, accuracy: 1e-9)
    }

    func testAnchorOffsetInVenueFrameTranslatesDeviceOriginToThatOffset() {
        let anchorInVenue = Pose(x: 5, y: 0, z: 2)
        let transform = AnchorMath.deviceToVenue(fromAnchorDigitalPose: anchorInVenue, observedPose: identity)
        XCTAssertEqual(transform.x, 5, accuracy: 1e-9)
        XCTAssertEqual(transform.y, 0, accuracy: 1e-9)
        XCTAssertEqual(transform.z, 2, accuracy: 1e-9)
    }

    func testDeviceOffsetFromAnchorIsSubtractedOutOfTheTransform() {
        let observedByDevice = Pose(x: 0, y: 0, z: -3)
        let transform = AnchorMath.deviceToVenue(fromAnchorDigitalPose: identity, observedPose: observedByDevice)
        XCTAssertEqual(transform.x, 0, accuracy: 1e-9)
        XCTAssertEqual(transform.y, 0, accuracy: 1e-9)
        XCTAssertEqual(transform.z, 3, accuracy: 1e-9)
    }

    func testBlendOfAgreeingAnchorsHasZeroResidual() {
        let a = Pose(x: 1, y: 2, z: 3)
        let b = Pose(x: 1, y: 2, z: 3)
        let blended = AnchorMath.blend([a, b])
        XCTAssertEqual(blended.residualMeters, 0, accuracy: 1e-9)
        XCTAssertEqual(blended.transform.x, 1, accuracy: 1e-9)
    }

    func testBlendOfDisagreeingAnchorsReportsRealResidual() {
        let a = Pose(x: 0, y: 0, z: 0)
        let b = Pose(x: 0.2, y: 0, z: 0)
        let blended = AnchorMath.blend([a, b])
        XCTAssertEqual(blended.residualMeters, 0.2, accuracy: 1e-9)
        XCTAssertEqual(blended.transform.x, 0.1, accuracy: 1e-9)
    }

    func testInterpolateAtEndpointsReturnsThoseSameAnchors() {
        let a = Pose(x: 0, y: 0, z: 0)
        let b = Pose(x: 10, y: 0, z: 0)
        XCTAssertEqual(AnchorMath.interpolate(a, b, 0).x, 0, accuracy: 1e-9)
        XCTAssertEqual(AnchorMath.interpolate(a, b, 1).x, 10, accuracy: 1e-9)
        XCTAssertEqual(AnchorMath.interpolate(a, b, 0.5).x, 5, accuracy: 1e-9)
    }

    func testInterpolateClampsOutOfRangeT() {
        let a = Pose(x: 0, y: 0, z: 0)
        let b = Pose(x: 10, y: 0, z: 0)
        XCTAssertEqual(AnchorMath.interpolate(a, b, -5).x, 0, accuracy: 1e-9)
        XCTAssertEqual(AnchorMath.interpolate(a, b, 5).x, 10, accuracy: 1e-9)
    }

    func testComposeThenInvertRecoversTheOriginalPoint() {
        let anchorInVenue = Pose(x: 3, y: -1, z: 4, qx: 0, qy: 0.7071, qz: 0, qw: 0.7071)
        let observed = Pose(x: 1, y: 2, z: -2, qx: 0, qy: 0, qz: 0.3827, qw: 0.9239)
        let transform = AnchorMath.deviceToVenue(fromAnchorDigitalPose: anchorInVenue, observedPose: observed)
        let recomposed = AnchorMath.compose(transform, observed)
        XCTAssertEqual(recomposed.x, anchorInVenue.x, accuracy: 1e-6)
        XCTAssertEqual(recomposed.y, anchorInVenue.y, accuracy: 1e-6)
        XCTAssertEqual(recomposed.z, anchorInVenue.z, accuracy: 1e-6)
    }
}
