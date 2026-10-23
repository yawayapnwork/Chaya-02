"""The reconstruction quality gate of SPLAT_RECONSTRUCTION (gaussian-splatting-audit D-5).

Completing every iteration and exporting a valid PLY proves the training ran and the file is well formed, not that the
cloud looks like the scene. Before a COMPLETED training is published as SPLAT, this module measures it against the
captured frames, with the production renderer, and the stage refuses it when the evidence says it did not learn the
scene. A PARTIAL training is measured too; the measurement is recorded, and the result stays SPLAT_PARTIAL either way.

What is measured, and why these and not others:

  * PSNR on held-out views. `split_views` holds every Nth posed view (by name) out of training, the 3DGS / Mip-NeRF 360
    protocol (N = 8). train() is never given those cameras, so their frames supervise nothing: no loss, no gradient, no
    densification. As in that protocol, the SfM poses and seed points come from every frame; only the photometric
    optimisation is held out. When too few views remain to train on (`min_training`), nothing is held out: the
    measurement is on training views, `basis` says TRAINING_VIEWS, and it is evidence of fitting, not of generalising.
  * The same PSNR of the untrained SfM seed the training started from, rendered the same way. The gain over the seed is
    the one comparison that needs no scene-specific threshold: a cloud that renders unseen views no better than its
    starting point has not reconstructed anything. Absolute PSNR depends on the scene, exposure and masking, so its
    floor is off by default (`min_psnr_db` 0).
  * Training loss progression: the mean loss of the last tenth of iterations against the first tenth. This is the
    training objective on training views, so it is reported separately and is never presented as quality; it catches
    a run that did not optimise (a single iteration, a frozen optimiser).
  * Finiteness of every rendered image and metric. A non-finite value means there is no measurement, which is
    SPLAT_QUALITY_UNVERIFIED rather than a pass.

PSNR is over the valid pixels only: privacy-masked pixels (the camera's `valid`) were never ground truth, so they are
excluded exactly as the loss excludes them. A view with no valid pixel is not evaluated. Not measured: coverage (the
rasteriser interface returns no alpha, and an empty region already costs PSNR against the frame), and Gaussian validity
and count (publish_outcome checks those with validate_trained_state and the viewer limits before this gate).

The renderer is an argument: the stage passes gsplat on CUDA, the CPU tests pass the CPU test renderer, and the report
names which one measured it (`renderer`), so a test measurement is never read as a real-scene one.
"""

from __future__ import annotations

import math
from collections.abc import Callable, Sequence
from dataclasses import asdict, dataclass
from typing import Any

import numpy as np

BASIS_HELD_OUT = "HELD_OUT_VIEWS"
BASIS_TRAINING = "TRAINING_VIEWS"
INSUFFICIENT = "SPLAT_QUALITY_INSUFFICIENT"  # measured, and the measurement says it did not learn the scene
UNVERIFIED = "SPLAT_QUALITY_UNVERIFIED"  # no valid measurement could be made, so it is not shown to be a reconstruction
PSNR_CAP_DB = 100.0  # an exact match has infinite PSNR; a capped value keeps the report finite and comparable


@dataclass(frozen=True)
class QualityThresholds:
    min_psnr_gain_db: float = 0.0  # the trained mean PSNR must exceed the seed's by MORE than this
    min_psnr_db: float = 0.0  # 0 = off
    max_loss_ratio: float = 1.0  # final-window / initial-window mean training loss must be BELOW this
    require_held_out: bool = False

    @classmethod
    def from_settings(cls, s: Any) -> QualityThresholds:
        return cls(min_psnr_gain_db=s.gsplat_quality_min_psnr_gain_db, min_psnr_db=s.gsplat_quality_min_psnr_db,
                   max_loss_ratio=s.gsplat_quality_max_loss_ratio, require_held_out=s.gsplat_quality_require_held_out)

    def problems(self) -> list[str]:
        """Settings that make the gate meaningless, found before any work."""
        out = []
        if not math.isfinite(self.min_psnr_gain_db):
            out.append("gsplat_quality_min_psnr_gain_db must be a finite number of dB")
        if not (math.isfinite(self.min_psnr_db) and self.min_psnr_db >= 0):
            out.append("gsplat_quality_min_psnr_db must be 0 (off) or a positive number of dB")
        if not (math.isfinite(self.max_loss_ratio) and self.max_loss_ratio > 0):
            out.append("gsplat_quality_max_loss_ratio must be a positive number")
        return out


def holdout_problems(every: int) -> list[str]:
    return [] if every == 0 or every >= 2 else ["gsplat_eval_holdout_every must be 0 (no held-out views) or at least 2"]


def split_views(cams: Sequence[dict[str, Any]], every: int, *, min_training: int) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    """Pure: (training views, held-out views). Every `every`-th view by name (index 0, every, 2*every, ...) is held out,
    unless that would leave fewer than `min_training` to train on or `every` is 0; then nothing is held out."""
    ordered = sorted(cams, key=lambda c: c["name"])
    if every < 2:
        return list(ordered), []
    held = [c for i, c in enumerate(ordered) if i % every == 0]
    train = [c for i, c in enumerate(ordered) if i % every != 0]
    if len(train) < min_training or not held:
        return list(ordered), []
    return train, held


def snapshot(params: dict) -> dict:
    """Detached host copies of the parameters: the untrained seed, kept before training updates them in place. In host
    memory, not on the training device: it is only rendered after training, so it holds no GPU memory meanwhile."""
    return {k: v.detach().to("cpu", copy=True) for k, v in params.items()}


def masked_psnr(pred: np.ndarray, gt: np.ndarray, valid: np.ndarray | None) -> float | None:
    """Pure: PSNR in dB (peak 1.0) over the valid pixels of (H, W, 3) images in [0, 1]; None when no pixel is valid;
    NaN when the render is not finite."""
    if valid is not None and not valid.any():
        return None
    diff = pred.astype(np.float64) - gt.astype(np.float64)
    if valid is not None:
        diff = diff[valid]
    mse = float(np.mean(diff**2))
    if not math.isfinite(mse):
        return float("nan")
    return min(PSNR_CAP_DB, 10.0 * math.log10(1.0 / mse)) if mse > 0 else PSNR_CAP_DB


def loss_progress(history: Sequence[dict[str, Any]]) -> dict[str, Any]:
    """Pure: the mean training loss over the first and the last tenth of iterations (at least one, at most 500 each)."""
    losses = [float(h["loss"]) for h in history]
    if not losses:
        return {"iterations": 0, "window": 0, "initial_mean": None, "final_mean": None, "ratio": None}
    w = max(1, min(500, len(losses) // 10))
    initial, final = float(np.mean(losses[:w])), float(np.mean(losses[-w:]))
    ratio = final / initial if initial > 0 else (float("nan") if not math.isfinite(initial) else (0.0 if final == 0 else float("inf")))
    return {"iterations": len(losses), "window": w, "initial_mean": initial, "final_mean": final, "ratio": ratio}


def _render_psnr(params: dict, cams: Sequence[dict[str, Any]], rasterize: Callable, device) -> list[float | None]:
    import torch  # noqa: PLC0415

    from .splat_training import render_inputs  # noqa: PLC0415

    out = []
    with torch.no_grad():
        inputs = render_inputs({k: v.to(device) for k, v in params.items()})
        for cam in cams:
            image = rasterize(*inputs, cam).image.detach().clamp(0.0, 1.0).cpu().numpy()  # clamp keeps NaN: it stays NaN
            out.append(masked_psnr(image, cam["image"], cam.get("valid")))
    return out


def evaluate(*, params: dict, seed: dict, eval_cams: Sequence[dict[str, Any]], train_cams: Sequence[dict[str, Any]],
             rasterize: Callable, history: Sequence[dict[str, Any]], thresholds: QualityThresholds, renderer: str,
             holdout_every: int) -> dict[str, Any]:
    """Measures the trained `params` and the untrained `seed` on the held-out views (or, with none, the training views)
    and returns the report's `quality` block, with the verdict: `passed`, and else `code` and `reasons`."""
    reasons: list[str] = []
    unverified = False
    basis, cams, fallback = BASIS_HELD_OUT, list(eval_cams), None
    if not cams:
        basis, cams = BASIS_TRAINING, list(train_cams)
        fallback = (f"too few views to hold any out (every {holdout_every}th view, keeping at least the minimum to train on)"
                    if holdout_every else "holding out views is disabled (gsplat_eval_holdout_every 0)")
        if thresholds.require_held_out:
            unverified = True
            reasons.append(f"no held-out view to measure on: {fallback}; gsplat_quality_require_held_out is set")

    device = next(iter(params.values())).device  # the seed is in host memory (snapshot); it is rendered where training ran
    trained, seeded = _render_psnr(params, cams, rasterize, device), _render_psnr(seed, cams, rasterize, device)
    per_view = [{"name": c["name"], "trained_psnr_db": t, "seed_psnr_db": s} for c, t, s in zip(cams, trained, seeded, strict=True)]
    measured = [v for v in per_view if v["trained_psnr_db"] is not None and v["seed_psnr_db"] is not None]
    unmeasurable = [v["name"] for v in per_view if v not in measured]
    psnr: dict[str, Any] = {"views": len(measured), "views_without_valid_pixels": unmeasurable, "per_view": per_view}
    if not measured:
        unverified = True
        reasons.append(f"none of the {len(cams)} evaluation view(s) has a valid (unmasked) pixel to measure")
    else:
        t_mean = float(np.mean([v["trained_psnr_db"] for v in measured]))
        s_mean = float(np.mean([v["seed_psnr_db"] for v in measured]))
        psnr.update({"trained_mean_db": t_mean, "seed_mean_db": s_mean, "gain_db": t_mean - s_mean,
                     "trained_min_db": float(min(v["trained_psnr_db"] for v in measured))})
        if not (math.isfinite(t_mean) and math.isfinite(s_mean)):
            unverified = True
            reasons.append("a rendered evaluation view is not finite, so its PSNR is undefined")
        else:
            if not t_mean - s_mean > thresholds.min_psnr_gain_db:
                reasons.append(f"mean PSNR on {basis.lower().replace('_', ' ')} is {t_mean:.2f} dB, the untrained SfM seed's "
                               f"{s_mean:.2f} dB: a gain of {t_mean - s_mean:.2f} dB, not more than the required "
                               f"{thresholds.min_psnr_gain_db:.2f} dB")
            if thresholds.min_psnr_db > 0 and t_mean < thresholds.min_psnr_db:
                reasons.append(f"mean PSNR on {basis.lower().replace('_', ' ')} is {t_mean:.2f} dB, below the configured "
                               f"floor of {thresholds.min_psnr_db:.2f} dB")

    progress = loss_progress(history)
    ratio = progress["ratio"]
    if ratio is None or not math.isfinite(ratio):
        unverified = True
        reasons.append("the training loss history is empty or not finite")
    elif not ratio < thresholds.max_loss_ratio:
        reasons.append(f"the training loss did not decrease enough: the last {progress['window']} iteration(s) average "
                       f"{progress['final_mean']:.4g}, {ratio:.3f} x the first {progress['window']}'s {progress['initial_mean']:.4g} "
                       f"(required: below {thresholds.max_loss_ratio:g} x)")

    passed = not reasons
    return _json_safe({
        "basis": basis, "held_out": basis == BASIS_HELD_OUT, "fallback_reason": fallback,
        "holdout_every": holdout_every, "evaluation_views": [c["name"] for c in cams],
        "training_views": len(train_cams), "renderer": renderer,
        "psnr": psnr,
        "training_loss": {**progress, "note": "the training objective on training views; progress evidence, not quality"},
        "thresholds": asdict(thresholds),
        "passed": passed, "code": None if passed else (UNVERIFIED if unverified else INSUFFICIENT), "reasons": reasons,
    })


def _json_safe(value: Any) -> Any:
    """A non-finite float becomes None (strict JSON has no NaN or Infinity); the verdict was decided before."""
    if isinstance(value, float) and not math.isfinite(value):
        return None
    if isinstance(value, dict):
        return {k: _json_safe(v) for k, v in value.items()}
    if isinstance(value, list):
        return [_json_safe(v) for v in value]
    return value
