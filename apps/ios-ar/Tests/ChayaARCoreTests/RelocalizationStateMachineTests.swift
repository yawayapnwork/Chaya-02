import XCTest
@testable import ChayaARCore

/// Pure state-machine tests, no ARKit or device needed. Mirrors ar-relocalization.test.ts; see docs/ar.md.
final class RelocalizationStateMachineTests: XCTestCase {

    typealias SM = RelocalizationStateMachine

    let obs = MarkerObservation(anchorId: UUID(), markerIdentifier: "entrance",
                                observedPose: Pose(x: 0.2, y: 0.1, z: -1.5), timestamp: 10, isTracked: true, estimatedScaleFactor: 1)
    let frameId = UUID()
    func loc(_ at: TimeInterval = 11) -> Localization {
        Localization(deviceToVenue: Pose(x: 3, y: 4, z: 0, qx: 0.5.squareRoot(), qw: 0.5.squareRoot()),
                     anchorIds: [obs.anchorId], residualMeters: nil, coordinateFrameId: frameId, solvedAt: at)
    }

    func reduce(_ s: NavigationState, _ events: NavigationEvent...) -> NavigationState {
        events.reduce(s) { SM.reduce($0, $1) }
    }

    func started() -> NavigationState {
        reduce(NavigationState(), .sessionStarted, .trackingChanged(.normal, at: 1))
    }

    func localized() -> NavigationState {
        var s = reduce(started(), .markersObserved([obs]))
        s = SM.reduce(s, .relocalizationSucceeded(requestId: s.pending!.requestId, loc()))
        XCTAssertEqual(s.phase, .localized)
        return s
    }

    func testLocalizesOnlyThroughAnObservedMarkerAndAServerSolve() {
        var s = SM.reduce(NavigationState(), .sessionStarted)
        XCTAssertEqual(s.phase, .searching)
        XCTAssertFalse(s.canAdvanceRoute)
        XCTAssertEqual(SM.reduce(s, .markersObserved([obs])), s, "no observation counts before ARKit reports normal tracking")

        s = reduce(s, .trackingChanged(.normal, at: 1), .markersObserved([obs]))
        XCTAssertEqual(s.phase, .solving)
        XCTAssertEqual(s.pending?.observations, [obs], "the observation is kept as an observation")
        XCTAssertNil(s.localization, "an observation is not a device-to-venue transform")

        s = SM.reduce(s, .relocalizationSucceeded(requestId: s.pending!.requestId, loc()))
        XCTAssertEqual(s.phase, .localized)
        XCTAssertEqual(s.localization, loc())
        XCTAssertTrue(s.canAdvanceRoute)
        XCTAssertTrue(s.routeVisible)
        XCTAssertEqual(s.trackingStatus, .normal)
    }

    /// Review AR-2's required test: startDetecting -> limited(initializing) -> detection must end localized.
    func testStartupInitializingIsNotALossAndADetectionAfterItLocalizes() {
        var s = reduce(NavigationState(), .sessionStarted, .trackingChanged(.limited(.initializing), at: 0.1))
        XCTAssertEqual(s.phase, .searching)
        XCTAssertEqual(s.trackingStatus, .limited(.initializing))
        s = reduce(s, .trackingChanged(.normal, at: 0.8), .markersObserved([obs]))
        s = SM.reduce(s, .relocalizationSucceeded(requestId: s.pending!.requestId, loc()))
        XCTAssertEqual(s.phase, .localized)
    }

    func testAFailedSolveReturnsToSearchingWithNoTransform() {
        var s = reduce(started(), .markersObserved([obs]))
        s = SM.reduce(s, .relocalizationFailed(requestId: s.pending!.requestId, error: "ANCHOR_NOT_CALIBRATED"))
        XCTAssertEqual(s.phase, .searching)
        XCTAssertNil(s.localization)
        XCTAssertEqual(s.lastError, "ANCHOR_NOT_CALIBRATED")
    }

    func testShortLimitedFreezesProgressButKeepsTheLocalization() {
        var s = SM.reduce(localized(), .trackingChanged(.limited(.excessiveMotion), at: 20))
        XCTAssertEqual(s.phase, .limited)
        XCTAssertEqual(s.trackingStatus, .limited(.excessiveMotion))
        XCTAssertFalse(s.canAdvanceRoute, "no progress while tracking is limited")
        XCTAssertTrue(s.routeVisible, "the route is held in place, frozen")
        s = reduce(s, .tick(at: 22), .trackingChanged(.normal, at: 22.5))
        XCTAssertEqual(s.phase, .localized)
        XCTAssertEqual(s.localization, loc())
        XCTAssertTrue(s.canAdvanceRoute)
    }

    func testLimitedForTooLongIsALoss() {
        var s = reduce(localized(), .trackingChanged(.limited(.insufficientFeatures), at: 20), .tick(at: 22))
        XCTAssertEqual(s.phase, .limited)
        s = SM.reduce(s, .tick(at: 23.5))
        XCTAssertEqual(s.phase, .trackingLost)
        XCTAssertEqual(s.trackingStatus, .lost)
        XCTAssertFalse(s.routeVisible)
    }

    func testRelocalizingNotAvailableAndInterruptionAreLosses() {
        XCTAssertEqual(SM.reduce(localized(), .trackingChanged(.limited(.relocalizing), at: 20)).phase, .trackingLost)
        XCTAssertEqual(SM.reduce(localized(), .trackingChanged(.limited(.relocalizing), at: 20)).trackingStatus, .relocalizing)
        XCTAssertEqual(SM.reduce(localized(), .trackingChanged(.notAvailable, at: 20)).phase, .trackingLost)
        let interrupted = SM.reduce(localized(), .sessionInterrupted)
        XCTAssertEqual(interrupted.phase, .trackingLost)
        XCTAssertEqual(interrupted.lostReason, "the AR session was interrupted")
        XCTAssertEqual(SM.reduce(localized(), .worldAnchorLost).phase, .trackingLost)
    }

    func testLossKeepsTheLastLocalizationOnlyForDisplay() {
        let s = SM.reduce(localized(), .trackingChanged(.notAvailable, at: 20))
        XCTAssertEqual(s.localization, loc())
        XCTAssertFalse(s.canAdvanceRoute)
        XCTAssertFalse(s.routeVisible)
    }

    func testRecoveryNeedsNormalTrackingThenAMarkerThenAServerSolve() {
        var s = SM.reduce(localized(), .trackingChanged(.limited(.relocalizing), at: 20))
        XCTAssertEqual(SM.reduce(s, .markersObserved([obs])), s, "no observation while ARKit is not tracking normally")
        s = SM.reduce(s, .trackingChanged(.normal, at: 21))
        XCTAssertEqual(s.phase, .recovered)
        XCTAssertEqual(s.trackingStatus, .recovered)
        XCTAssertFalse(s.canAdvanceRoute, "ARKit tracking again is not a localization")
        XCTAssertFalse(s.routeVisible)

        s = SM.reduce(s, .markersObserved([obs]))
        XCTAssertEqual(s.phase, .solving)
        let failed = SM.reduce(s, .relocalizationFailed(requestId: s.pending!.requestId, error: "boom"))
        XCTAssertEqual(failed.phase, .recovered, "a failed attempt after a loss waits for another marker")

        let newLoc = loc(99)
        s = SM.reduce(s, .relocalizationSucceeded(requestId: s.pending!.requestId, newLoc))
        XCTAssertEqual(s.phase, .localized)
        XCTAssertEqual(s.localization?.solvedAt, 99, "the new transform replaces the pre-loss one")
        XCTAssertNil(s.lostReason)
    }

    func testRecoveredDropsBackToLostIfTrackingDegradesAgain() {
        let s = reduce(localized(), .trackingChanged(.notAvailable, at: 20), .trackingChanged(.normal, at: 21),
                       .trackingChanged(.limited(.insufficientFeatures), at: 22))
        XCTAssertEqual(s.phase, .trackingLost)
    }

    func testAnAnswerToARequestOvertakenByTrackingLossIsIgnored() {
        var s = reduce(started(), .markersObserved([obs]))
        let stale = s.pending!.requestId
        s = SM.reduce(s, .trackingChanged(.limited(.excessiveMotion), at: 5))
        XCTAssertEqual(s.phase, .searching, "never localized, so there is nothing to call lost")
        XCTAssertNil(s.pending)
        XCTAssertEqual(SM.reduce(s, .relocalizationSucceeded(requestId: stale, loc())), s)

        var lost = reduce(localized(), .trackingChanged(.notAvailable, at: 20), .trackingChanged(.normal, at: 21), .markersObserved([obs]))
        let id = lost.pending!.requestId
        lost = SM.reduce(lost, .sessionInterrupted)
        XCTAssertEqual(lost.phase, .trackingLost)
        XCTAssertEqual(SM.reduce(lost, .relocalizationSucceeded(requestId: id, loc())), lost)
    }

    func testWhileLocalizedFurtherObservationsDoNotRestartLocalization() {
        let s = localized()
        XCTAssertEqual(SM.reduce(s, .markersObserved([obs])), s)
    }

    func testAnEndedOrFailedSessionAcceptsNothing() {
        let ended = SM.reduce(localized(), .sessionEnded)
        XCTAssertEqual(ended.phase, .ended)
        XCTAssertEqual(SM.reduce(ended, .trackingChanged(.normal, at: 1)), ended)
        let failed = SM.reduce(started(), .sessionFailed("camera access denied"))
        XCTAssertEqual(failed.phase, .ended)
        XCTAssertEqual(failed.lastError, "camera access denied")
    }
}
