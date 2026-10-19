"""The production rasteriser adapter (chaya_worker.splat_training.gsplat_rasterizer) on the CPU, around a STAND-IN gsplat.

The stand-in is not gsplat and renders nothing: `rasterization` returns tensors in gsplat 1.5.3's unpacked output
shapes, built from its inputs so that autograd connects them. That checks the adapter's own logic, the part that is not
gsplat: it renders with autograd off (evaluation, as tests/gpu/test_splat_acceptance.py measures PSNR), it still hands
training the screen-space gradient density control needs, and it refuses outputs of another shape. Whether gsplat's CUDA
rasteriser itself works is only shown on a GPU (tests/gpu/test_gsplat_cuda.py).
"""

from __future__ import annotations

import types

import numpy as np
import pytest

torch = pytest.importorskip("torch", reason="torch is not installed: pip install torch (CPU is enough for these tests)")

from chaya_worker import splat_training as st  # noqa: E402
from chaya_worker.splat_color import rgb_to_sh0  # noqa: E402
from tests.splat.renderer import camera, plane_points  # noqa: E402

CPU = torch.device("cpu")


def _stand_in_gsplat(*, means2d_rows: int | None = None) -> types.ModuleType:
    """A module with gsplat 1.5.3's `rasterization` signature and output shapes (renders [C, H, W, 3], means2d [C, N, 2],
    radii [C, N, 2]); `means2d_rows` returns a means2d of another size, as an incompatible gsplat would."""
    module = types.ModuleType("gsplat")
    module.__version__ = st.GSPLAT_VALIDATED_VERSION

    def rasterization(means, quats, scales, opacities, colors, viewmats, Ks, width, height, packed=True):
        assert packed is False, "the adapter relies on unpacked output"
        pc = means @ viewmats[0, :3, :3].T + viewmats[0, :3, 3]
        means2d = (pc[:, :2] / pc[:, 2:3] * Ks[0, 0, 0] + Ks[0, :2, 2])[None]
        if means2d_rows is not None:
            means2d = means2d[:, :means2d_rows]
        shade = (opacities[:, None] * colors).mean(dim=0) + 1e-3 * means2d.mean() + 0 * (quats.sum() + scales.sum())
        renders = shade.expand(1, height, width, 3)
        radii = torch.ones((1, len(means), 2), dtype=torch.int32)
        return renders, torch.ones((1, height, width, 1)), {"means2d": means2d, "radii": radii}

    module.rasterization = rasterization
    return module


@pytest.fixture
def stand_in(monkeypatch):
    def install(**kw):
        monkeypatch.setitem(__import__("sys").modules, "gsplat", _stand_in_gsplat(**kw))
        return st.gsplat_rasterizer(CPU)

    return install


def _params():
    pts = plane_points(3)
    return st.init_params(pts, rgb_to_sh0(np.full(pts.shape, 0.5, np.float32)), np.full(pts.shape, -2.0, np.float32), CPU)


def test_the_adapter_renders_with_autograd_off(stand_in):
    """Evaluation renders (no_grad, or a cloud read back from a PLY as plain tensors) must not need a gradient: the GPU
    acceptance test measures PSNR exactly like this."""
    render = stand_in()
    params = _params()
    with torch.no_grad():
        out = render(*st.render_inputs(params), camera(0.0, 0.0))
    assert out.image.shape == (24, 24, 3) and out.visible.shape == (len(params["means"]),)
    plain = {k: v.detach().clone() for k, v in params.items()}
    out = render(*st.render_inputs(plain), camera(0.0, 0.0))
    assert not out.means2d.requires_grad and out.means2d.grad is None


def test_the_adapter_still_exposes_the_screen_space_gradient_for_training(stand_in):
    render = stand_in()
    params = _params()
    out = render(*st.render_inputs(params), camera(0.0, 0.0))
    out.image.mean().backward()
    assert out.means2d.grad is not None and out.means2d.grad.reshape(-1, 2).shape == (len(params["means"]), 2)
    assert params["sh0"].grad is not None and torch.isfinite(params["sh0"].grad).all()


def test_an_output_of_another_shape_is_refused_not_trained_on(stand_in):
    render = stand_in(means2d_rows=2)
    with pytest.raises(st.GsplatContractError, match="unpacked shapes"):
        render(*st.render_inputs(_params()), camera(0.0, 0.0))
