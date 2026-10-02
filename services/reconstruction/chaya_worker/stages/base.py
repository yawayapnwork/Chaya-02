"""Helpers shared by stages."""

from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path
from typing import Any

from ..camera_model import Camera, CameraModelError, focal_scale_for, parse_capture_calibration
from ..contract import StageContext
from ..errors import StageError


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def write_json(path: Path, doc: Any) -> Path:
    path.write_text(json.dumps(doc, indent=2, sort_keys=True), encoding="utf-8")
    return path


def command_record(ctx: StageContext, config: dict[str, Any] | None = None) -> dict[str, Any]:
    """What ran: every external command actually executed, the tunables used and the tool versions."""
    commands = ctx.runner.commands()
    return {
        "stage": ctx.stage,
        "argv": commands[0] if commands else ["python", "-m", "chaya_worker", ctx.stage],
        "commands": commands,
        "config": {**ctx.settings.config_snapshot(), **(config or {})},
        "tools": ctx.toolchain.snapshot(),
    }


_DURATION = re.compile(r"Duration:\s*(\d+):(\d+):(\d+(?:\.\d+)?)")
_VIDEO = re.compile(r"Stream #\d+:\d+.*?: Video: ([^,\s]+).*?, (\d{2,5})x(\d{2,5})")
_FPS = re.compile(r"(\d+(?:\.\d+)?) fps")
# FFmpeg >= 5 prints the display matrix ("displaymatrix: rotation of -90.00 degrees"); older builds print a rotate tag.
_ROTATION = re.compile(r"rotation of (-?\d+(?:\.\d+)?) degrees|\brotate\s*:\s*(-?\d+(?:\.\d+)?)")


def probe_video(ctx: StageContext, path: Path) -> dict[str, Any]:
    """Container facts from `ffmpeg -i` (no ffprobe needed). Raises StageError when there is no video stream."""
    ffmpeg = ctx.toolchain.ffmpeg().path
    result = ctx.runner.run([ffmpeg, "-hide_banner", "-nostdin", "-i", path], check=False, capture=True, timeout=120)
    text = result.stderr
    video = _VIDEO.search(text)
    if not video:
        raise StageError(f"{path.name}: no decodable video stream", code="INPUT_INVALID",
                         details={"ffmpeg": text.strip().splitlines()[-3:]})
    duration = _DURATION.search(text)
    fps = _FPS.search(text)
    seconds = None
    if duration:
        seconds = int(duration.group(1)) * 3600 + int(duration.group(2)) * 60 + float(duration.group(3))
    rotation = _ROTATION.search(text)
    return {"codec": video.group(1), "width": int(video.group(2)), "height": int(video.group(3)),
            "fps": float(fps.group(1)) if fps else None, "duration_seconds": seconds,
            "rotation_degrees": float(rotation.group(1) or rotation.group(2)) % 360.0 if rotation else 0.0}


def capture_calibration(ctx: StageContext) -> dict[str, Any] | None:
    """The camera calibration declared in the capture's metadata files (RAW_METADATA, key `cameraCalibration`; see
    chaya_worker.camera_model.parse_capture_calibration), or None when the capture declares none.

    A declared calibration is validated before any work is done: an unsupported model is CAMERA_MODEL_UNSUPPORTED; a
    malformed block, a distortion that cannot be undistorted over the image, or more than one declared calibration (the
    pipeline reconstructs with a single camera) is CAMERA_CALIBRATION_INVALID. Nothing is approximated."""
    found = []
    for item in ctx.inputs_of("RAW_METADATA"):
        try:
            doc = json.loads(item.path.read_text(encoding="utf-8"))
        except (ValueError, UnicodeDecodeError):
            continue  # INPUT_VALIDATION reports malformed metadata files
        if not isinstance(doc, dict):
            continue
        try:
            parsed = parse_capture_calibration(doc)
            if parsed is not None and parsed[0].has_distortion:
                focal_scale_for(parsed[0])
        except CameraModelError as exc:
            raise StageError(f"metadata {item.artifact_id}: {exc}", code=exc.code, details={"artifact_id": item.artifact_id}) from exc
        if parsed is not None:
            found.append((item, parsed))
    if not found:
        return None
    if len(found) > 1:
        raise StageError(f"{len(found)} metadata files declare a camera calibration; a capture is reconstructed with one camera",
                         code="CAMERA_CALIBRATION_INVALID", details={"artifact_ids": [i.artifact_id for i, _ in found]})
    item, (camera, meta) = found[0]
    return {"camera": camera, "metadata_artifact_id": item.artifact_id, **meta}


def check_media_matches_calibration(camera: Camera, media: list[dict[str, Any]]) -> None:
    """Every raw video/image must be exactly the calibrated size (decoded orientation) and unrotated; a calibration is
    never rescaled or rotated to fit media it was not measured for."""
    problems = []
    for m in media:
        if m.get("rotation_degrees"):
            problems.append({"artifact_id": m["artifact_id"], "problem": f"video is rotated {m['rotation_degrees']:g} degrees; "
                             "a calibration's orientation would be ambiguous"})
        elif (m.get("width"), m.get("height")) != (camera.width, camera.height):
            problems.append({"artifact_id": m["artifact_id"], "problem": f"{m.get('width')}x{m.get('height')} does not match the "
                             f"calibrated {camera.width}x{camera.height}"})
    if problems:
        raise StageError(f"{len(problems)} media file(s) do not match the declared camera calibration",
                         code="CAMERA_CALIBRATION_INVALID", details={"problems": problems})
