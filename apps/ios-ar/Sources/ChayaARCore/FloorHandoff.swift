import Foundation

/// Which floor the device is on, from the registered markers it sees (docs/ar.md, "Floor handoff"). Pure. Mirrors
/// apps/web/lib/ar-floor-identification.ts.
///
/// Every marker is registered on one floor. After a floor change (stairs, lift) the session must not draw the next floor's
/// route until it is sure where the device is:
///   confirmed    only the expected floor's markers were seen, in at least `minSightings` of the recent frames that saw
///                any marker. Right after a floor change that is `minFloorSightings`; at the start of a session, on the floor
///                the user chose, one sighting.
///   unexpected   only another floor's markers were seen, in at least `minFloorSightings` frames: the user is on that floor
///                (got off the lift too early, took the wrong stairs). The route is not switched; the user is asked, and on
///                confirmation the session localizes on that floor and the route is planned again from there.
///   ambiguous    markers of two or more floors were seen within the window (a marker visible across an atrium or a
///                stairwell): nothing is decided until the camera sees one floor's markers alone.
///   pending      not enough evidence yet.
public enum FloorCheck: Equatable, Sendable {
    case confirmed
    case pending
    case ambiguous([UUID])
    case unexpected(UUID)
}

public enum FloorIdentification {

    public static let window = 4
    public static let minFloorSightings = 2

    /// The floors seen in each of the last `window` frames that saw any registered marker.
    public static func push(_ window: [[UUID]], _ batchFloors: [UUID]) -> [[UUID]] {
        let batch = Array(Set(batchFloors)).sorted { $0.uuidString < $1.uuidString }
        guard !batch.isEmpty else { return window }
        return Array((window + [batch]).suffix(Self.window))
    }

    public static func identify(_ window: [[UUID]], expected: UUID, minSightings: Int = minFloorSightings) -> FloorCheck {
        let floors = Array(Set(window.flatMap { $0 })).sorted { $0.uuidString < $1.uuidString }
        guard let only = floors.first else { return .pending }
        if floors.count > 1 { return .ambiguous(floors) }
        let sightings = window.filter { $0.contains(only) }.count
        if only == expected { return sightings >= minSightings ? .confirmed : .pending }
        return sightings >= minFloorSightings ? .unexpected(only) : .pending
    }
}

extension RouteGeometry {

    /// How close to the end of this floor's leg (a TRANSITION) the device must be to leave the floor. Same as the web
    /// client (HANDOFF_RADIUS_METERS).
    public static let handoffRadiusMeters = 2.0

    public struct Handoff: Equatable, Sendable {
        public var toFloorId: UUID
        public var connectorType: String?
    }

    /// The floor transition the device has reached on `floorId`, or nil: this floor's leg ends in a TRANSITION, the device
    /// is within `handoffRadiusMeters` of the leg's end along the route, and the route continues on `toFloorId`.
    /// Mirrors apps/web/lib/ar-navigation.ts `floorHandoff`.
    public static func floorHandoff(_ route: RouteResponse, floorId: UUID, remainingMeters: Double?) -> Handoff? {
        guard let start = route.waypoints.firstIndex(where: { $0.floorId == floorId }), let remainingMeters else { return nil }
        var end = start
        while end + 1 < route.waypoints.count && route.waypoints[end + 1].floorId == floorId { end += 1 }
        guard route.waypoints[end].kind == .transition, end + 1 < route.waypoints.count else { return nil }
        guard remainingMeters <= handoffRadiusMeters else { return nil }
        let toFloorId = route.waypoints[end + 1].floorId
        let transition = route.floorTransitions.first { $0.fromFloorId == floorId && $0.toFloorId == toFloorId }
        return Handoff(toFloorId: toFloorId, connectorType: transition?.connectorType)
    }

    /// The floors a route visits, in order, without repeats in a row.
    public static func floors(of route: RouteResponse) -> [UUID] {
        var out: [UUID] = []
        for w in route.waypoints where out.last != w.floorId { out.append(w.floorId) }
        return out
    }

    /// The scan version to pin a route request to. A destination on the localization's floor is routed on the version the
    /// session is localized in (its pinned navmesh and frame). A destination on another floor cannot be: the server keeps a
    /// version-pinned route on its floor (VERSION_WRONG_FLOOR), so it is routed on what each floor publishes now, and each
    /// leg is drawn only if `leg(of:for:)` finds its version and frame match the localization. Mirrors `arRouteRequest`.
    public static func routeScanVersion(destinationFloorId: UUID?, localization: Localization) -> UUID? {
        destinationFloorId == localization.floorId ? localization.scanVersionId : nil
    }
}

/// Whether the floor transitions a route was planned over can still be used. Mirrors apps/web/lib/floor-transitions.ts
/// `transitionsStillUsable`: the connection still exists, is in service, still runs in the route's direction, and -- for a
/// step-free route -- is still verified accessible. A route whose transition fails this is planned again; it is never
/// followed onto a closed lift.
public enum FloorTransitions {

    public struct Check: Equatable, Sendable {
        public var usable: Bool
        /// One line per floor transition of the route that can no longer be used as planned.
        public var problems: [String]
        /// The connections those problems are about.
        public var connectionIds: [UUID]
    }

    public static func stillUsable(_ route: RouteResponse, connections: [FloorConnection]) -> Check {
        var problems: [String] = []
        var ids: [UUID] = []
        for t in route.floorTransitions {
            let what = t.connectorType.lowercased()
            // A transition without a connection id predates registered connections; nothing can be re-checked for it.
            guard let id = t.connectionId else { continue }
            guard let c = connections.first(where: { $0.id == id }) else {
                problems.append("the \(what) on the route was removed")
                if !ids.contains(id) { ids.append(id) }
                continue
            }
            let before = problems.count
            if c.status != .inService { problems.append("the \(what) on the route is out of service") }
            let forward = c.fromFloorId == t.fromFloorId && c.toFloorId == t.toFloorId
            let backward = c.fromFloorId == t.toFloorId && c.toFloorId == t.fromFloorId
            if !forward && !(backward && c.bidirectional) { problems.append("the \(what) on the route no longer runs in this direction") }
            if route.accessibilityProfile == "STEP_FREE" && c.accessibilityVerifiedAt == nil {
                problems.append("the \(what) on the route is no longer verified as accessible")
            }
            if problems.count > before && !ids.contains(id) { ids.append(id) }
        }
        return Check(usable: problems.isEmpty, problems: problems, connectionIds: ids)
    }
}
