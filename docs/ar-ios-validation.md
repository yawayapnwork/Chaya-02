# iOS ARKit AR: manual device validation

This procedure validates the iOS app (`apps/ios-ar`; docs/ar.md, "iOS: Swift/ARKit") on a real device. ARKit world
tracking does not run in the iOS Simulator (`ARWorldTrackingConfiguration.isSupported` is false there). CI can build
the app and run its simulator tests, but it cannot detect a printed image, localize or draw the route in a room.

**Status: NOT RUN.** The app was written without a Mac, Xcode or iPhone. Its ARKit layer and app target have only been
syntax-checked. The first build is the `ios` CI workflow, or step 0 below. Until this procedure has passed on at
least one device and the record at the bottom has been filled in, iOS AR is not validated. Keep
`DeviceValidation.status` at `.notValidated`; the app then shows the "NOT DEVICE-VALIDATED" banner.

## 0. Build

1. On a Mac with Xcode 15 or later: `brew install xcodegen`, then `cd apps/ios-ar/App && xcodegen generate`, then
   `open ChayaAR.xcodeproj`.
2. Set a signing team on the `ChayaAR` target. Set the `CHAYA_API_BASE_URL` build setting to the API's HTTPS origin.
   iOS App Transport Security blocks plain HTTP; for a local API use a TLS tunnel or a trusted local certificate.
3. **Product → Test** on an iOS Simulator. Expected: all `ChayaARTests` pass. Record any compile error or failure:
   this is the first time this code is compiled.
4. Build and run on the device.

## 1. Supported device

| Requirement | Why |
|---|---|
| iPhone or iPad with an A9 chip or later (ARKit world tracking), iOS 16 or later | The deployment target is 16.0, and `UIRequiredDeviceCapabilities` requires `arkit` |
| Record the model, iOS version and whether it has LiDAR | LiDAR changes tracking quality; the app does not depend on it |

## 2. Permissions

1. First **Start AR navigation**. Expected: the iOS camera prompt shows the `NSCameraUsageDescription` text. Allow.
   The camera view opens with the status panel and the orange "NOT DEVICE-VALIDATED" banner.
2. **Denied.** In Settings → Privacy & Security → Camera, turn Chaya AR off, then return to the app. Expected: the
   setup screen shows `CAMERA_PERMISSION_DENIED`, Start is disabled, and there is no camera view.
3. **Simulator** (control). Expected: `WORLD_TRACKING_UNSUPPORTED`, Start disabled.

## 3. Marker setup

The same as Android (docs/ar-android-validation.md §3). Summary:

1. Use at least two `IMAGE_TARGET` anchors on the floor, each with `markerSizeMeters`.
2. Print each `target.png` (whole image, border included) at exactly that width, measure it, and mount it flat.
3. Measure and record each mounted centre against the venue's control points. Enter it as `digitalPose` using the
   marker pose convention (+X right, +Y out of the face, +Z toward the bottom edge). Calibrate each anchor.
4. Also register one `APRILTAG` anchor as a control.
5. Create a public viewer link for the venue.

Expected on the setup screen, after opening the link and choosing the floor:
- Each image target is "detectable (ARKit image tracking)", unless ARKit's `validate()` rejects its image. That is
  a platform verdict; record it.
- The AprilTag is "not detectable here: ARKit has no QR/ArUco/AprilTag pose detection".
- With only the AprilTag registered, the screen shows `NO_DETECTABLE_ANCHORS` and Start stays disabled.

## 4. Expected tracking behaviour

| Step | Expected |
|---|---|
| Start | `SEARCHING · tracking limited (initializing)`, with "Waiting for ARKit tracking". **Not** "lost" (review AR-2). Within a few seconds it shows `tracking normal` and "Point the camera at a registered marker." |
| Point at a floor or blank wall | Stays `SEARCHING`. Nothing is drawn. |
| Point at the AprilTag | Stays `SEARCHING` |
| Point at a registered image from 0.5–2 m | `SOLVING`, naming that marker, then `LOCALIZED` "from 1 marker(s) (residual unknown: one marker)". Using a proxy (Charles, or server logs), the `relocalize` body's `anchorId` is **that** marker's anchor id. |
| Show a copy printed at 70 % size | "Marker not usable: … check the printed size". No localization. |
| Two markers in view at once | "from 2 marker(s), residual N m". Record N. |

## 5. Expected route behaviour

| Check | Expected |
|---|---|
| After the first `LOCALIZED` | One `POST /navigation/routes` whose `start` is roughly the phone's position in venue metres (check with a tape measure from the marker). A blue tube and spheres appear on the floor, with the next waypoint enlarged in amber. |
| Walk around | The route stays fixed to the room, not the screen |
| Placement accuracy | Measure the drawn route against its canonical waypoints at 2, 5 and 10 m from the marker. Record the error. |
| Orientation | The route leaves the marker in the right direction. A rotation about the marker means `digitalPose` is rotated about the vertical, or the ARKit image axes differ from the documented convention. Record which. |
| Progress | "m to go" falls as you walk, "m off route" grows when you step aside, and passed waypoints turn grey |
| Multi-floor route | Only this floor's leg is drawn, and the transition is named |

## 6. Tracking states, failure and relocalization

1. **Limited, short.** Shake the phone quickly for about 1 s, or point it at a blank wall. Expected:
   `LIMITED · tracking limited (excessiveMotion | insufficientFeatures)`, "route position frozen". The route stays in
   place. Back to `LOCALIZED` when steady.
2. **Limited, long.** Keep the camera on a featureless surface for more than 3 s. Expected: `TRACKING_LOST`, with
   "tracking was limited for more than 3 s". The route is hidden and "m to go" frozen.
3. **Lost.** Cover the camera for about 3 s. Expected: `TRACKINGLOST · tracking lost` (or `limited`). The route is
   hidden and progress frozen.
4. **Recovered is not localized.** Uncover and point at the floor. Expected: `RECOVERED · tracking recovered`, "Point
   the camera at a registered marker…". The route stays hidden.
5. **Relocalize.** Point at the *other* marker. Expected: `SOLVING`, then `LOCALIZED`, the route back in the right
   place, and the request naming the second anchor's id.
6. **Interruption.** Press the Home/side button and return. Expected: a loss ("the AR session was interrupted"),
   possibly `tracking relocalizing` while ARKit restores its map, then `RECOVERED`, then a marker, then `LOCALIZED`.
7. **Server refusal.** While the session runs, `PUT` one anchor unchanged, which resets it to `UNCALIBRATED`. Lose
   tracking, then point at that marker. Expected: "Last relocalization failed: ANCHOR_NOT_CALIBRATED". No
   localization, and it retries no more often than every 1.5 s. Recalibrate afterwards.
8. **Network loss** (Airplane Mode) at a marker. Expected: a relocalization failure. Nothing localized.
9. **End AR.** Expected: back to the setup screen.

## Validation record

One row per device run. Attach a screen recording (Control Centre → Screen Recording) and the relocalize/route
request log.

| Date | Tester | Device / iOS | Xcode | Sections passed | Placement error at 2 / 5 / 10 m | Two-marker residual | Notes / defects |
|---|---|---|---|---|---|---|---|
| — | — | — | — | **not run** | — | — | No Mac or iPhone available when the app was written |

When a run passes, set `DeviceValidation.status` to `.validated(date:device:iOS:)` in
`App/ChayaAR/Sources/Capability.swift`, in the same change as the filled-in row.
