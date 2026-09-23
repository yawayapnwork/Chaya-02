import Foundation

/// Position + unit quaternion orientation, in one frame. Mirrors dev.chaya.api.ar.ArDtos.Pose and
/// apps/web/lib/ar-anchor-math.ts's `Pose`. Never assumed normalized -- see AnchorMath, which normalizes
/// defensively before using q.
public struct Pose: Codable, Equatable, Sendable {
    public var x: Double
    public var y: Double
    public var z: Double
    public var qx: Double
    public var qy: Double
    public var qz: Double
    public var qw: Double

    public init(x: Double, y: Double, z: Double, qx: Double = 0, qy: Double = 0, qz: Double = 0, qw: Double = 1) {
        self.x = x; self.y = y; self.z = z
        self.qx = qx; self.qy = qy; self.qz = qz; self.qw = qw
    }

    public static let identity = Pose(x: 0, y: 0, z: 0)
}

public enum MarkerType: String, Codable, Sendable {
    case qrCode = "QR_CODE"
    case arucoMarker = "ARUCO_MARKER"
    case imageTarget = "IMAGE_TARGET"
    case aprilTag = "APRILTAG"
}

public enum CalibrationStatus: String, Codable, Sendable {
    case uncalibrated = "UNCALIBRATED"
    case calibrated = "CALIBRATED"
    case stale = "STALE"
}

/// Mirrors dev.chaya.api.ar.ArDtos.Anchor.
public struct Anchor: Codable, Equatable, Sendable {
    public var id: UUID
    public var venueId: UUID
    public var floorId: UUID
    public var markerType: MarkerType
    public var markerIdentifier: String
    public var physicalPose: Pose
    public var digitalPose: Pose
    public var calibrationStatus: CalibrationStatus
    public var lastCalibratedAt: Date?

    public init(id: UUID, venueId: UUID, floorId: UUID, markerType: MarkerType, markerIdentifier: String,
                physicalPose: Pose, digitalPose: Pose, calibrationStatus: CalibrationStatus, lastCalibratedAt: Date?) {
        self.id = id; self.venueId = venueId; self.floorId = floorId
        self.markerType = markerType; self.markerIdentifier = markerIdentifier
        self.physicalPose = physicalPose; self.digitalPose = digitalPose
        self.calibrationStatus = calibrationStatus; self.lastCalibratedAt = lastCalibratedAt
    }
}

/// A live detection of one already-registered marker, reported during relocalization. `observedPose` must
/// be the marker's pose as ARKit's own tracking session currently reports it -- real sensor/vision output,
/// never fabricated. See docs/ar.md "Do not fabricate device sensor data".
public struct AnchorObservation: Codable, Sendable {
    public var anchorId: UUID
    public var observedPose: Pose

    public init(anchorId: UUID, observedPose: Pose) {
        self.anchorId = anchorId
        self.observedPose = observedPose
    }
}

/// Mirrors dev.chaya.api.ar.ArDtos.RelocalizationResponse. `residualMeters` is a real measured
/// disagreement across the anchors used, never a claimed accuracy figure -- see docs/ar.md.
public struct RelocalizationResponse: Codable, Sendable {
    public var deviceToVenueTransform: Pose
    public var residualMeters: Double
    public var anchorsUsed: Int
}

public enum WaypointKind: String, Codable, Sendable {
    case start = "START"
    case waypoint = "WAYPOINT"
    case transition = "TRANSITION"
    case destination = "DESTINATION"
}

/// Mirrors dev.chaya.api.navigation.NavigationDtos.Waypoint.
public struct Waypoint: Codable, Sendable {
    public var x: Double
    public var y: Double
    public var z: Double
    public var floorId: UUID
    public var kind: WaypointKind
}

/// Mirrors dev.chaya.api.navigation.NavigationDtos.FloorTransition.
public struct FloorTransition: Codable, Sendable {
    public var fromFloorId: UUID
    public var toFloorId: UUID
    public var connectorType: String
    public var poiId: UUID
}

/// Mirrors dev.chaya.api.navigation.NavigationDtos.RouteResponse (docs/navigation.md). Both AR clients
/// render this same route; neither computes its own path.
public struct RouteResponse: Codable, Sendable {
    public var waypoints: [Waypoint]
    public var distanceMeters: Double
    public var estimatedDurationSeconds: Double
    public var floorTransitions: [FloorTransition]
    public var accessibilityProfile: String
    public var accessibilityConstraintsApplied: [String]
}
