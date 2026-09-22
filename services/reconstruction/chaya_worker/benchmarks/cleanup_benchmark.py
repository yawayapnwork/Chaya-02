"""Experiment harness comparing geometric cleanup strategies on a trained Gaussian splat.

    python -m chaya_worker.benchmarks.cleanup_benchmark --ply splat.ply [--labels semantic-labels.json]
        [--poses poses.json --sparse-model sparse-model-dir --frames frames/ --sample-cameras 8] --out report.json

Five methods, exactly as specified:
    1. no_cleanup             -- always computable, the baseline
    2. opacity_threshold      -- naive, opacity-only (pure numpy; always computable)
    3. statistical_outlier    -- Open3D; unavailable if Open3D is not installed
    4. density_outlier        -- Open3D radius-based outlier removal; same availability
    5. semantic_aware         -- Open3D + semantic labels; needs both

Every method reports point_count_before/after and wall_clock_seconds, which never require anything beyond
numpy. PSNR/SSIM (real: skimage.metrics, computed by re-rendering the cleaned cloud with gsplat at held-out
camera poses and comparing to the real frame) are additionally computed only when --poses/--sparse-model/
--frames are given AND torch+gsplat+CUDA are available; otherwise they are reported as null with the exact
reason, never fabricated or interpolated.
"""

from __future__ import annotations

import argparse
import json
import time
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Callable

import numpy as np

from ..geometry_cleanup import opacity_threshold_mask, radius_outlier_mask, semantic_aware_mask, statistical_outlier_mask
from ..ply import GaussianCloud, read_ply
from ..settings import Settings
from ..toolchain import Toolchain

METHODS = ("no_cleanup", "opacity_threshold", "statistical_outlier", "density_outlier", "semantic_aware")


@dataclass
class MethodResult:
    method: str
    available: bool
    reason: str | None
    point_count_before: int | None = None
    point_count_after: int | None = None
    points_removed: int | None = None
    points_removed_pct: float | None = None
    wall_clock_seconds: float | None = None
    psnr: float | None = None
    psnr_reason: str | None = None
    ssim: float | None = None
    ssim_reason: str | None = None


def _timed_mask(cloud: GaussianCloud, fn: Callable[[], np.ndarray]) -> tuple[np.ndarray, float]:
    started = time.perf_counter()
    mask = fn()
    return mask, time.perf_counter() - started


def run_methods(cloud: GaussianCloud, *, labels: np.ndarray | None, settings: Settings,
                toolchain: Toolchain, render_eval: Callable[[GaussianCloud], tuple[float | None, str | None, float | None, str | None]] | None = None
                ) -> list[MethodResult]:
    n = len(cloud)
    results: list[MethodResult] = []
    open3d_ok = toolchain.module("open3d").available

    def finish(method: str, mask: np.ndarray, seconds: float) -> MethodResult:
        after = int(mask.sum())
        r = MethodResult(method, True, None, n, after, n - after, round(100 * (n - after) / n, 3) if n else 0.0,
                         round(seconds, 4))
        if render_eval is not None:
            r.psnr, r.psnr_reason, r.ssim, r.ssim_reason = render_eval(cloud.subset(mask))
        else:
            r.psnr_reason = r.ssim_reason = "no reference frames/poses were given to the benchmark"
        return r

    mask, seconds = _timed_mask(cloud, lambda: np.ones(n, dtype=bool))
    results.append(finish("no_cleanup", mask, seconds))

    mask, seconds = _timed_mask(cloud, lambda: opacity_threshold_mask(cloud, settings.cleanup_opacity_threshold))
    results.append(finish("opacity_threshold", mask, seconds))

    if not open3d_ok:
        results.append(MethodResult("statistical_outlier", False, "Open3D is not installed"))
        results.append(MethodResult("density_outlier", False, "Open3D is not installed"))
    else:
        mask, seconds = _timed_mask(cloud, lambda: statistical_outlier_mask(
            cloud, nb_neighbors=settings.cleanup_stat_nb_neighbors, std_ratio=settings.cleanup_stat_std_ratio)[0])
        results.append(finish("statistical_outlier", mask, seconds))
        mask, seconds = _timed_mask(cloud, lambda: radius_outlier_mask(
            cloud, nb_points=settings.cleanup_radius_nb_points, radius=settings.cleanup_radius)[0])
        results.append(finish("density_outlier", mask, seconds))

    if not open3d_ok:
        results.append(MethodResult("semantic_aware", False, "Open3D is not installed"))
    elif labels is None:
        results.append(MethodResult("semantic_aware", False, "no semantic labels were given to the benchmark"))
    else:
        mask, seconds = _timed_mask(cloud, lambda: semantic_aware_mask(
            cloud, labels, radius=settings.cleanup_radius, min_same_class_neighbors=settings.cleanup_semantic_min_neighbors)[0])
        results.append(finish("semantic_aware", mask, seconds))

    return results


def _render_eval_factory(*, sparse_model: Path, poses_path: Path, frames_dir: Path, sample_cameras: int, toolchain: Toolchain):
    """Builds the render_eval callback, or returns (None, reason) if it cannot (honest degrade, per spec)."""
    if not all(toolchain.status(r).available for r in ("py:torch", "py:gsplat", "cuda")):
        return None, "torch/gsplat/CUDA are not all available to re-render the cleaned splats"
    import cv2
    import torch
    from gsplat import rasterization
    from skimage.metrics import peak_signal_noise_ratio, structural_similarity

    from ..colmap_txt import parse_cameras_txt
    from ..stages.splat_reconstruction import build_cameras

    cameras_model = parse_cameras_txt((sparse_model / "cameras.txt").read_text(encoding="utf-8"))
    poses = json.loads(poses_path.read_text(encoding="utf-8"))["poses"]
    cams = build_cameras(poses, cameras_model)
    rng = np.random.default_rng(0)
    if len(cams) > sample_cameras:
        cams = [cams[i] for i in sorted(rng.choice(len(cams), size=sample_cameras, replace=False))]
    loaded = []
    for cam in cams:
        path = frames_dir / cam["name"]
        if not path.is_file():
            continue
        img = cv2.imdecode(np.fromfile(str(path), dtype=np.uint8), cv2.IMREAD_COLOR)
        cam["image"] = cv2.cvtColor(img, cv2.COLOR_BGR2RGB).astype(np.float32) / 255.0
        loaded.append(cam)
    if not loaded:
        return None, "none of the held-out camera poses matched a frame in --frames"

    device = torch.device("cuda")

    def render_eval(cloud: GaussianCloud) -> tuple[float | None, str | None, float | None, str | None]:
        if len(cloud) == 0:
            return None, "cleaned cloud is empty", None, "cleaned cloud is empty"
        means = torch.tensor(cloud.positions, device=device)
        quats = torch.tensor(cloud.rotations_normalized(), device=device, dtype=torch.float32)
        scales = torch.tensor(cloud.scales(), device=device, dtype=torch.float32)
        opac = torch.tensor(cloud.opacities(), device=device, dtype=torch.float32)
        colors = torch.tensor(np.clip(cloud.colors_rgb01(), 0, 1), device=device, dtype=torch.float32)
        psnrs, ssims = [], []
        with torch.no_grad():
            for cam in loaded:
                viewmat = torch.tensor(cam["viewmat"], device=device, dtype=torch.float32)[None]
                K = torch.tensor(cam["K"], device=device, dtype=torch.float32)[None]
                renders, _a, _m = rasterization(means, quats, scales, opac, colors, viewmat, K, cam["width"], cam["height"])
                pred = renders[0].clamp(0, 1).cpu().numpy()
                gt = cam["image"]
                psnrs.append(peak_signal_noise_ratio(gt, pred, data_range=1.0))
                ssims.append(structural_similarity(gt, pred, channel_axis=2, data_range=1.0))
        return float(np.mean(psnrs)), None, float(np.mean(ssims)), None

    return render_eval, None


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--ply", required=True, type=Path)
    parser.add_argument("--labels", type=Path, help="semantic-labels.json from SEMANTIC_SEGMENTATION")
    parser.add_argument("--poses", type=Path, help="poses.json from POSE_ESTIMATION (enables PSNR/SSIM)")
    parser.add_argument("--sparse-model", type=Path, help="directory with COLMAP TEXT cameras.txt (enables PSNR/SSIM)")
    parser.add_argument("--frames", type=Path, help="directory of anonymised frames (enables PSNR/SSIM)")
    parser.add_argument("--sample-cameras", type=int, default=8)
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args(argv)

    cloud = read_ply(args.ply)
    labels = np.array(json.loads(args.labels.read_text(encoding="utf-8"))["labels"], dtype=object) if args.labels else None
    settings = Settings()
    toolchain = Toolchain()

    render_eval, unavailable_reason = None, "no reference frames/poses were given to the benchmark"
    if args.poses and args.sparse_model and args.frames:
        render_eval, unavailable_reason = _render_eval_factory(
            sparse_model=args.sparse_model, poses_path=args.poses, frames_dir=args.frames,
            sample_cameras=args.sample_cameras, toolchain=toolchain)

    results = run_methods(cloud, labels=labels, settings=settings, toolchain=toolchain, render_eval=render_eval)
    if render_eval is None:
        for r in results:
            if r.available:
                r.psnr_reason = r.ssim_reason = unavailable_reason

    report = {"input": str(args.ply), "gaussian_count": len(cloud), "methods": [asdict(r) for r in results]}
    args.out.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
