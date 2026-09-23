# Chaya AR — iOS (Swift/ARKit)

Native iOS AR client. WebXR is unsupported on iOS Safari, so unlike Android (which uses WebXR inside
`apps/web`), iOS gets this native Swift package. See [docs/ar.md](../../docs/ar.md) for the shared
venue/floor/anchor/path/waypoint/destination model and the relocalization design both clients implement.

## Structure

This is a Swift Package (`Package.swift`), not (yet) an `.xcodeproj`. That split is deliberate:

- **`ChayaARCore`** — platform-independent. `Models.swift` (Codable mirrors of the backend DTOs),
  `APIClient.swift` (the thin `URLSession` REST client), `AnchorMath.swift` (coordinate-transform math),
  `RelocalizationStateMachine.swift` (tracking-loss handling). No `import ARKit` / `import UIKit` anywhere
  in this target, so it builds and its tests run with plain `swift test` on any platform Swift supports
  (including Linux CI) — no Xcode, simulator, or device required.
- **`ChayaARKitSession`** — the real ARKit integration (`ARSessionManager.swift`): wraps `ARSession` +
  `ARWorldTrackingConfiguration` with reference images built from registered anchors, and maps ARKit's own
  `ARCamera.TrackingState` into `RelocalizationStateMachine` events. Guarded with `#if canImport(ARKit)` so
  the package still builds everywhere; the target only does something on iOS with the ARKit SDK.

## Running the tests that don't need a device

```
swift test
```

This runs everything under `Tests/ChayaARCoreTests` — coordinate transformation and anchor math
(`AnchorMathTests.swift`) and the relocalization state machine (`RelocalizationStateMachineTests.swift`).
Neither needs Xcode's iOS simulator or a physical device; they're plain value-type unit tests.

## What needs a physical device

`ChayaARKitSession.ARSessionManager` needs a real iPhone/iPad with a camera and ARKit world-tracking
support. **The iOS Simulator does not implement ARKit camera tracking**, so this target cannot be
exercised there, and it cannot be exercised at all from this repository's development environment (no
macOS/Xcode host). It is not unit-tested for that reason — see docs/ar.md "Testing" for the full
device-requirement table. Manual verification requires:

1. A physical iOS device (iOS 16+) with a camera.
2. Printed or displayed markers matching the `ARReferenceImage` set built from the venue's registered
   `IMAGE_TARGET`/`QR_CODE` anchors, at their real physical size (ARKit needs the true printed width to
   estimate scale correctly — never assume a size in code).
3. Camera permission: add `NSCameraUsageDescription` to the eventual app target's `Info.plist`.

## Turning this into a runnable app

This package has no app entry point on purpose — an app target (a `.xcodeproj` with a SwiftUI/UIKit
lifecycle, asset catalog, and `Info.plist`) is created in Xcode, not hand-written here, because a
hand-authored `.xcodeproj` is fragile and not meaningfully "real" without Xcode to open and validate it. To
build the app:

1. `File > New > Project` in Xcode, iOS App template.
2. Add this directory as a local Swift package dependency (`File > Add Package Dependencies… > Add
   Local…`), and add `ChayaARCore` and `ChayaARKitSession` as target dependencies.
3. Add `NSCameraUsageDescription` to the app's `Info.plist`.
4. Build a SwiftUI/UIKit view that owns an `ARSessionManager`, requests camera authorization, and renders
   an `ARSCNView`/`ARView` bound to `ARSessionManager`'s underlying `ARSession`.
