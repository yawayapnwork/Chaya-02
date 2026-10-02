"""Stage 5: camera pose estimation with GLOMAP, falling back to COLMAP's incremental mapper.

Pipeline (all real COLMAP/GLOMAP command lines, executed through the stage runner so stdout/stderr and exit
codes are captured):
    colmap feature_extractor -> colmap {exhaustive,sequential}_matcher -> glomap mapper (or colmap mapper)
    -> colmap model_converter (TXT) -> poses.json

Nothing is simulated. If COLMAP is missing the stage fails with DEPENDENCY_UNAVAILABLE; GLOMAP is optional
(COLMAP alone can map). Only command construction and the poses parser are unit-tested without the tools;
executing the stage needs the toolchain and is covered by the gpu-marked tests, which skip when it is absent.
COLMAP flag names follow the 3.9/3.10 CLI (--SiftExtraction.*); newer releases renamed some options.

Camera model (review G-1). With a CAMERA_CALIBRATION input (a calibration declared in the capture metadata, rescaled
to the frames by FFMPEG_PREPROCESS) the feature extractor is given that model and its parameters
(--ImageReader.camera_model / --ImageReader.camera_params). Both mappers then use them as the starting point and may
refine focal length and distortion in bundle adjustment (their defaults; not overridden). Without one, COLMAP
self-calibrates a SIMPLE_RADIAL camera. Either way the camera that the poses belong to is read back from the model,
every parameter kept, and recorded in poses.json with where it came from; an unsupported model fails the stage.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import cv2
import numpy as np

from .. import archive
from ..camera_model import Camera, CameraModelError
from ..colmap_txt import parse_cameras_txt
from ..contract import ArtifactSpec, StageContext, StageError, StageResult
from .base import command_record, write_json

EXHAUSTIVE_MAX_FRAMES = 300


def build_pose_commands(*, colmap: str, glomap: str | None, database: Path, images: Path, sparse: Path, use_gpu: bool,
                        frame_count: int, mapper: str, num_threads: int = -1,
                        camera: Camera | None = None) -> dict[str, list[str]]:
    """Pure: the command lines of each step. mapper is 'glomap' or 'colmap'. num_threads > 0 bounds COLMAP's SIFT
    extraction and matching threads (Settings.colmap_num_threads); otherwise COLMAP's own default applies. camera is the
    declared calibration of the frames, or None to let COLMAP self-calibrate a SIMPLE_RADIAL camera."""
    gpu = "1" if use_gpu else "0"
    camera_args = (["--ImageReader.camera_model", camera.model, "--ImageReader.camera_params", camera.colmap_params()]
                   if camera is not None else ["--ImageReader.camera_model", "SIMPLE_RADIAL"])
    matcher = "exhaustive_matcher" if frame_count <= EXHAUSTIVE_MAX_FRAMES else "sequential_matcher"
    commands = {
        "feature_extractor": [colmap, "feature_extractor", "--database_path", str(database), "--image_path", str(images),
                              "--ImageReader.single_camera", "1", *camera_args,
                              "--SiftExtraction.use_gpu", gpu],
        "matcher": [colmap, matcher, "--database_path", str(database), "--SiftMatching.use_gpu", gpu],
    }
    if num_threads > 0:
        commands["feature_extractor"] += ["--SiftExtraction.num_threads", str(num_threads)]
        commands["matcher"] += ["--SiftMatching.num_threads", str(num_threads)]
    if mapper == "glomap":
        if not glomap:
            raise ValueError("glomap mapper requested but no glomap binary given")
        commands["mapper"] = [glomap, "mapper", "--database_path", str(database), "--image_path", str(images), "--output_path", str(sparse)]
    else:
        commands["mapper"] = [colmap, "mapper", "--database_path", str(database), "--image_path", str(images), "--output_path", str(sparse)]
    return commands


def parse_images_txt(text: str) -> list[dict[str, Any]]:
    """Parse COLMAP's images.txt: every other non-comment line is `IMAGE_ID QW QX QY QZ TX TY TZ CAMERA_ID NAME`."""
    poses: list[dict[str, Any]] = []
    lines = [ln for ln in text.splitlines() if ln.strip() and not ln.startswith("#")]
    for header in lines[::2]:  # the lines in between list 2D points
        parts = header.split()
        if len(parts) < 10:
            raise ValueError(f"malformed images.txt line: {header!r}")
        qw, qx, qy, qz, tx, ty, tz = map(float, parts[1:8])
        poses.append({"image_id": int(parts[0]), "name": parts[9], "camera_id": int(parts[8]),
                      "rotation_wxyz": [qw, qx, qy, qz], "translation": [tx, ty, tz]})
    return poses


def declared_camera(ctx: StageContext) -> tuple[Camera | None, dict[str, Any] | None]:
    """The frames' calibrated camera from a CAMERA_CALIBRATION input, or (None, None)."""
    inputs = ctx.inputs_of("CAMERA_CALIBRATION")
    if not inputs:
        return None, None
    doc = json.loads(inputs[0].path.read_text(encoding="utf-8"))
    try:
        return Camera.from_record(doc["frame_camera"]), doc
    except (CameraModelError, KeyError, TypeError) as exc:
        raise StageError(f"CAMERA_CALIBRATION {inputs[0].artifact_id} is unusable: {exc}",
                         code=getattr(exc, "code", "CAMERA_CALIBRATION_INVALID")) from exc


def camera_metadata(cameras: dict[int, Camera], declared: Camera | None, declared_doc: dict[str, Any] | None) -> dict[str, Any]:
    """Pure: what poses.json says about the cameras the poses belong to."""
    out: dict[str, Any] = {
        "calibration_source": "CAPTURE_METADATA" if declared is not None else "SFM_SELF_CALIBRATION",
        "cameras": {str(cid): cam.record() for cid, cam in sorted(cameras.items())},
        "capture_calibration": None,
    }
    if declared is not None:
        refined = {str(cid): {"model_changed": cam.model != declared.model,
                              "max_abs_param_change": (max(abs(a - b) for a, b in zip(cam.params, declared.params, strict=True))
                                                       if cam.model == declared.model else None)}
                   for cid, cam in sorted(cameras.items())}
        out["capture_calibration"] = {"frame_camera": declared.record(),
                                      "metadata_artifact_id": (declared_doc or {}).get("metadata_artifact_id"),
                                      "declared_source": (declared_doc or {}).get("declared_source"),
                                      "refinement_by_sfm": refined,
                                      "note": "the declared calibration seeds SfM; the mapper may refine it, and the cameras "
                                              "above (from the reconstructed model) are what the poses belong to"}
    return out


def _check_frame_sizes(frames: list[Path], camera: Camera) -> None:
    wrong = []
    for f in frames:
        img = cv2.imdecode(np.fromfile(str(f), dtype=np.uint8), cv2.IMREAD_COLOR)
        if img is None or img.shape[:2] != (camera.height, camera.width):
            wrong.append(f.name)
    if wrong:
        raise StageError(f"{len(wrong)} frame(s) are not the calibrated {camera.width}x{camera.height}",
                         code="CAMERA_CALIBRATION_INVALID", details={"frames": wrong[:20]})


def _model_dirs(sparse: Path) -> list[Path]:
    return sorted(p for p in sparse.iterdir() if p.is_dir() and (p / "images.bin").is_file()) if sparse.is_dir() else []


class PoseEstimation:
    name = "POSE_ESTIMATION"

    def run(self, ctx: StageContext) -> StageResult:
        ctx.toolchain.require(["colmap"], stage=self.name)  # GLOMAP is optional: COLMAP can map on its own
        archives = ctx.inputs_of("FRAME_ARCHIVE_ANON")
        if not archives:
            raise StageError("no anonymised frame archive was provided by the previous stage", code="INPUT_INVALID")
        colmap = ctx.toolchain.colmap().path
        glomap = ctx.toolchain.glomap()
        images_dir = ctx.workdir / "images"
        frames = archive.unpack(archives[0].path, images_dir)
        declared, declared_doc = declared_camera(ctx)
        if declared is not None:
            _check_frame_sizes(frames, declared)
        database, sparse = ctx.workdir / "database.db", ctx.workdir / "sparse"
        sparse.mkdir()
        use_gpu = ctx.toolchain.gpu_present()

        first_mapper = "glomap" if glomap.available else "colmap"
        steps = build_pose_commands(colmap=colmap, glomap=glomap.path, database=database, images=images_dir, sparse=sparse,
                                    use_gpu=use_gpu, frame_count=len(frames), mapper=first_mapper,
                                    num_threads=ctx.settings.colmap_num_threads, camera=declared)
        ctx.runner.run(steps["feature_extractor"], error_code="FEATURE_EXTRACTION_FAILED", timeout=7200)
        ctx.runner.run(steps["matcher"], error_code="FEATURE_MATCHING_FAILED", timeout=7200)

        used, fallback = first_mapper, False
        try:
            ctx.runner.run(steps["mapper"], error_code="MAPPING_FAILED", timeout=14400)
            if not _model_dirs(sparse):
                raise StageError(f"{first_mapper} finished but produced no model", code="MAPPING_FAILED")
        except StageError as exc:
            if first_mapper == "glomap" and exc.code != "TIME_LIMIT_EXCEEDED":
                ctx.logger.warning("GLOMAP failed; falling back to COLMAP mapper", extra={"reason": exc.message})
                steps = build_pose_commands(colmap=colmap, glomap=None, database=database, images=images_dir, sparse=sparse,
                                            use_gpu=use_gpu, frame_count=len(frames), mapper="colmap",
                                            num_threads=ctx.settings.colmap_num_threads, camera=declared)
                ctx.runner.run(steps["mapper"], error_code="MAPPING_FAILED", timeout=14400)
                used, fallback = "colmap", True
            else:
                raise
        models = _model_dirs(sparse)
        if not models:
            raise StageError("no sparse model was produced", code="MAPPING_FAILED")
        model = models[0]

        txt = ctx.workdir / "txt"
        txt.mkdir()
        ctx.runner.run([colmap, "model_converter", "--input_path", str(model), "--output_path", str(txt), "--output_type", "TXT"],
                       error_code="MODEL_CONVERSION_FAILED", timeout=600)
        poses = parse_images_txt((txt / "images.txt").read_text(encoding="utf-8"))
        try:
            cameras = parse_cameras_txt((txt / "cameras.txt").read_text(encoding="utf-8"))
        except CameraModelError as exc:
            raise StageError(f"the reconstructed model's camera cannot be used: {exc}", code=exc.code) from exc
        cameras_doc = camera_metadata(cameras, declared, declared_doc)
        ratio = len(poses) / len(frames)
        if len(poses) < 3 or ratio < ctx.settings.min_registered_ratio:
            raise StageError(f"only {len(poses)} of {len(frames)} frames were registered ({ratio:.0%}); need at least "
                             f"{ctx.settings.min_registered_ratio:.0%}", code="POSE_ESTIMATION_INSUFFICIENT",
                             details={"registered": len(poses), "frames": len(frames), "mapper": used})
        sparse_tar = ctx.workdir / "sparse-model.tar"
        archive.pack(model, sparse_tar)
        poses_json = write_json(ctx.workdir / "poses.json", {"mapper": used, "fallback_used": fallback, "frames": len(frames),
                                                              "registered": len(poses), "poses": poses, **cameras_doc})
        return StageResult(
            "SUCCEEDED", command_record(ctx, {"mapper_used": used, "fallback_used": fallback, "gpu": use_gpu,
                                           "calibration_source": cameras_doc["calibration_source"],
                                           "camera_models": sorted({c.model for c in cameras.values()})}),
            ctx.runner.last_exit_status(),
            [ArtifactSpec("SPARSE_MODEL", sparse_tar, "sparse-model.tar", "application/x-tar"),
             ArtifactSpec("POSES", poses_json, "poses.json", "application/json")])
