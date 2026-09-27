#if canImport(ARKit)
import ARKit
import ChayaARCore
import Foundation

/// The ARKit integration for iOS AR navigation (docs/ar.md, "iOS: Swift/ARKit").
///
///   1. `run` starts ARWorldTrackingConfiguration (gravity-aligned world, +Y up) with one ARReferenceImage per
///      registered IMAGE_TARGET anchor, named with the anchor id (MarkerRegistry).
///   2. Every ARFrame: its ARImageAnchors are turned into observations of registered markers (reference image name ->
///      anchor id), with ARKit's measured transform, the frame timestamp, isTracked and the estimated scale.
///   3. While searching (or recovered after a loss), eligible observations go to POST .../anchors/relocalize, which
///      solves the ARKit world -> canonical venue transform. In the same frame an ARAnchor is added at the observed
///      marker's pose; ARKit keeps moving it as it refines its map, and that correction is applied to the transform
///      every frame (VenueFrames.anchorCorrectedDeviceToVenue).
///   4. On the first localization the route is planned from the device's canonical position; then each frame the route
///      is placed with the corrected transform and progress is advanced from ARKit's camera transform, only while
///      localized.
///   5. ARKit's camera tracking state, session interruptions, errors, and the world anchor's disappearance drive
///      RelocalizationStateMachine (normal / limited / relocalizing / lost / recovered).
///
/// Every value comes from an ARFrame/ARAnchor/ARCamera callback or from the server; nothing is fabricated, and where
/// ARKit has not detected something the value stays absent. It needs a physical device: the iOS Simulator does not
/// run ARKit world tracking (ARWorldTrackingConfiguration.isSupported is false there). Delegate callbacks are delivered
/// on the main queue, and all state is mutated there.
@available(iOS 16.0, *)
public final class ARSessionManager: NSObject, ARSessionDelegate {

    public typealias Relocalize = @Sendable ([AnchorObservation]) async throws -> RelocalizationResponse
    public typealias PlanRoute = @Sendable (Vec3) async throws -> RouteResponse

    /// What the UI shows.
    public struct Snapshot: Equatable {
        public var state: NavigationState
        public var progress: RouteGeometry.Progress?
        public var route: RouteResponse?
        /// Why the most recent sighting of a registered marker could not be used (wrong size, not tracked), if any.
        public var lastRejectedObservation: String?
        public var routeError: String?
    }

    public let session = ARSession()
    public let renderer = RouteSceneRenderer()
    public var onUpdate: ((Snapshot) -> Void)?
    public private(set) var snapshot: Snapshot

    private let registry: MarkerRegistry
    private let floorId: UUID
    private let relocalize: Relocalize
    private let planRoute: PlanRoute

    private var routePoints: [Vec3] = []
    private var worldAnchor: (anchor: ARAnchor, poseAtSolve: Pose, addedAt: TimeInterval, seen: Bool)?
    private var lastFailureAt: TimeInterval = -.infinity
    private var lastPublishAt: TimeInterval = 0

    static let retryAfterFailure: TimeInterval = 1.5
    /// How long a newly added world anchor may be missing from ARFrame.anchors before that counts as a loss.
    static let worldAnchorGrace: TimeInterval = 1.0
    static let worldAnchorName = "chaya.localization"

    public init(registry: MarkerRegistry, floorId: UUID, relocalize: @escaping Relocalize, planRoute: @escaping PlanRoute) {
        self.registry = registry
        self.floorId = floorId
        self.relocalize = relocalize
        self.planRoute = planRoute
        self.snapshot = Snapshot(state: NavigationState())
        super.init()
        session.delegate = self
        session.delegateQueue = .main
    }

    /// Whether this device can run ARKit world tracking at all (false on the Simulator and pre-A9 devices).
    public static var isSupported: Bool { ARWorldTrackingConfiguration.isSupported }

    /// Starts world tracking with the registry's reference images. `referenceImages` must be built by ReferenceImages
    /// from the server's target images, at their registered printed widths.
    public func run(referenceImages: Set<ARReferenceImage>) {
        let configuration = ARWorldTrackingConfiguration()
        configuration.worldAlignment = .gravity
        configuration.detectionImages = referenceImages
        configuration.maximumNumberOfTrackedImages = min(referenceImages.count, 4)
        configuration.automaticImageScaleEstimationEnabled = true
        session.run(configuration, options: [.resetTracking, .removeExistingAnchors])
        dispatch(.sessionStarted)
    }

    public func stop() {
        session.pause()
        renderer.hide()
        dispatch(.sessionEnded)
    }

    private func dispatch(_ event: NavigationEvent) {
        let next = RelocalizationStateMachine.reduce(snapshot.state, event)
        guard next != snapshot.state else { return }
        snapshot.state = next
        publish(force: true)
    }

    private func publish(force: Bool) {
        let now = ProcessInfo.processInfo.systemUptime
        guard force || now - lastPublishAt > 0.25 else { return }
        lastPublishAt = now
        onUpdate?(snapshot)
    }

    // MARK: - ARSessionDelegate

    public func session(_ session: ARSession, didUpdate frame: ARFrame) {
        dispatch(.tick(at: frame.timestamp))
        observeMarkers(in: frame)
        updateRoute(in: frame)
    }

    public func session(_ session: ARSession, cameraDidChangeTrackingState camera: ARCamera) {
        let at = session.currentFrame?.timestamp ?? ProcessInfo.processInfo.systemUptime
        dispatch(.trackingChanged(TrackingQuality(camera.trackingState), at: at))
        if !snapshot.state.routeVisible { renderer.hide() }
    }

    public func sessionWasInterrupted(_ session: ARSession) {
        dispatch(.sessionInterrupted)
        renderer.hide()
    }

    public func sessionInterruptionEnded(_ session: ARSession) {
        // ARKit reports the tracking state it recovers to through cameraDidChangeTrackingState.
    }

    /// Let ARKit try to restore its own world map after an interruption (tracking shows as .limited(.relocalizing)).
    /// Even if it succeeds, the route waits for a marker: the state machine only relocalizes from a new observation.
    public func sessionShouldAttemptRelocalization(_ session: ARSession) -> Bool { true }

    public func session(_ session: ARSession, didRemove anchors: [ARAnchor]) {
        if let world = worldAnchor, anchors.contains(where: { $0.identifier == world.anchor.identifier }) {
            dispatch(.worldAnchorLost)
            renderer.hide()
        }
    }

    public func session(_ session: ARSession, didFailWithError error: Error) {
        let message: String
        if let arError = error as? ARError, arError.code == .cameraUnauthorized {
            message = "CAMERA_PERMISSION_DENIED: camera access is not allowed for this app"
        } else {
            message = "AR_SESSION_FAILED: \(error.localizedDescription)"
        }
        renderer.hide()
        dispatch(.sessionFailed(message))
    }

    // MARK: - Marker observations -> relocalization

    private func observeMarkers(in frame: ARFrame) {
        let phase = snapshot.state.phase
        guard phase == .searching || phase == .recovered, snapshot.state.deviceTracking == .normal,
              frame.timestamp - lastFailureAt > Self.retryAfterFailure else { return }
        let detections = frame.anchors.compactMap { $0 as? ARImageAnchor }.map { ImageDetection($0, timestamp: frame.timestamp) }
        let observations = MarkerObservations.observe(detections, registry: registry)
        guard !observations.isEmpty else { return }
        let eligible = MarkerObservations.eligible(observations)
        if eligible.isEmpty {
            snapshot.lastRejectedObservation = observations.compactMap { MarkerObservations.eligibility($0).reason }.first
            publish(force: false)
            return
        }
        snapshot.lastRejectedObservation = nil
        dispatch(.markersObserved(eligible))
        guard snapshot.state.phase == .solving, let pending = snapshot.state.pending else { return }

        // The world anchor is added in this frame, at the pose the first marker was measured at.
        let anchorPose = eligible[0].observedPose
        let anchor = ARAnchor(name: Self.worldAnchorName, transform: anchorPose.simdTransform)
        session.add(anchor: anchor)
        let requestId = pending.requestId
        let body = eligible.map(\.asAnchorObservation)
        let cameraPose = Pose(frame.camera.transform)
        let relocalize = self.relocalize
        Task { @MainActor [weak self] in
            do {
                let result = try await relocalize(body)
                self?.solved(requestId: requestId, result: result, anchorIds: eligible.map(\.anchorId), anchor: anchor,
                             anchorPose: anchorPose, cameraPose: cameraPose)
            } catch {
                self?.failed(requestId: requestId, error: error, anchor: anchor)
            }
        }
    }

    private func solved(requestId: Int, result: RelocalizationResponse, anchorIds: [UUID], anchor: ARAnchor,
                        anchorPose: Pose, cameraPose: Pose) {
        let now = ProcessInfo.processInfo.systemUptime
        let localization = Localization(deviceToVenue: result.deviceToVenueTransform, anchorIds: anchorIds,
                                        residualMeters: result.residualMeters, coordinateFrameId: result.coordinateFrameId,
                                        solvedAt: now)
        dispatch(.relocalizationSucceeded(requestId: requestId, localization))
        guard snapshot.state.localization == localization, snapshot.state.phase == .localized else {
            session.remove(anchor: anchor) // superseded: tracking was lost while the request was in flight
            return
        }
        if let previous = worldAnchor { session.remove(anchor: previous.anchor) }
        worldAnchor = (anchor, anchorPose, now, false)
        if snapshot.route == nil { fetchRoute(from: VenueFrames.devicePositionInVenue(camera: cameraPose, deviceToVenue: localization.deviceToVenue)) }
    }

    private func failed(requestId: Int, error: Error, anchor: ARAnchor) {
        session.remove(anchor: anchor)
        lastFailureAt = session.currentFrame?.timestamp ?? ProcessInfo.processInfo.systemUptime
        let text = (error as? APIError).map { "\($0.code): \($0.detail)" } ?? error.localizedDescription
        dispatch(.relocalizationFailed(requestId: requestId, error: text))
    }

    /// Planned once, from where the device was when first localized. A failure is a routing error, not a localization
    /// one: the localization stays.
    private func fetchRoute(from start: Vec3) {
        let planRoute = self.planRoute
        let floorId = self.floorId
        Task { @MainActor [weak self] in
            do {
                let route = try await planRoute(start)
                guard let self else { return }
                self.snapshot.route = route
                self.snapshot.routeError = nil
                self.routePoints = RouteGeometry.points(RouteGeometry.legOnFloor(route, floorId: floorId))
                self.renderer.setRoute(self.routePoints)
                self.publish(force: true)
            } catch {
                guard let self else { return }
                self.snapshot.routeError = (error as? APIError).map { "\($0.code): \($0.detail)" } ?? error.localizedDescription
                self.publish(force: true)
            }
        }
    }

    // MARK: - Route placement and progress

    private func updateRoute(in frame: ARFrame) {
        let state = snapshot.state
        guard state.routeVisible, let localization = state.localization, let world = worldAnchor else {
            renderer.hide() // not localized: nothing drawn, progress frozen
            return
        }
        guard let current = frame.anchors.first(where: { $0.identifier == world.anchor.identifier }) else {
            if !world.seen && ProcessInfo.processInfo.systemUptime - world.addedAt < Self.worldAnchorGrace {
                renderer.hide()
                return
            }
            dispatch(.worldAnchorLost)
            renderer.hide()
            return
        }
        worldAnchor?.seen = true
        let deviceToVenue = VenueFrames.anchorCorrectedDeviceToVenue(localization.deviceToVenue, anchorAtSolve: world.poseAtSolve,
                                                                      anchorNow: Pose(current.transform))
        let device = VenueFrames.devicePositionInVenue(camera: Pose(frame.camera.transform), deviceToVenue: deviceToVenue)
        snapshot.progress = RouteGeometry.advance(snapshot.progress, canAdvance: state.canAdvanceRoute, points: routePoints, device: device)
        renderer.update(venueToWorld: VenueFrames.venueToWorld(deviceToVenue: deviceToVenue),
                        nextIndex: snapshot.progress?.nextIndex ?? 0, visible: !routePoints.isEmpty)
        publish(force: false)
    }
}
#endif
