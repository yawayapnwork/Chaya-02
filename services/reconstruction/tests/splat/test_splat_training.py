"""The splat training mechanics, run on CPU with the test renderer in conftest.py. That renderer is not gsplat (see its
docstring in tests/splat/renderer.py), so nothing here shows reconstruction quality. It covers the colour convention in training, density control
(clone, split, prune, cap), checkpoint/resume, the time budget, PARTIAL status, and the stage's artifacts and
provenance."""

from __future__ import annotations

import json
import logging
import time
from dataclasses import replace
from datetime import UTC, datetime, timedelta

import numpy as np
import pytest

torch = pytest.importorskip("torch", reason="torch is not installed: pip install torch (CPU is enough for these tests)")

from chaya_worker import ksplat
from chaya_worker.contract import StageContext, StageResult
from chaya_worker.ply import read_ply
from chaya_worker.runner import CommandRunner
from chaya_worker.settings import Settings
from chaya_worker.splat_color import rgb_to_sh0, sh0_to_rgb
from chaya_worker.splat_training import (
    STATUS_COMPLETED,
    STATUS_PARTIAL,
    CheckpointMismatch,
    DensityControl,
    TrainingConfig,
    init_params,
    l1_dssim_loss,
    load_checkpoint,
    make_optimizer,
    render_inputs,
    source_identity,
    train,
)
from chaya_worker.stages.base import sha256_file
from chaya_worker.stages.splat_reconstruction import initial_scale_log, publish_outcome
from chaya_worker.toolchain import Toolchain
from tests.splat.renderer import cpu_rasterizer, plane_points, rig

CPU = torch.device("cpu")
SOURCE = source_identity(run_id="run-1", scan_version_id="v1", inputs=[{"kind": "SPARSE_MODEL", "sha256": "a" * 64}])
NO_DENSIFY = TrainingConfig(iterations=0, densify_start=10**9, densify_stop=10**9, opacity_reset_every=0, checkpoint_every=0)


def _params(points: np.ndarray, rgb01: np.ndarray):
    return init_params(points, rgb_to_sh0(np.broadcast_to(rgb01, points.shape).astype(np.float32)), initial_scale_log(points), CPU)


def _solid(colour):
    return lambda cam: np.broadcast_to(np.asarray(colour, np.float32), (cam["height"], cam["width"], 3)).copy()


def _checker(cam):
    ys, xs = np.mgrid[0:cam["height"], 0:cam["width"]]
    board = ((xs // 3 + ys // 3) % 2).astype(np.float32)
    return np.stack([board, 1 - board, 0.5 * board], axis=-1)


# ---- colour ------------------------------------------------------------------------------------------------------------


def test_the_rasteriser_is_given_the_colour_every_consumer_displays():
    rgb = np.array([[0.2, 0.5, 0.8], [1.0, 0.0, 0.25]], np.float32)
    params = init_params(np.zeros((2, 3), np.float32), rgb_to_sh0(rgb), np.zeros((2, 3), np.float32), CPU)
    handed = render_inputs(params)[4].detach().numpy()
    np.testing.assert_allclose(handed, rgb, atol=1e-6)
    np.testing.assert_allclose(handed, sh0_to_rgb(params["sh0"].detach().numpy()), atol=1e-6)


@pytest.mark.parametrize("target", [(0.8, 0.3, 0.15), (0.1, 0.6, 0.9)])
def test_a_converged_single_colour_fit_exports_its_colour(tmp_path, target):
    """Review R-3's test: fit a single-colour scene, export it to the viewer format, and look at it as the viewer would.
    The Gaussians start mid-grey, so the colour comes from optimisation, not from the initialisation. Every value is taken
    from the decoded ksplat bytes: positions, scales, rotations, 8-bit opacity and 8-bit colour. They are rendered and
    compared with the target. Individual Gaussians need not equal the target: over a black background they are brighter
    where coverage is below 1. The image is what must match. Under the old sigmoid convention this image was off by up to
    about 0.09 at 0.8."""
    points = plane_points(8, half=3.2)
    cams = rig(_solid(target))
    config = replace(NO_DENSIFY, iterations=300, lr_color=2e-2)
    outcome = train(params=_params(points, np.full(3, 0.5, np.float32)), cams=cams, rasterize=cpu_rasterizer, config=config,
                    loss_fn=l1_dssim_loss, checkpoint_path=tmp_path / "c.pt", source=SOURCE)
    assert outcome.status == STATUS_COMPLETED
    result = publish_outcome(_ctx(tmp_path), outcome, source=SOURCE, cameras_used=len(cams), versions={})
    cloud = read_ply(next(a.path for a in result.artifacts if a.kind == "SPLAT"))
    viewer = ksplat.decode(ksplat.encode(cloud))
    rgba = torch.tensor(viewer["rgba"].astype(np.float32) / 255.0)
    shown = cpu_rasterizer(torch.tensor(viewer["centers"]), torch.tensor(viewer["rotations_wxyz"]), torch.tensor(viewer["scales"]),
                           rgba[:, 3], rgba[:, :3], cams[0]).image.numpy()
    np.testing.assert_allclose(shown[6:-6, 6:-6].reshape(-1, 3).mean(axis=0), target, atol=0.02)
    # and not just on average: every interior pixel
    assert np.abs(shown[6:-6, 6:-6] - np.asarray(target)).max() < 0.03


# ---- density control ---------------------------------------------------------------------------------------------------


def _four_gaussians():
    """Two small (0.01) and two large (1.0) Gaussians, in a scene of extent 10 (split/clone boundary 0.1)."""
    points = np.array([[0, 0, 5], [1, 0, 5], [0, 1, 5], [1, 1, 5]], np.float32)
    scales = np.log(np.array([[0.01] * 3, [0.01] * 3, [1.0] * 3, [1.0] * 3], np.float32))
    params = init_params(points, np.zeros((4, 3), np.float32), scales, CPU)
    optimizer = make_optimizer(params, NO_DENSIFY, scene_extent=10.0)
    loss = sum((p.float() ** 2).sum() for p in params.values())  # any gradient, so Adam has non-zero moments
    loss.backward()
    optimizer.step()
    return params, optimizer


def test_densify_clones_small_and_splits_large_high_gradient_gaussians():
    params, optimizer = _four_gaussians()
    old_means = params["means"].detach().clone()
    old_moment = optimizer.state[params["means"]]["exp_avg"].clone()
    density = DensityControl(torch.tensor([1e-3, 1e-6, 1e-3, 1e-6]), torch.ones(4))
    config = replace(NO_DENSIFY, densify_grad_threshold=2e-4, percent_dense=0.01)

    event = density.densify(99, params, optimizer, config, scene_extent=10.0)

    assert (event.before, event.cloned, event.split, event.after) == (4, 1, 1, 6)
    means = params["means"].detach()
    # survivors first, in order: 0 (cloned parent), 1, 3; the split parent 2 is gone
    assert torch.equal(means[:3], old_means[[0, 1, 3]])
    assert torch.equal(means[3], old_means[0]), "the clone is an exact copy"
    children = means[4:]
    assert (children - old_means[2]).norm(dim=-1).max() < 4.0 and not torch.equal(children[0], children[1])
    np.testing.assert_allclose(torch.exp(params["scales_log"][4:]).detach().numpy(), 1.0 / 1.6, rtol=1e-6)
    # Adam moments follow their Gaussians; new ones start at zero
    moment = optimizer.state[params["means"]]["exp_avg"]
    assert torch.equal(moment[:3], old_moment[[0, 1, 3]]) and not moment[3:].any()
    assert all(len(optimizer.state[params[k]]["exp_avg"]) == 6 for k in params)
    assert not density.grad2d.any() and len(density.grad2d) == 6


def test_densify_prunes_transparent_gaussians_and_oversized_ones_after_the_first_reset():
    params, optimizer = _four_gaussians()
    with torch.no_grad():
        params["opacity_logit"][1] = -10.0  # opacity ~ 4.5e-5 < 0.005
    density = DensityControl(torch.zeros(4), torch.ones(4))
    config = replace(NO_DENSIFY, prune_opacity=0.005, prune_scale_fraction=0.05, opacity_reset_every=3000)

    early = density.densify(999, params, optimizer, config, scene_extent=10.0)  # before any reset: size pruning is off
    assert (early.pruned_opacity, early.pruned_scale, early.after) == (1, 0, 3)
    late = density.densify(3099, params, optimizer, config, scene_extent=10.0)  # large (1.0 > 0.05 * 10) are now pruned
    assert (late.pruned_opacity, late.pruned_scale, late.after) == (0, 2, 1)
    assert torch.exp(params["scales_log"]).max().item() == pytest.approx(0.01, rel=0.02)


def test_the_gaussian_cap_keeps_the_highest_gradient_candidates():
    params, optimizer = _four_gaussians()
    density = DensityControl(torch.tensor([1e-3, 5e-3, 1e-3, 1e-6]), torch.ones(4))
    config = replace(NO_DENSIFY, densify_grad_threshold=2e-4, max_gaussians=5)
    event = density.densify(99, params, optimizer, config, scene_extent=10.0)
    assert (event.cloned, event.split, event.capped, event.after) == (1, 0, 2, 5)
    assert len(params["means"]) <= config.max_gaussians


def test_training_changes_the_gaussian_count_through_density_control(tmp_path):
    points = plane_points(4, half=3.0)
    config = replace(NO_DENSIFY, iterations=60, densify_start=10, densify_stop=50, densify_every=10, densify_grad_threshold=1e-5)
    outcome = train(params=_params(points, np.full(3, 0.5, np.float32)), cams=rig(_checker), rasterize=cpu_rasterizer, config=config,
                    loss_fn=l1_dssim_loss, checkpoint_path=tmp_path / "c.pt", source=SOURCE)
    counts = [h["gaussians"] for h in outcome.history]
    assert [e.iteration for e in outcome.densify_events] == [10, 20, 30, 40]
    assert outcome.initial_gaussian_count == 16 and outcome.gaussian_count > 16
    assert sum(e.cloned + e.split for e in outcome.densify_events) > 0
    assert len(set(counts)) > 1, "the Gaussian count must actually change during training"
    assert counts[-1] == outcome.gaussian_count == len(outcome.params["means"])


# ---- checkpoint / resume -------------------------------------------------------------------------------------------------


RESUME_CONFIG = replace(NO_DENSIFY, iterations=40, densify_start=10, densify_stop=35, densify_every=10, densify_grad_threshold=1e-5,
                        opacity_reset_every=25)


def test_resuming_from_a_checkpoint_reproduces_an_uninterrupted_run_exactly(tmp_path):
    points = plane_points(4, half=3.0)
    cams = rig(_checker)
    straight = train(params=_params(points, np.full(3, 0.5, np.float32)), cams=cams, rasterize=cpu_rasterizer, config=RESUME_CONFIG,
                     loss_fn=l1_dssim_loss, checkpoint_path=tmp_path / "straight.pt", source=SOURCE)

    first = train(params=_params(points, np.full(3, 0.5, np.float32)), cams=cams, rasterize=cpu_rasterizer,
                  config=replace(RESUME_CONFIG, iterations=22), loss_fn=l1_dssim_loss, checkpoint_path=tmp_path / "half.pt", source=SOURCE)
    assert first.status == STATUS_COMPLETED and first.completed_iterations == 22
    state = load_checkpoint(first.checkpoint_path, config=RESUME_CONFIG, source=SOURCE, device=CPU)
    assert state[0] == 22
    resumed = train(params=state[1], cams=cams, rasterize=cpu_rasterizer, config=RESUME_CONFIG, loss_fn=l1_dssim_loss,
                    checkpoint_path=tmp_path / "resumed.pt", source=SOURCE, resume=state)

    assert resumed.resumed_from_iteration == 22 and resumed.completed_iterations == 40
    assert resumed.gaussian_count == straight.gaussian_count
    for name in straight.params:
        assert torch.equal(resumed.params[name], straight.params[name]), name
    assert [e.__dict__ for e in resumed.densify_events] == [e.__dict__ for e in straight.densify_events]
    assert resumed.opacity_resets == straight.opacity_resets == [25]
    assert [h["gaussians"] for h in resumed.history] == [h["gaussians"] for h in straight.history]
    assert resumed.initial_gaussian_count == 16


def test_a_checkpoint_records_what_a_resume_needs(tmp_path):
    outcome = train(params=_params(plane_points(3), np.full(3, 0.5, np.float32)), cams=rig(_checker), rasterize=cpu_rasterizer,
                    config=replace(RESUME_CONFIG, iterations=12), loss_fn=l1_dssim_loss, checkpoint_path=tmp_path / "c.pt", source=SOURCE)
    payload = torch.load(outcome.checkpoint_path, weights_only=True)
    assert payload["iteration"] == 12 and payload["source"] == SOURCE
    assert payload["config"]["densify_grad_threshold"] == 1e-5
    assert set(payload["params"]) == {"means", "scales_log", "quats", "opacity_logit", "sh0"}
    # Adam state of every parameter that received a gradient (the isotropic test renderer gives rotations none, so Adam
    # holds no state for them, and the checkpoint mirrors exactly that)
    assert all({"exp_avg", "exp_avg_sq", "step"} <= set(payload["optimizer"][k]) for k in ("means", "scales_log", "opacity_logit", "sh0"))
    assert payload["optimizer"]["quats"] == {}
    assert len(payload["density"]["grad2d"]) == len(payload["params"]["means"])


def test_a_checkpoint_is_refused_for_other_inputs_or_other_settings(tmp_path):
    outcome = train(params=_params(plane_points(3), np.full(3, 0.5, np.float32)), cams=rig(_checker), rasterize=cpu_rasterizer,
                    config=replace(RESUME_CONFIG, iterations=5), loss_fn=l1_dssim_loss, checkpoint_path=tmp_path / "c.pt", source=SOURCE)
    other = source_identity(run_id="run-2", scan_version_id="v1", inputs=[])
    with pytest.raises(CheckpointMismatch, match="different inputs"):
        load_checkpoint(outcome.checkpoint_path, config=RESUME_CONFIG, source=other, device=CPU)
    with pytest.raises(CheckpointMismatch, match="lr_color"):
        load_checkpoint(outcome.checkpoint_path, config=replace(RESUME_CONFIG, lr_color=1.0), source=SOURCE, device=CPU)
    # only the training length (and checkpoint cadence) may change on resume
    load_checkpoint(outcome.checkpoint_path, config=replace(RESUME_CONFIG, iterations=10_000), source=SOURCE, device=CPU)


# ---- time budget and partial status --------------------------------------------------------------------------------------


class FakeClock:
    """Each call advances one second: every iteration then "takes" at least a second, deterministically."""

    def __init__(self) -> None:
        self.t = 1_000.0

    def __call__(self) -> float:
        self.t += 1.0
        return self.t


def test_the_time_budget_stops_training_early_as_partial_with_a_resumable_checkpoint(tmp_path):
    clock = FakeClock()
    config = replace(NO_DENSIFY, iterations=500)
    outcome = train(params=_params(plane_points(3), np.full(3, 0.5, np.float32)), cams=rig(_checker), rasterize=cpu_rasterizer,
                    config=config, loss_fn=l1_dssim_loss, checkpoint_path=tmp_path / "c.pt", source=SOURCE,
                    deadline=clock.t + 40, stop_margin_seconds=10, clock=clock)
    assert outcome.status == STATUS_PARTIAL and outcome.stop_reason == "time budget"
    assert 0 < outcome.completed_iterations < config.iterations
    assert clock.t <= 1_000.0 + 40, "stopped before the deadline, with the margin left for outputs"
    payload = torch.load(outcome.checkpoint_path, weights_only=True)
    assert payload["iteration"] == outcome.completed_iterations


def test_a_budget_already_spent_runs_no_iteration(tmp_path):
    outcome = train(params=_params(plane_points(3), np.full(3, 0.5, np.float32)), cams=rig(_checker), rasterize=cpu_rasterizer,
                    config=replace(NO_DENSIFY, iterations=100), loss_fn=l1_dssim_loss, checkpoint_path=tmp_path / "c.pt",
                    source=SOURCE, deadline=time.time() - 1, stop_margin_seconds=0)
    assert outcome.status == STATUS_PARTIAL and outcome.completed_iterations == 0


def test_cancellation_raises_rather_than_returning_a_result(tmp_path):
    with pytest.raises(InterruptedError):
        train(params=_params(plane_points(3), np.full(3, 0.5, np.float32)), cams=rig(_checker), rasterize=cpu_rasterizer,
              config=replace(NO_DENSIFY, iterations=100), loss_fn=l1_dssim_loss, checkpoint_path=tmp_path / "c.pt", source=SOURCE,
              is_cancelled=lambda: True)


# ---- the stage's result, artifacts and provenance ---------------------------------------------------------------------


def _ctx(tmp_path) -> StageContext:
    work = tmp_path / "work"
    work.mkdir(exist_ok=True)
    runner = CommandRunner(work / "out.log", work / "err.log")
    return StageContext({"stage": "SPLAT_RECONSTRUCTION", "inputs": [], "runId": "run-1"}, [], work, logging.getLogger("t"), runner,
                        Toolchain(env={}, which=lambda _n: None), Settings(), None)


def _outcome(tmp_path, *, iterations: int, deadline_after: float | None):
    clock = FakeClock()
    return train(params=_params(plane_points(3), np.full(3, 0.5, np.float32)), cams=rig(_checker), rasterize=cpu_rasterizer,
                 config=replace(NO_DENSIFY, iterations=iterations), loss_fn=l1_dssim_loss, checkpoint_path=tmp_path / "c.pt",
                 source=SOURCE, deadline=None if deadline_after is None else clock.t + deadline_after, stop_margin_seconds=2, clock=clock)


def test_a_completed_run_succeeds_with_a_splat_and_full_provenance(tmp_path):
    ctx = _ctx(tmp_path)
    result = publish_outcome(ctx, _outcome(tmp_path, iterations=8, deadline_after=None), source=SOURCE, cameras_used=4,
                             versions={"torch": torch.__version__})
    assert isinstance(result, StageResult) and result.status == "SUCCEEDED" and result.error_code is None
    kinds = {a.kind: a for a in result.artifacts}
    assert set(kinds) == {"SPLAT", "SPLAT_CHECKPOINT", "SPLAT_TRAINING_REPORT"} and not kinds["SPLAT"].partial
    report = json.loads(kinds["SPLAT_TRAINING_REPORT"].path.read_text())
    assert report["status"] == "COMPLETED" and report["completed_iterations"] == report["target_iterations"] == 8
    prov = report["provenance"]
    assert prov["source"] == SOURCE and prov["versions"]["torch"] == torch.__version__
    assert prov["splat"]["sha256"] == sha256_file(kinds["SPLAT"].path)
    assert prov["checkpoint"]["sha256"] == sha256_file(kinds["SPLAT_CHECKPOINT"].path)
    assert report["gaussian_count"] == len(read_ply(kinds["SPLAT"].path))


def test_an_early_stop_is_partial_never_completed(tmp_path):
    result = publish_outcome(_ctx(tmp_path), _outcome(tmp_path, iterations=1000, deadline_after=12), source=SOURCE, cameras_used=4,
                             versions={})
    assert result.status == "FAILED" and result.error_code == "TIME_LIMIT_EXCEEDED"
    kinds = {a.kind: a for a in result.artifacts}
    assert "SPLAT" not in kinds, "a partial cloud is never published as the finished SPLAT"
    assert kinds["SPLAT_PARTIAL"].partial and kinds["SPLAT_PARTIAL"].name == "splat-partial.ply"
    assert "SPLAT_CHECKPOINT" in kinds
    report = json.loads(kinds["SPLAT_TRAINING_REPORT"].path.read_text())
    assert report["status"] == "PARTIAL" and report["stop_reason"] == "time budget"
    assert 0 < report["completed_iterations"] < report["target_iterations"] == 1000
    assert result.error_details["completed_iterations"] == report["completed_iterations"]
    assert result.error_details["checkpoint"] == "splat-checkpoint.pt"


def test_no_iteration_means_no_splat_at_all(tmp_path):
    result = publish_outcome(_ctx(tmp_path), _outcome(tmp_path, iterations=100, deadline_after=0), source=SOURCE, cameras_used=4,
                             versions={})
    assert result.status == "FAILED" and result.error_code == "TIME_LIMIT_EXCEEDED"
    assert not {"SPLAT", "SPLAT_PARTIAL"} & {a.kind for a in result.artifacts}
    assert "no iteration ran" in result.error_message


# ---- through the orchestrator: the report the control plane receives -----------------------------------------------------


class CpuTimeBoxedSplat:
    """A TEST stage: the real training loop, publish_outcome and the orchestrator, with the CPU test renderer in place of
    gsplat, against the work order's real deadline. It stands in for SplatReconstruction only to show which report the
    control plane gets when the budget runs out."""

    name = "SPLAT_RECONSTRUCTION"

    def run(self, ctx):
        outcome = train(params=_params(plane_points(3), np.full(3, 0.5, np.float32)), cams=rig(_checker), rasterize=cpu_rasterizer,
                        config=replace(NO_DENSIFY, iterations=10**7), loss_fn=l1_dssim_loss,
                        checkpoint_path=ctx.workdir / "splat-checkpoint.pt", source=SOURCE, deadline=ctx.deadline,
                        stop_margin_seconds=1.0)
        return publish_outcome(ctx, outcome, source=SOURCE, cameras_used=4, versions={})


def test_the_control_plane_receives_a_partial_splat_when_the_budget_runs_out(harness):
    deadline = (datetime.now(UTC) + timedelta(seconds=3)).isoformat().replace("+00:00", "Z")
    order = harness.order("SPLAT_RECONSTRUCTION", [], deadline=deadline)
    started = time.time()
    report = harness.run(order, registry={"SPLAT_RECONSTRUCTION": CpuTimeBoxedSplat()})
    assert time.time() - started < 10
    assert report["status"] == "FAILED" and report["errorCode"] == "TIME_LIMIT_EXCEEDED"
    partial = [a for a in report["artifacts"] if a["kind"] == "SPLAT_PARTIAL"]
    assert len(partial) == 1 and partial[0]["partial"] is True  # what PipelineService#advance turns into a PARTIAL run
    assert {a["kind"] for a in report["artifacts"]} >= {"SPLAT_PARTIAL", "SPLAT_CHECKPOINT", "SPLAT_TRAINING_REPORT"}
    assert report["errorDetails"]["completed_iterations"] > 0
