# Incremental re-scan

When a physical venue changes, an operator should not have to reconstruct the whole venue again. This is
the architecture for capturing, aligning and splicing in just the changed region, and recording the result
as a new immutable `scan_version`. It reuses the reconstruction pipeline's control plane
(`dev.chaya.api.pipeline`, docs/pipeline.md) end to end -- an incremental re-scan is a differently-shaped
pipeline run, not a separate system.

## The flow

1. **Select an existing venue version** -- `GET /venues/{v}/floors/{f}/scan-versions` lists a floor's
   `scan_version` history; the operator picks a `FINALIZED` one. If none exists yet (the floor has only
   ever had a full reconstruction, never a `scan_version`), `POST
   /venues/{v}/floors/{f}/scan-versions/finalize-current` bootstraps version 1 from the floor's latest
   successful full-venue pipeline run (`dev.chaya.api.rescan.RescanService#finalizeCurrent`) -- it
   formalizes an already-completed reconstruction as a version record, it does not fabricate one.
2. **Select the changed region** -- a simple polygon (`{"points": [[x, y], ...]}`, >= 3 vertices) in the
   floor's venue frame.
3. **Capture only that region** -- `POST /venues/{v}/floors/{f}/rescan` (`RescanController#initiate`)
   validates the version and region (`PolygonGeometry.area` must fall within
   `chaya.rescan.min/max-region-area-square-meters`) and creates a region-scoped `capture_session`
   (`parent_scan_version_id` + `region_geometry` recorded on it). The operator then uploads media to it
   through the **ordinary** capture endpoints (`/venues/{v}/captures/{captureId}/media/...`,
   `/complete-upload`) -- nothing about upload is special-cased for a re-scan.
4. **Reconstruct the local region** -- `POST /venues/{v}/captures/{captureId}/processing` (unchanged
   endpoint). `CaptureService#startProcessing` detects the capture's recorded parent version and delegates
   to `RescanService#startIncrementalProcessing`, which creates the DRAFT `scan_version` and starts
   `PipelineDefinition.INCREMENTAL_STAGES` instead of the full-venue plan.
5. **Align it with the existing global reconstruction** -- `REGION_ALIGNMENT` (see "ALIGNMENT" below).
6. **Replace only the changed region** -- `REGION_SPLICE` (see "ALIGNMENT" below).
7. **Rebuild affected navigation data** -- see "NAVIGATION".
8. **Update affected semantic objects** -- see "SEARCH".
9. **A new immutable ScanVersion** -- only once the run truly `SUCCEEDED` (see "VERSIONING").

## The incremental pipeline plan

`PipelineDefinition.INCREMENTAL_STAGES`:

```
INPUT_VALIDATION -> FFMPEG_PREPROCESS -> FRAME_QUALITY_FILTER -> PRIVACY_PREPROCESS -> POSE_ESTIMATION ->
SPLAT_RECONSTRUCTION -> GEOMETRIC_CLEANUP -> REGION_ALIGNMENT -> REGION_SPLICE ->
PLANE_FITTING -> ARTIFACT_GENERATION -> SEMANTIC_INDEXING -> [NAVIGATION_BAKING]
```

The first seven stages are identical to the full-venue plan, run against only the newly captured region's
media -- the worker never sees the rest of the venue's frames. `PLANE_FITTING`, `ARTIFACT_GENERATION`,
`SEMANTIC_INDEXING` and (when included) `NAVIGATION_BAKING` all run on `REGION_SPLICE`'s output (kind
`SPLAT_MERGED`) rather than the region alone, because they need the whole venue's up-to-date geometry, not
just the part that changed (`chaya_worker` stages prefer `SPLAT_MERGED` over `SPLAT_CLEAN`/`SPLAT` when
present -- see `plane_fitting.py`, `artifact_generation.py`, `semantic_indexing.py`, `navigation_baking.py`).

`NAVIGATION_BAKING` is in brackets because whether it is in the plan **at all** is decided once, at step 4,
before the run is even created -- see "NAVIGATION".

## ALIGNMENT

`REGION_ALIGNMENT` (`chaya_worker.stages.region_alignment`, math in `chaya_worker.region_alignment`) aligns
the region's own `GEOMETRIC_CLEANUP` output (`SPLAT_CLEAN`, in an arbitrary local frame from this capture's
own `POSE_ESTIMATION`) onto `GLOBAL_CLOUD` -- the selected parent version's own cleaned/merged
reconstruction, supplied by the control plane (`PipelineService#globalCloudInput`; **never** the current
run's own output, which would be aligning the region against itself). Real, two-stage registration, never
a hardcoded translation:

1. **Feature matching**: FPFH descriptors + RANSAC correspondence matching (Open3D
   `registration_ransac_based_on_feature_matching`) -- coarse global registration, no initial guess needed.
2. **ICP**: point-to-plane `registration_icp`, refined from step 1's result.

`alignment_confidence(fitness, inlier_rmse, voxel_size)` turns Open3D's own registration-quality numbers
into the single score everything gates on: `fitness` (a real correspondence-coverage fraction) scaled down
the closer `inlier_rmse` gets to the voxel size (an RMSE near the voxel size means the "inliers" barely
qualify). This is never a constant and never independent of what the registration actually found.

`REGION_SPLICE` (`chaya_worker.region_splice`) then does the actual replacement: every Gaussian of
`GLOBAL_CLOUD` whose (x, y) falls inside the region polygon is removed (`point_in_polygon`, ray casting),
and the aligned region's Gaussians (`transform_gaussians` -- position **and** the Gaussian's own orientation
quaternion are both rotated by the alignment transform, not just translated) are appended in their place.
Everything outside the polygon is untouched, byte for byte.

## QUALITY GATES

**The splice must fail if alignment confidence is below the configured threshold, and this is enforced
twice, independently:**

1. The worker itself refuses to publish `SPLAT_ALIGNED` when
   `confidence < settings.min_alignment_confidence` (`ALIGNMENT_CONFIDENCE_BELOW_THRESHOLD`,
   `chaya_worker/stages/region_alignment.py`).
2. The control plane **never trusts the worker's own threshold**: when `REGION_ALIGNMENT`'s report reaches
   `PipelineService#applyReport`, `recordAlignmentAndCheckGate` writes the reported method/confidence/
   residual onto the `scan_version` regardless of outcome, then independently compares confidence against
   `chaya.rescan.min-alignment-confidence` (`RescanProperties`, a control-plane-owned setting). Below it,
   the stage is treated as failed (`ALIGNMENT_CONFIDENCE_BELOW_THRESHOLD`) even if the worker itself
   reported success -- `REGION_SPLICE` is never queued, and the run fails.

A failed or rejected alignment leaves its `scan_version` **DRAFT forever** -- it is never finalized, never
promoted, never silently merged. `RescanControlPlaneTest#lowAlignmentConfidenceFailsTheRunAndNeverFinalizesTheVersion`
proves this against the real HTTP worker protocol, not a mock.

Never claim centimeter accuracy without measurement: the residual recorded (`alignment_residual_m`) is
Open3D's own measured inlier RMSE, never an assumed or advertised bound.

## VERSIONING

Old `scan_version` rows are immutable (`scan_version_guard`, unchanged from before this feature: a
`FINALIZED` row can never be updated or deleted). A new version, once it exists, records:

| Field | Column | Written |
|---|---|---|
| parent version | `parent_version_id` | at creation (`startIncrementalProcessing`) |
| changed region | `region_geometry` | at creation |
| alignment method | `alignment_method` | when `REGION_ALIGNMENT` reports (any outcome) |
| alignment confidence | `alignment_confidence` | when `REGION_ALIGNMENT` reports (any outcome) |
| alignment residual | `alignment_residual_m` | when `REGION_ALIGNMENT` reports (any outcome) |
| changed artifacts | `changed_artifact_kinds` | appended as each relevant stage succeeds (`SPLAT_MERGED`, `NAVIGATION_GRAPH`, `DETECTED_OBJECTS`) |
| processing configuration | `processing_config` | at creation (`navigationRebuildRequired`, `privacyEnabled`, `timeBudgetSeconds`) |
| timestamp | `created_at` / `finalized_at` | at creation / on success |

`status` moves `DRAFT -> FINALIZED` only when the run's very last stage succeeds
(`PipelineService#recordRescanOutcome`, called from `finishRun`). `PARTIAL`, `FAILED` and `CANCELLED` all
leave it `DRAFT` -- an abandoned attempt, indistinguishable in the database from one nobody ever retried,
which is the correct level of trust for it.

## NAVIGATION

"Only affected regions should require navigation updates where technically possible" is decided **once**,
before the run is even created, because the region is already known at step 4 (`RescanService`'s
`navigationIntersectsRegion`): it queries the floor's current `ACTIVE` `STANDARD` `navigation_graph`'s
nodes and tests each against the region polygon (`PolygonGeometry.pointInPolygon`). If none fall inside it,
`NAVIGATION_BAKING` is **left out of the plan entirely** -- not run and skipped, genuinely never queued --
and the floor's existing routing graph keeps serving `RouteService` unchanged, because nothing about it is
stale. If the region does intersect it, `NAVIGATION_BAKING` runs (on the spliced, venue-wide geometry, per
the existing full-floor Recast bake this codebase has -- see docs/navigation.md; true per-tile partial
re-baking is a Recast capability this codebase does not build on and is out of scope here).

## SEARCH

"Only affected semantic objects should need re-indexing" falls directly out of what the worker can
possibly see: `SEMANTIC_INDEXING` only ever processes the captured region's own frames, so it can only ever
detect objects inside that region. `PipelineService#ingestDetectedObjects`, for an incremental run, first
soft-deletes every existing POI on the floor whose latest position falls inside the region polygon
(`supersedePoisInRegion`) and only then inserts the fresh detections -- exactly the set that changed.
Everything outside the polygon is never touched, so it never needs re-embedding or re-indexing.

## AUDIT

Every step is recorded on the real `audit_log` (`dev.chaya.api.audit.AuditService`), keyed so a full
history can always be reconstructed:

| Action | Who | Metadata |
|---|---|---|
| `rescan.initiate` | the operator | `floorId`, `sourceVersionId`, `regionAreaSquareMeters`, `navigationRebuildRequired` |
| `rescan.processing_started` | the operator | `sourceVersionId`, `runId`, `navigationRebuildRequired` |
| `rescan.outcome` | the run's actor | `sourceVersionId`, `resultingVersionId`, `outcome` (`SUCCEEDED`/`PARTIAL`/`FAILED`/`CANCELLED`), `failureCode` when applicable |
| `rescan.version_bootstrapped` | the operator | `floorId`, `runId` (only for `finalizeCurrent`) |

`rescan.outcome` is written for every terminal run state, not only success, so a rejected alignment or any
other failure is exactly as auditable as a successful splice.

## TESTS

Three separated tiers, matching the rest of the pipeline's testing story (docs/pipeline.md):

| Tier | Where | Needs |
|---|---|---|
| Anchor-free geometry (polygon crop, quaternion transform) | `tests/unit/test_region_splice.py` | nothing -- pure numpy |
| Real feature-matching + ICP alignment, with known-transform fixtures | `tests/gpu/test_region_alignment.py` | Open3D (skipped, not failed, without it) |
| Control-plane orchestration, gating and versioning | `RescanServiceTest`, `RescanControlPlaneTest` | Testcontainers Postgres + MinIO (skipped, not failed, without Docker) |

`test_region_alignment.py`'s known-transform fixture (`_room_corner` + a known rotation/translation) is
exactly what "do not claim successful incremental reconstruction unless the alignment pipeline actually
ran" requires a test to do: it applies a **known** rigid transform to build the "captured" cloud, runs the
real registration, and asserts the recovered transform's **translation error**, **rotation error** (both
measured against that known ground truth) and **residual** (Open3D's own inlier RMSE) are all small --
never asserting merely that *some* transform came back. A second fixture (two non-overlapping clouds)
proves the confidence score is genuinely low when there is nothing real to align, not a value that always
passes. `RescanControlPlaneTest` proves the same discipline one level up: it drives the real HTTP worker
protocol through a low-confidence `REGION_ALIGNMENT` report and asserts the run fails and the version is
never finalized, then repeats with a high-confidence report and asserts it is.
