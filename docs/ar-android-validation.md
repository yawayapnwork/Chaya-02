# Android WebXR AR: manual device validation

This is the procedure that validates the Android AR client (`/ar`, docs/ar.md "Android: WebXR") on a real device. None
of it can run in CI or on a desktop: desktop Chrome has no `immersive-ar`, and the WebXR emulator extensions do not
implement image tracking or ARCore anchors.

**Status: BLOCKED (NOT RUN).** No physical Android ARCore device is attached to this environment. The unit and
regression tests cover the transforms, observation handling, capability states, invalid transforms, stale routes,
relocalization timeout, expired sessions and state machine. They do not show that a real session delivers frames,
detects the printed images, creates anchors or draws the route. Do not describe Android AR as working until this
procedure has passed on at least one device and the record at the bottom has been filled in.

## Pre-defined Acceptance Tolerances

Before interpreting any physical measurement, the following numerical pass/fail tolerances apply:

| Metric | Pass Tolerance | Enforcement / Measurement |
|---|---|---|
| Printed marker physical width match | `±20%` of `markerSizeMeters` (`0.80 <= measuredWidth / registeredWidth <= 1.20`) | `WIDTH_TOLERANCE = 0.20` (`lib/ar-marker-tracking.ts`) |
| Gravity-alignment tilt of solved `deviceToVenue` | `<= 15.0°` between device `+Y` and canonical `+Z` | `MAX_GRAVITY_TILT_DEGREES = 15` (`lib/ar-relocalization.ts`, server `ArDeviceFrame`) |
| Multi-anchor solve residual (2+ anchors in view) | Translation `<= 0.30 m` and rotation `<= 8.0°` | `solve(..., 0.3, 8)` (`lib/ar-anchor-math.ts`, server `AnchorService`) |
| Initial waypoint placement error at measured checkpoints | `<= 0.20 m` at `2 m`, `<= 0.35 m` at `5 m`, `<= 0.50 m` at `10 m` | Physical tape-measure check from marker to floor tape marks |
| VIO drift when re-observing a marker while localized | Translation `<= 0.30 m` and rotation `<= 8.0°` (above this, `DRIFT_EXCEEDED` drops transform and relocalizes) | `DRIFT_LIMIT_METERS = 0.30`, `DRIFT_LIMIT_DEGREES = 8.0` (`lib/ar-navigation.ts`) |
| Limited-tracking hold and recovery jump | Hold `<= 3.0 s`; world-anchor jump on recovery `<= 0.30 m` | `MAX_LIMITED_MS = 3000`, `RECOVERY_MAX_JUMP_METERS = 0.30` (`lib/ar-relocalization.ts`) |
| Relocalization request timeout | `<= 8.0 s` | `RELOCALIZATION_TIMEOUT_MS = 8000` (`lib/ar-relocalization.ts`) |
| Floor-transition handoff radius & sightings | Within `<= 2.0 m` of `TRANSITION` waypoint; `>= 2` consecutive sightings on new floor | `HANDOFF_RADIUS_METERS = 2.0`, `MIN_FLOOR_SIGHTINGS = 2` |

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

## 6b. Versions, drift and floors

Run these after section 6, with two floors that each publish a scan version, each with at least one calibrated
`IMAGE_TARGET`, a registered floor connection (an elevator), and a destination on the second floor.

| Check | How | Expected |
|---|---|---|
| Version pin | Localize, then in the chrome://inspect Network tab look at the next `relocalize` request (after ≥ 5 s, pointing at a marker again) | Its body carries `scanVersionId`: the version the first answer named. The overlay names that version. |
| Republished floor | While localized, publish a new version of the floor from the desktop (a new calibrated run, or `finalize-current`). Point at a marker after 5 s | The re-solve is refused (`VERSION_MISMATCH` in "Last relocalization failed"); the route stays drawn on the old version until you restart AR. Nothing from the new version is mixed in. |
| Unversioned anchor | An anchor from before poses were versioned (or one whose pose was never re-entered) | Listed as "not usable: its pose was never entered against a scan version"; never registered for tracking |
| Refresh | Stand still, localized, pointing at a marker for > 5 s | One background `relocalize` request; the state stays `LOCALIZED`; the route does not jump (it blends over about half a second) |
| Drift | Localize from marker A, then move marker A by about 0.5 m (or rotate it about 15°) and point at it | `TRACKING_LOST` with "… from where the transform puts it (drift)", then `RELOCALIZING` and `LOCALIZED` from the moved marker. Put it back and recalibrate it afterwards. |
| Floor handoff | Walk the route to the elevator | Within about 2 m of the transition: `FLOOR_TRANSITION`, the route disappears, the overlay says which connector and floor. Losing tracking in the elevator does not change that. |
| Next floor | On the next floor, point at one of its markers (pointing at a first-floor marker, if you carry a print, does nothing) | `RELOCALIZING` (the request goes to the second floor's `/floors/{f}/anchors/relocalize`), then `LOCALIZED`, and the second floor's leg is drawn, ending at the destination |
| Version / frame check | (Desktop) recalibrate the second floor's reconstruction between starting AR and arriving there | The second floor's leg is not drawn: "Route not drawn: FRAME_MISMATCH" |
| Hit test | Point at a floor or a blank wall at any point | Nothing is ever placed on it; no state changes |

## 6c. Multi-marker consistency, limited tracking, failures, step-free (added 2026-10-11)

Layout for this section: **three** calibrated `IMAGE_TARGET`s on the same floor, 4-8 m apart, on at least two walls,
all at about 1.2-1.5 m, positioned so two or three can be in view at once from some spot. Record the layout (anchor ids,
measured positions, which wall, height) in the record below.

| Check | How | Expected |
|---|---|---|
| Two-marker residual | Localize with two markers in view at once | The overlay shows `residual N m`: the larger distance between a marker's observation mapped through the transform and its registered pose. Record N. A correct layout gives a few centimetres; record what you get. |
| Moved marker, three in view | Move one print about 1 m along its wall; stand where all three are in view | `LOCALIZED` from the other two; the message names the moved marker as "set aside: inconsistent". The `relocalize` response has `rejectedAnchorIds` with its id and `anchorResiduals` with about 1 m for it. Put it back afterwards. |
| Moved marker, two in view | Same moved print, with only it and one other in view | No localization: `ANCHORS_INCONSISTENT` ("the markers in view disagree"), `409 RELOCALIZATION_INCONSISTENT` in the network log. It retries no more than every 1.5 s. Pointing at one marker alone then localizes. |
| Misidentified print | Swap two prints (marker A's print where B's was) and point at both | They are set aside or refused as above; never averaged into a route that is visibly off. |
| Limited, short | While `LOCALIZED`, sweep the phone fast for about 1 s, or aim at a blank wall | `LIMITED`: "Stop and hold still… route frozen". The route stays drawn where it was (it does not follow you); "m to go" does not change. Back to `LOCALIZED` without a marker when tracking returns within 3 s. |
| Limited, long | Keep it on a featureless surface for more than 3 s | `TRACKING_LOST` "tracking was limited for more than 3 s"; route hidden; needs a marker. |
| Limited, map shifted | Hard to provoke. If a recovery ever snaps the route visibly, check the overlay | `TRACKING_LOST` "… moved the route's world anchor N m meanwhile (it re-anchored its map)" instead of resuming. Record N if seen. |
| Network loss, route drawn | Localize and let the route draw, then airplane mode; keep walking | `NETWORK_UNAVAILABLE` shown; the route stays drawn and progress keeps advancing (tracking is on-device). |
| Network loss, before the route | Airplane mode right after localizing (before the route arrives), then back online | `NETWORK_UNAVAILABLE`; the route is fetched by itself once back online, without restarting AR. |
| Expired session | Use a viewer link whose token expires during the test (or revoke it from the desktop), then lose tracking and point at a marker | `SESSION_EXPIRED` with "sign in again / reopen the viewer link"; no more `relocalize` or route requests are sent (network log). |
| Anchor unavailable | Delete one anchor from the desktop mid-session, lose tracking, point at its print | `ANCHOR_UNAVAILABLE`; that marker is not sent again this session (network log); the others still localize. |
| Step-free route | Tick "Step-free route" before starting, to a destination on another floor reachable by stairs and by an elevator | The route uses the elevator; the overlay lists the step-free constraints applied. Without a step-free connection: a routing error, never a route through stairs. |
| Same-floor version pin | Destination on the session's floor | The `routes` request body carries `scanVersionId` (the localization's); for another floor's destination it does not. |
|Drift over distance | Localize at marker A; walk 10, 20 and 40 m along the route; at each point aim at a registered marker you have not used and read "… m from where the transform puts it" in the drift message, or the residual of the refresh | Record translation and rotation at each distance. This is the only measurement of VIO drift; unit tests cannot provide it. |
| Destination deleted | Delete the selected destination POI from the desktop mid-session and trigger a route plan/replan | `REFUSED` (`POI_NOT_FOUND`): "The selected destination no longer exists. Choose another destination." The stale route is removed from the scene. |
| App backgrounding | Switch away from Chrome or lock the phone while localized, then return | Session visibility changes to `hidden` / `visible-blurred`; state enters `TRACKING_LOST` and hides the route until a registered marker is observed again. |

## 6d. Camera obstacle sensing (added 2026-10-19)

Needs an ARCore device with the Depth API (Google's ARCore supported-devices list, "Supports Depth API"). Check
`chrome://inspect` → console: `session.enabledFeatures` must include `depth-sensing`. Without it, the overlay says
"Obstacle detection is not available on this device", and that is the expected result on such a phone. Rules:
docs/navigation.md, "Camera obstacle sensing".

| Check | How | Expected |
|---|---|---|
| Depth convention | Localize, then hold a flat board square-on across the route at a tape-measured 2.0 m | An obstruction about 2 m ahead. Record the reported uncertainty, and the region's distance from the device computed from the request body. If it is off by more than its uncertainty, the assumed `getDepthInMeters` convention (top-left origin, depth along −Z) is wrong. |
| Static obstacle | A box or cart on the route 3 m ahead | Within about a second: "Something is in the way about 3 m ahead (estimated position, ±…)" and a `routes` request with `blockedRegions` (network log). The drawn route goes around it. |
| Restore | Remove it | After about 5 s: "back on the usual route", and a `routes` request without `blockedRegions`. |
| Beside the route | The same box 1 m beside the route | No request, no warning. |
| A person | Someone stands on the route | On Android they are found by depth, as "Something" (no person detector in the browser). |
| Unreachable | Block the only way to the destination | "No way around the obstruction … route is unchanged"; the route stays drawn; the request is not repeated (network log). |
| Step-free | The same with Step-free ticked, where only a narrow part is blocked | The step-free reroute keeps `accessibility: STEP_FREE`. |
| Tracking loss | Cover the camera while an obstacle is shown | "Obstacle detection paused"; no request while it lasts. |
| Cost | Read "Obstacle sensing every … ms, … ms per run (p90)" over 5 minutes of walking | Record both figures and the device model. A low-end phone should show the back-off ("reduced detail"), not frame drops. |

## 7. Negative capability checks (same phone, plus a desktop)

| Setup | Expected page state |
|---|---|
| Desktop Chrome | `WEBXR_UNAVAILABLE` or `IMMERSIVE_AR_UNSUPPORTED`. No camera, no simulated view. |
| Phone, `webxr-incubations` flag **Disabled** | `IMAGE_TRACKING_UNSUPPORTED` |
| Phone over plain `http://` on the LAN IP (not localhost) | `WEBXR_UNAVAILABLE` (not a secure context) |
| Phone without Google Play Services for AR | `IMMERSIVE_AR_UNSUPPORTED` |
| Samsung Internet / Firefox for Android on the same phone | Not supported; record which state each shows (`WEBXR_UNAVAILABLE`, `IMMERSIVE_AR_UNSUPPORTED` or `IMAGE_TRACKING_UNSUPPORTED`) |

## Validation record

Fill in one row per device run. Record lighting conditions (lux or qualitative: indoor fluorescent / daylight / dim),
anchor placement (ids, positions, wall/floor orientation, height, printed width), and attach screen recordings
(Chrome → ⋮ → Share → record, or `adb shell screenrecord`) and the `chrome://inspect` network log for both passed and
failed tests.

| Date | Tester | Device model / Android | Chrome version | ARCore version | Lighting & Anchor layout (ids, positions, walls, heights, lux) | Test route (floors, length, step-free?) | Sections passed | Placement error at 2 / 5 / 10 m | Drift at 10 / 20 / 40 m | Relocalization results (count, residuals, refusals) | Known limitations / defects |
|---|---|---|---|---|---|---|---|---|---|---|---|
| — | — | — | — | — | — | — | **not run** | — | — | — | No device available when the client was written |
| 2026-10-05 | — | — | — | — | — | — | **not run (pending)** | — | — | — | Version pinning, drift, refresh and floor handoff added (sections 6b). No Android device and no `adb` on the development machine; only the unit tests and the desktop Playwright spec ran. Hardware validation is pending. |
| 2026-10-11 | — | — | — | — | — | — | **not run (pending)** | — | — | — | Multi-marker consistency, LIMITED hold with validated recovery, failure states, step-free and version-pinned route requests added (section 6c). Verified only by unit and integration tests with synthetic layouts and the desktop Playwright spec; no device was available. |
| 2026-10-27 | — | — | — | — | — | — | **BLOCKED (no physical device)** | — | — | — | Pre-defined tolerances, invalid transform rejection, relocalization timeout (8 s), deleted-destination handling, and regression tests added (`414` web tests passing). Physical ARCore device and `adb` unavailable on workstation. |
