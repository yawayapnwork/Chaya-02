"""Benchmark 2, controlled part: every cleanup method, on the SAME synthetic scenes with per-point ground truth.

    docker run --rm -v "$PWD:/repo" -w /repo -e PYTHONPATH=/repo/services/reconstruction chaya-bench-open3d \\
        python benchmarks/b2_geometry_cleanup/synthetic.py [--seeds 5] [--confusion benchmarks/results/b6_semantic_models/latest.json]

Why synthetic: semantic-aware cleanup needs SEMANTIC_SEGMENTATION labels on a reconstructed splat, which needs a GPU
pipeline run that does not exist yet (docs/BENCHMARKS.md, B2). The scene (synthetic_scene.py) is an assumption, not a
measurement of real splats: results say how the methods behave on THESE structures and noise types, nothing more.

Methods (the production functions and Settings): opacity threshold; statistical outlier; radius outlier; semantic-aware
alone; and the production chain GEOMETRIC_CLEANUP runs (statistical -> radius -> semantic-aware). Opacity filtering is not
part of the production chain (Settings.cleanup_opacity_threshold is read only by benchmarks).

Label scenarios for semantic-aware cleanup:
  oracle, noise = background  structure labelled correctly; each noise point gets the bucket of the surface behind it,
                              which is what projecting 2D segmentation onto a floater gives;
  oracle, noise = unknown     noise points labelled unknown (no confident view);
  measured confusion          every point's label drawn from the stock SegFormer's MEASURED pixel confusion on the B6
                              indoor set (reviewed mapping), given its true bucket. Per-point independent draws are a
                              simplification: multi-view voting would correlate and partly correct them.

Metrics, on the same points for every method: noise removed (recall) and the share of removed points that are noise
(precision); for each structure part (floor, upper floor, ramp, stairs, wall, furniture, thin furniture, clutter) the share
kept. No PSNR/SSIM: there are no rendered ground-truth images of a synthetic point set.
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from benchmarks.b2_geometry_cleanup.synthetic_scene import (  # noqa: E402
    BUCKET,
    NOISE,
    SCENE_PARAMS,
    STRUCTURE,
    build,
    nearest_structure_bucket,
)
from benchmarks.common.results import Metric, Run, sha256_file  # noqa: E402

BUCKETS = ("floor", "wall", "furniture", "clutter", "stairs")


def cloud_of(scene):
    from chaya_worker.ply import GaussianCloud

    n = len(scene.positions)
    p = np.clip(scene.opacity, 1e-6, 1 - 1e-6)
    return GaussianCloud(positions=scene.positions, scales_log=np.zeros((n, 3), np.float32),
                         rotations_wxyz=np.tile(np.array([1, 0, 0, 0], np.float32), (n, 1)),
                         opacity_logit=np.log(p / (1 - p)).astype(np.float32), colors_dc=np.zeros((n, 3), np.float32))


def measured_labels(scene, confusion_rows: np.ndarray, rng) -> np.ndarray:
    """Pure (given rng): each point's label drawn from the confusion row of its true bucket (noise: the bucket behind)."""
    behind = nearest_structure_bucket(scene)
    true = np.array([BUCKET.get(t, behind[i]) for i, t in enumerate(scene.truth)], dtype=object)
    probs = confusion_rows / confusion_rows.sum(axis=1, keepdims=True)
    out = np.empty(len(true), dtype=object)
    for b, name in enumerate(BUCKETS):
        idx = np.where(true == name)[0]
        out[idx] = np.array(BUCKETS, dtype=object)[rng.choice(len(BUCKETS), size=len(idx), p=probs[b])]
    return out


def evaluate(scene, keep: np.ndarray) -> dict[str, float]:
    noise = np.isin(scene.truth, NOISE)
    removed = ~keep
    out = {"removed": int(removed.sum()),
           "noise_recall": float((removed & noise).sum() / max(1, noise.sum())),
           "noise_precision": float((removed & noise).sum() / removed.sum()) if removed.any() else float("nan")}
    for part in STRUCTURE:
        sel = scene.truth == part
        out[f"kept {part}"] = float(keep[sel].mean())
    return out


def run_methods(scene, labels_by_scenario: dict[str, np.ndarray]) -> dict[str, dict[str, float]]:
    from chaya_worker.geometry_cleanup import (
        characteristic_spacing,
        opacity_threshold_mask,
        radius_min_neighbors,
        radius_outlier_mask,
        semantic_aware_mask,
        statistical_outlier_mask,
    )
    from chaya_worker.settings import Settings

    s = Settings()
    cloud = cloud_of(scene)
    radius = s.cleanup_radius_spacing_factor * characteristic_spacing(cloud.positions)
    nb_points, _ = radius_min_neighbors(cloud.positions, radius, cap=s.cleanup_radius_nb_points, share=s.cleanup_radius_neighbor_share)
    results, timing = {}, {}

    def timed(name, fn):
        t0 = time.perf_counter()
        keep = fn()
        timing[name] = time.perf_counter() - t0
        results[name] = evaluate(scene, keep) | {"seconds": timing[name]}
        return keep

    timed("no cleanup", lambda: np.ones(len(cloud), bool))
    timed(f"opacity threshold {s.cleanup_opacity_threshold}", lambda: opacity_threshold_mask(cloud, s.cleanup_opacity_threshold))
    stat = timed("statistical outlier", lambda: statistical_outlier_mask(cloud, nb_neighbors=s.cleanup_stat_nb_neighbors,
                                                                        std_ratio=s.cleanup_stat_std_ratio)[0])
    rad = timed("radius outlier (production radius)", lambda: radius_outlier_mask(cloud, nb_points=nb_points, radius=radius)[0])
    for scenario, labels in labels_by_scenario.items():
        timed(f"semantic-aware alone | {scenario}", lambda labels=labels: semantic_aware_mask(
            cloud, labels, radius=radius, min_same_class_neighbors=s.cleanup_semantic_min_neighbors)[0])

        def chain(labels=labels):
            keep = stat & rad
            sub = cloud.subset(keep)
            sem, _ = semantic_aware_mask(sub, labels[keep], radius=radius, min_same_class_neighbors=s.cleanup_semantic_min_neighbors)
            full = np.ones(len(cloud), bool)
            full[np.where(keep)[0]] = sem
            return keep & full
        timed(f"production chain (statistical -> radius -> semantic) | {scenario}", chain)
    keep = stat & rad
    results["production chain without labels (statistical -> radius)"] = evaluate(scene, keep)
    return results


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--seeds", type=int, default=5)
    ap.add_argument("--confusion", type=Path, default=Path("benchmarks/results/b6_semantic_models/latest.json"))
    args = ap.parse_args(argv)
    run = Run("b2_geometry_cleanup_synthetic", "Geometry cleanup methods on controlled synthetic scenes with ground truth")
    conf = None
    if args.confusion.is_file():
        doc = json.loads(args.confusion.read_text(encoding="utf-8"))
        mappings = doc["details"]["segmentation"]["mappings"]
        key = next(k for k in mappings if "after" in k)
        classes = doc["details"]["segmentation"]["classes"]
        full = np.array(mappings[key]["confusion_rows_gt_cols_pred"], dtype=np.float64)
        idx = [classes.index(b) for b in BUCKETS]
        conf = full[np.ix_(idx, idx)] + 1e-9  # a bucket never seen keeps a (vanishing) chance of each label
        run.inputs["confusion"] = {"file": str(args.confusion), "sha256": sha256_file(args.confusion), "mapping": key,
                                   "dataset": doc["inputs"]["dataset"]}
    per_seed = []
    for seed in range(args.seeds):
        scene = build(seed)
        behind = nearest_structure_bucket(scene)
        truth_bucket = np.array([BUCKET.get(t, behind[i]) for i, t in enumerate(scene.truth)], dtype=object)
        noise = np.isin(scene.truth, NOISE)
        scenarios = {"oracle, noise = background": truth_bucket,
                     "oracle, noise = unknown": np.where(noise, "unknown", truth_bucket).astype(object)}
        if conf is not None:
            scenarios["measured confusion (B6)"] = measured_labels(scene, conf, np.random.default_rng(1000 + seed))
        per_seed.append(run_methods(scene, scenarios))
        if seed == 0:
            run.inputs["scene"] = {"points": len(scene.positions), "params": SCENE_PARAMS,
                                   "parts": {p: int((scene.truth == p).sum()) for p in (*STRUCTURE, *NOISE)}}
    methods = list(per_seed[0])
    summary = {}
    for m in methods:
        keys = [k for k in per_seed[0][m] if k != "seconds"]
        summary[m] = {k: (float(np.mean([r[m][k] for r in per_seed])), float(np.std([r[m][k] for r in per_seed]))) for k in keys}
        cond = f"synthetic scene x {args.seeds} seeds | {m}"
        for k, (mean, std) in summary[m].items():
            unit = "count" if k == "removed" else "fraction"
            if np.isnan(mean):
                continue
            run.add(Metric(k, round(mean, 4), unit, "measured", cond, "benchmarks/b2_geometry_cleanup/synthetic.py",
                           note=f"std over seeds {std:.4f}"))
    run.details["per_seed"] = per_seed
    run.details["what_this_is"] = "Synthetic scenes with assumed geometry, opacities and noise (SCENE_PARAMS); not real splats."
    path = run.write()
    print(f"wrote {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
