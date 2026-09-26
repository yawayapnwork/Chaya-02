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
| 1 | `INPUT_VALIDATION` | verifies every raw file against its recorded checksum, decodes video/images, parses metadata | **executes** |
| 2 | `FFMPEG_PREPROCESS` | FFmpeg frame extraction (fps, max height, frame cap), image normalisation (drops EXIF) | **executes** |
| 3 | `FRAME_QUALITY_FILTER` | drops blurred (Laplacian variance), under/over-exposed and near-duplicate frames, with reasons | **executes** |
| 4 | `PRIVACY_PREPROCESS` | face and screen/document detection, blurring, verification pass; fails closed | **executes** |
| 5 | `POSE_ESTIMATION` | COLMAP feature extraction + matching, GLOMAP mapper, COLMAP mapper fallback, poses.json | implemented; **needs COLMAP** (GLOMAP optional); fails with `DEPENDENCY_UNAVAILABLE` here |
| 6 | `SPLAT_RECONSTRUCTION` | gsplat training (Adam, L1+D-SSIM) seeded from the SfM point cloud, with adaptive density control (clone/split/prune, capped), a time-boxed loop, resumable checkpoints and one colour convention to the viewer; see "Splat training" below | implemented; **needs torch + gsplat + CUDA + COLMAP**; fails with `DEPENDENCY_UNAVAILABLE` here. Training mechanics validated on CPU with a test renderer; **gsplat training has never run** |
| 7 | `SEMANTIC_SEGMENTATION` | per-frame SegFormer (or any configured HF model) segmentation, projected onto the splat and bucketed into floor/wall/furniture/clutter | implemented; **needs torch + transformers + the model cached locally + COLMAP**; fails with `DEPENDENCY_UNAVAILABLE` here |
| 8 | `GEOMETRIC_CLEANUP` | Open3D statistical + radius outlier removal (radius a multiple of the cloud's own median nearest-neighbour spacing: scale-invariant, not metres), then semantic class-aware filtering (never opacity alone) | implemented; **needs Open3D**; fails with `DEPENDENCY_UNAVAILABLE` here |
| 9 | `PLANE_FITTING` | iterative Open3D RANSAC plane extraction (spacing-relative inlier distance); floor/ceiling/wall classification against the calibrated frame's up or this reconstruction's `GRAVITY_ESTIMATE` (floor plane oriented by the cameras; [coordinate-frames.md](coordinate-frames.md)) | implemented; **needs Open3D**; fails with `DEPENDENCY_UNAVAILABLE` here |
| 10 | `ARTIFACT_GENERATION` | `.ksplat` conversion (the level-0 KSplat layout of the pinned viewer, @mkkellogg/gaussian-splats-3d 0.4.7; see "Viewer asset format" below), the artifact manifest, the compressed viewer bundle | implemented; only needs the cleaned splat as input |
| 11 | `SEMANTIC_INDEXING` | Grounding DINO open-vocabulary detection (stock checkpoint, not fine-tuned) + 3D localisation against the splat + real CLIP embeddings, positions in canonical metres; see [docs/search.md](docs/search.md) | implemented; **needs a calibrated coordinate frame** (else `NOT_CALIBRATED`) **and torch + transformers + open_clip + Pillow + the Grounding DINO checkpoint cached locally + COLMAP** |
| 12 | `NAVIGATION_BAKING` | observed-floor occupancy grid + wall/furniture obstacle boxes in canonical metres, a Detour navmesh baked by the real Recast/Detour library (chaya-navmesh, recastnavigation 1.6.0) through the single Recast axis boundary, NAVMESH + NAVMESH_MANIFEST provenance, STANDARD/STEP_FREE routing graphs from the Detour polygon links; see [docs/navigation.md](docs/navigation.md) | implemented; **needs a calibrated coordinate frame** (else `NOT_CALIBRATED`) **and chaya-navmesh** (else `NAVMESH_TOOL_UNAVAILABLE`; the CPU worker image includes it) |

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
`NAVIGATION_BAKING` re-run on the spliced result. See [docs/rescan.md](docs/rescan.md) for the full design,
including the alignment confidence quality gate and how navigation/search updates are scoped to only the
changed region.

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
- Only an administrator may run a pipeline with privacy preprocessing disabled; it is recorded on the run.
- **Limits, stated plainly:** detection is classical computer vision (OpenCV Haar cascades for faces; a quadrilateral
  heuristic for screens/documents that over-blurs and misses tilted or dim ones). It is a strong first line, not a
  guarantee; a learned detector can replace either behind the `RegionDetector` interface. Raw uploads in the raw
  bucket are not touched by this stage.

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
and OpenCV only. A GPU worker image needs COLMAP, GLOMAP, PyTorch/CUDA and gsplat on a CUDA base; set `COLMAP_BIN` /
`GLOMAP_BIN` if they are not on PATH.

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
- Not implemented from the reference: position learning-rate decay, higher SH degrees, and screen-size pruning.

**Time budget and status.** Before each iteration the loop checks the work order's deadline. It stops when the time
left is below `GSPLAT_STOP_MARGIN_SECONDS` (time reserved for writing and uploading outputs) plus the slowest iteration
so far.

- **COMPLETED**, all `GSPLAT_ITERATIONS` ran: the stage SUCCEEDS with `SPLAT`.
- **PARTIAL**, stopped early: the stage FAILS with `TIME_LIMIT_EXCEEDED`, `SPLAT_PARTIAL` (`partial=true`), and error
  details carrying the completed and target iterations and the Gaussian count. The run becomes PARTIAL (above).
- If zero iterations ran, no splat is published.

In both cases `splat-training-report.json` records the status, stop reason, completed and target iterations, initial
and final Gaussian count, densification events, loss history, and provenance: the input identity, the splat and
checkpoint SHA-256, and the torch/gsplat/CUDA versions.

**Checkpoints** (`SPLAT_CHECKPOINT`, `splat-checkpoint.pt`) are written every `GSPLAT_CHECKPOINT_EVERY` iterations and
always at the end, atomically. They hold:

- the iteration;
- all parameters;
- the Adam state (moments and step counts) for each parameter;
- the density-control accumulators and history;
- the configuration;
- the source identity: the run, scan, scan version, and the input artifacts with their SHA-256, plus a digest.

A SPLAT_RECONSTRUCTION job given a `SPLAT_CHECKPOINT` input resumes from it. Only the iteration target and checkpoint
cadence may differ; different inputs or other settings are refused with `CHECKPOINT_MISMATCH`. Randomness is derived
from (seed, iteration), so a resumed run takes the same steps an uninterrupted one would have. The test asserts the
parameters are bit-identical on CPU.

**Not done yet:** the control plane does not offer a previous job's checkpoint to a new job, and a PARTIAL run cannot
be retried. So in production a checkpoint is recorded, but nothing resumes it automatically.

**What has been validated, at three levels:**

| Level | Status |
|---|---|
| Pipeline implemented | Yes: the loop, density control, checkpoints, budget, PARTIAL reporting and colour convention are in the stage. |
| Local fixture validated | **Mechanics only, on CPU.** `tests/splat` runs the real training loop with a small CPU test renderer (`tests/splat/renderer.py`: isotropic, not gsplat). It checks colour round trip through the viewer bytes, Gaussian count changes, clone/split/prune/cap, bit-exact resume, deadline stop, PARTIAL status, artifacts and provenance, and the report the control plane receives through the orchestrator. `tests/unit/test_splat_color.py` checks the colour chain without training. **gsplat's CUDA rasteriser has not been executed**: no NVIDIA GPU was available, and gsplat has no CPU path (its PyTorch reference still calls CUDA kernels). The GPU-marked test (`tests/gpu`, now also asserting that densification changed the count) was skipped. |
| Real venue reconstruction validated | **No.** No capture has been reconstructed end to end (docs/E2E_VALIDATION.md). |

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
