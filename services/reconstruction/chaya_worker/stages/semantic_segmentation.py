"""Stage 7: semantic segmentation of the registered frames, projected onto the trained Gaussian splat so
every Gaussian gets a scene-cleanup class: floor, wall, furniture, clutter or stairs (chaya_worker.semantic_classes).

The model's labels are mapped to those classes by chaya_worker.semantic_classes.class_mapping (canonical for a checkpoint
trained on Chaya's label set, the reviewed ADE20K-150 table for the default checkpoints, keywords otherwise), and the
artifact records which mapping and which weights were used (`class_mapping`, `model_provenance`): these labels are
model predictions, never ground truth. A Gaussian whose winning class is too narrow a winner
(`semantic_min_vote_share`) is "unknown", and the report gives the distribution of winning shares.

Real per-pixel inference with a Hugging Face semantic segmentation model (default: SegFormer fine-tuned on
ADE20K), not a heuristic. The model's own (dataset-specific) label set is bucketed into the four classes by
keyword, so this works with any semantic segmentation checkpoint the worker is configured with, not just
one hardcoded label table. A Gaussian's class is the confidence-weighted majority vote across every camera
that sees it; a Gaussian no camera sees clearly enough (below `semantic_confidence_min`) is left "unknown"
rather than guessed.

Privacy masks (review G-2): a pixel inside an anonymised region shows a blur or a solid block, so it casts no vote
(chaya_worker.privacy.masks; required in a privacy-enabled run). A Gaussian seen only through fills stays "unknown".

Needs torch + transformers, and the configured model weights already present in the local Hugging Face
cache (no implicit download at run time -- see Toolchain.huggingface_model), plus COLMAP to recover camera
intrinsics from the sparse model already computed by POSE_ESTIMATION.
"""

from __future__ import annotations

import json

import numpy as np

from .. import archive
from ..camera_model import CameraModelError, FrameRectifier
from ..colmap_txt import parse_cameras_txt
from ..contract import ArtifactSpec, StageContext, StageError, StageResult
from ..model_loading import load_pretrained, resolved_revision
from ..model_provenance import ModelLicenseError, enforce_license_policy, provenance
from ..ply import read_ply
from ..privacy import masks as privacy_masks
from ..semantic_classes import CLUTTER, FLOOR, FURNITURE, STAIRS, UNKNOWN, WALL, class_mapping
from .base import command_record, frame_archives, write_json
from .splat_reconstruction import build_cameras

CLASSES = [FLOOR, WALL, FURNITURE, CLUTTER, STAIRS, UNKNOWN]
VOTED = CLASSES[:-1]  # every class a pixel can vote for; UNKNOWN is what no vote decided


def project_points(positions: np.ndarray, viewmat: np.ndarray, K: np.ndarray, width: int, height: int) -> tuple[np.ndarray, np.ndarray]:
    """Pure: project (N,3) world points into one camera. Returns (pixel_xy float (N,2), visible bool (N,))."""
    homog = np.concatenate([positions, np.ones((len(positions), 1))], axis=1)
    cam = (viewmat @ homog.T).T[:, :3]
    in_front = cam[:, 2] > 1e-6
    proj = (K @ cam.T).T
    px = np.zeros((len(positions), 2))
    with np.errstate(divide="ignore", invalid="ignore"):
        px[:, 0] = proj[:, 0] / proj[:, 2]
        px[:, 1] = proj[:, 1] / proj[:, 2]
    visible = in_front & (px[:, 0] >= 0) & (px[:, 0] < width) & (px[:, 1] >= 0) & (px[:, 1] < height)
    return px, visible


def vote_labels(class_votes: np.ndarray, confidence_sum: np.ndarray, seen: np.ndarray, min_confidence: float,
                min_share: float = 0.0) -> list[str]:
    """Pure: given per-point accumulated confidence per VOTED class, pick the winner. UNKNOWN when nothing (confident
    enough) saw the point, or when the winner holds less than `min_share` of the point's votes (an uncertain label)."""
    shares = winning_shares(class_votes)
    labels = []
    for i in range(len(class_votes)):
        if not seen[i] or confidence_sum[i] < min_confidence or (min_share > 0 and shares[i] < min_share):
            labels.append(UNKNOWN)
            continue
        labels.append(CLASSES[int(np.argmax(class_votes[i]))])
    return labels


def winning_shares(class_votes: np.ndarray) -> np.ndarray:
    """Pure: per point, the winning class's share of all its votes (0 when it has none)."""
    total = class_votes.sum(axis=1)
    with np.errstate(divide="ignore", invalid="ignore"):
        return np.where(total > 0, class_votes.max(axis=1) / np.where(total > 0, total, 1.0), 0.0)


def share_histogram(shares: np.ndarray, voted: np.ndarray) -> dict[str, int]:
    """Pure: how decisive the votes were, over points that had any: counts per winning-share band."""
    s = shares[voted]
    edges = [0.0, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0001]
    return {f"{lo:.1f}-{min(hi, 1.0):.1f}": int(((s >= lo) & (s < hi)).sum()) for lo, hi in zip(edges[:-1], edges[1:], strict=True)}


class SemanticSegmentation:
    name = "SEMANTIC_SEGMENTATION"

    def run(self, ctx: StageContext) -> StageResult:
        s = ctx.settings
        rev = s.semantic_segmentation_revision
        try:
            enforce_license_policy(
                provenance(s.semantic_segmentation_model, requested_revision=rev, resolved_revision=None),
                commercial_use_only=s.commercial_use_only,
            )
        except ModelLicenseError as exc:
            raise StageError(str(exc), code="MODEL_LICENSE_RESTRICTED") from exc

        ctx.toolchain.require(["py:torch", "py:transformers", "colmap"], stage=self.name)
        ctx.toolchain.require([f"model:{s.semantic_segmentation_model}"], stage=self.name)

        splats = ctx.inputs_of("SPLAT")
        sparse_archives = ctx.inputs_of("SPARSE_MODEL")
        poses_inputs = ctx.inputs_of("POSES")
        frames = frame_archives(ctx)
        if not splats or not sparse_archives or not poses_inputs or not frames:
            raise StageError("SEMANTIC_SEGMENTATION needs SPLAT, SPARSE_MODEL, POSES and a frame archive (FRAME_ARCHIVE_ANON, or "
                             "FRAME_ARCHIVE_SELECTED with privacy disabled)",
                             code="INPUT_INVALID")

        import cv2
        import torch
        from transformers import AutoImageProcessor, AutoModelForSemanticSegmentation

        cloud = read_ply(splats[0].path)
        sparse_dir = ctx.workdir / "sparse-bin"
        archive.unpack(sparse_archives[0].path, sparse_dir)
        txt_dir = ctx.workdir / "sparse-txt"
        txt_dir.mkdir()
        colmap = ctx.toolchain.colmap().path
        ctx.runner.run([colmap, "model_converter", "--input_path", str(sparse_dir), "--output_path", str(txt_dir),
                        "--output_type", "TXT"], error_code="MODEL_CONVERSION_FAILED", timeout=600)
        try:
            cameras_model = parse_cameras_txt((txt_dir / "cameras.txt").read_text(encoding="utf-8"))
        except CameraModelError as exc:
            raise StageError(f"the sparse model's camera cannot be used: {exc}", code=exc.code) from exc
        poses = json.loads(poses_inputs[0].path.read_text(encoding="utf-8"))["poses"]
        cams = build_cameras(poses, cameras_model)[:: max(1, s.semantic_sample_every)]
        images_dir = ctx.workdir / "images"
        archive.unpack(frames[0].path, images_dir)
        masks = privacy_masks.from_inputs(ctx)

        device = "cuda" if ctx.toolchain.cuda().available else "cpu"
        processor = AutoImageProcessor.from_pretrained(s.semantic_segmentation_model, **({"revision": rev} if rev else {}))
        model = load_pretrained(AutoModelForSemanticSegmentation.from_pretrained, s.semantic_segmentation_model, revision=rev,
                                allow_pickle=s.allow_pickle_weights, what="semantic segmentation model").to(device).eval()
        buckets, mapping_method = class_mapping(model.config.id2label)
        model_prov = provenance(s.semantic_segmentation_model, requested_revision=rev, resolved_revision=resolved_revision(model))
        try:
            enforce_license_policy(model_prov, commercial_use_only=s.commercial_use_only)
        except ModelLicenseError as exc:
            raise StageError(str(exc), code="MODEL_LICENSE_RESTRICTED") from exc

        n = len(cloud)
        class_votes = np.zeros((n, len(VOTED)), dtype=np.float64)  # one column per VOTED class
        confidence_sum = np.zeros(n, dtype=np.float64)
        seen = np.zeros(n, dtype=bool)
        frames_processed = 0
        rectifier = FrameRectifier()  # project through the pinhole camera of the undistorted frame (review G-1)

        with torch.no_grad():
            for cam in cams:
                path = images_dir / cam["name"]
                if not path.is_file():
                    continue
                img_bgr = cv2.imdecode(np.fromfile(str(path), dtype=np.uint8), cv2.IMREAD_COLOR)
                if img_bgr is None:
                    continue
                valid = masks.valid(cam["name"], img_bgr.shape[:2]) if masks is not None else None
                try:
                    img_bgr, pinhole = rectifier.rectify(cam["camera"], img_bgr)
                    if valid is not None:
                        valid = rectifier.rectify_mask(cam["camera"], valid)
                except CameraModelError as exc:
                    raise StageError(f"frame {cam['name']}: {exc}", code=exc.code) from exc
                img_rgb = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2RGB)
                inputs = processor(images=img_rgb, return_tensors="pt").to(device)
                logits = model(**inputs).logits  # (1, num_classes, h, w)
                logits = torch.nn.functional.interpolate(logits, size=img_rgb.shape[:2], mode="bilinear", align_corners=False)
                probs = torch.softmax(logits[0], dim=0)
                confidence, class_id = probs.max(dim=0)
                confidence, class_id = confidence.cpu().numpy(), class_id.cpu().numpy()

                px, visible = project_points(cloud.positions, cam["viewmat"], pinhole.K, pinhole.width, pinhole.height)
                idx = np.where(visible)[0]
                if len(idx) == 0:
                    continue
                cols, rows = px[idx, 0].astype(int), px[idx, 1].astype(int)
                if valid is not None:  # no vote from an anonymised pixel
                    keep = valid[rows, cols]
                    idx, cols, rows = idx[keep], cols[keep], rows[keep]
                    if len(idx) == 0:
                        continue
                conf, cls = confidence[rows, cols], class_id[rows, cols]
                bucket_names = np.array([buckets.get(int(c), CLUTTER) for c in cls])
                for bucket_idx, name in enumerate(VOTED):
                    hit = (bucket_names == name) & (conf >= s.semantic_confidence_min)
                    class_votes[idx[hit], bucket_idx] += conf[hit]
                confidence_sum[idx] += np.where(conf >= s.semantic_confidence_min, conf, 0.0)
                seen[idx] = True
                frames_processed += 1

        if frames_processed == 0:
            raise StageError("no frame could be projected onto the splat (no camera matched an image)", code="SEMANTIC_PROJECTION_FAILED")

        labels = vote_labels(class_votes, confidence_sum, seen, s.semantic_confidence_min, s.semantic_min_vote_share)
        shares = winning_shares(class_votes)
        voted = class_votes.sum(axis=1) > 0
        counts: dict[str, int] = {c: 0 for c in CLASSES}
        for label in labels:
            counts[label] += 1
        ctx.logger.info("semantic segmentation done", extra={"frames_processed": frames_processed, "counts": counts})

        labels_path = ctx.workdir / "semantic-labels.json"
        write_json(labels_path, {"model": s.semantic_segmentation_model, "model_provenance": model_prov.as_dict(),
                                 "class_mapping": mapping_method, "labels_are": "model predictions, not ground truth",
                                 "frames_processed": frames_processed, "confidence_min": s.semantic_confidence_min,
                                 "min_vote_share": s.semantic_min_vote_share, "counts": counts, "labels": labels})
        report_path = ctx.workdir / "semantic-report.json"
        write_json(report_path, {"gaussian_count": n, "counts": counts, "frames_processed": frames_processed, "cameras_total": len(cams),
                                 "class_mapping": mapping_method, "model_provenance": model_prov.as_dict(),
                                 "winning_share_histogram": share_histogram(shares, voted),
                                 "uncertain_below_min_share": int((voted & (shares < s.semantic_min_vote_share)).sum())
                                 if s.semantic_min_vote_share > 0 else 0})

        return StageResult(
            "SUCCEEDED", command_record(ctx, {"model": s.semantic_segmentation_model, "class_mapping": mapping_method,
                                              "fine_tuned": model_prov.fine_tuned, "frames_processed": frames_processed,
                                              "counts": counts}),
            ctx.runner.last_exit_status(),
            [ArtifactSpec("SEMANTIC_LABELS", labels_path, "semantic-labels.json", "application/json"),
             ArtifactSpec("SEMANTIC_REPORT", report_path, "semantic-report.json", "application/json")])
