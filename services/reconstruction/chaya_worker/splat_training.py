"""The Gaussian splat optimiser behind SPLAT_RECONSTRUCTION: parameters, adaptive density control, checkpoints and the
time-boxed training loop.

This module needs torch, and nothing here depends on gsplat or CUDA. The loop takes the rasteriser as an argument.
SPLAT_RECONSTRUCTION passes gsplat's CUDA rasteriser (`gsplat_rasterizer`); the tests pass a small CPU renderer. That
keeps densification, pruning, checkpointing and the time budget testable on a machine with no GPU. It never makes a
CPU "reconstruction": the stage itself still requires gsplat on CUDA.

Parameters (N Gaussians, all float32, all optimised in unconstrained space exactly as stored in the PLY):
    means (N, 3)          position, reconstruction frame
    scales_log (N, 3)     log of the per-axis standard deviation; the rasteriser gets exp()
    quats (N, 4)          rotation, wxyz, unnormalised; the rasteriser gets the normalised quaternion
    opacity_logit (N,)    logit of opacity; the rasteriser gets sigmoid()
    sh0 (N, 3)            degree-0 SH colour; the rasteriser gets SH_C0 * sh0 + 0.5, clamped at 0 (splat_color)

Adaptive density control follows the 3D Gaussian Splatting paper (Kerbl et al. 2023, section 5.2). gsplat's
DefaultStrategy follows it too, and this module uses the same NDC gradient normalisation:
    * accumulate the norm of each visible Gaussian's screen-space position gradient;
    * every `densify_every` iterations in [densify_start, densify_stop), for Gaussians whose average gradient exceeds
      `densify_grad_threshold`:
        - clone the small ones (max scale <= percent_dense * scene extent);
        - split the large ones into two, sampled from the parent, with scales divided by 1.6;
    * prune Gaussians below `prune_opacity`, and, after the first opacity reset, those larger than
      `prune_scale_fraction` * scene extent;
    * every `opacity_reset_every` iterations, clamp opacities down to 0.01 so floaters must earn their opacity back;
    * `max_gaussians` caps growth: when there are more candidates than room, the highest-gradient candidates win.
Adam moments of new Gaussians start at zero; those of removed Gaussians are dropped.

Randomness (which camera, where a split child lands) is derived from (seed, iteration) alone. So a run resumed from a
checkpoint takes exactly the steps an uninterrupted run would have taken, without saving RNG state.
"""

from __future__ import annotations

import hashlib
import json
import math
import time
from collections.abc import Callable, Sequence
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, NamedTuple

import numpy as np
import torch

from .ply import GaussianCloud
from .splat_color import sh0_to_rgb

PARAM_NAMES = ("means", "scales_log", "quats", "opacity_logit", "sh0")
CHECKPOINT_FORMAT = "chaya-splat-checkpoint/1"

STATUS_COMPLETED = "COMPLETED"  # every configured iteration ran
STATUS_PARTIAL = "PARTIAL"  # stopped early by the time budget; the cloud is usable but not the configured result


@dataclass(frozen=True)
class TrainingConfig:
    iterations: int = 7000
    lr_position: float = 1.6e-4
    lr_scale: float = 5e-3
    lr_rotation: float = 1e-3
    lr_opacity: float = 5e-2
    lr_color: float = 2.5e-3
    ssim_weight: float = 0.2
    densify_start: int = 500
    densify_stop: int = 3500
    densify_every: int = 100
    densify_grad_threshold: float = 2e-4
    percent_dense: float = 0.01
    prune_opacity: float = 0.005
    prune_scale_fraction: float = 0.1
    opacity_reset_every: int = 3000
    max_gaussians: int = 3_000_000
    checkpoint_every: int = 1000
    seed: int = 0

    @classmethod
    def from_settings(cls, s: Any) -> TrainingConfig:
        return cls(iterations=s.gsplat_iterations, lr_position=s.gsplat_lr_position, lr_scale=s.gsplat_lr_scale,
                   lr_rotation=s.gsplat_lr_rotation, lr_opacity=s.gsplat_lr_opacity, lr_color=s.gsplat_lr_color,
                   ssim_weight=s.gsplat_ssim_weight, densify_start=s.gsplat_densify_start, densify_stop=s.gsplat_densify_stop,
                   densify_every=s.gsplat_densify_every, densify_grad_threshold=s.gsplat_densify_grad_threshold,
                   percent_dense=s.gsplat_percent_dense, prune_opacity=s.gsplat_prune_opacity,
                   prune_scale_fraction=s.gsplat_prune_scale_fraction, opacity_reset_every=s.gsplat_opacity_reset_every,
                   max_gaussians=s.gsplat_max_gaussians, checkpoint_every=s.gsplat_checkpoint_every, seed=s.gsplat_seed)

    def as_dict(self) -> dict[str, Any]:
        return asdict(self)

    def densifies_at(self, iteration: int) -> bool:
        """Adaptive density control runs after `iteration` (0-based) completes."""
        step = iteration + 1
        return self.densify_start <= step < self.densify_stop and step % self.densify_every == 0

    def resets_opacity_at(self, iteration: int) -> bool:
        step = iteration + 1
        return self.opacity_reset_every > 0 and step < self.densify_stop and step % self.opacity_reset_every == 0


# ---- parameters and optimiser ----------------------------------------------------------------------------------------


def init_params(xyz: np.ndarray, sh0: np.ndarray, scales_log: np.ndarray, device: torch.device) -> dict[str, torch.nn.Parameter]:
    """The standard 3DGS initialisation: SfM positions and colours, isotropic scales, identity rotations, opacity 0.1."""
    n = len(xyz)
    return {
        "means": torch.nn.Parameter(torch.tensor(np.asarray(xyz), dtype=torch.float32, device=device)),
        "scales_log": torch.nn.Parameter(torch.tensor(np.asarray(scales_log), dtype=torch.float32, device=device)),
        "quats": torch.nn.Parameter(torch.tensor(np.tile([1.0, 0.0, 0.0, 0.0], (n, 1)), dtype=torch.float32, device=device)),
        "opacity_logit": torch.nn.Parameter(torch.full((n,), math.log(0.1 / 0.9), dtype=torch.float32, device=device)),
        "sh0": torch.nn.Parameter(torch.tensor(np.asarray(sh0), dtype=torch.float32, device=device)),
    }


def make_optimizer(params: dict[str, torch.nn.Parameter], config: TrainingConfig, scene_extent: float) -> torch.optim.Adam:
    """One Adam param group per parameter (named), so density control can edit each group's state. The position
    learning rate is scaled by the scene extent, as in 3DGS."""
    lrs = {"means": config.lr_position * scene_extent, "scales_log": config.lr_scale, "quats": config.lr_rotation,
           "opacity_logit": config.lr_opacity, "sh0": config.lr_color}
    return torch.optim.Adam([{"params": [params[k]], "lr": lrs[k], "name": k} for k in PARAM_NAMES], eps=1e-15)


def _group(optimizer: torch.optim.Optimizer, name: str) -> dict:
    return next(g for g in optimizer.param_groups if g.get("name") == name)


def _replace_param(params: dict, optimizer: torch.optim.Optimizer, name: str, new_value: torch.Tensor,
                   state_fn: Callable[[torch.Tensor], torch.Tensor]) -> None:
    """Swap one parameter for `new_value`, carrying its Adam moments through `state_fn` (select rows, append zeros)."""
    old = params[name]
    new = torch.nn.Parameter(new_value.detach().contiguous())
    group = _group(optimizer, name)
    state = optimizer.state.pop(old, None)
    if state:
        for key in ("exp_avg", "exp_avg_sq"):
            state[key] = state_fn(state[key])
        optimizer.state[new] = state
    group["params"] = [new]
    params[name] = new


def select_rows(params: dict, optimizer: torch.optim.Optimizer, keep: torch.Tensor) -> None:
    for name in PARAM_NAMES:
        _replace_param(params, optimizer, name, params[name][keep], lambda s: s[keep])  # noqa: B023 - applied immediately


def append_rows(params: dict, optimizer: torch.optim.Optimizer, new_rows: dict[str, torch.Tensor]) -> None:
    for name in PARAM_NAMES:
        extra = new_rows[name]
        _replace_param(params, optimizer, name, torch.cat([params[name].data, extra]),
                       lambda s: torch.cat([s, torch.zeros((len(extra), *s.shape[1:]), dtype=s.dtype, device=s.device)]))  # noqa: B023


def quat_to_rotmat(q: torch.Tensor) -> torch.Tensor:
    q = q / q.norm(dim=-1, keepdim=True)
    w, x, y, z = q.unbind(-1)
    return torch.stack([
        1 - 2 * (y * y + z * z), 2 * (x * y - w * z), 2 * (x * z + w * y),
        2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x),
        2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y)], dim=-1).reshape(q.shape[:-1] + (3, 3))


def render_inputs(params: dict) -> tuple[torch.Tensor, ...]:
    """What the rasteriser receives: constrained values derived from the unconstrained parameters."""
    return (params["means"], params["quats"] / params["quats"].norm(dim=-1, keepdim=True), torch.exp(params["scales_log"]),
            torch.sigmoid(params["opacity_logit"]), sh0_to_rgb(params["sh0"]).clamp_min(0.0))


def to_cloud(params: dict) -> GaussianCloud:
    return GaussianCloud(positions=params["means"].detach().cpu().numpy().astype(np.float32),
                         scales_log=params["scales_log"].detach().cpu().numpy().astype(np.float32),
                         rotations_wxyz=params["quats"].detach().cpu().numpy().astype(np.float32),
                         opacity_logit=params["opacity_logit"].detach().cpu().numpy().astype(np.float32),
                         colors_dc=params["sh0"].detach().cpu().numpy().astype(np.float32))


# ---- adaptive density control ------------------------------------------------------------------------------------------


@dataclass
class DensifyEvent:
    iteration: int
    before: int
    cloned: int
    split: int
    pruned_opacity: int
    pruned_scale: int
    capped: int  # candidates not densified because of max_gaussians
    after: int


@dataclass
class DensityControl:
    """Screen-space gradient statistics since the last densification, one entry per Gaussian."""

    grad2d: torch.Tensor
    count: torch.Tensor
    events: list[DensifyEvent] = field(default_factory=list)
    resets: list[int] = field(default_factory=list)

    @classmethod
    def empty(cls, n: int, device: torch.device) -> DensityControl:
        return cls(torch.zeros(n, device=device), torch.zeros(n, device=device))

    def accumulate(self, means2d_grad: torch.Tensor, visible: torch.Tensor, width: int, height: int) -> None:
        """Add this view's screen-space gradient norms, converted from pixels to NDC as gsplat's DefaultStrategy does
        (so the 3DGS threshold 2e-4 means the same thing)."""
        g = means2d_grad.detach().clone()
        g[:, 0] *= width / 2.0
        g[:, 1] *= height / 2.0
        ids = torch.nonzero(visible, as_tuple=False).squeeze(-1)
        self.grad2d.index_add_(0, ids, g[ids].norm(dim=-1))
        self.count.index_add_(0, ids, torch.ones_like(ids, dtype=self.count.dtype))

    def densify(self, iteration: int, params: dict, optimizer: torch.optim.Optimizer, config: TrainingConfig,
                scene_extent: float) -> DensifyEvent:
        n = len(params["means"])
        device = params["means"].device
        avg = self.grad2d / self.count.clamp_min(1.0)
        max_scale = torch.exp(params["scales_log"].detach()).max(dim=-1).values
        candidates = avg > config.densify_grad_threshold
        room = max(0, config.max_gaussians - n)
        capped = 0
        if int(candidates.sum()) > room:  # each clone or split adds one Gaussian net
            ranked = torch.argsort(torch.where(candidates, avg, torch.full_like(avg, -1.0)), descending=True)
            keep = torch.zeros(n, dtype=torch.bool, device=device)
            keep[ranked[:room]] = True
            capped = int(candidates.sum()) - room
            candidates &= keep
        small = max_scale <= config.percent_dense * scene_extent
        clone, split = candidates & small, candidates & ~small

        gen = torch.Generator(device="cpu").manual_seed(config.seed * 1_000_003 + iteration)
        new: dict[str, list[torch.Tensor]] = {k: [] for k in PARAM_NAMES}
        if clone.any():
            for k in PARAM_NAMES:
                new[k].append(params[k].detach()[clone])
        n_split = int(split.sum())
        if n_split:
            scales = torch.exp(params["scales_log"].detach()[split])
            rot = quat_to_rotmat(params["quats"].detach()[split])
            for _ in range(2):
                offsets = torch.randn((n_split, 3), generator=gen).to(device) * scales
                new["means"].append(params["means"].detach()[split] + torch.einsum("nij,nj->ni", rot, offsets))
                new["scales_log"].append(torch.log(scales / 1.6))
                for k in ("quats", "opacity_logit", "sh0"):
                    new[k].append(params[k].detach()[split])
        if clone.any() or n_split:
            append_rows(params, optimizer, {k: torch.cat(v) for k, v in new.items()})
            # the split parents are replaced by their children
            keep = torch.cat([~split, torch.ones(len(params["means"]) - n, dtype=torch.bool, device=device)])
            select_rows(params, optimizer, keep)

        opacity = torch.sigmoid(params["opacity_logit"].detach())
        low_opacity = opacity < config.prune_opacity
        too_big = torch.zeros_like(low_opacity)
        if config.opacity_reset_every > 0 and iteration + 1 > config.opacity_reset_every:
            too_big = torch.exp(params["scales_log"].detach()).max(dim=-1).values > config.prune_scale_fraction * scene_extent
        prune = low_opacity | too_big
        if prune.any():
            select_rows(params, optimizer, ~prune)

        event = DensifyEvent(iteration + 1, n, int(clone.sum()), n_split, int(low_opacity.sum()), int((too_big & ~low_opacity).sum()),
                             capped, len(params["means"]))
        self.events.append(event)
        self.grad2d = torch.zeros(len(params["means"]), device=device)
        self.count = torch.zeros(len(params["means"]), device=device)
        return event

    def reset_opacity(self, iteration: int, params: dict, optimizer: torch.optim.Optimizer) -> None:
        cap = math.log(0.01 / 0.99)
        _replace_param(params, optimizer, "opacity_logit", params["opacity_logit"].detach().clamp_max(cap), torch.zeros_like)
        self.resets.append(iteration + 1)


# ---- checkpoints -------------------------------------------------------------------------------------------------------


def source_identity(**parts: Any) -> dict[str, Any]:
    """What a checkpoint is a checkpoint *of*: the run and the exact input artifacts (by checksum). A checkpoint is only
    resumed against the same identity."""
    canonical = json.dumps(parts, sort_keys=True, default=str)
    return {**parts, "digest": hashlib.sha256(canonical.encode("utf-8")).hexdigest()}


def save_checkpoint(path: Path, *, iteration: int, params: dict, optimizer: torch.optim.Optimizer, density: DensityControl,
                    config: TrainingConfig, source: dict[str, Any], scene_extent: float, history: list[dict[str, Any]]) -> Path:
    """Everything a resume needs, as plain tensors (no pickled classes): completed iterations, parameters, Adam moments and
    step counts per parameter, the density-control accumulators, the config, the source identity and the history."""
    optim_state = {}
    for name in PARAM_NAMES:
        st = optimizer.state.get(params[name], {})
        optim_state[name] = {k: (v.detach().cpu() if torch.is_tensor(v) else v) for k, v in st.items()}
    payload = {
        "format": CHECKPOINT_FORMAT, "iteration": iteration, "config": config.as_dict(), "source": source,
        "scene_extent": scene_extent, "params": {k: params[k].detach().cpu() for k in PARAM_NAMES},
        "optimizer": optim_state, "lrs": {g["name"]: g["lr"] for g in optimizer.param_groups},
        "density": {"grad2d": density.grad2d.cpu(), "count": density.count.cpu(),
                    "events": [asdict(e) for e in density.events], "resets": density.resets},
        "history": history,
    }
    tmp = path.with_suffix(path.suffix + ".tmp")
    torch.save(payload, tmp)
    tmp.replace(path)  # atomic: a crash mid-write never leaves a truncated checkpoint in place
    return path


class CheckpointMismatch(ValueError):
    """The checkpoint is not of this training job (different source or incompatible configuration)."""


# Settings a resumed run may change: how long to train and how often to checkpoint. Changing any other setting would make
# the resumed run a different optimisation than the one the checkpoint belongs to.
RESUMABLE_CONFIG_CHANGES = {"iterations", "checkpoint_every"}


def load_checkpoint(path: Path, *, config: TrainingConfig, source: dict[str, Any], device: torch.device):
    payload = torch.load(path, map_location="cpu", weights_only=True)
    if payload.get("format") != CHECKPOINT_FORMAT:
        raise CheckpointMismatch(f"{path.name} is not a {CHECKPOINT_FORMAT} checkpoint")
    if payload["source"].get("digest") != source.get("digest"):
        raise CheckpointMismatch("the checkpoint was trained from different inputs", )
    differing = {k for k, v in config.as_dict().items() if payload["config"].get(k) != v} - RESUMABLE_CONFIG_CHANGES
    if differing:
        raise CheckpointMismatch(f"the checkpoint was trained with different settings: {sorted(differing)}")
    params = {k: torch.nn.Parameter(payload["params"][k].to(device)) for k in PARAM_NAMES}
    optimizer = make_optimizer(params, config, payload["scene_extent"])
    for g in optimizer.param_groups:
        g["lr"] = payload["lrs"][g["name"]]
    for name in PARAM_NAMES:
        st = payload["optimizer"][name]
        if st:
            optimizer.state[params[name]] = {k: (v.to(device) if torch.is_tensor(v) else v) for k, v in st.items()}
    d = payload["density"]
    density = DensityControl(d["grad2d"].to(device), d["count"].to(device), [DensifyEvent(**e) for e in d["events"]], list(d["resets"]))
    return payload["iteration"], params, optimizer, density, payload["scene_extent"], list(payload["history"])


# ---- the loop ------------------------------------------------------------------------------------------------------------


class RenderOutput(NamedTuple):
    image: torch.Tensor  # (H, W, 3) in [0, 1]
    means2d: torch.Tensor  # (N, 2) pixel positions, part of the graph, with retain_grad() called
    visible: torch.Tensor  # (N,) bool


Rasterizer = Callable[[torch.Tensor, torch.Tensor, torch.Tensor, torch.Tensor, torch.Tensor, dict[str, Any]], RenderOutput]


def gsplat_rasterizer(device: torch.device) -> Rasterizer:
    """gsplat's CUDA rasteriser, the production renderer. Unpacked output, so means2d is (1, N, 2) and its gradient
    lines up with the Gaussians."""
    from gsplat import rasterization  # noqa: PLC0415

    def render(means, quats, scales, opacities, rgb, cam):
        viewmat = torch.as_tensor(cam["viewmat"], device=device, dtype=torch.float32)[None]
        K = torch.as_tensor(cam["K"], device=device, dtype=torch.float32)[None]
        renders, _alphas, meta = rasterization(means, quats, scales, opacities, rgb, viewmat, K, cam["width"], cam["height"],
                                               packed=False)
        means2d = meta["means2d"]
        means2d.retain_grad()
        radii = meta["radii"]
        visible = (radii > 0).all(dim=-1) if radii.dim() == 3 else radii > 0
        return RenderOutput(renders[0], means2d, visible[0])

    return render


@dataclass
class TrainingOutcome:
    status: str  # STATUS_COMPLETED | STATUS_PARTIAL
    completed_iterations: int
    target_iterations: int
    resumed_from_iteration: int
    gaussian_count: int
    initial_gaussian_count: int
    stop_reason: str
    history: list[dict[str, Any]]
    densify_events: list[DensifyEvent]
    opacity_resets: list[int]
    checkpoint_path: Path | None
    params: dict = field(repr=False, default_factory=dict)


def scene_extent_of(cams: Sequence[dict[str, Any]]) -> float:
    """1.1 x the largest camera-centre distance from their mean (the 3DGS `cameras_extent`)."""
    centers = np.array([-np.asarray(c["viewmat"])[:3, :3].T @ np.asarray(c["viewmat"])[:3, 3] for c in cams])
    return float(max(1.1 * np.linalg.norm(centers - centers.mean(axis=0), axis=1).max(), 1e-6))


def train(*, params: dict, cams: Sequence[dict[str, Any]], rasterize: Rasterizer, config: TrainingConfig, loss_fn: Callable,
          checkpoint_path: Path, source: dict[str, Any], deadline: float | None = None, stop_margin_seconds: float = 0.0,
          clock: Callable[[], float] = time.time, is_cancelled: Callable[[], bool] = lambda: False,
          resume: tuple | None = None, on_iteration: Callable[[int, torch.Tensor], None] | None = None) -> TrainingOutcome:
    """Runs iterations until `config.iterations` are done (COMPLETED) or the time budget says stop (PARTIAL).

    The budget: before each iteration, if the time left before `deadline` is below `stop_margin_seconds` plus the
    slowest iteration seen so far, training stops. `stop_margin_seconds` is time reserved to write the checkpoint and
    outputs and upload them. A checkpoint is written every `checkpoint_every` iterations and always at the end, whatever
    the status, so an early stop leaves a resumable state. Cancellation raises; nothing is returned for a cancelled job.
    """
    device = next(iter(params.values())).device
    if resume is not None:
        start, params, optimizer, density, scene_extent, history = resume
    else:
        start, history = 0, []
        scene_extent = scene_extent_of(cams)
        optimizer = make_optimizer(params, config, scene_extent)
        density = DensityControl.empty(len(params["means"]), device)
    initial_count = len(params["means"]) if resume is None else (density.events[0].before if density.events else len(params["means"]))
    slowest = 0.0
    it = start
    stop_reason = "iterations completed"

    def checkpoint(done: int) -> Path:
        return save_checkpoint(checkpoint_path, iteration=done, params=params, optimizer=optimizer, density=density, config=config,
                               source=source, scene_extent=scene_extent, history=history)

    while it < config.iterations:
        if is_cancelled():
            raise InterruptedError("cancelled")
        if deadline is not None and deadline - clock() < stop_margin_seconds + slowest:
            stop_reason = "time budget"
            break
        t0 = clock()
        cam = cams[int(np.random.default_rng((config.seed, it)).integers(0, len(cams)))]
        gt = torch.as_tensor(cam["image"], device=device, dtype=torch.float32)
        out = rasterize(*render_inputs(params), cam)
        pred = out.image.clamp(0.0, 1.0)
        loss, parts = loss_fn(pred, gt, config)
        optimizer.zero_grad(set_to_none=True)
        loss.backward()
        grad = out.means2d.grad
        if grad is not None:
            density.accumulate(grad.reshape(-1, 2), out.visible, cam["width"], cam["height"])
        optimizer.step()
        if on_iteration is not None:
            on_iteration(it, pred.detach())
        if config.densifies_at(it):
            density.densify(it, params, optimizer, config, scene_extent)
        if config.resets_opacity_at(it):
            density.reset_opacity(it, params, optimizer)
        history.append({"iteration": it + 1, "gaussians": len(params["means"]), **{k: float(v) for k, v in parts.items()}})
        it += 1
        slowest = max(slowest, clock() - t0)
        if config.checkpoint_every > 0 and it % config.checkpoint_every == 0 and it < config.iterations:
            checkpoint(it)

    path = checkpoint(it)
    status = STATUS_COMPLETED if it >= config.iterations else STATUS_PARTIAL
    return TrainingOutcome(status, it, config.iterations, start, len(params["means"]), initial_count, stop_reason, history,
                           list(density.events), list(density.resets), path, params)


# ---- the photometric loss ----------------------------------------------------------------------------------------------


def _gaussian_window(size: int, sigma: float, device, dtype):
    coords = torch.arange(size, dtype=dtype, device=device) - size // 2
    g = torch.exp(-(coords**2) / (2 * sigma**2))
    g = g / g.sum()
    return (g[:, None] @ g[None, :])[None, None, :, :]


def ssim(pred_chw: torch.Tensor, gt_chw: torch.Tensor, window_size: int = 11) -> torch.Tensor:
    """Differentiable single-scale SSIM (Gaussian window, depthwise), the 3DGS regulariser. (C, H, W) in [0, 1]."""
    import torch.nn.functional as F  # noqa: PLC0415

    c = pred_chw.shape[0]
    window = _gaussian_window(window_size, 1.5, pred_chw.device, pred_chw.dtype).repeat(c, 1, 1, 1)
    pad = window_size // 2
    pred, gt = pred_chw[None], gt_chw[None]
    mu_p = F.conv2d(pred, window, padding=pad, groups=c)
    mu_g = F.conv2d(gt, window, padding=pad, groups=c)
    mu_p2, mu_g2, mu_pg = mu_p * mu_p, mu_g * mu_g, mu_p * mu_g
    var_p = F.conv2d(pred * pred, window, padding=pad, groups=c) - mu_p2
    var_g = F.conv2d(gt * gt, window, padding=pad, groups=c) - mu_g2
    cov_pg = F.conv2d(pred * gt, window, padding=pad, groups=c) - mu_pg
    c1, c2 = 0.01**2, 0.03**2
    return (((2 * mu_pg + c1) * (2 * cov_pg + c2)) / ((mu_p2 + mu_g2 + c1) * (var_p + var_g + c2))).mean()


def l1_dssim_loss(pred_hwc: torch.Tensor, gt_hwc: torch.Tensor, config: TrainingConfig):
    """(1 - w) * L1 + w * (1 - SSIM), the 3DGS objective."""
    l1 = (pred_hwc - gt_hwc).abs().mean()
    d_ssim = 1.0 - ssim(pred_hwc.permute(2, 0, 1), gt_hwc.permute(2, 0, 1))
    loss = (1 - config.ssim_weight) * l1 + config.ssim_weight * d_ssim
    return loss, {"l1": l1.detach(), "d_ssim": d_ssim.detach(), "loss": loss.detach()}
