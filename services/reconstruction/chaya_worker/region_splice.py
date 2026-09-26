"""Replacing the changed region of a venue-wide GaussianCloud with a newly captured, aligned one: the splice step of an
incremental re-scan (docs/rescan.md, "SPLICE"). Pure numpy + scipy.

Everything is decided in canonical metres (chaya_worker.frames), through the parent reconstruction's calibrated frame.
The clouds themselves stay in the parent reconstruction's frame. What is replaced is a **volume**: the region polygon
(canonical x, y) times the height range of the newly captured region's own geometry, grown by `z_margin_m`.

  * Venue Gaussians inside that volume are removed. Venue Gaussians inside the polygon but above or below it (a ceiling
    the re-capture never saw, a mezzanine) are unrelated geometry and are kept. So is everything outside the polygon,
    unchanged and in order.
  * Region Gaussians inside that volume are added. Region Gaussians captured outside it are discarded, so the venue
    never gains a second, overlapping copy of geometry it already had.
  * The seam is measured, not assumed. For region Gaussians within `seam_band_m` inside the edge, the step to the kept
    venue surface just across the edge is the median point-to-plane distance to their nearest kept Gaussians. A step
    above `max_seam_step_m` is a visible seam, and the splice is refused (SpliceRejected).

The result records exactly what was replaced: the index sets (removed venue Gaussians, added region Gaussians, both as
indices into their input clouds) and the volume.
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np
from scipy.spatial import cKDTree

from .frames import Similarity
from .ply import GaussianCloud
from .similarity_registration import surface_normals


class SpliceRejected(ValueError):
    """The splice would damage the venue: nothing to add, or a visible seam."""


def point_in_polygon(points_xy: np.ndarray, polygon_xy: np.ndarray) -> np.ndarray:
    """Ray-casting point-in-polygon test (the standard, dependency-free algorithm), vectorised over N
    points against one simple polygon given as (x, y) vertices in order (not required to be convex)."""
    polygon_xy = np.asarray(polygon_xy, dtype=np.float64)
    if polygon_xy.shape[0] < 3:
        raise ValueError("a polygon needs at least 3 vertices")
    x, y = points_xy[:, 0], points_xy[:, 1]
    n = len(polygon_xy)
    inside = np.zeros(len(points_xy), dtype=bool)
    j = n - 1
    for i in range(n):
        xi, yi = polygon_xy[i]
        xj, yj = polygon_xy[j]
        # Guard against a horizontal edge (yj == yi) dividing by zero; such an edge can never itself
        # register a crossing for a ray cast along +x, so 1e-30 keeps the comparison false without a branch.
        intersects = ((yi > y) != (yj > y)) & (x < (xj - xi) * (y - yi) / (yj - yi + 1e-30) + xi)
        inside ^= intersects
        j = i
    return inside


def distance_to_polygon_edge(points_xy: np.ndarray, polygon_xy: np.ndarray) -> np.ndarray:
    """Horizontal distance from each point to the nearest polygon edge (inside or outside)."""
    p = np.asarray(points_xy, dtype=np.float64)[:, None, :]
    a = np.asarray(polygon_xy, dtype=np.float64)
    b = np.roll(a, -1, axis=0)
    ab = (b - a)[None]
    t = np.clip(((p - a[None]) * ab).sum(-1) / np.maximum((ab**2).sum(-1), 1e-30), 0.0, 1.0)
    closest = a[None] + t[..., None] * ab
    return np.linalg.norm(p - closest, axis=-1).min(axis=1)


def polygon_area(polygon_xy: np.ndarray) -> float:
    """Shoelace formula. Used to reject a degenerate (near-zero-area or self-crossing-to-a-line) region
    before it is ever sent to a worker -- see dev.chaya.api.rescan.RescanService."""
    polygon_xy = np.asarray(polygon_xy, dtype=np.float64)
    x, y = polygon_xy[:, 0], polygon_xy[:, 1]
    return float(0.5 * abs(np.dot(x, np.roll(y, -1)) - np.dot(y, np.roll(x, -1))))


def transform_gaussians(cloud: GaussianCloud, transform: Similarity | np.ndarray) -> GaussianCloud:
    """Applies a similarity transform (a :class:`Similarity`, or a 4x4 [[s*R, t], [0, 1]] matrix) to every
    Gaussian: position -> s*R*p + t, orientation -> R*q, and every axis scale multiplied by s (log-scale + ln s)."""
    if not isinstance(transform, Similarity):
        transform = np.asarray(transform, dtype=np.float64)
        if transform.shape != (4, 4):
            raise ValueError("transform must be 4x4")
        transform = Similarity.from_matrix4(transform)
    positions = transform.apply(cloud.positions)
    rotations = transform.apply_orientation(cloud.rotations_normalized())
    scales_log = cloud.scales_log.astype(np.float64) + np.log(transform.scale)
    return GaussianCloud(positions.astype(np.float32), scales_log.astype(np.float32), rotations.astype(np.float32),
                         cloud.opacity_logit.copy(), cloud.colors_dc.copy())


def _concat(a: GaussianCloud, b: GaussianCloud) -> GaussianCloud:
    return GaussianCloud(np.concatenate([a.positions, b.positions]), np.concatenate([a.scales_log, b.scales_log]),
                         np.concatenate([a.rotations_wxyz, b.rotations_wxyz]), np.concatenate([a.opacity_logit, b.opacity_logit]),
                         np.concatenate([a.colors_dc, b.colors_dc]))


@dataclass
class SpliceResult:
    merged: GaussianCloud
    removed_global_indices: np.ndarray  # into the venue cloud
    added_region_indices: np.ndarray  # into the aligned region cloud
    report: dict


def seam_step(added_canonical: np.ndarray, kept_canonical: np.ndarray, polygon_xy: np.ndarray, band_m: float,
              k: int = 8) -> dict:
    """The step across the seam: for added Gaussians within `band_m` inside the edge, the median distance to the plane of
    the kept venue surface just across it (the normal from the kept Gaussians' k-neighbourhood). About the sampling noise
    when the new geometry continues the old surface. The size of the offset when it does not."""
    in_band = distance_to_polygon_edge(added_canonical[:, :2], polygon_xy) < band_m
    kept_band = distance_to_polygon_edge(kept_canonical[:, :2], polygon_xy) < 2 * band_m
    if in_band.sum() < 3 or kept_band.sum() < k:
        return {"band_points": int(in_band.sum()), "kept_points_across": int(kept_band.sum()), "step_m": None}
    kept = kept_canonical[kept_band]
    tree = cKDTree(kept)
    normals = surface_normals(kept, k, tree)
    d, j = tree.query(added_canonical[in_band], distance_upper_bound=2 * band_m)
    ok = np.isfinite(d)
    if ok.sum() < 3:
        return {"band_points": int(in_band.sum()), "kept_points_across": int(kept_band.sum()), "step_m": None}
    diff = added_canonical[in_band][ok] - kept[j[ok]]
    step = np.abs(np.einsum("ij,ij->i", diff, normals[j[ok]]))
    return {"band_points": int(in_band.sum()), "kept_points_across": int(kept_band.sum()), "matched": int(ok.sum()),
            "step_m": float(np.median(step))}


def splice_region(global_cloud: GaussianCloud, aligned_region_cloud: GaussianCloud, polygon_xy: np.ndarray,
                  to_canonical: Similarity, *, z_margin_m: float, seam_band_m: float, max_seam_step_m: float) -> SpliceResult:
    """Replaces the venue's geometry in the region volume with the aligned region's (see the module docstring).
    `to_canonical` is the parent reconstruction's calibrated frame. Raises SpliceRejected when there is nothing to add or
    the seam is visible."""
    if len(aligned_region_cloud) == 0:
        raise SpliceRejected("the aligned region cloud is empty; refusing to splice nothing into the venue")
    polygon_xy = np.asarray(polygon_xy, dtype=np.float64)
    g = to_canonical.apply(global_cloud.positions)
    r = to_canonical.apply(aligned_region_cloud.positions)
    r_in_polygon = point_in_polygon(r[:, :2], polygon_xy)
    if not r_in_polygon.any():
        raise SpliceRejected("no Gaussian of the aligned region falls inside the selected polygon; refusing to splice")
    z_lo, z_hi = np.percentile(r[r_in_polygon, 2], [0.5, 99.5])
    z_lo, z_hi = float(z_lo - z_margin_m), float(z_hi + z_margin_m)

    g_in_polygon = point_in_polygon(g[:, :2], polygon_xy)
    replaced = g_in_polygon & (g[:, 2] >= z_lo) & (g[:, 2] <= z_hi)
    added = r_in_polygon & (r[:, 2] >= z_lo) & (r[:, 2] <= z_hi)

    seam = seam_step(r[added], g[~replaced], polygon_xy, seam_band_m)
    seam["max_step_m"] = max_seam_step_m
    if seam["step_m"] is not None and seam["step_m"] > max_seam_step_m:
        raise SpliceRejected(f"the new geometry meets the kept geometry with a median step of {seam['step_m']:.3f} m at the "
                             f"region edge (limit {max_seam_step_m} m); refusing to splice a visible seam")

    removed_idx = np.flatnonzero(replaced)
    added_idx = np.flatnonzero(added)
    merged = _concat(global_cloud.subset(~replaced), aligned_region_cloud.subset(added))
    report = {
        "volume": {"polygon_xy": polygon_xy.tolist(), "z_min_m": z_lo, "z_max_m": z_hi, "z_margin_m": z_margin_m},
        "removed_from_global": int(replaced.sum()),
        "kept_from_global": int((~replaced).sum()),
        "kept_inside_polygon_outside_volume": int((g_in_polygon & ~replaced).sum()),
        "added_from_region": int(added.sum()),
        "discarded_from_region_outside_polygon": int((~r_in_polygon).sum()),
        "discarded_from_region_outside_volume": int((r_in_polygon & ~added).sum()),
        "total_after": len(merged),
        "seam": seam,
    }
    return SpliceResult(merged, removed_idx, added_idx, report)
