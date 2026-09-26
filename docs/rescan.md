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
2. **Select the changed region** -- a simple polygon (`{"points": [[x, y], ...]}`, >= 3 vertices) in canonical
   venue metres (x, y horizontal; [coordinate-frames.md](coordinate-frames.md)). The parent version's reconstruction
   must have a canonical coordinate frame, or initiation is refused with `409 NOT_CALIBRATED` -- a polygon in metres
   has no location in an uncalibrated reconstruction.
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
6. **Replace only the changed region** -- `REGION_SPLICE` (see "SPLICE" below).
7. **Rebuild affected navigation data** and 8. **update affected semantic objects** -- see "Downstream rebuilds".
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
before the run is even created -- see "Downstream rebuilds".

## ALIGNMENT

### Never rigid-only; always in the canonical frame

Two independent SfM reconstructions differ by an arbitrary similarity: scale, rotation and translation. A rigid fit
between them cannot be right, and neither reconstruction's own frame means anything to the other. So registration never
happens between reconstruction frames. It happens in the **canonical venue frame** (metres, +Z up;
[coordinate-frames.md](coordinate-frames.md)), and both sides must be calibrated:

- the parent reconstruction's canonical frame (work order `parentCoordinateFrame`): moves `GLOBAL_CLOUD` into canonical
  metres, where the region polygon crops it;
- the re-scan capture's own calibration (work order `coordinateFrame`): it must at least be **metric**. An uncalibrated
  re-scan stops at REGION_ALIGNMENT with `NOT_CALIBRATED` and is never merged. The operator calibrates this run's
  reconstruction (at least two measured distances, or control points) and retries.

### Two modes (`chaya_worker.stages.region_alignment.choose_mode`)

| Mode | When | What is estimated |
|---|---|---|
| `DIRECT_CANONICAL` | The re-scan and the parent are both canonical **and** both surveyed to the same venue control points (`VENUE_CONTROL_POINTS`). | Nothing new. The re-scan's calibration places it directly in the canonical frame. Similarity ICP only verifies and refines, and the correction it applies is itself gated: two surveyed calibrations must agree. |
| `FEATURE_SIMILARITY` | Anything else: a `FLOOR_LOCAL` calibration has its own origin and heading, and a scale-only one has no orientation. | A full similarity: **scale + rotation + translation**, by robust correspondence estimation. |

FEATURE_SIMILARITY (`chaya_worker.region_alignment`, maths in `chaya_worker.similarity_registration`):

1. **Putative correspondences**: FPFH descriptors (Open3D) on both clouds, at `alignment_voxel_size_m`, matched as mutual
   nearest neighbours in descriptor space. Many will be wrong.
2. **RANSAC similarity**: minimal 3-point Umeyama hypotheses. A hypothesis whose scale falls outside `1 ± alignment_max_scale_correction`
   is discarded before scoring: the re-scan is already metric, so a large scale can only come from wrong matches or a
   wrong calibration. The best hypothesis is refitted on its consensus set.
3. **Similarity ICP**, coarse to fine over `alignment_icp_schedule_m` (default 1.0 / 0.5 / 0.25 / 0.1 m), on clouds
   downsampled to `alignment_icp_voxel_m` (0.02 m).
4. If the re-scan is gravity-aligned (a canonical `FLOOR_LOCAL` frame), it starts from its canonical pose, and the
   correction may not tilt it (`tilt_deg`).

Only step 1 needs Open3D. RANSAC, ICP, the metrics and the gates are numpy/scipy, and are tested in CI without Open3D.
DIRECT_CANONICAL needs no Open3D at all.

The aligned region is written in the **parent reconstruction's** frame, so the merged cloud, and every stage after
REGION_SPLICE, stays in that one calibrated frame. `ALIGNMENT_REPORT` records the correction and the
region->canonical and region->parent-reconstruction similarities. SEMANTIC_INDEXING uses the latter to move this
capture's cameras into the merged cloud's frame.

## QUALITY GATES

Every alignment is measured on its final transform (`similarity_registration.alignment_metrics`), in physical units,
and must pass **every** gate. Thresholds are worker settings; the defaults are shown.

| Gate | Measures | Default |
|---|---|---|
| `correspondence_count` | re-scan points (inside the venue crop) with a venue point within the final ICP distance | ≥ 200 |
| `inlier_ratio` | that count / the re-scan points inside the venue crop | ≥ 0.5 |
| `scale_correction` | \|scale − 1\|: how far the re-scan's metric calibration was off | ≤ 0.10 |
| `rotation_residual_deg` | \|ω\| of the residual rigid motion between the surfaces (below) | ≤ 1° |
| `translation_residual_m` | \|δ\| of the residual rigid motion between the surfaces | ≤ 0.03 m |
| `icp_residual_m` | RMS point-to-point distance at the correspondences (noise, plus about half the sample spacing) | ≤ 0.05 m |
| `confidence` | `inlier_ratio × (1 − translation_residual_m / 0.03)` | ≥ 0.6 |
| `direct_rotation_correction_deg` / `direct_translation_correction_m` | DIRECT only: how far ICP moved the surveyed pose | ≤ 5° / 0.5 m |
| `ransac_inliers` | FEATURE only: consensus size behind the estimate | ≥ 20 |
| `tilt_deg` | FEATURE, gravity-aligned re-scan only: tilt the correction introduces | ≤ 5° |

**Residual rigid motion** (`residual_rigid_motion`): the rotation ω and translation δ that would still move the aligned
re-scan onto the venue's surfaces. It is the Huber-weighted least-squares solution of the linearised point-to-plane system
at the correspondences. This is deliberately not a median distance. Each surface only observes offsets along its own
normal, so a median over mixed surfaces misses a 6 cm vertical offset: the walls slide along themselves and outnumber
the floor. The joint solve sees it. The confidence uses δ, not the point-to-point RMS, so it does not move with the
sampling density. That was the flaw the earlier score had (docs/BENCHMARKS.md, B5).

A failed gate is **`ALIGNMENT_REJECTED`**, enforced twice:

1. The worker publishes nothing: no SPLAT_ALIGNED, so REGION_SPLICE cannot run. Its error details carry the full
   report: mode, every gate with its value and threshold, the metrics, the transforms and the exact input artifacts.
2. The control plane never trusts the worker alone. `PipelineService#recordAlignmentAndCheckGate` records the report
   on the ScanVersion, then independently requires three things:
   - `gates_passed`;
   - confidence ≥ `chaya.rescan.min-alignment-confidence`;
   - |scale − 1| ≤ `chaya.rescan.max-scale-correction`.

   A "successful" report that fails any of them is rejected too.

Either way:

- the run fails with `ALIGNMENT_REJECTED`;
- the ScanVersion becomes `ALIGNMENT_REJECTED`. That status is terminal and immutable like FINALIZED (V19), and it
  records `rejected_at` and the reason in its provenance;
- the run cannot be retried (`409 RUN_NOT_RETRYABLE`). A new re-scan starts a new version.

The parent version is never touched. Its cloud is only read; every output is a new artifact under the re-scan's own run;
and the parent row is unchanged. `RescanControlPlaneTest` checks the parent row field by field.

Never claim centimetre accuracy without measurement. The recorded residuals are what registration measured against the
venue's own reconstruction, not absolute accuracy, which needs surveyed control points.

## SPLICE

`REGION_SPLICE` (`chaya_worker.region_splice`) replaces a **volume**, not a column: the region polygon (canonical x, y)
times the height range of the aligned re-scan's own geometry inside it (0.5–99.5 percentile), grown by
`splice_z_margin_m` (0.2 m).

- **Replaced**: venue Gaussians inside that volume.
- **Kept, unchanged and in order**: everything outside the polygon; also venue geometry inside the polygon but above or
  below the volume (a ceiling or mezzanine the re-scan never captured). None of this is unrelated geometry the splice
  may delete.
- **Added**: re-scan Gaussians inside the volume. Re-scan Gaussians outside it are discarded, so nothing the venue
  already had is duplicated (review V-2).
- **Seam**: measured, not assumed. For added Gaussians within `splice_seam_band_m` (0.15 m) of the polygon edge, the
  step is the median point-to-plane distance to the kept venue surface just across the edge. A step above
  `splice_max_seam_step_m` (0.03 m) is a visible seam, and the splice fails with `SPLICE_REJECTED`.

What was replaced is recorded exactly:

- `SPLICE_INDEX` (`splice-index.npz`) holds the indices of every removed venue Gaussian and every added re-scan
  Gaussian;
- `SPLICE_REPORT` holds the volume, the counts, the seam measurement, and the input and output artifacts by id and
  SHA-256;
- the control plane copies the report onto the ScanVersion (`splice_report`).

A no-op re-scan (the venue's own Gaussians, perfectly calibrated) returns the venue unchanged. The worker tests check
this.

## Downstream rebuilds: what is selective and what is not

| Artifact | Rebuilt | When it goes live |
|---|---|---|
| Merged cloud (`SPLAT_MERGED`) | Only the region's volume is replaced (above). | With the version (new artifact under the re-scan's run). |
| Planes (`PLANE_FITTING`) | **Whole floor**: refitted over the merged cloud. Not selective. | With the version. |
| Viewer asset (`.ksplat`) | **Whole floor**: regenerated from the merged cloud. It is one file. Not selective. | Viewers list a re-scan's model only once its version is FINALIZED (`ReconstructionService#listForFloor`). A failed or rejected re-scan never replaces what they see. |
| Navigation | **Skipped entirely** when no node of the floor's ACTIVE graph lies in the region (`RescanService#navigationIntersectsRegion`, decided before the run exists). **Otherwise the whole floor** is re-baked: Recast has no per-tile partial bake here. | The new graphs are ingested as DRAFT and activated only when the version finalizes (`activateRescanGraphs`). |
| Semantic index / POIs | Only the region: SEMANTIC_INDEXING sees only the re-scan's frames, and only AUTO_DETECTED POIs inside the polygon are superseded. **MANUAL POIs are never touched.** | Only when the version finalizes (`applyRescanDetections`). A re-scan that fails or is rejected changes no POI (review V-6). |

## VERSIONING

Old `scan_version` rows are immutable (`scan_version_guard`): a `FINALIZED` or `ALIGNMENT_REJECTED` row can never be
updated or deleted. A re-scan's version records:

| Field | Column | Written |
|---|---|---|
| parent version | `parent_version_id` | at creation (`startIncrementalProcessing`) |
| changed region | `region_geometry` | at creation |
| operator | `created_by` | at creation |
| processing configuration | `processing_config` | at creation (`navigationRebuildRequired`, `privacyEnabled`, `timeBudgetSeconds`) |
| alignment method / confidence / residual | `alignment_method`, `alignment_confidence`, `alignment_residual_m` | when REGION_ALIGNMENT reports, any outcome |
| full alignment report | `alignment_report` | when REGION_ALIGNMENT reports, any outcome |
| what was replaced | `splice_report` | when REGION_SPLICE succeeds |
| changed artifacts | `changed_artifact_kinds` | as they change (`SPLAT_MERGED`, `NAVIGATION_GRAPH`, `DETECTED_OBJECTS`) |
| timestamps | `created_at`, `finalized_at` / `rejected_at` | at creation / on success / on rejection |
| provenance | `provenance` | on success (`runId`, stages) or rejection (`runId`, reason) |

`status` is `DRAFT → FINALIZED` only when the run's last stage succeeds, and `DRAFT → ALIGNMENT_REJECTED` when the
alignment is rejected. A run that fails for any other reason (or is PARTIAL or CANCELLED) leaves the version DRAFT.

The parent's cloud (`GLOBAL_CLOUD`) is the SPLAT_MERGED, else SPLAT_CLEAN, of exactly the run the parent's provenance
names, not any cloud of the same scan (review V-8).

Not addressed here (review V-4): version numbers are still `parent + 1` with no per-floor uniqueness, and there is no
"current version" pointer on the floor.

## AUDIT

| Action | Who | Metadata |
|---|---|---|
| `rescan.initiate` | the operator | `floorId`, `sourceVersionId`, `regionAreaSquareMeters`, `navigationRebuildRequired` |
| `rescan.processing_started` | the operator | `sourceVersionId`, `runId`, `navigationRebuildRequired` |
| `rescan.downstream_applied` | the run's actor | `navigationGraphsActivated`, `poisSuperseded`, `poisCreated` (only on finalization) |
| `rescan.outcome` | the run's actor | `sourceVersionId`, `resultingVersionId`, `outcome`, `failureCode` when applicable |
| `rescan.version_bootstrapped` | the operator | `floorId`, `runId` (only for `finalizeCurrent`) |

## TESTS

All geometry tests use **synthetic** point clouds (`tests/rescan_scene.py`: a small room). They are mathematical tests,
not venue data.

| What | Where | Needs |
|---|---|---|
| Umeyama, RANSAC, ICP, residuals, confidence, gates: scale / rotation / translation / combined recovery, reflections, degenerate input, outliers, a scale outside the prior, no overlap, offsets only some surfaces observe, confidence independent of sampling | `tests/unit/test_similarity_registration.py` | nothing |
| Both modes end to end: similarity recovery, too few correspondences, too little overlap, bad scale, high residual, tilt, surveyed calibrations that disagree | `tests/unit/test_region_alignment.py` (a stand-in supplies FEATURE mode's matches) | nothing |
| Splice: overlapping-geometry replacement, re-scan beyond the polygon, unrelated geometry kept, no-op, visible seam refused, canonical-frame volume, exact index sets | `tests/unit/test_region_splice.py` | nothing |
| Both stages through the orchestrator, in non-identity frames: successful merge, rejected merge (nothing published), no-op re-scan, Open3D requirement, uncalibrated re-scan | `tests/orchestration/test_rescan_stages.py` | nothing |
| Real FPFH matching feeding the estimator | `tests/gpu/test_region_alignment.py` | Open3D (skipped without it; run in the `chaya-bench-open3d` image) |
| Control plane: worker rejection, control-plane rejection, parent untouched, rejected version immutable and not retryable, full lineage on success, POIs and viewer only on finalization, MANUAL POIs survive | `RescanControlPlaneTest`, `RescanServiceTest`, `ScanVersionImmutabilityTest` | Testcontainers |

B5 (docs/BENCHMARKS.md) measures the FEATURE_SIMILARITY path on a real COLMAP cloud with known similarity misalignments.

**Not validated:** no real venue has been re-scanned and merged. Every number above is from synthetic clouds, or from a
real SfM cloud with simulated misalignment.
