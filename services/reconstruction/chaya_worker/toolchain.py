"""Detection of the external tools and libraries the pipeline needs.

Detection is explicit and structured. A missing dependency becomes a DependencyError that names exactly
what is missing; it is never papered over with a substitute or an empty output.
"""

from __future__ import annotations

import importlib.util
import os
import shutil
import subprocess
from dataclasses import asdict, dataclass
from typing import Callable, Iterable, Mapping

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

    # ---- requirements -----------------------------------------------------------------------

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
