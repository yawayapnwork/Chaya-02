import Foundation

/// What the iOS client can do with each registered anchor, and how an ARKit detection is attributed to one
/// (docs/ar.md, "iOS: Swift/ARKit").
///
/// ARKit's marker detection is image detection/tracking (`ARReferenceImage` -> `ARImageAnchor`). It detects the
/// images it is given; it has no AprilTag/ArUco detector, and a QR code has no stable printed artwork registered on
/// the server to hand it. So only calibrated IMAGE_TARGET anchors with a printed size and a versioned pose are
/// trackable; every other anchor gets an explicit reason and is never treated as detected.
///
/// Identity: each trackable anchor becomes one reference image whose `name` is the anchor's id. A detected
/// ARImageAnchor's `referenceImage.name` therefore identifies exactly one backend anchor; a name this registry did
/// not issue is not attributed to anything.
public enum AnchorSupport: String, Equatable, Sendable {
    case trackable = "TRACKABLE"
    case fiducialDetectionUnsupported = "FIDUCIAL_DETECTION_UNSUPPORTED"
    case missingPrintedSize = "MISSING_PRINTED_SIZE"
    case notCalibrated = "NOT_CALIBRATED"
    /// The pose was never entered against a scan version: the server refuses it (ANCHOR_UNVERSIONED).
    case unversioned = "UNVERSIONED"

    public var explanation: String {
        switch self {
        case .trackable: return "detectable (ARKit image tracking)"
        case .fiducialDetectionUnsupported: return "not detectable here: ARKit has no QR/ArUco/AprilTag pose detection"
        case .missingPrintedSize: return "not detectable: no printed size registered"
        case .notCalibrated: return "not usable: not calibrated"
        case .unversioned: return "not usable: its pose was never entered against a scan version; re-enter it"
        }
    }

    public static func of(_ anchor: Anchor) -> AnchorSupport {
        guard anchor.markerType == .imageTarget else { return .fiducialDetectionUnsupported }
        guard anchor.calibrationStatus == .calibrated else { return .notCalibrated }
        guard let size = anchor.markerSizeMeters, size > 0 else { return .missingPrintedSize }
        guard anchor.scanVersionId != nil, anchor.poseId != nil else { return .unversioned }
        return .trackable
    }
}

/// One registered marker the session tracks.
public struct RegisteredMarker: Equatable, Sendable {
    public let anchor: Anchor
    /// The ARReferenceImage name: the anchor id, lower-case.
    public let referenceImageName: String
    /// The printed width, the ARReferenceImage physicalWidth.
    public let physicalWidthMeters: Double
}

public struct MarkerRegistry: Equatable, Sendable {
    public let markers: [RegisteredMarker]
    private let byName: [String: RegisteredMarker]

    /// Trackable anchors only; see `AnchorSupport.of`.
    public init(anchors: [Anchor]) {
        let markers = anchors.filter { AnchorSupport.of($0) == .trackable }.map {
            RegisteredMarker(anchor: $0, referenceImageName: $0.id.apiString, physicalWidthMeters: $0.markerSizeMeters!)
        }
        self.markers = markers
        self.byName = Dictionary(uniqueKeysWithValues: markers.map { ($0.referenceImageName, $0) })
    }

    /// The registered marker a detected reference image belongs to; nil for an image this registry did not issue.
    public func marker(forReferenceImageName name: String?) -> RegisteredMarker? {
        guard let name else { return nil }
        return byName[name]
    }

    /// Keeps only the markers whose names are in `names` (e.g. those whose reference image ARKit validated).
    public func restricted(to names: Set<String>) -> MarkerRegistry {
        MarkerRegistry(markers: markers.filter { names.contains($0.referenceImageName) })
    }

    private init(markers: [RegisteredMarker]) {
        self.markers = markers
        self.byName = Dictionary(uniqueKeysWithValues: markers.map { ($0.referenceImageName, $0) })
    }

    public static func == (a: MarkerRegistry, b: MarkerRegistry) -> Bool { a.markers == b.markers }
}

/// What ARKit reported about one detected image in one frame. All fields are ARKit's own: the reference image name,
/// the ARImageAnchor transform, ARFrame.timestamp, ARImageAnchor.isTracked and ARImageAnchor.estimatedScaleFactor.
public struct ImageDetection: Equatable, Sendable {
    public var referenceImageName: String?
    public var pose: Pose
    public var timestamp: TimeInterval
    public var isTracked: Bool
    public var estimatedScaleFactor: Double

    public init(referenceImageName: String?, pose: Pose, timestamp: TimeInterval, isTracked: Bool, estimatedScaleFactor: Double) {
        self.referenceImageName = referenceImageName; self.pose = pose; self.timestamp = timestamp
        self.isTracked = isTracked; self.estimatedScaleFactor = estimatedScaleFactor
    }
}

/// A detection of a registered marker: the backend anchor it is, the pose ARKit measured (world frame, +Y up), when,
/// and whether ARKit is currently tracking it.
public struct MarkerObservation: Equatable, Sendable {
    public var anchorId: UUID
    public var markerIdentifier: String
    public var observedPose: Pose
    public var timestamp: TimeInterval
    public var isTracked: Bool
    /// Measured size / registered printed size (ARKit automatic image scale estimation). 1 means the print is exactly
    /// the registered size.
    public var estimatedScaleFactor: Double

    public var asAnchorObservation: AnchorObservation { AnchorObservation(anchorId: anchorId, observedPose: observedPose) }
}

public enum MarkerObservations {

    /// How far ARKit's estimated scale may differ from the registered printed size. A larger difference means the
    /// wrong print size (or the wrong image), and the observation would put the device in the wrong place.
    public static let scaleTolerance = 0.2

    /// Attributes each detection to its registered marker. A detection of an image the registry did not issue is
    /// dropped: it cannot be attributed to a backend anchor.
    public static func observe(_ detections: [ImageDetection], registry: MarkerRegistry) -> [MarkerObservation] {
        detections.compactMap { d in
            guard let marker = registry.marker(forReferenceImageName: d.referenceImageName) else { return nil }
            return MarkerObservation(anchorId: marker.anchor.id, markerIdentifier: marker.anchor.markerIdentifier,
                                     observedPose: d.pose, timestamp: d.timestamp, isTracked: d.isTracked,
                                     estimatedScaleFactor: d.estimatedScaleFactor)
        }
    }

    /// Whether an observation may relocalize: ARKit must be tracking the image in this frame (not reporting its last
    /// known pose), and its measured size must match the printed size.
    public static func eligibility(_ o: MarkerObservation) -> (eligible: Bool, reason: String?) {
        if !o.isTracked {
            return (false, "\(o.markerIdentifier): ARKit is not tracking it in this frame")
        }
        if !(o.estimatedScaleFactor > 0) || abs(o.estimatedScaleFactor - 1) > scaleTolerance {
            return (false, "\(o.markerIdentifier): measured at \(String(format: "%.2f", o.estimatedScaleFactor))x its registered size; check the printed size")
        }
        return (true, nil)
    }

    /// The eligible observations of one frame, at most one per anchor (the server refuses duplicates), capped at the
    /// server's limit of 16.
    public static func eligible(_ observations: [MarkerObservation]) -> [MarkerObservation] {
        var seen = Set<UUID>()
        var out: [MarkerObservation] = []
        for o in observations where eligibility(o).eligible && !seen.contains(o.anchorId) {
            seen.insert(o.anchorId)
            out.append(o)
            if out.count == 16 { break }
        }
        return out
    }
}
