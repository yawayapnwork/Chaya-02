"""Navigation geometry in the Chaya canonical frame (chaya_worker.frames: metres, right-handed, +Z up).

This module prepares what the real Recast build consumes and interprets what the real Detour navmesh contains. It never
voxelises, builds regions, contours or polygons itself -- that is Recast's job, run by the chaya-navmesh tool
(services/reconstruction/native/chaya-navmesh, driven by chaya_worker.recast). It also never converts axes: every coordinate in and out of this
module is canonical, and only chaya_worker.recast crosses into Recast's +Y-up frame, through chaya_worker.recast_boundary.

Pieces:
  * NavGeometry: a triangle soup (walkable candidates + blocked geometry), read/written as a canonical-frame OBJ.
  * geometry_from_reconstruction: the NavGeometry for a reconstructed floor, built from cleaned splat geometry.
  * Polygon / build_routing_graphs: the routing graphs dev.chaya.api.navigation.RouteService pathfinds over, taken from
    the polygons and links of the Detour navmesh Recast built.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import numpy as np

from .errors import DependencyError, StageError
from .frames import CANONICAL_UP

# ---- failure states -------------------------------------------------------------------------------------------------
# The structured error codes NAVIGATION_BAKING and route queries fail with. NAVMESH_NOT_READY is the API's (a floor
# with no navmesh-backed graph, dev.chaya.api.navigation.RouteService); it is listed here so the set is in one place.

NAVMESH_TOOL_UNAVAILABLE = "NAVMESH_TOOL_UNAVAILABLE"
INVALID_GEOMETRY = "INVALID_GEOMETRY"
NO_WALKABLE_SURFACE = "NO_WALKABLE_SURFACE"
NAVMESH_BUILD_FAILED = "NAVMESH_BUILD_FAILED"
NAVMESH_NOT_READY = "NAVMESH_NOT_READY"
ROUTE_UNAVAILABLE = "ROUTE_UNAVAILABLE"


class NavmeshError(StageError):
    """A navmesh build or query failed in one of the ways above; `code` says which."""


class NavmeshToolUnavailable(DependencyError):
    """The chaya-navmesh tool (real Recast/Detour) is not installed on this worker. Never worked around."""

    code = NAVMESH_TOOL_UNAVAILABLE


# ---- geometry -------------------------------------------------------------------------------------------------------


@dataclass
class NavGeometry:
    """Canonical-frame triangles. `obstacle[i]` marks triangle i as blocked geometry: Recast rasterises it as solid but
    never walkable. Every other triangle is walkable only if Recast's own slope/height/clearance tests pass."""

    vertices: np.ndarray  # (N, 3) float64, canonical metres
    triangles: np.ndarray  # (M, 3) int
    obstacle: np.ndarray = field(default=None)  # (M,) bool

    def __post_init__(self) -> None:
        self.vertices = np.asarray(self.vertices, dtype=np.float64).reshape(-1, 3)
        self.triangles = np.asarray(self.triangles, dtype=np.int64).reshape(-1, 3)
        self.obstacle = (np.zeros(len(self.triangles), dtype=bool) if self.obstacle is None
                         else np.asarray(self.obstacle, dtype=bool).reshape(-1))

    def validate(self) -> None:
        """Raises INVALID_GEOMETRY for anything Recast should never be handed."""
        problems = []
        if len(self.triangles) == 0:
            problems.append("no triangles")
        if len(self.obstacle) != len(self.triangles):
            problems.append("obstacle mask length differs from the triangle count")
        if not np.isfinite(self.vertices).all():
            problems.append("non-finite vertex coordinates")
        if len(self.triangles) and (self.triangles.min() < 0 or self.triangles.max() >= len(self.vertices)):
            problems.append("triangle indices out of range")
        if not problems and len(self.vertices):
            extent = self.vertices[:, :2].max(axis=0) - self.vertices[:, :2].min(axis=0)
            if (extent <= 0).any():
                problems.append("no horizontal extent")
        if problems:
            raise NavmeshError("navigation geometry is invalid: " + "; ".join(problems), code=INVALID_GEOMETRY,
                               details={"problems": problems, "vertices": len(self.vertices), "triangles": len(self.triangles)})

    @property
    def walkable_candidate_count(self) -> int:
        return int((~self.obstacle).sum())


def write_obj(path: Path, geometry: NavGeometry, *, header: str = "") -> None:
    """Writes canonical-frame geometry as OBJ, blocked triangles under `g obstacle`. No axis conversion: this is the
    human-inspectable NAVMESH_INPUT_GEOMETRY artifact, in the same frame as every other canonical artifact."""
    lines = [f"# {ln}" for ln in header.splitlines()]
    lines += [f"v {v[0]:.6f} {v[1]:.6f} {v[2]:.6f}" for v in geometry.vertices]
    for group, mask in (("walkable_candidates", ~geometry.obstacle), ("obstacle", geometry.obstacle)):
        if mask.any():
            lines.append(f"g {group}")
            lines += [f"f {t[0] + 1} {t[1] + 1} {t[2] + 1}" for t in geometry.triangles[mask]]
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def read_obj(path: Path) -> NavGeometry:
    """Reads a canonical-frame OBJ (as write_obj writes, or a hand-made fixture). Faces under a group/object whose name
    starts with "obstacle" are blocked geometry. Polygons are fan-triangulated."""
    vertices: list[list[float]] = []
    triangles: list[list[int]] = []
    obstacle: list[bool] = []
    in_obstacle = False
    for raw in path.read_text(encoding="utf-8").splitlines():
        parts = raw.split()
        if not parts or parts[0].startswith("#"):
            continue
        if parts[0] in ("g", "o"):
            in_obstacle = len(parts) > 1 and parts[1].startswith("obstacle")
        elif parts[0] == "v":
            vertices.append([float(c) for c in parts[1:4]])
        elif parts[0] == "f":
            idx = []
            for token in parts[1:]:
                i = int(token.split("/")[0])
                idx.append(i - 1 if i > 0 else len(vertices) + i)
            for k in range(2, len(idx)):
                triangles.append([idx[0], idx[k - 1], idx[k]])
                obstacle.append(in_obstacle)
    return NavGeometry(np.array(vertices, dtype=np.float64).reshape(-1, 3), np.array(triangles, dtype=np.int64).reshape(-1, 3),
                       np.array(obstacle, dtype=bool))


# ---- geometry from a reconstruction ---------------------------------------------------------------------------------


@dataclass(frozen=True)
class CanonicalPlane:
    """A fitted plane expressed in the canonical frame: unit normal oriented towards +Z, the median height of its
    inliers (metres), and the inlier indices into the cloud it was fitted on."""

    normal: np.ndarray
    height: float
    inlier_indices: np.ndarray


def select_floor_plane(planes: list[CanonicalPlane], *, max_tilt_deg: float, min_relative_support: float = 0.25) -> CanonicalPlane | None:
    """Pure: the walkable floor among fitted planes. Horizontal means within `max_tilt_deg` of canonical +Z.
    Among horizontal planes with at least `min_relative_support` of the best-supported one's inliers, the floor is
    the lowest -- a ceiling or a table top is above it. None when no plane is horizontal."""
    cos_limit = np.cos(np.radians(max_tilt_deg))
    horizontal = [p for p in planes if abs(float(np.asarray(p.normal) @ CANONICAL_UP)) >= cos_limit]
    if not horizontal:
        return None
    largest = max(len(p.inlier_indices) for p in horizontal)
    candidates = [p for p in horizontal if len(p.inlier_indices) >= min_relative_support * largest]
    return min(candidates, key=lambda p: p.height)


def obstacle_band_mask(points_canonical: np.ndarray, floor_height: float, *, agent_max_climb: float, agent_height: float) -> np.ndarray:
    """Pure: which points can block a walking agent -- those between the height it can step over and its own
    height, measured along canonical +Z from the floor. Ceiling and floor-level geometry never block."""
    z = np.asarray(points_canonical, dtype=np.float64)[:, 2] - floor_height
    return (z > agent_max_climb) & (z < agent_height)


def _cell_keys(xy: np.ndarray, origin: np.ndarray, cell_m: float) -> np.ndarray:
    return np.floor((xy - origin) / cell_m).astype(np.int64)


def geometry_from_reconstruction(floor_points: np.ndarray, obstacle_points: np.ndarray, floor_height: float, *, cell_m: float,
                                 min_points_per_cell: int, obstacle_min_height_m: float) -> NavGeometry:
    """Pure: the triangle soup Recast bakes for one reconstructed floor, from cleaned canonical-frame points.

    Floor: an occupancy grid of `cell_m` squares over the floor plane's inliers. Only cells holding at least
    `min_points_per_cell` real floor points become geometry (a quad at the median height of those points), so unscanned
    gaps, courtyards and the outside of an L-shaped room stay holes -- never the convex hull of the floor.

    Obstacles: every cell holding wall/furniture points (already filtered to the band an agent collides with, see
    obstacle_band_mask) becomes a closed box from the floor up to the highest such point (at least
    `obstacle_min_height_m`), marked blocked. Recast rasterises it as solid, so the floor under and around it is cut
    out and eroded by the agent radius exactly as for any other solid geometry.
    """
    floor_points = np.asarray(floor_points, dtype=np.float64).reshape(-1, 3)
    obstacle_points = np.asarray(obstacle_points, dtype=np.float64).reshape(-1, 3)
    if cell_m <= 0 or min_points_per_cell < 1:
        raise ValueError("cell_m must be positive and min_points_per_cell at least 1")
    if len(floor_points) == 0:
        return NavGeometry(np.zeros((0, 3)), np.zeros((0, 3), dtype=np.int64))
    origin = np.floor(np.vstack([floor_points[:, :2], obstacle_points[:, :2]]).min(axis=0) / cell_m) * cell_m

    verts: list[np.ndarray] = []
    tris: list[np.ndarray] = []
    blocked: list[np.ndarray] = []
    offset = 0

    keys = _cell_keys(floor_points[:, :2], origin, cell_m)
    uniq, inverse, counts = np.unique(keys, axis=0, return_inverse=True, return_counts=True)
    inverse = inverse.reshape(-1)
    order = np.argsort(inverse, kind="stable")
    splits = np.split(floor_points[order, 2], np.cumsum(counts)[:-1])
    occupied = counts >= min_points_per_cell
    if occupied.any():
        cells = uniq[occupied]
        heights = np.array([float(np.median(z)) for z, ok in zip(splits, occupied, strict=True) if ok])
        x0 = origin[0] + cells[:, 0] * cell_m
        y0 = origin[1] + cells[:, 1] * cell_m
        quad = np.stack([np.stack([x0, y0, heights], 1), np.stack([x0 + cell_m, y0, heights], 1),
                         np.stack([x0 + cell_m, y0 + cell_m, heights], 1), np.stack([x0, y0 + cell_m, heights], 1)], 1)
        n = len(cells)
        verts.append(quad.reshape(-1, 3))
        base = offset + 4 * np.arange(n)[:, None]
        # Counter-clockwise seen from +Z: the face normal points up, as Recast's slope test expects after the boundary.
        tris.append(np.concatenate([base + [0, 1, 2], base + [0, 2, 3]]))
        blocked.append(np.zeros(2 * n, dtype=bool))
        offset += 4 * n

    if len(obstacle_points):
        okeys = _cell_keys(obstacle_points[:, :2], origin, cell_m)
        ouniq, oinv = np.unique(okeys, axis=0, return_inverse=True)
        oinv = oinv.reshape(-1)
        tops = np.full(len(ouniq), -np.inf)
        np.maximum.at(tops, oinv, obstacle_points[:, 2])
        tops = np.maximum(tops, floor_height + obstacle_min_height_m)
        x0 = origin[0] + ouniq[:, 0] * cell_m
        y0 = origin[1] + ouniq[:, 1] * cell_m
        zb = np.full(len(ouniq), floor_height)
        corners = []
        for z in (zb, tops):
            corners += [np.stack([x0, y0, z], 1), np.stack([x0 + cell_m, y0, z], 1),
                        np.stack([x0 + cell_m, y0 + cell_m, z], 1), np.stack([x0, y0 + cell_m, z], 1)]
        n = len(ouniq)
        verts.append(np.stack(corners, 1).reshape(-1, 3))
        box = np.array([[0, 2, 1], [0, 3, 2], [4, 5, 6], [4, 6, 7], [0, 1, 5], [0, 5, 4],
                        [1, 2, 6], [1, 6, 5], [2, 3, 7], [2, 7, 6], [3, 0, 4], [3, 4, 7]])
        tris.append((offset + 8 * np.arange(n)[:, None, None] + box[None]).reshape(-1, 3))
        blocked.append(np.ones(12 * n, dtype=bool))

    if not verts:
        return NavGeometry(np.zeros((0, 3)), np.zeros((0, 3), dtype=np.int64))
    return NavGeometry(np.vstack(verts), np.vstack(tris), np.concatenate(blocked))


# ---- the baked navmesh's polygons, and routing graphs from them ------------------------------------------------------


@dataclass
class Link:
    """A Detour link from one polygon to a neighbour, with the portal edge (two canonical points) it crosses."""

    neighbor: int
    portal: tuple[tuple[float, float, float], tuple[float, float, float]]


@dataclass
class Polygon:
    id: int
    vertices: list[tuple[float, float, float]]
    links: list[Link] = field(default_factory=list)

    @property
    def neighbors(self) -> list[int]:
        return [link.neighbor for link in self.links]


def polygon_centroid(poly: Polygon) -> np.ndarray:
    return np.mean(np.array(poly.vertices), axis=0)


def polygon_slope_degrees(poly: Polygon) -> float:
    """Pure: angle between the polygon's own face normal and the canonical vertical (+Z), in degrees. 0 = flat.
    Meaningful only because the polygon is in the canonical frame, where +Z really is opposite to gravity."""
    v = np.array(poly.vertices, dtype=np.float64)
    normal = np.zeros(3)
    for i in range(1, len(v) - 1):  # Newell-style sum over the fan: robust to a degenerate first triangle
        normal += np.cross(v[i] - v[0], v[i + 1] - v[0])
    norm = np.linalg.norm(normal)
    if norm < 1e-12:
        return 0.0
    cos_angle = abs(float(np.dot(normal / norm, CANONICAL_UP)))
    return float(np.degrees(np.arccos(np.clip(cos_angle, 0.0, 1.0))))


def portal_width(link: Link) -> float:
    """Pure: the length of the portal edge Detour links two polygons through. Because Recast already eroded the walkable
    area by the agent radius, this is the free width left for the agent's centre, not the wall-to-wall corridor width
    (review finding N-3)."""
    a, b = (np.array(p, dtype=np.float64) for p in link.portal)
    return float(np.linalg.norm(a - b))


def point_in_polygon_xy(point: np.ndarray, poly: Polygon) -> bool:
    """Pure: whether a canonical point's horizontal (x, y) position lies inside the polygon's horizontal footprint."""
    x, y = float(point[0]), float(point[1])
    v = np.array(poly.vertices)[:, :2]
    inside = False
    j = len(v) - 1
    for i in range(len(v)):
        if (v[i, 1] > y) != (v[j, 1] > y) and x < (v[j, 0] - v[i, 0]) * (y - v[i, 1]) / (v[j, 1] - v[i, 1]) + v[i, 0]:
            inside = not inside
        j = i
    return inside


def build_routing_graphs(polygons: list[Polygon], *, max_ramp_slope_deg: float) -> dict[str, dict[str, list]]:
    """Pure: turns the Detour navmesh's polygon links into the two profile graphs dev.chaya.api.navigation.RouteService
    pathfinds over. Nodes are polygon centroids; an edge is a Detour link, weighted by centroid-to-centroid distance, with
    the portal it crosses recorded as `min_clearance_m` (see portal_width). STEP_FREE keeps only the edges between two
    polygons that are BOTH within `max_ramp_slope_deg` of level -- a real per-polygon slope computation, excluding stairs
    and anything steeper than an accessible ramp, never a renamed copy of STANDARD.
    """
    by_id = {p.id: p for p in polygons}
    slopes = {p.id: polygon_slope_degrees(p) for p in polygons}
    centroids = {p.id: polygon_centroid(p) for p in polygons}
    nodes = [{"local_id": f"p{p.id}", "kind": "WAYPOINT", "x": float(centroids[p.id][0]),
             "y": float(centroids[p.id][1]), "z": float(centroids[p.id][2]), "connector_type": None} for p in polygons]

    seen: set[tuple[int, int]] = set()
    standard_edges: list[dict[str, Any]] = []
    step_free_edges: list[dict[str, Any]] = []
    for p in polygons:
        for link in p.links:
            other = by_id.get(link.neighbor)
            if other is None or (other.id, p.id) in seen or other.id == p.id:
                continue
            seen.add((p.id, other.id))
            length = float(np.linalg.norm(centroids[p.id] - centroids[other.id]))
            step_free = slopes[p.id] <= max_ramp_slope_deg and slopes[other.id] <= max_ramp_slope_deg
            edge = {"from": f"p{p.id}", "to": f"p{other.id}", "length_m": length, "step_free": step_free,
                    "min_clearance_m": portal_width(link)}
            standard_edges.append(edge)
            if step_free:
                step_free_edges.append(edge)

    return {"STANDARD": {"nodes": nodes, "edges": standard_edges}, "STEP_FREE": {"nodes": nodes, "edges": step_free_edges}}
