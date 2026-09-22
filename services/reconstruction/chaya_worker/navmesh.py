"""Navigation-mesh geometry: prepares a walkable surface for Recast (exe:recast-cli, a real external
toolchain dependency like colmap/glomap -- see chaya_worker.toolchain) from real reconstructed geometry,
and turns Recast's own polygon output back into the routing graph shape dev.chaya.api.navigation ingests.
This module never reimplements Recast's voxelization/region/contour pipeline; it only prepares its input
and interprets its output. See chaya_worker.stages.navigation_baking for how it is used end to end.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Any

import numpy as np

# ---- input side: walkable surface from real geometry -------------------------------------------------


def orthonormal_basis(normal: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """Pure: an arbitrary right-handed (u, v) basis perpendicular to `normal`."""
    normal = normal / np.linalg.norm(normal)
    helper = np.array([1.0, 0.0, 0.0]) if abs(normal[0]) < 0.9 else np.array([0.0, 1.0, 0.0])
    u = np.cross(normal, helper)
    u = u / np.linalg.norm(u)
    v = np.cross(normal, u)
    return u, v


def project_to_plane(points: np.ndarray, plane_point: np.ndarray, normal: np.ndarray) -> np.ndarray:
    """Pure: (N, 3) world points to (N, 2) local (u, v) coordinates in the plane's own frame."""
    if len(points) == 0:
        return np.zeros((0, 2))
    u, v = orthonormal_basis(normal)
    rel = points - plane_point
    return np.stack([rel @ u, rel @ v], axis=1)


def triangulate_walkable_area(floor_points_2d: np.ndarray) -> np.ndarray:
    """Pure: Delaunay triangulation of the floor footprint. Returns (M, 3) vertex indices."""
    from scipy.spatial import Delaunay  # noqa: PLC0415

    if len(floor_points_2d) < 4:
        raise ValueError("need at least 4 floor points to triangulate a walkable area")
    return Delaunay(floor_points_2d).simplices


def carve_obstacles(vertices_2d: np.ndarray, triangles: np.ndarray, obstacle_points_2d: np.ndarray,
                    agent_radius: float) -> np.ndarray:
    """Pure: a boolean walkable mask per triangle -- False where its centroid is within `agent_radius` of
    a real wall/furniture point, i.e. an agent of that radius could not actually stand there."""
    if len(obstacle_points_2d) == 0:
        return np.ones(len(triangles), dtype=bool)
    from scipy.spatial import cKDTree  # noqa: PLC0415

    tree = cKDTree(obstacle_points_2d)
    centroids = vertices_2d[triangles].mean(axis=1)
    dist, _ = tree.query(centroids, k=1)
    return dist >= agent_radius


def write_walkable_obj(path: Path, vertices_3d: np.ndarray, triangles: np.ndarray, walkable_mask: np.ndarray) -> int:
    """Writes only the walkable triangles as a Wavefront OBJ (Recast's standard input format).
    Returns the number of triangles written."""
    kept = triangles[walkable_mask]
    used = sorted(set(int(i) for i in kept.flatten().tolist()))
    remap = {old: i + 1 for i, old in enumerate(used)}  # OBJ vertex indices are 1-based
    lines = [f"v {vertices_3d[i][0]:.6f} {vertices_3d[i][1]:.6f} {vertices_3d[i][2]:.6f}" for i in used]
    for tri in kept:
        lines.append(f"f {remap[int(tri[0])]} {remap[int(tri[1])]} {remap[int(tri[2])]}")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return len(kept)


# ---- Recast invocation ----------------------------------------------------------------------------

# The real rcConfig fields Recast's own build pipeline takes (see Recast's Config.h); this project's
# recast-cli is expected to accept each as a `--flagName value` argument and to build a standard
# solo-mesh navmesh, emitting its polygons as JSON (see parse_recast_polygons for the expected shape).
RECAST_CONFIG_FLAGS = (
    "cellSize", "cellHeight", "agentHeight", "agentRadius", "agentMaxClimb", "agentMaxSlope",
    "regionMinSize", "regionMergeSize", "edgeMaxLen", "edgeMaxError", "vertsPerPoly",
    "detailSampleDist", "detailSampleMaxError",
)


def build_recast_argv(recast_cli: str, input_obj: Path, output_json: Path, **config: float) -> list[str]:
    """Pure: the recast-cli command line. An unknown key in `config` is a programming error (a typo would
    otherwise silently build a wrong navmesh), so it raises rather than being dropped."""
    unknown = set(config) - set(RECAST_CONFIG_FLAGS)
    if unknown:
        raise ValueError(f"unknown Recast config keys: {sorted(unknown)}")
    argv = [recast_cli, "--input", str(input_obj), "--output", str(output_json)]
    for key, value in config.items():
        argv += [f"--{key}", str(value)]
    return argv


# ---- output side: Recast polygons to the routing graph -----------------------------------------------


@dataclass
class Polygon:
    id: int
    vertices: list[tuple[float, float, float]]
    neighbors: list[int]


def parse_recast_polygons(doc: dict[str, Any]) -> list[Polygon]:
    """Pure: validates and parses recast-cli's polygon JSON output."""
    polygons = []
    for raw in doc.get("polygons", []):
        vertices = [tuple(float(c) for c in v) for v in raw["vertices"]]
        if len(vertices) < 3:
            raise ValueError(f"polygon {raw.get('id')} has fewer than 3 vertices")
        polygons.append(Polygon(id=int(raw["id"]), vertices=vertices, neighbors=[int(n) for n in raw.get("neighbors", [])]))
    return polygons


def polygon_centroid(poly: Polygon) -> np.ndarray:
    return np.mean(np.array(poly.vertices), axis=0)


def polygon_slope_degrees(poly: Polygon) -> float:
    """Pure: angle between the polygon's own face normal and vertical (z-up), in degrees. 0 = flat."""
    v = np.array(poly.vertices[:3])
    normal = np.cross(v[1] - v[0], v[2] - v[0])
    norm = np.linalg.norm(normal)
    if norm < 1e-9:
        return 0.0
    normal = normal / norm
    cos_angle = abs(float(np.dot(normal, np.array([0.0, 0.0, 1.0]))))
    return float(np.degrees(np.arccos(np.clip(cos_angle, 0.0, 1.0))))


def shared_edge_length(a: Polygon, b: Polygon) -> float:
    """Pure: length of the portal (shared boundary) between two adjacent polygons -- the real corridor
    width at that passage, used as min_clearance_m. 0.0 if fewer than two vertices coincide (defensive:
    the neighbor list came from recast-cli's own output and is trusted for adjacency, but not blindly)."""
    va = {tuple(round(c, 4) for c in v) for v in a.vertices}
    vb = {tuple(round(c, 4) for c in v) for v in b.vertices}
    shared = list(va & vb)
    if len(shared) < 2:
        return 0.0
    return float(np.linalg.norm(np.array(shared[0]) - np.array(shared[1])))


def build_routing_graphs(polygons: list[Polygon], *, max_ramp_slope_deg: float) -> dict[str, dict[str, list]]:
    """Pure: turns Recast's polygon adjacency into the two profile graphs
    dev.chaya.api.navigation.RouteService pathfinds over. STEP_FREE keeps only the edges between two
    polygons that are BOTH within `max_ramp_slope_deg` of level -- a real per-polygon slope computation,
    excluding stairs and anything steeper than an accessible ramp. It is never a renamed copy of STANDARD:
    with any stairs present, STEP_FREE strictly has fewer edges.
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
        for n in p.neighbors:
            other = by_id.get(n)
            if other is None or (n, p.id) in seen:
                continue
            seen.add((p.id, n))
            length = float(np.linalg.norm(centroids[p.id] - centroids[other.id]))
            clearance = shared_edge_length(p, other)
            step_free = slopes[p.id] <= max_ramp_slope_deg and slopes[other.id] <= max_ramp_slope_deg
            edge = {"from": f"p{p.id}", "to": f"p{other.id}", "length_m": length, "step_free": step_free,
                   "min_clearance_m": clearance}
            standard_edges.append(edge)
            if step_free:
                step_free_edges.append(edge)

    return {"STANDARD": {"nodes": nodes, "edges": standard_edges}, "STEP_FREE": {"nodes": nodes, "edges": step_free_edges}}
