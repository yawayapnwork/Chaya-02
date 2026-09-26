"""Stage 6: 3D Gaussian Splatting training with gsplat, seeded from the COLMAP/GLOMAP sparse model and the posed,
anonymised frames that POSE_ESTIMATION produced.

Real optimisation (chaya_worker.splat_training):
  * Gaussians are initialised at the SfM points3D positions and colours.
  * Position, log-scale, rotation, opacity logit and degree-0 SH colour are optimised against the registered frames
    with gsplat's CUDA rasteriser, Adam, and the 3DGS L1 + D-SSIM loss.
  * Adaptive density control clones, splits and prunes Gaussians during training, up to `gsplat_max_gaussians`.
  * Colour follows chaya_worker.splat_color from frame to viewer. The rasteriser is given SH_C0 * f_dc + 0.5, the value
    every consumer displays.

Time budget: the loop stops before the work order's deadline, leaving `gsplat_stop_margin_seconds` for outputs. Then:
  * **COMPLETED**, every configured iteration ran: the stage SUCCEEDS with a `SPLAT`.
  * **PARTIAL**, the budget ended training early: the stage FAILS with `TIME_LIMIT_EXCEEDED` and publishes the cloud as
    `SPLAT_PARTIAL` (`partial=True`). The control plane then ends the run PARTIAL, a partial-quality reconstruction
    that is not finalised (dev.chaya.api.pipeline.PipelineService#advance). A partial cloud is never labelled as the
    configured result.
  * If not a single iteration ran, there is no splat, only the report.

Either way a `SPLAT_CHECKPOINT` is published. It holds the iteration, parameters, Adam state, density-control state,
configuration and the identity of the inputs. A later SPLAT_RECONSTRUCTION job given it as input resumes from it, but
only for the same inputs and compatible settings (CHECKPOINT_MISMATCH otherwise). The control plane does not yet offer
a previous job's checkpoint to a new job (docs/pipeline.md).

Needs torch + gsplat + a CUDA device (gsplat's rasteriser is CUDA-only) and COLMAP (to convert the binary sparse model
to TEXT). If any of that is missing the stage fails with DEPENDENCY_UNAVAILABLE and nothing is produced. There is no CPU
or "fake" fallback path.
"""

from __future__ import annotations

import json
import time
from typing import Any

import numpy as np

from .. import archive
from ..colmap_txt import parse_cameras_txt, parse_points3d_txt
from ..contract import ArtifactSpec, StageContext, StageError, StageResult
from ..ply import write_ply
from ..splat_color import rgb_bytes_to_sh0
from .base import command_record, sha256_file, write_json

REQUIREMENTS = ["py:torch", "py:gsplat", "cuda", "colmap"]


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


def training_source(order: dict[str, Any], inputs: list) -> dict[str, Any]:
    """The identity a checkpoint is bound to: the run, scan version and the exact input artifacts."""
    from ..splat_training import source_identity  # noqa: PLC0415

    return source_identity(run_id=order.get("runId"), scan_id=order.get("scanId"), scan_version_id=order.get("scanVersionId"),
                           inputs=sorted(({"kind": i.kind, "artifact_id": i.artifact_id, "sha256": i.ref.get("sha256")} for i in inputs
                                          if i.kind in ("SPARSE_MODEL", "POSES", "FRAME_ARCHIVE_ANON")), key=lambda d: d["kind"]))


def publish_outcome(ctx: StageContext, outcome, *, source: dict[str, Any], cameras_used: int, versions: dict[str, Any],
                    keyframes_tar=None) -> StageResult:
    """Turns a training outcome into the stage result: COMPLETED -> SUCCEEDED with SPLAT; PARTIAL -> FAILED with
    TIME_LIMIT_EXCEEDED and SPLAT_PARTIAL (partial=True). The report and checkpoint go out either way. Pure apart from
    writing files into ctx.workdir, so it is tested without a GPU (tests/splat)."""
    from ..splat_training import STATUS_COMPLETED, STATUS_PARTIAL, to_cloud  # noqa: PLC0415

    artifacts: list[ArtifactSpec] = []
    splat_name = splat_sha = None
    if outcome.completed_iterations > 0:  # a cloud that was never optimised is the SfM seed, not a reconstruction
        cloud = to_cloud(outcome.params)
        partial = outcome.status == STATUS_PARTIAL
        splat_name = "splat-partial.ply" if partial else "splat.ply"
        ply_path = write_ply(cloud, ctx.workdir / splat_name)
        splat_sha = sha256_file(ply_path)
        artifacts.append(ArtifactSpec("SPLAT_PARTIAL" if partial else "SPLAT", ply_path, splat_name, "application/octet-stream",
                                      partial=partial))
    checkpoint_sha = None
    if outcome.checkpoint_path is not None and outcome.checkpoint_path.is_file():
        checkpoint_sha = sha256_file(outcome.checkpoint_path)
        artifacts.append(ArtifactSpec("SPLAT_CHECKPOINT", outcome.checkpoint_path, "splat-checkpoint.pt", "application/octet-stream"))
    if keyframes_tar is not None:
        artifacts.append(ArtifactSpec("KEYFRAME_RENDERS", keyframes_tar, "keyframes.tar", "application/x-tar"))

    summary = {
        "status": outcome.status, "stop_reason": outcome.stop_reason,
        "completed_iterations": outcome.completed_iterations, "target_iterations": outcome.target_iterations,
        "resumed_from_iteration": outcome.resumed_from_iteration,
        "initial_gaussian_count": outcome.initial_gaussian_count, "gaussian_count": outcome.gaussian_count,
    }
    report = write_json(ctx.workdir / "splat-training-report.json", {
        **summary,
        "provenance": {"source": source, "splat": {"artifact": splat_name, "sha256": splat_sha},
                       "checkpoint": {"artifact": "splat-checkpoint.pt" if checkpoint_sha else None, "sha256": checkpoint_sha},
                       "versions": versions},
        "cameras_used": cameras_used,
        "densification": [e.__dict__ for e in outcome.densify_events], "opacity_resets": outcome.opacity_resets,
        "loss_history": outcome.history[:: max(1, len(outcome.history) // 200)] + outcome.history[-1:],
        "colour_convention": "chaya_worker.splat_color: rgb = SH_C0 * f_dc + 0.5, sRGB-encoded values end to end",
    })
    artifacts.append(ArtifactSpec("SPLAT_TRAINING_REPORT", report, "splat-training-report.json", "application/json"))
    command = command_record(ctx, {**summary, "cameras_used": cameras_used})

    if outcome.status == STATUS_COMPLETED:
        return StageResult("SUCCEEDED", command, ctx.runner.last_exit_status(), artifacts)
    message = (f"the time budget stopped training after {outcome.completed_iterations} of {outcome.target_iterations} iterations; "
               + ("the partial cloud is published as SPLAT_PARTIAL, not as a finished reconstruction"
                  if splat_name else "no iteration ran, so no splat was produced"))
    return StageResult("FAILED", command, ctx.runner.last_exit_status(), artifacts, "TIME_LIMIT_EXCEEDED", message,
                       {**summary, "checkpoint": "splat-checkpoint.pt" if checkpoint_sha else None})


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
        import gsplat
        import torch

        from .. import splat_training as st

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

        poses = json.loads(poses_inputs[0].path.read_text(encoding="utf-8"))["poses"]
        cams = build_cameras(poses, cameras_model)
        images_dir = ctx.workdir / "images"
        archive.unpack(frame_archives[0].path, images_dir)
        for cam in cams:
            path = images_dir / cam["name"]
            if not path.is_file():
                continue
            img = cv2.imdecode(np.fromfile(str(path), dtype=np.uint8), cv2.IMREAD_COLOR)
            cam["image"] = cv2.cvtColor(img, cv2.COLOR_BGR2RGB).astype(np.float32) / 255.0  # sRGB values in [0, 1]
        cams = [c for c in cams if "image" in c]
        if len(cams) < 3:
            raise StageError(f"only {len(cams)} posed frames could be matched to images; need at least 3 to train",
                             code="SPLAT_INIT_INSUFFICIENT", details={"posed_frames_with_images": len(cams)})

        s = ctx.settings
        config = st.TrainingConfig.from_settings(s)
        device = torch.device("cuda")
        torch.manual_seed(config.seed)
        source = training_source(ctx.order, ctx.inputs)
        xyz = np.array(points["xyz"], dtype=np.float32)
        params = st.init_params(xyz, rgb_bytes_to_sh0(np.array(points["rgb"])), initial_scale_log(xyz), device)

        resume = None
        checkpoints = ctx.inputs_of("SPLAT_CHECKPOINT")
        if checkpoints:
            try:
                resume = st.load_checkpoint(checkpoints[0].path, config=config, source=source, device=device)
            except st.CheckpointMismatch as exc:
                raise StageError(str(exc), code="CHECKPOINT_MISMATCH", details={"checkpoint": checkpoints[0].artifact_id}) from exc
            ctx.logger.info("resuming from checkpoint", extra={"iteration": resume[0], "gaussians": len(resume[1]["means"])})

        keyframes_dir = ctx.workdir / "keyframes"
        keyframes_dir.mkdir()

        def keyframe(it: int, pred) -> None:
            if it % max(1, s.gsplat_keyframe_every) == 0 or it == config.iterations - 1:
                frame_uint8 = (pred.clamp(0, 1).cpu().numpy() * 255).astype(np.uint8)
                cv2.imwrite(str(keyframes_dir / f"iter-{it:06d}.png"), cv2.cvtColor(frame_uint8, cv2.COLOR_RGB2BGR))

        try:
            outcome = st.train(params=params, cams=cams, rasterize=st.gsplat_rasterizer(device), config=config,
                               loss_fn=st.l1_dssim_loss, checkpoint_path=ctx.workdir / "splat-checkpoint.pt", source=source,
                               deadline=ctx.deadline, stop_margin_seconds=s.gsplat_stop_margin_seconds, clock=time.time,
                               is_cancelled=ctx.cancelled.is_set, resume=resume, on_iteration=keyframe)
        except InterruptedError as exc:
            raise StageError("cancelled by the control plane", code="CANCELLED") from exc

        keyframes_tar = ctx.workdir / "keyframes.tar"
        archive.pack(keyframes_dir, keyframes_tar)
        versions = {"torch": torch.__version__, "gsplat": getattr(gsplat, "__version__", None),
                    "cuda_devices": ctx.toolchain.cuda_devices()}
        return publish_outcome(ctx, outcome, source=source, cameras_used=len(cams), versions=versions, keyframes_tar=keyframes_tar)
