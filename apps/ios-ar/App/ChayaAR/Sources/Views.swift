import ARKit
import ChayaARCore
import ChayaARKitSession
import SceneKit
import SwiftUI

@main
struct ChayaARApp: App {
    @StateObject private var model = NavigationViewModel(apiBaseURL: AppConfig.apiBaseURL)

    var body: some Scene {
        WindowGroup {
            if model.manager != nil {
                ARNavigationScreen(model: model)
            } else {
                SetupScreen(model: model)
            }
        }
    }
}

enum AppConfig {
    /// ChayaAPIBaseURL in Info.plist, set from the CHAYA_API_BASE_URL build setting (App/project.yml).
    static var apiBaseURL: URL {
        let value = Bundle.main.object(forInfoDictionaryKey: "ChayaAPIBaseURL") as? String ?? ""
        guard let url = URL(string: value), url.scheme != nil else {
            fatalError("ChayaAPIBaseURL is not set: build with CHAYA_API_BASE_URL=https://your-chaya-host")
        }
        return url
    }
}

struct ValidationBanner: View {
    var body: some View {
        if let text = DeviceValidation.banner {
            Text(text)
                .font(.footnote.weight(.semibold))
                .padding(8)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color.orange.opacity(0.9))
                .foregroundColor(.black)
                .accessibilityIdentifier("device-validation-banner")
        }
    }
}

struct SetupScreen: View {
    @ObservedObject var model: NavigationViewModel
    @State private var link = ""

    var body: some View {
        NavigationStack {
            Form {
                Section { ValidationBanner() }
                if model.capability.blocksAR {
                    Section("AR unavailable") {
                        Text(model.capability.explanation).accessibilityIdentifier("ar-unavailable")
                    }
                }
                Section("Viewer link") {
                    TextField("https://…/viewer?link=…", text: $link)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    Button("Open") { Task { await model.open(link: link) } }
                        .disabled(link.isEmpty || model.loading != nil)
                }
                if let venue = model.venue {
                    Section(venue.name) {
                        Picker("Floor", selection: $model.selectedFloorId) {
                            Text("Select…").tag(UUID?.none)
                            ForEach(model.floors) { Text($0.name).tag(UUID?.some($0.id)) }
                        }
                        Picker("Destination", selection: $model.selectedDestinationId) {
                            Text("Select…").tag(UUID?.none)
                            ForEach(model.destinations) { Text($0.label).tag(UUID?.some($0.id)) }
                        }
                        .disabled(model.selectedFloorId == nil)
                    }
                }
                if model.selectedFloorId != nil {
                    Section("Registered markers on this floor") {
                        if model.markerRows.isEmpty && model.loading == nil { Text("None.") }
                        ForEach(model.markerRows) { row in
                            VStack(alignment: .leading) {
                                Text(row.label)
                                Text(row.status).font(.caption).foregroundColor(.secondary)
                            }
                        }
                        if !model.markerRows.isEmpty && model.trackableCount == 0 && model.loading == nil {
                            Text("NO_DETECTABLE_ANCHORS: this floor has no calibrated IMAGE_TARGET anchor whose image ARKit accepts.")
                                .foregroundColor(.orange)
                        }
                    }
                }
                if let loading = model.loading { Section { ProgressView(loading) } }
                if let error = model.error { Section { Text(error).foregroundColor(.red) } }
                Section {
                    Button("Start AR navigation") { Task { await model.start() } }
                        .disabled(!model.canStart)
                }
            }
            .navigationTitle("Chaya AR")
            .onAppear { model.refreshCapability() }
        }
    }
}

struct ARNavigationScreen: View {
    @ObservedObject var model: NavigationViewModel

    var body: some View {
        ZStack(alignment: .top) {
            if let manager = model.manager {
                ARSceneView(manager: manager).ignoresSafeArea()
            }
            VStack(alignment: .leading, spacing: 4) {
                ValidationBanner()
                if let snapshot = model.snapshot { StatusText(snapshot: snapshot) }
                Button("End AR") { model.stop() }
                    .buttonStyle(.borderedProminent)
            }
            .padding(8)
            .background(Color.black.opacity(0.55))
            .foregroundColor(.white)
        }
    }
}

struct StatusText: View {
    let snapshot: ARSessionManager.Snapshot

    var body: some View {
        let s = snapshot.state
        VStack(alignment: .leading, spacing: 2) {
            Text("\(Self.name(s.phase)) · tracking \(Self.describe(s.trackingStatus))")
                .font(.headline)
                .accessibilityIdentifier("ar-state")
            switch s.phase {
            case .searching:
                Text(s.deviceTracking == .normal ? "Point the camera at a registered marker."
                                                 : "Waiting for ARKit tracking (\(s.deviceTracking.description)). Move the phone slowly.")
            case .solving:
                Text("Marker seen (\(s.pending?.observations.map(\.markerIdentifier).joined(separator: ", ") ?? "")): localizing…")
            case .localized, .limited:
                if let loc = s.localization {
                    Text("Localized in scan version \(String(loc.scanVersionId.apiString.prefix(8))) from \(loc.anchorIds.count) marker(s)" +
                         (loc.residualMeters.map { String(format: ", residual %.2f m", $0) } ?? " (residual unknown: one marker)"))
                }
                if s.phase == .limited { Text("Tracking limited: route position frozen.") }
                if let p = snapshot.progress {
                    Text(String(format: "%.1f m to go, %.1f m off route", p.remainingMeters, p.offRouteMeters))
                } else if snapshot.route == nil && snapshot.routeError == nil {
                    Text("Fetching route…")
                }
            case .trackingLost:
                Text("Tracking lost (\(s.lostReason ?? "")). Route position frozen" +
                     (snapshot.progress.map { String(format: " at %.1f m to go", $0.remainingMeters) } ?? "") + ".")
            case .recovered:
                Text("Tracking recovered. Point the camera at a registered marker to relocalize before the route returns.")
            case .ended:
                Text(s.lastError ?? "Session ended.")
            case .idle:
                EmptyView()
            }
            if let rejected = snapshot.lastRejectedObservation { Text("Marker not usable: \(rejected)").foregroundColor(.yellow) }
            if let error = s.lastError, s.phase != .ended { Text("Last relocalization failed: \(error)").foregroundColor(.yellow) }
            if let error = snapshot.routeError { Text("Route: \(error)").foregroundColor(.yellow) }
            if let route = snapshot.route, let transition = route.floorTransitions.first {
                Text("The route continues on another floor via \(transition.connectorType).")
            }
        }
        .font(.subheadline)
    }

    static func name(_ phase: NavigationPhase) -> String {
        switch phase {
        case .idle: return "IDLE"
        case .searching: return "SEARCHING"
        case .solving: return "SOLVING"
        case .localized: return "LOCALIZED"
        case .limited: return "LIMITED"
        case .trackingLost: return "TRACKING_LOST"
        case .recovered: return "RECOVERED"
        case .ended: return "ENDED"
        }
    }

    static func describe(_ status: TrackingStatus) -> String {
        switch status {
        case .normal: return "normal"
        case .limited(let reason): return "limited (\(reason.rawValue))"
        case .relocalizing: return "relocalizing"
        case .lost: return "lost"
        case .recovered: return "recovered"
        }
    }
}

/// The camera view: an ARSCNView running the manager's ARSession, with the route renderer's nodes in its scene.
struct ARSceneView: UIViewRepresentable {
    let manager: ARSessionManager

    func makeUIView(context: Context) -> ARSCNView {
        let view = ARSCNView(frame: .zero)
        view.session = manager.session
        view.scene = SCNScene()
        view.automaticallyUpdatesLighting = true
        manager.renderer.attach(to: view.scene)
        return view
    }

    func updateUIView(_ view: ARSCNView, context: Context) {}
}
