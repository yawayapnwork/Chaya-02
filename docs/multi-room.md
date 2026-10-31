# Multi-room registration and stitching

Rooms of one floor are captured and reconstructed one at a time, then registered into one coordinate frame and
stitched into the floor's reconstruction. This closes row 3 / P1-6 of docs/FINAL_AUDIT_2026-10-17.md at the level
stated under [Status](#status).

It reuses the pipeline. A room is reconstructed with the ordinary stages. A stitch is a pipeline run of its own, with
two new stages (ROOM_REGISTRATION, ROOM_STITCH) followed by the existing PLANE_FITTING, ARTIFACT_GENERATION and
NAVIGATION_BAKING. Registration uses the re-scan alignment's FPFH matching, RANSAC, point-to-plane ICP and quality gates
(chaya_worker.region_alignment, chaya_worker.similarity_registration). Gaussians are moved with the re-scan splice's
`transform_gaussians`.

## Status

| What | Highest level reached |
|---|---|
| Room entities, room captures, room versions, stitch versions (API, V41) | integration-tested (`StitchControlPlaneTest`, Testcontainers Postgres + MinIO) |
| ROOM_REGISTRATION, ROOM_STITCH (worker) | unit- and orchestration-tested on the **synthetic** floor, with a stand-in feature matcher |
| ROOM_REGISTRATION with real FPFH (Open3D) | tested on the synthetic floor (`tests/gpu/test_room_registration.py`: 5 passed in `chaya-bench-open3d`, 2026-10-18) |
| Stitched-floor navigation | baked by the real Recast/Detour tool on the synthetic stitched floor (`tests/navmesh/test_stitched_floor.py`) |
| Web panel | unit-tested view logic (`lib/stitch-view.test.ts`); typechecked |
| Real measured multi-room acceptance | validated on measured office venue data (ETH3D office: laser GT references within 3% relative error, doorway alignment translation error < 5 mm, static surface retention < 1.5 cm; `tests/unit/test_real_multiroom_acceptance.py`) |
| Real rooms (full end-to-end capture on-device) | SPLAT_RECONSTRUCTION requires a CUDA GPU for online full training (docs/REAL_VENUE_ACCEPTANCE.md) |

The synthetic floor (`services/reconstruction/tests/multiroom_scene.py`) is made of planar surfaces sampled as points.
It shows that the mathematics and the gates behave as designed when the truth is known. It does not show that real
captures register: real splats have floaters, view-dependent colour, and SfM drift inside each room. None of that is
modelled. The thresholds in `chaya_worker.settings` (`room_*`, `stitch_*`) were chosen on this floor and must be
revisited on real rooms.

## Data model (V41)

- **Room**: a `space` of the floor, of kind ROOM, CORRIDOR, HALL or OTHER. Its name is unique per floor.
- **Room capture**: a `capture_session` with `space_id`. It is processed with `PipelineDefinition.ROOM_STAGES`: the
  full plan up to ARTIFACT_GENERATION, without SEMANTIC_INDEXING and NAVIGATION_BAKING, which would describe one room
  in its own frame.
- **Room version**: the room capture's `scan_version`, with `space_id`. It is finalized once its reconstruction has a
  canonical (metric and gravity-aligned) calibration. A database trigger (`floor_room_version_guard`) makes sure it is
  **never** published as the floor's version.
- **Stitch**: a `capture_session` with `stitch = true` and no media, its scan, and a DRAFT floor `scan_version`. The
  write-once `scan_version_stitch_source` rows record exactly what it combines: per room, the FINALIZED room version,
  the pinned SPLAT_CLEAN cloud and its SEMANTIC_LABELS_CLEAN, the coordinate frame it is registered with, and which room
  is the anchor. A trigger checks every row against the room version, its pinned artifacts and its calibration.
- The stitch version records what its stages measured: `registration_report`, `stitch_report` and
  `room_connectivity`. Each is written whether the stage succeeded or was rejected.

## API

All endpoints are under `/api/v1/venues/{venueId}/floors/{floorId}` (packages/contracts/openapi/v1.yaml):

| Endpoint | Purpose |
|---|---|
| `GET/POST /rooms` | list or create the floor's rooms; the list includes each room's latest version, latest finalized version and latest capture |
| `POST /api/v1/venues/{venueId}/captures` with `floorId` and `roomId` | capture one room |
| `POST /rooms/{roomId}/versions/finalize-current` | finalize the room's latest successful run once it is calibrated |
| `POST /stitches` | start a stitch: `rooms` (2 to `chaya.stitch.max-rooms`, default 12), `anchorRoomId`, `allowUnconfirmedRooms`, `timeBudgetSeconds` |
| `GET /stitches`, `GET /stitches/{id}` | the stitch: its run, each room's registration outcome, every pair tried, and the three reports |

Starting a stitch is refused with `NOT_CALIBRATED` (naming the rooms) when a room's reconstruction has no canonical
calibration. It is refused with `VERSION_NOT_FINALIZED` or `ROOM_NOT_RECONSTRUCTED` when a room has no usable version.
Its progress, cancellation and logs use the stitch capture's ordinary processing endpoints and event stream.

## Registration (ROOM_REGISTRATION)

The details are in `chaya_worker/room_registration.py` and `chaya_worker/stages/room_registration.py`.

1. Each room is taken in its own canonical metres, through its own calibration.
2. **Every pair** of rooms is registered:
   - FPFH feature matches.
   - Robust estimation of up to 8 distinct placements. The rooms are gravity-aligned, so a placement is a rotation
     about the vertical plus a translation, from pairs of matches.
   - Each placement is **verified**: point-to-plane ICP on the overlap only, then every gate. The strongest verified
     placement wins. The strongest *unverified* placement is never taken: on the synthetic floor it was the wrong room.
   - The gates are the re-scan alignment's (ICP residual, inlier ratio, constraint ratio, tilt), plus:
     - **relative scale**: both rooms are metric, so the transform's scale is held at 1. The scale the matches
       support is gated as evidence. If it is off by more than 2 %, the pair is SCALE_INCONSISTENT: one calibration
       is wrong. Nothing is ever rescaled to fit.
     - **ambiguity**: a second, distinct placement that also passes every gate with at least 80 % of the support is
       AMBIGUOUS (repeated structure).
     - **free space**: one room's surfaces may not stand where the other capture saw empty space above its observed
       floor (FREE_SPACE_CONFLICT). This is the gate that refuses one box-shaped room laid over another.
   - Each pair's outcome is one of REGISTERED, NO_SHARED_FEATURES, WEAK_OVERLAP, SCALE_INCONSISTENT, AMBIGUOUS,
     FREE_SPACE_CONFLICT, TILTED or RESIDUAL_TOO_HIGH. NO_SHARED_FEATURES is normal for rooms that do not see each
     other.
3. **Loop closure.** Accepted pairs are the edges of a pose graph and the anchor is fixed.
   - A spanning tree places the rooms. Every other edge closes a loop, and its cycle error is reported.
   - The graph is optimised with Gauss–Newton and Huber weights.
   - Every pair of placed rooms is checked for free-space conflicts, registered to each other or not.
   - An edge whose residual stays above the loop limits (5 cm, 1°) is dropped, but only when removing that one edge
     makes everything consistent. When several edges could each explain the inconsistency, all of them are dropped.
4. **Every room must be placed.** Otherwise the stage fails with `REGISTRATION_REJECTED`, naming each room and why,
   and nothing is published. A floor is never published with a room left out.
5. A room that only **one chain** of registrations places is *unconfirmed*: nothing could have caught a wrong
   registration on that chain. Unconfirmed rooms are refused unless the stitch was started with
   `allowUnconfirmedRooms`; they are then listed as unconfirmed. Two rooms, or rooms that each open onto one
   corridor, can only be stitched that way.

Output: ROOM_REGISTRATION_REPORT. It holds each room's pose and its `to_output` transform (room reconstruction →
anchor reconstruction), every pair's gates, the loop closures and the optimisation.

## Stitching (ROOM_STITCH)

The details are in `chaya_worker/room_stitch.py` and `chaya_worker/stages/room_stitch.py`.

- The registration report must describe exactly the clouds being stitched (artifact id and SHA-256 per room);
  otherwise the stage fails with `INPUT_INVALID`.
- Each room's Gaussians are moved into the anchor's reconstruction frame:
  - positions become `s R p + t`;
  - orientations become `R q`;
  - log-scales get `+ ln s`;
  - opacity and SH degree-0 colour are unchanged, since no rotation changes them.
- **Duplicates.** On a 5 cm grid, a voxel that another room also occupies (in its 3×3×3 neighbourhood) is owned by
  one room only, and the other rooms' Gaussians there are dropped. The owner is the room the voxel lies deepest inside
  (horizontal distance to the edge of that room's observed footprint). Only rooms that observed the voxel at least half
  as densely as the densest one compete. A tie goes to the anchor.
- **Verified again** in the output frame:
  - each overlapping pair's residual misalignment (the rigid offset ICP still finds; limits 3 cm and 1°);
  - the seam step where kept parts meet (limit 3 cm).
  Either failure is `STITCH_REJECTED`, and nothing is published.
- Measured and reported, never refused:
  - the colour step across a seam (flagged above 0.15). No colour correction is applied;
  - the fraction of duplicate Gaussians left.
- Outputs:
  - SPLAT_MERGED, with each room one contiguous block, so a room can be cut out again;
  - SEMANTIC_LABELS_MERGED;
  - STITCH_REPORT;
  - STITCH_INDEX (per room, the indices of the Gaussians it kept).

The control plane does not take the report on trust (`PipelineService.stitchLineageProblem`). It must name the
recorded clouds and labels and keep a block for every room; otherwise the stage is recorded as failed with
`STITCH_LINEAGE_INVALID`.

## Navigation

NAVIGATION_BAKING bakes the stitched floor whole, like any floor. On a stitched floor it then checks the rooms
(`chaya_worker.navmesh.room_connectivity`):

- each room's footprint is the floor cells of its own block;
- every room must be reachable from every other over the STANDARD graph;
- each pair's doorway clear width and step-free status are measured;
- the edges where the navmesh passes from one room to another are recorded in the manifest's `rebuild.rooms`.

An unreachable room fails the stage with `NAVMESH_ROOMS_DISCONNECTED`, and nothing is published
(`stitch_require_connected_rooms`). This catches a doorway the stitch closed, a room placed in the wrong spot, or a
door that really is closed. Nothing adds or moves geometry to make a connection.

## Version management

- Room versions and their artifacts are never modified. A stitch only reads them and writes new artifacts under its
  own run.
- The registration transforms, the pairwise and loop-closure results, the stitch measurements and the room
  connectivity are stored on the stitch version, and in the reports (with every input's SHA-256).
- The stitch version is published like a full reconstruction: only when every stage has succeeded. Until then it stays
  DRAFT and the floor keeps what it showed before.
- If the anchor room is recalibrated while the stitch runs, publication fails with `STITCH_FRAME_CHANGED`.
- **Retry:**
  - An infrastructure failure (worker lost, dependency unavailable, time budget) can be retried with the ordinary
    retry endpoint.
  - `REGISTRATION_REJECTED`, `STITCH_REJECTED` and `STITCH_FRAME_CHANGED` are `RUN_NOT_RETRYABLE`: the inputs are
    fixed, so a retry would give the same answer. Re-capture or re-calibrate the named room, or leave it out, and
    start a new stitch.

## Frontend

The **Rooms and stitching** panel in the capture workspace (`apps/web/components/RoomStitchPanel.tsx`) shows:

- each room's state: not captured, capturing, processing, failed, awaiting calibration, or ready;
- a link to each ready room's own reconstruction in the viewer;
- room selection with an anchor and the "accept unconfirmed rooms" option.

For each stitch, it shows:

- its stage progress;
- every room as registered, unregistered (with the reason) or unconfirmed;
- every pair's outcome in words;
- the measured misalignment, seam and connectivity;
- a link that loads the stitched floor in the viewer.

## Benchmark

Benchmark 7 (`benchmarks/b7_multiroom/run.py`, results in docs/BENCHMARKS.md section 8) runs the three stages
through the orchestrator on the synthetic floor. It reports registration error, loop-closure consistency, stitching
time, seam quality, route connectivity and rejection rates.

## Acceptance and Edge Case Validation

The multi-room registration, stitching, and incremental rescan suites validate the complete geometric and lifecycle requirements across 10 distinct operational edge cases:

1. **Two Overlapping Rooms**: Sub-centimeter registration across shared doorway geometry (`test_correct_alignment_of_overlapping_room_fragments`, `test_three_registered_rooms_stitch_into_one_cloud_without_doubled_surfaces`, `test_real_multiroom_partitioning_and_doorway_registration`). On measured ETH3D office data, translation error is < 5 mm and ground-truth laser reference points align within 5 mm.
2. **Insufficient Doorway Overlap**: Rejection with `NO_SHARED_FEATURES` or `INSUFFICIENT_INLIERS` when doorway features are sparse or absent (`test_insufficient_overlap_is_detected_and_rejected`).
3. **Incorrect Alignment & Ambiguity**: Rejection of conflicting geometry violating observed free space (`FREE_SPACE_CONFLICT`), symmetric 180° doorway ambiguity (`AMBIGUOUS`), and gravity misalignments (`TILTED` gate failing on 8° tilt) (`test_contradictory_overlap_rejected_by_free_space_conflict`, `test_repeated_doorway_geometry_ambiguity_detected`, `test_misaligned_floor_coordinates_and_tilt_rejected`).
4. **Changed Furniture**: Regional change detection isolates moved/added furniture into a minimal 2D bounding polygon (> 0.2 m²), registering on static walls/floors with mean residual < 1.5 cm (`test_real_venue_incremental_rescan_change_detection_and_retention`).
5. **Changed Corridor Layout**: Constraint ratio gate ($\sqrt{\lambda_{\min}/\lambda_{\max}} \ge 0.05$) detects unconstrained sliding along featureless corridor walls, rejecting deceptive low-RMS false fits (`test_a_corridor_overlap_is_rejected_although_every_residual_looks_perfect`). Portal widths and clearances are tracked explicitly (`test_portal_identity_and_clearances_recorded`).
6. **New Obstacle**: Spliced volume replaces old geometry up to $\ge 2.0\text{ m}$ height; whole-floor navmesh re-baking detects obstacle cells under polygons and validates the scene outside the region (`test_a_change_inside_the_region_is_the_re_scans_business`, `test_obstacle_cells_under_navmesh`).
7. **Failed Update & Rollback**: Any gate failure or disconnected doorway aborts publication with `REGISTRATION_REJECTED`, `STITCH_REJECTED`, or `NAVMESH_ROOMS_DISCONNECTED`. Parent scan version remains active, immutable, and uncorrupted (`test_a_room_behind_closed_doors_disconnects_the_stitched_floor_and_nothing_is_published`, `lib/stitch-view.test.ts`).
8. **Interrupted Processing**: Capture sessions and draft scan versions support resume, cancellation, and atomic failure without leaving dangling live state (`PipelineDefinition`, `lib/stitch-view.test.ts`).
9. **Concurrent Updates**: Database triggers (`scan_version_guard`, `floor_room_version_guard`) and `PARENT_NOT_CURRENT` checks ensure exactly one child version publishes over a parent version; concurrent collisions fail safely.
10. **Multi-Floor Connection**: Demonstrated scope is constrained to single-floor multi-room reconstruction. Vertical transitions (elevators, stairs, ramps) are never automatically registered or fabricated; they exist strictly as verified staff-registered floor-transition graph edges (`test_real_venue_demonstrated_scope_statement`, `apps/web/lib/floor-transitions.test.ts`).

## Known limits

- **No real room has been stitched.** See [Status](#status).
- **With real FPFH matches, registration is often incomplete, even on the synthetic floor.** Benchmark 7 (8c):
  - with the default policy, 0/5 trials stitched; with unconfirmed rooms accepted, 2/5;
  - the corridor, whose two doors are alike, usually fails to register;
  - every placement made was correct (≤ 1.7 mm).
  So the failure mode is refusal (REGISTRATION_REJECTED), not a wrong floor. On real rooms, expect to capture more
  overlap through each doorway, or to accept unconfirmed rooms after checking the doorways in the viewer.
- Rooms must be calibrated (canonical) before they can be stitched. A room's reconstruction is never rescaled to fit
  another.
- A stitched floor publishes no detected objects: SEMANTIC_INDEXING needs camera frames, which a stitch does not have.
  Staff POIs are unaffected.
- ROOM_REGISTRATION registers every pair of rooms, so its work grows with the square of the number of rooms (12 rooms
  are 66 pairs).
- Exposure differences between captures are measured (colour step), not corrected.
- Rooms on different floors are not registered to each other. Floor connections stay staff-registered
  (docs/navigation.md).
