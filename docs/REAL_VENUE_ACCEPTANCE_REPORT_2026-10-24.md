# Real-venue acceptance report, 2026-10-24: BLOCKED

**Result: the first measured indoor digital twin was NOT produced, and the real-venue capability is NOT validated.**

No real venue was captured, uploaded, reconstructed, calibrated or published today. So this report contains:
- no venue metadata;
- no source-media checksums;
- no stage-by-stage execution of a venue run;
- no calibration residuals;
- no GPU measurements;
- no screenshots of a reconstructed venue.

Producing any of them without a run would be fabricated, and none was produced. What follows is:
- the prerequisite check, as actually executed;
- the exact outstanding prerequisites;
- the acceptance tooling made ready for the first run (procedure: [REAL_VENUE_ACCEPTANCE.md](REAL_VENUE_ACCEPTANCE.md)).

- **Code:** `main` after `b27ef1e`, plus this milestone's commit.
- **Host:** Windows 11, Intel Core Ultra 5 125H, 16 GB RAM, Intel integrated graphics only, Docker Desktop (7.5 GiB VM),
  about 15 GB free on C:. No phone or capture device is attached.

## 1. Prerequisites, as checked

| Prerequisite | State | Evidence |
|---|---|---|
| The GPU worker can build and execute its reconstruction stage | **MISSING** | No NVIDIA device: `nvidia-smi` is absent, and `Win32_VideoController` is "Intel(R) Graphics". `Dockerfile.gpu` has never been built: it needs about 40 GB of disk, about 15 GB is free. `scripts/gpu/acceptance.sh` stops BLOCKED (exit 3, "nvidia-smi not found"). docs/pipeline.md, "GPU acceptance" |
| API, database, object storage and worker run together | **MET (CPU stages only)** | The stack smoke passed today, against the real API image, Postgres and pgvector, MinIO, ClamAV, Keycloak and the real worker code (docs/TEST_REPRODUCIBILITY.md, 2026-10-24). Only CPU stages can be served here |
| A real indoor venue, accessible with permission | **MISSING** | No venue was named and no authorization exists. There is no consent record |
| A supported capture device and media | **MISSING** | No capture exists on this workstation: `%USERPROFILE%\Videos\Captures` holds only `desktop.ini`. No phone is attached |
| ≥ 2 tape-measured distances or ≥ 3 surveyed control points, with uncertainty | **MISSING** | No measurement sheet exists |
| Venue, room, floor and capture-session metadata | **MISSING** | It cannot exist without a capture. The template is `scripts/acceptance/templates/capture-metadata.example.json` |
| Storage and GPU time for a small run | **PARTLY**: storage yes, GPU time no | About 15 GB local disk. No GPU host, GPU budget or self-hosted `gpu` runner |

The harness's prerequisite step was run on this workstation:

```
python scripts/acceptance/real_venue.py --api http://localhost:18080 --token-file <scratch>/op.token \
  --venue none --floor none --media "%USERPROFILE%\Videos\Captures" --thresholds <scratch>/thresholds.json \
  --out <scratch>/out --check-prerequisites
```

It exited **5 (BLOCKED)**:

| Check | Status | Detail |
|---|---|---|
| API reachable | FAIL | no API on this workstation at the time (`WinError 10061`): the stack was not left running |
| capture metadata | FAIL | `--capture-metadata` is required |
| consent and authorization | FAIL | `--consent` is required |
| measurements | FAIL | `--measurements` is required: ≥ 2 distances or ≥ 3 control points |
| capture media | FAIL | 0 videos, 0 images |
| pre-registered thresholds | PASS | the template's 7 thresholds |
| local disk | PASS | about 15 GB free |

The worker check (does a live worker offer POSE_ESTIMATION and SPLAT_RECONSTRUCTION?) did not run here, because there
was no API. Its behaviour on the real situation, where only the CPU worker is live, is pinned by
`test_no_gpu_worker_blocks_the_run_before_any_upload`.

## 2. Outstanding prerequisites, in the order they unblock the run

1. **A CUDA host**:
   - an L4 or A10-class GPU, driver ≥ 560.28, NVIDIA Container Toolkit, ≥ 40 GB disk;
   - `bash scripts/gpu/acceptance.sh` must exit 0 on it;
   - attach it to the deployed stack as the GPU worker, with `/models` filled.
2. **A deployed stack** reachable from that host (`infra/deploy`; DEPLOYMENT.md §3), and a venue manager's token.
3. **A venue and written authorization**:
   - `consent.json`: who authorized it, when, the scope, and the people policy;
   - screens and documents reviewed before upload.
4. **The capture**:
   - a phone video (or ≥ 20 overlapping photos) of one textured room, from several heights and angles;
   - the device and settings recorded in `capture.json`.
5. **Measurements**:
   - ≥ 2 tape or laser distances for calibration, plus at least one held-out check distance;
   - each with its uncertainty, and each end point marked in at least two media.
6. **Thresholds**: copy `scripts/acceptance/templates/thresholds.example.json` unchanged, or change it **before** the
   run. It is frozen by its SHA-256 at the first run.

Then follow [REAL_VENUE_ACCEPTANCE.md](REAL_VENUE_ACCEPTANCE.md), "The run". Keep the data outside Git
("Where the data lives").

## 3. What was done instead: the tooling for the first run

| Change | Why (observed failure or requirement) | Tested by |
|---|---|---|
| **Token renewal**: the token file is re-read when it changes; on a 401 the token is renewed once by `--token-command` or a re-read | The 2026-10-19 ETH3D run: the harness read its token once, and every run longer than the 5-minute token failed (VALIDATION D4) | `test_an_expired_token_is_renewed_…`, `test_a_token_file_rewritten_…`, `test_a_token_that_stays_invalid_…` |
| **A resume continues the same report**: sections are kept, and sessions are listed | Same run: `--resume` rewrote `report.json` without its `media` section (D4) | `test_a_resume_keeps_the_report_sections_…` |
| **An unreachable API is a structured BLOCKED**, not a traceback | Found today, by running the prerequisite step with no stack: `URLError` escaped every handler, and `report.json` had no `result` | `test_an_unreachable_api_is_a_blocked_prerequisite_…` |
| **Prerequisite step**, exit 5 BLOCKED before any upload: workers offering every plan stage, consent, metadata, measurements (count, uncertainty, ≥ 2 media per point), media, thresholds, disk; unfilled template placeholders refused | No upload of an unauthorized or under-measured capture, and no run against a stack that cannot reconstruct | `test_no_gpu_worker_blocks_…`, `test_a_missing_prerequisite_is_blocked_and_named` (4 cases), `test_measurements_need_…`, `test_no_privacy_needs_…`, `test_unfilled_templates_never_pass_…` |
| **Source manifest**: SHA-256 of every source file before upload; on resume a changed file is refused | Preserve and identify the original media | `test_a_resume_refuses_changed_source_media` |
| **Pre-registered thresholds**: frozen by SHA-256, an edited file refused, unmeasured = FAIL, and a failed threshold turns VERIFIED into FAILED | Thresholds fixed before looking at the metrics, never adjusted afterwards | `test_thresholds_cannot_be_edited_…`, `test_an_unmeasured_threshold_fails_…`, `test_the_threshold_inputs_come_from_…` |
| **Independent scale check**: `"role": "check"` distances are recorded but never sent to the calibration; their reconstructed length × the calibrated scale is compared with the tape | Compare the scale with measurements independent of it | `test_the_scale_is_checked_only_against_measurements_kept_out_…` |
| **Stage table** (`stages.json`, `stages.md`): inputs, outputs, tools, timing, exit status, error code, quality numbers; plus the run's resource usage | The per-stage record the acceptance needs | `test_the_stage_table_records_…` |
| **API: `lastRun.inputs`**: each stage attempt's input artifacts (id, kind, producing stage, sealed SHA-256, size), as the worker reported reading them, restricted to the venue's own artifacts; additive in `/api/v1` (`InputArtifactView`) | `pipeline_stage_run.input_artifact_ids` was recorded but not served, so a run's provenance could not be read through the API | `PipelineControlPlaneTest.eachAttemptListsTheArtifactsItRead…`; `OpenApiContractTest` (contract regenerated) |
| **Worker command records**: POSE_ESTIMATION adds `registered` and `frames`; SPLAT_RECONSTRUCTION adds the quality gate (passed, basis, PSNR gain, loss ratio), training seconds and peak GPU memory | The thresholds must be measured on what the API reports, not on files only the worker sees | `tests/splat`, `tests/unit/test_reconstruction_pipeline.py` |
| **Templates**: capture metadata, consent, measurements, thresholds | A repeatable sequence for another venue | `test_the_threshold_and_measurement_templates_are_valid_inputs` |
| **Collinearity & transverse spread gate**: control points must span ≥ 2.0 m and maintain ≥ 0.5 m transverse spread off any line | Near-collinear points leave rotation about the line unconstrained | `CoordinateFrameCalibrationTest.controlPointsWithSmallTransverseSpreadAreRefused` |
| **Duplicate reference rejection**: distance references must have distinct endpoints and labels | Duplicate references cannot bypass the minimum reference count requirement | `CoordinateFrameCalibrationTest.duplicateDistanceReferencesAreRefused` |
| **Holdout validation independence**: holdout distances and control points must not overlap with calibration fit references | Holdout accuracy claims must be measured on observations independent of fitting | `CoordinateFrameService.validateHoldouts` |
| **Guidance evidence requirement**: routing and physical AR require frames calibrated from triangulated observations (`point_source = TRIANGULATED_OBSERVATIONS`) | Hand-selected points (`OPERATOR_SUPPLIED`) must not advertise metric route distances or physical AR guidance without measured evidence | `RouteService.requireMetricFrame`, `AnchorService.requireCurrentFrame` |
| **Downstream artifact retirement**: active navigation graphs in superseded frames are automatically retired (`status = 'RETIRED'`) on recalibration | Recalibrated floors must not route on graphs baked in an old coordinate frame | `CoordinateFrameCalibrationTest.recalibratingTheSameReconstructionSupersedesTheFrameAndReprojectsPoisAndAnchorsExactly` |
| `.gitignore`: `/acceptance-*/`, `/gpu-acceptance-*/`, `/venue-data/` | Real media and run outputs stay out of Git | — |

## 4. Not claimed

The following are not claimed:
- real-venue execution;
- GPU execution;
- calibration accuracy on a venue;
- reconstruction quality;
- search relevance;
- route validity;
- viewer rendering of a real venue;
- device or AR behaviour.

The capability stays at the level docs/BASELINE_AUDIT_2026-10-24.md records: **fixture-validated**, with real public
photographs up to SfM. The first real run must be reported as a new dated record, with the run directory's
`report.json`, `stages.md` and `source-manifest.json`.
