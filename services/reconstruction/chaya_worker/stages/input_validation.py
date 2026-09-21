"""Stage 1: input validation. Every claimed raw file must be what the control plane says it is and decode."""

from __future__ import annotations

import json
from pathlib import Path

import cv2
import numpy as np

from ..contract import ArtifactSpec, StageContext, StageError, StageResult
from .base import command_record, probe_video, sha256_file, write_json


class InputValidation:
    name = "INPUT_VALIDATION"

    def run(self, ctx: StageContext) -> StageResult:
        raw = ctx.inputs_of("RAW_VIDEO", "RAW_IMAGE", "RAW_METADATA")
        if not raw:
            raise StageError("the capture has no raw media to process", code="INPUT_INVALID")
        if any(i.kind == "RAW_VIDEO" for i in raw):
            ctx.toolchain.require(["ffmpeg"], stage=self.name)

        files, problems = [], []
        for item in raw:
            entry = {"artifact_id": item.artifact_id, "kind": item.kind, "size_bytes": item.path.stat().st_size,
                     "sha256": sha256_file(item.path)}
            try:
                entry.update(self._check(ctx, item.kind, item.path))
            except StageError as exc:
                problems.append({"artifact_id": item.artifact_id, "kind": item.kind, "problem": exc.message})
                ctx.logger.error("input rejected", extra={"artifact_id": item.artifact_id, "problem": exc.message})
            files.append(entry)

        videos = sum(1 for f in files if f["kind"] == "RAW_VIDEO")
        images = sum(1 for f in files if f["kind"] == "RAW_IMAGE")
        if problems:
            raise StageError(f"{len(problems)} input file(s) failed validation", code="INPUT_INVALID",
                             details={"problems": problems})
        if videos == 0 and images < ctx.settings.min_frames:
            raise StageError(f"need at least one video or {ctx.settings.min_frames} images (have {videos} videos, {images} images)",
                             code="INPUT_INSUFFICIENT", details={"videos": videos, "images": images})

        report = write_json(ctx.workdir / "input-report.json",
                            {"files": files, "videos": videos, "images": images, "metadata_files": len(files) - videos - images})
        return StageResult(
            "SUCCEEDED", command_record(ctx), ctx.runner.last_exit_status(),
            [ArtifactSpec("INPUT_REPORT", report, "input-report.json", "application/json")])

    @staticmethod
    def _check(ctx: StageContext, kind: str, path: Path) -> dict:
        if kind == "RAW_VIDEO":
            facts = probe_video(ctx, path)
            # Actually decode a frame: a container header alone proves nothing.
            decode = ctx.runner.run(
                [ctx.toolchain.ffmpeg().path, "-v", "error", "-nostdin", "-i", path, "-frames:v", "1", "-f", "null", "-"],
                check=False, capture=True, timeout=300)
            if decode.returncode != 0:
                raise StageError(f"{path.name}: video cannot be decoded ({decode.stderr.strip()[:200]})", code="INPUT_INVALID")
            return facts
        if kind == "RAW_IMAGE":
            img = cv2.imdecode(np.fromfile(str(path), dtype=np.uint8), cv2.IMREAD_COLOR)
            if img is None:
                raise StageError(f"{path.name}: image cannot be decoded", code="INPUT_INVALID")
            return {"width": int(img.shape[1]), "height": int(img.shape[0])}
        try:
            doc = json.loads(path.read_text(encoding="utf-8"))
        except (ValueError, UnicodeDecodeError) as exc:
            raise StageError(f"{path.name}: metadata is not valid JSON", code="INPUT_INVALID") from exc
        if not isinstance(doc, dict):
            raise StageError(f"{path.name}: metadata must be a JSON object", code="INPUT_INVALID")
        return {"keys": sorted(doc)[:50]}
