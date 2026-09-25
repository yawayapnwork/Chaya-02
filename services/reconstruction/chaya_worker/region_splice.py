"""Replacing the changed region of a venue-wide GaussianCloud with a newly captured, aligned one -- the
splice step of an incremental re-scan (see docs/rescan.md). Real geometric operations only: a
similarity-transform application to the new region's cloud, a point-in-polygon crop of both clouds, then
concatenation. Never a full re-reconstruction, and the region's Gaussians are never merely translated --
their own orientation quaternions are rotated, and their scales multiplied, by the alignment transform, so
splat shape stays correct in the merged frame. Pure numpy; no optional dependency needed.

The region polygon is in the canonical venue frame (metres, +Z up; chaya_worker.frames): its (x, y) are
horizontal coordinates only there. The clouds themselves stay in the parent reconstruction's frame, so the
polygon test is done on their canonical positions, through the parent's calibrated frame.
"""

from __future__ import annotations

import numpy as np

from .frames import Similarity
from .ply import GaussianCloud


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


def splice_region(global_cloud: GaussianCloud, aligned_region_cloud: GaussianCloud, polygon_xy: np.ndarray,
                  to_canonical: Similarity) -> tuple[GaussianCloud, dict]:
    """Removes every Gaussian of `global_cloud` whose canonical (x, y) falls inside `polygon_xy` and replaces
    them with the Gaussians of `aligned_region_cloud` (already in the global cloud's frame, see
    chaya_worker.stages.region_alignment) whose canonical (x, y) also fall inside it. Region Gaussians the
    operator captured outside the polygon are dropped, not appended: outside the polygon the venue keeps its
    existing geometry, byte for byte, instead of gaining a second, overlapping copy.

    `to_canonical` is the parent reconstruction's calibrated frame (reconstruction -> canonical metres)."""
    if len(aligned_region_cloud) == 0:
        raise ValueError("the aligned region cloud is empty; refusing to splice nothing into the venue")
    polygon_xy = np.asarray(polygon_xy, dtype=np.float64)
    keep_mask = ~point_in_polygon(to_canonical.apply(global_cloud.positions)[:, :2], polygon_xy)
    region_inside = point_in_polygon(to_canonical.apply(aligned_region_cloud.positions)[:, :2], polygon_xy)
    if not region_inside.any():
        raise ValueError("no Gaussian of the aligned region falls inside the selected polygon; refusing to splice")
    kept = global_cloud.subset(keep_mask)
    added = aligned_region_cloud.subset(region_inside)
    merged = GaussianCloud(
        positions=np.concatenate([kept.positions, added.positions]),
        scales_log=np.concatenate([kept.scales_log, added.scales_log]),
        rotations_wxyz=np.concatenate([kept.rotations_wxyz, added.rotations_wxyz]),
        opacity_logit=np.concatenate([kept.opacity_logit, added.opacity_logit]),
        colors_dc=np.concatenate([kept.colors_dc, added.colors_dc]))
    report = {
        "removed_from_global": int((~keep_mask).sum()),
        "kept_from_global": int(keep_mask.sum()),
        "added_from_region": int(region_inside.sum()),
        "discarded_from_region_outside_polygon": int((~region_inside).sum()),
        "total_after": len(merged),
    }
    return merged, report
