"""Pure unit tests for chaya_worker.navmesh: the surface model NAVIGATION_BAKING builds from the cleaned reconstruction,
the canonical-frame geometry it hands to Recast, and the routing graphs built from the Detour navmesh's polygons. No
external tools: the real Recast build over real geometry is tests/navmesh. The polygons here are hand-made -- synthetic
unit inputs, never presented as navmesh output."""

from __future__ import annotations

import numpy as np
import pytest

from chaya_worker.navmesh import (
    INVALID_GEOMETRY,
    NO_WALKABLE_SURFACE,
    POLYFLAG_LEVEL_CHANGE,
    POLYFLAG_WALK,
    CanonicalPlane,
    Link,
    NavGeometry,
    NavmeshError,
    Polygon,
    build_routing_graphs,
    build_surface_model,
    edge_grade_degrees,
    edge_rise_run,
    geometry_from_surface,
    point_in_polygon_xy,
    polygon_centroid,
    polygon_slope_degrees,
    portal_width,
    read_obj,
    select_floor_plane,
    write_obj,
)

SURFACE = {"cell_m": 0.1, "min_points_per_cell": 3, "ground_band_m": 0.05, "agent_max_climb_m": 0.4, "agent_height_m": 1.8,
           "step_min_rise_m": 0.03, "max_level_above_reference_m": 2.0, "accessible_width_m": 0.9, "width_cap_m": 2.0}


def _plane(normal, height, inliers):
    return CanonicalPlane(np.array(normal, dtype=float) / np.linalg.norm(normal), height, np.arange(inliers))


def _grid(x0, x1, y0, y1, step=0.05, z=0.0):
    xs, ys = np.meshgrid(np.arange(x0, x1 - 1e-9, step) + step / 2, np.arange(y0, y1 - 1e-9, step) + step / 2)
    zs = z(xs.ravel(), ys.ravel()) if callable(z) else np.full(xs.size, z)
    return np.stack([xs.ravel(), ys.ravel(), zs], axis=1)


def _link(neighbor, a, b):
    return Link(neighbor, (tuple(float(c) for c in a), tuple(float(c) for c in b)))


def _surface(ground, obstacles=None, **overrides):
    return build_surface_model(ground, np.zeros((0, 3)) if obstacles is None else obstacles, reference_z=0.0,
                               **{**SURFACE, **overrides})


def _far_surface():
    """A surface with nothing under the hand-made polygons below: build_routing_graphs then falls back to their own
    geometry for heights and slopes, and measures no clearance."""
    return _surface(_grid(100, 101, 100, 101))


def _quad(i, x0, x1, z0=0.0, z1=None, links=(), flags=POLYFLAG_WALK):
    z1 = z0 if z1 is None else z1
    return Polygon(i, [(x0, 0, z0), (x1, 0, z1), (x1, 1, z1), (x0, 1, z0)], list(links), flags=flags)


# ---- the floor plane --------------------------------------------------------------------------------------------------


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


# ---- the surface model --------------------------------------------------------------------------------------------------


def test_unobserved_ground_stays_a_hole_never_the_convex_hull():
    """An L-shaped floor: the missing quadrant has no floor points, so it gets no geometry."""
    s = _surface(np.vstack([_grid(0, 6, 0, 3), _grid(0, 3, 3, 6)]))
    g = geometry_from_surface(s, obstacle_min_height_m=1.0)
    assert not g.obstacle.any() and not g.level_change.any()
    centroids = g.vertices[g.triangles].mean(axis=1)
    assert not ((centroids[:, 0] > 3.0) & (centroids[:, 1] > 3.0)).any(), "no floor geometry in the unscanned quadrant"
    area = 0.5 * np.linalg.norm(np.cross(*(g.vertices[g.triangles[:, k]] - g.vertices[g.triangles[:, 0]] for k in (1, 2))), axis=1).sum()
    assert area == pytest.approx(27.0, rel=0.02)  # 6x3 + 3x3 m^2


def test_ground_faces_point_up_in_the_canonical_frame():
    g = geometry_from_surface(_surface(_grid(0, 1, 0, 1)), obstacle_min_height_m=1.0)
    v = g.vertices[g.triangles]
    normals = np.cross(v[:, 1] - v[:, 0], v[:, 2] - v[:, 0])
    assert (normals[:, 2] > 0).all() and np.allclose(normals[:, :2], 0)


def test_a_cell_needs_a_dense_band_of_real_points_to_be_ground():
    sparse = np.array([[0.05, 0.05, 0.0], [1.05, 1.05, 0.0], [1.07, 1.07, 0.0], [1.09, 1.09, 0.0]])
    s = _surface(sparse)
    assert int(np.isfinite(s.ground_z).sum()) == 1, "only the one cell with three points is ground"


def test_ground_is_the_lowest_dense_band_not_a_stray_point_or_a_table_top():
    cell = np.array([[0.05, 0.05, z] for z in (-0.3, 0.0, 0.01, 0.02, 0.75, 0.75, 0.76, 0.76)])
    s = _surface(cell)
    assert s.ground_z[0, 0] == pytest.approx(0.01)


def test_no_ground_in_the_floors_height_range_is_no_walkable_surface():
    with pytest.raises(NavmeshError) as exc:
        _surface(_grid(0, 1, 0, 1, z=5.0))
    assert exc.value.code == NO_WALKABLE_SURFACE


def test_obstacles_are_what_stands_in_the_agents_band_above_the_local_ground():
    """Measured from the ground under each obstacle point, not from the one floor plane: a box on the raised platform
    blocks from the platform up; low clutter is stepped over; the ceiling and a lintel above head height do not block."""
    ground = np.vstack([_grid(0, 2, 0, 2), _grid(2, 4, 0, 2, z=0.5)])  # a 0.5 m platform (a ledge: more than the climb)
    obstacles = np.vstack([
        _grid(0.5, 0.7, 0.5, 0.7, step=0.05, z=0.25),  # clutter 0.25 m tall: below the climb, stepped over
        _grid(1.0, 1.2, 1.0, 1.2, step=0.05, z=1.0),  # a box 1 m tall: blocks
        _grid(3.0, 3.2, 1.0, 1.2, step=0.05, z=0.8),  # 0.3 m above the platform: stepped over there
        _grid(3.4, 3.6, 1.0, 1.2, step=0.05, z=1.5),  # 1.0 m above the platform: blocks
        _grid(0, 4, 0, 2, step=0.2, z=2.8),  # ceiling
    ])
    s = _surface(ground, obstacles)
    blocked = {(round(s.origin[0] + (i + 0.5) * 0.1, 2), round(s.origin[1] + (j + 0.5) * 0.1, 2)) for i, j in zip(*np.nonzero(s.obstacle), strict=True)}
    assert (1.05, 1.05) in blocked and (3.45, 1.05) in blocked
    assert (0.55, 0.55) not in blocked and (3.05, 1.05) not in blocked
    assert int(s.obstacle.sum()) == 8, "two 0.2 m boxes; nothing for the clutter, the low box on the platform or the ceiling"


def test_an_obstacle_with_no_ground_seen_under_it_still_blocks():
    """A sofa hides the floor under it: those cells have no ground, so its band is measured from the neighbouring ground."""
    ground = _grid(0, 3, 0, 3)
    ground = ground[~((ground[:, 0] > 1) & (ground[:, 0] < 2) & (ground[:, 1] > 1) & (ground[:, 1] < 2))]
    sofa = _grid(1.0, 2.0, 1.0, 2.0, step=0.05, z=0.6)
    s = _surface(ground, sofa)
    i, j = s.cell_of(np.array([1.5, 1.5]))
    assert s.obstacle[i, j] and not np.isfinite(s.ground_z[i, j])
    g = geometry_from_surface(s, obstacle_min_height_m=1.0)
    blocked = g.vertices[np.unique(g.triangles[g.obstacle])]
    assert blocked[:, 2].max() == pytest.approx(1.0) and blocked[:, 2].min() < 0.0, "a box from below the ground"


def test_a_step_is_a_level_change_a_ramp_is_a_slope():
    """Two lanes rising 0.17 m: one in a single step at x = 2, one up a 1:14 ramp. The step's cells are level changes;
    the ramp is a continuous sloped surface of the real slope."""
    step = _grid(0, 4, 0, 1, z=lambda x, y: np.where(x >= 2.0, 0.17, 0.0))
    ramp = _grid(0, 4, 2, 3, z=lambda x, y: np.clip((x - 0.5) / 14, 0.0, 0.17))
    s = _surface(np.vstack([step, ramp]))
    lc_x = s.origin[0] + (np.nonzero(s.level_change)[0] + 0.5) * 0.1
    lc_y = s.origin[1] + (np.nonzero(s.level_change)[1] + 0.5) * 0.1
    assert set(np.round(lc_x, 2)) == {1.95, 2.05} and (lc_y < 1.0).all(), "both cells beside the riser, step lane only"
    slopes = s.cell_slope_deg()
    i, j = s.cell_of(np.array([1.5, 2.5]))
    assert slopes[i, j] == pytest.approx(np.degrees(np.arctan(1 / 14)), abs=0.3)
    i, j = s.cell_of(np.array([1.0, 0.5]))
    assert slopes[i, j] == pytest.approx(0.0, abs=1e-6)
    g = geometry_from_surface(s, obstacle_min_height_m=1.0)
    assert g.level_change.sum() == 2 * int(s.level_change.sum())
    on_ramp = g.vertices[(g.vertices[:, 1] > 2.0) & (g.vertices[:, 1] < 3.0) & (g.vertices[:, 0] > 0.6) & (g.vertices[:, 0] < 2.8)]
    np.testing.assert_allclose(on_ramp[:, 2], (on_ramp[:, 0] - 0.5) / 14, atol=0.006)  # continuous, at the real height


def test_a_ledge_higher_than_the_climb_is_not_a_level_change():
    s = _surface(_grid(0, 2, 0, 1, z=lambda x, y: np.where(x >= 1.0, 0.6, 0.0)))
    assert not s.level_change.any()


def test_width_is_measured_on_the_obstacle_geometry():
    """A 1.1 m gap between two walls: a body ~1.1 m wide fits centred in it, none beside the wall; a 0.8 m gap fits
    none of the accessible width."""
    ground = _grid(0, 6, 0, 4)
    walls = np.vstack([_grid(2.9, 3.1, 0, 1.5, step=0.05, z=1.0), _grid(2.9, 3.1, 2.6, 4, step=0.05, z=1.0)])
    s = _surface(ground, walls)
    i, j = s.cell_of(np.array([3.0, 2.05]))
    assert s.width_m[i, j] == pytest.approx(1.1, abs=0.11)
    i, j = s.cell_of(np.array([2.95, 1.55]))
    assert s.width_m[i, j] < 0.9
    narrow = _surface(ground, np.vstack([_grid(2.9, 3.1, 0, 1.6, step=0.05, z=1.0), _grid(2.9, 3.1, 2.4, 4, step=0.05, z=1.0)]))
    gap = [narrow.cell_of(np.array([3.0, y])) for y in np.arange(1.65, 2.4, 0.1)]
    assert max(narrow.width_m[i, j] for i, j in gap) < 0.9


def _rect(i, x0, x1, y0, y1):
    return Polygon(i, [(x0, y0, 0), (x1, y0, 0), (x1, y1, 0), (x0, y1, 0)])


def test_edge_clearance_is_the_doorway_width_even_inside_one_polygon():
    """Polygon A spans the 1.1 m doorway with its centre off the door's axis: no portal lies in the door, yet the best
    way from A's centre to B's must go through it, at the door's width."""
    ground = _grid(0, 8, 0, 4)
    walls = np.vstack([_grid(2.9, 3.1, 0, 1.5, step=0.05, z=1.0), _grid(2.9, 3.1, 2.6, 4, step=0.05, z=1.0)])
    s = _surface(ground, walls)
    a = Polygon(0, [(1.0, 0.6, 0), (4.5, 1.6, 0), (4.5, 2.5, 0), (1.0, 3.4, 0)])  # through the door, centre at y = 2
    b = _rect(1, 4.5, 6.5, 0.6, 3.4)
    assert s.edge_clear_width_m(a, b) == pytest.approx(1.1, abs=0.11)
    assert s.edge_clear_width_m(_rect(2, 4.5, 6.0, 0.6, 2.0), _rect(3, 4.5, 6.0, 2.0, 3.4)) > 1.5, "an open room is wide"
    beside_wall = _rect(4, 0.5, 2.85, 0.1, 0.5)  # a strip along the wall's foot
    assert s.edge_clear_width_m(beside_wall, _rect(5, 0.5, 2.85, 0.5, 0.9)) < 2.0


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
    with pytest.raises(NavmeshError, match="both an obstacle and a level change"):
        NavGeometry(np.array([[0, 0, 0], [1, 0, 0], [0, 1, 0]], dtype=float), np.array([[0, 1, 2]]), np.array([True]),
                    np.array([True])).validate()


def test_obj_round_trip_keeps_blocked_and_level_change_geometry(tmp_path):
    g = NavGeometry(np.array([[0, 0, 0], [1, 0, 0], [1, 1, 0], [0, 1, 0], [0, 0, 1]], dtype=float),
                    np.array([[0, 1, 2], [0, 2, 3], [0, 1, 4]]), np.array([False, False, True]), np.array([False, True, False]))
    write_obj(tmp_path / "g.obj", g, header="test")
    text = (tmp_path / "g.obj").read_text()
    assert "g level_change" in text and "g obstacle" in text
    back = read_obj(tmp_path / "g.obj")
    np.testing.assert_allclose(back.vertices, g.vertices)
    tris = [tuple(t) for t in back.triangles.tolist()]
    assert back.obstacle[tris.index((0, 1, 4))] and back.level_change[tris.index((0, 2, 3))]
    assert int(back.obstacle.sum()) == 1 and int(back.level_change.sum()) == 1


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
    flat_a = _quad(0, 0, 1, links=[_link(1, (1, 0, 0), (1, 1, 0))])
    flat_b = _quad(1, 1, 2, links=[_link(0, (1, 1, 0), (1, 0, 0)), _link(2, (2, 0, 0), (2, 1, 0))])
    stairs = _quad(2, 2, 3, 0.0, 3.0, links=[_link(1, (2, 1, 0), (2, 0, 0))])
    graphs = build_routing_graphs([flat_a, flat_b, stairs], max_ramp_slope_deg=5.0, surface=_far_surface())
    assert len(graphs["STANDARD"]["edges"]) == 2  # a-b and b-stairs, each once although both sides link
    assert len(graphs["STEP_FREE"]["edges"]) == 1  # only a-b
    assert len(graphs["STANDARD"]["nodes"]) == len(graphs["STEP_FREE"]["nodes"]) == 3
    assert all(e["portal_width_m"] == pytest.approx(1.0) for e in graphs["STANDARD"]["edges"])


def test_a_level_change_polygon_is_never_step_free_even_when_flat():
    """Recast may give a step's two sides one level polygon; the level-change flag still keeps it out of STEP_FREE."""
    flat = _quad(0, 0, 1, links=[_link(1, (1, 0, 0), (1, 1, 0))])
    over_step = _quad(1, 1, 2, links=[_link(0, (1, 1, 0), (1, 0, 0))], flags=POLYFLAG_WALK | POLYFLAG_LEVEL_CHANGE)
    graphs = build_routing_graphs([flat, over_step], max_ramp_slope_deg=5.0, surface=_far_surface())
    (edge,) = graphs["STANDARD"]["edges"]
    assert edge["level_change"] is True and edge["step_free"] is False and edge["max_slope_deg"] == pytest.approx(0.0)
    assert graphs["STEP_FREE"]["edges"] == []
    assert [n["level_change"] for n in graphs["STANDARD"]["nodes"]] == [False, True]


def test_build_routing_graphs_keeps_a_gentle_ramp_in_step_free():
    flat = _quad(0, 0, 1, links=[_link(1, (1, 0, 0), (1, 1, 0))])
    gentle_ramp = _quad(1, 1, 2, 0.0, 0.05, links=[_link(0, (1, 1, 0), (1, 0, 0))])
    graphs = build_routing_graphs([flat, gentle_ramp], max_ramp_slope_deg=5.0, surface=_far_surface())
    assert len(graphs["STEP_FREE"]["edges"]) == 1


def test_slopes_and_heights_come_from_the_reconstructed_surface_where_there_is_one():
    """Recast's polygon heights are quantised to the cell height; the reconstructed surface is not. A 1:14 ramp whose
    Recast polygons say 0.05 m over 0.3 m (9.5 degrees) is measured at its real slope, so it stays step-free."""
    s = _surface(_grid(0, 3, 0, 1, z=lambda x, y: x / 14))
    a = Polygon(0, [(1.0, 0.1, 0.05), (1.3, 0.1, 0.05), (1.3, 0.9, 0.05), (1.0, 0.9, 0.05)], [_link(1, (1.3, 0.1, 0.05), (1.3, 0.9, 0.05))])
    b = Polygon(1, [(1.3, 0.1, 0.1), (1.6, 0.1, 0.1), (1.6, 0.9, 0.1), (1.3, 0.9, 0.1)], [_link(0, (1.3, 0.9, 0.1), (1.3, 0.1, 0.1))])
    graphs = build_routing_graphs([a, b], max_ramp_slope_deg=5.0, surface=s)
    (edge,) = graphs["STANDARD"]["edges"]
    assert edge["max_slope_deg"] == pytest.approx(np.degrees(np.arctan(1 / 14)), abs=0.5)
    assert edge["step_free"] is True
    assert graphs["STANDARD"]["nodes"][0]["z"] == pytest.approx(1.15 / 14, abs=0.01)


def test_polygon_centroid_is_the_real_vertex_average():
    centroid = polygon_centroid(Polygon(0, [(0, 0, 0), (2, 0, 0), (1, 3, 0)]))
    assert np.allclose(centroid, [1.0, 1.0, 0.0])


# ---- slope against the canonical up axis ------------------------------------------------------------------------------


def test_edge_rise_and_run_are_measured_along_canonical_up_not_a_horizontal_axis():
    """+Z is up in the canonical frame. A 3 m move along +Y is level (Recast's +Y-up convention must never leak in here);
    the same move along +Z is a vertical rise."""
    assert edge_rise_run(np.zeros(3), np.array([0.0, 3.0, 0.0])) == pytest.approx((0.0, 3.0))
    assert edge_grade_degrees(np.zeros(3), np.array([0.0, 3.0, 0.0])) == pytest.approx(0.0)
    assert edge_rise_run(np.zeros(3), np.array([0.0, 0.0, 3.0])) == pytest.approx((3.0, 0.0))
    assert edge_grade_degrees(np.zeros(3), np.array([0.0, 0.0, 3.0])) == pytest.approx(90.0)
    # 1:12 (the accessible-ramp limit) in metres: 0.1 m of rise over 1.2 m of run, in any horizontal direction
    run_dir = np.array([0.6, 0.8, 0.0])
    assert edge_grade_degrees(np.zeros(3), run_dir * 1.2 + [0, 0, 0.1]) == pytest.approx(np.degrees(np.arctan(1 / 12)))
    assert edge_rise_run(np.array([0, 0, 0.5]), np.zeros(3))[0] == pytest.approx(-0.5), "rise is signed: descending is negative"


def test_a_step_between_two_level_polygons_is_not_step_free():
    """Two treads: each polygon is perfectly level, but the second is 0.17 m higher (a stair riser) 0.3 m further on.
    Per-polygon slope alone would call this step-free; the centroid-to-centroid grade does not."""
    lower = Polygon(0, [(0, 0, 0), (0.3, 0, 0), (0.3, 1, 0), (0, 1, 0)], [_link(1, (0.3, 0, 0), (0.3, 1, 0))])
    upper = Polygon(1, [(0.3, 0, 0.17), (0.6, 0, 0.17), (0.6, 1, 0.17), (0.3, 1, 0.17)], [_link(0, (0.3, 1, 0), (0.3, 0, 0))])
    assert polygon_slope_degrees(lower) == polygon_slope_degrees(upper) == pytest.approx(0.0)

    graphs = build_routing_graphs([lower, upper], max_ramp_slope_deg=5.0, surface=_far_surface())
    (edge,) = graphs["STANDARD"]["edges"]
    assert edge["rise_m"] == pytest.approx(0.17)
    assert edge["max_slope_deg"] == pytest.approx(np.degrees(np.arctan2(0.17, 0.3)))
    assert edge["step_free"] is False
    assert graphs["STEP_FREE"]["edges"] == []


def test_every_edge_records_its_measurements_and_its_portal():
    flat = _quad(0, 0, 1, links=[_link(1, (1, 0, 0), (1, 1, 0))])
    level_far = _quad(1, 1, 4, links=[_link(0, (1, 1, 0), (1, 0, 0))])
    (edge,) = build_routing_graphs([flat, level_far], max_ramp_slope_deg=5.0, surface=_far_surface())["STEP_FREE"]["edges"]
    assert edge["length_m"] == pytest.approx(2.0)  # centroids at x = 0.5 and x = 2.5
    assert edge["max_slope_deg"] == pytest.approx(0.0)
    assert edge["rise_m"] == pytest.approx(0.0)
    assert edge["portal"] == [[1.0, 0.0, 0.0], [1.0, 1.0, 0.0]]
    assert edge["portal_width_m"] == pytest.approx(1.0)
    assert edge["min_clearance_m"] == 0.0, "nothing measured where the surface has no ground: stored as unmeasured"
