import XCTest
@testable import ChayaARCore

/// Reference image -> registered marker -> backend anchor, and which observations may relocalize.
final class MarkerRegistryTests: XCTestCase {

    func anchor(_ type: MarkerType, _ identifier: String, size: Double? = 0.3, status: CalibrationStatus = .calibrated,
                versioned: Bool = true) -> Anchor {
        Anchor(id: UUID(), venueId: UUID(), floorId: UUID(), markerType: type, markerIdentifier: identifier, markerSizeMeters: size,
               physicalPose: .identity, digitalPose: .identity, calibrationStatus: status, lastCalibratedAt: nil, coordinateFrameId: UUID(),
               scanVersionId: versioned ? UUID() : nil, poseId: versioned ? UUID() : nil, poseRevision: versioned ? 1 : nil)
    }

    func testOnlyCalibratedImageTargetsWithAPrintedSizeAreTrackable() {
        XCTAssertEqual(AnchorSupport.of(anchor(.imageTarget, "a")), .trackable)
        for type in [MarkerType.aprilTag, .arucoMarker, .qrCode] {
            XCTAssertEqual(AnchorSupport.of(anchor(type, "f")), .fiducialDetectionUnsupported)
        }
        XCTAssertEqual(AnchorSupport.of(anchor(.imageTarget, "s", status: .stale)), .notCalibrated)
        XCTAssertEqual(AnchorSupport.of(anchor(.imageTarget, "n", size: nil)), .missingPrintedSize)
    }

    func testAnAnchorWhosePoseWasNeverEnteredAgainstAScanVersionIsNotRegistered() {
        let unversioned = anchor(.imageTarget, "old", versioned: false)
        XCTAssertEqual(AnchorSupport.of(unversioned), .unversioned)
        XCTAssertTrue(MarkerRegistry(anchors: [unversioned]).markers.isEmpty, "the server would refuse it (ANCHOR_UNVERSIONED)")
    }

    func testAReferenceImageNameIdentifiesExactlyOneBackendAnchor() {
        let entrance = anchor(.imageTarget, "entrance")
        let stairs = anchor(.imageTarget, "stairs", size: 0.5)
        let registry = MarkerRegistry(anchors: [entrance, stairs, anchor(.aprilTag, "tag")])
        XCTAssertEqual(registry.markers.map(\.anchor.id), [entrance.id, stairs.id])
        XCTAssertEqual(registry.marker(forReferenceImageName: stairs.id.apiString)?.anchor, stairs)
        XCTAssertEqual(registry.marker(forReferenceImageName: stairs.id.apiString)?.physicalWidthMeters, 0.5)
        XCTAssertNil(registry.marker(forReferenceImageName: "stairs"), "the marker identifier is a label, not the identity")
        XCTAssertNil(registry.marker(forReferenceImageName: nil))
        XCTAssertEqual(registry.restricted(to: [entrance.id.apiString]).markers.map(\.anchor.id), [entrance.id])
    }

    func testADetectionBecomesAnObservationOfThatAnchorCarryingARKitsValues() {
        let stairs = anchor(.imageTarget, "stairs")
        let registry = MarkerRegistry(anchors: [anchor(.imageTarget, "entrance"), stairs])
        let pose = Pose(x: 1, y: 0.2, z: -2, qy: 0.6, qw: 0.8)
        let obs = MarkerObservations.observe([
            ImageDetection(referenceImageName: stairs.id.apiString, pose: pose, timestamp: 42.5, isTracked: true, estimatedScaleFactor: 0.97),
            ImageDetection(referenceImageName: "someone-else's-image", pose: pose, timestamp: 42.5, isTracked: true, estimatedScaleFactor: 1),
        ], registry: registry)
        XCTAssertEqual(obs, [MarkerObservation(anchorId: stairs.id, markerIdentifier: "stairs", observedPose: pose, timestamp: 42.5,
                                               isTracked: true, estimatedScaleFactor: 0.97)])
        XCTAssertEqual(obs[0].asAnchorObservation, AnchorObservation(anchorId: stairs.id, observedPose: pose))
    }

    func testOnlyTrackedCorrectlySizedObservationsRelocalizeOncePerAnchor() {
        let id = UUID()
        func o(_ tracked: Bool, _ scale: Double, _ anchorId: UUID = UUID()) -> MarkerObservation {
            MarkerObservation(anchorId: anchorId, markerIdentifier: "m", observedPose: .identity, timestamp: 1, isTracked: tracked, estimatedScaleFactor: scale)
        }
        XCTAssertFalse(MarkerObservations.eligibility(o(false, 1)).eligible)
        XCTAssertTrue(MarkerObservations.eligibility(o(false, 1)).reason!.contains("not tracking"))
        XCTAssertTrue(MarkerObservations.eligibility(o(true, 1.15)).eligible)
        XCTAssertFalse(MarkerObservations.eligibility(o(true, 0.7)).eligible)
        XCTAssertTrue(MarkerObservations.eligibility(o(true, 0.7)).reason!.contains("printed size"))
        XCTAssertEqual(MarkerObservations.eligible([o(true, 1, id), o(true, 1, id), o(false, 1)]).count, 1)
        XCTAssertEqual(MarkerObservations.eligible((0..<20).map { _ in o(true, 1) }).count, 16)
    }
}
