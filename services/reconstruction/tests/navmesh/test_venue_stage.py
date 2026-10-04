"""The venue fixture (tests/navmesh/venue_scene.py) end to end through the orchestrator with the real Recast/Detour tool:
a cleaned splat + labels + plane model in a NON-canonical reconstruction frame -> NAVIGATION_BAKING -> navmesh + routing
graphs -> Detour route; and a re-scan (review N-1) through REGION_SPLICE and NAVIGATION_BAKING on the merged cloud. The
scene is synthetic; nothing here validates navigation in a real venue."""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pytest

from chaya_worker.frames import Similarity
from chaya_worker.navmesh import NavmeshError
from chaya_worker.ply import GaussianCloud, write_ply
from chaya_worker.recast import find_path, sha256_file
from chaya_worker.runner import CommandRunner
from chaya_worker.toolchain import Toolchain
from tests.conftest import DERIVED_BUCKET
from tests.navmesh import venue_scene as V

pytestmark = pytest.mark.navmesh

# reconstruction -> canonical: scale 2 (one reconstruction unit is 2 m), +90 degrees about X (its +Y is up) and an offset,
# as SfM output typically is.
TO_CANONICAL = Similarity.from_quaternion(2.0, np.array([np.cos(np.pi / 4), np.sin(np.pi / 4), 0.0, 0.0]), np.array([-3.0, 4.0, 0.5]))
FRAME = {"id": "frame-venue", "sourceRunId": "run-venue", "version": 2, "metricStatus": "METRIC", "gravityStatus": "ALIGNED",
         "horizontalDatum": "FLOOR_LOCAL", **TO_CANONICAL.to_dict()}
IDENTITY = Similarity.from_quaternion(1.0, np.array([1.0, 0.0, 0.0, 0.0]), np.zeros(3))
CANONICAL_FRAME = {"id": "frame-parent", "sourceRunId": "run-parent", "version": 1, "metricStatus": "METRIC",
                   "gravityStatus": "ALIGNED", "horizontalDatum": "FLOOR_LOCAL", **IDENTITY.to_dict()}


def _cloud(positions: np.ndarray, path: Path) -> Path:
    n = len(positions)
    return write_ply(GaussianCloud(positions.astype(np.float32), np.full((n, 3), -3.0, np.float32),
                                   np.tile([1, 0, 0, 0], (n, 1)).astype(np.float32), np.zeros(n, np.float32),
                                   np.zeros((n, 3), np.float32)), path)


def _json(path: Path, doc: dict) -> Path:
    path.write_text(json.dumps(doc), encoding="utf-8")
    return path


def _planes(canonical: np.ndarray, labels: np.ndarray, to_canonical: Similarity) -> dict:
    floor = np.flatnonzero((labels == "floor") & (np.abs(canonical[:, 2]) < 0.01))
    up = to_canonical.inverse().apply_direction(np.array([0.0, 0.0, 1.0]))
    return {"planes": [{"equation": [*up.tolist(), 0.0], "inlier_indices": floor.tolist()}]}


def _derived(harness, kind: str, path: Path, content_type: str = "application/octet-stream") -> dict:
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


def _bake(harness, navmesh_tool, tmp_path, splat_kind, splat, labels_kind, labels, planes, frame):
    order = harness.order("NAVIGATION_BAKING", [_derived(harness, splat_kind, splat), _derived(harness, "PLANE_MODEL", planes, "application/json"),
                                                _derived(harness, labels_kind, labels, "application/json")])
    order["coordinateFrame"] = frame
    return harness.run(order, toolchain=Toolchain(env={"CHAYA_NAVMESH_BIN": navmesh_tool}))


@pytest.fixture
def venue_report(harness, navmesh_tool, tmp_path):
    canonical, labels = V.build()
    positions = TO_CANONICAL.inverse().apply(canonical)
    splat = _cloud(positions, tmp_path / "splat-clean.ply")
    labels_path = _json(tmp_path / "labels.json", {"labels": [str(v) for v in labels]})
    planes = _json(tmp_path / "planes.json", _planes(canonical, labels, TO_CANONICAL))
    report = _bake(harness, navmesh_tool, tmp_path, "SPLAT_CLEAN", splat, "SEMANTIC_LABELS_CLEAN", labels_path, planes, FRAME)
    assert report["status"] == "SUCCEEDED", report.get("errorMessage")
    return report


def test_a_reconstruction_bakes_to_a_navmesh_whose_routes_respect_its_geometry(harness, navmesh_tool, tmp_path, venue_report):
    """Integration: reconstructed geometry fixture -> NAVIGATION_BAKING (in a scaled, rotated reconstruction frame) ->
    Detour navmesh -> routing graphs and a Detour route, all in canonical metres."""
    graph = json.loads(_download(harness, venue_report, "NAVIGATION_GRAPH", tmp_path).read_text())
    manifest = json.loads(_download(harness, venue_report, "NAVMESH_MANIFEST", tmp_path).read_text())
    navmesh = _download(harness, venue_report, "NAVMESH", tmp_path)
    report = json.loads(_download(harness, venue_report, "NAVIGATION_BAKING_REPORT", tmp_path).read_text())

    assert manifest["source"]["input_geometry"]["level_change_triangles"] > 0, "the step reached Recast"
    assert manifest["source"]["input_geometry"]["obstacle_triangles"] > 0
    assert report["level_change_polygons"] >= 1 and report["labels"]["artifact_kind"] == "SEMANTIC_LABELS_CLEAN"
    assert report["surface_model"]["ground_z_max_m"] == pytest.approx(V.STEP_RISE_M, abs=0.01), "canonical metres, not units"

    std, sf = graph["graphs"]["STANDARD"], graph["graphs"]["STEP_FREE"]
    assert any(e["level_change"] for e in std["edges"]) and not any(e["level_change"] for e in sf["edges"])
    assert all(len(e["portal"]) == 2 for e in std["edges"])
    zs = np.array([n["z"] for n in std["nodes"]])
    assert zs.min() == pytest.approx(0.0, abs=0.02) and zs.max() == pytest.approx(V.STEP_RISE_M, abs=0.02)

    runner = CommandRunner(tmp_path / "o.log", tmp_path / "e.log")
    route = find_path(navmesh_tool, navmesh, np.array([1.0, 1.0, 0.0]), np.array([11.0, 1.5, V.STEP_RISE_M]), tmp_path, runner,
                      snap_horizontal_m=0.5, snap_vertical_m=0.5, step_free=True)
    path = np.array(route["straight_path"])
    crossings = [a[1] + (b[1] - a[1]) * (4.0 - a[0]) / (b[0] - a[0]) for a, b in zip(path[:-1], path[1:], strict=True)
                 if (a[0] - 4.0) * (b[0] - 4.0) < 0] + [p[1] for p in path if p[0] == 4.0]
    assert crossings and all(V.D1[0] < y < V.D1[1] or V.D2[0] < y < V.D2[1] for y in crossings), "through a doorway"
    assert path[:, 1].max() > V.RAMP_LANE_Y[0], "step-free: up the ramp, not over the step"


# ---- review N-1: a re-scan's re-bake keeps the venue's obstacles ----------------------------------------------------------
# The re-bake is a FULL-FLOOR bake of the merged scene, as a re-scan work order runs it (regionGeometry, the parent's
# GLOBAL_CLOUD and GLOBAL_LABELS, this run's SPLICE_REPORT). NAVIGATION_BAKING checks the result against the parent scene
# outside the region and refuses one that does not preserve it.

REGION = [[7.2, 0.2], [12.0, 0.2], [12.0, 2.8], [7.2, 2.8]]  # the step lane: captured again
CRATE = (10.4, 11.2, 0.8, 1.6)  # new since the parent version: a crate on the platform, inside the region
SCAN_VERSION = "scan-version-2"


def _rescan_capture(*, with_crate: bool):
    """The re-captured step lane in canonical metres, a hair apart from the venue's own sampling; with a new crate."""
    venue, venue_labels = V.build()
    inside = (venue[:, 0] > 6.7) & (venue[:, 1] < 3.3) & (venue[:, 1] > -0.3)
    region, labels = venue[inside] + np.array([0.0, 0.0, 0.001]), venue_labels[inside]
    if with_crate:
        under = V.in_box(region, CRATE) & (labels == "floor")
        crate = V._block(CRATE, 0.2 + V.STEP_RISE_M, 1.0 + V.STEP_RISE_M)
        region = np.vstack([region[~under], crate])
        labels = np.concatenate([labels[~under], np.array(["furniture"] * len(crate), dtype=object)])
    return region, labels


def _splice(harness, tmp_path, *, with_venue_labels: bool, with_crate: bool = False):
    """REGION_SPLICE of the re-captured lane into the venue. Returns (report, the parent's GLOBAL_CLOUD/GLOBAL_LABELS refs)."""
    venue, venue_labels = V.build()
    region, region_labels = _rescan_capture(with_crate=with_crate)
    parent = {"GLOBAL_CLOUD": _derived(harness, "GLOBAL_CLOUD", _cloud(venue, tmp_path / "venue.ply")),
              "GLOBAL_LABELS": _derived(harness, "GLOBAL_LABELS", _json(tmp_path / "venue-labels.json",
                                                                        {"labels": [str(v) for v in venue_labels]}), "application/json")}
    inputs = [_derived(harness, "SPLAT_ALIGNED", _cloud(region, tmp_path / "aligned.ply")), parent["GLOBAL_CLOUD"],
              _derived(harness, "SEMANTIC_LABELS_CLEAN", _json(tmp_path / "region-labels.json", {"labels": [str(v) for v in region_labels]}),
                       "application/json")]
    if with_venue_labels:
        inputs.append(parent["GLOBAL_LABELS"])
    order = harness.order("REGION_SPLICE", inputs)
    order.update(coordinateFrame=CANONICAL_FRAME, regionGeometry={"points": REGION}, scanVersionId=SCAN_VERSION)
    return harness.run(order), parent


def _merged_planes(tmp_path, merged: Path, labels_path: Path, *, fitted_on: str | None = None) -> Path:
    """PLANE_FITTING's PLANE_MODEL of the merged cloud, naming the cloud it was fitted on as the stage does."""
    from chaya_worker.ply import read_ply

    positions = read_ply(merged).positions.astype(np.float64)
    labels = np.array(json.loads(labels_path.read_text())["labels"], dtype=object)
    doc = _planes(positions, labels, IDENTITY)
    doc["source"] = {"splat": {"kind": "SPLAT_MERGED", "sha256": fitted_on or sha256_file(merged)}}
    return _json(tmp_path / "merged-planes.json", doc)


def _rebake(harness, navmesh_tool, tmp_path, splice, parent, *, merged_labels: Path | None = None, global_cloud: dict | None = None,
            planes: Path | None = None, drop: tuple[str, ...] = ()):
    """NAVIGATION_BAKING as a re-scan run's work order offers it: this run's SPLAT_MERGED, SEMANTIC_LABELS_MERGED,
    SPLICE_REPORT and PLANE_MODEL, the region's own SPLAT_CLEAN/SEMANTIC_LABELS_CLEAN too (which must not be used), and the
    parent's GLOBAL_CLOUD and GLOBAL_LABELS."""
    merged = _download(harness, splice, "SPLAT_MERGED", tmp_path)
    labels = merged_labels or _download(harness, splice, "SEMANTIC_LABELS_MERGED", tmp_path)
    region, region_labels = _rescan_capture(with_crate=False)
    inputs = {
        "SPLAT_CLEAN": _derived(harness, "SPLAT_CLEAN", _cloud(region, tmp_path / "region-clean.ply")),
        "SEMANTIC_LABELS_CLEAN": _derived(harness, "SEMANTIC_LABELS_CLEAN", _json(tmp_path / "region-clean-labels.json",
                                                                                  {"labels": [str(v) for v in region_labels]}), "application/json"),
        "SPLAT_MERGED": _derived(harness, "SPLAT_MERGED", merged),
        "SEMANTIC_LABELS_MERGED": _derived(harness, "SEMANTIC_LABELS_MERGED", labels, "application/json"),
        "SPLICE_REPORT": _derived(harness, "SPLICE_REPORT", _download(harness, splice, "SPLICE_REPORT", tmp_path), "application/json"),
        "PLANE_MODEL": _derived(harness, "PLANE_MODEL", planes or _merged_planes(tmp_path, merged, labels), "application/json"),
        "GLOBAL_CLOUD": global_cloud or parent["GLOBAL_CLOUD"],
        "GLOBAL_LABELS": parent["GLOBAL_LABELS"],
    }
    order = harness.order("NAVIGATION_BAKING", [v for k, v in inputs.items() if k not in drop])
    order.update(coordinateFrame=CANONICAL_FRAME, regionGeometry={"points": REGION}, scanVersionId=SCAN_VERSION)
    return harness.run(order, toolchain=Toolchain(env={"CHAYA_NAVMESH_BIN": navmesh_tool}))


def _path_samples(navmesh_tool, navmesh, tmp_path, start, end, **kw) -> np.ndarray:
    runner = CommandRunner(tmp_path / "o.log", tmp_path / "e.log")
    kw = {"snap_horizontal_m": 0.5, "snap_vertical_m": 0.5, **kw}
    path = np.array(find_path(navmesh_tool, navmesh, np.array(start), np.array(end), tmp_path, runner, **kw)["straight_path"])
    return np.vstack([a + (b - a) * t for a, b in zip(path[:-1], path[1:], strict=True) for t in np.linspace(0, 1, 60)])


def test_a_rescan_rebake_keeps_furniture_and_walls_outside_the_region(harness, navmesh_tool, tmp_path):
    """The re-captured lane gained a crate. The full-floor re-bake avoids the crate (changed geometry replaced) and still
    has the sofa, the wall, the column and the railing outside the region (unchanged geometry preserved); the stage measured
    the parent and merged scenes identical outside the region and records that it re-baked the whole floor."""
    splice, parent = _splice(harness, tmp_path, with_venue_labels=True, with_crate=True)
    assert splice["status"] == "SUCCEEDED", splice.get("errorMessage")
    report = _rebake(harness, navmesh_tool, tmp_path, splice, parent)
    assert report["status"] == "SUCCEEDED", report.get("errorMessage")
    graph = json.loads(_download(harness, report, "NAVIGATION_GRAPH", tmp_path).read_text())
    baking = json.loads(_download(harness, report, "NAVIGATION_BAKING_REPORT", tmp_path).read_text())
    manifest = json.loads(_download(harness, report, "NAVMESH_MANIFEST", tmp_path).read_text())
    assert baking["labels"]["artifact_kind"] == "SEMANTIC_LABELS_MERGED", "the merged scene's labels, not the region's"

    rebuild = manifest["rebuild"]
    assert rebuild["scope"] == "FULL_FLOOR" and rebuild["rescan"] and rebuild["incremental"] is False
    outside = rebuild["outside_region"]
    assert outside["consistent"] and outside["cells_differing"] == 0 and outside["cells_compared"] > 3000
    assert outside["obstacle_cells_outside_region"]["merged"] == outside["obstacle_cells_outside_region"]["parent"] > 100
    assert rebuild["obstacle_cells_under_navmesh"]["count"] == 0
    assert baking["rebuild"] == rebuild
    for doc in (graph["source"], manifest["source"], baking["source"]):
        assert doc["scan_version_id"] == SCAN_VERSION and doc["coordinate_frame_id"] == CANONICAL_FRAME["id"]

    navmesh = _download(harness, report, "NAVMESH", tmp_path)
    around_sofa = _path_samples(navmesh_tool, navmesh, tmp_path, [1.5, 3.2, 0.0], [1.5, 5.2, 0.0])
    assert not V.in_box(around_sofa, V.SOFA, margin=0.2).any(), "the sofa, outside the re-captured region, still blocks"
    nodes = np.array([[n["x"], n["y"]] for n in graph["graphs"]["STANDARD"]["nodes"]])
    assert not V.in_box(nodes, (V.WALL[0], V.WALL[1], 0.0, V.D1[0] - 0.4)).any(), "the wall still blocks"
    assert not V.in_box(nodes, V.COLUMN).any(), "the column still blocks"
    assert any(e["level_change"] for e in graph["graphs"]["STANDARD"]["edges"]), "and the re-captured step is still a step"
    # the new crate inside the region blocks: no navmesh under its centre, and a route past it (whose straight line would
    # graze it) goes around
    crate_centre = [(CRATE[0] + CRATE[1]) / 2, (CRATE[2] + CRATE[3]) / 2, V.STEP_RISE_M]
    with pytest.raises(NavmeshError, match="not within the search extents"):
        _path_samples(navmesh_tool, navmesh, tmp_path, crate_centre, [11.0, 2.4, V.STEP_RISE_M], snap_horizontal_m=0.05)
    past_crate = _path_samples(navmesh_tool, navmesh, tmp_path, [9.9, 1.2, V.STEP_RISE_M], [11.0, 2.4, V.STEP_RISE_M])
    assert not V.in_box(past_crate, CRATE, margin=0.2).any(), "the new crate inside the region blocks"


def test_a_rebake_that_would_lose_an_obstacle_outside_the_region_is_refused(harness, navmesh_tool, tmp_path):
    """The merged labels (bound to the right cloud) call the sofa floor: a re-bake would let routes through the sofa,
    which nothing in the re-scan changed. Refused, nothing published, and the lost obstacle cells are counted."""
    splice, parent = _splice(harness, tmp_path, with_venue_labels=True)
    labels_path = _download(harness, splice, "SEMANTIC_LABELS_MERGED", tmp_path)
    doc = json.loads(labels_path.read_text())
    from chaya_worker.ply import read_ply

    positions = read_ply(_download(harness, splice, "SPLAT_MERGED", tmp_path)).positions.astype(np.float64)
    sofa = V.in_box(positions, V.SOFA)
    doc["labels"] = ["floor" if is_sofa else lbl for lbl, is_sofa in zip(doc["labels"], sofa, strict=True)]
    corrupted = _json(tmp_path / "corrupted-labels.json", doc)
    report = _rebake(harness, navmesh_tool, tmp_path, splice, parent, merged_labels=corrupted)
    assert report["status"] == "FAILED" and report["errorCode"] == "NAVMESH_REGION_INCONSISTENT", report.get("errorMessage")
    assert report["artifacts"] == []
    assert report["errorDetails"]["obstacle_cells_lost"] > 0 and not report["errorDetails"]["consistent"]
    assert all(V.in_box(np.array(report["errorDetails"]["differing_cell_centres_xy"]), V.SOFA, margin=0.3))


@pytest.mark.parametrize("case", ["region_only", "labels_of_another_cloud", "planes_of_another_cloud", "global_cloud_not_spliced",
                                  "no_parent_scene"])
def test_a_rescan_rebake_refuses_inputs_that_are_not_the_merged_scene(harness, navmesh_tool, tmp_path, case):
    """A re-scan's navmesh is only ever baked from the merged scene the splice produced, with that scene's own labels and
    planes, checked against the parent scene the splice started from. Anything else is refused before Recast runs."""
    splice, parent = _splice(harness, tmp_path, with_venue_labels=True)
    kw: dict = {}
    if case == "region_only":
        kw["drop"] = ("SPLAT_MERGED", "SEMANTIC_LABELS_MERGED")
    elif case == "labels_of_another_cloud":
        doc = json.loads(_download(harness, splice, "SEMANTIC_LABELS_MERGED", tmp_path).read_text())
        doc["cloud"]["sha256"] = "0" * 64
        kw["merged_labels"] = _json(tmp_path / "other-labels.json", doc)
    elif case == "planes_of_another_cloud":
        merged = _download(harness, splice, "SPLAT_MERGED", tmp_path)
        kw["planes"] = _merged_planes(tmp_path, merged, _download(harness, splice, "SEMANTIC_LABELS_MERGED", tmp_path), fitted_on="1" * 64)
    elif case == "global_cloud_not_spliced":
        venue, _ = V.build()
        kw["global_cloud"] = _derived(harness, "GLOBAL_CLOUD", _cloud(venue + np.array([0.0, 0.0, 0.3]), tmp_path / "other-venue.ply"))
    else:
        kw["drop"] = ("GLOBAL_CLOUD", "GLOBAL_LABELS")
    report = _rebake(harness, navmesh_tool, tmp_path, splice, parent, **kw)
    expected = {"region_only": "INPUT_INVALID", "labels_of_another_cloud": "NAVMESH_LABELS_UNAVAILABLE",
                "planes_of_another_cloud": "INVALID_GEOMETRY", "global_cloud_not_spliced": "NAVMESH_REGION_INCONSISTENT",
                "no_parent_scene": "INPUT_INVALID"}[case]
    assert report["status"] == "FAILED" and report["errorCode"] == expected, report.get("errorMessage")
    assert report["artifacts"] == []


def test_without_the_venues_labels_no_merged_labels_and_no_rebake(harness, navmesh_tool, tmp_path):
    splice, _ = _splice(harness, tmp_path, with_venue_labels=False)
    assert splice["status"] == "SUCCEEDED", splice.get("errorMessage")
    assert "SEMANTIC_LABELS_MERGED" not in {a["kind"] for a in splice["artifacts"]}
    report_doc = json.loads(_download(harness, splice, "SPLICE_REPORT", tmp_path).read_text())
    assert report_doc["labels"] == {"spliced": False, "reason": "missing GLOBAL_LABELS"}
    merged = _download(harness, splice, "SPLAT_MERGED", tmp_path)
    order = harness.order("NAVIGATION_BAKING", [_derived(harness, "SPLAT_MERGED", merged),
                                                _derived(harness, "PLANE_MODEL", _json(tmp_path / "p.json", {"planes": []}), "application/json")])
    order["coordinateFrame"] = CANONICAL_FRAME
    report = harness.run(order, toolchain=Toolchain(env={"CHAYA_NAVMESH_BIN": navmesh_tool}))
    assert report["status"] == "FAILED" and report["errorCode"] == "NAVMESH_LABELS_UNAVAILABLE"


def test_spliced_labels_follow_the_spliced_gaussians():
    from chaya_worker.region_splice import splice_labels, splice_region

    venue, venue_labels = V.build()
    inside = (venue[:, 0] > 6.7) & (venue[:, 1] < 3.3)
    region, region_labels = venue[inside], venue_labels[inside]
    clouds = [GaussianCloud(p.astype(np.float32), np.zeros((len(p), 3), np.float32), np.tile([1, 0, 0, 0], (len(p), 1)).astype(np.float32),
                            np.zeros(len(p), np.float32), np.zeros((len(p), 3), np.float32)) for p in (venue, region)]
    result = splice_region(clouds[0], clouds[1], np.array(REGION), IDENTITY, z_margin_m=0.2, seam_band_m=0.15, max_seam_step_m=0.03)
    merged = splice_labels(venue_labels, region_labels, result)
    assert len(merged) == len(result.merged)
    lookup = {tuple(np.round(p, 4)): lbl for p, lbl in zip(venue, venue_labels, strict=True)}
    for p, lbl in zip(result.merged.positions.astype(np.float64)[::97], merged[::97], strict=True):
        assert lookup[tuple(np.round(p, 4))] == lbl, "every Gaussian keeps its own label through the splice"
