"""Stage 12: object detection and CLIP semantic indexing.

For a representative sample of registered frames: run open-vocabulary object detection (Grounding DINO,
chaya_worker.grounding_dino -- the stock public checkpoint, not fine-tuned; see that module's docstring),
localise each detection in 3D by finding where the trained splat's own points land inside its 2D box
(reusing the exact camera-projection math chaya_worker.stages.semantic_segmentation already uses and
tests), crop the detection and embed it with real CLIP image embeddings (chaya_worker.clip_embeddings),
then cluster detections of the same physical object seen from multiple frames by 3D proximity alone --
never by matching label text, since label text is not the search mechanism here (CLIP embedding
similarity is; see docs/search.md). The result is written as a DETECTED_OBJECTS artifact; this worker has
no database access (see ARCHITECTURE.md), so turning these into `poi`/`poi_version` rows with pgvector
embeddings is the control plane's job (dev.chaya.api.search on ingest of this stage's report).

Needs torch + transformers (Grounding DINO) + open_clip (CLIP) + Pillow, the Grounding DINO checkpoint
weights already cached locally, plus COLMAP to recover camera intrinsics like SEMANTIC_SEGMENTATION does.
"""

from __future__ import annotations

import json
from typing import Any

import numpy as np

from .. import archive
from ..clip_embeddings import ClipEmbedder
from ..colmap_txt import parse_cameras_txt
from ..contract import ArtifactSpec, StageContext, StageError, StageResult
from ..grounding_dino import Detection, GroundingDinoDetector
from ..ply import read_ply
from .base import command_record, write_json
from .semantic_segmentation import project_points
from .splat_reconstruction import build_cameras


def associate_detection_with_geometry(detection: Detection, positions: np.ndarray, px: np.ndarray,
                                      visible: np.ndarray) -> tuple[np.ndarray, int] | None:
    """Pure: which reconstructed 3D points reproject inside this 2D detection box, and their centroid.
    Returns None if no splat point localises the detection (nothing to place in 3D)."""
    x0, y0, x1, y1 = detection.box_xyxy
    inside = visible & (px[:, 0] >= x0) & (px[:, 0] < x1) & (px[:, 1] >= y0) & (px[:, 1] < y1)
    count = int(inside.sum())
    if count == 0:
        return None
    return np.median(positions[inside], axis=0), count


def cluster_by_distance(objects: list[dict[str, Any]], distance: float) -> list[dict[str, Any]]:
    """Pure: greedy spatial clustering. Two raw detections within `distance` scene units of an existing
    cluster's running centroid are the same physical object, regardless of what label text either one
    carries -- label text is never the merge key, only 3D proximity. Each output cluster's `embedding` is
    the mean of its members' (already L2-normalised) embeddings, re-normalised; `confidence` is the max
    over members; `label` is the most common raw label, kept only for display, not for matching."""
    clusters: list[dict[str, Any]] = []
    for obj in objects:
        pos = np.asarray(obj["position"])
        best, best_dist = None, distance
        for c in clusters:
            d = float(np.linalg.norm(np.asarray(c["position"]) - pos))
            if d <= best_dist:
                best, best_dist = c, d
        if best is None:
            clusters.append({**obj, "members": [obj]})
        else:
            best["members"].append(obj)

    merged = []
    for c in clusters:
        members = c["members"]
        positions = np.array([m["position"] for m in members])
        embeddings = np.array([m["embedding"] for m in members])
        mean_embedding = embeddings.mean(axis=0)
        norm = np.linalg.norm(mean_embedding)
        if norm > 1e-9:
            mean_embedding = mean_embedding / norm
        labels = [m["label"] for m in members]
        best_member = max(members, key=lambda m: m["confidence"])
        merged.append({
            "label": max(set(labels), key=labels.count),
            "confidence": max(m["confidence"] for m in members),
            "position": positions.mean(axis=0).tolist(),
            "embedding": mean_embedding.tolist(),
            "bbox_px": best_member["bbox_px"],
            "source_frame": best_member["source_frame"],
            "detections_merged": len(members),
            "support_points": max(m["support_points"] for m in members),
        })
    return merged


class SemanticIndexing:
    name = "SEMANTIC_INDEXING"

    def run(self, ctx: StageContext) -> StageResult:
        s = ctx.settings
        ctx.toolchain.require(["py:torch", "py:transformers", "py:open_clip", "py:PIL", "colmap"], stage=self.name)
        ctx.toolchain.require([f"model:{s.grounding_dino_model}"], stage=self.name)

        splats = ctx.inputs_of("SPLAT_MERGED") or ctx.inputs_of("SPLAT_CLEAN") or ctx.inputs_of("SPLAT")
        sparse_archives = ctx.inputs_of("SPARSE_MODEL")
        poses_inputs = ctx.inputs_of("POSES")
        frame_archives = ctx.inputs_of("FRAME_ARCHIVE_ANON")
        if not splats or not sparse_archives or not poses_inputs or not frame_archives:
            raise StageError("SEMANTIC_INDEXING needs a splat, SPARSE_MODEL, POSES and FRAME_ARCHIVE_ANON", code="INPUT_INVALID")

        import cv2

        cloud = read_ply(splats[0].path)
        sparse_dir = ctx.workdir / "sparse-bin"
        archive.unpack(sparse_archives[0].path, sparse_dir)
        txt_dir = ctx.workdir / "sparse-txt"
        txt_dir.mkdir()
        colmap = ctx.toolchain.colmap().path
        ctx.runner.run([colmap, "model_converter", "--input_path", str(sparse_dir), "--output_path", str(txt_dir),
                        "--output_type", "TXT"], error_code="MODEL_CONVERSION_FAILED", timeout=600)
        cameras_model = parse_cameras_txt((txt_dir / "cameras.txt").read_text(encoding="utf-8"))
        poses = json.loads(poses_inputs[0].path.read_text(encoding="utf-8"))["poses"]
        cams = build_cameras(poses, cameras_model)[:: max(1, s.semantic_indexing_sample_every)]
        images_dir = ctx.workdir / "images"
        archive.unpack(frame_archives[0].path, images_dir)

        device = "cuda" if ctx.toolchain.cuda().available else "cpu"
        detector = GroundingDinoDetector(s.grounding_dino_model, device=device, box_threshold=s.object_detection_box_threshold,
                                         text_threshold=s.object_detection_text_threshold,
                                         revision=s.grounding_dino_revision, allow_pickle=s.allow_pickle_weights)
        embedder = ClipEmbedder(s.clip_model_name, s.clip_pretrained, device=device)

        raw_objects: list[dict[str, Any]] = []
        crops: list[np.ndarray] = []
        frames_processed = 0

        for cam in cams:
            path = images_dir / cam["name"]
            if not path.is_file():
                continue
            img_bgr = cv2.imdecode(np.fromfile(str(path), dtype=np.uint8), cv2.IMREAD_COLOR)
            if img_bgr is None:
                continue
            img_rgb = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2RGB)
            h, w = img_rgb.shape[:2]
            detections = detector.detect(img_rgb, s.object_detection_prompt)
            if not detections:
                frames_processed += 1
                continue

            px, visible = project_points(cloud.positions, cam["viewmat"], cam["K"], w, h)
            for det in detections:
                located = associate_detection_with_geometry(det, cloud.positions, px, visible)
                if located is None:
                    continue
                position, support = located
                x0, y0, x1, y1 = (int(max(0, v)) for v in det.box_xyxy)
                x1, y1 = min(w, x1), min(h, y1)
                if x1 <= x0 or y1 <= y0:
                    continue
                crops.append(img_rgb[y0:y1, x0:x1])
                raw_objects.append({
                    "label": det.label, "confidence": det.confidence, "position": position.tolist(),
                    "bbox_px": {"x": x0, "y": y0, "width": x1 - x0, "height": y1 - y0, "frameWidth": w, "frameHeight": h},
                    "source_frame": cam["name"], "support_points": support,
                })
            frames_processed += 1

        if frames_processed == 0:
            raise StageError("no frame could be matched to a registered pose", code="SEMANTIC_INDEXING_NO_FRAMES")

        embeddings = embedder.embed_images(crops)
        for obj, emb in zip(raw_objects, embeddings):
            obj["embedding"] = emb.tolist()

        objects = cluster_by_distance(raw_objects, s.object_cluster_distance)
        ctx.logger.info("semantic indexing done", extra={"frames_processed": frames_processed, "raw_detections": len(raw_objects),
                                                          "objects": len(objects)})

        objects_path = write_json(ctx.workdir / "detected-objects.json", {
            "detector_model": detector.model_id, "detector_fine_tuned": detector.fine_tuned,
            "embedding_model": embedder.model_id, "embedding_dim": len(embeddings[0]) if len(embeddings) else 0,
            "frames_processed": frames_processed, "raw_detection_count": len(raw_objects), "objects": objects})
        report_path = write_json(ctx.workdir / "semantic-indexing-report.json", {
            "detector_model": detector.model_id, "detector_fine_tuned": detector.fine_tuned, "embedding_model": embedder.model_id,
            "frames_processed": frames_processed, "raw_detection_count": len(raw_objects), "object_count": len(objects)})

        return StageResult(
            "SUCCEEDED", command_record(ctx, {"detector_model": detector.model_id, "embedding_model": embedder.model_id,
                                              "object_count": len(objects)}),
            ctx.runner.last_exit_status(),
            [ArtifactSpec("DETECTED_OBJECTS", objects_path, "detected-objects.json", "application/json"),
             ArtifactSpec("SEMANTIC_INDEXING_REPORT", report_path, "semantic-indexing-report.json", "application/json")])
