import Foundation

/// The transform chain between the device and the venue (docs/ar.md, "Frames"). Pure. Mirrors
/// dev.chaya.api.ar.ArDeviceFrame and apps/web/lib/ar-frame-boundary.ts + ar-route.ts.
///
///   camera frame        ARFrame.camera: x right, y up, -z forward (the camera's own frame)
///   ARKit world frame   the session's world coordinate space: metres, gravity-aligned (worldAlignment .gravity), +Y up,
///                       origin where the session started. ARKit gives the camera's transform in it every frame, and
///                       each detected image's transform (ARImageAnchor.transform).
///   canonical venue     Chaya's frame: metres, +Z up (docs/coordinate-frames.md). Routes, POIs and anchors live here.
///
///   camera -> world:       ARFrame.camera.transform (measured by ARKit, per frame)
///   world -> canonical:    deviceToVenue, solved by the server from marker observations (digitalPose * observedPose^-1).
///                          It absorbs the +Y-up -> +Z-up axis change; the server refuses one that tilts gravity.
///   canonical -> world:    its inverse. The route is built in canonical coordinates and placed in the scene with it.
///
/// Nothing here uses scene coordinates the app made up: every world-frame value is an ARKit measurement, and every
/// canonical value comes from the server.
public enum VenueFrames {

    /// The device convention the server expects observations in (ArDeviceFrame.CONVENTION).
    public static let deviceFrameConvention = "DEVICE_Y_UP_RIGHT_HANDED_METRES"

    static func point(_ p: Vec3) -> Pose { Pose(x: p.x, y: p.y, z: p.z) }

    static func translation(_ p: Pose) -> Vec3 { Vec3(p.x, p.y, p.z) }

    /// A point in the camera's own frame, in the ARKit world frame.
    public static func cameraPointToWorld(camera: Pose, _ p: Vec3) -> Vec3 {
        translation(AnchorMath.compose(camera, point(p)))
    }

    /// ARKit world point -> canonical venue metres.
    public static func worldPointToVenue(deviceToVenue: Pose, _ p: Vec3) -> Vec3 {
        translation(AnchorMath.compose(deviceToVenue, point(p)))
    }

    /// Canonical venue point -> ARKit world point.
    public static func venuePointToWorld(deviceToVenue: Pose, _ p: Vec3) -> Vec3 {
        translation(AnchorMath.compose(AnchorMath.invert(deviceToVenue), point(p)))
    }

    /// camera frame -> ARKit world -> canonical venue.
    public static func cameraPointToVenue(camera: Pose, deviceToVenue: Pose, _ p: Vec3) -> Vec3 {
        worldPointToVenue(deviceToVenue: deviceToVenue, cameraPointToWorld(camera: camera, p))
    }

    /// Where the device (the camera centre) is, in canonical venue metres.
    public static func devicePositionInVenue(camera: Pose, deviceToVenue: Pose) -> Vec3 {
        cameraPointToVenue(camera: camera, deviceToVenue: deviceToVenue, Vec3(0, 0, 0))
    }

    /// The canonical -> ARKit world pose: the renderer places the canonical route geometry with it.
    public static func venueToWorld(deviceToVenue: Pose) -> Pose {
        AnchorMath.invert(deviceToVenue)
    }

    /// The world -> canonical transform now, corrected by how far ARKit has moved the localization's world anchor
    /// (an ARAnchor added where the marker was observed) since the transform was solved. ARKit updates anchor
    /// transforms as it refines its map; the physical point that was at w is now at At * A0^-1 * w, so the current
    /// transform is deviceToVenue * A0 * At^-1. With no correction it is deviceToVenue itself.
    public static func anchorCorrectedDeviceToVenue(_ deviceToVenue: Pose, anchorAtSolve: Pose, anchorNow: Pose) -> Pose {
        AnchorMath.compose(deviceToVenue, AnchorMath.compose(anchorAtSolve, AnchorMath.invert(anchorNow)))
    }

    /// Angle, in degrees, between where the transform sends the device's up (+Y) and canonical +Z.
    public static func gravityTiltDegrees(_ deviceToVenue: Pose) -> Double {
        let (x, y, z) = AnchorMath.rotate(AnchorMath.normalize(deviceToVenue), 0, 1, 0)
        let c = z / (x * x + y * y + z * z).squareRoot()
        return acos(max(-1, min(1, c))) * 180 / Double.pi
    }
}

/// Route geometry on one floor, and progress along it. Pure.
public enum RouteGeometry {

    /// The part of a route on `floorId`: from its first waypoint on that floor through the last consecutive one (a
    /// TRANSITION to another floor, or the DESTINATION).
    public static func legOnFloor(_ route: RouteResponse, floorId: UUID) -> [Waypoint] {
        guard let start = route.waypoints.firstIndex(where: { $0.floorId == floorId }) else { return [] }
        var end = start
        while end + 1 < route.waypoints.count && route.waypoints[end + 1].floorId == floorId { end += 1 }
        return Array(route.waypoints[start...end])
    }

    public enum LegProblem: String, Equatable, Sendable {
        case noLegOnFloor = "NO_LEG_ON_FLOOR"
        case routeSourceMissing = "ROUTE_SOURCE_MISSING"
        case versionMismatch = "VERSION_MISMATCH"
        case frameMismatch = "FRAME_MISMATCH"
    }

    public enum LegCheck: Equatable, Sendable {
        case drawable([Waypoint])
        case refused(LegProblem, String)
    }

    /// The localization's floor leg of `route`, if it may be drawn with that localization: the server must have routed the
    /// floor on the scan version and in the coordinate frame the transform was solved in. A leg of another version or frame
    /// is in other canonical coordinates; placed with this transform it would be misplaced, so it is refused, never drawn.
    /// Mirrors apps/web/lib/ar-navigation.ts `routeLegFor`.
    public static func leg(of route: RouteResponse, for localization: Localization) -> LegCheck {
        let floorId = localization.floorId
        let leg = legOnFloor(route, floorId: floorId)
        guard !leg.isEmpty else {
            return .refused(.noLegOnFloor, "the route does not pass through floor \(floorId.apiString)")
        }
        guard let source = route.routingSources?.first(where: { $0.floorId == floorId }) else {
            return .refused(.routeSourceMissing, "the route does not say what floor \(floorId.apiString) was routed on")
        }
        guard source.scanVersionId == localization.scanVersionId else {
            return .refused(.versionMismatch, "floor \(floorId.apiString) was routed on scan version " +
                "\(source.scanVersionId?.apiString ?? "(none)"), but the session is localized in \(localization.scanVersionId.apiString)")
        }
        guard source.coordinateFrameId == localization.coordinateFrameId else {
            return .refused(.frameMismatch, "floor \(floorId.apiString) was routed in coordinate frame " +
                "\(source.coordinateFrameId?.apiString ?? "(none)"), but the session is localized in \(localization.coordinateFrameId.apiString)")
        }
        return .drawable(leg)
    }

    public static func points(_ waypoints: [Waypoint]) -> [Vec3] {
        waypoints.map { Vec3($0.x, $0.y, $0.z) }
    }

    public struct Progress: Equatable, Sendable {
        /// Index of the next route vertex ahead of the device.
        public var nextIndex: Int
        /// Distance still to walk along the route from the device's projection onto it, metres.
        public var remainingMeters: Double
        /// Horizontal distance from the device to the route, metres.
        public var offRouteMeters: Double
        /// Canonical device position this was computed from.
        public var from: Vec3
    }

    static func horizontal(_ a: Vec3, _ b: Vec3) -> Double {
        ((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y)).squareRoot()
    }

    /// Projects the device's canonical position onto the route polyline in the horizontal plane (canonical x, y: the
    /// route runs over the floor, the phone is ~1.5 m above it).
    public static func progress(along points: [Vec3], device: Vec3) -> Progress? {
        guard let first = points.first else { return nil }
        if points.count == 1 {
            let d = horizontal(first, device)
            return Progress(nextIndex: 0, remainingMeters: d, offRouteMeters: d, from: device)
        }
        var best = (dist: Double.infinity, seg: 0, t: 0.0)
        for i in 0..<(points.count - 1) {
            let a = points[i], b = points[i + 1]
            let dx = b.x - a.x, dy = b.y - a.y
            let len2 = dx * dx + dy * dy
            let t = len2 == 0 ? 0 : max(0, min(1, ((device.x - a.x) * dx + (device.y - a.y) * dy) / len2))
            let d = ((a.x + t * dx - device.x) * (a.x + t * dx - device.x) + (a.y + t * dy - device.y) * (a.y + t * dy - device.y)).squareRoot()
            if d < best.dist { best = (d, i, t) }
        }
        var remaining = (1 - best.t) * horizontal(points[best.seg], points[best.seg + 1])
        for i in (best.seg + 1)..<(points.count - 1) { remaining += horizontal(points[i], points[i + 1]) }
        let nextIndex = best.t >= 1 ? min(best.seg + 2, points.count - 1) : best.seg + 1
        return Progress(nextIndex: nextIndex, remainingMeters: remaining, offRouteMeters: best.dist, from: device)
    }

    /// Route progress for this frame: recomputed only when the route may advance; otherwise the previous progress,
    /// unchanged.
    public static func advance(_ previous: Progress?, canAdvance: Bool, points: [Vec3], device: Vec3?) -> Progress? {
        guard canAdvance, let device else { return previous }
        return progress(along: points, device: device)
    }
}
