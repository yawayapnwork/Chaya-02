"""A stitched floor (docs/multi-room.md) baked by the real Recast/Detour tool: the three rooms of the SYNTHETIC multi-room
floor (tests/multiroom_scene.py) registered, stitched and baked through the orchestrator, then routed on across their
doorways. Feature matching is a stand-in (multiroom_scene.oracle_matches). Nothing here validates a real venue."""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pytest

from chaya_worker.ply import read_ply, write_ply
from chaya_worker.recast import find_path, sha256_file
from chaya_worker.region_splice import _concat, transform_gaussians
from chaya_worker.runner import CommandRunner
from chaya_worker.stages import room_registration as stage_module
from chaya_worker.toolchain import Toolchain
from tests.conftest import DERIVED_BUCKET, Harness
from tests.multiroom_scene import CALIBRATION, DOORS, LOCAL, ROOMS, Floor, capture, gaussians, oracle_matches, wire

pytestmark = pytest.mark.navmesh
FLOOR_DENSITY = 2500  # points per square metre of floor: no 0.1 m cell of the floor grid without its 3 points

OUTPUT_TO_VENUE = LOCAL["A"].inverse().compose(CALIBRATION["A"])  # the stitched floor is in room A's reconstruction frame
VENUE_TO_NAV = LOCAL["A"]  # NAVIGATION_BAKING works in A's canonical frame; routes are asked for there


def _derived(harness, kind: str, path: Path, content_type: str = "application/octet-stream") -> dict:
    ref = harness.raw_input(kind, path, content_type)
    ref["containsPii"] = False
    return ref


def _download(harness, report, kind, tmp_path) -> Path:
    art = next(a for a in report["artifacts"] if a["kind"] == kind)
    dest = tmp_path / "downloads" / Path(art["key"]).name
    dest.parent.mkdir(exist_ok=True)
    harness.storage.download(DERIVED_BUCKET, art["key"], dest)
    return dest


def _plane_model(splat: Path, labels: list[str]) -> dict:
    """PLANE_FITTING's floor plane of the stitched cloud (Open3D's RANSAC is not run here): the floor-labelled Gaussians at
    floor height, the plane's normal in the cloud's own frame, naming the cloud it was fitted on."""
    cloud = read_ply(splat)
    venue = OUTPUT_TO_VENUE.apply(cloud.positions)
    floor = np.flatnonzero((np.array(labels) == "floor") & (np.abs(venue[:, 2]) < 0.02))
    up = OUTPUT_TO_VENUE.inverse().apply_direction(np.array([0.0, 0.0, 1.0]))
    return {"planes": [{"equation": [*up.tolist(), 0.0], "inlier_indices": floor.tolist()}],
            "source": {"splat": {"kind": "SPLAT_MERGED", "sha256": sha256_file(splat)}}}


def _bake(harness, navmesh_tool, tmp_path, merged: Path, labels: Path, stitch_report: Path, **settings):
    planes = tmp_path / "planes.json"
    planes.write_text(json.dumps(_plane_model(merged, json.loads(labels.read_text())["labels"])))
    order = harness.order("NAVIGATION_BAKING", [_derived(harness, "SPLAT_MERGED", merged), _derived(harness, "PLANE_MODEL", planes),
                                                _derived(harness, "SEMANTIC_LABELS_MERGED", labels, "application/json"),
                                                _derived(harness, "STITCH_REPORT", stitch_report, "application/json")])
    order["coordinateFrame"] = wire("A")
    runner = harness if not settings else Harness(tmp_path / "h", **settings)
    if runner is not harness:
        runner.storage = harness.storage
    return runner.run(order, toolchain=Toolchain(env={"CHAYA_NAVMESH_BIN": navmesh_tool}))


def _route(navmesh_tool, navmesh, tmp_path, start_venue, end_venue) -> np.ndarray:
    runner = CommandRunner(tmp_path / "o.log", tmp_path / "e.log")
    path = find_path(navmesh_tool, navmesh, VENUE_TO_NAV.apply(np.array(start_venue)), VENUE_TO_NAV.apply(np.array(end_venue)),
                     tmp_path, runner, snap_horizontal_m=0.5, snap_vertical_m=0.5)["straight_path"]
    return VENUE_TO_NAV.inverse().apply(np.array(path))


def _crossings(path: np.ndarray, axis: int, at: float) -> list[float]:
    """Where the polyline crosses the plane `axis` = `at`: the other horizontal coordinate there."""
    other = 1 - axis
    return [a[other] + (b[other] - a[other]) * (at - a[axis]) / (b[axis] - a[axis])
            for a, b in zip(path[:-1], path[1:], strict=True) if (a[axis] - at) * (b[axis] - at) < 0]


@pytest.fixture(scope="module")
def stitched(tmp_path_factory):
    """The three rooms registered and stitched through the orchestrator, at a density that leaves no hole in the floor grid."""
    tmp = tmp_path_factory.mktemp("stitched")
    harness = Harness(tmp)
    floor = Floor(harness, tmp, density=400, floor_density=FLOOR_DENSITY)
    mp = pytest.MonkeyPatch()
    mp.setattr(stage_module, "match_features", oracle_matches(floor.canonical, LOCAL))
    try:
        reg = harness.run(floor.order("ROOM_REGISTRATION"))
        assert reg["status"] == "SUCCEEDED", reg.get("errorMessage")
        stitch = harness.run(floor.order("ROOM_STITCH", extra_inputs=harness.outputs_as_inputs(reg)))
        assert stitch["status"] == "SUCCEEDED", stitch.get("errorMessage")
    finally:
        mp.undo()
    return harness, tmp, {k: _download(harness, stitch, k, tmp) for k in ("SPLAT_MERGED", "SEMANTIC_LABELS_MERGED", "STITCH_REPORT")}


def test_a_stitched_floor_bakes_into_one_navmesh_whose_routes_cross_room_boundaries(stitched, navmesh_tool, tmp_path):
    harness, _, files = stitched
    report = _bake(harness, navmesh_tool, tmp_path, files["SPLAT_MERGED"], files["SEMANTIC_LABELS_MERGED"], files["STITCH_REPORT"])
    assert report["status"] == "SUCCEEDED", (report.get("errorCode"), report.get("errorMessage"))
    manifest = json.loads(_download(harness, report, "NAVMESH_MANIFEST", tmp_path).read_text())
    rooms = manifest["rebuild"]["rooms"]
    assert rooms["connected"] and rooms["unreachable_rooms"] == [] and rooms["step_free_connected"]
    pairs = {("space-A", "space-B"), ("space-A", "space-C"), ("space-B", "space-C")}
    assert {tuple(c["rooms"]) for c in rooms["connections"]} == pairs, "every pair of rooms shares a doorway"
    assert all(c["step_free"] for c in rooms["connections"])
    assert {tuple(w["rooms"]) for w in rooms["clear_widths"]} == pairs
    for w in rooms["clear_widths"]:  # 1.2 m doorways, measured on the 0.1 m grid: the jambs' cells and centring lose up to 0.3 m
        assert 0.8 <= w["clear_width_m"] <= 1.2, w
        assert w["accessible_width"]
    assert all(r["nodes"] > 0 and r["reached"] for r in rooms["rooms"].values())

    navmesh = _download(harness, report, "NAVMESH", tmp_path)
    a_to_b = _route(navmesh_tool, navmesh, tmp_path, [1.0, 1.5, 0.0], [9.0, 2.0, 0.0])
    xs = _crossings(a_to_b, 0, 5.0)  # the A|B wall is x = 5; its door spans y 1.5 - 2.5
    assert xs and all(DOORS["AB"][2][0] < y < DOORS["AB"][2][1] for y in xs), xs
    a_to_corridor = _route(navmesh_tool, navmesh, tmp_path, [1.0, 1.5, 0.0], [8.5, 5.0, 0.0])
    ys = _crossings(a_to_corridor, 1, 4.0)  # the rooms|corridor wall is y = 4; doors AC x 2-3, BC x 7-8
    assert ys and all(DOORS["AC"][2][0] < x < DOORS["AC"][2][1] or DOORS["BC"][2][0] < x < DOORS["BC"][2][1] for x in ys), ys


def _exact_stitch(tmp_path: Path, closed_doors: tuple[str, ...]) -> dict[str, Path]:
    """A stitched floor built from the rooms' exact placements (no registration: a room behind closed doors could not be
    registered at all), in room A's reconstruction frame, with the STITCH_REPORT NAVIGATION_BAKING reads: the output's
    checksum and each room's block."""
    pieces, labels, blocks, start = [], [], [], 0
    for i, r in enumerate(ROOMS):
        p, lab = capture(r, seed=i, density=400, noise_m=0.003, peeks=[], closed_doors=closed_doors, floor_density=FLOOR_DENSITY)
        pieces.append(transform_gaussians(gaussians(p, seed=i), OUTPUT_TO_VENUE.inverse()))
        labels += [str(x) for x in lab]
        blocks.append({"piece": f"space-{r}", "start": start, "count": len(p)})
        start += len(p)
    merged = pieces[0]
    for piece in pieces[1:]:
        merged = _concat(merged, piece)
    merged_path = write_ply(merged, tmp_path / "merged.ply")
    labels_path = tmp_path / "labels.json"
    labels_path.write_text(json.dumps({"labels": labels, "cloud": {"sha256": sha256_file(merged_path)}}))
    report_path = tmp_path / "stitch-report.json"
    report_path.write_text(json.dumps({"output": {"sha256": sha256_file(merged_path)}, "blocks": blocks}))
    return {"SPLAT_MERGED": merged_path, "SEMANTIC_LABELS_MERGED": labels_path, "STITCH_REPORT": report_path}


def test_a_room_behind_closed_doors_disconnects_the_stitched_floor_and_nothing_is_published(harness, navmesh_tool, tmp_path):
    files = _exact_stitch(tmp_path, closed_doors=("AB", "BC"))  # both of room B's doors are shut
    report = _bake(harness, navmesh_tool, tmp_path, files["SPLAT_MERGED"], files["SEMANTIC_LABELS_MERGED"], files["STITCH_REPORT"])
    assert report["status"] == "FAILED" and report["errorCode"] == "NAVMESH_ROOMS_DISCONNECTED", report.get("errorMessage")
    assert report["errorDetails"]["unreachable_rooms"] == ["space-B"]
    assert {tuple(c["rooms"]) for c in report["errorDetails"]["connections"]} == {("space-A", "space-C")}
    widths = {tuple(w["rooms"]): w["clear_width_m"] for w in report["errorDetails"]["clear_widths"]}
    assert widths[("space-A", "space-B")] == 0.0 and widths[("space-B", "space-C")] == 0.0 and widths[("space-A", "space-C")] > 0.8
    assert [a for a in report["artifacts"] if not a["kind"].startswith("LOG_")] == []


def test_the_same_floor_with_its_doors_open_is_connected(harness, navmesh_tool, tmp_path):
    files = _exact_stitch(tmp_path, closed_doors=())
    report = _bake(harness, navmesh_tool, tmp_path, files["SPLAT_MERGED"], files["SEMANTIC_LABELS_MERGED"], files["STITCH_REPORT"])
    assert report["status"] == "SUCCEEDED", report.get("errorMessage")


def test_a_disconnected_floor_can_be_baked_when_the_operator_allows_it_and_the_gap_is_recorded(harness, navmesh_tool, tmp_path):
    files = _exact_stitch(tmp_path, closed_doors=("AB", "BC"))
    report = _bake(harness, navmesh_tool, tmp_path, files["SPLAT_MERGED"], files["SEMANTIC_LABELS_MERGED"], files["STITCH_REPORT"],
                   stitch_require_connected_rooms=False)
    assert report["status"] == "SUCCEEDED", report.get("errorMessage")
    manifest = json.loads(_download(harness, report, "NAVMESH_MANIFEST", tmp_path).read_text())
    assert manifest["rebuild"]["rooms"]["unreachable_rooms"] == ["space-B"]


def test_a_stitch_report_of_another_cloud_is_refused(harness, navmesh_tool, tmp_path):
    files = _exact_stitch(tmp_path, closed_doors=())
    doc = json.loads(files["STITCH_REPORT"].read_text())
    doc["output"]["sha256"] = "0" * 64
    files["STITCH_REPORT"].write_text(json.dumps(doc))
    report = _bake(harness, navmesh_tool, tmp_path, files["SPLAT_MERGED"], files["SEMANTIC_LABELS_MERGED"], files["STITCH_REPORT"])
    assert report["errorCode"] == "INVALID_GEOMETRY" and "STITCH_REPORT" in report["errorMessage"]
