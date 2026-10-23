"""Checkpoint persistence kept apart from the reconstruction (gaussian-splatting-audit D-6), on CPU.

A checkpoint is a resume point, not the result. A completed, valid cloud is exported and validated whether or not its
final checkpoint could be written. A PARTIAL training publishes the last checkpoint written completely, which is what a
retry resumes. Neither a checkpoint failure nor anything else lets an unwritten, unverified or unstored SPLAT through.

What is real: train(), save_checkpoint (the real torch serializer writes into a file that runs out of space),
load_checkpoint, publish_outcome and the orchestrator's upload. What is not production: the CPU test renderer in place
of gsplat, a synthetic scene, and storage failures injected at the file or storage boundary.
"""

from __future__ import annotations

import errno
import io
import logging
from dataclasses import replace
from pathlib import Path

import numpy as np
import pytest

torch = pytest.importorskip("torch", reason="torch is not installed: pip install torch (CPU is enough for this test)")

from chaya_worker import splat_training as st  # noqa: E402
from chaya_worker.contract import StageContext  # noqa: E402
from chaya_worker.ply import read_ply  # noqa: E402
from chaya_worker.runner import CommandRunner  # noqa: E402
from chaya_worker.settings import Settings  # noqa: E402
from chaya_worker.splat_color import rgb_to_sh0  # noqa: E402
from chaya_worker.splat_training import (  # noqa: E402
    TrainingConfig,
    TrainingDiverged,
    init_params,
    l1_dssim_loss,
    load_checkpoint,
    source_identity,
    train,
)
from chaya_worker.stages import splat_reconstruction  # noqa: E402
from chaya_worker.stages.splat_reconstruction import initial_scale_log, publish_outcome, training_failure  # noqa: E402
from chaya_worker.storage import LocalStorage, StorageError  # noqa: E402
from chaya_worker.toolchain import Toolchain  # noqa: E402
from tests.conftest import Harness  # noqa: E402
from tests.splat.renderer import cpu_quality, cpu_rasterizer, plane_points, rig  # noqa: E402

CPU = torch.device("cpu")
SOURCE = source_identity(run_id="run-d6", inputs=[])
# 8 iterations with a checkpoint at 4: the final write (iteration 8) is distinct from the periodic one. This training
# beats its SfM seed, so it passes the quality gate and only the checkpoint decides anything.
CONFIG = TrainingConfig(iterations=8, densify_start=10**9, densify_stop=10**9, opacity_reset_every=0, checkpoint_every=4)


def _checker(cam):
    ys, xs = np.mgrid[0:cam["height"], 0:cam["width"]]
    board = ((xs // 3 + ys // 3) % 2).astype(np.float32)
    return np.stack([board, 1 - board, 0.5 * board], axis=-1)


def _seed():
    points = plane_points(3)
    return init_params(points, rgb_to_sh0(np.full(points.shape, 0.5, np.float32)), initial_scale_log(points), CPU)


class FakeClock:
    def __init__(self) -> None:
        self.t = 1_000.0

    def __call__(self) -> float:
        self.t += 1.0
        return self.t


def _train(path: Path, *, config=CONFIG, budget: float | None = None, loss_fn=l1_dssim_loss):
    clock = FakeClock()
    return train(params=_seed(), cams=rig(_checker), rasterize=cpu_rasterizer, config=config, loss_fn=loss_fn,
                 checkpoint_path=path, source=SOURCE, deadline=None if budget is None else clock.t + budget,
                 stop_margin_seconds=2, clock=clock)


def _ctx(tmp_path) -> StageContext:
    work = tmp_path / "work"
    work.mkdir(exist_ok=True)
    return StageContext({"stage": "SPLAT_RECONSTRUCTION", "inputs": [], "runId": "run-d6"}, [], work, logging.getLogger("t"),
                        CommandRunner(work / "out.log", work / "err.log"), Toolchain(env={}, which=lambda _n: None), Settings(), None)


def _publish(tmp_path, outcome):
    return publish_outcome(_ctx(tmp_path), outcome, source=SOURCE, cameras_used=4, versions={},
                           quality=cpu_quality(outcome, _seed(), rig(_checker)))


def _report(result) -> dict:
    import json

    return json.loads(next(a.path for a in result.artifacts if a.kind == "SPLAT_TRAINING_REPORT").read_text(encoding="utf-8"))


# ---- storage failures, injected where the checkpoint is written --------------------------------------------------------


class _DiskFills(io.FileIO):
    """A real file that runs out of space after `room` bytes: the torch serializer gets ENOSPC from write() mid-stream,
    after part of the archive is on disk, which is what a full disk does."""

    room = 4096

    def write(self, b):
        left = self.room - self.tell()
        if len(b) > left:
            super().write(bytes(memoryview(b)[: max(left, 0)]))
            raise OSError(errno.ENOSPC, "No space left on device", self.name)
        return super().write(b)


def _failing_writes(monkeypatch, fail_on: set[int], kind: str = "disk_full") -> list[int]:
    """Makes save_checkpoint's write number n (1-based) fail for each n in `fail_on`; returns the call log."""
    calls: list[int] = []
    real_open, real_replace = open, Path.replace

    def fake_open(path, mode="r", *a, **k):
        if str(path).endswith(".tmp"):
            calls.append(len(calls) + 1)
            if calls[-1] in fail_on and kind == "disk_full":
                return _DiskFills(path, mode.replace("b", ""))
        return real_open(path, mode, *a, **k)

    def fake_replace(self, target):
        if str(self).endswith(".tmp") and calls and calls[-1] in fail_on and kind == "permission":
            raise PermissionError(errno.EACCES, "Permission denied", str(target))
        return real_replace(self, target)

    monkeypatch.setattr(st, "open", fake_open, raising=False)
    monkeypatch.setattr(Path, "replace", fake_replace)
    return calls


def _count_writes(tmp_path, **kw) -> int:
    calls = []
    real = st.save_checkpoint

    def spy(*a, **k):
        calls.append(k["iteration"])
        return real(*a, **k)

    with pytest.MonkeyPatch.context() as m:
        m.setattr(st, "save_checkpoint", spy)
        _train(tmp_path / "count.pt", **kw)
    return len(calls)


# ---- a completed training does not depend on its checkpoint ------------------------------------------------------------


@pytest.mark.parametrize(("kind", "code"), [("disk_full", errno.ENOSPC), ("permission", errno.EACCES)])
def test_a_completed_valid_cloud_is_published_when_its_final_checkpoint_cannot_be_written(tmp_path, monkeypatch, kind, code):
    path = tmp_path / "c.pt"
    _failing_writes(monkeypatch, {2}, kind)  # write 1 is the periodic one at iteration 4, write 2 the final one
    outcome = _train(path)
    monkeypatch.undo()

    assert outcome.status == "COMPLETED" and outcome.completed_iterations == 8
    assert [(f["iteration"], f["errno"]) for f in outcome.checkpoint_failures] == [(8, code)]
    assert outcome.checkpoint_iteration == 4, "the last checkpoint written completely, never the failed one"
    assert not path.with_suffix(".pt.tmp").exists(), "the incomplete temporary checkpoint is removed"
    assert load_checkpoint(path, config=CONFIG, source=SOURCE, device=CPU)[0] == 4, "the earlier checkpoint is intact"

    result = _publish(tmp_path, outcome)
    assert result.status == "SUCCEEDED" and result.error_code is None
    kinds = {a.kind: a for a in result.artifacts}
    assert "SPLAT" in kinds and "SPLAT_CHECKPOINT" not in kinds, "a stale checkpoint never goes out beside a completed SPLAT"
    cloud = read_ply(kinds["SPLAT"].path)
    assert np.array_equal(cloud.positions, outcome.params["means"].detach().numpy()), "the SPLAT is the fully trained cloud"
    report = _report(result)
    assert report["validation"]["valid"] and report["provenance"]["checkpoint"] == {"artifact": None, "sha256": None, "iteration": None}
    assert report["checkpoint"]["published"] is False and report["checkpoint"]["final"] is False
    assert report["checkpoint"]["failure_code"] == "SPLAT_CHECKPOINT_WRITE_FAILED"
    assert report["checkpoint"]["write_failures"][0]["errno"] == code and "auxiliary" in report["checkpoint"]["role"]


def test_a_completed_training_publishes_its_final_checkpoint_when_it_was_written(tmp_path):
    result = _publish(tmp_path, _train(tmp_path / "c.pt"))
    assert result.status == "SUCCEEDED" and {"SPLAT", "SPLAT_CHECKPOINT"} <= {a.kind for a in result.artifacts}
    report = _report(result)
    assert report["checkpoint"] == {**report["checkpoint"], "published": True, "final": True, "iteration": 8,
                                    "write_failures": [], "failure_code": None}
    assert report["provenance"]["checkpoint"]["iteration"] == 8


def test_a_failed_periodic_checkpoint_does_not_stop_training(tmp_path, monkeypatch):
    _failing_writes(monkeypatch, {1})
    outcome = _train(tmp_path / "c.pt")
    monkeypatch.undo()
    assert outcome.status == "COMPLETED" and outcome.completed_iterations == 8
    assert [f["iteration"] for f in outcome.checkpoint_failures] == [4] and outcome.checkpoint_iteration == 8
    assert load_checkpoint(tmp_path / "c.pt", config=CONFIG, source=SOURCE, device=CPU)[0] == 8


# ---- a partial training: the checkpoint is the resume point ------------------------------------------------------------


def test_a_partial_training_publishes_the_last_complete_checkpoint_when_the_final_write_fails(tmp_path, monkeypatch):
    config, budget = replace(CONFIG, iterations=1000), 40
    n = _count_writes(tmp_path, config=config, budget=budget)
    _failing_writes(monkeypatch, {n})
    outcome = _train(tmp_path / "c.pt", config=config, budget=budget)
    monkeypatch.undo()
    assert outcome.status == "PARTIAL" and outcome.checkpoint_failures[0]["iteration"] == outcome.completed_iterations
    k = outcome.checkpoint_iteration
    assert k is not None and 0 < k < outcome.completed_iterations

    result = _publish(tmp_path, outcome)
    assert result.status == "FAILED" and result.error_code == "TIME_LIMIT_EXCEEDED"
    kinds = {a.kind for a in result.artifacts}
    assert "SPLAT_PARTIAL" in kinds and "SPLAT" not in kinds and "SPLAT_CHECKPOINT" in kinds
    assert f"resumes from the last one written, at iteration {k}" in result.error_message
    assert result.error_details["checkpoint_iteration"] == k and result.error_details["checkpoint_write_failures"] == 1
    published = next(a.path for a in result.artifacts if a.kind == "SPLAT_CHECKPOINT")
    resumed = load_checkpoint(published, config=config, source=SOURCE, device=CPU)
    assert resumed[0] == k, "a retry resumes exactly the checkpoint the report names"
    assert _report(result)["checkpoint"]["iteration"] == k and "resume point" in _report(result)["checkpoint"]["role"]


def test_a_partial_training_with_no_checkpoint_written_says_it_cannot_be_resumed(tmp_path, monkeypatch):
    _failing_writes(monkeypatch, set(range(1, 1000)), "permission")
    outcome = _train(tmp_path / "c.pt", config=replace(CONFIG, iterations=1000), budget=40)
    monkeypatch.undo()
    assert outcome.status == "PARTIAL" and outcome.checkpoint_path is None and outcome.checkpoint_iteration is None
    assert not (tmp_path / "c.pt").exists() and not (tmp_path / "c.pt.tmp").exists()

    result = _publish(tmp_path, outcome)
    assert result.error_code == "TIME_LIMIT_EXCEEDED" and "a retry cannot resume this attempt" in result.error_message
    kinds = {a.kind for a in result.artifacts}
    assert "SPLAT_PARTIAL" in kinds and not {"SPLAT", "SPLAT_CHECKPOINT"} & kinds
    assert result.error_details["checkpoint"] is None


# ---- atomicity and integrity -------------------------------------------------------------------------------------------


def test_an_interrupted_serialisation_propagates_and_leaves_the_previous_checkpoint_intact(tmp_path, monkeypatch):
    """Not an OSError: a crash inside the serializer is not a storage failure, so train() does not swallow it."""
    real_save, calls = torch.save, []

    def crashes_mid_write(obj, f, *a, **k):
        calls.append(1)
        if len(calls) == 2:
            f.write(b"PK\x03\x04 half an archive")
            raise RuntimeError("serializer crashed mid-write")
        return real_save(obj, f, *a, **k)

    monkeypatch.setattr(st.torch, "save", crashes_mid_write)
    with pytest.raises(RuntimeError, match="serializer crashed"):
        _train(tmp_path / "c.pt")
    monkeypatch.undo()
    assert not (tmp_path / "c.pt.tmp").exists()
    assert load_checkpoint(tmp_path / "c.pt", config=CONFIG, source=SOURCE, device=CPU)[0] == 4
    assert training_failure(RuntimeError("serializer crashed mid-write")) is None, "reported as INTERNAL_ERROR, not hidden"


def test_a_corrupt_leftover_temporary_checkpoint_is_never_used(tmp_path):
    (tmp_path / "c.pt.tmp").write_bytes(b"garbage from a crashed worker")
    outcome = _train(tmp_path / "c.pt")
    assert outcome.checkpoint_iteration == 8 and not (tmp_path / "c.pt.tmp").exists()
    assert load_checkpoint(outcome.checkpoint_path, config=CONFIG, source=SOURCE, device=CPU)[0] == 8


def test_an_io_error_other_than_a_checkpoint_write_is_not_swallowed_or_mislabelled(tmp_path):
    def unreadable_frame(*_a, **_k):
        raise FileNotFoundError(errno.ENOENT, "No such file or directory", "frame-0003.png")

    with pytest.raises(FileNotFoundError):
        _train(tmp_path / "c.pt", loss_fn=unreadable_frame)
    failure = training_failure(FileNotFoundError(errno.ENOENT, "No such file or directory", "frame-0003.png"))
    assert failure.code == "SPLAT_IO_FAILED" and "checkpoint" not in failure.message
    assert failure.details == {"errno": errno.ENOENT, "filename": "frame-0003.png"}


# ---- failed training, export and read-back: nothing is published -------------------------------------------------------


class _Stage:
    """A TEST stage with SplatReconstruction.run's shape: train, map a training failure with training_failure, publish."""

    name = "SPLAT_RECONSTRUCTION"

    def __init__(self, loss_fn=l1_dssim_loss) -> None:
        self.loss_fn = loss_fn

    def run(self, ctx):
        seed = _seed()
        try:
            outcome = train(params=seed, cams=rig(_checker), rasterize=cpu_rasterizer, config=CONFIG, loss_fn=self.loss_fn,
                            checkpoint_path=ctx.workdir / "splat-checkpoint.pt", source=SOURCE)
        except Exception as exc:
            failure = training_failure(exc)
            if failure is None:
                raise
            raise failure from exc
        return publish_outcome(ctx, outcome, source=SOURCE, cameras_used=4, versions={},
                               quality=cpu_quality(outcome, _seed(), rig(_checker)))


def test_a_failed_training_publishes_nothing_even_with_a_valid_earlier_checkpoint(tmp_path):
    def diverges_at_seven(pred, gt, config, valid=None):
        loss, parts = l1_dssim_loss(pred, gt, config, valid=valid)
        diverges_at_seven.n = getattr(diverges_at_seven, "n", 0) + 1
        return (loss * float("nan"), parts) if diverges_at_seven.n == 7 else (loss, parts)

    with pytest.raises(TrainingDiverged):  # the checkpoint at iteration 4 was written and is valid ...
        _train(tmp_path / "c.pt", loss_fn=diverges_at_seven)
    assert load_checkpoint(tmp_path / "c.pt", config=CONFIG, source=SOURCE, device=CPU)[0] == 4

    diverges_at_seven.n = 0
    h = Harness(tmp_path / "h")
    report = h.run(h.order("SPLAT_RECONSTRUCTION", []), registry={"SPLAT_RECONSTRUCTION": _Stage(diverges_at_seven)})
    assert report["status"] == "FAILED" and report["errorCode"] == "SPLAT_TRAINING_DIVERGED"
    assert report["artifacts"] == [], "... and still no splat or checkpoint of the failed run is registered"


def test_a_failed_ply_export_publishes_no_splat_whatever_the_checkpoint(tmp_path, monkeypatch):
    outcome = _train(tmp_path / "c.pt")

    def disk_full(*_a, **_k):
        raise OSError(errno.ENOSPC, "No space left on device")

    monkeypatch.setattr(splat_reconstruction, "write_ply", disk_full)
    result = _publish(tmp_path, outcome)
    assert result.status == "FAILED" and result.error_code == "SPLAT_EXPORT_FAILED"
    assert [a.kind for a in result.artifacts] == ["SPLAT_TRAINING_REPORT"], "a written final checkpoint does not rescue the export"


def test_a_ply_that_fails_read_back_is_not_published_after_a_checkpoint_failure(tmp_path, monkeypatch):
    _failing_writes(monkeypatch, {2})
    outcome = _train(tmp_path / "c.pt")
    monkeypatch.undo()
    real_read = splat_reconstruction.read_ply

    def lossy(path):
        cloud = real_read(path)
        cloud.colors_dc[0, 0] += 1e-3
        return cloud

    monkeypatch.setattr(splat_reconstruction, "read_ply", lossy)
    result = _publish(tmp_path, outcome)
    assert result.status == "FAILED" and result.error_code == "SPLAT_EXPORT_INVALID"
    assert [a.kind for a in result.artifacts] == ["SPLAT_TRAINING_REPORT"]
    assert _report(result)["checkpoint"]["write_failures"][0]["iteration"] == 8, "the checkpoint failure is still recorded"


class _FailsOn(LocalStorage):
    def __init__(self, root, name: str) -> None:
        super().__init__(root)
        self.name = name

    def upload(self, bucket, key, src, content_type):
        if key.endswith(self.name):
            raise StorageError(f"could not store {self.name}: disk full")
        return super().upload(bucket, key, src, content_type)


@pytest.mark.parametrize("name", ["splat.ply", "splat-checkpoint.pt", "splat-training-report.json"])
def test_an_output_that_cannot_be_uploaded_fails_the_job_and_registers_nothing(tmp_path, name):
    """The orchestrator registers a stage's outputs all or nothing, so a SPLAT is never registered without the rest."""
    h = Harness(tmp_path)
    h.storage = _FailsOn(tmp_path / "objects", name)
    report = h.run(h.order("SPLAT_RECONSTRUCTION", []), registry={"SPLAT_RECONSTRUCTION": _Stage()})
    assert report["status"] == "FAILED" and report["errorCode"] == "STORAGE_ERROR" and name in report["errorMessage"]
    assert report["artifacts"] == []


def test_the_same_stage_succeeds_with_every_output_stored(tmp_path):
    h = Harness(tmp_path)
    report = h.run(h.order("SPLAT_RECONSTRUCTION", []), registry={"SPLAT_RECONSTRUCTION": _Stage()})
    assert report["status"] == "SUCCEEDED"
    assert {a["kind"] for a in report["artifacts"]} == {"SPLAT", "SPLAT_CHECKPOINT", "SPLAT_TRAINING_REPORT"}



def test_a_full_disk_reaches_train_as_the_os_error_torch_wraps_in_a_runtime_error(tmp_path):
    """torch.save reports a mid-archive ENOSPC as RuntimeError("unexpected pos ..."); save_checkpoint recovers the OSError,
    so train() can tell a storage failure from a serializer fault."""
    with pytest.raises(RuntimeError) as wrapped, _DiskFills(tmp_path / "raw.pt", "w") as f:
        torch.save({"x": torch.zeros(10_000)}, f)
    assert isinstance(wrapped.value.__context__, OSError) and wrapped.value.__context__.errno == errno.ENOSPC

    with pytest.MonkeyPatch.context() as m:
        m.setattr(st, "open", lambda p, mode="r", *a, **k: _DiskFills(p, mode.replace("b", "")), raising=False)
        params = _seed()
        optimizer = st.make_optimizer(params, CONFIG, 1.0)
        with pytest.raises(OSError) as raised:
            st.save_checkpoint(tmp_path / "c.pt", iteration=1, params=params, optimizer=optimizer,
                               density=st.DensityControl.empty(len(params["means"]), CPU), config=CONFIG, source=SOURCE,
                               scene_extent=1.0, history=[])
    assert raised.value.errno == errno.ENOSPC and not (tmp_path / "c.pt").exists() and not (tmp_path / "c.pt.tmp").exists()
