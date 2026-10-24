"""Detection of the external tools and libraries the pipeline needs.

Detection is explicit and structured. A missing dependency becomes a DependencyError that names exactly
what is missing; it is never papered over with a substitute or an empty output.
"""

from __future__ import annotations

import importlib.util
import os
import shutil
import subprocess
from collections.abc import Callable, Iterable, Mapping
from dataclasses import asdict, dataclass

from .errors import DependencyError


def colmap_banner_has_cuda(text: str) -> bool:
    """`colmap help` prints e.g. "(Commit Unknown on Unknown without CUDA)" or "(Commit abc on 2024-01-01 with CUDA)"."""
    lowered = text.lower()
    return "with cuda" in lowered and "without cuda" not in lowered


def _num(text: str) -> float | None:
    try:
        return float(text)
    except ValueError:
        return None  # "[N/A]" on some boards


@dataclass(frozen=True)
class ToolStatus:
    name: str
    available: bool
    path: str | None = None
    version: str | None = None
    detail: str | None = None

    def as_dict(self) -> dict:
        return asdict(self)


class Toolchain:
    def __init__(self, env: Mapping[str, str] | None = None, which: Callable[[str], str | None] = shutil.which) -> None:
        self._env = os.environ if env is None else env
        self._which = which
        self._cache: dict[str, ToolStatus] = {}
        self._driver: dict | None = None

    # ---- executables ------------------------------------------------------------------------

    def _executable(self, name: str, env_var: str, *fallbacks: Callable[[], str | None]) -> ToolStatus:
        if name in self._cache:
            return self._cache[name]
        path = self._env.get(env_var) or self._which(name)
        if not path:
            for fallback in fallbacks:
                path = fallback()
                if path:
                    break
        if not path or not os.path.exists(path) and not self._which(path):
            status = ToolStatus(name, False, detail=f"not found on PATH; set {env_var} to its location")
        else:
            status = ToolStatus(name, True, path=path, version=self._version(path))
        self._cache[name] = status
        return status

    @staticmethod
    def _version(path: str) -> str | None:
        for flag in ("-version", "--version", "help"):
            try:
                out = subprocess.run([path, flag], capture_output=True, text=True, timeout=20)
            except (OSError, subprocess.SubprocessError):
                return None
            text = (out.stdout or out.stderr).strip().splitlines()
            if text and out.returncode == 0:
                return text[0][:160]
        return None

    def ffmpeg(self) -> ToolStatus:
        def bundled() -> str | None:
            try:  # optional dev extra: chaya-worker[ffmpeg-bundled]
                import imageio_ffmpeg

                return imageio_ffmpeg.get_ffmpeg_exe()
            except Exception:  # noqa: BLE001 - any import/lookup failure just means "not available"
                return None

        return self._executable("ffmpeg", "FFMPEG_BIN", bundled)

    def colmap(self) -> ToolStatus:
        return self._executable("colmap", "COLMAP_BIN")

    def glomap(self) -> ToolStatus:
        return self._executable("glomap", "GLOMAP_BIN")

    # ---- python libraries -------------------------------------------------------------------

    def module(self, name: str) -> ToolStatus:
        key = f"py:{name}"
        if key not in self._cache:
            try:
                found = importlib.util.find_spec(name) is not None
            except (ImportError, ValueError):
                found = False
            self._cache[key] = ToolStatus(name, found, detail=None if found else f"python module '{name}' is not installed")
        return self._cache[key]

    def cuda(self) -> ToolStatus:
        """A usable CUDA device: torch reports one. (An nvidia-smi alone is not enough to train on.)"""
        if "cuda" not in self._cache:
            status = ToolStatus("cuda", False, detail="torch is not installed, so no CUDA device can be used")
            if importlib.util.find_spec("torch") is not None:
                try:
                    import torch

                    ok = bool(torch.cuda.is_available())
                    detail = None
                    if not ok:
                        detail = "torch is installed but reports no CUDA device"
                        if torch.version.cuda:
                            try:  # is_available() swallows the reason; init() raises it (e.g. "driver ... is too old")
                                torch.cuda.init()
                            except Exception as init_exc:  # noqa: BLE001
                                detail = f"{detail}: {init_exc}"
                    status = ToolStatus("cuda", ok, version=torch.version.cuda, detail=detail)
                except Exception as exc:  # noqa: BLE001
                    status = ToolStatus("cuda", False, detail=f"torch failed to initialise: {exc}")
            self._cache["cuda"] = status
        return self._cache["cuda"]

    def gpu_present(self) -> bool:
        """nvidia-smi answers. Used only to decide COLMAP's GPU flags (with colmap_has_cuda)."""
        return self._which("nvidia-smi") is not None

    def colmap_has_cuda(self) -> bool:
        """Whether the COLMAP binary was built with CUDA: its banner says "with CUDA" or "without CUDA". Ubuntu's COLMAP
        (the GPU image's) is built without CUDA; asked for GPU SIFT it falls back to OpenGL SiftGPU, which needs a display
        a headless worker does not have. So GPU SIFT is requested only when this is True and a GPU is present."""
        colmap = self.colmap()
        if not colmap.available:
            return False
        try:
            out = subprocess.run([colmap.path, "help"], capture_output=True, text=True, timeout=20)
        except (OSError, subprocess.SubprocessError):
            return False
        return colmap_banner_has_cuda(out.stdout + out.stderr)

    def nvidia_driver(self) -> dict:
        """What the host's NVIDIA driver says, independently of torch: whether any NVIDIA device is visible to this
        process at all, the driver version, and each GPU's name and memory. Never used to decide that training can run
        (only torch enumerating a device does that); used to tell "no GPU here" from "a GPU whose driver is too old"."""
        if self._driver is not None:
            return self._driver
        info: dict = {"present": False, "version": None, "gpus": [], "source": None, "detail": None}
        smi = self._env.get("NVIDIA_SMI_BIN") or self._which("nvidia-smi")
        if smi:
            try:
                out = subprocess.run([smi, "--query-gpu=name,driver_version,memory.total,memory.free",
                                      "--format=csv,noheader,nounits"], capture_output=True, text=True, timeout=20)
                if out.returncode == 0:
                    for line in out.stdout.strip().splitlines():
                        name, version, total, free = (part.strip() for part in line.split(","))
                        info["gpus"].append({"name": name, "memory_total_mb": _num(total), "memory_free_mb": _num(free)})
                        info["version"] = version
                    info.update(present=bool(info["gpus"]), source="nvidia-smi")
                else:
                    info["detail"] = (out.stderr or out.stdout).strip()[:300] or f"nvidia-smi exited {out.returncode}"
            except (OSError, subprocess.SubprocessError, ValueError) as exc:
                info["detail"] = f"nvidia-smi failed: {exc}"
        if not info["present"]:
            proc = self._env.get("NVIDIA_DRIVER_VERSION_FILE", "/proc/driver/nvidia/version")
            try:
                text = open(proc, encoding="utf-8").read()
                for token in text.split():
                    if token.count(".") >= 1 and token.replace(".", "").isdigit():
                        info.update(present=True, version=token, source=proc)
                        break
            except OSError:
                pass
        if not info["present"] and os.path.exists(self._env.get("NVIDIA_DEVICE_NODE", "/dev/nvidia0")):
            info.update(present=True, source="device node")
        self._driver = info
        return info

    def cuda_memory(self, index: int) -> dict | None:
        """Free and total memory of CUDA device `index` as torch sees it (cudaMemGetInfo), or None."""
        try:
            import torch

            free, total = torch.cuda.mem_get_info(index)
            return {"free_bytes": int(free), "total_bytes": int(total)}
        except Exception:  # noqa: BLE001 - no torch, no device, or the query failed: unknown, not zero
            return None

    def cuda_devices(self) -> list[dict]:
        """Per-device name and compute capability, via torch. Empty if torch or CUDA is unavailable."""
        if importlib.util.find_spec("torch") is None:
            return []
        try:
            import torch

            if not torch.cuda.is_available():
                return []
            devices = []
            for i in range(torch.cuda.device_count()):
                major, minor = torch.cuda.get_device_capability(i)
                props = torch.cuda.get_device_properties(i)
                devices.append({"index": i, "name": props.name, "compute_capability": f"{major}.{minor}",
                                "compute_capability_value": major + minor / 10, "total_memory_bytes": props.total_memory})
            return devices
        except Exception:  # noqa: BLE001 - device introspection failing means "treat as unavailable"
            return []

    def select_cuda_device(self, minimum: float) -> dict | None:
        """The device training should run on: the most capable one at or above `minimum` (ties: the lowest index),
        or None. torch's default device is index 0, which may be the one that does not qualify."""
        qualifying = [d for d in self.cuda_devices() if d["compute_capability_value"] >= minimum]
        if not qualifying:
            return None
        return max(qualifying, key=lambda d: (d["compute_capability_value"], -d["index"]))

    def module_version(self, name: str) -> str | None:
        """The installed distribution version of a Python module, or None (not installed, or no version metadata)."""
        dist = {"cv2": None}.get(name, name)
        candidates = ["opencv-python-headless", "opencv-python"] if dist is None else [dist]
        from importlib import metadata  # noqa: PLC0415

        for candidate in candidates:
            try:
                return metadata.version(candidate)
            except metadata.PackageNotFoundError:
                continue
        return None

    def torch_build(self) -> dict:
        """What the installed torch was built for: its version and CUDA runtime version (None for a CPU-only build)."""
        if importlib.util.find_spec("torch") is None:
            return {"version": None, "cuda": None, "error": "torch is not installed"}
        try:
            import torch

            return {"version": torch.__version__, "cuda": torch.version.cuda, "error": None}
        except Exception as exc:  # noqa: BLE001 - a broken torch install is reported, not raised
            return {"version": None, "cuda": None, "error": f"torch failed to import: {exc}"}

    def gsplat_backend(self, *, allow_jit: bool) -> ToolStatus:
        """Whether gsplat's CUDA kernels can be loaded. gsplat 1.5.3 on PyPI is a pure-Python wheel: without the compiled
        extension (`gsplat.csrc`, built from source with CUDA) it compiles its kernels on first use with nvcc, and with
        no CUDA toolkit it silently leaves its backend unset, so the first rasterisation fails deep inside training.
        Production workers carry the compiled extension (services/reconstruction/Dockerfile.gpu); JIT is opt-in."""
        key = f"gsplat-backend:{allow_jit}"
        if key in self._cache:
            return self._cache[key]
        name = "gsplat-cuda-backend"
        if importlib.util.find_spec("gsplat") is None:
            status = ToolStatus(name, False, detail="gsplat is not installed")
        else:
            try:
                prebuilt = importlib.util.find_spec("gsplat.csrc") is not None
            except (ImportError, ValueError) as exc:
                prebuilt, status = False, ToolStatus(name, False, detail=f"gsplat failed to import: {exc}")
            else:
                status = None
            if status is None and prebuilt:
                try:
                    importlib.import_module("gsplat.csrc")
                    status = ToolStatus(name, True, version="prebuilt")
                except Exception as exc:  # noqa: BLE001 - e.g. an undefined symbol: built against another torch
                    status = ToolStatus(name, False, detail=f"gsplat's compiled CUDA extension does not load ({exc}); it was built "
                                                            "against a different torch or CUDA. Rebuild gsplat against the installed torch")
            elif status is None and allow_jit:
                try:
                    from gsplat.cuda import _backend  # noqa: PLC0415 - compiles the kernels if they are not cached yet

                    ok = _backend._C is not None
                    status = ToolStatus(name, ok, version="jit" if ok else None,
                                        detail=None if ok else "gsplat found no CUDA toolkit (nvcc) to compile its kernels")
                except Exception as exc:  # noqa: BLE001
                    status = ToolStatus(name, False, detail=f"gsplat's JIT compilation failed: {exc}")
            elif status is None:
                status = ToolStatus(name, False, detail=(
                    "gsplat has no compiled CUDA extension (gsplat.csrc). The PyPI wheel is pure Python and compiles its kernels "
                    "with nvcc on first use, inside the training job's time budget. Build gsplat from source with CUDA "
                    "(services/reconstruction/Dockerfile.gpu), or set GSPLAT_ALLOW_JIT_BACKEND=true on a host with the CUDA toolkit"))
        self._cache[key] = status
        return status

    def require_cuda_compute_capability(self, minimum: float, *, stage: str) -> None:
        """Raise a structured DependencyError if no CUDA device meets the minimum compute capability."""
        devices = self.cuda_devices()
        if not devices:
            raise DependencyError(f"{stage} needs a CUDA device but none was detected", details={"missing": ["cuda"]})
        best = max(d["compute_capability_value"] for d in devices)
        if best < minimum:
            raise DependencyError(
                f"{stage} needs a CUDA device with compute capability >= {minimum}, best available is {best}",
                details={"missing": ["cuda-compute-capability"], "devices": devices, "required_minimum": minimum})

    # ---- requirements -----------------------------------------------------------------------

    def huggingface_model(self, model_id: str) -> ToolStatus:
        """Is `model_id` available without a network call (weights already cached locally)? We never
        silently fall back to downloading mid-stage: if it is not cached, the stage fails with a structured
        DependencyError naming the model, rather than blocking on (or failing deep inside) a download."""
        key = f"hf:{model_id}"
        if key in self._cache:
            return self._cache[key]
        if importlib.util.find_spec("huggingface_hub") is None:
            status = ToolStatus(model_id, False, detail="huggingface_hub is not installed, so model cache availability cannot be checked")
        else:
            try:
                from huggingface_hub import scan_cache_dir

                cached = any(repo.repo_id == model_id for repo in scan_cache_dir().repos)
                status = ToolStatus(model_id, cached, detail=None if cached else f"model {model_id!r} is not present in the local HF cache")
            except Exception as exc:  # noqa: BLE001
                status = ToolStatus(model_id, False, detail=f"could not inspect the local HF cache: {exc}")
        self._cache[key] = status
        return status

    def status(self, requirement: str) -> ToolStatus:
        if requirement == "ffmpeg":
            return self.ffmpeg()
        if requirement == "colmap":
            return self.colmap()
        if requirement == "glomap":
            return self.glomap()
        if requirement == "cuda":
            return self.cuda()
        if requirement.startswith("py:"):
            return self.module(requirement[3:])
        if requirement.startswith("model:"):
            return self.huggingface_model(requirement[len("model:"):])
        if requirement.startswith("exe:"):
            name = requirement[4:]
            return self._executable(name, name.upper().replace("-", "_") + "_BIN")
        raise ValueError(f"unknown requirement {requirement!r}")

    def missing(self, requirements: Iterable[str]) -> list[ToolStatus]:
        return [s for s in (self.status(r) for r in requirements) if not s.available]

    def require(self, requirements: Iterable[str], *, stage: str) -> None:
        """Raise a structured DependencyError naming everything that is missing."""
        missing = self.missing(requirements)
        if missing:
            raise DependencyError(
                f"{stage} cannot run on this worker; missing: " + ", ".join(m.name for m in missing),
                details={"missing": [m.name for m in missing], "tools": [m.as_dict() for m in missing]})

    def snapshot(self) -> dict[str, str | None]:
        """Versions of the tools that were looked up (and found) so far; recorded in stage commands."""
        return {s.name: s.version for k, s in self._cache.items() if s.available and not k.startswith("py:")}
