import XCTest
@testable import ChayaARCore

/// camera -> ARKit world -> canonical venue, world-anchor correction and route progress. Mirrors ar-route.test.ts.
final class VenueFramesTests: XCTestCase {

    let s = 0.5.squareRoot()
    /// A marker flat on the floor at canonical (5, 2, 0), image normal (+Y) along canonical +Z.
    var digital: Pose { Pose(x: 5, y: 2, z: 0, qx: s, qw: s) }
    /// The same marker as ARKit sees it (+Y up): 1.5 m below and 2 m in front of the session origin.
    let observed = Pose(x: 0, y: -1.5, z: -2)

    func assertClose(_ a: Vec3, _ b: Vec3, accuracy: Double = 1e-9, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(a.x, b.x, accuracy: accuracy, file: file, line: line)
        XCTAssertEqual(a.y, b.y, accuracy: accuracy, file: file, line: line)
        XCTAssertEqual(a.z, b.z, accuracy: accuracy, file: file, line: line)
    }

    var transform: Pose { AnchorMath.deviceToVenue(fromAnchorDigitalPose: digital, observedPose: observed) }

    func testTheSolvedTransformMapsTheObservedMarkerOntoItsRegisteredPose() {
        assertClose(VenueFrames.worldPointToVenue(deviceToVenue: transform, Vec3(0, -1.5, -2)), Vec3(5, 2, 0))
        // ARKit -Z (forward) is canonical +Y here: the session origin, 2 m behind the marker and 1.5 m up
        assertClose(VenueFrames.devicePositionInVenue(camera: .identity, deviceToVenue: transform), Vec3(5, 0, 1.5))
        XCTAssertLessThan(VenueFrames.gravityTiltDegrees(transform), 1e-6, "ARKit +Y up becomes canonical +Z up")
    }

    func testACameraFramePointReachesTheVenueThroughTheCameraTransform() {
        // the camera turned 90 degrees left about +Y: its forward (-Z) is ARKit -X, which is canonical -X here
        let camera = Pose(x: 0, y: 0, z: 0, qy: s, qw: s)
        assertClose(VenueFrames.cameraPointToVenue(camera: camera, deviceToVenue: transform, Vec3(0, 0, -1)), Vec3(4, 0, 1.5))
    }

    func testVenueAndWorldPointsRoundTripAndTheRendererPoseAgrees() {
        let t = AnchorMath.deviceToVenue(fromAnchorDigitalPose: digital, observedPose: Pose(x: 0.4, y: -1.2, z: -2, qy: sin(0.3), qw: cos(0.3)))
        let p = Vec3(7.5, 8, 0.2)
        let world = VenueFrames.venuePointToWorld(deviceToVenue: t, p)
        assertClose(VenueFrames.worldPointToVenue(deviceToVenue: t, world), p)
        let placed = AnchorMath.compose(VenueFrames.venueToWorld(deviceToVenue: t), Pose(x: p.x, y: p.y, z: p.z))
        assertClose(Vec3(placed.x, placed.y, placed.z), world)
        // floor-level route points sit on ARKit's floor
        XCTAssertEqual(VenueFrames.venuePointToWorld(deviceToVenue: transform, Vec3(5, 6, 0)).y, -1.5, accuracy: 1e-9)
    }

    func testWorldAnchorCorrection() {
        let unchanged = VenueFrames.anchorCorrectedDeviceToVenue(transform, anchorAtSolve: observed, anchorNow: observed)
        assertClose(VenueFrames.venuePointToWorld(deviceToVenue: unchanged, Vec3(5, 4, 0)),
                    VenueFrames.venuePointToWorld(deviceToVenue: transform, Vec3(5, 4, 0)))

        // ARKit refines its map: the anchor (and the room around it) is now 0.2 m along +X and 5 degrees about +Y
        let half = 5 * Double.pi / 180 / 2
        let correction = Pose(x: 0.2, y: 0, z: 0, qy: sin(half), qw: cos(half))
        let anchorNow = AnchorMath.compose(correction, observed)
        let corrected = VenueFrames.anchorCorrectedDeviceToVenue(transform, anchorAtSolve: observed, anchorNow: anchorNow)
        assertClose(VenueFrames.venuePointToWorld(deviceToVenue: corrected, Vec3(5, 2, 0)), Vec3(anchorNow.x, anchorNow.y, anchorNow.z))
        let before = VenueFrames.venuePointToWorld(deviceToVenue: transform, Vec3(5, 6, 0))
        let moved = AnchorMath.compose(correction, Pose(x: before.x, y: before.y, z: before.z))
        assertClose(VenueFrames.venuePointToWorld(deviceToVenue: corrected, Vec3(5, 6, 0)), Vec3(moved.x, moved.y, moved.z))
        XCTAssertLessThan(VenueFrames.gravityTiltDegrees(corrected), 1e-6)
    }

    func testTheLegOnAFloorStopsAtTheTransition() {
        let f1 = UUID(), f2 = UUID()
        func w(_ f: UUID, _ k: WaypointKind, _ x: Double) -> Waypoint { Waypoint(x: x, y: 0, z: 0, floorId: f, kind: k) }
        let route = RouteResponse(waypoints: [w(f1, .start, 0), w(f1, .transition, 3), w(f2, .waypoint, 3), w(f2, .destination, 6)],
                                  distanceMeters: 6, estimatedDurationSeconds: 5, floorTransitions: [],
                                  accessibilityProfile: "STANDARD", accessibilityConstraintsApplied: [])
        XCTAssertEqual(RouteGeometry.points(RouteGeometry.legOnFloor(route, floorId: f1)), [Vec3(0, 0, 0), Vec3(3, 0, 0)])
        XCTAssertEqual(RouteGeometry.legOnFloor(route, floorId: UUID()), [])
    }

    func testProgressProjectsTheDeviceAndIsFrozenWhenItMayNotAdvance() {
        let route = [Vec3(0, 0, 0), Vec3(10, 0, 0), Vec3(10, 10, 0)]
        let p = RouteGeometry.progress(along: route, device: Vec3(4, 1, 1.5))!
        XCTAssertEqual(p.nextIndex, 1)
        XCTAssertEqual(p.remainingMeters, 16, accuracy: 1e-9)
        XCTAssertEqual(p.offRouteMeters, 1, accuracy: 1e-9)
        XCTAssertEqual(RouteGeometry.advance(p, canAdvance: false, points: route, device: Vec3(10, 5, 1.5)), p)
        XCTAssertEqual(RouteGeometry.advance(p, canAdvance: true, points: route, device: Vec3(10, 5, 1.5))?.nextIndex, 2)
    }
}
