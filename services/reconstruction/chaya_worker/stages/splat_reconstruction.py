"""Stage 6: 3D Gaussian Splatting training with gsplat, seeded from the COLMAP/GLOMAP sparse model and
posed, anonymised frames that POSE_ESTIMATION produced.

Real optimisation, not a placeholder: Gaussians are initialised at the SfM points3D positions/colours and
optimised (position, log-scale, rotation quaternion, opacity logit, SH-degree-0 colour) against the actual
registered frames using gsplat's rasteriser, an Adam optimiser and an L1 + D-SSIM photometric loss -- the
same objective the reference 3D Gaussian Splatting paper uses. This is a deliberately simplified trainer:
it does not implement adaptive density control (splitting/cloning/pruning during training), so Gaussian
count stays fixed at the SfM point count. GEOMETRIC_CLEANUP is where over-large/under-supported Gaussians
get removed after training, not silently during it.

Needs torch + gsplat + a CUDA device (gsplat's rasteriser is CUDA-only) and COLMAP (to convert the binary
sparse model to TEXT, reusing the same tool POSE_ESTIMATION required). If any of that is missing the stage
fails with DEPENDENCY_UNAVAILABLE and nothing is produced; there is no CPU or "fake" fallback path.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

import numpy as np

from .. import archive
from ..colmap_txt import parse_cameras_txt, parse_points3d_txt
from ..contract import ArtifactSpec, StageContext, StageError, StageResult
from ..ply import GaussianCloud
from .base import command_record, write_json

REQUIREMENTS = ["py:torch", "py:gsplat", "cuda", "colmap"]
SH_C0 = 0.28209479177387814


def quat_wxyz_to_rotmat(q: np.ndarray) -> np.ndarray:
    """COLMAP's images.txt quaternion (wxyz, world-to-camera) to a 3x3 rotation matrix."""
    w, x, y, z = q / np.linalg.norm(q)
    return np.array([
        [1 - 2 * (y * y + z * z), 2 * (x * y - w * z), 2 * (x * z + w * y)],
        [2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x)],
        [2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y)],
    ])


def build_cameras(poses: list[dict[str, Any]], camera_models: dict[int, dict[str, Any]]) -> list[dict[str, Any]]:
    """Pure: viewmat (world-to-camera 4x4) and intrinsics 3x3 for every registered, decodable image."""
    cams = []
    for p in poses:
        model = camera_models.get(p["camera_id"])
        if model is None:
            continue
        R = quat_wxyz_to_rotmat(np.array(p["rotation_wxyz"], dtype=np.float64))
        t = np.array(p["translation"], dtype=np.float64)
        viewmat = np.eye(4, dtype=np.float64)
        viewmat[:3, :3], viewmat[:3, 3] = R, t
        K = np.array([[model["fx"], 0.0, model["cx"]], [0.0, model["fy"], model["cy"]], [0.0, 0.0, 1.0]])
        cams.append({"name": p["name"], "viewmat": viewmat, "K": K, "width": model["width"], "height": model["height"]})
    return cams


def initial_scale_log(positions: np.ndarray, k: int = 4) -> np.ndarray:
    """Isotropic initial scale per Gaussian = log(mean distance to its k nearest SfM neighbours), the
    standard 3DGS initialisation (a Gaussian should roughly span the local point spacing)."""
    from scipy.spatial import cKDTree  # noqa: PLC0415

    tree = cKDTree(positions)
    dist, _ = tree.query(positions, k=min(k + 1, len(positions)))
    mean_dist = dist[:, 1:].mean(axis=1) if dist.shape[1] > 1 else np.full(len(positions), 0.01)
    mean_dist = np.clip(mean_dist, 1e-4, None)
    return np.log(mean_dist)[:, None].repeat(3, axis=1)


def _gaussian_window(size: int, sigma: float, device, dtype):
    import torch  # noqa: PLC0415

    coords = torch.arange(size, dtype=dtype, device=device) - size // 2
    g = torch.exp(-(coords**2) / (2 * sigma**2))
    g = g / g.sum()
    window = g[:, None] @ g[None, :]
    return window[None, None, :, :]


def ssim(pred_chw, gt_chw, window_size: int = 11):
    """A compact, differentiable single-scale SSIM (Gaussian-windowed, per-channel depthwise conv), the same
    formulation used to regularise 3D Gaussian Splatting training. pred/gt are (C,H,W) in [0, 1]."""
    import torch  # noqa: PLC0415
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
    ssim_map = ((2 * mu_pg + c1) * (2 * cov_pg + c2)) / ((mu_p2 + mu_g2 + c1) * (var_p + var_g + c2))
    return ssim_map.mean()


class SplatReconstruction:
    name = "SPLAT_RECONSTRUCTION"

    def run(self, ctx: StageContext) -> StageResult:
        ctx.toolchain.require(REQUIREMENTS, stage=self.name)
        ctx.toolchain.require_cuda_compute_capability(ctx.settings.gsplat_min_compute_capability, stage=self.name)

        sparse_archives = ctx.inputs_of("SPARSE_MODEL")
        poses_inputs = ctx.inputs_of("POSES")
        frame_archives = ctx.inputs_of("FRAME_ARCHIVE_ANON")
        if not sparse_archives or not poses_inputs or not frame_archives:
            raise StageError("SPLAT_RECONSTRUCTION needs SPARSE_MODEL, POSES and FRAME_ARCHIVE_ANON from POSE_ESTIMATION",
                             code="INPUT_INVALID")

        import cv2
        import torch
        from gsplat import rasterization

        sparse_dir = ctx.workdir / "sparse-bin"
        archive.unpack(sparse_archives[0].path, sparse_dir)
        txt_dir = ctx.workdir / "sparse-txt"
        txt_dir.mkdir()
        colmap = ctx.toolchain.colmap().path
        ctx.runner.run([colmap, "model_converter", "--input_path", str(sparse_dir), "--output_path", str(txt_dir),
                        "--output_type", "TXT"], error_code="MODEL_CONVERSION_FAILED", timeout=600)
        cameras_model = parse_cameras_txt((txt_dir / "cameras.txt").read_text(encoding="utf-8"))
        points = parse_points3d_txt((txt_dir / "points3D.txt").read_text(encoding="utf-8"))
        if len(points["ids"]) < 4:
            raise StageError(f"only {len(points['ids'])} 3D points from SfM; not enough to seed a splat",
                             code="SPLAT_INIT_INSUFFICIENT", details={"points": len(points["ids"])})

        import json
        poses = json.loads(poses_inputs[0].path.read_text(encoding="utf-8"))["poses"]
        cams = build_cameras(poses, cameras_model)
        images_dir = ctx.workdir / "images"
        archive.unpack(frame_archives[0].path, images_dir)
        for cam in cams:
            path = images_dir / cam["name"]
            if not path.is_file():
                continue
            img = cv2.imdecode(np.fromfile(str(path), dtype=np.uint8), cv2.IMREAD_COLOR)
            cam["image"] = cv2.cvtColor(img, cv2.COLOR_BGR2RGB).astype(np.float32) / 255.0
        cams = [c for c in cams if "image" in c]
        if len(cams) < 3:
            raise StageError(f"only {len(cams)} posed frames could be matched to images; need at least 3 to train",
                             code="SPLAT_INIT_INSUFFICIENT", details={"posed_frames_with_images": len(cams)})

        device = torch.device("cuda")
        xyz = np.array(points["xyz"], dtype=np.float32)
        rgb01 = np.array(points["rgb"], dtype=np.float32) / 255.0
        colors_dc = (rgb01 - 0.5) / SH_C0

        means = torch.nn.Parameter(torch.tensor(xyz, device=device))
        scales_log = torch.nn.Parameter(torch.tensor(initial_scale_log(xyz), device=device, dtype=torch.float32))
        quats = torch.nn.Parameter(torch.tensor(np.tile([1.0, 0.0, 0.0, 0.0], (len(xyz), 1)), device=device, dtype=torch.float32))
        opacity_logit = torch.nn.Parameter(torch.zeros(len(xyz), device=device))  # sigmoid(0) = 0.5
        colors = torch.nn.Parameter(torch.tensor(colors_dc, device=device, dtype=torch.float32))

        s = ctx.settings
        optimizer = torch.optim.Adam([
            {"params": [means], "lr": s.gsplat_lr_position},
            {"params": [scales_log, quats, opacity_logit, colors], "lr": s.gsplat_lr_other},
        ])

        keyframes_dir = ctx.workdir / "keyframes"
        keyframes_dir.mkdir()
        rng = np.random.default_rng(0)
        loss_history: list[dict[str, Any]] = []

        for it in range(s.gsplat_iterations):
            if ctx.cancelled.is_set():
                raise StageError("cancelled by the control plane", code="CANCELLED")
            cam = cams[int(rng.integers(0, len(cams)))]
            viewmat = torch.tensor(cam["viewmat"], device=device, dtype=torch.float32)[None]
            K = torch.tensor(cam["K"], device=device, dtype=torch.float32)[None]
            gt = torch.tensor(cam["image"], device=device).permute(2, 0, 1)  # C,H,W

            renders, _alphas, _meta = rasterization(
                means, quats / quats.norm(dim=-1, keepdim=True), torch.exp(scales_log), torch.sigmoid(opacity_logit),
                torch.sigmoid(colors) if colors.shape[-1] == 3 else colors, viewmat, K, cam["width"], cam["height"])
            pred = renders[0].permute(2, 0, 1).clamp(0.0, 1.0)  # C,H,W

            l1 = (pred - gt).abs().mean()
            d_ssim = 1.0 - ssim(pred, gt)
            loss = (1 - s.gsplat_ssim_weight) * l1 + s.gsplat_ssim_weight * d_ssim
            optimizer.zero_grad()
            loss.backward()
            optimizer.step()

            if it % max(1, s.gsplat_keyframe_every) == 0 or it == s.gsplat_iterations - 1:
                loss_history.append({"iteration": it, "l1": float(l1.item()), "d_ssim": float(d_ssim.item()), "loss": float(loss.item())})
                frame_uint8 = (pred.detach().permute(1, 2, 0).clamp(0, 1).cpu().numpy() * 255).astype(np.uint8)
                cv2.imwrite(str(keyframes_dir / f"iter-{it:06d}.png"), cv2.cvtColor(frame_uint8, cv2.COLOR_RGB2BGR))

        cloud = GaussianCloud(
            positions=means.detach().cpu().numpy().astype(np.float32),
            scales_log=scales_log.detach().cpu().numpy().astype(np.float32),
            rotations_wxyz=quats.detach().cpu().numpy().astype(np.float32),
            opacity_logit=opacity_logit.detach().cpu().numpy().astype(np.float32),
            colors_dc=colors.detach().cpu().numpy().astype(np.float32))
        from ..ply import write_ply
        ply_path = write_ply(cloud, ctx.workdir / "splat.ply")
        keyframes_tar = ctx.workdir / "keyframes.tar"
        archive.pack(keyframes_dir, keyframes_tar)
        report = write_json(ctx.workdir / "splat-training-report.json", {
            "gaussian_count": len(cloud), "cameras_used": len(cams), "iterations": s.gsplat_iterations,
            "cuda_devices": ctx.toolchain.cuda_devices(), "loss_history": loss_history})

        return StageResult(
            "SUCCEEDED", command_record(ctx, {"gaussian_count": len(cloud), "cameras_used": len(cams), "iterations": s.gsplat_iterations}),
            ctx.runner.last_exit_status(),
            [ArtifactSpec("SPLAT", ply_path, "splat.ply", "application/octet-stream"),
             ArtifactSpec("KEYFRAME_RENDERS", keyframes_tar, "keyframes.tar", "application/x-tar"),
             ArtifactSpec("SPLAT_TRAINING_REPORT", report, "splat-training-report.json", "application/json")])
