"""Stage 4: privacy preprocessing. Faces and screens/documents are detected and blurred.

Fails closed. If a detector is unavailable, a frame cannot be read, or a face is still detectable after
blurring, the stage fails and publishes nothing: later stages never receive unprocessed frames. The
control plane additionally deletes the PII-flagged input objects once this stage has succeeded.

Alongside the anonymised frames it publishes PRIVACY_MASKS (chaya_worker.privacy.masks): per frame, which pixels are
still the captured scene and which were rewritten. The mask is built from the regions of the LAST anonymisation round
(the initial detections plus everything the verification rounds added), so it covers every pixel that was changed.
Later stages use it to keep the fills out of SfM, splat training and semantics (review G-2).
"""

from __future__ import annotations

import cv2
import numpy as np

from .. import archive
from ..contract import ArtifactSpec, StageContext, StageError, StageResult
from ..errors import DependencyError
from ..privacy import FaceDetector, RegionDetector, ScreenDocumentDetector, anonymize, merge
from ..privacy import masks as privacy_masks
from .base import command_record, write_json

MAX_ROUNDS = 5
MIN_SFM_FEATURES_PER_FRAME = 15
MIN_USABLE_SFM_FRAMES = 3
DEGRADED_RETENTION_THRESHOLD = 0.50
DEGRADED_SFM_MASK_FRACTION = 0.45


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
        mask_dir = ctx.workdir / "masks"
        mask_dir.mkdir()
        fast = cv2.FastFeatureDetector_create(threshold=20, nonmaxSuppression=True)
        sfm_margin_px = max(0, int(ctx.settings.privacy_sfm_mask_margin_px))
        per_frame, totals = [], {"face": 0, "screen_or_document": 0, "frames_with_regions": 0, "escalated_frames": 0,
                                 "masked_pixels": 0, "pixels": 0, "sfm_masked_pixels": 0,
                                 "total_features": 0, "unmasked_features": 0,
                                 "usable_before_mask": 0, "usable_after_mask": 0, "degraded_frames": 0,
                                 "uncertain_regions": 0}
        degraded_frame_names: list[str] = []
        total_uncertain = 0

        for path in frames:
            img = cv2.imdecode(np.fromfile(str(path), dtype=np.uint8), cv2.IMREAD_COLOR)
            if img is None:
                raise StageError(f"frame {path.name} cannot be read, so it cannot be anonymised", code="PRIVACY_FRAME_UNREADABLE")
            regions = merge(faces.detect(img) + screens.detect(img), iou=0.0)
            out, rounds, current = img, 0, regions
            if regions:
                # Round 1 pixelates and blurs. Blurred faces can still be detectable, so verify with the detector and
                # keep covering whatever it still flags (solid fill, growing coverage). Fail closed if that never converges.
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
            mask = privacy_masks.valid_mask(current, *img.shape[:2])
            privacy_masks.write_mask(mask_dir, path.name, mask)
            masked = int((mask == privacy_masks.MASKED).sum())
            masked_fraction = masked / mask.size
            sfm_valid = privacy_masks.sfm_mask(mask == privacy_masks.VALID, sfm_margin_px) == privacy_masks.VALID
            sfm_masked = int((~sfm_valid).sum())
            sfm_masked_fraction = sfm_masked / mask.size

            gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
            kps = fast.detect(gray, None) or []
            total_features = len(kps)
            h, w = img.shape[:2]
            unmasked_features = sum(
                1 for kp in kps
                if 0 <= int(round(kp.pt[1])) < h and 0 <= int(round(kp.pt[0])) < w and bool(sfm_valid[int(round(kp.pt[1])), int(round(kp.pt[0]))])
            )
            retained_ratio = (unmasked_features / total_features) if total_features > 0 else 1.0
            usable_before = total_features >= MIN_SFM_FEATURES_PER_FRAME
            usable_after = unmasked_features >= MIN_SFM_FEATURES_PER_FRAME
            sfm_degraded = bool(
                regions and (
                    sfm_masked_fraction >= DEGRADED_SFM_MASK_FRACTION
                    or (usable_before and (not usable_after or retained_ratio < DEGRADED_RETENTION_THRESHOLD))
                )
            )
            if sfm_degraded:
                degraded_frame_names.append(path.name)

            uncertain_in_frame = [r for r in current if getattr(r, "uncertain", False)]
            total_uncertain += len(uncertain_in_frame)

            totals["masked_pixels"] += masked
            totals["pixels"] += int(mask.size)
            totals["sfm_masked_pixels"] += sfm_masked
            totals["total_features"] += total_features
            totals["unmasked_features"] += unmasked_features
            totals["usable_before_mask"] += 1 if usable_before else 0
            totals["usable_after_mask"] += 1 if usable_after else 0
            totals["degraded_frames"] += 1 if sfm_degraded else 0
            totals["uncertain_regions"] = total_uncertain
            for r in regions:
                totals[r.label] = totals.get(r.label, 0) + 1
            totals["frames_with_regions"] += 1 if regions else 0
            totals["escalated_frames"] += 1 if escalated else 0
            per_frame.append({"frame": path.name,
                              "regions": [{"label": r.label, "x": r.x, "y": r.y, "w": r.w, "h": r.h, "confidence": getattr(r, "confidence", 1.0), "uncertain": getattr(r, "uncertain", False)} for r in regions],
                              "covered_regions": [{"label": r.label, "x": r.x, "y": r.y, "w": r.w, "h": r.h} for r in current],
                              "uncertain_regions": len(uncertain_in_frame),
                              "masked_fraction": round(masked_fraction, 6),
                              "sfm_masked_fraction": round(sfm_masked_fraction, 6),
                              "total_features": total_features,
                              "unmasked_features": unmasked_features,
                              "retained_feature_ratio": round(retained_ratio, 6),
                              "sfm_degraded": sfm_degraded,
                              "anonymisation_rounds": rounds, "escalated_to_solid_fill": escalated})
            if not cv2.imwrite(str(out_dir / path.name), out, [cv2.IMWRITE_JPEG_QUALITY, 92]):
                raise StageError(f"could not write anonymised frame {path.name}", code="PRIVACY_WRITE_FAILED")

        mean_retained_ratio = round(
            totals["unmasked_features"] / max(1, totals["total_features"]) if totals["total_features"] > 0 else 1.0,
            6,
        )
        mean_sfm_masked_fraction = round(totals["sfm_masked_pixels"] / max(1, totals["pixels"]), 6)
        warnings: list[str] = []
        if degraded_frame_names:
            warnings.append(
                f"Privacy masking materially degraded SfM feature coverage in {len(degraded_frame_names)} of {len(frames)} frame(s) "
                f"(mean retained feature ratio {mean_retained_ratio:.2f}, mean SfM-masked area {mean_sfm_masked_fraction * 100:.1f}%)."
            )
        if totals["screen_or_document"] > 0 and degraded_frame_names:
            warnings.append(
                "Heuristic quadrilateral screen/document detection masked large rectangular surfaces (such as doors, windows, "
                "posters, or wall panels) that carry structural keypoints needed for camera pose estimation."
            )
        if total_uncertain > 0:
            warnings.append(
                f"Privacy preprocessing identified {total_uncertain} uncertain detection(s); "
                "operator review is recommended to verify whether confidential screens or documents are present."
            )
        review_required = len(degraded_frame_names) > 0 or mean_retained_ratio < 0.65 or total_uncertain > 0

        sfm_impact = {
            "sfm_margin_px": sfm_margin_px,
            "min_sfm_features_per_frame": MIN_SFM_FEATURES_PER_FRAME,
            "usable_frames_before_mask": totals["usable_before_mask"],
            "usable_frames_after_mask": totals["usable_after_mask"],
            "degraded_frame_count": len(degraded_frame_names),
            "degraded_frames": degraded_frame_names,
            "uncertain_regions_count": total_uncertain,
            "mean_retained_feature_ratio": mean_retained_ratio,
            "mean_sfm_masked_fraction": mean_sfm_masked_fraction,
            "review_required": review_required,
            "warnings": warnings,
        }

        if totals["usable_before_mask"] >= MIN_USABLE_SFM_FRAMES and totals["usable_after_mask"] < MIN_USABLE_SFM_FRAMES:
            raise StageError(
                f"privacy masking removed too much building texture for SfM: only {totals['usable_after_mask']} of "
                f"{totals['usable_before_mask']} textured frame(s) retain >= {MIN_SFM_FEATURES_PER_FRAME} unmasked keypoints "
                f"(need at least {MIN_USABLE_SFM_FRAMES})",
                code="PRIVACY_SFM_DEGRADED",
                details=sfm_impact,
            )

        ctx.logger.info("privacy preprocessing done", extra=totals)
        anon = ctx.workdir / "frames-anon.tar"
        archive.pack(out_dir, anon)
        masks_tar = ctx.workdir / "privacy-masks.tar"
        archive.pack(mask_dir, masks_tar)
        report = write_json(ctx.workdir / "privacy-report.json", {
            "frames": len(frames), "totals": totals, "policy": "fail-closed",
            "detectors": {"faces": faces.name, "screens_documents": screens.name},
            "note": "Detection is classical CV (Haar faces, quadrilateral screens/documents), not a guarantee; see docs/pipeline.md.",
            "masks": {"artifact": "privacy-masks.tar", "convention": "<frame>.png, 255 = reconstruction-valid, 0 = privacy-masked",
                      "codec_block_px": privacy_masks.CODEC_BLOCK,
                      "masked_fraction": round(totals["masked_pixels"] / max(1, totals["pixels"]), 6),
                      "sfm_masked_fraction": mean_sfm_masked_fraction,
                      "note": "masked pixels were rewritten by anonymisation and are excluded from SfM, splat training and "
                              "semantic stages; they are not scene content"},
            "sfm_impact": sfm_impact,
            "per_frame": per_frame})
        return StageResult(
            "SUCCEEDED",
            command_record(ctx, {
                "detectors": {"faces": faces.name, "screens_documents": screens.name},
                "privacy_degraded_frames": len(degraded_frame_names),
                "privacy_degraded_frame_names": degraded_frame_names,
                "privacy_review_required": review_required,
                "privacy_warnings": warnings,
                "mean_retained_feature_ratio": mean_retained_ratio,
                "mean_sfm_masked_fraction": mean_sfm_masked_fraction,
                "usable_sfm_frames_before": totals["usable_before_mask"],
                "usable_sfm_frames_after": totals["usable_after_mask"],
            }),
            None,
            [ArtifactSpec("FRAME_ARCHIVE_ANON", anon, "frames-anon.tar", "application/x-tar"),
             ArtifactSpec(privacy_masks.KIND, masks_tar, "privacy-masks.tar", "application/x-tar"),
             ArtifactSpec("PRIVACY_REPORT", report, "privacy-report.json", "application/json")])
