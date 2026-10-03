"""Navigation from reconstructed venue geometry, with the real Recast/Detour tool (review N-2): the deterministic venue of
tests/navmesh/venue_scene.py (labelled points, as GEOMETRIC_CLEANUP leaves them) -> surface model -> Recast input ->
Detour navmesh -> routing graphs and Detour paths.

The scene is synthetic. These tests show that walls, doorways, furniture, a column, a step and a ramp reach the
navmesh and the graphs correctly; they do not validate navigation in any real venue.
"""

from __future__ import annotations

import heapq
from dataclasses import replace
from pathlib import Path

import numpy as np
import pytest

from chaya_worker.navmesh import (
    GROUND_LABELS,
    NO_ROUTE,
    OBSTACLE_LABELS,
    NavmeshError,
    build_routing_graphs,
    build_surface_model,
    geometry_from_surface,
    point_in_polygon_xy,
)
from chaya_worker.recast import RecastConfig, bake, find_path
from chaya_worker.runner import CommandRunner
from chaya_worker.semantic_classes import STAIRS, bucket_label
from chaya_worker.settings import Settings
from tests.navmesh import venue_scene as V

pytestmark = pytest.mark.navmesh

SETTINGS = Settings()
CONFIG = RecastConfig.from_settings(SETTINGS)
# The API's STEP_FREE limits (dev.chaya.api.navigation.NavigationProperties defaults).
API_MIN_CLEARANCE_M = 0.9
API_MAX_SLOPE_DEG = 4.76
# Recast's eroded boundary may sit up to one edge-simplification error closer to an obstacle than the agent radius.
MARGIN = CONFIG.agent_radius_m - CONFIG.edge_max_error_m


class Baked:
    def __init__(self, tool: str, work: Path, **scene) -> None:
        self.points, self.labels = V.build(**scene)
        s = SETTINGS
        self.surface = build_surface_model(
            self.points[np.isin(self.labels, GROUND_LABELS)], self.points[np.isin(self.labels, OBSTACLE_LABELS)], reference_z=0.0,
            cell_m=s.navmesh_floor_grid_m, min_points_per_cell=s.navmesh_floor_min_points_per_cell, ground_band_m=s.navmesh_ground_band_m,
            agent_max_climb_m=s.navmesh_agent_max_climb_m, agent_height_m=s.navmesh_agent_height_m,
            step_min_rise_m=s.navmesh_step_min_rise_m, max_level_above_reference_m=s.navmesh_max_level_above_floor_m,
            accessible_width_m=s.navmesh_accessible_width_m, width_cap_m=s.navmesh_width_cap_m)
        self.geometry = geometry_from_surface(self.surface, obstacle_min_height_m=s.navmesh_obstacle_min_height_m)
        work.mkdir(parents=True, exist_ok=True)
        self.tool, self.work = tool, work
        self.runner = CommandRunner(work / "stdout.log", work / "stderr.log")
        self.build = bake(tool, self.geometry, CONFIG, work, self.runner)
        self.graphs = build_routing_graphs(self.build.polygons, max_ramp_slope_deg=s.navmesh_max_ramp_slope_deg, surface=self.surface)

    def detour(self, start, end, *, step_free=False):
        return np.array(find_path(self.tool, self.build.navmesh_path, np.array(start, float), np.array(end, float), self.work,
                                  self.runner, snap_horizontal_m=0.5, snap_vertical_m=0.5, step_free=step_free)["straight_path"])

    def on_navmesh(self, point) -> bool:
        return any(point_in_polygon_xy(np.asarray(point), p) for p in self.build.polygons)

    def route(self, profile: str, start, end, *, min_clearance_m=API_MIN_CLEARANCE_M):
        """Dijkstra over the published graph with the API's rules (RouteService): STEP_FREE uses an edge only when it is
        step-free and its measured clearance and slope pass. Returns the node path, or None."""
        graph = self.graphs[profile]
        pos = {n["local_id"]: np.array([n["x"], n["y"], n["z"]]) for n in graph["nodes"]}
        adj: dict[str, list[tuple[str, dict]]] = {}
        for e in graph["edges"]:
            if profile == "STEP_FREE" and not (e["step_free"] and e["min_clearance_m"] >= min_clearance_m
                                               and e["max_slope_deg"] <= API_MAX_SLOPE_DEG):
                continue
            adj.setdefault(e["from"], []).append((e["to"], e))
            adj.setdefault(e["to"], []).append((e["from"], e))
        nearest = lambda p: min(pos, key=lambda k: np.linalg.norm(pos[k][:2] - np.asarray(p)[:2]))  # noqa: E731
        src, dst = nearest(start), nearest(end)
        dist, prev, queue = {src: 0.0}, {}, [(0.0, src)]
        while queue:
            d, u = heapq.heappop(queue)
            if u == dst:
                break
            if d > dist[u]:
                continue
            for v, e in adj.get(u, []):
                nd = d + float(np.linalg.norm(pos[u] - pos[v]))
                if nd < dist.get(v, np.inf):
                    dist[v], prev[v] = nd, (u, e)
                    heapq.heappush(queue, (nd, v))
        if dst not in dist:
            return None
        nodes, edges, cur = [dst], [], dst
        while cur != src:
            cur, e = prev[cur]
            nodes.append(cur)
            edges.append(e)
        return [pos[n] for n in reversed(nodes)], list(reversed(edges))


@pytest.fixture(scope="module")
def venue(navmesh_tool, tmp_path_factory):
    return Baked(navmesh_tool, tmp_path_factory.mktemp("venue"))


def _samples(path: np.ndarray, step=0.02) -> np.ndarray:
    out = [path[0]]
    for a, b in zip(path[:-1], path[1:], strict=True):
        n = max(1, int(np.ceil(np.linalg.norm(b - a) / step)))
        out += [a + (b - a) * t for t in np.linspace(0, 1, n + 1)[1:]]
    return np.array(out)


def _x_crossings(path: np.ndarray, x: float) -> list[float]:
    """The y at which the polyline crosses the vertical line at x."""
    ys = []
    for a, b in zip(path[:-1], path[1:], strict=True):
        if (a[0] - x) * (b[0] - x) < 0:
            ys.append(a[1] + (b[1] - a[1]) * (x - a[0]) / (b[0] - a[0]))
        elif a[0] == x:  # a corner of the path exactly on the line
            ys.append(a[1])
    return ys


# ---- the Recast input is the reconstructed geometry -------------------------------------------------------------------


def test_stairs_are_walkable_surface_not_furniture():
    assert bucket_label("stairs;steps") == STAIRS and bucket_label("stairway;staircase") == STAIRS
    assert bucket_label("escalator;moving staircase") != STAIRS and bucket_label("railing;rail") == "furniture"


def test_the_recast_input_holds_every_level_and_every_obstacle(venue):
    s, g = venue.surface, venue.geometry
    assert np.nanmin(s.ground_z) == pytest.approx(0.0, abs=0.01) and np.nanmax(s.ground_z) == pytest.approx(V.STEP_RISE_M, abs=0.01)
    for x, y, z in ((2.0, 1.0, 0.0), (11.0, 1.0, V.STEP_RISE_M), (11.0, 5.0, V.STEP_RISE_M),
                    (8.3, 5.0, V.STEP_RISE_M * (8.35 - V.RAMP_X0) / (V.STEP_X - V.RAMP_X0))):
        assert venue.surface.height_at(np.array([x, y])) == pytest.approx(z, abs=0.01), (x, y)
    for name, box in (("wall", (V.WALL[0], V.WALL[1], 0.0, V.D1[0])), ("column", V.COLUMN), ("sofa", V.SOFA), ("railing", V.RAILING)):
        lo, hi = s.cell_of(np.array([box[0] + 0.01, box[2] + 0.01])), s.cell_of(np.array([box[1] - 0.01, box[3] - 0.01]))
        cells = (slice(lo[0], hi[0] + 1), slice(lo[1], hi[1] + 1))
        assert s.obstacle[cells].any(), f"the {name} is an obstacle in the Recast input"
        assert not s.free[cells].any(), f"no part of the {name} is free ground (obstacle, or a hole where it hid the floor)"
    for y in (sum(V.D1) / 2, sum(V.D2) / 2):  # the lintel is above head height: the doorway is free
        i, j = s.cell_of(np.array([4.0, y]))
        assert not s.obstacle[i, j] and np.isfinite(s.ground_z[i, j])
    lc = g.vertices[np.unique(g.triangles[g.level_change])]
    assert len(lc) and (np.abs(lc[:, 0] - V.STEP_X) <= 0.2).all() and (lc[:, 1] <= V.STEP_LANE_Y[1] + 0.1).all(), \
        "level changes are exactly beside the step, and nowhere on the ramp"
    ramp = (g.vertices[g.triangles].mean(axis=1))
    on_ramp = (~g.obstacle) & (ramp[:, 0] > V.RAMP_X0 + 0.2) & (ramp[:, 0] < V.STEP_X - 0.2) & (ramp[:, 1] > V.RAMP_LANE_Y[0] + 0.2)
    v = g.vertices[g.triangles[on_ramp]]
    n = np.cross(v[:, 1] - v[:, 0], v[:, 2] - v[:, 0])
    slope = np.degrees(np.arccos(np.abs(n[:, 2]) / np.linalg.norm(n, axis=1)))
    assert np.median(slope) == pytest.approx(np.degrees(np.arctan(1 / 14)), abs=0.3), "the ramp is given to Recast as a slope"


# ---- walls, doorways, furniture ---------------------------------------------------------------------------------------


def test_a_wall_blocks_traversal_except_through_its_doorways(venue):
    for y in np.arange(0.05, 7.0, 0.1):
        if not any(lo + MARGIN <= y <= hi - MARGIN for lo, hi in (V.D1, V.D2)):
            assert not venue.on_navmesh([4.0, y]), f"walkable inside the wall at y={y:.2f}"
    path = venue.detour([1.0, 1.0, 0.0], [6.0, 1.0, 0.0])
    (y,) = _x_crossings(path, 4.0)
    assert V.D1[0] < y < V.D1[1], "the west-east route goes through the doorway, never through the wall"


def test_furniture_and_a_column_block_traversal(venue):
    for name, box in (("sofa", V.SOFA), ("column", V.COLUMN), ("railing", V.RAILING)):
        pts = np.array([[x, y] for x in np.arange(box[0], box[1], 0.05) for y in np.arange(box[2], box[3], 0.05)])
        assert not any(venue.on_navmesh(p) for p in pts), f"the {name} is walkable"
    path = venue.detour([1.5, 3.2, 0.0], [1.5, 5.2, 0.0])  # straight through the sofa
    assert not V.in_box(_samples(path), V.SOFA, margin=MARGIN).any(), "the route goes round the sofa"
    assert len(path) > 2


def test_detour_paths_stay_on_the_navmesh(venue):
    path = venue.detour([0.6, 0.6, 0.0], [11.4, 6.4, V.STEP_RISE_M])
    assert all(venue.on_navmesh(p) for p in _samples(path)[1:-1]), "every point of the string-pulled path is on the navmesh"


# ---- steps and ramps --------------------------------------------------------------------------------------------------


def test_standard_crosses_the_step_step_free_takes_the_ramp(venue):
    start, end = [8.5, 1.5, 0.0], [11.0, 1.5, V.STEP_RISE_M]
    standard = venue.detour(start, end)
    step_free = venue.detour(start, end, step_free=True)
    assert np.linalg.norm(np.diff(standard, axis=0), axis=1).sum() < 3.0, "straight over the 0.17 m step"
    assert step_free[:, 1].max() > V.RAMP_LANE_Y[0], "round the railing and up the ramp"
    assert any(p.level_change for p in venue.build.polygons)


def test_the_accessible_graph_route_avoids_the_step_when_the_ramp_exists(venue):
    start, end = [8.5, 1.5, 0.0], [11.0, 1.5, V.STEP_RISE_M]
    nodes, edges = venue.route("STANDARD", start, end)
    assert any(e["level_change"] for e in edges), "STANDARD takes the step"
    nodes, edges = venue.route("STEP_FREE", start, end)
    assert not any(e["level_change"] for e in edges)
    assert max(n[1] for n in nodes) > V.RAMP_LANE_Y[0], "STEP_FREE goes up the ramp"
    assert all(e["max_slope_deg"] <= API_MAX_SLOPE_DEG for e in edges)
    assert all(not e["level_change"] for e in venue.graphs["STEP_FREE"]["edges"])


def test_with_no_ramp_the_accessible_route_fails_closed(navmesh_tool, tmp_path):
    venue = Baked(navmesh_tool, tmp_path / "no-ramp", with_ramp=False)
    start, end = [8.5, 1.5, 0.0], [11.0, 1.5, V.STEP_RISE_M]
    assert venue.route("STANDARD", start, end) is not None
    assert venue.route("STEP_FREE", start, end) is None
    with pytest.raises(NavmeshError) as exc:
        venue.detour(start, end, step_free=True)
    assert exc.value.code == NO_ROUTE


# ---- clearance from the obstacle geometry -----------------------------------------------------------------------------


def _doorway_used(nodes) -> str:
    path = np.array(nodes)
    ys = _x_crossings(path, 4.0)
    return "D1" if any(V.D1[0] - 0.5 < y < V.D1[1] + 0.5 for y in ys) else "D2" if ys else "?"


def test_clearance_is_measured_on_the_walls_and_decides_the_accessible_route(venue):
    start, end = [1.0, 2.5, 0.0], [6.0, 2.5, 0.0]
    nodes, edges = venue.route("STEP_FREE", start, end, min_clearance_m=0.9)
    assert _doorway_used(nodes) == "D1", "1.1 m doorway: wide enough for 0.9 m, and the shorter way"
    assert min(e["min_clearance_m"] for e in edges) == pytest.approx(1.1, abs=0.15), "measured: the 1.1 m doorway"
    nodes, edges = venue.route("STEP_FREE", start, end, min_clearance_m=1.3)
    assert _doorway_used(nodes) == "D2", "needing 1.3 m, only the 1.6 m doorway will do"
    assert min(e["min_clearance_m"] for e in edges) >= 1.3
    assert venue.route("STEP_FREE", start, end, min_clearance_m=1.8) is None, "nothing is 1.8 m wide: fails closed"
    assert venue.route("STANDARD", start, end) is not None


def test_without_the_wide_doorway_a_wide_requirement_fails_closed(navmesh_tool, tmp_path):
    venue = Baked(navmesh_tool, tmp_path / "one-door", with_wide_doorway=False)
    start, end = [1.0, 2.5, 0.0], [6.0, 2.5, 0.0]
    assert venue.route("STEP_FREE", start, end, min_clearance_m=0.9) is not None
    assert venue.route("STEP_FREE", start, end, min_clearance_m=1.3) is None


def test_every_edge_carries_its_portal_in_canonical_coordinates(venue):
    for e in venue.graphs["STANDARD"]["edges"]:
        a, b = (np.array(p) for p in e["portal"])
        assert np.isfinite(a).all() and np.isfinite(b).all() and np.linalg.norm(a - b) > 0
        assert -0.1 <= a[2] <= V.STEP_RISE_M + 0.15, "portal heights are canonical +Z heights"


def test_the_bake_is_deterministic(navmesh_tool, tmp_path, venue):
    again = Baked(navmesh_tool, tmp_path / "again")
    assert again.build.sha256 == venue.build.sha256
    assert replace(Settings()) == SETTINGS
