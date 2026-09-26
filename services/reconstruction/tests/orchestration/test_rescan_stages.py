"""REGION_ALIGNMENT and REGION_SPLICE through the orchestrator: work orders, frames, artifacts and reports, on SYNTHETIC
clouds (tests/rescan_scene.py). DIRECT_CANONICAL mode needs no Open3D. FEATURE_SIMILARITY is covered by
tests/unit/test_region_alignment.py and, with real FPFH, tests/gpu/test_region_alignment.py.

The frames are deliberately not identities. The parent reconstruction is at 0.5 units per metre, rotated and shifted.
The re-scan's reconstruction is at 2 units per metre, in its own orientation. Only the calibrations relate them."""

from __future__ import annotations

import io
import json

import numpy as np
import pytest

from chaya_worker.frames import Similarity
from chaya_worker.ply import read_ply, write_ply
from chaya_worker.region_splice import point_in_polygon
from chaya_worker.toolchain import Toolchain
from tests.conftest import DERIVED_BUCKET
from tests.rescan_scene import cloud, room, rotation

pytestmark = pytest.mark.orchestration

PARENT_TO_CANONICAL = Similarity(2.0, rotation([0, 0, 1], 30), np.array([5.0, -3.0, 0.0]))  # parent recon: 0.5 units / m
RESCAN_TO_CANONICAL = Similarity(0.5, rotation([1, 1, 0], 70), np.array([-2.0, 4.0, 1.0]))  # re-scan recon: 2 units / m
POLYGON = [[1.0, 0.5], [3.0, 0.5], [3.0, 3.5], [1.0, 3.5]]


def frame(fid: str, t: Similarity, datum: str = "VENUE_CONTROL_POINTS") -> dict:
    return {"id": fid, "sourceRunId": f"run-{fid}", "version": 1, "metricStatus": "METRIC", "gravityStatus": "ALIGNED",
            "horizontalDatum": datum, **t.to_dict()}


def _input(harness, tmp_path, kind: str, gaussian_cloud) -> dict:
    path = write_ply(gaussian_cloud, tmp_path / f"{kind.lower()}.ply")
    ref = harness.raw_input(kind, path)
    ref["containsPii"] = False
    return ref


def _order(harness, stage: str, inputs: list[dict], *, region_frame: dict | None, parent_frame: dict) -> dict:
    order = harness.order(stage, inputs)
    order["regionGeometry"] = {"points": POLYGON}
    order["coordinateFrame"] = region_frame if stage == "REGION_ALIGNMENT" else parent_frame
    order["parentCoordinateFrame"] = parent_frame
    return order


def _download(harness, report, kind, tmp_path):
    art = next(a for a in report["artifacts"] if a["kind"] == kind)
    dest = tmp_path / "dl" / art["key"].split("/")[-1]
    dest.parent.mkdir(exist_ok=True)
    harness.storage.download(DERIVED_BUCKET, art["key"], dest)
    return dest


def _venue(tmp_path, harness):
    """The parent version's cloud, stored in the parent reconstruction's own frame."""
    venue = cloud(PARENT_TO_CANONICAL.inverse().apply(room(1, density=300)), seed=1)
    return venue, _input(harness, tmp_path, "GLOBAL_CLOUD", venue)


def _rescan(points_canonical, calibration_error: Similarity, seed=2):
    """A re-scan in its own reconstruction frame, whose calibration is off from the truth by `calibration_error`."""
    true_to_recon = RESCAN_TO_CANONICAL.inverse()
    recon = true_to_recon.apply(calibration_error.inverse().apply(points_canonical))
    return cloud(recon, seed=seed)


def test_a_rescan_aligns_and_splices_through_both_stages(harness, tmp_path):
    venue, venue_ref = _venue(tmp_path, harness)
    error = Similarity(1.02, rotation([0, 0, 1], 1.0), np.array([0.1, -0.06, 0.02]))  # the two surveys disagree a little
    rescan = _rescan(room(2, density=300), error)
    parent = frame("parent", PARENT_TO_CANONICAL)
    align = harness.run(_order(harness, "REGION_ALIGNMENT", [_input(harness, tmp_path, "SPLAT_CLEAN", rescan), venue_ref],
                               region_frame=frame("rescan", RESCAN_TO_CANONICAL), parent_frame=parent))
    assert align["status"] == "SUCCEEDED", align.get("errorMessage")
    report = json.loads(_download(harness, align, "ALIGNMENT_REPORT", tmp_path).read_text())
    assert report["status"] == "ACCEPTED" and report["mode"] == "DIRECT_CANONICAL" and report["gates_passed"]
    assert report["correction"]["scale"] == pytest.approx(1.02, abs=0.005)
    assert report["inputs"]["global_cloud"]["artifact_id"] == venue_ref["artifactId"]
    assert align["command"]["config"]["alignment"]["confidence"] == report["confidence"] > 0.6

    aligned_ref = harness.raw_input("SPLAT_ALIGNED", _download(harness, align, "SPLAT_ALIGNED", tmp_path))
    aligned_ref["containsPii"] = False
    splice = harness.run(_order(harness, "REGION_SPLICE", [aligned_ref, venue_ref], region_frame=None, parent_frame=parent))
    assert splice["status"] == "SUCCEEDED", splice.get("errorMessage")
    assert {a["kind"] for a in splice["artifacts"]} == {"SPLAT_MERGED", "SPLICE_REPORT", "SPLICE_INDEX"}
    merged = read_ply(_download(harness, splice, "SPLAT_MERGED", tmp_path))
    splice_report = json.loads(_download(harness, splice, "SPLICE_REPORT", tmp_path).read_text())
    index = np.load(io.BytesIO(_download(harness, splice, "SPLICE_INDEX", tmp_path).read_bytes()))

    # the merged cloud is in the parent's frame: in canonical metres, the re-scan's geometry lands where the room is
    canonical = PARENT_TO_CANONICAL.apply(merged.positions)
    added = canonical[-splice_report["added_from_region"]:]
    near_box = (added[:, 0] > 1.45) & (added[:, 0] < 2.55) & (added[:, 1] > 0.95) & (added[:, 1] < 1.65)
    floor = added[(np.abs(added[:, 2]) < 0.1) & ~near_box]  # the floor, away from the box's vertical faces
    assert len(floor) > 100 and np.abs(floor[:, 2]).max() < 0.02, "the re-scanned floor sits on the venue floor"
    assert point_in_polygon(added[:, :2], np.array(POLYGON)).all()
    # exactly what was replaced is recorded, and it adds up
    assert len(index["removed_global_indices"]) == splice_report["removed_from_global"] > 0
    assert len(index["added_region_indices"]) == splice_report["added_from_region"] > 0
    assert len(merged) == len(venue) - splice_report["removed_from_global"] + splice_report["added_from_region"]
    assert splice_report["inputs"]["global_cloud"]["artifact_id"] == venue_ref["artifactId"]
    assert splice_report["output"]["sha256"] == next(a["sha256"] for a in splice["artifacts"] if a["kind"] == "SPLAT_MERGED")


def test_a_rejected_alignment_publishes_nothing_and_says_why(harness, tmp_path):
    _, venue_ref = _venue(tmp_path, harness)
    error = Similarity(1.0, np.eye(3), np.array([1.2, 0.0, 0.0]))  # the re-scan's survey is 1.2 m off
    rescan = _rescan(room(2, density=300), error)
    report = harness.run(_order(harness, "REGION_ALIGNMENT", [_input(harness, tmp_path, "SPLAT_CLEAN", rescan), venue_ref],
                                region_frame=frame("rescan", RESCAN_TO_CANONICAL), parent_frame=frame("parent", PARENT_TO_CANONICAL)))
    assert report["status"] == "FAILED" and report["errorCode"] == "ALIGNMENT_REJECTED"
    assert report["artifacts"] == [], "nothing that could be spliced is published"
    details = report["errorDetails"]
    assert details["status"] == "REJECTED" and not details["gates_passed"]
    assert "direct_translation_correction_m" in details["failed_gates"]
    assert all({"name", "value", "threshold", "passed"} <= set(g) for g in details["gates"])
    json.dumps(details, allow_nan=False)  # valid JSON for the control plane: no NaN/Infinity


def test_a_noop_rescan_gives_back_the_venue(harness, tmp_path):
    """Re-scanning with the venue's own Gaussians, perfectly calibrated: the merge is the venue itself."""
    venue, venue_ref = _venue(tmp_path, harness)
    canonical = PARENT_TO_CANONICAL.apply(venue.positions)
    inside = point_in_polygon(canonical[:, :2], np.array(POLYGON)) & (canonical[:, 2] < 1.0)
    same = venue.subset(inside)  # already in the parent frame, so its "calibration" is the parent's
    parent = frame("parent", PARENT_TO_CANONICAL)
    align = harness.run(_order(harness, "REGION_ALIGNMENT", [_input(harness, tmp_path, "SPLAT_CLEAN", same), venue_ref],
                               region_frame=frame("rescan", PARENT_TO_CANONICAL), parent_frame=parent))
    assert align["status"] == "SUCCEEDED", align.get("errorMessage")
    report = json.loads(_download(harness, align, "ALIGNMENT_REPORT", tmp_path).read_text())
    assert report["correction"]["scale"] == pytest.approx(1.0, abs=1e-4)
    aligned_ref = harness.raw_input("SPLAT_ALIGNED", _download(harness, align, "SPLAT_ALIGNED", tmp_path))
    aligned_ref["containsPii"] = False
    splice = harness.run(_order(harness, "REGION_SPLICE", [aligned_ref, venue_ref], region_frame=None, parent_frame=parent))
    assert splice["status"] == "SUCCEEDED", splice.get("errorMessage")
    merged = read_ply(_download(harness, splice, "SPLAT_MERGED", tmp_path))
    assert len(merged) == len(venue)
    np.testing.assert_allclose(np.sort(merged.positions, axis=0), np.sort(venue.positions, axis=0), atol=2e-4)


def test_feature_mode_needs_open3d_and_an_uncalibrated_rescan_is_never_merged(harness, tmp_path):
    _, venue_ref = _venue(tmp_path, harness)
    rescan_ref = _input(harness, tmp_path, "SPLAT_CLEAN", _rescan(room(2, density=100), Similarity.identity()))
    parent = frame("parent", PARENT_TO_CANONICAL)
    no_tools = Toolchain(env={}, which=lambda _n: None)
    floor_local = frame("rescan", RESCAN_TO_CANONICAL, datum="FLOOR_LOCAL")  # its own origin/heading: must be registered
    report = harness.run(_order(harness, "REGION_ALIGNMENT", [rescan_ref, venue_ref], region_frame=floor_local, parent_frame=parent),
                         toolchain=no_tools)
    assert report["errorCode"] == "DEPENDENCY_UNAVAILABLE" and report["artifacts"] == []
    uncalibrated = {**floor_local, "metricStatus": "NOT_CALIBRATED", "gravityStatus": "NOT_ALIGNED", "scale": None,
                    "rotation": None, "translation": None}
    report = harness.run(_order(harness, "REGION_ALIGNMENT", [rescan_ref, venue_ref], region_frame=uncalibrated, parent_frame=parent))
    assert report["errorCode"] == "NOT_CALIBRATED" and report["artifacts"] == []
