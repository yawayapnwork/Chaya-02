"""Stage 12: object detection and CLIP semantic indexing.

For a representative sample of registered frames: run open-vocabulary object detection (Grounding DINO,
chaya_worker.grounding_dino -- the stock public checkpoint, not fine-tuned; see that module's docstring),
localise each detection in 3D on the surface its box actually shows: the splat's centres are projected into the frame
(the camera-projection math chaya_worker.stages.semantic_segmentation uses and tests), centres hidden behind nearer
geometry are discarded, and the object is the nearest depth layer in the box that covers it
(chaya_worker.object_localization, review CV-1). A detection without that depth evidence is not placed at all; the
report counts it by reason. Crop the detection and embed it with real CLIP image embeddings (chaya_worker.clip_embeddings),
then cluster detections of the same physical object seen from multiple frames: same detector label AND 3D
proximity (cluster_by_distance), so neighbouring but different objects are never fused into one. The result is written as a DETECTED_OBJECTS artifact; this worker has
no database access (see ARCHITECTURE.md), so turning these into `poi`/`poi_version` rows with pgvector
embeddings is the control plane's job (dev.chaya.api.search on ingest of this stage's report).

Object positions are published in the canonical venue frame (chaya_worker.frames: metres, +Z up), and the
3D clustering distance is in metres, so the stage requires a calibrated coordinate frame on its work order and
fails with NOT_CALIBRATED (before loading any model) when there is none.

In an incremental re-scan the splat is the merged, venue-wide cloud in the parent reconstruction's frame,
while POSES and SPARSE_MODEL are the region capture's own. The cameras are moved into the parent's frame with
REGION_ALIGNMENT's region-to-parent similarity (ALIGNMENT_REPORT) before any projection; without that report
the stage refuses to project, rather than attach detections to unrelated geometry.

Privacy masks (review G-2; chaya_worker.privacy.masks, required in a privacy-enabled run): a detection whose box is more
than `privacy_detection_max_masked_fraction` anonymised is dropped (what the detector and CLIP see there is the fill),
and a detection is placed only through splat points that project onto unmasked pixels.

Needs torch + transformers (Grounding DINO) + open_clip (CLIP) + Pillow, the Grounding DINO checkpoint
weights already cached locally, plus COLMAP to recover camera intrinsics like SEMANTIC_SEGMENTATION does.
"""

from __future__ import annotations

import json
from typing import Any

import numpy as np

from .. import archive
from ..camera_model import CameraModelError, FrameRectifier
from ..clip_embeddings import ClipEmbedder
from ..colmap_txt import parse_cameras_txt
from ..contract import ArtifactSpec, StageContext, StageError, StageResult
from ..frames import Similarity, frame_provenance, require_canonical
from ..grounding_dino import GroundingDinoDetector
from ..object_localization import METHOD as LOCALIZATION_METHOD
from ..object_localization import REJECTIONS, Localized, camera_depth, localize_detection
from ..ply import read_ply
from ..privacy import masks as privacy_masks
from .base import command_record, frame_archives, run_provenance, venue_cloud, write_json
from .semantic_segmentation import project_points
from .splat_reconstruction import build_cameras


def visible_through_valid(px: np.ndarray, visible: np.ndarray, valid: np.ndarray | None) -> np.ndarray:
    """Pure: `visible` restricted to points that project onto a reconstruction-valid (not privacy-masked) pixel."""
    if valid is None:
        return visible
    out = visible.copy()
    ids = np.where(out)[0]
    out[ids] = valid[px[ids, 1].astype(int), px[ids, 0].astype(int)]
    return out


def box_masked_fraction(valid: np.ndarray | None, x0: int, y0: int, x1: int, y1: int) -> float:
    """Pure: the fraction of a pixel box (end-exclusive, inside the image) that is privacy-masked."""
    if valid is None or x1 <= x0 or y1 <= y0:
        return 0.0
    return float((~valid[y0:y1, x0:x1]).mean())


def viewmat_in_other_frame(viewmat: np.ndarray, region_to_other: Similarity) -> np.ndarray:
    """Pure: a camera posed in one reconstruction frame (world-to-camera rigid 4x4), re-expressed for points in
    another frame related by X_other = region_to_other(X_region).

    x_cam = R_c X_region + t_c with X_region = (1/s) R^T (X_other - t). Pinhole projection is invariant to a
    uniform scale of camera coordinates, so multiplying by s gives the equivalent rigid view matrix
    [R_c R^T | s t_c - R_c R^T t]: every point projects to the same pixel."""
    v = np.asarray(viewmat, dtype=np.float64)
    r_c, t_c = v[:3, :3], v[:3, 3]
    r, t, scale = region_to_other.rotation, region_to_other.translation, region_to_other.scale
    out = np.eye(4)
    out[:3, :3] = r_c @ r.T
    out[:3, 3] = scale * t_c - r_c @ r.T @ t
    return out


def normalized_label(label: str) -> str:
    """Pure: the detector label as a merge key (case and surrounding/inner whitespace ignored)."""
    return " ".join(str(label).lower().split())


def cluster_by_distance(objects: list[dict[str, Any]], distance: float) -> list[dict[str, Any]]:
    """Pure: greedy clustering of raw detections into physical objects. A detection joins a cluster only if it has the
    same detector label (normalized_label) AND lies within `distance` canonical metres of that cluster's running
    centroid; among such clusters it joins the nearest. 3D proximity alone is not enough: a fire extinguisher 0.3 m from
    an exit sign is two objects, and averaging their CLIP crops would describe neither (docs/ADVERSARIAL_REVIEW.md CV-2).

    Requiring the same label errs towards keeping objects apart: a sofa labelled "sofa" in one frame and "couch" in
    another stays two POIs, a duplicate rather than a lost or corrupted object. The label is only a merge guard; search
    never matches on it textually (docs/search.md).

    Each output's `embedding` is the mean of its members' (already L2-normalised) CLIP image embeddings, re-normalised;
    `position` is the members' centroid; `confidence` is the max over members; `bbox_px` and `source_frame` come together
    from the single most confident member, so the box always refers to the frame it was measured in.

    `localization` keeps how well the position is supported (docs/search.md, "Localization"): MULTI_VIEW when members
    come from at least two frames, else SINGLE_VIEW; `view_spread_m`, the RMS distance of the members from the centroid
    (how much independent views disagree; 0 for one view); `depth_spread_m`, the median of the members' surface
    thickness; `uncertainty_m`, the larger of the two. Measured spreads, not errors against ground truth."""
    clusters: list[dict[str, Any]] = []
    for obj in objects:
        key = normalized_label(obj["label"])
        pos = np.asarray(obj["position"], dtype=np.float64)
        best, best_dist = None, distance
        for c in clusters:
            if c["key"] != key:
                continue
            d = float(np.linalg.norm(c["centroid"] - pos))
            if d <= best_dist:
                best, best_dist = c, d
        if best is None:
            clusters.append({"key": key, "members": [obj], "centroid": pos})
        else:
            best["members"].append(obj)
            best["centroid"] = np.mean([np.asarray(m["position"], dtype=np.float64) for m in best["members"]], axis=0)

    merged = []
    for c in clusters:
        members = c["members"]
        embeddings = np.array([m["embedding"] for m in members])
        mean_embedding = embeddings.mean(axis=0)
        norm = np.linalg.norm(mean_embedding)
        if norm > 1e-9:
            mean_embedding = mean_embedding / norm
        best_member = max(members, key=lambda m: m["confidence"])
        positions = np.array([m["position"] for m in members], dtype=np.float64)
        views = len({m["source_frame"] for m in members})
        view_spread = float(np.sqrt(np.mean(np.sum((positions - c["centroid"]) ** 2, axis=1)))) if len(members) > 1 else 0.0
        depth_spreads = [m["localization"]["depth_spread_m"] for m in members if m.get("localization")]
        depth_spread = float(np.median(depth_spreads)) if depth_spreads else None
        merged.append({
            "label": best_member["label"],
            "confidence": best_member["confidence"],
            "position": c["centroid"].tolist(),
            "embedding": mean_embedding.tolist(),
            "bbox_px": best_member["bbox_px"],
            "source_frame": best_member["source_frame"],
            "detections_merged": len(members),
            "support_points": max(m["support_points"] for m in members),
            "localization": {
                "method": LOCALIZATION_METHOD, "status": "MULTI_VIEW" if views >= 2 else "SINGLE_VIEW", "views": views,
                "view_spread_m": view_spread, "depth_spread_m": depth_spread,
                "uncertainty_m": max(view_spread, depth_spread or 0.0),
                "coverage": (best_member.get("localization") or {}).get("coverage"),
            },
        })
    return merged


class SemanticIndexing:
    name = "SEMANTIC_INDEXING"

    def run(self, ctx: StageContext) -> StageResult:
        s = ctx.settings
        frame = require_canonical(ctx.order, self.name)  # before any model is loaded
        to_canonical = frame.to_canonical()
        ctx.toolchain.require(["py:torch", "py:transformers", "py:open_clip", "py:PIL", "colmap"], stage=self.name)
        ctx.toolchain.require([f"model:{s.grounding_dino_model}"], stage=self.name)

        splat_kind, splats, _, _ = venue_cloud(ctx, self.name)  # a re-scan indexes into the merged venue, never the region alone
        merged = splats if splat_kind == "SPLAT_MERGED" else []
        region_to_parent = None
        if merged:
            reports = ctx.inputs_of("ALIGNMENT_REPORT")
            if not reports:
                raise StageError("the merged splat is in the parent reconstruction's frame and no ALIGNMENT_REPORT relates this "
                                 "capture's cameras to it", code="INPUT_INVALID")
            region_to_parent = Similarity.from_dict(
                json.loads(reports[0].path.read_text(encoding="utf-8"))["region_to_parent_reconstruction"])
        sparse_archives = ctx.inputs_of("SPARSE_MODEL")
        poses_inputs = ctx.inputs_of("POSES")
        frames = frame_archives(ctx)
        if not splats or not sparse_archives or not poses_inputs or not frames:
            raise StageError("SEMANTIC_INDEXING needs a splat, SPARSE_MODEL, POSES and a frame archive (FRAME_ARCHIVE_ANON, or "
                             "FRAME_ARCHIVE_SELECTED with privacy disabled)", code="INPUT_INVALID")

        import cv2

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
        cams = build_cameras(poses, cameras_model)[:: max(1, s.semantic_indexing_sample_every)]
        if region_to_parent is not None:
            for cam in cams:
                cam["viewmat"] = viewmat_in_other_frame(cam["viewmat"], region_to_parent)
        images_dir = ctx.workdir / "images"
        archive.unpack(frames[0].path, images_dir)
        masks = privacy_masks.from_inputs(ctx)

        device = "cuda" if ctx.toolchain.cuda().available else "cpu"
        detector = GroundingDinoDetector(s.grounding_dino_model, device=device, box_threshold=s.object_detection_box_threshold,
                                         text_threshold=s.object_detection_text_threshold,
                                         revision=s.grounding_dino_revision, allow_pickle=s.allow_pickle_weights)
        embedder = ClipEmbedder(s.clip_model_name, s.clip_pretrained, device=device)

        raw_objects: list[dict[str, Any]] = []
        crops: list[np.ndarray] = []
        frames_processed = 0
        dropped_privacy = 0
        rejected = dict.fromkeys(REJECTIONS, 0)
        params = s.localization_params()
        metres_per_unit = to_canonical.scale  # the cloud's frame -> canonical: camera depths in metres
        # Detection and projection both use the undistorted frame and its pinhole camera (review G-1), so bbox_px is in
        # undistorted-frame pixels (same size as the frame).
        rectifier = FrameRectifier()

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
            h, w = img_rgb.shape[:2]
            detections = detector.detect(img_rgb, s.object_detection_prompt)
            if not detections:
                frames_processed += 1
                continue

            px, visible = project_points(cloud.positions, cam["viewmat"], pinhole.K, w, h)
            visible = visible_through_valid(px, visible, valid)
            depth_m = camera_depth(cloud.positions, cam["viewmat"]) * metres_per_unit
            for det in detections:
                x0, y0, x1, y1 = (int(max(0, v)) for v in det.box_xyxy)
                x1, y1 = min(w, x1), min(h, y1)
                if x1 <= x0 or y1 <= y0:
                    continue
                if box_masked_fraction(valid, x0, y0, x1, y1) > s.privacy_detection_max_masked_fraction:
                    dropped_privacy += 1
                    continue
                located = localize_detection((x0, y0, x1, y1), cloud.positions, px, depth_m, visible, params)
                if not isinstance(located, Localized):  # no depth evidence: never placed (review CV-1)
                    rejected[located.reason] += 1
                    continue
                crops.append(img_rgb[y0:y1, x0:x1])
                raw_objects.append({
                    "label": det.label, "confidence": det.confidence, "position": located.position.tolist(),
                    "bbox_px": {"x": x0, "y": y0, "width": x1 - x0, "height": y1 - y0, "frameWidth": w, "frameHeight": h},
                    "source_frame": cam["name"], "support_points": located.support_points,
                    "localization": {"depth_m": located.depth_m, "depth_spread_m": located.depth_spread_m,
                                     "coverage": located.coverage, "layers": located.layers, "hidden_in_box": located.hidden_in_box},
                })
            frames_processed += 1

        if frames_processed == 0:
            raise StageError("no frame could be matched to a registered pose", code="SEMANTIC_INDEXING_NO_FRAMES")

        embeddings = embedder.embed_images(crops)
        for obj, emb in zip(raw_objects, embeddings, strict=True):
            obj["embedding"] = emb.tolist()

        for obj in raw_objects:  # reconstruction units -> canonical metres, before clustering in metres
            obj["position"] = to_canonical.apply(np.asarray(obj["position"], dtype=np.float64)).tolist()
        objects = cluster_by_distance(raw_objects, s.object_cluster_distance_m)
        ctx.logger.info("semantic indexing done", extra={"frames_processed": frames_processed, "raw_detections": len(raw_objects),
                                                          "objects": len(objects)})

        objects_path = write_json(ctx.workdir / "detected-objects.json", {
            "coordinate_frame": frame_provenance(frame), "source": run_provenance(ctx, frame.id),
            "detector_model": detector.model_id, "detector_fine_tuned": detector.fine_tuned,
            "embedding_model": embedder.model_id, "embedding_dim": len(embeddings[0]) if len(embeddings) else 0,
            "frames_processed": frames_processed, "raw_detection_count": len(raw_objects),
            "localization": {"method": LOCALIZATION_METHOD, "rejected": rejected}, "objects": objects})
        report_path = write_json(ctx.workdir / "semantic-indexing-report.json", {
            "detector_model": detector.model_id, "detector_fine_tuned": detector.fine_tuned, "embedding_model": embedder.model_id,
            "frames_processed": frames_processed, "raw_detection_count": len(raw_objects), "object_count": len(objects),
            "dropped_as_privacy_masked": dropped_privacy, "privacy_masks_applied": masks is not None,
            "localization": {"method": LOCALIZATION_METHOD, "params": params.__dict__, "rejected": rejected,
                             "multi_view_objects": sum(1 for o in objects if o["localization"]["status"] == "MULTI_VIEW")}})

        return StageResult(
            "SUCCEEDED", command_record(ctx, {"detector_model": detector.model_id, "embedding_model": embedder.model_id,
                                              "object_count": len(objects)}),
            ctx.runner.last_exit_status(),
            [ArtifactSpec("DETECTED_OBJECTS", objects_path, "detected-objects.json", "application/json"),
             ArtifactSpec("SEMANTIC_INDEXING_REPORT", report_path, "semantic-indexing-report.json", "application/json")])
