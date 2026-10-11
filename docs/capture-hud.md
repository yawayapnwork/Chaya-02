# Capture planning and the live capture HUD

What the operator does while capturing: a short reconnaissance walk, room bounds estimated from it (or a measured
outline), a persisted scan plan, and guided capture along it with live position, coverage, frame quality and reshoot
recommendations. Every signal is measured or recomputed from real posted data. Nothing is a random or placeholder
number, and every part that cannot be computed says so instead of guessing.

Code: `apps/web/components/CaptureHud.tsx` and `apps/web/lib/{capture-tracking,capture-xr-session,capture-xr-gl,capture-guidance,
capture-minimap,capture-quality,capture-position,trajectory-import,evidence-queue,hud-api,hud-types,sse}.ts` (browser);
`services/api/.../hud` (endpoints under `/api/v1/venues/{v}/captures/{c}/hud`) and `services/api/.../planning`
(`Reconnaissance`, `RoomBoundsEstimator`, `EstimatedScene`, `CapturePathPlanner`). The planning algorithm is the same
deterministic engine as route-plan (docs/route-planning.md); there is no second planner.

## 1. Flow

1. **Choose how positions are known** (section 2): AR tracking, manual marks on a measured plan, or an imported trajectory.
2. **Reconnaissance walk.** Walk the room for about ten seconds, pointing the camera at each wall and corner. The server
   checks the walk as it arrives (`GET .../hud/reconnaissance`, section 3).
3. **Room bounds.** Estimate them from the walk's evidence (`POST .../hud/scene/estimate`, section 4), or enter a
   measured outline (`PUT .../hud/scene`).
4. **Scan plan.** `POST .../hud/plan` runs the planner on the bounds and the walk and stores the plan (section 5).
5. **Guided capture.** The HUD shows the next waypoint, the walking leg to it, progress, live coverage and what to fix
   (section 6). Estimating and planning can be repeated as evidence grows; each plan supersedes the previous one.

## 2. Positions: what is supported, and the capability boundary

| Source (`source`) | What it is | Metric? | Live? |
|---|---|---|---|
| `webxr` | ARCore world tracking through a WebXR `immersive-ar` session (Chrome on Android). Viewer pose in the `local-floor` (else `local`) reference space; surface points from hit tests against detected planes. | yes (platform) | yes |
| `arkit` | Accepted by the API for a native ARKit client. **No capture client posts it yet** (see below). | yes (platform) | yes |
| `manual` | The operator taps their spot on the to-scale plan. | only on a measured outline | yes, as marks |
| `operator-trajectory` | A trajectory exported by another tracker and imported (`t, x, y[, yawDegrees]`, units stated as metres). | as the operator states | never |

- **Video is not a position source.** A video file carries no camera poses, and poses recovered from video alone
  (structure from motion) have no metric scale and exist only after processing. `POST .../pose` refuses `source` values
  naming video (`VIDEO_IS_NOT_A_POSE_SOURCE`), and the reconnaissance check reports `VIDEO_ONLY` for a capture whose only
  evidence is uploaded video.
- **Frames.** A WebXR session's world frame exists only for that session, so every tracked position carries `frameId`
  (one per session). Positions in a frame other than the scene's are not placed on the plan (`FRAME_MISMATCH`). After a
  session restart the operator re-aligns: stand on a known spot on the map (by default the last tracked position), face
  the arrow, confirm. One correspondence fixes a 2D rigid transform (`alignmentFromCorrespondence`); later positions are
  posted in the plan frame with `frameAlignment: OPERATOR_ALIGNED`. Its error is the operator's placement error, which is
  not measured. A measured outline used with AR tracking is aligned the same way.
- **Kinds shown.** The position is shown as TRACKED (filled marker with heading), MANUAL (hollow marker), or, when tracking
  is unavailable, as the last known position (grey, "?"). The recorded path is drawn solid where tracked or imported,
  dashed where it is a straight-line assumption between manual marks (INTERPOLATED), and is broken (never joined) across
  a tracking loss or a change of source. An imported trajectory is never shown as a live position.
- **Manual headings.** A mark only carries a facing direction after the operator calibrates it ("Set direction" while
  facing the plan's +x). The heading of the rear camera comes from the full DeviceOrientation Euler angles
  (`cameraHeadingFromEuler`), so it does not depend on screen rotation. Without the calibration, marks have no heading.

**Capability boundary.** On-device position tracking in this repository is exactly WebXR `immersive-ar` on ARCore
Android devices. iOS Safari has no WebXR; the native iOS app (`apps/ios-ar`) has ARKit world tracking but implements AR
navigation only, not capture, and is not device-validated. Desktop browsers and iOS Safari get an explicit unsupported
state and the manual or import modes. Remaining integration work for iOS: an ARKit capture mode in `apps/ios-ar` that posts
`arkit` positions and `ARRaycastQuery` wall hits to the same endpoints (the API already accepts them).

## 3. Reconnaissance input (`Reconnaissance`)

The walk is READY when (defaults in `Reconnaissance.Requirements`): tracked or imported positions span at least 8 s and
10 samples, or there are at least 3 manual marks on a measured outline; the walked path is at least 3 m; and positions
were unavailable for at most 30% of the walk. Otherwise it is INCOMPLETE (or NOT_STARTED) with blocking issues that say
what is missing (`WALK_TOO_SHORT`, `PATH_TOO_SHORT`, `TRACKING_UNRELIABLE`, `MANUAL_NEEDS_MEASURED_OUTLINE`, ...).

Positions are split into **segments** at every tracking loss (a `LOST`, `LIMITED`, `INTERRUPTED` or `SESSION_ENDED`
event, or a gap of more than 1.5 s between tracked samples), at every change of source, and between manual marks more than
60 s apart. The planner never interpolates or credits coverage across a segment break (`TrajectorySample.segment`).
Bounds are estimable only from tracked or imported positions with at least 12 wall observations.

## 4. Room bounds

- **Estimated** (`RoomBoundsEstimator`, docs/route-planning.md section 12): from the tracked positions and wall/floor hits of
  one frame. The result has a status: ESTIMATED (the boundary is supported by observed surfaces), LOW_CONFIDENCE (a
  lower bound: part of the boundary was not observed, or there is an opening the room may continue through), or
  NOT_ESTIMABLE (with the reason; nothing is stored). It reports the confidence (a heuristic, not a probability) and its
  parts, the **frontier** (boundary stretches where observation stopped, drawn orange), unobserved interior, observed
  obstacles, the assumptions made, and the issues found. Manual marks never estimate walls.
- **Measured**: the operator's outline, used as given and checked against the evidence in its frame (positions outside
  it, observed walls outside it, edges no observation supports).

## 5. Scan plans, persisted

`POST .../hud/plan` requires bounds and a READY walk. It runs `CapturePathPlanner.plan` with the bounds as the scene, the
segmented walk as the trajectory, and the bounds' frontier and unobserved interior as **priority regions** (cells get
higher importance, and a waypoint whose gain is mostly there is typed `UNOBSERVED`). The plan is stored in `capture_plan`
with: the scene and its source, the bounds report, the reconnaissance assessment, the configuration, the evidence it was
computed from (counts per source, id high-water marks, time window, frame), the planner result, and the observed coverage
at planning time. Plans are versioned; a new one supersedes the old (one current plan per capture, enforced by a partial
unique index). Each creation is audited (`capture.scan_plan_created`).

Two kinds of coverage are kept apart everywhere:

| Kind | Meaning |
|---|---|
| PREDICTED | What a plan expects the walk plus its route to cover, by the planner's model, before the route is walked. |
| OBSERVED | The same model applied to the positions actually recorded. It says where the camera was and which way it faced, not whether reconstruction will succeed. |

`GET .../hud/evidence` (readable in any capture state) is the audit view: evidence counts by source, kind and state,
frames, the bounds, the reconnaissance assessment, observed coverage now, every plan with its predicted and observed-at-plan
coverage, and progress along the current plan.

## 6. Live coverage, progress and guidance

`GET .../hud/status` (and the same over SSE) recomputes on every read:

- **Tracking state**: TRACKING, STALE (no position for `chaya.hud.position-stale-after-ms`), LIMITED, LOST, INTERRUPTED,
  SESSION_ENDED, FRAME_MISMATCH, PERMISSION_DENIED, UNSUPPORTED, MANUAL, IMPORTED_ONLY or NOT_STARTED.
- **Coverage**: `CapturePathPlanner.assess` (the observed coverage only, no route search) and the remaining gaps
  (NOT_OBSERVED or INSUFFICIENTLY_OBSERVED). `coverageBasis` says what it is computed from (LIVE_TRACKED,
  TRACKED_NOT_LIVE, MANUAL_MARKS, IMPORTED_TRAJECTORY, MIXED) and `coverageLive` is true only for fresh tracked positions.
  Replayed or imported positions are never presented as live.
- **Plan progress** (`CapturePathPlanner.progress`): from positions recorded after the plan, a waypoint is VISITED when a
  position came within 0.5 m and, if positions have headings, faced within half the field of view of its heading;
  SKIPPED when a later one was visited first; UNREACHABLE when the current bounds no longer let it be stood on or walked
  to. The next waypoint is the first not visited, with the walking leg to it on the standable grid. Completion is
  NOT_STARTED, IN_PROGRESS, ROUTE_COMPLETE, or COVERAGE_TARGET_REACHED (observed coverage at the planner's stop target).
  Without a plan, a live suggestion is shown, labelled as such.
- **Reshoot recommendations**: a waypoint visited while most frames within 1.5 s had a capture defect (BLUR, exposure,
  LOW_FEATURE_COUNT, FAST_MOTION); a warning shared by at least 3 of the last 5 frames; the largest remaining gaps.
- **Guidance** (`capture-guidance.ts`, browser): prioritised instructions from the status and the device's own state:
  blocking problems first (camera denied, unsupported device, tracking lost, re-alignment needed), then quality and motion
  warnings, then the next action (walk more, estimate bounds, plan, walk N m ahead-left to waypoint k and face east).
  Directions are given only from a live position.

Frame quality is computed in the browser (section 8). In AR mode it needs the WebXR `camera-access` feature (the AR
session owns the camera); when the browser does not grant it, the HUD says frame quality is unavailable during AR. Motion
measured from tracked poses (over 1 m/s, or turning over 60 deg/s) is shown immediately and attached to frame samples as
FAST_MOTION.

## 7. Reliability

| Situation | Behaviour |
|---|---|
| Camera permission denied | `PERMISSION_DENIED` event; blocking instruction to allow the camera; manual mode still works without quality checks. |
| No WebXR / immersive AR unsupported | AR mode disabled with the browser's reason; manual and import modes offered. |
| Session interrupted (app switch, notification) | `INTERRUPTED`, then `RESUMED`; no positions while hidden. |
| Session ended or restarted | `SESSION_ENDED`; a new session has a new frame: FRAME_MISMATCH until re-aligned. |
| Tracking lost / limited (low texture, low light, fast motion) | `LOST` (no pose for 400 ms) or `LIMITED` (emulated position); nothing is posted or credited; instruction to hold still, aim at a textured lit area and return to the last good spot. |
| Low light, low texture, blur | sustained frame warnings become instructions and reshoot recommendations. |
| Orientation change | `ORIENTATION_CHANGED` logged; AR poses are world-anchored and manual headings use the full device orientation, so neither depends on screen rotation; the map refits. |
| Incomplete trajectory | the reconnaissance check says what is missing; planning is refused until READY. |
| Network loss | evidence is queued and resent in order; over the cap the oldest is dropped and the count is shown. |
| Page reload | everything is durable server-side; a new AR session must be re-aligned. |

## 8. Frame-quality signals (client-side)

Computed in the browser from a downsampled copy of the camera frame (`capture-quality.ts`); only the numbers are sent.

| Signal | Method |
|---|---|
| Blur | Variance of the discrete Laplacian over the grayscale frame. Higher = sharper; a threshold flags `BLUR`. |
| Motion / duplicate frame | Mean absolute grayscale difference against the previous analysed frame; near zero flags `DUPLICATE_FRAME`. |
| Exposure | Mean brightness and the near-black / near-white fractions; flags `UNDEREXPOSED` / `OVEREXPOSED`. |
| Feature richness | FAST-9 corner count (ORB's keypoint stage); flags `LOW_FEATURE_COUNT` (a blank wall). |
| Frame spacing | Distance from the previous analysed frame's position; flags `SPACING_TOO_CLOSE` / `SPACING_TOO_FAR`. |
| Device motion | From tracked poses (AR mode): `FAST_MOTION` when walking or turning too fast. |

## 9. API

| Call | Purpose |
|---|---|
| `PUT .../hud/scene` | Sets a measured outline (optionally in an existing frame). Validated against the planner. |
| `POST .../hud/scene/estimate` | Estimates bounds from the evidence; stores them when estimable; returns the report either way. |
| `POST .../hud/pose` | Appends positions (`source`, `frameId`, `frameAlignment`). |
| `POST .../hud/surface` | Appends surface observations (wall/floor hits with the camera position and normal). |
| `POST .../hud/tracking-events` | Appends tracking state changes and session problems. |
| `POST .../hud/quality` | Appends frame-quality samples (never image bytes). |
| `GET .../hud/reconnaissance` | Whether the walk can be planned from, and what is missing. |
| `POST .../hud/plan`, `GET .../hud/plan` | Make a persisted plan; the current plan with progress. |
| `GET .../hud/status`, `GET .../hud/stream` | The live status, by REST and over SSE (fetch-based reader: the API needs a bearer token, which `EventSource` cannot send). |
| `GET .../hud/evidence` | Audit view (section 5). |

Writes require the capture to be `CREATED` or `UPLOADING` (`CAPTURE_NOT_CAPTURING` otherwise); reads work in any state.
All are venue-scoped like every capture endpoint.

## 10. What is and is not verified

- **Algorithmically validated** (deterministic tests, synthetic ground truth): reconnaissance segmentation and verdicts
  (`ReconnaissanceTest`); bounds estimation for rectangular, L-shaped, slanted-wall rooms, pillars, partitions, missing
  observations, sparse evidence, tracking loss and measured-outline checks (`RoomBoundsEstimatorTest`); planning on
  estimated bounds measured against the true room, frontier prioritisation, iterative re-estimation past an occluder,
  unreachable and impossible waypoints, plan progress, coverage gaps closing, no credit across tracking loss
  (`CapturePlanningPipelineTest`); the REST surface, persistence and audit separation (`CapturePlanApiTest`,
  `CaptureHudApiTest`). Wall observations in these tests come from `SyntheticRangeSensor`, a noise-free ray caster: the
  results bound the algorithm with perfect hits and are not field accuracy. Benchmark: docs/route-planning.md section 12.
- **Browser flow** (node, no device): pose and hit conversion, tracking-state transitions, throttling, motion,
  re-alignment, heading calibration (`capture-tracking.test.ts`); the AR session against a scripted fake WebXR runtime,
  including permission denial, unsupported devices, lost and limited tracking, interruption, session end and re-alignment
  (`capture-xr-session.test.ts`); guidance (`capture-guidance.test.ts`); trajectory import; the upload queue; the map's
  extent. The component itself is checked by lint, typecheck and a production build.
- **Not yet run with real sensor data**: no WebXR session has been run on an ARCore device with this code. Unknown until
  then: real hit-test density and noise, ARCore drift over a walk, whether Chrome grants `camera-access` and how the
  camera image reads back (`capture-xr-gl.ts` is NOT DEVICE-VALIDATED), DOM-overlay layout, and how the thresholds
  (estimator confidence, LOST after 400 ms, visit radius 0.5 m) behave in real rooms.
- **Needs field testing**: estimated bounds against tape-measured rooms; plans followed by operators; whether PREDICTED
  coverage tracks reconstruction quality (`benchmarks/b1_capture_path/reconstruction_coverage.py` measures that once
  paired real captures exist).
