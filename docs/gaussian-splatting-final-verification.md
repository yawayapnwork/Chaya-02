# Gaussian Splatting: Final End-to-End Verification (2026-10-19)

Question: can Chaya-02 turn a valid real COLMAP/GLOMAP reconstruction and its posed images into a trained Gaussian
Splatting model and validated downstream artifacts?

**Answer: not demonstrated.** Real gsplat CUDA training has never executed, on any machine. This host has no NVIDIA
GPU, and the GPU image cannot be built here (disk). No real-scene sparse model with its images is available on this
host. Everything around the training step is implemented and tested, mostly with synthetic data and a CPU test
renderer in place of gsplat, and every check that can run here passed. **Real-scene reconstruction is NOT verified.**

Companion documents (same day):
- `gaussian-splatting-audit.md`: code audit; defects D-1 to D-7
- `gaussian-splatting-test-baseline.md`: CPU baseline and post-fix results
- `gaussian-splatting-gpu-environment.md`: GPU image and host

## Re-verification after D-3 to D-6 (2026-10-23)

HEAD `6929948`, plus the V-2 fix (audit, "Verification pass"). Exact counts are in `gaussian-splatting-test-baseline.md`
§8. **The answer above is unchanged: real-scene reconstruction is NOT verified, and GPU runtime verification is
BLOCKED BY HARDWARE** (Intel graphics only, no `nvidia-smi`). Sections 1–11 below are the 2026-10-19 record. Where
they say D-3 to D-6 are open, this section supersedes them.

**1. CPU-verified.** Worker suite, torch 2.14: 731 passed, 0 failed, 0 errors, 64 skipped (not run). Splat suites
re-run on the pinned torch 2.7.1 CPU build: 149 passed, 0 skipped. With the CPU test renderer and synthetic scenes:
- Resume on the worker side (D-3): `resume_state`, refusal of a corrupt or mismatched checkpoint, bit-exact resume.
- The worker downloading only the declared inputs (D-4).
- The quality gate (D-5):
  - the held-out split, and that training never renders a held-out frame;
  - PSNR over valid pixels, compared with the SfM seed;
  - loss progress, the UNVERIFIED and INSUFFICIENT verdicts, and no SPLAT on refusal;
  - PARTIAL staying PARTIAL;
  - the seed kept in host memory (V-2).
- Checkpoint persistence (D-6):
  - disk full and permission denied, using torch's real serializer;
  - atomic writes, temporary-file cleanup and the fallback checkpoint for PARTIAL;
  - export and read-back failures publishing nothing.

**2. Integration-verified.**
- Java control plane on real PostgreSQL and MinIO (Testcontainers), full `mvn verify`: 668 tests, 0 failures,
  0 errors, 4 skipped (opt-in ClamAV and Keycloak live tests). This covers:
  - a time-boxed attempt registered PARTIAL, and its retry resuming the checkpoint to a FINAL run (simulated worker
    reports);
  - refused checkpoints never offered again, and no checkpoint crossing a run, scan or tenant;
  - the V43 transition guard;
  - work orders that offer only declared inputs (`SplatResumeControlPlaneTest`, 12).
- Worker orchestrator with on-disk storage: all-or-nothing upload (`STORAGE_ERROR`, nothing registered), and a
  failed training registering nothing.
- **Not run:** the 43 live-stack integration and acceptance tests (no stack configured).

**3. GPU-verified. None.** 0 of 19 `tests/gpu` tests ran: 4 need gsplat with CUDA, 1 is the acceptance test, 3 need
real photos and 11 need Open3D. The GPU image has never been built (≥ 30–40 GB needed, 4.9 GB free). The pinned
inputs were re-checked without building: `docker build --check` is clean, the base digests resolve, and the gsplat
sdist hash is OK.

**4. Real-scene-verified. None for splat training.** No real COLMAP project with images is on this host. The only
real-data evidence is still the 2026-10-19 ETH3D run up to SfM, plus the camera check in section 4.

**5. Remaining unverified:**
- gsplat training, density control and resume on CUDA (P-1, P-6).
- What held-out PSNR and gain over the seed a real capture reaches. The D-5 thresholds are floors, not measurements.
- Whether the time-budget margin covers evaluation, checkpoint, PLY and upload on a real capture (P-3). The D-5
  evaluation adds work after the training loop, and it is unmeasured.
- A real full disk or permission failure during a checkpoint write. It was injected at the file boundary.
- The live stack (43 tests) and the GPU image build (P-5).
- The run status of a reopened PARTIAL run whose resumed attempt fails other than by the time budget (audit V-1). It
  is open, and it is not covered by any test.

## Verdict by level

| Level | Verdict | Basis |
|---|---|---|
| **Unit-tested** | **YES**, on CPU, synthetic data | 641 passed, 0 failed (CI's CPU selection). Training loop, loss and masks, density control, checkpoints, time budget, publish gate, PLY round trip, colour, .ksplat, preflight logic, the gsplat adapter's own logic against a stand-in module |
| **Integration-tested** | **PARTLY**: worker ↔ storage ↔ control plane, without gsplat | Worker orchestrator with on-disk storage: training *test stages* through `publish_outcome`, uploads and reports, SUCCEEDED and TIME_LIMIT_EXCEEDED/PARTIAL. The real `SplatReconstruction` stage runs only up to its preflight refusal here. Java control plane on real PostgreSQL and MinIO (Testcontainers): 51 passed. Live-stack tests (44) were not run this session |
| **GPU-tested** | **NO** | 0 of 9 GPU tests have ever run. The GPU image has never been built. `gpu.yml` has 0 runs |
| **Verified on real data** | **NO** | Real ETH3D data has passed through the pipeline up to SfM (earlier today; `VALIDATION_2026-10-19.md`). This session also ran the real ETH3D poses through the splat stage's camera code. Real-data splat training has never run |

## 1. The actual end-to-end architecture

```
capture upload (API: Keycloak auth, MinIO raw bucket, ClamAV, Tika)
  └─ Java control plane (PipelineService) enqueues one job per stage; worker claims, heartbeats a lease, reports
INPUT_VALIDATION → FFMPEG_PREPROCESS → FRAME_QUALITY_FILTER → PRIVACY_PREPROCESS (FRAME_ARCHIVE_ANON + PRIVACY_MASKS)
  → POSE_ESTIMATION (COLMAP mapper; GLOMAP if on PATH, absent from every image) → SPARSE_MODEL (binary) + POSES (poses.json)
  → SPLAT_RECONSTRUCTION (GPU worker, Dockerfile.gpu), chaya_worker/stages/splat_reconstruction.py:
       preflight (python, torch CUDA build, device ≥ CC 7.0, gsplat 1.5.3 + compiled gsplat.csrc, COLMAP, lock drift)
       → config check → optional SPLAT_CHECKPOINT load (CHECKPOINT_CORRUPT / CHECKPOINT_MISMATCH)
       → unpack SPARSE_MODEL → `colmap model_converter` → cameras.txt / points3D.txt (≥ 4 points)
       → POSES → build_cameras (world-to-camera) → unpack frames + PRIVACY_MASKS → FrameRectifier (undistort to pinhole)
       → init_params (SfM xyz/rgb, kNN scales) → splat_training.train(gsplat_rasterizer, L1 + D-SSIM masked,
         density control, LR schedule, periodic checkpoints, deadline − stop margin)
       → publish_outcome: COMPLETED → SPLAT (PLY, read back bit-identical) | PARTIAL → TIME_LIMIT_EXCEEDED + SPLAT_PARTIAL;
         always SPLAT_TRAINING_REPORT, SPLAT_CHECKPOINT, KEYFRAME_RENDERS, TRAINING_CAMERAS
  └─ orchestrator: sha256 + upload to the MinIO derived bucket → report → API seals and verifies the objects
     → PipelineService.advance (PARTIAL run on a partial splat; FAILED otherwise)
→ SEMANTIC_SEGMENTATION → GEOMETRIC_CLEANUP → PLANE_FITTING → ARTIFACT_GENERATION (.ksplat + manifest, viewer
  contract) → SEMANTIC_INDEXING → NAVIGATION_BAKING → scan-version publication → web viewer
```

## 2. Environment and hardware

- **Host:** Windows 11, Intel Core Ultra 5 125H, 16 GB RAM.
- **GPU:** **Intel integrated graphics only**, no `nvidia-smi`. `docker run --gpus all` fails with
  `nvidia-container-cli: initialization error: WSL environment detected but no adapters were found`.
- **Disk:** C: has 3.9 GB free.
- **Docker:** Desktop 29.6.2 (VM 7.5 GiB).
- **Code:** HEAD `ae0dfc4` plus the uncommitted fixes for D-1, D-2 and D-7 (`gaussian-splatting-test-baseline.md` §7).
- **Worker tests:** `services/reconstruction/.venv`, with Python 3.12.10, torch 2.14.0+cpu, numpy 2.2.6 and
  opencv-python-headless 4.14.0.94. No gsplat, no COLMAP; ffmpeg 7.1 is bundled. CI pins torch 2.7.1.
- **Java tests:** `maven:3.9-eclipse-temurin-21`, with Testcontainers `pgvector/pgvector:pg17` and the project's MinIO mirror.

## 3. Real-data availability

- **No usable real dataset is on this host.** The ETH3D `office` run earlier today (26 real DSLR photos, CC BY-NC-SA)
  produced SPARSE_MODEL, POSES, FRAME_ARCHIVE_ANON and PRIVACY_MASKS. Its stack was then removed with
  `docker compose -p chaya-acceptance down -v` (`VALIDATION_2026-10-19.md`), so those artifacts are gone.
- **What survives:** the ETH3D archive is not on disk. Only 7 review JPEGs and the run's JSON records remain
  (`docs/e2e-evidence/2026-10-19-eth3d-office/`, including the real `stage-outputs/poses.json`).
- **Not re-downloaded:** the ETH3D archive (289 MB) was not re-downloaded. Without a GPU it could not get past preflight.

**What a real-data verification needs:**
- A COLMAP or GLOMAP project: `images/` plus `sparse/0/{cameras,images,points3D}.bin`.
- At least 3 registered images (`GSPLAT_MIN_TRAINING_FRAMES`) and at least 4 SfM points; in practice tens of
  overlapping views and thousands of points.
- A supported camera model (PINHOLE, SIMPLE_PINHOLE, or radial/OpenCV, which are undistorted first).
- Frames matching the calibrated size.

Either feed it to the GPU acceptance test (`CHAYA_SPLAT_DATASET`), or upload the photos through the API so the full
pipeline produces SPARSE_MODEL, POSES, masks and frames (the ETH3D procedure in `VALIDATION_2026-10-19.md`).

## 4. Flow step by step

| # | Step | Status | Data | Evidence |
|---|---|---|---|---|
| 1 | Input dataset and image validation | **Verified on real data upstream** (earlier today, not re-run); loader rejection tested | real (ETH3D) / synthetic | INPUT_VALIDATION SUCCEEDED on 26 real photos (`VALIDATION_2026-10-19.md`). `test_a_colmap_project_missing_a_registered_image_is_refused` |
| 2 | Sparse reconstruction loading | **Tested, not on real data** | synthetic content, real COLMAP binary format | Real `colmap model_converter` (COLMAP 3.8) on a binary project via `from_colmap_project` (baseline §7). The stage's own conversion path is behind preflight and has never run |
| 3 | Camera and pose loading | **Real-data check this session**, geometry only | **real** (ETH3D POSES) | `build_cameras` + `FrameRectifier.pinhole` + `validate_camera` on the 14 real registered poses: all pass. PINHOLE 1621×1080, K = [[896.09, 0, 814.85], [0, 895.39, 538.43]], scene extent 8.14 SfM units. Images were not available |
| 4 | Privacy-mask processing | Masks **produced on real data** upstream (earlier today); use in training tested | real upstream / synthetic | `test_privacy_masked_training.py` (7), `test_privacy_masks.py` (missing/invalid → PRIVACY_MASKS_MISSING / PRIVACY_MASK_INVALID) |
| 5 | Configuration and preflight | Refusal **verified for real** on this host; a *passing* preflight has never been observed | host | `test_the_stage_reports_a_missing_gpu_as_a_structured_failure…`: DEPENDENCY_UNAVAILABLE, nothing published, worker keeps working. CONFIG_INVALID cases unit-tested |
| 6 | Gaussian initialisation | Unit-tested | synthetic | `init_params` / `initial_scale_log` in every training test |
| 7 | **Real gsplat CUDA training** | **NOT RUN** | — | No GPU, no image |
| 8 | Loss and density control | Unit-tested with the CPU test renderer | synthetic | clone/split/prune/cap/reset, masked loss and gradients |
| 9 | Checkpoint creation and resume | Unit-tested (bit-exact resume on CPU); CUDA resume not run; **unreachable in production** (audit D-3). *Superseded 2026-10-23: D-3 fixed, resume reachable and integration-tested (see top)* | synthetic | `test_training_lifecycle.py`, `test_splat_training.py`, corrupt/mismatched checkpoint tests |
| 10 | Time budget and partial result | Integration-tested: worker → report → control plane | synthetic | `test_the_control_plane_receives_a_partial_splat_when_the_budget_runs_out` (orchestrator, real deadline); `PipelineControlPlaneTest` / `ProcessingLiveStatusTest` (PARTIAL run, TIME_LIMIT_EXCEEDED) |
| 11 | PLY export and read-back | Unit-tested | synthetic | bit-identical read-back; SPLAT_EXPORT_FAILED / SPLAT_EXPORT_INVALID / SPLAT_INVALID never published |
| 12 | Training report | Unit-tested | synthetic | `test_a_completed_run_succeeds_with_a_splat_and_full_provenance` |
| 13 | Artifact persistence and retrieval | Integration-tested | synthetic bytes | Orchestrator: sha256, upload and keys (local storage double). Java: `ArtifactIntegrityTest`, `ReconstructionArtifactServingTest` on real MinIO. Earlier today on a real stack: 9/9 ETH3D artifacts re-downloaded with matching sha256 |
| 14 | Downstream .ksplat and viewer | Integration-tested | synthetic (CPU-trained) | `test_splat_artifact_smoke.py`: real ARTIFACT_GENERATION → .ksplat → **pinned viewer library loads it**; partial is never turned into the viewer asset. `KsplatValidatorTest`, `KsplatPublicationTest` |
| 15 | Error handling and cleanup | Tested | synthetic | Structured codes (section 5). Workdir removal asserted after success and after cancellation (`test_stages_and_orchestrator.py:91,390`); the orchestrator's `finally: rmtree` covers every path |

## 5. Failure handling

| Case | Covered by | Result | Level |
|---|---|---|---|
| Missing or malformed sparse model | **No stage-level test** (MODEL_CONVERSION_FAILED, SPLAT_INIT_INSUFFICIENT) | — | Reachable only past preflight, on a GPU host. Gap |
| Invalid camera calibration | `test_camera_calibration.py` (CAMERA_CALIBRATION_INVALID); `test_a_camera_outside_the_convention_fails_before_any_iteration` | pass | unit / orchestration. The stage-level CAMERA_CONVENTION_INVALID mapping is untested |
| Missing image files | `test_a_colmap_project_missing_a_registered_image_is_refused` (dataset loader) | pass | unit. The stage drops cameras without frames, then enforces the minimum frame count |
| Invalid privacy masks | `test_privacy_masks.py` (PRIVACY_MASKS_MISSING, PRIVACY_MASK_INVALID) | pass | orchestration |
| Corrupt checkpoints | `test_a_truncated_or_garbage_checkpoint_is_corrupt_not_a_crash`, mistyped fields, Adam moment mismatch, format 1, other inputs/settings | pass | unit. Stage-level CHECKPOINT_CORRUPT is only in the GPU acceptance test (not run) |
| CUDA or dependency incompatibility | real DEPENDENCY_UNAVAILABLE on this host; GSPLAT_INCOMPATIBLE, SPLAT_GPU_OUT_OF_MEMORY, GPU_RUNTIME_ERROR mapping; drift/version preflight | pass | real refusal plus unit |
| Interrupted training | resume from the last periodic checkpoint to the uninterrupted result; OOM mid-run propagates after a resumable checkpoint; cancellation raises; cancelled job uploads nothing | pass | unit / orchestration |
| Insufficient time budget | budget already spent → no splat; early stop → PARTIAL, never COMPLETED; control plane → PARTIAL run | pass | unit / orchestration / Java |
| Artifact write or validation failure | SPLAT_EXPORT_FAILED, SPLAT_EXPORT_INVALID, SPLAT_INVALID, SPLAT_CHECKPOINT_WRITE_FAILED, STORAGE_ERROR, INPUT_CHECKSUM_MISMATCH | pass | unit / orchestration. *2026-10-23: since D-6 a failed checkpoint write no longer fails the stage; SPLAT_CHECKPOINT_WRITE_FAILED is now a report field, and other I/O errors are SPLAT_IO_FAILED* |

No failure path observed or tested produces a successful reconstruction or a placeholder artifact.

## 6. Commands executed and results (this session's final pass)

Worker suites ran from `services/reconstruction` with `HF_HUB_OFFLINE=1` and
`.venv/Scripts/python.exe -m pytest -p no:cacheprovider -q -rfEs`. Every row exited 0.

| Run | Arguments | Tests | Passed | Failed | Errors | Skipped |
|---|---|---:|---:|---:|---:|---:|
| F1 failure paths | `tests/splat/test_splat_preflight_and_failures.py tests/splat/test_training_lifecycle.py tests/orchestration/test_privacy_masks.py tests/orchestration/test_camera_calibration.py` | 87 | 87 | 0 | 0 | 0 |
| F2 orchestrator and artifacts | `tests/orchestration/test_stages_and_orchestrator.py tests/smoke/test_splat_artifact_smoke.py tests/smoke/test_pipeline_smoke.py` | 50 | 49 | 0 | 0 | 1 |
| F3 training and data | `tests/splat/test_splat_training.py …/test_privacy_masked_training.py …/test_splat_dataset.py …/test_gsplat_adapter.py tests/unit/test_splat_color.py …/test_ksplat_validation.py …/test_ksplat_fixture.py` | 56 | 56 | 0 | 0 | 0 |
| F4 CI CPU selection | `-m "not gpu and not open3d and not integration and not acceptance"` | 643 | **641** | 0 | 0 | 2 |
| F5 GPU | `-m gpu tests/gpu` | 9 | **0** | 0 | 0 | **9** |
| F6 live stack | `-m "integration or acceptance"` | 44 | **0** | 0 | 0 | **44** |

Java control plane: `docker run --rm -m 3g … maven:3.9-eclipse-temurin-21 sh api_tests.sh`. The repo is mounted
read-only, the API is copied to `/w`, and `mvn -B -ntp -q verify -Dtest=<classes>` runs. Maven exited 0.

| Class | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| PipelineControlPlaneTest | 20 | 0 | 0 | 0 |
| ProcessingLiveStatusTest | 10 | 0 | 0 | 0 |
| ArtifactIntegrityTest | 6 | 0 | 0 | 0 |
| KsplatValidatorTest | 6 | 0 | 0 | 0 |
| KsplatPublicationTest | 4 | 0 | 0 | 0 |
| PipelineOutputContractTest | 4 | 0 | 0 | 0 |
| ReconstructionArtifactServingTest | 1 | 0 | 0 | 0 |
| **Total** | **51** | 0 | 0 | 0 |

Real-data camera check: `.venv/Scripts/python.exe real_poses_check.py docs/e2e-evidence/2026-10-19-eth3d-office/stage-outputs/poses.json`
exited 0 (section 4, step 3).

**Skip reasons:**
- **F2 and F4:** `got empty parameter set` is expected; CI lists it.
- **F4:** `tests/ml` skips because `transformers` is not installed. It is unrelated to splats.
- **F5:** 4 tests need torch-CUDA, gsplat and a CUDA device. The acceptance test fails preflight (gsplat missing,
  CPU-only torch, no COLMAP). 3 tests need `CHAYA_TEST_IMAGE_SEQUENCE`. 1 needs Open3D.
- **F6:** `BLOCKED: no stack configured` for 40 acceptance tests and the stack end-to-end test, MinIO not configured
  for 2, and the `tests/ml` module skip. No stack was started: there is no disk headroom, and the run would stop at
  POSE_ESTIMATION or SPLAT_RECONSTRUCTION anyway.

## 7. GPU acceptance evidence

**None.** `tests/gpu/test_splat_acceptance.py` has never run on any machine. No `gpu-acceptance.json`, no training
metrics, no trained real-scene PLY and no real-scene .ksplat exist.

## 8. Artifacts produced in this session

- **No pipeline artifacts:** no trained model, PLY or .ksplat of any real or synthetic scene was kept.
- **Test outputs:** pytest temporary directories and JUnit XML, under the session scratchpad
  (`final/py/`, `final/api-reports/`). They are ephemeral and not part of the repository.
- **Repository changes:** only documentation (this file and its companions) and the D-1, D-2 and D-7 fixes described
  in the test baseline. Nothing was committed.

## 9. Known limitations

- The CPU tests prove the training *mechanics* with an isotropic CPU test renderer, not gsplat's anisotropic CUDA rasteriser.
- *Fixed 2026-10-23 (see top):* checkpoint resume unreachable (D-3); every later stage downloading the checkpoint
  (D-4); no quality floor (D-5); a failed final checkpoint write discarding a completed cloud (D-6); acceptance PSNR
  on training views only (the gate now uses held-out views). All are CPU- or integration-verified only, not on a GPU.
- The default acceptance dataset is synthetic.
- GLOMAP is in no image; COLMAP's mapper is used.
- Real-data upstream defects from the ETH3D run remain open (VALIDATION D1–D3):
  - false face detections that mask frames;
  - low-texture frames rejected as blurred;
  - COLMAP OOM at default threads.
- The local venv's torch (2.14) is newer than the pinned 2.7.1.

## 10. Remaining blockers

1. **No NVIDIA GPU** on this host (hardware).
2. **No disk headroom** to build the GPU image (3.9 GB free vs well over 15 GB).
3. **No real-scene dataset on disk.** The ETH3D artifacts were discarded; re-obtaining them needs a 289 MB download.
4. **No self-hosted `gpu` runner** registered for `.github/workflows/gpu.yml`.

## 11. Summary

**Genuinely working, as far as can be shown without a GPU:**
- Real photos go through validation, privacy and COLMAP SfM, with mm-accurate poses on ETH3D.
- The splat stage's camera and pose handling accepts those real poses.
- The training loop, masking, density control, checkpointing, time budget and publish gate behave correctly on CPU
  with synthetic data.
- Failures surface as explicit, structured errors, and nothing is published for them.
- The worker → MinIO → control-plane → .ksplat → pinned-viewer chain works end to end on a CPU-trained synthetic cloud.

**Not shown:** that gsplat trains on CUDA, and that a real scene becomes a trained splat and a viewable .ksplat.

**Single highest-priority next action:** on a cloud CUDA host (L4 or A10G, driver ≥ 560, Container Toolkit, ≥ 80 GB
disk), build `Dockerfile.gpu` and run `python -m chaya_worker.splat_preflight`. Then run
`CHAYA_REQUIRE_GPU=1 pytest -m gpu` with `CHAYA_SPLAT_DATASET` pointing at a real COLMAP project (e.g. ETH3D `office`).
Commands are in `gaussian-splatting-gpu-environment.md` §6.
