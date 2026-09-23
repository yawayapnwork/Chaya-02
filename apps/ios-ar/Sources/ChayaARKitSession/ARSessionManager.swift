#if canImport(ARKit)
import ARKit
import ChayaARCore
import Foundation

/// The real ARKit integration for iOS AR navigation (docs/ar.md "iOS: Swift/ARKit"). This wraps
/// `ARSession` with an `ARImageTrackingConfiguration`/world-tracking + reference-image setup so
/// registered fiducial markers (`Anchor.markerIdentifier`, `ARUCO_MARKER`/`IMAGE_TARGET`/`QR_CODE`) can be
/// detected as `ARImageAnchor`s, and maps ARKit's own `ARCamera.TrackingState` into
/// `RelocalizationStateMachine` events.
///
/// Every value this class produces comes from a real `ARFrame`/`ARAnchor`/`ARCamera` callback -- it does
/// not fabricate device sensor data. Where ARKit has not yet detected something, the corresponding state
/// here stays `nil`/unset rather than being invented. This type requires a physical device: the iOS
/// Simulator does not implement ARKit camera tracking, so it cannot be exercised there or in this
/// non-macOS development environment -- see docs/ar.md "Testing".
@available(iOS 16.0, *)
public final class ARSessionManager: NSObject, ARSessionDelegate {

    private let session = ARSession()
    private(set) public var arState = ArSessionState()
    public var onStateChange: ((ArSessionState) -> Void)?

    /// Registered anchors this floor's session should be able to relocalize against. Each becomes one
    /// `ARReferenceImage` (for `IMAGE_TARGET`/`QR_CODE` markers detected as images) tracked by name
    /// (`markerIdentifier`), so a detection can be mapped straight back to its `Anchor.id`.
    private var anchorsByIdentifier: [String: Anchor] = [:]

    public override init() {
        super.init()
        session.delegate = self
    }

    /// Starts (or restarts) the session with the given floor's calibrated anchors as reference images.
    /// `referenceImages` must be built by the app from real, physically-sized marker artwork (the printed
    /// marker's actual width in meters) -- this class never assumes a size.
    public func start(anchors: [Anchor], referenceImages: Set<ARReferenceImage>) {
        anchorsByIdentifier = Dictionary(uniqueKeysWithValues: anchors.map { ($0.markerIdentifier, $0) })
        let configuration = ARWorldTrackingConfiguration()
        configuration.detectionImages = referenceImages
        configuration.maximumNumberOfTrackedImages = referenceImages.count
        session.run(configuration)
        transition(.startDetecting)
    }

    public func stop() {
        session.pause()
    }

    private func transition(_ event: ArEvent) {
        arState = RelocalizationStateMachine.reduce(arState, event)
        onStateChange?(arState)
    }

    // MARK: - ARSessionDelegate

    public func session(_ session: ARSession, didAdd anchors: [ARAnchor]) {
        for anchor in anchors {
            guard let imageAnchor = anchor as? ARImageAnchor,
                  let name = imageAnchor.referenceImage.name,
                  let registered = anchorsByIdentifier[name] else { continue }
            let pose = Pose(fromARImageAnchor: imageAnchor)
            transition(.anchorDetected(pose))
            _ = registered // kept for a future "which anchor id fired" callback; not fabricated, just unused here
        }
    }

    public func session(_ session: ARSession, cameraDidChangeTrackingState camera: ARCamera) {
        switch camera.trackingState {
        case .notAvailable, .limited:
            transition(.trackingLost)
        case .normal:
            break // recovery is only confirmed by an explicit relocalize() call, per docs/ar.md
        @unknown default:
            transition(.trackingLost)
        }
    }

    public func session(_ session: ARSession, didFailWithError error: Error) {
        transition(.trackingLost)
    }
}

extension Pose {
    /// Extracts a real pose from ARKit's own column-major 4x4 transform -- no synthesized values.
    init(fromARImageAnchor anchor: ARImageAnchor) {
        let t = anchor.transform
        let translation = simd_make_float3(t.columns.3)
        let quaternion = simd_quaternion(t)
        self.init(x: Double(translation.x), y: Double(translation.y), z: Double(translation.z),
                   qx: Double(quaternion.vector.x), qy: Double(quaternion.vector.y),
                   qz: Double(quaternion.vector.z), qw: Double(quaternion.vector.w))
    }
}
#endif
