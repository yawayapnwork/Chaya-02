import AVFoundation
import Foundation

/// Whether this build of the app has been validated on a physical device (docs/ar-ios-validation.md). ARKit world
/// tracking cannot run in the Simulator, and this app was written without access to a Mac or an iPhone, so it has
/// never been run. The UI shows this state on every screen. Change it only after the device procedure has passed and
/// its record has been filled in.
enum DeviceValidation {
    enum Status: Equatable {
        case notValidated
        case validated(date: String, device: String, iOS: String)
    }

    static let status: Status = .notValidated

    static var banner: String? {
        switch status {
        case .notValidated:
            return "NOT DEVICE-VALIDATED: this build has never been run on a physical iPhone. Marker detection, " +
                "localization accuracy and route placement are unverified (docs/ar-ios-validation.md)."
        case .validated:
            return nil
        }
    }
}

/// What stops AR navigation on this device, each as its own explicit state. Nothing falls back to a simulated view.
enum ARCapability: Equatable {
    /// ARWorldTrackingConfiguration.isSupported is false: the Simulator, or a device without an A9 or later.
    case worldTrackingUnsupported
    case cameraDenied
    case cameraRestricted
    /// The camera has not been asked for yet; the app asks when the user starts AR.
    case cameraNotDetermined
    case ready

    static func evaluate(worldTrackingSupported: Bool, camera: AVAuthorizationStatus) -> ARCapability {
        guard worldTrackingSupported else { return .worldTrackingUnsupported }
        switch camera {
        case .authorized: return .ready
        case .denied: return .cameraDenied
        case .restricted: return .cameraRestricted
        case .notDetermined: return .cameraNotDetermined
        @unknown default: return .cameraDenied
        }
    }

    var blocksAR: Bool { self != .ready && self != .cameraNotDetermined }

    var explanation: String {
        switch self {
        case .worldTrackingUnsupported:
            return "WORLD_TRACKING_UNSUPPORTED: this device cannot run ARKit world tracking. The iOS Simulator never can; " +
                "AR navigation needs a physical iPhone or iPad (A9 or later)."
        case .cameraDenied:
            return "CAMERA_PERMISSION_DENIED: allow camera access for Chaya AR in Settings > Privacy & Security > Camera."
        case .cameraRestricted:
            return "CAMERA_RESTRICTED: camera access is restricted on this device (Screen Time or device management)."
        case .cameraNotDetermined:
            return "Camera access will be requested when AR starts."
        case .ready:
            return "Ready."
        }
    }
}

/// A public viewer link as the web app issues it (/viewer?link=<secret>), or the bare secret.
enum ViewerLink {
    static func secret(from text: String) -> String? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        if let components = URLComponents(string: trimmed), components.scheme != nil {
            return components.queryItems?.first(where: { $0.name == "link" })?.value.flatMap { $0.isEmpty ? nil : $0 }
        }
        return trimmed.contains("/") || trimmed.contains(" ") ? nil : trimmed
    }
}
