"""Pure unit tests for chaya_worker.navmesh: the walkable-surface geometry NAVIGATION_BAKING prepares for
Recast, and the routing-graph construction from Recast's own polygon output. No external tools."""

from __future__ import annotations

import numpy as np
import pytest

from chaya_worker.navmesh import (
    Polygon,
    build_recast_argv,
    build_routing_graphs,
    carve_obstacles,
    parse_recast_polygons,
    polygon_centroid,
    polygon_slope_degrees,
    project_to_plane,
    shared_edge_length,
    triangulate_walkable_area,
    write_walkable_obj,
)


def _flat_floor_grid(n: int = 8, extent: float = 4.0) -> np.ndarray:
    xs, ys = np.meshgrid(np.linspace(0, extent, n), np.linspace(0, extent, n))
    return np.stack([xs.ravel(), ys.ravel(), np.zeros(xs.size)], axis=1)


def test_project_to_plane_is_a_real_orthonormal_projection():
    points = np.array([[1.0, 2.0, 5.0], [3.0, 4.0, 5.0]])
    uv = project_to_plane(points, plane_point=np.array([0.0, 0.0, 5.0]), normal=np.array([0.0, 0.0, 1.0]))
    assert uv.shape == (2, 2)
    # A flat floor plane's normal is z; projecting drops z and keeps x/y (up to the chosen u/v basis).
    assert np.allclose(np.linalg.norm(uv[0]), np.linalg.norm(points[0][:2]), atol=1e-6)


def test_triangulate_walkable_area_needs_at_least_four_points():
    with pytest.raises(ValueError):
        triangulate_walkable_area(np.array([[0.0, 0.0], [1.0, 0.0], [0.0, 1.0]]))


def test_triangulate_and_carve_real_floor_geometry():
    floor = _flat_floor_grid()
    uv = project_to_plane(floor, plane_point=floor.mean(axis=0), normal=np.array([0.0, 0.0, 1.0]))
    triangles = triangulate_walkable_area(uv)
    assert len(triangles) > 0

    obstacle = np.array([[2.0, 2.0]])
    mask = carve_obstacles(uv, triangles, obstacle, agent_radius=1.0)
    assert mask.sum() < len(triangles), "some triangles near the obstacle must be carved out"
    assert mask.sum() > 0, "most of the floor must remain walkable"


def test_carve_obstacles_keeps_everything_walkable_when_there_are_no_obstacles():
    floor = _flat_floor_grid()
    uv = project_to_plane(floor, plane_point=floor.mean(axis=0), normal=np.array([0.0, 0.0, 1.0]))
    triangles = triangulate_walkable_area(uv)
    mask = carve_obstacles(uv, triangles, np.zeros((0, 2)), agent_radius=0.5)
    assert mask.all()


def test_write_walkable_obj_only_includes_walkable_triangles(tmp_path):
    floor = _flat_floor_grid(n=4)
    uv = project_to_plane(floor, plane_point=floor.mean(axis=0), normal=np.array([0.0, 0.0, 1.0]))
    triangles = triangulate_walkable_area(uv)
    mask = np.zeros(len(triangles), dtype=bool)
    mask[0] = True  # keep exactly one triangle
    path = tmp_path / "walkable.obj"
    count = write_walkable_obj(path, floor, triangles, mask)
    assert count == 1
    text = path.read_text()
    assert text.count("\nf ") + (1 if text.startswith("f ") else 0) == 1
    assert text.count("\nv ") + (1 if text.startswith("v ") else 0) == 3  # one triangle has 3 unique vertices


def test_build_recast_argv_rejects_unknown_config_keys(tmp_path):
    with pytest.raises(ValueError, match="unknown Recast config"):
        build_recast_argv("recast-cli", tmp_path / "in.obj", tmp_path / "out.json", notARealFlag=1.0)


def test_build_recast_argv_is_a_real_flag_per_config_value(tmp_path):
    argv = build_recast_argv("recast-cli", tmp_path / "in.obj", tmp_path / "out.json", cellSize=0.3, agentRadius=0.4)
    assert argv[0] == "recast-cli"
    assert "--cellSize" in argv and "0.3" in argv
    assert "--agentRadius" in argv and "0.4" in argv


def test_parse_recast_polygons_rejects_a_degenerate_polygon():
    with pytest.raises(ValueError):
        parse_recast_polygons({"polygons": [{"id": 0, "vertices": [[0, 0, 0], [1, 0, 0]]}]})


def test_parse_recast_polygons_round_trip():
    doc = {"polygons": [{"id": 0, "vertices": [[0, 0, 0], [1, 0, 0], [1, 1, 0]], "neighbors": [1]},
                        {"id": 1, "vertices": [[1, 0, 0], [2, 0, 0], [1, 1, 0]], "neighbors": [0]}]}
    polys = parse_recast_polygons(doc)
    assert len(polys) == 2
    assert polys[0].neighbors == [1]


def test_polygon_slope_degrees_flat_vs_vertical():
    flat = Polygon(id=0, vertices=[(0, 0, 0), (1, 0, 0), (1, 1, 0)], neighbors=[])
    vertical = Polygon(id=1, vertices=[(0, 0, 0), (0, 0, 1), (1, 0, 1)], neighbors=[])
    assert polygon_slope_degrees(flat) == pytest.approx(0.0, abs=1e-6)
    assert polygon_slope_degrees(vertical) == pytest.approx(90.0, abs=1e-6)


def test_shared_edge_length_is_the_real_portal_width():
    a = Polygon(id=0, vertices=[(0, 0, 0), (1, 0, 0), (1, 1, 0), (0, 1, 0)], neighbors=[1])
    b = Polygon(id=1, vertices=[(1, 0, 0), (2, 0, 0), (2, 1, 0), (1, 1, 0)], neighbors=[0])
    assert shared_edge_length(a, b) == pytest.approx(1.0, abs=1e-4)


def test_shared_edge_length_is_zero_for_non_adjacent_polygons():
    a = Polygon(id=0, vertices=[(0, 0, 0), (1, 0, 0), (1, 1, 0)], neighbors=[])
    b = Polygon(id=1, vertices=[(10, 10, 0), (11, 10, 0), (11, 11, 0)], neighbors=[])
    assert shared_edge_length(a, b) == 0.0


def test_build_routing_graphs_excludes_stairs_from_step_free_but_not_standard():
    flat_a = Polygon(id=0, vertices=[(0, 0, 0), (1, 0, 0), (1, 1, 0), (0, 1, 0)], neighbors=[1])
    flat_b = Polygon(id=1, vertices=[(1, 0, 0), (2, 0, 0), (2, 1, 0), (1, 1, 0)], neighbors=[0, 2])
    stairs = Polygon(id=2, vertices=[(2, 0, 0), (3, 0, 3), (3, 1, 3), (2, 1, 0)], neighbors=[1])

    graphs = build_routing_graphs([flat_a, flat_b, stairs], max_ramp_slope_deg=5.0)
    assert len(graphs["STANDARD"]["edges"]) == 2  # a-b and b-stairs
    assert len(graphs["STEP_FREE"]["edges"]) == 1  # only a-b
    assert len(graphs["STANDARD"]["nodes"]) == len(graphs["STEP_FREE"]["nodes"]) == 3


def test_build_routing_graphs_keeps_a_gentle_ramp_in_step_free():
    # A very slight incline (well under the ADA-inspired 5 degree default threshold).
    flat = Polygon(id=0, vertices=[(0, 0, 0), (1, 0, 0), (1, 1, 0), (0, 1, 0)], neighbors=[1])
    gentle_ramp = Polygon(id=1, vertices=[(1, 0, 0), (2, 0, 0.05), (2, 1, 0.05), (1, 1, 0)], neighbors=[0])
    graphs = build_routing_graphs([flat, gentle_ramp], max_ramp_slope_deg=5.0)
    assert len(graphs["STEP_FREE"]["edges"]) == 1


def test_polygon_centroid_is_the_real_vertex_average():
    poly = Polygon(id=0, vertices=[(0, 0, 0), (2, 0, 0), (1, 3, 0)], neighbors=[])
    centroid = polygon_centroid(poly)
    assert np.allclose(centroid, [1.0, 1.0, 0.0])
