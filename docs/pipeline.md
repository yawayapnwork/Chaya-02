# Reconstruction pipeline

Spring Boot is the **control plane**: it owns runs, jobs, stage records, artifact metadata, leases, the time
box and every access-control decision. Python is the **execution plane**: a stateless worker claims one
stage job, executes it, and reports one structured stage record. No GPU-heavy work ever runs inside an HTTP
request, and the worker has no database access.

```
operator -> POST /captures/{id}/processing ---> Spring Boot (control plane) ---- Postgres (run, jobs, stage runs, artifacts)
                                                   |  ^                               |
                       claim / heartbeat / report  |  |  work order (inputs, prefix,   |  MinIO raw bucket (uploads)
                       (service-account JWT)       v  |  deadline)                     |  MinIO derived bucket (outputs, logs)
                                              Python worker (services/reconstruction) -+
```

## Runs, jobs and states
`POST .../processing` creates a scan, a `pipeline_run` (one per scan) and queues the first stage as a
`processing_job`. A job is `QUEUED -> RUNNING -> SUCCEEDED | FAILED | CANCELLED` (`FAILED -> QUEUED` for a
bounded retry). When a stage succeeds the control plane queues the next; one job is active at a time.

| Run status | Meaning | Quality |
|---|---|---|
| `RUNNING` | a stage is queued or executing | - |
| `SUCCEEDED` | every planned stage succeeded | `FINAL` |
| `PARTIAL` | time budget ran out **after a reconstruction artifact existed**; explicitly not finalized | `PARTIAL` |
| `FAILED` | a stage failed or the budget ran out with no reconstruction; retryable | none |
| `CANCELLED` | stopped by an operator | none |

Database constraints enforce that `quality` is `FINAL` only with `SUCCEEDED` and `PARTIAL` only with `PARTIAL`,
that runs and jobs only take valid transitions, and that stage records and artifacts are write-once. The
capture becomes `COMPLETED` for `SUCCEEDED` and `PARTIAL` runs (the run's `quality` tells them apart) and stays
`PROCESSING` while a run is `FAILED` so it can be retried. Nothing finalizes a `scan_version` yet: that
belongs to the artifact-generation milestone, and a `PARTIAL` result must be finalized explicitly, never implicitly.

## The stages

| # | Stage | What it does | Status on a machine with only FFmpeg + OpenCV |
|---|---|---|---|
| 1 | `INPUT_VALIDATION` | verifies every raw file against its recorded checksum, decodes video/images, parses metadata, validates a declared camera calibration (see "Camera calibration and lens distortion") | **executes** |
| 2 | `FFMPEG_PREPROCESS` | FFmpeg frame extraction (fps, max height, frame cap), image normalisation (drops EXIF); rescales a declared calibration to the frames and publishes it as `CAMERA_CALIBRATION` | **executes** |
| 3 | `FRAME_QUALITY_FILTER` | drops blurred (Laplacian variance), under/over-exposed and near-duplicate frames, with reasons | **executes** |
| 4 | `PRIVACY_PREPROCESS` | face and screen/document detection, blurring, verification pass; fails closed | **executes** |
| 5 | `POSE_ESTIMATION` | COLMAP feature extraction (seeded with the declared calibration when there is one) + matching, GLOMAP mapper, COLMAP mapper fallback, poses.json with the model's cameras | implemented; **needs COLMAP** (GLOMAP optional); fails with `DEPENDENCY_UNAVAILABLE` here |
| 6 | `SPLAT_RECONSTRUCTION` | gsplat training (Adam, L1+D-SSIM) on undistorted frames, seeded from the SfM point cloud, with adaptive density control (clone/split/prune, capped), a time-boxed loop, resumable checkpoints and one colour convention to the viewer; see "Splat training" below | implemented; **needs torch + gsplat + CUDA + COLMAP**; fails with `DEPENDENCY_UNAVAILABLE` here. Training mechanics validated on CPU with a test renderer; **gsplat training has never run** |
| 7 | `SEMANTIC_SEGMENTATION` | per-frame SegFormer (or any configured HF model) segmentation, projected onto the splat and bucketed into floor/wall/furniture/clutter | implemented; **needs torch + transformers + the model cached locally + COLMAP**; fails with `DEPENDENCY_UNAVAILABLE` here |
| 8 | `GEOMETRIC_CLEANUP` | Open3D statistical + radius outlier removal (radius a multiple of the cloud's own median nearest-neighbour spacing: scale-invariant, not metres), then semantic class-aware filtering (never opacity alone) | implemented; **needs Open3D**; fails with `DEPENDENCY_UNAVAILABLE` here |
| 9 | `PLANE_FITTING` | iterative Open3D RANSAC plane extraction (spacing-relative inlier distance); floor/ceiling/wall classification against the calibrated frame's up or this reconstruction's `GRAVITY_ESTIMATE` (floor plane oriented by the cameras; [coordinate-frames.md](coordinate-frames.md)) | implemented; **needs Open3D**; fails with `DEPENDENCY_UNAVAILABLE` here |
| 10 | `ARTIFACT_GENERATION` | `.ksplat` conversion (the level-0 KSplat layout of the pinned viewer, @mkkellogg/gaussian-splats-3d 0.4.7; see "Viewer asset format" below), the artifact manifest, the compressed viewer bundle | implemented; only needs the cleaned splat as input |
| 11 | `SEMANTIC_INDEXING` | Grounding DINO open-vocabulary detection (stock checkpoint, not fine-tuned) + 3D localisation against the splat + real CLIP embeddings, positions in canonical metres; see [docs/search.md](docs/search.md) | implemented; **needs a calibrated coordinate frame** (else `NOT_CALIBRATED`) **and torch + transformers + open_clip + Pillow + the Grounding DINO checkpoint cached locally + COLMAP** |
| 12 | `NAVIGATION_BAKING` | a multi-level surface model of the cleaned, labelled splat (ground at each level, steps as their own Recast area, wall/furniture/clutter obstacle boxes, measured clear widths) in canonical metres, requiring labels for the baked splat (else `NAVMESH_LABELS_UNAVAILABLE`), a Detour navmesh baked by the real Recast/Detour library (chaya-navmesh, recastnavigation 1.6.0) through the single Recast axis boundary, NAVMESH + NAVMESH_MANIFEST provenance, STANDARD/STEP_FREE routing graphs from the Detour polygon links; see [docs/navigation.md](docs/navigation.md) | implemented; **needs a calibrated coordinate frame** (else `NOT_CALIBRATED`) **and chaya-navmesh** (else `NAVMESH_TOOL_UNAVAILABLE`; the CPU worker image includes it) |

Stage order runs SEMANTIC_INDEXING before NAVIGATION_BAKING (not their original numbering): neither
depends on the other's output, and chaya-navmesh is a separately built binary a worker may not have
(unlike the reconstruction toolchain SEMANTIC_INDEXING needs), so a worker without it still produces a fully
searchable reconstruction -- only routing is unavailable, not search too (a stage failure stops the run
from advancing; see `PipelineService#advance`).

There is no code path that produces a `.ply`/`.ksplat`/mesh/detection/navmesh without the real algorithm
behind it: stages 6-12 run real gsplat/Open3D/transformers/Grounding-DINO/CLIP/Recast code, each gated by
`chaya_worker.toolchain` dependency checks -- a missing dependency is `DEPENDENCY_UNAVAILABLE`, structured
and actionable, never a simulated result.
On a worker with only FFmpeg/OpenCV/COLMAP (no CUDA, gsplat, Open3D, transformers or chaya-navmesh) a run
still ends `FAILED` at `SPLAT_RECONSTRUCTION`, which is the intended honest behaviour; the reconstruction
toolchain (`pip install chaya-worker[reconstruction]`) and a CUDA GPU are needed to get past it, and
chaya-navmesh specifically is needed only for `NAVIGATION_BAKING` (the run's last stage) to reach
`SUCCEEDED` end to end.
Coordinate frames ([coordinate-frames.md](coordinate-frames.md)): every stage up to ARTIFACT_GENERATION works in the
reconstruction's own arbitrary frame, with scale-invariant thresholds. The stages whose output is metric --
SEMANTIC_INDEXING, NAVIGATION_BAKING, REGION_ALIGNMENT, REGION_SPLICE -- read the calibrated frame from their work
order and fail with `NOT_CALIBRATED` (retryable) when there is none; they are never run on reconstruction units
presented as metres. A full run therefore stops at SEMANTIC_INDEXING until an operator calibrates the reconstruction
(`POST /venues/{v}/reconstructions/{runId}/coordinate-frames`) and retries the run.

Viewer asset format: `chaya_worker.ksplat` writes the KSplat layout that the pinned viewer library
(@mkkellogg/gaussian-splats-3d **0.4.7**, `apps/web/package-lock.json`) reads, with values read from that version's own
source, not from memory: a 4096-byte file header (version 0.1, one section, compression level 0), a 1024-byte section
header, then 44-byte records (float32 position, float32 linear scale, float32 rotation **w, x, y, z**, uint8 RGBA with
colour `floor((0.5 + SH_C0·f_dc)·255)` and alpha `floor(sigmoid(opacity)·255)`). Only level 0, SH degree 0. The earlier
hand-made layout (32-byte header, uint8 rotations) could not be loaded by that library at all (`RangeError: Invalid
typed array length: 4096`); it was replaced.

What proves compatibility, and nothing else does:

- `apps/web/lib/ksplat-compat.test.ts` (`npm test`) loads the committed production output
  `packages/contracts/fixtures/ksplat/scene.ksplat` with the pinned library's own `KSplatLoader` (its ES module build),
  and checks every splat's count, position, scale, rotation, colour and alpha against values derived from the input
  cloud (`cloud.json`) in the test itself. It also cross-checks against the library's own route for the same cloud as a
  standard 3DGS PLY (`PlyLoader`).
- `apps/web/e2e/ksplat-viewer.spec.ts` (Playwright) serves those bytes as the artifact download to the real Next.js
  viewer, which loads them with GaussianSplats3D in Chromium and reports all 3 splats.
- `services/reconstruction/tests/unit/test_ksplat_fixture.py` fails unless the committed fixture is byte-identical to what
  the production encoder and PLY writer produce now (regenerate with
  `packages/contracts/fixtures/ksplat/generate_ksplat_fixture.py`, then re-run the two tests above).

`chaya_worker.ksplat.decode` is a diagnostic reader of the same layout; a round trip through it is not evidence of viewer
compatibility.

The cleanup benchmark harness (`python -m chaya_worker.benchmarks.cleanup_benchmark`) compares no-cleanup,
opacity-threshold, statistical-outlier, density/radius-outlier and semantic-aware cleanup on a trained
splat; point counts and timings are always reported, PSNR/SSIM only when reference frames/poses and the
GPU toolchain are available -- otherwise it says so instead of inventing a number.

## Camera calibration and lens distortion

This addresses review finding G-1 (training discarded lens distortion). `chaya_worker.camera_model` is the single
camera description from ingestion to training. It holds a COLMAP model name, the image size and every parameter in
COLMAP's order and pixel convention.

**Supported models:** `SIMPLE_PINHOLE`, `PINHOLE`, `SIMPLE_RADIAL`, `RADIAL`, `OPENCV`, `FULL_OPENCV`. The distortion
formulas are COLMAP's. The tests check them against OpenCV's `projectPoints`.

**Refused:** the fisheye and field-of-view models (`SIMPLE_RADIAL_FISHEYE`, `RADIAL_FISHEYE`, `OPENCV_FISHEYE`, `FOV`,
`THIN_PRISM_FISHEYE`, `RAD_TAN_THIN_PRISM_FISHEYE`) and any unknown name fail with `CAMERA_MODEL_UNSUPPORTED`. A
distortion that folds over itself inside the image (non-positive Jacobian), or that cannot fill a same-size pinhole
image from the photograph, fails with `CAMERA_CALIBRATION_INVALID`. Neither is approximated by another model.

Where the camera comes from, stage by stage:

| Stage | What happens to the camera |
|---|---|
| INPUT_VALIDATION | A `cameraCalibration` block in a metadata file ([capture-ingestion.md](capture-ingestion.md)) is parsed and validated. Every video and image must be exactly the calibrated size. Rotated videos are refused, and so is more than one declared calibration. `input-report.json` records `camera_calibration_status` (`DECLARED` / `NOT_PROVIDED`). |
| FFMPEG_PREPROCESS | Frames are downscaled, so the calibration is rescaled per axis by the actual output/input size. Focal lengths and principal point scale; distortion coefficients do not change, since they act on normalised coordinates. A single-focal model rescaled non-uniformly (FFmpeg rounds widths to even) becomes its exact two-focal equivalent. The result is published as `CAMERA_CALIBRATION` (`camera-calibration.json`, not PII, so every later stage receives it). No later stage resizes frames. |
| POSE_ESTIMATION | With `CAMERA_CALIBRATION`, the frames must be the calibrated size. `feature_extractor` gets `--ImageReader.camera_model <model> --ImageReader.camera_params <params>`. Without it, COLMAP self-calibrates `SIMPLE_RADIAL`, as before. The mappers' bundle adjustment may refine focal length and distortion in both cases (their defaults are not overridden), so the declared calibration is a starting value. `poses.json` records the reconstructed model's cameras with every parameter, `calibration_source` (`CAPTURE_METADATA` / `SFM_SELF_CALIBRATION`) and, for a declared calibration, how far SfM moved it. |
| SPLAT_RECONSTRUCTION | gsplat renders a pinhole camera. Frames of a camera with non-zero distortion are undistorted first (`FrameRectifier`): bilinear resampling through the model's forward distortion to a `PINHOLE` camera of the same size and principal point, with focal lengths `focal_scale · (fx, fy)`. `focal_scale` is the smallest value that leaves no blank pixel. The pose is unchanged: undistortion is a 2D resampling, so the world-to-camera transform in `POSES` is the training camera's pose. Frames of a camera without distortion are used as they are. `TRAINING_CAMERAS` (`training-cameras.json`) records, per camera, the source camera (model, size, fx, fy, cx, cy, distortion model and coefficients), `undistorted`, the pinhole camera trained with, `focal_scale`, the mapping `x_source = K_source · distort(K_training⁻¹ · x_training)`, and the calibration source. |
| SEMANTIC_SEGMENTATION, SEMANTIC_INDEXING | These also projected the splat through a pinhole `K` onto distorted frames. They now use the same undistorted frame and pinhole camera. Detection boxes (`bbox_px`) are therefore in undistorted-frame pixels, which have the same size as the frame. |

**What exists and what does not.** No capture client (web capture page, iOS app) writes a `cameraCalibration` block
today. Every real capture therefore still takes the `SFM_SELF_CALIBRATION` path: COLMAP estimates one radial
coefficient, and training now undistorts with it instead of discarding it. Nothing in the pipeline claims a physical
calibration unless the metadata declares one. The declared `source` text is recorded verbatim and not verified.

**Validation.** The tests use synthetic parameters, not a measured lens:

- `tests/unit/test_camera_model.py`: parameters survive parsing for every supported model; projection matches OpenCV;
  rescaling; capture-metadata parsing and refusals; undistortion.
- The review's required fixture: known 3D points photographed through a distorted `OPENCV` camera land within 0.5 px of
  their blobs in the undistorted frame when projected through the training camera. The measured maximum is 0.04 px. The
  old pinhole treatment is off by up to 53 px on the same fixture.
- `tests/orchestration/test_camera_calibration.py`: the real INPUT_VALIDATION and FFMPEG_PREPROCESS stages carry a
  declared calibration through a 2x downscale into `CAMERA_CALIBRATION`. From that artifact it builds the COLMAP command
  and the training cameras on the extracted frames, again within 0.5 px.

**Not verified.** COLMAP was not run: that test writes `cameras.txt` from the declared camera, as if bundle adjustment
left it unchanged. gsplat has still never executed (see "Splat training"). No real capture has been trained with
undistortion, and no accuracy improvement on real data has been measured.

## The stage artifact contract

`packages/contracts/pipeline/stage-artifacts.json` says what every stage reads and publishes. Both sides are tested
against it:

- `PipelineArtifactContractTest` checks `PipelineDefinition`'s plans and `REQUIRED_OUTPUTS`.
- `tests/unit/test_stage_artifact_contract.py` reads each worker stage's source and checks what it really reads and
  writes. It also checks that every plan (full or incremental, with or without privacy preprocessing or navigation
  baking) satisfies every stage's inputs from the stages before it, and that a shipped worker claims every planned stage.

A `SUCCEEDED` report is accepted as a success only if its outputs keep the contract. Otherwise the stage run is recorded
as **FAILED** and the run fails at that stage, retryably, before anything downstream runs:

| Code | Meaning |
|---|---|
| `STAGE_OUTPUT_MISSING` | a required output is absent: for example ARTIFACT_GENERATION without `KSPLAT`, or NAVIGATION_BAKING without `NAVMESH`/`NAVMESH_MANIFEST`/`NAVIGATION_GRAPH`. Before, the first went unnoticed until publication (`VERSION_INCOMPLETE`, after every later stage had run); a bake without outputs was not caught at all for a full run. |
| `ARTIFACT_UNREADABLE` | `DETECTED_OBJECTS` or `NAVIGATION_GRAPH` is not JSON when read back from storage. Before, this was logged and skipped: the stage succeeded with nothing searchable or routable. |
| `DETECTED_OBJECTS_INVALID` | no `objects` list; no `embedding_model`; or an object without a 3D position, without a finite 512-d embedding, or with a malformed localization. Before, such objects were dropped silently. |
| `NAVIGATION_GRAPH_INVALID` | no `graphs` map, or no STANDARD graph with nodes. Before, this was skipped: the stage succeeded with no routable graph. |

A document in the wrong frame or version, or a graph bound to another navmesh, still refuses the whole report (409
`ARTIFACT_FRAME_MISMATCH`, `ARTIFACT_VERSION_MISMATCH`, `NAVMESH_BINDING_INVALID`), as before. A checksum or size that
does not match the stored object refuses it too (`ARTIFACT_CHECKSUM_MISMATCH`, `ARTIFACT_SIZE_MISMATCH`, ArtifactSealer).

**Runs with privacy disabled** (admin only): the plan has no PRIVACY_PREPROCESS, so there is no `FRAME_ARCHIVE_ANON`.
POSE_ESTIMATION, SPLAT_RECONSTRUCTION, SEMANTIC_SEGMENTATION and SEMANTIC_INDEXING read only that archive, so every such
run failed at POSE_ESTIMATION with `INPUT_INVALID`. They now read `FRAME_ARCHIVE_SELECTED` when the work order says
privacy is disabled (`chaya_worker.stages.base.frame_archives`), and never in a privacy-enabled run. A GPU host's
reconstruction storage account cannot read `pii/` staging (DEPLOYMENT.md), so a privacy-disabled run's GPU stages
need a worker with access to it.

**Who runs what.** The shipped CPU worker (`infra/deploy`) claims the stages before privacy, plus REGION_SPLICE,
ARTIFACT_GENERATION and **NAVIGATION_BAKING**. NAVIGATION_BAKING needs only numpy and `chaya-navmesh`, which the CPU image
builds. No shipped worker claimed it before, so every run waited at the last stage until its deadline. The GPU image
(`Dockerfile.gpu`) claims POSE_ESTIMATION, SPLAT_RECONSTRUCTION, SEMANTIC_SEGMENTATION, GEOMETRIC_CLEANUP,
REGION_ALIGNMENT, PLANE_FITTING and SEMANTIC_INDEXING.

## The stage contract
Every attempt of every stage produces one immutable `pipeline_stage_run` row (returned by
`GET .../processing`), submitted by the worker in a single `POST /api/v1/internal/jobs/{id}/report`:

| Contract field | Where it lives |
|---|---|
| stage name | `stage` |
| input artifact(s) | `input_artifact_ids`, and the work order's `inputs` |
| output artifacts | `processing_artifact` rows (`stage_run_id`, `kind`, bucket/key, size, `sha256`, content type, `partial`, `contains_pii`) |
| command / configuration | `command` (jsonb): every external `argv` executed, tunables used, tool versions, worker id/version |
| start / end time | `started_at`, `finished_at` |
| exit status | `exit_status` (of the external process, null when none ran) |
| stdout / stderr location | log artifacts `LOG_STDOUT` / `LOG_STDERR` (bucket + key), also on failure |
| checksum | per artifact `sha256`, and `output_sha256` over the sorted output checksums (computed by the server) |
| error code | `error_code`, `error_message`, structured `error_details` |

The control plane verifies a report before accepting it: every artifact key must lie under the attempt's
server-issued prefix (`org/{org}/venue/{venue}/scan/{scan}/run/{run}/{STAGE}/attempt-{n}/`, no `..`), the
object must exist in the derived bucket with the reported size, PII flags must match the key layout, only
reconstruction artifacts may be `partial`. A rejected report changes nothing; the worker then reports an
explicit `REPORT_REJECTED` failure. (Content checksums are recorded, not re-hashed server-side.)

## Failure handling and retry
- A failed stage never advances the run. Earlier artifacts are untouched (artifacts are write-once; a retry
  writes under `attempt-{n+1}/`), later stages are never queued.
- `POST .../processing/retry` re-queues the failed stage (bounded by `max_retries`, default 3) with a fresh time
  budget; earlier successful stages are not repeated and their outputs are offered as inputs.
- **Lease and heartbeat.** `claim` leases the job (default 300 s); the worker heartbeats every 60 s and is told to
  stop if the run was cancelled. The enforcer (`PipelineEnforcer`, every 30 s) fails jobs whose lease expired
  (`WORKER_LOST`) and jobs that overrun the run deadline by the grace period (`TIME_LIMIT_EXCEEDED`), then the run
  is `FAILED` and retryable.
- Worker-side, a stage crash, missing tool, timeout, corrupt input or storage failure becomes a structured
  `FAILED` report with logs uploaded; a bug in a stage cannot take the worker down.

## Incremental re-scan
A capture whose `capture_session.parent_scan_version_id` is set (created by `POST
/venues/{v}/floors/{f}/rescan`) runs `PipelineDefinition.INCREMENTAL_STAGES` instead of the plan above:
the same first seven stages, against only the newly captured region, then `REGION_ALIGNMENT` (real
feature-matching + ICP against the selected parent version's reconstruction) and `REGION_SPLICE` (replaces
just the changed region), before the usual `PLANE_FITTING`/`ARTIFACT_GENERATION`/`SEMANTIC_INDEXING`/
`NAVIGATION_BAKING` re-run on the spliced result. Those four are **not** regional: they re-derive the whole floor
from the merged cloud (navigation is re-baked whenever the parent version has a navmesh, and checked against the
parent scene outside the region). See [docs/rescan.md](docs/rescan.md) for the full design, including the alignment
quality gates and exactly what is regional.

## Time-boxed reconstruction
Each run has `time_budget_seconds` (default 3600, max 86400) and a `deadline_at`. Work orders carry the deadline;
external commands are killed when it passes (`TIME_LIMIT_EXCEEDED`). The control plane never hands out a job of an
expired run. When the budget is spent:
- after a reconstruction (`SPLAT`) artifact exists: run `PARTIAL`, quality `PARTIAL`, message states it is not finalized;
- otherwise: run `FAILED`, `TIME_LIMIT_EXCEEDED`.
A worker may also report a stage that hit the limit while holding a checkpoint (`failed` + `TIME_LIMIT_EXCEEDED`
+ an artifact with `partial=true`); only then is the run `PARTIAL`. SPLAT_RECONSTRUCTION does exactly this when its
training loop stops for the budget (see "Splat training"). The UI shows PARTIAL with its own badge and the
words "not finalized"; a success without `FINAL` quality is flagged as inconsistent.

## Privacy boundary
- Frames extracted from raw video (and raw media) are `contains_pii` and live under `.../pii/` keys.
- With privacy enabled (the default) the control plane **withholds** PII-flagged inputs from every stage after
  `PRIVACY_PREPROCESS`, rejects reports from those stages that register PII artifacts (`PII_AFTER_PRIVACY`), and
  **deletes** the PII objects when the privacy stage succeeds (audited as `pipeline.pii_purged`). The worker
  independently refuses to start such a stage with a PII input (`PRIVACY_VIOLATION`) and always deletes its local work directory.
- The privacy stage **fails closed**: missing detector, unreadable frame, or a face still detectable after
  anonymisation means no output. Blurred faces often remain detectable, so it verifies with the detector and
  keeps covering (pixelate+blur, then solid fill) until none is found or fails after 5 rounds.
- **Privacy masks (review G-2).** A blurred or solid-filled region is not the scene, so it must not become learned
  geometry or colour. `PRIVACY_PREPROCESS` publishes `PRIVACY_MASKS` (`chaya_worker.privacy.masks`): `<frame>.png`,
  255 = reconstruction-valid, 0 = privacy-masked. A mask covers every pixel anonymisation rewrote (escalation rounds
  included), widened to whole 16×16 JPEG MCUs plus one MCU of margin. That way the anonymised frame's JPEG encoding
  cannot carry fill colour into a pixel marked valid: outside the mask, the decoded frame is bit-identical to the decoded
  original. The mask holds rectangles only and is not PII. In a privacy-enabled run every stage that reads anonymised
  frames requires it and fails with `PRIVACY_MASKS_MISSING` otherwise; a missing or malformed per-frame mask is
  `PRIVACY_MASK_INVALID`.
  - `POSE_ESTIMATION`: COLMAP `--ImageReader.mask_path`, with the masked area grown by `PRIVACY_SFM_MASK_MARGIN_PX`
    (16), so no keypoint, descriptor, SfM point or seed colour comes from a fill.
  - `SPLAT_RECONSTRUCTION`: masks are undistorted with their frames (a valid pixel never samples a masked one), masked
    pixels are zeroed in the training image and excluded from L1 and D-SSIM (an SSIM window touching a masked pixel
    is dropped), so they get no gradient and drive no densification. The masks are part of the checkpoint identity.
    `splat-training-report.json` records `privacy_masks`.
  - `SEMANTIC_SEGMENTATION`: no class vote from a masked pixel. `SEMANTIC_INDEXING`: detections whose box is more than
    `PRIVACY_DETECTION_MAX_MASKED_FRACTION` (0.25) masked are dropped; geometry is associated only through unmasked pixels.
- **PII staging retention (review S-7).** `pii/` objects are deleted when privacy preprocessing succeeds, and when a
  run ends SUCCEEDED, PARTIAL or CANCELLED (including privacy-disabled runs). A FAILED run keeps them for
  `PII_STAGING_RETENTION` (default `PT24H`) so it can be retried. After that a sweep (every minute) deletes them, and it
  also retries deletions that failed. Each deletion is recorded in `pii_staging_purge` and audited. A retry that would
  need purged frames is refused with 409 `PII_STAGING_PURGED`. Gauge `chaya_pii_staging_overdue_artifacts`, alert
  `ChayaPiiStagingNotPurged`.
- Only an administrator may run a pipeline with privacy preprocessing disabled; it is recorded on the run. Such a
  reconstruction is never listed or served to a public viewer link (review S-6); signed-in venue members still see it.
- **Limits, stated plainly:** detection is classical computer vision (OpenCV Haar cascades for faces; a quadrilateral
  heuristic for screens/documents that over-blurs and misses tilted or dim ones). It is a strong first line, not a
  guarantee; a learned detector can replace either behind the `RegionDetector` interface. Raw uploads in the raw
  bucket are not touched by this stage. Masks only cover what was detected: a missed face is neither anonymised nor
  masked, and it is trained as scene content like any other pixel. Not detected at all: people and bodies other than
  faces, licence plates, text on arbitrary surfaces (badges, whiteboards, labels), reflections of any of these, and
  non-visual PII such as EXIF or GPS (frames are re-encoded by OpenCV, which writes no EXIF).

## Observability
Worker logs are JSON, one object per line, with `ts, level, logger, msg, job_id, run_id, stage, attempt, worker_id`
plus event fields. The same lines are uploaded as the stage's stdout log. Status: `GET
/api/v1/venues/{v}/captures/{c}/processing` returns the run (status, quality, deadline, failure), every stage
(state, attempts, last execution record with command, exit status, timings, checksums, error, log locations) and
the job history. Every transition is audited (`pipeline.start|stage_succeeded|stage_failed|succeeded|partial|
failed|retry|cancel|pii_purged`, `job.claim`).

## Running it
```
cd services/reconstruction
py -3.12 -m venv .venv && .venv/Scripts/pip install -e ".[dev]"      # bash: .venv/bin/pip
CHAYA_API_URL=... CHAYA_TOKEN_URL=... CHAYA_CLIENT_SECRET=... S3_ENDPOINT=... S3_ACCESS_KEY=... S3_SECRET_KEY=... python -m chaya_worker
```
or `docker compose --env-file .env -f infra/docker/docker-compose.yml --profile pipeline up`. The image contains FFmpeg
and OpenCV only. The GPU worker image for SPLAT_RECONSTRUCTION (and COLMAP's POSE_ESTIMATION) is
`services/reconstruction/Dockerfile.gpu` ("GPU worker image" below); set `COLMAP_BIN` / `GLOMAP_BIN` if they are not on
PATH.

## Splat training

`chaya_worker.stages.splat_reconstruction` runs the loop in `chaya_worker.splat_training` with gsplat's CUDA rasteriser.

**Representation.** N Gaussians, float32, optimised in unconstrained space and written to the PLY as stored:

| Parameter | Shape | Stored as | Rasteriser receives |
|---|---|---|---|
| position | (N, 3) | `means` (PLY x, y, z) | as is |
| scale | (N, 3) | log standard deviation (`scale_0..2`) | `exp()` |
| rotation | (N, 4) | wxyz quaternion, unnormalised (`rot_0..3`) | normalised |
| opacity | (N,) | logit (`opacity`) | `sigmoid()` |
| colour | (N, 3) | degree-0 SH `f_dc` (`f_dc_0..2`) | `SH_C0 * f_dc + 0.5`, clamped at 0 |

**Colour.** `chaya_worker.splat_color` is the single convention. Values are sRGB-encoded from start to finish and are
never linearised. The chain:

- frame pixel / 255;
- COLMAP point RGB → `f_dc = (rgb/255 − 0.5) / SH_C0`;
- training renders `SH_C0 · f_dc + 0.5`;
- the PLY stores `f_dc`;
- the ksplat byte is `floor((SH_C0 · f_dc + 0.5) · 255)`;
- the viewer shows byte / 255.

This fixes review R-3: training used to render `sigmoid(f_dc)`, so 0.8 was shown as ≈0.89. Documented tolerances:

- RGB → f_dc → rendered colour: < 1e-6.
- PLY: bit-exact.
- Viewer byte: equal to the input byte, or 1 below it, never above.
- A converged single-colour fit: the viewer-decoded render is within 0.02 of the target.

**Adaptive density control**, as in the 3DGS paper and gsplat's DefaultStrategy:

- The screen-space position gradient is accumulated per Gaussian (normalised to NDC).
- Every `GSPLAT_DENSIFY_EVERY` steps in `[GSPLAT_DENSIFY_START, GSPLAT_DENSIFY_STOP)`, Gaussians above
  `GSPLAT_DENSIFY_GRAD_THRESHOLD` are cloned if small and split in two (scale / 1.6) if large.
- Gaussians are pruned below `GSPLAT_PRUNE_OPACITY`, and, after the first opacity reset, when larger than
  `GSPLAT_PRUNE_SCALE_FRACTION` × the scene extent.
- Opacities are reset every `GSPLAT_OPACITY_RESET_EVERY` steps.
- Growth never exceeds `GSPLAT_MAX_GAUSSIANS`. When there are more candidates than room, the highest-gradient ones win.
- Adam moments follow their Gaussians: new Gaussians start at zero, and removed ones are dropped.
- Every event (before, cloned, split, pruned, capped, after) is in the training report.
- Screen-size pruning is not implemented; gsplat 1.5.3's `DefaultStrategy` leaves it off by default too
  (`refine_scale2d_stop_iter = 0`). Departure from gsplat's defaults: densification stops at step 3 500
  (`GSPLAT_DENSIFY_STOP`), where `refine_stop_iter` is 15 000. This was not changed here.

**Position learning rate** (review G-3). This is the reference schedule (INRIA `get_expon_lr_func`): log-linear from
`GSPLAT_LR_POSITION` (1.6e-4) to `GSPLAT_LR_POSITION_FINAL` (1.6e-6) over `GSPLAT_LR_POSITION_DECAY_STEPS` (30 000),
then held, multiplied by the scene extent. A 7 000-iteration run therefore ends at 1.6e-4·0.01^(7/30), as the
reference's 7k snapshot does. The rate depends only on the iteration number (`splat_training.position_lr`), so a resume
follows the uninterrupted schedule exactly. That is why the horizon and the final rate cannot change on resume. Each
iteration's rate is in the report's loss history (`lr_position`).

**SH degree 0, deliberately.** The viewer asset (KSplat level 0) holds degree 0 only, so higher bands would be trained
and then dropped, and the viewer would show a colour no camera was fitted against. A re-scan splice also rotates
Gaussians, and the higher bands would need a Wigner-D rotation that does not exist here. The degree is fixed
(`SH_DEGREE = 0`). It is recorded in the checkpoint and the report. `read_ply` refuses a PLY carrying `f_rest_*`
rather than dropping the bands silently; the cleanup benchmark opts in. gsplat receives RGB (`sh_degree=None`).
gsplat 1.5.3 evaluates SH as `clamp_min(SH + 0.5, 0)`, so for degree 0 this is the same colour;
`tests/gpu/test_gsplat_cuda.py` checks it on CUDA.

**gsplat version.** The adapter (`splat_training.gsplat_rasterizer`) was written against gsplat **1.5.3**'s
`rasterization()`, read from its source distribution (PyPI sha256 `343f080c…a906`; re-read on 2026-10-10: the
signature, `packed=False` output `means2d [C, N, 2]` and `radii [C, N, 2]`, and RGB colours with `sh_degree=None`
are as the adapter assumes). The `reconstruction` extra pins `gsplat==1.5.3`. The adapter checks the output shapes
and raises `GsplatContractError` (stage code `GSPLAT_INCOMPATIBLE`) on a mismatch. The report records the installed and
the validated version.

**gsplat's CUDA kernels (a real defect, found in its source).** On PyPI, gsplat 1.5.3 is a pure-Python
`py3-none-any` wheel. Its `gsplat/cuda/_backend.py` imports the compiled `gsplat.csrc` if present. Otherwise it
JIT-compiles the kernels with nvcc on first use. With no CUDA toolkit it prints a warning and leaves `_C = None`, so
the first rasterisation fails with `AttributeError: 'NoneType' object has no attribute …`, deep inside training.
`pip install .[reconstruction]` therefore did not give a worker that can train. And on a host that did have nvcc, the
first job spent minutes compiling inside its time budget. Now:

- the GPU image builds gsplat from its sdist with the CUDA extension compiled in;
- preflight (below) loads `gsplat.csrc` and refuses to train without it (`GSPLAT_CUDA_BACKEND_MISSING`), unless
  `GSPLAT_ALLOW_JIT_BACKEND=true` is set on a development host with the toolkit.

**Preflight** (`chaya_worker.splat_preflight`). Before any archive is unpacked or frame decoded, the stage checks the
environment and reports every problem at once in `errorDetails.preflight`, each with a fix. It fails with
`DEPENDENCY_UNAVAILABLE`, and `details.missing` names what is missing. The environment checks are:

- `PACKAGE_MISSING`: torch, gsplat, numpy, scipy or OpenCV;
- `PACKAGE_VERSION_UNSUPPORTED`: gsplat ≠ 1.5.3, torch < 2.6, or Python < 3.11;
- `PACKAGE_VERSION_DRIFT`: a version differs from the image's `/opt/chaya/gpu-lock.json`;
- `TORCH_WITHOUT_CUDA`: a CPU build of torch;
- `CUDA_UNAVAILABLE`;
- `GPU_CAPABILITY_UNSUPPORTED`: below `GSPLAT_MIN_COMPUTE_CAPABILITY`, default 7.0;
- `GSPLAT_CUDA_BACKEND_MISSING`;
- `TOOL_MISSING`: COLMAP.

Then come the settings (`CONFIG_INVALID`: no iterations, densification ending before it starts, or a
`GSPLAT_MAX_GAUSSIANS` whose `.ksplat` would exceed the viewer limit), and any offered checkpoint, which is loaded
before frames are prepared. Too few SfM points or posed frames fail as `SPLAT_INIT_INSUFFICIENT`, with the frame
minimum from `GSPLAT_MIN_TRAINING_FRAMES` (default 3). `python -m chaya_worker.splat_preflight` prints the same report
and exits 0 only when training can start.

**Device selection (a real defect).** The old check accepted a worker if *any* GPU reached the minimum compute
capability, then trained on `torch.device("cuda")`, which is device 0. On a host whose device 0 is the weaker card,
training ran on the device that failed the check. Preflight now selects the most capable qualifying device, and
training runs there (`training_device` in the report).

**Failures during training** are structured stage failures, so the worker process keeps running and the report ends
the job's lease:

- `SPLAT_GPU_OUT_OF_MEMORY` (lower `GSPLAT_MAX_GAUSSIANS` or the frame resolution);
- `GPU_RUNTIME_ERROR` (a CUDA error);
- `GSPLAT_INCOMPATIBLE`;
- `SPLAT_CHECKPOINT_WRITE_FAILED` (disk);
- `CANCELLED`, `CAMERA_CONVENTION_INVALID`, `SPLAT_TRAINING_DIVERGED`.

GPU memory is released before the report. Any other exception is a bug and is reported as `INTERNAL_ERROR` with its
traceback; it is not relabelled as a GPU failure.

**Conventions and divergence.** Before the first iteration every training camera is checked: a 4×4 world-to-camera
matrix with a proper rotation (det +1; OpenCV/COLMAP axes), a pinhole K with no skew and the principal point inside
the image, and an sRGB float32 image of the camera's size. Otherwise the stage fails with `CAMERA_CONVENTION_INVALID`.
A non-finite loss or parameter stops training with `SPLAT_TRAINING_DIVERGED`. Nothing from the diverged state is
checkpointed or published.

**Time budget and status.** Before each iteration the loop checks the work order's deadline. It stops when the time
left is below `GSPLAT_STOP_MARGIN_SECONDS` (time reserved for writing and uploading outputs) plus the slowest iteration
so far.

- **COMPLETED**, all `GSPLAT_ITERATIONS` ran: the stage SUCCEEDS with `SPLAT`.
- **PARTIAL**, stopped early: the stage FAILS with `TIME_LIMIT_EXCEEDED`, `SPLAT_PARTIAL` (`partial=true`), and error
  details carrying the completed and target iterations and the Gaussian count. The run becomes PARTIAL (above).
- If zero iterations ran, no splat is published.
- Nothing is published as a splat unless it is a valid trained state. That means: at least one iteration ran, and
  every configured iteration ran for `SPLAT`; parameters are finite with consistent shapes and non-zero quaternions,
  with at least one Gaussian left; the cloud fits the viewer contract (at least `minSplatCount` splats, and a `.ksplat` of
  at most `maxBytes`, packages/contracts/viewer/ksplat-contract.json), which applies to `SPLAT_PARTIAL` as much as to
  `SPLAT`; and the PLY was written and reads back bit-identical to the trained cloud. Otherwise the stage fails with
  `SPLAT_NOT_TRAINED`, `SPLAT_INVALID`, `SPLAT_EXPORT_FAILED` (the PLY could not be written) or `SPLAT_EXPORT_INVALID`.
  It then publishes only the report, with no splat and no checkpoint, and the report's `validation` field says why.
- `SPLAT_PARTIAL` is never a viewer source: ARTIFACT_GENERATION takes only `SPLAT` (or the cleaned/merged clouds), so a
  partial result never becomes the published `.ksplat` (`tests/smoke/test_splat_artifact_smoke.py`).

In both cases `splat-training-report.json` records the status, stop reason, completed and target iterations, initial
and final Gaussian count, densification events, loss history, and provenance: the input identity, the splat and
checkpoint SHA-256, and the torch/gsplat/CUDA versions.

**Checkpoints** (`SPLAT_CHECKPOINT`, `splat-checkpoint.pt`) are written every `GSPLAT_CHECKPOINT_EVERY` iterations and
always at the end, atomically. They hold:

- the iteration;
- all parameters;
- the Adam state (moments and step counts) for each parameter;
- the density-control accumulators and history;
- the configuration, including the position learning-rate schedule;
- the format (`chaya-splat-checkpoint/2`) and the SH degree;
- the source identity: the run, scan, scan version, and the input artifacts with their SHA-256, plus a digest.

A SPLAT_RECONSTRUCTION job given a `SPLAT_CHECKPOINT` input resumes from it. Only the iteration target and checkpoint
cadence may differ. The stage refuses with `CHECKPOINT_MISMATCH`: different inputs or other settings; a format-1
checkpoint (written with a constant position rate); and an internally inconsistent one (non-finite values, row counts
that disagree, density statistics of the wrong length, Adam moments of another shape than their parameter). A file that
is not a readable checkpoint at all (truncated, not a torch archive, missing or mistyped fields, non-float32
parameters) fails with `CHECKPOINT_CORRUPT`, before any frame is prepared, instead of crashing the stage. Randomness is derived
from (seed, iteration), so a resumed run takes the same steps an uninterrupted one would have. The test asserts the
parameters are bit-identical on CPU.

**Not done yet:** the control plane does not offer a previous job's checkpoint to a new job, and a PARTIAL run cannot
be retried. So in production a checkpoint is recorded, but nothing resumes it automatically.

**What has been validated, at three levels:**

| Level | Status |
|---|---|
| Pipeline implemented | Yes: the loop, density control, checkpoints, budget, PARTIAL reporting and colour convention are in the stage. |
| Local fixture validated | **Mechanics only, on CPU.** `tests/splat` (including `test_training_lifecycle.py`: LR schedule, resume across a time-box stop, checkpoint format/consistency, camera conventions, divergence, publish validation) runs the real training loop with a small CPU test renderer (`tests/splat/renderer.py`: isotropic, not gsplat). It checks colour round trip through the viewer bytes, Gaussian count changes, clone/split/prune/cap, bit-exact resume, deadline stop, PARTIAL status, artifacts and provenance, and the report the control plane receives through the orchestrator. `tests/unit/test_splat_color.py` checks the colour chain without training. **gsplat's CUDA rasteriser has not been executed**: no NVIDIA GPU was available, and gsplat has no CPU path (its PyTorch reference still calls CUDA kernels). The GPU-marked tests were skipped: `tests/gpu/test_reconstruction_toolchain.py`, which asserts that densification changed the count, and `tests/gpu/test_gsplat_cuda.py`, which checks the gsplat version, the degree-0 colour against gsplat's own SH path, the adapter contract, and a short synthetic training run with resume on CUDA (synthetic, not a reconstruction). They have never run. |
| Real venue reconstruction validated | **No.** No capture has been reconstructed end to end (docs/E2E_VALIDATION.md). |

Added on 2026-10-10 (CPU, no NVIDIA GPU on the host):

- `tests/splat/test_splat_preflight_and_failures.py`: preflight decisions (with a stub toolchain stating what an
  environment reports), the real preflight on this host, the real stage failing `DEPENDENCY_UNAVAILABLE` through the
  orchestrator with the worker processing its next job, corrupt and inconsistent checkpoints, a training run
  interrupted mid-way resuming from its last periodic checkpoint to the bit-identical uninterrupted result, the
  OOM/CUDA/IO error mapping, export failure, and the viewer-contract size check for SPLAT and SPLAT_PARTIAL.
- `tests/smoke/test_splat_artifact_smoke.py`: training with the CPU test renderer, through the orchestrator, then the
  real ARTIFACT_GENERATION. It checks upload/read-back checksums, the `.ksplat` contract, exact positions and colour
  bytes, the manifest and the bundle. It then loads the result with the pinned viewer library
  (`apps/web/scripts/verify-ksplat-artifact.ts`).
- `tests/splat/test_splat_dataset.py`: the GPU acceptance dataset. Its binary COLMAP model was also read by real
  COLMAP (`model_converter`, `model_analyzer`: 16 images, 3168 points, mean track length 11.1).

**GPU training is still NOT VALIDATED: `tests/gpu/test_splat_acceptance.py` has not run** ("GPU acceptance" below).

### GPU worker image

`services/reconstruction/Dockerfile.gpu` contains:

- CUDA 12.6.3 on Ubuntu 24.04 with Python 3.12, base images pinned by digest;
- torch 2.7.1+cu126, sha256-checked;
- gsplat 1.5.3 built from its sha256-checked sdist with the CUDA extension, for `TORCH_CUDA_ARCH_LIST="7.0;7.5;8.0;8.6;8.9;9.0+PTX"`;
- every other Python package at an exact version: `gpu/requirements-gpu.txt`, plus the full resolved closure
  `gpu/constraints-gpu.txt`, resolved on 2026-10-10 with a pip dry run against PyPI and the cu126 index;
- Ubuntu's COLMAP.

`gpu/write_lock.py` runs the worker's own preflight during the build. The build fails unless the only problem is the
build host's missing GPU, which includes gsplat's compiled kernels failing to load against the installed torch. The
step writes `/opt/chaya/gpu-lock.json`, and a running worker refuses to train if its versions drift from it.

Host requirements: an NVIDIA GPU with compute capability ≥ 7.0, a driver supporting CUDA 12.6 (≥ 560), and the NVIDIA
Container Toolkit. The image has not been built here, because the workstation lacks the disk space for a CUDA build.
`docker build --check` passes, and both base digests resolve.

### GPU acceptance

On a GPU worker:

```
docker build -f services/reconstruction/Dockerfile.gpu -t chaya-worker-gpu services/reconstruction
docker run --rm --gpus all chaya-worker-gpu python -m chaya_worker.splat_preflight      # must print "ok": true
docker run --rm --gpus all --user 0 -e CHAYA_REQUIRE_GPU=1 -e CHAYA_GPU_ACCEPTANCE_REPORT=/out/gpu-acceptance.json \
  -v "$PWD/services/reconstruction/tests:/src/tests:ro" -v "$PWD/gpu-acceptance:/out" -w /src chaya-worker-gpu \
  sh -c "pip install pytest==8.4.1 && python -m pytest -p no:cacheprovider -m gpu tests/gpu/test_splat_acceptance.py -v -rs -s"
cd apps/web && node --experimental-strip-types scripts/verify-ksplat-artifact.ts ../../gpu-acceptance/viewer-check
```

The test runs the real SPLAT_RECONSTRUCTION through the orchestrator. It checks:

- training ran on the preflight-selected CUDA device, with gsplat 1.5.3, every iteration, and density control;
- the trained cloud renders, through gsplat, at least 3 dB PSNR above the untrained SfM seed over the training views;
- a second job resumes exactly from the first job's checkpoint;
- a corrupt checkpoint fails `CHECKPOINT_CORRUPT`;
- the SPLAT becomes a contract-valid `.ksplat`, with checksums, manifest and bundle verified.

`gpu-acceptance.json` records the device, the versions, timings and sizes. With `CHAYA_REQUIRE_GPU=1` a missing
requirement fails the test instead of skipping it.

The default dataset is SYNTHETIC: a ray-traced textured room corner with exact cameras
(`tests/gpu/splat_dataset.py`). Set `CHAYA_SPLAT_DATASET` to a real COLMAP project (`images/` and `sparse/0`) to train
on real data.

## Tests (three separate tiers)
| Tier | Where | Needs | Run |
|---|---|---|---|
| unit | `tests/unit` | nothing | `pytest tests/unit` |
| splat training mechanics | `tests/splat` | CPU PyTorch (skipped without it; CI installs it and fails on a skip) | `pytest tests/splat` |
| orchestration | `tests/orchestration` | tiny real files (FFmpeg-encoded video, a real face photo from scikit-image, JSON); test doubles only for the HTTP client and object storage | `pytest -m orchestration` |
| integration | `tests/integration` | real MinIO; the real stack (API, Postgres, MinIO, ClamAV, Keycloak) | `pytest -m integration -rs` with `CHAYA_IT_*` set |
| GPU / toolchain | `tests/gpu` | COLMAP, GLOMAP, gsplat+CUDA, a real photo folder (`CHAYA_TEST_IMAGE_SEQUENCE`) | `pytest -m gpu -rs` |

GPU tests are skipped with a printed reason when their dependency is absent; they cannot pass without running
the tool. The Java side (`mvn verify`) tests the control plane against real PostgreSQL and MinIO.

## Not verified here
COLMAP/GLOMAP command lines are unit-tested for construction only; they were not executed (not installed). COLMAP flag
names follow the 3.9/3.10 CLI. Every stage after pose estimation is implemented, but none has run on a real capture; gsplat training has not run
at all (see "Splat training"). The worker Docker image
was built but not run against the stack. MinIO credentials are shared between the API and the worker (per-service
service accounts are a later hardening step).
