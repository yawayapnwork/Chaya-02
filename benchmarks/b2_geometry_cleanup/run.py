"""Benchmark 2: geometry cleanup (docs/BENCHMARKS.md).

Methods (the pipeline's own functions, chaya_worker.geometry_cleanup, with the pipeline's own settings):

    baseline             no cleanup
    opacity_filtering    opacity_threshold_mask            (needs Gaussian opacities: splat input only)
    outlier_filtering    statistical_outlier_mask (Open3D) and radius_outlier_mask (Open3D)
    semantic_aware       semantic_aware_mask (Open3D + SEMANTIC_SEGMENTATION labels)

Which metrics are valid depends on the input, and the runner only reports those:

  --ply <trained splat>       point counts, runtime, and overlap between methods (Jaccard of removed sets).
                              Whether a removal improved the scene needs re-rendering at held-out views
                              (PSNR/SSIM) -- torch + gsplat + CUDA + the scene's training frames -- reported as
                              unavailable otherwise.
  --sfm-points points3D.txt   a PROXY on real SfM geometry: COLMAP stores, for every 3D point, its mean
                              reprojection error and track length. Outlier filtering is scored by how strongly the
                              points it removes are enriched in weakly supported points (track length 2 or
                              reprojection error above the cloud's 90th percentile). Opacity and semantic-aware
                              cleanup do not apply to SfM points.

Scale: splats trained from COLMAP poses and SfM clouds have an arbitrary unit. The statistical filter is
scale-invariant (neighbour count, std ratio). The radius filter used to be a fixed 0.05 "m" applied to arbitrary units;
production now uses Settings.cleanup_radius_spacing_factor x the cloud's median nearest-neighbour distance
(docs/coordinate-frames.md), so both radius rows below apply that rule (production factor, and this benchmark's own
RADIUS_RELATIVE_K). Results recorded before that change measured the old fixed radius.

    python benchmarks/b2_geometry_cleanup/run.py --ply playroom/point_cloud.ply --sfm-points castle/points3D.txt
"""

from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from benchmarks.common.results import Metric, Run, sha256_file, unavailable  # noqa: E402

RADIUS_RELATIVE_K = 3.0


def median_nn(positions: np.ndarray, sample: int = 20000, seed: int = 0) -> float:
    from scipy.spatial import cKDTree
    rng = np.random.default_rng(seed)
    idx = rng.choice(len(positions), size=min(sample, len(positions)), replace=False)
    d, _ = cKDTree(positions).query(positions[idx], k=2)
    return float(np.median(d[:, 1]))


def timed(fn):
    t0 = time.perf_counter()
    out = fn()
    return out, time.perf_counter() - t0


def jaccard(a: np.ndarray, b: np.ndarray) -> float:
    """Jaccard similarity of the REMOVED sets (masks are keep-masks)."""
    ra, rb = ~a, ~b
    union = int((ra | rb).sum())
    return int((ra & rb).sum()) / union if union else 1.0


def splat_benchmark(run: Run, ply: Path, labels_path: Path | None) -> None:
    from chaya_worker.geometry_cleanup import opacity_threshold_mask, radius_outlier_mask, semantic_aware_mask, statistical_outlier_mask
    from chaya_worker.ply import read_ply
    from chaya_worker.settings import Settings

    s = Settings()
    cloud, load_s = timed(lambda: read_ply(ply))
    n = len(cloud)
    nn = median_nn(cloud.positions)
    cond = f"trained splat: {ply.parent.name}"
    run.inputs["splat"] = {"file": ply.name, "sha256": sha256_file(ply), "gaussians": n}
    run.add(Metric("Gaussians in the input splat", n, "count", "measured", cond, "chaya_worker.ply.read_ply"),
            Metric("median nearest-neighbour distance", round(nn, 6), "scene units", "measured", cond, "scipy cKDTree, 20k sample"),
            Metric("opacity threshold", s.cleanup_opacity_threshold, "sigmoid(opacity)", "configuration", cond, "Settings.cleanup_opacity_threshold"),
            Metric("statistical filter", f"{s.cleanup_stat_nb_neighbors} neighbours, std ratio {s.cleanup_stat_std_ratio}", "",
                   "configuration", cond, "Settings.cleanup_stat_*"),
            Metric("radius filter (production)", f"{s.cleanup_radius_nb_points} points within {s.cleanup_radius_spacing_factor} x "
                   f"median NN = {s.cleanup_radius_spacing_factor * nn:.5f}", "scene units", "configuration", cond,
                   "Settings.cleanup_radius_spacing_factor", note="scale-invariant: production uses the same rule"),
            Metric("radius filter (scale-relative)", f"{s.cleanup_radius_nb_points} points within {RADIUS_RELATIVE_K} x median NN "
                   f"= {RADIUS_RELATIVE_K * nn:.5f}", "scene units", "configuration", cond, "this benchmark"))

    masks: dict[str, np.ndarray] = {"baseline": np.ones(n, dtype=bool)}
    runs = {
        "opacity_filtering": lambda: opacity_threshold_mask(cloud, s.cleanup_opacity_threshold),
        "outlier_filtering (statistical)": lambda: statistical_outlier_mask(
            cloud, nb_neighbors=s.cleanup_stat_nb_neighbors, std_ratio=s.cleanup_stat_std_ratio)[0],
        "outlier_filtering (radius, production spacing factor)": lambda: radius_outlier_mask(
            cloud, nb_points=s.cleanup_radius_nb_points, radius=s.cleanup_radius_spacing_factor * nn)[0],
        "outlier_filtering (radius, scale-relative)": lambda: radius_outlier_mask(
            cloud, nb_points=s.cleanup_radius_nb_points, radius=RADIUS_RELATIVE_K * nn)[0],
    }
    for name, fn in runs.items():
        mask, secs = timed(fn)
        masks[name] = mask
        removed = n - int(mask.sum())
        run.add(Metric("Gaussians removed", removed, "count", "measured", f"{cond} | {name}", "chaya_worker.geometry_cleanup"),
                Metric("Gaussians removed", round(100 * removed / n, 3), "%", "measured", f"{cond} | {name}", "chaya_worker.geometry_cleanup"),
                Metric("runtime", round(secs, 3), "s", "measured", f"{cond} | {name}", "wall clock, this host's CPU"))
    opacity = 1 / (1 + np.exp(-cloud.opacity_logit))
    for name, mask in masks.items():
        if name == "baseline" or mask.all():
            continue
        run.add(Metric("median opacity of removed Gaussians", round(float(np.median(opacity[~mask])), 4), "sigmoid", "measured",
                       f"{cond} | {name}", "descriptive"))
    names = [k for k in masks if k != "baseline"]
    run.details["removed_set_jaccard"] = {f"{a} vs {b}": round(jaccard(masks[a], masks[b]), 4)
                                          for i, a in enumerate(names) for b in names[i + 1:]}
    run.details["median_opacity_all"] = round(float(np.median(opacity)), 4)

    if labels_path is None:
        run.add(unavailable("Gaussians removed", "count", "no SEMANTIC_SEGMENTATION labels exist for this splat: producing them needs "
                            "the scene's frames, poses and the segmentation model (a GPU pipeline run)",
                            "python benchmarks/b2_geometry_cleanup/run.py --ply <splat> --labels <semantic-labels.json from the "
                            "run's SEMANTIC_SEGMENTATION artifact>", condition=f"{cond} | semantic_aware"))
    else:
        import json
        labels = np.asarray(json.loads(labels_path.read_text(encoding="utf-8"))["labels"])
        mask, secs = timed(lambda: semantic_aware_mask(cloud, labels, radius=s.cleanup_radius_spacing_factor * nn,
                                                       min_same_class_neighbors=s.cleanup_semantic_min_neighbors)[0])
        run.add(Metric("Gaussians removed", n - int(mask.sum()), "count", "measured", f"{cond} | semantic_aware", "geometry_cleanup"),
                Metric("runtime", round(secs, 3), "s", "measured", f"{cond} | semantic_aware", "wall clock"))
    for m in ("PSNR at held-out views", "SSIM at held-out views"):
        run.add(unavailable(m, "dB" if m.startswith("PSNR") else "", "re-rendering the cleaned splat needs torch + gsplat + a CUDA "
                            "GPU and the scene's training frames and poses; this host has no CUDA device",
                            "python -m chaya_worker.benchmarks.cleanup_benchmark --ply <splat> --poses poses.json "
                            "--sparse-model <sparse> --frames <frames dir> --sample-cameras 8 --out report.json   (on a GPU host)",
                            condition=f"{cond} | every method"))


def read_points3d(path: Path) -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
    xyz, rgb, err, track = [], [], [], []
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        p = line.split()
        xyz.append([float(p[1]), float(p[2]), float(p[3])])
        rgb.append([int(p[4]), int(p[5]), int(p[6])])
        err.append(float(p[7]))
        track.append((len(p) - 8) // 2)
    return np.asarray(xyz), np.asarray(rgb), np.asarray(err), np.asarray(track)


def sfm_proxy(run: Run, points: Path) -> None:
    from chaya_worker.geometry_cleanup import radius_outlier_mask, statistical_outlier_mask
    from chaya_worker.ply import GaussianCloud
    from chaya_worker.settings import Settings

    s = Settings()
    xyz, rgb, err, track = read_points3d(points)
    n = len(xyz)
    nn = median_nn(xyz)
    # Only positions matter to the outlier filters; the other Gaussian fields are filled neutrally and never scored.
    cloud = GaussianCloud(positions=xyz.astype(np.float32), scales_log=np.full((n, 3), np.log(nn), dtype=np.float32),
                          rotations_wxyz=np.tile(np.array([1, 0, 0, 0], np.float32), (n, 1)),
                          opacity_logit=np.zeros(n, np.float32), colors_dc=((rgb / 255.0 - 0.5) / 0.28209).astype(np.float32))
    err_p90 = float(np.percentile(err, 90))
    weak = (track <= 2) | (err > err_p90)
    base_rate = float(weak.mean())
    cond = f"real SfM cloud (proxy): {points.parent.name}"
    run.inputs["sfm_points"] = {"file": points.name, "sha256": sha256_file(points), "points": n}
    run.add(Metric("SfM points", n, "count", "measured", cond, "COLMAP points3D.txt"),
            Metric("weakly supported points (track <= 2 or error > p90)", round(100 * base_rate, 2), "%", "measured", cond,
                   "COLMAP per-point track length and reprojection error", note=f"p90 reprojection error = {err_p90:.3f} px"),
            Metric("weak-point definition", "track length <= 2 OR reprojection error > the cloud's 90th percentile", "", "assumption", cond,
                   "this benchmark", note="COLMAP's own evidence of a poorly constrained point; not ground truth"))
    methods = {
        "outlier_filtering (statistical)": lambda: statistical_outlier_mask(
            cloud, nb_neighbors=s.cleanup_stat_nb_neighbors, std_ratio=s.cleanup_stat_std_ratio)[0],
        "outlier_filtering (radius, scale-relative)": lambda: radius_outlier_mask(
            cloud, nb_points=s.cleanup_radius_nb_points, radius=RADIUS_RELATIVE_K * nn)[0],
    }
    for name, fn in methods.items():
        mask, secs = timed(fn)
        removed = ~mask
        k = int(removed.sum())
        c = f"{cond} | {name}"
        run.add(Metric("points removed", round(100 * k / n, 2), "%", "measured", c, "chaya_worker.geometry_cleanup"),
                Metric("runtime", round(secs, 3), "s", "measured", c, "wall clock"))
        if k:
            precision = float(weak[removed].mean())
            run.add(Metric("weak points among removed (precision)", round(100 * precision, 2), "%", "measured", c, "vs COLMAP evidence"),
                    Metric("enrichment over base rate", round(precision / base_rate, 3) if base_rate else float("nan"), "x", "measured", c,
                           "precision / base rate", note="1.0 = no better than removing points at random"),
                    Metric("share of all weak points removed (recall)", round(100 * float(removed[weak].mean()), 2), "%", "measured", c,
                           "vs COLMAP evidence"),
                    Metric("median reprojection error removed / kept", f"{np.median(err[removed]):.3f} / {np.median(err[mask]):.3f}", "px",
                           "measured", c, "COLMAP"))
    for m in ("opacity_filtering", "semantic_aware"):
        run.add(unavailable("points removed", "%", "SfM points carry no opacity or semantic label; this method needs a trained splat"
                            + (" and SEMANTIC_SEGMENTATION labels" if m == "semantic_aware" else ""),
                            "python benchmarks/b2_geometry_cleanup/run.py --ply <trained splat> [--labels <labels.json>]",
                            condition=f"{cond} | {m}"))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--ply", type=Path)
    ap.add_argument("--labels", type=Path)
    ap.add_argument("--sfm-points", type=Path)
    a = ap.parse_args()
    if not a.ply and not a.sfm_points:
        sys.exit("give --ply and/or --sfm-points")
    import open3d
    run = Run("b2_geometry_cleanup", "Geometry cleanup: baseline vs opacity vs outlier vs semantic-aware")
    run.environment = {"open3d": open3d.__version__, "numpy": np.__version__}
    if a.ply:
        splat_benchmark(run, a.ply, a.labels)
    if a.sfm_points:
        sfm_proxy(run, a.sfm_points)
    path = run.write()
    for m in run.metrics:
        print(f"  [{m.kind:12}] {m.condition:70} {m.metric}: {m.value} {m.unit}")
    print(run.details.get("removed_set_jaccard"))
    print(f"wrote {path}")


if __name__ == "__main__":
    main()
