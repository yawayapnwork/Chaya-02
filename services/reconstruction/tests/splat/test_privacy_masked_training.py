"""Review G-2's required test: privacy fills are not trained as scene content. CPU test renderer (tests/splat/renderer.py),
so this is about what the loss lets through, not reconstruction quality."""

from __future__ import annotations

from dataclasses import replace

import numpy as np
import pytest

torch = pytest.importorskip("torch", reason="torch is not installed: pip install torch (CPU is enough for these tests)")

from chaya_worker.splat_color import rgb_to_sh0
from chaya_worker.splat_training import (
    TrainingConfig,
    init_params,
    l1_dssim_loss,
    render_inputs,
    source_identity,
    ssim_support,
    train,
)
from chaya_worker.stages.splat_reconstruction import initial_scale_log
from tests.splat.renderer import SIZE, camera, cpu_rasterizer, plane_points

CPU = torch.device("cpu")
SOURCE = source_identity(run_id="run-g2", inputs=[])
CONFIG = TrainingConfig(iterations=300, lr_color=2e-2, densify_start=10**9, densify_stop=10**9, opacity_reset_every=0,
                        checkpoint_every=0)
SCENE = np.array([0.25, 0.55, 0.30], np.float32)  # the wall's true colour
FILL = np.array([1.0, 0.0, 1.0], np.float32)  # a solid privacy fill


def _views():
    """Three views of a flat wall. In the first two, someone stood in front of the same patch of wall, so their frames
    have a solid fill over it, as PRIVACY_PREPROCESS leaves it; the third (b) saw the wall clear. A fill in a single view
    is outvoted by an L1 loss anyway; a fill that dominates the views of a patch is what turns into scene content."""
    a, c, b = camera(-0.3, 0.0), camera(0.3, 0.0), camera(0.0, 0.3)
    for v in (a, c, b):
        v["image"] = np.broadcast_to(SCENE, (SIZE, SIZE, 3)).copy()
    for v in (a, c):
        v["valid"] = np.ones((SIZE, SIZE), bool)
        v["valid"][6:18, 6:18] = False
        v["image"][~v["valid"]] = FILL
    return a, c, b


def _train(cams, tmp_path):
    points = plane_points(20, half=3.2)  # fine enough that single Gaussians fall inside the fill
    params = init_params(points, rgb_to_sh0(np.full(points.shape, 0.5, np.float32)), initial_scale_log(points), CPU)
    outcome = train(params=params, cams=cams, rasterize=cpu_rasterizer, config=CONFIG, loss_fn=l1_dssim_loss,
                    checkpoint_path=tmp_path / "c.pt", source=SOURCE)
    return outcome.params


def _fill_likeness(image: np.ndarray) -> float:
    """How far the render's most fill-like pixel moved from the scene colour towards the fill colour (0 = none, 1 = all
    the way)."""
    toward = (image.reshape(-1, 3) - SCENE) @ (FILL - SCENE) / np.dot(FILL - SCENE, FILL - SCENE)
    return float(toward.max())


def test_a_masked_fill_does_not_appear_in_another_view(tmp_path):
    a, c, b = _views()
    params = _train([a, c, b], tmp_path)
    with torch.no_grad():
        render_b = cpu_rasterizer(*render_inputs(params), b).image.numpy()
        render_a = cpu_rasterizer(*render_inputs(params), a).image.numpy()
    assert _fill_likeness(render_b) < 0.02, "the fill colour appears in the other view"
    assert _fill_likeness(render_a) < 0.02, "the fill colour was learned even in its own view"
    np.testing.assert_allclose(render_b[6:18, 6:18].reshape(-1, 3).mean(axis=0), SCENE, atol=0.03)


def test_control_without_the_mask_the_fill_is_learned(tmp_path):
    """The same scene with the mask dropped (the behaviour before G-2 was fixed): the fill leaks into the other view, so
    the test above would catch a regression."""
    a, c, b = _views()
    del a["valid"], c["valid"]
    params = _train([a, c, b], tmp_path)
    with torch.no_grad():
        render_b = cpu_rasterizer(*render_inputs(params), b).image.numpy()
    assert _fill_likeness(render_b) > 0.1


def test_masked_ground_truth_has_no_influence_on_loss_or_gradient():
    rng = np.random.default_rng(0)
    pred = torch.tensor(rng.random((SIZE, SIZE, 3), dtype=np.float32), requires_grad=True)
    gt = rng.random((SIZE, SIZE, 3), dtype=np.float32)
    valid = np.ones((SIZE, SIZE), bool)
    valid[4:12, 10:20] = False
    other = gt.copy()
    other[~valid] = rng.random((int((~valid).sum()), 3), dtype=np.float32)
    results = []
    for target in (gt, other):
        pred.grad = None
        loss, _ = l1_dssim_loss(pred, torch.tensor(target), CONFIG, valid=torch.tensor(valid))
        loss.backward()
        results.append((loss.item(), pred.grad.clone()))
    assert results[0][0] == results[1][0]
    assert torch.equal(results[0][1], results[1][1])
    assert results[0][1][torch.tensor(~valid)].abs().max() == 0, "a masked pixel received a gradient"


def test_an_all_valid_mask_is_the_unmasked_loss():
    rng = np.random.default_rng(1)
    pred, gt = torch.tensor(rng.random((SIZE, SIZE, 3), dtype=np.float32)), torch.tensor(rng.random((SIZE, SIZE, 3), dtype=np.float32))
    plain, _ = l1_dssim_loss(pred, gt, CONFIG)
    masked, _ = l1_dssim_loss(pred, gt, CONFIG, valid=torch.ones((SIZE, SIZE), dtype=torch.bool))
    assert masked.item() == pytest.approx(plain.item(), rel=1e-6)


def test_a_fully_masked_view_contributes_nothing():
    pred = torch.rand((SIZE, SIZE, 3), requires_grad=True)
    loss, _ = l1_dssim_loss(pred, torch.rand((SIZE, SIZE, 3)), CONFIG, valid=torch.zeros((SIZE, SIZE), dtype=torch.bool))
    loss.backward()
    assert loss.item() == 0 and pred.grad.abs().max() == 0


def test_ssim_windows_touching_a_masked_pixel_are_excluded():
    valid = torch.ones((SIZE, SIZE), dtype=torch.bool)
    valid[12, 12] = False
    support = ssim_support(valid)
    assert not support[7:18, 7:18].any() and support[:7].all() and support[18:].all()


def test_training_uses_the_mask_from_the_camera(tmp_path):
    """train() passes a camera's `valid` to the loss: with every pixel of every view masked, nothing is learned."""
    a, c, b = _views()
    for v in (a, c, b):
        v["valid"] = np.zeros((SIZE, SIZE), bool)
    points = plane_points(8, half=3.2)
    params = init_params(points, rgb_to_sh0(np.full(points.shape, 0.5, np.float32)), initial_scale_log(points), CPU)
    before = {k: v.detach().clone() for k, v in params.items()}
    out = train(params=params, cams=[a, c, b], rasterize=cpu_rasterizer, config=replace(CONFIG, iterations=20),
                loss_fn=l1_dssim_loss, checkpoint_path=tmp_path / "c.pt", source=SOURCE)
    for k, v in before.items():
        assert torch.equal(out.params[k].detach(), v), k
