"""Stage 4: privacy preprocessing. Faces and screens/documents are detected and blurred.

Fails closed. If a detector is unavailable, a frame cannot be read, or a face is still detectable after
blurring, the stage fails and publishes nothing: later stages never receive unprocessed frames. The
control plane additionally deletes the PII-flagged input objects once this stage has succeeded.
"""

from __future__ import annotations

import cv2
import numpy as np

from .. import archive
from ..contract import ArtifactSpec, StageContext, StageError, StageResult
from ..errors import DependencyError
from ..privacy import FaceDetector, RegionDetector, ScreenDocumentDetector, anonymize, merge
from .base import command_record, write_json

MAX_ROUNDS = 5


class PrivacyPreprocess:
    name = "PRIVACY_PREPROCESS"

    def __init__(self, faces: RegionDetector | None = None, screens: RegionDetector | None = None) -> None:
        self._faces = faces
        self._screens = screens

    def _detectors(self, ctx: StageContext) -> tuple[RegionDetector, RegionDetector]:
        faces = self._faces or FaceDetector()  # raises DependencyError when the models are missing
        screens = self._screens
        if screens is None:
            if ctx.settings.privacy_screen_detector != "heuristic-quad":
                raise DependencyError(f"screen/document detector {ctx.settings.privacy_screen_detector!r} is not available",
                                      details={"missing": [ctx.settings.privacy_screen_detector]})
            screens = ScreenDocumentDetector()
        return faces, screens

    def run(self, ctx: StageContext) -> StageResult:
        archives = ctx.inputs_of("FRAME_ARCHIVE_SELECTED")
        if not archives:
            raise StageError("no selected-frame archive was provided by the previous stage", code="INPUT_INVALID")
        faces, screens = self._detectors(ctx)
        frames = archive.unpack(archives[0].path, ctx.workdir / "in")
        out_dir = ctx.workdir / "anon"
        out_dir.mkdir()
        per_frame, totals = [], {"face": 0, "screen_or_document": 0, "frames_with_regions": 0, "escalated_frames": 0}

        for path in frames:
            img = cv2.imdecode(np.fromfile(str(path), dtype=np.uint8), cv2.IMREAD_COLOR)
            if img is None:
                raise StageError(f"frame {path.name} cannot be read, so it cannot be anonymised", code="PRIVACY_FRAME_UNREADABLE")
            regions = merge(faces.detect(img) + screens.detect(img), iou=0.0)
            out, rounds = img, 0
            if regions:
                # Round 1 pixelates and blurs. Blurred faces can still be detectable, so verify with the detector and
                # keep covering whatever it still flags (solid fill, growing coverage). Fail closed if that never converges.
                current = regions
                for rounds in range(1, MAX_ROUNDS + 1):
                    out = anonymize(img, current, solid=rounds > 1)
                    remaining = faces.detect(out)
                    if not remaining:
                        break
                    current = merge(current + remaining, iou=0.0)
                else:
                    raise StageError(f"a face is still detectable in {path.name} after {MAX_ROUNDS} anonymisation rounds",
                                     code="PRIVACY_VERIFICATION_FAILED", details={"frame": path.name, "rounds": MAX_ROUNDS})
            escalated = rounds > 1
            for r in regions:
                totals[r.label] = totals.get(r.label, 0) + 1
            totals["frames_with_regions"] += 1 if regions else 0
            totals["escalated_frames"] += 1 if escalated else 0
            per_frame.append({"frame": path.name, "regions": [{"label": r.label, "x": r.x, "y": r.y, "w": r.w, "h": r.h} for r in regions],
                              "anonymisation_rounds": rounds, "escalated_to_solid_fill": escalated})
            if not cv2.imwrite(str(out_dir / path.name), out, [cv2.IMWRITE_JPEG_QUALITY, 92]):
                raise StageError(f"could not write anonymised frame {path.name}", code="PRIVACY_WRITE_FAILED")

        ctx.logger.info("privacy preprocessing done", extra=totals)
        anon = ctx.workdir / "frames-anon.tar"
        archive.pack(out_dir, anon)
        report = write_json(ctx.workdir / "privacy-report.json", {
            "frames": len(frames), "totals": totals, "policy": "fail-closed",
            "detectors": {"faces": faces.name, "screens_documents": screens.name},
            "note": "Detection is classical CV (Haar faces, quadrilateral screens/documents), not a guarantee; see docs/pipeline.md.",
            "per_frame": per_frame})
        return StageResult(
            "SUCCEEDED", command_record(ctx, {"detectors": {"faces": faces.name, "screens_documents": screens.name}}), None,
            [ArtifactSpec("FRAME_ARCHIVE_ANON", anon, "frames-anon.tar", "application/x-tar"),
             ArtifactSpec("PRIVACY_REPORT", report, "privacy-report.json", "application/json")])
