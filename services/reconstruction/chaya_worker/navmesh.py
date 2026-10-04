"""Navigation geometry in the Chaya canonical frame (chaya_worker.frames: metres, right-handed, +Z up).

This module prepares what the real Recast build consumes and interprets what the real Detour navmesh contains. It never
voxelises, builds regions, contours or polygons itself -- that is Recast's job, run by the chaya-navmesh tool
(services/reconstruction/native/chaya-navmesh, driven by chaya_worker.recast). It also never converts axes: every coordinate in and out of this
module is canonical, and only chaya_worker.recast crosses into Recast's +Y-up frame, through chaya_worker.recast_boundary.

Pieces:
  * NavGeometry: a triangle soup -- walkable candidates, walkable candidates next to a step (`level_change`) and blocked
    geometry -- read/written as a canonical-frame OBJ.
  * SurfaceModel / build_surface_model: what the cleaned reconstruction says about the floor, per horizontal grid cell:
    the walkable surface height (multi-level: floor, stair treads, ramps, raised areas), the obstacles standing on it
    (walls, columns, furniture, clutter, unlabelled geometry in the agent's height band), the steps between cells, and
    the clear width around every free cell (a distance transform of the obstacle/hole geometry).
  * geometry_from_surface: the NavGeometry Recast is given, built from a SurfaceModel.
  * Polygon / build_routing_graphs: the routing graphs dev.chaya.api.navigation.RouteService pathfinds over, taken from
    the polygons and links of the Detour navmesh Recast built, with each portal's clear width measured on the
    SurfaceModel.
  * compare_outside_region / obstacle_cells_under_navmesh: what an incremental re-scan's re-bake must preserve. Outside
    the changed region (plus the surface model's reach) the merged scene's surface model must equal the parent's, cell
    for cell, and no navmesh polygon may cover an obstacle cell there (docs/rescan.md, "NAVIGATION").
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import numpy as np

from .errors import DependencyError, StageError
from .frames import CANONICAL_UP

# ---- failure states -------------------------------------------------------------------------------------------------
# The structured error codes NAVIGATION_BAKING and route queries fail with. The route states are the API's
# (dev.chaya.api.navigation.RouteService); they are listed here so the set is in one place. The worker's own Detour
# path query (chaya_worker.recast.find_path) only ever fails with NO_ROUTE.

NAVMESH_TOOL_UNAVAILABLE = "NAVMESH_TOOL_UNAVAILABLE"
INVALID_GEOMETRY = "INVALID_GEOMETRY"
NO_WALKABLE_SURFACE = "NO_WALKABLE_SURFACE"
NAVMESH_BUILD_FAILED = "NAVMESH_BUILD_FAILED"
NAVMESH_REGION_INCONSISTENT = "NAVMESH_REGION_INCONSISTENT"
NAVMESH_NOT_READY = "NAVMESH_NOT_READY"
NO_ROUTE = "NO_ROUTE"
NO_ACCESSIBLE_ROUTE = "NO_ACCESSIBLE_ROUTE"
FLOOR_CONNECTION_UNAVAILABLE = "FLOOR_CONNECTION_UNAVAILABLE"
METRIC_CALIBRATION_REQUIRED = "METRIC_CALIBRATION_REQUIRED"


class NavmeshError(StageError):
    """A navmesh build or query failed in one of the ways above; `code` says which."""


class NavmeshToolUnavailable(DependencyError):
    """The chaya-navmesh tool (real Recast/Detour) is not installed on this worker. Never worked around."""

    code = NAVMESH_TOOL_UNAVAILABLE


# ---- geometry -------------------------------------------------------------------------------------------------------


@dataclass
class NavGeometry:
    """Canonical-frame triangles. `obstacle[i]` marks triangle i as blocked geometry: Recast rasterises it as solid but
    never walkable. `level_change[i]` marks a walkable candidate next to a step: Recast gives it its own area
    (AREA_LEVEL_CHANGE in chaya-navmesh), so the polygons over it are flagged and a step-free route never crosses them.
    Every non-obstacle triangle is walkable only if Recast's own slope/height/clearance tests pass."""

    vertices: np.ndarray  # (N, 3) float64, canonical metres
    triangles: np.ndarray  # (M, 3) int
    obstacle: np.ndarray = field(default=None)  # (M,) bool
    level_change: np.ndarray = field(default=None)  # (M,) bool

    def __post_init__(self) -> None:
        self.vertices = np.asarray(self.vertices, dtype=np.float64).reshape(-1, 3)
        self.triangles = np.asarray(self.triangles, dtype=np.int64).reshape(-1, 3)
        self.obstacle = (np.zeros(len(self.triangles), dtype=bool) if self.obstacle is None
                         else np.asarray(self.obstacle, dtype=bool).reshape(-1))
        self.level_change = (np.zeros(len(self.triangles), dtype=bool) if self.level_change is None
                             else np.asarray(self.level_change, dtype=bool).reshape(-1))

    def validate(self) -> None:
        """Raises INVALID_GEOMETRY for anything Recast should never be handed."""
        problems = []
        if len(self.triangles) == 0:
            problems.append("no triangles")
        if len(self.obstacle) != len(self.triangles) or len(self.level_change) != len(self.triangles):
            problems.append("obstacle or level-change mask length differs from the triangle count")
        elif (self.obstacle & self.level_change).any():
            problems.append("a triangle is both an obstacle and a level change")
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


def obj_groups(geometry: NavGeometry) -> list[tuple[str, np.ndarray]]:
    """The OBJ groups chaya-navmesh reads: walkable candidates, `level_change*` (walkable, next to a step) and
    `obstacle*` (blocked)."""
    walkable = ~geometry.obstacle & ~geometry.level_change
    return [("walkable_candidates", walkable), ("level_change", geometry.level_change), ("obstacle", geometry.obstacle)]


def write_obj(path: Path, geometry: NavGeometry, *, header: str = "") -> None:
    """Writes canonical-frame geometry as OBJ, in the groups of obj_groups. No axis conversion: this is the
    human-inspectable NAVMESH_INPUT_GEOMETRY artifact, in the same frame as every other canonical artifact."""
    lines = [f"# {ln}" for ln in header.splitlines()]
    lines += [f"v {v[0]:.6f} {v[1]:.6f} {v[2]:.6f}" for v in geometry.vertices]
    for group, mask in obj_groups(geometry):
        if mask.any():
            lines.append(f"g {group}")
            lines += [f"f {t[0] + 1} {t[1] + 1} {t[2] + 1}" for t in geometry.triangles[mask]]
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def read_obj(path: Path) -> NavGeometry:
    """Reads a canonical-frame OBJ (as write_obj writes, or a hand-made fixture). Faces under a group/object whose name
    starts with "obstacle" are blocked geometry, under one starting with "level_change" walkable next to a step.
    Polygons are fan-triangulated."""
    vertices: list[list[float]] = []
    triangles: list[list[int]] = []
    obstacle: list[bool] = []
    level_change: list[bool] = []
    in_obstacle = in_level_change = False
    for raw in path.read_text(encoding="utf-8").splitlines():
        parts = raw.split()
        if not parts or parts[0].startswith("#"):
            continue
        if parts[0] in ("g", "o"):
            in_obstacle = len(parts) > 1 and parts[1].startswith("obstacle")
            in_level_change = len(parts) > 1 and parts[1].startswith("level_change")
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
                level_change.append(in_level_change)
    return NavGeometry(np.array(vertices, dtype=np.float64).reshape(-1, 3), np.array(triangles, dtype=np.int64).reshape(-1, 3),
                       np.array(obstacle, dtype=bool), np.array(level_change, dtype=bool))


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


# Labels (chaya_worker.semantic_classes, after GEOMETRIC_CLEANUP) whose points are the walkable surface, and whose are
# obstacles when they stand in the agent's height band. "unknown" is an obstacle: geometry nobody could classify that
# stands where an agent walks is not assumed passable.
GROUND_LABELS = ("floor", "stairs")
OBSTACLE_LABELS = ("wall", "furniture", "clutter", "unknown")


_CELL_SAMPLES = np.array([[0.0, 0.0], [-0.25, -0.25], [0.25, -0.25], [0.25, 0.25], [-0.25, 0.25]])


@dataclass
class SurfaceModel:
    """What the cleaned reconstruction says about one floor, on a horizontal grid in canonical metres.

    Cell (i, j) covers x in [origin_x + i*cell_m, +cell_m), y likewise. Arrays are (nx, ny).
      ground_z      walkable surface height (canonical +Z, metres) -- the lowest dense band of floor/stairs points in
                    the cell; NaN where nothing was observed (a hole, never assumed walkable)
      obstacle      the cell holds obstacle geometry in the agent's height band above its local ground
      obstacle_top  the highest such obstacle point (metres), NaN elsewhere
      level_change  the cell borders a neighbour whose ground differs by more than step_min_rise_m and at most the
                    agent's climb: a step the agent can take but a step-free route must not
      clear_m       for each free cell (ground, no obstacle): the distance from its centre to the nearest obstacle or
                    unobserved cell's centre (scipy distance transform), metres; 0 elsewhere
      width_m       for each free cell: the clear width available to a body centred there (centred_width), capped at
                    width_cap_m; 0 elsewhere
      narrow        free, not a level change, and width_m below accessible_width_m: no body of the accessible width can be
                    centred there (reported; routing uses each edge's measured width, edge_clear_width_m)
    """

    origin: np.ndarray
    cell_m: float
    ground_z: np.ndarray
    obstacle: np.ndarray
    obstacle_top: np.ndarray
    level_change: np.ndarray
    clear_m: np.ndarray
    width_m: np.ndarray
    narrow: np.ndarray
    reference_z: float
    step_min_rise_m: float
    accessible_width_m: float
    width_cap_m: float

    @property
    def free(self) -> np.ndarray:
        return np.isfinite(self.ground_z) & ~self.obstacle

    def cell_of(self, xy: np.ndarray) -> np.ndarray:
        return np.floor((np.asarray(xy, dtype=np.float64)[..., :2] - self.origin) / self.cell_m).astype(np.int64)

    def _cells_in(self, poly: Polygon) -> list[tuple[int, int]]:
        v = np.array(poly.vertices, dtype=np.float64)
        lo, hi = self.cell_of(v[:, :2].min(axis=0)), self.cell_of(v[:, :2].max(axis=0))
        nx, ny = self.ground_z.shape
        out = []
        for i in range(max(lo[0], 0), min(hi[0], nx - 1) + 1):
            for j in range(max(lo[1], 0), min(hi[1], ny - 1) + 1):
                centre = self.origin + (np.array([i, j]) + 0.5) * self.cell_m
                # under the polygon if it covers the cell's centre or a quarter point: a thin polygon tip (in a doorway
                # Recast eroded to a few voxels) still claims the cells it overlaps
                if any(point_in_polygon_xy(centre + d, poly) for d in _CELL_SAMPLES * self.cell_m):
                    out.append((i, j))
        if not out:
            i, j = self.cell_of(polygon_centroid(poly)[:2])
            if 0 <= i < nx and 0 <= j < ny:
                out.append((i, j))
        return out

    def edge_clear_width_m(self, poly_a: Polygon, poly_b: Polygon) -> float:
        """Pure: the clear width a traveller has going from one polygon into a linked one: the bottleneck (the narrowest
        width_m) of the widest path from the roomiest cell of one to the roomiest cell of the other, through the free
        cells of the two polygons (8-connected). Found by bisecting over the widths present with connected-component
        labelling. It is the width of the best way through, wherever Recast put the polygons' boundaries and centres: a
        doorway inside a polygon has to be crossed; a thin polygon Recast fitted into a doorway is as wide as the door,
        not as narrow as its off-axis centroid. 0 when nothing joins them."""
        from scipy.ndimage import label  # noqa: PLC0415

        cells_a = [c for c in self._cells_in(poly_a) if self.free[c]]
        cells_b = [c for c in self._cells_in(poly_b) if self.free[c]]
        if not cells_a or not cells_b:
            return 0.0
        cells = set(cells_a) | set(cells_b)
        idx = np.array(sorted(cells))
        lo = idx.min(axis=0)
        shape = tuple(idx.max(axis=0) - lo + 1)
        width = np.full(shape, -1.0)
        width[idx[:, 0] - lo[0], idx[:, 1] - lo[1]] = self.width_m[idx[:, 0], idx[:, 1]]

        def roomiest(own: list[tuple[int, int]]) -> tuple[int, int]:
            i, j = max(own, key=lambda c: self.width_m[c])
            return i - int(lo[0]), j - int(lo[1])

        a, b = roomiest(cells_a), roomiest(cells_b)
        candidates = np.unique(width[width >= 0])
        eight = np.ones((3, 3), dtype=int)

        def joined(t: float) -> bool:
            labels, _ = label(width >= t, structure=eight)
            return labels[a] != 0 and labels[a] == labels[b]

        lo_i, hi_i = 0, len(candidates) - 1
        if not joined(candidates[0]):
            return 0.0
        while lo_i < hi_i:  # largest candidate width that still joins a and b
            mid = (lo_i + hi_i + 1) // 2
            if joined(candidates[mid]):
                lo_i = mid
            else:
                hi_i = mid - 1
        return float(candidates[lo_i])

    def height_at(self, xy: np.ndarray) -> float:
        """Pure: the reconstructed ground height of the cell containing `xy`, NaN outside the grid or over a hole."""
        i, j = self.cell_of(np.asarray(xy, dtype=np.float64)[:2])
        nx, ny = self.ground_z.shape
        return float(self.ground_z[i, j]) if 0 <= i < nx and 0 <= j < ny else float("nan")

    def cell_slope_deg(self) -> np.ndarray:
        """Pure: (nx, ny) surface slope per ground cell against canonical +Z, degrees, from central (else one-sided)
        differences of the ground heights of level neighbours (within step_min_rise_m; a step is not a slope). NaN over
        holes; 0 for a cell with no level neighbour."""
        g = self.ground_z
        grads = []
        for axis in (0, 1):
            fwd = np.full(g.shape, np.nan)
            bwd = np.full(g.shape, np.nan)
            if axis == 0:
                fwd[:-1, :] = g[1:, :] - g[:-1, :]
                bwd[1:, :] = g[1:, :] - g[:-1, :]
            else:
                fwd[:, :-1] = g[:, 1:] - g[:, :-1]
                bwd[:, 1:] = g[:, 1:] - g[:, :-1]
            with np.errstate(invalid="ignore"):
                fwd = np.where(np.abs(fwd) <= self.step_min_rise_m, fwd, np.nan)
                bwd = np.where(np.abs(bwd) <= self.step_min_rise_m, bwd, np.nan)
                both = np.isfinite(fwd) & np.isfinite(bwd)
                d = np.where(both, (fwd + bwd) / 2, np.where(np.isfinite(fwd), fwd, np.where(np.isfinite(bwd), bwd, 0.0)))
            grads.append(d / self.cell_m)
        slope = np.degrees(np.arctan(np.hypot(grads[0], grads[1])))
        return np.where(np.isfinite(g), slope, np.nan)

    def polygon_slope_deg(self, poly: Polygon, cell_slopes: np.ndarray | None = None) -> float:
        """Pure: the steepest reconstructed surface slope under a navmesh polygon: the max cell slope over the ground cells
        whose centres lie in its horizontal footprint (or the cell under its centroid, for a polygon smaller than a cell).
        NaN when there is no ground under it."""
        slopes = self.cell_slope_deg() if cell_slopes is None else cell_slopes
        found = [slopes[i, j] for i, j in self._cells_in(poly) if np.isfinite(slopes[i, j])]
        return float(max(found)) if found else float("nan")

    def summary(self) -> dict[str, Any]:
        g = np.isfinite(self.ground_z)
        return {"cell_m": self.cell_m, "cells": int(self.ground_z.size), "ground_cells": int(g.sum()),
                "obstacle_cells": int(self.obstacle.sum()), "free_cells": int(self.free.sum()),
                "level_change_cells": int(self.level_change.sum()), "narrow_cells_below_accessible_width": int(self.narrow.sum()),
                "accessible_width_m": self.accessible_width_m, "width_cap_m": self.width_cap_m,
                "ground_z_min_m": float(np.nanmin(self.ground_z)) if g.any() else None,
                "ground_z_max_m": float(np.nanmax(self.ground_z)) if g.any() else None,
                "step_min_rise_m": self.step_min_rise_m}


def _lowest_dense_band(z_sorted: np.ndarray, band_m: float, min_points: int) -> float | None:
    """Pure: the median height of the lowest run of at least `min_points` heights spanning at most `band_m`, or None."""
    if len(z_sorted) < min_points:
        return None
    ends = np.searchsorted(z_sorted, z_sorted + band_m, side="right")
    dense = np.flatnonzero(ends - np.arange(len(z_sorted)) >= min_points)
    if len(dense) == 0:
        return None
    i = int(dense[0])
    return float(np.median(z_sorted[i:ends[i]]))


def centred_width(clear_m: np.ndarray, free: np.ndarray, cell_m: float, cap_m: float) -> np.ndarray:
    """Pure: per free cell, the clear width available to a body CENTRED there: 2 x the distance from the cell's centre
    to the nearest obstacle or hole cell's centre, less one cell (that distance runs centre to centre, so this never
    overstates), capped at cap_m; 0 elsewhere. A body of width W can travel along a path exactly when every cell on it
    has centred width >= W -- the same test Recast's agent-radius erosion applies for the standard agent, here for the
    accessible width. In a 1.1 m doorway the middle cells are ~1.1 m; in a 0.85 m one no cell reaches 0.9 m."""
    return np.where(free, np.clip(2.0 * clear_m - cell_m, 0.0, cap_m), 0.0)


def build_surface_model(ground_points: np.ndarray, obstacle_points: np.ndarray, *, reference_z: float, cell_m: float,
                        min_points_per_cell: int, ground_band_m: float, agent_max_climb_m: float, agent_height_m: float,
                        step_min_rise_m: float, max_level_above_reference_m: float, accessible_width_m: float,
                        width_cap_m: float) -> SurfaceModel:
    """Pure: the SurfaceModel of one floor from cleaned canonical-frame points.

    ground_points    floor/stairs-labelled points (GROUND_LABELS)
    obstacle_points  wall/furniture/clutter/unknown points (OBSTACLE_LABELS)
    reference_z      the floor plane's height: ground is looked for from 0.5 m below it to max_level_above_reference_m
                     above it (raised areas, landings, treads), never on a table or a shelf above that.

    Ground: per cell, the lowest band of at least `min_points_per_cell` ground points no thicker than `ground_band_m`
    (so a stray point under the floor or a rug edge does not decide the height). A cell without one is a hole.
    Obstacles: per cell, any obstacle point between `agent_max_climb_m` and `agent_height_m` above the cell's ground
    (or, where the ground under it was not observed, the lowest observed neighbour ground, else reference_z). What an
    agent can step over, and what is above its head, does not block.
    Level changes: 4-neighbour cells whose ground heights differ by more than `step_min_rise_m` and at most
    `agent_max_climb_m` -- both cells are marked. A larger difference is a ledge, which Recast's climb filter already
    refuses to connect.
    Widths: centred_width over the free cells; free cells where it is below `accessible_width_m` (and not level changes)
    are `narrow`.
    """
    if cell_m <= 0 or min_points_per_cell < 1 or ground_band_m <= 0 or step_min_rise_m <= 0:
        raise ValueError("cell_m, ground_band_m and step_min_rise_m must be positive and min_points_per_cell at least 1")
    ground_points = np.asarray(ground_points, dtype=np.float64).reshape(-1, 3)
    obstacle_points = np.asarray(obstacle_points, dtype=np.float64).reshape(-1, 3)
    lo, hi = reference_z - 0.5, reference_z + max_level_above_reference_m
    ground_points = ground_points[(ground_points[:, 2] >= lo) & (ground_points[:, 2] <= hi)]
    obstacle_points = obstacle_points[(obstacle_points[:, 2] >= lo) & (obstacle_points[:, 2] <= hi + agent_height_m)]
    if len(ground_points) == 0:
        raise NavmeshError("no floor or stairs point lies within the floor's height range", code=NO_WALKABLE_SURFACE,
                           details={"reference_z": reference_z})
    xy = np.vstack([ground_points[:, :2], obstacle_points[:, :2]])
    origin = np.floor(xy.min(axis=0) / cell_m) * cell_m
    shape = tuple(int(v) for v in np.floor((xy.max(axis=0) - origin) / cell_m).astype(np.int64) + 1)
    nx, ny = shape

    # ground: lowest dense band per cell
    ground_z = np.full(shape, np.nan)
    gij = np.floor((ground_points[:, :2] - origin) / cell_m).astype(np.int64)
    flat = gij[:, 0] * ny + gij[:, 1]
    order = np.lexsort((ground_points[:, 2], flat))
    flat_sorted, z_sorted = flat[order], ground_points[order, 2]
    cells, starts = np.unique(flat_sorted, return_index=True)
    bounds = np.append(starts, len(flat_sorted))
    for c, a, b in zip(cells, bounds[:-1], bounds[1:], strict=True):
        h = _lowest_dense_band(z_sorted[a:b], ground_band_m, min_points_per_cell)
        if h is not None:
            ground_z[c // ny, c % ny] = h

    # local reference for obstacles: the cell's own ground, else the lowest observed 8-neighbour ground, else the floor
    padded = np.pad(ground_z, 1, constant_values=np.nan)
    neighbours = np.stack([padded[1 + di:1 + di + nx, 1 + dj:1 + dj + ny] for di in (-1, 0, 1) for dj in (-1, 0, 1)])
    with np.errstate(all="ignore"):
        neighbour_min = np.where(np.isfinite(neighbours).any(axis=0), np.nanmin(np.where(np.isfinite(neighbours), neighbours, np.inf), axis=0),
                                 np.nan)
    local_ref = np.where(np.isfinite(ground_z), ground_z, np.where(np.isfinite(neighbour_min), neighbour_min, reference_z))

    obstacle = np.zeros(shape, dtype=bool)
    obstacle_top = np.full(shape, np.nan)
    if len(obstacle_points):
        oij = np.floor((obstacle_points[:, :2] - origin) / cell_m).astype(np.int64)
        above = obstacle_points[:, 2] - local_ref[oij[:, 0], oij[:, 1]]
        band = (above > agent_max_climb_m) & (above < agent_height_m)
        if band.any():
            bij = oij[band]
            obstacle[bij[:, 0], bij[:, 1]] = True
            tops = np.full(shape, -np.inf)
            np.maximum.at(tops, (bij[:, 0], bij[:, 1]), obstacle_points[band, 2])
            obstacle_top = np.where(obstacle, tops, np.nan)

    # steps between 4-neighbours
    level_change = np.zeros(shape, dtype=bool)
    for axis in (0, 1):
        a = ground_z[:-1, :] if axis == 0 else ground_z[:, :-1]
        b = ground_z[1:, :] if axis == 0 else ground_z[:, 1:]
        with np.errstate(invalid="ignore"):
            rise = np.abs(a - b)
            step = np.isfinite(rise) & (rise > step_min_rise_m) & (rise <= agent_max_climb_m)
        if axis == 0:
            level_change[:-1, :] |= step
            level_change[1:, :] |= step
        else:
            level_change[:, :-1] |= step
            level_change[:, 1:] |= step

    from scipy.ndimage import distance_transform_edt  # noqa: PLC0415

    free = np.isfinite(ground_z) & ~obstacle
    clear = distance_transform_edt(np.pad(free, 1, constant_values=False), sampling=cell_m)[1:-1, 1:-1]
    clear = np.where(free, clear, 0.0)
    width = centred_width(clear, free, cell_m, width_cap_m)
    narrow = free & ~level_change & (width < accessible_width_m)
    return SurfaceModel(origin, float(cell_m), ground_z, obstacle, obstacle_top, level_change, clear, width, narrow,
                        float(reference_z), float(step_min_rise_m), float(accessible_width_m), float(width_cap_m))


# ---- what an incremental re-scan's re-bake must preserve ---------------------------------------------------------------

# A cell's surface-model fields depend only on the points in its 3 x 3 neighbourhood: its ground on its own points, its
# obstacle reference and level changes on its neighbours'. A cell whose centre is more than 1.5 * sqrt(2) cells from the
# region polygon therefore sees only geometry the splice kept unchanged; 3 cells leaves room for rounding.
RESCAN_INFLUENCE_CELLS = 3.0


def cells_outside_region(origin: np.ndarray, shape: tuple[int, int], cell_m: float, polygon_xy: np.ndarray, margin_m: float,
                         chunk: int = 50_000) -> np.ndarray:
    """Pure: (nx, ny) bool, True for grid cells (origin, cell_m) whose centre is outside `polygon_xy` by more than
    `margin_m`. Only cells near the polygon's bounding box are tested against it; the rest are outside by construction."""
    from .region_splice import distance_to_polygon_edge, point_in_polygon  # noqa: PLC0415

    polygon_xy = np.asarray(polygon_xy, dtype=np.float64)
    ii, jj = np.indices(shape)
    centres = np.asarray(origin, dtype=np.float64) + (np.stack([ii.ravel(), jj.ravel()], axis=1) + 0.5) * cell_m
    outside = np.ones(len(centres), dtype=bool)
    near = np.flatnonzero(np.all((centres >= polygon_xy.min(axis=0) - margin_m) & (centres <= polygon_xy.max(axis=0) + margin_m), axis=1))
    for start in range(0, len(near), chunk):
        k = near[start:start + chunk]
        outside[k] = ~point_in_polygon(centres[k], polygon_xy) & (distance_to_polygon_edge(centres[k], polygon_xy) > margin_m)
    return outside.reshape(shape)


def compare_outside_region(parent: SurfaceModel, merged: SurfaceModel, polygon_xy: np.ndarray, *, margin_m: float) -> dict[str, Any]:
    """Pure: whether the merged scene's surface model equals the parent's everywhere outside the changed region.

    Both models are placed on their common grid (their origins are multiples of cell_m, so the cells coincide). Every
    cell whose centre is more than `margin_m` outside the polygon and that either model observed is compared: observed
    ground and its height, obstacle, level change. The splice keeps every Gaussian outside the polygon and their labels
    unchanged, so any difference there means the re-bake would not preserve the venue (mismatched or corrupted inputs),
    and `consistent` is False. Returns the counts, the obstacle cells on each side, and up to 10 differing cell centres."""
    if parent.cell_m != merged.cell_m:
        raise ValueError(f"the surface models have different cells ({parent.cell_m} m, {merged.cell_m} m)")
    c = merged.cell_m
    po = np.round(parent.origin / c).astype(np.int64)
    mo = np.round(merged.origin / c).astype(np.int64)
    lo = np.minimum(po, mo)
    hi = np.maximum(po + np.array(parent.ground_z.shape), mo + np.array(merged.ground_z.shape))
    shape = (int(hi[0] - lo[0]), int(hi[1] - lo[1]))

    def place(model: SurfaceModel, o: np.ndarray) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
        g, ob, lc = np.full(shape, np.nan), np.zeros(shape, dtype=bool), np.zeros(shape, dtype=bool)
        i0, j0 = (int(v) for v in o - lo)
        nx, ny = model.ground_z.shape
        g[i0:i0 + nx, j0:j0 + ny] = model.ground_z
        ob[i0:i0 + nx, j0:j0 + ny] = model.obstacle
        lc[i0:i0 + nx, j0:j0 + ny] = model.level_change
        return g, ob, lc

    pg, pob, plc = place(parent, po)
    mg, mob, mlc = place(merged, mo)
    origin = lo * c
    outside = cells_outside_region(origin, shape, c, polygon_xy, margin_m)
    pfin, mfin = np.isfinite(pg), np.isfinite(mg)
    relevant = outside & (pfin | mfin | pob | mob)
    with np.errstate(invalid="ignore"):
        height_moved = pfin & mfin & (np.abs(pg - mg) > 1e-6)
    ground_diff = relevant & ((pfin != mfin) | height_moved)
    obstacle_lost = relevant & pob & ~mob
    obstacle_added = relevant & ~pob & mob
    level_diff = relevant & (plc != mlc)
    differing = ground_diff | obstacle_lost | obstacle_added | level_diff
    di, dj = np.nonzero(differing)
    examples = (origin + (np.stack([di, dj], axis=1)[:10] + 0.5) * c).round(3).tolist()
    return {"consistent": not bool(differing.any()), "margin_m": float(margin_m), "cell_m": c,
            "cells_compared": int(relevant.sum()), "cells_differing": int(differing.sum()),
            "ground_cells_differing": int(ground_diff.sum()), "obstacle_cells_lost": int(obstacle_lost.sum()),
            "obstacle_cells_added": int(obstacle_added.sum()), "level_change_cells_differing": int(level_diff.sum()),
            "obstacle_cells_outside_region": {"parent": int((outside & pob).sum()), "merged": int((outside & mob).sum())},
            "differing_cell_centres_xy": examples}


def obstacle_cells_under_navmesh(polygons: list[Polygon], model: SurfaceModel, mask: np.ndarray) -> np.ndarray:
    """Pure: the centres (k, 2) of the obstacle cells within `mask` that lie inside the horizontal footprint of a navmesh
    polygon -- obstacles the navmesh would let an agent walk through. Recast erodes the walkable area by the agent radius
    around every obstacle box, so there should be none; a re-bake that has any outside the changed region is refused."""
    from .region_splice import point_in_polygon  # noqa: PLC0415

    candidates = model.obstacle & mask
    hit = np.zeros_like(candidates)
    top = np.array(candidates.shape) - 1
    for poly in polygons if candidates.any() else []:
        v = np.array(poly.vertices, dtype=np.float64)[:, :2]
        (i0, j0), (i1, j1) = np.clip(model.cell_of(v.min(axis=0)), 0, top), np.clip(model.cell_of(v.max(axis=0)), 0, top)
        ii, jj = np.nonzero(candidates[i0:i1 + 1, j0:j1 + 1] & ~hit[i0:i1 + 1, j0:j1 + 1])  # only the cells under its box
        if len(ii):
            ii, jj = ii + i0, jj + j0
            inside = point_in_polygon(model.origin + (np.stack([ii, jj], axis=1) + 0.5) * model.cell_m, v)
            hit[ii[inside], jj[inside]] = True
    hi, hj = np.nonzero(hit)
    return model.origin + (np.stack([hi, hj], axis=1) + 0.5) * model.cell_m


def geometry_from_surface(model: SurfaceModel, *, obstacle_min_height_m: float) -> NavGeometry:
    """Pure: the triangle soup Recast bakes, from a SurfaceModel (canonical metres).

    Walkable candidates: one quad per ground cell. Its corners are at the mean ground height of the cells sharing the
    corner that are level with it (within step_min_rise_m), so a ramp or a gently uneven floor is one continuous
    sloped surface whose real slope Recast's slope test sees, while a step stays a discontinuity. Quads of
    level-change cells are written under `level_change`.
    Obstacles: a closed box per obstacle cell, from 0.1 m below the lowest ground on the floor to the highest obstacle
    point in it (at least `obstacle_min_height_m` above its local ground), marked blocked. Recast rasterises it as solid,
    so the walkable surface under and beside it is cut out and eroded by the agent radius like any other solid.
    Holes (unobserved cells) produce no geometry at all: Recast never sees a surface there.
    """
    c = model.cell_m
    gz = model.ground_z
    nx, ny = gz.shape
    verts: list[np.ndarray] = []
    tris: list[np.ndarray] = []
    blocked: list[np.ndarray] = []
    step: list[np.ndarray] = []
    offset = 0

    ii, jj = np.nonzero(np.isfinite(gz))
    if len(ii):
        padded = np.pad(gz, 1, constant_values=np.nan)
        h = gz[ii, jj]
        corners = []
        for dx, dy in ((0, 0), (1, 0), (1, 1), (0, 1)):
            # the 4 cells sharing corner (i+dx, j+dy): padded indices (i+dx+a, j+dy+b) for a, b in {0, 1}
            vals = np.stack([padded[ii + dx + a, jj + dy + b] for a in (0, 1) for b in (0, 1)], axis=1)
            with np.errstate(invalid="ignore"):
                level = np.isfinite(vals) & (np.abs(vals - h[:, None]) <= model.step_min_rise_m)
            z = np.where(level, vals, 0.0).sum(axis=1) / level.sum(axis=1)  # the cell itself is always level with itself
            x = model.origin[0] + (ii + dx) * c
            y = model.origin[1] + (jj + dy) * c
            corners.append(np.stack([x, y, z], axis=1))
        n = len(ii)
        verts.append(np.stack(corners, axis=1).reshape(-1, 3))
        base = offset + 4 * np.arange(n)[:, None]
        # Counter-clockwise seen from +Z: the face normal points up, as Recast's slope test expects after the boundary.
        tris.append(np.concatenate([base + [0, 1, 2], base + [0, 2, 3]]))
        blocked.append(np.zeros(2 * n, dtype=bool))
        lc = model.level_change[ii, jj]
        step.append(np.concatenate([lc, lc]))
        offset += 4 * n

    oi, oj = np.nonzero(model.obstacle)
    if len(oi):
        bottom = float(np.nanmin(gz)) - 0.1 if np.isfinite(gz).any() else model.reference_z - 0.1
        local = np.where(np.isfinite(gz[oi, oj]), gz[oi, oj], model.reference_z)
        tops = np.maximum(model.obstacle_top[oi, oj], local + obstacle_min_height_m)
        x0 = model.origin[0] + oi * c
        y0 = model.origin[1] + oj * c
        zb = np.full(len(oi), bottom)
        corners = []
        for z in (zb, tops):
            corners += [np.stack([x0, y0, z], 1), np.stack([x0 + c, y0, z], 1),
                        np.stack([x0 + c, y0 + c, z], 1), np.stack([x0, y0 + c, z], 1)]
        n = len(oi)
        verts.append(np.stack(corners, 1).reshape(-1, 3))
        box = np.array([[0, 2, 1], [0, 3, 2], [4, 5, 6], [4, 6, 7], [0, 1, 5], [0, 5, 4],
                        [1, 2, 6], [1, 6, 5], [2, 3, 7], [2, 7, 6], [3, 0, 4], [3, 4, 7]])
        tris.append((offset + 8 * np.arange(n)[:, None, None] + box[None]).reshape(-1, 3))
        blocked.append(np.ones(12 * n, dtype=bool))
        step.append(np.zeros(12 * n, dtype=bool))

    if not verts:
        return NavGeometry(np.zeros((0, 3)), np.zeros((0, 3), dtype=np.int64))
    return NavGeometry(np.vstack(verts), np.vstack(tris), np.concatenate(blocked), np.concatenate(step))


# ---- the baked navmesh's polygons, and routing graphs from them ------------------------------------------------------


@dataclass
class Link:
    """A Detour link from one polygon to a neighbour, with the portal edge (two canonical points) it crosses."""

    neighbor: int
    portal: tuple[tuple[float, float, float], tuple[float, float, float]]


# chaya-navmesh's Detour polygon flags (services/reconstruction/native/chaya-navmesh/src/main.cpp).
POLYFLAG_WALK = 0x01
POLYFLAG_LEVEL_CHANGE = 0x02


@dataclass
class Polygon:
    id: int
    vertices: list[tuple[float, float, float]]
    links: list[Link] = field(default_factory=list)
    area: int = 63  # Recast area id: 63 walkable, 1 level change (AREA_LEVEL_CHANGE)
    flags: int = POLYFLAG_WALK

    @property
    def level_change(self) -> bool:
        """The polygon lies over a step (NavGeometry.level_change): walkable, never step-free."""
        return bool(self.flags & POLYFLAG_LEVEL_CHANGE)

    @property
    def neighbors(self) -> list[int]:
        return [link.neighbor for link in self.links]


def polygon_centroid(poly: Polygon) -> np.ndarray:
    return np.mean(np.array(poly.vertices), axis=0)


def polygon_slope_degrees(poly: Polygon) -> float:
    """Pure: angle between the polygon's own face normal and the canonical up axis (CANONICAL_UP, +Z), in degrees.
    0 = flat. Meaningful only because the polygon is in the canonical frame, where +Z is opposite to gravity (the frame's
    gravity alignment, docs/coordinate-frames.md) and units are metres."""
    v = np.array(poly.vertices, dtype=np.float64)
    normal = np.zeros(3)
    for i in range(1, len(v) - 1):  # Newell-style sum over the fan: robust to a degenerate first triangle
        normal += np.cross(v[i] - v[0], v[i + 1] - v[0])
    norm = np.linalg.norm(normal)
    if norm < 1e-12:
        return 0.0
    cos_angle = abs(float(np.dot(normal / norm, CANONICAL_UP)))
    return float(np.degrees(np.arccos(np.clip(cos_angle, 0.0, 1.0))))


def edge_rise_run(a: np.ndarray, b: np.ndarray) -> tuple[float, float]:
    """Pure: the vertical rise (along CANONICAL_UP, metres, signed) and the horizontal run (metres, perpendicular to
    CANONICAL_UP) from canonical point `a` to `b`. Never reads a coordinate index as "up": the up axis is the frame's."""
    d = np.asarray(b, dtype=np.float64) - np.asarray(a, dtype=np.float64)
    rise = float(d @ CANONICAL_UP)
    run = float(np.linalg.norm(d - rise * CANONICAL_UP))
    return rise, run


def edge_grade_degrees(a: np.ndarray, b: np.ndarray) -> float:
    """Pure: the grade of the straight segment from `a` to `b` against the canonical horizontal, in degrees (0 = level,
    90 = vertical). Two level polygons at different heights -- a step or a stair flight between two treads -- have a
    non-zero grade even though each polygon on its own is flat."""
    rise, run = edge_rise_run(a, b)
    return float(np.degrees(np.arctan2(abs(rise), run)))


def portal_width(link: Link) -> float:
    """Pure: the length of the portal edge Detour links two polygons through. Because Recast already eroded the walkable
    area by the agent radius, this is the free width left for the agent's centre, not the wall-to-wall corridor width.
    Recorded as `portal_width_m`; the clearance routing uses (`min_clearance_m`) is measured on the obstacle geometry
    (SurfaceModel.edge_clear_width_m)."""
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


def build_routing_graphs(polygons: list[Polygon], *, max_ramp_slope_deg: float, surface: SurfaceModel) -> dict[str, dict[str, list]]:
    """Pure: turns the Detour navmesh's polygon links into the two profile graphs dev.chaya.api.navigation.RouteService
    pathfinds over. Nodes are polygon centroids (horizontal position from Detour, height from the reconstructed surface
    where there is one). An edge is a Detour link, recording what was measured on the canonical geometry:

      length_m         3-D centroid-to-centroid distance, metres.
      rise_m           signed rise along CANONICAL_UP between the centroids, metres.
      max_slope_deg    the steepest of: the reconstructed surface slope under each polygon (SurfaceModel.polygon_slope_deg)
                       and the grade of the centroid-to-centroid segment, against CANONICAL_UP. Heights come from the
                       reconstructed surface (medians of real points), not from Recast's polygons, whose heights are
                       quantised to the cell height and would turn a gentle ramp into false 5-10 degree grades; Recast's
                       own polygon geometry is used only where the surface has no ground. The grade catches a step
                       between two level polygons.
      level_change     either polygon lies over a step the surface model found (Polygon.level_change).
      min_clearance_m  the clear width along the edge, measured on the obstacle and hole geometry
                       (SurfaceModel.edge_clear_width_m: the bottleneck of the widest path from the roomiest cell of
                       one polygon to the roomiest cell of the other, through their free cells), metres.
      portal_width_m   the Detour portal's own length (after Recast's agent-radius erosion), metres.
      portal           the portal's two endpoints, canonical metres: the API string-pulls the route through them.

    STEP_FREE keeps only the edges that cross no level change and whose max_slope_deg is within `max_ramp_slope_deg` --
    excluding stairs, steps and anything steeper than an accessible ramp. It is never a renamed copy of STANDARD. A
    polygon or edge whose slope cannot be computed is never step-free.
    """
    by_id = {p.id: p for p in polygons}
    cell_slopes = surface.cell_slope_deg()
    slopes, centroids = {}, {}
    for p in polygons:
        c = polygon_centroid(p)
        h = surface.height_at(c)
        centroids[p.id] = np.array([c[0], c[1], h if np.isfinite(h) else c[2]])
        measured = surface.polygon_slope_deg(p, cell_slopes)
        slopes[p.id] = measured if np.isfinite(measured) else polygon_slope_degrees(p)
    nodes = [{"local_id": f"p{p.id}", "kind": "WAYPOINT", "x": float(centroids[p.id][0]),
             "y": float(centroids[p.id][1]), "z": float(centroids[p.id][2]), "connector_type": None,
             "level_change": p.level_change} for p in polygons]

    seen: set[tuple[int, int]] = set()
    standard_edges: list[dict[str, Any]] = []
    step_free_edges: list[dict[str, Any]] = []
    for p in polygons:
        for link in p.links:
            other = by_id.get(link.neighbor)
            if other is None or (other.id, p.id) in seen or other.id == p.id:
                continue
            seen.add((p.id, other.id))
            a, b = centroids[p.id], centroids[other.id]
            length = float(np.linalg.norm(b - a))
            if not np.isfinite(length) or length <= 0:
                continue  # coincident centroids: no measurable edge
            rise, _ = edge_rise_run(a, b)
            max_slope = max(slopes[p.id], slopes[other.id], edge_grade_degrees(a, b))
            level_change = p.level_change or other.level_change
            step_free = bool(np.isfinite(max_slope) and max_slope <= max_ramp_slope_deg and not level_change)
            pa, pb = (np.array(q, dtype=np.float64) for q in link.portal)
            edge = {"from": f"p{p.id}", "to": f"p{other.id}", "length_m": length, "rise_m": rise,
                    "max_slope_deg": float(max_slope), "level_change": level_change, "step_free": step_free,
                    "min_clearance_m": surface.edge_clear_width_m(p, other),
                    "portal_width_m": portal_width(link),
                    "portal": [[float(v) for v in pa], [float(v) for v in pb]]}
            standard_edges.append(edge)
            if step_free:
                step_free_edges.append(edge)

    return {"STANDARD": {"nodes": nodes, "edges": standard_edges}, "STEP_FREE": {"nodes": nodes, "edges": step_free_edges}}

