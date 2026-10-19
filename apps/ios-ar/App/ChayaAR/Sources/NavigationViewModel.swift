import ARKit
import AVFoundation
import ChayaARCore
import ChayaARKitSession
import Foundation

/// Drives the app from a public viewer link to a running AR navigation session (docs/ar.md, "iOS: Swift/ARKit").
/// Everything it shows comes from the backend (venue, floors, POIs, anchors, target images, relocalization, route) or
/// from ARKit (capability, camera permission, image validation, the session itself).
@MainActor
final class NavigationViewModel: ObservableObject {

    struct MarkerRow: Identifiable, Equatable {
        let id: UUID
        let label: String
        let status: String
    }

    @Published private(set) var capability: ARCapability
    @Published private(set) var venue: Venue?
    @Published private(set) var floors: [Floor] = []
    @Published private(set) var destinations: [Poi] = []
    @Published private(set) var markerRows: [MarkerRow] = []
    @Published private(set) var trackableCount = 0
    @Published private(set) var loading: String?
    @Published private(set) var error: String?
    @Published private(set) var snapshot: ARSessionManager.Snapshot?
    /// Route step-free (elevators and ramps with measured, passing clearances only).
    @Published var stepFree = false
    /// The floor the user starts on.
    @Published var selectedFloorId: UUID? { didSet { if selectedFloorId != oldValue { showFloor() } } }
    @Published var selectedDestinationId: UUID?

    @Published private(set) var manager: ARSessionManager?
    private let unauthenticated: APIClient
    private var api: APIClient?
    private var pois: [Poi] = []
    /// Every floor's anchors and usable reference images: a route may cross floors, and ARKit's detection images are fixed
    /// when the session starts.
    private var anchors: [Anchor] = []
    private var imageProblems: [UUID: String] = [:]
    private var registry = MarkerRegistry(anchors: [])
    private var referenceImages: Set<ARReferenceImage> = []

    init(apiBaseURL: URL) {
        unauthenticated = APIClient(baseURL: apiBaseURL, authorizer: nil)
        capability = ARCapability.evaluate(worldTrackingSupported: ARSessionManager.isSupported,
                                           camera: AVCaptureDevice.authorizationStatus(for: .video))
    }

    var canStart: Bool {
        !capability.blocksAR && trackableCount > 0 && selectedDestinationId != nil && loading == nil && manager == nil
    }

    // MARK: - Viewer link -> venue

    func open(link text: String) async {
        guard let secret = ViewerLink.secret(from: text) else {
            error = "Paste a Chaya viewer link (…/viewer?link=…) or its secret."
            return
        }
        await run("Opening the viewer link…") {
            let token = try await self.unauthenticated.exchangeViewerLink(secret: secret)
            let api = self.unauthenticated.with(authorizer: ViewerTokenAuthorizer(token: token))
            async let venue = api.getVenue(venueId: token.venueId)
            async let floors = api.listFloors(venueId: token.venueId)
            async let pois = api.listPois(venueId: token.venueId)
            self.api = api
            self.venue = try await venue
            self.floors = try await floors.sorted { $0.level < $1.level }
            self.pois = try await pois
            try await self.loadMarkers(api: api, venueId: token.venueId)
        }
        showFloor()
    }

    // MARK: - Floors -> anchors, reference images, destinations

    /// Every floor's anchors and target images. A session tracks all of them: after a stair or elevator handoff the next
    /// floor is identified and localized from its own markers (docs/ar.md, "Floor handoff").
    private func loadMarkers(api: APIClient, venueId: UUID) async throws {
        var anchors: [Anchor] = []
        for floor in floors { anchors += try await api.listAnchors(venueId: venueId, floorId: floor.id) }
        let registry = MarkerRegistry(anchors: anchors)
        var images: Set<ARReferenceImage> = []
        var imageProblems: [UUID: String] = [:]
        for marker in registry.markers {
            do {
                let png = try await api.targetImage(venueId: venueId, floorId: marker.anchor.floorId, anchorId: marker.anchor.id)
                let image = try ReferenceImages.make(png: png, marker: marker)
                if let problem = await ReferenceImages.validate(image) {
                    imageProblems[marker.anchor.id] = "ARKit rejected the image: \(problem)"
                } else {
                    images.insert(image)
                }
            } catch {
                imageProblems[marker.anchor.id] = "target image unavailable: \(Self.describe(error))"
            }
        }
        self.anchors = anchors
        self.imageProblems = imageProblems
        self.registry = registry.restricted(to: Set(images.compactMap(\.name)))
        self.referenceImages = images
    }

    /// The start floor's markers, and destinations on every floor (a route to another floor crosses a registered stair,
    /// ramp or elevator). Only POIs placed in their floor's current frame.
    private func showFloor() {
        guard let floorId = selectedFloorId else { return }
        let level = Dictionary(uniqueKeysWithValues: floors.map { ($0.id, $0.level) })
        destinations = pois.filter { $0.floorId != nil && $0.frameStatus == .current }.sorted {
            let a = level[$0.floorId!] ?? .max, b = level[$1.floorId!] ?? .max
            return a != b ? a < b : $0.label < $1.label
        }
        selectedDestinationId = nil
        let here = anchors.filter { $0.floorId == floorId }
        trackableCount = registry.markers.filter { $0.anchor.floorId == floorId }.count
        markerRows = here.map { anchor in
            let status = imageProblems[anchor.id] ?? AnchorSupport.of(anchor).explanation
            return MarkerRow(id: anchor.id, label: "\(anchor.markerType.rawValue) “\(anchor.markerIdentifier)”", status: status)
        }
    }

    func floorName(_ id: UUID?) -> String {
        guard let id else { return "an unknown floor" }
        return floors.first { $0.id == id }?.name ?? id.apiString
    }

    /// "Café", or "Café (Level 2)" for a destination on another floor than the start.
    func destinationLabel(_ poi: Poi) -> String {
        poi.floorId == selectedFloorId ? poi.label : "\(poi.label) (\(floorName(poi.floorId)))"
    }

    // MARK: - AR session

    func start() async {
        guard let api, let venue, let floorId = selectedFloorId, let destinationId = selectedDestinationId,
              let destination = destinations.first(where: { $0.id == destinationId }) else { return }
        if capability == .cameraNotDetermined {
            _ = await AVCaptureDevice.requestAccess(for: .video)
            refreshCapability()
        }
        guard capability == .ready else { return }
        let stepFree = self.stepFree
        let manager = ARSessionManager(
            registry: registry, floorId: floorId,
            relocalize: { observations, onFloor, scanVersionId in
                try await api.relocalize(venueId: venue.id, floorId: onFloor, observations: observations, scanVersionId: scanVersionId)
            },
            planRoute: { [stepFree, destinationFloorId = destination.floorId] start, localization, blockedRegions in
                // The same authenticated, venue-scoped route contract as the web client: on the localization's scan version
                // when the destination is on its floor, otherwise on what each floor publishes now.
                try await api.planRoute(venueId: venue.id, floorId: localization.floorId, start: start, destinationPoiId: destinationId,
                                        scanVersionId: RouteGeometry.routeScanVersion(destinationFloorId: destinationFloorId,
                                                                                      localization: localization),
                                        accessibility: stepFree ? "STEP_FREE" : "STANDARD", blockedRegions: blockedRegions)
            },
            listConnections: { try await api.listFloorConnections(venueId: venue.id) }, stepFree: stepFree)
        manager.onUpdate = { [weak self] snapshot in Task { @MainActor in self?.snapshot = snapshot } }
        self.manager = manager
        manager.run(referenceImages: referenceImages)
        snapshot = manager.snapshot
    }

    /// The user confirmed being on the floor the markers in view are registered on.
    func acceptDetectedFloor() {
        manager?.acceptDetectedFloor()
    }

    func stop() {
        manager?.stop()
        manager = nil
        snapshot = nil
    }

    func refreshCapability() {
        capability = ARCapability.evaluate(worldTrackingSupported: ARSessionManager.isSupported,
                                           camera: AVCaptureDevice.authorizationStatus(for: .video))
    }

    // MARK: -

    private func run(_ what: String, _ body: @escaping () async throws -> Void) async {
        loading = what
        error = nil
        do { try await body() } catch { self.error = Self.describe(error) }
        loading = nil
    }

    static func describe(_ error: Error) -> String {
        if let api = error as? APIError { return "\(api.code): \(api.detail)" }
        return error.localizedDescription
    }
}
