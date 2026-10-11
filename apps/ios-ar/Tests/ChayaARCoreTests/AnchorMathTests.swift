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

    // MARK: - multi-anchor solve: the same layout and cases as CoordinateTransformTest.java and ar-anchor-math.test.ts
    // TRUE maps device (+Y up) -> venue (+Z up): +90 deg about X, 30 deg of heading about venue +Z, then (12, -4, 0).

    static let limitM = 0.3
    static let limitDeg = 8.0
    static let axes = Pose(x: 0, y: 0, z: 0, qx: 0.5.squareRoot(), qy: 0, qz: 0, qw: 0.5.squareRoot())
    static let heading = Pose(x: 0, y: 0, z: 0, qx: 0, qy: 0, qz: sin(Double.pi / 12), qw: cos(Double.pi / 12))
    static let truth = AnchorMath.compose(Pose(x: 12, y: -4, z: 0), AnchorMath.compose(heading, axes))
    static let markers = [
        Pose(x: 10, y: 0, z: 1.5, qx: 1, qy: 0, qz: 0, qw: 0),
        Pose(x: 16, y: 0, z: 1.5, qx: 1, qy: 0, qz: 0, qw: 0),
        Pose(x: 13, y: 7, z: 1.2, qx: 0, qy: 1, qz: 0, qw: 0),
    ]

    static func observed(_ digital: Pose, _ headingErrorDeg: Double) -> Pose {
        let exact = AnchorMath.compose(AnchorMath.invert(truth), digital)
        let h = headingErrorDeg * Double.pi / 360
        let r = AnchorMath.compose(Pose(x: 0, y: 0, z: 0, qx: 0, qy: sin(h), qz: 0, qw: cos(h)),
                                   Pose(x: 0, y: 0, z: 0, qx: exact.qx, qy: exact.qy, qz: exact.qz, qw: exact.qw))
        return Pose(x: exact.x, y: exact.y, z: exact.z, qx: r.qx, qy: r.qy, qz: r.qz, qw: r.qw)
    }

    static func shifted(_ p: Pose, _ dx: Double, _ dy: Double, _ dz: Double) -> Pose {
        Pose(x: p.x + dx, y: p.y + dy, z: p.z + dz, qx: p.qx, qy: p.qy, qz: p.qz, qw: p.qw)
    }

    static func map(_ t: Pose, _ x: Double, _ y: Double, _ z: Double) -> Pose { AnchorMath.compose(t, Pose(x: x, y: y, z: z)) }

    static func dist(_ a: Pose, _ b: Pose) -> Double { ((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z)).squareRoot() }

    func testSolveOfAgreeingAnchorsRecoversTheTrueTransformWithZeroResidual() {
        let s = AnchorMath.solve(Self.markers.map { .init(digitalPose: $0, observedPose: Self.observed($0, 0)) },
                                 maxResidualMeters: Self.limitM, maxResidualDegrees: Self.limitDeg)
        XCTAssertTrue(s.consistent)
        XCTAssertEqual(s.maxResidualMeters, 0, accuracy: 1e-9)
        XCTAssertLessThan(Self.dist(Self.map(s.transform, 1, 1.6, -2), Self.map(Self.truth, 1, 1.6, -2)), 1e-9)
    }

    func testSolveReportsTheMeasuredResidualPerAnchor() {
        let s = AnchorMath.solve([
            .init(digitalPose: Self.markers[0], observedPose: Self.observed(Self.markers[0], 0)),
            .init(digitalPose: Self.shifted(Self.markers[1], 0.2, 0, 0), observedPose: Self.observed(Self.markers[1], 0)),
        ], maxResidualMeters: Self.limitM, maxResidualDegrees: Self.limitDeg)
        XCTAssertTrue(s.consistent)
        for r in s.residuals { XCTAssertEqual(r.translationMeters, 0.1, accuracy: 1e-9) }
    }

    func testSolveDoesNotDependOnWhereTheTrackingSessionStarted() {
        let noise = [1.0, -1.0, 0.5]
        let near = Self.markers.enumerated().map { AnchorMath.AnchorPair(digitalPose: $0.element, observedPose: Self.observed($0.element, noise[$0.offset])) }
        let far = near.map { AnchorMath.AnchorPair(digitalPose: $0.digitalPose, observedPose: Self.shifted($0.observedPose, 25, 0, -25)) }
        let a = AnchorMath.solve(near, maxResidualMeters: Self.limitM, maxResidualDegrees: Self.limitDeg)
        let b = AnchorMath.solve(far, maxResidualMeters: Self.limitM, maxResidualDegrees: Self.limitDeg)
        XCTAssertLessThan(Self.dist(Self.map(a.transform, 1, 1.6, -3), Self.map(b.transform, 26, 1.6, -28)), 1e-9)
        for (i, r) in a.residuals.enumerated() { XCTAssertEqual(b.residuals[i].translationMeters, r.translationMeters, accuracy: 1e-9) }
        XCTAssertLessThan(b.maxResidualMeters, 0.09)
    }

    func testSolveSetsAsideAMovedAnchorAmongThree() {
        let s = AnchorMath.solve([
            .init(digitalPose: Self.markers[0], observedPose: Self.observed(Self.markers[0], 0)),
            .init(digitalPose: Self.markers[1], observedPose: Self.observed(Self.markers[1], 0)),
            .init(digitalPose: Self.markers[2], observedPose: Self.observed(Self.shifted(Self.markers[2], 1.2, 0, 0), 0)),
        ], maxResidualMeters: Self.limitM, maxResidualDegrees: Self.limitDeg)
        XCTAssertTrue(s.consistent)
        XCTAssertEqual(s.rejected, [2])
        XCTAssertEqual(s.residuals[2].translationMeters, 1.2, accuracy: 1e-6)
        XCTAssertLessThan(Self.dist(Self.map(s.transform, 0, 1.5, 0), Self.map(Self.truth, 0, 1.5, 0)), 1e-9)
    }

    func testSolveSetsAsideAMisidentifiedAnchorWithTheWrongOrientation() {
        let turned = AnchorMath.compose(Self.markers[0], Pose(x: 0, y: 0, z: 0, qx: 0, qy: 0.5.squareRoot(), qz: 0, qw: 0.5.squareRoot()))
        let s = AnchorMath.solve([
            .init(digitalPose: Self.markers[0], observedPose: Self.observed(turned, 0)),
            .init(digitalPose: Self.markers[1], observedPose: Self.observed(Self.markers[1], 0)),
            .init(digitalPose: Self.markers[2], observedPose: Self.observed(Self.markers[2], 0)),
        ], maxResidualMeters: Self.limitM, maxResidualDegrees: Self.limitDeg)
        XCTAssertEqual(s.rejected, [0])
        XCTAssertGreaterThan(s.residuals[0].rotationDegrees, 80)
    }

    func testSolveReturnsTwoDisagreeingAnchorsAsInconsistent() {
        let s = AnchorMath.solve([
            .init(digitalPose: Self.markers[0], observedPose: Self.observed(Self.markers[0], 0)),
            .init(digitalPose: Self.markers[1], observedPose: Self.observed(Self.shifted(Self.markers[1], 1, 0, 0), 0)),
        ], maxResidualMeters: Self.limitM, maxResidualDegrees: Self.limitDeg)
        XCTAssertFalse(s.consistent)
        XCTAssertEqual(s.maxResidualMeters, 0.5, accuracy: 1e-6)
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
