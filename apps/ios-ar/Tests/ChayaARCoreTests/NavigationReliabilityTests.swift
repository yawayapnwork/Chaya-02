import XCTest
@testable import ChayaARCore
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Failure states, the limited-tracking recovery check, and the relocalization response's per-anchor residuals. Pure; no
/// ARKit or device. Mirrors ar-errors.ts and the LIMITED cases of ar-relocalization.test.ts.
final class NavigationReliabilityTests: XCTestCase {

    typealias SM = RelocalizationStateMachine

    let floorId = UUID()
    let obs = MarkerObservation(anchorId: UUID(), markerIdentifier: "entrance", observedPose: Pose(x: 0.2, y: 0.1, z: -1.5),
                                timestamp: 10, isTracked: true, estimatedScaleFactor: 1)

    func localized() -> NavigationState {
        var s = SM.reduce(SM.reduce(NavigationState(), .sessionStarted(floorId: floorId)), .trackingChanged(.normal, at: 1))
        s = SM.reduce(s, .markersObserved([obs]))
        let loc = Localization(deviceToVenue: Pose(x: 3, y: 4, z: 0, qx: 0.5.squareRoot(), qw: 0.5.squareRoot()), anchorIds: [obs.anchorId],
                               residualMeters: nil, coordinateFrameId: UUID(), scanVersionId: UUID(), floorId: floorId, solvedAt: 11)
        s = SM.reduce(s, .relocalizationSucceeded(requestId: s.pending!.requestId, loc))
        XCTAssertEqual(s.phase, .localized)
        return s
    }

    // MARK: - failures

    func testAnExpiredViewerLinkStopsRetrying() {
        let f = ArFailure.classify(APIError(status: 401, code: "HTTP_401", detail: "invalid or expired viewer token"))
        XCTAssertEqual(f.kind, .sessionExpired)
        XCTAssertFalse(f.retryable)
    }

    func testANetworkFailureIsRetriedAndSaysSo() {
        let f = ArFailure.classify(URLError(.notConnectedToInternet))
        XCTAssertEqual(f.kind, .networkUnavailable)
        XCTAssertTrue(f.retryable)
    }

    func testAnUnusableAnchorIsNamedSoTheSessionCanDropIt() {
        let id = UUID()
        let f = ArFailure.classify(APIError(status: 409, code: "ANCHOR_FRAME_STALE",
                                            detail: "anchor \(id.apiString) was placed in coordinate frame x, not the floor's current frame y"))
        XCTAssertEqual(f.kind, .anchorUnavailable)
        XCTAssertEqual(f.anchorId, id)
        XCTAssertFalse(f.retryable)
        XCTAssertEqual(ArFailure.classify(APIError(status: 404, code: "NOT_FOUND", detail: "anchor not found")).kind, .anchorUnavailable)
    }

    func testInconsistentAnchorsAndARepublishedFloorAreTheirOwnStates() {
        XCTAssertEqual(ArFailure.classify(APIError(status: 409, code: "RELOCALIZATION_INCONSISTENT", detail: "disagree")).kind,
                       .anchorsInconsistent)
        XCTAssertEqual(ArFailure.classify(APIError(status: 409, code: "VERSION_MISMATCH", detail: "republished")).kind, .versionChanged)
        let server = ArFailure.classify(APIError(status: 503, code: "HTTP_503", detail: "busy"))
        XCTAssertEqual(server.kind, .refused)
        XCTAssertTrue(server.retryable)
    }

    // MARK: - limited tracking: freeze, then resume only after a validated recovery

    func testALimitedSpellHoldsTheRouteFrozenAndAValidatedRecoveryResumes() {
        var s = SM.reduce(localized(), .trackingChanged(.limited(.excessiveMotion), at: 20))
        XCTAssertEqual(s.phase, .limited)
        XCTAssertTrue(s.routeVisible, "the last valid overlay is held")
        XCTAssertFalse(s.canAdvanceRoute, "but frozen")
        let before = Pose(x: 1, y: 0, z: -2)
        XCTAssertNil(VenueFrames.recoveryProblem(anchorAtLimitedStart: before, anchorNow: Pose(x: 1.05, y: 0, z: -2)))
        s = SM.reduce(s, .trackingChanged(.normal, at: 21))
        XCTAssertEqual(s.phase, .localized)
    }

    func testARecoveryInWhichTheWorldAnchorJumpedOrVanishedIsALoss() {
        let before = Pose(x: 1, y: 0, z: -2)
        let jumped = VenueFrames.recoveryProblem(anchorAtLimitedStart: before, anchorNow: Pose(x: 1.5, y: 0, z: -2))
        XCTAssertNotNil(jumped)
        XCTAssertTrue(jumped!.contains("re-anchored"))
        XCTAssertNotNil(VenueFrames.recoveryProblem(anchorAtLimitedStart: before, anchorNow: nil))
        XCTAssertNotNil(VenueFrames.recoveryProblem(anchorAtLimitedStart: nil, anchorNow: before))

        var s = SM.reduce(SM.reduce(localized(), .trackingChanged(.limited(.insufficientFeatures), at: 20)), .trackingChanged(.normal, at: 21))
        s = SM.reduce(s, .recoveryRejected(jumped!))
        XCTAssertEqual(s.phase, .trackingLost)
        XCTAssertFalse(s.routeVisible)
        XCTAssertEqual(s.lostReason, jumped)
        // As for any loss, normal tracking alone does not bring the route back.
        s = SM.reduce(s, .trackingChanged(.normal, at: 22))
        XCTAssertEqual(s.phase, .recovered)
        XCTAssertFalse(s.routeVisible)
    }

    func testRecoveryRejectedOutsideALocalizationIsIgnored() {
        let searching = SM.reduce(SM.reduce(NavigationState(), .sessionStarted(floorId: floorId)), .trackingChanged(.normal, at: 1))
        XCTAssertEqual(SM.reduce(searching, .recoveryRejected("x")), searching)
    }

    // MARK: - wire format

    func testPerAnchorResidualsAndRejectedAnchorsDecode() throws {
        let json = """
        {"deviceToVenueTransform":{"x":1,"y":2,"z":0,"qx":0.7071,"qy":0,"qz":0,"qw":0.7071},"residualMeters":0.02,"residualDegrees":0.4,
         "anchorsUsed":2,"gravityTiltDegrees":0.4,"deviceFrameConvention":"DEVICE_Y_UP_RIGHT_HANDED_METRES",
         "coordinateFrameId":"6f1c2c1e-1111-4a4a-9999-0123456789ab","scanVersionId":"6f1c2c1e-2222-4a4a-9999-0123456789ab",
         "anchorResiduals":[{"anchorId":"6f1c2c1e-3333-4a4a-9999-0123456789ab","translationMeters":0.02,"rotationDegrees":0.4,"used":true},
                            {"anchorId":"6f1c2c1e-4444-4a4a-9999-0123456789ab","translationMeters":1.1,"rotationDegrees":2,"used":false}],
         "rejectedAnchorIds":["6f1c2c1e-4444-4a4a-9999-0123456789ab"]}
        """
        let r = try JSONDecoder.chayaAR.decode(RelocalizationResponse.self, from: Data(json.utf8))
        XCTAssertEqual(r.rejectedAnchorIds, [UUID(uuidString: "6f1c2c1e-4444-4a4a-9999-0123456789ab")!])
        XCTAssertEqual(r.anchorResiduals?.count, 2)
        XCTAssertEqual(r.anchorResiduals?.last?.used, false)
        XCTAssertEqual(r.residualDegrees, 0.4)
    }

    // MARK: - invalid transforms, relocalization timeout, drift, and route/POI failure classification

    func testInvalidTransformsAreRefusedAndNeverLocalize() {
        var s = SM.reduce(SM.reduce(NavigationState(), .sessionStarted(floorId: floorId)), .trackingChanged(.normal, at: 1))
        s = SM.reduce(s, .markersObserved([obs]))
        let badTilt = Localization(deviceToVenue: .identity, anchorIds: [obs.anchorId], residualMeters: nil,
                                   coordinateFrameId: UUID(), scanVersionId: UUID(), floorId: floorId, solvedAt: 11)
        let afterTilt = SM.reduce(s, .relocalizationSucceeded(requestId: s.pending!.requestId, badTilt))
        XCTAssertEqual(afterTilt.phase, .searching)
        XCTAssertNil(afterTilt.localization)
        XCTAssertTrue(afterTilt.lastError?.hasPrefix("INVALID_TRANSFORM") ?? false)

        let badNan = Localization(deviceToVenue: Pose(x: .nan, y: 0, z: 0, qx: 0.5.squareRoot(), qw: 0.5.squareRoot()),
                                  anchorIds: [obs.anchorId], residualMeters: nil,
                                  coordinateFrameId: UUID(), scanVersionId: UUID(), floorId: floorId, solvedAt: 11)
        let afterNan = SM.reduce(s, .relocalizationSucceeded(requestId: s.pending!.requestId, badNan))
        XCTAssertEqual(afterNan.phase, .searching)
        XCTAssertTrue(afterNan.lastError?.hasPrefix("INVALID_TRANSFORM") ?? false)
    }

    func testRelocalizationTimeoutCancelsHungRequestAndRestoresPreviousPhase() {
        var s = SM.reduce(SM.reduce(NavigationState(), .sessionStarted(floorId: floorId)), .trackingChanged(.normal, at: 1))
        s = SM.reduce(s, .markersObserved([obs]))
        XCTAssertEqual(s.phase, .solving)
        XCTAssertEqual(SM.reduce(s, .tick(at: obs.timestamp + SM.maxSolvingSeconds - 0.5)).phase, .solving)
        let timedOut = SM.reduce(s, .tick(at: obs.timestamp + SM.maxSolvingSeconds + 0.1))
        XCTAssertEqual(timedOut.phase, .searching)
        XCTAssertNil(timedOut.pending)
        XCTAssertTrue(timedOut.lastError?.hasPrefix("RELOCALIZATION_TIMEOUT") ?? false)
    }

    func testDriftDetectionRefreshAndBlendingWhileLocalized() {
        let s = localized()
        let d2v = s.localization!.deviceToVenue
        let digital = AnchorMath.compose(d2v, obs.observedPose)
        XCTAssertFalse(VenueFrames.driftExceeded(VenueFrames.markerDrift(deviceToVenue: d2v, observedPose: obs.observedPose, digitalPose: digital)))
        let shifted = Pose(x: digital.x + 0.5, y: digital.y, z: digital.z, qx: digital.qx, qy: digital.qy, qz: digital.qz, qw: digital.qw)
        XCTAssertTrue(VenueFrames.driftExceeded(VenueFrames.markerDrift(deviceToVenue: d2v, observedPose: obs.observedPose, digitalPose: shifted)))
        let drifted = SM.reduce(s, .driftExceeded("entrance drifted 0.50 m"))
        XCTAssertEqual(drifted.phase, .trackingLost)
        XCTAssertFalse(drifted.routeVisible)

        // Refresh after 5 s while localized stays localized and blends
        let laterObs = MarkerObservation(anchorId: obs.anchorId, markerIdentifier: "entrance", observedPose: obs.observedPose,
                                         timestamp: 20, isTracked: true, estimatedScaleFactor: 1)
        let refreshing = SM.reduce(s, .markersObserved([laterObs]))
        XCTAssertEqual(refreshing.phase, .localized)
        XCTAssertNotNil(refreshing.pending)
        let blended = VenueFrames.blendedTransform(from: d2v, startedAt: 20, target: Pose(x: 5, y: 4, z: 0, qx: d2v.qx, qw: d2v.qw), now: 20.3)
        XCTAssertEqual(blended.x, 4, accuracy: 1e-6)
    }

    func testDeletedDestinationAndUnreachableRoutesHaveActionableClassifications() {
        let deleted = ArFailure.classify(APIError(status: 404, code: "POI_NOT_FOUND", detail: "destination POI not found"))
        XCTAssertEqual(deleted.kind, .refused)
        XCTAssertFalse(deleted.retryable)
        XCTAssertTrue(deleted.action.contains("no longer exists"))

        let noRoute = ArFailure.classify(APIError(status: 409, code: "NO_ACCESSIBLE_ROUTE", detail: "stairs only"))
        XCTAssertEqual(noRoute.kind, .refused)
        XCTAssertFalse(noRoute.retryable)
        XCTAssertTrue(noRoute.action.contains("step-free"))
    }
}
