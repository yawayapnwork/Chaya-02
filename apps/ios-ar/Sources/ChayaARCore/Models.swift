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

/// A point, in whichever frame the caller says. Canonical venue points are metres, +Z up (docs/coordinate-frames.md);
/// ARKit world points are metres, gravity-aligned, +Y up.
public struct Vec3: Codable, Equatable, Sendable {
    public var x: Double
    public var y: Double
    public var z: Double

    public init(_ x: Double, _ y: Double, _ z: Double) {
        self.x = x; self.y = y; self.z = z
    }
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
    /// Printed width in metres. Required for IMAGE_TARGET, the only marker type ARKit can detect here (docs/ar.md).
    public var markerSizeMeters: Double?
    public var physicalPose: Pose
    /// Canonical venue pose (+Z up). For an IMAGE_TARGET: the printed image's centre, with the image's own axes
    /// (docs/ar.md, "Marker pose convention").
    public var digitalPose: Pose
    public var calibrationStatus: CalibrationStatus
    public var lastCalibratedAt: Date?
    /// The coordinate frame `digitalPose` is expressed in.
    public var coordinateFrameId: UUID?
    /// The scan version the pose was entered against (ar_anchor_pose, V28). nil for an anchor whose pose was never entered
    /// against a version: the server refuses it (ANCHOR_UNVERSIONED), so it is never registered for tracking.
    public var scanVersionId: UUID?
    /// The immutable versioned pose record these values are, and its revision. nil exactly when scanVersionId is.
    public var poseId: UUID?
    public var poseRevision: Int?

    public init(id: UUID, venueId: UUID, floorId: UUID, markerType: MarkerType, markerIdentifier: String,
                markerSizeMeters: Double?, physicalPose: Pose, digitalPose: Pose, calibrationStatus: CalibrationStatus,
                lastCalibratedAt: Date?, coordinateFrameId: UUID?, scanVersionId: UUID? = nil, poseId: UUID? = nil,
                poseRevision: Int? = nil) {
        self.id = id; self.venueId = venueId; self.floorId = floorId
        self.markerType = markerType; self.markerIdentifier = markerIdentifier; self.markerSizeMeters = markerSizeMeters
        self.physicalPose = physicalPose; self.digitalPose = digitalPose
        self.calibrationStatus = calibrationStatus; self.lastCalibratedAt = lastCalibratedAt
        self.coordinateFrameId = coordinateFrameId; self.scanVersionId = scanVersionId
        self.poseId = poseId; self.poseRevision = poseRevision
    }
}

/// Mirrors dev.chaya.api.ar.ArDtos.AnchorObservation. `observedPose` is the pose of that anchor's own marker as ARKit's
/// world tracking measured it (an ARImageAnchor transform: metres, gravity-aligned, +Y up). Never fabricated -- see
/// docs/ar.md "Do not fabricate device sensor data".
public struct AnchorObservation: Codable, Equatable, Sendable {
    public var anchorId: UUID
    public var observedPose: Pose

    public init(anchorId: UUID, observedPose: Pose) {
        self.anchorId = anchorId
        self.observedPose = observedPose
    }
}

/// Mirrors dev.chaya.api.ar.ArDtos.RelocalizationResponse. `residualMeters` is a real measured disagreement across the
/// anchors used, never a claimed accuracy figure, and nil (unknown) when only one anchor was used -- see docs/ar.md.
/// `deviceToVenueTransform` maps ARKit world coordinates to canonical venue metres, in `coordinateFrameId` of
/// `scanVersionId` (the version the floor published when the server solved it).
public struct RelocalizationResponse: Codable, Equatable, Sendable {
    public var deviceToVenueTransform: Pose
    public var residualMeters: Double?
    public var anchorsUsed: Int
    public var gravityTiltDegrees: Double
    public var deviceFrameConvention: String
    public var coordinateFrameId: UUID
    public var scanVersionId: UUID
}

public enum WaypointKind: String, Codable, Sendable {
    case start = "START"
    case waypoint = "WAYPOINT"
    case transition = "TRANSITION"
    case destination = "DESTINATION"
}

/// Mirrors dev.chaya.api.navigation.NavigationDtos.Waypoint: canonical venue metres, +Z up.
public struct Waypoint: Codable, Equatable, Sendable {
    public var x: Double
    public var y: Double
    public var z: Double
    public var floorId: UUID
    public var kind: WaypointKind

    public init(x: Double, y: Double, z: Double, floorId: UUID, kind: WaypointKind) {
        self.x = x; self.y = y; self.z = z; self.floorId = floorId; self.kind = kind
    }
}

/// Mirrors dev.chaya.api.navigation.NavigationDtos.FloorTransition.
public struct FloorTransition: Codable, Equatable, Sendable {
    public var fromFloorId: UUID
    public var toFloorId: UUID
    public var connectorType: String
    public var poiId: UUID
    public var connectionId: UUID?
    public var toPoiId: UUID?
    public var distanceMeters: Double?
    public var durationSeconds: Double?
}

/// Mirrors dev.chaya.api.navigation.NavigationDtos.RoutingSource (the fields the app uses): what one floor's leg was
/// routed on. scanVersionId and coordinateFrameId say which version of the floor, and which canonical frame, the leg's
/// waypoints are in.
public struct RoutingSource: Codable, Equatable, Sendable {
    public var floorId: UUID
    public var graphId: UUID?
    public var source: String?
    public var pathMethod: String?
    public var scanVersionId: UUID?
    public var coordinateFrameId: UUID?

    public init(floorId: UUID, graphId: UUID? = nil, source: String? = nil, pathMethod: String? = nil,
                scanVersionId: UUID?, coordinateFrameId: UUID?) {
        self.floorId = floorId; self.graphId = graphId; self.source = source; self.pathMethod = pathMethod
        self.scanVersionId = scanVersionId; self.coordinateFrameId = coordinateFrameId
    }
}

/// Mirrors dev.chaya.api.navigation.NavigationDtos.RouteResponse (docs/navigation.md). Both AR clients
/// render this same route; neither computes its own path. `routingSources` is optional only so that a response without
/// it still decodes: a leg without a routing source is never drawn (RouteGeometry.leg(of:for:)).
public struct RouteResponse: Codable, Equatable, Sendable {
    public var waypoints: [Waypoint]
    public var distanceMeters: Double
    public var estimatedDurationSeconds: Double
    public var floorTransitions: [FloorTransition]
    public var accessibilityProfile: String
    public var accessibilityConstraintsApplied: [String]
    public var routingSources: [RoutingSource]?

    public init(waypoints: [Waypoint], distanceMeters: Double, estimatedDurationSeconds: Double, floorTransitions: [FloorTransition],
                accessibilityProfile: String, accessibilityConstraintsApplied: [String], routingSources: [RoutingSource]? = nil) {
        self.waypoints = waypoints; self.distanceMeters = distanceMeters; self.estimatedDurationSeconds = estimatedDurationSeconds
        self.floorTransitions = floorTransitions; self.accessibilityProfile = accessibilityProfile
        self.accessibilityConstraintsApplied = accessibilityConstraintsApplied; self.routingSources = routingSources
    }
}

/// Mirrors dev.chaya.api.venue's venue view (the fields the app uses).
public struct Venue: Codable, Equatable, Sendable {
    public var id: UUID
    public var name: String
}

/// Mirrors dev.chaya.api.venue's floor view.
public struct Floor: Codable, Equatable, Sendable, Identifiable {
    public var id: UUID
    public var level: Int
    public var name: String
}

/// Mirrors dev.chaya.api.poi's POI view (the fields the app uses). x/y/z are canonical venue metres.
public struct Poi: Codable, Equatable, Sendable, Identifiable {
    public enum FrameStatus: String, Codable, Sendable {
        case current = "CURRENT", stale = "STALE", unbound = "UNBOUND"
    }

    public var id: UUID
    public var floorId: UUID?
    public var label: String
    public var category: String?
    public var x: Double
    public var y: Double
    public var z: Double
    public var frameStatus: FrameStatus
}

/// Mirrors the response of POST /public/viewer-token: a short-lived token bound to one venue (docs/security.md).
public struct PublicViewerToken: Codable, Equatable, Sendable {
    public var token: String
    public var expiresAt: Date
    public var venueId: UUID
}
