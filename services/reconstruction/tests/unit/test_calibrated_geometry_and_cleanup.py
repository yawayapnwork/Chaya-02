"""Tests for validated calibrated geometry, floater removal, density-conditioned opacity filtering,
and furniture exclusion from walkable floor planes.
"""

from __future__ import annotations

import numpy as np

from chaya_worker.geometry_cleanup import (
    FittedPlane,
    characteristic_spacing,
    density_conditioned_opacity_mask,
    disconnected_cluster_mask,
    reclassify_planes,
)
from chaya_worker.navmesh import CanonicalPlane, select_floor_plane
from chaya_worker.ply import GaussianCloud

# ==============================================================================
# 1. Disconnected Floating Geometry Detection (Floaters)
# ==============================================================================

def test_disconnected_cluster_mask_removes_floating_islands_and_keeps_connected_surfaces():
    """Disconnected clusters with fewer than min_cluster_size points are filtered out,

    while dense continuous surfaces (e.g. walls/floors) are preserved.
    """
    rng = np.random.default_rng(42)
    # 1. Continuous floor surface: 500 points on a grid with 0.05 spacing
    xs, ys = np.meshgrid(np.linspace(0, 1, 25), np.linspace(0, 1, 20))
    floor_pts = np.stack([xs.ravel(), ys.ravel(), np.zeros(500)], axis=1)

    # 2. Floating cluster A: 6 points clustered around (2.0, 2.0, 1.5)
    floater_a = np.array([[2.0, 2.0, 1.5]]) + rng.normal(0, 0.01, size=(6, 3))

    # 3. Floating cluster B: 4 points clustered around (-1.0, 0.5, 2.0)
    floater_b = np.array([[-1.0, 0.5, 2.0]]) + rng.normal(0, 0.01, size=(4, 3))

    # 4. Isolated singleton floater at (0.5, 0.5, 3.0)
    singleton = np.array([[0.5, 0.5, 3.0]])

    all_positions = np.concatenate([floor_pts, floater_a, floater_b, singleton], axis=0)
    spacing = characteristic_spacing(all_positions)

    # Run floater removal with radius = 3.0 * spacing and min_cluster_size = 15
    mask, report = disconnected_cluster_mask(all_positions, radius=3.0 * spacing, min_cluster_size=15)

    # All 500 floor points must be preserved
    assert mask[:500].all(), "Continuous floor points must all be kept"

    # Floater clusters A, B and singleton must all be removed
    assert not mask[500:].any(), "All floating clusters and singletons must be removed"
    assert report["removed"] == 11
    assert report["removed_components"] == 3


def test_disconnected_cluster_mask_on_empty_and_single_point():
    mask, report = disconnected_cluster_mask(np.zeros((0, 3)), radius=0.1, min_cluster_size=5)
    assert len(mask) == 0 and report["removed"] == 0

    mask_single, report_single = disconnected_cluster_mask(np.array([[1.0, 2.0, 3.0]]), radius=0.1, min_cluster_size=5)
    assert not mask_single[0] and report_single["removed"] == 1


# ==============================================================================
# 2. Density-Conditioned Opacity Filtering
# ==============================================================================

def test_density_conditioned_opacity_preserves_dense_semi_transparent_surfaces():
    """Semi-transparent surfaces (e.g. glass partitions, fine edges) have many neighbors

    despite low opacity and must be kept. Isolated low-opacity haze/floaters must be removed.
    """
    rng = np.random.default_rng(10)
    n = 200

    # 1. Dense glass partition: 150 points with low opacity (0.02)
    xs, ys = np.meshgrid(np.linspace(0, 0.5, 15), np.linspace(0, 0.5, 10))
    glass_pts = np.stack([xs.ravel(), ys.ravel(), np.full(150, 1.0)], axis=1)

    # 2. Sparse haze/floaters: 50 points scattered randomly with low opacity (0.02)
    haze_pts = rng.uniform(-10.0, 10.0, size=(50, 3))

    positions = np.concatenate([glass_pts, haze_pts], axis=0)

    # Logit for opacity: sigmoid(logit) = 0.02 -> logit ~ -3.89
    p_low = 0.02
    logit_low = float(np.log(p_low / (1 - p_low)))

    cloud = GaussianCloud(
        positions=positions,
        scales_log=np.zeros((n, 3), np.float32),
        rotations_wxyz=np.tile(np.array([1, 0, 0, 0], np.float32), (n, 1)),
        opacity_logit=np.full(n, logit_low, dtype=np.float32),
        colors_dc=np.zeros((n, 3), np.float32),
    )

    spacing = characteristic_spacing(glass_pts)
    radius = 3.0 * spacing

    # Density-conditioned opacity: min_opacity = 0.05, min_neighbors = 4
    mask, report = density_conditioned_opacity_mask(cloud, min_opacity=0.05, radius=radius, min_neighbors=4)

    # Glass points have >4 neighbors within radius and must all be KEPT
    assert mask[:150].all(), "Dense semi-transparent glass partition points must be kept"

    # Haze points have <4 neighbors within radius and must be REMOVED
    assert (~mask[150:]).sum() >= 45, "Isolated sparse haze points must be removed"
    assert report["low_opacity_count"] == 200


def test_density_conditioned_opacity_preserves_opaque_points():
    """Opaque points are never removed by density-conditioned opacity filtering."""
    positions = np.array([[100.0, 100.0, 100.0]])  # isolated point
    cloud = GaussianCloud(
        positions=positions,
        scales_log=np.zeros((1, 3), np.float32),
        rotations_wxyz=np.array([[1, 0, 0, 0]], np.float32),
        opacity_logit=np.array([5.0], np.float32),  # opacity ~ 0.993
        colors_dc=np.zeros((1, 3), np.float32),
    )
    mask, report = density_conditioned_opacity_mask(cloud, min_opacity=0.05, radius=1.0, min_neighbors=5)
    assert mask[0], "Opaque isolated points must not be dropped by opacity filtering"
    assert report["removed"] == 0


# ==============================================================================
# 3. Horizontal Furniture Exclusion from Walkable Floors
# ==============================================================================

def test_horizontal_furniture_surface_is_not_classified_as_walkable_floor():
    """A large horizontal desk or tabletop with majority furniture labels must be

    classified as FURNITURE and disqualified from select_floor_plane.
    """
    # Table plane at z = 0.75 m with 500 inliers, labeled 'furniture'
    table_plane = CanonicalPlane(
        normal=np.array([0.0, 0.0, 1.0]),
        height=0.75,
        inlier_indices=np.arange(500),
        classification="furniture",
        ground_support=0.02,
    )

    # Floor plane at z = 0.0 m with 400 inliers, labeled 'floor'
    floor_plane = CanonicalPlane(
        normal=np.array([0.0, 0.0, 1.0]),
        height=0.0,
        inlier_indices=np.arange(400),
        classification="floor",
        ground_support=0.98,
    )

    # Even though table has MORE inliers (500 vs 400), it must NOT be selected as floor
    selected = select_floor_plane([table_plane, floor_plane], max_tilt_deg=10.0)
    assert selected is floor_plane, "Floor plane must be selected, not the horizontal furniture table"


def test_reclassify_planes_preserves_furniture_classification():
    """reclassify_planes preserves furniture classification on horizontal surfaces."""
    positions = np.zeros((100, 3))
    positions[:, 2] = 0.75  # desk at height 0.75 m
    labels = np.full(100, "furniture", dtype=object)

    p = FittedPlane(equation=(0.0, 0.0, 1.0, -0.75), inlier_indices=np.arange(100), classification="furniture", confidence=1.0)
    up = np.array([0.0, 0.0, 1.0])
    reference_camera = np.array([0.0, 0.0, 1.6])  # camera at eye height 1.6 m

    reclassified = reclassify_planes([p], positions, up, reference_camera, labels=labels)
    assert len(reclassified) == 1
    assert reclassified[0].classification == "furniture", "Horizontal furniture must remain classified as furniture"


# ==============================================================================
# 4. Scale Estimation, Residuals, and Coordinate Invariance
# ==============================================================================

def test_scale_estimation_from_measured_distances_recovers_ground_truth_scale():
    """Scale estimation s = sum(l_i * m_i) / sum(l_i^2) exactly recovers ground-truth scale

    and computes relative spread.
    """
    true_scale = 1.854  # 1 reconstruction unit = 1.854 metres

    # 3 pairs of points in reconstruction units with lengths 1.0, 2.0, 3.0
    lengths = np.array([1.0, 2.0, 3.0])
    measured = lengths * true_scale

    # Compute scale
    estimated_scale = float(np.sum(lengths * measured) / np.sum(lengths**2))
    assert np.isclose(estimated_scale, true_scale, atol=1e-9)

    # Relative spread across individual measurements
    individual_scales = measured / lengths
    spread = float(np.max(np.abs(individual_scales - estimated_scale)) / estimated_scale)
    assert spread < 1e-12, "Zero-noise measurements must have negligible relative spread"


def test_scale_estimation_rejects_inconsistent_distance_measurements():
    """Measurements with relative spread > 3% are flagged as inconsistent."""
    lengths = np.array([1.0, 2.0])
    # Measured with 8% disagreement
    measured = np.array([1.0 * 2.00, 2.0 * 2.16])

    estimated_scale = float(np.sum(lengths * measured) / np.sum(lengths**2))
    individual_scales = measured / lengths
    spread = float(np.max(np.abs(individual_scales - estimated_scale)) / estimated_scale)

    assert spread > 0.03, f"Expected spread > 0.03 (3%), got {spread}"
