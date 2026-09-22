# Live capture-quality and coverage HUD

What the operator sees while capturing: current position, planned and completed path, coverage estimate,
uncovered zones, frame quality, and reshoot recommendations. Every signal is measured or recomputed from real
posted data; nothing here is a random or a placeholder number, and every part that cannot be computed says so
instead of guessing.

Code: `apps/web/lib/capture-quality.ts`, `apps/web/lib/capture-position.ts`, `apps/web/lib/hud-api.ts`,
`apps/web/lib/sse.ts`, `apps/web/components/CaptureHud.tsx` (frontend); `services/api/.../hud` and the endpoints
under `/api/v1/venues/{v}/captures/{c}/hud` (backend).

## 1. Frame-quality signals (client-side)

Computed in the browser from a downsampled copy of the live camera frame (`apps/web/lib/capture-quality.ts`),
so nothing needs a backend round trip per camera frame; only the resulting numbers are ever sent.

| Signal | Method |
|---|---|
| Blur | Variance of the discrete Laplacian (`[[0,1,0],[1,-4,1],[0,1,0]]`) over the grayscale frame. Higher = sharper; a threshold flags `BLUR`. |
| Motion / duplicate frame | Mean absolute grayscale difference against the previous analysed frame, normalised 0..1. Near zero flags `DUPLICATE_FRAME`. |
| Exposure | Mean brightness plus the fraction of near-black (<=16) and near-white (>=240) pixels; flags `UNDEREXPOSED` / `OVEREXPOSED`. |
| Feature richness | FAST-9 corner count: the keypoint-detection stage of ORB (a pixel is a corner if >= 9 of the 16 points on a radius-3 circle around it are all consistently brighter, or all consistently darker, than the centre). Full ORB also ranks and describes (BRIEF) keypoints for matching between frames; that is unneeded for a richness count and is not implemented. Flags `LOW_FEATURE_COUNT` on a low count (a blank wall). |
| Frame spacing | Straight-line distance from the previous analysed frame's position (when a position is known); flags `SPACING_TOO_CLOSE` / `SPACING_TOO_FAR`. |

Grayscale conversion is BT.601 luma. All thresholds are named constants (`DEFAULT_QUALITY_THRESHOLDS`), tuned
for a ~320x240 analysis frame, not hard-coded magic numbers scattered through the code. Tests
(`capture-quality.test.ts`) use constructed pixel-array fixtures (a checkerboard, isolated bright squares on a
dark background, uniform fields, a box-blurred copy of a sharp image) chosen so each metric's real, known answer
can be asserted — for example FAST correctly finds *no* corners at a checkerboard's 4-way junctions (a known
"saddle point" blind spot of the algorithm), so that fixture is used for the blur test and a separate
isolated-square fixture is used for the feature-count test.

## 2. Position

No on-device SLAM or AR tracking exists in this codebase yet. Rather than fabricate a position, this build asks
the operator to mark their own position by tapping their spot on a to-scale floor-plan minimap (`source:
"manual"` on the posted pose sample); a device compass heading (`DeviceOrientationEvent`) is attached when the
browser and device provide one, and left out — never invented — when they do not. `CaptureHud.tsx`'s only
dependency on *how* a position arrives is the `{x, y, yawDegrees?}` sample shape, so a future on-device
SLAM-lite estimator can post the same shape with a different `source` value without changing the coverage or
rendering code at all.

The room outline itself is a single measured rectangle (width x depth, entered by the operator) rather than a
freehand drawing, because a freehand canvas sketch has no metric scale to compute coverage against.

## 3. Coverage: the same planner, replayed live

Coverage is **not** a second implementation: `CaptureHudService` reruns the existing, tested, deterministic
`CapturePathPlanner` (see docs/route-planning.md) with the room outline as `scene` and the pose samples reported
so far as `trajectory`, exactly as `route-plan` does with a 10 s reconnaissance lap. `baseline` in the result is
what the operator has actually covered; `waypoints` is the route the planner would still add to reach the
configured coverage target, shown as the HUD's "planned path"; `uncoveredRegions` (computed after that suggested
route) is shown as "uncovered zones" — the areas that need attention even once the obvious gaps are closed.

Recomputing the full planner on every posted pose is deliberately not free (it is `O(waypoints x candidates x
headings x cells seen)`, see docs/route-planning.md section 10), so the SSE broadcaster only recomputes and
pushes when new data has actually arrived (a per-capture write counter), on a fixed tick (`chaya.hud.broadcast-
interval-ms`, default 750 ms), not once per pose.

## 4. API

| Call | Purpose |
|---|---|
| `PUT .../hud/scene` | Sets (or replaces) the room outline. Validated immediately against the planner (with an empty trajectory, since none exists yet) so bad geometry is rejected at draw time. |
| `POST .../hud/pose` | Appends pose samples (durable; Postgres). |
| `POST .../hud/quality` | Appends frame-quality samples (durable; Postgres; never image bytes). |
| `GET .../hud/status` | The full computed status: durable, poll-friendly REST. Always available, including after the capture is no longer accepting writes. |
| `GET .../hud/stream` | The same status, pushed over Server-Sent Events as it changes. |

All four require the capture to be visible to the caller (venue-scoped, same as every other capture endpoint);
the three writes additionally require the capture to still be `CREATED` or `UPLOADING` (`CAPTURE_NOT_CAPTURING`
otherwise). `status`/`stream` remain readable in any state.

**Why SSE over a fetch reader, not `EventSource`:** the API is bearer-token authenticated throughout, and the
browser's `EventSource` cannot set an `Authorization` header. `hud-api.ts` reads the same `text/event-stream`
wire format by hand instead (`sse.ts`, unit tested), which can carry the header.

## 5. Degradation

- **No position samples reported, or the most recent one is older than `chaya.hud.position-stale-after-ms`
  (default 8 s):** `trackingAvailable: false` with a stated reason; the last known position, if any, is still
  returned (clearly stale) but the UI never advances it — no dead reckoning, no invented movement.
- **No room outline set yet, or the trajectory has no usable samples, or the trajectory lies entirely outside
  the outline:** `coverageAvailable: false` with the planner's own message as the reason (the same codes
  `route-plan` returns, e.g. `NO_USABLE_TRAJECTORY`, `TRAJECTORY_OUTSIDE_SCENE`). No coverage percentage or
  heatmap is ever synthesised in this state.
- **Camera unavailable (permission denied, no camera, etc.):** the quality panel says so; position and coverage
  keep working from posted pose samples independent of the camera.

## 6. What is and is not verified

- `capture-quality.test.ts`, `capture-position.test.ts`, `sse.test.ts`: real pixel-array fixtures and pure
  geometry, run with `node --test` (no DOM, no network).
- `CaptureHudApiTest`: the REST surface end to end (MockMvc + Testcontainers Postgres, the same harness as
  `RoutePlanApiTest`) — scene validation, a real posted path producing real coverage and a planned path,
  repeated quality warnings producing a reshoot recommendation, tenancy/authorization, and the honest-degradation
  responses before anything has been reported.
- Coverage math itself (monotonicity, the greedy selection, 2-opt, etc.) is `CapturePathPlannerTest`'s job, not
  re-tested here: this feature reuses that engine rather than reimplementing it.
- Not verified: the SSE stream endpoint end to end (it is a thin wrapper over the same status computation used
  and tested via REST) and the browser UI in an actual browser/device (getUserMedia, DeviceOrientationEvent,
  canvas rendering) — consistent with the rest of this repository's web UI, which is checked by lint, typecheck,
  unit tests and a production build, not a real browser.
