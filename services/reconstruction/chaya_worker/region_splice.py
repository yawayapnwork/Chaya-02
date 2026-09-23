"""Replacing the changed region of a venue-wide GaussianCloud with a newly captured, aligned one -- the
splice step of an incremental re-scan (see docs/rescan.md). Real geometric operations only: a
point-in-polygon crop of the existing global cloud plus a rigid-transform application to the new region's
cloud, then concatenation. Never a full re-reconstruction, and the region's Gaussians are never merely
translated -- their own orientation quaternions are rotated by the alignment transform too, so splat
orientation stays correct in the merged frame. Pure numpy; no optional dependency needed.
"""

from __future__ import annotations

import numpy as np

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


def transform_gaussians(cloud: GaussianCloud, transform: np.ndarray) -> GaussianCloud:
    """Applies a rigid 4x4 transform to every Gaussian: rotates and translates its position AND rotates its
    own orientation quaternion by the transform's rotation component -- not just a position translation."""
    transform = np.asarray(transform, dtype=np.float64)
    if transform.shape != (4, 4):
        raise ValueError("transform must be 4x4")
    rotation = transform[:3, :3]
    translation = transform[:3, 3]
    positions = cloud.positions.astype(np.float64) @ rotation.T + translation
    rotation_quat_wxyz = _matrix_to_quaternion_wxyz(rotation)
    rotations = _quaternion_multiply(rotation_quat_wxyz, cloud.rotations_normalized().astype(np.float64))
    return GaussianCloud(positions.astype(np.float32), cloud.scales_log.copy(), rotations.astype(np.float32),
                         cloud.opacity_logit.copy(), cloud.colors_dc.copy())


def splice_region(global_cloud: GaussianCloud, aligned_region_cloud: GaussianCloud,
                  polygon_xy: np.ndarray) -> tuple[GaussianCloud, dict]:
    """Removes every Gaussian of `global_cloud` whose (x, y) falls inside `polygon_xy` and appends
    `aligned_region_cloud` (already transformed into the venue frame by `transform_gaussians`) in its
    place. The rest of the venue's geometry -- everything outside the polygon -- is untouched, byte for
    byte: this is a targeted replacement, not a re-reconstruction of the whole venue."""
    if len(aligned_region_cloud) == 0:
        raise ValueError("the aligned region cloud is empty; refusing to splice nothing into the venue")
    keep_mask = ~point_in_polygon(global_cloud.positions[:, :2].astype(np.float64), polygon_xy)
    kept = global_cloud.subset(keep_mask)
    merged = GaussianCloud(
        positions=np.concatenate([kept.positions, aligned_region_cloud.positions]),
        scales_log=np.concatenate([kept.scales_log, aligned_region_cloud.scales_log]),
        rotations_wxyz=np.concatenate([kept.rotations_wxyz, aligned_region_cloud.rotations_wxyz]),
        opacity_logit=np.concatenate([kept.opacity_logit, aligned_region_cloud.opacity_logit]),
        colors_dc=np.concatenate([kept.colors_dc, aligned_region_cloud.colors_dc]))
    report = {
        "removed_from_global": int((~keep_mask).sum()),
        "kept_from_global": int(keep_mask.sum()),
        "added_from_region": len(aligned_region_cloud),
        "total_after": len(merged),
    }
    return merged, report


def _matrix_to_quaternion_wxyz(m: np.ndarray) -> np.ndarray:
    """Rotation matrix -> quaternion (w, x, y, z), Shepperd's method (numerically stable across all
    rotation angles, unlike the naive single-branch formula)."""
    trace = np.trace(m)
    if trace > 0:
        s = 0.5 / np.sqrt(trace + 1.0)
        w = 0.25 / s
        x = (m[2, 1] - m[1, 2]) * s
        y = (m[0, 2] - m[2, 0]) * s
        z = (m[1, 0] - m[0, 1]) * s
    elif m[0, 0] > m[1, 1] and m[0, 0] > m[2, 2]:
        s = 2.0 * np.sqrt(1.0 + m[0, 0] - m[1, 1] - m[2, 2])
        w = (m[2, 1] - m[1, 2]) / s
        x = 0.25 * s
        y = (m[0, 1] + m[1, 0]) / s
        z = (m[0, 2] + m[2, 0]) / s
    elif m[1, 1] > m[2, 2]:
        s = 2.0 * np.sqrt(1.0 + m[1, 1] - m[0, 0] - m[2, 2])
        w = (m[0, 2] - m[2, 0]) / s
        x = (m[0, 1] + m[1, 0]) / s
        y = 0.25 * s
        z = (m[1, 2] + m[2, 1]) / s
    else:
        s = 2.0 * np.sqrt(1.0 + m[2, 2] - m[0, 0] - m[1, 1])
        w = (m[1, 0] - m[0, 1]) / s
        x = (m[0, 2] + m[2, 0]) / s
        y = (m[1, 2] + m[2, 1]) / s
        z = 0.25 * s
    return np.array([w, x, y, z])


def _quaternion_multiply(q_wxyz: np.ndarray, others_wxyz: np.ndarray) -> np.ndarray:
    """q_wxyz (4,) applied to each row of others_wxyz (N, 4): result[i] = q * others[i]."""
    w1, x1, y1, z1 = q_wxyz
    w2, x2, y2, z2 = others_wxyz[:, 0], others_wxyz[:, 1], others_wxyz[:, 2], others_wxyz[:, 3]
    w = w1 * w2 - x1 * x2 - y1 * y2 - z1 * z2
    x = w1 * x2 + x1 * w2 + y1 * z2 - z1 * y2
    y = w1 * y2 - x1 * z2 + y1 * w2 + z1 * x2
    z = w1 * z2 + x1 * y2 - y1 * x2 + z1 * w2
    return np.stack([w, x, y, z], axis=1)
