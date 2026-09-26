"""Shared pieces for the splat training tests (torch, CPU). Imported only after the tests have checked torch exists.

`cpu_rasterizer` is a TEST RENDERER, not gsplat and not a reconstruction path. It is a small differentiable Gaussian
splatter: pinhole projection, an isotropic screen-space footprint (it ignores rotation and anisotropy), and front-to-back
alpha compositing. It exists so the training mechanics can run on a machine without a CUDA GPU: densification, pruning,
checkpoint/resume, the time budget, partial status and the colour convention. SPLAT_RECONSTRUCTION never uses it, and
nothing it renders is ever published. Whether gsplat's own rasteriser trains well is only checked on a GPU
(tests/gpu).
"""

from __future__ import annotations

import numpy as np
import torch

from chaya_worker.splat_training import RenderOutput

SIZE = 24  # image width = height, pixels
FOCAL = 24.0


def cpu_rasterizer(means, quats, scales, opacities, rgb, cam) -> RenderOutput:
    viewmat = torch.as_tensor(cam["viewmat"], dtype=torch.float32)
    K = torch.as_tensor(cam["K"], dtype=torch.float32)
    pc = means @ viewmat[:3, :3].T + viewmat[:3, 3]
    z = pc[:, 2].clamp_min(1e-3)
    means2d = torch.stack([K[0, 0] * pc[:, 0] / z + K[0, 2], K[1, 1] * pc[:, 1] / z + K[1, 2]], dim=-1)
    if means2d.requires_grad:
        means2d.retain_grad()
    sigma = (K[0, 0] * scales.mean(dim=-1) / z).clamp_min(0.3)
    visible = pc[:, 2] > 0.01
    ys, xs = torch.meshgrid(torch.arange(cam["height"], dtype=torch.float32) + 0.5,
                            torch.arange(cam["width"], dtype=torch.float32) + 0.5, indexing="ij")
    d2 = ((xs[None] - means2d[:, 0, None, None]) ** 2 + (ys[None] - means2d[:, 1, None, None]) ** 2) / sigma[:, None, None] ** 2
    alpha = (opacities[:, None, None] * torch.exp(-0.5 * d2)).clamp(max=0.99) * visible[:, None, None]
    order = torch.argsort(z)
    alpha = alpha[order]
    transmittance = torch.cumprod(torch.cat([torch.ones_like(alpha[:1]), 1 - alpha[:-1]]), dim=0)
    image = torch.einsum("nhw,nc->hwc", alpha * transmittance, rgb[order])
    return RenderOutput(image, means2d, visible & (sigma * 3 > 0.5))


def camera(cx: float, cy: float, image: np.ndarray | None = None) -> dict:
    """A camera at (cx, cy, 0) looking down +Z (world-to-camera = translation only)."""
    viewmat = np.eye(4)
    viewmat[:3, 3] = [-cx, -cy, 0.0]
    K = np.array([[FOCAL, 0, SIZE / 2], [0, FOCAL, SIZE / 2], [0, 0, 1.0]])
    cam = {"name": f"cam-{cx}-{cy}", "viewmat": viewmat, "K": K, "width": SIZE, "height": SIZE}
    if image is not None:
        cam["image"] = image
    return cam


def rig(image_fn) -> list[dict]:
    """Four cameras around the origin; image_fn(cam) gives each camera's target image (H, W, 3) in [0, 1]."""
    cams = [camera(x, y) for x, y in ((-0.3, -0.3), (0.3, -0.3), (-0.3, 0.3), (0.3, 0.3))]
    for c in cams:
        c["image"] = image_fn(c)
    return cams


def plane_points(n_side: int, depth: float = 5.0, half: float = 2.0) -> np.ndarray:
    xs, ys = np.meshgrid(np.linspace(-half, half, n_side), np.linspace(-half, half, n_side))
    return np.stack([xs.ravel(), ys.ravel(), np.full(xs.size, depth)], axis=1).astype(np.float32)
