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
///      solves the ARKit world -> canonical venue transform. The first answer pins the floor's scan version; every later
///      request sends it, and an answer in another version is refused (RelocalizationStateMachine). In the same frame an
///      ARAnchor is added at the observed marker's pose; ARKit keeps moving it as it refines its map, and that correction
///      is applied to the transform every frame (VenueFrames.anchorCorrectedDeviceToVenue).
///   4. On the first localization the route is planned from the device's canonical position, in the localization's scan
///      version. Its leg is drawn only if the server routed it in that version and coordinate frame
///      (RouteGeometry.leg(of:for:)), re-checked on every later localization. Each frame the route is placed with the
///      corrected transform and progress is advanced from ARKit's camera transform, only while localized.
///   5. ARKit's camera tracking state, session interruptions, errors, and the world anchor's disappearance drive
///      RelocalizationStateMachine (normal / limited / relocalizing / lost / recovered).
///
/// Every value comes from an ARFrame/ARAnchor/ARCamera callback or from the server; nothing is fabricated, and where
/// ARKit has not detected something the value stays absent. It needs a physical device: the iOS Simulator does not
/// run ARKit world tracking (ARWorldTrackingConfiguration.isSupported is false there). Delegate callbacks are delivered
/// on the main queue, and all state is mutated there.
@available(iOS 16.0, *)
public final class ARSessionManager: NSObject, ARSessionDelegate {

    /// The observations, and the scan version the session pinned for the floor (nil before the first localization).
    public typealias Relocalize = @Sendable ([AnchorObservation], UUID?) async throws -> RelocalizationResponse
    /// The device's canonical start, and the scan version the localization it was computed with was solved in.
    public typealias PlanRoute = @Sendable (Vec3, UUID) async throws -> RouteResponse

    /// What the UI shows.
    public struct Snapshot: Equatable {
        public var state: NavigationState
        public var progress: RouteGeometry.Progress?
        public var route: RouteResponse?
        /// Why the most recent sighting of a registered marker could not be used (wrong size, not tracked), if any.
        public var lastRejectedObservation: String?
        /// Why there is no route to draw: the route request failed, or its leg was refused for this localization
        /// (VERSION_MISMATCH, FRAME_MISMATCH, ROUTE_SOURCE_MISSING, NO_LEG_ON_FLOOR).
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
    private var routeRequested = false
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
        dispatch(.sessionStarted(floorId: floorId))
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
        let pinnedVersion = snapshot.state.pinnedScanVersionId
        let cameraPose = Pose(frame.camera.transform)
        let relocalize = self.relocalize
        Task { @MainActor [weak self] in
            do {
                let result = try await relocalize(body, pinnedVersion)
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
                                        scanVersionId: result.scanVersionId, floorId: floorId, solvedAt: now)
        dispatch(.relocalizationSucceeded(requestId: requestId, localization))
        guard snapshot.state.localization == localization, snapshot.state.phase == .localized else {
            // Superseded (tracking was lost while the request was in flight) or refused (another scan version or floor).
            // A refusal is retried no faster than a failure.
            session.remove(anchor: anchor)
            lastFailureAt = session.currentFrame?.timestamp ?? ProcessInfo.processInfo.systemUptime
            return
        }
        if let previous = worldAnchor { session.remove(anchor: previous.anchor) }
        worldAnchor = (anchor, anchorPose, now, false)
        if snapshot.route == nil {
            if !routeRequested {
                routeRequested = true
                fetchRoute(from: VenueFrames.devicePositionInVenue(camera: cameraPose, deviceToVenue: localization.deviceToVenue),
                           scanVersionId: localization.scanVersionId)
            }
        } else {
            applyRoute() // a new localization may be in another frame than the route: re-check before drawing
        }
    }

    private func failed(requestId: Int, error: Error, anchor: ARAnchor) {
        session.remove(anchor: anchor)
        lastFailureAt = session.currentFrame?.timestamp ?? ProcessInfo.processInfo.systemUptime
        let text = (error as? APIError).map { "\($0.code): \($0.detail)" } ?? error.localizedDescription
        dispatch(.relocalizationFailed(requestId: requestId, error: text))
    }

    /// Planned once, from where the device was when first localized, on the scan version that localization was solved in.
    /// A failure is a routing error, not a localization one: the localization stays.
    private func fetchRoute(from start: Vec3, scanVersionId: UUID) {
        let planRoute = self.planRoute
        Task { @MainActor [weak self] in
            do {
                let route = try await planRoute(start, scanVersionId)
                guard let self else { return }
                self.snapshot.route = route
                self.applyRoute()
            } catch {
                guard let self else { return }
                self.routeRequested = false // retried at the next localization
                self.snapshot.routeError = (error as? APIError).map { "\($0.code): \($0.detail)" } ?? error.localizedDescription
                self.publish(force: true)
            }
        }
    }

    /// Draws the route's leg on this floor only if it was routed in the scan version and coordinate frame of the current
    /// localization; otherwise nothing is drawn and the refusal is shown. Called when the route arrives and after every
    /// later localization.
    private func applyRoute() {
        guard let route = snapshot.route, let localization = snapshot.state.localization else { return }
        switch RouteGeometry.leg(of: route, for: localization) {
        case .drawable(let leg):
            routePoints = RouteGeometry.points(leg)
            snapshot.routeError = nil
        case .refused(let problem, let detail):
            routePoints = []
            snapshot.progress = nil
            snapshot.routeError = "\(problem.rawValue): \(detail)"
        }
        renderer.setRoute(routePoints)
        publish(force: true)
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
