"""Pure geometry tests for chaya_worker.region_splice -- no Open3D, no external tools. Real fixtures: a
synthetic venue-wide GaussianCloud laid out on a grid, and a polygon selecting one sub-region of it.
"""

from __future__ import annotations

import numpy as np
import pytest

from chaya_worker.frames import Similarity
from chaya_worker.ply import GaussianCloud
from chaya_worker.region_splice import (
    SpliceRejected,
    distance_to_polygon_edge,
    point_in_polygon,
    polygon_area,
    splice_region,
    transform_gaussians,
)
from tests.rescan_scene import cloud as scene_cloud
from tests.rescan_scene import room

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


def _region(positions: np.ndarray) -> GaussianCloud:
    n = len(positions)
    return GaussianCloud(
        positions=np.asarray(positions, dtype=np.float32),
        scales_log=np.ones((n, 3), dtype=np.float32),  # distinguishable from the global cloud's zeros
        rotations_wxyz=np.tile(np.array([1.0, 0, 0, 0], dtype=np.float32), (n, 1)),
        opacity_logit=np.ones(n, dtype=np.float32) * 5.0,
        colors_dc=np.ones((n, 3), dtype=np.float32),
    )


def test_transform_gaussians_scales_positions_and_gaussian_extents_with_a_similarity():
    cloud = _grid_cloud(nx=2, ny=2)
    t = Similarity(2.5, np.eye(3), np.array([1.0, 0.0, 0.0]))
    moved = transform_gaussians(cloud, t)
    np.testing.assert_allclose(moved.positions, 2.5 * cloud.positions + [1.0, 0.0, 0.0], atol=1e-5)
    np.testing.assert_allclose(moved.scales(), 2.5 * cloud.scales(), rtol=1e-6)


SPLICE = {"z_margin_m": 0.2, "seam_band_m": 0.15, "max_seam_step_m": 0.03}
ROOM_POLYGON = np.array([[1.0, 0.5], [3.0, 0.5], [3.0, 3.5], [1.0, 3.5]])  # the floor and the 0.8 m box; no wall


def _rows(cloud: GaussianCloud) -> np.ndarray:
    """Every Gaussian as one row, sorted: two clouds with the same rows hold the same Gaussians, in any order."""
    rows = np.hstack([cloud.positions, cloud.scales_log, cloud.rotations_wxyz, cloud.opacity_logit[:, None], cloud.colors_dc])
    return rows[np.lexsort(rows.T[::-1])]


def _venue_and_ceiling():
    """The synthetic room, plus a ceiling at 2.5 m the re-scan below 2 m never sees."""
    rng = np.random.default_rng(11)
    ceiling = np.column_stack([rng.random((4000, 2)) * 4, np.full(4000, 2.5)])
    return scene_cloud(np.vstack([room(1, density=300), ceiling]), seed=1)


def test_splice_replaces_the_overlapping_geometry_and_nothing_else():
    """The re-scan covers the region and 1 m beyond it (review V-2's case). Inside the replaced volume, the venue's old
    Gaussians are gone and the re-scan's are in. Outside the polygon, the venue is unchanged, in order, and none of the
    re-scan's out-of-polygon Gaussians were appended. Inside the polygon but above the re-scan (the ceiling), the venue's
    geometry is kept."""
    venue = _venue_and_ceiling()
    rescan_points = room(2, density=300)
    rescan_points = rescan_points[rescan_points[:, 2] < 2.0]  # the operator re-captured up to 2 m
    rescan = scene_cloud(rescan_points, seed=2)

    result = splice_region(venue, rescan, ROOM_POLYGON, Similarity.identity(), **SPLICE)
    merged, report = result.merged, result.report
    v_in = point_in_polygon(venue.positions[:, :2].astype(np.float64), ROOM_POLYGON)
    r_in = point_in_polygon(rescan.positions[:, :2].astype(np.float64), ROOM_POLYGON)

    # removed: exactly the venue Gaussians inside the polygon and within the replaced volume. The re-scan holds the floor and
    # the 0.8 m box there, so the volume is the minimum height above the floor (2 m), not the box top + margin
    z_top = report["volume"]["z_max_m"]
    assert report["volume"]["observed_z_m"][1] == pytest.approx(0.8, abs=0.01)
    assert z_top == pytest.approx(2.0, abs=0.01) and report["volume"]["z_max_from"] == "min_height"
    expected_removed = np.flatnonzero(v_in & (venue.positions[:, 2] <= z_top))
    np.testing.assert_array_equal(result.removed_global_indices, expected_removed)
    # unrelated geometry: the ceiling inside the polygon survives
    ceiling_inside = v_in & (venue.positions[:, 2] > 2.4)
    assert ceiling_inside.sum() > 100 and report["kept_inside_polygon_outside_volume"] == int(ceiling_inside.sum())
    # outside the polygon: unchanged, in order, and first
    kept = venue.subset(np.setdiff1d(np.arange(len(venue)), expected_removed))
    np.testing.assert_array_equal(merged.positions[: len(kept)], kept.positions)
    np.testing.assert_array_equal(merged.colors_dc[: len(kept)], kept.colors_dc)
    # added: exactly the re-scan inside the polygon, and nothing the re-scan saw outside it
    np.testing.assert_array_equal(result.added_region_indices, np.flatnonzero(r_in))
    assert report["discarded_from_region_outside_polygon"] == int((~r_in).sum()) > 0
    tail = merged.positions[len(kept):]
    assert point_in_polygon(tail[:, :2].astype(np.float64), ROOM_POLYGON).all()
    assert len(merged) == len(kept) + int(r_in.sum()) == report["total_after"]
    # no duplicated geometry: the merged cloud's density inside the polygon is the re-scan's, not venue + re-scan
    m_in = point_in_polygon(merged.positions[:, :2].astype(np.float64), ROOM_POLYGON) & (merged.positions[:, 2] <= z_top)
    assert int(m_in.sum()) == int(r_in.sum())
    # and the seam was measured, and is invisible
    assert report["seam"]["step_m"] is not None and report["seam"]["step_m"] < 0.005


def test_moved_furniture_is_replaced_and_every_gaussian_outside_the_region_is_kept_bit_for_bit():
    """The box was moved 1.4 m within the region between the two captures. The merged venue has the box only where the
    re-scan saw it, and every venue Gaussian outside the polygon comes through unchanged in every attribute (position,
    scale, orientation, opacity, colour) and in its original order."""
    venue = _venue_and_ceiling()
    points = room(2, density=300)
    old_box = (points[:, 0] >= 1.5) & (points[:, 0] <= 2.5) & (points[:, 1] >= 1.0) & (points[:, 1] <= 1.6) & (points[:, 2] > 0.01)
    moved = points.copy()
    moved[old_box, 1] += 1.4
    rescan = scene_cloud(moved, seed=2)

    result = splice_region(venue, rescan, ROOM_POLYGON, Similarity.identity(), **SPLICE)
    merged = result.merged.positions.astype(np.float64)

    def in_box(p, y0, y1):
        # the box's faces and top (it is sampled on its surface), not the floor under it
        return (p[:, 0] > 1.45) & (p[:, 0] < 2.55) & (p[:, 1] > y0 - 0.05) & (p[:, 1] < y1 + 0.05) & (p[:, 2] > 0.05) & (p[:, 2] < 0.85)

    assert in_box(venue.positions.astype(np.float64), 1.0, 1.6).sum() > 50, "the venue had the box at its old place"
    assert not in_box(merged, 1.0, 1.6).any(), "nothing of the box is left at its old place"
    assert in_box(merged, 2.4, 3.0).sum() == in_box(moved, 2.4, 3.0).sum() > 50, "the box is where the re-scan saw it"

    outside = ~point_in_polygon(venue.positions[:, :2].astype(np.float64), ROOM_POLYGON)
    kept_outside = venue.subset(outside)
    merged_outside = result.merged.subset(~point_in_polygon(merged[:, :2], ROOM_POLYGON))
    for field in ("positions", "scales_log", "rotations_wxyz", "opacity_logit", "colors_dc"):
        np.testing.assert_array_equal(getattr(merged_outside, field), getattr(kept_outside, field), err_msg=field)


def test_a_noop_rescan_changes_nothing():
    """Re-scanning a region with exactly the venue's own Gaussians must give back the venue: the same Gaussians, no more,
    no fewer."""
    venue = _venue_and_ceiling()
    v_in = point_in_polygon(venue.positions[:, :2].astype(np.float64), ROOM_POLYGON) & (venue.positions[:, 2] < 2.0)
    same = venue.subset(v_in)
    result = splice_region(venue, same, ROOM_POLYGON, Similarity.identity(), **SPLICE)
    assert len(result.merged) == len(venue)
    np.testing.assert_array_equal(_rows(result.merged), _rows(venue))
    assert result.report["seam"]["step_m"] == pytest.approx(0.0, abs=1e-6)


def test_a_rescan_that_does_not_meet_the_kept_surface_is_refused_as_a_seam():
    """A re-scan 8 cm too high meets the kept floor with a step at the region edge. Refused, not merged."""
    venue = _venue_and_ceiling()
    raised = room(2, density=300) + np.array([0, 0, 0.08])
    with pytest.raises(SpliceRejected, match="seam"):
        splice_region(venue, scene_cloud(raised, seed=2), ROOM_POLYGON, Similarity.identity(), **SPLICE)


def test_splice_tests_the_volume_in_canonical_coordinates_not_reconstruction_units():
    """The clouds are in a reconstruction frame where 0.5 units = 1 m, shifted by 10 m. The polygon is canonical metres."""
    to_canonical = Similarity(2.0, np.eye(3), np.array([10.0, 0.0, 0.0]))
    venue = scene_cloud(to_canonical.inverse().apply(room(1, density=200)), seed=1)
    rescan = scene_cloud(to_canonical.inverse().apply(room(2, density=200)), seed=2)
    result = splice_region(venue, rescan, ROOM_POLYGON, to_canonical, **SPLICE)
    canonical = to_canonical.apply(venue.positions)
    in_canonical = np.flatnonzero(point_in_polygon(canonical[:, :2], ROOM_POLYGON) & (canonical[:, 2] <= result.report["volume"]["z_max_m"]))
    np.testing.assert_array_equal(result.removed_global_indices, in_canonical)
    raw = point_in_polygon(venue.positions[:, :2].astype(np.float64), ROOM_POLYGON)
    assert not raw.any(), "tested in reconstruction units the polygon would select nothing: the frame matters"
    assert result.report["removed_from_global"] > 0 and result.report["added_from_region"] > 0


def test_splice_refuses_an_empty_region_or_one_entirely_outside_the_polygon():
    venue = _venue_and_ceiling()
    empty = GaussianCloud(np.zeros((0, 3), np.float32), np.zeros((0, 3), np.float32), np.zeros((0, 4), np.float32),
                          np.zeros(0, np.float32), np.zeros((0, 3), np.float32))
    with pytest.raises(SpliceRejected):
        splice_region(venue, empty, ROOM_POLYGON, Similarity.identity(), **SPLICE)
    with pytest.raises(SpliceRejected):
        splice_region(venue, scene_cloud(np.array([[9.0, 9.0, 0.0]] * 5)), ROOM_POLYGON, Similarity.identity(), **SPLICE)


def test_distance_to_polygon_edge():
    d = distance_to_polygon_edge(np.array([[0.5, 0.5], [0.5, 0.1], [2.0, 0.5]]), SQUARE)
    np.testing.assert_allclose(d, [0.5, 0.1, 1.0])


# ---- unintended deletion: nothing is removed that the re-scan did not observe ------------------------------------------


def test_a_rescan_covering_half_the_region_is_refused_instead_of_deleting_the_other_half():
    """The operator walked only the near half of the polygon. Before the coverage gate the splice succeeded, with a measured
    (perfect) seam on the near side, and every venue Gaussian in the far half was deleted with nothing in its place."""
    venue = _venue_and_ceiling()
    points = room(2, density=300)
    near_half = scene_cloud(points[(points[:, 1] < 2.0) & (points[:, 2] < 2.0)], seed=2)
    with pytest.raises(SpliceRejected, match="observed nothing") as rejected:
        splice_region(venue, near_half, ROOM_POLYGON, Similarity.identity(), **SPLICE)
    assert "%" in str(rejected.value)


def test_a_gap_along_the_region_edge_is_refused_as_an_unmeasured_seam():
    """The re-scan stops 30 cm short of the polygon edge all round: a ring of venue floor is deleted. The ring is too thin for
    the coverage gate (the cells next to it are observed) but the seam cannot be measured where the venue had geometry."""
    venue = _venue_and_ceiling()
    points = room(2, density=300)
    inner = (points[:, 0] > 1.3) & (points[:, 0] < 2.7) & (points[:, 1] > 0.8) & (points[:, 1] < 3.2) & (points[:, 2] < 2.0)
    with pytest.raises(SpliceRejected, match="not measured|observed nothing"):
        splice_region(venue, scene_cloud(points[inner], seed=2), ROOM_POLYGON, Similarity.identity(), **SPLICE)


def test_a_complete_rescan_reports_full_coverage_and_its_invariants():
    venue = _venue_and_ceiling()
    rescan = room(2, density=300)
    result = splice_region(venue, scene_cloud(rescan[rescan[:, 2] < 2.0], seed=2), ROOM_POLYGON, Similarity.identity(), **SPLICE)
    report = result.report
    assert report["coverage"]["uncovered_fraction"] <= 0.01
    assert report["seam"]["measured"] and report["seam"]["removed_in_band"] > 0
    assert report["invariants"] == {"kept_outside_volume_unchanged": True, "added_inside_volume": True}
    assert report["net_change"] == report["added_from_region"] - report["removed_from_global"]


def test_removed_furniture_is_removed_from_the_venue():
    """A box that was taken away: the re-scan sees only floor where it stood. Before the minimum volume height the replaced
    volume ended 0.2 m above that floor, and 136 Gaussians of the old box were kept standing (and stayed an obstacle in the
    re-baked navmesh). Now it is gone, and removing it is a change, not a hole: the floor under it was observed."""
    venue = _venue_and_ceiling()
    points = room(2, density=300)
    box = (points[:, 0] >= 1.5) & (points[:, 0] <= 2.5) & (points[:, 1] >= 1.0) & (points[:, 1] <= 1.6) & (points[:, 2] > 0.01)
    rescan = scene_cloud(points[~box & (points[:, 2] < 2.0)], seed=2)
    result = splice_region(venue, rescan, ROOM_POLYGON, Similarity.identity(), **SPLICE)
    assert result.report["coverage"]["uncovered_removed"] == 0
    merged = result.merged.positions.astype(np.float64)
    gone = (merged[:, 0] > 1.55) & (merged[:, 0] < 2.45) & (merged[:, 1] > 1.05) & (merged[:, 1] < 1.55) & (merged[:, 2] > 0.1) & (merged[:, 2] < 0.85)
    assert not gone.any()


def test_coverage_counts_only_removed_geometry_without_an_observation_nearby():
    from chaya_worker.region_splice import coverage

    removed = np.array([[0.1, 0.1, 0], [0.6, 0.1, 0], [2.0, 2.0, 0], [2.1, 2.1, 0]])
    added = np.array([[0.3, 0.1, 0]])  # cell (1, 0): its neighbours are cells 0..2 in x and -1..1 in y
    c = coverage(removed, added, 0.25)
    assert c["uncovered_removed"] == 2 and c["uncovered_cells"] == 1 and c["uncovered_fraction"] == 0.5
