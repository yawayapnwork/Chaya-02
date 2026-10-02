"""Stage 2: FFmpeg preprocessing. Raw videos become still frames; raw images are normalised.

The frames may show people and screens, so they are published as PII-flagged artifacts: only the privacy
stage may read them, and the control plane deletes them once it has finished.

When the capture metadata declares a camera calibration, the frames' camera is published as CAMERA_CALIBRATION (not
PII, so every later stage receives it). Frames are downscaled here, so the calibration is rescaled with them: focal
lengths and principal point by the actual output/input size ratio per axis, distortion coefficients unchanged (they act
on normalised coordinates). Nothing after this stage resizes frames.
"""

from __future__ import annotations

import math
from pathlib import Path

import cv2
import numpy as np

from .. import archive
from ..camera_model import CameraModelError
from ..contract import ArtifactSpec, StageContext, StageError, StageResult
from .base import capture_calibration, command_record, write_json


def build_extract_command(ffmpeg: str, video: Path, out_pattern: Path, fps: float, max_height: int, max_frames: int) -> list[str]:
    """Pure: the exact FFmpeg command used to extract frames. Never upscales."""
    return [ffmpeg, "-hide_banner", "-nostdin", "-y", "-i", str(video),
            "-vf", f"fps={fps:g},scale=-2:'min({max_height},ih)'",
            "-frames:v", str(max_frames), "-q:v", "2", str(out_pattern)]


def frame_calibration(calibration: dict, frame_sizes: dict[str, tuple[int, int]]) -> dict:
    """Pure: the CAMERA_CALIBRATION document for frames of the given sizes (per source artifact). Every frame must come
    out the same size: the pipeline reconstructs with one camera."""
    camera = calibration["camera"]
    sizes = set(frame_sizes.values())
    if len(sizes) != 1:
        raise StageError(f"frames have {len(sizes)} different sizes {sorted(sizes)}; one calibrated camera cannot describe them",
                         code="CAMERA_CALIBRATION_INVALID", details={"frame_sizes": {k: list(v) for k, v in frame_sizes.items()}})
    width, height = sizes.pop()
    sx, sy = width / camera.width, height / camera.height
    try:
        frame_camera = camera.scaled(sx, sy, width, height)
    except CameraModelError as exc:
        raise StageError(str(exc), code=exc.code) from exc
    return {
        "source": "CAPTURE_METADATA",
        "metadata_artifact_id": calibration["metadata_artifact_id"],
        "declared_source": calibration["declared_source"],
        "declared": calibration["declared"],
        "declared_pixel_coordinate_origin": calibration["pixel_coordinate_origin"],
        "capture_camera": camera.record(),
        "frame_camera": frame_camera.record(),
        "frame_scale": {"x": sx, "y": sy, "uniform": math.isclose(sx, sy, rel_tol=1e-9)},
        "undistorted": False,
        "note": "the frames are as captured (distorted); undistortion happens where a pinhole camera is needed",
    }


class FfmpegPreprocess:
    name = "FFMPEG_PREPROCESS"

    def run(self, ctx: StageContext) -> StageResult:
        videos = ctx.inputs_of("RAW_VIDEO")
        images = ctx.inputs_of("RAW_IMAGE")
        if videos:
            ctx.toolchain.require(["ffmpeg"], stage=self.name)
        calibration = capture_calibration(ctx)
        frames_dir = ctx.workdir / "frames"
        frames_dir.mkdir()
        s = ctx.settings
        sources, frame_sizes = [], {}
        for vi, video in enumerate(videos):
            pattern = frames_dir / f"v{vi:02d}-%06d.jpg"
            ctx.runner.run(build_extract_command(ctx.toolchain.ffmpeg().path, video.path, pattern, s.frame_fps, s.max_frame_height,
                                                 s.max_frames_per_video), error_code="FFMPEG_FAILED", timeout=3600)
            produced = sorted(p.name for p in frames_dir.glob(f"v{vi:02d}-*.jpg"))
            sources.append({"artifact_id": video.artifact_id, "kind": "RAW_VIDEO", "frames": len(produced)})
            if produced:
                first = cv2.imdecode(np.fromfile(str(frames_dir / produced[0]), dtype=np.uint8), cv2.IMREAD_COLOR)
                frame_sizes[video.artifact_id] = (int(first.shape[1]), int(first.shape[0]))
            ctx.logger.info("frames extracted", extra={"artifact_id": video.artifact_id, "frames": len(produced)})
        for ii, image in enumerate(images):
            img = cv2.imdecode(np.fromfile(str(image.path), dtype=np.uint8), cv2.IMREAD_COLOR)
            if img is None:
                raise StageError(f"image {image.artifact_id} cannot be decoded", code="INPUT_INVALID")
            if calibration is not None and img.shape[:2] != (calibration["camera"].height, calibration["camera"].width):
                raise StageError(f"image {image.artifact_id} is {img.shape[1]}x{img.shape[0]}, not the calibrated "
                                 f"{calibration['camera'].width}x{calibration['camera'].height}", code="CAMERA_CALIBRATION_INVALID")
            if img.shape[0] > s.max_frame_height:
                scale = s.max_frame_height / img.shape[0]
                img = cv2.resize(img, (round(img.shape[1] * scale), s.max_frame_height), interpolation=cv2.INTER_AREA)
            # Re-encoding drops EXIF (GPS, device ids) along with the original container.
            cv2.imwrite(str(frames_dir / f"i{ii:04d}.jpg"), img, [cv2.IMWRITE_JPEG_QUALITY, 95])
            frame_sizes[image.artifact_id] = (int(img.shape[1]), int(img.shape[0]))
            sources.append({"artifact_id": image.artifact_id, "kind": "RAW_IMAGE", "frames": 1})

        names = sorted(p.name for p in frames_dir.glob("*.jpg"))
        if not names:
            raise StageError("no frames were produced from the input", code="NO_FRAMES", details={"sources": sources})
        artifacts = []
        if calibration is not None:
            doc = frame_calibration(calibration, frame_sizes)
            artifacts.append(ArtifactSpec("CAMERA_CALIBRATION", write_json(ctx.workdir / "camera-calibration.json", doc),
                                          "camera-calibration.json", "application/json"))
        archive_path = ctx.workdir / "frames.tar"
        archive.pack(frames_dir, archive_path)
        manifest = write_json(ctx.workdir / "frames-manifest.json", {
            "count": len(names), "sources": sources, "frames": names,
            "camera_calibration": "CAPTURE_METADATA" if calibration is not None else "NOT_PROVIDED"})
        return StageResult(
            "SUCCEEDED", command_record(ctx), ctx.runner.last_exit_status(),
            [ArtifactSpec("FRAME_ARCHIVE", archive_path, "frames.tar", "application/x-tar", contains_pii=True),
             ArtifactSpec("FRAME_MANIFEST", manifest, "frames-manifest.json", "application/json"), *artifacts])
