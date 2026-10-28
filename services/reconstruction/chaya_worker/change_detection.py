"""Changed-region detection and local alignment for incremental updates (docs/rescan.md).

Pure numpy + scipy: compares existing parent venue geometry with newly captured/reconstructed geometry
to detect and isolate modified volumes, verify unchanged regions, and anchor local registration
on static surfaces rather than moving objects.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

import numpy as np
from scipy.spatial import cKDTree

from .frames import Similarity


@dataclass(frozen=True)
class ChangeDetectionResult:
    """The result of comparing new geometry against parent geometry in canonical metres."""

    changed_points_count: int
    unchanged_points_count: int
    added_points_count: int
    removed_points_count: int
    change_ratio: float  # changed_points / max(1, total_evaluated)
    changed_area_m2: float
    changed_polygon: list[list[float]]  # 2D polygon vertices [[x, y], ...] bounding the changed region
    max_deviation_m: float
    mean_unchanged_residual_m: float
    unchanged_mask: np.ndarray  # boolean mask into the evaluated rescan points
    added_mask: np.ndarray  # boolean mask into the evaluated rescan points
    details: dict[str, Any] = field(default_factory=dict)

    def as_dict(self) -> dict[str, Any]:
        return {
            "changed_points_count": self.changed_points_count,
            "unchanged_points_count": self.unchanged_points_count,
            "added_points_count": self.added_points_count,
            "removed_points_count": self.removed_points_count,
            "change_ratio": round(float(self.change_ratio), 4),
            "changed_area_m2": round(float(self.changed_area_m2), 4),
            "changed_polygon": [[round(float(c), 3) for c in pt] for pt in self.changed_polygon],
            "max_deviation_m": round(float(self.max_deviation_m), 4),
            "mean_unchanged_residual_m": round(float(self.mean_unchanged_residual_m), 4),
            "details": self.details,
        }


def _convex_hull_2d(points_xy: np.ndarray) -> np.ndarray:
    """Computes the 2D convex hull of points_xy in counter-clockwise order using monotone chain."""
    p = np.unique(np.round(points_xy, decimals=4), axis=0)
    if len(p) <= 2:
        return p
    # Sort lexicographically by x, then y
    p = p[np.lexsort((p[:, 1], p[:, 0]))]

    def cross(o, a, b):
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])

    lower = []
    for pt in p:
        while len(lower) >= 2 and cross(lower[-2], lower[-1], pt) <= 0:
            lower.pop()
        lower.append(pt)

    upper = []
    for pt in reversed(p):
        while len(upper) >= 2 and cross(upper[-2], upper[-1], pt) <= 0:
            upper.pop()
        upper.append(pt)

    return np.array(lower[:-1] + upper[:-1])


def detect_changed_regions(
    parent_points_m: np.ndarray,
    rescan_points_m: np.ndarray,
    *,
    tolerance_m: float = 0.05,
    cell_size_m: float = 0.25,
    min_cluster_points: int = 5,
) -> ChangeDetectionResult:
    """Detects differences between parent and rescan geometry in canonical metres.

    Identifies:
      - unchanged surfaces (within tolerance_m of parent surfaces)
      - added surfaces (in rescan, not present in parent within tolerance_m)
      - removed surfaces (in parent within the rescan footprint, not present in rescan)
      - bounding 2D polygon enclosing the detected changes
    """
    parent = np.asarray(parent_points_m, dtype=np.float64)
    rescan = np.asarray(rescan_points_m, dtype=np.float64)

    if len(parent) == 0 or len(rescan) == 0:
        empty_poly = [[0.0, 0.0], [1.0, 0.0], [1.0, 1.0], [0.0, 1.0]]
        return ChangeDetectionResult(
            changed_points_count=len(rescan),
            unchanged_points_count=0,
            added_points_count=len(rescan),
            removed_points_count=0,
            change_ratio=1.0,
            changed_area_m2=0.0,
            changed_polygon=empty_poly,
            max_deviation_m=0.0,
            mean_unchanged_residual_m=0.0,
            unchanged_mask=np.zeros(len(rescan), dtype=bool),
            added_mask=np.ones(len(rescan), dtype=bool),
            details={"empty_input": True},
        )

    parent_tree = cKDTree(parent)
    rescan_distances, _ = parent_tree.query(rescan)

    unchanged_mask = rescan_distances <= tolerance_m
    added_mask = ~unchanged_mask

    unchanged_count = int(unchanged_mask.sum())
    added_count = int(added_mask.sum())
    mean_unchanged_res = float(np.mean(rescan_distances[unchanged_mask])) if unchanged_count > 0 else 0.0

    # Determine rescan horizontal bounding footprint
    rescan_min_xy = np.min(rescan[:, :2], axis=0) - tolerance_m
    rescan_max_xy = np.max(rescan[:, :2], axis=0) + tolerance_m
    in_footprint = np.all((parent[:, :2] >= rescan_min_xy) & (parent[:, :2] <= rescan_max_xy), axis=1)

    parent_in_footprint = parent[in_footprint]
    if len(parent_in_footprint) > 0:
        rescan_tree = cKDTree(rescan)
        parent_distances, _ = rescan_tree.query(parent_in_footprint)
        removed_mask = parent_distances > tolerance_m
        removed_points = parent_in_footprint[removed_mask]
        removed_count = int(removed_mask.sum())
    else:
        removed_count = 0
        removed_points = np.zeros((0, 3), dtype=np.float64)

    total_changed = added_count + removed_count
    total_evaluated = max(1, len(rescan) + removed_count)
    change_ratio = float(total_changed / total_evaluated)

    # Maximum deviation observed
    max_dev = float(np.max(rescan_distances)) if len(rescan_distances) > 0 else 0.0

    # Compute changed grid cells
    changed_xy_parts = []
    if added_count > 0:
        changed_xy_parts.append(rescan[added_mask, :2])
    if removed_count > 0:
        changed_xy_parts.append(removed_points[:, :2])

    if changed_xy_parts:
        changed_xy = np.vstack(changed_xy_parts)
        cells = np.floor(changed_xy / cell_size_m).astype(np.int64)
        unique_cells = np.unique(cells, axis=0)
        changed_area_m2 = float(len(unique_cells) * (cell_size_m**2))

        # Build bounding polygon around changed cells
        # Create corner points for all unique changed cells with a safety margin
        margin = 0.15
        corners = []
        for cx, cy in unique_cells:
            bx, by = cx * cell_size_m, cy * cell_size_m
            corners.extend([
                [bx - margin, by - margin],
                [bx + cell_size_m + margin, by - margin],
                [bx + cell_size_m + margin, by + cell_size_m + margin],
                [bx - margin, by + cell_size_m + margin],
            ])
        hull = _convex_hull_2d(np.array(corners))
        if len(hull) >= 3:
            polygon = hull.tolist()
        else:
            min_c = np.min(changed_xy, axis=0) - margin
            max_c = np.max(changed_xy, axis=0) + margin
            polygon = [
                [float(min_c[0]), float(min_c[1])],
                [float(max_c[0]), float(min_c[1])],
                [float(max_c[0]), float(max_c[1])],
                [float(min_c[0]), float(max_c[1])],
            ]
    else:
        changed_area_m2 = 0.0
        # If no change detected, polygon is empty / minimal bounding box around rescan center
        cx, cy = float(np.mean(rescan[:, 0])), float(np.mean(rescan[:, 1]))
        polygon = [[cx - 0.5, cy - 0.5], [cx + 0.5, cy - 0.5], [cx + 0.5, cy + 0.5], [cx - 0.5, cy + 0.5]]

    return ChangeDetectionResult(
        changed_points_count=total_changed,
        unchanged_points_count=unchanged_count,
        added_points_count=added_count,
        removed_points_count=removed_count,
        change_ratio=change_ratio,
        changed_area_m2=changed_area_m2,
        changed_polygon=polygon,
        max_deviation_m=max_dev,
        mean_unchanged_residual_m=mean_unchanged_res,
        unchanged_mask=unchanged_mask,
        added_mask=added_mask,
        details={
            "tolerance_m": tolerance_m,
            "cell_size_m": cell_size_m,
            "parent_points_evaluated": len(parent),
            "rescan_points_evaluated": len(rescan),
        },
    )


def align_on_unchanged_surfaces(
    source_points: np.ndarray,
    target_points: np.ndarray,
    initial_transform: Similarity | None = None,
    *,
    tolerance_m: float = 0.05,
    icp_iterations: int = 50,
) -> tuple[Similarity, ChangeDetectionResult]:
    """Performs local alignment anchored specifically on unchanged static surfaces.

    Identifies unchanged static regions between source and target, then refines
    the alignment using point-to-plane ICP restricted to those static surfaces.
    This guarantees that moving furniture or changed objects do not displace
    or pull the alignment away from true walls and floors.
    """
    from .similarity_registration import icp_point_to_plane

    t = initial_transform or Similarity.identity()
    current_source = t.apply(source_points)

    detection = detect_changed_regions(target_points, current_source, tolerance_m=tolerance_m)

    if detection.unchanged_points_count < 20:
        # Insufficient unchanged surface to anchor on; return initial transform
        return t, detection

    # Refine alignment using only unchanged points
    static_source = current_source[detection.unchanged_mask]
    schedule_m = [tolerance_m * 2.0, tolerance_m * 1.5, tolerance_m]
    icp_res = icp_point_to_plane(
        static_source, target_points, Similarity.identity(), schedule_m=schedule_m, max_iterations_per_stage=icp_iterations
    )
    refined_t = icp_res.transform
    final_transform = refined_t.compose(t)

    # Re-evaluate change detection with refined transform
    final_source = final_transform.apply(source_points)
    final_detection = detect_changed_regions(target_points, final_source, tolerance_m=tolerance_m)

    return final_transform, final_detection
