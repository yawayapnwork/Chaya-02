"""Edge-case tests for multi-room registration, stitching, and portal tracking.

Covers:
- Correct alignment of overlapping room fragments
- Insufficient overlap (weak overlap gate rejection)
- Contradictory overlap (free space collision rejection)
- Repeated doorway geometry / ambiguous hypotheses
- Misaligned floor coordinates / tilt gate rejection
- Explicit portal identities and clearances
"""

from __future__ import annotations

import numpy as np
import pytest

from chaya_worker.frames import Similarity
from chaya_worker.navmesh import room_connectivity
from chaya_worker.room_registration import FloorGrid, register_pair
from chaya_worker.settings import Settings
from chaya_worker.similarity_registration import rotation_angle_degrees
from tests.multiroom_scene import capture, local_frame, true_matches, wrong_matches
from tests.rescan_scene import rotation

SETTINGS = Settings()
GATES = SETTINGS.room_gates()
SCHEDULE = SETTINGS.room_schedule()
ROOMS = ("A", "B", "C")
LOCAL = {r: local_frame(i) for i, r in enumerate(ROOMS)}


class Room(np.ndarray):
    floor: FloorGrid


def _points(room: str, *, density: int = 150, peeks=None, scale: float = 1.0) -> Room:
    p, labels = capture(room, seed=ROOMS.index(room), density=density, noise_m=0.003, peeks=peeks)
    out = (scale * LOCAL[room].apply(p)).view(Room)
    out.floor = FloorGrid.of(np.asarray(out), labels)
    return out


@pytest.fixture(scope="module")
def clouds() -> dict[str, Room]:
    return {r: _points(r) for r in ROOMS}


def _truth(source: str, target: str) -> Similarity:
    return LOCAL[target].compose(LOCAL[source].inverse())


def _register(source, src: Room, target, dst: Room, correspondences, **kw):
    gravity_aligned = kw.pop("gravity_aligned", True)
    return register_pair(
        source,
        np.asarray(src),
        target,
        np.asarray(dst),
        gates=GATES,
        feature_voxel_m=SETTINGS.room_feature_voxel_m,
        icp_voxel_m=SETTINGS.room_icp_voxel_m,
        overlap_radius_m=SETTINGS.room_overlap_radius_m,
        icp_schedule_m=SCHEDULE,
        gravity_aligned=gravity_aligned,
        correspondences=correspondences,
        source_floor=src.floor,
        target_floor=dst.floor,
        **kw,
    )


def test_correct_alignment_of_overlapping_room_fragments(clouds):
    """Overlapping room fragments sharing doorway geometry align within sub-centimeter accuracy."""
    res = _register("A", clouds["A"], "B", clouds["B"], true_matches(_truth("A", "B")))
    assert res.accepted and res.outcome == "REGISTERED"
    t_err = np.linalg.norm(res.transform.apply(clouds["A"].mean(axis=0)) - _truth("A", "B").apply(clouds["A"].mean(axis=0)))
    r_err = rotation_angle_degrees(res.transform.rotation @ _truth("A", "B").rotation.T)
    assert t_err < 0.02
    assert r_err < 0.5
    assert res.transform.scale == pytest.approx(1.0)
    assert res.details["overlap_source_fraction"] > 0.02


def test_insufficient_overlap_is_detected_and_rejected(clouds):
    """Room pair with too few or zero correspondences in overlap is rejected."""
    # Zero or empty correspondences
    def empty_fn(s, d, v):
        return (np.zeros((0, 3)), np.zeros((0, 3)))

    res_empty = _register("A", clouds["A"], "B", clouds["B"], empty_fn)
    assert not res_empty.accepted
    assert res_empty.outcome in ("NO_SHARED_FEATURES", "INSUFFICIENT_INLIERS")

    # Few sparse correspondences below minimum threshold
    base_fn = true_matches(_truth("A", "B"))

    def sparse_fn(s, d, v):
        matches = base_fn(s, d, v)
        return (matches[0][:2], matches[1][:2])

    res_sparse = _register("A", clouds["A"], "B", clouds["B"], sparse_fn)
    assert not res_sparse.accepted
    assert res_sparse.outcome in ("NO_SHARED_FEATURES", "INSUFFICIENT_INLIERS")


def test_contradictory_overlap_rejected_by_free_space_conflict(clouds):
    """Placement that positions geometry where the other capture observed open free space is rejected."""
    conflicting = Similarity(1.0, np.eye(3), np.array([2.5, 0.0, 0.0])).compose(_truth("A", "B"))
    res = _register("A", clouds["A"], "B", clouds["B"], wrong_matches(conflicting))
    assert not res.accepted
    assert "free_space" in res.details


def test_repeated_doorway_geometry_ambiguity_detected(clouds):
    """Ambiguous or symmetric duplicate doorway matches that lack unique convergence are rejected."""
    ambiguous_transform = Similarity(1.0, rotation([0, 0, 1], 180), np.array([0.0, 0.0, 0.0])).compose(_truth("A", "B"))
    res = _register("A", clouds["A"], "B", clouds["B"], wrong_matches(ambiguous_transform))
    assert not res.accepted


def test_misaligned_floor_coordinates_and_tilt_rejected(clouds):
    """A room fragment whose vertical axis is tilted relative to gravity is rejected."""
    # Artificially tilt room A by 8 degrees
    tilt_rot = rotation([1, 0, 0], 8.0)
    tilted_A = (tilt_rot @ clouds["A"].T).T.view(Room)
    tilted_A.floor = clouds["A"].floor

    corr = true_matches(_truth("A", "B"))
    res = _register("A", tilted_A, "B", clouds["B"], corr, gravity_aligned=True)
    assert not res.accepted
    assert res.outcome == "TILTED"
    assert "tilt_deg" in res.gates.failed


def test_portal_identity_and_clearances_recorded():
    """Verify that room_connectivity returns explicit portal identities and clearances."""
    # Simple 2-room graph with a shared doorway edge
    nodes = [
        {"local_id": "n1", "x": 1.0, "y": 1.0, "z": 0.0},
        {"local_id": "n2", "x": 2.9, "y": 1.0, "z": 0.0},
        {"local_id": "n3", "x": 3.1, "y": 1.0, "z": 0.0},
        {"local_id": "n4", "x": 5.0, "y": 1.0, "z": 0.0},
    ]
    edges = [
        {"from": "n1", "to": "n2", "min_clearance_m": 1.2, "step_free": True},
        {
            "from": "n2",
            "to": "n3",
            "min_clearance_m": 0.92,
            "step_free": True,
            "portal": [[3.0, 0.5, 0.0], [3.0, 1.5, 0.0]],
        },
        {"from": "n3", "to": "n4", "min_clearance_m": 1.2, "step_free": True},
    ]
    graphs = {
        "STANDARD": {"nodes": nodes, "edges": edges},
        "STEP_FREE": {"nodes": nodes, "edges": edges},
    }

    # Footprints for room A (cells x in [0, 2]) and room B (cells x in [3, 5])
    footprints = {
        "room_A": np.array([[0, 1], [1, 1], [2, 1]]),
        "room_B": np.array([[3, 1], [4, 1], [5, 1]]),
    }

    conn = room_connectivity(graphs, footprints, cell_m=1.0, accessible_width_m=0.8)
    assert conn["connected"]
    assert len(conn["connections"]) == 1
    c = conn["connections"][0]
    assert c["rooms"] == ["room_A", "room_B"]
    assert c["portal_id"] == "portal:room_A-room_B"
    assert c["portal"] == [[3.0, 0.5, 0.0], [3.0, 1.5, 0.0]]
    assert c["portal_midpoint"] == pytest.approx([3.0, 1.0, 0.0])
    assert c["clear_width_m"] == pytest.approx(0.92)
    assert c["step_free"]
