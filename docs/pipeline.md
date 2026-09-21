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
| 6 | `SPLAT_RECONSTRUCTION` | gsplat training | **not implemented**: `DEPENDENCY_UNAVAILABLE` (torch, gsplat, CUDA) or `STAGE_NOT_IMPLEMENTED` |
| 7-12 | segmentation, Open3D cleanup, plane fitting, artifact generation, navigation baking, semantic indexing | | **not implemented**: same structured errors |

There is no code path that produces a `.ply`/`.splat`/`.ksplat`/mesh without the real algorithm. Stages 6-12
check their dependencies (so the error names what to install) and then fail with `STAGE_NOT_IMPLEMENTED`;
they can never return success. Consequently **no run can currently finish as `SUCCEEDED`**; on this milestone
every run ends `FAILED` at the first stage that cannot run, which is the intended honest behaviour.

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

## Time-boxed reconstruction
Each run has `time_budget_seconds` (default 3600, max 86400) and a `deadline_at`. Work orders carry the deadline;
external commands are killed when it passes (`TIME_LIMIT_EXCEEDED`). The control plane never hands out a job of an
expired run. When the budget is spent:
- after a reconstruction (`SPLAT`) artifact exists: run `PARTIAL`, quality `PARTIAL`, message states it is not finalized;
- otherwise: run `FAILED`, `TIME_LIMIT_EXCEEDED`.
A worker may also report a stage that hit the limit while holding a checkpoint (`failed` + `TIME_LIMIT_EXCEEDED`
+ an artifact with `partial=true`); only then is the run `PARTIAL`. The UI shows PARTIAL with its own badge and the
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

## Tests (three separate tiers)
| Tier | Where | Needs | Run |
|---|---|---|---|
| unit | `tests/unit` | nothing | `pytest tests/unit` |
| orchestration | `tests/orchestration` | tiny real files (FFmpeg-encoded video, a real face photo from scikit-image, JSON); test doubles only for the HTTP client and object storage | `pytest -m orchestration` |
| integration | `tests/integration` | real MinIO; the real stack (API, Postgres, MinIO, ClamAV, Keycloak) | `pytest -m integration -rs` with `CHAYA_IT_*` set |
| GPU / toolchain | `tests/gpu` | COLMAP, GLOMAP, gsplat+CUDA, a real photo folder (`CHAYA_TEST_IMAGE_SEQUENCE`) | `pytest -m gpu -rs` |

GPU tests are skipped with a printed reason when their dependency is absent; they cannot pass without running
the tool. The Java side (`mvn verify`) tests the control plane against real PostgreSQL and MinIO.

## Not verified here
COLMAP/GLOMAP command lines are unit-tested for construction only; they were not executed (not installed). COLMAP flag
names follow the 3.9/3.10 CLI. gsplat and every stage after pose estimation are unimplemented. The worker Docker image
was built but not run against the stack. MinIO credentials are shared between the API and the worker (per-service
service accounts are a later hardening step).
