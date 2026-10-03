"""The training lifecycle beyond the mechanics in test_splat_training.py: the position learning-rate schedule (review
G-3), camera-convention checks, divergence, checkpoint format and consistency, resume across a time-box stop, and the
rule that nothing invalid is published as a splat. CPU test renderer (tests/splat/renderer.py): none of this shows that
gsplat trains correctly on CUDA; tests/gpu/test_gsplat_cuda.py is where that is checked."""

from __future__ import annotations

import json
import logging
import math
import struct
from dataclasses import replace

import numpy as np
import pytest

torch = pytest.importorskip("torch", reason="torch is not installed: pip install torch (CPU is enough for these tests)")

from chaya_worker.contract import StageContext
from chaya_worker.ply import GAUSSIAN_PROPERTIES, read_ply
from chaya_worker.runner import CommandRunner
from chaya_worker.settings import Settings
from chaya_worker.splat_color import rgb_to_sh0
from chaya_worker.splat_training import (
    CHECKPOINT_FORMAT,
    PARAM_NAMES,
    SH_DEGREE,
    STATUS_COMPLETED,
    STATUS_PARTIAL,
    CameraConventionError,
    CheckpointMismatch,
    TrainingConfig,
    TrainingDiverged,
    TrainingOutcome,
    init_params,
    l1_dssim_loss,
    load_checkpoint,
    position_lr,
    source_identity,
    train,
    validate_trained_state,
)
from chaya_worker.stages import splat_reconstruction
from chaya_worker.stages.splat_reconstruction import initial_scale_log, publish_outcome
from chaya_worker.toolchain import Toolchain
from tests.splat.renderer import camera, cpu_rasterizer, plane_points, rig

CPU = torch.device("cpu")
SOURCE = source_identity(run_id="run-lifecycle", inputs=[])
NO_DENSIFY = TrainingConfig(iterations=0, densify_start=10**9, densify_stop=10**9, opacity_reset_every=0, checkpoint_every=0)


def _params(points=None):
    points = plane_points(3) if points is None else points
    return init_params(points, rgb_to_sh0(np.full(points.shape, 0.5, np.float32)), initial_scale_log(points), CPU)


def _checker(cam):
    ys, xs = np.mgrid[0:cam["height"], 0:cam["width"]]
    board = ((xs // 3 + ys // 3) % 2).astype(np.float32)
    return np.stack([board, 1 - board, 0.5 * board], axis=-1)


class FakeClock:
    def __init__(self) -> None:
        self.t = 1_000.0

    def __call__(self) -> float:
        self.t += 1.0
        return self.t


# ---- position learning-rate schedule --------------------------------------------------------------------------------


def test_the_position_schedule_is_the_reference_log_linear_decay():
    c = TrainingConfig()  # the reference values: 1.6e-4 -> 1.6e-6 over 30 000 steps
    assert (c.lr_position, c.lr_position_final, c.lr_position_decay_steps) == (1.6e-4, 1.6e-6, 30_000)
    extent = 2.5
    assert position_lr(c, 0, extent) == pytest.approx(1.6e-4 * extent, rel=1e-12)
    assert position_lr(c, 15_000, extent) == pytest.approx(math.sqrt(1.6e-4 * 1.6e-6) * extent, rel=1e-12)  # geometric mean
    assert position_lr(c, 30_000, extent) == pytest.approx(1.6e-6 * extent, rel=1e-12)
    assert position_lr(c, 90_000, extent) == position_lr(c, 30_000, extent), "held after the horizon"
    rates = [position_lr(c, i, 1.0) for i in range(0, 30_001, 1000)]
    assert all(a > b for a, b in zip(rates, rates[1:], strict=False)), "strictly decreasing over the horizon"
    ratios = [b / a for a, b in zip(rates, rates[1:], strict=False)]
    assert max(ratios) - min(ratios) < 1e-9, "a constant ratio per step: exponential"
    # the default 7 000-iteration run ends where the reference's 7k snapshot does
    assert position_lr(c, 7_000, 1.0) == pytest.approx(1.6e-4 * 0.01 ** (7_000 / 30_000), rel=1e-12)


def test_zero_decay_steps_is_a_constant_rate_and_bad_schedules_are_refused():
    assert position_lr(replace(TrainingConfig(), lr_position_decay_steps=0), 5_000, 3.0) == pytest.approx(1.6e-4 * 3.0)
    with pytest.raises(ValueError):
        TrainingConfig(lr_position_final=1e-3)  # above the initial rate
    with pytest.raises(ValueError):
        TrainingConfig(lr_position_final=0.0)
    with pytest.raises(ValueError):
        TrainingConfig(lr_position_decay_steps=-1)


def test_training_applies_the_schedule_at_every_iteration(tmp_path):
    config = replace(NO_DENSIFY, iterations=30, lr_position_decay_steps=20)
    cams = rig(_checker)
    outcome = train(params=_params(), cams=cams, rasterize=cpu_rasterizer, config=config, loss_fn=l1_dssim_loss,
                    checkpoint_path=tmp_path / "c.pt", source=SOURCE)
    from chaya_worker.splat_training import scene_extent_of

    extent = scene_extent_of(cams)
    used = [h["lr_position"] for h in outcome.history]
    assert used == [position_lr(config, i, extent) for i in range(30)]
    assert used[0] == pytest.approx(1.6e-4 * extent) and used[25] == pytest.approx(1.6e-6 * extent)
    payload = torch.load(outcome.checkpoint_path, weights_only=True)
    assert payload["lrs"]["means"] == used[-1]


def test_the_schedule_survives_densification(tmp_path):
    """Density control replaces the means parameter; the schedule must keep driving the new one."""
    config = replace(NO_DENSIFY, iterations=40, densify_start=10, densify_stop=35, densify_every=10, densify_grad_threshold=1e-5,
                     lr_position_decay_steps=40)
    cams = rig(_checker)
    outcome = train(params=_params(plane_points(4, half=3.0)), cams=cams, rasterize=cpu_rasterizer, config=config,
                    loss_fn=l1_dssim_loss, checkpoint_path=tmp_path / "c.pt", source=SOURCE)
    from chaya_worker.splat_training import scene_extent_of

    assert sum(e.cloned + e.split for e in outcome.densify_events) > 0
    extent = scene_extent_of(cams)
    assert [h["lr_position"] for h in outcome.history] == [position_lr(config, i, extent) for i in range(40)]


# ---- resume across a time-box stop ---------------------------------------------------------------------------------


SCHEDULED = replace(NO_DENSIFY, iterations=40, densify_start=10, densify_stop=35, densify_every=10, densify_grad_threshold=1e-5,
                    opacity_reset_every=25, lr_position_decay_steps=30)


def test_a_run_stopped_by_the_time_budget_resumes_to_exactly_the_uninterrupted_result(tmp_path):
    points, cams = plane_points(4, half=3.0), rig(_checker)
    straight = train(params=_params(points), cams=cams, rasterize=cpu_rasterizer, config=SCHEDULED, loss_fn=l1_dssim_loss,
                     checkpoint_path=tmp_path / "straight.pt", source=SOURCE)
    clock = FakeClock()
    stopped = train(params=_params(points), cams=cams, rasterize=cpu_rasterizer, config=SCHEDULED, loss_fn=l1_dssim_loss,
                    checkpoint_path=tmp_path / "stopped.pt", source=SOURCE, deadline=clock.t + 40, stop_margin_seconds=10,
                    clock=clock)
    assert stopped.status == STATUS_PARTIAL and 0 < stopped.completed_iterations < 40
    state = load_checkpoint(stopped.checkpoint_path, config=SCHEDULED, source=SOURCE, device=CPU)
    assert state[0] == stopped.completed_iterations
    resumed = train(params=state[1], cams=cams, rasterize=cpu_rasterizer, config=SCHEDULED, loss_fn=l1_dssim_loss,
                    checkpoint_path=tmp_path / "resumed.pt", source=SOURCE, resume=state)
    assert resumed.status == STATUS_COMPLETED and resumed.completed_iterations == 40
    for name in PARAM_NAMES:
        assert torch.equal(resumed.params[name], straight.params[name]), name
    assert [h["lr_position"] for h in resumed.history] == [h["lr_position"] for h in straight.history]


# ---- checkpoints ---------------------------------------------------------------------------------------------------


def _checkpoint(tmp_path, config=SCHEDULED, iterations=5):
    outcome = train(params=_params(), cams=rig(_checker), rasterize=cpu_rasterizer, config=replace(config, iterations=iterations),
                    loss_fn=l1_dssim_loss, checkpoint_path=tmp_path / "c.pt", source=SOURCE)
    return outcome.checkpoint_path


def test_a_checkpoint_records_its_format_degree_and_schedule(tmp_path):
    payload = torch.load(_checkpoint(tmp_path), weights_only=True)
    assert payload["format"] == CHECKPOINT_FORMAT == "chaya-splat-checkpoint/2" and payload["sh_degree"] == SH_DEGREE == 0
    assert payload["config"]["lr_position_final"] == 1.6e-6 and payload["config"]["lr_position_decay_steps"] == 30


def test_the_schedule_horizon_cannot_change_on_resume(tmp_path):
    path = _checkpoint(tmp_path)
    with pytest.raises(CheckpointMismatch, match="lr_position_decay_steps"):
        load_checkpoint(path, config=replace(SCHEDULED, lr_position_decay_steps=31), source=SOURCE, device=CPU)
    with pytest.raises(CheckpointMismatch, match="lr_position_final"):
        load_checkpoint(path, config=replace(SCHEDULED, lr_position_final=1e-6), source=SOURCE, device=CPU)


def test_a_format_1_checkpoint_is_refused_with_a_reason(tmp_path):
    path = _checkpoint(tmp_path)
    payload = torch.load(path, weights_only=True)
    payload["format"] = "chaya-splat-checkpoint/1"
    torch.save(payload, path)
    with pytest.raises(CheckpointMismatch, match="cannot be resumed exactly"):
        load_checkpoint(path, config=SCHEDULED, source=SOURCE, device=CPU)


@pytest.mark.parametrize("corrupt", ["nan", "rows", "density", "degree"])
def test_an_inconsistent_checkpoint_is_refused(tmp_path, corrupt):
    path = _checkpoint(tmp_path)
    payload = torch.load(path, weights_only=True)
    if corrupt == "nan":
        payload["params"]["means"][0, 0] = float("nan")
    elif corrupt == "rows":
        payload["params"]["sh0"] = payload["params"]["sh0"][:-1]
    elif corrupt == "density":
        payload["density"]["grad2d"] = payload["density"]["grad2d"][:-1]
    else:
        payload["sh_degree"] = 3
    torch.save(payload, path)
    with pytest.raises(CheckpointMismatch):
        load_checkpoint(path, config=SCHEDULED, source=SOURCE, device=CPU)


# ---- conventions ---------------------------------------------------------------------------------------------------


@pytest.mark.parametrize("break_it, message", [
    ("reflection", "proper rotation"),
    ("not_rigid", "4x4 rigid"),
    ("skew", "without skew"),
    ("principal_point", "principal point"),
    ("image_size", "expected"),
    ("image_uint8", "expected"),
    ("image_range", r"\[0, 1\]"),
    ("mask", "validity mask"),
])
def test_a_camera_outside_the_convention_fails_before_any_iteration(tmp_path, break_it, message):
    cams = rig(_checker)
    cam = cams[1]
    if break_it == "reflection":
        cam["viewmat"] = cam["viewmat"] @ np.diag([1.0, 1.0, -1.0, 1.0])
    elif break_it == "not_rigid":
        cam["viewmat"] = cam["viewmat"].copy()
        cam["viewmat"][3, 2] = 1.0
    elif break_it == "skew":
        cam["K"] = cam["K"].copy()
        cam["K"][0, 1] = 0.5
    elif break_it == "principal_point":
        cam["K"] = cam["K"].copy()
        cam["K"][0, 2] = -3.0
    elif break_it == "image_size":
        cam["image"] = cam["image"][:-1]
    elif break_it == "image_uint8":
        cam["image"] = (cam["image"] * 255).astype(np.uint8)
    elif break_it == "image_range":
        cam["image"] = cam["image"] * 255.0
    else:
        cam["valid"] = np.ones((3, 3), bool)
    seen = []
    with pytest.raises(CameraConventionError, match=message):
        train(params=_params(), cams=cams, rasterize=lambda *a: seen.append(1) or cpu_rasterizer(*a), config=replace(NO_DENSIFY, iterations=5),
              loss_fn=l1_dssim_loss, checkpoint_path=tmp_path / "c.pt", source=SOURCE)
    assert not seen and not (tmp_path / "c.pt").exists()


def test_the_test_rig_is_in_the_convention():
    from chaya_worker.splat_training import validate_camera

    for cam in rig(_checker) + [camera(0.0, 0.0, _checker({"height": 24, "width": 24}))]:
        validate_camera(cam)


# ---- divergence ----------------------------------------------------------------------------------------------------


def test_a_non_finite_loss_stops_training_and_checkpoints_nothing(tmp_path):
    calls = []

    def nan_after_three(pred, gt, config, valid=None):
        calls.append(1)
        loss, parts = l1_dssim_loss(pred, gt, config, valid)
        return (loss * float("nan") if len(calls) > 3 else loss), parts

    with pytest.raises(TrainingDiverged, match="iteration 4"):
        train(params=_params(), cams=rig(_checker), rasterize=cpu_rasterizer, config=replace(NO_DENSIFY, iterations=20),
              loss_fn=nan_after_three, checkpoint_path=tmp_path / "c.pt", source=SOURCE)
    assert not (tmp_path / "c.pt").exists()


def test_a_parameter_made_non_finite_by_an_update_stops_training_even_with_a_finite_loss(tmp_path):
    def nan_gradient(pred, gt, config, valid=None):
        loss, parts = l1_dssim_loss(pred, gt, config, valid)
        # value exactly 0, gradient sqrt'(0) * sign(0) = inf * 0 = nan: the update poisons the parameters
        return loss + torch.sqrt((pred - pred.detach()).abs().sum()), parts

    with pytest.raises(TrainingDiverged, match="parameter became non-finite at iteration 1"):
        train(params=_params(), cams=rig(_checker), rasterize=cpu_rasterizer, config=replace(NO_DENSIFY, iterations=20),
              loss_fn=nan_gradient, checkpoint_path=tmp_path / "c.pt", source=SOURCE)
    assert not (tmp_path / "c.pt").exists()


# ---- what may be published ------------------------------------------------------------------------------------------


def _ctx(tmp_path) -> StageContext:
    work = tmp_path / "work"
    work.mkdir(exist_ok=True)
    runner = CommandRunner(work / "out.log", work / "err.log")
    return StageContext({"stage": "SPLAT_RECONSTRUCTION", "inputs": [], "runId": "run-1"}, [], work, logging.getLogger("t"), runner,
                        Toolchain(env={}, which=lambda _n: None), Settings(), None)


def _trained(tmp_path, iterations=6):
    return train(params=_params(), cams=rig(_checker), rasterize=cpu_rasterizer, config=replace(NO_DENSIFY, iterations=iterations),
                 loss_fn=l1_dssim_loss, checkpoint_path=tmp_path / "c.pt", source=SOURCE)


def _report(result):
    return json.loads(next(a for a in result.artifacts if a.kind == "SPLAT_TRAINING_REPORT").path.read_text())


def test_a_valid_completed_run_publishes_a_splat_that_reads_back_exactly(tmp_path):
    outcome = _trained(tmp_path)
    result = publish_outcome(_ctx(tmp_path), outcome, source=SOURCE, cameras_used=4, versions={})
    assert result.status == "SUCCEEDED"
    splat = next(a for a in result.artifacts if a.kind == "SPLAT")
    back = read_ply(splat.path)
    np.testing.assert_array_equal(back.positions, outcome.params["means"].detach().numpy())
    report = _report(result)
    assert report["validation"] == {"valid": True, "code": None, "message": None}
    assert report["sh_degree"] == 0 and report["position_lr_schedule"]["final"] == 1.6e-6


@pytest.mark.parametrize("damage, code", [
    ("nan", "SPLAT_INVALID"),
    ("empty", "SPLAT_INVALID"),
    ("zero_quat", "SPLAT_INVALID"),
    ("short", "SPLAT_INVALID"),
    ("untrained", "SPLAT_NOT_TRAINED"),
])
def test_an_invalid_trained_state_is_never_published(tmp_path, damage, code):
    outcome = _trained(tmp_path)
    if damage == "nan":
        with torch.no_grad():
            outcome.params["opacity_logit"][0] = float("inf")
    elif damage == "empty":
        outcome.params = {k: v[:0] for k, v in outcome.params.items()}
    elif damage == "zero_quat":
        with torch.no_grad():
            outcome.params["quats"][2] = 0.0
    elif damage == "short":  # claims COMPLETED without having run every iteration
        outcome = replace(outcome, target_iterations=outcome.completed_iterations + 5)
    else:  # 0 configured iterations: "completed" without training
        outcome = replace(outcome, completed_iterations=0, target_iterations=0)
    result = publish_outcome(_ctx(tmp_path), outcome, source=SOURCE, cameras_used=4, versions={})
    assert result.status == "FAILED" and result.error_code == code
    assert {a.kind for a in result.artifacts} == {"SPLAT_TRAINING_REPORT"}, "no splat and no checkpoint of an invalid state"
    assert _report(result)["validation"]["valid"] is False


def test_a_ply_that_does_not_read_back_as_the_trained_cloud_is_not_published(tmp_path, monkeypatch):
    real = splat_reconstruction.read_ply

    def lossy(path):
        cloud = real(path)
        cloud.colors_dc[0, 0] += 1e-3
        return cloud

    monkeypatch.setattr(splat_reconstruction, "read_ply", lossy)
    result = publish_outcome(_ctx(tmp_path), _trained(tmp_path), source=SOURCE, cameras_used=4, versions={})
    assert result.status == "FAILED" and result.error_code == "SPLAT_EXPORT_INVALID" and "colors_dc" in result.error_message
    assert {a.kind for a in result.artifacts} == {"SPLAT_TRAINING_REPORT"}


def test_validate_trained_state_accepts_a_trained_state():
    assert validate_trained_state(_params()) == []


def test_the_outcome_type_carries_its_config():
    assert "config" in TrainingOutcome.__dataclass_fields__


# ---- SH degree ------------------------------------------------------------------------------------------------------


def test_a_ply_with_higher_sh_bands_is_refused_rather_than_silently_truncated(tmp_path):
    props = GAUSSIAN_PROPERTIES[:9] + [f"f_rest_{i}" for i in range(9)] + GAUSSIAN_PROPERTIES[9:]
    header = "\n".join(["ply", "format binary_little_endian 1.0", "element vertex 1",
                        *(f"property float {p}" for p in props), "end_header", ""]).encode("ascii")
    values = [0.0] * len(props)
    values[props.index("rot_0")] = 1.0
    path = tmp_path / "sh1.ply"
    path.write_bytes(header + struct.pack(f"<{len(props)}f", *values))
    with pytest.raises(ValueError, match="higher-order SH"):
        read_ply(path)
    assert len(read_ply(path, drop_higher_sh=True)) == 1
