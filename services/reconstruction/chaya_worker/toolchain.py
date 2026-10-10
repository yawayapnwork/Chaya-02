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
                    status = ToolStatus("cuda", ok, version=torch.version.cuda,
                                        detail=None if ok else "torch is installed but reports no CUDA device")
                except Exception as exc:  # noqa: BLE001
                    status = ToolStatus("cuda", False, detail=f"torch failed to initialise: {exc}")
            self._cache["cuda"] = status
        return self._cache["cuda"]

    def gpu_present(self) -> bool:
        """nvidia-smi answers. Used only to decide COLMAP's GPU flags."""
        return self._which("nvidia-smi") is not None

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
