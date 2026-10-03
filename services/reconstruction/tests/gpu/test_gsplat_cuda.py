"""CUDA validation of the gsplat training path: what the CPU tests in tests/splat cannot show.

The CPU tests use a small test renderer, so they validate the loop's mechanics (schedule, densification, checkpoints,
time box, publishing rules), not gsplat. These tests run the production rasteriser adapter
(chaya_worker.splat_training.gsplat_rasterizer) on a CUDA device:

  * the installed gsplat is the version the adapter was written against;
  * gsplat's own degree-0 SH path gives the same image as the RGB the adapter hands it (the colour convention);
  * the adapter's output contract (shapes, means2d gradient, visibility) holds;
  * a short training run on a SYNTHETIC scene (a textured plane seen by four cameras) lowers the loss, densifies, and
    resumes from a checkpoint.

The synthetic scene is not a reconstruction and proves nothing about reconstruction quality on a real venue (review
G-4). Every test SKIPS, with the reason shown by `pytest -m gpu -rs`, when torch + gsplat + CUDA are not all present;
a skip is not a pass.
"""

from __future__ import annotations

from dataclasses import replace

import numpy as np
import pytest

pytestmark = pytest.mark.gpu

from chaya_worker.toolchain import Toolchain  # noqa: E402

_tc = Toolchain()
needs_cuda_gsplat = pytest.mark.skipif(not all(_tc.status(r).available for r in ("py:torch", "py:gsplat", "cuda")),
                                       reason="torch + gsplat + a CUDA device are not all available")


def _imports():
    import gsplat
    import torch

    from chaya_worker import splat_training as st

    return gsplat, torch, st


def _checker(h: int, w: int) -> np.ndarray:
    ys, xs = np.mgrid[0:h, 0:w]
    board = ((xs // 6 + ys // 6) % 2).astype(np.float32)
    return np.stack([0.15 + 0.7 * board, 0.85 - 0.7 * board, 0.5 * np.ones_like(board)], axis=-1).astype(np.float32)


def _cams(size: int = 64, focal: float = 64.0) -> list[dict]:
    cams = []
    for cx, cy in ((-0.3, -0.3), (0.3, -0.3), (-0.3, 0.3), (0.3, 0.3)):
        viewmat = np.eye(4)
        viewmat[:3, 3] = [-cx, -cy, 0.0]
        cams.append({"name": f"cam-{cx}-{cy}", "viewmat": viewmat, "K": np.array([[focal, 0, size / 2], [0, focal, size / 2], [0, 0, 1.0]]),
                     "width": size, "height": size, "image": _checker(size, size)})
    return cams


def _plane(n: int = 24, depth: float = 5.0, half: float = 3.0) -> np.ndarray:
    xs, ys = np.meshgrid(np.linspace(-half, half, n), np.linspace(-half, half, n))
    return np.stack([xs.ravel(), ys.ravel(), np.full(xs.size, depth)], axis=1).astype(np.float32)


@needs_cuda_gsplat
def test_the_installed_gsplat_is_the_validated_version():
    gsplat, _torch, st = _imports()
    assert gsplat.__version__ == st.GSPLAT_VALIDATED_VERSION, (
        f"gsplat {gsplat.__version__} is installed; the adapter was validated against {st.GSPLAT_VALIDATED_VERSION}")


@needs_cuda_gsplat
def test_rgb_handed_to_gsplat_is_its_own_degree_0_sh_colour():
    gsplat, torch, st = _imports()
    from chaya_worker.splat_color import sh0_to_rgb

    dev = torch.device("cuda")
    g = torch.Generator().manual_seed(0)
    n = 500
    means = (torch.rand((n, 3), generator=g) * torch.tensor([4.0, 4.0, 2.0]) + torch.tensor([-2.0, -2.0, 4.0])).to(dev)
    quats = torch.nn.functional.normalize(torch.randn((n, 4), generator=g), dim=-1).to(dev)
    scales = (torch.rand((n, 3), generator=g) * 0.1 + 0.02).to(dev)
    opac = (torch.rand(n, generator=g) * 0.9 + 0.05).to(dev)
    sh0 = (torch.randn((n, 3), generator=g) * 1.5).to(dev)  # includes values whose colour is negative: the clamp matters
    cam = _cams()[0]
    viewmat = torch.as_tensor(cam["viewmat"], dtype=torch.float32, device=dev)[None]
    K = torch.as_tensor(cam["K"], dtype=torch.float32, device=dev)[None]
    rgb, _, _ = gsplat.rasterization(means, quats, scales, opac, sh0_to_rgb(sh0).clamp_min(0.0), viewmat, K, 64, 64,
                                     packed=False)
    sh, _, _ = gsplat.rasterization(means, quats, scales, opac, sh0[:, None, :], viewmat, K, 64, 64, sh_degree=0, packed=False)
    torch.testing.assert_close(rgb, sh, atol=1e-5, rtol=1e-5)


@needs_cuda_gsplat
def test_the_adapter_meets_its_contract_on_cuda():
    _gsplat, torch, st = _imports()
    from chaya_worker.splat_color import rgb_to_sh0
    from chaya_worker.stages.splat_reconstruction import initial_scale_log

    dev = torch.device("cuda")
    pts = _plane()
    params = st.init_params(pts, rgb_to_sh0(np.full(pts.shape, 0.5, np.float32)), initial_scale_log(pts), dev)
    cam = _cams()[0]
    out = st.gsplat_rasterizer(dev)(*st.render_inputs(params), cam)
    assert out.image.shape == (64, 64, 3) and out.visible.dtype == torch.bool and out.visible.shape == (len(pts),)
    out.image.mean().backward()
    assert out.means2d.grad is not None and out.means2d.grad.reshape(-1, 2).shape == (len(pts), 2)
    assert params["sh0"].grad is not None and torch.isfinite(params["sh0"].grad).all()


@needs_cuda_gsplat
def test_a_short_synthetic_training_run_on_cuda_learns_densifies_and_resumes(tmp_path):
    _gsplat, torch, st = _imports()
    from chaya_worker.splat_color import rgb_to_sh0
    from chaya_worker.stages.splat_reconstruction import initial_scale_log

    dev = torch.device("cuda")
    pts = _plane()
    cams = _cams()
    config = st.TrainingConfig(iterations=300, densify_start=50, densify_stop=250, densify_every=50, opacity_reset_every=0,
                               checkpoint_every=0, lr_position_decay_steps=300)
    source = st.source_identity(run_id="cuda-synthetic", inputs=[])

    def fresh():
        return st.init_params(pts, rgb_to_sh0(np.full(pts.shape, 0.5, np.float32)), initial_scale_log(pts), dev)

    outcome = st.train(params=fresh(), cams=cams, rasterize=st.gsplat_rasterizer(dev), config=config, loss_fn=st.l1_dssim_loss,
                       checkpoint_path=tmp_path / "full.pt", source=source)
    assert outcome.status == st.STATUS_COMPLETED and st.validate_trained_state(outcome.params) == []
    first = np.mean([h["l1"] for h in outcome.history[:20]])
    last = np.mean([h["l1"] for h in outcome.history[-20:]])
    assert last < 0.5 * first, f"L1 went from {first:.4f} to {last:.4f}"
    assert outcome.densify_events, "density control ran on gsplat's screen-space gradients"
    assert outcome.history[-1]["lr_position"] < outcome.history[0]["lr_position"]

    half = st.train(params=fresh(), cams=cams, rasterize=st.gsplat_rasterizer(dev), config=replace(config, iterations=150),
                    loss_fn=st.l1_dssim_loss, checkpoint_path=tmp_path / "half.pt", source=source)
    state = st.load_checkpoint(half.checkpoint_path, config=config, source=source, device=dev)
    resumed = st.train(params=state[1], cams=cams, rasterize=st.gsplat_rasterizer(dev), config=config, loss_fn=st.l1_dssim_loss,
                       checkpoint_path=tmp_path / "resumed.pt", source=source, resume=state)
    assert resumed.status == st.STATUS_COMPLETED and resumed.completed_iterations == 300
    # gsplat's backward uses atomic adds, so CUDA runs are not bit-reproducible: compare the outcome, not the bits
    resumed_last = np.mean([h["l1"] for h in resumed.history[-20:]])
    assert resumed_last < 0.5 * first
