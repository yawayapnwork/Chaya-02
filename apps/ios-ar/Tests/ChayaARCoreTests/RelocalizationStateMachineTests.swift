import XCTest
@testable import ChayaARCore

/// Pure state-machine tests, no ARKit/device needed. Mirrors ar-relocalization.test.ts; see docs/ar.md
/// "Tracking failure".
final class RelocalizationStateMachineTests: XCTestCase {

    func testStartsUninitializedWithNoPose() {
        let state = ArSessionState()
        XCTAssertEqual(state.state, .uninitialized)
        XCTAssertNil(state.lastKnownPose)
    }

    func testFullHappyPath() {
        var state = ArSessionState()
        state = RelocalizationStateMachine.reduce(state, .startDetecting)
        XCTAssertEqual(state.state, .detecting)

        let pose1 = Pose(x: 1, y: 0, z: 0)
        state = RelocalizationStateMachine.reduce(state, .anchorDetected(pose1))
        XCTAssertEqual(state.state, .localized)
        XCTAssertEqual(state.lastKnownPose, pose1)

        state = RelocalizationStateMachine.reduce(state, .trackingLost)
        XCTAssertEqual(state.state, .trackingLost)
        // Frozen: the last known pose must survive a tracking-loss event untouched.
        XCTAssertEqual(state.lastKnownPose, pose1)

        state = RelocalizationStateMachine.reduce(state, .beginRelocalizing)
        XCTAssertEqual(state.state, .relocalizing)
        XCTAssertEqual(state.lastKnownPose, pose1)

        let pose2 = Pose(x: 2, y: 0, z: 0)
        state = RelocalizationStateMachine.reduce(state, .relocalized(pose2))
        XCTAssertEqual(state.state, .localized)
        XCTAssertEqual(state.lastKnownPose, pose2)
    }

    func testTrackingLossWhileRelocalizingGoesBackToTrackingLostWithoutInventingAPose() {
        let pose = Pose(x: 1, y: 0, z: 0)
        var state = ArSessionState(state: .relocalizing, lastKnownPose: pose)
        state = RelocalizationStateMachine.reduce(state, .trackingLost)
        XCTAssertEqual(state.state, .trackingLost)
        XCTAssertEqual(state.lastKnownPose, pose)
    }

    func testEventInvalidForCurrentStateIsANoOp() {
        let state = ArSessionState() // uninitialized
        let next = RelocalizationStateMachine.reduce(state, .relocalized(Pose(x: 9, y: 9, z: 9)))
        XCTAssertEqual(next, state)
    }

    func testStrayTrackingLossWhileAlreadyTrackingLostDoesNotMoveTheMarkerOrCrash() {
        let pose = Pose(x: 1, y: 0, z: 0)
        let state = ArSessionState(state: .trackingLost, lastKnownPose: pose)
        let next = RelocalizationStateMachine.reduce(state, .trackingLost)
        XCTAssertEqual(next, state)
    }
}
