"""Stage 11: navigation mesh baking with the real Recast/Detour library (services/reconstruction/native/chaya-navmesh, driven by
chaya_worker.recast).

Runs only in the canonical venue frame (chaya_worker.frames: metres, +Z up). Recast's agent radius, height, climb and
slope, the routing graph's edge lengths and clearances and the STEP_FREE slope test are all physical quantities; applied
to arbitrary reconstruction units they would be meaningless. Without a calibrated coordinate frame on the work order the
stage fails with NOT_CALIBRATED (retryable once the reconstruction is calibrated) and produces nothing.

  1. Cleaned geometry -> canonical metres: the cleaned splat (SPLAT_MERGED / SPLAT_CLEAN / SPLAT), PLANE_FITTING's floor
     plane (the reference height) and the semantic labels of exactly that splat (SEMANTIC_LABELS_MERGED /
     SEMANTIC_LABELS_CLEAN / SEMANTIC_LABELS). Labels are required: without them nothing distinguishes a wall or a
     sofa from the floor, so the stage fails with NAVMESH_LABELS_UNAVAILABLE rather than bake an obstacle-free floor
     (review N-1).
  2. Recast input geometry (chaya_worker.navmesh.build_surface_model, geometry_from_surface): every floor/stairs point
     gives the walkable surface per grid cell -- multi-level, so stair treads, ramps and raised areas are surfaces at
     their own heights, not the one floor plane (review N-2); wall/furniture/clutter/unknown points in the agent's
     height band above their local surface become blocked boxes; cells on either side of a step are `level_change`.
     Published as NAVMESH_INPUT_GEOMETRY (canonical OBJ).
  3. Recast bake (chaya_worker.recast.bake): slope filtering, agent height/climb/radius, regions, contours, polygons,
     detail mesh, Detour tile. The single axis boundary is chaya_worker.recast_boundary.
  4. NAVMESH (the Detour tile) + NAVMESH_MANIFEST (provenance: source reconstruction version and geometry, frame, Recast
     configuration, tool/library version, checksum, status).
  5. NAVIGATION_GRAPH: the Detour polygon links as the STANDARD and STEP_FREE routing graphs
     (chaya_worker.navmesh.build_routing_graphs), bound to the navmesh by its checksum. dev.chaya.api ingests it and
     routes only on graphs that carry such a binding (see dev.chaya.api.navigation.RouteService, NAVMESH_NOT_READY).

Every input is checked to describe the same cloud: the labels name the cloud they label (SEMANTIC_LABELS_MERGED does,
by SHA-256) and PLANE_MODEL names the cloud it was fitted on; a mismatch is refused, not baked.

**Incremental re-scans** (the work order carries `regionGeometry`) re-bake the **whole floor** from the merged scene
(SPLAT_MERGED + SEMANTIC_LABELS_MERGED). This is not a local re-bake: the navmesh is one Detour tile, and Recast's
region partitioning and polygonisation are global, so a patch of polygons could not be swapped in without breaking the
topology at its border. Instead the stage proves that the full re-bake preserved the venue outside the region:

  * the SPLAT_MERGED being baked must be the one REGION_SPLICE produced from exactly this GLOBAL_CLOUD and GLOBAL_LABELS
    (SPLICE_REPORT);
  * the surface model of the parent scene (GLOBAL_CLOUD + GLOBAL_LABELS, the parent version's pinned cloud and labels)
    and of the merged scene must be identical, cell for cell, everywhere farther than RESCAN_INFLUENCE_CELLS cells outside
    the region polygon: the same ground, the same walls and furniture, the same steps
    (chaya_worker.navmesh.compare_outside_region);
  * no polygon of the new navmesh may cover an obstacle cell there (obstacle_cells_under_navmesh).

Any failure is NAVMESH_REGION_INCONSISTENT and nothing is published. The manifest's `rebuild` block records the scope
(FULL_FLOOR) and these measurements.

Failure states: NAVMESH_TOOL_UNAVAILABLE (no chaya-navmesh on this worker), INVALID_GEOMETRY, NO_WALKABLE_SURFACE,
NAVMESH_LABELS_UNAVAILABLE, NAVMESH_REGION_INCONSISTENT, NAVMESH_BUILD_FAILED -- each a structured stage failure with
nothing published.

Elevators are not detected from geometry -- a hallway scan generally cannot see inside one reliably. Floor-to-floor
transitions, including elevators, are resolved at query time from stairs/elevator POIs shared across adjacent floors
(see RouteService), not by this stage.
"""

from __future__ import annotations

import json

import numpy as np

from ..contract import ArtifactSpec, StageContext, StageError, StageResult
from ..frames import UNITS, UP_AXIS, frame_provenance, require_canonical
from ..navmesh import (
    GROUND_LABELS,
    INVALID_GEOMETRY,
    NAVMESH_REGION_INCONSISTENT,
    NO_WALKABLE_SURFACE,
    OBSTACLE_LABELS,
    RESCAN_INFLUENCE_CELLS,
    CanonicalPlane,
    NavmeshError,
    SurfaceModel,
    build_routing_graphs,
    build_surface_model,
    cells_outside_region,
    compare_outside_region,
    geometry_from_surface,
    obstacle_cells_under_navmesh,
    select_floor_plane,
    write_obj,
)
from ..ply import read_ply
from ..recast import NAVMESH_FORMAT, NavmeshBuild, RecastConfig, bake, recast_frame_provenance, resolve_tool, sha256_file
from .base import command_record, is_rescan, run_provenance, venue_cloud, write_json

NAVMESH_STATUS_READY = "READY"
NAVMESH_LABELS_UNAVAILABLE = "NAVMESH_LABELS_UNAVAILABLE"
FULL_FLOOR = "FULL_FLOOR"
INPUT_REPRESENTATION = (
    "canonical-frame triangle soup (metres, +Z up), converted to Recast's +Y-up frame only by chaya_worker.recast_boundary: "
    "one quad per observed ground cell of the surface model (corners at the mean height of level neighbouring cells, so "
    "ramps are continuous slopes and steps stay discontinuities), groups walkable_candidates / level_change (cells "
    "beside a step); and one closed box per obstacle cell (group obstacle), from below the ground to the top of the "
    "obstacle geometry in the agent's height band. Unobserved cells have no geometry.")


def navmesh_reference(build: NavmeshBuild) -> dict:
    """How the manifest and the graph name the NAVMESH artifact: its name, format, checksum, size and polygon count."""
    return {"artifact": "navmesh.bin", "format": NAVMESH_FORMAT, "sha256": build.sha256, "bytes": build.navmesh_path.stat().st_size,
            "polygon_count": len(build.polygons)}


def navigation_graph_document(build: NavmeshBuild, navmesh_ref: dict, tool: dict, config: RecastConfig, frame: dict, *,
                              max_ramp_slope_deg: float, surface: SurfaceModel, source: dict | None = None) -> dict:
    """The NAVIGATION_GRAPH artifact: the Detour polygon graph as STANDARD/STEP_FREE routing graphs, bound to the navmesh
    it came from (source, status, checksum, tool/library versions). dev.chaya.api ingests a graph only with this binding,
    and only when the checksum is that of the NAVMESH artifact published alongside it."""
    binding = {"source": "RECAST_NAVMESH", "status": NAVMESH_STATUS_READY, "manifest": "navmesh-manifest.json", **navmesh_ref,
               "tool_version": tool["tool_version"], "recastnavigation_version": tool["recastnavigation_version"]}
    measurements = {
        "units": UNITS, "up_axis": UP_AXIS,
        "length_m": "3-D distance between polygon centroids",
        "max_slope_deg": ("steepest of the reconstructed surface slope under both polygons and the centroid-to-centroid grade "
                          "on the reconstructed surface heights, against canonical +Z"),
        "min_clearance_m": ("clear width along the edge, measured on the surface model's obstacle and hole geometry on a "
                            f"{surface.cell_m} m grid: the bottleneck of the widest path from the roomiest cell of one polygon "
                            "to the roomiest cell of the other, through their cells, where the width at a cell is that available to a body centred there "
                            "(2 x distance to the nearest obstacle or hole - one cell); "
                            f"capped at {surface.width_cap_m} m"),
        "portal_width_m": "Detour portal length; the walkable area was already eroded by recast_config.agent_radius_m",
        "portal": "the portal's endpoints in canonical metres; dev.chaya.api string-pulls routes through them",
        "level_change": ("either polygon lies over a step: ground heights of neighbouring cells differ by more than "
                         f"{surface.step_min_rise_m} m (and at most the agent's climb)"),
        "step_free_max_slope_deg": max_ramp_slope_deg,
    }
    return {"coordinate_frame": frame, "source": source or {}, "navmesh": binding, "recast_config": config.as_dict(),
            "polygon_count": len(build.polygons),
            "edge_measurements": measurements,
            "graphs": build_routing_graphs(build.polygons, max_ramp_slope_deg=max_ramp_slope_deg, surface=surface)}


def _input_ref(f) -> dict:
    return {k: f.ref.get(k) for k in ("artifactId", "kind", "stage", "key", "sha256")}


class NavigationBaking:
    name = "NAVIGATION_BAKING"

    def run(self, ctx: StageContext) -> StageResult:
        s = ctx.settings
        frame = require_canonical(ctx.order, self.name)
        to_canonical = frame.to_canonical()
        tool_path, tool = resolve_tool(ctx.toolchain)

        # a re-scan bakes the merged venue, never the region alone (venue_cloud refuses that), with that cloud's labels
        splat_kind, splats, labels_kind, labels_inputs = venue_cloud(ctx, self.name)
        planes_inputs = ctx.inputs_of("PLANE_MODEL")
        if not planes_inputs:
            raise StageError("NAVIGATION_BAKING needs a splat and PLANE_MODEL from PLANE_FITTING", code="INPUT_INVALID")
        rescan = is_rescan(ctx)
        splat_sha = sha256_file(splats[0].path)

        # ---- 1. cleaned reconstruction geometry, in canonical metres ----
        cloud = read_ply(splats[0].path)
        positions = to_canonical.apply(cloud.positions)  # canonical metres, +Z up
        if len(positions) == 0 or not np.isfinite(positions).all():
            raise NavmeshError("the splat has no finite positions", code=INVALID_GEOMETRY, details={"gaussians": len(positions)})
        if not labels_inputs:
            raise NavmeshError(f"no {labels_kind} describes the {splat_kind} to bake: without semantic labels walls and furniture "
                               "cannot be told from the floor, and an obstacle-free navmesh is never baked",
                               code=NAVMESH_LABELS_UNAVAILABLE, details={"splat": splat_kind, "labels": labels_kind})
        labels_doc = json.loads(labels_inputs[0].path.read_text(encoding="utf-8"))
        labels = np.array(labels_doc["labels"], dtype=object)
        if len(labels) != len(cloud):
            raise NavmeshError(f"{labels_kind} has {len(labels)} labels for {len(cloud)} Gaussians of {splat_kind}: they do not "
                               "describe the same cloud", code=NAVMESH_LABELS_UNAVAILABLE,
                               details={"labels": len(labels), "gaussians": len(cloud)})
        labelled_cloud = (labels_doc.get("cloud") or {}).get("sha256")
        if labelled_cloud != splat_sha and (labelled_cloud is not None or rescan):
            raise NavmeshError(f"{labels_kind} labels cloud {labelled_cloud}, not the {splat_kind} being baked ({splat_sha}); "
                               "labels of another cloud, even one of the same size, would put walls and furniture in the wrong "
                               "places", code=NAVMESH_LABELS_UNAVAILABLE,
                               details={"labels_cloud_sha256": labelled_cloud, "splat_sha256": splat_sha})
        planes_doc = json.loads(planes_inputs[0].path.read_text(encoding="utf-8"))
        fitted_on = ((planes_doc.get("source") or {}).get("splat") or {}).get("sha256")
        if fitted_on != splat_sha and (fitted_on is not None or rescan):
            raise NavmeshError(f"PLANE_MODEL was fitted on cloud {fitted_on}, not the {splat_kind} being baked ({splat_sha}); its "
                               "inlier indices would select the wrong Gaussians", code=INVALID_GEOMETRY,
                               details={"plane_model_cloud_sha256": fitted_on, "splat_sha256": splat_sha})
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

        # ---- 2. Recast input geometry: the surface model of the cleaned geometry ----
        ground_mask = np.isin(labels, GROUND_LABELS)
        obstacle_mask = np.isin(labels, OBSTACLE_LABELS)
        surface = build_surface_model(positions[ground_mask], positions[obstacle_mask], reference_z=floor.height,
                                      cell_m=s.navmesh_floor_grid_m, min_points_per_cell=s.navmesh_floor_min_points_per_cell,
                                      ground_band_m=s.navmesh_ground_band_m, agent_max_climb_m=s.navmesh_agent_max_climb_m,
                                      agent_height_m=s.navmesh_agent_height_m, step_min_rise_m=s.navmesh_step_min_rise_m,
                                      max_level_above_reference_m=s.navmesh_max_level_above_floor_m,
                                      accessible_width_m=s.navmesh_accessible_width_m, width_cap_m=s.navmesh_width_cap_m)
        rebuild: dict = {"scope": FULL_FLOOR, "rescan": rescan}
        outside = None
        if rescan:
            polygon = np.array(ctx.order["regionGeometry"]["points"], dtype=np.float64)
            margin_m = RESCAN_INFLUENCE_CELLS * surface.cell_m
            parent = _parent_surface(ctx, s, to_canonical, splat_sha, floor.height)
            comparison = compare_outside_region(parent, surface, polygon, margin_m=margin_m)
            rebuild.update({"incremental": False, "region_polygon_xy": polygon.tolist(),
                            "reason": ("the navmesh is a single Detour tile and Recast partitions and polygonises it globally, so "
                                       "the whole floor is re-baked from the merged scene and checked against the parent scene "
                                       "outside the region"),
                            "outside_region": comparison})
            if not comparison["consistent"]:
                raise NavmeshError(f"the merged scene differs from the parent scene in {comparison['cells_differing']} cells more "
                                   f"than {margin_m:.2f} m outside the re-scanned region ({comparison['obstacle_cells_lost']} "
                                   f"obstacle cells lost, {comparison['obstacle_cells_added']} added); a re-bake from it would not "
                                   "preserve the venue outside the region", code=NAVMESH_REGION_INCONSISTENT, details=comparison)
            outside = cells_outside_region(surface.origin, surface.ground_z.shape, surface.cell_m, polygon, margin_m)
        geometry = geometry_from_surface(surface, obstacle_min_height_m=s.navmesh_obstacle_min_height_m)
        if geometry.walkable_candidate_count == 0:
            raise NavmeshError(f"no {s.navmesh_floor_grid_m} m cell holds {s.navmesh_floor_min_points_per_cell} floor/stairs points "
                               "in one height band", code=NO_WALKABLE_SURFACE, details={"surface": surface.summary()})
        geometry.validate()
        provenance = frame_provenance(frame)
        geometry_path = ctx.workdir / "navmesh-input-geometry.obj"
        write_obj(geometry_path, geometry, header=f"Chaya NAVMESH_INPUT_GEOMETRY, canonical frame {frame.id}: metres, +Z up\n"
                                                  "g walkable_candidates: observed ground cells; g level_change: ground cells beside a "
                                                  "step; g obstacle: blocked geometry")
        label_counts = {str(k): int(v) for k, v in zip(*np.unique(labels.astype(str), return_counts=True), strict=True)}

        # ---- 3. the real Recast build ----
        config = RecastConfig.from_settings(s)
        build = bake(tool_path, geometry, config, ctx.workdir / "recast", ctx.runner)
        report = build.report
        checked = np.ones(surface.obstacle.shape, dtype=bool) if outside is None else outside
        walked_through = obstacle_cells_under_navmesh(build.polygons, surface, checked)
        rebuild["obstacle_cells_under_navmesh"] = {"count": len(walked_through), "examples_xy": walked_through[:10].round(3).tolist(),
                                                   "checked": "outside the region" if rescan else "whole floor"}
        if rescan and len(walked_through):
            raise NavmeshError(f"{len(walked_through)} obstacle cells outside the re-scanned region lie under navmesh polygons",
                               code=NAVMESH_REGION_INCONSISTENT, details=rebuild["obstacle_cells_under_navmesh"])

        # ---- 4. navmesh artifact + manifest ----
        navmesh_ref = navmesh_reference(build)
        navmesh_sha = navmesh_ref["sha256"]
        geometry_sha = sha256_file(geometry_path)
        manifest = {
            "status": NAVMESH_STATUS_READY,
            "navmesh": navmesh_ref,
            "rebuild": rebuild,
            "source": {
                **run_provenance(ctx, frame.id),
                "splat": _input_ref(splats[0]), "plane_model": _input_ref(planes_inputs[0]),
                "semantic_labels": _input_ref(labels_inputs[0]),
                "input_geometry": {"artifact": "navmesh-input-geometry.obj", "sha256": geometry_sha, "vertices": len(geometry.vertices),
                                   "triangles": len(geometry.triangles), "obstacle_triangles": int(geometry.obstacle.sum()),
                                   "level_change_triangles": int(geometry.level_change.sum()),
                                   "representation": INPUT_REPRESENTATION, "surface_model": surface.summary(),
                                   "ground_labels": list(GROUND_LABELS), "obstacle_labels": list(OBSTACLE_LABELS)},
            },
            "coordinate_frame": provenance,
            "recast_frame": recast_frame_provenance(),
            "recast_config": {"metres": config.as_dict(), "voxels": report.get("config_voxels")},
            "tool": tool,
            "build": {"stages": report.get("stages"), "input": report.get("input")},
        }
        manifest_path = write_json(ctx.workdir / "navmesh-manifest.json", manifest)

        # ---- 5. routing graphs from the Detour polygon links ----
        graph_doc = navigation_graph_document(build, navmesh_ref, tool, config, provenance, max_ramp_slope_deg=s.navmesh_max_ramp_slope_deg,
                                              surface=surface, source=run_provenance(ctx, frame.id))
        graphs = graph_doc["graphs"]
        graph_path = write_json(ctx.workdir / "navigation-graph.json", graph_doc)
        baking_report = write_json(ctx.workdir / "navigation-baking-report.json", {
            "coordinate_frame": provenance, "source": run_provenance(ctx, frame.id), "rebuild": rebuild,
            "floor_height_m": floor.height, "floor_inliers": len(floor.inlier_indices),
            "labels": {"artifact_kind": labels_kind, "counts": label_counts}, "surface_model": surface.summary(),
            "input_representation": INPUT_REPRESENTATION,
            "input_triangles": len(geometry.triangles), "input_obstacle_triangles": int(geometry.obstacle.sum()),
            "input_level_change_triangles": int(geometry.level_change.sum()),
            "level_change_polygons": sum(1 for p in build.polygons if p.level_change),
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


def _parent_surface(ctx: StageContext, s, to_canonical, splat_sha: str, reference_z: float) -> SurfaceModel:
    """The surface model of the parent scene the re-scan was spliced into: GLOBAL_CLOUD + GLOBAL_LABELS (the parent
    version's pinned cloud and its labels), with the settings and floor height the merged scene is baked with. The chain is
    checked first: SPLICE_REPORT must say REGION_SPLICE made exactly the SPLAT_MERGED being baked, from exactly this
    GLOBAL_CLOUD and these GLOBAL_LABELS."""
    clouds, label_inputs, splices = ctx.inputs_of("GLOBAL_CLOUD"), ctx.inputs_of("GLOBAL_LABELS"), ctx.inputs_of("SPLICE_REPORT")
    missing = [k for k, v in (("GLOBAL_CLOUD", clouds), ("GLOBAL_LABELS", label_inputs), ("SPLICE_REPORT", splices)) if not v]
    if missing:
        raise StageError(f"an incremental re-scan's NAVIGATION_BAKING needs {', '.join(missing)} to show the re-bake preserves the "
                         "venue outside the region", code="INPUT_INVALID", details={"missing": missing})
    splice = json.loads(splices[0].path.read_text(encoding="utf-8"))
    chain = {"merged": ((splice.get("output") or {}).get("sha256"), splat_sha),
             "global_cloud": (((splice.get("inputs") or {}).get("global_cloud") or {}).get("sha256"), sha256_file(clouds[0].path)),
             "global_labels": (((splice.get("labels") or {}).get("venue_labels") or {}).get("sha256"), sha256_file(label_inputs[0].path))}
    broken = {k: {"splice_report": a, "given": b} for k, (a, b) in chain.items() if a != b}
    if broken:
        raise NavmeshError(f"the re-scan's inputs are not the ones REGION_SPLICE used ({', '.join(broken)})",
                           code=NAVMESH_REGION_INCONSISTENT, details=broken)
    parent_cloud = read_ply(clouds[0].path)
    parent_labels = np.array(json.loads(label_inputs[0].path.read_text(encoding="utf-8"))["labels"], dtype=object)
    if len(parent_labels) != len(parent_cloud):
        raise NavmeshError(f"GLOBAL_LABELS has {len(parent_labels)} labels for {len(parent_cloud)} Gaussians of GLOBAL_CLOUD",
                           code=NAVMESH_LABELS_UNAVAILABLE)
    positions = to_canonical.apply(parent_cloud.positions)
    return build_surface_model(positions[np.isin(parent_labels, GROUND_LABELS)], positions[np.isin(parent_labels, OBSTACLE_LABELS)],
                               reference_z=reference_z, cell_m=s.navmesh_floor_grid_m,
                               min_points_per_cell=s.navmesh_floor_min_points_per_cell, ground_band_m=s.navmesh_ground_band_m,
                               agent_max_climb_m=s.navmesh_agent_max_climb_m, agent_height_m=s.navmesh_agent_height_m,
                               step_min_rise_m=s.navmesh_step_min_rise_m, max_level_above_reference_m=s.navmesh_max_level_above_floor_m,
                               accessible_width_m=s.navmesh_accessible_width_m, width_cap_m=s.navmesh_width_cap_m)
