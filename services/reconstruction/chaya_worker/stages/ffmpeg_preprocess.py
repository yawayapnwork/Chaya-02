"""Stage 2: FFmpeg preprocessing. Raw videos become still frames; raw images are normalised.

The frames may show people and screens, so they are published as PII-flagged artifacts: only the privacy
stage may read them, and the control plane deletes them once it has finished.
"""

from __future__ import annotations

from pathlib import Path

import cv2
import numpy as np

from .. import archive
from ..contract import ArtifactSpec, StageContext, StageError, StageResult
from .base import command_record, write_json


def build_extract_command(ffmpeg: str, video: Path, out_pattern: Path, fps: float, max_height: int, max_frames: int) -> list[str]:
    """Pure: the exact FFmpeg command used to extract frames. Never upscales."""
    return [ffmpeg, "-hide_banner", "-nostdin", "-y", "-i", str(video),
            "-vf", f"fps={fps:g},scale=-2:'min({max_height},ih)'",
            "-frames:v", str(max_frames), "-q:v", "2", str(out_pattern)]


class FfmpegPreprocess:
    name = "FFMPEG_PREPROCESS"

    def run(self, ctx: StageContext) -> StageResult:
        videos = ctx.inputs_of("RAW_VIDEO")
        images = ctx.inputs_of("RAW_IMAGE")
        if videos:
            ctx.toolchain.require(["ffmpeg"], stage=self.name)
        frames_dir = ctx.workdir / "frames"
        frames_dir.mkdir()
        s = ctx.settings
        sources = []
        for vi, video in enumerate(videos):
            pattern = frames_dir / f"v{vi:02d}-%06d.jpg"
            ctx.runner.run(build_extract_command(ctx.toolchain.ffmpeg().path, video.path, pattern, s.frame_fps, s.max_frame_height,
                                                 s.max_frames_per_video), error_code="FFMPEG_FAILED", timeout=3600)
            produced = sorted(p.name for p in frames_dir.glob(f"v{vi:02d}-*.jpg"))
            sources.append({"artifact_id": video.artifact_id, "kind": "RAW_VIDEO", "frames": len(produced)})
            ctx.logger.info("frames extracted", extra={"artifact_id": video.artifact_id, "frames": len(produced)})
        for ii, image in enumerate(images):
            img = cv2.imdecode(np.fromfile(str(image.path), dtype=np.uint8), cv2.IMREAD_COLOR)
            if img is None:
                raise StageError(f"image {image.artifact_id} cannot be decoded", code="INPUT_INVALID")
            if img.shape[0] > s.max_frame_height:
                scale = s.max_frame_height / img.shape[0]
                img = cv2.resize(img, (round(img.shape[1] * scale), s.max_frame_height), interpolation=cv2.INTER_AREA)
            # Re-encoding drops EXIF (GPS, device ids) along with the original container.
            cv2.imwrite(str(frames_dir / f"i{ii:04d}.jpg"), img, [cv2.IMWRITE_JPEG_QUALITY, 95])
            sources.append({"artifact_id": image.artifact_id, "kind": "RAW_IMAGE", "frames": 1})

        names = sorted(p.name for p in frames_dir.glob("*.jpg"))
        if not names:
            raise StageError("no frames were produced from the input", code="NO_FRAMES", details={"sources": sources})
        archive_path = ctx.workdir / "frames.tar"
        archive.pack(frames_dir, archive_path)
        manifest = write_json(ctx.workdir / "frames-manifest.json", {"count": len(names), "sources": sources, "frames": names})
        return StageResult(
            "SUCCEEDED", command_record(ctx), ctx.runner.last_exit_status(),
            [ArtifactSpec("FRAME_ARCHIVE", archive_path, "frames.tar", "application/x-tar", contains_pii=True),
             ArtifactSpec("FRAME_MANIFEST", manifest, "frames-manifest.json", "application/json")])
