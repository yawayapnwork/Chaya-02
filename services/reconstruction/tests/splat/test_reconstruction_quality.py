"""The reconstruction quality gate (chaya_worker.splat_quality, publish_outcome; gaussian-splatting-audit D-5), on CPU.

What is real: the training loop, the held-out split, the evaluation (PSNR against the frames, the SfM-seed reference,
the loss progression), the verdict and publish_outcome. What is NOT production: the rasteriser is the CPU TEST RENDERER
(tests/splat/renderer.py), and the scene is synthetic: a plane of coloured Gaussians rendered from a grid of cameras. So
these tests prove the gate's logic and its wiring, not what PSNR gsplat reaches on a real capture. That needs a CUDA GPU
and real scene data (tests/gpu/test_splat_acceptance.py with CHAYA_SPLAT_DATASET).
"""

from __future__ import annotations

import json
import logging
import math
from dataclasses import replace

import numpy as np
import pytest

torch = pytest.importorskip("torch", reason="torch is not installed: pip install torch (CPU is enough for this test)")

from chaya_worker import splat_quality as sq  # noqa: E402
from chaya_worker.contract import StageContext  # noqa: E402
from chaya_worker.runner import CommandRunner  # noqa: E402
from chaya_worker.settings import Settings  # noqa: E402
from chaya_worker.splat_color import rgb_to_sh0  # noqa: E402
from chaya_worker.splat_training import (  # noqa: E402
    CheckpointMismatch,
    TrainingConfig,
    init_params,
    l1_dssim_loss,
    load_checkpoint,
    render_inputs,
    source_identity,
    train,
)
from chaya_worker.stages.splat_reconstruction import initial_scale_log, publish_outcome  # noqa: E402
from chaya_worker.toolchain import Toolchain  # noqa: E402
from tests.splat.renderer import camera, cpu_rasterizer, plane_points  # noqa: E402

CPU = torch.device("cpu")
SOURCE = source_identity(run_id="run-quality", inputs=[])
CONFIG = TrainingConfig(iterations=60, densify_start=10**9, densify_stop=10**9, opacity_reset_every=0, checkpoint_every=0,
                        lr_color=2e-2)
POINTS = plane_points(6, half=2.5)
RENDERER = "CPU TEST RENDERER, not gsplat"


def _truth():
    """The synthetic scene: the seed's Gaussians with a checkerboard of colours (what training must learn)."""
    i, j = np.meshgrid(np.arange(6), np.arange(6))
    board = ((i + j) % 2).ravel().astype(np.float32)
    rgb = np.stack([0.15 + 0.7 * board, 0.85 - 0.7 * board, np.full_like(board, 0.4)], axis=1)
    return init_params(POINTS, rgb_to_sh0(rgb), initial_scale_log(POINTS), CPU)


def _seed():
    """The untrained SfM seed: the right positions, one grey for every Gaussian."""
    return init_params(POINTS, rgb_to_sh0(np.full(POINTS.shape, 0.5, np.float32)), initial_scale_log(POINTS), CPU)


def _views(n_side: int = 4):
    """A grid of cameras, each with the truth rendered as its frame: a held-out view sees the same scene."""
    truth = _truth()
    cams = [camera(round(x, 3), round(y, 3)) for x in np.linspace(-0.6, 0.6, n_side) for y in np.linspace(-0.6, 0.6, n_side)]
    with torch.no_grad():
        for c in cams:
            c["image"] = cpu_rasterizer(*render_inputs(truth), c).image.clamp(0, 1).numpy().astype(np.float32)
    return cams


def _train(cams, config=CONFIG, tmp_path=None, **kw):
    params = _seed()
    seed = sq.snapshot(params)
    outcome = train(params=params, cams=cams, rasterize=cpu_rasterizer, config=config, loss_fn=l1_dssim_loss,
                    checkpoint_path=tmp_path / "c.pt", source=SOURCE, **kw)
    return outcome, seed


def _evaluate(outcome, seed, eval_cams, train_cams, **thresholds):
    return sq.evaluate(params=outcome.params, seed=seed, eval_cams=eval_cams, train_cams=train_cams, rasterize=cpu_rasterizer,
                       history=outcome.history, thresholds=sq.QualityThresholds(**thresholds), renderer=RENDERER, holdout_every=8)


def _ctx(tmp_path) -> StageContext:
    work = tmp_path / "work"
    work.mkdir(exist_ok=True)
    return StageContext({"stage": "SPLAT_RECONSTRUCTION", "inputs": [], "runId": "run-quality"}, [], work, logging.getLogger("t"),
                        CommandRunner(work / "out.log", work / "err.log"), Toolchain(env={}, which=lambda _n: None), Settings(), None)


def _report(result) -> dict:
    def refuse(token):
        raise AssertionError(f"the training report is not strict JSON: {token}")

    path = next(a.path for a in result.artifacts if a.kind == "SPLAT_TRAINING_REPORT")
    return json.loads(path.read_text(encoding="utf-8"), parse_constant=refuse)


# ---- the held-out split: no evaluation view is ever trained on -------------------------------------------------------

def test_every_eighth_view_by_name_is_held_out_and_never_rendered_by_training(tmp_path):
    cams = _views()
    train_cams, eval_cams = sq.split_views(list(reversed(cams)), 8, min_training=3)
    names = sorted(c["name"] for c in cams)
    assert [c["name"] for c in eval_cams] == [names[0], names[8]], "index 0, 8, ... of the views sorted by name"
    assert len(train_cams) == 14 and not {c["name"] for c in train_cams} & {c["name"] for c in eval_cams}

    rendered = []

    def spy(*args):
        rendered.append(args[-1]["name"])
        return cpu_rasterizer(*args)

    params = _seed()
    train(params=params, cams=train_cams, rasterize=spy, config=replace(CONFIG, iterations=40), loss_fn=l1_dssim_loss,
          checkpoint_path=tmp_path / "c.pt", source=SOURCE)
    assert rendered and not set(rendered) & {c["name"] for c in eval_cams}, "a held-out frame never supervises training"


def test_nothing_is_held_out_when_too_few_views_would_remain_or_holding_out_is_off():
    cams = _views(2)  # 4 views: holding out 1 would leave 3
    assert sq.split_views(cams, 8, min_training=4) == (sorted(cams, key=lambda c: c["name"]), [])
    assert sq.split_views(cams, 0, min_training=1)[1] == []
    assert len(sq.split_views(cams, 2, min_training=1)[1]) == 2
    assert sq.holdout_problems(1) and not sq.holdout_problems(0) and not sq.holdout_problems(8)


def test_a_checkpoint_is_only_resumed_with_the_same_held_out_views(tmp_path):
    outcome, _ = _train(_views(), replace(CONFIG, iterations=5, eval_holdout_every=8), tmp_path)
    with pytest.raises(CheckpointMismatch, match="eval_holdout_every"):
        load_checkpoint(outcome.checkpoint_path, config=replace(CONFIG, iterations=5, eval_holdout_every=0), source=SOURCE,
                        device=CPU)


# ---- acceptance and rejection ------------------------------------------------------------------------------------------

def test_a_reconstruction_that_renders_unseen_views_better_than_its_seed_is_published(tmp_path):
    train_cams, eval_cams = sq.split_views(_views(), 8, min_training=3)
    outcome, seed = _train(train_cams, tmp_path=tmp_path)
    quality = _evaluate(outcome, seed, eval_cams, train_cams)

    assert quality["passed"] and quality["code"] is None and quality["reasons"] == []
    assert quality["basis"] == sq.BASIS_HELD_OUT and quality["held_out"] is True and quality["renderer"] == RENDERER
    assert quality["evaluation_views"] == [c["name"] for c in eval_cams] and quality["training_views"] == len(train_cams)
    assert quality["psnr"]["gain_db"] > 1.0, quality["psnr"]
    assert quality["training_loss"]["ratio"] < 1.0

    result = publish_outcome(_ctx(tmp_path), outcome, source=SOURCE, cameras_used=len(train_cams), versions={}, quality=quality)
    assert result.status == "SUCCEEDED"
    assert {a.kind for a in result.artifacts} == {"SPLAT", "SPLAT_CHECKPOINT", "SPLAT_TRAINING_REPORT"}
    report = _report(result)
    assert report["quality"]["passed"] is True and report["quality"]["basis"] == sq.BASIS_HELD_OUT
    assert report["quality"]["thresholds"] == {"min_psnr_gain_db": 0.0, "min_psnr_db": 0.0, "max_loss_ratio": 1.0,
                                               "require_held_out": False}


def test_a_completed_training_that_ends_worse_than_its_seed_is_refused_and_nothing_is_published(tmp_path):
    """A real failure mode, not a constructed one: an opacity reset late in training leaves a nearly transparent cloud
    that renders every view worse than the untrained seed. It completed every iteration and is a valid, exportable PLY."""
    train_cams, eval_cams = sq.split_views(_views(), 8, min_training=3)
    config = replace(CONFIG, iterations=40, opacity_reset_every=35, densify_stop=10**9)
    outcome, seed = _train(train_cams, config, tmp_path)
    assert outcome.status == "COMPLETED" and outcome.completed_iterations == 40
    quality = _evaluate(outcome, seed, eval_cams, train_cams)

    assert not quality["passed"] and quality["code"] == sq.INSUFFICIENT
    assert quality["psnr"]["gain_db"] < 0 and any("untrained SfM seed" in r for r in quality["reasons"])
    result = publish_outcome(_ctx(tmp_path), outcome, source=SOURCE, cameras_used=len(train_cams), versions={}, quality=quality)
    assert result.status == "FAILED" and result.error_code == "SPLAT_QUALITY_INSUFFICIENT"
    assert {a.kind for a in result.artifacts} == {"SPLAT_TRAINING_REPORT"}, "no SPLAT and no checkpoint of a refused cloud"
    assert "quality gate" in result.error_message and "untrained SfM seed" in result.error_message
    assert result.error_details["quality"]["reasons"] == quality["reasons"]
    assert result.error_details["psnr_gain_db"] == pytest.approx(quality["psnr"]["gain_db"])
    report = _report(result)
    assert report["validation"] == {"valid": False, "code": "SPLAT_QUALITY_INSUFFICIENT", "message": result.error_message}
    assert report["quality"]["reasons"] == quality["reasons"] and report["provenance"]["splat"]["artifact"] is None


def test_a_single_iteration_is_refused_for_insufficient_training_progress(tmp_path):
    """The audit's example: GSPLAT_ITERATIONS=1 used to be published as SPLAT. Its PSNR may move a fraction of a dB, but
    its loss cannot show progress."""
    train_cams, eval_cams = sq.split_views(_views(), 8, min_training=3)
    outcome, seed = _train(train_cams, replace(CONFIG, iterations=1), tmp_path)
    quality = _evaluate(outcome, seed, eval_cams, train_cams)
    assert quality["code"] == sq.INSUFFICIENT and quality["training_loss"]["ratio"] == 1.0
    assert any("training loss did not decrease" in r for r in quality["reasons"])
    result = publish_outcome(_ctx(tmp_path), outcome, source=SOURCE, cameras_used=len(train_cams), versions={}, quality=quality)
    assert result.error_code == "SPLAT_QUALITY_INSUFFICIENT" and "SPLAT" not in {a.kind for a in result.artifacts}


def test_a_configured_absolute_psnr_floor_and_a_stricter_gain_are_applied(tmp_path):
    train_cams, eval_cams = sq.split_views(_views(), 8, min_training=3)
    outcome, seed = _train(train_cams, tmp_path=tmp_path)
    measured = _evaluate(outcome, seed, eval_cams, train_cams)["psnr"]
    floor = _evaluate(outcome, seed, eval_cams, train_cams, min_psnr_db=measured["trained_mean_db"] + 1)
    gain = _evaluate(outcome, seed, eval_cams, train_cams, min_psnr_gain_db=measured["gain_db"] + 1)
    loss = _evaluate(outcome, seed, eval_cams, train_cams, max_loss_ratio=0.01)
    assert floor["code"] == gain["code"] == loss["code"] == sq.INSUFFICIENT
    assert "configured floor" in floor["reasons"][0] and "required" in gain["reasons"][0] and "loss" in loss["reasons"][0]


# ---- no measurement is never a pass -------------------------------------------------------------------------------------

def test_a_completed_training_without_an_evaluation_is_unverified_not_published(tmp_path):
    outcome, _ = _train(_views(), tmp_path=tmp_path)
    result = publish_outcome(_ctx(tmp_path), outcome, source=SOURCE, cameras_used=16, versions={}, quality=None)
    assert result.status == "FAILED" and result.error_code == "SPLAT_QUALITY_UNVERIFIED"
    assert {a.kind for a in result.artifacts} == {"SPLAT_TRAINING_REPORT"}
    assert _report(result)["quality"] == {"passed": None, "reason": "not evaluated"}


def test_held_out_views_without_a_valid_pixel_leave_the_quality_unverified(tmp_path):
    train_cams, eval_cams = sq.split_views(_views(), 8, min_training=3)
    outcome, seed = _train(train_cams, tmp_path=tmp_path)
    masked = [{**c, "valid": np.zeros((c["height"], c["width"]), bool)} for c in eval_cams]  # every pixel privacy-masked
    quality = _evaluate(outcome, seed, masked, train_cams)
    assert quality["code"] == sq.UNVERIFIED and quality["psnr"]["views"] == 0
    assert quality["psnr"]["views_without_valid_pixels"] == [c["name"] for c in eval_cams]
    assert "valid (unmasked) pixel" in quality["reasons"][0]


def test_with_too_few_views_to_hold_out_the_training_views_are_measured_and_labelled_so(tmp_path):
    cams = _views(2)
    train_cams, eval_cams = sq.split_views(cams, 8, min_training=4)
    outcome, seed = _train(train_cams, tmp_path=tmp_path)
    quality = _evaluate(outcome, seed, eval_cams, train_cams)
    assert quality["passed"] and quality["basis"] == sq.BASIS_TRAINING and quality["held_out"] is False
    assert "too few views" in quality["fallback_reason"]

    strict = _evaluate(outcome, seed, eval_cams, train_cams, require_held_out=True)
    assert strict["code"] == sq.UNVERIFIED and "gsplat_quality_require_held_out" in strict["reasons"][0]


def test_non_finite_renders_and_losses_are_unverified_and_reported_as_strict_json(tmp_path):
    train_cams, eval_cams = sq.split_views(_views(), 8, min_training=3)
    outcome, seed = _train(train_cams, tmp_path=tmp_path)
    broken = replace(outcome, params={**outcome.params, "sh0": torch.full_like(outcome.params["sh0"], float("nan"))})
    quality = _evaluate(broken, seed, eval_cams, train_cams)
    assert quality["code"] == sq.UNVERIFIED and any("not finite" in r for r in quality["reasons"])
    assert quality["psnr"]["trained_mean_db"] is None, "NaN is reported as null, never as a number"
    json.dumps(quality, allow_nan=False)

    nan_loss = replace(outcome, history=[{**h, "loss": float("nan")} for h in outcome.history])
    assert _evaluate(nan_loss, seed, eval_cams, train_cams)["code"] == sq.UNVERIFIED
    assert _evaluate(replace(outcome, history=[]), seed, eval_cams, train_cams)["code"] == sq.UNVERIFIED


# ---- partial results keep their meaning ---------------------------------------------------------------------------------

def test_a_partial_result_stays_partial_whatever_its_measurement(tmp_path):
    train_cams, eval_cams = sq.split_views(_views(), 8, min_training=3)
    clock = iter(range(1_000, 100_000))
    outcome, seed = _train(train_cams, replace(CONFIG, iterations=1000), tmp_path, deadline=1_006, stop_margin_seconds=2,
                           clock=lambda: float(next(clock)))
    assert outcome.status == "PARTIAL" and outcome.completed_iterations >= 1
    quality = _evaluate(outcome, seed, eval_cams, train_cams, min_psnr_gain_db=100.0)  # certain to fail
    assert not quality["passed"]
    result = publish_outcome(_ctx(tmp_path), outcome, source=SOURCE, cameras_used=len(train_cams), versions={}, quality=quality)
    assert result.status == "FAILED" and result.error_code == "TIME_LIMIT_EXCEEDED", "a partial is never relabelled as a quality failure"
    kinds = {a.kind: a for a in result.artifacts}
    assert kinds["SPLAT_PARTIAL"].partial and "SPLAT" not in kinds and "SPLAT_CHECKPOINT" in kinds, "the resumable state is kept"
    assert _report(result)["quality"]["passed"] is False, "the measurement is recorded"


# ---- the pieces --------------------------------------------------------------------------------------------------------

def test_psnr_counts_only_valid_pixels():
    gt = np.zeros((4, 4, 3), np.float32)
    pred = gt.copy()
    pred[:2] = 1.0  # wrong only where the privacy mask is
    valid = np.ones((4, 4), bool)
    valid[:2] = False
    assert sq.masked_psnr(pred, gt, valid) == sq.PSNR_CAP_DB
    assert sq.masked_psnr(pred, gt, None) == pytest.approx(10 * math.log10(1 / 0.5))
    assert sq.masked_psnr(pred, gt, np.zeros((4, 4), bool)) is None
    assert math.isnan(sq.masked_psnr(np.full_like(gt, np.nan), gt, None))


def test_loss_progress_compares_the_first_and_last_tenth():
    history = [{"loss": 1.0}] * 10 + [{"loss": 0.5}] * 80 + [{"loss": 0.25}] * 10
    assert sq.loss_progress(history) == {"iterations": 100, "window": 10, "initial_mean": 1.0, "final_mean": 0.25, "ratio": 0.25}
    assert sq.loss_progress([{"loss": 0.3}])["ratio"] == 1.0
    assert sq.loss_progress([])["ratio"] is None


def test_thresholds_come_from_the_environment_and_meaningless_ones_are_refused():
    s = Settings.from_env({"GSPLAT_EVAL_HOLDOUT_EVERY": "10", "GSPLAT_QUALITY_MIN_PSNR_GAIN_DB": "1.5",
                           "GSPLAT_QUALITY_MIN_PSNR_DB": "18", "GSPLAT_QUALITY_MAX_LOSS_RATIO": "0.8",
                           "GSPLAT_QUALITY_REQUIRE_HELD_OUT": "true"})
    assert TrainingConfig.from_settings(s).eval_holdout_every == 10
    assert sq.QualityThresholds.from_settings(s) == sq.QualityThresholds(1.5, 18.0, 0.8, True)
    d = Settings()
    assert (d.gsplat_eval_holdout_every, sq.QualityThresholds.from_settings(d)) == (8, sq.QualityThresholds())
    assert len(sq.QualityThresholds(min_psnr_gain_db=float("nan"), min_psnr_db=-1, max_loss_ratio=0).problems()) == 3
    assert sq.QualityThresholds().problems() == []


def test_the_seed_waits_in_host_memory_and_is_rendered_on_the_training_device():
    """The SfM seed is only rendered after training, so it holds no training-device memory meanwhile; evaluate() renders
    it where the trained cloud lives. The `meta` device stands in for CUDA: a CPU tensor reaching the rasteriser fails."""
    from chaya_worker.splat_training import RenderOutput

    seed = sq.snapshot(_seed())
    assert {v.device.type for v in seed.values()} == {"cpu"} and all(not v.requires_grad for v in seed.values())
    trained = {k: v.detach().to("meta") for k, v in _seed().items()}
    cams = _views(2)[:1]
    seen = []

    def spy(*args):
        seen.append({a.device.type for a in args if torch.is_tensor(a)})
        return RenderOutput(torch.as_tensor(cams[0]["image"]), None, None)

    sq.evaluate(params=trained, seed=seed, eval_cams=cams, train_cams=cams, rasterize=spy, history=[{"loss": 1.0}, {"loss": 0.5}],
                thresholds=sq.QualityThresholds(), renderer=RENDERER, holdout_every=8)
    assert seen == [{"meta"}, {"meta"}], "the trained cloud and the seed are both rendered on the training device"
