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
///      version when the destination is on that floor (RouteGeometry.routeScanVersion). Its leg is drawn only if the server routed it in that version and coordinate frame
///      (RouteGeometry.leg(of:for:)), re-checked on every later localization. Each frame the route is placed with the
///      corrected transform and progress is advanced from ARKit's camera transform, only while localized.
///   5. ARKit's camera tracking state, session interruptions, errors, and the world anchor's disappearance drive
///      RelocalizationStateMachine (normal / limited / relocalizing / lost / recovered). A short `limited` spell holds the
///      route frozen; the return to normal is validated (the world anchor still tracked and barely moved,
///      VenueFrames.recoveryProblem), otherwise it is a loss that needs a marker.
///   6. Failed requests are classified (ArFailure): an expired viewer link stops relocalization and routing; a network
///      failure is retried (the route too, while localized); a marker the server will no longer use is dropped for the
///      session; an answer that set markers aside as inconsistent says which.
///   7. Floors (docs/ar.md, "Floor handoff"): every floor's markers are registered for the session. Within
///      `RouteGeometry.handoffRadiusMeters` of the end of this floor's leg (a TRANSITION) the session hands off: the
///      transform and world anchor are dropped (each floor has its own canonical frame) and the next floor is localized
///      from its own markers once FloorIdentification confirms it, in its own pinned version, and its leg drawn after the
///      same version/frame check. Markers of another floor only are reported, never used; the user may accept that floor,
///      and the route is then planned again from there. The route's floor connections are re-checked every
///      `transitionCheckInterval` s; one that closed, was removed, changed direction or lost its step-free verification
///      gets the route planned again.
///
/// Every value comes from an ARFrame/ARAnchor/ARCamera callback or from the server; nothing is fabricated, and where
/// ARKit has not detected something the value stays absent. It needs a physical device: the iOS Simulator does not
/// run ARKit world tracking (ARWorldTrackingConfiguration.isSupported is false there). Delegate callbacks are delivered
/// on the main queue, and all state is mutated there.
@available(iOS 16.0, *)
public final class ARSessionManager: NSObject, ARSessionDelegate {

    /// The observations, the floor they are registered on, and the scan version the session pinned for that floor (nil
    /// before its first localization).
    public typealias Relocalize = @Sendable ([AnchorObservation], UUID, UUID?) async throws -> RelocalizationResponse
    /// The device's canonical start, and the localization it was computed with (its floor, version and frame).
    public typealias PlanRoute = @Sendable (Vec3, Localization) async throws -> RouteResponse
    /// The venue's floor connections, to re-check the route's transitions.
    public typealias ListConnections = @Sendable () async throws -> [FloorConnection]

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
        /// The meaning of the last failed request (session expired, network, marker unavailable, markers inconsistent ...).
        public var failure: ArFailure?
        /// Markers the last localization set aside as inconsistent with the others (moved, misidentified, mis-registered).
        public var setAsideMarkers: [String] = []
        /// The floor transition the session last handed off over (cleared once the next floor is localized).
        public var handoff: RouteGeometry.Handoff?
        /// Why the route had to be planned again: a floor connection on it can no longer be used as planned.
        public var transitionProblem: String?
    }

    public let session = ARSession()
    public let renderer = RouteSceneRenderer()
    public var onUpdate: ((Snapshot) -> Void)?
    public private(set) var snapshot: Snapshot

    private let registry: MarkerRegistry
    private let startFloorId: UUID
    private let relocalize: Relocalize
    private let planRoute: PlanRoute
    private let listConnections: ListConnections?
    private var lastTransitionCheckAt: TimeInterval = -.infinity
    private var transitionCheckInFlight = false
    /// Connections the route was already planned again for: a second failing check does not replan in a loop.
    private var replannedFor = Set<UUID>()
    static let transitionCheckInterval: TimeInterval = 30

    private var routePoints: [Vec3] = []
    private var routeRequested = false
    /// Bumped by `replan`: a route answer for an earlier request is dropped.
    private var routeGeneration = 0
    private var worldAnchor: (anchor: ARAnchor, poseAtSolve: Pose, addedAt: TimeInterval, seen: Bool)?
    private var lastFailureAt: TimeInterval = -.infinity
    private var lastPublishAt: TimeInterval = 0
    /// Set when retrying cannot help (an expired viewer link): no relocalization or route request is sent after it.
    private var blocked: ArFailure?
    /// Markers the server will no longer use: not sent again this session.
    private var unavailableAnchors = Set<UUID>()
    /// The world anchor's pose when a `limited` spell began, to validate the recovery.
    private var anchorAtLimitedStart: Pose?
    private var lastRouteAttemptAt: TimeInterval = -.infinity
    static let routeRetryInterval: TimeInterval = 5

    static let retryAfterFailure: TimeInterval = 1.5
    /// How long a newly added world anchor may be missing from ARFrame.anchors before that counts as a loss.
    static let worldAnchorGrace: TimeInterval = 1.0
    static let worldAnchorName = "chaya.localization"

    /// `registry`: the markers of every floor the session may reach; `floorId`: the floor the user starts on.
    public init(registry: MarkerRegistry, floorId: UUID, relocalize: @escaping Relocalize, planRoute: @escaping PlanRoute,
                listConnections: ListConnections? = nil) {
        self.registry = registry
        self.startFloorId = floorId
        self.relocalize = relocalize
        self.planRoute = planRoute
        self.listConnections = listConnections
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
        dispatch(.sessionStarted(floorId: startFloorId))
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
        checkTransitionsIfDue()
    }

    public func session(_ session: ARSession, cameraDidChangeTrackingState camera: ARCamera) {
        let at = session.currentFrame?.timestamp ?? ProcessInfo.processInfo.systemUptime
        let before = snapshot.state.phase
        if before == .localized { anchorAtLimitedStart = currentWorldAnchorPose(session.currentFrame) }
        dispatch(.trackingChanged(TrackingQuality(camera.trackingState), at: at))
        if before == .limited && snapshot.state.phase == .localized {
            // Validated recovery: the world anchor must be tracked and barely moved while tracking was limited.
            if let problem = VenueFrames.recoveryProblem(anchorAtLimitedStart: anchorAtLimitedStart,
                                                         anchorNow: currentWorldAnchorPose(session.currentFrame)) {
                dispatch(.recoveryRejected(problem))
            }
            anchorAtLimitedStart = nil
        }
        if !snapshot.state.routeVisible { renderer.hide() }
    }

    private func currentWorldAnchorPose(_ frame: ARFrame?) -> Pose? {
        guard let world = worldAnchor, let anchor = frame?.anchors.first(where: { $0.identifier == world.anchor.identifier }) else {
            return nil
        }
        return Pose(anchor.transform)
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
        guard phase == .searching || phase == .recovered || phase == .floorTransition, snapshot.state.deviceTracking == .normal,
              blocked == nil,
              frame.timestamp - lastFailureAt > Self.retryAfterFailure else { return }
        let detections = frame.anchors.compactMap { $0 as? ARImageAnchor }.map { ImageDetection($0, timestamp: frame.timestamp) }
        let observations = MarkerObservations.observe(detections, registry: registry).filter { !unavailableAnchors.contains($0.anchorId) }
        guard !observations.isEmpty else { return }
        let eligible = MarkerObservations.eligible(observations)
        if eligible.isEmpty {
            snapshot.lastRejectedObservation = observations.compactMap { MarkerObservations.eligibility($0).reason }.first
            publish(force: false)
            return
        }
        snapshot.lastRejectedObservation = nil
        dispatch(.markersObserved(eligible))
        // Not solving: the floor is not identified yet (markers of another floor, or of several), or nothing applied.
        guard snapshot.state.phase == .solving, let pending = snapshot.state.pending, let floorId = snapshot.state.floorId,
              let first = pending.observations.first else { return }

        // The world anchor is added in this frame, at the pose the first marker used was measured at.
        let anchorPose = first.observedPose
        let anchor = ARAnchor(name: Self.worldAnchorName, transform: anchorPose.simdTransform)
        session.add(anchor: anchor)
        let requestId = pending.requestId
        let used = pending.observations.map(\.anchorId)
        let body = pending.observations.map(\.asAnchorObservation)
        let pinnedVersion = snapshot.state.pinnedScanVersionId
        let cameraPose = Pose(frame.camera.transform)
        let relocalize = self.relocalize
        Task { @MainActor [weak self] in
            do {
                let result = try await relocalize(body, floorId, pinnedVersion)
                self?.solved(requestId: requestId, result: result, floorId: floorId, anchorIds: used, anchor: anchor,
                             anchorPose: anchorPose, cameraPose: cameraPose)
            } catch {
                self?.failed(requestId: requestId, error: error, anchor: anchor, anchorIds: used)
            }
        }
    }

    private func solved(requestId: Int, result: RelocalizationResponse, floorId: UUID, anchorIds: [UUID], anchor: ARAnchor,
                        anchorPose: Pose, cameraPose: Pose) {
        let now = ProcessInfo.processInfo.systemUptime
        let setAside = Set(result.rejectedAnchorIds ?? [])
        snapshot.setAsideMarkers = registry.markers.filter { setAside.contains($0.anchor.id) }.map(\.anchor.markerIdentifier)
        snapshot.failure = nil
        let localization = Localization(deviceToVenue: result.deviceToVenueTransform, anchorIds: anchorIds.filter { !setAside.contains($0) },
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
        snapshot.handoff = nil
        if snapshot.route == nil {
            if !routeRequested {
                routeRequested = true
                fetchRoute(from: VenueFrames.devicePositionInVenue(camera: cameraPose, deviceToVenue: localization.deviceToVenue),
                           localization: localization)
            }
        } else {
            // A new localization may be in another frame than the route, or on the next floor after a handoff: re-check
            // before drawing that floor's leg.
            applyRoute()
        }
    }

    /// The user confirmed being on the floor whose markers the camera sees (floorCheck .unexpected). The session localizes
    /// there, and the route is planned again from there: the old one assumed another floor.
    public func acceptDetectedFloor() {
        let before = snapshot.state.floorId
        dispatch(.acceptDetectedFloor)
        if snapshot.state.floorId != before { replan() }
    }

    /// Drops the route and plans it again: at once while localized (updateRoute), otherwise at the next localization.
    private func replan() {
        routeGeneration += 1
        snapshot.route = nil
        snapshot.progress = nil
        routeRequested = false
        lastRouteAttemptAt = -.infinity
        routePoints = []
        renderer.setRoute([])
        renderer.hide()
        publish(force: true)
    }

    /// The device reached the end of this floor's leg: leave the floor. The transform and world anchor belong to this
    /// floor's frame and are dropped; nothing is drawn until the next floor is identified and localized.
    private func handOff(_ handoff: RouteGeometry.Handoff) {
        dispatch(.floorHandoff(toFloorId: handoff.toFloorId))
        guard snapshot.state.phase == .floorTransition else { return }
        let world = worldAnchor
        worldAnchor = nil // before the removal is reported: leaving the floor is not a loss
        if let world { session.remove(anchor: world.anchor) }
        routePoints = []
        snapshot.progress = nil
        snapshot.handoff = handoff
        renderer.setRoute([])
        renderer.hide()
        publish(force: true)
    }

    /// Every `transitionCheckInterval` s while the route crosses floors: are its connections still usable as planned?
    private func checkTransitionsIfDue() {
        let now = ProcessInfo.processInfo.systemUptime
        guard let listConnections, !transitionCheckInFlight, blocked == nil, let route = snapshot.route,
              !route.floorTransitions.isEmpty, now - lastTransitionCheckAt > Self.transitionCheckInterval else { return }
        lastTransitionCheckAt = now
        transitionCheckInFlight = true
        Task { @MainActor [weak self] in
            let connections = try? await listConnections() // a failed check is repeated at the next interval
            guard let self else { return }
            self.transitionCheckInFlight = false
            guard let connections, self.snapshot.route == route else { return }
            let check = FloorTransitions.stillUsable(route, connections: connections)
            if check.usable {
                self.snapshot.transitionProblem = nil
            } else if check.connectionIds.allSatisfy(self.replannedFor.contains) {
                // Planned again already, and the new route still uses it: say so rather than replanning in a loop.
                self.snapshot.transitionProblem = "This route uses a connection that is no longer usable: "
                    + check.problems.joined(separator: "; ") + "."
            } else {
                self.replannedFor.formUnion(check.connectionIds)
                self.snapshot.transitionProblem = "Route changed: \(check.problems.joined(separator: "; ")). Planning it again."
                self.replan()
            }
            self.publish(force: true)
        }
    }

    private func failed(requestId: Int, error: Error, anchor: ARAnchor, anchorIds: [UUID]) {
        session.remove(anchor: anchor)
        lastFailureAt = session.currentFrame?.timestamp ?? ProcessInfo.processInfo.systemUptime
        let failure = ArFailure.classify(error)
        snapshot.failure = failure
        switch failure.kind {
        case .sessionExpired:
            blocked = failure
        case .anchorUnavailable:
            // The server named the anchor, or there was only one in the request: drop it for this session.
            if let id = failure.anchorId { unavailableAnchors.insert(id) } else if anchorIds.count == 1 { unavailableAnchors.insert(anchorIds[0]) }
        default:
            break
        }
        dispatch(.relocalizationFailed(requestId: requestId, error: "\(failure.kind.rawValue): \(failure.code): \(failure.detail)"))
        publish(force: true)
    }

    /// Planned once, from where the device was when first localized, with that localization (the caller pins the route to
    /// its scan version when the destination is on its floor), and again only after `replan`. A failure is a routing
    /// error, not a localization one: the localization stays.
    private func fetchRoute(from start: Vec3, localization: Localization) {
        guard blocked == nil else { return }
        lastRouteAttemptAt = ProcessInfo.processInfo.systemUptime
        let planRoute = self.planRoute
        let generation = routeGeneration
        Task { @MainActor [weak self] in
            do {
                let route = try await planRoute(start, localization)
                guard let self, generation == self.routeGeneration else { return } // superseded by a replan
                self.snapshot.route = route
                self.applyRoute()
            } catch {
                guard let self, generation == self.routeGeneration else { return }
                let failure = ArFailure.classify(error)
                if failure.kind == .sessionExpired { self.blocked = failure }
                self.routeRequested = false // retried while localized (updateRoute), or at the next localization
                self.snapshot.failure = failure
                self.snapshot.routeError = "\(failure.kind.rawValue): \(failure.code): \(failure.detail)"
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
        // A route that failed to load (for example while offline) is retried while localized.
        let uptime = ProcessInfo.processInfo.systemUptime
        if snapshot.route == nil && !routeRequested && blocked == nil && state.canAdvanceRoute
            && uptime - lastRouteAttemptAt > Self.routeRetryInterval {
            routeRequested = true
            fetchRoute(from: device, localization: localization)
        }
        snapshot.progress = RouteGeometry.advance(snapshot.progress, canAdvance: state.canAdvanceRoute, points: routePoints, device: device)
        // This floor's leg was drawn and ends at a stair, ramp or elevator the device has reached: hand off to the next floor.
        if state.canAdvanceRoute, !routePoints.isEmpty, let route = snapshot.route,
           let handoff = RouteGeometry.floorHandoff(route, floorId: localization.floorId,
                                                    remainingMeters: snapshot.progress?.remainingMeters) {
            handOff(handoff)
            return
        }
        renderer.update(venueToWorld: VenueFrames.venueToWorld(deviceToVenue: deviceToVenue),
                        nextIndex: snapshot.progress?.nextIndex ?? 0, visible: !routePoints.isEmpty)
        publish(force: false)
    }
}
#endif
