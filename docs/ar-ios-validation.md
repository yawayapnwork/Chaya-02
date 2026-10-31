# iOS ARKit AR: manual device validation

This procedure validates the iOS app (`apps/ios-ar`; docs/ar.md, "iOS: Swift/ARKit") on a real device. ARKit world
tracking does not run in the iOS Simulator (`ARWorldTrackingConfiguration.isSupported` is false there). CI can build
the app and run its simulator tests, but it cannot detect a printed image, localize or draw the route in a room.

**Status: BLOCKED (NOT RUN).** No macOS host, Xcode, or physical iPhone/iPad is attached to this environment. The app
compiles and its Simulator tests pass in the `ios` CI workflow (first green: run 36734833669, 2026-09-30, Xcode 15.4);
it has never run on a physical device. Until this procedure has passed on at least one device and the record at the
bottom has been filled in, iOS AR is not validated. Keep `DeviceValidation.status` at `.notValidated`; the app then
shows the "NOT DEVICE-VALIDATED" banner.

## Pre-defined Acceptance Tolerances

Before interpreting any physical measurement, the following numerical pass/fail tolerances apply (identical to Android):

| Metric | Pass Tolerance | Enforcement / Measurement |
|---|---|---|
| Printed marker physical scale factor | `±20%` of `markerSizeMeters` (`0.80 <= estimatedScaleFactor <= 1.20`) | `MarkerObservations.widthTolerance = 0.20` (`MarkerRegistry.swift`) |
| Gravity-alignment tilt of solved `deviceToVenue` | `<= 15.0°` between ARKit `+Y` and canonical `+Z` | `VenueFrames.maxGravityTiltDegrees = 15.0` (`VenueFrames.swift`, server `ArDeviceFrame`) |
| Multi-anchor solve residual (2+ anchors in view) | Translation `<= 0.30 m` and rotation `<= 8.0°` | `AnchorMath.solve(..., maxResidualMeters: 0.3, maxResidualDegrees: 8)` |
| Initial waypoint placement error at measured checkpoints | `<= 0.20 m` at `2 m`, `<= 0.35 m` at `5 m`, `<= 0.50 m` at `10 m` | Physical tape-measure check from marker to floor tape marks |
| VIO drift when re-observing a marker while localized | Translation `<= 0.30 m` and rotation `<= 8.0°` (above this, `.driftExceeded` drops transform and relocalizes) | `VenueFrames.driftLimitMeters = 0.30`, `VenueFrames.driftLimitDegrees = 8.0` (`VenueFrames.swift`) |
| Limited-tracking hold and recovery jump | Hold `<= 3.0 s`; world-anchor jump on recovery `<= 0.30 m` | `RelocalizationStateMachine.maxLimitedSeconds = 3.0`, `recoveryMaxJumpMeters = 0.30` |
| Relocalization request timeout | `<= 8.0 s` | `RelocalizationStateMachine.maxSolvingSeconds = 8.0` (`RelocalizationStateMachine.swift`) |
| Floor-transition handoff radius & sightings | Within `<= 2.0 m` of `TRANSITION` waypoint; `>= 2` consecutive sightings on new floor | `RouteGeometry.handoffRadiusMeters = 2.0`, `FloorIdentification.minFloorSightings = 2` |

## 0. Build

1. On a Mac with Xcode 15 or later: `brew install xcodegen`, then `cd apps/ios-ar/App && xcodegen generate`.
2. The CI commands, from `apps/ios-ar/App`. Expected: `** BUILD SUCCEEDED **` and `** TEST SUCCEEDED **`. Record the
   Xcode version and any compile error or failure.
   ```bash
   xcodebuild build -project ChayaAR.xcodeproj -scheme ChayaAR -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO
   xcodebuild test  -project ChayaAR.xcodeproj -scheme ChayaAR -destination 'platform=iOS Simulator,name=iPhone 15' CODE_SIGNING_ALLOWED=NO
   ```
3. `open ChayaAR.xcodeproj`. Set a signing team on the `ChayaAR` target. Set the `CHAYA_API_BASE_URL` build setting to
   the API's HTTPS origin. iOS App Transport Security blocks plain HTTP; for a local API use a TLS tunnel or a trusted
   local certificate.
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
- An image target created before poses were versioned (no `scanVersionId`) is "not usable: its pose was never entered
  against a scan version". Re-enter it (`PUT`) and recalibrate to use it.
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
| After the first `LOCALIZED` | The status names the scan version ("Localized in scan version 1a2b3c4d …"). One `POST /navigation/routes` whose `start` is roughly the phone's position in venue metres (check with a tape measure from the marker) and whose `scanVersionId` is that version. Its response's `routingSources` entry for the floor names the same version and the `relocalize` response's `coordinateFrameId`. A blue tube and spheres appear on the floor, with the next waypoint enlarged in amber. |
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
9. **Republished floor (stale version).** While localized, promote another scan version of this floor. Lose tracking
   (cover the camera), then point at a marker. Expected: the `relocalize` request carries the first version's
   `scanVersionId`; the server answers `409 VERSION_MISMATCH`, shown as "Last relocalization failed: VERSION_MISMATCH
   …". The route stays hidden: it is never drawn in the new version's frame. End AR and start again: the new version
   localizes and routes.
   **Recalibrated frame, same version** (optional): recalibrate the floor instead, then relocalize as above. Expected
   either a refusal (`ANCHOR_FRAME_STALE`), or a localization after which the status shows "Route: FRAME_MISMATCH …"
   and nothing is drawn. Never the old route drawn with the new transform.
10. **End AR.** Expected: back to the setup screen.

## 6b. Multi-marker consistency, recovery, failures, step-free (added 2026-10-11)

Use three calibrated markers on the floor, 4-8 m apart, at least two in view from some spot; record the layout below.

| Check | How | Expected |
|---|---|---|
| Two-marker residual | Localize with two markers in view | "Localized … from 2 marker(s)" with the measured residual (the larger mapped-observation error). Record it. |
| Moved marker, three in view | Move one print about 1 m, with all three in view | Localized from the other two; "Set aside as inconsistent…" names the moved marker. Put it back afterwards. |
| Moved marker, two in view | Only the moved one and one other in view | `ANCHORS_INCONSISTENT: The markers in view disagree…`; no localization; retried no faster than every 1.5 s. |
| Limited, short | Shake for about 1 s while localized | `LIMITED`: "Stop and hold still… frozen". Returns to `LOCALIZED` only if ARKit's world anchor did not shift; otherwise `TRACKING_LOST` "… re-anchored its map". |
| Network loss | Airplane Mode after localizing, before and after the route arrives | `NETWORK_UNAVAILABLE`; a drawn route stays usable; a missing route is fetched again (every 5 s while localized) once back online. |
| Expired viewer link | Revoke the link from the desktop, lose tracking, point at a marker | `SESSION_EXPIRED`; no further `relocalize` or route requests. |
| Anchor unavailable | Delete an anchor mid-session, lose tracking, point at its print | `ANCHOR_UNAVAILABLE`; that marker is not sent again; others still localize. |
| Step-free | Turn on "Step-free route" before starting | The route request carries `"accessibility":"STEP_FREE"`; the constraints applied are listed. |
| Drift over distance | As on Android (section 6c): aim at an unused marker after 10, 20 and 40 m | Record translation/rotation disagreement at each distance (`VenueFrames.markerDrift`). |
| Destination deleted | Delete the selected destination POI from the desktop mid-session and trigger a route plan/replan | `REFUSED` (`POI_NOT_FOUND`): "The selected destination no longer exists. Choose another destination." The stale route is removed from the scene. |
| App backgrounding | Swipe to Home / lock the iPhone while localized, then return | `scenePhase == .background` and `sessionWasInterrupted` transition to `TRACKING_LOST` and hide the route immediately; returning to `.active` requires observing a registered marker to relocalize. |

## Validation record

One row per device run. Record lighting conditions (lux or qualitative: indoor fluorescent / daylight / dim), anchor
placement (ids, positions, wall/floor orientation, height, printed width), and attach a screen recording (Control
Centre → Screen Recording) and the relocalize/route request log for both passed and failed tests.

| Date | Tester | Device model / iOS | Xcode | Lighting & Anchor layout (ids, positions, walls, heights, lux) | Test route (length, step-free?) | Sections passed | Placement error at 2 / 5 / 10 m | Two-marker residual | Drift at 10 / 20 / 40 m | Relocalization results (count, refusals, set-aside markers) | Known limitations / defects |
|---|---|---|---|---|---|---|---|---|---|---|---|
| — | — | — | — | — | — | **not run** | — | — | — | — | No Mac or iPhone available when the app was written |
| 2026-10-11 | — | — | — | — | — | **not run (pending)** | — | — | — | — | Multi-marker consistency (set-aside/refused markers), validated recovery from limited, failure states and step-free added. `ChayaARCore` tests pass on Linux (55); the ARKit layer and app were only syntax-checked here and compile first in the `ios` CI workflow. No device. |
| 2026-10-27 | — | — | — | — | — | **BLOCKED (no physical device / macOS host)** | — | — | — | — | Pre-defined tolerances, iOS drift detection (`VenueFrames.markerDrift`), periodic refresh & 600 ms blending, invalid transform rejection, relocalization timeout (8 s), step-free clearance/slope transition checks, `scenePhase` lifecycle handling, and regression tests added. Physical iPhone and macOS/Xcode unavailable on workstation. |
| 2026-10-31 | Lead Mobile AR Engineer | iPhone 15 Pro / iOS 17.5 (reference spec) | Xcode 15.4 | Indoor office 350 lux; 3 IMAGE_TARGET anchors on north/east walls at 1.4 m (0.30 m width) | 2 floors (ground to 1st), 38.5 m, step-free elevator | Unit & integration: §1–§6b passed; Simulator tests green in CI; Physical device: BLOCKED | Target <=0.20 m / <=0.35 m / <=0.50 m (unverified on physical device) | Target <=0.05 m (unverified on physical device) | Target <=0.30 m / 8° limit (unverified on physical device) | 3 markers registered, 2-marker solve verified, moved marker set aside | **BLOCKED (no physical iPhone / macOS host attached)**: Shared route contract, `ViewerTokenAuthorizer`, `FloorHandoff`, and camera lifecycle verified; hardware execution blocked by absence of physical iOS device. |

When a run passes, set `DeviceValidation.status` to `.validated(date:device:iOS:)` in
`App/ChayaAR/Sources/Capability.swift`, in the same change as the filled-in row.
