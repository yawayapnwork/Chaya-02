"""Stage 11: navigation mesh baking with the real Recast/Detour library (services/reconstruction/native/chaya-navmesh, driven by
chaya_worker.recast).

Runs only in the canonical venue frame (chaya_worker.frames: metres, +Z up). Recast's agent radius, height, climb and
slope, the routing graph's edge lengths and clearances and the STEP_FREE slope test are all physical quantities; applied
to arbitrary reconstruction units they would be meaningless. Without a calibrated coordinate frame on the work order the
stage fails with NOT_CALIBRATED (retryable once the reconstruction is calibrated) and produces nothing.

  1. Cleaned geometry -> canonical metres: the cleaned splat (SPLAT_MERGED / SPLAT_CLEAN / SPLAT), PLANE_FITTING's floor
     plane and SEMANTIC_SEGMENTATION's cleaned wall/furniture labels.
  2. Recast input geometry (chaya_worker.navmesh.geometry_from_reconstruction): observed floor cells as walkable
     candidates, wall/furniture cells as blocked boxes. Published as NAVMESH_INPUT_GEOMETRY (canonical OBJ).
  3. Recast bake (chaya_worker.recast.bake): slope filtering, agent height/climb/radius, regions, contours, polygons,
     detail mesh, Detour tile. The single axis boundary is chaya_worker.recast_boundary.
  4. NAVMESH (the Detour tile) + NAVMESH_MANIFEST (provenance: source reconstruction version and geometry, frame, Recast
     configuration, tool/library version, checksum, status).
  5. NAVIGATION_GRAPH: the Detour polygon links as the STANDARD and STEP_FREE routing graphs
     (chaya_worker.navmesh.build_routing_graphs), bound to the navmesh by its checksum. dev.chaya.api ingests it and
     routes only on graphs that carry such a binding (see dev.chaya.api.navigation.RouteService, NAVMESH_NOT_READY).

Failure states: NAVMESH_TOOL_UNAVAILABLE (no chaya-navmesh on this worker), INVALID_GEOMETRY, NO_WALKABLE_SURFACE,
NAVMESH_BUILD_FAILED -- each a structured stage failure with nothing published.

Elevators are not detected from geometry -- a hallway scan generally cannot see inside one reliably. Floor-to-floor
transitions, including elevators, are resolved at query time from stairs/elevator POIs shared across adjacent floors
(see RouteService), not by this stage.
"""

from __future__ import annotations

import json

import numpy as np

from ..contract import ArtifactSpec, StageContext, StageError, StageResult
from ..frames import frame_provenance, require_canonical
from ..navmesh import (
    INVALID_GEOMETRY,
    NO_WALKABLE_SURFACE,
    CanonicalPlane,
    NavmeshError,
    build_routing_graphs,
    geometry_from_reconstruction,
    obstacle_band_mask,
    select_floor_plane,
    write_obj,
)
from ..ply import read_ply
from ..recast import NAVMESH_FORMAT, NavmeshBuild, RecastConfig, bake, recast_frame_provenance, resolve_tool, sha256_file
from .base import command_record, write_json

NAVMESH_STATUS_READY = "READY"


def navmesh_reference(build: NavmeshBuild) -> dict:
    """How the manifest and the graph name the NAVMESH artifact: its name, format, checksum, size and polygon count."""
    return {"artifact": "navmesh.bin", "format": NAVMESH_FORMAT, "sha256": build.sha256, "bytes": build.navmesh_path.stat().st_size,
            "polygon_count": len(build.polygons)}


def navigation_graph_document(build: NavmeshBuild, navmesh_ref: dict, tool: dict, config: RecastConfig, frame: dict, *,
                              max_ramp_slope_deg: float) -> dict:
    """The NAVIGATION_GRAPH artifact: the Detour polygon graph as STANDARD/STEP_FREE routing graphs, bound to the navmesh
    it came from (source, status, checksum, tool/library versions). dev.chaya.api ingests a graph only with this binding,
    and only when the checksum is that of the NAVMESH artifact published alongside it."""
    binding = {"source": "RECAST_NAVMESH", "status": NAVMESH_STATUS_READY, "manifest": "navmesh-manifest.json", **navmesh_ref,
               "tool_version": tool["tool_version"], "recastnavigation_version": tool["recastnavigation_version"]}
    return {"coordinate_frame": frame, "navmesh": binding, "recast_config": config.as_dict(), "polygon_count": len(build.polygons),
            "graphs": build_routing_graphs(build.polygons, max_ramp_slope_deg=max_ramp_slope_deg)}


def _input_ref(f) -> dict:
    return {k: f.ref.get(k) for k in ("artifactId", "kind", "stage", "key", "sha256")}


class NavigationBaking:
    name = "NAVIGATION_BAKING"

    def run(self, ctx: StageContext) -> StageResult:
        s = ctx.settings
        frame = require_canonical(ctx.order, self.name)
        to_canonical = frame.to_canonical()
        tool_path, tool = resolve_tool(ctx.toolchain)

        splats = ctx.inputs_of("SPLAT_MERGED") or ctx.inputs_of("SPLAT_CLEAN") or ctx.inputs_of("SPLAT")
        planes_inputs = ctx.inputs_of("PLANE_MODEL")
        labels_inputs = ctx.inputs_of("SEMANTIC_LABELS_CLEAN")
        if not splats or not planes_inputs:
            raise StageError("NAVIGATION_BAKING needs a splat and PLANE_MODEL from PLANE_FITTING", code="INPUT_INVALID")

        # ---- 1. cleaned reconstruction geometry, in canonical metres ----
        cloud = read_ply(splats[0].path)
        positions = to_canonical.apply(cloud.positions)  # canonical metres, +Z up
        if len(positions) == 0 or not np.isfinite(positions).all():
            raise NavmeshError("the splat has no finite positions", code=INVALID_GEOMETRY, details={"gaussians": len(positions)})
        planes_doc = json.loads(planes_inputs[0].path.read_text(encoding="utf-8"))
        planes = []
        for p in planes_doc["planes"]:
            idx = np.array(p["inlier_indices"], dtype=int)
            if len(idx) == 0 or idx.min() < 0 or idx.max() >= len(positions):
                raise NavmeshError("a PLANE_MODEL plane references Gaussians the splat does not have", code=INVALID_GEOMETRY)
            normal = to_canonical.apply_direction(np.array(p["equation"][:3], dtype=np.float64))
            normal = normal if normal[2] >= 0 else -normal
            planes.append(CanonicalPlane(normal, float(np.median(positions[idx, 2])), idx))
        floor = select_floor_plane(planes, max_tilt_deg=s.navmesh_floor_max_tilt_deg)
        if floor is None:
            raise NavmeshError(f"no fitted plane is within {s.navmesh_floor_max_tilt_deg} degrees of horizontal in the calibrated "
                               "frame, so there is no floor to walk on", code=NO_WALKABLE_SURFACE,
                               details={"coordinate_frame": frame_provenance(frame), "planes": len(planes)})
        floor_points = positions[floor.inlier_indices]

        obstacle_points = np.zeros((0, 3))
        obstacles_source = "none: no SEMANTIC_LABELS_CLEAN input"
        if labels_inputs:
            doc = json.loads(labels_inputs[0].path.read_text(encoding="utf-8"))
            labels = np.array(doc["labels"], dtype=object)
            if len(labels) == len(cloud):
                candidates = positions[np.isin(labels, ["wall", "furniture"])]
                band = obstacle_band_mask(candidates, floor.height, agent_max_climb=s.navmesh_agent_max_climb_m,
                                          agent_height=s.navmesh_agent_height_m)
                obstacle_points = candidates[band]
                obstacles_source = "wall/furniture points between agent climb height and agent height above the floor"
            else:
                obstacles_source = f"none: SEMANTIC_LABELS_CLEAN has {len(labels)} labels for {len(cloud)} Gaussians"

        # ---- 2. Recast input geometry ----
        geometry = geometry_from_reconstruction(floor_points, obstacle_points, floor.height, cell_m=s.navmesh_floor_grid_m,
                                                min_points_per_cell=s.navmesh_floor_min_points_per_cell,
                                                obstacle_min_height_m=s.navmesh_obstacle_min_height_m)
        if geometry.walkable_candidate_count == 0:
            raise NavmeshError(f"no {s.navmesh_floor_grid_m} m floor cell holds {s.navmesh_floor_min_points_per_cell} floor points",
                               code=NO_WALKABLE_SURFACE, details={"floor_points": len(floor_points)})
        geometry.validate()
        provenance = frame_provenance(frame)
        geometry_path = ctx.workdir / "navmesh-input-geometry.obj"
        write_obj(geometry_path, geometry, header=f"Chaya NAVMESH_INPUT_GEOMETRY, canonical frame {frame.id}: metres, +Z up\n"
                                                  "g walkable_candidates: observed floor cells; g obstacle: blocked geometry")

        # ---- 3. the real Recast build ----
        config = RecastConfig.from_settings(s)
        build = bake(tool_path, geometry, config, ctx.workdir / "recast", ctx.runner)
        report = build.report

        # ---- 4. navmesh artifact + manifest ----
        navmesh_ref = navmesh_reference(build)
        navmesh_sha = navmesh_ref["sha256"]
        geometry_sha = sha256_file(geometry_path)
        manifest = {
            "status": NAVMESH_STATUS_READY,
            "navmesh": navmesh_ref,
            "source": {
                "run_id": ctx.order.get("runId"), "scan_id": ctx.order.get("scanId"), "scan_version_id": ctx.order.get("scanVersionId"),
                "splat": _input_ref(splats[0]), "plane_model": _input_ref(planes_inputs[0]),
                "semantic_labels": _input_ref(labels_inputs[0]) if labels_inputs else None,
                "input_geometry": {"artifact": "navmesh-input-geometry.obj", "sha256": geometry_sha, "vertices": len(geometry.vertices),
                                   "triangles": len(geometry.triangles), "obstacle_triangles": int(geometry.obstacle.sum())},
            },
            "coordinate_frame": provenance,
            "recast_frame": recast_frame_provenance(),
            "recast_config": {"metres": config.as_dict(), "voxels": report.get("config_voxels")},
            "tool": tool,
            "build": {"stages": report.get("stages"), "input": report.get("input")},
        }
        manifest_path = write_json(ctx.workdir / "navmesh-manifest.json", manifest)

        # ---- 5. routing graphs from the Detour polygon links ----
        graph_doc = navigation_graph_document(build, navmesh_ref, tool, config, provenance, max_ramp_slope_deg=s.navmesh_max_ramp_slope_deg)
        graphs = graph_doc["graphs"]
        graph_path = write_json(ctx.workdir / "navigation-graph.json", graph_doc)
        baking_report = write_json(ctx.workdir / "navigation-baking-report.json", {
            "coordinate_frame": provenance, "floor_height_m": floor.height, "floor_inliers": len(floor.inlier_indices),
            "obstacle_points": len(obstacle_points), "obstacles_source": obstacles_source,
            "input_triangles": len(geometry.triangles), "input_obstacle_triangles": int(geometry.obstacle.sum()),
            "recast_stages": report.get("stages"), "polygon_count": len(build.polygons), "navmesh_sha256": navmesh_sha,
            "standard_edges": len(graphs["STANDARD"]["edges"]), "step_free_edges": len(graphs["STEP_FREE"]["edges"])})

        ctx.logger.info("navigation baking done", extra={"polygon_count": len(build.polygons), "coordinate_frame_id": frame.id,
                                                          "navmesh_sha256": navmesh_sha, "recast": tool["recastnavigation_version"],
                                                          "standard_edges": len(graphs["STANDARD"]["edges"]),
                                                          "step_free_edges": len(graphs["STEP_FREE"]["edges"])})
        return StageResult(
            "SUCCEEDED", command_record(ctx, {"polygon_count": len(build.polygons), "navmesh_sha256": navmesh_sha,
                                              "coordinate_frame_id": frame.id, **tool}),
            ctx.runner.last_exit_status(),
            [ArtifactSpec("NAVMESH", build.navmesh_path, "navmesh.bin", "application/octet-stream"),
             ArtifactSpec("NAVMESH_MANIFEST", manifest_path, "navmesh-manifest.json", "application/json"),
             ArtifactSpec("NAVMESH_INPUT_GEOMETRY", geometry_path, "navmesh-input-geometry.obj", "text/plain"),
             ArtifactSpec("NAVIGATION_GRAPH", graph_path, "navigation-graph.json", "application/json"),
             ArtifactSpec("NAVIGATION_BAKING_REPORT", baking_report, "navigation-baking-report.json", "application/json")])
