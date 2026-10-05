# Incremental re-scan

When a physical venue changes, an operator should not have to reconstruct the whole venue again. This is
the architecture for capturing, aligning and splicing in just the changed region, and recording the result
as a new immutable `scan_version`. It reuses the reconstruction pipeline's control plane
(`dev.chaya.api.pipeline`, docs/pipeline.md) end to end -- an incremental re-scan is a differently-shaped
pipeline run, not a separate system.

## What is regional, and what is not

| Step | Scope | How |
|---|---|---|
| Finding the changed region | **Not detected.** | The operator draws the polygon. Nothing compares the venue before and after to find what changed. |
| Capture, SfM, training, segmentation, cleanup | Region only | The run sees only the region's own media. |
| Alignment | Region against the parent's cloud, cropped to the polygon's box | Similarity (scale, rotation, translation) in canonical metres, gated ("ALIGNMENT"). |
| Geometry and appearance (`SPLAT_MERGED`) | Region only | Venue Gaussians in the region's volume are replaced; every other Gaussian is kept bit for bit and in order ("SPLICE"). |
| Planes, viewer asset (`.ksplat`) | **Whole floor**, regenerated | Derived from the merged cloud. Outside the region the input is unchanged; the outputs are new files. |
| Navigation | **Whole floor**, re-baked | Not a local re-bake (see "NAVIGATION"). Re-baked from the merged scene, then **verified** to match the parent scene outside the region. |
| Semantic index / POIs | Region only | Only AUTO_DETECTED POIs inside the polygon are superseded. |

So an incremental re-scan saves the capture and the reconstruction of everything outside the region, which is most of
the cost. It does **not** save the downstream derivations, which are cheap and run over the whole floor.

## The flow

1. **Select an existing venue version** -- `GET /venues/{v}/floors/{f}/scan-versions` lists a floor's
   `scan_version` history; the operator picks a `FINALIZED` one. Every full-venue run is a DRAFT version from the start
   and is published when it succeeds with a calibrated reconstruction (see "Publication"). One calibrated only after it
   succeeded is published with `POST /venues/{v}/floors/{f}/scan-versions/finalize-current`
   (`dev.chaya.api.rescan.RescanService#finalizeCurrent`), which also gives a run from before V28 its version. It does
   not fabricate one: the run must be calibrated (a canonical frame, else `409 NOT_CALIBRATED`) and must have published
   its own viewer asset and cloud (else `409 VERSION_INCOMPLETE`). See "VERSIONING".
2. **Select the changed region** -- a simple polygon (`{"points": [[x, y], ...]}`, >= 3 vertices) in canonical
   venue metres (x, y horizontal; [coordinate-frames.md](coordinate-frames.md)). The operator chooses it; the system
   does not detect change. What the splice then replaces is that polygon times the height range the re-scan observed. The parent version's reconstruction
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
SPLAT_RECONSTRUCTION -> SEMANTIC_SEGMENTATION -> GEOMETRIC_CLEANUP -> REGION_ALIGNMENT -> REGION_SPLICE ->
PLANE_FITTING -> ARTIFACT_GENERATION -> SEMANTIC_INDEXING -> [NAVIGATION_BAKING]
```

The first eight stages are identical to the full-venue plan, run against only the newly captured region's
media -- the worker never sees the rest of the venue's frames. `SEMANTIC_SEGMENTATION` labels the region
(review N-1): `GEOMETRIC_CLEANUP` cleans the labels with the cloud, and `REGION_SPLICE` splices them into the
venue's labels -- the control plane hands it the parent's `SEMANTIC_LABELS_CLEAN`/`_MERGED` from the same stage
run as the pinned cloud, as `GLOBAL_LABELS` -- with exactly the index sets it uses for the Gaussians, publishing
`SEMANTIC_LABELS_MERGED`. `NAVIGATION_BAKING` refuses to bake a splat without labels for exactly that cloud
(`NAVMESH_LABELS_UNAVAILABLE`), so a re-bake never drops the walls and furniture outside the region. `PLANE_FITTING`, `ARTIFACT_GENERATION`,
`SEMANTIC_INDEXING` and (when included) `NAVIGATION_BAKING` all run on `REGION_SPLICE`'s output (kind
`SPLAT_MERGED`) rather than the region alone, because they need the whole venue's up-to-date geometry, not
just the part that changed. They all choose their cloud through `chaya_worker.stages.base.venue_cloud`:

- `SPLAT_MERGED` wins; in a re-scan (the work order carries `regionGeometry`) a stage **refuses** to fall back to
  the region's own `SPLAT_CLEAN` (`INPUT_INVALID`), which would produce planes, a viewer asset, an index or a navmesh
  missing everything outside the region;
- the labels are always those of the chosen cloud (`SEMANTIC_LABELS_MERGED` for `SPLAT_MERGED`). PLANE_FITTING used to
  pair the merged cloud with the region's `SEMANTIC_LABELS_CLEAN`. Once SEMANTIC_SEGMENTATION joined the plan (review
  N-1), every re-scan would have failed there on the length mismatch.

`NAVIGATION_BAKING` is in brackets because whether it is in the plan **at all** is decided once, at step 4,
before the run is even created: it is included exactly when the parent version has a navmesh -- see "NAVIGATION".

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
| Navigation | **Whole floor**, re-baked from the merged scene whenever the parent version has a navmesh; never inherited. Verified against the parent scene outside the region (see "NAVIGATION"). | The new graphs are ingested as DRAFT, tagged with the version, and activated only when the version finalizes (`activateRescanGraphs`). |
| Semantic index / POIs | Only the region: SEMANTIC_INDEXING sees only the re-scan's frames, and only AUTO_DETECTED POIs inside the polygon are superseded. **MANUAL POIs are never touched.** | Only when the version finalizes (`applyRescanDetections`). A re-scan that fails or is rejected changes no POI (review V-6). A superseded POI is soft-deleted with `superseded_by_scan_version_id`, so the versions before the re-scan still show it. The version pins its own region-only DETECTED_OBJECTS next to the ones it inherits. |

## NAVIGATION

### Why the whole floor is re-baked

The navmesh is a single Detour tile. Recast's region partitioning (watershed) and polygonisation run over the whole
heightfield, so the polygons outside the region can change shape when anything inside it changes. Cutting the old
polygons along the region and splicing in new ones would leave portals that do not match across the cut. The bake is
cheap compared with reconstruction, so the re-scan re-bakes the **whole floor** from the merged scene. A tiled navmesh
(`dtTileCache`) would allow a real local rebuild. It is not implemented.

### When

Exactly when the parent version pinned a NAVMESH (`RescanService#parentHasNavigation`), wherever the region lies. The
earlier rule (re-bake only when a node of the floor's ACTIVE graph lies in the region, otherwise inherit the parent's
navmesh) was wrong in three ways:

- nodes are polygon centroids, so a region inside one large polygon held none of them;
- a region where furniture was removed has no walkable polygon yet;
- it read the floor's current graph, which may belong to another reconstruction or frame, not the parent version's.

In each case the new version routed on a navmesh baked for geometry it no longer had. A parent without a navmesh gives a
version without one.

### Proof that the venue outside the region survived

`NAVIGATION_BAKING` in a re-scan gets the parent's `GLOBAL_CLOUD` and `GLOBAL_LABELS` next to its own `SPLAT_MERGED`,
`SEMANTIC_LABELS_MERGED`, `PLANE_MODEL` and `SPLICE_REPORT`. Before Recast runs, it checks:

1. **The chain.** `SPLICE_REPORT` says REGION_SPLICE made exactly this `SPLAT_MERGED` (SHA-256) from exactly this
   `GLOBAL_CLOUD` and these `GLOBAL_LABELS`. The merged labels name the cloud they label (`cloud.sha256`), and
   `PLANE_MODEL` names the cloud it was fitted on (`source.splat.sha256`). Labels or planes of any other cloud are
   refused, even one with the same number of Gaussians (`NAVMESH_LABELS_UNAVAILABLE`, `INVALID_GEOMETRY`).
2. **The scene outside the region** (`chaya_worker.navmesh.compare_outside_region`). It builds the surface model, the
   grid Recast's input is made from, of both the parent scene and the merged scene, with the same settings and floor
   height. It then compares every cell more than 3 cells (0.3 m) outside the polygon: observed ground and its height,
   obstacle, step. A cell depends only on its 3 × 3 neighbourhood, and the splice keeps everything outside the polygon,
   so the two must be **identical**. Any difference means the inputs do not describe the venue the re-scan started
   from (corrupted or mismatched labels, the wrong parent cloud), and the bake is refused.

After the bake, it checks **the navmesh itself** (`obstacle_cells_under_navmesh`): no polygon of the new navmesh may
cover an obstacle cell outside the region.

Any failure is `NAVMESH_REGION_INCONSISTENT`, and nothing is published. On success, `NAVMESH_MANIFEST` and
`NAVIGATION_BAKING_REPORT` carry a `rebuild` block: `scope: FULL_FLOOR`, `incremental: false`, the region, the cell
counts compared and differing, the obstacle cells outside the region on each side, and the obstacle cells under the
navmesh. A full reconstruction's bake records `scope: FULL_FLOOR` and the whole-floor obstacle check, without enforcing
it.

This proves that the Recast **input** outside the region is unchanged. The polygons there may still be shaped
differently from the parent's navmesh, for the reason above. What stays the same is where an agent can and cannot
stand.

## VERSIONING

A `scan_version` is one reconstruction of one floor. It is created DRAFT and becomes FINALIZED (or, for a re-scan,
ALIGNMENT_REJECTED) exactly once; after that the row cannot be updated or deleted (`scan_version_guard`, V3/V19/V23).
Inserting a row that is not DRAFT is refused.

### What a version is made of (V23)

| What | Where | Written |
|---|---|---|
| the run it is | `pipeline_run_id` | at creation, with the run (`PipelineService#start` for a full run, `startIncrementalProcessing` for a re-scan; `finalize-current` for a run from before V28). Fixed once set. |
| its coordinate frame | `coordinate_frame_id` | at finalization: the ACTIVE canonical calibration of that run's reconstruction frame (for a re-scan, the parent's reconstruction, since the splice writes into it) |
| its artifacts | `scan_version_artifact` | at finalization (`ScanVersionService#finalizeVersion`), see below |

Finalization is refused by the database unless the frame is a calibration of the run's own reconstruction frame and the
version has pinned its own viewer asset (`KSPLAT`) and cloud (`SPLAT_MERGED` for a re-scan, `SPLAT_CLEAN` for a full
reconstruction). The service refuses earlier with `NOT_CALIBRATED` or `VERSION_INCOMPLETE`, and with
`VERSION_FRAME_MISMATCH` if one of the version's navigation graphs was baked in another frame.

`scan_version_artifact` pins exact `processing_artifact` rows:

- **own**: the latest published, non-PII `KSPLAT`, `ARTIFACT_MANIFEST`, `PLANE_MODEL`, `NAVMESH`, `NAVMESH_MANIFEST`,
  `NAVIGATION_GRAPH`, `DETECTED_OBJECTS` and cloud of the version's own run;
- **inherited** (re-scans only): the parent's `DETECTED_OBJECTS` (a re-scan's own detections cover only its region).
  Navigation is **never** inherited (V26): a version pins its own `NAVMESH`, `NAVMESH_MANIFEST` and `NAVIGATION_GRAPH`,
  all three or none, and a re-scan whose parent pinned a navmesh is finalized only with its own
  (`VERSION_INCOMPLETE` otherwise). The routing graphs bound to a version must be the ones baked with the navmesh it pins
  (`VERSION_INCONSISTENT`). The database refuses an inherited navigation pin and a finalization that breaks either
  rule. Versions finalized before V26 keep the pins they have.

Every derived document names the run, version and frame it was produced for (`source`:
`chaya_worker.stages.base.run_provenance`): `ALIGNMENT_REPORT`, `SPLICE_REPORT`, `SEMANTIC_LABELS_MERGED`, `PLANE_MODEL`,
`DETECTED_OBJECTS`, `NAVMESH_MANIFEST`, `NAVIGATION_GRAPH`, `NAVIGATION_BAKING_REPORT` (and `ARTIFACT_MANIFEST`'s
`sourceScanVersion`). The control plane refuses a `NAVIGATION_GRAPH` or `DETECTED_OBJECTS` that names another version,
or none, with `409 ARTIFACT_VERSION_MISMATCH` (`PipelineService#requireArtifactVersion`) -- for every run since V28, full
runs included (each is a version from the start).

`owner_version_id` names the version whose run produced each artifact. A trigger refuses a pin whose artifact is not
from the version's own run (when claimed as its own) or not one of the parent's pins (when claimed as inherited), and
refuses any pin, update or delete once the version is no longer DRAFT. A run's `processing_artifact` rows carry its
`scan_version_id` (V4's column; every run since V28), and an artifact produced for a version is only ever pinned as that
version's own (V28).

### What names its version

| Data | Column | Set |
|---|---|---|
| POI version | `poi_version.scan_version_id` | detected: the version whose run detected it, written when that version is published (a detection never exists without one: `poi_version_detected_has_version`, V28). MANUAL: the version the floor publishes (`floor.current_scan_version_id`); null while the floor publishes none, and bound when a version of that reconstruction is published. Set once (V23 relaxes the V5 guard for exactly that). |
| superseded POI | `poi.superseded_by_scan_version_id` | when a re-scan finalizes and replaces a detected POI in its region |
| navigation graph | `navigation_graph.scan_version_id` | at ingestion, the run's version (a graph of a versioned run naming anything else is refused, V28); a pre-V28 run's graphs when its version is published. Fixed once set. |
| AR anchor pose | `ar_anchor_pose` (`scan_version_id`, `coordinate_frame_id`, both poses, `calibrated_at`) | a new immutable revision on create, edit, and re-projection by a recalibration; `ar_anchor` is the head naming the current one (`current_pose_id`). The frame must be a calibration of the version's reconstruction. See "Publication". |
| splat / cloud / navmesh / detections | `scan_version_artifact` | at finalization (above) |

### Numbering and lineage

`version_number` is the floor's next number (`ScanVersionService#nextVersionNumber` locks the floor row;
`UNIQUE (floor_id, version_number)`), never `parent + 1`. Two re-scans of the same parent get two numbers, and a
DRAFT that never finalizes keeps its number (numbers are not reused). `parent_version_id` is the version a re-scan was
derived from. A bootstrapped full reconstruction has no parent: its geometry is not derived from any earlier version,
and a new reconstruction frame has no known relation to the old one (docs/coordinate-frames.md). V23 renumbered floors
whose numbers collided, in creation order, recording the old number in `provenance.renumberedFrom`.

### Reading one version

Everything that shows or routes on a version resolves it through `dev.chaya.api.rescan.ScanVersionService` and
refuses a version that is not FINALIZED (`409 VERSION_NOT_FINALIZED`):

| Endpoint | With a version |
|---|---|
| `GET /venues/{v}/floors/{f}/reconstructions` | only FINALIZED versions; each entry names its `scanVersionId`, `versionNumber`, `parentVersionId`, and `current` |
| `GET /venues/{v}/reconstructions/{runId}`, `GET /venues/{v}/scan-versions/{id}/reconstruction` | for a version's run: exactly its pinned artifacts, served from `/scan-versions/{id}/artifacts/{kind}`, and its recorded frame |
| `GET /venues/{v}/pois?scanVersionId=` | the version's POIs (`scan_version_poi_version`): placed against it or an ancestor, and not deleted, unless the deletion was a re-scan outside its lineage. Each POI is shown as it is in the version's frame; `frameStatus` is relative to that frame. |
| `POST /navigation/routes` with `scanVersionId` | on the graph of the navmesh the version pinned, in its frame, to the destination as it is in that version; single floor |
| `GET /venues/{v}/search?scanVersionId=` | only the version's POIs |
| `GET /venues/{v}/floors/{f}/anchors?scanVersionId=` | each anchor's pose in that version (`scan_version_anchor_pose`: entered against it or an ancestor, preferring one in its own frame), so a later recalibration never moves an anchor in an older version |

The web viewer (`ViewerWorkspace`) opens on the floor's current version (`initialVersion`), lets the user pick another
finalized one, and requests its POIs, routes and search results for that version. Every result is stored with the scene it was requested for and drawn only while that scene is on
screen (`lib/version-scope.ts`), so version N's model is never drawn with version N+1's frame, POIs or route, not even
between a switch and the next response.

### Publication: the floor's current version (V28)

`floor.current_scan_version_id` names the one FINALIZED version a floor publishes. It changes only by promotion
(`ScanVersionService#promote`), in one transaction:

1. the version is finalized if it is still DRAFT (`finalizeVersion`: pins, frame);
2. what its run produced before V28 is bound to it (graphs, POIs, and anchors in a frame of its reconstruction; an anchor
   gets a pose revision);
3. every ACTIVE graph of the floor is retired and the version's own graphs (one per profile) are activated -- none when
   it has no navigation, so an older version's graph is never routed on in its place;
4. the ACTIVE canonical frame of its reconstruction becomes the floor's frame (POIs and anchors are re-projected when it
   is a recalibration of the same reconstruction; anchors of another one are marked STALE);
5. the pointer is set.

Who promotes: a run that succeeds with a calibrated reconstruction is promoted by its last stage report
(`PipelineService#tryPromote`), after its detections are written as POIs naming the version (a re-scan's replacing the
region's). That happens inside a savepoint of the report's transaction: if anything refuses -- `VERSION_INCOMPLETE`,
`VERSION_FRAME_MISMATCH`, `PARENT_NOT_CURRENT`, `DETECTIONS_UNREADABLE`, a V28 check -- the savepoint is rolled back,
nothing of the promotion remains (no pin, no POI, no live graph, no frame change), the stage report is kept, and the run
ends `FAILED` with that code (retryable). A run that succeeds with an uncalibrated reconstruction stays a DRAFT version
(`scan_version.awaiting_calibration`) until `finalize-current` promotes it. A run that fails, is PARTIAL or CANCELLED
never publishes anything: its graphs stay DRAFT, its detections never become POIs, and calibrating its reconstruction does
not move the floor's frame (`CoordinateFrameService#calibrate` leaves the frame of a floor that publishes another
reconstruction alone).

A re-scan is promoted only over its own parent (or onto a floor that publishes nothing): a re-scan of an older version
would silently drop what was published since (`409 PARENT_NOT_CURRENT`). A full reconstruction replaces whatever is
current. An ALIGNMENT_REJECTED version is never promoted.

The database enforces the end state, checked at commit (`DEFERRABLE INITIALLY DEFERRED` constraint triggers;
`promote` runs them early with `SET CONSTRAINTS ... IMMEDIATE` so a refusal stays inside the savepoint):

- the pointer names a FINALIZED version of that floor with a recorded frame, and is never cleared;
- a floor's ACTIVE graphs are exactly its current version's (none while it publishes nothing);
- the floor's frame is a calibration of its current version's reconstruction;
- a live anchor that has a version names its current pose, and its head columns are exactly that pose; pose revisions are
  write-once (only their first calibration may be recorded).

What readers call current, without a version:

| Endpoint | Current means |
|---|---|
| `GET .../floors/{f}/reconstructions/latest` | the floor's current version (404 while it publishes none); never merely the newest run |
| `GET /venues/{v}/pois` | POIs placed against their floor's current version or an ancestor (`poi_version_is_current`); a staff POI on a floor that has never published a version also counts. `current` on each POI; `?all=true` (staff) lists the others too |
| `GET /venues/{v}/search` | the same POIs |
| `POST /navigation/routes` | each floor's current version: its ACTIVE graph (which the database guarantees is that version's), a destination and landing POIs of that version (`409 VERSION_MISMATCH` otherwise); `routingSources[].scanVersionId` names it. A floor that publishes nothing is `NAVMESH_NOT_READY`. |
| `POST .../anchors`, `PUT .../anchors/{id}`, `.../calibrate` | the pose is entered against the current version (`409 NO_CURRENT_SCAN_VERSION` without one) |
| `POST .../anchors/relocalize` | only anchors entered against the current version or an ancestor (`ANCHOR_VERSION_MISMATCH`; a pre-V28 anchor that never had a version: `ANCHOR_UNVERSIONED`); a request naming another `scanVersionId` than the current one is `409 VERSION_MISMATCH`. The response names the version. |

A public link (`PUBLIC_VIEWER`) is only ever served a FINALIZED version's reconstruction.

### A re-scan's lineage record

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

The parent's cloud (`GLOBAL_CLOUD`) is the cloud the parent version pinned, not any cloud of the same scan or run
(review V-8).

### Not addressed

- Storage is still mutable (review S-2): a version pins artifacts by object key, and nothing re-verifies their bytes.
- A capture with no floor produces a run with no version; it can never publish anything.
- Anchors from before V28 whose tag could not be tied to a frame of their version's reconstruction lost the tag (a
  recalibration had moved their pose); they cannot be used for relocalization until their pose is entered again.
- Detected POIs written before V28 without a version are never current; publishing their run's version binds them.
- Versions finalized before V23 get their run from `provenance.runId` and their frame from the calibration in force at
  `finalized_at`; one whose frame was never recorded stays without one (the constraint is `NOT VALID` for old rows) and
  cannot be viewed as a version (`VERSION_INCOMPLETE`); the viewer lists its run as a reconstruction that is not a
  version. Their pins are their own run's artifacts only; inheritance is not
  reconstructed, and POIs they superseded before V23 were only soft-deleted, so older versions no longer show them.
- A manual deletion removes a POI from every version, not only from the newer ones.

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
| Splice: overlapping-geometry replacement, moved furniture replaced, every Gaussian outside the region kept bit for bit (all attributes, in order), re-scan beyond the polygon, unrelated geometry kept, no-op, visible seam refused, canonical-frame volume, exact index sets | `tests/unit/test_region_splice.py` | nothing |
| Downstream stages use the merged cloud with its own labels, never the region alone; the outside-region surface comparison (unchanged, change inside, sofa removed / relabelled / new obstacle outside, grown extent, margin); obstacle cells under a navmesh | `tests/unit/test_rescan_consistency.py` | nothing |
| Scale mismatch through the orchestrator (re-scan reconstruction at 2 units/m, parent at 0.5): a calibration 4 % off is corrected, 20–25 % off is `ALIGNMENT_REJECTED` with the scale gate's value and threshold, nothing published | `tests/orchestration/test_rescan_stages.py` | nothing |
| Re-bake with the real Recast tool as a re-scan work order runs it: a new crate inside the region blocks; the sofa, wall, column and step outside it survive; `rebuild` block; refused when an obstacle outside the region is lost, when given the region alone, labels or planes of another cloud, a parent cloud the splice did not use, or no parent scene | `tests/navmesh/test_venue_stage.py` | chaya-navmesh |
| Both stages through the orchestrator, in non-identity frames: successful merge, rejected merge (nothing published), no-op re-scan, Open3D requirement, uncalibrated re-scan | `tests/orchestration/test_rescan_stages.py` | nothing |
| Real FPFH matching feeding the estimator | `tests/gpu/test_region_alignment.py` | Open3D (skipped without it; run in the `chaya-bench-open3d` image) |
| Control plane: worker rejection, control-plane rejection, parent untouched, rejected version immutable and not retryable, full lineage on success, POIs and viewer only on finalization, MANUAL POIs survive; the re-bake decided by the parent version's navmesh (not the floor's graph, not where the region lies); NAVIGATION_BAKING given the parent's cloud and labels; a re-scan publishing no navmesh is not finalized; graphs naming another version or none refused | `RescanControlPlaneTest`, `RescanServiceTest` | Testcontainers |
| Version integrity in the database: created DRAFT only, finalization needs run, frame and own KSPLAT and cloud, pins only from the own run or the parent, navigation never inherited and pinned all three or none, nothing changes after finalization, numbers unique per floor | `ScanVersionImmutabilityTest` | Testcontainers |
| Publication (V28) through the worker protocol: a run is a DRAFT version from the start and nothing of it is current until it succeeds; a new scan that fails leaves the published version, its graphs, POIs, frame and routes untouched (the floor's frame does not move when the new reconstruction is calibrated); a refused promotion (no viewer asset) publishes nothing and fails the run; a successful one switches graphs, POIs, frame and pointer at once, and routes, POI lists, search and relocalization never mix versions; an anchor as of its version keeps its original pose after a recalibration; artifacts that name no or another version are refused; the database refuses a version-less or mixed publication; a re-scan of an older version is not published over a newer one | `ScanVersionPublicationTest`, `AnchorServiceTest`, `SemanticSearchRankingTest` | Testcontainers |
| Versions end to end over HTTP: v1 (full run, real Recast navmesh fixture, bootstrap), v2 (re-scan over the graph), v3 (re-scan away from every graph node, which still re-bakes); lineage and numbering, the run of every pinned artifact, the viewer switched between the three (model bytes, frame, POIs, routing graph), refusals for a DRAFT version and another venue | `ScanVersionLineageTest` | Testcontainers |
| The viewer never uses one scene's POIs, route or model for another | `apps/web/lib/version-scope.test.ts` | nothing |

B5 (docs/BENCHMARKS.md) measures the FEATURE_SIMILARITY path on a real COLMAP cloud with known similarity misalignments.

**Not validated:** no real venue has been re-scanned and merged. Every number above is from synthetic clouds, or from a
real SfM cloud with simulated misalignment. The outside-region check has only met synthetic scenes; on a real venue its
exact-equality test is still exact (the splice copies the Gaussians), but its 0.3 m margin has not been checked against
real label noise at the polygon's edge.
