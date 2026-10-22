# Gaussian Splatting Reconstruction: Implementation and Execution Audit (2026-10-19)

Scope: SPLAT_RECONSTRUCTION end to end. That covers the inputs from POSE_ESTIMATION, preflight, the training loop,
checkpoints, the time budget, export and validation, worker orchestration, control-plane handling, the GPU image and CI.
Method: code reading plus safe local commands (section 8). Nothing was installed, built, deleted or trained beyond the
existing CPU test suite.

## Verdict

The training implementation is real. It uses gsplat's CUDA `rasterization()`, seeds from SfM points, applies the 3DGS
L1 + D-SSIM loss with privacy masking, runs density control, and refuses to run without CUDA. No mock or fallback path
can publish a `SPLAT`.

**It has never executed on a GPU, and the GPU image as written most likely cannot be built** (D-1). This host has no
NVIDIA GPU, so no GPU evidence could be produced here. GPU training status: **NOT VALIDATED**.

| # | Question | Finding |
|---|---|---|
| 1 | Training entry point | `SplatReconstruction.run` (`chaya_worker/stages/splat_reconstruction.py:331`) → `splat_training.train` (`splat_training.py:563`). The orchestrator calls it for a claimed `SPLAT_RECONSTRUCTION` job (`orchestrator.py:188`). |
| 2 | gsplat CUDA rasteriser used for optimisation | Yes in code: `gsplat_rasterizer` → `gsplat.rasterization(..., packed=False)` (`splat_training.py:511-537`), with output-shape contract checks. **Never executed.** |
| 3 | SfM points seed the Gaussians | Yes: `points3D.txt` xyz/rgb via `colmap model_converter` → `init_params` (`splat_reconstruction.py:409-410`), kNN log-scale init, identity rotations, opacity 0.1. |
| 4 | Cameras / poses / intrinsics / colours / masks | Correct by inspection and CPU tests. POSES and SPARSE_MODEL come from the same COLMAP model (`pose_estimation.py:209-232`). Poses are world-to-camera. Distorted cameras are undistorted to pinhole using the COLMAP pixel-centre convention (`camera_model.py:383-392`). Images are sRGB float in [0,1]. Masks are undistorted with the frame and their pixels zeroed and excluded from the loss. `validate_camera` rejects convention violations. |
| 5 | Loss and gradients | Valid: `(1-w)·L1 + w·(1-SSIM)`. The masked variant uses `torch.where`, so excluded pixels contribute exactly zero gradient (tested). Non-finite loss or parameters raise `TrainingDiverged`. |
| 6 | Clone / split / prune | Matches 3DGS / gsplat DefaultStrategy: NDC-normalised gradient threshold; clone if small, split into 2 with scale/1.6; opacity and world-scale pruning after the first reset; cap by gradient rank; Adam moments carried or zeroed. CPU-tested. |
| 7 | Checkpoint save/resume | Saves params, Adam moments and steps, LRs, density accumulators and events, config, input identity and history. Writes are atomic. Loads with `weights_only=True` plus structural validation. Bit-exact resume on CPU is tested. **Unreachable in production** (D-3). On CUDA it is untested (P-1). |
| 8 | Timeouts and partial results | Honest. PARTIAL becomes `FAILED/TIME_LIMIT_EXCEEDED` plus `SPLAT_PARTIAL(partial=true)`, and the control plane ends the run PARTIAL. A partial is never labelled SPLAT. Whether the margin is large enough is unmeasured (P-3). |
| 9 | Output validation | `write_ply` → `read_ply` → bit-identical comparison; `validate_trained_state`; .ksplat size and count limits before publishing. ARTIFACT_GENERATION re-validates against the viewer contract (smoke test). |
| 10 | Preflight | Checks Python ≥3.11, torch ≥2.6 with a CUDA build, a visible device, compute capability ≥7.0, gsplat ==1.5.3 with a compiled `gsplat.csrc` (JIT is opt-in), COLMAP, lock drift, config and checkpoint. It does **not** check device-arch support (P-2), GPU memory, or disk space. |
| 11 | Docker / CUDA compatibility | The CUDA 12.6.3 base, torch 2.7.1+cu126, gsplat built with nvcc 12.6 and Python 3.12 are consistent. **The build's first `pip install` cannot resolve** (D-1). The image has never been built. |
| 12 | Orchestration errors / retries / artifacts | Structured stage errors; upload failure becomes STORAGE_ERROR; a cancelled job uploads nothing; the workdir is always removed. Retries never carry the checkpoint (D-3). Every later stage downloads the checkpoint (D-4). |
| 13 | Do tests exercise real behaviour? | CPU tests run the real loop, loss, density control, checkpointing and publishing against a *test renderer*, not gsplat. Preflight tests use a fake `Toolchain`. GPU tests are real but have never run. The acceptance test has a crash bug (D-2). |
| 14 | Default GPU acceptance dataset | **Synthetic** (ray-traced room corner, `tests/gpu/splat_dataset.py`). A real COLMAP project only when `CHAYA_SPLAT_DATASET` / workflow input `splat_dataset` is set. |
| 15 | Success without training? | No fake path: 0 iterations is refused (`config_problems`), no CUDA means DEPENDENCY_UNAVAILABLE, and COMPLETED requires all configured iterations. But SUCCEEDED has **no quality floor** (D-5). |

**Status update (2026-10-19, later):**
- **D-1** fixed: the hash-pinned torch and torchvision wheels are installed before the requirements step. Verified
  with a pip dry-run; the image itself is not built yet.
- **D-2** fixed: `retain_grad` runs only for renders that require grad. Covered by the new
  `tests/splat/test_gsplat_adapter.py`.
- **D-7** found and fixed: `from_colmap_project` returned no cameras, so the PSNR gate never ran on real data, and it
  re-encoded frames. Verified with real COLMAP.
- **D-4 to D-6** are open. All GPU runtime verification is still blocked. Evidence:
  `docs/gaussian-splatting-test-baseline.md` §7.

**Status update (2026-10-21):**
- **D-3** fixed. A retry of SPLAT_RECONSTRUCTION is offered the checkpoint of the run's latest time-boxed attempt, unless
  a later attempt refused it (`PipelineService#resumableCheckpoint`). A PARTIAL run whose training the time budget
  stopped can be retried to resume it; migration V43 allows exactly that PARTIAL → RUNNING transition. The worker's
  checkpoint handling is `resume_state`. Covered by `SplatResumeControlPlaneTest` (control plane, real PostgreSQL/MinIO)
  and `tests/splat/test_checkpoint_resume.py` (worker, CPU test renderer). Resume on CUDA is still unverified (P-1).
  See `docs/pipeline.md`, "Resuming training".

**Status update (2026-10-22):**
- **D-4** fixed. A work order offers a stage only the kinds its contract entry declares (`PipelineDefinition.INPUTS`,
  kept equal to `stage-artifacts.json` by `PipelineArtifactContractTest`), so SPLAT_CHECKPOINT and KEYFRAME_RENDERS reach
  no stage after SPLAT_RECONSTRUCTION. A retry of SPLAT_RECONSTRUCTION is still offered its resume checkpoint. The worker
  filters the same way before downloading (`chaya_worker.stage_inputs`, checked against the contract). A stage that
  crashes while a required input is missing fails as `INPUT_INVALID` naming it; stages' own codes are unchanged. Covered by
  `SplatResumeControlPlaneTest#aCompletedTrainingsCheckpointAndKeyframesAreOfferedToNoLaterStage`,
  `tests/orchestration/test_stage_inputs.py` and `tests/smoke/test_splat_artifact_smoke.py`.

## 1. Confirmed working behaviour

Confirmed by code reading and/or the CPU test run in section 6. "Working" here means CPU-verified mechanics, not GPU execution.

- **No fallback.** `splat_reconstruction.py:333-339` raises `DependencyError` before unpacking anything when preflight
  fails. `docs/VALIDATION_2026-10-19.md` records a real run on this host ending `DEPENDENCY_UNAVAILABLE` with no
  placeholder. The local preflight reproduces the refusal (section 6).
- **Device selection.** Training uses the preflight-selected device (`torch.cuda.set_device`), not device 0.
- **Camera path.** Pose quaternion wxyz → R, viewmat `[R|t]`. Undistortion remap tables are shifted by −0.5 for
  OpenCV's convention. Frame and mask sizes are checked against the camera. Privacy masks are required in
  privacy-enabled runs (`privacy/masks.py:130`), and a missing per-frame mask fails the job.
- **Loss masking.** L1 is the mean over valid pixels. D-SSIM is the mean over windows with no masked pixel. A fully
  masked view contributes 0. Tests: `tests/splat/test_privacy_masked_training.py`, including "a masked fill does not
  appear in another view" with a control showing it *is* learned without the mask.
- **LR schedule.** INRIA log-linear position decay × scene extent, a pure function of the iteration, so resume follows it.
- **Divergence.** A NaN/Inf loss or parameter stops training. No checkpoint or splat of the diverged state is published.
- **Failure mapping.** `training_failure` maps cancel, camera convention, divergence, gsplat contract, OOM, OSError and
  CUDA runtime errors to structured codes. Other exceptions surface as INTERNAL_ERROR with a traceback, not disguised
  as GPU errors (tested).
- **Publish gate** (`publish_outcome`, `splat_reconstruction.py:231-325`). SPLAT is published only if COMPLETED with
  completed == target ≥ 1, the state is valid, it fits the viewer limits, and the PLY reads back bit-identical. Otherwise
  the stage FAILs with the report only, and no checkpoint of an invalid state is published.
- **Control plane.** `PipelineService.advance` turns `TIME_LIMIT_EXCEEDED` plus a `partial` SPLAT/SPLAT_PARTIAL into a
  PARTIAL run (`PipelineService.java:947-951`). REQUIRED_OUTPUTS forces `SPLAT` for a SUCCEEDED report.
- **Viewer chain.** A CPU-trained cloud goes through real ARTIFACT_GENERATION to .ksplat and loads in the pinned viewer
  library (`tests/smoke/test_splat_artifact_smoke.py`, ran here). A PARTIAL result is never turned into the viewer asset.

## 2. Confirmed defects

**D-1 (P0, blocks everything on GPU). `Dockerfile.gpu` cannot resolve its first dependency install.**
`Dockerfile.gpu:54` runs `pip install -r gpu/requirements-gpu.txt -c gpu/constraints-gpu.txt` *before* torch is
installed, against PyPI only (no `--index-url`/`--extra-index-url` anywhere in the image or the gpu/ files).
`requirements-gpu.txt` includes `open_clip_torch` and `timm`, which depend on `torch` and `torchvision`. The constraints
pin `torch==2.7.1+cu126` (line 111) and `torchvision==0.22.1+cu126` (line 112). PEP 440 `==` with a local label matches
only that exact local version. The PyPI JSON for torch 2.7.1 and torchvision 0.22.1 (queried today) contains no `+cu126`
file. So the resolver has no candidate and the step fails. Even if torch were installed first (line 57), `torchvision`
is never installed from the PyTorch index. *Evidence: static analysis plus the registry query; the build itself was not
run (large image, out of scope).* This matches the record that the image "has never been built" (DEPLOYMENT.md §10).

**D-2 (P1). The GPU acceptance test crashes at its only learned-quality assertion.**
`tests/gpu/test_splat_acceptance.py:96-103` (`_mean_psnr`) renders through the production adapter under
`torch.no_grad()`, with plain tensors from `_params_from_ply`. `gsplat_rasterizer` calls `means2d.retain_grad()`
unconditionally (`splat_training.py:533`). Verified locally with torch 2.14:
`RuntimeError: can't retain_grad on Tensor that has requires_grad=False`. Training (step 1) would succeed, and then the
test errors before the PSNR-gain check, the resume check and the viewer check. The CPU test renderer guards this
(`if means2d.requires_grad`), which is why CPU tests cannot catch it. Production training is unaffected because grad is
enabled there.

**D-3 (P1). Checkpoint resume is unreachable in production.**
The worker resumes only when a job's inputs include `SPLAT_CHECKPOINT`. The control plane builds inputs only from
artifacts of SUCCEEDED stage runs (`PipelineService.java:490`). A time-boxed splat stage is FAILED, so its checkpoint is
never offered. A PARTIAL run cannot be retried at all (`:251`, "only a FAILED run can be retried"). A FAILED-run retry
re-enqueues SPLAT_RECONSTRUCTION from the SfM seed. The input identity also includes `run_id`, so a checkpoint could
never cross runs. This is documented as a gap (`docs/pipeline.md`, ADVERSARIAL_REVIEW R-5). In practice, the
checkpoint/resume machinery is exercised only by tests.

**D-4 (P2). Every downstream stage downloads the splat checkpoint and keyframes.**
`inputs()` (`PipelineService.java:475-497`) returns every non-log artifact of every SUCCEEDED stage, without filtering
by `stage-artifacts.json` `requiredInputs`/`optionalInputs`. The worker's `_download_inputs` (`orchestrator.py:208`)
downloads all of them. At the `GSPLAT_MAX_GAUSSIANS=3,000,000` cap, a checkpoint is about 14 float32 params plus 2 Adam
moments ≈ 168 B per Gaussian ≈ **0.5 GB**. SEMANTIC_SEGMENTATION, GEOMETRIC_CLEANUP, PLANE_FITTING, ARTIFACT_GENERATION
and the other later stages each download it inside the same run-wide time budget.

**D-5 (P2). SUCCEEDED has no reconstruction-quality floor.**
`config_problems` only rejects `GSPLAT_ITERATIONS < 1`. With `GSPLAT_ITERATIONS=1`, a near-seed cloud is published as
`SPLAT` and the run can finish FINAL. Nothing checks that loss decreased or that held-out PSNR improved over the seed.
Training genuinely ran, so this is not a fake result, but "SUCCEEDED" does not mean "reconstructed".

**D-6 (P3). A completed training is discarded if the final checkpoint write fails.**
`train()` always writes the end checkpoint before returning (`splat_training.py:632`). An `OSError` there becomes
`SPLAT_CHECKPOINT_WRITE_FAILED`, and the fully trained cloud is never exported. Also, *any* `OSError` raised during
training is labelled a checkpoint-write failure.

## 3. Potential defects (require GPU execution or measurement)

| ID | Risk | How to confirm |
|---|---|---|
| P-1 | `load_checkpoint` moves Adam `step` tensors to CUDA (`splat_training.py:487`). Non-capturable Adam keeps them on CPU. This probably works but forces a host sync per step. Resume on CUDA has never run. | `tests/gpu/test_gsplat_cuda.py::test_a_short_synthetic_training_run_on_cuda_learns_densifies_and_resumes` |
| P-2 | Preflight accepts any capability ≥7.0 but does not check `torch.cuda.get_arch_list()` or the gsplat build's `TORCH_CUDA_ARCH_LIST` (7.0–9.0+PTX). A newer GPU (e.g. sm_120) could pass preflight and then fail at the first kernel with GPU_RUNTIME_ERROR. | Preflight plus one iteration on the target GPU |
| P-3 | One run-wide budget (default 3600 s, `application.yml:54`) covers every stage. The COLMAP mapper timeout alone is 14,400 s. The worker reserves 120 s, plus 60 s control-plane grace, for the final checkpoint (≈0.5 GB at the cap), PLY (68 B/Gaussian ≈ 204 MB), keyframes, hashing and upload. Exceeding this means the control plane fails the job and the partial is lost. The default 7000 iterations may rarely complete within the default budget. | Timed run on a real capture; record `training_seconds` and upload time |
| P-4 | All frames are held in host RAM as float32 RGB (1080p ≈ 25 MB per frame, 300 frames ≈ 7.5 GB), and one frame is copied host→device per iteration from pageable memory. | Memory/throughput profile on a real capture |
| P-5 | The gsplat 1.5.3 sdist build with nvcc 12.6 inside the image (vendored glm, arch list, `MAX_JOBS`) has never been attempted. | Build after D-1 is fixed |
| P-6 | Density-control thresholds (2e-4 NDC, 0.01 extent) are the paper defaults. Growth and pruning on a real indoor venue are unmeasured. The synthetic scene is 192×144. | Real-dataset acceptance run (`CHAYA_SPLAT_DATASET`) |
| P-7 | Upstream: POSE_ESTIMATION takes `models[0]` (lexicographic), not the largest sub-model, if COLMAP splits the scene (`pose_estimation.py:204`). | A capture that fragments |
| P-8 | The acceptance PSNR uses training views only ("not a held-out evaluation"). Even after D-2 is fixed, it cannot detect overfitting. | Add held-out views |

## 4. Dependencies and environment requirements

| Component | Required | Source of truth |
|---|---|---|
| Host GPU | NVIDIA, compute capability ≥ 7.0, driver ≥ 560 (CUDA 12.6) | `Dockerfile.gpu` header, `GSPLAT_MIN_COMPUTE_CAPABILITY` |
| Container runtime | Docker plus the NVIDIA Container Toolkit, `--gpus all` | `gpu.yml` |
| Base images | `nvidia/cuda:12.6.3-{devel,runtime}-ubuntu24.04` (pinned by digest) | `Dockerfile.gpu:39-40` |
| Python | 3.12 (Ubuntu 24.04); preflight minimum 3.11 | `splat_preflight.MIN_PYTHON` |
| torch | 2.7.1+cu126 (sha256-pinned wheel); preflight minimum 2.6 | `Dockerfile.gpu:46-48` |
| gsplat | 1.5.3 exactly, built from sdist with `gsplat.csrc` compiled | `GSPLAT_VALIDATED_VERSION` |
| Others | numpy 2.2.6, scipy 1.15.3, opencv-python-headless 4.11.0.86, plus the closure in `constraints-gpu.txt` | `gpu/requirements-gpu.txt` |
| COLMAP | Ubuntu 24.04 package (3.9.x, no CUDA); used only for `model_converter` here | `Dockerfile.gpu` runtime stage |
| GLOMAP | Optional, and absent from every image (COLMAP mapper is used) | FINAL_AUDIT P1-12 |
| Lock | `/opt/chaya/gpu-lock.json`, written at build; drift is refused at runtime | `gpu/write_lock.py` |

Key env vars: `GSPLAT_ITERATIONS` (7000), `GSPLAT_MAX_GAUSSIANS` (3e6), `GSPLAT_CHECKPOINT_EVERY` (1000),
`GSPLAT_STOP_MARGIN_SECONDS` (120), `GSPLAT_MIN_TRAINING_FRAMES` (3), `GSPLAT_ALLOW_JIT_BACKEND` (false), `COLMAP_BIN`,
`CHAYA_GPU_LOCK`. Test-only: `CHAYA_REQUIRE_GPU`, `CHAYA_SPLAT_DATASET`, `CHAYA_GPU_ACCEPTANCE_REPORT`.

Compose: `infra/docker/docker-compose.yml` `reconstruction-worker` builds the **CPU** `Dockerfile` with no GPU
reservation. `infra/deploy` runs only CPU stages. No compose file runs a GPU worker.

## 5. Environment discovered on this host (2026-10-19)

| Item | Result |
|---|---|
| OS | Windows 11 Home 10.0.26200 |
| GPU | **Intel(R) Graphics only** (driver 32.0.101.8331); `nvidia-smi` not found |
| Python | 3.14.3 (default), 3.12 (py launcher); worker `.venv` is 3.12.10 |
| Worker venv | torch **2.14.0+cpu**, numpy 2.2.6, scipy 1.18.1, opencv-python-headless 4.14.0.94, pytest 9.1.1; **no gsplat, no COLMAP**. (This drifts from the image pins: torch 2.7.1, scipy 1.15.3, opencv 4.11. CI's CPU job uses torch 2.7.1.) |
| Docker | 29.6.2, Compose v5.3.1, daemon up (Docker Desktop). An `nvidia` runtime is registered, but there is no NVIDIA device to use. |
| Images | No `chaya-worker-gpu` image exists. CPU worker, acceptance and COLMAP probe images exist. |
| Containers | All Chaya containers exited. Only an unrelated `n8n` is running. |
| CI | `gpu.yml`: **zero runs** (`gh run list --workflow gpu.yml` is empty). Latest `python` on main (39201362117) is red from the `open3d` job (`test_geometric_cleanup_and_plane_fitting…`, FINAL_AUDIT P1-14), which is not splat code. |

## 6. Test inventory and results here

| Suite | What it really exercises | Result on this host |
|---|---|---|
| `tests/splat/test_splat_training.py` | Real loop/loss/density/checkpoint/publish code with the CPU **test renderer** | ran: pass |
| `tests/splat/test_training_lifecycle.py` | LR schedule, resume across a time-box stop, checkpoint format, camera conventions, divergence, publish validation | ran: pass |
| `tests/splat/test_privacy_masked_training.py` | Mask semantics in loss and gradient, end to end on CPU | ran: pass |
| `tests/splat/test_splat_preflight_and_failures.py` | Preflight logic with a **fake Toolchain**; corrupt checkpoints; failure mapping; one real "no GPU here" stage run | ran: pass |
| `tests/splat/test_splat_dataset.py` | The synthetic GPU dataset's camera, pose and track consistency | ran: pass |
| `tests/unit/test_splat_color.py`, `test_ksplat_validation.py` | Colour convention; .ksplat contract | ran: pass |
| `tests/smoke/test_splat_artifact_smoke.py` | CPU-trained cloud → real ARTIFACT_GENERATION → pinned viewer library | ran: pass |
| **Total CPU** | | **116 passed, 0 skipped (70.6 s)** |
| `tests/gpu/test_gsplat_cuda.py` (4) | Real gsplat: version, SH-vs-RGB colour, adapter contract, synthetic train and resume | **skipped**: no torch-CUDA/gsplat/CUDA |
| `tests/gpu/test_splat_acceptance.py` (1) | Real stage, orchestrator, COLMAP, ARTIFACT_GENERATION; synthetic by default | **skipped** (preflight); would crash on D-2 |
| `tests/gpu/test_reconstruction_toolchain.py` (4) | Real-media COLMAP; Open3D | **skipped**: no image sequence / Open3D |
| **Total GPU** | | **0 passed, 9 skipped. Not a pass.** |

## 7. Commands to reproduce

Run from `services/reconstruction` (PowerShell or Git Bash). All are read-only apart from pytest temp files.

```sh
nvidia-smi                                                  # GPU present? (absent here)
docker info ; docker images ; docker ps -a                  # daemon, images (no chaya-worker-gpu)
.venv/Scripts/python.exe -m pip list                        # torch 2.14.0+cpu, no gsplat
.venv/Scripts/python.exe -m chaya_worker.splat_preflight    # exit 1: gsplat missing, CPU torch, no COLMAP
.venv/Scripts/python.exe -m pytest -p no:cacheprovider -q -rs tests/splat tests/unit/test_splat_color.py \
    tests/unit/test_ksplat_validation.py tests/smoke/test_splat_artifact_smoke.py      # 116 passed
.venv/Scripts/python.exe -m pytest -p no:cacheprovider -q -rs -m gpu tests/gpu        # 9 skipped
curl -s https://pypi.org/pypi/torch/2.7.1/json | python -c "import json,sys;print(any('cu126' in u['filename'] for u in json.load(sys.stdin)['urls']))"  # False (D-1)
gh run list --workflow gpu.yml                              # empty
```

On a CUDA host, after D-1 is fixed:

```sh
docker build -f services/reconstruction/Dockerfile.gpu -t chaya-worker-gpu services/reconstruction
docker run --rm --gpus all chaya-worker-gpu python -m chaya_worker.splat_preflight
docker run --rm --gpus all --user 0 -e CHAYA_REQUIRE_GPU=1 -e CHAYA_GPU_ACCEPTANCE_REPORT=/out/gpu-acceptance.json \
  -v "$PWD/services/reconstruction/tests:/src/tests:ro" -v "$PWD/gpu-out:/out" -w /src chaya-worker-gpu \
  sh -c "pip install -q pytest==8.4.1 && python -m pytest -p no:cacheprovider -m gpu tests/gpu -rA"
# then repeat with -e CHAYA_SPLAT_DATASET=/data/splat -v <real COLMAP project>:/data/splat:ro
```

## 8. Current execution blockers

1. **No NVIDIA GPU on this host.** gsplat has no CPU path, so no GPU evidence is possible here.
2. **D-1.** The GPU image is expected to fail at its first `pip install`. No image means no preflight on a GPU host.
3. **No self-hosted `gpu` runner.** `gpu.yml` has never run.
4. **D-2.** Even on a working GPU image, the acceptance test cannot pass.
5. **No real COLMAP dataset is wired in by default.** The acceptance test is synthetic unless `CHAYA_SPLAT_DATASET` is supplied.

## 9. Prioritised remediation plan

| Pri | Action | Done when |
|---|---|---|
| 1 | **Fix D-1.** Install the hash-checked torch wheel first. Give the requirements step the PyTorch cu126 index (`--extra-index-url https://download.pytorch.org/whl/cu126`), or pin and hash-check the `torchvision` wheel the way torch is pinned. Keep `constraints-gpu.txt` as the closure. | `docker build -f Dockerfile.gpu` completes; `write_lock.py` reports only CUDA_UNAVAILABLE on a GPU-less builder |
| 2 | **Fix D-2.** Call `retain_grad()` only when `means2d.requires_grad`, and add a CPU unit test that calls the adapter's no-grad path through a stub `rasterization`. | The acceptance test reaches its PSNR assertion |
| 3 | Provision an A10/L4-class CUDA host plus the NVIDIA toolkit and register a `gpu` runner. Run preflight and `pytest -m gpu` with `CHAYA_REQUIRE_GPU=1`. | `gpu-acceptance.json` with device, versions, timings; 0 unexpected skips |
| 4 | Run the acceptance test on a **real** COLMAP project (`CHAYA_SPLAT_DATASET`), then on one real capture through the full pipeline. Record P-3/P-4 measurements. | Real-dataset PSNR gain recorded; training and upload time vs budget known |
| 5 | P-2: add an arch check to preflight (`torch.cuda.get_arch_list()` plus the recorded `cuda_arch_list`). | An unsupported GPU is refused at preflight, not mid-training |
| 6 | D-4: filter work-order inputs by the stage's declared required/optional input kinds (control plane), or skip undeclared kinds in `_download_inputs`. | Later stages no longer download `SPLAT_CHECKPOINT`/`KEYFRAME_RENDERS` |
| 7 | D-5: add a minimum-quality gate (e.g. a minimum iteration floor plus loss or held-out PSNR improvement over the seed) that FAILs rather than publishes. Also add held-out views to the acceptance test (P-8). | A 1-iteration run cannot finish FINAL |
| 8 | D-3: decide whether resume is a product feature. If it is, register checkpoints from TIME_LIMIT_EXCEEDED attempts and offer the latest to a retry of the same run (identity already binds it to the inputs). If not, stop publishing checkpoints by default to save storage and upload time. | One documented, tested path |
| 9 | D-6: export the cloud before (or regardless of) the final checkpoint write; narrow the OSError mapping. | A disk-full checkpoint write cannot discard a completed cloud |

Do not mark Gaussian Splatting as working until items 1–4 produce GPU execution evidence on real data.

## Files inspected

`chaya_worker/stages/splat_reconstruction.py`, `splat_training.py`, `splat_preflight.py`, `toolchain.py` (probes),
`settings.py` (gsplat settings), `orchestrator.py`, `contract.py`, `stages/base.py`, `stages/pose_estimation.py`,
`camera_model.py` (FrameRectifier, undistortion), `privacy/masks.py`, `ply.py`, `stages/unimplemented.py`,
`gpu/requirements-gpu.txt`, `gpu/constraints-gpu.txt`, `gpu/write_lock.py`, `Dockerfile.gpu`, `pyproject.toml`,
`tests/splat/*`, `tests/gpu/*`, `tests/smoke/test_splat_artifact_smoke.py`, `infra/docker/docker-compose.yml`,
`infra/deploy/docker-compose.yml`, `.github/workflows/gpu.yml`, `.github/workflows/python.yml`,
`scripts/ci/pytest-summary.py`, `packages/contracts/pipeline/stage-artifacts.json`, `services/api/.../PipelineService.java`
(inputs, advance, retry, deadlines), `PipelineDefinition.java`, `application.yml`, and the prior audits
`docs/FINAL_AUDIT_2026-10-17.md`, `docs/VALIDATION_2026-10-19.md`, `docs/pipeline.md`, and `DEPLOYMENT.md` §10.
