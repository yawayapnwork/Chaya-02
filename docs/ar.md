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

`apps/web/app/ar/page.tsx` + `apps/web/components/ArWorkspace.tsx`. Capability detection
(`apps/web/lib/webxr-support.ts`) checks the real, present browser API:
`navigator.xr?.isSessionSupported('immersive-ar')`. When `navigator.xr` is absent (desktop Chrome, most
current dev machines/browsers) or the promise resolves `false`, the workspace renders an explicit
**unsupported-device state** naming the reason — it never falls back to a fake or simulated AR view.
Hit-testing for anchor detection uses the WebXR `hit-test` feature against the platform's own plane/marker
detection; nothing about a detected pose is synthesized client-side.

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
| Anchor CRUD / calibration / relocalization service | `AnchorServiceTest.java` | No — Testcontainers Postgres only (skipped, not failed, without Docker) |
| WebXR capability detection (unsupported-device branch) | `webxr-support.test.ts` | No — mocks `navigator.xr`'s presence/absence |
| WebXR device integration (hit-test, real session) | none automated | **Yes** — a WebXR-capable Android/Chrome device; not run in CI |
| ARKit device integration (image detection, tracking-state transitions, camera) | none automated | **Yes** — a physical iOS device; the Simulator cannot run ARKit camera tracking |
