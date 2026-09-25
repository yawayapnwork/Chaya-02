"""Real feature-based + ICP alignment (chaya_worker.region_alignment), exercised against synthetic but
geometrically real fixtures: a room-corner-shaped point cloud (two walls + a floor, not pure noise, so FPFH
features have something real to match), captured "again" after a KNOWN rigid transform is applied. This is
SKIPPED, with the reason printed by `pytest -rs`, when Open3D is not installed -- nothing here can claim a
result without actually running the registration.

Per docs/rescan.md "TESTS": measures translation error, rotation error and the alignment residual
(inlier RMSE) against the transform actually applied, and separately verifies the confidence gate reports
LOW confidence -- never fabricated high confidence -- when the two clouds do not really overlap.
"""

from __future__ import annotations

import numpy as np
import pytest

from chaya_worker.region_alignment import align_region, alignment_confidence, rotation_angle_degrees
from chaya_worker.toolchain import Toolchain

_tc = Toolchain()
needs_open3d = pytest.mark.skipif(not _tc.module("open3d").available, reason="Open3D is not installed")
pytestmark = [pytest.mark.gpu, needs_open3d]


def _rotation_matrix_z(degrees: float) -> np.ndarray:
    theta = np.radians(degrees)
    c, s = np.cos(theta), np.sin(theta)
    return np.array([[c, -s, 0.0], [s, c, 0.0], [0.0, 0.0, 1.0]])


def _room_corner(n_per_wall: int = 40, rng: np.random.Generator | None = None) -> np.ndarray:
    """Two perpendicular walls (x=0 plane, y=0 plane) and a floor (z=0 plane), each a dense, irregular
    (jittered grid, not a perfect lattice) patch of points -- real enough geometric structure for FPFH
    normals/curvature to be meaningful, unlike uniform random noise."""
    rng = rng or np.random.default_rng(0)

    def patch(const_axis: int, const_value: float, extent: float) -> np.ndarray:
        u = rng.uniform(0, extent, n_per_wall)
        v = rng.uniform(0, extent, n_per_wall)
        pts = np.zeros((n_per_wall, 3))
        axes = [a for a in range(3) if a != const_axis]
        pts[:, axes[0]] = u
        pts[:, axes[1]] = v
        pts[:, const_axis] = const_value + rng.normal(0, 0.002, n_per_wall)  # a little real surface noise
        return pts

    wall_x = patch(0, 0.0, 3.0)
    wall_y = patch(1, 0.0, 3.0)
    floor = patch(2, 0.0, 3.0)
    return np.concatenate([wall_x, wall_y, floor], axis=0)


def _apply_transform(points: np.ndarray, rotation: np.ndarray, translation: np.ndarray) -> np.ndarray:
    return points @ rotation.T + translation


def test_alignment_recovers_a_known_small_transform_with_low_error():
    rng = np.random.default_rng(1)
    target = _room_corner(rng=rng)  # "the existing global reconstruction", in the venue frame
    true_rotation = _rotation_matrix_z(7.0)  # a modest, realistic re-capture misalignment
    true_translation = np.array([0.30, -0.15, 0.02])
    source = _apply_transform(target, true_rotation, true_translation)  # "the freshly captured region"

    result = align_region(source, target, voxel_size=0.05)

    estimated_rotation = result.transform[:3, :3]
    estimated_translation = result.transform[:3, 3]

    # Applying the estimated transform to `source` should land back close to `target` -- but the transform
    # align_region returns maps SOURCE -> TARGET, and source = R_true @ target + t_true, so the transform
    # we expect to recover is the INVERSE of (R_true, t_true).
    expected_rotation = true_rotation.T
    expected_translation = -true_rotation.T @ true_translation

    translation_error_m = float(np.linalg.norm(estimated_translation - expected_translation))
    rotation_error_deg = rotation_angle_degrees(estimated_rotation @ expected_rotation.T)

    assert translation_error_m < 0.03, f"translation error too high: {translation_error_m} m"
    assert rotation_error_deg < 2.0, f"rotation error too high: {rotation_error_deg} deg"
    assert result.inlier_rmse < 0.05, f"alignment residual too high: {result.inlier_rmse} m"
    assert result.confidence > 0.6, f"a clean, well-overlapping alignment should score confidently: {result.confidence}"
    assert result.method == "FEATURE_RANSAC_ICP"


def test_alignment_of_non_overlapping_clouds_reports_low_confidence_never_a_fabricated_pass():
    rng = np.random.default_rng(2)
    target = _room_corner(rng=rng)
    # A region captured somewhere else entirely -- shares no real structure with `target`, so no honest
    # registration should report high confidence for it.
    disjoint_source = _room_corner(rng=rng) + np.array([500.0, 500.0, 500.0])

    result = align_region(disjoint_source, target, voxel_size=0.05)

    assert result.confidence < 0.6, (
        "non-overlapping clouds must not be reported as a confident alignment "
        f"(got confidence={result.confidence}, fitness={result.fitness}, inlier_rmse={result.inlier_rmse})"
    )


def test_alignment_requires_a_minimum_number_of_points():
    tiny = np.random.default_rng(3).uniform(0, 1, (3, 3))
    target = _room_corner()
    with pytest.raises(ValueError):
        align_region(tiny, target, voxel_size=0.05)


def test_alignment_confidence_is_a_real_function_of_its_inputs_not_a_constant():
    # Same fitness, worse RMSE relative to voxel size -> strictly lower confidence.
    good = alignment_confidence(fitness=0.9, inlier_rmse=0.01, voxel_size=0.05)
    bad = alignment_confidence(fitness=0.9, inlier_rmse=0.049, voxel_size=0.05)
    assert good > bad
    # Zero fitness can never produce nonzero confidence, regardless of RMSE.
    assert alignment_confidence(fitness=0.0, inlier_rmse=0.0, voxel_size=0.05) == 0.0
    with pytest.raises(ValueError):
        alignment_confidence(fitness=1.0, inlier_rmse=0.0, voxel_size=0.0)


def test_scale_aware_alignment_recovers_a_residual_calibration_scale_error():
    """A region pre-scaled by its own metric calibration is still off by that calibration's error. With
    with_scaling=True registration must recover it; this is the case REGION_ALIGNMENT runs (docs/coordinate-frames.md)."""
    rng = np.random.default_rng(4)
    target = _room_corner(rng=rng)
    true_scale = 1.04  # a 4 % calibration error on the region
    source = _apply_transform(target, _rotation_matrix_z(5.0), np.array([0.2, 0.1, 0.0])) * true_scale

    result = align_region(source, target, voxel_size=0.05, with_scaling=True)

    recovered_scale = float(np.cbrt(np.linalg.det(result.transform[:3, :3])))
    assert abs(recovered_scale * true_scale - 1.0) < 0.01, f"scale not recovered: {recovered_scale} vs {1 / true_scale}"
    assert result.confidence > 0.6
