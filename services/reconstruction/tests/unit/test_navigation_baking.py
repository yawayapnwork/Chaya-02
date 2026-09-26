"""Pure unit tests for chaya_worker.navmesh: the canonical-frame geometry NAVIGATION_BAKING hands to Recast, and the
routing graphs built from the Detour navmesh's polygons. No external tools: the real Recast build over real geometry is
tests/navmesh/test_recast_fixture.py. The graphs here are hand-made polygon sets -- synthetic unit inputs, never
presented as navmesh output."""

from __future__ import annotations

import numpy as np
import pytest

from chaya_worker.navmesh import (
    INVALID_GEOMETRY,
    CanonicalPlane,
    Link,
    NavGeometry,
    NavmeshError,
    Polygon,
    build_routing_graphs,
    geometry_from_reconstruction,
    obstacle_band_mask,
    point_in_polygon_xy,
    polygon_centroid,
    polygon_slope_degrees,
    portal_width,
    read_obj,
    select_floor_plane,
    write_obj,
)


def _plane(normal, height, inliers):
    return CanonicalPlane(np.array(normal, dtype=float) / np.linalg.norm(normal), height, np.arange(inliers))


def _grid(x0, x1, y0, y1, step=0.05, z=0.0):
    xs, ys = np.meshgrid(np.arange(x0, x1, step) + step / 2, np.arange(y0, y1, step) + step / 2)
    return np.stack([xs.ravel(), ys.ravel(), np.full(xs.size, z)], axis=1)


def _link(neighbor, a, b):
    return Link(neighbor, (tuple(float(c) for c in a), tuple(float(c) for c in b)))


# ---- floor and obstacles --------------------------------------------------------------------------------------------


def test_select_floor_plane_takes_the_lowest_well_supported_horizontal_plane():
    floor = _plane([0, 0, 1], 0.0, 4000)
    ceiling = _plane([0, 0, 1], 2.8, 9000)
    table = _plane([0, 0, 1], 0.75, 300)  # horizontal but too little support to be the floor
    wall = _plane([1, 0, 0], 1.2, 12000)
    sub_basement_speck = _plane([0, 0, 1], -1.0, 100)
    assert select_floor_plane([ceiling, wall, table, floor, sub_basement_speck], max_tilt_deg=10) is floor


def test_select_floor_plane_refuses_when_nothing_is_horizontal_in_canonical_terms():
    tilted = _plane([0, np.sin(np.radians(30)), np.cos(np.radians(30))], 0.0, 5000)
    assert select_floor_plane([tilted, _plane([1, 0, 0], 0, 5000)], max_tilt_deg=10) is None


def test_obstacle_band_keeps_only_heights_that_block_a_walking_agent():
    pts = np.array([[0, 0, 0.1], [0, 0, 0.5], [0, 0, 1.7], [0, 0, 2.5]])  # floor clutter, knee, head, ceiling
    mask = obstacle_band_mask(pts, 0.0, agent_max_climb=0.4, agent_height=1.8)
    assert mask.tolist() == [False, True, True, False]


# ---- Recast input geometry from reconstructed points ------------------------------------------------------------------


def test_geometry_covers_only_observed_floor_cells_never_the_convex_hull():
    """An L-shaped floor (review finding N-2): the missing quadrant has no floor points, so it gets no geometry, even
    though a convex hull / Delaunay triangulation of the same points would cover it."""
    floor = np.vstack([_grid(0, 6, 0, 3), _grid(0, 3, 3, 6)])
    g = geometry_from_reconstruction(floor, np.zeros((0, 3)), 0.0, cell_m=0.2, min_points_per_cell=3, obstacle_min_height_m=1.0)
    assert not g.obstacle.any()
    centroids = g.vertices[g.triangles].mean(axis=1)
    assert len(centroids) > 0
    assert not ((centroids[:, 0] > 3.0) & (centroids[:, 1] > 3.0)).any(), "no floor geometry in the unscanned quadrant"
    area = 0.5 * np.linalg.norm(np.cross(*(g.vertices[g.triangles[:, k]] - g.vertices[g.triangles[:, 0]] for k in (1, 2))), axis=1).sum()
    assert area == pytest.approx(27.0, rel=0.02)  # 6x3 + 3x3 m^2


def test_geometry_floor_faces_point_up_in_the_canonical_frame():
    g = geometry_from_reconstruction(_grid(0, 1, 0, 1), np.zeros((0, 3)), 0.0, cell_m=0.2, min_points_per_cell=1, obstacle_min_height_m=1.0)
    v = g.vertices[g.triangles]
    normals = np.cross(v[:, 1] - v[:, 0], v[:, 2] - v[:, 0])
    assert (normals[:, 2] > 0).all() and np.allclose(normals[:, :2], 0)


def test_geometry_needs_enough_real_points_per_cell():
    sparse = np.array([[0.05, 0.05, 0.0], [1.05, 1.05, 0.0], [1.07, 1.07, 0.0], [1.09, 1.09, 0.0]])
    g = geometry_from_reconstruction(sparse, np.zeros((0, 3)), 0.0, cell_m=0.2, min_points_per_cell=3, obstacle_min_height_m=1.0)
    assert len(g.triangles) == 2, "only the one cell with three points becomes floor"


def test_geometry_turns_obstacle_points_into_blocked_boxes_from_the_floor_up():
    floor = _grid(0, 4, 0, 4)
    furniture = _grid(1.0, 2.0, 1.0, 2.0, step=0.1, z=0.6)
    g = geometry_from_reconstruction(floor, furniture, 0.0, cell_m=0.2, min_points_per_cell=3, obstacle_min_height_m=1.0)
    blocked = g.vertices[np.unique(g.triangles[g.obstacle])]
    assert g.obstacle.sum() == 12 * 25  # 5x5 cells, a closed 12-triangle box each
    assert blocked[:, 2].min() == 0.0 and blocked[:, 2].max() == pytest.approx(1.0), "raised to the minimum obstacle height"
    assert blocked[:, 0].min() == pytest.approx(1.0) and blocked[:, 0].max() == pytest.approx(2.0)


def test_nav_geometry_validation_is_invalid_geometry():
    with pytest.raises(NavmeshError) as exc:
        NavGeometry(np.array([[0, 0, 0], [1, 0, np.nan], [0, 1, 0]]), np.array([[0, 1, 2]])).validate()
    assert exc.value.code == INVALID_GEOMETRY
    with pytest.raises(NavmeshError):
        NavGeometry(np.zeros((3, 3)), np.array([[0, 1, 5]])).validate()
    with pytest.raises(NavmeshError):
        NavGeometry(np.zeros((0, 3)), np.zeros((0, 3), dtype=int)).validate()
    with pytest.raises(NavmeshError, match="horizontal extent"):  # a wall seen edge-on: no footprint at all
        NavGeometry(np.array([[0, 0, 0], [0, 0, 1], [0, 0, 2]]), np.array([[0, 1, 2]])).validate()


def test_obj_round_trip_keeps_blocked_geometry(tmp_path):
    g = NavGeometry(np.array([[0, 0, 0], [1, 0, 0], [1, 1, 0], [0, 1, 0], [0, 0, 1]], dtype=float),
                    np.array([[0, 1, 2], [0, 2, 3], [0, 1, 4]]), np.array([False, False, True]))
    write_obj(tmp_path / "g.obj", g, header="test")
    back = read_obj(tmp_path / "g.obj")
    np.testing.assert_allclose(back.vertices, g.vertices)
    assert back.obstacle.tolist() == [False, False, True]
    assert sorted(map(tuple, back.triangles.tolist())) == sorted(map(tuple, g.triangles.tolist()))


# ---- polygons and routing graphs (hand-made polygons: unit inputs only) ------------------------------------------------


def test_polygon_slope_degrees_flat_vertical_and_ramp():
    flat = Polygon(0, [(0, 0, 0), (1, 0, 0), (1, 1, 0)])
    vertical = Polygon(1, [(0, 0, 0), (0, 0, 1), (1, 0, 1)])
    ramp = Polygon(2, [(0, 0, 0), (1, 0, 0), (1, 1, 0.5), (0, 1, 0.5)])
    assert polygon_slope_degrees(flat) == pytest.approx(0.0, abs=1e-6)
    assert polygon_slope_degrees(vertical) == pytest.approx(90.0, abs=1e-6)
    assert polygon_slope_degrees(ramp) == pytest.approx(np.degrees(np.arctan(0.5)), abs=1e-6)


def test_portal_width_is_the_detour_portal_length():
    assert portal_width(_link(1, (1, 0, 0), (1, 0.8, 0))) == pytest.approx(0.8)


def test_point_in_polygon_xy():
    square = Polygon(0, [(0, 0, 0), (2, 0, 0), (2, 2, 0), (0, 2, 0)])
    assert point_in_polygon_xy(np.array([1.0, 1.0, 5.0]), square)
    assert not point_in_polygon_xy(np.array([2.5, 1.0, 0.0]), square)


def test_build_routing_graphs_excludes_stairs_from_step_free_but_not_standard():
    flat_a = Polygon(0, [(0, 0, 0), (1, 0, 0), (1, 1, 0), (0, 1, 0)], [_link(1, (1, 0, 0), (1, 1, 0))])
    flat_b = Polygon(1, [(1, 0, 0), (2, 0, 0), (2, 1, 0), (1, 1, 0)],
                     [_link(0, (1, 1, 0), (1, 0, 0)), _link(2, (2, 0, 0), (2, 1, 0))])
    stairs = Polygon(2, [(2, 0, 0), (3, 0, 3), (3, 1, 3), (2, 1, 0)], [_link(1, (2, 1, 0), (2, 0, 0))])

    graphs = build_routing_graphs([flat_a, flat_b, stairs], max_ramp_slope_deg=5.0)
    assert len(graphs["STANDARD"]["edges"]) == 2  # a-b and b-stairs, each once although both sides link
    assert len(graphs["STEP_FREE"]["edges"]) == 1  # only a-b
    assert len(graphs["STANDARD"]["nodes"]) == len(graphs["STEP_FREE"]["nodes"]) == 3
    assert all(e["min_clearance_m"] == pytest.approx(1.0) for e in graphs["STANDARD"]["edges"])


def test_build_routing_graphs_keeps_a_gentle_ramp_in_step_free():
    flat = Polygon(0, [(0, 0, 0), (1, 0, 0), (1, 1, 0), (0, 1, 0)], [_link(1, (1, 0, 0), (1, 1, 0))])
    gentle_ramp = Polygon(1, [(1, 0, 0), (2, 0, 0.05), (2, 1, 0.05), (1, 1, 0)], [_link(0, (1, 1, 0), (1, 0, 0))])
    graphs = build_routing_graphs([flat, gentle_ramp], max_ramp_slope_deg=5.0)
    assert len(graphs["STEP_FREE"]["edges"]) == 1


def test_polygon_centroid_is_the_real_vertex_average():
    centroid = polygon_centroid(Polygon(0, [(0, 0, 0), (2, 0, 0), (1, 3, 0)]))
    assert np.allclose(centroid, [1.0, 1.0, 0.0])
