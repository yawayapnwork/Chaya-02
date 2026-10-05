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

    func testALegIsDrawnOnlyWhenRoutedInTheLocalizationsScanVersionAndFrame() {
        let floor = UUID(), other = UUID(), version = UUID(), frame = UUID()
        let localization = Localization(deviceToVenue: transform, anchorIds: [UUID()], residualMeters: nil, coordinateFrameId: frame,
                                        scanVersionId: version, floorId: floor, solvedAt: 1)
        func route(_ sources: [RoutingSource]?) -> RouteResponse {
            RouteResponse(waypoints: [Waypoint(x: 5, y: 4, z: 0, floorId: floor, kind: .start),
                                      Waypoint(x: 5, y: 8, z: 0, floorId: floor, kind: .destination)],
                          distanceMeters: 4, estimatedDurationSeconds: 3, floorTransitions: [], accessibilityProfile: "STANDARD",
                          accessibilityConstraintsApplied: [], routingSources: sources)
        }
        func problem(_ check: RouteGeometry.LegCheck) -> RouteGeometry.LegProblem? {
            if case .refused(let p, _) = check { return p }
            return nil
        }
        let ok = RouteGeometry.leg(of: route([RoutingSource(floorId: floor, scanVersionId: version, coordinateFrameId: frame)]), for: localization)
        XCTAssertEqual(ok, .drawable(route(nil).waypoints))
        XCTAssertEqual(problem(RouteGeometry.leg(of: route(nil), for: localization)), .routeSourceMissing)
        XCTAssertEqual(problem(RouteGeometry.leg(of: route([RoutingSource(floorId: other, scanVersionId: version, coordinateFrameId: frame)]),
                                                 for: localization)), .routeSourceMissing)
        XCTAssertEqual(problem(RouteGeometry.leg(of: route([RoutingSource(floorId: floor, scanVersionId: UUID(), coordinateFrameId: frame)]),
                                                 for: localization)), .versionMismatch, "a stale (or newer) version's leg is never drawn")
        XCTAssertEqual(problem(RouteGeometry.leg(of: route([RoutingSource(floorId: floor, scanVersionId: nil, coordinateFrameId: frame)]),
                                                 for: localization)), .versionMismatch)
        XCTAssertEqual(problem(RouteGeometry.leg(of: route([RoutingSource(floorId: floor, scanVersionId: version, coordinateFrameId: UUID())]),
                                                 for: localization)), .frameMismatch)
        var elsewhere = localization
        elsewhere.floorId = other
        XCTAssertEqual(problem(RouteGeometry.leg(of: route([RoutingSource(floorId: other, scanVersionId: version, coordinateFrameId: frame)]),
                                                 for: elsewhere)), .noLegOnFloor)
    }

    /// The explicit canonical (+Z up) <-> ARKit (+Y up) boundary for a marker on a wall (docs/ar.md "Marker pose
    /// convention": facing canonical -Y, upright, 180 degrees about X), seen straight ahead by ARKit 2 m away.
    func testCanonicalUpIsARKitUpAndCanonicalAxesLandWhereTheWallMarkerSays() {
        let wall = Pose(x: 3, y: 10, z: 1.5, qx: 1, qy: 0, qz: 0, qw: 0)
        // ARKit: the image faces the camera (+Y out of the face = ARKit +Z toward the viewer), its right is ARKit +X and its
        // bottom edge is ARKit -Y: that is +90 degrees about X.
        let seen = Pose(x: 0, y: 0, z: -2, qx: s, qw: s)
        let t = AnchorMath.deviceToVenue(fromAnchorDigitalPose: wall, observedPose: seen)
        XCTAssertLessThan(VenueFrames.gravityTiltDegrees(t), 1e-6)
        assertClose(VenueFrames.worldPointToVenue(deviceToVenue: t, Vec3(0, 0, -2)), Vec3(3, 10, 1.5))
        // the session origin is 2 m in front of the wall: canonical -Y of the marker, at its height
        assertClose(VenueFrames.devicePositionInVenue(camera: .identity, deviceToVenue: t), Vec3(3, 8, 1.5))
        // one metre up in ARKit is one metre up in canonical
        assertClose(VenueFrames.worldPointToVenue(deviceToVenue: t, Vec3(0, 1, 0)), Vec3(3, 8, 2.5))
        // and back: a canonical point 1 m above the floor below the marker is 0.5 m below the marker in ARKit
        assertClose(VenueFrames.venuePointToWorld(deviceToVenue: t, Vec3(3, 10, 1)), Vec3(0, -0.5, -2))
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
