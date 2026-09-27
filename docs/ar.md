# Chaya 02 — AR navigation foundation

Cross-platform AR wayfinding. Android has no native client: it uses WebXR inside the existing Next.js
app (`apps/web`). iOS has no WebXR (Safari does not implement it), so it gets a native Swift/ARKit app
(`apps/ios-ar`). Both are thin clients: they consume the same backend venue/floor/anchor/path/waypoint/
destination model and never invent geometry, poses, or sensor data of their own.

## Data model

Both clients read the same shapes from `/api/v1`:

- **venue**, **floor** — existing `GET /venues`, `GET /venues/{id}/floors` (ARCHITECTURE.md §5).
- **anchor** — `dev.chaya.api.ar.ArDtos.Anchor` / `ar_anchor` table (`V14__ar_anchors.sql`):
  `id`, `venueId`, `floorId`, `markerType` (`QR_CODE` | `ARUCO_MARKER` | `IMAGE_TARGET` | `APRILTAG`),
  `markerIdentifier`, `physicalPose`, `digitalPose` (each a position + unit quaternion), `calibrationStatus`
  (`UNCALIBRATED` | `CALIBRATED` | `STALE`), `lastCalibratedAt`.
- **path / waypoint / destination** — `dev.chaya.api.navigation.NavigationDtos.RouteResponse` (docs/navigation.md):
  a `Waypoint` list, each tagged `START` / `WAYPOINT` / `TRANSITION` / `DESTINATION` and a floor id, plus
  `floorTransitions` and `accessibilityConstraintsApplied`. AR clients render this same route; they do not
  compute their own paths.

A floor may have several anchors — see "Relocalization" below for why that is not just redundancy.

## Anchors and calibration

`POST/PUT /venues/{v}/floors/{f}/anchors` (ADMIN/VENUE_MANAGER/OPERATOR) registers a marker's
`physicalPose` (the marker's own physical placement) and `digitalPose` (its pose in canonical venue metres, +Z up,
in the floor's **current coordinate frame** -- [coordinate-frames.md](coordinate-frames.md)). A floor without a
calibrated frame cannot have anchors (`409 NOT_CALIBRATED`); every anchor records the frame its pose is in
(`coordinateFrameId`). A new or edited anchor starts `UNCALIBRATED`; an operator marks
it `CALIBRATED` with `POST .../anchors/{a}/calibrate` only after physically verifying `digitalPose` against
the built reconstruction. **Relocalization refuses an uncalibrated anchor** (`ANCHOR_NOT_CALIBRATED`, 409)
rather than trusting an unverified pose, and refuses an anchor whose frame is no longer the floor's current one
(`ANCHOR_FRAME_STALE`, 409). Recalibrating the same reconstruction re-projects anchors exactly; a different
reconstruction becoming current marks calibrated anchors `STALE` until their poses are re-entered.

## Relocalization

`POST /venues/{v}/floors/{f}/anchors/relocalize` takes one to 16 `{anchorId, observedPose}` pairs, one per anchor
— each `observedPose` is the marker's pose as the *client's own tracking session* currently reports it, in the
device's native convention (metres, gravity-aligned, **+Y up**: `DEVICE_Y_UP_RIGHT_HANDED_METRES`), never
fabricated — see "Do not fabricate device sensor data" below. The server (`dev.chaya.api.ar.AnchorService#relocalize`,
`CoordinateTransform`) composes each anchor's known `digitalPose` (canonical, +Z up) with the inverse of its
`observedPose` to get a candidate device-frame → venue-frame transform. The device/canonical axis boundary is
`dev.chaya.api.ar.ArDeviceFrame` (web: `lib/ar-frame-boundary.ts`): because both frames are gravity-aligned, each
candidate must send device +Y to canonical +Z; one that tilts it by more than `chaya.frames.max-device-gravity-tilt-deg`
is refused (`RELOCALIZATION_GRAVITY_MISMATCH`, 409) -- the signature of a mis-entered anchor orientation or an
axis-convention mistake. The response reports the measured `gravityTiltDegrees`, the `deviceFrameConvention`, and
the `coordinateFrameId` the transform lands in. Then:

- **one anchor**: that transform is used directly; the residual is `null` -- unknown, since there is nothing to
  compare it against (never reported as 0).
- **several anchors**: candidates are blended (translation averaged, quaternions nlerp-averaged), and the
  **residual** returned is the real, measured largest pairwise translation disagreement between the
  candidates — never a fabricated or assumed accuracy number. **Never claim centimeter accuracy without
  measurement**: the API reports this residual and nothing stronger.
- **interpolation between anchors** while walking between fixes is `CoordinateTransform.interpolate`
  (linear translation + quaternion nlerp) — the same primitive both clients' own local math wraps for
  smoothing between relocalization fixes without waiting for a fresh server round trip every frame.

The identical algorithm exists in three places on purpose, each independently unit-tested against the same
cases: `dev.chaya.api.ar.CoordinateTransform` (`CoordinateTransformTest`), `apps/web/lib/ar-anchor-math.ts`
(`ar-anchor-math.test.ts`), and `apps/ios-ar/Sources/ChayaARCore/AnchorMath.swift`
(`AnchorMathTests.swift`). The server is authoritative for anything that must be consistent across clients
(routes, audit); the transform itself must also run client-side, at frame rate, without a network call.

## Tracking failure

Both clients implement the same rules, each in a pure, unit-tested reducer: `apps/web/lib/ar-relocalization.ts`
(states in "Android: WebXR" below) and `ChayaARCore/RelocalizationStateMachine.swift` ("iOS: Swift/ARKit" below).

1. **Freeze the route.** Progress along the route advances only while localized. On a loss, the last route and
   progress are kept unchanged. Nothing is recomputed or guessed.
2. **Show the tracking state** in the UI. The route is never silently kept rendering as if nothing happened.
3. **Prompt for relocalization.** Ask the user to point at a registered marker.
4. **Recover only from a new observation.** The platform reporting normal tracking again is *not* a localization:
   the world frame may have been reset or moved during the loss. Localization is only re-entered after a fresh
   marker observation and a successful server `relocalize`. Its transform then replaces the old one.
5. **Startup is not a loss.** Platform initialisation before the first localization is shown as "waiting for
   tracking", never as "tracking lost".

## Security

AR sessions are scoped by the same tenancy model as the rest of the API (docs/security.md): every anchor
and relocalization endpoint is `/venues/{venueId}/floors/{floorId}/...` and goes through `TenantGuard`, so
a session authenticated for one venue can never read another venue's anchors. Anchor reads and
relocalization allow `PUBLIC_VIEWER` exactly like POIs and routes do — a public AR viewer link is already
bound to one venue by the opaque viewer token that authenticated it (docs/security.md "Public viewer
links"), so this grants no broader surface than the rest of the public viewer already has. **Public AR
access never takes a venue id from an unauthenticated caller** — there is no anchor or relocalization
endpoint that does not require either a bearer token or an already-exchanged, venue-bound viewer token.
Anchor mutation (create/update/delete/calibrate) requires ADMIN/VENUE_MANAGER/OPERATOR, matching who may
run captures.

## Android: WebXR

`apps/web/app/ar/page.tsx` + `apps/web/components/ArWorkspace.tsx`. **Device validation status: NOT DONE.** No
physical Android device was available when this client was written. Everything below that runs on a device is
implemented against the WebXR APIs but has not been observed working. The procedure that would validate it is
[ar-android-validation.md](ar-android-validation.md). Until it has been run and its record filled in, treat this client
as an unvalidated integration, not a working feature.

### What WebXR actually provides, and what this client uses

Five different things get called "anchors" or "detection" in AR. They are kept apart here:

| Concept | WebXR on Chrome for Android (ARCore devices) | Used for |
|---|---|---|
| **Plane hit testing** (`hit-test`) | Shipped. Casts a ray and returns the pose of a real surface it hits. | **Nothing.** A hit says there is a floor or wall under a ray. It does not say which registered marker is where, so it cannot localize anything. The earlier client treated it as a marker detection (review AR-1). That code is gone. |
| **Image tracking** (`image-tracking`, WebXR Marker Tracking incubation) | Only behind `chrome://flags/#webxr-incubations`. Per registered image it reports the image index, the pose of the image's centre, `tracked`/`emulated`, and the measured width. | **Marker detection**, the only kind this client does. |
| **Fiducial detection** (AprilTag, ArUco, QR) | **Not provided by any WebXR API.** | Nothing. Anchors of those types are listed as "not detectable here" (`FIDUCIAL_DETECTION_UNSUPPORTED`). No detector is simulated. A camera-access + JS-detector pipeline would be a separate project, and `camera-access` is itself unshipped on most builds. |
| **World anchors** (`anchors`, `XRAnchor`) | Shipped. Pins a pose that the platform keeps updating as it refines its map. Identifies nothing. | After a relocalization, one is created at the observed marker's pose. It carries later map corrections into the route transform. If the platform stops tracking it, that counts as tracking loss. |
| **Route rendering** | `XRWebGLLayer` via three.js `WebXRManager` (`lib/ar-route-renderer.ts`) | Draws the server's route (tube + waypoint spheres, next waypoint highlighted) only while localized. |

`dom-overlay` is requested as optional, for the in-session status text.

### Capability states

Each state is explicit and named. None falls back to a simulated view (`lib/webxr-support.ts`):

| State | Detected by |
|---|---|
| `WEBXR_UNAVAILABLE` | `navigator.xr` absent: desktop Chrome, Firefox, Safari, insecure (non-HTTPS) origins |
| `IMMERSIVE_AR_UNSUPPORTED` | `isSessionSupported("immersive-ar")` resolves false (no ARCore / Google Play Services for AR, or desktop with a WebXR emulator) |
| `CAPABILITY_CHECK_FAILED` | `isSessionSupported` threw |
| `IMAGE_TRACKING_UNSUPPORTED` | `XRImageTrackingResult` not exposed (flag off), or a granted session without `getTrackedImageScores` |
| `ANCHORS_UNSUPPORTED` | `XRAnchor` not exposed |
| `CAMERA_PERMISSION_DENIED` | Permissions API says `camera: denied`, or `requestSession` rejects with `NotAllowedError`/`SecurityError` |
| `REQUIRED_FEATURE_UNSUPPORTED` | `requestSession` rejects with `NotSupportedError`. The browser does not say whether `image-tracking` or `anchors` was refused. |
| `NO_DETECTABLE_ANCHORS` | The floor has no calibrated `IMAGE_TARGET` anchor with a printed size |
| `NO_TRACKABLE_MARKERS` | The platform scored every registered image `untrackable` |
| `SESSION_FAILED` | Any other `requestSession` failure |

### Markers: IMAGE_TARGET anchors

An `IMAGE_TARGET` anchor needs `markerSizeMeters`: the printed width of its image, in metres
(`V20__ar_image_target_size.sql`; the server returns `MARKER_SIZE_REQUIRED` without it).
`GET .../anchors/{a}/target.png` returns the anchor's target image. It is a 1024 × 1024 PNG, generated
deterministically from the anchor id (`dev.chaya.api.ar.ImageTarget`), so the image the operator prints and the image
the client registers are the same bytes. The client registers one image per trackable anchor, in a fixed order, so an
`XRImageTrackingResult.index` identifies exactly one backend anchor id (`lib/ar-marker-tracking.ts` `observeImages`).
A result whose index is not registered is dropped.

A **marker observation** (`MarkerObservation`) has the backend anchor id and marker identifier, the measured pose in
the AR world frame, the frame timestamp, the platform tracking state and the measured width. Only a `tracked`
observation whose measured width is within 20 % of the registered printed width can relocalize
(`relocalizationEligibility`). `emulated` means ARCore is extrapolating an image it can no longer see, so it is not a
measurement.

#### Marker pose convention

`digitalPose` of an `IMAGE_TARGET` is the pose of the **centre of the printed image** (the whole PNG, border included)
in canonical venue metres (+Z up), with the image's own axes, the convention WebXR image tracking reports (the ARCore
augmented-image convention):

- **+X** points to the image's right, as you look at it
- **+Y** is the normal, pointing out of the printed face toward the viewer
- **+Z** points toward the image's bottom edge

Example: an image on a wall, facing canonical −Y (a person reading it looks toward +Y), upright. Its +X is canonical
+X, its +Y is canonical −Y and its +Z is canonical −Z. That is 180° about X: `qx=1, qy=0, qz=0, qw=0`. A marker flat on
the floor, top edge toward canonical +Y, is 90° about X: `qx=√½, qw=√½`. A wrong orientation is usually rejected by
the server's gravity check (`RELOCALIZATION_GRAVITY_MISMATCH`). A wrong rotation about the vertical is not, and it
shows up as a route rotated about the marker. The device procedure checks for exactly that. **This convention has not
been confirmed on a device yet.**

### Frames

```
camera/device frame --viewer pose (XRFrame.getViewerPose, per frame)--> AR world frame ("local" space, metres, +Y up)
AR world frame --deviceToVenue (server-solved from marker observations, world-anchor corrected)--> canonical venue (+Z up)
```

- `deviceToVenue` = `digitalPose ∘ observedPose⁻¹` (server, `AnchorService#relocalize`). It absorbs the +Y-up → +Z-up
  axis change, and the server refuses one that tilts gravity (`lib/ar-frame-boundary.ts`, `ArDeviceFrame`).
- World-anchor correction: if the anchor was at `A0` when the transform was solved and is at `At` now,
  the current transform is `deviceToVenue ∘ A0 ∘ At⁻¹` (`lib/ar-route.ts` `anchorCorrectedDeviceToVenue`).
- The device's venue position is `deviceToVenue(viewerPose.position)` (`devicePositionInVenue`). Route progress
  (`progressAlong`) projects it onto the route in the horizontal plane.
- The route is built once in canonical coordinates. The renderer places it with `deviceToVenue⁻¹`
  (`venueToArWorld`). three.js drives the camera from the platform's viewer pose every frame.

### Session state machine (web)

`lib/ar-relocalization.ts`:

```
IDLE -> SEARCHING -> RELOCALIZING -> LOCALIZED -> TRACKING_LOST -> RELOCALIZING -> LOCALIZED
```

- `SEARCHING`: no localization yet. While the platform has not reported 6-DoF tracking (ARCore initialising, or
  `emulatedPosition`), the UI says "waiting for device tracking", not "tracking lost": there is nothing to lose yet.
  Marker observations are ignored until device tracking is confirmed.
- `RELOCALIZING`: an eligible observation has been sent. A world anchor is created in the same frame at the
  observed marker's pose. The answer is applied only if that request is still pending. A tracking loss cancels it,
  and a late answer is dropped along with its anchor.
- `LOCALIZED`: the only state in which route progress advances (`canAdvanceRoute`) and the route is drawn.
- `TRACKING_LOST`: entered from `LOCALIZED` on no viewer pose, emulated position, a hidden session, or the world
  anchor no longer being tracked. The route is hidden and its progress frozen, and the UI says tracking is lost and
  asks the user to point at a marker. **The platform reporting tracking again does not leave this state.** The AR world
  frame may have been reset during the loss. Only a fresh eligible marker observation and a successful server
  relocalization return to `LOCALIZED`. The new transform then replaces the old one.

On the first localization the route is fetched (`POST /navigation/routes`), starting from the device's venue position
and going to the chosen destination. Only the leg on the current floor is drawn. A floor transition is named in the
status text.

## iOS: Swift/ARKit

`apps/ios-ar`: the `ChayaARCore` and `ChayaARKitSession` libraries, plus the SwiftUI app in `apps/ios-ar/App`
(XcodeGen `project.yml`, `Info.plist` with `NSCameraUsageDescription`, `arkit` required). See
[apps/ios-ar/README.md](../apps/ios-ar/README.md).

**Validation status: NOT DONE.** No macOS host, Xcode or iPhone was available.
- `ChayaARCore` is built and its tests pass on Linux Swift 5.10.
- The ARKit layer and the app have only been syntax-checked. They have never been type-checked, built or run; the
  `ios` CI workflow (`.github/workflows/ios.yml`) is the first place they compile.
- The app shows a "NOT DEVICE-VALIDATED" banner (`DeviceValidation.status`) until
  [ar-ios-validation.md](ar-ios-validation.md) has passed on a device.

### Concepts on ARKit

| Concept | ARKit | Used for |
|---|---|---|
| **Image detection/tracking** (`ARReferenceImage` → `ARImageAnchor`) | Detects and tracks the given images, reporting the image's transform, `isTracked` and `estimatedScaleFactor` | **Marker detection.** One reference image per calibrated `IMAGE_TARGET` anchor: the server's `target.png`, at `markerSizeMeters`, **named with the anchor id**. |
| **Fiducials** (AprilTag, ArUco, QR pose) | No pose detector | Nothing. Listed as `FIDUCIAL_DETECTION_UNSUPPORTED`, never treated as detected. |
| **Plane detection / raycasts** | Available | Not used. A surface identifies no marker. |
| **World anchors** (`ARAnchor`) | ARKit updates anchor transforms as it refines its map | One is added at the observed marker's pose when relocalizing. It carries map corrections into the transform, and its removal is a tracking loss. |
| **Route rendering** | `ARSCNView` + SceneKit | `RouteSceneRenderer`: tube and waypoint spheres built in canonical coordinates, placed each frame with the corrected canonical → world pose |

### Identity chain

```
ARImageAnchor.referenceImage.name  (= anchor id, lower-case)
  -> MarkerRegistry.marker(forReferenceImageName:)     registered marker: that anchor, its printed width
  -> AnchorObservation(anchorId, ARImageAnchor.transform)
  -> POST .../anchors/relocalize                       server: that anchor's calibrated digitalPose ∘ observedPose⁻¹
  -> deviceToVenue (ARKit world -> canonical venue, gravity-checked, in the floor's current coordinate frame)
```

An image name the registry did not issue is not attributed to anything. A `MarkerObservation` carries the anchor id,
ARKit's measured pose, `ARFrame.timestamp`, `isTracked`, and the estimated scale. Only a tracked observation whose
scale is within 20 % of the printed size is eligible, with at most one per anchor.

### Frames

The same chain as Android ("Frames" above), in `ChayaARCore/VenueFrames.swift`. The ARKit world is
`worldAlignment = .gravity`: metres, +Y up, the device convention the server expects.
- camera → world: `ARFrame.camera.transform`.
- world → canonical: the server's `deviceToVenue`, corrected by the world anchor
  (`anchorCorrectedDeviceToVenue`).
- The device's venue position (`devicePositionInVenue`) drives progress. The route root node is placed with
  `venueToWorld`.

No scene coordinate is made up by the app. **Marker pose convention:** ARKit's image anchor is expected to use the
same axes as the web client (+X right, +Y out of the face, +Z toward the bottom edge). This has not been confirmed on
a device.

### Tracking states

`RelocalizationStateMachine` (phases `idle → searching → solving → localized`, plus `limited`, `trackingLost`,
`recovered` and `ended`). The UI's tracking status is normal / limited / relocalizing / lost / recovered.

| ARKit signal | Before the first localization | While localized |
|---|---|---|
| `.normal` | Observations accepted | Route shown, progress advances |
| `.limited(.initializing)` | "Waiting for ARKit tracking": **not a loss** (review AR-2) | Loss (the world was reset) |
| `.limited(.excessiveMotion / .insufficientFeatures)` | Waiting | `limited`: route held, progress frozen. Back to `localized` on `.normal`. After `maxLimitedSeconds` (3 s), a loss. |
| `.limited(.relocalizing)` (ARKit restoring its map after an interruption) | Waiting | Loss. Status shows "relocalizing". |
| `.notAvailable`, `sessionWasInterrupted`, world anchor removed or missing | — | Loss |
| `.normal` after a loss | — | `recovered`: route still hidden. A new marker observation and a server solve return to `localized`. |
| `session(_:didFailWithError:)` | `ended`, with the error (`CAMERA_PERMISSION_DENIED` for `ARError.cameraUnauthorized`) | same |

A server answer to a request overtaken by a loss is ignored, and its world anchor is removed.

### Capability states (app)

- `WORLD_TRACKING_UNSUPPORTED`: `ARWorldTrackingConfiguration.isSupported` is false (the Simulator, pre-A9).
  AR cannot start, and no view is simulated.
- `CAMERA_PERMISSION_DENIED`, `CAMERA_RESTRICTED`: from `AVCaptureDevice.authorizationStatus`. When the status is
  not determined, the app asks when AR starts.
- `NO_DETECTABLE_ANCHORS`: no calibrated image target on the floor that ARKit accepts. Each marker row says why.

### Auth

The app opens a public viewer link (`/viewer?link=…` or the secret) and exchanges it at
`POST /public/viewer-token` for a venue-bound token. It sends that token as `X-Chaya-Viewer-Token`, as the web viewer
does. The token allows floors, POIs, anchors, target images, routes and relocalization for that one venue
(see "Security").

## Testing

Test tiers, from pure functions to physical devices:

| Tier | Where | Requires a physical device? |
|---|---|---|
| Coordinate transformation / anchor math | `CoordinateTransformTest.java`, `ar-anchor-math.test.ts`, `AnchorMathTests.swift` | No — pure functions, run in CI |
| Device/canonical axis boundary, gravity-tilt check | `ArDeviceFrameTest.java`, `ar-frame-boundary.test.ts`, `VenueFramesTests.swift` | No — pure functions |
| Relocalization state machine | `ar-relocalization.test.ts`, `RelocalizationStateMachineTests.swift` (incl. AR-2's startup `initializing` case) | No — pure state transitions |
| iOS frames, world-anchor correction, route progress; marker registry (image → anchor id); wire format (null residual, Java instants) | `VenueFramesTests.swift`, `MarkerRegistryTests.swift`, `APIClientCodingTests.swift` | No — `swift test`, Linux CI (`ios` workflow, `core` job) |
| iOS app level: capability/permission states, ARKit tracking-state mapping, transforms, reference images, renderer placement | `App/ChayaARTests/AppLevelTests.swift` | No — iOS Simulator (`ios` workflow, `app` job). **Never run yet** (no macOS here). |
| Web camera → AR world → venue chain, world-anchor correction, route placement and progress | `ar-route.test.ts` | No — pure functions |
| Image-tracking result → marker observation (anchor identity, eligibility) | `ar-marker-tracking.test.ts` | No — pure functions over WebXR-shaped results |
| Anchor CRUD / calibration / relocalization service, image-target size and image | `AnchorServiceTest.java` | No — Testcontainers Postgres only (skipped, not failed, without Docker) |
| WebXR capability detection (every unsupported state) | `webxr-support.test.ts` | No — injected `navigator`/globals |
| `/ar` on desktop Chromium: explicit unsupported state, no start button, no canvas | `e2e/ar.spec.ts` (Playwright) | No — real Chromium; two cases replace WebXR globals to reach the image-tracking/anchors states |
| WebXR device integration (image tracking, world anchor, rendering, tracking loss) | [ar-android-validation.md](ar-android-validation.md), manual | **Yes** — an ARCore Android device with Chrome; **not yet run** |
| ARKit device integration (image detection, relocalization, route placement, tracking states) | [ar-ios-validation.md](ar-ios-validation.md), manual | **Yes** — a physical iPhone/iPad; the Simulator cannot run ARKit world tracking. **Not yet run.** |
