"""Stage 3: frame quality filtering. Drops blurred, badly exposed, motion-blurred and near-duplicate frames.

Metrics are computed on every frame:
  - sharpness: whole-image Laplacian variance
  - edge_sharpness: gradient magnitude on prominent edges (Tenengrad), robust to low-texture walls
  - block_sharpness_max: 8x8 grid local sharpness to detect focused features in sparse rooms
  - effective_sharpness: combined sharpness across whole-image and prominent edge signals
  - motion_blur_anisotropy: eigenvalue ratio of the spatial gradient tensor for directional blur
  - exposure: dark, bright, and specular pixel fractions
  - feature_count: FAST corner density for texture richness
  - duplicate: L1 distance between 16x16 thumbnails

The report documents why each frame was kept or dropped, along with operator feedback for texture-poor,
reflective, or insufficient-overlap conditions.
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
        h, w = gray.shape

    # 1. Global Laplacian variance
    lap = np.abs(cv2.Laplacian(gray, cv2.CV_64F))
    lap_var = float(cv2.Laplacian(gray, cv2.CV_64F).var())

    # 2. Gradient magnitude & edge-conditioned sharpness (Laplacian magnitude on prominent edges)
    gx = cv2.Sobel(gray, cv2.CV_64F, 1, 0, ksize=3)
    gy = cv2.Sobel(gray, cv2.CV_64F, 0, 1, ksize=3)
    gmag = np.sqrt(gx**2 + gy**2)

    # Prominent edges: top 5% or magnitude >= 10.0
    edge_thresh = max(10.0, float(np.percentile(gmag, 95)))
    edges_mask = gmag >= edge_thresh
    edge_sharpness = float(lap[edges_mask].mean()) if edges_mask.any() else 0.0

    # 3. 8x8 block local Laplacian variance (measures sharp localized features on plain walls)
    bh, bw = max(1, h // 8), max(1, w // 8)
    block_vars = []
    for i in range(8):
        for j in range(8):
            block = gray[i * bh:(i + 1) * bh, j * bw:(j + 1) * bw]
            if block.size > 0:
                block_vars.append(float(cv2.Laplacian(block, cv2.CV_64F).var()))
    block_sharpness_max = float(max(block_vars)) if block_vars else 0.0

    # Effective sharpness combines whole-image and prominent edge signals
    effective_sharpness = max(lap_var, edge_sharpness)

    # 4. Motion blur detection via gradient anisotropy
    gxx = float(np.mean(gx**2))
    gyy = float(np.mean(gy**2))
    gxy = float(np.mean(gx * gy))
    t_val = gxx + gyy
    d_val = max(0.0, gxx * gyy - gxy**2)
    disc = max(0.0, t_val**2 - 4 * d_val)
    l1 = (t_val + np.sqrt(disc)) / 2.0
    l2 = max(1e-5, (t_val - np.sqrt(disc)) / 2.0)
    anisotropy = float(l1 / l2)

    # 5. Exposure & Specularity
    dark_frac = float((gray < 20).mean())
    bright_frac = float((gray > 235).mean())
    specular_frac = float((gray > 250).mean())
    mean_bright = float(gray.mean())

    # 6. Feature richness (FAST keypoints)
    fast = cv2.FastFeatureDetector_create(threshold=20, nonmaxSuppression=True)
    kps = fast.detect(gray, None) or []
    feature_count = len(kps)

    return {
        "sharpness": lap_var,
        "edge_sharpness": edge_sharpness,
        "block_sharpness_max": block_sharpness_max,
        "effective_sharpness": effective_sharpness,
        "motion_blur_anisotropy": anisotropy,
        "dark_fraction": dark_frac,
        "bright_fraction": bright_frac,
        "specular_fraction": specular_frac,
        "mean_brightness": mean_bright,
        "feature_count": float(feature_count),
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
        decisions, kept, last_thumb, last_kept_name = [], 0, None, None
        reasons: dict[str, int] = {}
        overlap_gaps: list[str] = []

        for path in frames:
            img = cv2.imdecode(np.fromfile(str(path), dtype=np.uint8), cv2.IMREAD_COLOR)
            if img is None:
                decisions.append({"frame": path.name, "kept": False, "reason": "undecodable"})
                reasons["undecodable"] = reasons.get("undecodable", 0) + 1
                continue
            m = frame_metrics(img)
            thumb = thumbnail(img)
            thumb_diff = float(np.abs(thumb - last_thumb).mean()) if last_thumb is not None else None

            # Overlap tracking between consecutive kept frames
            if thumb_diff is not None and thumb_diff > 45.0 and last_kept_name is not None:
                overlap_gaps.append(f"{last_kept_name} -> {path.name} (distance {thumb_diff:.1f})")

            reason = None
            if m["dark_fraction"] > s.dark_fraction_max or m["mean_brightness"] < 15.0:
                reason = "underexposed"
            elif m["bright_fraction"] > s.bright_fraction_max or m["mean_brightness"] > 240.0:
                reason = "overexposed"
            elif m["effective_sharpness"] < s.blur_threshold and m["edge_sharpness"] < s.min_edge_sharpness:
                reason = "blurred"
            elif m["motion_blur_anisotropy"] >= s.motion_blur_threshold and m["sharpness"] > 5.0 and m["effective_sharpness"] < 50.0:
                reason = "motion_blurred"
            elif last_thumb is not None and thumb_diff is not None and thumb_diff < s.duplicate_distance:
                reason = "near_duplicate"

            is_texture_poor = m["feature_count"] < s.min_feature_count
            is_reflective = m["specular_fraction"] > 0.015

            decisions.append({
                "frame": path.name,
                "kept": reason is None,
                "reason": reason,
                "texture_poor": is_texture_poor,
                "reflective": is_reflective,
                "thumbnail_distance": round(thumb_diff, 4) if thumb_diff is not None else None,
                **{k: round(v, 4) for k, v in m.items()},
            })

            if reason is None:
                (kept_dir / path.name).write_bytes(path.read_bytes())
                last_thumb = thumb
                last_kept_name = path.name
                kept += 1
            else:
                reasons[reason] = reasons.get(reason, 0) + 1

        texture_poor_frames = [d["frame"] for d in decisions if d["kept"] and d.get("texture_poor")]
        reflective_frames = [d["frame"] for d in decisions if d["kept"] and d.get("reflective")]
        motion_blurred_frames = [d["frame"] for d in decisions if d.get("reason") == "motion_blurred"]

        operator_feedback: list[str] = []
        if texture_poor_frames:
            operator_feedback.append(
                f"Texture-poor / plain surfaces detected in {len(texture_poor_frames)} frame(s) ({', '.join(texture_poor_frames[:5])}...); "
                "ensure visual markers or textured objects remain visible to support SfM pose estimation."
            )
        if reflective_frames:
            operator_feedback.append(
                f"Reflective or glass specular highlights detected in {len(reflective_frames)} frame(s) ({', '.join(reflective_frames[:5])}...); "
                "diffuse ambient lighting or polarizing filters recommended to avoid camera tracking drift."
            )
        if overlap_gaps:
            operator_feedback.append(
                f"Insufficient visual overlap detected between consecutive frame pairs ({', '.join(overlap_gaps[:5])}...); "
                "walk slower and maintain at least 60-70% visual overlap between adjacent frames."
            )
        if motion_blurred_frames:
            operator_feedback.append(
                f"Motion blur detected in {len(motion_blurred_frames)} frame(s); "
                "stabilize camera movement or increase shutter speed."
            )

        ctx.logger.info("frame quality filtered", extra={"total": len(frames), "kept": kept, "dropped": reasons})
        if kept < s.min_frames:
            raise StageError(
                f"only {kept} of {len(frames)} frames passed quality filtering; at least {s.min_frames} are needed",
                code="INSUFFICIENT_QUALITY_FRAMES", details={"total": len(frames), "kept": kept, "dropped": reasons})

        selected = ctx.workdir / "frames-selected.tar"
        archive.pack(kept_dir, selected)
        report = write_json(
            ctx.workdir / "quality-report.json",
            {
                "total": len(frames),
                "kept": kept,
                "dropped": reasons,
                "thresholds": s.config_snapshot(),
                "operator_feedback": operator_feedback,
                "warnings": operator_feedback,
                "texture_poor_frames": texture_poor_frames,
                "reflective_frames": reflective_frames,
                "overlap_gaps": overlap_gaps,
                "frames": decisions,
            },
        )
        return StageResult(
            "SUCCEEDED", command_record(ctx), None,
            [ArtifactSpec("FRAME_ARCHIVE_SELECTED", selected, "frames-selected.tar", "application/x-tar", contains_pii=True),
             ArtifactSpec("FRAME_QUALITY_REPORT", report, "quality-report.json", "application/json")])
