"""Generates the NAVIGATION_BAKING output fixture: the REAL Recast/Detour output for a tiny hand-made mesh.

services/reconstruction/tests/fixtures/navmesh/room_with_doorway.obj (an 8 x 4 m room split by a wall with a doorway, a
furniture block and a steep wedge, in canonical metres) is baked by the chaya-navmesh tool -- the real recastnavigation
library -- with the worker's production Recast settings, and written exactly as NAVIGATION_BAKING writes it:

    navmesh.bin              the Detour navmesh tile (NAVMESH)
    navmesh-manifest.json    its provenance (NAVMESH_MANIFEST)
    navigation-graph.json    the Detour polygon graph, bound to navmesh.bin by SHA-256 (NAVIGATION_GRAPH)

dev.chaya.api's PipelineControlPlaneTest reports these bytes as a NAVIGATION_BAKING stage result and routes on what
ingestion stores. The geometry is a fixture, not a venue: this proves the Recast -> ingestion -> routing path, never
routing quality on real reconstructed data. The coordinate frame id is the placeholder FIXTURE_FRAME_ID; a consumer
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

from chaya_worker.frames import HANDEDNESS, UNITS, UP_AXIS  # noqa: E402
from chaya_worker.navmesh import read_obj  # noqa: E402
from chaya_worker.recast import RecastConfig, bake, recast_frame_provenance, tool_version  # noqa: E402
from chaya_worker.runner import CommandRunner  # noqa: E402
from chaya_worker.settings import Settings  # noqa: E402
from chaya_worker.stages.navigation_baking import NAVMESH_STATUS_READY, navigation_graph_document, navmesh_reference  # noqa: E402

FIXTURE_OBJ = REPO / "services" / "reconstruction" / "tests" / "fixtures" / "navmesh" / "room_with_doorway.obj"
FRAME = {"id": "FIXTURE_FRAME_ID", "source_run_id": None, "version": 1, "horizontal_datum": "FLOOR_LOCAL", "units": UNITS,
         "up_axis": UP_AXIS, "handedness": HANDEDNESS}


def main() -> None:
    tool = os.environ.get("CHAYA_NAVMESH_BIN") or shutil.which("chaya-navmesh")
    if not tool:
        sys.exit("set CHAYA_NAVMESH_BIN to the built chaya-navmesh tool")
    versions = tool_version(tool)
    settings = Settings()
    config = RecastConfig.from_settings(settings)
    with tempfile.TemporaryDirectory() as tmp:
        work = Path(tmp)
        build = bake(tool, read_obj(FIXTURE_OBJ), config, work, CommandRunner(work / "stdout.log", work / "stderr.log"))
        shutil.copyfile(build.navmesh_path, HERE / "navmesh.bin")
        ref = navmesh_reference(build)
        graph = navigation_graph_document(build, ref, versions, config, FRAME, max_ramp_slope_deg=settings.navmesh_max_ramp_slope_deg)
        manifest = {"status": NAVMESH_STATUS_READY, "navmesh": ref,
                    "source": {"fixture": FIXTURE_OBJ.relative_to(REPO).as_posix(), "note": "fixture geometry, not a reconstruction"},
                    "coordinate_frame": FRAME, "recast_frame": recast_frame_provenance(),
                    "recast_config": {"metres": config.as_dict(), "voxels": build.report.get("config_voxels")},
                    "tool": versions, "build": {"stages": build.report.get("stages"), "input": build.report.get("input")}}
    (HERE / "navigation-graph.json").write_text(json.dumps(graph, indent=1) + "\n", encoding="utf-8")
    (HERE / "navmesh-manifest.json").write_text(json.dumps(manifest, indent=1) + "\n", encoding="utf-8")
    print(f"navmesh {ref['sha256']} ({ref['polygon_count']} polygons) with {versions}")


if __name__ == "__main__":
    main()
