"""Preflight decisions and failure paths of SPLAT_RECONSTRUCTION, deterministic and CPU-only.

The preflight decision tests use a StubToolchain that states what an environment reports (versions, devices, the gsplat
backend). They test the decisions made from those facts, not GPU training. Nothing here claims a CUDA device works:
`test_this_host_*` runs the real preflight against this machine's real packages, and tests/gpu runs on CUDA.
"""

from __future__ import annotations

import logging
from dataclasses import replace

import numpy as np
import pytest

torch = pytest.importorskip("torch", reason="torch is not installed: pip install torch (CPU is enough for these tests)")

from chaya_worker import ksplat, splat_preflight  # noqa: E402
from chaya_worker.contract import StageContext  # noqa: E402
from chaya_worker.runner import CommandRunner  # noqa: E402
from chaya_worker.settings import Settings  # noqa: E402
from chaya_worker.splat_color import rgb_to_sh0  # noqa: E402
from chaya_worker.splat_training import (  # noqa: E402
    CheckpointCorrupt,
    CheckpointMismatch,
    GsplatContractError,
    TrainingConfig,
    init_params,
    l1_dssim_loss,
    load_checkpoint,
    source_identity,
    train,
)
from chaya_worker.stages import splat_reconstruction  # noqa: E402
from chaya_worker.stages.splat_reconstruction import initial_scale_log, publish_outcome, training_failure  # noqa: E402
from chaya_worker.toolchain import Toolchain, ToolStatus  # noqa: E402
from tests.conftest import DERIVED_BUCKET  # noqa: E402
from tests.splat.renderer import cpu_rasterizer, plane_points, rig  # noqa: E402

CPU = torch.device("cpu")
SOURCE = source_identity(run_id="run-failures", inputs=[])
CONFIG = TrainingConfig(iterations=12, densify_start=4, densify_stop=10, densify_every=2, densify_grad_threshold=1e-7,
                        opacity_reset_every=0, checkpoint_every=4)


def _params():
    points = plane_points(3)
    return init_params(points, rgb_to_sh0(np.full(points.shape, 0.5, np.float32)), initial_scale_log(points), CPU)


def _checker(cam):
    ys, xs = np.mgrid[0:cam["height"], 0:cam["width"]]
    board = ((xs // 3 + ys // 3) % 2).astype(np.float32)
    return np.stack([board, 1 - board, 0.5 * board], axis=-1)


def _train(tmp_path, *, config=CONFIG, rasterize=cpu_rasterizer, resume=None, name="c.pt"):
    return train(params=_params(), cams=rig(_checker), rasterize=rasterize, config=config, loss_fn=l1_dssim_loss,
                 checkpoint_path=tmp_path / name, source=SOURCE, resume=resume)


# ---- preflight: the decisions -----------------------------------------------------------------------------------------


class StubToolchain(Toolchain):
    """States what an environment reports. Defaults: a complete GPU worker (two devices, the second qualifying)."""

    def __init__(self, **over):
        super().__init__(env={}, which=lambda _n: None)
        self.f = {"modules": dict.fromkeys(splat_preflight.REQUIRED_MODULES, True),
                  "versions": {"torch": "2.7.1+cu126", "gsplat": "1.5.3", "numpy": "2.2.6", "scipy": "1.15.3", "cv2": "4.11.0.86"},
                  "torch_build": {"version": "2.7.1+cu126", "cuda": "12.6", "error": None},
                  "cuda": ToolStatus("cuda", True, version="12.6"),
                  "devices": [{"index": 0, "name": "old", "compute_capability": "6.1", "compute_capability_value": 6.1},
                              {"index": 1, "name": "new", "compute_capability": "8.6", "compute_capability_value": 8.6}],
                  "backend": ToolStatus("gsplat-cuda-backend", True, version="prebuilt"),
                  "colmap": ToolStatus("colmap", True, path="/usr/bin/colmap", version="COLMAP 3.9.1")}
        self.f.update(over)

    def module(self, name):
        return ToolStatus(name, self.f["modules"][name])

    def module_version(self, name):
        return self.f["versions"].get(name)

    def torch_build(self):
        return self.f["torch_build"]

    def cuda(self):
        return self.f["cuda"]

    def cuda_devices(self):
        return self.f["devices"]

    def gsplat_backend(self, *, allow_jit):
        return self.f["backend"]

    def colmap(self):
        return self.f["colmap"]


def _report(tc, **kw):
    return splat_preflight.environment_report(tc, min_compute_capability=7.0, python_version=(3, 12), **kw)


def _codes(report):
    return sorted(p["code"] for p in report["problems"])


def test_a_complete_environment_passes_and_trains_on_the_qualifying_device_not_device_0():
    r = _report(StubToolchain())
    assert r["ok"] and r["problems"] == []
    assert r["selected_device"]["index"] == 1, "device 0 (6.1) is below the minimum; torch's default device must not be used"


@pytest.mark.parametrize(("over", "code", "missing"), [
    ({"modules": {**dict.fromkeys(splat_preflight.REQUIRED_MODULES, True), "gsplat": False}}, "PACKAGE_MISSING", "gsplat"),
    ({"torch_build": {"version": "2.7.1+cpu", "cuda": None, "error": None}}, "TORCH_WITHOUT_CUDA", "cuda"),
    ({"cuda": ToolStatus("cuda", False, detail="torch is installed but reports no CUDA device")}, "CUDA_UNAVAILABLE", "cuda"),
    ({"devices": [{"index": 0, "name": "old", "compute_capability": "6.1", "compute_capability_value": 6.1}]},
     "GPU_CAPABILITY_UNSUPPORTED", "cuda-compute-capability"),
    ({"backend": ToolStatus("gsplat-cuda-backend", False, detail="no compiled extension")}, "GSPLAT_CUDA_BACKEND_MISSING",
     "gsplat-cuda-backend"),
    ({"colmap": ToolStatus("colmap", False)}, "TOOL_MISSING", "colmap"),
])
def test_each_missing_requirement_is_named_with_a_fix(over, code, missing):
    r = _report(StubToolchain(**over))
    assert not r["ok"] and code in _codes(r)
    assert missing in splat_preflight.missing_names(r)
    assert all(p["fix"] for p in r["problems"]), "every problem says how to fix it"


def test_unsupported_versions_and_drift_from_the_image_lock_are_refused():
    tc = StubToolchain(versions={"torch": "2.5.1+cu124", "gsplat": "1.4.0", "numpy": "2.2.6", "scipy": "1.15.3", "cv2": "4.11.0.86"},
                       torch_build={"version": "2.5.1+cu124", "cuda": "12.4", "error": None})
    assert _codes(_report(tc)).count("PACKAGE_VERSION_UNSUPPORTED") == 2  # torch < 2.6 and gsplat != 1.5.3
    lock = {"torch": "2.7.1+cu126", "gsplat": "1.5.3", "numpy": "2.2.6", "scipy": "1.15.3", "cv2": "4.11.0.86", "torch_cuda": "12.6",
            "python": "3.12"}
    assert _report(StubToolchain(), lock=lock)["ok"]
    drift = StubToolchain(versions={**StubToolchain().f["versions"], "numpy": "2.3.0"})
    r = _report(drift, lock=lock)
    assert _codes(r) == ["PACKAGE_VERSION_DRIFT"] and "numpy is 2.3.0" in r["problems"][0]["message"]
    assert _codes(splat_preflight.environment_report(StubToolchain(), min_compute_capability=7.0, python_version=(3, 10))) == [
        "PACKAGE_VERSION_UNSUPPORTED"]


def test_settings_that_cannot_produce_a_valid_artifact_are_refused_before_any_work():
    ok = splat_preflight.config_problems(TrainingConfig(), max_ksplat_bytes=ksplat.MAX_BYTES, encoded_size=ksplat.encoded_size)
    assert ok == []
    bad = splat_preflight.config_problems(TrainingConfig(iterations=0, densify_start=10, densify_stop=5, max_gaussians=20_000_000),
                                          max_ksplat_bytes=ksplat.MAX_BYTES, encoded_size=ksplat.encoded_size)
    messages = " ".join(p.message for p in bad)
    assert {p.code for p in bad} == {"CONFIG_INVALID"} and len(bad) == 3
    assert "below 1" in messages and "densify_stop" in messages and "viewer limit" in messages


def test_too_few_frames_or_points_are_reported():
    assert splat_preflight.input_problems(posed_frames=10, sfm_points=100, min_frames=3) == []
    assert [p.code for p in splat_preflight.input_problems(posed_frames=2, sfm_points=3, min_frames=3)] == [
        "INSUFFICIENT_POINTS", "INSUFFICIENT_FRAMES"]
    assert splat_preflight.input_problems(posed_frames=None, sfm_points=100, min_frames=3) == [], "frames not counted yet"


# ---- preflight on this host, for real --------------------------------------------------------------------------------


def test_this_host_preflight_agrees_with_what_is_installed():
    """The real probes: on a host without gsplat or CUDA the report must say so; it must never report a device torch
    does not enumerate."""
    tc = Toolchain()
    r = splat_preflight.environment_report(tc, min_compute_capability=7.0)
    real_devices = tc.cuda_devices()
    assert r["devices"] == real_devices or not r["devices"]
    if not tc.module("gsplat").available:
        assert not r["ok"] and "gsplat" in splat_preflight.missing_names(r)
        assert not tc.gsplat_backend(allow_jit=False).available
    if torch.version.cuda is None:
        assert "TORCH_WITHOUT_CUDA" in _codes(r) and r["selected_device"] is None
    if r["ok"]:
        assert r["selected_device"] in real_devices


def test_the_stage_reports_a_missing_gpu_as_a_structured_failure_and_the_worker_keeps_working(harness):
    """Missing CUDA, for real: on this host the real SPLAT_RECONSTRUCTION stage must fail DEPENDENCY_UNAVAILABLE with the
    preflight report, submit exactly one report (so the lease ends), publish nothing, and leave the worker able to run its
    next job. Skipped only on a host that can actually train (tests/gpu covers that one)."""
    pre = splat_preflight.environment_report(Toolchain(), min_compute_capability=7.0)
    if pre["ok"]:
        pytest.skip("this host can train with gsplat on CUDA; the failure path is not reachable here")
    report = harness.run(harness.order("SPLAT_RECONSTRUCTION", []))
    assert report["status"] == "FAILED" and report["errorCode"] == "DEPENDENCY_UNAVAILABLE"
    assert report["artifacts"] == [] and report["errorDetails"]["missing"]
    assert report["errorDetails"]["preflight"]["problems"] == pre["problems"]
    assert len(harness.api.reports) == 1, "the failure was reported, so the control plane releases the job"
    assert not [k for k in harness.storage.keys(DERIVED_BUCKET) if not k.split("/")[-2] == "logs"], "only logs were stored"
    nxt = harness.run(harness.order("ARTIFACT_GENERATION", []))
    assert nxt["status"] == "FAILED" and nxt["errorCode"] == "INPUT_INVALID", "the same worker processes its next job"
    assert len(harness.api.reports) == 2


# ---- corrupt and incompatible checkpoints ---------------------------------------------------------------------------------


@pytest.fixture()
def checkpoint(tmp_path):
    return _train(tmp_path).checkpoint_path


def _load(path, config=CONFIG):
    return load_checkpoint(path, config=config, source=SOURCE, device=CPU)


def test_a_truncated_or_garbage_checkpoint_is_corrupt_not_a_crash(tmp_path, checkpoint):
    data = checkpoint.read_bytes()
    (tmp_path / "truncated.pt").write_bytes(data[: len(data) // 2])
    (tmp_path / "garbage.pt").write_bytes(np.random.default_rng(0).integers(0, 256, 4096, dtype=np.uint8).tobytes())
    (tmp_path / "empty.pt").write_bytes(b"")
    for name in ("truncated.pt", "garbage.pt", "empty.pt"):
        with pytest.raises(CheckpointCorrupt, match="not a readable training checkpoint"):
            _load(tmp_path / name)


@pytest.mark.parametrize("damage", ["drop_params", "float64", "missing_lrs", "not_a_dict", "bad_iteration", "bad_density"])
def test_a_checkpoint_with_missing_or_mistyped_fields_is_corrupt(tmp_path, checkpoint, damage):
    payload = torch.load(checkpoint, weights_only=True)
    if damage == "drop_params":
        del payload["params"]
    elif damage == "float64":
        payload["params"]["means"] = payload["params"]["means"].double()
    elif damage == "missing_lrs":
        payload["lrs"].pop("sh0")
    elif damage == "not_a_dict":
        payload = [1, 2, 3]
    elif damage == "bad_iteration":
        payload["iteration"] = "twelve"
    elif damage == "bad_density":
        payload["density"] = {"grad2d": payload["density"]["grad2d"]}
    torch.save(payload, tmp_path / "damaged.pt")
    with pytest.raises(CheckpointCorrupt):
        _load(tmp_path / "damaged.pt")


def test_adam_moments_that_do_not_match_their_parameter_are_refused_before_training(tmp_path, checkpoint):
    payload = torch.load(checkpoint, weights_only=True)
    payload["optimizer"]["means"]["exp_avg"] = payload["optimizer"]["means"]["exp_avg"][:-1]
    torch.save(payload, tmp_path / "moments.pt")
    with pytest.raises(CheckpointMismatch, match="optimiser state of means"):
        _load(tmp_path / "moments.pt")


# ---- interrupted training --------------------------------------------------------------------------------------------------


def test_training_interrupted_mid_run_resumes_from_its_last_periodic_checkpoint_to_the_uninterrupted_result(tmp_path):
    uninterrupted = _train(tmp_path, name="full.pt")
    calls = {"n": 0}

    def crashing(*args):
        calls["n"] += 1
        if calls["n"] == 7:  # mid-iteration 7: after the iteration-4 checkpoint, before the iteration-8 one
            raise RuntimeError("worker process killed")
        return cpu_rasterizer(*args)

    with pytest.raises(RuntimeError, match="killed"):
        _train(tmp_path, rasterize=crashing, name="interrupted.pt")
    resume = _load(tmp_path / "interrupted.pt")
    assert resume[0] == 4, "the last completed periodic checkpoint is iteration 4"
    resumed = _train(tmp_path, resume=resume, name="interrupted.pt")
    assert resumed.resumed_from_iteration == 4 and resumed.completed_iterations == 12 and resumed.status == "COMPLETED"
    for k in resumed.params:
        assert torch.equal(resumed.params[k], uninterrupted.params[k]), f"{k} differs from the uninterrupted run"


# ---- failures during training become structured stage failures ---------------------------------------------------------------


@pytest.mark.parametrize(("exc", "code"), [
    (torch.cuda.OutOfMemoryError("CUDA out of memory. Tried to allocate 2.00 GiB"), "SPLAT_GPU_OUT_OF_MEMORY"),
    (RuntimeError("CUDA error: an illegal memory access was encountered"), "GPU_RUNTIME_ERROR"),
    (GsplatContractError("gsplat 1.6.0 returned renders (1, 2, 3)"), "GSPLAT_INCOMPATIBLE"),
    (OSError(28, "No space left on device"), "SPLAT_CHECKPOINT_WRITE_FAILED"),
    (InterruptedError("cancelled"), "CANCELLED"),
])
def test_gpu_and_io_failures_map_to_actionable_codes(exc, code):
    failure = training_failure(exc)
    assert failure is not None and failure.code == code and failure.message


def test_a_bug_is_not_disguised_as_a_gpu_failure():
    assert training_failure(ValueError("a bug")) is None
    assert training_failure(RuntimeError("shape mismatch in a test helper")) is None


def test_an_out_of_memory_mid_training_propagates_after_a_resumable_checkpoint(tmp_path):
    calls = {"n": 0}

    def oom(*args):
        calls["n"] += 1
        if calls["n"] == 6:
            raise torch.cuda.OutOfMemoryError("CUDA out of memory")
        return cpu_rasterizer(*args)

    with pytest.raises(torch.cuda.OutOfMemoryError) as caught:
        _train(tmp_path, rasterize=oom)
    assert training_failure(caught.value).code == "SPLAT_GPU_OUT_OF_MEMORY"
    assert _load(tmp_path / "c.pt")[0] == 4, "the iteration-4 checkpoint survives for a retry"


# ---- export --------------------------------------------------------------------------------------------------------------------


def _ctx(tmp_path) -> StageContext:
    work = tmp_path / "work"
    work.mkdir(exist_ok=True)
    return StageContext({"stage": "SPLAT_RECONSTRUCTION", "inputs": [], "runId": "run-1"}, [], work, logging.getLogger("t"),
                        CommandRunner(work / "out.log", work / "err.log"), Toolchain(env={}, which=lambda _n: None), Settings(), None)


def test_an_export_that_cannot_be_written_publishes_no_splat_and_no_checkpoint(tmp_path, monkeypatch):
    outcome = _train(tmp_path)

    def disk_full(*_a, **_k):
        raise OSError(28, "No space left on device")

    monkeypatch.setattr(splat_reconstruction, "write_ply", disk_full)
    result = publish_outcome(_ctx(tmp_path), outcome, source=SOURCE, cameras_used=4, versions={})
    assert result.status == "FAILED" and result.error_code == "SPLAT_EXPORT_FAILED" and "free disk space" in result.error_message
    assert [a.kind for a in result.artifacts] == ["SPLAT_TRAINING_REPORT"]


@pytest.mark.parametrize("partial", [False, True])
def test_a_cloud_the_viewer_contract_would_refuse_is_never_published(tmp_path, monkeypatch, partial):
    config = replace(CONFIG, iterations=1000) if partial else CONFIG
    clock = iter(range(1_000, 100_000))
    outcome = train(params=_params(), cams=rig(_checker), rasterize=cpu_rasterizer, config=config, loss_fn=l1_dssim_loss,
                    checkpoint_path=tmp_path / "c.pt", source=SOURCE, deadline=1_010 if partial else None, stop_margin_seconds=2,
                    clock=lambda: float(next(clock)))
    assert outcome.status == ("PARTIAL" if partial else "COMPLETED")
    monkeypatch.setattr(ksplat, "MAX_BYTES", ksplat.encoded_size(outcome.gaussian_count - 1))
    result = publish_outcome(_ctx(tmp_path), outcome, source=SOURCE, cameras_used=4, versions={})
    assert result.status == "FAILED" and result.error_code == "SPLAT_INVALID" and "viewer contract" in result.error_message
    assert not {"SPLAT", "SPLAT_PARTIAL", "SPLAT_CHECKPOINT"} & {a.kind for a in result.artifacts}
