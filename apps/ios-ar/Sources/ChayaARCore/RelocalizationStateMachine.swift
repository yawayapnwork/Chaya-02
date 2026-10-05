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
///   trackingLost --normal--> recovered --markersObserved--> solving --...--> localized
///   anything --sessionEnded / sessionFailed--> ended
///
/// Rules:
///   * A marker observation and the device->venue transform are different things with different types:
///     `pending.observations` holds what ARKit saw; `localization.deviceToVenue` only what the server solved from it.
///   * Route progress advances only in `localized` (`canAdvanceRoute`).
///   * `.limited(.initializing)` at session start is not a loss: before the first localization there is nothing to
///     lose. Observations are only accepted while ARKit reports `.normal` tracking.
///   * A short `limited` (fast motion, a blank wall) keeps the localization -- ARKit keeps its world frame -- but
///     freezes progress. ARKit relocalizing, tracking not available, or a session interruption may move or reset the
///     world frame, so the localization is lost.
///   * Tracking loss is sticky. ARKit reporting `.normal` again only gets to `recovered`; the route returns only after a
///     registered marker is observed again and the server relocalizes from it.
///   * A server answer for a request that is no longer pending is ignored.
///   * A localization belongs to one floor and one scan version. The session's floor is fixed when it starts; its version
///     is pinned at the first localization and sent with every later request. An answer for another floor, or in another
///     version (the floor was republished mid-session), is refused (FLOOR_MISMATCH / VERSION_MISMATCH) like a failed
///     solve: it never replaces the transform, so a stale version's route is never drawn with a new version's transform.
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
    case idle, searching, solving, localized, limited, trackingLost, recovered, ended
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
    /// The floor this session localizes on (set when it starts). Answers for another floor are refused.
    public var floorId: UUID?
    /// The scan version the floor was first localized in. Sent with every later relocalization; an answer in another
    /// version is refused.
    public var pinnedScanVersionId: UUID?
    public var deviceTracking: TrackingQuality = .notAvailable
    /// The current localization; kept while trackingLost/recovered for display only, never used to advance.
    public var localization: Localization?
    public var pending: Pending?
    public var limitedSince: TimeInterval?
    public var lostReason: String?
    public var lastError: String?
    public var nextRequestId = 1

    public init() {}

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
    case relocalizationSucceeded(requestId: Int, Localization)
    case relocalizationFailed(requestId: Int, error: String)
    case worldAnchorLost
    case sessionFailed(String)
    case sessionEnded
}

public enum RelocalizationStateMachine {

    /// How long a localized session may stay `limited` before the localization is considered lost.
    public static let maxLimitedSeconds: TimeInterval = 3

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
            guard s.phase == .searching || s.phase == .recovered, s.deviceTracking == .normal, !observations.isEmpty else { return s }
            n.phase = .solving
            n.pending = .init(requestId: s.nextRequestId, observations: observations, resumeTo: s.phase)
            n.nextRequestId += 1

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
            n.pinnedScanVersionId = localization.scanVersionId
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

    /// Tracking went bad while a request was in flight: the observation it was made from is no longer in a trustworthy
    /// frame. Back to searching if never localized, otherwise lost.
    private static func cancelPending(_ s: NavigationState, reason: String) -> NavigationState {
        var n = s
        let wasRecovering = s.pending?.resumeTo == .recovered
        n.pending = nil
        n.phase = wasRecovering ? .trackingLost : .searching
        n.lostReason = reason
        return n
    }
}
