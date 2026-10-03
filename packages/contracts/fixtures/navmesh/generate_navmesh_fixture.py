"""Generates the NAVIGATION_BAKING output fixture: the REAL Recast/Detour output for a synthetic reconstructed venue.

services/reconstruction/tests/navmesh/venue_scene.py (labelled points, as GEOMETRIC_CLEANUP leaves a cleaned splat: a
12 x 7 m floor split by a wall with a 1.1 m and a 1.6 m doorway, a sofa, a column, a railing, and a platform 0.17 m up
reached over a step or up a 1:14 ramp, in canonical metres) goes through the stage's own geometry code -- surface model,
Recast input -- and is baked by the chaya-navmesh tool, the real recastnavigation library, with the worker's production
settings, then written exactly as NAVIGATION_BAKING writes it:

    navmesh.bin              the Detour navmesh tile (NAVMESH)
    navmesh-manifest.json    its provenance (NAVMESH_MANIFEST)
    navigation-graph.json    the Detour polygon graph, bound to navmesh.bin by SHA-256 (NAVIGATION_GRAPH)

dev.chaya.api's PipelineControlPlaneTest reports these bytes as a NAVIGATION_BAKING stage result and routes on what
ingestion stores. The geometry is a fixture, not a venue: this proves the reconstruction-geometry -> Recast -> ingestion
-> routing path, never routing quality on real reconstructed data. The coordinate frame id is the placeholder FIXTURE_FRAME_ID; a consumer
substitutes the id of the frame it calibrated.

    cmake -S services/reconstruction/native/chaya-navmesh -B services/reconstruction/native/chaya-navmesh/build
    cmake --build services/reconstruction/native/chaya-navmesh/build
    CHAYA_NAVMESH_BIN=... python packages/contracts/fixtures/navmesh/generate_navmesh_fixture.py
"""

from __future__ import annotations

import json
import os
import shutil
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[3]
sys.path.insert(0, str(REPO / "services" / "reconstruction"))

import numpy as np  # noqa: E402

from chaya_worker.frames import HANDEDNESS, UNITS, UP_AXIS  # noqa: E402
from chaya_worker.navmesh import GROUND_LABELS, OBSTACLE_LABELS, build_surface_model, geometry_from_surface  # noqa: E402
from chaya_worker.recast import RecastConfig, bake, recast_frame_provenance, tool_version  # noqa: E402
from chaya_worker.runner import CommandRunner  # noqa: E402
from chaya_worker.settings import Settings  # noqa: E402
from chaya_worker.stages.navigation_baking import (  # noqa: E402
    INPUT_REPRESENTATION,
    NAVMESH_STATUS_READY,
    navigation_graph_document,
    navmesh_reference,
)
from tests.navmesh import venue_scene  # noqa: E402

SCENE = REPO / "services" / "reconstruction" / "tests" / "navmesh" / "venue_scene.py"
FRAME = {"id": "FIXTURE_FRAME_ID", "source_run_id": None, "version": 1, "horizontal_datum": "FLOOR_LOCAL", "units": UNITS,
         "up_axis": UP_AXIS, "handedness": HANDEDNESS}


def main() -> None:
    tool = os.environ.get("CHAYA_NAVMESH_BIN") or shutil.which("chaya-navmesh")
    if not tool:
        sys.exit("set CHAYA_NAVMESH_BIN to the built chaya-navmesh tool")
    versions = tool_version(tool)
    settings = Settings()
    config = RecastConfig.from_settings(settings)
    s = settings
    points, labels = venue_scene.build()
    surface = build_surface_model(points[np.isin(labels, GROUND_LABELS)], points[np.isin(labels, OBSTACLE_LABELS)], reference_z=0.0,
                                  cell_m=s.navmesh_floor_grid_m, min_points_per_cell=s.navmesh_floor_min_points_per_cell,
                                  ground_band_m=s.navmesh_ground_band_m, agent_max_climb_m=s.navmesh_agent_max_climb_m,
                                  agent_height_m=s.navmesh_agent_height_m, step_min_rise_m=s.navmesh_step_min_rise_m,
                                  max_level_above_reference_m=s.navmesh_max_level_above_floor_m,
                                  accessible_width_m=s.navmesh_accessible_width_m, width_cap_m=s.navmesh_width_cap_m)
    geometry = geometry_from_surface(surface, obstacle_min_height_m=s.navmesh_obstacle_min_height_m)
    with tempfile.TemporaryDirectory() as tmp:
        work = Path(tmp)
        build = bake(tool, geometry, config, work, CommandRunner(work / "stdout.log", work / "stderr.log"))
        shutil.copyfile(build.navmesh_path, HERE / "navmesh.bin")
        ref = navmesh_reference(build)
        graph = navigation_graph_document(build, ref, versions, config, FRAME, max_ramp_slope_deg=settings.navmesh_max_ramp_slope_deg,
                                          surface=surface)
        manifest = {"status": NAVMESH_STATUS_READY, "navmesh": ref,
                    "source": {"fixture": SCENE.relative_to(REPO).as_posix(), "note": "synthetic venue geometry, not a reconstruction",
                               "input_geometry": {"representation": INPUT_REPRESENTATION, "surface_model": surface.summary(),
                                                  "triangles": len(geometry.triangles),
                                                  "obstacle_triangles": int(geometry.obstacle.sum()),
                                                  "level_change_triangles": int(geometry.level_change.sum())}},
                    "coordinate_frame": FRAME, "recast_frame": recast_frame_provenance(),
                    "recast_config": {"metres": config.as_dict(), "voxels": build.report.get("config_voxels")},
                    "tool": versions, "build": {"stages": build.report.get("stages"), "input": build.report.get("input")}}
    (HERE / "navigation-graph.json").write_text(json.dumps(graph, indent=1) + "\n", encoding="utf-8")
    (HERE / "navmesh-manifest.json").write_text(json.dumps(manifest, indent=1) + "\n", encoding="utf-8")
    print(f"navmesh {ref['sha256']} ({ref['polygon_count']} polygons) with {versions}")


if __name__ == "__main__":
    main()
