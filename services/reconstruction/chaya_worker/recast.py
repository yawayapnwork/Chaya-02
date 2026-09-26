"""The real Recast/Detour integration: runs the chaya-navmesh tool (services/reconstruction/native/chaya-navmesh, a small CLI linked against the
pinned upstream recastnavigation release) to bake a Detour navmesh and to query paths on it.

This is the only module that talks to Recast, and so the only module that crosses between the Chaya canonical frame
(metres, +Z up) and Recast's (metres, +Y up) -- always through chaya_worker.recast_boundary:

    canonical NavGeometry --canonical_to_recast--> Recast-frame OBJ --> chaya-navmesh bake --> Detour tile + report
    report polygons/portals, path points --recast_to_canonical--> canonical Polygon / path points

Every value handed to Recast is a metre (or degree, or a count) quantity from RecastConfig; the tool converts them to
Recast's voxel units itself and echoes both, so the navmesh artifact records exactly what the library was given.
"""

from __future__ import annotations

import hashlib
import json
import subprocess
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Protocol

import numpy as np

from .navmesh import (
    INVALID_GEOMETRY,
    NAVMESH_BUILD_FAILED,
    NO_WALKABLE_SURFACE,
    ROUTE_UNAVAILABLE,
    Link,
    NavGeometry,
    NavmeshError,
    NavmeshToolUnavailable,
    Polygon,
)
from .recast_boundary import CONVERSION, RECAST_UP_AXIS, canonical_half_extents_to_recast, canonical_to_recast, recast_to_canonical

TOOL_NAME = "chaya-navmesh"
TOOL_REQUIREMENT = f"exe:{TOOL_NAME}"  # resolved by chaya_worker.toolchain: CHAYA_NAVMESH_BIN, else PATH
NAVMESH_FORMAT = "detour-tile"  # the raw dtCreateNavMeshData output: loadable with dtNavMesh::init(data, size, flags)

# chaya-navmesh's exit statuses (services/reconstruction/native/chaya-navmesh/src/main.cpp) -> this project's failure states.
_EXIT_CODES = {3: INVALID_GEOMETRY, 4: NO_WALKABLE_SURFACE, 5: NAVMESH_BUILD_FAILED, 6: ROUTE_UNAVAILABLE}


class Runner(Protocol):
    def run(self, argv: list[str], *, check: bool = ..., error_code: str = ..., timeout: float | None = ...) -> subprocess.CompletedProcess: ...


@dataclass(frozen=True)
class RecastConfig:
    """Recast's build settings in canonical physical units. Lengths are metres, heights are along canonical +Z (Recast's
    +Y after the boundary), areas are square metres, the slope is degrees from level. verts_per_poly is a count."""

    cell_size_m: float
    cell_height_m: float
    agent_height_m: float
    agent_radius_m: float
    agent_max_climb_m: float
    agent_max_slope_deg: float
    region_min_area_m2: float
    region_merge_area_m2: float
    edge_max_len_m: float
    edge_max_error_m: float
    verts_per_poly: int
    detail_sample_dist_m: float
    detail_sample_max_error_m: float

    @classmethod
    def from_settings(cls, s: Any) -> RecastConfig:
        return cls(cell_size_m=s.navmesh_cell_size_m, cell_height_m=s.navmesh_cell_height_m, agent_height_m=s.navmesh_agent_height_m,
                   agent_radius_m=s.navmesh_agent_radius_m, agent_max_climb_m=s.navmesh_agent_max_climb_m,
                   agent_max_slope_deg=s.navmesh_agent_max_slope_deg, region_min_area_m2=s.navmesh_region_min_area_m2,
                   region_merge_area_m2=s.navmesh_region_merge_area_m2, edge_max_len_m=s.navmesh_edge_max_len_m,
                   edge_max_error_m=s.navmesh_edge_max_error_m, verts_per_poly=int(s.navmesh_verts_per_poly),
                   detail_sample_dist_m=s.navmesh_detail_sample_dist_m, detail_sample_max_error_m=s.navmesh_detail_sample_max_error_m)

    def as_dict(self) -> dict[str, float]:
        return asdict(self)

    def argv(self) -> list[str]:
        """The tool's flags: each field name with `_` -> `-` (e.g. cell_size_m -> --cell-size-m). The tool has no defaults."""
        out: list[str] = []
        for key, value in self.as_dict().items():
            out += [f"--{key.replace('_', '-')}", repr(float(value)) if key != "verts_per_poly" else str(int(value))]
        return out


@dataclass
class NavmeshBuild:
    navmesh_path: Path
    report: dict[str, Any]  # the tool's own report, Recast-frame coordinates as the tool wrote them
    polygons: list[Polygon]  # canonical

    @property
    def sha256(self) -> str:
        return sha256_file(self.navmesh_path)


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def tool_version(tool_path: str) -> dict[str, str]:
    """Asks the binary what it is. Anything that does not answer as chaya-navmesh is not the tool."""
    try:
        out = subprocess.run([tool_path, "--version"], capture_output=True, text=True, timeout=20)
    except (OSError, subprocess.SubprocessError) as exc:
        raise NavmeshToolUnavailable(f"{tool_path} could not be run: {exc}", details={"missing": [TOOL_NAME], "path": tool_path}) from exc
    parts = out.stdout.split()
    if out.returncode != 0 or len(parts) != 4 or parts[0] != TOOL_NAME or parts[2] != "recastnavigation":
        raise NavmeshToolUnavailable(f"{tool_path} is not a {TOOL_NAME} binary (--version said {out.stdout.strip()!r})",
                                     details={"missing": [TOOL_NAME], "path": tool_path})
    return {"tool": TOOL_NAME, "tool_version": parts[1], "recastnavigation_version": parts[3]}


def resolve_tool(toolchain: Any) -> tuple[str, dict[str, str]]:
    """The tool's path and version, or NAVMESH_TOOL_UNAVAILABLE."""
    status = toolchain.status(TOOL_REQUIREMENT)
    if not status.available or not status.path:
        raise NavmeshToolUnavailable(f"NAVIGATION_BAKING needs the {TOOL_NAME} tool (Recast/Detour, services/reconstruction/native/chaya-navmesh); "
                                     f"{status.detail or 'it was not found'}",
                                     details={"missing": [TOOL_NAME], "tools": [status.as_dict()]})
    return status.path, tool_version(status.path)


# ---- boundary crossings (the only ones) ------------------------------------------------------------------------------


def write_recast_obj(path: Path, geometry: NavGeometry) -> None:
    """The geometry as the tool reads it: Recast-frame vertices, blocked triangles under `g obstacle`."""
    recast = canonical_to_recast(geometry.vertices)
    lines = [f"v {v[0]:.6f} {v[1]:.6f} {v[2]:.6f}" for v in recast]
    for group, mask in (("walkable_candidates", ~geometry.obstacle), ("obstacle", geometry.obstacle)):
        if mask.any():
            lines.append(f"g {group}")
            lines += [f"f {t[0] + 1} {t[1] + 1} {t[2] + 1}" for t in geometry.triangles[mask]]
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def _canonical_point(p: list[float]) -> tuple[float, float, float]:
    c = recast_to_canonical(np.array(p, dtype=np.float64))
    return (float(c[0]), float(c[1]), float(c[2]))


def polygons_from_report(report: dict[str, Any]) -> list[Polygon]:
    """The Detour navmesh's ground polygons and links, converted to canonical coordinates."""
    polygons = []
    for raw in report.get("polygons", []):
        vertices = [_canonical_point(v) for v in raw["vertices"]]
        if len(vertices) < 3:
            raise NavmeshError(f"navmesh polygon {raw.get('id')} has fewer than 3 vertices", code=NAVMESH_BUILD_FAILED)
        links = [Link(int(link["neighbor"]), (_canonical_point(link["portal"][0]), _canonical_point(link["portal"][1])))
                 for link in raw.get("links", [])]
        polygons.append(Polygon(int(raw["id"]), vertices, links))
    return polygons


# ---- tool invocations -----------------------------------------------------------------------------------------------


def _read_report(path: Path) -> dict[str, Any] | None:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


def _fail(proc: subprocess.CompletedProcess, report: dict[str, Any] | None, what: str) -> NavmeshError:
    code = _EXIT_CODES.get(proc.returncode, NAVMESH_BUILD_FAILED)
    message = (report or {}).get("message") or f"{TOOL_NAME} {what} exited with status {proc.returncode}"
    details = {"exit_status": proc.returncode, "tool_status": (report or {}).get("status")}
    if report:
        details.update({k: report[k] for k in ("input", "stages", "config_voxels") if k in report})
    return NavmeshError(message, code=code, details=details, exit_status=proc.returncode)


def bake(tool_path: str, geometry: NavGeometry, config: RecastConfig, workdir: Path, runner: Runner, *,
         timeout: float = 1800) -> NavmeshBuild:
    """Runs Recast's full build over `geometry` (canonical) and returns the Detour tile plus its canonical polygons.
    Raises NavmeshError with INVALID_GEOMETRY, NO_WALKABLE_SURFACE or NAVMESH_BUILD_FAILED."""
    geometry.validate()
    workdir.mkdir(parents=True, exist_ok=True)
    obj = workdir / "navmesh-input.recast.obj"
    navmesh_path = workdir / "navmesh.bin"
    report_path = workdir / "navmesh-tool-report.json"
    write_recast_obj(obj, geometry)
    argv = [tool_path, "bake", "--input", str(obj), "--navmesh", str(navmesh_path), "--report", str(report_path), *config.argv()]
    proc = runner.run(argv, check=False, error_code=NAVMESH_BUILD_FAILED, timeout=timeout)
    report = _read_report(report_path)
    if proc.returncode != 0:
        raise _fail(proc, report, "bake")
    if report is None or report.get("status") != "OK" or not navmesh_path.is_file():
        raise NavmeshError(f"{TOOL_NAME} bake reported success but its outputs are missing or unreadable", code=NAVMESH_BUILD_FAILED)
    polygons = polygons_from_report(report)
    if not polygons:
        raise NavmeshError("the navmesh has no polygons", code=NO_WALKABLE_SURFACE, details={"stages": report.get("stages")})
    return NavmeshBuild(navmesh_path, report, polygons)


def find_path(tool_path: str, navmesh_path: Path, start: np.ndarray, end: np.ndarray, workdir: Path, runner: Runner, *,
              snap_horizontal_m: float, snap_vertical_m: float) -> dict[str, Any]:
    """Detour's own query (findNearestPoly, findPath, findStraightPath) between two canonical points. Returns the
    straight path and the snapped endpoints in canonical coordinates, and the polygon corridor. ROUTE_UNAVAILABLE when
    either point is off the navmesh or they are not connected -- never a partial path."""
    out = workdir / "navmesh-path.json"
    s, e = canonical_to_recast(np.asarray(start, dtype=np.float64)), canonical_to_recast(np.asarray(end, dtype=np.float64))
    ext = canonical_half_extents_to_recast(snap_horizontal_m, snap_vertical_m)
    argv = [tool_path, "path", "--navmesh", str(navmesh_path), "--output", str(out),
            "--start", *(repr(float(c)) for c in s), "--end", *(repr(float(c)) for c in e), "--half-extents", *(repr(float(c)) for c in ext)]
    proc = runner.run(argv, check=False, error_code=NAVMESH_BUILD_FAILED, timeout=120)
    report = _read_report(out)
    if proc.returncode != 0:
        raise _fail(proc, report, "path")
    assert report is not None  # noqa: S101 - the tool always writes its report on success
    return {"straight_path": [_canonical_point(p) for p in report["straight_path"]],
            "start_on_navmesh": _canonical_point(report["start_on_navmesh"]),
            "end_on_navmesh": _canonical_point(report["end_on_navmesh"]),
            "corridor": [int(i) for i in report["corridor"]]}


def recast_frame_provenance() -> dict[str, str]:
    return {"up_axis": RECAST_UP_AXIS, "units": "metres", "conversion": CONVERSION,
            "boundary": "chaya_worker.recast_boundary"}
