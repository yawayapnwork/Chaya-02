"""Real geometric cleanup operations on a GaussianCloud, shared by the GEOMETRIC_CLEANUP and PLANE_FITTING
stages and by the cleanup benchmark harness (chaya_worker.benchmarks.cleanup_benchmark).

Every function that needs Open3D imports it lazily and only when called; callers gate on
`ctx.toolchain.require(["py:open3d"], stage=...)` first, exactly like POSE_ESTIMATION gates on COLMAP. None
of these functions ever drop geometry silently -- each returns a boolean keep-mask plus a reason count, so
the caller can report exactly what was removed and why.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

import numpy as np

from .ply import GaussianCloud

FLOOR, WALL, FURNITURE, CLUTTER, UNKNOWN = "floor", "wall", "furniture", "clutter", "unknown"
CEILING = "ceiling"  # a plane classification only (never a semantic segmentation class)
SEMANTIC_CLASSES = (FLOOR, WALL, FURNITURE, CLUTTER)


def characteristic_spacing(positions: np.ndarray, *, sample: int = 20000, seed: int = 0) -> float:
    """Median distance from a point to its nearest neighbour, in the cloud's own units.

    GEOMETRIC_CLEANUP and PLANE_FITTING run before any metric calibration exists, in reconstruction units whose
    scale is arbitrary (chaya_worker.frames). A distance threshold written in metres would mean something
    different in every reconstruction. Expressing those thresholds as multiples of this spacing makes them
    scale-invariant instead: the same fraction of structure is affected whatever the reconstruction's scale.
    Deterministic for a given cloud (fixed-seed subsample)."""
    from scipy.spatial import cKDTree  # noqa: PLC0415

    p = np.asarray(positions, dtype=np.float64)
    if len(p) < 2:
        raise ValueError("need at least 2 points to measure spacing")
    tree = cKDTree(p)
    query = p if len(p) <= sample else p[np.random.default_rng(seed).choice(len(p), sample, replace=False)]
    dist, _ = tree.query(query, k=2)
    spacing = float(np.median(dist[:, 1]))
    if not spacing > 0:
        raise ValueError("the cloud has no measurable point spacing (duplicate points)")
    return spacing


def _o3d_pointcloud(positions: np.ndarray, colors01: np.ndarray | None = None):
    import open3d as o3d  # noqa: PLC0415 - intentionally lazy; caller already checked availability

    pcd = o3d.geometry.PointCloud()
    pcd.points = o3d.utility.Vector3dVector(positions.astype(np.float64))
    if colors01 is not None:
        pcd.colors = o3d.utility.Vector3dVector(np.clip(colors01, 0.0, 1.0).astype(np.float64))
    return pcd


def opacity_threshold_mask(cloud: GaussianCloud, threshold: float) -> np.ndarray:
    """Baseline #2: keep only Gaussians whose opacity exceeds `threshold`. Deliberately naive -- this is the
    method the task asks us NOT to use alone, kept here only as the benchmark's comparison baseline."""
    return cloud.opacities() >= threshold


def statistical_outlier_mask(cloud: GaussianCloud, *, nb_neighbors: int, std_ratio: float) -> tuple[np.ndarray, dict[str, Any]]:
    """Open3D's statistical outlier removal: a point is kept if its mean distance to its `nb_neighbors`
    nearest neighbours is within `std_ratio` standard deviations of the dataset's mean neighbour distance."""
    pcd = _o3d_pointcloud(cloud.positions)
    _, kept_idx = pcd.remove_statistical_outlier(nb_neighbors=nb_neighbors, std_ratio=std_ratio)
    mask = np.zeros(len(cloud), dtype=bool)
    mask[np.asarray(kept_idx, dtype=int)] = True
    return mask, {"nb_neighbors": nb_neighbors, "std_ratio": std_ratio}


def radius_outlier_mask(cloud: GaussianCloud, *, nb_points: int, radius: float) -> tuple[np.ndarray, dict[str, Any]]:
    """Open3D's radius outlier removal: a point is kept only if at least `nb_points` other points lie
    within `radius` of it -- catches sparse floating debris that statistical removal can miss."""
    pcd = _o3d_pointcloud(cloud.positions)
    _, kept_idx = pcd.remove_radius_outlier(nb_points=nb_points, radius=radius)
    mask = np.zeros(len(cloud), dtype=bool)
    mask[np.asarray(kept_idx, dtype=int)] = True
    return mask, {"nb_points": nb_points, "radius": radius}


def semantic_aware_mask(cloud: GaussianCloud, labels: np.ndarray, *, radius: float,
                        min_same_class_neighbors: int) -> tuple[np.ndarray, dict[str, Any]]:
    """Class-conditioned filtering: floor/wall/furniture points are always kept (structural classes are
    trusted even if sparse); a 'clutter' point is kept only if it has at least `min_same_class_neighbors`
    other clutter points within `radius` of it (isolated clutter is almost always reconstruction noise,
    a real surface has many correlated splats). 'unknown' (no segmentation reached it) is kept as-is.
    """
    if len(labels) != len(cloud):
        raise ValueError(f"labels length {len(labels)} does not match cloud length {len(cloud)}")
    import open3d as o3d  # noqa: PLC0415

    mask = np.ones(len(cloud), dtype=bool)
    clutter_idx = np.where(labels == CLUTTER)[0]
    removed = 0
    if len(clutter_idx) > 0:
        pcd = _o3d_pointcloud(cloud.positions[clutter_idx])
        tree = o3d.geometry.KDTreeFlann(pcd)
        for local_i, point in enumerate(np.asarray(pcd.points)):
            count, _, _ = tree.search_radius_vector_3d(point, radius)
            if count - 1 < min_same_class_neighbors:  # -1: a point is its own neighbour in the search
                mask[clutter_idx[local_i]] = False
                removed += 1
    return mask, {"radius": radius, "min_same_class_neighbors": min_same_class_neighbors, "clutter_points": len(clutter_idx),
                 "clutter_removed": removed}


@dataclass
class FittedPlane:
    equation: tuple[float, float, float, float]  # ax + by + cz + d = 0, normalised (a,b,c) unit normal
    inlier_indices: np.ndarray  # indices into the point cloud this plane was fit on
    classification: str  # "floor" | "ceiling" | "wall" | "other" ("ceiling" only when gravity is known)
    confidence: float  # fraction of this plane's inliers whose semantic label agrees with `classification`


def fit_planes(positions: np.ndarray, *, distance_threshold: float, ransac_n: int, num_iterations: int,
               max_planes: int, min_inliers: int, labels: np.ndarray | None = None) -> list[FittedPlane]:
    """Iterative RANSAC plane extraction (real Open3D `segment_plane`, run repeatedly on the remaining
    points, the standard technique for extracting multiple planes from one cloud). Stops when the next
    plane would have fewer than `min_inliers` inliers or `max_planes` has been reached.

    Classification is geometric, not guessed: the cloud's dominant "up" axis is estimated as the principal
    axis with least variance among all points classified as near-planar during extraction; a plane is
    "floor" if its normal is within 25 degrees of that axis, "wall" if within 25 degrees of perpendicular
    to it, else "other". When `labels` are supplied, `confidence` reports how well that geometric call
    agrees with the semantic segmentation of the plane's own inlier points (also honest, not fabricated).
    """

    remaining_positions = positions.copy()
    remaining_indices = np.arange(len(positions))
    planes: list[FittedPlane] = []
    raw: list[tuple[np.ndarray, np.ndarray]] = []  # (normal, inlier_indices_in_original_cloud) before classification

    for _ in range(max_planes):
        if len(remaining_positions) < max(ransac_n, min_inliers):
            break
        pcd = _o3d_pointcloud(remaining_positions)
        model, inliers = pcd.segment_plane(distance_threshold=distance_threshold, ransac_n=ransac_n, num_iterations=num_iterations)
        if len(inliers) < min_inliers:
            break
        a, b, c, d = model
        normal = np.array([a, b, c])
        normal = normal / max(np.linalg.norm(normal), 1e-9)
        global_idx = remaining_indices[np.asarray(inliers, dtype=int)]
        raw.append((normal, global_idx))
        keep = np.ones(len(remaining_positions), dtype=bool)
        keep[inliers] = False
        remaining_positions = remaining_positions[keep]
        remaining_indices = remaining_indices[keep]

    if not raw:
        return []

    up = _estimate_up_axis(positions, raw)
    for normal, global_idx in raw:
        classification = _classify(normal, up)
        planes.append(FittedPlane(tuple(float(v) for v in (*normal, -float(np.dot(normal, positions[global_idx].mean(axis=0))))),
                                  global_idx, classification, _agreement(classification, global_idx, labels)))
    return planes


def _classify(normal: np.ndarray, up: np.ndarray) -> str:
    angle_to_up = np.degrees(np.arccos(np.clip(abs(float(np.dot(normal, up))), 0.0, 1.0)))
    if angle_to_up <= 25.0:
        return FLOOR
    if angle_to_up >= 65.0:
        return WALL
    return "other"


def _agreement(classification: str, inlier_idx: np.ndarray, labels: np.ndarray | None) -> float:
    if labels is None:
        return 1.0
    plane_labels = labels[inlier_idx]
    agree_with = {FLOOR: FLOOR, WALL: WALL}.get(classification)
    return float((plane_labels == agree_with).mean()) if agree_with else float((plane_labels == UNKNOWN).mean())


def reclassify_planes(planes: list[FittedPlane], positions: np.ndarray, up: np.ndarray, reference_point: np.ndarray,
                      labels: np.ndarray | None = None) -> list[FittedPlane]:
    """Re-classifies already-fitted planes against a known up direction (chaya_worker.gravity, or a calibrated
    frame's), in the same frame as `positions`. A horizontal plane below `reference_point` (the camera centroid)
    is the floor, one above it a ceiling -- a distinction the up-axis-free classification in fit_planes cannot make."""
    up = np.asarray(up, dtype=np.float64) / np.linalg.norm(up)
    out = []
    for p in planes:
        normal = np.asarray(p.equation[:3], dtype=np.float64)
        classification = _classify(normal, up)
        if classification == FLOOR:
            centre = positions[p.inlier_indices].mean(axis=0)
            classification = FLOOR if float((np.asarray(reference_point) - centre) @ up) > 0 else CEILING
        out.append(FittedPlane(p.equation, p.inlier_indices, classification, _agreement(classification, p.inlier_indices, labels)))
    return out


def _estimate_up_axis(positions: np.ndarray, raw_planes: list[tuple[np.ndarray, np.ndarray]]) -> np.ndarray:
    """The axis most of the extracted plane normals cluster around or against. A weak, sign-free guess used only
    for the provisional classification inside fit_planes; it can pick a dominant wall normal. PLANE_FITTING
    replaces it with chaya_worker.gravity's estimate (or the calibrated frame's up) via reclassify_planes, and
    nothing that needs the true up direction ever reads this one."""
    normals = np.array([n for n, _ in raw_planes])
    normals = np.where(normals[:, [0]] < 0, -normals, normals) if len(normals) else normals  # sign is arbitrary; pick a canonical half
    cov = normals.T @ normals
    eigvals, eigvecs = np.linalg.eigh(cov)
    return eigvecs[:, np.argmax(eigvals)]
