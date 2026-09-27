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

Both clients implement the same state machine (`apps/web/lib/ar-relocalization.ts`,
`ChayaARCore/RelocalizationStateMachine.swift`):

```
UNINITIALIZED -> DETECTING -> LOCALIZED -> TRACKING_LOST -> RELOCALIZING -> LOCALIZED
                                  ^                                            |
                                  +--------------------------------------------+
```

On a tracking-loss signal from the platform session (WebXR `visibilitychange`/frame loss, ARKit
`.limited`/`.notAvailable` camera tracking state):

1. **Freeze the last known route state** — the last route, waypoint index, and rendered position are
   retained unchanged; nothing about the route is recomputed or discarded.
2. **Show a tracking-loss state** in the UI — never silently keep rendering as if nothing happened.
3. **Prompt for re-localization** — ask the user to re-sight a known anchor; do not guess a resumed pose.
4. **Never silently move the user marker.** A `LOCALIZED` state is only re-entered from a fresh
   `relocalize` call (or, for pure motion tracking recovery, the platform explicitly reporting normal
   tracking again) — an interpolated guess is never presented as a confirmed position.

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

`apps/ios-ar` is a Swift package foundation:

- `Sources/ChayaARCore` — platform-independent: `Models.swift` (Codable mirrors of the server DTOs),
  `APIClient.swift` (a thin `URLSession` client for the same `/api/v1` path/anchor/relocalization
  endpoints), `AnchorMath.swift`, `RelocalizationStateMachine.swift`. No ARKit or UIKit import, so
  `swift test` runs its tests anywhere Swift runs (Linux CI included).
- `Sources/ChayaARKitSession` — the real ARKit integration: `ARSessionManager.swift` wraps
  `ARSession`/`ARWorldTrackingConfiguration` with an `ARReferenceImage` set built from registered anchors'
  `markerIdentifier`s, and maps ARKit's own `ARCamera.TrackingState` into
  `RelocalizationStateMachine` events. It reads real `ARFrame`/`ARAnchor` data only — **it does not
  fabricate device sensor data**; where ARKit itself hasn't detected something, that value is `nil`/absent
  in the model, never a placeholder. This target requires iOS/ARKit to compile and only runs on a physical
  device with a camera (the iOS Simulator does not implement ARKit camera tracking).

Turning the package into a runnable app (an `.xcodeproj`/`.xcworkspace` app target that embeds
`ChayaARCore` + `ChayaARKitSession`, with `NSCameraUsageDescription` in `Info.plist`) is an Xcode-side step
documented in `apps/ios-ar/README.md`, not something meaningfully representable as hand-written project
files here.

## Testing

Three separated tiers, per `apps/ios-ar/README.md` and the test files themselves:

| Tier | Where | Requires a physical device? |
|---|---|---|
| Coordinate transformation / anchor math | `CoordinateTransformTest.java`, `ar-anchor-math.test.ts`, `AnchorMathTests.swift` | No — pure functions, run in CI |
| Device/canonical axis boundary, gravity-tilt check | `ArDeviceFrameTest.java`, `ar-frame-boundary.test.ts` | No — pure functions. The iOS package has **not** been updated to the boundary (no Swift toolchain here) |
| Relocalization state machine | `ar-relocalization.test.ts`, `RelocalizationStateMachineTests.swift` | No — pure state transitions |
| Web camera → AR world → venue chain, world-anchor correction, route placement and progress | `ar-route.test.ts` | No — pure functions |
| Image-tracking result → marker observation (anchor identity, eligibility) | `ar-marker-tracking.test.ts` | No — pure functions over WebXR-shaped results |
| Anchor CRUD / calibration / relocalization service, image-target size and image | `AnchorServiceTest.java` | No — Testcontainers Postgres only (skipped, not failed, without Docker) |
| WebXR capability detection (every unsupported state) | `webxr-support.test.ts` | No — injected `navigator`/globals |
| `/ar` on desktop Chromium: explicit unsupported state, no start button, no canvas | `e2e/ar.spec.ts` (Playwright) | No — real Chromium; two cases replace WebXR globals to reach the image-tracking/anchors states |
| WebXR device integration (image tracking, world anchor, rendering, tracking loss) | [ar-android-validation.md](ar-android-validation.md), manual | **Yes** — an ARCore Android device with Chrome; **not yet run** |
| ARKit device integration (image detection, tracking-state transitions, camera) | none automated | **Yes** — a physical iOS device; the Simulator cannot run ARKit camera tracking |
