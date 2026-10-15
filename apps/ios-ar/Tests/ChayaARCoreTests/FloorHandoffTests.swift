import XCTest
@testable import ChayaARCore

/// Floor identification, floor handoff, transition re-checks and the multi-floor route request on iOS. Pure: no ARKit.
/// Mirrors floor-transitions.test.ts, ar-navigation.test.ts and ar-relocalization.test.ts (docs/ar.md, "Floor handoff").
/// The routes are hand-built: they test the client's rules, not that a real venue's floors connect.
final class FloorHandoffTests: XCTestCase {

    typealias SM = RelocalizationStateMachine

    let ground = UUID(uuidString: "00000000-0000-0000-0000-00000000000a")!
    let first = UUID(uuidString: "00000000-0000-0000-0000-00000000000b")!
    let second = UUID(uuidString: "00000000-0000-0000-0000-00000000000c")!
    let groundVersion = UUID(), firstVersion = UUID(), secondVersion = UUID()
    let groundFrame = UUID(), firstFrame = UUID(), secondFrame = UUID()
    let lift = UUID(), stairs = UUID()

    func marker(_ floor: UUID?, _ name: String = "m") -> MarkerObservation {
        MarkerObservation(anchorId: UUID(), markerIdentifier: name, observedPose: Pose(x: 0, y: 0, z: -1), timestamp: 1,
                          isTracked: true, estimatedScaleFactor: 1, floorId: floor)
    }

    func loc(_ floor: UUID, _ version: UUID, _ frame: UUID) -> Localization {
        Localization(deviceToVenue: .identity, anchorIds: [], residualMeters: nil, coordinateFrameId: frame, scanVersionId: version,
                     floorId: floor, solvedAt: 1)
    }

    func reduce(_ s: NavigationState, _ events: NavigationEvent...) -> NavigationState {
        events.reduce(s) { SM.reduce($0, $1) }
    }

    func solved(_ s: NavigationState, _ l: Localization) -> NavigationState {
        SM.reduce(s, .relocalizationSucceeded(requestId: s.pending!.requestId, l))
    }

    func localizedOnGround() -> NavigationState {
        let s = reduce(NavigationState(), .sessionStarted(floorId: ground), .trackingChanged(.normal, at: 1), .markersObserved([marker(ground)]))
        return solved(s, loc(ground, groundVersion, groundFrame))
    }

    /// ground --lift--> first --stairs--> second, each floor's leg in its own frame.
    func threeFloorRoute(profile: String = "STANDARD") -> RouteResponse {
        RouteResponse(
            waypoints: [
                Waypoint(x: 0, y: 0, z: 0, floorId: ground, kind: .start),
                Waypoint(x: 10, y: 0, z: 0, floorId: ground, kind: .transition),
                Waypoint(x: -3, y: 7, z: 0, floorId: first, kind: .waypoint),
                Waypoint(x: 4, y: 7, z: 0, floorId: first, kind: .transition),
                Waypoint(x: 50, y: 50, z: 0, floorId: second, kind: .waypoint),
                Waypoint(x: 55, y: 50, z: 0, floorId: second, kind: .destination),
            ],
            distanceMeters: 30, estimatedDurationSeconds: 60,
            floorTransitions: [
                FloorTransition(fromFloorId: ground, toFloorId: first, connectorType: "ELEVATOR", poiId: UUID(), connectionId: lift,
                                toPoiId: UUID(), distanceMeters: nil, durationSeconds: 30),
                FloorTransition(fromFloorId: first, toFloorId: second, connectorType: "STAIRS", poiId: UUID(), connectionId: stairs,
                                toPoiId: UUID(), distanceMeters: 6, durationSeconds: 12),
            ],
            accessibilityProfile: profile, accessibilityConstraintsApplied: [],
            routingSources: [
                RoutingSource(floorId: ground, scanVersionId: groundVersion, coordinateFrameId: groundFrame),
                RoutingSource(floorId: first, scanVersionId: firstVersion, coordinateFrameId: firstFrame),
                RoutingSource(floorId: second, scanVersionId: secondVersion, coordinateFrameId: secondFrame),
            ])
    }

    // MARK: - floor identification

    func testFloorIdentificationConfirmsPendsAndReportsUnexpectedAndAmbiguousFloors() {
        typealias F = FloorIdentification
        XCTAssertEqual(F.identify([], expected: first), .pending)
        let once = F.push([], [first])
        XCTAssertEqual(F.identify(once, expected: first), .pending, "one sighting right after a floor change is not enough")
        XCTAssertEqual(F.identify(once, expected: first, minSightings: 1), .confirmed, "at session start, the floor the user chose")
        XCTAssertEqual(F.identify(F.push(once, [first, first]), expected: first), .confirmed)
        let other = F.push(F.push([], [second]), [second])
        XCTAssertEqual(F.identify(other, expected: first), .unexpected(second))
        XCTAssertEqual(F.identify(F.push([], [second]), expected: first, minSightings: 1), .pending,
                       "one sighting of another floor is not yet evidence of being there")
        XCTAssertEqual(F.identify(F.push(once, [second]), expected: first), .ambiguous([first, second].sorted { $0.uuidString < $1.uuidString }))
        var window: [[UUID]] = []
        for _ in 0..<6 { window = F.push(window, [first]) }
        window = F.push(window, [])
        XCTAssertEqual(window.count, F.window, "only the recent frames that saw a marker count")
    }

    // MARK: - handoff

    func testHandoffHappensOnlyNearTheEndOfALegThatEndsAtATransition() {
        let route = threeFloorRoute()
        XCTAssertEqual(RouteGeometry.floorHandoff(route, floorId: ground, remainingMeters: 1.5),
                       RouteGeometry.Handoff(toFloorId: first, connectorType: "ELEVATOR"))
        XCTAssertNil(RouteGeometry.floorHandoff(route, floorId: ground, remainingMeters: 2.5), "not yet at the lift")
        XCTAssertNil(RouteGeometry.floorHandoff(route, floorId: ground, remainingMeters: nil), "no progress: nothing is known")
        XCTAssertEqual(RouteGeometry.floorHandoff(route, floorId: first, remainingMeters: 0.4)?.toFloorId, second,
                       "a three-floor route hands off again on the intermediate floor")
        XCTAssertEqual(RouteGeometry.floorHandoff(route, floorId: first, remainingMeters: 0.4)?.connectorType, "STAIRS")
        XCTAssertNil(RouteGeometry.floorHandoff(route, floorId: second, remainingMeters: 0), "the last leg ends at the destination")
        XCTAssertNil(RouteGeometry.floorHandoff(route, floorId: UUID(), remainingMeters: 0))
        XCTAssertEqual(RouteGeometry.floors(of: route), [ground, first, second])
    }

    func testEachFloorsLegIsDrawnOnlyWithThatFloorsOwnLocalization() {
        let route = threeFloorRoute()
        guard case .drawable(let leg) = RouteGeometry.leg(of: route, for: loc(first, firstVersion, firstFrame)) else {
            return XCTFail("the first floor's leg is drawable with a first-floor localization in its version and frame")
        }
        XCTAssertEqual(leg.map(\.x), [-3, 4])
        guard case .refused(.versionMismatch, _) = RouteGeometry.leg(of: route, for: loc(first, UUID(), firstFrame)) else {
            return XCTFail("a floor republished since the route was planned: its leg is not drawn")
        }
    }

    // MARK: - state machine across floors

    func testTwoConnectedFloorsTheNextFloorLocalizesOnlyAfterItIsIdentified() {
        var s = SM.reduce(localizedOnGround(), .floorHandoff(toFloorId: first))
        XCTAssertEqual(s.phase, .floorTransition)
        XCTAssertEqual(s.floorId, first)
        XCTAssertNil(s.localization, "the ground floor's transform means nothing on the first floor")
        XCTAssertFalse(s.canAdvanceRoute)
        XCTAssertFalse(s.routeVisible)
        XCTAssertNil(s.pinnedScanVersionId, "the first floor has no version pinned yet")

        s = SM.reduce(s, .markersObserved([marker(first)]))
        XCTAssertEqual(s.phase, .floorTransition, "one sighting after a floor change is not enough")
        XCTAssertEqual(s.floorCheck, .pending)
        s = SM.reduce(s, .markersObserved([marker(first)]))
        XCTAssertEqual(s.phase, .solving)
        XCTAssertNil(s.floorCheck)
        XCTAssertEqual(s.pending?.resumeTo, .floorTransition)

        s = solved(s, loc(first, firstVersion, firstFrame))
        XCTAssertEqual(s.phase, .localized)
        XCTAssertEqual(s.localization?.floorId, first)
        XCTAssertEqual(s.pinnedScanVersionId, firstVersion)
        XCTAssertEqual(s.pinnedScanVersions, [ground: groundVersion, first: firstVersion], "each floor keeps its own pin")
    }

    func testMarkersOfTwoFloorsAtOnceLocalizeNothing() {
        var s = SM.reduce(localizedOnGround(), .floorHandoff(toFloorId: first))
        s = reduce(s, .markersObserved([marker(first), marker(ground)]), .markersObserved([marker(first)]),
                   .markersObserved([marker(first)]))
        XCTAssertEqual(s.phase, .floorTransition, "a ground-floor marker seen across the atrium keeps the floor uncertain")
        guard case .ambiguous(let floors)? = s.floorCheck else { return XCTFail("expected ambiguous, got \(String(describing: s.floorCheck))") }
        XCTAssertEqual(Set(floors), [ground, first])
        XCTAssertNil(s.pending)
    }

    func testAnUnexpectedFloorIsReportedAndOnlyUsedOnceTheUserAcceptsIt() {
        var s = SM.reduce(localizedOnGround(), .floorHandoff(toFloorId: first))
        s = reduce(s, .markersObserved([marker(second)]), .markersObserved([marker(second)]))
        XCTAssertEqual(s.floorCheck, .unexpected(second), "got off the lift on the wrong floor")
        XCTAssertEqual(s.phase, .floorTransition)
        XCTAssertNil(s.pending, "another floor's markers are never sent as this floor's")

        s = SM.reduce(s, .acceptDetectedFloor)
        XCTAssertEqual(s.floorId, second)
        XCTAssertNil(s.floorCheck)
        s = reduce(s, .markersObserved([marker(second)]), .markersObserved([marker(second)]))
        XCTAssertEqual(s.phase, .solving)
        XCTAssertEqual(solved(s, loc(second, secondVersion, secondFrame)).localization?.floorId, second)
    }

    func testAcceptingIsIgnoredWithoutAnUnexpectedFloor() {
        let s = SM.reduce(localizedOnGround(), .floorHandoff(toFloorId: first))
        XCTAssertEqual(SM.reduce(s, .acceptDetectedFloor), s)
        XCTAssertEqual(SM.reduce(localizedOnGround(), .acceptDetectedFloor), localizedOnGround())
    }

    func testAnUnexpectedFloorAtSessionStartIsAlsoReported() {
        var s = reduce(NavigationState(), .sessionStarted(floorId: ground), .trackingChanged(.normal, at: 1))
        s = reduce(s, .markersObserved([marker(first)]), .markersObserved([marker(first)]))
        XCTAssertEqual(s.phase, .searching)
        XCTAssertEqual(s.floorCheck, .unexpected(first), "the user picked the wrong start floor")
    }

    func testHandoffNeverLeavesFromLimitedNorToTheSameFloor() {
        let limited = SM.reduce(localizedOnGround(), .trackingChanged(.limited(.excessiveMotion), at: 2))
        XCTAssertEqual(SM.reduce(limited, .floorHandoff(toFloorId: first)), limited, "progress is frozen while limited")
        XCTAssertEqual(SM.reduce(localizedOnGround(), .floorHandoff(toFloorId: ground)), localizedOnGround())
    }

    func testAnAnswerForThePreviousFloorArrivingAfterTheHandoffIsRefused() {
        var s = SM.reduce(localizedOnGround(), .floorHandoff(toFloorId: first))
        s = reduce(s, .markersObserved([marker(first)]), .markersObserved([marker(first)]))
        s = solved(s, loc(ground, groundVersion, groundFrame))
        XCTAssertEqual(s.phase, .floorTransition)
        XCTAssertNil(s.localization)
        XCTAssertTrue(s.lastError?.hasPrefix("FLOOR_MISMATCH") ?? false)
    }

    func testAFailedSolveOrTrackingLossDuringTheTransitionReturnsToTheTransition() {
        var s = SM.reduce(localizedOnGround(), .floorHandoff(toFloorId: first))
        s = reduce(s, .markersObserved([marker(first)]), .markersObserved([marker(first)]))
        let solving = s
        s = SM.reduce(s, .relocalizationFailed(requestId: s.pending!.requestId, error: "NETWORK"))
        XCTAssertEqual(s.phase, .floorTransition)
        XCTAssertEqual(s.floorId, first)
        XCTAssertEqual(SM.reduce(solving, .trackingChanged(.limited(.excessiveMotion), at: 3)).phase, .floorTransition)
    }

    func testARecoveredSessionRelocalizesOnlyFromItsOwnFloorsMarkers() {
        var s = reduce(localizedOnGround(), .trackingChanged(.notAvailable, at: 2), .trackingChanged(.normal, at: 3))
        XCTAssertEqual(s.phase, .recovered)
        XCTAssertEqual(SM.reduce(s, .markersObserved([marker(first)])), s, "another floor's marker cannot restore this floor's transform")
        let own = marker(ground)
        s = SM.reduce(s, .markersObserved([marker(first), own]))
        XCTAssertEqual(s.phase, .solving)
        XCTAssertEqual(s.pending?.observations, [own])
    }

    // MARK: - transitions re-checked during a session

    func connection(_ id: UUID, from: UUID, to: UUID, type: String, status: FloorConnection.Status = .inService,
                    bidirectional: Bool = true, verified: Bool = true) -> FloorConnection {
        FloorConnection(id: id, connectorType: type, fromFloorId: from, toFloorId: to, bidirectional: bidirectional, status: status,
                        accessibilityVerifiedAt: verified ? Date(timeIntervalSince1970: 1_790_000_000) : nil)
    }

    func testARouteStaysUsableWhileItsConnectionsAreUnchanged() {
        let ok = FloorTransitions.stillUsable(threeFloorRoute(), connections: [
            connection(lift, from: first, to: ground, type: "ELEVATOR"), // registered the other way round, both directions
            connection(stairs, from: first, to: second, type: "STAIRS", verified: false),
        ])
        XCTAssertEqual(ok, FloorTransitions.Check(usable: true, problems: [], connectionIds: []))
    }

    func testAClosedRemovedReversedOrUnverifiedConnectionMakesTheRouteBePlannedAgain() {
        let closed = FloorTransitions.stillUsable(threeFloorRoute(), connections: [
            connection(lift, from: ground, to: first, type: "ELEVATOR", status: .outOfService),
            connection(stairs, from: first, to: second, type: "STAIRS"),
        ])
        XCTAssertFalse(closed.usable)
        XCTAssertEqual(closed.problems, ["the elevator on the route is out of service"])
        XCTAssertEqual(closed.connectionIds, [lift])

        let removed = FloorTransitions.stillUsable(threeFloorRoute(), connections: [connection(lift, from: ground, to: first, type: "ELEVATOR")])
        XCTAssertEqual(removed.problems, ["the stairs on the route was removed"])
        XCTAssertEqual(removed.connectionIds, [stairs])

        let oneWay = FloorTransitions.stillUsable(threeFloorRoute(), connections: [
            connection(lift, from: first, to: ground, type: "ELEVATOR", bidirectional: false),
            connection(stairs, from: first, to: second, type: "STAIRS"),
        ])
        XCTAssertEqual(oneWay.problems, ["the elevator on the route no longer runs in this direction"])

        let stepFree = FloorTransitions.stillUsable(threeFloorRoute(profile: "STEP_FREE"), connections: [
            connection(lift, from: ground, to: first, type: "ELEVATOR", verified: false),
            connection(stairs, from: first, to: second, type: "STAIRS", verified: false),
        ])
        XCTAssertEqual(stepFree.connectionIds, [lift, stairs], "a step-free route needs every connection still verified")
    }

    // MARK: - the route request

    func testOnlyASameFloorDestinationIsRoutedOnTheLocalizationsVersion() {
        let here = loc(ground, groundVersion, groundFrame)
        XCTAssertEqual(RouteGeometry.routeScanVersion(destinationFloorId: ground, localization: here), groundVersion)
        XCTAssertNil(RouteGeometry.routeScanVersion(destinationFloorId: first, localization: here),
                     "a version-pinned route stays on its floor (VERSION_WRONG_FLOOR); another floor is routed on what it publishes now")
        XCTAssertNil(RouteGeometry.routeScanVersion(destinationFloorId: nil, localization: here))
    }

    func testFloorConnectionsDecodeFromTheServersView() throws {
        let json = """
        [{"id":"0b8f9d1a-1111-4b4b-8888-0123456789ab","connectorType":"ELEVATOR",
          "fromFloorId":"0b8f9d1a-4444-4b4b-8888-0123456789ab","fromPoiId":"0b8f9d1a-2222-4b4b-8888-0123456789ab",
          "toFloorId":"0b8f9d1a-5555-4b4b-8888-0123456789ab","toPoiId":"0b8f9d1a-3333-4b4b-8888-0123456789ab",
          "bidirectional":true,"lengthM":null,"maxSlopeDeg":null,"minClearanceM":0.95,"status":"OUT_OF_SERVICE",
          "accessibilityVerifiedAt":"2026-10-14T09:00:00.5Z","accessibilityVerifiedBy":"someone"},
         {"id":"0b8f9d1a-6666-4b4b-8888-0123456789ab","connectorType":"STAIRS",
          "fromFloorId":"0b8f9d1a-4444-4b4b-8888-0123456789ab","fromPoiId":"0b8f9d1a-2222-4b4b-8888-0123456789ab",
          "toFloorId":"0b8f9d1a-5555-4b4b-8888-0123456789ab","toPoiId":"0b8f9d1a-3333-4b4b-8888-0123456789ab",
          "bidirectional":false,"lengthM":6.5,"maxSlopeDeg":null,"minClearanceM":null,"status":"IN_SERVICE",
          "accessibilityVerifiedAt":null,"accessibilityVerifiedBy":null}]
        """
        let cs = try JSONDecoder.chayaAR.decode([FloorConnection].self, from: Data(json.utf8))
        XCTAssertEqual(cs.map(\.status), [.outOfService, .inService])
        XCTAssertNotNil(cs[0].accessibilityVerifiedAt)
        XCTAssertNil(cs[1].accessibilityVerifiedAt)
        XCTAssertFalse(cs[1].bidirectional)
    }

    func testARouteRequestToAnotherFloorCarriesNoScanVersion() throws {
        let body = try APIClient.routeBody(venueId: UUID(), floorId: ground, start: Vec3(1, 2, 1.5), destinationPoiId: UUID(),
                                           scanVersionId: nil, accessibility: "STEP_FREE")
        let json = try JSONSerialization.jsonObject(with: body) as! [String: Any]
        XCTAssertNil(json["scanVersionId"])
        XCTAssertEqual(json["accessibility"] as? String, "STEP_FREE")
    }
}
