# Chaya AR — iOS (Swift/ARKit)

Native iOS AR navigation client. iOS Safari has no WebXR, so iOS gets this app instead of the web client Android uses.
[docs/ar.md](../../docs/ar.md) describes the shared anchor/relocalization/route model and the iOS design.

> **Status: NOT DEVICE-VALIDATED.** This app was written on a machine with no macOS, Xcode or iPhone.
> - `ChayaARCore` is built and tested on Linux Swift 5.10 (`swift test`, 35 tests).
> - The ARKit layer and the app were only syntax-checked (`swiftc -parse`). They have **never been type-checked,
>   built or run**. The `ios` CI workflow is the first place they will compile.
> - The app shows a "NOT DEVICE-VALIDATED" banner on every screen (`DeviceValidation.status`) until
>   [docs/ar-ios-validation.md](../../docs/ar-ios-validation.md) has been run on a physical iPhone and its record
>   filled in.

## Layout

| Path | What | Builds / tests where |
|---|---|---|
| `Sources/ChayaARCore` | Backend models and API client (public viewer link auth), the camera → ARKit world → canonical venue frame chain (`VenueFrames`), the marker registry (reference image name → anchor id → anchor), route geometry and progress, and the tracking/relocalization state machine | Anywhere Swift runs: `swift test` (Linux CI) |
| `Sources/ChayaARKitSession` | `ARSessionManager` (ARKit session, detections, relocalization, world anchor, route placement), `ReferenceImages`, `RouteSceneRenderer` (SceneKit), ARKit ↔ core conversions | iOS only (`#if canImport(ARKit)`) |
| `App/` | The SwiftUI app: viewer link → floor → destination → AR. `Info.plist` has `NSCameraUsageDescription` and requires `arkit`. `project.yml` is the XcodeGen spec. | Xcode on macOS |
| `App/ChayaARTests` | App-level tests: capability/permission states, ARKit tracking-state mapping, transforms, reference-image construction, and renderer placement against the frame math | iOS Simulator (`xcodebuild test`) |

## Build and run

```bash
swift test                      # core tests, any platform (or: docker run --rm -v "$PWD:/src" -w /src swift:5.10-jammy swift test)

brew install xcodegen           # macOS
cd App && xcodegen generate     # writes ChayaAR.xcodeproj (not committed)
open ChayaAR.xcodeproj
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
4. They go to `POST .../anchors/relocalize`, which returns the ARKit world → canonical venue transform. An `ARAnchor`
   added at the observed marker pose carries ARKit's later map corrections into that transform.
5. The route is planned from the device's canonical position, drawn with SceneKit, and advanced from
   `ARFrame.camera.transform` while localized.
