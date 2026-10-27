import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// What a failed AR request means for the session. Mirrors apps/web/lib/ar-errors.ts: each state is named, shown, and
/// decides whether the session keeps retrying on its own (docs/ar.md, "Failure states").
public enum ArFailureKind: String, Equatable, Sendable {
    /// 401: the viewer link (or sign-in) expired. Retrying cannot help; relocalization and routing stop.
    case sessionExpired = "SESSION_EXPIRED"
    /// No HTTP answer at all (offline, DNS, TLS, a dropped connection). Retried; a route already drawn stays usable.
    case networkUnavailable = "NETWORK_UNAVAILABLE"
    /// The server will not use this marker any more (deleted, not calibrated, its pose in another frame or version).
    case anchorUnavailable = "ANCHOR_UNAVAILABLE"
    /// The markers seen together disagree (one moved or misidentified).
    case anchorsInconsistent = "ANCHORS_INCONSISTENT"
    /// The floor was republished.
    case versionChanged = "VERSION_CHANGED"
    case refused = "REFUSED"
}

public struct ArFailure: Equatable, Sendable {
    public let kind: ArFailureKind
    public let code: String
    public let detail: String
    public let retryable: Bool
    /// For anchorUnavailable: the anchor the server named, when it did.
    public let anchorId: UUID?
    public let action: String

    static let anchorCodes: Set<String> = ["ANCHOR_NOT_CALIBRATED", "ANCHOR_FRAME_STALE", "ANCHOR_UNVERSIONED", "ANCHOR_VERSION_MISMATCH"]

    public static func classify(_ error: Error) -> ArFailure {
        if let api = error as? APIError {
            let anchorId = firstUUID(in: api.detail)
            if api.status == 401 {
                return ArFailure(kind: .sessionExpired, code: api.code, detail: api.detail, retryable: false, anchorId: nil,
                                 action: "The viewer link has expired. Open a new viewer link, then start AR again.")
            }
            if anchorCodes.contains(api.code) || (api.status == 404 && api.detail.lowercased().contains("anchor")) {
                return ArFailure(kind: .anchorUnavailable, code: api.code, detail: api.detail, retryable: false, anchorId: anchorId,
                                 action: "This marker can no longer be used. Point the camera at another registered marker.")
            }
            if api.code == "RELOCALIZATION_INCONSISTENT" {
                return ArFailure(kind: .anchorsInconsistent, code: api.code, detail: api.detail, retryable: true, anchorId: nil,
                                 action: "The markers in view disagree: one was moved or is the wrong print. Point the camera at one "
                                    + "marker at a time, or include a third marker.")
            }
            if api.code == "VERSION_MISMATCH" {
                return ArFailure(kind: .versionChanged, code: api.code, detail: api.detail, retryable: false, anchorId: nil,
                                 action: "This floor was republished. End AR and start again to load the new version.")
            }
            if api.code == "NOT_CALIBRATED" || api.code == "METRIC_CALIBRATION_REQUIRED" {
                return ArFailure(kind: .refused, code: api.code, detail: api.detail, retryable: false, anchorId: nil,
                                 action: "The floor has no calibrated coordinate frame in metres. Physical AR guidance is unavailable until the venue is calibrated.")
            }
            if api.code == "METRIC_EVIDENCE_INSUFFICIENT" {
                return ArFailure(kind: .refused, code: api.code, detail: api.detail, retryable: false, anchorId: nil,
                                 action: "The floor was calibrated from hand-entered points rather than measured observations. Physical AR guidance requires calibration from marked observations.")
            }
            if api.code == "POI_NOT_FOUND" || (api.status == 404 && (api.detail.localizedCaseInsensitiveContains("poi") || api.detail.localizedCaseInsensitiveContains("destination"))) {
                return ArFailure(kind: .refused, code: api.code, detail: api.detail, retryable: false, anchorId: nil,
                                 action: "The selected destination no longer exists. Choose another destination.")
            }
            if api.code == "NO_ROUTE" || api.code == "NO_ACCESSIBLE_ROUTE" || api.code == "FLOOR_CONNECTION_UNAVAILABLE" || api.code == "NO_REACHABLE_STOP" {
                return ArFailure(kind: .refused, code: api.code, detail: api.detail, retryable: false, anchorId: nil,
                                 action: api.code == "NO_ACCESSIBLE_ROUTE"
                                     ? "No step-free route exists to the destination."
                                     : "The destination cannot be reached from your current position on the walkable area.")
            }
            if api.code == "START_OFF_NAVMESH" || api.code == "START_OUT_OF_BOUNDS" || api.code == "DESTINATION_OFF_NAVMESH" || api.code == "DESTINATION_OUT_OF_BOUNDS" || api.code == "START_FLOOR_MISMATCH" || api.code == "DESTINATION_FLOOR_MISMATCH" {
                return ArFailure(kind: .refused, code: api.code, detail: api.detail, retryable: false, anchorId: nil,
                                 action: "The start or destination is not on the calibrated walkable area for this floor.")
            }
            if api.code == "NAVMESH_NOT_READY" || api.code == "NO_PUBLISHED_NAVMESH" || api.code == "VERSION_NOT_FINALIZED" {
                return ArFailure(kind: .refused, code: api.code, detail: api.detail, retryable: false, anchorId: nil,
                                 action: "The floor's navigation mesh or scan version is not finalized yet.")
            }
            if api.code == "RELOCALIZATION_GRAVITY_MISMATCH" {
                return ArFailure(kind: .refused, code: api.code, detail: api.detail, retryable: false, anchorId: nil,
                                 action: "The marker's registered orientation or the device's gravity alignment disagrees with the venue frame.")
            }
            return ArFailure(kind: .refused, code: api.code, detail: api.detail, retryable: api.status >= 500, anchorId: nil,
                             action: "The server refused the request (\(api.code)).")
        }
        if error is URLError {
            return ArFailure(kind: .networkUnavailable, code: "NETWORK", detail: error.localizedDescription, retryable: true, anchorId: nil,
                             action: "No connection to the server. A route already shown stays usable while tracking holds; "
                                + "localizing and routing resume when the connection returns.")
        }
        return ArFailure(kind: .refused, code: "ERROR", detail: error.localizedDescription, retryable: true, anchorId: nil,
                         action: "The request failed.")
    }

    static func firstUUID(in text: String) -> UUID? {
        let pattern = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
        guard let range = text.range(of: pattern, options: .regularExpression) else { return nil }
        return UUID(uuidString: String(text[range]))
    }
}
