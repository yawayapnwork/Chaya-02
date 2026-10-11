"""Evaluation metrics for segmentation, detection and retrieval (benchmarks/b6_semantic_models). Pure numpy, no ML
dependency, so every formula is unit-tested on hand-computed cases (tests/unit/test_evaluation.py).

Uncertainty is a percentile bootstrap over IMAGES (the independent unit; pixels and boxes of one image are correlated):
resample images with replacement, recompute the statistic from the resampled images' accumulated counts. A paired
bootstrap of a difference resamples the same images for both methods, which is what makes two mappings or two
checkpoints evaluated on the same images comparable.
"""

from __future__ import annotations

from collections.abc import Callable, Sequence

import numpy as np

# ---- segmentation ----------------------------------------------------------------------------------------------------

def confusion(gt: np.ndarray, pred: np.ndarray, n_classes: int, ignore_index: int) -> np.ndarray:
    """(n, n) counts, rows = ground truth, columns = prediction, over pixels whose ground truth is not `ignore_index`.
    A prediction outside 0..n-1 (e.g. an abstention) counts in no column but still in its row's total via `abstained`."""
    gt = np.asarray(gt).ravel()
    pred = np.asarray(pred).ravel()
    keep = (gt != ignore_index) & (gt < n_classes) & (pred >= 0) & (pred < n_classes)
    idx = gt[keep].astype(np.int64) * n_classes + pred[keep].astype(np.int64)
    return np.bincount(idx, minlength=n_classes * n_classes).reshape(n_classes, n_classes)


def iou_per_class(conf: np.ndarray) -> np.ndarray:
    """IoU per class; NaN for a class absent from both ground truth and prediction."""
    tp = np.diag(conf).astype(np.float64)
    denom = conf.sum(axis=0) + conf.sum(axis=1) - tp
    with np.errstate(divide="ignore", invalid="ignore"):
        return np.where(denom > 0, tp / denom, np.nan)


def mean_iou(conf: np.ndarray, classes: Sequence[int] | None = None) -> float:
    ious = iou_per_class(conf)
    if classes is not None:
        ious = ious[list(classes)]
    return float(np.nanmean(ious)) if np.any(~np.isnan(ious)) else float("nan")


def pixel_accuracy(conf: np.ndarray) -> float:
    total = conf.sum()
    return float(np.trace(conf) / total) if total else float("nan")


# ---- bootstrap ---------------------------------------------------------------------------------------------------------

def bootstrap_ci(per_image: Sequence[np.ndarray], statistic: Callable[[np.ndarray], float], *, n_boot: int = 1000,
                 seed: int = 0, alpha: float = 0.05) -> tuple[float, float, float]:
    """(point estimate, low, high): `statistic` of the summed per-image arrays, and its percentile bootstrap interval."""
    stack = np.stack([np.asarray(a, dtype=np.float64) for a in per_image])
    point = statistic(stack.sum(axis=0))
    rng = np.random.default_rng(seed)
    n = len(stack)
    samples = []
    for _ in range(n_boot):
        idx = rng.integers(0, n, n)
        value = statistic(stack[idx].sum(axis=0))
        if not np.isnan(value):
            samples.append(value)
    if not samples:
        return point, float("nan"), float("nan")
    lo, hi = np.percentile(samples, [100 * alpha / 2, 100 * (1 - alpha / 2)])
    return point, float(lo), float(hi)


def paired_bootstrap_diff(per_image_a: Sequence[np.ndarray], per_image_b: Sequence[np.ndarray],
                          statistic: Callable[[np.ndarray], float], *, n_boot: int = 1000, seed: int = 0,
                          alpha: float = 0.05) -> tuple[float, float, float]:
    """(statistic(b) - statistic(a), low, high) with the same image resample for both."""
    a = np.stack([np.asarray(x, dtype=np.float64) for x in per_image_a])
    b = np.stack([np.asarray(x, dtype=np.float64) for x in per_image_b])
    if a.shape != b.shape:
        raise ValueError("paired bootstrap needs the same images for both methods")
    point = statistic(b.sum(axis=0)) - statistic(a.sum(axis=0))
    rng = np.random.default_rng(seed)
    diffs = []
    for _ in range(n_boot):
        idx = rng.integers(0, len(a), len(a))
        d = statistic(b[idx].sum(axis=0)) - statistic(a[idx].sum(axis=0))
        if not np.isnan(d):
            diffs.append(d)
    lo, hi = np.percentile(diffs, [100 * alpha / 2, 100 * (1 - alpha / 2)]) if diffs else (float("nan"), float("nan"))
    return float(point), float(lo), float(hi)


# ---- detection ---------------------------------------------------------------------------------------------------------

def box_iou(a: Sequence[float], b: Sequence[float]) -> float:
    """IoU of two xyxy boxes."""
    ix = max(0.0, min(a[2], b[2]) - max(a[0], b[0]))
    iy = max(0.0, min(a[3], b[3]) - max(a[1], b[1]))
    inter = ix * iy
    union = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
    return inter / union if union > 0 else 0.0


def match_image(preds: list[tuple[Sequence[float], float]], gts: list[Sequence[float]], iou: float = 0.5) -> list[tuple[float, bool]]:
    """One image, one class: greedy matching in descending score (each ground-truth box matched at most once).
    Returns (score, true positive) per prediction."""
    used = [False] * len(gts)
    out = []
    for box, score in sorted(preds, key=lambda p: -p[1]):
        best, best_iou = -1, iou
        for j, g in enumerate(gts):
            if not used[j]:
                v = box_iou(box, g)
                if v >= best_iou:
                    best, best_iou = j, v
        if best >= 0:
            used[best] = True
        out.append((score, best >= 0))
    return out


def average_precision(scored: list[tuple[float, bool]], n_gt: int) -> float:
    """All-point interpolated AP (area under the monotone precision envelope), over predictions from every image."""
    if n_gt == 0:
        return float("nan")
    if not scored:
        return 0.0
    order = sorted(scored, key=lambda s: -s[0])
    tp = np.cumsum([1 if t else 0 for _, t in order])
    fp = np.cumsum([0 if t else 1 for _, t in order])
    recall = tp / n_gt
    precision = tp / np.maximum(tp + fp, 1)
    mrec = np.concatenate([[0.0], recall, [1.0]])
    mpre = np.concatenate([[0.0], precision, [0.0]])
    for i in range(len(mpre) - 2, -1, -1):
        mpre[i] = max(mpre[i], mpre[i + 1])
    idx = np.where(mrec[1:] != mrec[:-1])[0]
    return float(np.sum((mrec[idx + 1] - mrec[idx]) * mpre[idx + 1]))


def precision_recall_at(scored: list[tuple[float, bool]], n_gt: int, threshold: float) -> tuple[float, float, int, int]:
    """(precision, recall, true positives, false positives) of the predictions scoring >= threshold."""
    kept = [t for s, t in scored if s >= threshold]
    tp = sum(kept)
    fp = len(kept) - tp
    precision = tp / len(kept) if kept else float("nan")
    recall = tp / n_gt if n_gt else float("nan")
    return precision, recall, tp, fp


# ---- retrieval ---------------------------------------------------------------------------------------------------------

def retrieval_metrics(similarity: np.ndarray, relevant: np.ndarray, ks: Sequence[int] = (1, 5)) -> dict[str, float]:
    """Text->image retrieval: `similarity` (queries x items), `relevant` (queries x items, bool). Queries with no relevant
    item are skipped. Returns recall@k (any relevant item in the top k), precision@k, and mAP over queries."""
    out: dict[str, list[float]] = {f"recall@{k}": [] for k in ks} | {f"precision@{k}": [] for k in ks} | {"ap": []}
    for q in range(similarity.shape[0]):
        rel = relevant[q]
        if not rel.any():
            continue
        order = np.argsort(-similarity[q], kind="stable")
        hits = rel[order]
        for k in ks:
            out[f"recall@{k}"].append(float(hits[:k].any()))
            out[f"precision@{k}"].append(float(hits[:k].mean()))
        cum = np.cumsum(hits)
        ranks = np.where(hits)[0]
        out["ap"].append(float(np.mean(cum[ranks] / (ranks + 1))))
    return {("mAP" if k == "ap" else k): (float(np.mean(v)) if v else float("nan")) for k, v in out.items()} | {
        "queries": float(len(out["ap"]))}


def top1_accuracy(similarity: np.ndarray, true_index: np.ndarray) -> float:
    """Image->text zero-shot classification: rows = images, columns = class texts."""
    return float(np.mean(np.argmax(similarity, axis=1) == true_index)) if len(true_index) else float("nan")
