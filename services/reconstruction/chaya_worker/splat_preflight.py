"""Preflight of SPLAT_RECONSTRUCTION: everything that can be known to be wrong before any archive is unpacked or any
frame decoded, reported together and in words an operator can act on.

Environment (`environment_report`):
  PACKAGE_MISSING                torch, gsplat, numpy, scipy or OpenCV is not installed
  PACKAGE_VERSION_UNSUPPORTED    gsplat is not GSPLAT_VALIDATED_VERSION, torch is older than MIN_TORCH, Python is too old
  PACKAGE_VERSION_DRIFT          an installed version differs from the worker image's lock (gpu-lock.json)
  TORCH_WITHOUT_CUDA             torch is a CPU-only build
  GPU_NOT_PRESENT                no NVIDIA GPU is visible to this process at all: no nvidia-smi answer, no
                                 /proc/driver/nvidia, no /dev/nvidia0 (a CPU host, or a container started without --gpus)
  CUDA_DRIVER_INCOMPATIBLE       an NVIDIA driver is there but older than gpu_min_driver_version (CUDA 12.6 needs 560.28),
                                 or torch's CUDA initialisation says the driver is too old or insufficient
  CUDA_UNAVAILABLE               a GPU and driver are visible but torch still sees no usable device (container runtime,
                                 CUDA_VISIBLE_DEVICES, a driver/library mismatch); the detail carries torch's own message
  GPU_CAPABILITY_UNSUPPORTED     no device reaches gsplat_min_compute_capability
  GPU_MEMORY_INSUFFICIENT        the selected device has less free memory than gpu_min_free_memory_mb
  GSPLAT_CUDA_BACKEND_MISSING    gsplat's CUDA kernels cannot be loaded (Toolchain.gsplat_backend)
  TOOL_MISSING                   COLMAP, needed to read the sparse model, is not installed

Configuration and inputs (`config_problems`, `input_problems`):
  CONFIG_INVALID                 settings that cannot produce a valid artifact
  INSUFFICIENT_FRAMES            fewer posed frames with images than gsplat_min_training_frames
  INSUFFICIENT_POINTS            too few SfM points to seed Gaussians

A checkpoint offered as input is checked by chaya_worker.splat_training.load_checkpoint before the frames are prepared.

`python -m chaya_worker.splat_preflight` prints the environment report as JSON and exits 0 only when training can
start. It never claims more than it measured: a CUDA device is only reported when torch itself enumerates one. The report also
carries the driver as nvidia-smi sees it (`driver`), free and total memory per device, and GLOMAP as an optional tool
(`optional`: POSE_ESTIMATION falls back to COLMAP's mapper without it; it is not part of this stage).
"""

from __future__ import annotations

import json
import os
import sys
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any

from .toolchain import Toolchain

GSPLAT_VALIDATED_VERSION = "1.5.3"  # chaya_worker.splat_training.gsplat_rasterizer was checked against this source
MIN_TORCH = (2, 6)  # torch.load weights_only default and CVE-2025-32434 (pyproject.toml)
MIN_PYTHON = (3, 11)
REQUIRED_MODULES = ("torch", "gsplat", "numpy", "scipy", "cv2")
DEFAULT_LOCK_PATH = Path("/opt/chaya/gpu-lock.json")  # written by services/reconstruction/Dockerfile.gpu
MIN_SFM_POINTS = 4


@dataclass(frozen=True)
class Problem:
    code: str
    message: str
    fix: str
    subject: str | None = None  # the missing thing, for DEPENDENCY_UNAVAILABLE's details.missing

    def as_dict(self) -> dict[str, str]:
        return asdict(self)


def _version_tuple(v: str | None) -> tuple[int, ...]:
    if not v:
        return ()
    head = v.split("+")[0]
    out = []
    for part in head.split("."):
        digits = "".join(ch for ch in part if ch.isdigit())
        if not digits:
            break
        out.append(int(digits))
    return tuple(out)


def load_lock(path: Path | None) -> dict[str, Any] | None:
    """The worker image's recorded versions, or None when there is no lock file (a development host)."""
    if path is None or not path.is_file():
        return None
    return json.loads(path.read_text(encoding="utf-8"))


def _driver_too_old(version: str | None, minimum: str | None) -> bool:
    if not version or not minimum:
        return False
    have, want = _version_tuple(version), _version_tuple(minimum)
    return bool(have) and bool(want) and have < want


def _cuda_problem(toolchain: Toolchain, detail: str, torch_cuda: str | None, min_driver: str | None) -> Problem:
    """Why a CUDA build of torch sees no device: no GPU at all, a driver too old for it, or something else."""
    driver = toolchain.nvidia_driver()
    run_on_gpu = (f"run on an NVIDIA GPU host with a driver supporting CUDA {torch_cuda} (>= {min_driver}), with the GPU "
                  "passed to the container (docker run --gpus all) and the NVIDIA Container Toolkit installed")
    if not driver["present"]:
        why = f": {driver['detail']}" if driver.get("detail") else ""
        return Problem("GPU_NOT_PRESENT", "no NVIDIA GPU is visible to this process (no nvidia-smi answer, no "
                       f"/proc/driver/nvidia, no /dev/nvidia0){why}", run_on_gpu, "cuda")
    lowered = detail.lower()
    if _driver_too_old(driver.get("version"), min_driver) or ("driver" in lowered and ("too old" in lowered or "insufficient" in lowered)):
        return Problem("CUDA_DRIVER_INCOMPATIBLE", f"NVIDIA driver {driver.get('version') or '(version unknown)'} cannot run CUDA "
                       f"{torch_cuda} ({detail})", f"upgrade the host's NVIDIA driver to {min_driver} or newer", "cuda-driver")
    return Problem("CUDA_UNAVAILABLE", f"an NVIDIA GPU is visible (driver {driver.get('version')}) but torch reports no usable "
                   f"CUDA device: {detail}", "check docker run --gpus all, CUDA_VISIBLE_DEVICES and the NVIDIA Container Toolkit",
                   "cuda")


def environment_report(toolchain: Toolchain, *, min_compute_capability: float, allow_jit: bool = False,
                       lock: dict[str, Any] | None = None, python_version: tuple[int, int] | None = None,
                       min_driver_version: str | None = None, min_free_memory_mb: int = 0) -> dict[str, Any]:
    """What this worker can and cannot do for SPLAT_RECONSTRUCTION. `ok` is True only when every check passed; then
    `selected_device` is the CUDA device training will use."""
    problems: list[Problem] = []
    py = python_version or sys.version_info[:2]
    if tuple(py) < MIN_PYTHON:
        problems.append(Problem("PACKAGE_VERSION_UNSUPPORTED", f"Python {py[0]}.{py[1]} is older than {MIN_PYTHON[0]}.{MIN_PYTHON[1]}",
                                "run the worker on Python 3.12 (Dockerfile.gpu)"))
    present = {m: toolchain.module(m).available for m in REQUIRED_MODULES}
    for m, ok in present.items():
        if not ok:
            problems.append(Problem("PACKAGE_MISSING", f"python module '{m}' is not installed",
                                    "install the GPU worker's pinned requirements (services/reconstruction/gpu/requirements-gpu.txt)", m))
    versions: dict[str, Any] = {m: toolchain.module_version(m) for m in REQUIRED_MODULES if present[m]}
    versions["python"] = f"{py[0]}.{py[1]}"

    torch_build = toolchain.torch_build() if present["torch"] else {"version": None, "cuda": None, "error": None}
    versions["torch_cuda"] = torch_build.get("cuda")
    devices: list[dict] = []
    selected = None
    if present["torch"]:
        if torch_build.get("error"):
            problems.append(Problem("PACKAGE_MISSING", torch_build["error"], "reinstall torch from the pinned CUDA index", "torch"))
        elif _version_tuple(torch_build["version"])[:2] < MIN_TORCH:
            problems.append(Problem("PACKAGE_VERSION_UNSUPPORTED", f"torch {torch_build['version']} is older than "
                                    f"{MIN_TORCH[0]}.{MIN_TORCH[1]}", "install the pinned torch (requirements-gpu.txt)"))
        if not torch_build.get("error") and not torch_build.get("cuda"):
            problems.append(Problem("TORCH_WITHOUT_CUDA", f"torch {torch_build['version']} is a CPU-only build",
                                    "install the CUDA build of torch from the pinned index (requirements-gpu.txt)", "cuda"))
        else:
            cuda = toolchain.cuda()
            if not cuda.available:
                problems.append(_cuda_problem(toolchain, cuda.detail or "torch reports no CUDA device", torch_build.get("cuda"),
                                              min_driver_version))
            else:
                devices = [{**d, "memory": toolchain.cuda_memory(d["index"])} for d in toolchain.cuda_devices()]
                selected = toolchain.select_cuda_device(min_compute_capability)
                if selected is None:
                    best = max((d["compute_capability_value"] for d in devices), default=None)
                    problems.append(Problem("GPU_CAPABILITY_UNSUPPORTED",
                                            f"no CUDA device has compute capability >= {min_compute_capability} (best: {best})",
                                            "use a Volta (7.0) or newer NVIDIA GPU", "cuda-compute-capability"))
                else:
                    memory = next((d["memory"] for d in devices if d["index"] == selected["index"]), None)
                    selected = {**selected, "memory": memory}
                    free_mb = memory["free_bytes"] / 2**20 if memory else None
                    if min_free_memory_mb and free_mb is not None and free_mb < min_free_memory_mb:
                        problems.append(Problem("GPU_MEMORY_INSUFFICIENT", f"CUDA device {selected['index']} ({selected['name']}) has "
                                                f"{free_mb:.0f} MiB free; at least {min_free_memory_mb} MiB are required",
                                                "free the GPU (other processes hold its memory) or use a larger one",
                                                "cuda-memory"))
    if present["gsplat"]:
        gv = versions.get("gsplat")
        if gv != GSPLAT_VALIDATED_VERSION:
            problems.append(Problem("PACKAGE_VERSION_UNSUPPORTED", f"gsplat {gv} is installed; the rasteriser adapter was validated "
                                    f"against {GSPLAT_VALIDATED_VERSION}", f"install gsplat=={GSPLAT_VALIDATED_VERSION} built from source"))
        if present["torch"] and torch_build.get("cuda"):
            backend = toolchain.gsplat_backend(allow_jit=allow_jit)
            versions["gsplat_backend"] = backend.version
            if not backend.available:
                problems.append(Problem("GSPLAT_CUDA_BACKEND_MISSING", backend.detail or "gsplat's CUDA kernels are unavailable",
                                        "use the GPU worker image (services/reconstruction/Dockerfile.gpu)", "gsplat-cuda-backend"))
    driver = toolchain.nvidia_driver()
    glomap = toolchain.glomap()
    colmap = toolchain.colmap()
    versions["colmap"] = colmap.version
    if not colmap.available:
        problems.append(Problem("TOOL_MISSING", "COLMAP is not installed; it converts the sparse model for training",
                                "install COLMAP or set COLMAP_BIN", "colmap"))
    if lock:
        for key in ("torch", "gsplat", "numpy", "scipy", "cv2", "torch_cuda", "python"):
            want, have = lock.get(key), versions.get(key)
            if want is not None and have is not None and str(want) != str(have):
                problems.append(Problem("PACKAGE_VERSION_DRIFT", f"{key} is {have}, the worker image was built with {want}",
                                        "rebuild or reinstall the worker image; do not upgrade packages inside it"))
    return {"ok": not problems, "problems": [p.as_dict() for p in problems], "versions": versions, "devices": devices,
            "selected_device": selected, "min_compute_capability": min_compute_capability, "allow_jit_backend": allow_jit,
            "driver": driver, "min_driver_version": min_driver_version, "min_free_memory_mb": min_free_memory_mb,
            "optional": {"glomap": glomap.as_dict()}, "lock": lock}


def missing_names(report: dict[str, Any]) -> list[str]:
    """What the existing DEPENDENCY_UNAVAILABLE consumers show (details.missing): one name per problem."""
    return list(dict.fromkeys(p["subject"] or p["code"].lower() for p in report["problems"]))


def config_problems(config: Any, *, max_ksplat_bytes: int, encoded_size) -> list[Problem]:
    """Settings that cannot produce a valid artifact, found before any work. `config` is a TrainingConfig."""
    problems = []
    if config.iterations < 1:
        problems.append(Problem("CONFIG_INVALID", "gsplat_iterations is below 1: the SfM seed alone is not a reconstruction",
                                "set GSPLAT_ITERATIONS to a positive number"))
    if config.densify_stop < config.densify_start:
        problems.append(Problem("CONFIG_INVALID", "gsplat_densify_stop is before gsplat_densify_start",
                                "set GSPLAT_DENSIFY_STOP after GSPLAT_DENSIFY_START"))
    if config.max_gaussians < 1 or encoded_size(config.max_gaussians) > max_ksplat_bytes:
        problems.append(Problem("CONFIG_INVALID", f"gsplat_max_gaussians {config.max_gaussians} allows a cloud whose .ksplat would "
                                f"exceed the viewer limit of {max_ksplat_bytes} bytes", "lower GSPLAT_MAX_GAUSSIANS"))
    if config.checkpoint_every < 0:
        problems.append(Problem("CONFIG_INVALID", "gsplat_checkpoint_every is negative", "set it to 0 (end only) or more"))
    return problems


def input_problems(*, posed_frames: int | None, sfm_points: int, min_frames: int) -> list[Problem]:
    """posed_frames None: not counted yet (the points are checked before the frames are decoded)."""
    problems = []
    if sfm_points < MIN_SFM_POINTS:
        problems.append(Problem("INSUFFICIENT_POINTS", f"only {sfm_points} 3D points from SfM; at least {MIN_SFM_POINTS} are needed "
                                "to seed Gaussians", "capture again with more texture and overlap"))
    if posed_frames is not None and posed_frames < min_frames:
        problems.append(Problem("INSUFFICIENT_FRAMES", f"only {posed_frames} posed frames could be matched to images; at least "
                                f"{min_frames} are needed to train", "capture again with more overlap between views"))
    return problems


def main(argv: list[str] | None = None) -> int:
    from .settings import Settings  # noqa: PLC0415

    s = Settings.from_env()
    lock_path = Path(os.environ.get("CHAYA_GPU_LOCK", str(DEFAULT_LOCK_PATH)))
    report = environment_report(Toolchain(), min_compute_capability=s.gsplat_min_compute_capability,
                                allow_jit=s.gsplat_allow_jit_backend, lock=load_lock(lock_path),
                                min_driver_version=s.gpu_min_driver_version, min_free_memory_mb=s.gpu_min_free_memory_mb)
    print(json.dumps(report, indent=2, default=str))
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
