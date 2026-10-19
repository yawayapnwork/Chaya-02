"""What one stage attempt used, as this worker process can observe it: the stage report's `resources`
(dev.chaya.api.cost.ResourceMeasurements; docs/operations-dashboard.md, "Resources and cost").

Measured, never assumed:

  * **CPU time** (`os.times`): user + system time of this worker process over the stage (its threads included: the
    heartbeat thread's share is negligible), plus that of the external tools it ran and waited for (COLMAP, FFmpeg) where
    the platform reports child times. POSIX does; Windows does not, so there `childrenMeasured` is false and only the
    worker process is counted.
  * **GPU**: only through torch, and only if torch is already loaded in this process (this module never imports it).
    When the stage made CUDA allocations, it reports each device's name and total memory, the torch allocator's peak
    (`max_memory_allocated`, reset at the stage's start: tensors only, not the CUDA context or other processes), and
    the stage's wall time as the time the GPU was held. That is not a utilisation figure. Otherwise `gpu` is null and
    `gpuUnmeasuredReason` says why. GPU work by external tools (COLMAP's SIFT on CUDA) is not visible to torch and is
    never counted.
  * **resourceClass**: WORKER_RESOURCE_CLASS, the rate-card class the operator says this machine is priced at.
"""

from __future__ import annotations

import os
import platform
import sys
from typing import Any

SCHEMA = 1
GPU_BASIS = "STAGE_WALL_TIME_WITH_CUDA_ALLOCATIONS"
EXTERNAL = "GPU work by external tools (COLMAP) is not observed"


class ResourceMeter:
    """start() before a stage runs, measure(wall_seconds) after it; never raises."""

    def __init__(self, resource_class: str | None) -> None:
        self.resource_class = resource_class
        self._times0: os.times_result | None = None
        self._cuda_before = False
        self._allocated0: dict[int, int] = {}
        self._start_error: str | None = None

    def start(self) -> None:
        self._times0 = os.times()
        torch = sys.modules.get("torch")
        try:
            if torch is not None and _cuda_initialised(torch):
                self._cuda_before = True
                for i in range(torch.cuda.device_count()):
                    self._allocated0[i] = int(torch.cuda.memory_allocated(i))
                    torch.cuda.reset_peak_memory_stats(i)
        except Exception as exc:  # noqa: BLE001 - a broken CUDA runtime means "not measured", never a failed stage
            self._start_error = f"torch could not read CUDA memory statistics: {exc}"

    def measure(self, wall_seconds: float) -> dict[str, Any]:
        out: dict[str, Any] = {"schema": SCHEMA, "resourceClass": self.resource_class,
                               "host": {"cpuCount": os.cpu_count(), "platform": f"{platform.system()} {platform.machine()}"},
                               "wallSeconds": wall_seconds}
        out["cpu"] = self._cpu()
        gpu, why = self._gpu(wall_seconds)
        out["gpu"] = gpu
        out["gpuUnmeasuredReason"] = why
        return out

    def _cpu(self) -> dict[str, Any] | None:
        if self._times0 is None:
            return None
        t0, t1 = self._times0, os.times()
        children_measured = platform.system() != "Windows"  # os.times reports 0 for children there
        return {"processSeconds": max(0.0, (t1.user - t0.user) + (t1.system - t0.system)),
                "childrenSeconds": max(0.0, (t1.children_user - t0.children_user) + (t1.children_system - t0.children_system))
                if children_measured else None,
                "childrenMeasured": children_measured,
                "method": "os.times"}

    def _gpu(self, wall_seconds: float) -> tuple[dict[str, Any] | None, str | None]:
        if self._start_error:
            return None, self._start_error
        torch = sys.modules.get("torch")
        if torch is None:
            return None, f"torch is not loaded in this worker process, so it ran no CUDA work; {EXTERNAL}"
        try:
            if not _cuda_initialised(torch):
                return None, f"CUDA was not initialised in this worker process during the stage; {EXTERNAL}"
            devices = []
            for i in range(torch.cuda.device_count()):
                peak = int(torch.cuda.max_memory_allocated(i))
                used = peak > self._allocated0.get(i, 0) if self._cuda_before else peak > 0
                if used:
                    props = torch.cuda.get_device_properties(i)
                    devices.append({"index": i, "name": props.name, "totalMemoryBytes": int(props.total_memory),
                                    "peakAllocatedBytes": peak})
        except Exception as exc:  # noqa: BLE001
            return None, f"torch could not read CUDA memory statistics: {exc}"
        if not devices:
            return None, f"this worker process made no CUDA allocation during the stage; {EXTERNAL}"
        return {"seconds": wall_seconds, "basis": GPU_BASIS, "devices": devices,
                "peakMemory": "torch allocator peak (max_memory_allocated): tensors only, not the CUDA context"}, None


def _cuda_initialised(torch) -> bool:
    cuda = getattr(torch, "cuda", None)
    return bool(cuda is not None and cuda.is_available() and cuda.is_initialized())
