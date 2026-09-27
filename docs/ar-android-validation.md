# Android WebXR AR: manual device validation

This is the procedure that validates the Android AR client (`/ar`, docs/ar.md "Android: WebXR") on a real device. None
of it can run in CI or on a desktop: desktop Chrome has no `immersive-ar`, and the WebXR emulator extensions do not
implement image tracking or ARCore anchors.

**Status: NOT RUN.** No physical Android device was available when the client was written. The unit tests cover the
transforms, observation handling, capability states and state machine. They do not show that a real session delivers
frames, detects the printed images, creates anchors or draws the route. Do not describe Android AR as working until
this procedure has passed on at least one device and the record at the bottom has been filled in.

## 1. Supported browser and device

| Requirement | Why |
|---|---|
| Android phone on the [ARCore supported devices list](https://developers.google.com/ar/devices), with **Google Play Services for AR** installed and up to date | `immersive-ar` in Chrome is backed by ARCore |
| **Chrome for Android**, current stable (record the version) | Other Android browsers are out of scope. Samsung Internet and Firefox are not supported. |
| `chrome://flags/#webxr-incubations` **Enabled**, then Chrome restarted | WebXR image tracking is an incubation. Without the flag the page must show `IMAGE_TRACKING_UNSUPPORTED`. |
| The page served over **HTTPS**, or `http://localhost` through `adb reverse` | WebXR only exists in secure contexts |

Serving a local build to the phone:

```bash
# on the dev machine, with the phone connected over USB and USB debugging on
adb reverse tcp:3000 tcp:3000   # web app
adb reverse tcp:8080 tcp:8080   # API, if the web app calls it at localhost:8080
# then open http://localhost:3000/ar on the phone; chrome://inspect on the desktop shows its console
```

## 2. Permissions

1. The first time **Start AR navigation** is tapped, Chrome asks for camera access (and, on some versions, to confirm
   entering AR). Allow it. Expected: the camera view opens with the status overlay at the top.
2. **Denied case.** In Chrome → Site settings → Camera, block the site, then reload. Expected: the page shows
   `CAMERA_PERMISSION_DENIED` before any session starts, or right after tapping Start (the session request is
   rejected with `NotAllowedError`). No camera view, and nothing that looks like AR. Allow the camera again afterwards.

## 3. Marker setup

For each marker (use **at least two** on the floor, so the multi-marker residual and switching markers are tested):

1. Pick a flat, well-lit, non-glossy spot: a wall at eye height, or the floor.
2. Register the anchor (ADMIN/VENUE_MANAGER/OPERATOR). The floor must have a calibrated coordinate frame:
   ```
   POST /api/v1/venues/{v}/floors/{f}/anchors
   { "markerType": "IMAGE_TARGET", "markerIdentifier": "entrance-wall", "markerSizeMeters": 0.30,
     "physicalPose": {...}, "digitalPose": { "x":..,"y":..,"z":.., "qx":..,"qy":..,"qz":..,"qw":.. } }
   ```
   `digitalPose` is the centre of the printed image, in canonical metres, with the image axes of docs/ar.md
   "Marker pose convention" (+X right, +Y out of the face, +Z toward the bottom edge).
3. Download `GET .../anchors/{id}/target.png` and print the **whole PNG, black border included**, at exactly
   `markerSizeMeters` wide. Measure the printed width with a ruler and record it. If it is more than 2 % off, fix the
   print or re-register the size.
4. Mount it flat, at the registered position and orientation. Measure the mounted centre against the venue's
   control points or the reconstruction, and record the measurement.
5. Calibrate the anchor: `POST .../anchors/{id}/calibrate`.
6. Also register one `APRILTAG` (or `QR_CODE`) anchor on the same floor, as a control.

Expected on `/ar`, after choosing the venue and floor: each `IMAGE_TARGET` is listed as "detectable (WebXR image
tracking)". The AprilTag is listed as "not detectable here: WebXR has no QR/ArUco/AprilTag detection". With only the
AprilTag registered, the page shows `NO_DETECTABLE_ANCHORS` and cannot start.

## 4. Expected tracking behaviour

Choose a destination POI on this floor and tap **Start AR navigation**.

| Step | Expected |
|---|---|
| Session starts | The overlay shows `SEARCHING`. For the first second or so it says "Waiting for device tracking", not "Tracking lost", while ARCore initialises. Then it says "Point the camera at a registered marker." |
| Any image scored `untrackable` | That marker is ignored. If all are, the session ends with `NO_TRACKABLE_MARKERS` (a platform verdict: record it) |
| Point at a **floor or blank wall** with no marker | Stays `SEARCHING`. Nothing is drawn. (This is the old defect: a surface must never localize.) |
| Point at the **AprilTag** | Stays `SEARCHING` |
| Point at a registered image from 0.5 to 2 m | `RELOCALIZING`, naming that marker's identifier, then `LOCALIZED` "from 1 marker(s) (residual unknown: one marker)". In the chrome://inspect Network tab, the `relocalize` request body's `anchorId` is **that marker's** backend anchor id. |
| Print a copy at the wrong size (e.g. 70 %) and show it | No relocalization: the measured width does not match the registered size |

## 5. Expected route behaviour

| Check | Expected |
|---|---|
| After the first `LOCALIZED` | One `POST /navigation/routes` whose `start` is roughly the phone's position in the venue (compare with a tape measure from the marker). A blue tube and spheres appear along the route, with the next waypoint enlarged in amber. |
| Walk around | The route stays fixed to the floor of the room, not to the screen. It does not swim or lag. |
| Placement accuracy | Measure where a drawn waypoint meets the floor against that waypoint's canonical position. Record the error at 2 m, 5 m and 10 m from the marker. |
| Orientation | The route leaves the marker in the right direction. If it is rotated about the marker, `digitalPose` has the wrong rotation about the vertical (see "Marker pose convention"). |
| Progress | "m to go" falls as you walk along the route. "m off route" grows when you step sideways. Passed waypoints turn grey. |
| Multi-floor route | Only this floor's leg is drawn, and the overlay names the floor transition |

## 6. Failure and relocalization test

1. **Cover the camera** with a hand for about 3 s while `LOCALIZED`. Expected: `TRACKING_LOST` with a reason. The
   route disappears. The overlay shows "Route position frozen at N m to go", and N does not change while you walk
   with the camera covered.
2. **Uncover** and point at the floor, not a marker. Expected: it **stays** `TRACKING_LOST`, even though ARCore is
   tracking again. The route stays hidden. The overlay asks you to point at a registered marker.
3. **Point at a marker** (use the *other* one). Expected: `RELOCALIZING`, then `LOCALIZED`, and the route reappears in
   the right place. The `relocalize` request names the second anchor's id.
4. **Background the app** (home button) and return. Expected: `TRACKING_LOST` ("the AR session is hidden"), then the
   same recovery as in step 3.
5. **Server refusal.** An anchor that is not `CALIBRATED` when the page loads is never registered for tracking. So
   start the session first, then invalidate one anchor from the desktop: `PUT` it unchanged, which resets it to
   `UNCALIBRATED`. Cover the camera to lose tracking, then point at that marker. Expected: "Last relocalization failed:
   ANCHOR_NOT_CALIBRATED". It does not localize, and it retries no more often than every 1.5 s. Recalibrate the
   anchor afterwards.
6. **Network loss** while pointing at a marker (airplane mode). Expected: a relocalization failure is shown, and no
   localization is made up.
7. **End AR.** Expected: the session closes and the page returns to the start form.

## 7. Negative capability checks (same phone, plus a desktop)

| Setup | Expected page state |
|---|---|
| Desktop Chrome | `WEBXR_UNAVAILABLE` or `IMMERSIVE_AR_UNSUPPORTED`. No camera, no simulated view. |
| Phone, `webxr-incubations` flag **Disabled** | `IMAGE_TRACKING_UNSUPPORTED` |
| Phone over plain `http://` on the LAN IP (not localhost) | `WEBXR_UNAVAILABLE` (not a secure context) |
| Phone without Google Play Services for AR | `IMMERSIVE_AR_UNSUPPORTED` |

## Validation record

Fill in one row per device run. Attach screen recordings (Chrome → ⋮ → Share → record, or `adb shell screenrecord`)
and the chrome://inspect network log for steps 4 and 6.

| Date | Tester | Device / Android | Chrome version | ARCore version | Sections passed | Placement error at 2 / 5 / 10 m | Notes / defects |
|---|---|---|---|---|---|---|---|
| — | — | — | — | — | **not run** | — | No device available when the client was written |
