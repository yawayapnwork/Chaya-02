"""Stage 7: semantic segmentation of the registered frames, projected onto the trained Gaussian splat so
every Gaussian gets a scene-cleanup class: floor, wall, furniture, clutter or stairs (chaya_worker.semantic_classes).

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
from ..model_loading import load_pretrained
from ..ply import read_ply
from ..privacy import masks as privacy_masks
from ..semantic_classes import CLUTTER, FLOOR, FURNITURE, STAIRS, UNKNOWN, WALL, bucket_all
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


def vote_labels(class_votes: np.ndarray, confidence_sum: np.ndarray, seen: np.ndarray, min_confidence: float) -> list[str]:
    """Pure: given per-point accumulated confidence per VOTED class, pick the winner."""
    labels = []
    for i in range(len(class_votes)):
        if not seen[i] or confidence_sum[i] < min_confidence:
            labels.append(UNKNOWN)
            continue
        labels.append(CLASSES[int(np.argmax(class_votes[i]))])
    return labels


class SemanticSegmentation:
    name = "SEMANTIC_SEGMENTATION"

    def run(self, ctx: StageContext) -> StageResult:
        s = ctx.settings
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
        rev = s.semantic_segmentation_revision
        processor = AutoImageProcessor.from_pretrained(s.semantic_segmentation_model, **({"revision": rev} if rev else {}))
        model = load_pretrained(AutoModelForSemanticSegmentation.from_pretrained, s.semantic_segmentation_model, revision=rev,
                                allow_pickle=s.allow_pickle_weights, what="semantic segmentation model").to(device).eval()
        buckets = bucket_all(model.config.id2label)

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

        labels = vote_labels(class_votes, confidence_sum, seen, s.semantic_confidence_min)
        counts: dict[str, int] = {c: 0 for c in CLASSES}
        for label in labels:
            counts[label] += 1
        ctx.logger.info("semantic segmentation done", extra={"frames_processed": frames_processed, "counts": counts})

        labels_path = ctx.workdir / "semantic-labels.json"
        write_json(labels_path, {"model": s.semantic_segmentation_model, "frames_processed": frames_processed,
                                 "confidence_min": s.semantic_confidence_min, "counts": counts, "labels": labels})
        report_path = ctx.workdir / "semantic-report.json"
        write_json(report_path, {"gaussian_count": n, "counts": counts, "frames_processed": frames_processed, "cameras_total": len(cams)})

        return StageResult(
            "SUCCEEDED", command_record(ctx, {"model": s.semantic_segmentation_model, "frames_processed": frames_processed, "counts": counts}),
            ctx.runner.last_exit_status(),
            [ArtifactSpec("SEMANTIC_LABELS", labels_path, "semantic-labels.json", "application/json"),
             ArtifactSpec("SEMANTIC_REPORT", report_path, "semantic-report.json", "application/json")])
