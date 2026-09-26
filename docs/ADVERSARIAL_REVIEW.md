# Chaya 02: adversarial technical review

**Date:** 2026-09-25
**Commit reviewed:** `cf92005` (clean working tree)
**Reviewer stance:** hostile senior engineer and technical judge. This review looks for reasons to reject or distrust the
implementation. It does not list strengths.
**Method:** I read the source, not the plans. Every finding cites the file (and line where useful) that shows the problem.
Where a finding depends on an external library's behaviour that I could not run here, it says **needs verification** and
names the test that would settle it.

> **Status update (coordinate-frame correction; `.ksplat` format).** The findings below remain as written against
> `cf92005`. Since then two things have been implemented: the coordinate-frame model in
> [coordinate-frames.md](coordinate-frames.md), and a `.ksplat` encoder verified against the pinned viewer library.
> The table says what each changes.
>
> - "Addressed" means implemented and tested with synthetic mathematical fixtures. **None of the coordinate work has
>   been validated on a real capture.**
> - The `.ksplat` result is proven with a tiny fixture cloud. It has not been exercised with a real reconstruction's
>   splat.
>
> | Finding | State |
> |---|---|
> | R-2 no metric scale / gravity | Addressed. Calibration comes from measured distances or control points, gravity from the reconstructed floor plane, operator floor points or control points. Metric stages fail `NOT_CALIBRATED` without it. Pre-calibration cleanup and RANSAC thresholds are scale-invariant (spacing multiples; untuned). |
> | D-1 spatial data not bound to a frame | Addressed. POIs, anchors and navigation graphs record their frame. Recalibrating the same reconstruction re-projects POIs and anchors; a different reconstruction makes them STALE, and routing and relocalization refuse them. |
> | V-1 rigid registration across SfM scales | Addressed in code: the region is pre-scaled by its own calibration, registration refines scale, and a correction beyond 10 % fails. The Open3D test for it was **not executed** in this environment. |
> | V-2 splice appends the whole region | Addressed. Region Gaussians outside the polygon are discarded; the polygon is tested in canonical x/y. |
> | V-3 rescan indexing mixes frames | Addressed. Cameras are re-expressed in the parent reconstruction frame with REGION_ALIGNMENT's similarity. |
> | N-4 step-free slope in an arbitrary frame | Partially. Slope is now measured against canonical +Z, and obstacles are filtered by height band. The single-floor-plane input remains. |
> | N-5 multi-floor in unrelated frames | Addressed. Both floors must be registered to one surveyed venue datum, otherwise `FLOORS_NOT_REGISTERED`. Connectors are still matched by proximity within that datum. |
> | AR-3 relocalization | Partially. One anchor gives `residualMeters: null`; observations are capped and must be distinct; a gravity-tilt check is added. `physicalPose` is still unused. |
> | AR-4 anchors never invalidated | Addressed. Anchors are bound to the floor's current frame and become STALE when a different reconstruction becomes current. |
> | R-1 `.ksplat` layout (**confirmed**: the pinned library could not load the old files, `RangeError: Invalid typed array length: 4096`) | Fixed. The encoder writes the level-0 layout of @mkkellogg/gaussian-splats-3d 0.4.7. This is proven by `apps/web/lib/ksplat-compat.test.ts`, which uses the real `KSplatLoader` plus a cross-check against the library's own PLY loader, and by `apps/web/e2e/ksplat-viewer.spec.ts`, where the real viewer in Chromium loads the production bytes. See docs/pipeline.md, "Viewer asset format". |
> | T-2 viewer test never delivers bytes | Fixed by `e2e/ksplat-viewer.spec.ts`. It exposed a second defect on the same path: the viewer called `Viewer.getSplatCount()`, which 0.4.7 does not have, so no scene could ever reach "loaded". It now uses `getSplatMesh().getSplatCount()`. |
> | N-1 `recast-cli` is fictional | Fixed. `recast-cli` is gone. NAVIGATION_BAKING now runs chaya-navmesh (`services/reconstruction/native/chaya-navmesh`), a CLI linked against the real recastnavigation 1.6.0 (pinned by SHA-256 in CMake) that runs Recast's full solo-mesh build and Detour's path query. The worker image and the `python` CI job build it, and CI fails if the real-tool tests skip. A tiny real mesh fixture is baked and routed on by the real library (Windows and Linux), and the API ingests and routes on the real output. Routes need a navmesh-backed graph (`NAVMESH_NOT_READY` otherwise). **No real reconstructed venue has been baked.** See docs/navigation.md. |
> | N-2 convex-hull walkable surface | Partially. The floor is an occupancy grid of observed floor cells, and obstacles are solid boxes Recast erodes around. Tested with a synthetic L-shaped floor. The single-floor-plane input remains, so ramps and stairs are still absent from the input. |
> | N-3 clearance | Partially. Clearance is now Detour's own portal length rather than vertex matching, so it is never an unknown 0. It is still the radius-eroded portal, not the corridor width. |
> | Everything else (AR-1, AR-2, security, ops, …) | Unchanged. |

Severity scale:

| Severity | Meaning |
|---|---|
| **CRITICAL** | The feature cannot work as claimed, or a security/privacy property the system advertises does not hold. Blocks release. |
| **HIGH** | Wrong results, data loss/corruption or a real security gap under realistic conditions. |
| **MEDIUM** | Incorrect behaviour in edge cases, operational fragility, misleading output. |
| **LOW** | Hygiene, drift or latent risk. |

---

## 0. Verdict

**Reject as a digital-twin navigation product. Accept, with fixes, as a well-built control plane.**

The Spring control plane is real and careful: tenant scoping, composite foreign keys, compare-and-set job transitions,
artifact prefix validation, and audit logging. It would survive review.

Nothing downstream of `POSE_ESTIMATION` has ever run on real data. The repository's own
[E2E_VALIDATION.md](E2E_VALIDATION.md) confirms this. Once you read that code instead of its docstrings, it has
design-level defects that no GPU will fix:

1. **There is no metric scale and no gravity-aligned frame anywhere.** COLMAP/GLOMAP output has an arbitrary scale,
   origin and orientation. Every downstream consumer still assumes metres and a fixed "up" axis: navmesh agent radius,
   `length_m`, `min_clearance_m`, route duration, blocked regions, the rescan crop margin, alignment voxel size, AR
   anchor poses and multi-floor matching. Those assumptions also disagree with each other: Recast is y-up, the slope
   code is z-up, and the polygon crops use x/y.
2. **Every reconstruction run creates a new, unrelated coordinate frame.** Nothing re-registers POIs, AR anchors or
   navigation graphs when a floor is reconstructed again. A second full reconstruction silently leaves all manual
   POIs and calibrated anchors pointing into the wrong space.
3. **Incremental rescan uses rigid (no-scale) registration between two independent SfM reconstructions.** Those
   reconstructions have different, arbitrary scales, so the alignment cannot converge on a correct answer.
4. **The `.ksplat` encoder almost certainly does not produce files the pinned viewer library can read.** Its layout
   does not match GaussianSplats3D's format (header size and rotation encoding), and it has only been tested against
   its own decoder.
5. **The AR client is fake.** The web client treats any WebXR hit-test surface as a fiducial detection and sends the
   wrong anchor id. It never renders anything. The iOS client throws away which anchor it detected and drops to
   "tracking lost" on the first frame.
6. **Navigation baking depends on `recast-cli`, a binary that does not exist.** The code defines its command-line and
   JSON interface itself. No such tool is built, vendored or referenced anywhere.

`PROJECT_PLAN.md` marks M9, M10, M11, M12 and M13 **"Done"**. Measured against the code, that is misleading.

---

## 1. Feature classification

| Feature | Classification | Basis |
|---|---|---|
| Keycloak JWT auth, roles, service accounts | **REAL** | `security/*`; issuer/audience/signature validation; tests against real Keycloak (`KeycloakLiveTest`) |
| Tenant isolation (org/venue) | **REAL** | `TenantGuard`, composite FKs in `V2`–`V16`, `TenantApiIsolationTest` |
| Public viewer links | **REAL** (with gaps S-4, S-5, S-6) | `PublicViewerService` |
| Chunked upload, Tika, ClamAV | **REAL** | `MediaService`, `MediaValidationService`, `ClamAvLiveTest` |
| Job engine: leases, retry, time box | **REAL** | `PipelineService`, `ProcessingJobRepository` |
| Time-boxed **PARTIAL** reconstruction | **INTERFACE ONLY** | No stage ever emits `partial=true` (R-5) |
| Input validation / FFmpeg / frame quality | **REAL** | Ran in E2E |
| Privacy preprocessing | **PARTIALLY REAL** | Runs, but uses Haar cascades: 55 false "faces" on a building, recall on people never measured (CV-6) |
| Pose estimation (COLMAP/GLOMAP) | **REAL** | Ran in E2E on 11 frames |
| Gaussian splat training | **PARTIALLY REAL** | Real gsplat loop, but no densification, a colour-space bug (R-3), ignores the time budget, and has **never executed** |
| `.ksplat` export | **FAKE / MISLEADING** | Self-invented layout, validated only against its own decoder (R-1) |
| Digital twin viewer | **PARTIALLY REAL** | Real Three.js/GaussianSplats3D wiring. **Never loaded a pipeline-produced asset**; Playwright stubs the download (T-2) |
| Semantic segmentation, cleanup, plane fitting | **PARTIALLY REAL** | Real library calls, **never executed end to end** |
| Object detection → POIs | **PARTIALLY REAL** | Real Grounding DINO/CLIP; 3D localization ignores occlusion, clustering merges unrelated objects (CV-1, CV-2) |
| Semantic search | **PARTIALLY REAL** | Works for manual POIs (text-to-text). Detected objects are practically unreachable in the mixed ranking (CV-4) |
| Navmesh baking | **INTERFACE ONLY** | `recast-cli` does not exist (N-1) |
| Single-floor routing (Dijkstra) | **REAL** over hand-inserted graphs; **no real graph has ever existed** | `RouteService`, B4 is synthetic |
| "Accessible" (STEP_FREE) routing | **FAKE / MISLEADING** | Unknown clearance passes the filter; slope is computed in an arbitrary frame (N-3, N-4) |
| Multi-floor routing | **FAKE / MISLEADING** | Compares x/y across floors that share no coordinate frame (N-5) |
| Dynamic obstacle avoidance | **PARTIALLY REAL** | Removes nodes, not edges crossing the region (N-6) |
| Incremental rescan | **INTERFACE ONLY** (broken by design) | Rigid alignment across SfM scales; splice duplicates geometry (V-1, V-2) |
| Scan versioning | **PARTIALLY REAL** | FINALIZED immutability is real. Lineage numbering is broken, and the viewer ignores versions (V-3, V-4, V-5) |
| AR relocalization math (server) | **PARTIALLY REAL** | Pose composition is right; `physicalPose` is dead; single-anchor residual `0.0` is misleading (AR-3) |
| Android WebXR AR | **FAKE / MISLEADING** | Hit-test treated as marker detection; no render layer; no route (AR-1) |
| iOS ARKit AR | **INTERFACE ONLY** | No app, never compiled for iOS, detected anchor discarded (AR-2) |
| Capture path planner, HUD | **PARTIALLY REAL** | Real algorithm, validated only on synthetic floor plans (per docs/BENCHMARKS.md) |
| Operations dashboard | **REAL** | Reads real tables. Roles are not venue-scoped (S-8) |
| Monitoring / alerting | **PARTIALLY REAL** | Alerts exist; no alert for PII purge failures, stuck validation or backups (O-3) |
| Backups | **PARTIALLY REAL** | Same host, no off-host copy, Keycloak users not backed up (O-1) |
| Organization management API | **UNIMPLEMENTED** | Orgs created in SQL (E2E F3) |
| Data erasure | **UNIMPLEMENTED** | Admitted in `docs/backup-recovery.md` |
| Redis | **UNIMPLEMENTED** (provisioned, unused) | E2E F4 |
| OpenAPI contract | **STALE** | Most feature endpoints missing (D-6) |

---

## 2. Findings

Each finding lists: **Severity · File · Problem · Why it matters · Exact fix · Test required.**

### 2.1 Reconstruction (R)

#### R-1 · CRITICAL · `.ksplat` encoder layout does not match the viewer's format (needs verification against the library)
- **File:** `services/reconstruction/chaya_worker/ksplat.py:42` (`FILE_HEADER_SIZE = 32`), `:52` (record offsets `[0, 12, 24, 28]`), module docstring.
- **Problem:** The encoder writes a 32-byte file header and 44-byte records laid out as pos (12) + scale (12) +
  **uint8** rotation (4) + uint8 colour (4) + 12 bytes of zero padding. As far as I know, the uncompressed (level 0)
  format in `@mkkellogg/gaussian-splats-3d` has a **4096-byte** main header, and its 44 bytes per splat are
  12 + 12 + **16 (float32×4 rotation)** + 4 (colour). If so, the viewer reads the header wrongly, reads rotations from
  uint8 + colour + padding bytes as floats, and reads colour from the zero padding (black, fully transparent). The
  docstring contradicts itself ("rot(uint8x4) … 12 pad-to-original…"). The only test is a round trip through this
  module's own `decode()`.
- **Why it matters:** This is the one asset the whole product displays. The docstring says "the viewer can load
  directly", yet no pipeline-produced file has ever been opened by the real loader.
- **Exact fix:** Delete the hand-rolled format. Either (a) write a standard 3DGS `.ply` and convert it with the
  library's own `PlyLoader` → `SplatBuffer` → `KSplatLoader.downloadFile` path in a Node build step, or (b) copy the
  exact constants from the pinned library version (`SplatBuffer.HeaderSizeBytes`, `CompressionLevels[0]`) and cite
  the version.
- **Test required:** A Node test in `apps/web` that loads a `.ksplat` produced by `chaya_worker.ksplat.write_ksplat`
  with the pinned `@mkkellogg/gaussian-splats-3d` `KSplatLoader` and checks splat count, one known position, one known
  rotation and one known colour.

#### R-2 · CRITICAL · No metric scale and no gravity alignment anywhere in the pipeline
- **Files:** `stages/pose_estimation.py` (output used as-is), `stages/splat_reconstruction.py`, `navmesh.py:234-243`
  (z-up), `navmesh.py:172-182` (writes raw world coords to Recast, which is y-up), `stages/region_alignment.py:85-88`
  (`margin = 1.0  # meters`, crops on x/y), `settings.py:95,97,100,114`
  (`object_cluster_distance … # scene units`, `navmesh_agent_radius`, `alignment_voxel_size_m`),
  `V5__poi.sql` ("metres"), `RouteService.java:225,381,430-434`.
- **Problem:** SfM reconstructions have an arbitrary similarity gauge: scale, rotation and origin. No stage estimates
  metric scale (from a fiducial of known size, IMU/ARKit poses, or a user-entered measurement) or gravity (from IMU,
  or plane fitting followed by an explicit rotation into a canonical up-axis frame). All numbers labelled `_m` are in
  arbitrary units. The code also uses three different "up" conventions: Recast (y), `polygon_slope_degrees` (z), and
  crops and route boxes (x/y horizontal).
- **Why it matters:** Route distances and durations, clearance thresholds, agent radius, cluster distance, alignment
  voxel size and AR anchor translation are all meaningless. Accessibility guarantees are therefore meaningless.
- **Exact fix:** Add a mandatory `SCALE_AND_GRAVITY_ALIGNMENT` stage directly after `POSE_ESTIMATION`. Estimate up
  from the dominant floor plane (or device gravity in capture metadata) and scale from a known-size fiducial or capture
  device odometry. Rewrite `SPARSE_MODEL`/`POSES` into a canonical frame (metres, +Z up), and fail the run when scale
  cannot be established. Record the transform and its uncertainty in provenance. Then use one up-axis constant
  everywhere, and transform before calling Recast (swap Y/Z).
- **Test required:** A synthetic scene rendered at a known scale and rotation, run through pose → alignment stage.
  Assert the recovered scale error is below 2% and the up-axis error below 2°. Also a navmesh test that feeds a tilted
  input and asserts STEP_FREE rejects a real 30° ramp and accepts a flat floor.

#### R-3 · HIGH · Splat colour is trained in one colour space and exported in another
- **File:** `stages/splat_reconstruction.py:151,180,200`
- **Problem:** Colours are initialised as SH-DC coefficients `(rgb-0.5)/SH_C0`. They are rendered during training as
  `torch.sigmoid(colors)` (the `shape[-1] == 3` condition is always true), then exported as `colors_dc`, which viewers
  interpret as `0.5 + SH_C0 * f_dc`. The optimiser learns `f` such that `sigmoid(f)` matches the image, but the viewer
  shows `0.5 + 0.282*f`. For example, a target of 0.8 becomes about 0.89, and 0.2 becomes about 0.11.
- **Why it matters:** Exported colours systematically disagree with what training fitted: contrast distortion and
  clipping.
- **Exact fix:** Render with `SH_C0 * colors + 0.5` (clamped), or pass `sh_degree=0` with colours shaped
  `[N, 1, 3]` so gsplat evaluates SH itself. Drop the sigmoid.
- **Test required:** Unit test: train 200 iterations on a single-colour synthetic scene, export, decode, and assert the
  mean exported RGB is within 0.02 of the target.

#### R-4 · HIGH · No densification: Gaussian count is frozen at the SfM point count
- **File:** `stages/splat_reconstruction.py:1-15, 153-157`
- **Problem:** Admitted in the docstring. Sparse SfM clouds (typically 10³–10⁵ points) cannot represent indoor
  surfaces. This is not "3D Gaussian Splatting" in the paper's sense.
- **Why it matters:** The core deliverable, a photoreal twin, will look like sparse blobs. The "Done" status of M9 is
  misleading.
- **Exact fix:** Use gsplat's `DefaultStrategy` (clone/split/prune on gradient thresholds) or its `simple_trainer`
  reference loop instead of a hand-rolled one.
- **Test required:** A GPU-marked test on a public indoor dataset (e.g. a Mip-NeRF 360 room) asserting held-out PSNR
  above a stated floor.

#### R-5 · HIGH · The "PARTIAL" time-boxed result path is dead code; training ignores the time budget
- **Files:** `PipelineService.java:467-472`, `PipelineDefinition.java:394`, `stages/splat_reconstruction.py:170-193`
- **Problem:** `partialOutput` requires an artifact with `partial=true` of kind `SPLAT`/`SPLAT_PARTIAL`. No worker code
  ever sets `partial=True` (searching the worker finds none). The training loop checks `ctx.cancelled` but never
  `ctx.deadline`. The runner's deadline only bounds subprocesses, and training is in-process. An overrun is killed by
  the enforcer (`TIME_LIMIT_EXCEEDED` after grace) and **everything is discarded**.
- **Why it matters:** The documented "PARTIAL quality" behaviour never happens. Long jobs burn GPU and produce nothing.
- **Exact fix:** In the loop, check `ctx.remaining()` every N iterations. When below a checkpoint margin, write the
  current cloud as `SPLAT_PARTIAL` with `partial=True` and raise `TimeLimitExceeded` carrying the artifacts. Make the
  orchestrator upload artifacts attached to a `TimeLimitExceeded`.
- **Test required:** Orchestration test with a stub rasteriser and a 2 s deadline. Assert the report is FAILED /
  `TIME_LIMIT_EXCEEDED` with a `SPLAT_PARTIAL` artifact, and that the control plane marks the run `PARTIAL`.

#### R-6 · MEDIUM · All training frames held in RAM as float32
- **File:** `stages/splat_reconstruction.py:137-143`
- **Problem:** 300 frames at 1920×1080 is about 7.5 GB of host RAM before CUDA copies.
- **Why it matters:** OOM on realistic captures. The E2E toolchain worker had a 2 GiB limit.
- **Exact fix:** Keep uint8 on disk or in memory, decode and upload per iteration (or cache on GPU as uint8), and
  downscale to a configured training resolution.
- **Test required:** A memory-bounded test (e.g. `resource.setrlimit`) with 300 synthetic 1080p frames asserting the
  stage stays under a budget.

#### R-7 · MEDIUM · COLMAP sub-model selection is arbitrary
- **File:** `stages/pose_estimation.py:320-323` (`model = models[0]`)
- **Problem:** When mapping produces several disconnected models, the first directory is taken, not the one with the
  most registered images.
- **Why it matters:** Silently drops most of the venue on a fragmented capture.
- **Exact fix:** Pick the model with the most registered images (parse each `images.bin`) and record the discarded
  models in the report.
- **Test required:** Unit test with two fake model directories of different sizes.

#### R-8 · MEDIUM · Worker-reported artifact checksums are never verified by the control plane
- **Files:** `PipelineService.java:573-584` (`verifyStored` checks size only), `ReconstructionController.java:66-73`
  (streams without checking).
- **Problem:** `checksum_sha256` is whatever the worker claimed. Combined with S-2 (the worker account can overwrite
  any derived object), stored objects can differ from recorded provenance and nobody notices.
- **Why it matters:** "Provenance" and "write-once artifacts" are claims the system does not enforce.
- **Exact fix:** Verify SHA-256 server-side before accepting a report (stream once; S3 `ChecksumSHA256` on upload
  makes this cheap). Enable bucket versioning plus object lock on `derived` and store the version id in
  `processing_artifact`, then serve that exact version.
- **Test required:** Integration test that uploads an object, reports a wrong sha256, and expects 409
  `ARTIFACT_CHECKSUM_MISMATCH`. A second test overwrites a served object and asserts the viewer endpoint still returns
  the original bytes (pinned version).

### 2.2 Computer vision and search (CV)

#### CV-1 · HIGH · Detection → 3D position ignores occlusion
- **File:** `stages/semantic_indexing.py:36-45`, `stages/semantic_segmentation.py:34-45`
- **Problem:** Every splat centre that projects inside the 2D box and is in front of the camera counts, including the
  wall behind the object and anything behind that. The median of that set is usually the background, not the object.
  There is no depth buffer or visibility test.
- **Why it matters:** POI positions, and therefore navigation destinations and AR overlays, are systematically wrong.
- **Exact fix:** Render a depth map per frame (gsplat returns depth with `render_mode="RGB+ED"`). Keep only points
  whose camera-space depth is within ε of the rendered depth at their pixel, then take the median of the front-most
  cluster.
- **Test required:** Synthetic scene with an object in front of a wall. Assert the recovered position is within
  0.1 × object size of the object's true centroid, not the wall's.

#### CV-2 · HIGH · Clustering merges unrelated objects and averages their embeddings
- **File:** `stages/semantic_indexing.py:48-90`
- **Problem:** Detections within 0.75 "scene units" are merged "regardless of label". A fire extinguisher next to an
  exit sign becomes one POI, and its embedding is the mean of two unrelated CLIP vectors.
- **Why it matters:** Loses safety-relevant objects and corrupts search embeddings.
- **Exact fix:** Cluster per label (or per embedding cosine > τ **and** distance < d). Use an object-size-relative
  distance after scale is fixed (R-2).
- **Test required:** Unit test: two detections 0.3 apart with labels "exit sign" and "fire extinguisher" must yield
  two objects.

#### CV-3 · MEDIUM · Model outputs ingested without validation or confidence gating
- **File:** `PipelineService.java:715-743`
- **Problem:** A missing confidence defaults to `0.0` and is stored as if measured. Coordinates are not checked for
  finiteness or plausibility. Embedding dimension is not checked against `vector(512)`: a mismatch throws inside the
  report transaction, rolls back the whole stage report and turns it into a 500. There is no minimum confidence at
  ingestion. `scan_version_id` is never set on auto-detected POIs.
- **Why it matters:** Garbage-in becomes permanent POIs. One malformed object fails the entire stage report.
- **Exact fix:** Validate each object (finite xyz, confidence present and in [0, 1], `len(embedding) == 512`, model id
  equal to the configured search model) and skip invalid ones with a counted reason. Apply `min_detection_confidence`.
  Set `scan_version_id`/frame provenance.
- **Test required:** Control-plane test with a `DETECTED_OBJECTS` document containing one NaN position, one 768-dim
  embedding and one valid object. Expect the stage SUCCEEDED, 1 POI created, and 2 rejections recorded in the audit
  metadata.

#### CV-4 · HIGH · Search ranks text-to-text and text-to-image similarities on one scale
- **File:** `SemanticSearchService.java:65-72, 160-165`
- **Problem:** Manual POIs use CLIP **text** embeddings (query similarity about 0.8), and detected POIs use CLIP
  **image** embeddings (about 0.2–0.3). The class comment admits the scales differ, but the SQL still does
  `ORDER BY similarity DESC` across both. Detected objects essentially never reach the top-k when any manual POI
  exists. When no manual POIs exist, detected objects are **never filtered**, so every query returns something.
- **Why it matters:** The flagship "find anything the scan saw" feature is dominated by whatever staff typed in.
- **Exact fix:** Rank each source separately, normalise per source (z-score against the per-source distribution
  computed in the query, or calibrated per-source thresholds), then merge. Calibrate a threshold for image embeddings
  on a labelled venue. Filter by `embedding_model = <current model>`.
- **Test required:** Search integration test with one manual "sofa" POI and one detected couch (image embedding from
  a real CLIP run on a fixture crop). Query "couch" and assert the detected couch ranks first or is returned.

#### CV-5 · MEDIUM · Search does not enforce the model that produced each embedding
- **File:** `SemanticSearchService.java:65-68`
- **Problem:** The query embedding comes from `services/vision`; POI embeddings come from the worker or the backfill.
  `embedding_model` is stored but not used as a filter.
- **Why it matters:** A config drift (different CLIP checkpoint) silently turns search into noise.
- **Exact fix:** `AND v.embedding_model = :currentModel`, and report POIs pending re-embedding in ops.
- **Test required:** Insert a POI with `embedding_model='other'`; assert it is excluded from vector search and counted
  as stale.

#### CV-6 · MEDIUM · Privacy detection is classical CV with unmeasured recall on people
- **File:** `privacy/detectors.py:67-110`, E2E finding F5
- **Problem:** Haar cascades miss small, turned, occluded and back-of-head faces. Measured precision on a building:
  55 false faces in 11 frames. Recall on people: never measured. Reconstructions built from these frames are served to
  **anonymous public links**.
- **Why it matters:** The privacy claim is untested in the direction that matters (missed people).
- **Exact fix:** Use a modern detector (e.g. RetinaFace/YOLO-face plus a person segmenter for full-body masking) and
  publish recall on a labelled indoor set. Block public-link serving for runs where the privacy report's detector
  version is below the audited baseline.
- **Test required:** A labelled fixture set with people at several distances and poses, asserting recall ≥ a stated
  target.

### 2.3 Navigation (N)

#### N-1 · CRITICAL · `recast-cli` is a fictional dependency
- **Files:** `navmesh.py:185-206` ("this project's recast-cli is expected to accept…"), `stages/navigation_baking.py:40,79-95`,
  `services/reconstruction/Dockerfile`, `infra/ci/worker-colmap.Dockerfile`.
- **Problem:** The code invents the CLI flags (`--cellSize` …) and the JSON output schema (`polygons[].vertices/neighbors`).
  No Dockerfile builds it, no submodule vendors it, and no upstream Recast tool with that interface is referenced.
  Tests only check argv construction and parse hand-written JSON.
- **Why it matters:** Navigation is **INTERFACE ONLY**. PROJECT_PLAN M13 "Done" is false.
- **Exact fix:** Vendor recastnavigation (zlib) and build a small C++ CLI in-repo (`tools/recast-cli`) that implements
  exactly the contract in `navmesh.py`, with a Dockerfile stage. Or use `recast-navigation-js`/`pyrecast` bindings in
  process.
- **Test required:** CI job that builds the tool, bakes a known L-shaped floor OBJ, and asserts polygon count > 0, that
  the polygon union area is within 5% of the true walkable area, and that no polygon crosses the inner corner.

#### N-2 · HIGH · Walkable surface is the convex hull of floor-plane inliers
- **File:** `navmesh.py:149-155` (Delaunay over all floor points), `:158-169` (carving by centroid distance only),
  `stages/navigation_baking.py:50-58`
- **Problem:** Delaunay triangulates the convex hull, so L-shaped rooms, courtyards and unscanned gaps become
  "walkable". Carving only checks triangle **centroids** against obstacle points: a long thin triangle crossing a wall
  survives if its centroid is far from wall points. Only the single largest floor plane is used, so ramps, stairs and
  other levels are never in the input.
- **Why it matters:** Routes through walls and across voids.
- **Exact fix:** Build an occupancy grid (or alpha shape) from floor points. Mark cells occupied from wall and
  furniture points within agent height. Pass Recast a heightfield or triangle soup of actual floor cells. Include all
  near-horizontal planes and the connecting geometry so ramps and stairs exist in the input.
- **Test required:** Synthetic L-shaped floor plus wall points. Assert no navmesh polygon intersects the missing
  quadrant, and that a route between the two arms bends at the corner.

#### N-3 · CRITICAL · "Accessible" routes admit edges with unknown clearance
- **Files:** `RouteService.java:244`, `PipelineService.java:839-842`, `navmesh.py:246-255`
- **Problem:** `shared_edge_length` returns 0.0 whenever two polygons don't share two vertices at 4-decimal precision.
  Ingestion stores that as `NULL`, and `RouteService` then **keeps** NULL-clearance edges in STEP_FREE
  (`e.minClearanceM() != null && …`). The value is also a Recast portal length, which is already eroded by agent
  radius, not a corridor width.
- **Why it matters:** The product labels a route "excludes passages narrower than X m" when it has no measurement. For
  a wheelchair user that is a safety claim.
- **Exact fix:** Fail closed. In STEP_FREE, reject NULL clearance, and state in the response when clearance is unknown.
  Compute clearance from a distance transform of the occupancy grid along the edge, not from the portal length.
- **Test required:** RouteService test: a STEP_FREE graph whose only path has an edge with NULL clearance must return
  `ROUTE_UNAVAILABLE`, or a route explicitly flagged `clearanceUnknown`.

#### N-4 · HIGH · Step-free classification is computed in an arbitrary frame and against a single plane
- **File:** `navmesh.py:234-243, 258-289`
- **Problem:** Slope is measured against world +Z, which has no meaning in an SfM frame (R-2). Since the input is one
  fitted plane, all polygons have roughly the same slope, so STEP_FREE is either identical to STANDARD or empty.
- **Why it matters:** The docstring promises "with any stairs present, STEP_FREE strictly has fewer edges". That
  cannot happen when stairs are never in the input.
- **Exact fix:** Depends on R-2 and N-2. Compute slope relative to the canonical up axis, and detect steps as height
  discontinuities between adjacent cells greater than `agentMaxClimb`.
- **Test required:** Synthetic two-level floor joined by a stair (5 steps) and a 1:12 ramp. Assert STANDARD uses the
  stair and STEP_FREE uses the ramp.

#### N-5 · CRITICAL · Multi-floor transitions compare coordinates from unrelated frames
- **File:** `RouteService.java:376-390` (`Math.hypot(pa.x() - pb.x(), pa.y() - pb.y())`)
- **Problem:** Each floor is reconstructed independently (its own SfM frame, R-2 and D-1). Matching stairs or elevator
  POIs across floors by x/y distance assumes a shared venue frame that nothing establishes. Elevator matching also
  assumes every elevator stops at every floor.
- **Why it matters:** Multi-floor routes are coincidental.
- **Exact fix:** Introduce a venue frame with an explicit per-floor transform (`floor.frame_to_venue`, set by
  registering floors through shared stairwells or survey points). Model vertical connectors as explicit links
  (`connector(poi_a, poi_b, type, served_levels)`) created by staff, not inferred by proximity.
- **Test required:** Two floors with different frame offsets and a registered transform. Assert the route uses the
  linked elevator. Without a link, assert `ROUTE_UNAVAILABLE`.

#### N-6 · MEDIUM · Blocked regions remove nodes, not edges that cross them
- **File:** `RouteService.java:222-252`
- **Problem:** An edge whose endpoints are both outside a blocked box but whose segment passes through it is kept.
- **Why it matters:** The "obstacle avoided" claim fails for any obstacle smaller than the polygon spacing.
- **Exact fix:** Also drop edges whose segment intersects any blocked AABB (segment–box test).
- **Test required:** Two nodes on either side of a small blocked box. Assert the direct edge is excluded.

#### N-7 · MEDIUM · Graph promotion is not tied to run success or scan-version finalization
- **File:** `PipelineService.java:388-390, 800-850`
- **Problem:** `NAVIGATION_BAKING` ingestion retires the previous ACTIVE graph and activates the new one **inside the
  stage report**, even for an incremental rescan whose ScanVersion may never finalize. A graph with nodes but zero
  edges is still promoted.
- **Why it matters:** A DRAFT/abandoned version's graph serves live routes, and a zero-edge graph replaces a working
  one.
- **Exact fix:** Ingest as DRAFT with `scan_version_id`/`pipeline_run_id`, and promote in `finishRun` only on
  `SUCCEEDED`, in the same transaction that finalizes the version. Refuse promotion when the edge count is 0 or the
  largest connected component covers less than X% of nodes.
- **Test required:** Rescan run where `NAVIGATION_BAKING` succeeds and a later stage fails. Assert the previous graph is
  still ACTIVE.

#### N-8 · LOW · Architecture contradiction on where routing runs
- **File:** `ARCHITECTURE.md:30,83` vs `RouteService.java`
- **Problem:** The architecture says routing runs client-side with Detour/WASM against a versioned navmesh
  `scene_asset`, and returns `NAVMESH_UNAVAILABLE`. The code does server-side Dijkstra over tables and returns
  `ROUTE_UNAVAILABLE`.
- **Fix / Test:** Update ARCHITECTURE.md. No test.

### 2.4 AR

#### AR-1 · CRITICAL · Web AR "localization" is a hit-test on any surface; relocalization sends the wrong data
- **File:** `apps/web/components/ArWorkspace.tsx:95-117, 124-133, 188`
- **Problem:**
  - `ANCHOR_DETECTED` fires on the first WebXR **hit-test result**, i.e. any detected plane. No marker, QR or ArUco
    detection exists.
  - `attemptRelocalization` sends `calibratedAnchors[0]` (an arbitrary anchor, not one that was seen) with
    `lastKnownPose` (the old hit-test pose) as the "observation".
  - After relocalization, `lastKnownPose` is overwritten with a device→venue **transform**, so the same field holds two
    different kinds of value.
  - No `XRWebGLLayer` is created (`updateRenderState` never called). Per the WebXR spec, immersive animation frames are
    not delivered without a base layer, so `onFrame` very likely never runs (**needs verification on device**).
  - No route is rendered, yet the UI says "Your route is preserved".
- **Why it matters:** This is a fake state machine dressed as AR navigation.
- **Exact fix:** Implement marker detection (WebXR `camera-access` feature plus an ArUco/AprilTag detector in a
  worker, or WebXR image tracking where available). Map the detection to the specific anchor id. Create an
  `XRWebGLLayer` and render the route polyline in the venue frame transformed by the solved pose. Keep "observed
  anchor pose" and "device→venue transform" as distinct typed fields.
- **Test required:** Unit test for `attemptRelocalization` asserting it sends the **detected** anchor id and a pose
  from the detector. Device test (manual, recorded) showing a printed marker producing a transform whose residual
  against a second marker is under a stated bound.

#### AR-2 · HIGH · iOS client discards the detected anchor id and breaks on normal startup tracking states
- **File:** `apps/ios-ar/Sources/ChayaARKitSession/ARSessionManager.swift:57-77`
- **Problem:** `registered` (the anchor matched by name) is explicitly thrown away, so relocalization cannot know which
  anchor was seen. `.limited` (which includes `.initializing` at every session start) triggers `trackingLost`. From
  `TRACKING_LOST`, `anchorDetected` is ignored, so image detections after startup never localize. ARKit image
  detection cannot detect ArUco/AprilTag as such, although the anchor schema allows them. There is no app target, and
  the code has never been compiled for iOS.
- **Why it matters:** Nothing here has run; the design flaws are visible without a device.
- **Exact fix:** Emit `(anchorId, pose)` on detection. Treat `.limited(.initializing | .relocalizing)` as a transient
  state, not a loss. Restrict iOS marker types to `IMAGE_TARGET`/`QR_CODE` in the API or add a Vision-based ArUco
  detector. Add a minimal Xcode app and a macOS CI job running `xcodebuild build`.
- **Test required:** State-machine test: `startDetecting → trackingLost(limited.initializing) → anchorDetected` must end
  LOCALIZED. CI `xcodebuild` on a macOS runner.

#### AR-3 · MEDIUM · Relocalization math drops `physicalPose`, reports a fake-perfect residual and never rejects outliers
- **Files:** `AnchorService.java:173-189`, `CoordinateTransform.java:248-280`, `V14__ar_anchors.sql` header
- **Problem:** `physical_*` is stored and required, yet never used. The migration says it is used "together with a
  live device detection". A single anchor returns `residualMeters = 0.0`, which reads as a perfect measurement.
  Residual ignores rotation disagreement. There is no maximum residual, no outlier rejection, and duplicate anchor ids
  are double-counted. The observation list is unbounded, and each observation is a DB query, callable by
  `PUBLIC_VIEWER`. The migration says the transform is computed client-side; the controller says server-side.
- **Why it matters:** Clients cannot tell a well-constrained pose from a guess.
- **Exact fix:** Return `residualMeters = null` (unknown) for n=1. Add a rotational residual. Reject when the residual
  exceeds a configured bound (409 `RELOCALIZATION_INCONSISTENT`). De-duplicate ids, cap the list size, load anchors in
  one query. Remove `physical_*` or use it (marker frame convention), and fix the migration comment.
- **Test required:** Two anchors whose candidate transforms differ by 1 m must yield 409. A single anchor must return
  `residualMeters == null`. 50 observations must return 400.

#### AR-4 · HIGH · Anchors are never invalidated when the reconstruction frame changes
- **Files:** `V14__ar_anchors.sql` (`STALE` status exists), `AnchorService.java:137-150`, `PipelineService.java` (no reference to anchors)
- **Problem:** `STALE` is never set. A new reconstruction (new SfM frame, D-1) leaves every anchor `CALIBRATED` against
  the old frame. `calibrate` is a bare flag flip with no measurement attached.
- **Why it matters:** AR places users and routes in the wrong place, with the system asserting calibration.
- **Exact fix:** Bind anchors to `scan_version_id`. On finalizing a new version for the floor, mark anchors `STALE`
  unless the version's frame transform to the previous one is recorded and applied. Require `calibrate` to carry a
  measured residual.
- **Test required:** Finalize a new version for a floor; assert its anchors become STALE and relocalization returns 409
  `ANCHOR_NOT_CALIBRATED`.

### 2.5 Data, versioning and state (D / V)

#### D-1 · CRITICAL · POIs, anchors and navigation graphs are not bound to the frame they were measured in
- **Files:** `V5__poi.sql` (`scan_version_id` column exists), `poi/PoiService.java` (never sets it),
  `PipelineService.java:732-741` (sets `pipeline_run_id` only), `V6__navigation.sql`, `V14__ar_anchors.sql`.
- **Problem:** Positions are "in the floor reconstruction frame", but which frame is not recorded. Every new full
  reconstruction is a new SfM frame, and nothing transforms existing spatial data into it.
- **Why it matters:** After the second reconstruction of any floor, every manual POI, anchor and route destination is
  silently wrong. There is no provenance to detect or repair it.
- **Exact fix:** Make `scan_version_id` NOT NULL on `poi_version`, `navigation_graph` and `ar_anchor`. Add
  `scan_version.frame_to_parent` (a 4×4 similarity). Readers transform on read into the current version, or refuse
  (409 `FRAME_MISMATCH`) when no transform exists.
- **Test required:** Create a POI on version 1, finalize version 2 with a known transform, and assert
  `GET /pois` returns transformed coordinates. Without a transform, assert the POI is flagged stale.

#### V-1 · CRITICAL · Incremental rescan registration is rigid across independent SfM scales
- **File:** `chaya_worker/region_alignment.py:76` (`TransformationEstimationPointToPoint(False)`), `:100-104` (point-to-plane ICP, rigid)
- **Problem:** The rescanned region comes from a separate SfM run with a different arbitrary scale. A rigid
  RANSAC + ICP cannot recover scale. The FPFH radii and voxel size (`0.05 "m"`) are also meaningless across scales.
  The unit test only applies a known **rigid** transform to a copy of the same cloud.
- **Why it matters:** Incremental rescan cannot produce a correct merge. At best the confidence gate rejects it; at
  worst it accepts a wrong alignment.
- **Exact fix:** After R-2 makes both clouds metric, keep rigid alignment. Until then, use similarity estimation
  (`TransformationEstimationPointToPoint(True)`), and apply the scale to Gaussian scales in `transform_gaussians`.
- **Test required:** Align a region scaled by 1.7 relative to the target. Assert the recovered scale is within 2% and
  confidence ≥ threshold. With rigid-only estimation, the same test must fail, proving the test catches the bug.

#### V-2 · HIGH · Splice appends the whole region cloud, not the part inside the polygon
- **File:** `chaya_worker/region_splice.py:60-84`
- **Problem:** Global Gaussians inside the polygon are removed, but **all** aligned region Gaussians are appended,
  including everything the operator captured outside the polygon. The result is duplicated, ghosted geometry around
  the region. The `(x, y)` polygon test assumes z-up (R-2).
- **Why it matters:** Every rescan degrades the venue model at its seams.
- **Exact fix:** Crop the aligned region to the polygon plus a feather band, and blend or remove duplicates in the band
  (nearest-neighbour de-duplication).
- **Test required:** Splice a region cloud that extends 1 m beyond the polygon. Assert no global Gaussian and region
  Gaussian lie within ε of each other outside the polygon.

#### V-3 · HIGH · Rescan semantic indexing projects venue-frame geometry with local-frame cameras
- **Files:** `stages/semantic_indexing.py:99-102, 146`
- **Problem:** In an incremental run, `splats = SPLAT_MERGED` (venue frame after splice), but `POSES`/`SPARSE_MODEL` are
  this capture's **local** frame. Projection mixes the two frames, so detections attach to unrelated geometry.
- **Why it matters:** Rescans create mis-located POIs, then `supersedePoisInRegion` deletes the correct ones (V-6).
- **Exact fix:** Transform camera poses by the alignment transform (read `ALIGNMENT_REPORT.transform`) before
  projecting, or project against `SPLAT_ALIGNED`.
- **Test required:** Incremental orchestration test with a known alignment transform. Assert detected positions are in
  the venue frame.

#### V-4 · HIGH · Version numbering is not unique per floor; bootstrap always creates version 1
- **Files:** `RescanService.java:112-119` (`parent.versionNumber() + 1`), `:199-201` (`version_number = 1`), `V3`
  (`UNIQUE (scan_id, version_number)` only)
- **Problem:** Two concurrent rescans from the same parent both become N+1 under different scans, with no constraint
  violation. A later full reconstruction bootstrapped by `finalize-current` becomes another "version 1" of the same
  floor, with no parent. The lineage is ambiguous, and there is no "current version" pointer.
- **Why it matters:** Broken versioning, and "which reconstruction is live" is undefined.
- **Exact fix:** `UNIQUE (floor_id, version_number)`. Allocate version numbers with
  `SELECT … FOR UPDATE` on the floor row. Add `floor.current_scan_version_id`, updated only on finalization. Bootstrap
  should create `max+1` with `parent = current`.
- **Test required:** Two concurrent `startIncrementalProcessing` calls from the same parent: exactly one succeeds or
  they get distinct numbers. A second bootstrap must produce version 2.

#### V-5 · HIGH · The viewer ignores scan versions and run outcome
- **File:** `ReconstructionService.java:139-202`
- **Problem:** "Latest" is the newest `ARTIFACT_GENERATION` success, whatever the run's status (FAILED, CANCELLED) and
  whether its ScanVersion is DRAFT. Public viewers can be served an abandoned rescan's merged model.
- **Why it matters:** Versioning is decorative for every reader.
- **Exact fix:** Serve `floor.current_scan_version_id`'s artifacts by default. List other versions explicitly. Exclude
  runs whose version is DRAFT unless the actor is staff.
- **Test required:** Rescan reaches `ARTIFACT_GENERATION` then fails. Assert `/reconstructions/latest` still returns the
  previous finalized version.

#### V-6 · HIGH · Rescan deletes manually curated POIs
- **File:** `PipelineService.java:689-712`
- **Problem:** `supersedePoisInRegion` soft-deletes **every** POI in the polygon, including `source = 'MANUAL'`, and
  does so during the stage report, before the run succeeds. There is no per-POI audit event and no restore path.
- **Why it matters:** Staff-entered POIs (accessible toilets, elevators used for routing) disappear on a rescan that
  may later fail.
- **Exact fix:** Filter `v.source = 'AUTO_DETECTED'`. Defer superseding to `finishRun(SUCCEEDED)`. Record
  `deleted_by_run_id`.
- **Test required:** Rescan over a region containing one manual and one auto POI. Assert the manual POI survives, and
  that on run failure the auto POI survives too.

#### V-7 · MEDIUM · Full-venue reruns duplicate auto-detected POIs
- **File:** `PipelineService.java:659-675`
- **Problem:** Superseding only happens for incremental runs. Every full run of a floor inserts a fresh set of
  `AUTO_DETECTED` POIs next to the previous run's.
- **Exact fix:** On `finishRun(SUCCEEDED)` of a full run, soft-delete prior `AUTO_DETECTED` POIs of that floor from
  older runs.
- **Test required:** Two successful full runs on one floor. Assert the POI count equals the second run's detections.

#### V-8 · MEDIUM · Global reference cloud for rescans is chosen loosely
- **File:** `PipelineService.java:274-290`
- **Problem:** It picks any SUCCEEDED `SPLAT_MERGED`/`SPLAT_CLEAN` from the parent's **scan**, regardless of run status
  and not the run recorded in the parent version's provenance. It also always prefers `SPLAT_MERGED` over a newer
  `SPLAT_CLEAN`.
- **Exact fix:** Resolve via `parent.provenance.runId` and the `SPLAT_MERGED`/`SPLAT_CLEAN` of **that run**.
- **Test required:** Parent scan with two runs (one cancelled after cleanup). Assert the finalized run's cloud is used.

#### D-2 · MEDIUM · Capture can be stranded in `VALIDATING`
- **File:** `CaptureService.java:170-195`
- **Problem:** State is committed as VALIDATING, then storage is verified outside the transaction. A crash between the
  two leaves the capture in VALIDATING forever. Only READY or FAILED are legal next states, and nothing sweeps it
  (only *media* VALIDATING is resumed, `MediaValidationService.java:100`). `completeUpload` counts media without
  locking the capture row, so a concurrent `media init` can add PENDING media to a capture being marked READY.
- **Exact fix:** `SELECT … FOR UPDATE` on `capture_session` in `completeUpload` and `MediaService.init`. Add a startup and
  periodic sweep that re-runs verification for captures in VALIDATING.
- **Test required:** Kill-and-restart test (or simulate by leaving a row in VALIDATING) asserting the sweep moves it to
  READY/FAILED. Concurrency test for init vs complete-upload.

#### D-3 · LOW · Single-instance assumptions
- **Files:** `MediaValidationService.java:64-68,100` (in-JVM executor, all replicas resubmit on start),
  `PipelineEnforcer.java` (every replica runs it), `RateLimiter` (in-memory), `hud/CaptureHudBroadcaster.java` (in-memory SSE)
- **Problem:** Correct only with exactly one API replica, and this isn't documented as a constraint in DEPLOYMENT.md
  as far as I found.
- **Exact fix:** Document `replicas: 1`, or use ShedLock/advisory locks for the enforcer and validation, and Redis
  (already provisioned) for rate limits and HUD fan-out.
- **Test required:** Two API instances against one DB; assert each VALIDATING media is validated once.

### 2.6 Security (S)

#### S-1 · HIGH · One worker credential controls the entire execution plane for all tenants
- **Files:** `infra/keycloak/chaya-realm.json:186-213` (single `chaya-worker` client for CPU and GPU workers),
  `InternalJobController.java:99-110` (claims any stage), `PipelineService.java:300-316` (report does not check the
  claiming worker id)
- **Problem:** The GPU host is supposedly isolated from raw media by MinIO policy (`chaya-recon`). It still holds the
  same API credential as the CPU worker, so it can claim `INPUT_VALIDATION` and receive raw object keys, and it can
  submit reports for any job of any tenant. `report` does not verify `job.worker_id` against the caller. Stage
  restriction (`WORKER_STAGES`) is enforced only by the worker itself.
- **Why it matters:** A compromised GPU host can forge results (e.g. publish a malicious "reconstruction" to every
  venue's public link, within prefix rules), and the separation of duties is only on paper.
- **Exact fix:** Separate Keycloak clients per worker class, each with a claim listing the stages it may run, enforced
  in `claim`. Bind report and heartbeat to `(job_id, worker_id, lease token)` returned at claim time.
- **Test required:** A token for the recon client claiming `INPUT_VALIDATION` gets 403. Reporting a job leased to
  another worker gets 409.

#### S-2 · HIGH · Worker MinIO account can overwrite any tenant's published artifacts
- **File:** `infra/docker/minio/init.sh:37-51`
- **Problem:** `chaya-worker` and `chaya-recon` have `PutObject` on `derived/*`, with no per-prefix scoping and no
  versioning or object lock. Published `.ksplat` files (served to public viewers) can be replaced in place. See also
  R-8: checksums are never verified.
- **Exact fix:** Enable versioning plus object lock (governance) on `derived`. Serve by version id. Issue per-job STS
  credentials scoped to the job's `prefix` (MinIO `AssumeRole` with an inline policy).
- **Test required:** With a worker credential, a `PutObject` to an existing published key must fail, or the API must
  keep serving the original version.

#### S-3 · MEDIUM · Tenancy lives entirely in Keycloak attributes; no membership table
- **Files:** `JwtActorConverter.java:36-49`, `chaya-realm.json` (user-profile `org_id`/`venue_id` editable by realm admins)
- **Problem:** PROJECT_PLAN M3 promises membership tables; none exist. Any Keycloak realm administrator (a helpdesk
  role, for instance) can move a user into any organization. The API has no second source of truth to check against,
  and there is no org or user management API, so the org "ADMIN" role cannot manage its own users.
- **Exact fix:** Add `membership(user_sub, organization_id, venue_id, role)`. Resolve roles and venues from it (tokens
  carry only `sub`), and add org-admin APIs for membership.
- **Test required:** A token with a forged `org_id` for a user without membership gets 403/404.

#### S-4 · MEDIUM · Public link secret is a reusable bearer credential in a query string
- **Files:** `ViewerWorkspace.tsx:40` (`?link=`), `PublicViewerService.java:175-194`
- **Problem:** The secret (lifetime up to 30 days) sits in the URL query, so it lands in browser history, sync, and
  copy-paste. Referrer is suppressed; history and logs are not. It can be exchanged unlimited times by anyone who has
  it, and there are no per-link usage caps or usage visibility.
- **Exact fix:** Put the secret in the URL **fragment** (`#link=`), which is never sent to servers. Strip it with
  `history.replaceState` after exchange. Add optional max-exchanges and show exchange counts in `listLinks`.
- **Test required:** Playwright: after loading `/viewer#link=…`, `location.href` no longer contains the secret, and
  the network log shows it only in the POST body.

#### S-5 · MEDIUM · All visitors of one public link share one rate-limit bucket
- **File:** `RateLimitFilter.java:48-53`
- **Problem:** `PUBLIC_VIEWER` actors are keyed by `public-link:<id>`, so a venue link posted at an entrance gives
  every visitor a combined 60 searches per minute. One abuser also exhausts it for everyone.
- **Exact fix:** Key public viewers by `token hash` (per exchange), plus an aggregate per-link ceiling.
- **Test required:** Two tokens from one link each get their own budget.

#### S-6 · MEDIUM · Reconstructions produced with privacy disabled are served to anonymous links
- **Files:** `PipelineService.java:132-136`, `ReconstructionService.java:189,215`
- **Problem:** An admin may run without `PRIVACY_PREPROCESS`. Its artifacts are `contains_pii = false` by construction
  (validation only forces the flag **before** the privacy stage, which doesn't exist in that plan) and are served to
  `PUBLIC_VIEWER`.
- **Exact fix:** Mark every artifact of a privacy-disabled run `contains_pii = true` (or add `pipeline_run.privacy_enabled`
  to the serving filter), and refuse to serve them to `PUBLIC_VIEWER`.
- **Test required:** Privacy-disabled run → the public-link fetch of its KSPLAT returns 404.

#### S-7 · MEDIUM · PII staging objects survive failed runs, and purge failures are silent
- **File:** `PipelineService.java:401-407` (purge only after privacy success), `:195` (and on cancel), `:612-631`
- **Problem:** A run that FAILS before or at `PRIVACY_PREPROCESS` (e.g. `WORKER_LOST`) never purges its `pii/`
  frames. Purge failures emit an audit row and a log line only. There is no retry and no alert (O-3). Raw captures
  have no retention at all, and backups keep them forever (O-1).
- **Exact fix:** Purge on every terminal status. Add a periodic sweeper for `contains_pii` artifacts of terminal runs
  whose objects still exist. Alert on `pipeline.pii_purge_incomplete`. Define raw-media retention.
- **Test required:** A run failing at `FRAME_QUALITY_FILTER` → after the enforcer tick, no `pii/` objects remain.

#### S-8 · MEDIUM · Roles are global, not per venue
- **Files:** `Actor.java:14-23`, `OpsSection.java`
- **Problem:** A user is `role × venue-set`. "Manager of venue A, viewer of venue B" cannot be expressed; granting
  both venues grants manager on both.
- **Exact fix:** Per-venue role in membership (S-3).
- **Test required:** A user who is manager on A and viewer on B gets 403 on `POST /venues/B/public-links`.

#### S-9 · LOW · Search logs anonymous free-text queries indefinitely
- **File:** `SemanticSearchService.java:187-192`
- **Problem:** Public visitors' queries are stored with no retention.
- **Fix:** Retention job (e.g. 90 days), or store only for authenticated actors.
- **Test:** Row older than retention is deleted by the job.

### 2.7 Frontend (F)

#### F-1 · MEDIUM · Viewer downloads the whole `.ksplat` into a Blob with no size cap or checksum check
- **Files:** `ViewerWorkspace.tsx:215-222`, `SplatViewerCanvas.tsx:118`
- **Problem:** Hundreds of MB go into memory twice (Blob plus parsed buffer) on mobile. The sha256 the API returns is
  never checked.
- **Exact fix:** Enforce a per-device-profile max size before download, verify the digest with `crypto.subtle` (or
  rely on server-side verification, R-8), and use the library's progressive loading from a URL with auth headers.
- **Test required:** Unit test: an artifact over the device limit shows a "too large for this device" state without
  downloading.

#### F-2 · LOW · CSP is mostly report-only
- **File:** `apps/web/next.config.ts:8-23, 48-55`
- **Problem:** The full resource policy is `Report-Only`. It is admitted, but it means no enforced XSS mitigation beyond
  the subset.
- **Fix:** Nonce-based CSP via middleware. **Test:** Playwright asserts an injected inline script is blocked.

### 2.8 Architecture (A)

#### A-1 · MEDIUM · Stage coupling through magic strings in the worker's `command` blob
- **File:** `PipelineService.java:418-434`
- **Problem:** The control plane reads alignment confidence from `command.config.alignment.confidence`, a free-form
  JSON field the worker also uses for tool config. The "independent re-check" re-checks the worker's own number.
  `alignment_method` has a DB CHECK (`'FEATURE_RANSAC_ICP'`), so any other string rolls back the entire report and
  turns it into a 500.
- **Exact fix:** A typed `metrics` field on `StageReport` per stage. Validate `method` against an enum before the
  UPDATE. Recompute the gate from the `ALIGNMENT_REPORT` artifact (with R-8 checksums).
- **Test required:** A report with `method: "OTHER"` returns 409 `INVALID_REPORT`, not 500.

#### A-2 · MEDIUM · Downstream ingestion is "best effort" inside a SUCCEEDED stage
- **File:** `PipelineService.java:641-652, 762-773`
- **Problem:** An unreadable `DETECTED_OBJECTS`/`NAVIGATION_GRAPH` is logged and skipped, while the stage and run are
  marked SUCCEEDED. Conversely, a `ClassCastException` on a malformed node (`(Number) n.get("x")` with null) aborts the
  whole report.
- **Exact fix:** Validate the artifact schema before accepting the report (reject with 409 `ARTIFACT_INVALID` so the
  stage is retried), then ingest strictly.
- **Test required:** A `NAVIGATION_GRAPH` with a node missing `x` gets 409, and the job stays RUNNING for resubmission.

#### A-3 · LOW · Legacy stage names and endpoints kept alive
- **Files:** `V15__incremental_rescan.sql` (stage CHECK lists `MEDIA_FILTER`, `SPLAT_TRAINING`, …),
  `InternalJobController.java:122-130`
- **Fix:** Remove them via migration once no rows use them. **Test:** Migration test asserting no legacy-stage rows exist.

### 2.9 Operations (O)

#### O-1 · HIGH · Backups are on the same host, Keycloak users are not backed up, and there is no scheduling or restore drill in CI
- **Files:** `scripts/backup/backup.sh`, `docs/backup-recovery.md:5-40`
- **Problem:** Admitted residual risk. Losing Keycloak loses every user's `org_id`/`venue_id`, the whole authorization
  model (S-3). The additive mirror retains unblurred raw captures forever, with no erasure.
- **Exact fix:** Off-host encrypted backups (restic) with retention. Back up the Keycloak DB. Add a scheduled
  restore-verify job (`verify.sh`) that alerts on failure.
- **Test required:** CI job: back up, destroy volumes, restore, then run the API test suite's read checks against the
  restored stack.

#### O-2 · MEDIUM · Validation and enforcement run on daemon threads with no health signal
- **Files:** `MediaValidationService.java:64-68`, `PipelineEnforcer.java:326-336`
- **Problem:** The enforcer swallows exceptions (logs only). A permanently failing enforcer means leases never expire
  and no alert fires.
- **Exact fix:** Metrics `chaya_enforcer_last_success_timestamp` and `chaya_media_validating_oldest_seconds`, with
  alerts on each.
- **Test required:** Unit test: an enforcer exception does not update the success gauge.

#### O-3 · MEDIUM · Missing alerts
- **File:** `infra/monitoring/prometheus/alerts.yml`
- **Problem:** No alerts for PII purge incomplete, backup age, media stuck in VALIDATING, a capture stuck in
  VALIDATING, or the navigation graph count dropping to 0.
- **Fix:** Add metrics and alerts for each. **Test:** `promtool test rules` fixtures for each alert.

#### O-4 · LOW · Audit gaps
- **Files:** `ReconstructionController.java` (artifact downloads by public viewers not audited),
  `PipelineService.java:707` (per-POI supersede not audited)
- **Fix:** Audit artifact reads (sampled, or per token), and per-POI deletion with the run id.
- **Test:** An artifact fetch creates an audit row with `actor_type = PUBLIC_VIEWER`.

### 2.10 Testing (T)

#### T-1 · HIGH · Semantic search tests use hash-seeded random vectors
- **File:** `services/api/src/test/java/dev/chaya/api/TestEmbeddingConfig.java`
- **Problem:** "couch" and "sofa" are orthogonal random vectors. Tests can only check plumbing, never ranking. The
  mixed-scale ranking bug (CV-4) passes every test.
- **Exact fix:** A small committed fixture of real CLIP embeddings (text and image) for about 20 labels, generated
  once from `services/vision`, used in `SemanticSearchServiceTest`.
- **Test required:** A detected-object image embedding for "couch" must appear for the query "sofa".

#### T-2 · HIGH · The viewer's only "loads a reconstruction" test never delivers bytes
- **File:** `apps/web/e2e/viewer.spec.ts:48-70` (route handler `() => {}` never fulfils)
- **Problem:** The test passes as long as a request is *made*. Together with R-1, the whole capture → viewer chain is
  unverified.
- **Exact fix:** Commit a small `.ksplat` produced by `chaya_worker.ksplat` from a synthetic cloud, serve it in the
  test, and assert the canvas reports `phase: loaded` with the expected splat count (expose via `data-` attribute).
- **Test required:** As described. It will fail today if R-1 is correct.

#### T-3 · HIGH · Every geometric test uses the same frame and scale on both sides
- **Files:** `tests/gpu/test_region_alignment.py:3,57`, `tests/unit/test_navigation_baking.py`, `RouteServiceTest.java`,
  `benchmarks/b4_navigation` (synthetic)
- **Problem:** Alignment tests apply a rigid transform to a copy. Navigation tests hand-insert metric graphs. None
  exercise the real failure mode: arbitrary SfM scale and orientation (R-2, V-1, N-4, N-5).
- **Exact fix / test:** The tests listed under R-2, V-1 and N-4.

#### T-4 · MEDIUM · GPU-marked tests silently skip everywhere CI runs
- **Files:** `tests/gpu/test_reconstruction_toolchain.py:22-37`, `.github/workflows/python.yml`
- **Problem:** Stages 6–12 have **zero** executed coverage in CI and **zero** executions anywhere (E2E §5.1).
- **Exact fix:** A self-hosted GPU runner (or scheduled cloud GPU job) running the `gpu` suite nightly on a fixed small
  dataset. Fail the job, don't skip, when the toolchain is absent on that runner.
- **Test required:** The nightly job itself, with results published as an artifact.

#### T-5 · MEDIUM · AR clients have no behavioural tests
- **Files:** `apps/web/lib/ar-relocalization.test.ts` (reducer only), `apps/ios-ar/Tests` (math and reducer only)
- **Problem:** AR-1 and AR-2 are invisible to the test suite.
- **Fix / test:** See AR-1 and AR-2.

### 2.11 Documentation and product claims (D)

#### D-4 · HIGH · PROJECT_PLAN marks unexecuted milestones "Done"
- **File:** `PROJECT_PLAN.md:14-20` (M9–M13)
- **Problem:** The plan's own definition is: "'Done' means its tests pass and nothing in it pretends to do work that
  isn't implemented." M9 (splat, .ksplat), M10 (viewer), M11, M12 (auto-detection) and M13 (Recast navmesh) have never
  run end to end. M13 depends on a tool that does not exist.
- **Exact fix:** Reclassify each as "Code written; unexecuted", with a link to the E2E BLOCKED rows.
- **Test required:** None. Treat it as a release-gate checklist item.

#### D-5 · MEDIUM · Stale and contradictory docs
- `README.md:5`: "No business functionality yet."
- `ReconstructionService.java:97-111`: says NAVIGATION_BAKING and SEMANTIC_INDEXING are unimplemented and "always fail".
- `stages/unimplemented.py:1-2`: "all twelve stages"; there are fourteen.
- `V14__ar_anchors.sql` header: "transform computed client-side"; the controller says server-side.
- `ARCHITECTURE.md:30,83`: client-side Detour/WASM routing (N-8).
- **Fix:** Correct each. **Test:** None.

#### D-6 · MEDIUM · The OpenAPI "contract" is not the contract
- **File:** `packages/contracts/openapi/v1.yaml`
- **Problem:** Missing navigation, anchors, reconstructions, POIs, search, public links, rescan and venues. No CI step
  checks it against the running API.
- **Exact fix:** Generate from springdoc in CI and diff against the committed file, failing on drift.
- **Test required:** A CI job that fails when an endpoint exists in the app but not in `v1.yaml`.

---

## 3. Priority order

| Order | Findings | Rationale |
|---|---|---|
| 1 | R-1, T-2 | Cheapest way to find out whether anything can be displayed at all |
| 2 | R-2, D-1, AR-4 | The coordinate-frame model. Every spatial feature depends on it |
| 3 | N-1, N-2, N-3, N-5 | Navigation and accessibility claims |
| 4 | V-1 to V-6 | Rescan and versioning correctness, and user data loss |
| 5 | S-1, S-2, S-6, S-7 | Execution-plane trust and privacy |
| 6 | AR-1, AR-2 | Rebuild AR only after 2 is done. Until then it has nothing correct to show |
| 7 | CV-1 to CV-4, T-1 | Detection placement and search ranking |
| 8 | D-4, D-5, D-6 | Stop claiming what the code does not do |

## 4. Not reviewed in depth

The following were only skimmed. Absence of findings here is **not** a clean bill of health:

- CI/CD workflows (`.github/workflows/*`), deploy script, Caddy config
- Capture path planner internals (`planning/*`) and HUD
- Ops dashboard SQL (`OpsDashboardService`)
- `services/vision` beyond the E2E notes
- Frame-quality and FFmpeg stages
- Monitoring dashboards (Grafana JSON)
- License tooling (`scripts/licenses`)
