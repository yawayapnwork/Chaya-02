"""NAVIGATION_BAKING end to end through the orchestrator with the real Recast/Detour tool, on cleaned reconstruction
outputs: a splat PLY, PLANE_FITTING's PLANE_MODEL and SEMANTIC_LABELS_CLEAN, in a reconstruction frame that is NOT the
canonical one (half scale and +Y up, as SfM output typically is). The stage must move everything into canonical metres,
bake it with Recast, and publish a navmesh whose provenance, checksum and routing graph all agree.

The scene (canonical metres, +Z up): an L-shaped floor -- 6 x 3 m plus a 3 x 3 m arm, with the 3 x 3 m quadrant at
x > 3, y > 3 never scanned -- a furniture block at x 1..2, y 1..2, and a ceiling at 2.8 m.
"""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pytest

from chaya_worker.frames import Similarity
from chaya_worker.ply import GaussianCloud, write_ply
from chaya_worker.recast import find_path, sha256_file
from chaya_worker.runner import CommandRunner
from chaya_worker.toolchain import Toolchain
from tests.conftest import DERIVED_BUCKET

pytestmark = pytest.mark.navmesh

# reconstruction -> canonical: scale 0.5 (the reconstruction is in half-metres) and +90 degrees about X (its +Y is up).
TO_CANONICAL = Similarity.from_quaternion(0.5, np.array([np.cos(np.pi / 4), np.sin(np.pi / 4), 0.0, 0.0]), np.array([10.0, -4.0, 1.5]))
FRAME = {"id": "frame-navmesh", "sourceRunId": "run-fixture", "version": 3, "metricStatus": "METRIC", "gravityStatus": "ALIGNED",
         "horizontalDatum": "FLOOR_LOCAL", **TO_CANONICAL.to_dict()}
FURNITURE = (1.0, 2.0, 1.0, 2.0)
MISSING_QUADRANT = (3.0, 6.0, 3.0, 6.0)


def _grid(x0, x1, y0, y1, z, step=0.05):
    xs, ys = np.meshgrid(np.arange(x0, x1, step) + step / 2, np.arange(y0, y1, step) + step / 2)
    return np.stack([xs.ravel(), ys.ravel(), np.full(xs.size, z)], axis=1)


def _scene(tmp_path: Path) -> tuple[Path, Path, Path]:
    floor = np.vstack([_grid(0, 6, 0, 3, 0.0), _grid(0, 3, 3, 6, 0.0)])
    furniture = np.vstack([_grid(1.0, 2.0, 1.0, 2.0, z, step=0.1) for z in (0.3, 0.6, 0.9)])
    ceiling = _grid(0, 6, 0, 6, 2.8, step=0.1)
    canonical = np.vstack([floor, furniture, ceiling])
    labels = ["floor"] * len(floor) + ["furniture"] * len(furniture) + ["ceiling"] * len(ceiling)
    positions = TO_CANONICAL.inverse().apply(canonical)  # what the reconstruction actually holds
    n = len(positions)
    splat = write_ply(GaussianCloud(positions.astype(np.float32), np.zeros((n, 3), np.float32), np.tile([1, 0, 0, 0], (n, 1)).astype(np.float32),
                                    np.zeros(n, np.float32), np.zeros((n, 3), np.float32)), tmp_path / "splat_merged.ply")
    up_in_reconstruction = TO_CANONICAL.inverse().apply_direction(np.array([0.0, 0.0, 1.0]))
    planes = tmp_path / "planes.json"
    planes.write_text(json.dumps({"planes": [
        {"equation": [*up_in_reconstruction.tolist(), 0.0], "inlier_indices": list(range(len(floor)))},
        {"equation": [*(-up_in_reconstruction).tolist(), 0.0], "inlier_indices": list(range(len(floor) + len(furniture), n))},
    ]}))
    labels_path = tmp_path / "labels.json"
    labels_path.write_text(json.dumps({"labels": labels}))
    return splat, planes, labels_path


@pytest.fixture
def baked(harness, navmesh_tool, tmp_path):
    splat, planes, labels = _scene(tmp_path)
    inputs = [_derived(harness, "SPLAT_MERGED", splat), _derived(harness, "PLANE_MODEL", planes, "application/json"),
              _derived(harness, "SEMANTIC_LABELS_CLEAN", labels, "application/json")]
    order = harness.order("NAVIGATION_BAKING", inputs)
    order["coordinateFrame"] = FRAME
    report = harness.run(order, toolchain=Toolchain(env={"CHAYA_NAVMESH_BIN": navmesh_tool}))
    return report, inputs, order


def _derived(harness, kind: str, path: Path, content_type: str = "application/octet-stream") -> dict:
    """An input produced after privacy preprocessing: never PII, as the control plane would offer it."""
    ref = harness.raw_input(kind, path, content_type)
    ref["containsPii"] = False
    return ref


def _download(harness, report, kind, tmp_path) -> Path:
    art = next(a for a in report["artifacts"] if a["kind"] == kind)
    dest = tmp_path / "downloads" / Path(art["key"]).name
    dest.parent.mkdir(exist_ok=True)
    harness.storage.download(DERIVED_BUCKET, art["key"], dest)
    assert sha256_file(dest) == art["sha256"]
    return dest


def test_stage_publishes_a_real_navmesh_with_full_provenance(harness, baked, navmesh_tool, tmp_path):
    report, inputs, order = baked
    assert report["status"] == "SUCCEEDED", report.get("errorMessage")
    assert {a["kind"] for a in report["artifacts"]} == {"NAVMESH", "NAVMESH_MANIFEST", "NAVMESH_INPUT_GEOMETRY", "NAVIGATION_GRAPH",
                                                          "NAVIGATION_BAKING_REPORT"}
    navmesh = _download(harness, report, "NAVMESH", tmp_path)
    manifest = json.loads(_download(harness, report, "NAVMESH_MANIFEST", tmp_path).read_text())
    geometry = _download(harness, report, "NAVMESH_INPUT_GEOMETRY", tmp_path)

    assert navmesh.read_bytes()[:4] == b"VAND"
    assert manifest["status"] == "READY"
    assert manifest["navmesh"]["sha256"] == sha256_file(navmesh) and manifest["navmesh"]["format"] == "detour-tile"
    assert manifest["source"]["scan_version_id"] == order["scanVersionId"] and manifest["source"]["run_id"] == order["runId"]
    assert manifest["source"]["splat"]["artifactId"] == inputs[0]["artifactId"] and manifest["source"]["splat"]["sha256"] == inputs[0]["sha256"]
    assert manifest["source"]["input_geometry"]["sha256"] == sha256_file(geometry)
    assert manifest["coordinate_frame"]["id"] == FRAME["id"] and manifest["coordinate_frame"]["up_axis"] == "+Z"
    assert manifest["recast_frame"]["up_axis"] == "+Y"
    assert manifest["recast_config"]["metres"]["agent_radius_m"] == 0.35 and manifest["recast_config"]["voxels"]["walkableRadius"] == 4
    assert manifest["tool"] == {"tool": "chaya-navmesh", "tool_version": "1.0.0", "recastnavigation_version": "1.6.0"}
    assert manifest["build"]["stages"]["polygons"] > 0
    assert manifest["source"]["input_geometry"]["obstacle_triangles"] > 0, "the furniture labels became blocked geometry"


def test_routing_graph_is_bound_to_the_navmesh_and_respects_the_real_geometry(harness, baked, navmesh_tool, tmp_path):
    report, _, _ = baked
    assert report["status"] == "SUCCEEDED", report.get("errorMessage")
    navmesh = _download(harness, report, "NAVMESH", tmp_path)
    graph = json.loads(_download(harness, report, "NAVIGATION_GRAPH", tmp_path).read_text())
    assert graph["navmesh"]["source"] == "RECAST_NAVMESH" and graph["navmesh"]["status"] == "READY"
    assert graph["navmesh"]["sha256"] == sha256_file(navmesh) and graph["navmesh"]["recastnavigation_version"] == "1.6.0"
    assert graph["coordinate_frame"]["id"] == FRAME["id"]

    nodes = np.array([[n["x"], n["y"], n["z"]] for n in graph["graphs"]["STANDARD"]["nodes"]])
    assert len(nodes) > 0 and len(graph["graphs"]["STANDARD"]["edges"]) > 0
    assert np.abs(nodes[:, 2]).max() < 0.15, "nodes are on the canonical floor (z = 0), not in reconstruction units"
    in_quadrant = (nodes[:, 0] > 3) & (nodes[:, 1] > 3)
    assert not in_quadrant.any(), "nothing routes through the unscanned quadrant (no convex hull)"
    assert graph["graphs"]["STEP_FREE"]["edges"], "the flat floor is step-free"

    # Detour's own path between the two arms of the L bends at the inner corner instead of cutting the missing quadrant.
    runner = CommandRunner(tmp_path / "o.log", tmp_path / "e.log")
    route = find_path(navmesh_tool, navmesh, np.array([5.5, 1.5, 0.0]), np.array([1.5, 5.5, 0.0]), tmp_path, runner,
                      snap_horizontal_m=0.5, snap_vertical_m=1.0)
    path = np.array(route["straight_path"])
    assert len(path) >= 3
    for a, b in zip(path[:-1], path[1:], strict=True):
        for t in np.linspace(0, 1, 50):
            p = a + (b - a) * t
            assert not (p[0] > 3.0 and p[1] > 3.0), f"the route enters the unscanned quadrant at {p}"
            assert not (FURNITURE[0] < p[0] < FURNITURE[1] and FURNITURE[2] < p[1] < FURNITURE[3]), "the route crosses the furniture"


def test_stage_reports_no_walkable_surface_when_there_is_no_floor(harness, navmesh_tool, tmp_path):
    splat, _, labels = _scene(tmp_path)
    walls_only = tmp_path / "walls.json"
    walls_only.write_text(json.dumps({"planes": [{"equation": [1.0, 0.0, 0.0, 0.0], "inlier_indices": [0, 1, 2, 3]}]}))
    order = harness.order("NAVIGATION_BAKING", [_derived(harness, "SPLAT_MERGED", splat),
                                                _derived(harness, "PLANE_MODEL", walls_only, "application/json")])
    order["coordinateFrame"] = FRAME
    report = harness.run(order, toolchain=Toolchain(env={"CHAYA_NAVMESH_BIN": navmesh_tool}))
    assert report["status"] == "FAILED" and report["errorCode"] == "NO_WALKABLE_SURFACE" and report["artifacts"] == []


def test_stage_reports_navmesh_tool_unavailable_and_publishes_nothing(harness, tmp_path):
    splat, planes, _ = _scene(tmp_path)
    order = harness.order("NAVIGATION_BAKING", [_derived(harness, "SPLAT_MERGED", splat), _derived(harness, "PLANE_MODEL", planes)])
    order["coordinateFrame"] = FRAME
    report = harness.run(order, toolchain=Toolchain(env={}, which=lambda _n: None))
    assert report["status"] == "FAILED" and report["errorCode"] == "NAVMESH_TOOL_UNAVAILABLE" and report["artifacts"] == []
    assert report["errorDetails"]["missing"] == ["chaya-navmesh"]

