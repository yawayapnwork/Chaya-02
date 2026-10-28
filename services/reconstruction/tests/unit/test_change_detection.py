"""Unit tests for changed-region detection and local alignment (change_detection.py)."""

from __future__ import annotations

import numpy as np
import pytest

from chaya_worker.change_detection import (
    align_on_unchanged_surfaces,
    detect_changed_regions,
)
from chaya_worker.frames import Similarity


def _make_room_cloud(width: float = 6.0, length: float = 8.0, height: float = 3.0, step: float = 0.2) -> np.ndarray:
    """Generates a simple box room point cloud (floor, ceiling, 4 walls)."""
    points = []
    # Floor: z = 0
    x, y = np.meshgrid(np.arange(0, width, step), np.arange(0, length, step))
    z = np.zeros_like(x)
    points.append(np.stack([x.ravel(), y.ravel(), z.ravel()], axis=1))

    # Walls
    # x = 0 and x = width
    y_w, z_w = np.meshgrid(np.arange(0, length, step), np.arange(0, height, step))
    points.append(np.stack([np.zeros_like(y_w).ravel(), y_w.ravel(), z_w.ravel()], axis=1))
    points.append(np.stack([np.full_like(y_w, width).ravel(), y_w.ravel(), z_w.ravel()], axis=1))

    # y = 0 and y = length
    x_w, z_w = np.meshgrid(np.arange(0, width, step), np.arange(0, height, step))
    points.append(np.stack([x_w.ravel(), np.zeros_like(x_w).ravel(), z_w.ravel()], axis=1))
    points.append(np.stack([x_w.ravel(), np.full_like(x_w, length).ravel(), z_w.ravel()], axis=1))

    return np.vstack(points)


def _make_box(center: tuple[float, float, float], size: tuple[float, float, float], step: float = 0.1) -> np.ndarray:
    cx, cy, cz = center
    sx, sy, sz = size
    xs = np.arange(cx - sx / 2, cx + sx / 2 + 1e-4, step)
    ys = np.arange(cy - sy / 2, cy + sy / 2 + 1e-4, step)
    zs = np.arange(cz - sz / 2, cz + sz / 2 + 1e-4, step)
    gx, gy, gz = np.meshgrid(xs, ys, zs)
    return np.stack([gx.ravel(), gy.ravel(), gz.ravel()], axis=1)


def test_identical_scenes_show_zero_change():
    room = _make_room_cloud()
    res = detect_changed_regions(room, room, tolerance_m=0.03)
    assert res.changed_points_count == 0
    assert res.added_points_count == 0
    assert res.removed_points_count == 0
    assert res.change_ratio == 0.0
    assert res.changed_area_m2 == 0.0
    assert res.unchanged_points_count == len(room)
    assert res.mean_unchanged_residual_m < 1e-4


def test_added_geometry_is_isolated_with_bounding_polygon():
    room = _make_room_cloud()
    crate = _make_box(center=(3.0, 4.0, 0.5), size=(1.0, 1.0, 1.0), step=0.1)
    rescan = np.vstack([room, crate])

    res = detect_changed_regions(room, rescan, tolerance_m=0.05, cell_size_m=0.25)
    assert res.added_points_count > 0
    assert res.added_points_count == pytest.approx(len(crate), abs=40)
    assert res.removed_points_count == 0
    assert res.changed_area_m2 > 0.5  # crate is 1m x 1m
    assert len(res.changed_polygon) >= 3

    # Polygon must enclose the crate coordinates (x in [2.5, 3.5], y in [3.5, 4.5])
    poly = np.array(res.changed_polygon)
    assert poly[:, 0].min() <= 2.5
    assert poly[:, 0].max() >= 3.5
    assert poly[:, 1].min() <= 3.5
    assert poly[:, 1].max() >= 4.5


def test_removed_geometry_is_isolated_with_bounding_polygon():
    room = _make_room_cloud()
    partition = _make_box(center=(2.0, 3.0, 1.0), size=(0.2, 2.0, 2.0), step=0.1)
    parent = np.vstack([room, partition])
    rescan = room  # partition removed in rescan

    res = detect_changed_regions(parent, rescan, tolerance_m=0.05, cell_size_m=0.25)
    assert res.removed_points_count > 0
    assert res.removed_points_count == pytest.approx(len(partition), abs=15)
    assert res.added_points_count == 0
    assert res.changed_area_m2 > 0.3
    assert len(res.changed_polygon) >= 3


def test_moved_geometry_detects_both_added_and_removed():
    room = _make_room_cloud()
    crate_old = _make_box(center=(1.5, 2.0, 0.5), size=(0.8, 0.8, 1.0), step=0.1)
    crate_new = _make_box(center=(4.5, 5.0, 0.5), size=(0.8, 0.8, 1.0), step=0.1)

    parent = np.vstack([room, crate_old])
    rescan = np.vstack([room, crate_new])

    res = detect_changed_regions(parent, rescan, tolerance_m=0.05, cell_size_m=0.25)
    assert res.removed_points_count > 0
    assert res.added_points_count > 0
    assert res.changed_points_count == res.removed_points_count + res.added_points_count
    assert res.changed_area_m2 > 1.0


def test_align_on_unchanged_surfaces_ignores_distracting_changes():
    # Parent room has static walls and a table
    room = _make_room_cloud(width=5.0, length=5.0, height=3.0, step=0.25)
    table_parent = _make_box(center=(2.5, 2.5, 0.4), size=(1.2, 1.2, 0.8), step=0.1)
    parent = np.vstack([room, table_parent])

    # Rescan room has same static walls, but the table moved completely
    table_rescan = _make_box(center=(1.0, 1.0, 0.4), size=(1.2, 1.2, 0.8), step=0.1)
    # Apply a small displacement to the rescan (e.g. 0.04m translation)
    true_shift = np.array([0.03, -0.02, 0.01])
    rescan_local = np.vstack([room, table_rescan]) + true_shift

    initial_transform = Similarity.identity()
    refined_transform, detection = align_on_unchanged_surfaces(
        rescan_local, parent, initial_transform=initial_transform, tolerance_m=0.06
    )

    # After alignment, the translation should cancel the true_shift on the static surfaces
    est_shift = refined_transform.translation
    assert est_shift[0] == pytest.approx(-0.03, abs=0.015)
    assert est_shift[1] == pytest.approx(0.02, abs=0.015)
    assert est_shift[2] == pytest.approx(-0.01, abs=0.015)
    assert detection.unchanged_points_count > 100
    assert detection.mean_unchanged_residual_m < 0.025


def test_empty_clouds_handled_gracefully():
    empty = np.zeros((0, 3))
    non_empty = np.array([[1.0, 2.0, 3.0]])
    res = detect_changed_regions(empty, non_empty)
    assert res.changed_points_count == 1
    assert res.unchanged_points_count == 0
