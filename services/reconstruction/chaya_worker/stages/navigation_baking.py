"""Stage 11: navigation mesh baking with Recast (exe:recast-cli -- a real external toolchain dependency,
gated exactly like colmap/glomap; see chaya_worker.toolchain).

Builds a walkable surface from PLANE_FITTING's floor plane and GEOMETRIC_CLEANUP's wall/furniture points
(real reconstructed geometry), hands it to the real Recast toolchain for polygon navmesh generation, and
turns Recast's own polygon adjacency into the routing graph dev.chaya.api.navigation.RouteService
pathfinds over -- both a STANDARD and a STEP_FREE profile (chaya_worker.navmesh.build_routing_graphs;
STEP_FREE is computed from each polygon's real slope, never a renamed copy of STANDARD).

Elevators are not detected from geometry -- a hallway scan generally cannot see inside one reliably.
Floor-to-floor transitions, including elevators, are resolved at query time from stairs/elevator POIs
shared across adjacent floors (see RouteService), not by this stage.
"""

from __future__ import annotations

import json

import numpy as np

from ..contract import ArtifactSpec, StageContext, StageError, StageResult
from ..navmesh import (
    build_recast_argv,
    build_routing_graphs,
    carve_obstacles,
    parse_recast_polygons,
    project_to_plane,
    triangulate_walkable_area,
    write_walkable_obj,
)
from ..ply import read_ply
from .base import command_record, write_json


class NavigationBaking:
    name = "NAVIGATION_BAKING"

    def run(self, ctx: StageContext) -> StageResult:
        s = ctx.settings
        ctx.toolchain.require(["exe:recast-cli"], stage=self.name)

        splats = ctx.inputs_of("SPLAT_MERGED") or ctx.inputs_of("SPLAT_CLEAN") or ctx.inputs_of("SPLAT")
        planes_inputs = ctx.inputs_of("PLANE_MODEL")
        labels_inputs = ctx.inputs_of("SEMANTIC_LABELS_CLEAN")
        if not splats or not planes_inputs:
            raise StageError("NAVIGATION_BAKING needs a splat and PLANE_MODEL from PLANE_FITTING", code="INPUT_INVALID")

        cloud = read_ply(splats[0].path)
        planes_doc = json.loads(planes_inputs[0].path.read_text(encoding="utf-8"))
        floor_planes = [p for p in planes_doc["planes"] if p["classification"] == "floor"]
        if not floor_planes:
            raise StageError("no floor plane was found by PLANE_FITTING; cannot bake a navmesh", code="NO_FLOOR_PLANE")
        floor_plane = max(floor_planes, key=lambda p: p["inlier_count"])
        floor_idx = np.array(floor_plane["inlier_indices"], dtype=int)
        normal = np.array(floor_plane["equation"][:3], dtype=float)
        floor_points = cloud.positions[floor_idx]
        plane_point = floor_points.mean(axis=0)
        floor_2d = project_to_plane(floor_points, plane_point, normal)

        obstacle_points = np.zeros((0, 3))
        if labels_inputs:
            doc = json.loads(labels_inputs[0].path.read_text(encoding="utf-8"))
            labels = np.array(doc["labels"], dtype=object)
            if len(labels) == len(cloud):
                obstacle_points = cloud.positions[np.isin(labels, ["wall", "furniture"])]
        obstacle_2d = project_to_plane(obstacle_points, plane_point, normal)

        try:
            triangles = triangulate_walkable_area(floor_2d)
        except ValueError as exc:
            raise StageError(str(exc), code="NAVMESH_INSUFFICIENT_GEOMETRY", details={"floor_points": len(floor_points)}) from exc
        walkable = carve_obstacles(floor_2d, triangles, obstacle_2d, s.navmesh_agent_radius)
        if not walkable.any():
            raise StageError("every triangulated cell was carved out by obstacles; nothing is walkable", code="NAVMESH_EMPTY")

        obj_path = ctx.workdir / "walkable.obj"
        kept = write_walkable_obj(obj_path, floor_points, triangles, walkable)

        recast_cli = ctx.toolchain.status("exe:recast-cli").path
        output_json = ctx.workdir / "navmesh-polygons.json"
        recast_config = {
            "cellSize": s.navmesh_cell_size, "cellHeight": s.navmesh_cell_height, "agentHeight": s.navmesh_agent_height,
            "agentRadius": s.navmesh_agent_radius, "agentMaxClimb": s.navmesh_agent_max_climb,
            "agentMaxSlope": s.navmesh_agent_max_slope_deg, "regionMinSize": s.navmesh_region_min_size,
            "regionMergeSize": s.navmesh_region_merge_size, "edgeMaxLen": s.navmesh_edge_max_len,
            "edgeMaxError": s.navmesh_edge_max_error, "vertsPerPoly": s.navmesh_verts_per_poly,
            "detailSampleDist": s.navmesh_detail_sample_dist, "detailSampleMaxError": s.navmesh_detail_sample_max_error,
        }
        argv = build_recast_argv(recast_cli, obj_path, output_json, **recast_config)
        ctx.runner.run(argv, error_code="NAVMESH_BAKE_FAILED", timeout=1800)
        if not output_json.is_file():
            raise StageError("recast-cli reported success but wrote no polygon output", code="NAVMESH_BAKE_FAILED")
        polygons = parse_recast_polygons(json.loads(output_json.read_text(encoding="utf-8")))
        if not polygons:
            raise StageError("recast-cli produced zero navmesh polygons", code="NAVMESH_EMPTY")

        graphs = build_routing_graphs(polygons, max_ramp_slope_deg=s.navmesh_max_ramp_slope_deg)
        graph_path = write_json(ctx.workdir / "navigation-graph.json",
                                {"recast_config": recast_config, "polygon_count": len(polygons), "graphs": graphs})
        report_path = write_json(ctx.workdir / "navigation-baking-report.json", {
            "walkable_triangles": int(kept), "polygon_count": len(polygons),
            "standard_edges": len(graphs["STANDARD"]["edges"]), "step_free_edges": len(graphs["STEP_FREE"]["edges"])})

        ctx.logger.info("navigation baking done", extra={"walkable_triangles": int(kept), "polygon_count": len(polygons),
                                                          "standard_edges": len(graphs["STANDARD"]["edges"]),
                                                          "step_free_edges": len(graphs["STEP_FREE"]["edges"])})
        return StageResult(
            "SUCCEEDED", command_record(ctx, {"polygon_count": len(polygons), "walkable_triangles": int(kept)}),
            ctx.runner.last_exit_status(),
            [ArtifactSpec("NAVIGATION_GRAPH", graph_path, "navigation-graph.json", "application/json"),
             ArtifactSpec("NAVIGATION_BAKING_REPORT", report_path, "navigation-baking-report.json", "application/json")])
