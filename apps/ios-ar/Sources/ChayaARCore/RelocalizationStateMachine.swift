import Foundation

/// The iOS AR navigation session's localization and tracking state machine (docs/ar.md, "iOS: Swift/ARKit"). Pure:
/// ARSessionManager feeds it ARKit's own signals and server answers; the UI and route renderer read it. It follows
/// the same rules as the web client (apps/web/lib/ar-relocalization.ts), with ARKit's tracking states made explicit.
///
///   idle --sessionStarted--> searching --markersObserved--> solving --relocalizationSucceeded--> localized
///                                                    (relocalizationFailed / tracking not normal: back where it came from)
///   localized --limited(excessiveMotion | insufficientFeatures)--> limited --normal--> localized
///   limited --still limited after maxLimitedSeconds--> trackingLost
///   localized / limited --limited(relocalizing) | notAvailable | interrupted | world anchor lost--> trackingLost
///   limited --normal, but the recovery check fails (recoveryRejected)--> trackingLost
///   trackingLost --normal--> recovered --markersObserved--> solving --...--> localized
///   localized --floorHandoff--> floorTransition --markersObserved (the floor identified, FloorIdentification)--> solving
///   searching / floorTransition with another floor's markers only (.unexpected) --acceptDetectedFloor--> the same phase on
///                                                                                that floor (the caller plans the route again)
///   anything --sessionEnded / sessionFailed--> ended
///
/// Rules:
///   * A marker observation and the device->venue transform are different things with different types:
///     `pending.observations` holds what ARKit saw; `localization.deviceToVenue` only what the server solved from it.
///   * Route progress advances only in `localized` (`canAdvanceRoute`).
///   * `.limited(.initializing)` at session start is not a loss: before the first localization there is nothing to
///     lose. Observations are only accepted while ARKit reports `.normal` tracking.
///   * A short `limited` (fast motion, a blank wall) keeps the localization -- ARKit keeps its world frame -- but
///     freezes progress. ARSessionManager validates the return to normal: the world anchor must still be tracked and have
///     moved less than `recoveryMaxJumpMeters` during the spell (`recoveryRejected` otherwise). ARKit relocalizing, tracking not available, or a session interruption may move or reset the
///     world frame, so the localization is lost.
///   * Tracking loss is sticky. ARKit reporting `.normal` again only gets to `recovered`; the route returns only after a
///     registered marker is observed again and the server relocalizes from it.
///   * A server answer for a request that is no longer pending is ignored.
///   * A localization belongs to one floor and one scan version. The session's floor is set when it starts and changes only
///     through a floor handoff or the user accepting the floor the markers show; each floor's version is pinned at its
///     first localization and sent with every later request for that floor. An answer for another floor, or in another
///     version (the floor was republished mid-session), is refused (FLOOR_MISMATCH / VERSION_MISMATCH) like a failed
///     solve: it never replaces the transform, so a stale version's route is never drawn with a new version's transform.
///   * Floors (docs/ar.md, "Floor handoff"): a transform is valid on one floor only. A handoff drops it; the next floor is
///     localized only once its markers alone have been seen in `FloorIdentification.minFloorSightings` frames. Markers of
///     several floors at once (.ambiguous) or of another floor only (.unexpected) localize nothing.
public enum LimitedReason: String, Equatable, Sendable {
    case initializing, excessiveMotion, insufficientFeatures, relocalizing, other

    /// Reasons during which ARKit keeps its world frame, so a localization can survive them briefly.
    var isTransient: Bool { self == .excessiveMotion || self == .insufficientFeatures }
}

/// ARCamera.TrackingState, as a plain value (ChayaARKitSession maps it).
public enum TrackingQuality: Equatable, Sendable {
    case normal
    case limited(LimitedReason)
    case notAvailable

    public var description: String {
        switch self {
        case .normal: return "normal"
        case .notAvailable: return "tracking not available"
        case .limited(.initializing): return "limited: initializing"
        case .limited(.excessiveMotion): return "limited: moving too fast"
        case .limited(.insufficientFeatures): return "limited: not enough visual detail"
        case .limited(.relocalizing): return "limited: ARKit is relocalizing"
        case .limited(.other): return "limited"
        }
    }
}

/// A server-solved localization (POST .../anchors/relocalize). Valid only on `floorId` (every floor has its own canonical
/// frame), and only in the scan version and coordinate frame the server solved it in.
public struct Localization: Equatable, Sendable {
    public var deviceToVenue: Pose
    public var anchorIds: [UUID]
    public var residualMeters: Double?
    public var coordinateFrameId: UUID
    public var scanVersionId: UUID
    public var floorId: UUID
    public var solvedAt: TimeInterval

    public init(deviceToVenue: Pose, anchorIds: [UUID], residualMeters: Double?, coordinateFrameId: UUID, scanVersionId: UUID,
                floorId: UUID, solvedAt: TimeInterval) {
        self.deviceToVenue = deviceToVenue; self.anchorIds = anchorIds; self.residualMeters = residualMeters
        self.coordinateFrameId = coordinateFrameId; self.scanVersionId = scanVersionId; self.floorId = floorId
        self.solvedAt = solvedAt
    }
}

public enum NavigationPhase: Equatable, Sendable {
    case idle, searching, solving, localized, limited, trackingLost, recovered
    /// Left one floor over a stair, ramp or elevator transition; not localized until the next floor is identified.
    case floorTransition
    case ended
}

/// The tracking status the UI shows (normal / limited / relocalizing / lost / recovered).
public enum TrackingStatus: Equatable, Sendable {
    case normal
    case limited(LimitedReason)
    /// ARKit is relocalizing its own world map (after an interruption).
    case relocalizing
    case lost
    /// ARKit tracks normally again after a loss; the route waits for a marker.
    case recovered
}

public struct NavigationState: Equatable, Sendable {
    public struct Pending: Equatable, Sendable {
        public var requestId: Int
        public var observations: [MarkerObservation]
        public var resumeTo: NavigationPhase
    }

    public var phase: NavigationPhase = .idle
    /// The floor this session localizes on now (set when it starts, changed by a handoff). Answers for another floor are
    /// refused.
    public var floorId: UUID?
    /// Per floor, the scan version it was first localized in this session. Sent with every later relocalization on that
    /// floor; an answer in another version is refused.
    public var pinnedScanVersions: [UUID: UUID] = [:]
    /// The floors seen in each of the recent frames that saw registered markers, while establishing the floor.
    public var floorWindow: [[UUID]] = []
    /// Why the session has not localized although markers were seen: another floor's markers (.unexpected), several floors'
    /// markers (.ambiguous), or not enough sightings (.pending). nil once identified.
    public var floorCheck: FloorCheck?
    public var deviceTracking: TrackingQuality = .notAvailable
    /// The current localization; kept while trackingLost/recovered for display only, never used to advance.
    public var localization: Localization?
    public var pending: Pending?
    public var limitedSince: TimeInterval?
    public var lostReason: String?
    public var lastError: String?
    public var nextRequestId = 1

    public init() {}

    /// The scan version pinned for the session's current floor (nil before its first localization).
    public var pinnedScanVersionId: UUID? { floorId.flatMap { pinnedScanVersions[$0] } }

    public var canAdvanceRoute: Bool { phase == .localized && localization != nil }

    /// The route is drawn while localized, and held (frozen) through a short limited spell.
    public var routeVisible: Bool { (phase == .localized || phase == .limited) && localization != nil }

    public var trackingStatus: TrackingStatus {
        if deviceTracking == .limited(.relocalizing) { return .relocalizing }
        switch phase {
        case .trackingLost: return .lost
        case .recovered: return .recovered
        default: break
        }
        switch deviceTracking {
        case .normal: return .normal
        case .limited(let reason): return .limited(reason)
        case .notAvailable: return phase == .idle || phase == .ended ? .normal : .lost
        }
    }
}

public enum NavigationEvent: Equatable, Sendable {
    case sessionStarted(floorId: UUID)
    case trackingChanged(TrackingQuality, at: TimeInterval)
    case tick(at: TimeInterval)
    case sessionInterrupted
    case markersObserved([MarkerObservation])
    /// The device reached the end of this floor's leg (`RouteGeometry.floorHandoff`); the route continues on `toFloorId`.
    case floorHandoff(toFloorId: UUID)
    /// The user confirmed being on the floor whose markers the camera sees (floorCheck .unexpected).
    case acceptDetectedFloor
    case relocalizationSucceeded(requestId: Int, Localization)
    case relocalizationFailed(requestId: Int, error: String)
    case worldAnchorLost
    /// Tracking returned to normal after `limited`, but the recovery could not be validated (the world anchor is missing,
    /// or ARKit moved it more than `recoveryMaxJumpMeters` meanwhile).
    case recoveryRejected(String)
    case sessionFailed(String)
    case sessionEnded
}

public enum RelocalizationStateMachine {

    /// How long a localized session may stay `limited` before the localization is considered lost.
    public static let maxLimitedSeconds: TimeInterval = 3
    /// A return from `limited` in which the world anchor moved more than this is not trusted (ARKit re-anchored its map).
    /// Same as the web client (RECOVERY_MAX_JUMP_METERS).
    public static let recoveryMaxJumpMeters = 0.3

    /// Pure reducer. An event that does not apply in the current state returns the state unchanged: a stray platform
    /// event must not crash a live session or skip a state.
    public static func reduce(_ s: NavigationState, _ e: NavigationEvent) -> NavigationState {
        if s.phase == .ended { return s }
        var n = s
        switch e {
        case .sessionStarted(let floorId):
            guard s.phase == .idle else { return s }
            n.phase = .searching
            n.floorId = floorId
            n.deviceTracking = .notAvailable

        case .trackingChanged(let quality, let at):
            guard s.phase != .idle else { return s }
            n.deviceTracking = quality
            switch s.phase {
            case .solving where quality != .normal:
                n = cancelPending(n, reason: quality.description)
            case .localized:
                if case .limited(let reason) = quality, reason.isTransient {
                    n.phase = .limited
                    n.limitedSince = at
                } else if quality != .normal {
                    n = lose(n, reason: quality.description)
                }
            case .limited:
                if quality == .normal {
                    n.phase = .localized
                    n.limitedSince = nil
                } else if case .limited(let reason) = quality, reason.isTransient {
                    break
                } else {
                    n = lose(n, reason: quality.description)
                }
            case .trackingLost where quality == .normal:
                n.phase = .recovered
            case .recovered where quality != .normal:
                n.phase = .trackingLost
                n.lostReason = quality.description
            default:
                break
            }

        case .tick(let at):
            guard s.phase == .limited, let since = s.limitedSince, at - since > maxLimitedSeconds else { return s }
            n = lose(n, reason: "tracking was limited for more than \(Int(maxLimitedSeconds)) s")

        case .sessionInterrupted:
            n.deviceTracking = .notAvailable
            switch s.phase {
            case .solving: n = cancelPending(n, reason: "the AR session was interrupted")
            case .localized, .limited, .recovered: n = lose(n, reason: "the AR session was interrupted")
            default: break
            }

        case .markersObserved(let observations):
            guard s.deviceTracking == .normal, !observations.isEmpty else { return s }
            switch s.phase {
            case .searching, .floorTransition:
                // Establishing the floor (session start, or after a floor change): localize only once it is identified.
                guard let expected = s.floorId else { return s }
                let window = FloorIdentification.push(s.floorWindow, observations.map { $0.floorId ?? expected })
                let check = FloorIdentification.identify(window, expected: expected,
                                                         minSightings: s.phase == .floorTransition ? FloorIdentification.minFloorSightings : 1)
                guard check == .confirmed else {
                    n.floorWindow = window
                    n.floorCheck = check
                    return n
                }
                n.floorWindow = []
                n.floorCheck = nil
                n = solve(n, observations.filter { ($0.floorId ?? expected) == expected }, resumeTo: s.phase)
            case .recovered:
                // Only this floor's markers: the transform being re-established is this floor's.
                let own = observations.filter { $0.floorId == nil || $0.floorId == s.floorId }
                guard !own.isEmpty else { return s }
                n = solve(n, own, resumeTo: .recovered)
            default:
                return s
            }

        case .floorHandoff(let toFloorId):
            // A transform is valid on one floor only (each floor has its own canonical frame). The next floor starts
            // unlocalized and is localized from its own markers. Never from limited: progress is frozen there.
            guard s.phase == .localized, toFloorId != s.floorId else { return s }
            n.phase = .floorTransition
            n.floorId = toFloorId
            n.localization = nil
            n.pending = nil
            n.lostReason = nil
            n.lastError = nil
            n.floorWindow = []
            n.floorCheck = nil

        case .acceptDetectedFloor:
            // The user is where the markers say. The session localizes on that floor; the caller plans the route again.
            guard s.phase == .searching || s.phase == .floorTransition, case .unexpected(let detected)? = s.floorCheck else { return s }
            n.floorId = detected
            n.floorCheck = nil
            n.floorWindow = []

        case .relocalizationSucceeded(let requestId, let localization):
            guard s.phase == .solving, let pending = s.pending, pending.requestId == requestId else { return s }
            if let refused = refusal(s, localization) {
                n.phase = pending.resumeTo
                n.pending = nil
                n.lastError = refused
                return n
            }
            n.phase = .localized
            n.localization = localization
            n.pinnedScanVersions[localization.floorId] = localization.scanVersionId
            n.pending = nil
            n.limitedSince = nil
            n.lostReason = nil
            n.lastError = nil

        case .relocalizationFailed(let requestId, let error):
            guard s.phase == .solving, let pending = s.pending, pending.requestId == requestId else { return s }
            n.phase = pending.resumeTo
            n.pending = nil
            n.lastError = error

        case .worldAnchorLost:
            guard s.phase == .localized || s.phase == .limited else { return s }
            n = lose(n, reason: "ARKit no longer tracks the world anchor the route is attached to")

        case .recoveryRejected(let reason):
            guard s.phase == .localized || s.phase == .limited else { return s }
            n = lose(n, reason: reason)

        case .sessionFailed(let error):
            n.phase = .ended
            n.pending = nil
            n.lastError = error

        case .sessionEnded:
            n.phase = .ended
            n.pending = nil
        }
        return n
    }

    /// Why a server answer cannot be used in this session, or nil.
    static func refusal(_ s: NavigationState, _ l: Localization) -> String? {
        if let floorId = s.floorId, l.floorId != floorId {
            return "FLOOR_MISMATCH: solved on floor \(l.floorId.apiString), but the session is on floor \(floorId.apiString)"
        }
        if let pinned = s.pinnedScanVersionId, pinned != l.scanVersionId {
            return "VERSION_MISMATCH: this floor was localized in scan version \(pinned.apiString), but the server now answers in " +
                "\(l.scanVersionId.apiString) (the floor was republished); end AR and start again to load the new version"
        }
        return nil
    }

    private static func lose(_ s: NavigationState, reason: String) -> NavigationState {
        var n = s
        n.phase = .trackingLost
        n.pending = nil
        n.limitedSince = nil
        n.lostReason = reason
        return n
    }

    private static func solve(_ s: NavigationState, _ observations: [MarkerObservation], resumeTo: NavigationPhase) -> NavigationState {
        var n = s
        n.phase = .solving
        n.pending = .init(requestId: s.nextRequestId, observations: observations, resumeTo: resumeTo)
        n.nextRequestId += 1
        return n
    }

    /// Tracking went bad while a request was in flight: the observation it was made from is no longer in a trustworthy
    /// frame. Back to searching (or to the floor transition) if not localized on this floor yet, otherwise lost.
    private static func cancelPending(_ s: NavigationState, reason: String) -> NavigationState {
        var n = s
        let resumeTo = s.pending?.resumeTo
        n.pending = nil
        n.phase = resumeTo == .recovered ? .trackingLost : resumeTo == .floorTransition ? .floorTransition : .searching
        n.lostReason = reason
        return n
    }
}
