import ARKit
import AVFoundation
import ChayaARCore
import ChayaARKitSession
import SceneKit
import UIKit
import XCTest
@testable import ChayaAR

/// App-level tests that run in the iOS Simulator (xcodebuild test). They exercise the real ARKit/SceneKit types the app
/// uses -- tracking-state mapping, transforms, reference images, the route renderer's placement -- and the app's
/// capability and validation states. They do not, and cannot, exercise camera tracking: the Simulator has none. That is
/// docs/ar-ios-validation.md, on a physical device.
final class CapabilityTests: XCTestCase {

    func testEachBlockingConditionIsItsOwnState() {
        XCTAssertEqual(ARCapability.evaluate(worldTrackingSupported: false, camera: .authorized), .worldTrackingUnsupported)
        XCTAssertEqual(ARCapability.evaluate(worldTrackingSupported: true, camera: .denied), .cameraDenied)
        XCTAssertEqual(ARCapability.evaluate(worldTrackingSupported: true, camera: .restricted), .cameraRestricted)
        XCTAssertEqual(ARCapability.evaluate(worldTrackingSupported: true, camera: .notDetermined), .cameraNotDetermined)
        XCTAssertEqual(ARCapability.evaluate(worldTrackingSupported: true, camera: .authorized), .ready)
        XCTAssertTrue(ARCapability.worldTrackingUnsupported.blocksAR)
        XCTAssertTrue(ARCapability.cameraDenied.blocksAR)
        XCTAssertFalse(ARCapability.cameraNotDetermined.blocksAR, "the app asks for the camera when AR starts")
    }

    func testTheSimulatorGetsTheUnsupportedStateNotASimulatedView() throws {
        #if targetEnvironment(simulator)
        XCTAssertFalse(ARSessionManager.isSupported)
        XCTAssertEqual(ARCapability.evaluate(worldTrackingSupported: ARSessionManager.isSupported, camera: .authorized),
                       .worldTrackingUnsupported)
        #else
        throw XCTSkip("device run: world tracking support is the device's own")
        #endif
    }

    func testThisBuildDeclaresItselfNotDeviceValidated() {
        XCTAssertEqual(DeviceValidation.status, .notValidated)
        XCTAssertNotNil(DeviceValidation.banner)
    }

    func testViewerLinkParsing() {
        XCTAssertEqual(ViewerLink.secret(from: "https://chaya.example/viewer?link=abc123"), "abc123")
        XCTAssertEqual(ViewerLink.secret(from: "  abc123 \n"), "abc123")
        XCTAssertNil(ViewerLink.secret(from: "https://chaya.example/viewer"))
        XCTAssertNil(ViewerLink.secret(from: ""))
    }

    func testTheCameraUsageDescriptionIsDeclared() {
        let text = Bundle.main.object(forInfoDictionaryKey: "NSCameraUsageDescription") as? String
        XCTAssertFalse(text?.isEmpty ?? true, "without it iOS terminates the app when the camera is requested")
    }
}

final class ARKitMappingTests: XCTestCase {

    func testEveryARKitTrackingStateMapsToTheMatchingState() {
        XCTAssertEqual(TrackingQuality(ARCamera.TrackingState.normal), .normal)
        XCTAssertEqual(TrackingQuality(ARCamera.TrackingState.notAvailable), .notAvailable)
        XCTAssertEqual(TrackingQuality(ARCamera.TrackingState.limited(.initializing)), .limited(.initializing))
        XCTAssertEqual(TrackingQuality(ARCamera.TrackingState.limited(.excessiveMotion)), .limited(.excessiveMotion))
        XCTAssertEqual(TrackingQuality(ARCamera.TrackingState.limited(.insufficientFeatures)), .limited(.insufficientFeatures))
        XCTAssertEqual(TrackingQuality(ARCamera.TrackingState.limited(.relocalizing)), .limited(.relocalizing))
    }

    func testPoseAndARKitTransformRoundTrip() {
        let angle: Float = 0.7
        var m = simd_float4x4(simd_quatf(angle: angle, axis: simd_normalize(SIMD3<Float>(0.2, 1, -0.3))))
        m.columns.3 = SIMD4<Float>(1.5, -0.25, 3, 1)
        let back = Pose(m).simdTransform
        for c in 0..<4 { for r in 0..<4 { XCTAssertEqual(back[c][r], m[c][r], accuracy: 1e-5) } }
        XCTAssertEqual(Pose(m).x, 1.5, accuracy: 1e-6)
    }
}

final class ReferenceImageTests: XCTestCase {

    /// A textured PNG, like the server's generated target image.
    static func targetPNG() -> Data {
        let size = CGSize(width: 512, height: 512)
        let renderer = UIGraphicsImageRenderer(size: size)
        return renderer.pngData { ctx in
            var seed: UInt64 = 42
            func next() -> CGFloat { seed = seed &* 6364136223846793005 &+ 1442695040888963407; return CGFloat(seed >> 33) / CGFloat(1 << 31) }
            UIColor.gray.setFill(); ctx.fill(CGRect(origin: .zero, size: size))
            for _ in 0..<600 {
                UIColor(white: next() > 0.5 ? 0.05 : 0.95, alpha: 1).setFill()
                ctx.fill(CGRect(x: next() * 512, y: next() * 512, width: 4 + next() * 60, height: 4 + next() * 60))
            }
        }
    }

    func testAReferenceImageIsTheTargetImageAtItsPrintedWidthNamedWithTheAnchorId() throws {
        let anchor = Anchor(id: UUID(), venueId: UUID(), floorId: UUID(), markerType: .imageTarget, markerIdentifier: "entrance",
                            markerSizeMeters: 0.3, physicalPose: .identity, digitalPose: .identity, calibrationStatus: .calibrated,
                            lastCalibratedAt: nil, coordinateFrameId: UUID())
        let marker = try XCTUnwrap(MarkerRegistry(anchors: [anchor]).markers.first)
        let image = try ReferenceImages.make(png: Self.targetPNG(), marker: marker)
        XCTAssertEqual(image.name, anchor.id.apiString)
        XCTAssertEqual(image.physicalSize.width, 0.3, accuracy: 1e-6)
        XCTAssertEqual(MarkerRegistry(anchors: [anchor]).marker(forReferenceImageName: image.name)?.anchor.id, anchor.id,
                       "a detection of this image resolves to its backend anchor")
        XCTAssertThrowsError(try ReferenceImages.make(png: Data("not a png".utf8), marker: marker))
    }
}

final class RouteSceneRendererTests: XCTestCase {

    func testTheRouteIsBuiltInCanonicalCoordinatesAndPlacedWhereTheTransformPutsIt() throws {
        let renderer = RouteSceneRenderer()
        let scene = SCNScene()
        renderer.attach(to: scene)
        let route = [Vec3(5, 4, 0), Vec3(5, 8, 0), Vec3(9, 8, 0)]
        renderer.setRoute(route)
        XCTAssertEqual(renderer.waypointCount, 3)
        XCTAssertEqual(renderer.segmentCount, 2)
        XCTAssertTrue(renderer.venueRoot.isHidden, "nothing is drawn before a localization places it")

        // the solved transform of VenueFramesTests: a floor marker at canonical (5, 2, 0) seen 1.5 m below, 2 m ahead
        let s = 0.5.squareRoot()
        let deviceToVenue = AnchorMath.deviceToVenue(fromAnchorDigitalPose: Pose(x: 5, y: 2, z: 0, qx: s, qw: s),
                                                     observedPose: Pose(x: 0, y: -1.5, z: -2))
        renderer.update(venueToWorld: VenueFrames.venueToWorld(deviceToVenue: deviceToVenue), nextIndex: 1, visible: true)
        XCTAssertFalse(renderer.venueRoot.isHidden)
        for (i, p) in route.enumerated() {
            let node = try XCTUnwrap(renderer.venueRoot.childNode(withName: "chaya.waypoint.\(i)", recursively: false))
            let world = node.simdWorldPosition
            let expected = VenueFrames.venuePointToWorld(deviceToVenue: deviceToVenue, p)
            XCTAssertEqual(Double(world.x), expected.x, accuracy: 1e-4)
            XCTAssertEqual(Double(world.y), expected.y, accuracy: 1e-4)
            XCTAssertEqual(Double(world.z), expected.z, accuracy: 1e-4)
            XCTAssertEqual(Double(world.y), -1.5, accuracy: 1e-4, "route points sit on ARKit's floor")
        }
        let next = try XCTUnwrap(renderer.venueRoot.childNode(withName: "chaya.waypoint.1", recursively: false))
        XCTAssertEqual(next.simdScale.x, 1.8, accuracy: 1e-6, "the next waypoint is highlighted")

        renderer.hide()
        XCTAssertTrue(renderer.venueRoot.isHidden)
    }
}
