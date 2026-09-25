# Coordinate frames

This is the normative description of where Chaya coordinates live and how they get there. The executable forms are:

- worker: `chaya_worker.frames`, `chaya_worker.gravity`, `chaya_worker.recast_boundary`
- API: `dev.chaya.api.frame`, `dev.chaya.api.ar.ArDeviceFrame`
- web: `apps/web/lib/coordinate-frame.ts`, `apps/web/lib/ar-frame-boundary.ts`

It exists because of adversarial review findings R-2 and D-1 ([ADVERSARIAL_REVIEW.md](ADVERSARIAL_REVIEW.md)).
COLMAP/GLOMAP output has an arbitrary similarity gauge: arbitrary scale, rotation and origin. Code that treats it as
metres with a fixed up axis is wrong.

**Status:** the model, the math, the storage and every consumer are implemented and tested with synthetic
mathematical fixtures. **No calibration has been performed on a real capture.** See [Validation status](#validation-status).

## 1. The canonical venue frame

| Property | Definition |
|---|---|
| Units | metres |
| Up axis | **+Z**, opposite to gravity |
| Horizontal axes | X and Y span the horizontal plane |
| Handedness | right-handed (X × Y = Z) |
| Origin | fixed by the frame's **horizontal datum** (below) |
| Transform representation | similarity `X_canonical = s · R · X_reconstruction + t`; `s > 0`; `R` a proper rotation stored as a unit quaternion **(w, x, y, z)**; `t` in metres |
| Precision | all transform arithmetic and stored transforms/coordinates in float64 (`double precision`). Point clouds in PLY/.ksplat artifacts stay float32 in their reconstruction frame (sub-millimetre precision up to several km from the origin) |

### Horizontal datum

| Datum | Origin and heading | Shared between floors? |
|---|---|---|
| `FLOOR_LOCAL` | z = 0 on the floor plane. x = y = 0 at the calibration's floor reference point: the camera centroid projected onto the floor for the reconstructed floor plane, the centroid of the floor points for operator floor points. +X is the horizontal projection of the reconstruction's own +X axis, or of its +Y when +X is within 25° of vertical. | **No.** Each floor has its own origin and heading. |
| `VENUE_CONTROL_POINTS` | Whatever venue datum the surveyed control points are expressed in (metres, +Z up). | **Yes**, for every floor calibrated against the same survey. |
| `NONE` | No rotation or translation is known (a scale-only frame). | n/a |

## 2. The coordinate-frame model

Table `coordinate_frame` (migration V17). One row is one calibration **version** of one reconstruction's frame. Rows
are immutable except for `ACTIVE → SUPERSEDED`; recalibrating adds version n+1. Each row records:

| What | Columns |
|---|---|
| Source reconstruction frame | `source_run_id` (the pipeline run whose POSE_ESTIMATION defines it), `source_artifact_id` (its POSES artifact) |
| Canonical transform | `scale`, `rotation_w/x/y/z`, `translation_x/y/z` |
| Metric status | `metric_status` (`METRIC` / `NOT_CALIBRATED`), `scale_source` (`MEASURED_DISTANCES` / `CONTROL_POINTS` / `NONE`), `scale_relative_spread` |
| Gravity alignment | `gravity_status` (`ALIGNED` / `NOT_ALIGNED`), `gravity_source` (`RECONSTRUCTED_FLOOR_PLANE` / `OPERATOR_FLOOR_POINTS` / `CONTROL_POINTS` / `NONE`), `gravity_up_reconstruction`, `gravity_disagreement_deg` |
| Datum | `horizontal_datum` |
| Fit quality | `control_point_rms_m`, `residuals` (per-reference numbers, JSON) |
| Method, inputs, who, when | `method`, `inputs` (the references verbatim), `calibrated_by`, `calibrated_at`, `version`, `status` |

A frame is **canonical** only when it is `METRIC` **and** `ALIGNED`. Only then does it have a rotation and translation.
Database CHECKs enforce every one of these implications.

A `METRIC`, `NOT_ALIGNED` frame carries its scale alone. This is what an incremental re-scan region needs before
registration.

## 3. Calibration: `POST /api/v1/venues/{venueId}/reconstructions/{runId}/coordinate-frames`

The run must have a successful POSE_ESTIMATION, otherwise `409 RECONSTRUCTION_FRAME_UNAVAILABLE`. Roles: admin,
venue-manager, operator. Every calibration is audited as `coordinate_frame.calibrate`.

### Metric scale: the system never assumes reconstruction units are metres

Scale comes from one of two sources:

1. **Measured distances** (`distanceReferences`). You need at least two, each consisting of two reconstruction points
   and the physically measured distance between them in metres: a door width, a wall length, the distance between two
   fiducials.
   - Scale: `s = Σ lᵢmᵢ / Σ lᵢ²`, where lᵢ is a reconstruction length and mᵢ is its measured length.
   - Each reference's own scale is mᵢ/lᵢ. If any of them deviates from `s` by more than
     `chaya.frames.max-scale-relative-spread` (3 %), the calibration is refused with `422 CALIBRATION_INCONSISTENT`.
   - A single distance is refused (`400`), because its error could not be checked.
2. **Surveyed control points** (`controlPoints`). You need at least three non-collinear points, each with its
   reconstruction coordinates and its venue coordinates (metres, +Z up).
   - The fit is a least-squares similarity: Horn's closed-form rotation, which is always a proper rotation, then
     Umeyama's scale and the translation.
   - It is refused (`422`) when the RMS residual exceeds `chaya.frames.max-control-point-rms-m` (5 cm).
   - Any distance references sent alongside the control points are checked against the fitted scale.

**If there is no calibration, there is no metric frame.** Downstream metric stages fail with `NOT_CALIBRATED` (§6).

### Gravity: a stable up direction from a real source

- **Reconstructed structural planes.** PLANE_FITTING publishes `GRAVITY_ESTIMATE` (`chaya_worker.gravity`).
  - The candidate planes are those within 30° of the mean image-up direction of the registered cameras.
  - The floor is the largest of those planes with at least 90 % of the camera centres above it. Its normal, oriented
    toward the cameras, is "up".
  - The estimate records the camera-up consistency and the agreement angle.
  - When the evidence is missing or inconsistent it says `NOT_ESTIMATED` and why, and the stage still succeeds.
- **Operator floor points** (`gravity.source = OPERATOR_FLOOR_POINTS`).
  - At least three reconstruction points on the floor, plus one point above it to orient the normal.
  - Refused if the points are not coplanar within `max-floor-points-rms-m` (5 cm, measured after scaling).
- **Control points.** Their venue coordinates are +Z up, so the fit fixes gravity. When a `GRAVITY_ESTIMATE` also
  exists, the angle between the two is recorded. The calibration is refused beyond `max-gravity-disagreement-deg` (5°).
- **Device IMU gravity is not available.** This capture pipeline does not record accelerometer data. Adding it would
  give an independent gravity source; it is not implemented.

No part of the system takes any reconstruction axis to be vertical.

## 4. Lifecycle and ownership

- **The control plane owns frames.** Workers never calibrate. They receive the ACTIVE frame on their work order
  (`coordinateFrame`, and `parentCoordinateFrame` for REGION_ALIGNMENT and REGION_SPLICE).
- **The floor's current frame.** `floor.current_coordinate_frame_id` is set when a canonical frame is activated for a
  full reconstruction of that floor, not for an incremental re-scan's region frame. POIs, anchors and navigation
  graphs on the floor must be in this frame.
- **Spatial data records its frame.** `poi_version.coordinate_frame_id`, `ar_anchor.coordinate_frame_id` and
  `navigation_graph.coordinate_frame_id` do this.
  - A manual POI is bound to the floor's current frame when it is written, or left `UNBOUND` when the floor has none.
  - Readers see `frameStatus`: `CURRENT`, `STALE` or `UNBOUND`.
- **Recalibration of the same reconstruction.** The new frame supersedes the old one. POIs and anchors are
  re-projected exactly, with `new = T_new ∘ T_old⁻¹ (old)`: POIs gain a version, anchors are updated in place.
  Navigation graphs are **not** re-projected, because their step-free flags depend on the up direction. They must be
  baked again.
- **A different reconstruction becomes current.** Two SfM reconstructions have no known relation, so nothing is
  guessed:
  - `CALIBRATED` anchors become `STALE`;
  - POIs keep their old frame and read as `STALE`;
  - graphs from the old frame are refused.
  - All of them must be re-placed or re-baked.
- **Which run's frame applies to a stage.** A full run uses its own frame. An incremental re-scan uses its own
  capture's frame up to and including REGION_ALIGNMENT. From REGION_SPLICE on it uses the parent reconstruction's
  frame, because the splice writes the region into the parent's geometry. `pipeline_run.reconstruction_frame_run_id`
  records the root (set once; immutable after).

## 5. Boundaries

### Recast (`chaya_worker.recast_boundary`)

Recast is +Y up. Canonical is +Z up. The conversion is a proper rotation of −90° about X:

```
canonical (x, y, z) -> Recast (x, z, -y)          Recast (x, y, z) -> canonical (x, -z, y)
```

Two functions call it, and nothing else in the codebase converts axes for Recast:
`chaya_worker.navmesh.write_walkable_obj` (input) and `parse_recast_polygons` (output). NAVIGATION_BAKING runs
entirely in canonical metres, so Recast's `cellSize`, `agentHeight`, `agentMaxClimb` and `agentMaxSlope` are
physical quantities.

### AR devices (`dev.chaya.api.ar.ArDeviceFrame`, `apps/web/lib/ar-frame-boundary.ts`)

- **Convention.** ARKit world tracking and WebXR `local` space are right-handed, metric, gravity-aligned and **+Y up**
  (`DEVICE_Y_UP_RIGHT_HANDED_METRES`). Device (x, y, z) maps to canonical axes (x, −z, y), a rotation of +90° about X.
- **What the client sends.** Clients send observations in their native convention. Anchor digital poses are canonical
  metres in the floor's current frame.
- **What the server returns.** The server solves `deviceToVenueTransform`, which maps device coordinates to canonical.
- **Gravity check.** Both frames are gravity-aligned, so the transform must send device +Y to canonical +Z. The server
  measures the deviation (`gravityTiltDegrees`) and refuses beyond `max-device-gravity-tilt-deg` (10°) with
  `409 RELOCALIZATION_GRAVITY_MISMATCH`. That catches a mis-entered anchor orientation or an axis-convention mistake.
- **Residual.** With one anchor the residual is `null` (unknown), not 0.

### Viewer

The `.ksplat` stays in its reconstruction frame. The artifact manifest says so (`coordinateSpace: RECONSTRUCTION`).
`GET .../reconstructions/{runId}` returns `coordinateFrame`.

- **Canonical frame.** The viewer passes `s`, `R` and `t` to GaussianSplats3D's scene transform (`position`,
  `rotation`, `scale`; composed as `s·R·p + t`), sets the camera up to +Z, and draws only POIs whose frame is that
  frame, plus routes.
- **No canonical frame.** The splat is shown in its own arbitrary frame, with a stated "Not calibrated" status. No POI
  or route is drawn on it.

## 6. Failure behaviour without calibration

| Consumer | Needs | Without it |
|---|---|---|
| GEOMETRIC_CLEANUP, PLANE_FITTING | nothing | Run before calibration. Their distance thresholds are multiples of the cloud's own median nearest-neighbour spacing (`characteristic_spacing`), so they are scale-invariant. They are not metres. |
| SEMANTIC_INDEXING | canonical frame | `NOT_CALIBRATED` (retryable), raised before any model loads. Output positions are canonical; clustering distance is `object_cluster_distance_m`. |
| NAVIGATION_BAKING | canonical frame | `NOT_CALIBRATED` (retryable). |
| REGION_ALIGNMENT | parent canonical frame + region metric scale | `NOT_CALIBRATED`. Registration refines scale (Open3D point-to-point with scaling); a correction beyond `alignment_max_scale_correction` (10 %) fails as `ALIGNMENT_SCALE_INCONSISTENT`. |
| REGION_SPLICE | parent canonical frame | `NOT_CALIBRATED`. The polygon is tested on canonical x/y, and region Gaussians outside the polygon are discarded. |
| Ingestion of DETECTED_OBJECTS / NAVIGATION_GRAPH | the artifact names the run's ACTIVE canonical frame | `409 ARTIFACT_FRAME_MISMATCH`: the whole report is refused. |
| Routing | the floor's current canonical frame; graph and destination POI in it; `VENUE_CONTROL_POINTS` on both floors for multi-floor | `409 NAVIGATION_NOT_CALIBRATED`, `NAVIGATION_FRAME_STALE`, `POI_NOT_CALIBRATED`, `FLOORS_NOT_REGISTERED` |
| Anchors, relocalization | the floor's current canonical frame; anchor in it | `409 NOT_CALIBRATED`, `ANCHOR_FRAME_STALE` |
| Rescan initiation | the parent version's reconstruction has a canonical frame | `409 NOT_CALIBRATED` |
| Viewer | canonical frame | Splat shown uncalibrated; no POIs or routes drawn |

A stage that fails with `NOT_CALIBRATED` fails the run at that stage. After calibrating, the operator retries the run
(`POST .../processing/retry`) and the stage receives the new frame. A retry consumes one of the job's bounded retries.

## 7. Validation status

**Tested, with synthetic mathematical fixtures only:**

- the shared fixture `packages/contracts/fixtures/synthetic-calibration.json`, used by the Python, Java and TypeScript
  tests;
- the tests: `test_frames.py`, `test_gravity.py`, `test_recast_boundary.py`, `test_calibration_gates.py`,
  `SimilarityTest`, `ArDeviceFrameTest`, `CoordinateFrameCalibrationTest`, `CoordinateFrameMigrationTest`,
  `coordinate-frame.test.ts`, `ar-frame-boundary.test.ts`.

What they cover:

- identity, translation, rotation, scale, composed similarity, inverse and round-trip error;
- metres↔reconstruction conversion;
- gravity recovery in an arbitrary gauge;
- the Recast and AR axis conversions;
- the NOT_CALIBRATED gates;
- calibration refusals;
- version supersession and exact re-projection;
- the V17 backfill against pre-existing rows.

**Not validated:**

- **No calibration has been performed on a real capture.** The gravity estimator, the scale-from-distances workflow
  and the spacing-relative cleanup and RANSAC factors (`cleanup_radius_spacing_factor = 3.0`,
  `plane_ransac_distance_spacing_factor = 1.0`) have not been run on a real reconstruction; those factors are untuned.
- **Scale-refining registration** (`with_scaling=True`) is covered by an Open3D test that skips where Open3D is not
  installed. It was **not executed** in this environment.
- **The web viewer's placement of a real `.ksplat` through the frame** has not been rendered: the Playwright tests
  never load a scene.
- **AR device conventions** are taken from the ARKit and WebXR specifications, and have not been checked on a
  device. `apps/web/components/ArWorkspace.tsx` still has the unrelated defects listed in review finding AR-1. The iOS
  package (`apps/ios-ar`) has **not** been updated to the device-frame boundary: it cannot be compiled here.
- **No picking UI.** An operator must supply reconstruction coordinates for distance references, floor points and
  control points. The viewer has no point-picking tool yet.
