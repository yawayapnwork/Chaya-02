"""Room registration with real FPFH feature matching (Open3D) on the SYNTHETIC multi-room floor of tests/multiroom_scene.py,
whose truth is known exactly. Marked `open3d` (CPU; required in the python workflow's open3d job); SKIPPED, with the reason
printed by `pytest -rs`, when Open3D is not installed. Nothing here can pass without running the registration.

Everything after feature matching (robust estimation, the uniqueness, scale, free-space and overlap gates, the pose graph)
is covered without Open3D in tests/unit/test_room_registration.py. This file checks that real FPFH matches on doorway
overlaps are good enough to feed it, and runs the ROOM_REGISTRATION stage with them. It says nothing about real captures:
synthetic surfaces are exact planes sampled uniformly; real splats are not.
"""

from __future__ import annotations

import json

import numpy as np
import pytest

from chaya_worker.frames import Similarity
from chaya_worker.region_alignment import fpfh_correspondences
from chaya_worker.room_registration import Edge, FloorGrid, register_pair, room_node, solve_room_graph
from chaya_worker.settings import Settings
from chaya_worker.similarity_registration import rotation_angle_degrees
from chaya_worker.toolchain import Toolchain
from tests.conftest import DERIVED_BUCKET
from tests.multiroom_scene import CALIBRATION, LOCAL, ROOMS, Floor, capture

_tc = Toolchain()
needs_open3d = pytest.mark.skipif(not _tc.module("open3d").available, reason="Open3D is not installed")
pytestmark = [pytest.mark.open3d, needs_open3d]

S = Settings()
GATES = S.room_gates()


@pytest.fixture(scope="module")
def rooms():
    out = {}
    for i, r in enumerate(ROOMS):
        p, labels = capture(r, seed=i, density=400, noise_m=0.003)
        local = LOCAL[r].apply(p)
        out[r] = (local, FloorGrid.of(local, labels))
    return out


def _register(rooms, source, target, seed=0):
    (src, src_floor), (dst, dst_floor) = rooms[source], rooms[target]
    return register_pair(source, src, target, dst, gates=GATES, feature_voxel_m=S.room_feature_voxel_m, icp_voxel_m=S.room_icp_voxel_m,
                         overlap_radius_m=S.room_overlap_radius_m, icp_schedule_m=S.room_schedule(), gravity_aligned=True,
                         correspondences=fpfh_correspondences, seed=seed, source_floor=src_floor, target_floor=dst_floor)


def _error(T: Similarity, source: str, target: str, at: np.ndarray) -> tuple[float, float]:
    truth = LOCAL[target].compose(LOCAL[source].inverse())
    return float(np.linalg.norm(T.apply(at) - truth.apply(at))), rotation_angle_degrees(T.rotation @ truth.rotation.T)


def test_real_fpfh_matches_register_two_rooms_through_their_doorway(rooms):
    result = _register(rooms, "B", "A")
    assert result.accepted, (result.outcome, result.gates.failed, result.details)
    t_err, r_err = _error(result.transform, "B", "A", rooms["B"][0].mean(axis=0))
    assert t_err < 0.02 and r_err < 0.3, (t_err, r_err)
    assert result.details["putative_correspondences"] > 50


def test_with_real_fpfh_the_corridor_is_refused_rather_than_misplaced(rooms):
    """What real FPFH matches give on the corridor (measured; synthetic floor): its two doors are 5 m apart and what it saw
    through one looks like what lies behind the other, so its registration onto room B verified at 5 m off along itself,
    passing every pairwise gate -- repeated structure no pairwise test can tell from the truth. Its registration onto room
    A verified at the true placement, so the loop A-B-C is inconsistent by 5 m. With one loop nothing shows which of the
    three registrations is wrong, and loop closure places neither B nor C rather than guess. The property checked is the
    safety one: every room that IS placed is placed right."""
    pairs = [("B", "A"), ("C", "A"), ("C", "B")]
    results = {(s, t): _register(rooms, s, t, seed=i) for i, (s, t) in enumerate(pairs)}
    accepted = [r for r in results.values() if r.accepted]
    assert results[("B", "A")].accepted, results[("B", "A")].gates.failed
    nodes = {r: room_node(r, rooms[r][0], fixed=r == "A", pose=Similarity.identity() if r == "A" else None) for r in ROOMS}
    graph = solve_room_graph(nodes, [Edge(r.target, r.source, r.transform, r.metrics.confidence) for r in accepted], gates=GATES)
    for room in graph.registered:
        if room != "A":
            t_err, r_err = _error(graph.poses[room], room, "A", rooms[room][0].mean(axis=0))
            assert t_err < 0.02 and r_err < 0.3, (room, t_err, r_err, {k: (r.outcome, r.accepted) for k, r in results.items()})
    for (s_, t_), r in results.items():  # a wrong placement may pass a pair's gates; loop closure must not keep it
        t_err, _ = _error(r.transform, s_, t_, rooms[s_][0].mean(axis=0))
        if r.accepted and t_err > 0.05:
            assert all(e["status"] != "ACCEPTED" for e in graph.edges if e["edge"] == f"{s_}->{t_}"), (s_, t_, t_err)


def test_rooms_that_see_only_each_other_s_door_frames_are_not_registered():
    a, la = capture("A", seed=0, density=400, noise_m=0.003, peeks=[])
    b, lb = capture("B", seed=1, density=400, noise_m=0.003, peeks=[])
    rooms = {"A": (LOCAL["A"].apply(a), FloorGrid.of(LOCAL["A"].apply(a), la)), "B": (LOCAL["B"].apply(b), FloorGrid.of(LOCAL["B"].apply(b), lb))}
    result = _register(rooms, "B", "A")
    t_err, _ = _error(result.transform, "B", "A", rooms["B"][0].mean(axis=0))
    assert not result.accepted or t_err < 0.02, "never a wrong placement accepted"


def _download(harness, report, kind, tmp_path):
    art = next(a for a in report["artifacts"] if a["kind"] == kind)
    dest = tmp_path / f"{kind.lower()}.json"
    harness.storage.download(DERIVED_BUCKET, art["key"], dest)
    return json.loads(dest.read_text())


def test_the_room_registration_stage_registers_two_rooms_with_real_fpfh(harness, tmp_path):
    floor = Floor(harness, tmp_path, density=400)
    report = harness.run(floor.order("ROOM_REGISTRATION", only=("A", "B"), allow_unconfirmed=True))
    assert report["status"] == "SUCCEEDED", (report.get("errorCode"), report.get("errorMessage"))
    doc = _download(harness, report, "ROOM_REGISTRATION_REPORT", tmp_path)
    assert doc["registered"] == ["space-A", "space-B"]
    assert doc["unconfirmed"] == ["space-B"], "two rooms: one registration, no loop to confirm it"
    entry = next(e for e in doc["rooms"] if e["space_id"] == "space-B")
    truth = CALIBRATION["A"].inverse().compose(LOCAL["A"]).compose(LOCAL["B"].inverse()).compose(CALIBRATION["B"])
    recon = CALIBRATION["B"].inverse().apply(floor.canonical["B"])
    err = np.median(np.linalg.norm(Similarity.from_dict(entry["to_output"]).apply(recon) - truth.apply(recon), axis=1))
    assert err * CALIBRATION["A"].scale < 0.02, err


def test_the_room_registration_stage_never_places_a_room_wrong_with_real_fpfh(harness, tmp_path):
    """The three rooms: the corridor is aliased (above), so the stage refuses; if it succeeds, every room must be right."""
    floor = Floor(harness, tmp_path, density=400)
    report = harness.run(floor.order("ROOM_REGISTRATION"))
    if report["status"] == "FAILED":
        assert report["errorCode"] == "REGISTRATION_REJECTED"
        assert [a for a in report["artifacts"] if not a["kind"].startswith("LOG_")] == []
        return
    doc = _download(harness, report, "ROOM_REGISTRATION_REPORT", tmp_path)
    for entry in doc["rooms"]:
        r = entry["space_id"][-1]
        truth = CALIBRATION["A"].inverse().compose(LOCAL["A"]).compose(LOCAL[r].inverse()).compose(CALIBRATION[r])
        recon = CALIBRATION[r].inverse().apply(floor.canonical[r])
        err = np.median(np.linalg.norm(Similarity.from_dict(entry["to_output"]).apply(recon) - truth.apply(recon), axis=1))
        assert err * CALIBRATION["A"].scale < 0.02, (r, err)
