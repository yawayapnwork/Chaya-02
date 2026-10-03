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

Privacy masks (review G-2). A camera may carry `valid`, a bool (H, W) mask of the pixels that are the captured scene.
Pixels outside it were rewritten by privacy anonymisation (chaya_worker.privacy.masks): they are not ground truth, so
the loss ignores them (masked L1, D-SSIM only over windows that contain no masked pixel) and they contribute no
gradient, and so no densification either. SPLAT_RECONSTRUCTION also zeroes them in the image, so the fill colours never
reach the optimiser at all.

Position learning rate (review G-3). The reference schedule (INRIA 3DGS `get_expon_lr_func`, as gsplat's trainer does
too): log-linear from `lr_position` to `lr_position_final` over `lr_position_decay_steps` iterations, then held, times
the scene extent. The defaults are the reference's (1.6e-4 -> 1.6e-6 over 30 000 steps), so a 7 000-iteration run ends
where the reference's 7k snapshot does. The rate is a function of the iteration number alone (`position_lr`), so a
resumed run follows exactly the schedule an uninterrupted one would; the horizon is therefore not a setting a resume
may change.

Spherical harmonics: degree 0 only, deliberately (`SH_DEGREE`). The viewer asset (chaya_worker.ksplat, KSplat level 0)
carries degree 0 only, so higher bands trained here would be dropped and the viewer would show the DC term alone: a
colour no camera was fitted against. And a re-scan splice rotates Gaussians into the parent frame
(chaya_worker.region_splice), which would need a Wigner-D rotation of every higher band that does not exist. Degree 0
is the one degree every consumer handles exactly. gsplat is given RGB (`sh_degree=None`): gsplat 1.5.3 evaluates SH as
`clamp_min(SH + 0.5, 0)`, so for degree 0 that is the same colour (chaya_worker.splat_color), checked on CUDA by
tests/gpu/test_gsplat_cuda.py.

Conventions are checked, not assumed (`validate_camera`): world-to-camera 4x4 view matrices with a proper rotation
(det +1, OpenCV/COLMAP axes: x right, y down, z forward), a pinhole K without skew whose principal point lies in the
image, and an sRGB float image of the camera's size. A camera that breaks one fails training before any iteration.

A non-finite loss or parameter stops training with TrainingDiverged; nothing from the diverged state is checkpointed.
`validate_trained_state` is what the stage checks before it publishes a cloud as SPLAT.

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
PARAM_WIDTHS = {"means": 3, "scales_log": 3, "quats": 4, "opacity_logit": None, "sh0": 3}  # None: shape (N,)
SH_DEGREE = 0  # see the module docstring: every consumer of the cloud handles degree 0 exactly, and only degree 0
# 2: the position learning-rate schedule (format 1 trained with a constant position rate; it cannot be resumed here).
CHECKPOINT_FORMAT = "chaya-splat-checkpoint/2"

STATUS_COMPLETED = "COMPLETED"  # every configured iteration ran
STATUS_PARTIAL = "PARTIAL"  # stopped early by the time budget; the cloud is usable but not the configured result


@dataclass(frozen=True)
class TrainingConfig:
    iterations: int = 7000
    lr_position: float = 1.6e-4
    lr_position_final: float = 1.6e-6
    lr_position_decay_steps: int = 30_000
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
        return cls(iterations=s.gsplat_iterations, lr_position=s.gsplat_lr_position,
                   lr_position_final=s.gsplat_lr_position_final, lr_position_decay_steps=s.gsplat_lr_position_decay_steps,
                   lr_scale=s.gsplat_lr_scale,
                   lr_rotation=s.gsplat_lr_rotation, lr_opacity=s.gsplat_lr_opacity, lr_color=s.gsplat_lr_color,
                   ssim_weight=s.gsplat_ssim_weight, densify_start=s.gsplat_densify_start, densify_stop=s.gsplat_densify_stop,
                   densify_every=s.gsplat_densify_every, densify_grad_threshold=s.gsplat_densify_grad_threshold,
                   percent_dense=s.gsplat_percent_dense, prune_opacity=s.gsplat_prune_opacity,
                   prune_scale_fraction=s.gsplat_prune_scale_fraction, opacity_reset_every=s.gsplat_opacity_reset_every,
                   max_gaussians=s.gsplat_max_gaussians, checkpoint_every=s.gsplat_checkpoint_every, seed=s.gsplat_seed)

    def __post_init__(self) -> None:
        if not 0 < self.lr_position_final <= self.lr_position:
            raise ValueError("lr_position_final must be positive and at most lr_position")
        if self.lr_position_decay_steps < 0:
            raise ValueError("lr_position_decay_steps must not be negative")

    def as_dict(self) -> dict[str, Any]:
        return asdict(self)

    def densifies_at(self, iteration: int) -> bool:
        """Adaptive density control runs after `iteration` (0-based) completes."""
        step = iteration + 1
        return self.densify_start <= step < self.densify_stop and step % self.densify_every == 0

    def resets_opacity_at(self, iteration: int) -> bool:
        step = iteration + 1
        return self.opacity_reset_every > 0 and step < self.densify_stop and step % self.opacity_reset_every == 0


def position_lr(config: TrainingConfig, iteration: int, scene_extent: float) -> float:
    """Pure: the position learning rate for 0-based `iteration` (module docstring). Log-linear from lr_position to
    lr_position_final over lr_position_decay_steps, then held; 0 decay steps means constant. Scaled by the scene extent."""
    if config.lr_position_decay_steps == 0:
        return config.lr_position * scene_extent
    t = min(max(iteration / config.lr_position_decay_steps, 0.0), 1.0)
    return math.exp((1.0 - t) * math.log(config.lr_position) + t * math.log(config.lr_position_final)) * scene_extent


class CameraConventionError(ValueError):
    """A training camera is not in the convention the rasteriser expects (validate_camera)."""


class TrainingDiverged(RuntimeError):
    """The loss or a parameter became non-finite."""


def validate_camera(cam: dict[str, Any]) -> None:
    """Raises CameraConventionError unless `cam` is a world-to-camera pinhole camera in the OpenCV/COLMAP convention with
    an image of its size (module docstring)."""
    name = cam.get("name", "?")

    def fail(why: str) -> None:
        raise CameraConventionError(f"camera {name}: {why}")

    v = np.asarray(cam["viewmat"], dtype=np.float64)
    if v.shape != (4, 4) or not np.allclose(v[3], [0.0, 0.0, 0.0, 1.0]):
        fail("the view matrix is not a 4x4 rigid transform")
    r = v[:3, :3]
    if not np.allclose(r.T @ r, np.eye(3), atol=1e-4) or np.linalg.det(r) <= 0:
        fail("the view matrix's rotation is not a proper rotation (orthonormal, det +1); a reflection would flip handedness")
    k = np.asarray(cam["K"], dtype=np.float64)
    w, h = int(cam["width"]), int(cam["height"])
    if k.shape != (3, 3) or not np.allclose(k[2], [0.0, 0.0, 1.0]) or k[1, 0] != 0 or k[0, 1] != 0:
        fail("K is not a pinhole intrinsic matrix without skew")
    if k[0, 0] <= 0 or k[1, 1] <= 0 or not (0 < k[0, 2] < w and 0 < k[1, 2] < h):
        fail("K needs positive focal lengths and a principal point inside the image")
    image = cam["image"]
    if image.shape != (h, w, 3) or image.dtype != np.float32:
        fail(f"the image is {image.shape} {image.dtype}, expected ({h}, {w}, 3) float32")
    if not np.isfinite(image).all() or image.min() < 0.0 or image.max() > 1.0:
        fail("the image is not sRGB values in [0, 1]")
    valid = cam.get("valid")
    if valid is not None and (valid.shape != (h, w) or valid.dtype != bool):
        fail("the validity mask is not a bool (H, W) array")


def validate_trained_state(params: dict) -> list[str]:
    """Pure: why a parameter set is not a usable trained cloud (empty when it is): no Gaussians, inconsistent shapes,
    non-finite values, or a degenerate rotation."""
    problems = []
    n = len(params["means"])
    if n == 0:
        return ["no Gaussian is left"]
    for k in PARAM_NAMES:
        t = params[k].detach()
        width = PARAM_WIDTHS[k]
        if t.shape != ((n,) if width is None else (n, width)):
            problems.append(f"{k} has shape {tuple(t.shape)}, expected {n} rows")
        elif not torch.isfinite(t).all():
            problems.append(f"{k} holds non-finite values")
    if not problems and (params["quats"].detach().norm(dim=-1) <= 1e-8).any():
        problems.append("a rotation quaternion has zero length")
    return problems


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
    learning rate starts at position_lr(iteration 0); train() follows the schedule from there."""
    lrs = {"means": position_lr(config, 0, scene_extent), "scales_log": config.lr_scale, "quats": config.lr_rotation,
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
        "format": CHECKPOINT_FORMAT, "sh_degree": SH_DEGREE, "iteration": iteration, "config": config.as_dict(), "source": source,
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
        raise CheckpointMismatch(f"{path.name} is a {payload.get('format')!r} checkpoint, not {CHECKPOINT_FORMAT}: it was written "
                                 "by a training loop with another schedule or layout and cannot be resumed exactly")
    if payload.get("sh_degree") != SH_DEGREE:
        raise CheckpointMismatch(f"the checkpoint holds SH degree {payload.get('sh_degree')}, this worker trains {SH_DEGREE}")
    if payload["source"].get("digest") != source.get("digest"):
        raise CheckpointMismatch("the checkpoint was trained from different inputs", )
    differing = {k for k, v in config.as_dict().items() if payload["config"].get(k) != v} - RESUMABLE_CONFIG_CHANGES
    if differing:
        raise CheckpointMismatch(f"the checkpoint was trained with different settings: {sorted(differing)}")
    problems = validate_trained_state(payload["params"])
    d = payload["density"]
    if problems or len(d["grad2d"]) != len(payload["params"]["means"]) or len(d["count"]) != len(payload["params"]["means"]):
        raise CheckpointMismatch(f"the checkpoint is not a consistent training state: {problems or 'density statistics do not match'}")
    params = {k: torch.nn.Parameter(payload["params"][k].to(device)) for k in PARAM_NAMES}
    optimizer = make_optimizer(params, config, payload["scene_extent"])
    for g in optimizer.param_groups:
        g["lr"] = payload["lrs"][g["name"]]
    for name in PARAM_NAMES:
        st = payload["optimizer"][name]
        if st:
            optimizer.state[params[name]] = {k: (v.to(device) if torch.is_tensor(v) else v) for k, v in st.items()}
    density = DensityControl(d["grad2d"].to(device), d["count"].to(device), [DensifyEvent(**e) for e in d["events"]], list(d["resets"]))
    return payload["iteration"], params, optimizer, density, payload["scene_extent"], list(payload["history"])


# ---- the loop ------------------------------------------------------------------------------------------------------------


class RenderOutput(NamedTuple):
    image: torch.Tensor  # (H, W, 3) in [0, 1]
    means2d: torch.Tensor  # (N, 2) pixel positions, part of the graph, with retain_grad() called
    visible: torch.Tensor  # (N,) bool


Rasterizer = Callable[[torch.Tensor, torch.Tensor, torch.Tensor, torch.Tensor, torch.Tensor, dict[str, Any]], RenderOutput]


GSPLAT_VALIDATED_VERSION = "1.5.3"  # the API this adapter was written against (pyproject.toml pins it)


def gsplat_rasterizer(device: torch.device) -> Rasterizer:
    """gsplat's CUDA rasteriser, the production renderer, per gsplat 1.5.3's `rasterization` (read from its source):
    world-to-camera `viewmats` [C, 4, 4] and pinhole `Ks` [C, 3, 3] (OpenCV axes), wxyz quaternions, linear scales,
    opacities in [0, 1], and colours as post-activation RGB (`sh_degree=None`). Unpacked output, so `meta["means2d"]` is
    [C, N, 2] and its gradient lines up with the Gaussians; `meta["radii"]` is [C, N, 2] (1.5) or [C, N] (earlier).
    The output is checked against that contract, so an incompatible gsplat fails loudly rather than training wrongly."""
    import gsplat  # noqa: PLC0415
    from gsplat import rasterization  # noqa: PLC0415

    def render(means, quats, scales, opacities, rgb, cam):
        viewmat = torch.as_tensor(cam["viewmat"], device=device, dtype=torch.float32)[None]
        K = torch.as_tensor(cam["K"], device=device, dtype=torch.float32)[None]
        renders, _alphas, meta = rasterization(means, quats, scales, opacities, rgb, viewmat, K, cam["width"], cam["height"],
                                               packed=False)
        n = len(means)
        means2d = meta["means2d"]
        radii = meta["radii"]
        if (tuple(renders.shape) != (1, cam["height"], cam["width"], 3) or tuple(means2d.shape) != (1, n, 2)
                or tuple(radii.shape[:2]) != (1, n)):
            raise RuntimeError(f"gsplat {getattr(gsplat, '__version__', '?')} returned renders {tuple(renders.shape)}, means2d "
                               f"{tuple(means2d.shape)}, radii {tuple(radii.shape)}; this adapter expects gsplat "
                               f"{GSPLAT_VALIDATED_VERSION}'s unpacked shapes")
        means2d.retain_grad()
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
    config: dict[str, Any] = field(default_factory=dict)  # the TrainingConfig this outcome was trained with


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
    for cam in cams:
        validate_camera(cam)
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
        valid = cam.get("valid")
        valid = None if valid is None else torch.as_tensor(valid, device=device, dtype=torch.bool)
        out = rasterize(*render_inputs(params), cam)
        pred = out.image.clamp(0.0, 1.0)
        loss, parts = loss_fn(pred, gt, config, valid=valid)
        if not torch.isfinite(loss):
            raise TrainingDiverged(f"the loss became {float(loss.detach())} at iteration {it + 1}")
        lr_means = position_lr(config, it, scene_extent)
        _group(optimizer, "means")["lr"] = lr_means
        optimizer.zero_grad(set_to_none=True)
        loss.backward()
        grad = out.means2d.grad
        if grad is not None:
            density.accumulate(grad.reshape(-1, 2), out.visible, cam["width"], cam["height"])
        optimizer.step()
        if not torch.stack([torch.isfinite(params[k]).all() for k in PARAM_NAMES]).all():
            raise TrainingDiverged(f"a parameter became non-finite at iteration {it + 1}")
        if on_iteration is not None:
            on_iteration(it, pred.detach())
        if config.densifies_at(it):
            density.densify(it, params, optimizer, config, scene_extent)
        if config.resets_opacity_at(it):
            density.reset_opacity(it, params, optimizer)
        history.append({"iteration": it + 1, "gaussians": len(params["means"]), "lr_position": lr_means,
                        **{k: float(v) for k, v in parts.items()}})
        it += 1
        slowest = max(slowest, clock() - t0)
        if config.checkpoint_every > 0 and it % config.checkpoint_every == 0 and it < config.iterations:
            checkpoint(it)

    path = checkpoint(it)
    status = STATUS_COMPLETED if it >= config.iterations else STATUS_PARTIAL
    return TrainingOutcome(status, it, config.iterations, start, len(params["means"]), initial_count, stop_reason, history,
                           list(density.events), list(density.resets), path, params,
                           config.as_dict())


# ---- the photometric loss ----------------------------------------------------------------------------------------------


def _gaussian_window(size: int, sigma: float, device, dtype):
    coords = torch.arange(size, dtype=dtype, device=device) - size // 2
    g = torch.exp(-(coords**2) / (2 * sigma**2))
    g = g / g.sum()
    return (g[:, None] @ g[None, :])[None, None, :, :]


def ssim(pred_chw: torch.Tensor, gt_chw: torch.Tensor, window_size: int = 11) -> torch.Tensor:
    """Differentiable single-scale SSIM (Gaussian window, depthwise), the 3DGS regulariser. (C, H, W) in [0, 1]."""
    return ssim_map(pred_chw, gt_chw, window_size).mean()


SSIM_WINDOW = 11


def ssim_map(pred_chw: torch.Tensor, gt_chw: torch.Tensor, window_size: int = SSIM_WINDOW) -> torch.Tensor:
    """The per-pixel, per-channel SSIM map (C, H, W) whose mean is ssim()."""
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
    return (((2 * mu_pg + c1) * (2 * cov_pg + c2)) / ((mu_p2 + mu_g2 + c1) * (var_p + var_g + c2)))[0]


def ssim_support(valid_hw: torch.Tensor, window_size: int = SSIM_WINDOW) -> torch.Tensor:
    """Pure: bool (H, W), the pixels whose whole SSIM window lies on valid pixels. The image border is not invalid:
    ssim() zero-pads there for every pixel alike."""
    import torch.nn.functional as F  # noqa: PLC0415

    invalid = (~valid_hw).to(torch.float32)[None, None]
    reached = F.max_pool2d(invalid, window_size, stride=1, padding=window_size // 2)[0, 0]
    return reached < 0.5


def l1_dssim_loss(pred_hwc: torch.Tensor, gt_hwc: torch.Tensor, config: TrainingConfig, valid: torch.Tensor | None = None):
    """(1 - w) * L1 + w * (1 - SSIM), the 3DGS objective. With `valid` (bool (H, W)), only valid pixels count: L1 is the
    mean over valid pixels and D-SSIM the mean over pixels whose window holds no invalid pixel, so an invalid
    ground-truth pixel has no influence on the loss or its gradient. A view with no valid pixel contributes nothing."""
    pred_chw, gt_chw = pred_hwc.permute(2, 0, 1), gt_hwc.permute(2, 0, 1)
    if valid is None:
        l1 = (pred_hwc - gt_hwc).abs().mean()
        d_ssim = 1.0 - ssim(pred_chw, gt_chw)
    else:
        weight = valid.to(pred_hwc.dtype)[..., None]
        n_valid = weight.sum() * pred_hwc.shape[-1]
        # torch.where, not a product: the gradient through an excluded pixel is exactly zero whatever it holds
        diff = torch.where(valid[..., None], (pred_hwc - gt_hwc).abs(), torch.zeros_like(pred_hwc))
        l1 = diff.sum() / n_valid.clamp_min(1.0)
        support = ssim_support(valid)
        n_support = support.sum() * pred_hwc.shape[-1]
        smap = ssim_map(pred_chw, gt_chw)
        kept = torch.where(support[None], smap, torch.ones_like(smap))  # an excluded window counts as a perfect match
        d_ssim = (1.0 - kept).sum() / n_support.clamp_min(1)
    loss = (1 - config.ssim_weight) * l1 + config.ssim_weight * d_ssim
    return loss, {"l1": l1.detach(), "d_ssim": d_ssim.detach(), "loss": loss.detach()}
