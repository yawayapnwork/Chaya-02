"""Reconstruction coverage of a floor plan: the real-data half of Benchmark 1 (docs/BENCHMARKS.md).

Given one or more reconstructed splats of the SAME venue (e.g. A = recon lap only, B = recon lap + planned route),
already registered to the floor plan's frame, measures how much of the floor was actually reconstructed:

    target cells   floor-plan polygons rasterised at --resolution (default 0.25 m, the planner's own grid)
    covered cell   at least --min-points Gaussians with opacity >= --min-opacity whose footprint position falls in the
                   cell and whose height is within --height-tolerance of the floor
    coverage %     100 * covered cells / target cells;  uncovered area = uncovered cells * resolution^2

This measures floor-surface reconstruction only (walls and furniture are not scored), which is what navigation and
the viewer's floor need; the definition is fixed here, before any real data exists, so it cannot be tuned to a result.

    python benchmarks/b1_capture_path/reconstruction_coverage.py --floor-plan floor.json \
        --splat A/splat-clean.ply --splat B/splat-clean.ply --labels A B --out coverage.json

floor.json: {"polygons": [[[x, y], ...], ...], "floor_height": 0.0, "plane": "xz"}   ("plane" names the two floor axes
of the splat's frame; the remaining axis is height).
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np

AXES = {"x": 0, "y": 1, "z": 2}


def rasterise(polygons: list[list[list[float]]], resolution: float) -> tuple[np.ndarray, np.ndarray, float, float]:
    """Boolean mask of cells whose centre lies inside any polygon (even-odd rule), plus the grid origin."""
    pts = np.concatenate([np.asarray(p, dtype=float) for p in polygons])
    x0, y0 = pts.min(axis=0)
    x1, y1 = pts.max(axis=0)
    nx, ny = int(np.ceil((x1 - x0) / resolution)), int(np.ceil((y1 - y0) / resolution))
    cx = x0 + (np.arange(nx) + 0.5) * resolution
    cy = y0 + (np.arange(ny) + 0.5) * resolution
    gx, gy = np.meshgrid(cx, cy, indexing="ij")
    inside = np.zeros(gx.shape, dtype=bool)
    for poly in polygons:
        p = np.asarray(poly, dtype=float)
        hit = np.zeros(gx.shape, dtype=bool)
        for i in range(len(p)):
            (xa, ya), (xb, yb) = p[i], p[(i + 1) % len(p)]
            crosses = ((ya > gy) != (yb > gy)) & (gx < (xb - xa) * (gy - ya) / np.where(yb == ya, 1e-12, yb - ya) + xa)
            hit ^= crosses
        inside |= hit
    return inside, np.array([x0, y0]), nx, ny


def coverage(positions: np.ndarray, opacity: np.ndarray, plan: dict, *, resolution: float, min_points: int,
             min_opacity: float, height_tolerance: float) -> dict[str, float]:
    a, b = (AXES[c] for c in plan.get("plane", "xz"))
    h = 3 - a - b
    target, origin, nx, ny = rasterise(plan["polygons"], resolution)
    keep = (opacity >= min_opacity) & (np.abs(positions[:, h] - float(plan.get("floor_height", 0.0))) <= height_tolerance)
    fp = positions[keep][:, [a, b]]
    ix = np.floor((fp[:, 0] - origin[0]) / resolution).astype(int)
    iy = np.floor((fp[:, 1] - origin[1]) / resolution).astype(int)
    ok = (ix >= 0) & (ix < nx) & (iy >= 0) & (iy < ny)
    counts = np.zeros((nx, ny), dtype=np.int64)
    np.add.at(counts, (ix[ok], iy[ok]), 1)
    covered = target & (counts >= min_points)
    cell = resolution * resolution
    n_target = int(target.sum())
    return {"target_cells": n_target, "covered_cells": int(covered.sum()), "target_area_m2": n_target * cell,
            "coverage_pct": 100.0 * covered.sum() / n_target if n_target else 0.0,
            "uncovered_area_m2": (n_target - int(covered.sum())) * cell, "gaussians_on_floor": int(ok.sum())}


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--floor-plan", required=True)
    ap.add_argument("--splat", action="append", required=True)
    ap.add_argument("--labels", nargs="+")
    ap.add_argument("--resolution", type=float, default=0.25)
    ap.add_argument("--min-points", type=int, default=3)
    ap.add_argument("--min-opacity", type=float, default=0.1)
    ap.add_argument("--height-tolerance", type=float, default=0.10)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "services" / "reconstruction"))
    from chaya_worker.ply import read_ply  # the pipeline's own PLY reader

    plan = json.loads(Path(a.floor_plan).read_text(encoding="utf-8"))
    labels = a.labels or [Path(s).parent.name for s in a.splat]
    out = {"definition": {k: getattr(a, k) for k in ("resolution", "min_points", "min_opacity", "height_tolerance")}, "results": {}}
    for label, path in zip(labels, a.splat, strict=True):
        cloud = read_ply(Path(path))
        opacity = 1 / (1 + np.exp(-cloud.opacity_logit))
        out["results"][label] = coverage(cloud.positions, opacity, plan, resolution=a.resolution, min_points=a.min_points,
                                         min_opacity=a.min_opacity, height_tolerance=a.height_tolerance)
    Path(a.out).write_text(json.dumps(out, indent=2), encoding="utf-8")
    print(json.dumps(out, indent=2))


if __name__ == "__main__":
    main()
