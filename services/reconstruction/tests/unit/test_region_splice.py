"""Pure geometry tests for chaya_worker.region_splice -- no Open3D, no external tools. Real fixtures: a
synthetic venue-wide GaussianCloud laid out on a grid, and a polygon selecting one sub-region of it.
"""

from __future__ import annotations

import numpy as np
import pytest

from chaya_worker.ply import GaussianCloud
from chaya_worker.region_splice import point_in_polygon, polygon_area, splice_region, transform_gaussians

SQUARE = np.array([[0.0, 0.0], [0.0, 1.0], [1.0, 1.0], [1.0, 0.0]])


def _grid_cloud(nx: int = 10, ny: int = 10, spacing: float = 1.0) -> GaussianCloud:
    xs, ys = np.meshgrid(np.arange(nx) * spacing, np.arange(ny) * spacing)
    positions = np.stack([xs.ravel(), ys.ravel(), np.zeros(nx * ny)], axis=1).astype(np.float32)
    n = len(positions)
    return GaussianCloud(
        positions=positions,
        scales_log=np.zeros((n, 3), dtype=np.float32),
        rotations_wxyz=np.tile(np.array([1.0, 0.0, 0.0, 0.0], dtype=np.float32), (n, 1)),  # identity
        opacity_logit=np.zeros(n, dtype=np.float32),
        colors_dc=np.zeros((n, 3), dtype=np.float32),
    )


def test_point_in_polygon_unit_square():
    points = np.array([[0.5, 0.5], [1.5, 0.5], [-0.1, 0.5], [0.5, -0.1]])
    inside = point_in_polygon(points, SQUARE)
    assert inside.tolist() == [True, False, False, False]


def test_point_in_polygon_rejects_degenerate_polygon():
    with pytest.raises(ValueError):
        point_in_polygon(np.array([[0.0, 0.0]]), np.array([[0.0, 0.0], [1.0, 1.0]]))


def test_polygon_area_unit_square_and_larger_rectangle():
    assert polygon_area(SQUARE) == pytest.approx(1.0)
    rectangle = np.array([[0.0, 0.0], [0.0, 2.0], [5.0, 2.0], [5.0, 0.0]])
    assert polygon_area(rectangle) == pytest.approx(10.0)


def test_transform_gaussians_translates_positions_and_rotates_orientation():
    cloud = _grid_cloud(nx=2, ny=2)
    translation = np.array([10.0, 20.0, 0.0])
    theta = np.radians(90.0)
    rotation = np.array([[np.cos(theta), -np.sin(theta), 0], [np.sin(theta), np.cos(theta), 0], [0, 0, 1]])
    transform = np.eye(4)
    transform[:3, :3] = rotation
    transform[:3, 3] = translation

    moved = transform_gaussians(cloud, transform)

    expected_positions = cloud.positions.astype(np.float64) @ rotation.T + translation
    np.testing.assert_allclose(moved.positions, expected_positions, atol=1e-5)
    # Every Gaussian started with identity orientation, so after a 90 degree rotation each one's own
    # orientation quaternion must now represent that same 90 degree rotation -- not be left untouched.
    rotated_quats = moved.rotations_normalized()
    for q in rotated_quats:
        w, x, y, z = q
        angle = 2 * np.degrees(np.arccos(np.clip(abs(w), 0, 1)))
        assert angle == pytest.approx(90.0, abs=1e-2)


def test_transform_gaussians_rejects_non_4x4():
    cloud = _grid_cloud(nx=2, ny=2)
    with pytest.raises(ValueError):
        transform_gaussians(cloud, np.eye(3))


def test_splice_region_replaces_only_the_polygon_and_leaves_the_rest_untouched():
    global_cloud = _grid_cloud(nx=10, ny=10, spacing=1.0)  # a 10x10m grid, one point per meter
    # Select the region x in [2, 5], y in [2, 5]: real grid points at x,y in {2,3,4} fall strictly inside.
    polygon = np.array([[2.0, 2.0], [2.0, 5.0], [5.0, 5.0], [5.0, 2.0]])

    n_region = 5
    region_cloud = GaussianCloud(
        positions=np.zeros((n_region, 3), dtype=np.float32),  # already "aligned" to sit at the origin corner
        scales_log=np.ones((n_region, 3), dtype=np.float32),  # distinguishable from the global cloud's zeros
        rotations_wxyz=np.tile(np.array([1.0, 0, 0, 0], dtype=np.float32), (n_region, 1)),
        opacity_logit=np.ones(n_region, dtype=np.float32) * 5.0,
        colors_dc=np.ones((n_region, 3), dtype=np.float32),
    )

    merged, report = splice_region(global_cloud, region_cloud, polygon)

    inside_mask = point_in_polygon(global_cloud.positions[:, :2].astype(np.float64), polygon)
    expected_removed = int(inside_mask.sum())
    assert report["removed_from_global"] == expected_removed
    assert report["added_from_region"] == n_region
    assert report["total_after"] == len(global_cloud) - expected_removed + n_region
    assert len(merged) == report["total_after"]

    # Every point outside the polygon must survive completely unchanged (same positions, in the same
    # order among themselves) -- this is a targeted replacement, not a re-reconstruction.
    outside_original = global_cloud.positions[~inside_mask]
    outside_merged = merged.positions[: len(outside_original)]
    np.testing.assert_array_equal(outside_original, outside_merged)

    # The new region's distinguishing opacity value must appear in the merged cloud.
    assert np.any(merged.opacity_logit == 5.0)


def test_splice_region_refuses_an_empty_aligned_region():
    global_cloud = _grid_cloud(nx=3, ny=3)
    empty_region = GaussianCloud(
        positions=np.zeros((0, 3), dtype=np.float32), scales_log=np.zeros((0, 3), dtype=np.float32),
        rotations_wxyz=np.zeros((0, 4), dtype=np.float32), opacity_logit=np.zeros(0, dtype=np.float32),
        colors_dc=np.zeros((0, 3), dtype=np.float32))
    with pytest.raises(ValueError):
        splice_region(global_cloud, empty_region, SQUARE)
