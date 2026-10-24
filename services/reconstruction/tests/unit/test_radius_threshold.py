"""GEOMETRIC_CLEANUP's radius-filter threshold (chaya_worker.geometry_cleanup.radius_min_neighbors), without Open3D.

A fixed neighbour count with a spacing-relative radius removed about two thirds of a randomly sampled surface while
keeping a lattice whole (FINAL_AUDIT P1-14). The threshold must follow how the cloud itself is sampled.
"""

import numpy as np
from scipy.spatial import cKDTree

from chaya_worker.geometry_cleanup import characteristic_spacing, radius_min_neighbors

CAP, SHARE = 8, 0.35


def _kept_share(points: np.ndarray, threshold: int, radius: float) -> float:
    counts = cKDTree(points).query_ball_point(points, r=radius, return_length=True)  # itself included, as Open3D counts
    return float((counts >= threshold).mean())


def _random_plane(n: int, seed: int) -> np.ndarray:
    rng = np.random.default_rng(seed)
    return np.stack([rng.uniform(0, 10, n), rng.uniform(0, 10, n), np.zeros(n)], axis=1)


def _lattice_plane(side: int) -> np.ndarray:
    a, b = np.meshgrid(np.arange(side, dtype=float), np.arange(side, dtype=float))
    return np.stack([a.ravel(), b.ravel(), np.zeros(side * side)], axis=1)


def test_a_dense_lattice_keeps_the_fixed_threshold():
    pts = _lattice_plane(120)
    radius = 3.0 * characteristic_spacing(pts)
    threshold, basis = radius_min_neighbors(pts, radius, cap=CAP, share=SHARE)
    assert threshold == CAP
    assert basis["median_neighbor_count"] > 25


def test_a_randomly_sampled_surface_keeps_almost_every_point():
    for seed in range(3):
        pts = _random_plane(20000, seed)
        radius = 3.0 * characteristic_spacing(pts)
        threshold, _ = radius_min_neighbors(pts, radius, cap=CAP, share=SHARE)
        assert threshold < CAP
        assert _kept_share(pts, threshold, radius) > 0.95
        assert _kept_share(pts, CAP, radius) < 0.5  # the fixed threshold this replaces: the defect


def test_isolated_debris_next_to_a_random_surface_is_still_below_the_threshold():
    surface = _random_plane(20000, 0)
    rng = np.random.default_rng(1)
    debris = np.stack([rng.uniform(0, 10, 200), rng.uniform(0, 10, 200), rng.uniform(2, 5, 200)], axis=1)
    pts = np.concatenate([surface, debris])
    radius = 3.0 * characteristic_spacing(pts)
    threshold, _ = radius_min_neighbors(pts, radius, cap=CAP, share=SHARE)
    counts = cKDTree(pts).query_ball_point(debris, r=radius, return_length=True)
    assert (counts < threshold).mean() > 0.95


def test_the_threshold_is_deterministic_and_clamped():
    pts = _random_plane(50000, 4)
    radius = 3.0 * characteristic_spacing(pts)
    assert radius_min_neighbors(pts, radius, cap=CAP, share=SHARE) == radius_min_neighbors(pts, radius, cap=CAP, share=SHARE)
    assert radius_min_neighbors(pts, radius, cap=CAP, share=0.0)[0] == 3  # floor
    assert radius_min_neighbors(pts, radius, cap=CAP, share=10.0)[0] == CAP
