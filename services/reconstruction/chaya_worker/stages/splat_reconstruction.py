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

Lens distortion (review G-1): gsplat renders a pinhole camera, so every frame of a camera with non-zero distortion
coefficients is undistorted first (chaya_worker.camera_model.FrameRectifier) with the camera the SfM model holds, and
trained against the pinhole camera that undistortion produces. Frames of a distortion-free camera are used as they are.
TRAINING_CAMERAS (training-cameras.json) records, per camera, the SfM camera (model, size, focal lengths, principal
point, distortion model and coefficients), whether its frames were undistorted, and the pinhole camera trained with.

Privacy masks (review G-2). The frames are anonymised, and an anonymised region is a blur or a solid block, not the
scene. In a privacy-enabled run PRIVACY_MASKS is a required input (chaya_worker.privacy.masks). Each frame's mask is
undistorted with the frame (a training pixel is valid only if every source pixel it is resampled from is valid); the
masked pixels are set to zero in the training image and carried as the camera's `valid` mask, which the loss honours
(chaya_worker.splat_training.l1_dssim_loss). So the fills are neither colour nor geometry supervision: a region seen
only through a fill keeps what the other views say about it, or nothing. The masks are part of the checkpoint's input
identity. SPLAT_TRAINING_REPORT records how much of the training signal was masked.

Needs torch + gsplat + a CUDA device (gsplat's rasteriser is CUDA-only) and COLMAP (to convert the binary sparse model
to TEXT). If any of that is missing the stage fails with DEPENDENCY_UNAVAILABLE and nothing is produced. There is no CPU
or "fake" fallback path.

Preflight (chaya_worker.splat_preflight) runs first, before any archive is unpacked: the environment (packages and their
versions, a CUDA build of torch, a usable device of sufficient compute capability, gsplat's compiled CUDA kernels,
COLMAP), the settings, and any offered checkpoint. Every environment problem is reported at once in
`details.preflight`, with a fix for each. Training runs on the device preflight selected, not on torch's default device.

Failures during training become structured stage failures, never a crashed worker (the orchestrator reports them and the
lease ends with the report): CANCELLED, CAMERA_CONVENTION_INVALID, SPLAT_TRAINING_DIVERGED, GSPLAT_INCOMPATIBLE,
SPLAT_GPU_OUT_OF_MEMORY, GPU_RUNTIME_ERROR, SPLAT_CHECKPOINT_WRITE_FAILED (`training_failure`). Exporting the cloud is
checked too: SPLAT_EXPORT_FAILED (the PLY could not be written), SPLAT_EXPORT_INVALID (it does not read back), and
SPLAT_INVALID for a cloud the viewer contract would refuse (packages/contracts/viewer/ksplat-contract.json).
"""

from __future__ import annotations

import json
import time
from pathlib import Path
from typing import Any

import numpy as np

from .. import archive, splat_preflight
from ..camera_model import Camera, CameraModelError, FrameRectifier
from ..colmap_txt import parse_cameras_txt, parse_points3d_txt
from ..contract import ArtifactSpec, StageContext, StageError, StageResult
from ..errors import DependencyError
from ..ply import read_ply, write_ply
from ..privacy import masks as privacy_masks
from ..splat_color import rgb_bytes_to_sh0
from .base import command_record, sha256_file, write_json


def training_failure(exc: BaseException) -> StageError | None:
    """Pure: the structured stage failure for an exception raised by training, or None for one that is a bug (which the
    orchestrator then reports as INTERNAL_ERROR with its traceback)."""
    import torch  # noqa: PLC0415

    from .. import splat_training as st  # noqa: PLC0415

    if isinstance(exc, InterruptedError):
        return StageError("cancelled by the control plane", code="CANCELLED")
    if isinstance(exc, st.CameraConventionError):
        return StageError(str(exc), code="CAMERA_CONVENTION_INVALID")
    if isinstance(exc, st.TrainingDiverged):
        return StageError(f"training diverged: {exc}; no splat or checkpoint of the diverged state is published",
                          code="SPLAT_TRAINING_DIVERGED")
    if isinstance(exc, st.GsplatContractError):
        return StageError(f"{exc}. Install gsplat {st.GSPLAT_VALIDATED_VERSION} (the GPU worker image)", code="GSPLAT_INCOMPATIBLE")
    if isinstance(exc, torch.cuda.OutOfMemoryError):
        return StageError("the GPU ran out of memory during training. Lower GSPLAT_MAX_GAUSSIANS or the frame resolution "
                          "(FFMPEG_PREPROCESS), or run on a GPU with more memory, then retry", code="SPLAT_GPU_OUT_OF_MEMORY",
                          details={"error": str(exc)[:500]})
    if isinstance(exc, OSError):
        return StageError(f"the training checkpoint could not be written: {exc}. Check the worker's free disk space, then retry",
                          code="SPLAT_CHECKPOINT_WRITE_FAILED")
    text = str(exc)
    if isinstance(exc, RuntimeError) and any(m in text for m in ("CUDA", "cuda", "CUBLAS", "cudaError", "device-side")):
        return StageError(f"the GPU failed during training: {text[:300]}. The worker keeps running; if this repeats, restart "
                          "it and check the GPU with nvidia-smi", code="GPU_RUNTIME_ERROR", details={"error": text[:2000]})
    return None


def _release_gpu_memory() -> None:
    try:
        import torch  # noqa: PLC0415

        if torch.cuda.is_available():
            torch.cuda.empty_cache()
    except Exception:  # noqa: BLE001, S110 - best effort after a failure that is already being reported
        pass


def quat_wxyz_to_rotmat(q: np.ndarray) -> np.ndarray:
    """COLMAP's images.txt quaternion (wxyz, world-to-camera) to a 3x3 rotation matrix."""
    w, x, y, z = q / np.linalg.norm(q)
    return np.array([
        [1 - 2 * (y * y + z * z), 2 * (x * y - w * z), 2 * (x * z + w * y)],
        [2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x)],
        [2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y)],
    ])


def build_cameras(poses: list[dict[str, Any]], camera_models: dict[int, Camera]) -> list[dict[str, Any]]:
    """Pure: viewmat (world-to-camera 4x4) and the full camera (distortion included) for every registered image. There
    is deliberately no pinhole `K` here: one is only valid for a frame after FrameRectifier has undistorted it."""
    cams = []
    for p in poses:
        camera = camera_models.get(p["camera_id"])
        if camera is None:
            continue
        R = quat_wxyz_to_rotmat(np.array(p["rotation_wxyz"], dtype=np.float64))
        t = np.array(p["translation"], dtype=np.float64)
        viewmat = np.eye(4, dtype=np.float64)
        viewmat[:3, :3], viewmat[:3, 3] = R, t
        cams.append({"name": p["name"], "camera_id": p["camera_id"], "viewmat": viewmat, "camera": camera,
                     "width": camera.width, "height": camera.height})
    return cams


def prepare_training_cameras(cams: list[dict[str, Any]], images_dir: Path, rectifier: FrameRectifier,
                             masks: privacy_masks.FrameMasks | None = None) -> list[dict[str, Any]]:
    """Loads each camera's frame, undistorts it if its camera has distortion, and sets the pinhole `K` and RGB `image`
    (float32 sRGB values in [0, 1]) the rasteriser is trained against. Cameras without a frame on disk are dropped; a
    frame whose size is not its camera's fails the stage. With `masks`, each frame's privacy mask is undistorted with
    it and set as the camera's `valid` (bool (H, W)); the image's masked pixels are zeroed, so no anonymised pixel
    reaches the optimiser, and `masked_fraction` records how much was excluded."""
    import cv2  # noqa: PLC0415

    ready = []
    for cam in cams:
        path = images_dir / cam["name"]
        if not path.is_file():
            continue
        img = cv2.imdecode(np.fromfile(str(path), dtype=np.uint8), cv2.IMREAD_COLOR)
        if img is None:
            continue
        valid = masks.valid(cam["name"], img.shape[:2]) if masks is not None else None
        try:
            img, pinhole = rectifier.rectify(cam["camera"], img)
            if valid is not None:
                valid = rectifier.rectify_mask(cam["camera"], valid)
        except CameraModelError as exc:
            raise StageError(f"frame {cam['name']}: {exc}", code=exc.code) from exc
        cam["K"], cam["width"], cam["height"] = pinhole.K, pinhole.width, pinhole.height
        image = cv2.cvtColor(img, cv2.COLOR_BGR2RGB).astype(np.float32) / 255.0  # sRGB values in [0, 1]
        if valid is not None:
            image[~valid] = 0.0
            cam["valid"] = valid
            cam["masked_fraction"] = float((~valid).mean())
        cam["image"] = image
        ready.append(cam)
    return ready


def training_cameras_doc(rectifier: FrameRectifier, poses_doc: dict[str, Any]) -> dict[str, Any]:
    """Pure: the TRAINING_CAMERAS document."""
    return {
        "calibration_source": poses_doc.get("calibration_source", "UNRECORDED (poses.json predates camera provenance)"),
        "capture_calibration": poses_doc.get("capture_calibration"),
        "cameras": rectifier.records(),
        "pose_frame": "world-to-camera transforms are the POSES artifact's, unchanged; they are the training cameras' poses",
    }


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
                                          if i.kind in ("SPARSE_MODEL", "POSES", "FRAME_ARCHIVE_ANON", privacy_masks.KIND)),
                                         key=lambda d: d["kind"]))


def privacy_mask_summary(cams: list[dict[str, Any]]) -> dict[str, Any]:
    """Pure: how much of the training signal the privacy masks excluded."""
    fractions = [c["masked_fraction"] for c in cams if "valid" in c]
    if not fractions:
        return {"applied": False}
    return {"applied": True, "frames": len(fractions), "frames_with_masked_pixels": sum(1 for f in fractions if f > 0),
            "masked_fraction": round(float(np.mean(fractions)), 6), "max_frame_masked_fraction": round(max(fractions), 6),
            "policy": "masked pixels are zeroed and excluded from L1 and D-SSIM; they are not supervision"}


def export_mismatch(cloud, ply_path: Path) -> str | None:
    """Pure apart from reading the file: None when the PLY reads back bit-identical to `cloud`, else what differs."""
    back = read_ply(ply_path)
    if len(back) != len(cloud):
        return f"{len(back)} Gaussians read back, {len(cloud)} written"
    for name in ("positions", "scales_log", "rotations_wxyz", "opacity_logit", "colors_dc"):
        if not np.array_equal(getattr(back, name), getattr(cloud, name)):
            return f"{name} differs"
    return None


def publish_outcome(ctx: StageContext, outcome, *, source: dict[str, Any], cameras_used: int, versions: dict[str, Any],
                    keyframes_tar=None, training_cameras: dict[str, Any] | None = None,
                    privacy: dict[str, Any] | None = None) -> StageResult:
    """Turns a training outcome into the stage result: COMPLETED -> SUCCEEDED with SPLAT; PARTIAL -> FAILED with
    TIME_LIMIT_EXCEEDED and SPLAT_PARTIAL (partial=True). The report and checkpoint go out either way. Pure apart from
    writing files into ctx.workdir, so it is tested without a GPU (tests/splat).

    Nothing is published as a splat unless it is a valid trained state: at least one iteration ran, every configured
    iteration for SPLAT, the parameters pass validate_trained_state, and the written PLY reads back bit-identical. A
    COMPLETED outcome that fails any of that is FAILED (SPLAT_NOT_TRAINED / SPLAT_INVALID / SPLAT_EXPORT_INVALID) with
    the report only: no splat and no checkpoint of an invalid state."""
    from ..ksplat import MAX_BYTES, MIN_SPLAT_COUNT, encoded_size  # noqa: PLC0415
    from ..splat_training import SH_DEGREE, STATUS_COMPLETED, STATUS_PARTIAL, to_cloud, validate_trained_state  # noqa: PLC0415

    artifacts: list[ArtifactSpec] = []
    splat_name = splat_sha = None
    invalid: tuple[str, str] | None = None
    if outcome.status == STATUS_COMPLETED and outcome.completed_iterations < outcome.target_iterations:
        invalid = ("SPLAT_INVALID", f"training reported COMPLETED after {outcome.completed_iterations} of "
                                    f"{outcome.target_iterations} iterations")
    elif outcome.completed_iterations == 0 and outcome.status == STATUS_COMPLETED:
        invalid = ("SPLAT_NOT_TRAINED", "no training iteration ran (0 configured iterations): the SfM seed is not a reconstruction")
    elif outcome.completed_iterations > 0:
        problems = validate_trained_state(outcome.params)
        if problems:
            invalid = ("SPLAT_INVALID", "the trained state is not a usable cloud: " + "; ".join(problems))
        elif not MIN_SPLAT_COUNT <= outcome.gaussian_count or encoded_size(outcome.gaussian_count) > MAX_BYTES:
            invalid = ("SPLAT_INVALID", f"{outcome.gaussian_count} Gaussians would make a {encoded_size(outcome.gaussian_count)}-byte "
                                        f".ksplat; the viewer contract allows {MIN_SPLAT_COUNT} splat(s) to {MAX_BYTES} bytes")
    if invalid is None and outcome.completed_iterations > 0:  # a cloud that was never optimised is the SfM seed, not a reconstruction
        cloud = to_cloud(outcome.params)
        partial = outcome.status == STATUS_PARTIAL
        splat_name = "splat-partial.ply" if partial else "splat.ply"
        try:
            ply_path = write_ply(cloud, ctx.workdir / splat_name)
            mismatch = export_mismatch(cloud, ply_path)
        except OSError as exc:
            invalid, splat_name = ("SPLAT_EXPORT_FAILED", f"the trained cloud could not be written: {exc}. Check the worker's "
                                                          "free disk space, then retry"), None
        else:
            if mismatch:
                invalid, splat_name = ("SPLAT_EXPORT_INVALID", f"the written PLY does not read back as the trained cloud: {mismatch}"), None
            else:
                splat_sha = sha256_file(ply_path)
                artifacts.append(ArtifactSpec("SPLAT_PARTIAL" if partial else "SPLAT", ply_path, splat_name, "application/octet-stream",
                                              partial=partial))
    checkpoint_sha = None
    if invalid is None and outcome.checkpoint_path is not None and outcome.checkpoint_path.is_file():
        checkpoint_sha = sha256_file(outcome.checkpoint_path)
        artifacts.append(ArtifactSpec("SPLAT_CHECKPOINT", outcome.checkpoint_path, "splat-checkpoint.pt", "application/octet-stream"))
    if keyframes_tar is not None:
        artifacts.append(ArtifactSpec("KEYFRAME_RENDERS", keyframes_tar, "keyframes.tar", "application/x-tar"))
    if training_cameras is not None:
        artifacts.append(ArtifactSpec("TRAINING_CAMERAS", write_json(ctx.workdir / "training-cameras.json", training_cameras),
                                      "training-cameras.json", "application/json"))

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
        "privacy_masks": privacy if privacy is not None else {"applied": False},
        "camera_calibration": None if training_cameras is None else {
            "calibration_source": training_cameras["calibration_source"],
            "undistorted": [c["undistorted"] for c in training_cameras["cameras"]],
            "artifact": "training-cameras.json"},
        "densification": [e.__dict__ for e in outcome.densify_events], "opacity_resets": outcome.opacity_resets,
        "loss_history": outcome.history[:: max(1, len(outcome.history) // 200)] + outcome.history[-1:],
        "colour_convention": "chaya_worker.splat_color: rgb = SH_C0 * f_dc + 0.5, sRGB-encoded values end to end",
        "sh_degree": SH_DEGREE,
        "position_lr_schedule": {"initial": outcome.config.get("lr_position"), "final": outcome.config.get("lr_position_final"),
                                 "decay_steps": outcome.config.get("lr_position_decay_steps"),
                                 "form": "log-linear (INRIA get_expon_lr_func), times the scene extent, held after decay_steps"},
        "validation": {"valid": invalid is None, "code": invalid[0] if invalid else None,
                       "message": invalid[1] if invalid else None},
    })
    artifacts.append(ArtifactSpec("SPLAT_TRAINING_REPORT", report, "splat-training-report.json", "application/json"))
    command = command_record(ctx, {**summary, "cameras_used": cameras_used})

    if invalid is not None:
        return StageResult("FAILED", command, ctx.runner.last_exit_status(), artifacts, invalid[0], invalid[1], summary)
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
        s = ctx.settings
        preflight = splat_preflight.environment_report(
            ctx.toolchain, min_compute_capability=s.gsplat_min_compute_capability, allow_jit=s.gsplat_allow_jit_backend,
            lock=splat_preflight.load_lock(splat_preflight.DEFAULT_LOCK_PATH))
        if not preflight["ok"]:
            raise DependencyError(
                f"{self.name} cannot run on this worker: " + "; ".join(p["message"] for p in preflight["problems"]),
                details={"missing": splat_preflight.missing_names(preflight), "preflight": preflight})

        import cv2
        import gsplat
        import torch

        from .. import ksplat
        from .. import splat_training as st

        config = st.TrainingConfig.from_settings(s)
        bad_config = splat_preflight.config_problems(config, max_ksplat_bytes=ksplat.MAX_BYTES, encoded_size=ksplat.encoded_size)
        if bad_config:
            raise StageError("; ".join(p.message for p in bad_config), code="CONFIG_INVALID",
                             details={"problems": [p.as_dict() for p in bad_config]})

        sparse_archives = ctx.inputs_of("SPARSE_MODEL")
        poses_inputs = ctx.inputs_of("POSES")
        frame_archives = ctx.inputs_of("FRAME_ARCHIVE_ANON")
        if not sparse_archives or not poses_inputs or not frame_archives:
            raise StageError("SPLAT_RECONSTRUCTION needs SPARSE_MODEL, POSES and FRAME_ARCHIVE_ANON from POSE_ESTIMATION",
                             code="INPUT_INVALID")

        index = preflight["selected_device"]["index"]
        device = torch.device("cuda", index)
        torch.cuda.set_device(device)
        source = training_source(ctx.order, ctx.inputs)
        resume = None
        checkpoints = ctx.inputs_of("SPLAT_CHECKPOINT")
        if checkpoints:  # checked before any frame is prepared: a checkpoint of another job fails in seconds
            try:
                resume = st.load_checkpoint(checkpoints[0].path, config=config, source=source, device=device)
            except st.CheckpointCorrupt as exc:
                raise StageError(f"{exc}. Retry without it (the run starts from the SfM seed)", code="CHECKPOINT_CORRUPT",
                                 details={"checkpoint": checkpoints[0].artifact_id}) from exc
            except st.CheckpointMismatch as exc:
                raise StageError(str(exc), code="CHECKPOINT_MISMATCH", details={"checkpoint": checkpoints[0].artifact_id}) from exc
            ctx.logger.info("resuming from checkpoint", extra={"iteration": resume[0], "gaussians": len(resume[1]["means"])})

        sparse_dir = ctx.workdir / "sparse-bin"
        archive.unpack(sparse_archives[0].path, sparse_dir)
        txt_dir = ctx.workdir / "sparse-txt"
        txt_dir.mkdir()
        colmap = ctx.toolchain.colmap().path
        ctx.runner.run([colmap, "model_converter", "--input_path", str(sparse_dir), "--output_path", str(txt_dir),
                        "--output_type", "TXT"], error_code="MODEL_CONVERSION_FAILED", timeout=600)
        try:
            cameras_model = parse_cameras_txt((txt_dir / "cameras.txt").read_text(encoding="utf-8"))
        except CameraModelError as exc:
            raise StageError(f"the sparse model's camera cannot be used: {exc}", code=exc.code) from exc
        points = parse_points3d_txt((txt_dir / "points3D.txt").read_text(encoding="utf-8"))
        too_few = splat_preflight.input_problems(posed_frames=None, sfm_points=len(points["ids"]), min_frames=s.gsplat_min_training_frames)
        if too_few:
            raise StageError(too_few[0].message, code="SPLAT_INIT_INSUFFICIENT",
                             details={"points": len(points["ids"]), "problems": [p.as_dict() for p in too_few]})

        poses_doc = json.loads(poses_inputs[0].path.read_text(encoding="utf-8"))
        cams = build_cameras(poses_doc["poses"], cameras_model)
        images_dir = ctx.workdir / "images"
        archive.unpack(frame_archives[0].path, images_dir)
        masks = privacy_masks.from_inputs(ctx)
        rectifier = FrameRectifier()
        cams = prepare_training_cameras(cams, images_dir, rectifier, masks)
        too_few = splat_preflight.input_problems(posed_frames=len(cams), sfm_points=len(points["ids"]),
                                                 min_frames=s.gsplat_min_training_frames)
        if too_few:
            raise StageError(too_few[0].message, code="SPLAT_INIT_INSUFFICIENT",
                             details={"posed_frames_with_images": len(cams), "problems": [p.as_dict() for p in too_few]})

        torch.manual_seed(config.seed)
        xyz = np.array(points["xyz"], dtype=np.float32)
        params = st.init_params(xyz, rgb_bytes_to_sh0(np.array(points["rgb"])), initial_scale_log(xyz), device)

        keyframes_dir = ctx.workdir / "keyframes"
        keyframes_dir.mkdir()

        def keyframe(it: int, pred) -> None:
            if it % max(1, s.gsplat_keyframe_every) == 0 or it == config.iterations - 1:
                frame_uint8 = (pred.clamp(0, 1).cpu().numpy() * 255).astype(np.uint8)
                cv2.imwrite(str(keyframes_dir / f"iter-{it:06d}.png"), cv2.cvtColor(frame_uint8, cv2.COLOR_RGB2BGR))

        started = time.time()
        try:
            outcome = st.train(params=params, cams=cams, rasterize=st.gsplat_rasterizer(device), config=config,
                               loss_fn=st.l1_dssim_loss, checkpoint_path=ctx.workdir / "splat-checkpoint.pt", source=source,
                               deadline=ctx.deadline, stop_margin_seconds=s.gsplat_stop_margin_seconds, clock=time.time,
                               is_cancelled=ctx.cancelled.is_set, resume=resume, on_iteration=keyframe)
        except Exception as exc:
            failure = training_failure(exc)
            if failure is None:
                raise
            _release_gpu_memory()
            raise failure from exc
        training_seconds = round(time.time() - started, 3)

        keyframes_tar = ctx.workdir / "keyframes.tar"
        archive.pack(keyframes_dir, keyframes_tar)
        versions = {"torch": torch.__version__, "torch_cuda": torch.version.cuda, "gsplat": getattr(gsplat, "__version__", None),
                    "gsplat_validated": st.GSPLAT_VALIDATED_VERSION, "gsplat_backend": preflight["versions"].get("gsplat_backend"),
                    "cuda_devices": ctx.toolchain.cuda_devices(), "training_device": preflight["selected_device"],
                    "training_seconds": training_seconds, "lock": preflight["lock"]}
        return publish_outcome(ctx, outcome, source=source, cameras_used=len(cams), versions=versions, keyframes_tar=keyframes_tar,
                               training_cameras=training_cameras_doc(rectifier, poses_doc), privacy=privacy_mask_summary(cams))
