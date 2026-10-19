"""chaya_worker.resources: what a stage report says about the attempt's resources. GPU figures come only from torch's own
CUDA statistics; a fake torch module stands in for a GPU here (this host has none), so these tests check what is reported
and when, not that a real device is measured (tests/gpu does that on a CUDA host)."""

from __future__ import annotations

import sys
import types

import pytest

from chaya_worker.resources import GPU_BASIS, ResourceMeter
from chaya_worker.settings import Settings


class FakeCuda:
    def __init__(self, *, initialised=True, allocated=(0,), peaks=(0,), fail=False):
        self.initialised, self.allocated, self.peaks, self.fail = initialised, list(allocated), list(peaks), fail
        self.resets = []

    def is_available(self):
        return True

    def is_initialized(self):
        if self.fail:
            raise RuntimeError("CUDA driver error")
        return self.initialised

    def device_count(self):
        return len(self.peaks)

    def memory_allocated(self, i):
        return self.allocated[i]

    def reset_peak_memory_stats(self, i):
        self.resets.append(i)

    def max_memory_allocated(self, i):
        return self.peaks[i]

    def get_device_properties(self, i):
        return types.SimpleNamespace(name=f"NVIDIA Test GPU {i}", total_memory=24 * 2**30)


@pytest.fixture()
def fake_torch(monkeypatch):
    def install(cuda: FakeCuda):
        monkeypatch.setitem(sys.modules, "torch", types.SimpleNamespace(cuda=cuda))
        return cuda
    return install


def test_without_torch_no_gpu_use_is_reported_and_the_reason_says_why(monkeypatch):
    monkeypatch.delitem(sys.modules, "torch", raising=False)
    meter = ResourceMeter("cpu-standard")
    meter.start()
    sum(i * i for i in range(200_000))  # some CPU work
    r = meter.measure(1.5)
    assert r["gpu"] is None and "torch is not loaded" in r["gpuUnmeasuredReason"]
    assert "COLMAP" in r["gpuUnmeasuredReason"], "external GPU use is named as unobserved, not assumed absent"
    assert r["resourceClass"] == "cpu-standard" and r["wallSeconds"] == 1.5
    assert r["cpu"]["processSeconds"] >= 0 and r["cpu"]["method"] == "os.times"
    assert r["host"]["cpuCount"] >= 1


def test_child_cpu_time_is_reported_only_where_the_platform_measures_it(monkeypatch):
    monkeypatch.delitem(sys.modules, "torch", raising=False)
    meter = ResourceMeter(None)
    meter.start()
    r = meter.measure(0.1)
    if sys.platform.startswith("win"):
        assert r["cpu"]["childrenMeasured"] is False and r["cpu"]["childrenSeconds"] is None
    else:
        assert r["cpu"]["childrenMeasured"] is True and r["cpu"]["childrenSeconds"] >= 0
    assert r["resourceClass"] is None


def test_a_stage_that_allocated_on_the_gpu_reports_its_device_peak_memory_and_time(fake_torch):
    cuda = fake_torch(FakeCuda(initialised=True, allocated=(1000, 0), peaks=(5_000_000, 0)))
    meter = ResourceMeter("gpu-a10g")
    meter.start()
    r = meter.measure(42.0)
    assert cuda.resets == [0, 1], "peak statistics are reset at the stage's start"
    assert r["gpuUnmeasuredReason"] is None
    gpu = r["gpu"]
    assert gpu["seconds"] == 42.0 and gpu["basis"] == GPU_BASIS
    assert [d["index"] for d in gpu["devices"]] == [0], "only the device the stage allocated on"
    assert gpu["devices"][0] == {"index": 0, "name": "NVIDIA Test GPU 0", "totalMemoryBytes": 24 * 2**30, "peakAllocatedBytes": 5_000_000}


def test_cuda_left_initialised_by_an_earlier_stage_does_not_count_as_gpu_use(fake_torch):
    """The worker process keeps its CUDA context between stages: a later CPU-only stage made no new allocation."""
    fake_torch(FakeCuda(initialised=True, allocated=(7_000,), peaks=(7_000,)))
    meter = ResourceMeter(None)
    meter.start()
    r = meter.measure(3.0)
    assert r["gpu"] is None and "no CUDA allocation" in r["gpuUnmeasuredReason"]


def test_cuda_not_initialised_is_no_gpu_use(fake_torch):
    fake_torch(FakeCuda(initialised=False))
    meter = ResourceMeter(None)
    meter.start()
    r = meter.measure(3.0)
    assert r["gpu"] is None and "not initialised" in r["gpuUnmeasuredReason"]


def test_a_broken_cuda_runtime_is_unmeasured_never_a_failed_stage(fake_torch):
    fake_torch(FakeCuda(fail=True))
    meter = ResourceMeter(None)
    meter.start()
    r = meter.measure(3.0)
    assert r["gpu"] is None and "CUDA driver error" in r["gpuUnmeasuredReason"]


def test_the_resource_class_comes_from_worker_resource_class():
    assert Settings.from_env({"WORKER_RESOURCE_CLASS": " gpu-a10g "}).resource_class == "gpu-a10g"
    assert Settings.from_env({"WORKER_RESOURCE_CLASS": ""}).resource_class is None
    assert Settings.from_env({}).resource_class is None
