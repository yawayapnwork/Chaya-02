import Foundation

/// AR relocalization/tracking-loss state machine. Mirrors apps/web/lib/ar-relocalization.ts so both
/// clients behave identically on the same signals; see docs/ar.md "Tracking failure":
///   - freeze the last known route state on tracking loss (`lastKnownPose` is untouched)
///   - show a tracking-loss state (`.trackingLost`)
///   - prompt for re-localization (`.relocalizing`)
///   - never silently move the user marker: `lastKnownPose` only changes on a `.localized` transition.
public enum ArState: Equatable, Sendable {
    case uninitialized
    case detecting
    case localized
    case trackingLost
    case relocalizing
}

public enum ArEvent: Sendable {
    case startDetecting
    case anchorDetected(Pose)
    case trackingLost
    case beginRelocalizing
    case relocalized(Pose)
}

public struct ArSessionState: Equatable, Sendable {
    public var state: ArState
    /// The last confirmed device-to-venue transform. Only ever set by a `.localized` transition -- never
    /// interpolated or guessed into existence while tracking is lost.
    public var lastKnownPose: Pose?

    public init(state: ArState = .uninitialized, lastKnownPose: Pose? = nil) {
        self.state = state
        self.lastKnownPose = lastKnownPose
    }
}

public enum RelocalizationStateMachine {

    private static func isValid(_ event: ArEvent, in state: ArState) -> Bool {
        switch state {
        case .uninitialized:
            if case .startDetecting = event { return true }
            return false
        case .detecting:
            switch event {
            case .anchorDetected, .trackingLost: return true
            default: return false
            }
        case .localized:
            if case .trackingLost = event { return true }
            return false
        case .trackingLost:
            if case .beginRelocalizing = event { return true }
            return false
        case .relocalizing:
            switch event {
            case .relocalized, .trackingLost: return true
            default: return false
            }
        }
    }

    /// Pure reducer: an event not valid for the current state is a no-op, never a thrown error -- a stray
    /// platform event (e.g. a second `.trackingLost` while already `.trackingLost`) must not crash a live
    /// AR session.
    public static func reduce(_ current: ArSessionState, _ event: ArEvent) -> ArSessionState {
        guard isValid(event, in: current.state) else { return current }
        switch event {
        case .startDetecting:
            var next = current
            next.state = .detecting
            return next
        case .anchorDetected(let pose):
            return ArSessionState(state: .localized, lastKnownPose: pose)
        case .trackingLost:
            var next = current
            next.state = .trackingLost // freeze: lastKnownPose is untouched
            return next
        case .beginRelocalizing:
            var next = current
            next.state = .relocalizing
            return next
        case .relocalized(let pose):
            return ArSessionState(state: .localized, lastKnownPose: pose)
        }
    }
}
