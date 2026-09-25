"""Stage 11: navigation mesh baking with Recast (exe:recast-cli -- a real external toolchain dependency,
gated exactly like colmap/glomap; see chaya_worker.toolchain).

Runs only in the canonical venue frame (chaya_worker.frames: metres, +Z up). Recast's agent radius, height,
climb and slope, the routing graph's edge lengths and clearances and the STEP_FREE slope test are all
physical quantities; applied to arbitrary reconstruction units they would be meaningless. Without a
calibrated coordinate frame on the work order the stage fails with NOT_CALIBRATED (retryable once the
reconstruction is calibrated) and produces nothing.

Builds a walkable surface from PLANE_FITTING's floor plane and GEOMETRIC_CLEANUP's wall/furniture points
(real reconstructed geometry, transformed to canonical metres), hands it to the real Recast toolchain for
polygon navmesh generation through the single axis boundary in chaya_worker.recast_boundary, and turns
Recast's own polygon adjacency into the routing graph dev.chaya.api.navigation.RouteService pathfinds over --
both a STANDARD and a STEP_FREE profile (chaya_worker.navmesh.build_routing_graphs; STEP_FREE is computed
from each polygon's real slope against canonical +Z, never a renamed copy of STANDARD).

Elevators are not detected from geometry -- a hallway scan generally cannot see inside one reliably.
Floor-to-floor transitions, including elevators, are resolved at query time from stairs/elevator POIs
shared across adjacent floors (see RouteService), not by this stage.
"""

from __future__ import annotations

import json

import numpy as np

from ..contract import ArtifactSpec, StageContext, StageError, StageResult
from ..frames import frame_provenance, require_canonical
from ..navmesh import (
    CanonicalPlane,
    build_recast_argv,
    build_routing_graphs,
    carve_obstacles,
    obstacle_band_mask,
    parse_recast_polygons,
    select_floor_plane,
    triangulate_walkable_area,
    write_walkable_obj,
)
from ..ply import read_ply
from .base import command_record, write_json


class NavigationBaking:
    name = "NAVIGATION_BAKING"

    def run(self, ctx: StageContext) -> StageResult:
        s = ctx.settings
        frame = require_canonical(ctx.order, self.name)
        to_canonical = frame.to_canonical()
        ctx.toolchain.require(["exe:recast-cli"], stage=self.name)

        splats = ctx.inputs_of("SPLAT_MERGED") or ctx.inputs_of("SPLAT_CLEAN") or ctx.inputs_of("SPLAT")
        planes_inputs = ctx.inputs_of("PLANE_MODEL")
        labels_inputs = ctx.inputs_of("SEMANTIC_LABELS_CLEAN")
        if not splats or not planes_inputs:
            raise StageError("NAVIGATION_BAKING needs a splat and PLANE_MODEL from PLANE_FITTING", code="INPUT_INVALID")

        cloud = read_ply(splats[0].path)
        positions = to_canonical.apply(cloud.positions)  # canonical metres, +Z up
        planes_doc = json.loads(planes_inputs[0].path.read_text(encoding="utf-8"))
        planes = []
        for p in planes_doc["planes"]:
            idx = np.array(p["inlier_indices"], dtype=int)
            normal = to_canonical.apply_direction(np.array(p["equation"][:3], dtype=np.float64))
            normal = normal if normal[2] >= 0 else -normal
            planes.append(CanonicalPlane(normal, float(np.median(positions[idx, 2])), idx))
        floor = select_floor_plane(planes, max_tilt_deg=s.navmesh_floor_max_tilt_deg)
        if floor is None:
            raise StageError(f"no fitted plane is within {s.navmesh_floor_max_tilt_deg} degrees of horizontal in the calibrated "
                             "frame; cannot bake a navmesh", code="NO_FLOOR_PLANE",
                             details={"coordinate_frame": frame_provenance(frame), "planes": len(planes)})
        floor_points = positions[floor.inlier_indices]

        obstacle_xy = np.zeros((0, 2))
        obstacles_source = "none: no SEMANTIC_LABELS_CLEAN input"
        if labels_inputs:
            doc = json.loads(labels_inputs[0].path.read_text(encoding="utf-8"))
            labels = np.array(doc["labels"], dtype=object)
            if len(labels) == len(cloud):
                candidates = positions[np.isin(labels, ["wall", "furniture"])]
                band = obstacle_band_mask(candidates, floor.height, agent_max_climb=s.navmesh_agent_max_climb,
                                          agent_height=s.navmesh_agent_height)
                obstacle_xy = candidates[band][:, :2]
                obstacles_source = "wall/furniture points between agent climb height and agent height above the floor"
            else:
                obstacles_source = f"none: SEMANTIC_LABELS_CLEAN has {len(labels)} labels for {len(cloud)} Gaussians"

        try:
            triangles = triangulate_walkable_area(floor_points[:, :2])
        except ValueError as exc:
            raise StageError(str(exc), code="NAVMESH_INSUFFICIENT_GEOMETRY", details={"floor_points": len(floor_points)}) from exc
        walkable = carve_obstacles(floor_points[:, :2], triangles, obstacle_xy, s.navmesh_agent_radius)
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
        provenance = frame_provenance(frame)
        graph_path = write_json(ctx.workdir / "navigation-graph.json",
                                {"coordinate_frame": provenance, "recast_config": recast_config, "polygon_count": len(polygons),
                                 "graphs": graphs})
        report_path = write_json(ctx.workdir / "navigation-baking-report.json", {
            "coordinate_frame": provenance, "floor_height_m": floor.height, "floor_inliers": len(floor.inlier_indices),
            "obstacle_points": len(obstacle_xy), "obstacles_source": obstacles_source,
            "walkable_triangles": int(kept), "polygon_count": len(polygons),
            "standard_edges": len(graphs["STANDARD"]["edges"]), "step_free_edges": len(graphs["STEP_FREE"]["edges"])})

        ctx.logger.info("navigation baking done", extra={"walkable_triangles": int(kept), "polygon_count": len(polygons),
                                                          "coordinate_frame_id": frame.id,
                                                          "standard_edges": len(graphs["STANDARD"]["edges"]),
                                                          "step_free_edges": len(graphs["STEP_FREE"]["edges"])})
        return StageResult(
            "SUCCEEDED", command_record(ctx, {"polygon_count": len(polygons), "walkable_triangles": int(kept),
                                              "coordinate_frame_id": frame.id}),
            ctx.runner.last_exit_status(),
            [ArtifactSpec("NAVIGATION_GRAPH", graph_path, "navigation-graph.json", "application/json"),
             ArtifactSpec("NAVIGATION_BAKING_REPORT", report_path, "navigation-baking-report.json", "application/json")])
