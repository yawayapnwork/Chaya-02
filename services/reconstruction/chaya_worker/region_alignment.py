"""Real feature-based + ICP alignment of a newly captured region against the venue's existing global
reconstruction, for incremental re-scans (see docs/rescan.md "ALIGNMENT"). Two-stage registration, the
standard approach when no initial transform is known:

  1. Coarse, feature-based global registration: FPFH descriptors + RANSAC correspondence matching
     (Open3D's `registration_ransac_based_on_feature_matching`). Works without an initial guess.
  2. Point-to-plane ICP refinement seeded from the coarse result (Open3D's `registration_icp`):
     high precision, but needs a starting point -- which step 1 provides.

The resulting transform is whatever these two real registration passes converge to; it is never a
hardcoded or assumed translation. `alignment_confidence` turns Open3D's own registration-quality numbers
(fitness, inlier RMSE) into the single score the control plane gates on -- see its docstring for the exact
formula. Needs Open3D; callers gate with `ctx.toolchain.require(["py:open3d"], stage=...)` first, exactly
like GEOMETRIC_CLEANUP.
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np

from .geometry_cleanup import _o3d_pointcloud


@dataclass
class AlignmentResult:
    transform: np.ndarray  # (4, 4): maps source (region, local capture frame) into target (venue frame)
    method: str  # "FEATURE_RANSAC_ICP"
    fitness: float  # Open3D ICP fitness: fraction of source points with a close correspondence, [0, 1]
    inlier_rmse: float  # RMSE, in meters, of those correspondences
    confidence: float  # see `alignment_confidence`
    translation_m: float  # magnitude of the transform's translation component
    rotation_deg: float  # angle of the transform's rotation component


def alignment_confidence(fitness: float, inlier_rmse: float, voxel_size: float) -> float:
    """A real, documented function of the two numbers Open3D's own ICP result reports -- never a constant
    and never independent of what actually happened.

    `fitness` (Open3D) is already a real correspondence-coverage fraction in [0, 1]. It is scaled down by
    how close `inlier_rmse` is to `voxel_size` (the length scale registration ran at): an RMSE near or
    above the voxel size means the "inlier" correspondences barely qualify, so confidence should not be as
    high as fitness alone suggests. The scale factor is 1 at rmse=0 and 0 at rmse >= voxel_size.
    """
    if voxel_size <= 0:
        raise ValueError("voxel_size must be positive")
    rmse_factor = max(0.0, 1.0 - inlier_rmse / voxel_size)
    return float(np.clip(fitness * rmse_factor, 0.0, 1.0))


def rotation_angle_degrees(rotation_matrix: np.ndarray) -> float:
    """Angle of a 3x3 rotation matrix's axis-angle representation, in degrees -- the real rotation
    magnitude, not a per-axis Euler decomposition (ambiguous and gimbal-lock-prone). Also used by tests to
    measure rotation error against a known ground truth (see tests/unit/test_region_alignment.py)."""
    trace = np.clip((np.trace(rotation_matrix) - 1.0) / 2.0, -1.0, 1.0)
    return float(np.degrees(np.arccos(trace)))


def _feature_registration(source, target, voxel_size: float):
    import open3d as o3d  # noqa: PLC0415 - intentionally lazy; caller already checked availability

    source_down = source.voxel_down_sample(voxel_size)
    target_down = target.voxel_down_sample(voxel_size)
    radius_normal = voxel_size * 2
    source_down.estimate_normals(o3d.geometry.KDTreeSearchParamHybrid(radius=radius_normal, max_nn=30))
    target_down.estimate_normals(o3d.geometry.KDTreeSearchParamHybrid(radius=radius_normal, max_nn=30))
    radius_feature = voxel_size * 5
    source_fpfh = o3d.pipelines.registration.compute_fpfh_feature(
        source_down, o3d.geometry.KDTreeSearchParamHybrid(radius=radius_feature, max_nn=100))
    target_fpfh = o3d.pipelines.registration.compute_fpfh_feature(
        target_down, o3d.geometry.KDTreeSearchParamHybrid(radius=radius_feature, max_nn=100))
    result = o3d.pipelines.registration.registration_ransac_based_on_feature_matching(
        source_down, target_down, source_fpfh, target_fpfh, mutual_filter=True,
        max_correspondence_distance=voxel_size * 1.5,
        estimation_method=o3d.pipelines.registration.TransformationEstimationPointToPoint(False),
        ransac_n=4,
        checkers=[
            o3d.pipelines.registration.CorrespondenceCheckerBasedOnEdgeLength(0.9),
            o3d.pipelines.registration.CorrespondenceCheckerBasedOnDistance(voxel_size * 1.5),
        ],
        criteria=o3d.pipelines.registration.RANSACConvergenceCriteria(100000, 0.999))
    return result, source_down, target_down


def align_region(source_positions: np.ndarray, target_positions: np.ndarray, *, voxel_size: float,
                 icp_max_correspondence_distance: float | None = None) -> AlignmentResult:
    """Aligns `source_positions` (the freshly captured region's point cloud, in its own local frame) onto
    `target_positions` (the relevant crop of the venue's existing global reconstruction). Real Open3D
    global registration + ICP end to end; the transform returned is exactly what they converged to."""
    import open3d as o3d  # noqa: PLC0415

    if len(source_positions) < 10 or len(target_positions) < 10:
        raise ValueError("at least 10 points are needed in both the source and target clouds to align")

    source = _o3d_pointcloud(source_positions)
    target = _o3d_pointcloud(target_positions)

    coarse, source_down, target_down = _feature_registration(source, target, voxel_size)

    icp_distance = icp_max_correspondence_distance or voxel_size * 1.5
    target_down.estimate_normals(o3d.geometry.KDTreeSearchParamHybrid(radius=voxel_size * 2, max_nn=30))
    icp = o3d.pipelines.registration.registration_icp(
        source_down, target_down, icp_distance, coarse.transformation,
        o3d.pipelines.registration.TransformationEstimationPointToPlane())

    transform = np.asarray(icp.transformation, dtype=np.float64)
    confidence = alignment_confidence(icp.fitness, icp.inlier_rmse, voxel_size)

    return AlignmentResult(
        transform=transform, method="FEATURE_RANSAC_ICP", fitness=float(icp.fitness),
        inlier_rmse=float(icp.inlier_rmse), confidence=confidence,
        translation_m=float(np.linalg.norm(transform[:3, 3])),
        rotation_deg=rotation_angle_degrees(transform[:3, :3]))
