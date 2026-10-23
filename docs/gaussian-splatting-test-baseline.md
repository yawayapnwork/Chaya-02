# Gaussian Splatting: CPU Test Baseline (2026-10-19)

Follows `docs/gaussian-splatting-audit.md`. This document records what the existing reconstruction tests show on this
host, with exact commands and counts. No test or source file was changed.

**Result: every CPU-runnable test passed (0 failed, 0 errors). Every GPU test was skipped. GPU training remains
NOT VALIDATED.** The passing tests show that the training loop's mechanics are correct on the CPU with a test
renderer. They say nothing about gsplat, CUDA or the reconstruction of a real scene.

## 1. Environment selection

| Candidate (in order of preference) | Finding | Used |
|---|---|---|
| Existing project env `services/reconstruction/.venv` | Python **3.12.10** (`pyvenv.cfg`: CPython 3.12 at `...\Programs\Python\Python312`, no system site-packages). `chaya-worker` is installed **editable** (`__editable__.chaya_worker-0.1.0.pth` → `services/reconstruction`), so tests import the working-tree source, not the stale `build/lib` copy. Matches `pyproject.toml` (`>=3.11`) and the documented route (`docs/TEST_REPRODUCIBILITY.md`). | **Yes** |
| Existing images `chaya-acceptance-reconstruction-worker:latest`, `chaya-worker:smoke`, `chaya-physical-worker:colmap` | CPU worker images, Python 3.12.14–15, **no torch, no pytest** (`physical-worker:colmap` has `/usr/bin/colmap`). They cannot run the splat tests without installing torch. | No |
| `chaya-ml-eval:latest` | Vision service image with torch 2.14.0+cpu and pytest, but a stale installed `chaya_worker`. It is no better than the venv. | No |
| GPU image `chaya-worker-gpu` | Does not exist locally. It could not be built here (no NVIDIA GPU; audit D-1). | No |
| New Python 3.12 env | Not needed. Only **4.8 GB** is free on C:, so a second torch install was avoided. | No |
| System Python 3.14.3 | Not used. It is outside the CI matrix, and it lacks the OpenCV Haar cascades the privacy tests need. | No |

Docker daemon: up (Docker Desktop 29.6.2, Compose v5.3.1). It was used only to inspect the images above.

### Versions in the test environment vs the pinned targets

| Package / tool | `.venv` (used) | CI CPU job (`python.yml`) | GPU image pin |
|---|---|---|---|
| Python | 3.12.10 | 3.12 | 3.12 |
| torch | **2.14.0+cpu** | 2.7.1 (CPU index) | 2.7.1+cu126 |
| gsplat | not installed | not installed | 1.5.3 (built with CUDA) |
| numpy | 2.2.6 | from pyproject (`>=1.26`) | 2.2.6 |
| scipy | 1.18.1 | from pyproject | 1.15.3 |
| opencv-python-headless | 4.14.0.94 | from pyproject | 4.11.0.86 |
| pytest | 9.1.1 | from `[dev]` | 8.4.1 (installed at test time) |
| scikit-image / imageio-ffmpeg | 0.26.0 / 0.6.0 | from `[dev]` | n/a |
| transformers | not installed | 4.57.1 | 4.52.4 |
| ffmpeg | 7.1 (bundled `imageio_ffmpeg`) | | |
| COLMAP / GLOMAP | not found / not found | | COLMAP 3.9 (Ubuntu) |
| chaya-navmesh | `native/chaya-navmesh/build/chaya-navmesh.exe` (MinGW build) | built in CI | |
| node (viewer check) | v24.14.0, `apps/web/node_modules/@mkkellogg/gaussian-splats-3d` present | 22 | |

Caveat: the venv's torch (2.14) is newer than CI's and the image's pin (2.7.1), so these results confirm behaviour on
torch 2.14 CPU only. A run against the exact pin (torch 2.7.1 CPU wheel, about 200 MB) was not done because of the
disk constraint.

Repository state: HEAD `ae0dfc4`. The only uncommitted file was `docs/gaussian-splatting-audit.md`, which contains no code.

## 2. Commands and results

All commands were run from `services/reconstruction` with `HF_HUB_OFFLINE=1` (as in CI). Each used
`.venv/Scripts/python.exe -m pytest -p no:cacheprovider -q -rfEs --basetemp=<scratch> --junitxml=<scratch>` plus the
arguments below. The suites were run narrowest first. Counts are taken from each run's JUnit XML.

| # | Arguments | Exit | Tests | Passed | Failed | Errors | Skipped | Time |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| 1 | `tests/splat/test_splat_dataset.py` | 0 | 3 | 3 | 0 | 0 | 0 | 19 s |
| 2 | `tests/splat/test_privacy_masked_training.py` | 0 | 7 | 7 | 0 | 0 | 0 | 37 s |
| 3 | `tests/splat/test_splat_training.py` | 0 | 17 | 17 | 0 | 0 | 0 | 31 s |
| 4 | `tests/splat/test_training_lifecycle.py` | 0 | 33 | 33 | 0 | 0 | 0 | 17 s |
| 5 | `tests/splat/test_splat_preflight_and_failures.py` | 0 | 31 | 31 | 0 | 0 | 0 | 18 s |
| 6 | `tests/unit/test_splat_color.py tests/unit/test_ksplat_validation.py tests/unit/test_ksplat_fixture.py` | 0 | 24 | 24 | 0 | 0 | 0 | 2 s |
| 7 | `tests/smoke/test_splat_artifact_smoke.py` | 0 | 3 | 3 | 0 | 0 | 0 | 20 s |
| 8 | `tests/splat tests/unit/test_splat_color.py` (CI's splat guard step) | 0 | 94 | 94 | 0 | 0 | 0 | 73 s |
| 9 | `tests/smoke` | 0 | 16 | 16 | 0 | 0 | 0 | 31 s |
| 10 | `tests/orchestration` | 0 | 74 | 73 | 0 | 0 | 1 | 122 s |
| 11 | `tests/unit` | 0 | 396 | 396 | 0 | 0 | 0 | 211 s |
| 12 | `-m "not gpu and not open3d and not integration and not acceptance"` (CI's CPU selection; 62 deselected) | 0 | 638 | **636** | 0 | 0 | 2 | 479 s |
| 13 | `-m gpu tests/gpu` (recorded only to capture skip reasons) | 0 | 9 | **0** | 0 | 0 | **9** | 9 s |

Run 12 by area: unit 396, splat 91, orchestration 74, navmesh 51 (real Recast/Detour binary), smoke 16,
model_loading 5, liveness 4, ml 1 (skipped).

## 3. Failures

None. No test failed or errored in any run, so nothing needed diagnosis (step 4). No test was modified, skipped or relaxed.

The audit's defect **D-2** (the GPU acceptance test calls `retain_grad()` under `no_grad`) is still present. It
**cannot** show up in these runs: it sits in a GPU-only test, and the CPU test renderer guards `retain_grad`. Its
reproduction (`RuntimeError: can't retain_grad on Tensor that has requires_grad=False`) is recorded in the audit.

## 4. Skipped tests and why

| Test | Reason (verbatim) | Classification |
|---|---|---|
| `tests/orchestration/test_stages_and_orchestrator.py::test_unwritten_stages_never_succeed_and_never_create_reconstruction_files[NOTSET]` | `got empty parameter set for (stage)` | Expected. No stage is unimplemented any more; CI lists it in `CPU_EXPECTED_SKIPS`. |
| `tests/ml/test_training_smoke.py` (module) | `could not import 'transformers': No module named 'transformers'` | Local environment gap, not related to splats. CI installs transformers 4.57.1, where this skip would be *unexpected*. Per project notes it runs in `chaya-ml-eval`. Not installed here because of disk space. |
| `tests/gpu/test_gsplat_cuda.py` (4 tests) | `torch + gsplat + a CUDA device are not all available` | **Needs an NVIDIA GPU.** NOT RUN. |
| `tests/gpu/test_splat_acceptance.py::test_gsplat_training_on_cuda_produces_a_validated_viewer_artifact` | `NOT RUN, GPU training is not validated here: PACKAGE_MISSING gsplat; TORCH_WITHOUT_CUDA torch 2.14.0+cpu; TOOL_MISSING COLMAP` | **Needs an NVIDIA GPU** plus the GPU image. NOT RUN. |
| `tests/gpu/test_reconstruction_toolchain.py` (3 tests) | `CHAYA_TEST_IMAGE_SEQUENCE is not set to a folder of real overlapping photos` | Needs real photos, COLMAP, and a GPU for the splat test. NOT RUN. |
| `tests/gpu/test_reconstruction_toolchain.py::test_geometric_cleanup_and_plane_fitting_run_on_a_real_synthetic_room` | `Open3D is not installed` | Open3D (CI `open3d` job; currently failing there, FINAL_AUDIT P1-14). Not a splat test. |

A skip is not a pass. **0 of the 9 GPU-marked tests have ever run** (here or in CI: `gpu.yml` has no runs).

## 5. What the tests exercise

| Suite | Needs NVIDIA GPU | Data | Exercises real implementation? |
|---|---|---|---|
| `tests/splat/test_splat_training.py`, `test_training_lifecycle.py` | No | Synthetic: checkerboard and single-colour images, four-camera 24×24 rig, plane of SfM points | **Real** `train()`, L1+D-SSIM loss, LR schedule, clone/split/prune/cap, opacity reset, checkpoint save/load/resume, time budget/PARTIAL, divergence, `publish_outcome`, PLY round trip. The renderer is a **CPU test double** (`tests/splat/renderer.py`, isotropic), **not gsplat**. |
| `tests/splat/test_privacy_masked_training.py` | No | Synthetic | Real masked loss and gradients, and mask use inside `train()`. Test renderer. |
| `tests/splat/test_splat_preflight_and_failures.py` | No | Synthetic | Preflight *logic* against a **fake `Toolchain`**. Real checkpoint corruption handling and failure mapping. One real stage run on this host proves the structured `DEPENDENCY_UNAVAILABLE` refusal. |
| `tests/splat/test_splat_dataset.py` | No | **Synthetic** ray-traced room corner (the GPU acceptance dataset) | Real camera-convention and COLMAP binary-model checks on that dataset. |
| `tests/unit/test_splat_color.py`, `test_ksplat_*.py` | No | Synthetic / committed fixture | Real colour convention and .ksplat encoder and validator. |
| `tests/smoke/test_splat_artifact_smoke.py` | No | Synthetic cloud trained on CPU with the test renderer | Real ARTIFACT_GENERATION → .ksplat → **pinned viewer library load** (node). A PARTIAL result is never published as the viewer asset. |
| `tests/gpu/test_gsplat_cuda.py` | **Yes** | Synthetic plane | Real gsplat CUDA rasteriser: version, SH colour, adapter contract, short train plus resume. **Not run.** |
| `tests/gpu/test_splat_acceptance.py` | **Yes** | **Synthetic by default**; real only with `CHAYA_SPLAT_DATASET` | Full real stage (gsplat, COLMAP, orchestrator, ARTIFACT_GENERATION, viewer). **Not run**, and it would fail on D-2. |
| `tests/gpu/test_reconstruction_toolchain.py` (splat test) | **Yes** | **Real photos** (`CHAYA_TEST_IMAGE_SEQUENCE`) | Real COLMAP/GLOMAP poses and a real splat. **Not run.** |

No test that reconstructs an actual scene with gsplat has ever been executed.

## 6. Reproduce

```sh
cd services/reconstruction
export HF_HUB_OFFLINE=1
.venv/Scripts/python.exe -m pytest -p no:cacheprovider -q -rfEs tests/splat tests/unit/test_splat_color.py     # 94 passed
.venv/Scripts/python.exe -m pytest -p no:cacheprovider -q -rfEs -m "not gpu and not open3d and not integration and not acceptance"  # 636 passed, 2 skipped
.venv/Scripts/python.exe -m pytest -p no:cacheprovider -q -rfEs -m gpu tests/gpu                               # 9 skipped (NOT RUN)
```

The full sequence (13 runs, about 18 min) used the session runner `run_suites.py`, which writes each run's log, JUnit
XML and `results.json`. These are session evidence outside the repository.

## 7. After the fixes (same day)

Fixed: audit **D-1** (GPU image dependency resolution), **D-2** (`retain_grad` under `no_grad`), and **D-7** (found while
fixing). D-7: `tests/gpu/splat_dataset.from_colmap_project` returned no cameras, so on a real COLMAP project the
acceptance test's PSNR check silently did not run, and it re-encoded the project's frames instead of copying them.
Status is recorded in `docs/gaussian-splatting-audit.md`. Same environment and runner:

| Arguments | Exit | Tests | Passed | Failed | Errors | Skipped |
|---|---:|---:|---:|---:|---:|---:|
| `tests/splat/test_gsplat_adapter.py` (new) | 0 | 3 | 3 | 0 | 0 | 0 |
| `tests/splat/test_splat_dataset.py` (+2) | 0 | 5 | 5 | 0 | 0 | 0 |
| `tests/splat tests/unit/test_splat_color.py` | 0 | 99 | 99 | 0 | 0 | 0 |
| `tests/smoke` | 0 | 16 | 16 | 0 | 0 | 0 |
| `-m "not gpu and not open3d and not integration and not acceptance"` | 0 | 643 | **641** | 0 | 0 | 2 (same as before) |
| `-m gpu tests/gpu` | 0 | 9 | 0 | 0 | 0 | 9 (same as before) |

Each new test failed before its fix. The adapter test failed with
`RuntimeError: can't retain_grad on Tensor that has requires_grad=False` at `splat_training.py:533`; the dataset test
failed with `cameras=[]`. Beyond pytest:

- D-1: `pip install --dry-run -r gpu/requirements-gpu.txt -c gpu/constraints-gpu.txt` in `python:3.12-slim-bookworm`
  failed before the fix with `ResolutionImpossible` (`open-clip-torch 2.32.0 depends on torch>=1.9.0`; constraint
  `torch==2.7.1+cu126`). After the fix, with torch and torchvision registered from their real wheel metadata
  (sha256-checked PEP 658 files from the PyTorch index), it exits 0 and installs 119 packages, all from PyPI.
  `docker build --check` reports no warnings. The image itself was **not built** (no GPU; 4 GB free disk).
- D-7: the fixed loader was checked with the **real** `colmap model_converter` (COLMAP 3.8, `chaya-physical-worker:colmap`)
  on a binary COLMAP project with JPEG frames. Result: 16 training cameras, frames byte-identical, ground truth equal to
  the decoded frames, poses and K as written.

GPU runtime verification of all three remains **blocked**: no NVIDIA GPU.

## 8. Verification of D-3 to D-6 (2026-10-23)

Code: HEAD `6929948` (D-3 `fb69c33`, D-4 `e96d477`, D-5 `f672b7f`, D-6 `6929948`), plus the uncommitted V-2 fix and
its test (audit, "Verification pass"). Host: Windows 11, Intel Graphics only, 4.0–4.9 GB free on C:.

**Worker:** `services/reconstruction/.venv`, with Python 3.12.10, torch 2.14.0+cpu and numpy 2.2.6. One full run:
`.venv/Scripts/python -m pytest -q -p no:cacheprovider -rs --junitxml=…`. It exited 0 in 6 min 48 s. The groups
below come from that run's JUnit XML. The CI row matches each id from `--collect-only -m "not gpu and not open3d and
not integration and not acceptance"` against the same XML; all 732 matched.

| Group | Tests | Passed | Failed | Errors | Skipped |
|---|---:|---:|---:|---:|---:|
| `tests/splat` other than privacy (unit, lifecycle, checkpoint resume and persistence, quality gate, adapter, dataset) | 138 | 138 | 0 | 0 | 0 |
| `tests/splat/test_privacy_masked_training.py` | 7 | 7 | 0 | 0 | 0 |
| `tests/orchestration/test_privacy_masks.py` | 17 | 17 | 0 | 0 | 0 |
| `tests/orchestration` (all, incl. `test_stage_inputs.py`, `test_stages_and_orchestrator.py`) | 98 | 97 | 0 | 0 | 1 |
| artifact validation: `tests/unit/test_ksplat*`, `test_stage_artifact_contract.py`, `test_splat_color.py` | 68 | 68 | 0 | 0 | 0 |
| `tests/smoke` (pipeline smoke, splat → .ksplat → pinned viewer) | 17 | 17 | 0 | 0 | 0 |
| `tests/gpu` | 19 | **0** | 0 | 0 | **19** |
| `tests/integration` + `tests/acceptance` | 43 | **0** | 0 | 0 | **43** |
| **All** | **795** | **731** | 0 | 0 | **64** |
| CI CPU selection | 732 | **731** | 0 | 0 | 1 |

**Skipped means not run:**
- GPU, 19:
  - 4 need torch-CUDA, gsplat and a device.
  - The acceptance test stops at its preflight: gsplat missing, CPU-only torch, no COLMAP.
  - 3 need `CHAYA_TEST_IMAGE_SEQUENCE`.
  - 11 need Open3D.
- Integration and acceptance, 43:
  - 40 are `BLOCKED: no stack configured`.
  - 2 have no MinIO.
  - 1 has no stack for the end-to-end test.
- Other, 2:
  - 1 orchestration case has an empty parameter set (expected).
  - `tests/ml` is skipped because `transformers` is not installed. It is unrelated to splats.

**Worker on the GPU image's torch version.** A throwaway venv held torch **2.7.1+cpu** (PyTorch CPU index), numpy
2.2.6, scipy 1.15.3, opencv-python-headless 4.11.0.86, boto3 1.39.0, requests 2.32.4 and pytest. It ran
`pytest -q -p no:cacheprovider tests/splat tests/smoke/test_splat_artifact_smoke.py`: **149 passed, 0 failed,
0 errors, 0 skipped** (1 min 42 s). The venv was deleted afterwards for disk space.

**Java control plane.** The full `mvn -B -ntp verify` ran in `maven:3.9-eclipse-temurin-21`, with Testcontainers
`pgvector/pgvector:pg17` and the pinned MinIO mirror. All images were already cached. It ended in `BUILD SUCCESS` after
23 min 19 s: **668 tests, 0 failures, 0 errors, 4 skipped**. The skips are `ClamAvLiveTest` (2) and
`KeycloakLiveTest` (2), which are opt-in live tests. Relevant classes, all 0 failures, errors and skips:

| Class | Tests |
|---|---:|
| `SplatResumeControlPlaneTest` | 12 |
| `PipelineControlPlaneTest` | 20 |
| `ProcessingLiveStatusTest` | 10 |
| `ArtifactIntegrityTest` | 6 |
| `pipeline.PipelineArtifactContractTest` | 5 |
| `PipelineOutputContractTest` | 4 |
| `KsplatPublicationTest` | 4 |
| `ReconstructionArtifactServingTest` | 1 |

**Other checks:**
- `ruff check chaya_worker tests`: clean.
- `apps/web` `npm test`: 385 passed, 0 failed, 0 skipped.

**Not run:** no GPU test, no live-stack test, no real-scene training.

## 9. Next recommended action (updated 2026-10-23)

1. Done: D-1, D-2 and D-7 (section 7), and D-3 to D-6 (section 8), with regression tests. Still open: audit V-1
   (the run status of a reopened PARTIAL run whose resume fails), and every GPU-only item (P-1 to P-6).
2. Done in section 8: the splat suites on the pinned torch 2.7.1 (CPU). Still not run there: `tests/ml`, which needs
   `transformers`.
3. On a CUDA host: build the GPU image, run preflight, then `CHAYA_REQUIRE_GPU=1 pytest -m gpu`, first synthetic and
   then with `CHAYA_SPLAT_DATASET` set to a real COLMAP project (`gaussian-splatting-gpu-environment.md` §7). Only that
   run can establish whether GPU training works and what held-out PSNR a real capture reaches.
