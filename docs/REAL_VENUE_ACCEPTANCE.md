# Real-venue acceptance

This document is the procedure that proves the capture-to-digital-twin workflow on a **real indoor venue**. It also says
what the repository's tests do and do not prove without one. The harness is `scripts/acceptance/real_venue.py`, and it
uses the production API only.

**Status (2026-10-24): never run. BLOCKED.** The outstanding prerequisites are recorded in
[REAL_VENUE_ACCEPTANCE_REPORT_2026-10-24.md](REAL_VENUE_ACCEPTANCE_REPORT_2026-10-24.md):
- a CUDA GPU worker;
- a permitted venue;
- a capture;
- tape-measured references;
- a consent record.

*Earlier record (2026-10-10):* never run. No measured indoor capture existed, the GPU image had not been built, and
SPLAT_RECONSTRUCTION had never trained on CUDA. That is still true.

## Fixture tests and real-input tests are separate

| Kind | Where | What it proves | What it does not prove |
|---|---|---|---|
| Control-plane contract (fixture worker) | `services/api` `PipelineOutputContractTest`, `PipelineControlPlaneTest`, `ScanVersionPublicationTest`, … via `PipelineTestSupport` | Every stage's required outputs (`packages/contracts/pipeline/stage-artifacts.json`); the failures, retries and publication rules; search and routing on what a run published. The fixture worker publishes the **real kinds** and the real shapes of the documents the control plane reads. Like the real worker, it fails the metric stages with `NOT_CALIBRATED` without a frame. | Its bytes are placeholders, and its navmesh is the synthetic Recast venue. It is not a reconstruction. |
| Worker contract | `services/reconstruction` `tests/unit/test_stage_artifact_contract.py` | What each stage reads and writes, from its source; every plan is satisfiable (privacy on or off, full or incremental); every planned stage is claimed by a shipped worker. | Nothing about a stage's output quality. |
| Recast fixtures | `packages/contracts/fixtures/navmesh`, `tests/navmesh` | The real Recast/Detour tool on a synthetic venue. | A real venue's walkable surface. |
| Viewer fixtures | `packages/contracts/fixtures/ksplat`, `apps/web/lib/ksplat-compat.test.ts`, `e2e/ksplat-viewer.spec.ts` | The encoder, checked against the pinned viewer library, and rendering in a browser. | A real `.ksplat`. |
| CPU splat smoke | `tests/smoke/test_splat_artifact_smoke.py` | Training plumbing (CPU **test renderer**, not gsplat), then ARTIFACT_GENERATION, then the viewer library. | gsplat training. |
| GPU acceptance | `scripts/gpu/acceptance.sh`, `tests/gpu/test_splat_acceptance.py` | Real gsplat on CUDA, on 24 real CC0 photographs (docs/pipeline.md, "GPU acceptance"). | A venue: it is an outdoor rock face. **Not run**: no CUDA host. |
| Harness rules | `tests/unit/test_real_venue_harness.py` | The harness's own behaviour: prerequisites BLOCK, tokens renew, a resume keeps the report, thresholds are frozen, the scale is checked independently. | Anything about a venue. |
| **Real-venue acceptance** | `scripts/acceptance/real_venue.py` | The whole workflow on your capture, through the production API. | **Not run**: no capture, no GPU. |

None of these substitutes for another. A passing fixture test is never reported as a real-venue result.

## Prerequisites (step 0 checks each one, and BLOCKS the run without it)

| Prerequisite | How it is checked |
|---|---|
| A deployed stack (`infra/deploy`) whose CPU worker serves the stages before privacy, REGION_SPLICE, ARTIFACT_GENERATION and NAVIGATION_BAKING | `GET /version`; the venue and floor exist |
| A GPU worker from `Dockerfile.gpu` that passed `bash scripts/gpu/acceptance.sh` and serves every other stage, with `/models` filled (`fetch_models.py`) | `GET /venues/{v}/ops/jobs`: a live worker offers every stage of the plan. Workers offer only what they can execute (docs/pipeline.md, "Which worker serves which stage"). This needs a venue manager's token; otherwise UNVERIFIED |
| Written permission to capture the venue, and a decision on people and sensitive material | `--consent`: `authorized_by`, `role`, `date`, `scope`, `people_policy` (`NO_PEOPLE_PRESENT`, `CONSENT_OBTAINED` or `BLURRED_BY_PIPELINE`), `sensitive_material_reviewed: true`. `--no-privacy` is refused unless `NO_PEOPLE_PRESENT` |
| Capture metadata | `--capture-metadata`: venue name, room, floor label, operator, device (model, OS, app), capture start and end, settings, orientation assumptions, and the sensors the media do **not** carry |
| Measured references | `--measurements`: at least 2 tape-measured distances or 3 surveyed control points for calibration. Each needs an `uncertainty`, and each point must be marked in at least 2 media. Add at least one `"role": "check"` distance, which is kept out of the calibration (the independent scale check) |
| Supported media | `--media`: `.mp4 .mov .webm .mkv` video, or at least 20 `.jpg .jpeg .png .heic` photos, plus an optional camera-calibration `.json` (docs/capture-ingestion.md) |
| Thresholds fixed before the run | `--thresholds` (below) |
| Local disk | twice the media size free under `--out` |

Every unfilled `"<...>"` placeholder from the templates is refused.

## Inputs

Start from `scripts/acceptance/templates/`:
- `capture-metadata.example.json`
- `consent.example.json`
- `measurements.example.json`
- `thresholds.example.json`

Fill them in for the venue, and keep the copies with the capture, not in Git.

**Measurements** are the bodies of `POST …/captures/{id}/measurements` (docs/capture-calibration.md), with two changes:
- each observation has `"media": "<file name>"` instead of a `mediaId`;
- `"role": "check"` marks an independent check distance. It is recorded in the API too, but its points are never sent
  to the calibration.

**Thresholds** are fixed before the run. The harness records the file's SHA-256 at the first run and refuses an edited
file on `--resume`. A threshold whose metric was not measured is `NOT_MEASURED`, and that **fails** it.

| Threshold | Measured on | Template value (chosen 2026-10-24, before any real run) |
|---|---|---|
| `min_registered_share` | POSE_ESTIMATION's command record: `registered / frames` | 0.75 |
| `max_scale_relative_spread` | the active coordinate frame's `scaleRelativeSpread` | 0.03 (the API's own refusal limit) |
| `max_control_point_rms_m` | the frame's `controlPointRmsM` (control points only) | — |
| `max_check_distance_relative_error` | worst `\|scale × reconstructed − measured\| / measured` over the check distances | 0.02 |
| `require_quality_gate` | SPLAT_RECONSTRUCTION's `quality.passed` (held-out views) | true |
| `min_psnr_gain_db` | SPLAT_RECONSTRUCTION's `quality.psnr_gain_db` over the SfM seed | 1.0 |
| `require_canonical_frame` | the published reconstruction's frame is canonical (metric and gravity-aligned) | true |
| `min_pois` | POIs of the published version | 1 |

## The run

```
# 0. Prerequisites only: exit 0 ready, 5 BLOCKED with every blocker named (nothing is uploaded)
python scripts/acceptance/real_venue.py --api https://api.<domain> --token-file operator.token \
  --token-command "<prints a fresh access token>" --venue <venueId> --floor <floorId> \
  --media <capture dir> --measurements measurements.json --capture-metadata capture.json --consent consent.json \
  --thresholds thresholds.json --out <run dir> --check-prerequisites

# 1-4. The run: upload, processing, then it stops at calibration (exit 3) and writes calibration-needed.json
python scripts/acceptance/real_venue.py <the same arguments without --check-prerequisites> \
  --search "exit sign" --search "reception desk" --route-from 1.0,2.0,0.0 --route-to "exit sign" --viewer-check

# Open the run's reconstruction and read off each measured point's reconstruction coordinates, check points included,
# into resolved.json:
#   {"points": {"door B2 width": {"A": [x, y, z], "B": [x, y, z]}, ...}, "gravity": {"source": "RECONSTRUCTED_FLOOR_PLANE"}}
python scripts/acceptance/real_venue.py <the same arguments> --resume --resolved-points resolved.json
```

Run notes:
- **Token renewal.** Access tokens live 5 minutes and a run takes hours. Give `--token-command` (its stdout is a fresh
  token; it runs on a 401 and rewrites `--token-file`), or keep `--token-file` refreshed externally (it is re-read when
  it changes). Before 2026-10-24 the token was read once, so a run longer than its lifetime failed (VALIDATION_2026-10-19
  D4).
- **Resume.** `--resume` continues the same capture and the same `report.json`: earlier sections are kept, and each
  session is listed. The source files must be unchanged (`source-manifest.json`).
- **Retries.** The script never retries a failed stage on its own: `--retry-failed N` allows N retries of retryable
  failures.
- **What it never does.** It never bypasses a refusal, never injects an artifact, and never publishes anything itself.
  A run that is failed, uncalibrated or `PARTIAL` stays visibly non-final in the API, and the harness reports it as
  such.

Exit status: 0 verified; 1 the workflow or a threshold failed; 2 bad arguments, access or changed inputs; 3 calibration
points needed; 4 timed out; 5 BLOCKED.

## What the run records (`<run dir>`)

| File | Content |
|---|---|
| `report.json` | `prerequisites` (each check PASS, FAIL or UNVERIFIED); `captureMetadata`, `consent`, `thresholdsSha256`; `media` (SHA-256, size, verdict, resumed parts, part retries, decoded size); `measurements` (role, metres, uncertainty); `timeline` (every run and stage state change, with job, worker, lease and retries); `failure`; `calibration` (the attempt and the measurements it used); `coordinateFrame`; `checkDistances` (reconstructed vs measured, with the tape's uncertainty); `artifacts`; `downloads` (re-hashed); `manifest`; `publication`; `reconstruction`; `pois`; `search`; `routes`; `viewerCheck`; `thresholds` (limit, measured, PASS, FAIL or NOT_MEASURED); `resources` (wall and GPU seconds of the scan version, worker usage, rate card); `tokenRenewals`; `result` |
| `source-manifest.json` | every source medium and input file: size, SHA-256, modification time (written before upload) |
| `stages.json`, `stages.md` | per stage: the last attempt and worker, start, end, duration, exit status, error code, **inputs** (the artifacts it read: id, kind, producing stage, SHA-256, from the API's `lastRun.inputs`, since 2026-10-24), outputs (id, kind, SHA-256, size), tool versions, and quality numbers (POSE_ESTIMATION: registered and frames; SPLAT_RECONSTRUCTION: iterations, Gaussians, the quality gate, training seconds, peak GPU memory) |
| `calibration-needed.json` | the measured points to resolve, when the run stopped for calibration |
| `artifacts/`, `viewer-check/` | the viewer's artifacts as served, and the pinned viewer library's verdict on the `.ksplat` |

Screenshots of the reconstructed venue have two sources:
- the acceptance browser check (`scripts/e2e/viewer_format_check.cjs`, as `scripts/acceptance/run-acceptance.sh` runs
  it), against the deployed web app;
- or a screen recording of `/viewer` on the published version.

Keep them with the run directory.

**Geometric accuracy is not visual plausibility.** A model can render convincingly at the wrong scale, or with a
warped floor. Only `checkDistances`, the frame's residuals and walked routes measure geometry. Report them separately
from screenshots.

## Where the data lives

The media, the run directory, the measurement sheet and the consent record **never go into Git**:
- they may show people, screens or documents;
- they are large.

`/acceptance-*/`, `/gpu-acceptance-*/` and `/venue-data/` are ignored at the repository root. Use a local path outside
the repository, for example `D:\chaya-venue-data\<venue>\<YYYY-MM-DD>\{capture,run}`, or a private bucket with access
limited to the team. Delete it at the end of the consent's retention.

The report committed afterwards may contain:
- checksums, counts, metrics and timings;
- reviewed screenshots without people.

It may never contain media.

## The boundary that remains unverified

1. **SPLAT_RECONSTRUCTION on CUDA.** gsplat 1.5.3 training has never executed. `scripts/gpu/acceptance.sh` is the
   check, on a GPU host.
2. **The GPU worker image.** It has not been built: there was no disk space for a CUDA build, and no GPU.
   `docker build --check` passes.
3. **SEMANTIC_SEGMENTATION, GEOMETRIC_CLEANUP, PLANE_FITTING, REGION_ALIGNMENT, SEMANTIC_INDEXING with their real
   models.** The model revisions are pinned in the image, but they have not been loaded from `/models`.
4. **Calibration on a real reconstruction.** The measured points are picked by hand: the marked pixels are not
   triangulated automatically (docs/capture-calibration.md).
5. **Search relevance and route quality on a real venue.** They have been tested on fixtures only.
6. **The harness end to end.** The 2026-10-19 ETH3D run used the earlier harness up to SfM
   (docs/VALIDATION_2026-10-19.md). The current one is unit-tested (`tests/unit/test_real_venue_harness.py`), and its
   prerequisite step has run on this workstation (BLOCKED). It has not run a venue.
