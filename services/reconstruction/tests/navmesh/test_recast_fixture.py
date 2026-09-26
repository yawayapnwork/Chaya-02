"""The real Recast/Detour pipeline (services/reconstruction/native/chaya-navmesh, recastnavigation 1.6.0) over a tiny real mesh fixture.

tests/fixtures/navmesh/room_with_doorway.obj is canonical-frame geometry (metres, +Z up): an 8 x 4 m floor split by a
wall with a 1.4 m doorway, a furniture box marked as blocked geometry, and a 59.5-degree wedge. Nothing here is mocked:
the geometry crosses chaya_worker.recast_boundary, Recast voxelises, filters, erodes, builds regions, contours and
polygons, Detour builds and queries the navmesh, and the results cross back.
"""

from __future__ import annotations

import json
import os
import subprocess
from pathlib import Path

import numpy as np
import pytest

from chaya_worker.navmesh import (
    INVALID_GEOMETRY,
    NAVMESH_BUILD_FAILED,
    NO_WALKABLE_SURFACE,
    ROUTE_UNAVAILABLE,
    NavGeometry,
    NavmeshError,
    NavmeshToolUnavailable,
    point_in_polygon_xy,
    read_obj,
)
from chaya_worker.recast import RecastConfig, bake, find_path, resolve_tool, sha256_file, tool_version
from chaya_worker.runner import CommandRunner
from chaya_worker.settings import Settings
from chaya_worker.toolchain import Toolchain

pytestmark = pytest.mark.navmesh

FIXTURE = Path(__file__).resolve().parents[1] / "fixtures" / "navmesh" / "room_with_doorway.obj"
# The committed real output for FIXTURE (packages/contracts/fixtures/navmesh), which the API's tests ingest and route on.
CONTRACT = Path(__file__).resolve().parents[4] / "packages" / "contracts" / "fixtures" / "navmesh"
CONFIG = RecastConfig.from_settings(Settings())  # the production defaults, in metres
FLOOR_TOLERANCE = 2 * CONFIG.cell_height_m  # Recast reports span tops, quantised to the cell height
# Recast erodes the walkable area by the agent radius, then simplifies contours by up to edge_max_error: the navmesh
# keeps at least this much clearance from any blocked footprint.
CLEARANCE = CONFIG.agent_radius_m - CONFIG.edge_max_error_m

FURNITURE = (1.5, 2.5, 1.0, 2.0)  # x0, x1, y0, y1 (canonical metres), from the fixture's header
WALL = (3.9, 4.1, 0.0, 2.6)
DOORWAY_Y = (2.6, 4.0)
WEDGE_STEEP = (6.2 + CONFIG.agent_max_climb_m / 1.7, 7.2, 0.0, 1.2)  # the part of the wedge higher than the agent can step


def _runner(tmp_path: Path) -> CommandRunner:
    return CommandRunner(tmp_path / "stdout.log", tmp_path / "stderr.log")


def _samples(box, margin=0.0, step=0.025):
    """Points within Euclidean distance `margin` of the (x0, x1, y0, y1) footprint."""
    x0, x1, y0, y1 = box
    xs, ys = np.meshgrid(np.arange(x0 - margin, x1 + margin + 1e-9, step), np.arange(y0 - margin, y1 + margin + 1e-9, step))
    pts = np.stack([xs.ravel(), ys.ravel(), np.zeros(xs.size)], axis=1)
    dx = np.maximum(np.maximum(x0 - pts[:, 0], 0), pts[:, 0] - x1)
    dy = np.maximum(np.maximum(y0 - pts[:, 1], 0), pts[:, 1] - y1)
    return pts[np.hypot(dx, dy) < margin] if margin > 0 else pts


def _segment_points(path, step=0.02):
    pts = []
    for a, b in zip(path[:-1], path[1:], strict=True):
        a, b = np.array(a), np.array(b)
        n = max(2, int(np.linalg.norm(b - a) / step))
        pts += [a + (b - a) * t for t in np.linspace(0, 1, n)]
    return np.array(pts)


def _inside(points, box):
    x0, x1, y0, y1 = box
    return (points[:, 0] > x0) & (points[:, 0] < x1) & (points[:, 1] > y0) & (points[:, 1] < y1)


@pytest.fixture(scope="module")
def built(navmesh_tool, tmp_path_factory):
    work = tmp_path_factory.mktemp("recast-fixture")
    return bake(navmesh_tool, read_obj(FIXTURE), CONFIG, work, _runner(work)), work


def test_the_real_tool_is_recastnavigation(navmesh_tool):
    assert tool_version(navmesh_tool) == {"tool": "chaya-navmesh", "tool_version": "1.0.0", "recastnavigation_version": "1.6.0"}


def test_navmesh_is_produced_as_a_detour_tile(built):
    build, _ = built
    data = build.navmesh_path.read_bytes()
    assert data[:4] == b"VAND", "Detour's DT_NAVMESH_MAGIC ('DNAV', little-endian) heads a real Detour tile"
    assert len(data) == build.report["navmesh"]["bytes"] and build.sha256 == sha256_file(build.navmesh_path)
    stages = build.report["stages"]
    # every Recast stage ran and produced something
    assert stages["walkable_triangles"] > 0 and stages["walkable_spans_after_erosion"] > 0
    assert stages["regions"] > 0 and stages["contours"] > 0 and stages["polygons"] == len(build.polygons) > 0
    # the metre configuration reached Recast as the right voxel values
    voxels = build.report["config_voxels"]
    assert voxels["walkableRadius"] == 4 and voxels["walkableHeight"] == 36 and voxels["walkableClimb"] == 8
    assert voxels["cs"] == pytest.approx(0.1) and voxels["walkableSlopeAngle"] == pytest.approx(45.0)


def test_walkable_polygons_exist_on_the_floor_in_canonical_coordinates(built):
    build, _ = built
    vertices = np.array([v for p in build.polygons for v in p.vertices])
    assert len(build.polygons) >= 2
    assert np.abs(vertices[:, 2]).max() <= FLOOR_TOLERANCE, "canonical z is the floor height (Recast's y came back as z)"
    assert vertices[:, 0].min() >= 0 and vertices[:, 0].max() <= 8 and vertices[:, 1].min() >= 0 and vertices[:, 1].max() <= 4
    # both rooms are walkable, connected only through the doorway
    assert any(point_in_polygon_xy(np.array([1.0, 3.0, 0]), p) for p in build.polygons)
    assert any(point_in_polygon_xy(np.array([6.0, 3.0, 0]), p) for p in build.polygons)
    assert all(p.links for p in build.polygons)


@pytest.mark.parametrize("name, box, margin", [
    ("furniture (blocked geometry)", FURNITURE, CLEARANCE),
    ("wall (vertical faces, top eroded)", WALL, CLEARANCE),
    ("steep wedge (fails Recast's slope test)", WEDGE_STEEP, 0.0),
])
def test_blocked_geometry_is_excluded(built, name, box, margin):
    build, _ = built
    hits = [tuple(pt[:2]) for pt in _samples(box, margin) if any(point_in_polygon_xy(pt, p) for p in build.polygons)]
    assert not hits, f"navmesh polygons cover the {name} at {hits[:3]}"


def test_route_is_calculated_by_detour_and_returned_in_canonical_coordinates(built, navmesh_tool, tmp_path):
    build, _ = built
    start, end = np.array([1.0, 0.6, 0.0]), np.array([6.0, 3.0, 0.0])
    route = find_path(navmesh_tool, build.navmesh_path, start, end, tmp_path, _runner(tmp_path), snap_horizontal_m=0.5, snap_vertical_m=1.0)
    path = np.array(route["straight_path"])
    assert len(path) >= 3, "the direct line is blocked, so Detour's string-pulled path must turn"
    np.testing.assert_allclose(path[0][:2], start[:2], atol=1e-3)
    np.testing.assert_allclose(path[-1][:2], end[:2], atol=1e-3)
    assert np.abs(path[:, 2]).max() <= FLOOR_TOLERANCE, "every waypoint is back in canonical z-up metres"
    assert len(route["corridor"]) >= 2

    walked = _segment_points(path)
    assert not _inside(walked, FURNITURE).any(), "the route goes around the blocked furniture"
    assert not _inside(walked, WALL).any(), "the route never passes through the wall"
    crossing = walked[np.abs(walked[:, 0] - 4.0) < 0.05]
    assert len(crossing) and (crossing[:, 1] > DOORWAY_Y[0] + CLEARANCE).all() and (crossing[:, 1] < DOORWAY_Y[1] - CLEARANCE).all(), \
        "the route crosses x = 4 m only through the doorway"
    length = np.linalg.norm(np.diff(path, axis=0), axis=1).sum()
    assert np.linalg.norm(end - start) < length < 2 * np.linalg.norm(end - start)


def test_route_to_a_point_inside_blocked_geometry_is_unavailable(built, navmesh_tool, tmp_path):
    build, _ = built
    with pytest.raises(NavmeshError) as exc:
        find_path(navmesh_tool, build.navmesh_path, np.array([1.0, 0.6, 0.0]), np.array([2.0, 1.5, 0.0]), tmp_path,
                  _runner(tmp_path), snap_horizontal_m=0.3, snap_vertical_m=1.0)
    assert exc.value.code == ROUTE_UNAVAILABLE


def test_route_between_disconnected_floors_is_unavailable_never_partial(navmesh_tool, tmp_path):
    two_islands = NavGeometry(np.array([[0, 0, 0], [3, 0, 0], [3, 3, 0], [0, 3, 0], [5, 0, 0], [8, 0, 0], [8, 3, 0], [5, 3, 0]], dtype=float),
                              np.array([[0, 1, 2], [0, 2, 3], [4, 5, 6], [4, 6, 7]]))
    build = bake(navmesh_tool, two_islands, CONFIG, tmp_path, _runner(tmp_path))
    with pytest.raises(NavmeshError) as exc:
        find_path(navmesh_tool, build.navmesh_path, np.array([1.5, 1.5, 0]), np.array([6.5, 1.5, 0]), tmp_path, _runner(tmp_path),
                  snap_horizontal_m=0.5, snap_vertical_m=1.0)
    assert exc.value.code == ROUTE_UNAVAILABLE


# ---- failure states, from the real tool ------------------------------------------------------------------------------


def test_no_walkable_surface_when_everything_is_too_steep(navmesh_tool, tmp_path):
    slab = NavGeometry(np.array([[0, 0, 0], [4, 0, 0], [4, 1, 3], [0, 1, 3]], dtype=float), np.array([[0, 1, 2], [0, 2, 3]]))
    with pytest.raises(NavmeshError) as exc:
        bake(navmesh_tool, slab, CONFIG, tmp_path, _runner(tmp_path))
    assert exc.value.code == NO_WALKABLE_SURFACE and exc.value.details["tool_status"] == "NO_WALKABLE_SURFACE"


def test_no_walkable_surface_when_the_agent_radius_erodes_everything(navmesh_tool, tmp_path):
    ledge = NavGeometry(np.array([[0, 0, 0], [0.5, 0, 0], [0.5, 0.5, 0], [0, 0.5, 0]], dtype=float), np.array([[0, 1, 2], [0, 2, 3]]))
    with pytest.raises(NavmeshError) as exc:
        bake(navmesh_tool, ledge, CONFIG, tmp_path, _runner(tmp_path))
    assert exc.value.code == NO_WALKABLE_SURFACE


def test_no_walkable_surface_when_the_only_floor_is_blocked(navmesh_tool, tmp_path):
    blocked = NavGeometry(np.array([[0, 0, 0], [4, 0, 0], [4, 4, 0], [0, 4, 0]], dtype=float), np.array([[0, 1, 2], [0, 2, 3]]),
                          np.array([True, True]))
    with pytest.raises(NavmeshError) as exc:
        bake(navmesh_tool, blocked, CONFIG, tmp_path, _runner(tmp_path))
    assert exc.value.code == NO_WALKABLE_SURFACE


def test_navmesh_build_failed_when_one_tile_cannot_hold_the_geometry(navmesh_tool, tmp_path):
    huge = NavGeometry(np.array([[0, 0, 0], [5000, 0, 0], [5000, 5000, 0], [0, 5000, 0]], dtype=float), np.array([[0, 1, 2], [0, 2, 3]]))
    with pytest.raises(NavmeshError) as exc:
        bake(navmesh_tool, huge, CONFIG, tmp_path, _runner(tmp_path))
    assert exc.value.code == NAVMESH_BUILD_FAILED


def test_invalid_geometry_is_refused_before_and_by_the_tool(navmesh_tool, tmp_path):
    with pytest.raises(NavmeshError) as exc:
        bake(navmesh_tool, NavGeometry(np.array([[0, 0, 0], [1, 0, np.inf], [0, 1, 0]]), np.array([[0, 1, 2]])), CONFIG, tmp_path,
             _runner(tmp_path))
    assert exc.value.code == INVALID_GEOMETRY
    # and the tool itself refuses a malformed file (a face naming a vertex that does not exist)
    bad = tmp_path / "bad.obj"
    bad.write_text("v 0 0 0\nv 1 0 0\nf 1 2 9\n")
    proc = subprocess.run([navmesh_tool, "bake", "--input", str(bad), "--navmesh", str(tmp_path / "x.bin"), "--report",
                           str(tmp_path / "r.json"), *CONFIG.argv()], capture_output=True, text=True)
    assert proc.returncode == 3 and '"status":"INVALID_GEOMETRY"' in (tmp_path / "r.json").read_text()


def test_the_tool_has_no_hidden_defaults(navmesh_tool, tmp_path):
    argv = CONFIG.argv()
    i = argv.index("--agent-radius-m")
    proc = subprocess.run([navmesh_tool, "bake", "--input", str(FIXTURE), "--navmesh", str(tmp_path / "x.bin"), "--report",
                           str(tmp_path / "r.json"), *(argv[:i] + argv[i + 2:])], capture_output=True, text=True)
    assert proc.returncode == 2 and "--agent-radius-m" in (tmp_path / "r.json").read_text()


def test_navmesh_tool_unavailable(tmp_path):
    with pytest.raises(NavmeshToolUnavailable) as exc:
        resolve_tool(Toolchain(env={}, which=lambda _n: None))
    assert exc.value.code == "NAVMESH_TOOL_UNAVAILABLE"
    impostor = tmp_path / ("impostor.bat" if os.name == "nt" else "impostor.sh")
    impostor.write_text("@echo recast-cli 9.9\n" if os.name == "nt" else "#!/bin/sh\necho recast-cli 9.9\n")
    impostor.chmod(0o755)
    with pytest.raises(NavmeshToolUnavailable, match="not a chaya-navmesh binary"):
        tool_version(str(impostor))


def test_the_committed_contract_fixture_is_a_detour_navmesh_bound_to_its_graph(navmesh_tool, tmp_path):
    """packages/contracts/fixtures/navmesh is real chaya-navmesh output for FIXTURE: the graph names navmesh.bin's
    checksum, Detour loads navmesh.bin and routes through the doorway on it, and the graph has one node per polygon."""
    graph = json.loads((CONTRACT / "navigation-graph.json").read_text(encoding="utf-8"))
    manifest = json.loads((CONTRACT / "navmesh-manifest.json").read_text(encoding="utf-8"))
    navmesh = CONTRACT / "navmesh.bin"
    assert graph["navmesh"]["sha256"] == manifest["navmesh"]["sha256"] == sha256_file(navmesh)
    assert graph["navmesh"]["source"] == "RECAST_NAVMESH" and manifest["status"] == "READY"
    assert manifest["tool"]["recastnavigation_version"] == "1.6.0"
    assert len(graph["graphs"]["STANDARD"]["nodes"]) == graph["polygon_count"] == manifest["navmesh"]["polygon_count"]
    route = find_path(navmesh_tool, navmesh, np.array([1.0, 3.0, 0.0]), np.array([6.0, 3.0, 0.0]), tmp_path, _runner(tmp_path),
                      snap_horizontal_m=0.5, snap_vertical_m=1.0)
    assert len(route["straight_path"]) >= 2
