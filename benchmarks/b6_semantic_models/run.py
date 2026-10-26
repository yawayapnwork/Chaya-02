"""Benchmark 6: the semantic models -- segmentation, object detection and retrieval -- on a held-out, locked dataset.

    python benchmarks/b6_semantic_models/run.py --dataset <chaya-dataset/2 dir> [--tasks seg,det,ret] [--limit N]
        [--seg-model ID --seg-revision SHA] [--det-model ID --det-revision SHA] [--bootstrap 1000] [--seed 0]

Runs inside chaya-ml-eval (benchmarks/docker/ml-eval.Dockerfile). Every model is the one the production stages load, at a
pinned revision, through the production code paths (chaya_worker.semantic_classes, chaya_worker.grounding_dino,
chaya_worker.clip_embeddings), so a later checkpoint -- e.g. one written by chaya_worker.training -- is compared by
passing it as --seg-model / --det-model on the SAME dataset lock. Three measurements, never mixed:

  seg   per-pixel bucket prediction (SegFormer -> class mapping) against the dataset's semantic masks: IoU per bucket,
        mIoU, pixel accuracy, with image-bootstrap 95% intervals. Two mappings of the same logits are compared paired:
        the keyword rule (before) and class_mapping (after: the reviewed ADE20K-150 table). Also the effect of the
        production confidence floor (semantic_confidence_min): how many pixels it leaves unlabelled and how accurate the
        rest are.
  det   Grounding DINO with the production prompt, against the dataset's boxes of the categories that prompt names
        (COCO_DETECTION_CLASSES): AP@0.5 per class over all scores, and precision/recall at the production box threshold.
        Detection quality only -- 3D localization is a separate measurement this dataset cannot support (no 3D truth).
  ret   CLIP: text query -> ground-truth crop retrieval (recall@1/5, mAP; the query is the bare category name, as a user
        types it), crop -> category zero-shot accuracy, and the same retrieval over the DETECTOR's crops at the production
        threshold (a crop is relevant if it matched a ground-truth box of that category at IoU 0.5) -- what search would
        return end to end, minus 3D.

The dataset must be locked (python -m chaya_worker.datasets.validate <dir> --lock); its name, version and SHA-256 are
recorded with every number. Seeds: torch/numpy seeded; inference is deterministic on CPU, the seed fixes the bootstrap.
"""

from __future__ import annotations

import argparse
import json
import os
import platform
import sys
import time
from collections import defaultdict
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from benchmarks.common.results import Metric, Run, unavailable  # noqa: E402

SEG_MODEL = "nvidia/segformer-b0-finetuned-ade-512-512"
SEG_REVISION = "489d5cd81a0b59fab9b7ea758d3548ebe99677da"
DET_MODEL = "IDEA-Research/grounding-dino-tiny"
DET_REVISION = "a2bb814dd30d776dcf7e30523b00659f4f141c71"
EVAL_BOX_THRESHOLD = 0.10  # low, to trace the precision/recall curve; the production threshold is applied afterwards
BUCKETS = ("floor", "wall", "furniture", "clutter", "stairs")


def load_dataset(root: Path):
    from chaya_worker.datasets.jsonl import read_annotations, read_images
    from chaya_worker.datasets.validate import require_locked

    lock = require_locked(root)
    manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
    images = [r for r in read_images(root / "images.jsonl") if r.split == "test"]
    anns = defaultdict(list)
    for a in read_annotations(root / "annotations.jsonl"):
        anns[a.image_id].append(a)
    return lock, manifest, images, anns


def read_rgb(root: Path, rec) -> np.ndarray:
    from PIL import Image

    with Image.open(root / "images" / rec.relative_path) as im:
        return np.asarray(im.convert("RGB"))


# ---- segmentation ----------------------------------------------------------------------------------------------------

def run_segmentation(run: Run, root: Path, manifest: dict, images, args) -> None:
    import torch
    from chaya_worker.evaluation import bootstrap_ci, confusion, iou_per_class, mean_iou, paired_bootstrap_diff, pixel_accuracy
    from chaya_worker.model_loading import load_pretrained, resolved_revision
    from chaya_worker.model_provenance import provenance
    from chaya_worker.semantic_classes import bucket_label, class_mapping
    from chaya_worker.settings import Settings
    from PIL import Image
    from transformers import AutoImageProcessor, AutoModelForSemanticSegmentation

    classes = list(manifest["semantic_classes"])
    ignore = manifest["preprocessing"].get("ignore_index", 255)
    gt_index = {name: classes.index(name) for name in BUCKETS}  # bucket -> dataset mask value (ramp never predicted)
    n = len(classes)
    floor_conf = Settings().semantic_confidence_min
    processor = AutoImageProcessor.from_pretrained(args.seg_model, revision=args.seg_revision or None)
    model = load_pretrained(AutoModelForSemanticSegmentation.from_pretrained, args.seg_model, revision=args.seg_revision,
                            allow_pickle=False, what="segmentation model").eval()
    prov = provenance(args.seg_model, requested_revision=args.seg_revision, resolved_revision=resolved_revision(model))
    id2label = {int(k): v for k, v in model.config.id2label.items()}
    mappings = {"keyword (before)": {i: bucket_label(v) for i, v in id2label.items()}}
    after, method = class_mapping(id2label)
    mappings[f"class_mapping: {method} (after)"] = after
    lut = {name: np.array([gt_index[m[i]] for i in range(len(id2label))]) for name, m in mappings.items()}

    per_image = {name: [] for name in mappings}
    confident = {name: [] for name in mappings}
    coverage = []
    t0 = time.perf_counter()
    for rec in images:
        rgb = read_rgb(root, rec)
        with Image.open(root / "masks" / rec.semantic_mask) as m:
            gt = np.asarray(m)
        with torch.no_grad():
            inputs = processor(images=rgb, return_tensors="pt")
            logits = model(**inputs).logits
            logits = torch.nn.functional.interpolate(logits, size=rgb.shape[:2], mode="bilinear", align_corners=False)
            probs = torch.softmax(logits[0], dim=0)
            conf, cls = probs.max(dim=0)
        conf, cls = conf.numpy(), cls.numpy()
        sure = conf >= floor_conf
        labelled = gt != ignore
        coverage.append(np.array([float((sure & labelled).sum()), float(labelled.sum())]))
        for name in mappings:
            pred = lut[name][cls]
            per_image[name].append(confusion(gt, pred, n, ignore))
            confident[name].append(confusion(gt, np.where(sure, pred, -1), n, ignore))
    secs = time.perf_counter() - t0

    cond = f"{manifest['name']}@{manifest['version']} ({len(images)} images)"
    run.details["segmentation"] = {"model_provenance": prov.as_dict(), "classes": classes, "mappings": {}}
    run.add(Metric("segmentation inference", round(secs / max(1, len(images)), 3), "s/image", "measured", cond,
                   "CPU, this host", note=f"{args.seg_model}@{prov.resolved_revision}"),
            Metric("model fine-tuned by this project", prov.fine_tuned, "", "measured", cond, "chaya_worker.model_provenance"))
    bucket_ids = [gt_index[b] for b in BUCKETS]
    present = [i for i in bucket_ids if sum(c[i].sum() for c in per_image[next(iter(mappings))]) > 0]
    stat_miou = lambda c: mean_iou(c, present)  # noqa: E731 - buckets that occur in the ground truth
    for name in mappings:
        point, lo, hi = bootstrap_ci(per_image[name], stat_miou, n_boot=args.bootstrap, seed=args.seed)
        total = sum(per_image[name])
        ious = iou_per_class(total)
        run.add(Metric("mIoU over buckets present in ground truth", round(point, 4), "IoU", "measured", f"{cond} | {name}",
                       "chaya_worker.evaluation", note=f"95% CI [{lo:.4f}, {hi:.4f}], image bootstrap n={args.bootstrap}"),
                Metric("pixel accuracy", round(pixel_accuracy(total), 4), "fraction", "measured", f"{cond} | {name}",
                       "chaya_worker.evaluation"))
        for b in BUCKETS:
            i = gt_index[b]
            if np.isnan(ious[i]):
                run.add(unavailable(f"IoU {b}", "IoU", "no pixel of this bucket in ground truth or prediction",
                                    "a dataset with this bucket", f"{cond} | {name}"))
                continue
            p, lo_b, hi_b = bootstrap_ci(per_image[name], lambda c, i=i: float(iou_per_class(c)[i]), n_boot=args.bootstrap,
                                         seed=args.seed)
            run.add(Metric(f"IoU {b}", round(p, 4), "IoU", "measured", f"{cond} | {name}", "chaya_worker.evaluation",
                           note=f"95% CI [{lo_b:.4f}, {hi_b:.4f}]; GT pixels {int(total[i].sum())}"))
        conf_total = sum(confident[name])
        run.add(Metric(f"pixel accuracy of pixels with confidence >= {floor_conf}", round(pixel_accuracy(conf_total), 4),
                       "fraction", "measured", f"{cond} | {name}", "Settings.semantic_confidence_min"))
        run.details["segmentation"]["mappings"][name] = {"confusion_rows_gt_cols_pred": total.tolist(),
                                                         "iou": [None if np.isnan(v) else round(float(v), 4) for v in ious]}
    cov = sum(coverage)
    run.add(Metric(f"labelled pixels with confidence >= {floor_conf}", round(cov[0] / cov[1], 4), "fraction", "measured", cond,
                   "Settings.semantic_confidence_min", note="the rest vote for nothing in SEMANTIC_SEGMENTATION"))
    names = list(mappings)
    d, lo, hi = paired_bootstrap_diff(per_image[names[0]], per_image[names[1]], stat_miou, n_boot=args.bootstrap, seed=args.seed)
    run.add(Metric("mIoU change, after - before (same logits)", round(d, 4), "IoU", "measured", cond, "paired image bootstrap",
                   note=f"95% CI [{lo:.4f}, {hi:.4f}]. A label-mapping change, not a model change."))
    changed = sorted({id2label[i].strip() for i in id2label if mappings[names[0]][i] != mappings[names[1]][i]})
    run.details["segmentation"]["labels_mapped_differently"] = changed


# ---- detection and retrieval -----------------------------------------------------------------------------------------

def phrase_class(label: str, classes: dict[str, tuple[str, ...]]) -> str | None:
    """The dataset class a detector phrase names, or None when it names none or several (e.g. "chair table")."""
    words = " " + " ".join(label.lower().split()) + " "
    hits = {c for c, phrases in classes.items() if any(f" {p} " in words for p in phrases)}
    return hits.pop() if len(hits) == 1 else None


def run_detection_and_retrieval(run: Run, root: Path, manifest: dict, images, anns, args, tasks: set[str]) -> None:
    from chaya_worker.clip_embeddings import ClipEmbedder
    from chaya_worker.evaluation import average_precision, match_image, precision_recall_at, retrieval_metrics, top1_accuracy
    from chaya_worker.settings import Settings
    from chaya_worker.stages.semantic_indexing import has_phrase

    s = Settings()
    cond = f"{manifest['name']}@{manifest['version']} ({len(images)} images)"
    det_classes = {k: tuple(v) for k, v in manifest["preprocessing"]["detection_classes"].items()}
    gt_boxes = {rec.image_id: [(a.object_class, a.bounding_box.to_xyxy(rec.width, rec.height)) for a in anns[rec.image_id]]
                for rec in images}
    detections: dict[str, list] = {}
    if "det" in tasks or "ret" in tasks:
        from chaya_worker.grounding_dino import GroundingDinoDetector

        detector = GroundingDinoDetector(args.det_model, device="cpu", box_threshold=EVAL_BOX_THRESHOLD,
                                         text_threshold=s.object_detection_text_threshold, revision=args.det_revision)
        t0 = time.perf_counter()
        for rec in images:
            detections[rec.image_id] = detector.detect(read_rgb(root, rec), s.object_detection_prompt)
        secs = time.perf_counter() - t0
        run.details["detection"] = {"model_provenance": detector.provenance.as_dict(), "prompt": s.object_detection_prompt,
                                    "eval_box_threshold": EVAL_BOX_THRESHOLD,
                                    "production_box_threshold": s.object_detection_box_threshold,
                                    "text_threshold": s.object_detection_text_threshold}
        run.add(Metric("detection inference", round(secs / max(1, len(images)), 3), "s/image", "measured", cond, "CPU, this host",
                       note=f"{args.det_model}@{detector.provenance.resolved_revision}"),
                Metric("detector fine-tuned by this project", detector.provenance.fine_tuned, "", "measured", cond,
                       "chaya_worker.model_provenance"))

    if "det" in tasks:
        scored, n_gt = defaultdict(list), defaultdict(int)
        raw_scored = defaultdict(list)
        unevaluable, ambiguous = defaultdict(int), 0
        raw_unevaluable, raw_ambiguous = defaultdict(int), 0
        for rec in images:
            preds = defaultdict(list)
            raw_preds = defaultdict(list)
            for d in detections[rec.image_id]:
                c = phrase_class(d.label, det_classes)
                if c is None:
                    if any(f" {p} " in f" {d.label.lower()} " for ps in det_classes.values() for p in ps):
                        ambiguous += 1
                    else:
                        unevaluable[" ".join(d.label.lower().split())] += 1
                else:
                    preds[c].append((d.box_xyxy, d.confidence))

                raw_lbl = d.raw_label if d.raw_label is not None else d.label
                rc = phrase_class(raw_lbl, det_classes)
                if rc is None:
                    if any(f" {p} " in f" {raw_lbl.lower()} " for ps in det_classes.values() for p in ps):
                        raw_ambiguous += 1
                    else:
                        raw_unevaluable[" ".join(raw_lbl.lower().split())] += 1
                else:
                    raw_preds[rc].append((d.box_xyxy, d.confidence))

            for c in det_classes:
                gts = [b for k, b in gt_boxes[rec.image_id] if k == c]
                n_gt[c] += len(gts)
                scored[c].extend(match_image(preds[c], gts))
                raw_scored[c].extend(match_image(raw_preds[c], gts))
        aps = []
        raw_aps = []
        raw_vs_canonical = {}
        for c in det_classes:
            if n_gt[c] == 0:
                run.add(unavailable(f"AP@0.5 {c}", "AP", "no ground-truth box of this class in the dataset",
                                    "a dataset with this class", cond))
                continue
            ap = average_precision(scored[c], n_gt[c])
            raw_ap = average_precision(raw_scored[c], n_gt[c])
            aps.append(ap)
            raw_aps.append(raw_ap)
            p, r, tp, fp = precision_recall_at(scored[c], n_gt[c], s.object_detection_box_threshold)
            rp, rr, rtp, rfp = precision_recall_at(raw_scored[c], n_gt[c], s.object_detection_box_threshold)
            raw_vs_canonical[c] = {
                "gt_boxes": n_gt[c],
                "raw_ap50": round(raw_ap, 4),
                "canonical_ap50": round(ap, 4),
                "raw_precision": None if np.isnan(rp) else round(rp, 4),
                "canonical_precision": None if np.isnan(p) else round(p, 4),
                "raw_recall": round(rr, 4),
                "canonical_recall": round(r, 4),
                "raw_tp": rtp, "raw_fp": rfp,
                "canonical_tp": tp, "canonical_fp": fp,
            }
            run.add(Metric(f"AP@0.5 {c}", round(ap, 4), "AP", "measured", cond, "chaya_worker.evaluation (all-point)",
                           note=f"{n_gt[c]} GT boxes (before canonicalization: {raw_ap:.4f})"),
                    Metric(f"precision at box threshold {s.object_detection_box_threshold} {c}",
                           None if np.isnan(p) else round(p, 4), "fraction", "measured" if not np.isnan(p) else "unavailable",
                           cond, "production threshold", note=None if not np.isnan(p) else "no detection above the threshold"),
                    Metric(f"recall at box threshold {s.object_detection_box_threshold} {c}", round(r, 4), "fraction", "measured",
                           cond, "production threshold", note=f"TP {tp}, FP {fp} (before canonicalization: TP {rtp}, FP {rfp})"))
        if aps:
            run.add(Metric("mAP@0.5 over evaluable classes", round(float(np.mean(aps)), 4), "AP", "measured", cond,
                           "chaya_worker.evaluation",
                           note=f"{len(aps)} classes; before canonicalization: {float(np.mean(raw_aps)):.4f}"))
        # Label quality at the production threshold: what SEMANTIC_INDEXING would actually receive.
        prompt_phrases = [p.strip() for p in s.object_detection_prompt.lower().split(".") if p.strip()]
        kept = [d for dets in detections.values() for d in dets if d.confidence >= s.object_detection_box_threshold]
        empty = sum(1 for d in kept if not has_phrase(d.label))
        merged = sum(1 for d in kept if has_phrase(d.label)
                     and " ".join(d.label.lower().split()) not in prompt_phrases)
        raw_merged = sum(1 for d in kept if has_phrase(d.raw_label or d.label)
                         and " ".join((d.raw_label or d.label).lower().split()) not in prompt_phrases)
        canonicalized = sum(1 for d in kept if d.raw_label and " ".join(d.raw_label.lower().split()) != " ".join(d.label.lower().split()))
        run.add(Metric(f"detections at box threshold {s.object_detection_box_threshold}", len(kept), "count", "measured", cond,
                       "production prompt and thresholds"),
                Metric("detections with no phrase (dropped by SEMANTIC_INDEXING)", round(empty / len(kept), 4) if kept else 0.0,
                       "fraction", "measured", cond, "chaya_worker.stages.semantic_indexing.has_phrase", note=f"{empty} boxes"),
                Metric("detections whose phrase is not one prompt phrase (several merged, or a fragment)",
                       round(merged / len(kept), 4) if kept else 0.0, "fraction", "measured", cond, "Grounding DINO text grouping",
                       note=f"{merged} boxes after canonicalization (raw before canonicalization: {raw_merged}/{len(kept)} = "
                            f"{(raw_merged / len(kept) if kept else 0.0):.4f}; {canonicalized} resolved)"))
        run.details["detection"]["unevaluable_phrases"] = dict(sorted(unevaluable.items(), key=lambda kv: -kv[1]))
        run.details["detection"]["ambiguous_phrases"] = ambiguous
        run.details["detection"]["raw_unevaluable_phrases"] = dict(sorted(raw_unevaluable.items(), key=lambda kv: -kv[1]))
        run.details["detection"]["raw_ambiguous_phrases"] = raw_ambiguous
        run.details["detection"]["raw_vs_canonical"] = raw_vs_canonical
        run.details["detection"]["raw_merged_at_threshold"] = raw_merged
        run.details["detection"]["canonical_merged_at_threshold"] = merged
        run.details["detection"]["canonicalized_at_threshold"] = canonicalized
        run.add(unavailable("3D localization error", "m", "the dataset has no 3D ground truth (COCO images are single "
                            "photographs); localization is measured separately and only on synthetic scenes "
                            "(tests/unit/test_object_localization.py)", "a venue capture with surveyed object positions", cond))

    if "ret" in tasks:
        embedder = ClipEmbedder(s.clip_model_name, s.clip_pretrained, device="cpu")
        import open_clip
        import torch

        tokenizer = open_clip.get_tokenizer(s.clip_model_name)
        crops, crop_class = [], []
        for rec in images:
            rgb = read_rgb(root, rec)
            for c, (x0, y0, x1, y1) in gt_boxes[rec.image_id]:
                crop = rgb[int(y0):int(np.ceil(y1)), int(x0):int(np.ceil(x1))]
                if crop.shape[0] >= 8 and crop.shape[1] >= 8:
                    crops.append(crop)
                    crop_class.append(c)
        queries = sorted(set(crop_class))
        t0 = time.perf_counter()
        img_emb = embedder.embed_images(crops)
        with torch.no_grad():
            txt = embedder.model.encode_text(tokenizer(queries))
            txt = (txt / txt.norm(dim=-1, keepdim=True)).numpy()
        secs = time.perf_counter() - t0
        sim = txt @ img_emb.T
        rel = np.array([[c == q for c in crop_class] for q in queries])
        m = retrieval_metrics(sim, rel)
        ccond = f"{cond} | {len(crops)} ground-truth crops, {len(queries)} category queries"
        run.add(Metric("text->crop recall@1", round(m["recall@1"], 4), "fraction", "measured", ccond, "chaya_worker.evaluation"),
                Metric("text->crop recall@5", round(m["recall@5"], 4), "fraction", "measured", ccond, "chaya_worker.evaluation"),
                Metric("text->crop precision@5", round(m["precision@5"], 4), "fraction", "measured", ccond, "chaya_worker.evaluation"),
                Metric("text->crop mAP", round(m["mAP"], 4), "AP", "measured", ccond, "chaya_worker.evaluation"),
                Metric("crop->category zero-shot top-1", round(top1_accuracy(sim.T, np.array([queries.index(c) for c in crop_class])), 4),
                       "fraction", "measured", ccond, "chaya_worker.evaluation"),
                Metric("CLIP embedding time", round(secs, 2), "s", "measured", ccond, "CPU, this host"))
        run.details["retrieval"] = {"model_provenance": embedder.provenance.as_dict(), "queries": queries,
                                    "per_query_crops": {q: int(sum(c == q for c in crop_class)) for q in queries}}
        # end to end: the detector's crops at the production threshold
        det_crops, det_truth = [], []
        for rec in images:
            rgb = read_rgb(root, rec)
            kept = [d for d in detections.get(rec.image_id, []) if d.confidence >= s.object_detection_box_threshold]
            for d in kept:
                x0, y0, x1, y1 = (int(max(0, v)) for v in d.box_xyxy)
                crop = rgb[y0:y1, x0:x1]
                if crop.shape[0] < 8 or crop.shape[1] < 8:
                    continue
                from chaya_worker.evaluation import box_iou

                truth = {c for c, b in gt_boxes[rec.image_id] if box_iou(d.box_xyxy, b) >= 0.5}
                det_crops.append(crop)
                det_truth.append(truth)
        if det_crops:
            demb = embedder.embed_images(det_crops)
            evaluable = [q for q in queries if any(q in t for t in det_truth)]
            if evaluable:
                with torch.no_grad():
                    t2 = embedder.model.encode_text(tokenizer(evaluable))
                    t2 = (t2 / t2.norm(dim=-1, keepdim=True)).numpy()
                m2 = retrieval_metrics(t2 @ demb.T, np.array([[q in t for t in det_truth] for q in evaluable]))
                dcond = (f"{cond} | {len(det_crops)} detector crops at threshold {s.object_detection_box_threshold}, "
                         f"{len(evaluable)} queries")
                run.add(Metric("text->detector-crop recall@5", round(m2["recall@5"], 4), "fraction", "measured", dcond,
                               "chaya_worker.evaluation", note="relevant = the crop matches a GT box of the query category at IoU 0.5"),
                        Metric("text->detector-crop precision@5", round(m2["precision@5"], 4), "fraction", "measured", dcond,
                               "chaya_worker.evaluation"),
                        Metric("text->detector-crop mAP", round(m2["mAP"], 4), "AP", "measured", dcond, "chaya_worker.evaluation"))


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dataset", type=Path, required=True)
    ap.add_argument("--tasks", default="seg,det,ret")
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--seg-model", default=SEG_MODEL)
    ap.add_argument("--seg-revision", default=SEG_REVISION)
    ap.add_argument("--det-model", default=DET_MODEL)
    ap.add_argument("--det-revision", default=DET_REVISION)
    ap.add_argument("--bootstrap", type=int, default=1000)
    ap.add_argument("--seed", type=int, default=0)
    args = ap.parse_args(argv)
    tasks = {t.strip() for t in args.tasks.split(",") if t.strip()}

    import torch

    torch.manual_seed(args.seed)
    np.random.seed(args.seed)
    lock, manifest, images, anns = load_dataset(args.dataset)
    if args.limit:
        images = images[: args.limit]
    run = Run("b6_semantic_models", "Semantic models: segmentation, detection and retrieval on a held-out dataset")
    import transformers

    try:
        import open_clip
        clip_version = open_clip.__version__
    except ImportError:
        clip_version = None
    run.environment = {"torch": torch.__version__, "transformers": transformers.__version__, "open_clip": clip_version,
                       "numpy": np.__version__, "cuda": torch.cuda.is_available(), "torch_threads": torch.get_num_threads(),
                       "processor": platform.processor() or platform.machine(), "host_cpus": os.cpu_count()}
    run.inputs["dataset"] = {"path": str(args.dataset), "name": lock["name"], "version": lock["version"], "sha256": lock["sha256"],
                             "images_evaluated": len(images), "split": "test"}
    run.details["config"] = {"tasks": sorted(tasks), "seed": args.seed, "bootstrap": args.bootstrap, "limit": args.limit,
                             "seg_model": args.seg_model, "seg_revision": args.seg_revision, "det_model": args.det_model,
                             "det_revision": args.det_revision}
    started = time.perf_counter()
    if "seg" in tasks:
        run_segmentation(run, args.dataset, manifest, images, args)
    if tasks & {"det", "ret"}:
        run_detection_and_retrieval(run, args.dataset, manifest, images, anns, args, tasks)
    run.environment["elapsed_seconds"] = round(time.perf_counter() - started, 1)
    path = run.write()
    print(f"wrote {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
