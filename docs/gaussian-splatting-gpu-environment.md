# Gaussian Splatting: GPU Container Environment (2026-10-19)

Follows `docs/gaussian-splatting-audit.md` and `docs/gaussian-splatting-test-baseline.md`. This document validates the
GPU worker image (`services/reconstruction/Dockerfile.gpu`) and its preflight workflow as far as this host allows.

**Result.**
- **The image was not built:** the build needs well over 15 GB, and this host has 3.9 GB free.
- **No NVIDIA GPU is present:** Intel graphics only, and `docker run --gpus all` fails.
- **Preflight was not run in the image**, because there is no image.

Everything that can be checked without a build or a GPU was checked, and it passed (section 4). **CUDA training is not
operational on any machine yet: it has never been executed.**

## 1. What the image is (from the Dockerfile, verified against registries)

| Item | Value | Verified how |
|---|---|---|
| Build base | `nvidia/cuda:12.6.3-devel-ubuntu24.04@sha256:392c0df7…8c148a` (amd64 manifest `sha256:badf6c452e8b…`, 11 layers, **4.08 GB compressed**) | `docker buildx imagetools inspect`: the pin is still the tag's current digest |
| Runtime base | `nvidia/cuda:12.6.3-runtime-ubuntu24.04@sha256:92906d87…884ba924` (amd64 `sha256:2c8193530ecc…`, 9 layers, **1.53 GB compressed**) | same |
| CUDA in base | `CUDA_VERSION=12.6.3`, cudart `12.6.77-1`, `NVIDIA_REQUIRE_CUDA=cuda>=12.6 …` | image config |
| Python | Ubuntu noble `python3` **3.12.3** (`3.12.3-0ubuntu2.1`) in a venv at `/opt/venv`, copied build → runtime | Launchpad (noble) |
| PyTorch | **2.7.1+cu126**, wheel sha256 `63bce059…c978` (822 MB) | PyTorch index hash = the Dockerfile's pin |
| torchvision | **0.22.1+cu126**, wheel sha256 `d6f17966…7b2e` (7.5 MB) | PyTorch index |
| gsplat | **1.5.3** from sdist sha256 `343f080c…a906` (4.7 MB), built with nvcc into `gsplat.csrc` | downloaded and hash-checked; contents inspected (section 4) |
| CUDA archs | `TORCH_CUDA_ARCH_LIST="7.0;7.5;8.0;8.6;8.9;9.0+PTX"` (Volta…Hopper native, PTX for newer), `MAX_JOBS=4` | Dockerfile; used by torch's `CUDAExtension` |
| COLMAP | Ubuntu noble `colmap` **3.9.1-2build2** (universe, built without CUDA); used by SPLAT_RECONSTRUCTION only for `model_converter` | Launchpad |
| Runtime libs (apt) | `python3`, `colmap`, `libgl1` (1.7.0), `libgomp1` (14.x; Open3D needs both). The CUDA runtime comes from the base image; cuDNN, cuBLAS, NCCL and the rest come from torch's pinned `nvidia-*-cu12` wheels | Dockerfile; Launchpad |
| Other Python | closure in `gpu/constraints-gpu.txt`. The largest are nvidia-cudnn 571 MB, open3d 448 MB, nvidia-cublas 393 MB, cusparse 217 MB, nccl 201 MB, cufft 200 MB, cusolver 158 MB, cusparselt 157 MB, triton 156 MB | PyPI JSON sizes |
| Lock | `gpu/write_lock.py` runs preflight at build time and fails the build unless the only problem is `CUDA_UNAVAILABLE`. It writes `/opt/chaya/gpu-lock.json` (python, torch, torch_cuda, gsplat, gsplat_backend, numpy, scipy, cv2, colmap, arch list) and `/opt/chaya/pip-freeze.txt` | Dockerfile, `write_lock.py` |
| User / entry | uid 10001, `CMD python -m chaya_worker`, healthcheck `python -m chaya_worker.liveness`, model volume `/models` | Dockerfile |

**The build needs no GPU.** It compiles gsplat with nvcc from the devel image, and `write_lock.py` tolerates exactly
one problem, `CUDA_UNAVAILABLE`. This is by design.

**Size.** The base layers are 5.6 GB compressed and the Python wheels 3.68 GB compressed. Unpacked, plus the venv
copied into the runtime stage and nvcc intermediates, that is well over the "about 15 GB" the Dockerfile header states.
Plan at least **30–40 GB** of free disk for a first build.

**GPU and driver requirements.**
- An NVIDIA GPU with compute capability ≥ 7.0 (`GSPLAT_MIN_COMPUTE_CAPABILITY`).
- A driver that supports CUDA 12.6 (≥ 560 on Linux; the base image's `NVIDIA_REQUIRE_CUDA=cuda>=12.6` makes the
  container toolkit refuse older drivers).
- The NVIDIA Container Toolkit and `docker run --gpus all`.

Architectures newer than 9.0 run gsplat through PTX JIT. Whether torch 2.7.1+cu126 supports them is unverified (audit P-2).

## 2. This host (checked today)

| Check | Result |
|---|---|
| Display adapters (`Win32_VideoController`, PnP Display class) | **Intel(R) Graphics** only (driver 32.0.101.8331) |
| `nvidia-smi` | not found (not on PATH, not in `System32`) |
| Docker | Desktop 29.6.2, WSL2 backend (`docker-desktop` running), 18 CPUs, 7.5 GiB memory |
| Runtimes | `runc` (default), `nvidia` registered (bundled with Docker Desktop), CDI dirs `/etc/cdi`, `/var/run/cdi` |
| `docker run --rm --gpus all alpine echo …` | **exit 125**: `nvidia-container-cli: initialization error: WSL environment detected but no adapters were found` |
| `--runtime=nvidia` container | starts, but **no `/dev/nvidia*`**; no `/dev/dxg` in containers |
| Free disk on C: (the only drive) | **3.9 GB** |
| Docker data disk | `docker_data.vhdx` 118.5 GB on C: (it grows on demand). Inside the VM: 103.6 GB used. Its `df` "852 GB available" is the virtual size, not real space |
| `docker system df` | images 49.4 GB (27.3 GB reclaimable), build cache 60.4 GB (19.4 GB reclaimable), volumes 7.8 GB, containers 0.5 GB |
| Image `chaya-worker-gpu` | **does not exist** |

**Container Toolkit status:** installed, but unusable here because there is no NVIDIA adapter.

## 3. Docker environment (inspected, not changed)

- **Compose file `infra/docker/docker-compose.yml`:** services postgres, minio, minio-init, clamav, keycloak, and
  `reconstruction-worker` (profile `pipeline`). That worker builds the **CPU** `Dockerfile`; there is no `gpus` or
  `runtime` setting. Every published port binds to `127.0.0.1` (Postgres 5432, MinIO 9000/9001, ClamAV 3310,
  Keycloak 8081). No compose file runs a GPU worker. Per DEPLOYMENT.md §10, a GPU worker runs on its own GPU host with
  `docker run --gpus all`.
- **Existing stacks:** `chaya` (this compose file, 5 containers), `chaya-e2e` (11) and `chaya-prodcheck` (10) are all
  stopped. Only an unrelated `n8n` container is running. No container, volume or image was started, stopped or
  removed.
- **Builds:** CI `docker.yml` builds the CPU worker image only. `Dockerfile.gpu` is built only by the manual `gpu.yml`
  on a self-hosted `gpu` runner, which has **0 runs**. No build script in `scripts/` or `infra/` builds it.

## 4. Validation status

| Level | Status | Evidence |
|---|---|---|
| Dockerfile syntax and lint | **passed** | `docker build --check -f Dockerfile.gpu .`: "Check complete, no warnings found." |
| Base image pins | **passed** | Both digests resolve and equal the current `12.6.3-{devel,runtime}-ubuntu24.04` tags |
| Python dependency resolution | **passed (dry run)** | With the real torch and torchvision wheel metadata, `pip install --dry-run -r requirements-gpu.txt -c constraints-gpu.txt` exits 0, with 119 packages from PyPI (test baseline §7). Before the D-1 fix this was `ResolutionImpossible` |
| Pinned downloads exist with the pinned hashes | **passed** | torch: PyTorch index sha256 = pin. torchvision: index sha256 = pin. gsplat sdist: downloaded, `sha256sum -c` OK. Sizes via HEAD and PyPI |
| gsplat source vs Dockerfile and adapter assumptions | **passed (static)** | Static inspection of the sdist, not executed:<br>• vendored GLM (`gsplat/cuda/csrc/third_party/glm`), so nvcc needs no network<br>• `CUDAExtension("gsplat.csrc")`, built unless `BUILD_NO_CUDA=1`<br>• `_backend.py` loads `from gsplat import csrc as _C` before any JIT, matching preflight's probe<br>• `rasterization(means, quats, scales, opacities, colors, viewmats, Ks, width, height, …, sh_degree=None, packed=True, …)` matches the adapter's positional call<br>• unpacked `radii` is `[..., C, N, 2]`, and gsplat's own visibility is `(radii > 0).all(-1)`, exactly as the adapter does |
| apt packages of the runtime stage | **available** | Launchpad noble: colmap 3.9.1-2build2, python3 3.12.3, libgl1 1.7.0, libgomp1 14.x |
| **Image build** | **BLOCKED (disk)**, not attempted | 3.9 GB free vs well over 15 GB needed. Building would fill C:, which also holds Docker's disk and the stopped stacks' volumes (Postgres data included). Freeing space would mean pruning images or caches, which was ruled out |
| Dependency and import validation inside the image (`pip check`, `import open3d, transformers, open_clip`, `gsplat.csrc` load, `write_lock.py`) | **NOT RUN** | Needs the image |
| `python -m chaya_worker.splat_preflight` in the image | **NOT RUN** | Needs the image. (The host dev venv's preflight from the audit is not an image test: it reports gsplat missing, CPU torch, no COLMAP) |
| CPU-only worker tests | **passed** | Host venv, Python 3.12.10, torch 2.14 CPU: 641 passed, 0 failed, 2 expected skips (test baseline §7). These run the training mechanics with a CPU test renderer, not gsplat |
| CUDA backend initialisation (gsplat kernels on a device) | **BLOCKED (hardware)** | No NVIDIA adapter; `--gpus all` fails |
| Real CUDA training (`pytest -m gpu`, acceptance) | **BLOCKED (hardware)** | 9 GPU tests skipped; `gpu.yml` has never run |

No GPU availability, preflight result or training metric is claimed anywhere in this document.

## 5. Expected preflight on a correct GPU host

From `splat_preflight.environment_report`. This is what a passing run must show; it has **not** been observed.

- `ok: true` and exit 0
- `versions.python 3.12`, `torch 2.7.1+cu126`, `torch_cuda 12.6`, `gsplat 1.5.3`, `gsplat_backend prebuilt`,
  `colmap 3.9.x`
- `selected_device` with `compute_capability_value ≥ 7.0`
- no `PACKAGE_VERSION_DRIFT` against `/opt/chaya/gpu-lock.json`

On this host the in-image run would stop at `CUDA_UNAVAILABLE` even if the image existed.

## 6. Recommended next step: a cloud GPU host

1. **Provision** one Linux GPU VM:
   - a 24 GB-class GPU with compute capability ≥ 7.0 (e.g. NVIDIA L4, sm_89, or A10G, sm_86; both natively in the arch list);
   - Ubuntu 22.04 or 24.04, NVIDIA driver ≥ 560, Docker and the NVIDIA Container Toolkit;
   - **≥ 80 GB free disk**, 8+ vCPUs, 32 GB RAM (nvcc with `MAX_JOBS=4`).
2. **Check the host:** `nvidia-smi`, then `docker run --rm --gpus all nvidia/cuda:12.6.3-runtime-ubuntu24.04 nvidia-smi`.
3. **Build and check, from a checkout of this branch:**
   ```sh
   docker build -f services/reconstruction/Dockerfile.gpu -t chaya-worker-gpu services/reconstruction 2>&1 | tee build.log
   docker run --rm --gpus all chaya-worker-gpu python -m chaya_worker.splat_preflight      # must exit 0
   docker run --rm chaya-worker-gpu cat /opt/chaya/gpu-lock.json
   docker run --rm -v chaya-models:/models chaya-worker-gpu python /opt/chaya/fetch_models.py
   docker run --rm --gpus all --user 0 -e CHAYA_REQUIRE_GPU=1 -e CHAYA_GPU_ACCEPTANCE_REPORT=/out/gpu-acceptance.json \
     -v "$PWD/services/reconstruction/tests:/src/tests:ro" -v "$PWD/gpu-out:/out" -w /src chaya-worker-gpu \
     sh -c "pip install -q pytest==8.4.1 && python -m pytest -p no:cacheprovider -m gpu tests/gpu -rA"
   ```
   Then repeat the last command with `-e CHAYA_SPLAT_DATASET=/data/splat -v <real COLMAP project>:/data/splat:ro`.
4. **Alternatively**, register that VM as a self-hosted runner labelled `gpu` and dispatch `.github/workflows/gpu.yml`.
   That yields the same evidence (build log, preflight, `gpu.xml`, `gpu-acceptance.json`) as CI artifacts.
5. **Record** in this document: the build log's exit code and duration, image size, `gpu-lock.json`, the preflight
   JSON, and the pytest results. Leave "operational" unclaimed until the acceptance test passes on real data.
