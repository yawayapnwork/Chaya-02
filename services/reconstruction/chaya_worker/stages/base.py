"""Helpers shared by stages."""

from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path
from typing import Any

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
    return {"codec": video.group(1), "width": int(video.group(2)), "height": int(video.group(3)),
            "fps": float(fps.group(1)) if fps else None, "duration_seconds": seconds}
