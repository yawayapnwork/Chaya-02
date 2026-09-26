"""Aligning a re-captured region onto the venue's existing reconstruction, in canonical metres (docs/rescan.md,
"ALIGNMENT").

Both inputs are already metric: the venue through its canonical frame, the region through its own calibration. What is
left to estimate depends on how the region was calibrated, and chaya_worker.stages.region_alignment picks the mode:

  * **DIRECT_CANONICAL**: both calibrations are surveyed to the same venue control points. The region's calibration
    alone places it in the canonical frame. ICP (similarity) only verifies and refines, and the correction it applies
    is gated: surveyed calibrations must agree.
  * **FEATURE_SIMILARITY**: anything else (a FLOOR_LOCAL or scale-only calibration has its own origin and heading).
    A full similarity (scale + rotation + translation) is estimated:
      1. putative correspondences from FPFH feature matching (Open3D computes the descriptors; the matching is mutual
         nearest neighbours in descriptor space);
      2. RANSAC over those correspondences (chaya_worker.similarity_registration.ransac_similarity), with the scale
         prior |s - 1| <= max_scale_correction, since the region is already metric;
      3. coarse-to-fine similarity ICP from the RANSAC estimate.
    If the region is gravity-aligned, the correction may only rotate it about the vertical (the tilt gate).

Either way the final transform is measured (correspondence count, inlier ratio, scale, rotation / translation / ICP
residuals, confidence; see chaya_worker.similarity_registration) and gated. The result says ACCEPTED or REJECTED, with
every gate's value and threshold. Nothing is spliced after a rejection.
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass, field

import numpy as np
from scipy.spatial import cKDTree

from .frames import Similarity
from .similarity_registration import (
    AlignmentGates,
    AlignmentMetrics,
    GateReport,
    RegistrationError,
    alignment_metrics,
    evaluate_gates,
    icp_similarity,
    ransac_similarity,
    rotation_angle_degrees,
    tilt_degrees,
    voxel_downsample,
)

MODE_DIRECT = "DIRECT_CANONICAL"
MODE_FEATURE = "FEATURE_SIMILARITY"
METHODS = {MODE_DIRECT: "DIRECT_CANONICAL_ICP", MODE_FEATURE: "FEATURE_SIMILARITY_ICP"}

Correspondences = Callable[[np.ndarray, np.ndarray, float], tuple[np.ndarray, np.ndarray]]


@dataclass
class AlignmentResult:
    mode: str
    method: str
    correction: Similarity  # maps the (metric) source onto the target; applied on top of the region's calibration
    metrics: AlignmentMetrics
    gates: GateReport
    details: dict = field(default_factory=dict)
    error: str | None = None  # why estimation itself failed, when it did

    @property
    def accepted(self) -> bool:
        return self.error is None and self.gates.passed

    @property
    def status(self) -> str:
        return "ACCEPTED" if self.accepted else "REJECTED"


def fpfh_correspondences(source: np.ndarray, target: np.ndarray, voxel_m: float) -> tuple[np.ndarray, np.ndarray]:
    """Putative correspondences from FPFH descriptors: mutual nearest neighbours in descriptor space between the two
    clouds, downsampled at `voxel_m`. Many will be wrong; ransac_similarity sorts them out. Needs Open3D (for FPFH only)."""
    import open3d as o3d  # noqa: PLC0415 - optional dependency; the stage requires it before this mode runs

    def features(points: np.ndarray):
        pcd = o3d.geometry.PointCloud(o3d.utility.Vector3dVector(points))
        down = pcd.voxel_down_sample(voxel_m)
        down.estimate_normals(o3d.geometry.KDTreeSearchParamHybrid(radius=voxel_m * 2, max_nn=30))
        fpfh = o3d.pipelines.registration.compute_fpfh_feature(down, o3d.geometry.KDTreeSearchParamHybrid(radius=voxel_m * 5, max_nn=100))
        return np.asarray(down.points), np.asarray(fpfh.data).T

    sp, sf = features(source)
    tp, tf = features(target)
    if len(sp) < 3 or len(tp) < 3:
        return np.zeros((0, 3)), np.zeros((0, 3))
    _, s2t = cKDTree(tf).query(sf)
    _, t2s = cKDTree(sf).query(tf)
    mutual = t2s[s2t] == np.arange(len(sp))
    return sp[mutual], tp[s2t[mutual]]


def align_region(source_m: np.ndarray, target_m: np.ndarray, *, mode: str, gates: AlignmentGates, icp_schedule_m: list[float],
                 icp_voxel_m: float, feature_voxel_m: float, gravity_aligned: bool = False,
                 correspondences: Correspondences = fpfh_correspondences, seed: int = 0) -> AlignmentResult:
    """Aligns `source_m` (the region, already metric and, in DIRECT mode, already canonical) onto `target_m` (the venue
    crop, canonical). Returns the correction, its metrics and the gate report. Estimation failures come back as a
    REJECTED result with `error` set; they are never raised."""
    if mode not in METHODS:
        raise ValueError(f"unknown alignment mode {mode!r}")
    src = voxel_downsample(np.asarray(source_m, dtype=np.float64), icp_voxel_m)
    dst = voxel_downsample(np.asarray(target_m, dtype=np.float64), icp_voxel_m)
    tree = cKDTree(dst) if len(dst) else None
    details: dict = {"source_points": len(src), "target_points": len(dst), "icp_voxel_m": icp_voxel_m, "icp_schedule_m": icp_schedule_m}
    report = GateReport()
    init = Similarity.identity()
    error = None

    def measured(T: Similarity) -> AlignmentMetrics:
        return alignment_metrics(src, dst, T, correspondence_distance_m=icp_schedule_m[-1], max_translation_residual_m=gates.max_translation_residual_m,
                                 dst_tree=tree)

    if len(src) < 3 or len(dst) < 3:
        error = f"too few points to align ({len(src)} region, {len(dst)} venue after {icp_voxel_m} m downsampling)"
    elif mode == MODE_FEATURE:
        p_src, p_dst = correspondences(src, dst, feature_voxel_m)
        details["putative_correspondences"] = len(p_src)
        bound = 1.0 + gates.max_scale_correction
        try:
            ransac = ransac_similarity(p_src, p_dst, inlier_threshold_m=1.5 * feature_voxel_m, scale_bounds=(1.0 / bound, bound), seed=seed)
            init = ransac.transform
            details.update(ransac_inliers=ransac.inlier_count, ransac_inlier_ratio=ransac.inlier_ratio,
                           ransac_hypotheses=ransac.hypotheses_tested, ransac_rejected_by_scale=ransac.hypotheses_rejected_by_scale)
            report.add("ransac_inliers", ransac.inlier_count, gates.min_ransac_inliers, ">=", "correspondences")
        except RegistrationError as exc:
            error = f"robust estimation failed: {exc}"
            report.add("ransac_inliers", 0, gates.min_ransac_inliers, ">=", "correspondences")

    T = init
    if error is None:
        try:
            icp = icp_similarity(src, dst, init, schedule_m=icp_schedule_m, dst_tree=tree)
            T = icp.transform
            details["icp_iterations"] = icp.iterations
        except RegistrationError as exc:
            error = f"ICP failed: {exc}"

    metrics = measured(T) if tree is not None and len(src) else AlignmentMetrics(0, 0, 0.0, T.scale, 90.0, float("inf"), float("inf"), float("inf"), 0.0)
    for r in evaluate_gates(metrics, gates).results:
        report.results.append(r)
    details["correction_rotation_deg"] = rotation_angle_degrees(T.rotation)
    details["correction_translation_at_centroid_m"] = float(np.linalg.norm(T.apply(src.mean(axis=0)) - src.mean(axis=0))) if len(src) else None
    if mode == MODE_DIRECT:
        report.add("direct_rotation_correction_deg", details["correction_rotation_deg"], gates.max_direct_rotation_correction_deg, "<=", "deg")
        report.add("direct_translation_correction_m", details["correction_translation_at_centroid_m"],
                   gates.max_direct_translation_correction_m, "<=", "m")
    if mode == MODE_FEATURE and gravity_aligned:
        details["correction_tilt_deg"] = tilt_degrees(T.rotation)
        report.add("tilt_deg", details["correction_tilt_deg"], gates.max_tilt_deg, "<=", "deg")
    if error is not None:
        report.add("estimation", None, 0.0, ">=")
    return AlignmentResult(mode, METHODS[mode], T, metrics, report, details, error)
