import ARKit
import AVFoundation
import ChayaARCore
import ChayaARKitSession
import ImageIO
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

    /// The anchor the committed fixture was rendered for. The image is the server's own target artwork for this id
    /// (ImageTarget.png, GET .../anchors/{a}/target.png): packages/contracts/fixtures/ar, kept pixel-identical to the
    /// generator by services/api ImageTargetFixtureTest, and copied into this test bundle by project.yml.
    static let fixtureAnchorId = UUID(uuidString: "5f0c1d2e-3a4b-4c5d-8e6f-7a8b9c0d1e2f")!
    static let fixturePixels = 1024

    static func targetPNG(file: StaticString = #filePath, line: UInt = #line) throws -> Data {
        let name = "image-target-\(fixtureAnchorId.apiString)"
        let url = try XCTUnwrap(Bundle(for: ReferenceImageTests.self).url(forResource: name, withExtension: "png"),
                                "\(name).png is not in the test bundle: see the ChayaARTests resources in project.yml",
                                file: file, line: line)
        return try Data(contentsOf: url)
    }

    /// An anchor the registry accepts: a calibrated image target with a printed size, placed in a pinned scan version
    /// (AnchorSupport.of: an unversioned anchor is not trackable).
    static func anchor(id: UUID = fixtureAnchorId, sizeMeters: Double = 0.3, versioned: Bool = true) -> Anchor {
        Anchor(id: id, venueId: UUID(), floorId: UUID(), markerType: .imageTarget, markerIdentifier: "entrance",
               markerSizeMeters: sizeMeters, physicalPose: .identity, digitalPose: .identity, calibrationStatus: .calibrated,
               lastCalibratedAt: nil, coordinateFrameId: UUID(),
               scanVersionId: versioned ? UUID() : nil, poseId: versioned ? UUID() : nil, poseRevision: versioned ? 1 : nil)
    }

    static func marker() throws -> RegisteredMarker {
        try XCTUnwrap(MarkerRegistry(anchors: [anchor()]).markers.first)
    }

    func testTheFixtureIsTheServersSquareTargetImage() throws {
        let png = try Self.targetPNG()
        XCTAssertEqual(Array(png.prefix(8)), [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A], "PNG signature")
        let source = try XCTUnwrap(CGImageSourceCreateWithData(png as CFData, nil))
        XCTAssertEqual(CGImageSourceGetStatusAtIndex(source, 0), .statusComplete)
        let image = try XCTUnwrap(CGImageSourceCreateImageAtIndex(source, 0, nil))
        XCTAssertEqual(image.width, Self.fixturePixels)
        XCTAssertEqual(image.height, Self.fixturePixels)
        let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any]
        XCTAssertEqual((properties?[kCGImagePropertyOrientation] as? Int) ?? 1, 1,
                       "no EXIF rotation: the pixels are the printed artwork as it hangs (ARKit orientation .up)")
    }

    func testAReferenceImageIsTheTargetImageAtItsPrintedWidthNamedWithTheAnchorId() throws {
        let anchor = Self.anchor()
        let marker = try XCTUnwrap(MarkerRegistry(anchors: [anchor]).markers.first)
        let image = try ReferenceImages.make(png: Self.targetPNG(), marker: marker)
        XCTAssertEqual(image.name, anchor.id.apiString)
        XCTAssertEqual(image.physicalSize.width, 0.3, accuracy: 1e-6)
        XCTAssertEqual(image.physicalSize.height, 0.3, accuracy: 1e-6, "a square target prints square")
        XCTAssertEqual(MarkerRegistry(anchors: [anchor]).marker(forReferenceImageName: image.name)?.anchor.id, anchor.id,
                       "a detection of this image resolves to its backend anchor")
        XCTAssertThrowsError(try ReferenceImages.make(png: Data("not a png".utf8), marker: marker))
    }

    /// ARKit's own check (the one NavigationViewModel.loadMarkers applies before registering an image). On a device a
    /// rejection fails the test. The Simulator has no ARKit tracking stack; if it cannot run the check the test says so
    /// rather than pass. Neither proves the image is detected by a camera: that is docs/ar-ios-validation.md.
    func testARKitAcceptsTheFixtureAsADetectionImage() async throws {
        let image = try ReferenceImages.make(png: Self.targetPNG(), marker: Self.marker())
        let problem = await ReferenceImages.validate(image)
        #if targetEnvironment(simulator)
        if let problem { throw XCTSkip("ARReferenceImage.validate is unavailable in this Simulator: \(problem)") }
        #else
        XCTAssertNil(problem, "ARKit rejected the server's target image")
        #endif
    }

    /// physicalWidth is the printed width; ARKit derives the height from the pixel aspect ratio of the image as given
    /// (orientation .up: no rotation). A landscape image therefore keeps its width and gets a proportional height.
    func testTheHeightFollowsThePixelAspectRatioWithoutRotation() throws {
        let png = UIGraphicsImageRenderer(size: CGSize(width: 400, height: 200)).pngData { ctx in
            UIColor.white.setFill(); ctx.fill(CGRect(x: 0, y: 0, width: 400, height: 200))
            UIColor.black.setFill(); ctx.fill(CGRect(x: 20, y: 20, width: 120, height: 60))
        }
        let image = try ReferenceImages.make(png: png, marker: Self.marker())
        let size = try XCTUnwrap(CGImageSourceCreateWithData(png as CFData, nil).flatMap { CGImageSourceCreateImageAtIndex($0, 0, nil) })
        let aspect = CGFloat(size.height) / CGFloat(size.width)
        XCTAssertEqual(image.physicalSize.width, 0.3, accuracy: 1e-6)
        XCTAssertEqual(image.physicalSize.height, 0.3 * aspect, accuracy: 1e-6)
        XCTAssertLessThan(image.physicalSize.height, image.physicalSize.width, "not rotated a quarter turn")
    }

    func testMissingAndMalformedImagesAreRejected() throws {
        let marker = try Self.marker()
        let fixture = try Self.targetPNG()
        var seed: UInt64 = 7
        let noise = Data((0..<4096).map { _ in seed = seed &* 6364136223846793005 &+ 1442695040888963407; return UInt8(seed >> 56) })
        let cases: [(String, Data)] = [
            ("an empty body (no image at all)", Data()),
            ("text", Data("not a png".utf8)),
            ("random bytes", noise),
            ("a PNG signature with no image after it", Data(fixture.prefix(8)) + noise),
            ("the fixture truncated to its header", Data(fixture.prefix(64))),
            ("the fixture truncated halfway", Data(fixture.prefix(fixture.count / 2))),
        ]
        for (label, data) in cases {
            XCTAssertThrowsError(try ReferenceImages.make(png: data, marker: marker), label) { error in
                let invalid = error as? ReferenceImages.InvalidImage
                XCTAssertNotNil(invalid, "\(label): \(error)")
                XCTAssertEqual(invalid?.markerIdentifier, "entrance", label)
            }
        }
        XCTAssertNoThrow(try ReferenceImages.make(png: fixture, marker: marker), "the intact fixture is accepted")
    }

    /// A marker whose image could not be loaded is dropped from the registry the session uses
    /// (NavigationViewModel.loadMarkers: restricted to the images ARKit accepted), so nothing resolves to it.
    func testAMarkerWithoutAnImageIsNotResolvable() throws {
        let registry = MarkerRegistry(anchors: [Self.anchor()])
        let name = try XCTUnwrap(registry.markers.first?.referenceImageName)
        XCTAssertNil(registry.restricted(to: []).marker(forReferenceImageName: name))
        XCTAssertNotNil(registry.restricted(to: [name]).marker(forReferenceImageName: name))
    }

    /// The test anchor of the original failure (docs/FINAL_AUDIT_2026-10-17.md P1-1): no pinned scan version, so the
    /// registry excludes it and no reference image is made for it, however good its artwork.
    func testAnUnversionedAnchorGetsNoReferenceImage() {
        let anchor = Self.anchor(versioned: false)
        XCTAssertEqual(AnchorSupport.of(anchor), .unversioned)
        XCTAssertTrue(MarkerRegistry(anchors: [anchor]).markers.isEmpty)
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

    func testARefusedLegLeavesNothingToDraw() {
        let renderer = RouteSceneRenderer()
        renderer.setRoute([Vec3(5, 4, 0), Vec3(5, 8, 0)])
        renderer.setRoute([]) // what ARSessionManager sets when RouteGeometry.leg(of:for:) refuses the leg
        renderer.update(venueToWorld: .identity, nextIndex: 0, visible: true)
        XCTAssertEqual(renderer.waypointCount, 0)
        XCTAssertTrue(renderer.venueRoot.isHidden)
    }
}

final class ARSessionManagerTests: XCTestCase {

    /// The manager hands the pinned scan version to relocalization and the localization's version to routing. No session is
    /// run: the Simulator cannot track, and nothing here pretends it does.
    func testTheManagerStartsIdleWithVersionAwareCallbacks() {
        let manager = ARSessionManager(
            registry: MarkerRegistry(anchors: []), floorId: UUID(),
            relocalize: { (_: [AnchorObservation], _: UUID, _: UUID?) in throw APIError(status: 0, code: "UNUSED", detail: "not called") },
            planRoute: { (_: Vec3, _: Localization, _: [BlockedRegion]) in throw APIError(status: 0, code: "UNUSED", detail: "not called") },
            listConnections: { throw APIError(status: 0, code: "UNUSED", detail: "not called") })
        XCTAssertEqual(manager.snapshot.state.phase, .idle)
        XCTAssertNil(manager.snapshot.state.pinnedScanVersionId)
        XCTAssertNil(manager.snapshot.route)
    }
}
