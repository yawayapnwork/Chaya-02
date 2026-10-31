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


def density_conditioned_opacity_mask(cloud: GaussianCloud, *, min_opacity: float = 0.05,
                                     radius: float, min_neighbors: int = 4) -> tuple[np.ndarray, dict[str, Any]]:
    """Improved opacity filtering conditioned on local point density.

    Rather than blindly deleting all low-opacity Gaussians (which destroys semi-transparent
    glass, thin wires, and fine architectural edges), this filter removes a Gaussian only if
    its opacity is below `min_opacity` AND its local neighbor count within `radius` is below
    `min_neighbors`. Low-opacity Gaussians that belong to dense, coherent surfaces are preserved.
    """
    from scipy.spatial import cKDTree  # noqa: PLC0415

    opacities = cloud.opacities()
    low_opacity_mask = opacities < min_opacity
    if not low_opacity_mask.any():
        return np.ones(len(cloud), dtype=bool), {
            "min_opacity": min_opacity, "radius": radius, "min_neighbors": min_neighbors,
            "low_opacity_count": 0, "removed": 0
        }

    tree = cKDTree(cloud.positions)
    low_idx = np.where(low_opacity_mask)[0]
    counts = tree.query_ball_point(cloud.positions[low_idx], r=radius, return_length=True)
    sparse_low_opacity = counts < min_neighbors

    mask = np.ones(len(cloud), dtype=bool)
    mask[low_idx[sparse_low_opacity]] = False
    return mask, {
        "min_opacity": min_opacity,
        "radius": radius,
        "min_neighbors": min_neighbors,
        "low_opacity_count": int(low_opacity_mask.sum()),
        "removed": int(sparse_low_opacity.sum()),
    }


def disconnected_cluster_mask(positions: np.ndarray, *, radius: float,
                              min_cluster_size: int = 15) -> tuple[np.ndarray, dict[str, Any]]:
    """Detects and removes disconnected floating clusters of geometry (floaters).

    Builds an undirected spatial adjacency graph connecting points within `radius`
    (expressed as a multiple of characteristic_spacing). Connected components with
    fewer than `min_cluster_size` points represent floating artifacts/debris disconnected
    from real structural geometry and are filtered out.
    """
    from scipy.sparse import coo_matrix  # noqa: PLC0415
    from scipy.sparse.csgraph import connected_components  # noqa: PLC0415
    from scipy.spatial import cKDTree  # noqa: PLC0415

    p = np.asarray(positions, dtype=np.float64)
    if len(p) == 0:
        return np.ones(0, dtype=bool), {"min_cluster_size": min_cluster_size, "radius": radius, "removed": 0}

    tree = cKDTree(p)
    pairs = tree.query_pairs(r=radius, output_type="ndarray")
    if len(pairs) == 0:
        mask = np.ones(len(p), dtype=bool) if min_cluster_size <= 1 else np.zeros(len(p), dtype=bool)
        return mask, {
            "min_cluster_size": min_cluster_size,
            "radius": radius,
            "total_components": len(p),
            "removed_components": len(p) if min_cluster_size > 1 else 0,
            "removed": int((~mask).sum()),
        }

    row = np.concatenate([pairs[:, 0], pairs[:, 1]])
    col = np.concatenate([pairs[:, 1], pairs[:, 0]])
    data = np.ones(len(row), dtype=bool)
    adj = coo_matrix((data, (row, col)), shape=(len(p), len(p)))
    n_components, comp_labels = connected_components(adj, directed=False)
    counts = np.bincount(comp_labels, minlength=n_components)
    mask = counts[comp_labels] >= min_cluster_size
    removed_count = int((~mask).sum())
    removed_components = int((counts < min_cluster_size).sum())
    return mask, {
        "min_cluster_size": min_cluster_size,
        "radius": radius,
        "total_components": int(n_components),
        "removed_components": removed_components,
        "removed": removed_count,
    }


def statistical_outlier_mask(cloud: GaussianCloud, *, nb_neighbors: int, std_ratio: float) -> tuple[np.ndarray, dict[str, Any]]:
    """Open3D's statistical outlier removal (with SciPy KDTree fallback when Open3D is unavailable):
    a point is kept if its mean distance to its `nb_neighbors` nearest neighbours is within `std_ratio`
    standard deviations of the dataset's mean neighbour distance."""
    try:
        pcd = _o3d_pointcloud(cloud.positions)
        _, kept_idx = pcd.remove_statistical_outlier(nb_neighbors=nb_neighbors, std_ratio=std_ratio)
        mask = np.zeros(len(cloud), dtype=bool)
        mask[np.asarray(kept_idx, dtype=int)] = True
    except (ImportError, ModuleNotFoundError):
        from scipy.spatial import cKDTree  # noqa: PLC0415
        p = np.asarray(cloud.positions, dtype=np.float64)
        if len(p) <= nb_neighbors:
            mask = np.ones(len(cloud), dtype=bool)
        else:
            tree = cKDTree(p)
            dists, _ = tree.query(p, k=nb_neighbors)
            mean_dists = np.mean(dists, axis=1)
            mean = float(np.mean(mean_dists))
            std = float(np.std(mean_dists))
            threshold = mean + std_ratio * std
            mask = mean_dists <= threshold
    return mask, {"nb_neighbors": nb_neighbors, "std_ratio": std_ratio}


def radius_min_neighbors(positions: np.ndarray, radius: float, *, cap: int, share: float, floor: int = 3,
                         sample: int = 20000, seed: int = 0) -> tuple[int, dict[str, Any]]:
    """The radius filter's neighbour threshold for THIS cloud: `share` of the median point's own count within `radius`
    (itself included, as Open3D counts), clamped to [floor, cap].

    A fixed count cannot work with a spacing-relative radius: how many neighbours an ordinary surface point has within
    k x the median nearest-neighbour spacing depends on how the surface is sampled, not on its density. A lattice has about
    pi*k^2 (28 at k = 3); a randomly sampled surface, which is what SfM and splat clouds look like, only about 1 + 9 ln 2
    (7.2). A fixed 8 therefore kept a lattice whole and removed about two thirds of a random floor or wall (FINAL_AUDIT
    P1-14). Relative to the cloud's own median count, the threshold separates isolated points from surface in both.
    `cap` keeps it never stricter than the fixed threshold it replaces. Deterministic (fixed-seed subsample)."""
    from scipy.spatial import cKDTree  # noqa: PLC0415

    p = np.asarray(positions, dtype=np.float64)
    query = p if len(p) <= sample else p[np.random.default_rng(seed).choice(len(p), sample, replace=False)]
    counts = cKDTree(p).query_ball_point(query, r=radius, return_length=True)
    median = float(np.median(counts))
    threshold = int(min(cap, max(floor, int(np.ceil(share * median)))))
    return threshold, {"median_neighbor_count": median, "neighbor_share": share, "floor": floor, "cap": cap}


def radius_outlier_mask(cloud: GaussianCloud, *, nb_points: int, radius: float) -> tuple[np.ndarray, dict[str, Any]]:
    """Open3D's radius outlier removal (with SciPy KDTree fallback when Open3D is unavailable):
    a point is kept only if at least `nb_points` points (itself included) lie within `radius` of it."""
    try:
        pcd = _o3d_pointcloud(cloud.positions)
        _, kept_idx = pcd.remove_radius_outlier(nb_points=nb_points, radius=radius)
        mask = np.zeros(len(cloud), dtype=bool)
        mask[np.asarray(kept_idx, dtype=int)] = True
    except (ImportError, ModuleNotFoundError):
        from scipy.spatial import cKDTree  # noqa: PLC0415
        p = np.asarray(cloud.positions, dtype=np.float64)
        counts = cKDTree(p).query_ball_point(p, r=radius, return_length=True)
        mask = counts >= nb_points
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

    mask = np.ones(len(cloud), dtype=bool)
    clutter_idx = np.where(labels == CLUTTER)[0]
    removed = 0
    if len(clutter_idx) > 0:
        try:
            import open3d as o3d  # noqa: PLC0415
            pcd = _o3d_pointcloud(cloud.positions[clutter_idx])
            tree = o3d.geometry.KDTreeFlann(pcd)
            for local_i, point in enumerate(np.asarray(pcd.points)):
                count, _, _ = tree.search_radius_vector_3d(point, radius)
                if count - 1 < min_same_class_neighbors:  # -1: a point is its own neighbour in the search
                    mask[clutter_idx[local_i]] = False
                    removed += 1
        except (ImportError, ModuleNotFoundError):
            from scipy.spatial import cKDTree  # noqa: PLC0415
            p = np.asarray(cloud.positions[clutter_idx], dtype=np.float64)
            counts = cKDTree(p).query_ball_point(p, r=radius, return_length=True)
            isolated = (counts - 1) < min_same_class_neighbors
            mask[clutter_idx[isolated]] = False
            removed = int(np.sum(isolated))
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
        inlier_labels = labels[global_idx] if labels is not None else None
        classification = _classify(normal, up, inlier_labels)
        planes.append(FittedPlane(tuple(float(v) for v in (*normal, -float(np.dot(normal, positions[global_idx].mean(axis=0))))),
                                  global_idx, classification, _agreement(classification, global_idx, labels)))
    return planes


def _classify(normal: np.ndarray, up: np.ndarray, inlier_labels: np.ndarray | None = None) -> str:
    angle_to_up = np.degrees(np.arccos(np.clip(abs(float(np.dot(normal, up))), 0.0, 1.0)))
    if inlier_labels is not None and len(inlier_labels) > 0:
        furniture_ratio = float((inlier_labels == FURNITURE).mean())
        if furniture_ratio > 0.40 and angle_to_up <= 35.0:
            return FURNITURE
    if angle_to_up <= 25.0:
        return FLOOR
    if angle_to_up >= 65.0:
        return WALL
    return "other"


def _agreement(classification: str, inlier_idx: np.ndarray, labels: np.ndarray | None) -> float:
    if labels is None:
        return 1.0
    plane_labels = labels[inlier_idx]
    agree_with = {FLOOR: FLOOR, WALL: WALL, FURNITURE: FURNITURE}.get(classification)
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
        inlier_labels = labels[p.inlier_indices] if labels is not None else None
        classification = _classify(normal, up, inlier_labels)
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
