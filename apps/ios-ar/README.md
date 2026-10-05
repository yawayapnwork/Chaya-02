# Chaya AR — iOS (Swift/ARKit)

Native iOS AR navigation client. iOS Safari has no WebXR, so iOS gets this app instead of the web client Android uses.
[docs/ar.md](../../docs/ar.md) describes the shared anchor/relocalization/route model and the iOS design.

> **Status: builds in CI; NOT DEVICE-VALIDATED.** No macOS, Xcode or iPhone has been available to the authors; every
> compile of the ARKit layer and the app has happened in the `ios` CI workflow.
> - `ChayaARCore` is built and tested on Linux Swift 5.10 (`swift test`, 43 tests).
> - The app and the ARKit layer first compiled on 2026-09-30 (`ios` run 36734833669, commit `f99b8a6`, Xcode 15.4 on
>   `macos-14`): `BUILD SUCCEEDED` for `generic/platform=iOS`, and the 9 app-level tests passed in the iOS Simulator.
> - The scan-version changes after that run (version pinning, versioned routes, route-leg checks) are type-checked
>   only by the next `ios` run; on Windows/Linux they were syntax-checked (`swiftc -parse`) and the core tests run.
> - Nothing has run with a camera. The Simulator has no ARKit world tracking, so CI proves the code compiles and the
>   non-camera logic works, not that markers are detected or the route is placed correctly.
> - The app shows a "NOT DEVICE-VALIDATED" banner on every screen (`DeviceValidation.status`) until
>   [docs/ar-ios-validation.md](../../docs/ar-ios-validation.md) has been run on a physical iPhone and its record
>   filled in.

## Layout

| Path | What | Builds / tests where |
|---|---|---|
| `Sources/ChayaARCore` | Backend models and API client (public viewer link auth), the camera → ARKit world → canonical venue frame chain (`VenueFrames`), the marker registry (reference image name → anchor id → anchor), route geometry, progress and the route-leg version/frame check, and the tracking/relocalization state machine (with scan-version pinning) | Anywhere Swift runs: `swift test` (Linux CI) |
| `Sources/ChayaARKitSession` | `ARSessionManager` (ARKit session, detections, relocalization, world anchor, route placement), `ReferenceImages`, `RouteSceneRenderer` (SceneKit), ARKit ↔ core conversions | iOS only (`#if canImport(ARKit)`) |
| `App/` | The SwiftUI app: viewer link → floor → destination → AR. `Info.plist` has `NSCameraUsageDescription` and requires `arkit`. `project.yml` is the XcodeGen spec. | Xcode on macOS |
| `App/ChayaARTests` | App-level tests: capability/permission states, ARKit tracking-state mapping, transforms, reference-image construction, renderer placement against the frame math, a refused leg drawing nothing | iOS Simulator (`xcodebuild test`) |

## Build and run

```bash
# core tests, any platform (Windows: use Docker)
swift test
docker run --rm -v "$PWD:/src" -w /src swift:5.10-jammy swift test

# the app (macOS with Xcode 15+): the exact commands of the `ios` workflow's `app` job
brew install xcodegen
cd App && xcodegen generate     # writes ChayaAR.xcodeproj (not committed); project.yml pins projectFormat xcode15_0
xcodebuild build -project ChayaAR.xcodeproj -scheme ChayaAR -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO
xcodebuild test  -project ChayaAR.xcodeproj -scheme ChayaAR -destination 'platform=iOS Simulator,name=iPhone 15' CODE_SIGNING_ALLOWED=NO
open ChayaAR.xcodeproj          # to run on a device
```

In Xcode, set a signing team on the `ChayaAR` target and set the `CHAYA_API_BASE_URL` build setting to your API's HTTPS
origin (the default, `https://chaya.example.invalid`, only lets the app launch). Then run on a **physical** iPhone or
iPad (A9 or later, iOS 16+). In the Simulator the app shows `WORLD_TRACKING_UNSUPPORTED` and cannot start AR, by
design.

## How a detection becomes a position

1. The operator registers an `IMAGE_TARGET` anchor with its printed width, prints `GET .../anchors/{id}/target.png`
   at that width, and calibrates it (docs/ar-android-validation.md §3 has the same marker setup).
2. The app downloads each calibrated image target's PNG and builds an `ARReferenceImage` at the printed width,
   **named with the anchor id**. ARKit's own `validate()` rejects untrackable images, and those are listed as
   unusable. QR, ArUco and AprilTag anchors are listed as not detectable: ARKit has no pose detector for them.
3. `ARWorldTrackingConfiguration` (gravity-aligned, +Y up) detects and tracks those images. Every `ARFrame`'s
   `ARImageAnchor`s become `MarkerObservation`s. Each carries the anchor id (from `referenceImage.name`), ARKit's
   transform, the frame timestamp, `isTracked` and `estimatedScaleFactor`. Only tracked observations whose scale
   matches the printed size within 20 % are used.
4. They go to `POST .../anchors/relocalize`, which returns the ARKit world → canonical venue transform, in the
   coordinate frame and scan version the floor publishes. The first answer pins that version for the session; every
   later request sends it, and an answer in another version is refused. An `ARAnchor` added at the observed marker
   pose carries ARKit's later map corrections into that transform.
5. The route is planned from the device's canonical position **in the pinned scan version**. Its leg is drawn with
   SceneKit only if the route's `routingSources` entry for the floor names the localization's version and frame, and
   it is advanced from `ARFrame.camera.transform` while localized.
