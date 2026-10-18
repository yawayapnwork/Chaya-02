"""ROOM_REGISTRATION and ROOM_STITCH through the orchestrator: work orders, frames, artifacts and reports, on the SYNTHETIC
multi-room floor (tests/multiroom_scene.py). Feature matching is a stand-in (multiroom_scene.oracle_matches); the real FPFH
path is tests/gpu/test_room_registration.py.

The frames are deliberately not identities. Each room's reconstruction has its own scale (0.5 to 2 units per metre),
rotation and origin, and its calibration is FLOOR_LOCAL: metric and gravity-aligned, with its own origin and heading. Only
the registration relates the rooms."""

from __future__ import annotations

import json

import numpy as np
import pytest

from chaya_worker.frames import Similarity
from chaya_worker.ply import read_ply
from chaya_worker.stages import room_registration as stage_module
from chaya_worker.toolchain import Toolchain
from tests.conftest import DERIVED_BUCKET
from tests.multiroom_scene import CALIBRATION, LOCAL, Floor, oracle_matches

pytestmark = pytest.mark.orchestration

@pytest.fixture()
def matcher(monkeypatch):
    def install(floor: Floor):
        monkeypatch.setattr(stage_module, "match_features", oracle_matches(floor.canonical, LOCAL))
        # FPFH is replaced, so Open3D is not needed; the stage still requires it for the real matcher (test below)
    return install


def _download(harness, report, kind, tmp_path):
    art = next(a for a in report["artifacts"] if a["kind"] == kind)
    dest = tmp_path / "dl" / art["key"].split("/")[-1]
    dest.parent.mkdir(exist_ok=True)
    harness.storage.download(DERIVED_BUCKET, art["key"], dest)
    return dest


def test_three_rooms_are_registered_and_stitched_in_the_anchor_s_reconstruction_frame(harness, tmp_path, matcher):
    floor = Floor(harness, tmp_path)
    matcher(floor)
    reg = harness.run(floor.order("ROOM_REGISTRATION"))
    assert reg["status"] == "SUCCEEDED", (reg.get("errorCode"), reg.get("errorMessage"))
    report = json.loads(_download(harness, reg, "ROOM_REGISTRATION_REPORT", tmp_path).read_text())
    assert report["registered"] == ["space-A", "space-B", "space-C"] and report["unregistered"] == {}
    assert report["anchor"] == "space-A" and report["output_frame"]["id"] == "frame-A"
    for entry in report["rooms"]:  # to_output: room recon -> anchor recon; check it against the truth at the room's points
        r = entry["space_id"][-1]
        truth = CALIBRATION["A"].inverse().compose(LOCAL["A"]).compose(LOCAL[r].inverse()).compose(CALIBRATION[r])
        recon = CALIBRATION[r].inverse().apply(floor.canonical[r])
        err = np.linalg.norm(Similarity.from_dict(entry["to_output"]).apply(recon) - truth.apply(recon), axis=1)
        assert np.median(err) * CALIBRATION["A"].scale < 0.01, (r, np.median(err))  # in metres
    accepted = {(p["source"], p["target"]) for p in report["pairs"] if p["status"] == "ACCEPTED"}
    assert len(accepted) == 3 and len(report["graph"]["loop_closures"]) == 1
    assert reg["command"]["config"]["registration"]["status"] == "ACCEPTED"

    stitch = harness.run(floor.order("ROOM_STITCH", extra_inputs=harness.outputs_as_inputs(reg)))
    assert stitch["status"] == "SUCCEEDED", (stitch.get("errorCode"), stitch.get("errorMessage"))
    kinds = {a["kind"] for a in stitch["artifacts"]}
    assert {"SPLAT_MERGED", "SEMANTIC_LABELS_MERGED", "STITCH_REPORT", "STITCH_INDEX"} <= kinds
    merged = read_ply(_download(harness, stitch, "SPLAT_MERGED", tmp_path))
    sreport = json.loads(_download(harness, stitch, "STITCH_REPORT", tmp_path).read_text())
    labels = json.loads(_download(harness, stitch, "SEMANTIC_LABELS_MERGED", tmp_path).read_text())
    assert len(labels["labels"]) == len(merged) and labels["cloud"]["sha256"] == sreport["output"]["sha256"]
    assert sreport["duplicate_fraction_after"] < 0.01 and sreport["coordinate_frame_id"] == "frame-A"
    # Every room's block lies where that room really is, in A's canonical frame (A's calibration applied to the output).
    venue = LOCAL["A"].inverse().compose(CALIBRATION["A"])  # output (A recon) -> venue canonical
    for block in sreport["blocks"]:
        part = venue.apply(merged.positions[block["start"]:block["start"] + block["count"]])
        r = block["piece"][-1]
        lo, hi = {"A": ((0, 0), (5, 4)), "B": ((5, 0), (10, 4)), "C": ((0, 4), (10, 6))}[r]
        inside = np.all((part[:, :2] > np.array(lo) - 2.1) & (part[:, :2] < np.array(hi) + 2.1), axis=1)
        assert inside.mean() > 0.999, r
    index = np.load(_download(harness, stitch, "STITCH_INDEX", tmp_path))
    assert [len(index[f"kept_{i}"]) for i in range(3)] == [b["count"] for b in sreport["blocks"]]


def test_a_room_that_cannot_be_registered_fails_the_run_with_its_reason_and_nothing_is_published(harness, tmp_path, matcher):
    """Room B saw only the frames of its doors: its doorway overlaps cannot fix its position."""
    floor = Floor(harness, tmp_path, peeks={"B": [], "A": [((1.0, 4.0), (4.0, 6.0))], "C": [((1.5, 2.5), (3.5, 4.0))]})
    matcher(floor)
    reg = harness.run(floor.order("ROOM_REGISTRATION", allow_unconfirmed=True))  # the corridor is placed by A alone
    assert reg["status"] == "FAILED" and reg["errorCode"] == "REGISTRATION_REJECTED"
    assert [a for a in reg["artifacts"] if not a["kind"].startswith("LOG_")] == []
    details = reg["errorDetails"]
    assert set(details["unregistered"]) == {"space-B"} and "Room B" in reg["errorMessage"]
    outcomes = {(p["source"], p["target"]): p["outcome"] for p in details["pairs"]}
    assert all(v != "REGISTERED" for k, v in outcomes.items() if "space-B" in k), outcomes


def test_a_room_no_loop_confirms_is_refused_unless_the_operator_accepts_it(harness, tmp_path, matcher):
    """Two rooms: one registration places B, and nothing could catch it if it were wrong (repeated structure passes every
    pairwise gate). Refused by default, with that reason; placed, and listed as unconfirmed, when the stitch accepts it."""
    floor = Floor(harness, tmp_path)
    matcher(floor)
    refused = harness.run(floor.order("ROOM_REGISTRATION", only=("A", "B")))
    assert refused["errorCode"] == "REGISTRATION_REJECTED"
    assert "no loop confirms it" in refused["errorDetails"]["unregistered"]["space-B"]
    assert refused["errorDetails"]["registered"] == ["space-A"]
    accepted = harness.run(floor.order("ROOM_REGISTRATION", only=("A", "B"), allow_unconfirmed=True))
    assert accepted["status"] == "SUCCEEDED", accepted.get("errorMessage")
    summary = accepted["command"]["config"]["registration"]
    assert summary["registered"] == ["space-A", "space-B"] and summary["unconfirmed"] == ["space-B"]
    assert summary["allow_unconfirmed_rooms"] is True
    three = harness.run(floor.order("ROOM_REGISTRATION"))  # the loop A-B-C confirms every room
    assert three["status"] == "SUCCEEDED" and three["command"]["config"]["registration"]["unconfirmed"] == []


def test_every_room_must_be_calibrated_and_the_run_must_be_in_the_anchor_s_frame(harness, tmp_path, matcher):
    floor = Floor(harness, tmp_path)
    matcher(floor)
    report = harness.run(floor.order("ROOM_REGISTRATION", uncalibrated=("C",)))
    assert report["errorCode"] == "NOT_CALIBRATED" and "Room C" in report["errorMessage"]
    report = harness.run(floor.order("ROOM_REGISTRATION", frame_of="B"))
    assert report["errorCode"] == "INPUT_INVALID" and "anchor" in report["errorMessage"]


def test_the_real_matcher_needs_open3d(harness, tmp_path):
    floor = Floor(harness, tmp_path)
    report = harness.run(floor.order("ROOM_REGISTRATION"), toolchain=Toolchain(env={}, which=lambda _n: None))
    assert report["errorCode"] == "DEPENDENCY_UNAVAILABLE"


def test_the_stitch_refuses_a_registration_computed_on_other_clouds(harness, tmp_path, matcher):
    floor = Floor(harness, tmp_path)
    matcher(floor)
    reg = harness.run(floor.order("ROOM_REGISTRATION"))
    assert reg["status"] == "SUCCEEDED"
    floor.recapture("A", seed=9)  # another capture of room A, which the registration never saw
    report = harness.run(floor.order("ROOM_STITCH", extra_inputs=harness.outputs_as_inputs(reg)))
    assert report["errorCode"] == "INPUT_INVALID" and "another cloud" in report["errorMessage"], report.get("errorMessage")
    assert [a for a in report["artifacts"] if not a["kind"].startswith("LOG_")] == []
