"""Stage 3: frame quality filtering. Drops blurred, badly exposed and near-duplicate frames.

Metrics are computed on every frame (sharpness = variance of the Laplacian, exposure = dark/bright pixel
fractions, duplicate = distance between 16x16 thumbnails); the report says why each frame was kept or dropped.
"""

from __future__ import annotations

import cv2
import numpy as np

from .. import archive
from ..contract import ArtifactSpec, StageContext, StageError, StageResult
from .base import command_record, write_json


def frame_metrics(img_bgr: np.ndarray) -> dict[str, float]:
    gray = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2GRAY)
    h, w = gray.shape
    if w > 640:  # measure at a fixed scale so thresholds do not depend on resolution
        gray = cv2.resize(gray, (640, max(1, round(h * 640 / w))), interpolation=cv2.INTER_AREA)
    return {
        "sharpness": float(cv2.Laplacian(gray, cv2.CV_64F).var()),
        "dark_fraction": float((gray < 20).mean()),
        "bright_fraction": float((gray > 235).mean()),
        "mean_brightness": float(gray.mean()),
    }


def thumbnail(img_bgr: np.ndarray) -> np.ndarray:
    return cv2.resize(cv2.cvtColor(img_bgr, cv2.COLOR_BGR2GRAY), (16, 16), interpolation=cv2.INTER_AREA).astype(np.float32)


class FrameQualityFilter:
    name = "FRAME_QUALITY_FILTER"

    def run(self, ctx: StageContext) -> StageResult:
        archives = ctx.inputs_of("FRAME_ARCHIVE")
        if not archives:
            raise StageError("no frame archive was provided by the previous stage", code="INPUT_INVALID")
        s = ctx.settings
        frames = archive.unpack(archives[0].path, ctx.workdir / "in")
        kept_dir = ctx.workdir / "kept"
        kept_dir.mkdir()
        decisions, kept, last_thumb = [], 0, None
        reasons: dict[str, int] = {}
        for path in frames:
            img = cv2.imdecode(np.fromfile(str(path), dtype=np.uint8), cv2.IMREAD_COLOR)
            if img is None:
                decisions.append({"frame": path.name, "kept": False, "reason": "undecodable"})
                reasons["undecodable"] = reasons.get("undecodable", 0) + 1
                continue
            m = frame_metrics(img)
            thumb = thumbnail(img)
            reason = None
            if m["sharpness"] < s.blur_threshold:
                reason = "blurred"
            elif m["dark_fraction"] > s.dark_fraction_max:
                reason = "underexposed"
            elif m["bright_fraction"] > s.bright_fraction_max:
                reason = "overexposed"
            elif last_thumb is not None and float(np.abs(thumb - last_thumb).mean()) < s.duplicate_distance:
                reason = "near_duplicate"
            decisions.append({"frame": path.name, "kept": reason is None, "reason": reason, **{k: round(v, 4) for k, v in m.items()}})
            if reason is None:
                (kept_dir / path.name).write_bytes(path.read_bytes())
                last_thumb = thumb
                kept += 1
            else:
                reasons[reason] = reasons.get(reason, 0) + 1
        ctx.logger.info("frame quality filtered", extra={"total": len(frames), "kept": kept, "dropped": reasons})
        if kept < s.min_frames:
            raise StageError(
                f"only {kept} of {len(frames)} frames passed quality filtering; at least {s.min_frames} are needed",
                code="INSUFFICIENT_QUALITY_FRAMES", details={"total": len(frames), "kept": kept, "dropped": reasons})
        selected = ctx.workdir / "frames-selected.tar"
        archive.pack(kept_dir, selected)
        report = write_json(ctx.workdir / "quality-report.json",
                            {"total": len(frames), "kept": kept, "dropped": reasons, "thresholds": s.config_snapshot(),
                             "frames": decisions})
        return StageResult(
            "SUCCEEDED", command_record(ctx), None,
            [ArtifactSpec("FRAME_ARCHIVE_SELECTED", selected, "frames-selected.tar", "application/x-tar", contains_pii=True),
             ArtifactSpec("FRAME_QUALITY_REPORT", report, "quality-report.json", "application/json")])
