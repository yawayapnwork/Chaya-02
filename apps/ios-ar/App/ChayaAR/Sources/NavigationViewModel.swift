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
    @Published var selectedFloorId: UUID? { didSet { if selectedFloorId != oldValue { Task { await loadFloor() } } } }
    @Published var selectedDestinationId: UUID?

    @Published private(set) var manager: ARSessionManager?
    private let unauthenticated: APIClient
    private var api: APIClient?
    private var pois: [Poi] = []
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
        }
    }

    // MARK: - Floor -> anchors, reference images, destinations

    private func loadFloor() async {
        guard let api, let venue, let floorId = selectedFloorId else { return }
        destinations = pois.filter { $0.floorId == floorId && $0.frameStatus == .current }.sorted { $0.label < $1.label }
        selectedDestinationId = nil
        markerRows = []
        trackableCount = 0
        referenceImages = []
        await run("Loading this floor's markers…") {
            let anchors = try await api.listAnchors(venueId: venue.id, floorId: floorId)
            let registry = MarkerRegistry(anchors: anchors)
            var images: Set<ARReferenceImage> = []
            var imageProblems: [UUID: String] = [:]
            for marker in registry.markers {
                do {
                    let png = try await api.targetImage(venueId: venue.id, floorId: floorId, anchorId: marker.anchor.id)
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
            let usable = Set(images.compactMap(\.name))
            self.registry = registry.restricted(to: usable)
            self.referenceImages = images
            self.trackableCount = images.count
            self.markerRows = anchors.map { anchor in
                let support = AnchorSupport.of(anchor)
                let status = imageProblems[anchor.id] ?? support.explanation
                return MarkerRow(id: anchor.id, label: "\(anchor.markerType.rawValue) “\(anchor.markerIdentifier)”", status: status)
            }
        }
    }

    // MARK: - AR session

    func start() async {
        guard let api, let venue, let floorId = selectedFloorId, let destinationId = selectedDestinationId else { return }
        if capability == .cameraNotDetermined {
            _ = await AVCaptureDevice.requestAccess(for: .video)
            refreshCapability()
        }
        guard capability == .ready else { return }
        let manager = ARSessionManager(
            registry: registry, floorId: floorId,
            relocalize: { observations, scanVersionId in
                try await api.relocalize(venueId: venue.id, floorId: floorId, observations: observations, scanVersionId: scanVersionId)
            },
            planRoute: { start, scanVersionId in
                try await api.planRoute(venueId: venue.id, floorId: floorId, start: start, destinationPoiId: destinationId,
                                        scanVersionId: scanVersionId)
            })
        manager.onUpdate = { [weak self] snapshot in Task { @MainActor in self?.snapshot = snapshot } }
        self.manager = manager
        manager.run(referenceImages: referenceImages)
        snapshot = manager.snapshot
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
