"""Room registration (chaya_worker.room_registration) on the SYNTHETIC multi-room floor (tests/multiroom_scene.py), whose
truth is known exactly. Mathematical tests only: they say nothing about real captures.

Pairwise registration normally gets its putative correspondences from Open3D FPFH (tests/gpu/test_room_registration.py
runs that). Here a stand-in supplies them (multiroom_scene.true_matches: true pairs inside the real overlap, 60 % replaced
by random wrong matches), so everything after feature matching is tested: robust estimation, the uniqueness test, the
scale policy, ICP on the overlap, the gates. The pose-graph tests use poses alone."""

from __future__ import annotations

import numpy as np
import pytest

from chaya_worker.frames import Similarity
from chaya_worker.room_registration import Edge, FloorGrid, register_pair, room_node, solve_room_graph
from chaya_worker.settings import Settings
from chaya_worker.similarity_registration import rotation_angle_degrees
from tests.multiroom_scene import capture, local_frame, true_matches, wrong_matches
from tests.rescan_scene import rotation

SETTINGS = Settings()
GATES = SETTINGS.room_gates()
SCHEDULE = SETTINGS.room_schedule()
ROOMS = ("A", "B", "C")  # C is the corridor both rooms open onto
LOCAL = {r: local_frame(i) for i, r in enumerate(ROOMS)}  # canonical -> each capture's own gravity-aligned frame


class Room(np.ndarray):
    """A capture's points in its own frame, carrying the floor it observed (FloorGrid, from its labels)."""

    floor: FloorGrid


def points(room: str, *, density: int = 200, peeks=None, scale: float = 1.0) -> Room:
    """A capture of `room` in its own frame. `scale` != 1 is a calibration that is off by that factor."""
    p, labels = capture(room, seed=ROOMS.index(room), density=density, noise_m=0.003, peeks=peeks)
    out = (scale * LOCAL[room].apply(p)).view(Room)
    out.floor = FloorGrid.of(np.asarray(out), labels)
    return out


@pytest.fixture(scope="module")
def clouds() -> dict[str, Room]:
    return {r: points(r) for r in ROOMS}


def truth(source: str, target: str) -> Similarity:
    """source room's frame -> target room's frame."""
    return LOCAL[target].compose(LOCAL[source].inverse())


def register(source, src: Room, target, dst: Room, correspondences, **kw):
    return register_pair(source, np.asarray(src), target, np.asarray(dst), gates=GATES, feature_voxel_m=SETTINGS.room_feature_voxel_m,
                         icp_voxel_m=SETTINGS.room_icp_voxel_m, overlap_radius_m=SETTINGS.room_overlap_radius_m,
                         icp_schedule_m=SCHEDULE, gravity_aligned=True, correspondences=correspondences,
                         source_floor=src.floor, target_floor=dst.floor, **kw)


def errors(T: Similarity, true: Similarity, at: np.ndarray) -> tuple[float, float]:
    return float(np.linalg.norm(T.apply(at) - true.apply(at))), rotation_angle_degrees(T.rotation @ true.rotation.T)


# ---- pairwise -------------------------------------------------------------------------------------------------------------


def test_two_overlapping_rooms_are_registered_through_their_doorway(clouds):
    result = register("A", clouds["A"], "B", clouds["B"], true_matches(truth("A", "B")))
    assert result.accepted and result.outcome == "REGISTERED", result.gates.failed
    t_err, r_err = errors(result.transform, truth("A", "B"), clouds["A"].mean(axis=0))
    assert t_err < 0.01 and r_err < 0.2, (t_err, r_err)
    assert result.transform.scale == 1.0, "both rooms are metric: the relative scale is held, never estimated"
    assert result.details["overlap_source_fraction"] < 0.5, "only the doorway overlaps, not the rooms"
    assert result.metrics.constraint_ratio > GATES.alignment.min_constraint_ratio


def test_mostly_wrong_feature_matches_still_give_the_right_registration(clouds):
    result = register("A", clouds["A"], "B", clouds["B"], true_matches(truth("A", "B"), outliers=0.9, n=600))
    assert result.accepted, result.gates.failed
    t_err, r_err = errors(result.transform, truth("A", "B"), clouds["A"].mean(axis=0))
    assert t_err < 0.01 and r_err < 0.2


@pytest.mark.parametrize(("source", "target", "yaw", "shift"), [("A", "B", 90, [1.5, -0.5, 0.0]), ("B", "A", 0, [2.0, 0.0, 0.0]),
                                                                  ("B", "C", 90, [1.5, -0.5, 0.0]), ("A", "C", 0, [2.0, 0.0, 0.0])])
def test_consistently_wrong_feature_matches_are_rejected(clouds, source, target, yaw, shift):
    """Matches that all agree on a wrong placement (what repeated structure produces). RANSAC finds that placement and
    ICP settles one room over the other, floor on floor and walls on walls. For A over B and B over A every surface gate
    passed: only the free-space test refuses it, because each room then stands where the other capture saw empty space."""
    wrong = Similarity(1.0, rotation([0, 0, 1], yaw), np.array(shift)).compose(truth(source, target))
    result = register(source, clouds[source], target, clouds[target], wrong_matches(wrong))
    assert not result.accepted
    t_err, _ = errors(result.transform, truth(source, target), clouds[source].mean(axis=0))
    assert t_err > 0.5, "the placement really is wrong"
    if result.metrics.correspondence_count >= GATES.alignment.min_correspondences and result.outcome != "NO_SHARED_FEATURES":
        assert result.details["free_space"]["source_in_target"]["measurable"]


def test_one_room_laid_over_another_is_refused_by_the_free_space_test_alone(clouds):
    """Refined by ICP, this overlay passed every surface gate (with the re-scan's constraint threshold, 0.05), which is why
    the free-space test exists. It now refuses the overlay on its unrefined pose already, and nothing else is claimed."""
    from dataclasses import replace

    lenient = replace(GATES, alignment=replace(GATES.alignment, min_constraint_ratio=SETTINGS.alignment_min_constraint_ratio))
    wrong = Similarity(1.0, rotation([0, 0, 1], 90), np.array([1.5, -0.5, 0.0])).compose(truth("A", "B"))
    result = register_pair("A", np.asarray(clouds["A"]), "B", np.asarray(clouds["B"]), gates=lenient,
                           feature_voxel_m=SETTINGS.room_feature_voxel_m, icp_voxel_m=SETTINGS.room_icp_voxel_m,
                           overlap_radius_m=SETTINGS.room_overlap_radius_m, icp_schedule_m=SCHEDULE, gravity_aligned=True,
                           correspondences=wrong_matches(wrong), source_floor=clouds["A"].floor, target_floor=clouds["B"].floor)
    assert result.gates.failed == ["free_space_conflict"], result.gates.failed
    assert result.outcome == "FREE_SPACE_CONFLICT"
    assert "correspondence_count" not in [g["name"] for g in result.gates.as_list()], "unmeasured gates are not reported as failed"
    fs = result.details["free_space"]
    assert max(fs["source_in_target"]["fraction"], fs["target_in_source"]["fraction"]) > 0.25


def test_a_right_registration_leaves_the_other_room_out_of_each_capture_s_free_space(clouds):
    result = register("A", clouds["A"], "B", clouds["B"], true_matches(truth("A", "B")))
    fs = result.details["free_space"]
    assert fs["source_in_target"]["measurable"] and fs["target_in_source"]["measurable"]
    assert max(fs["source_in_target"]["fraction"], fs["target_in_source"]["fraction"]) < 0.02


def test_two_placements_that_both_verify_are_ambiguous():
    """Repeated structure: the target capture holds two identical rooms 20 m apart, and the matches support both. Each
    placement passes every gate on its own -- they are equally right as far as the data can tell -- so the registration is
    refused as AMBIGUOUS rather than one picked."""
    p, labels = capture("A", seed=7, density=200, noise_m=0.003)
    q, qlabels = capture("A", seed=8, density=200, noise_m=0.003)
    twin = Similarity(1.0, np.eye(3), np.array([20.0, 0.0, 0.0]))
    target = np.vstack([q, twin.apply(q)])
    source = LOCAL["B"].apply(p)  # the source room in another frame
    truth = LOCAL["B"].inverse()  # source frame -> target frame (copy 1)
    first, second = true_matches(truth, n=300, outliers=0.3, seed=1), true_matches(twin.compose(truth), n=300, outliers=0.3, seed=2)

    def both(src, dst, voxel):
        (s1, d1), (s2, d2) = first(src, dst, voxel), second(src, dst, voxel)
        return np.vstack([s1, s2]), np.vstack([d1, d2])

    result = register_pair("A2", source, "twins", target, gates=GATES, feature_voxel_m=SETTINGS.room_feature_voxel_m,
                           icp_voxel_m=SETTINGS.room_icp_voxel_m, overlap_radius_m=SETTINGS.room_overlap_radius_m, icp_schedule_m=SCHEDULE,
                           gravity_aligned=True, correspondences=both, source_floor=FloorGrid.of(source, labels),
                           target_floor=FloorGrid.of(target, np.concatenate([qlabels, qlabels])))
    verified = [h for h in result.details["hypotheses"] if h["verified"]]
    assert len(verified) >= 2, result.details["hypotheses"]
    assert result.details["ambiguity_ratio"] > GATES.max_ambiguity_ratio
    assert not result.accepted and result.outcome == "AMBIGUOUS"


def test_the_strongest_placement_is_not_taken_unverified(clouds):
    """Most matches support room A laid over room B; fewer support the doorway. The overlay fails verification (free
    space), and the doorway placement, verified, is the registration."""
    overlay = Similarity(1.0, rotation([0, 0, 1], 90), np.array([1.5, -0.5, 0.0])).compose(truth("A", "B"))
    right = true_matches(truth("A", "B"), n=300, outliers=0.5, seed=4)

    def mixed(src, dst, voxel):
        s1 = src[np.random.default_rng(3).choice(len(src), 400, replace=False)]  # 400 matches agree exactly on the overlay
        s2, d2 = right(src, dst, voxel)  # ~150 right ones among 300
        return np.vstack([s1, s2]), np.vstack([overlay.apply(s1), d2])

    result = register("A", clouds["A"], "B", clouds["B"], mixed)
    assert result.accepted, (result.outcome, result.details["hypotheses"])
    assert result.details["chosen_hypothesis"] > 0, "not the strongest hypothesis"
    t_err, _ = errors(result.transform, truth("A", "B"), clouds["A"].mean(axis=0))
    assert t_err < 0.01


@pytest.mark.parametrize("peeks", [([((5.0, 1.8), (5.3, 2.2))], [((4.7, 1.8), (5.0, 2.2))]), ([], [])], ids=["glance", "jambs-only"])
def test_rooms_that_barely_see_each_other_are_rejected_as_a_weak_overlap(peeks):
    """Each camera only glanced through the door (a 0.3 m peek), or saw only the door frame: the shared surfaces are the
    jambs and the head, and nothing faces along the passage through the door. The best fit there is 15 cm off at the
    room's centre while every residual looks perfect; only the constraint ratio shows that a direction is unobserved."""
    a, b = points("A", peeks=peeks[0]), points("B", peeks=peeks[1])
    result = register("A", a, "B", b, true_matches(truth("A", "B")))
    t_err, _ = errors(result.transform, truth("A", "B"), np.asarray(a).mean(axis=0))
    assert t_err > 0.05, "the overlap really cannot fix the placement"
    assert result.metrics.translation_residual_m < GATES.alignment.max_translation_residual_m, "and the residuals do not show it"
    assert not result.accepted and result.outcome == "WEAK_OVERLAP", (result.outcome, result.gates.failed)
    assert result.gates.failed == ["constraint_ratio"]


def test_captures_without_shared_features_are_reported_as_such(clouds):
    def nothing(src, dst, voxel):
        return np.zeros((0, 3)), np.zeros((0, 3))

    result = register("A", clouds["A"], "B", clouds["B"], nothing)
    assert not result.accepted and result.outcome == "NO_SHARED_FEATURES"
    assert result.error and "robust estimation failed" in result.error
    assert result.gates.as_list(), "every gate is still reported"


@pytest.mark.parametrize("scale", [1.03, 0.97])
def test_two_calibrations_that_disagree_on_scale_are_rejected_by_name(clouds, scale):
    b = points("B", scale=scale)  # B's calibration is 3 % off; the stand-in matches B's points as they really are, scaled
    result = register("A", clouds["A"], "B", b, true_matches(Similarity(scale, np.eye(3), np.zeros(3)).compose(truth("A", "B")),
                                                             within_m=0.04))
    assert abs(result.details["ransac_scale"] - scale) < 0.01, "RANSAC sees the scale the calibrations disagree on"
    assert not result.accepted and result.outcome == "SCALE_INCONSISTENT", result.gates.failed
    assert result.transform.scale == 1.0, "nothing is ever rescaled to make two rooms fit"


def test_a_calibration_far_off_in_scale_finds_no_registration_at_all(clouds):
    b = points("B", scale=1.15)
    result = register("A", clouds["A"], "B", b, true_matches(Similarity(1.15, np.eye(3), np.zeros(3)).compose(truth("A", "B"))))
    assert not result.accepted
    assert result.transform.scale == 1.0


def test_a_small_scale_disagreement_within_the_limit_is_registered_rigidly(clouds):
    b = points("B", scale=1.01)
    result = register("A", clouds["A"], "B", b, true_matches(Similarity(1.01, np.eye(3), np.zeros(3)).compose(truth("A", "B"))))
    assert result.accepted, result.gates.failed
    assert result.transform.scale == 1.0


# ---- the room graph --------------------------------------------------------------------------------------------------------


def test_a_three_room_corridor_is_placed_in_the_anchor_frame_and_its_loop_closes(clouds):
    pairs = [("B", "A"), ("C", "A"), ("C", "B")]
    results = {(s, t): register(s, clouds[s], t, clouds[t], true_matches(truth(s, t)), seed=i) for i, (s, t) in enumerate(pairs)}
    assert all(r.accepted for r in results.values()), {k: r.gates.failed for k, r in results.items()}
    nodes = {r: room_node(r, clouds[r], fixed=r == "A", pose=Similarity.identity() if r == "A" else None) for r in ROOMS}
    edges = [Edge(t, s, r.transform, r.metrics.confidence) for (s, t), r in results.items()]
    graph = solve_room_graph(nodes, edges, gates=GATES)
    assert graph.registered == ["A", "B", "C"] and graph.unregistered == {}
    for room in ("B", "C"):  # pose: room frame -> anchor (A) frame
        t_err, r_err = errors(graph.poses[room], truth(room, "A"), clouds[room].mean(axis=0))
        assert t_err < 0.01 and r_err < 0.2, (room, t_err, r_err)
    [loop] = graph.loop_closures
    assert loop["consistent_before_optimisation"] and loop["status_after"] == "ACCEPTED"
    assert loop["cycle_translation_m"] < GATES.max_loop_translation_m
    assert all(e["status"] == "ACCEPTED" for e in graph.edges)
    assert graph.confirmed == {"A": True, "B": True, "C": True}, "the loop confirms every room"


def _graph(true_poses: dict[str, Similarity], links: list[tuple[str, str]], *, corrupt: dict | None = None, noise_m: float = 0.0,
           seed: int = 0):
    """Nodes 5 m rooms laid out in a line; edges measured from the true poses (target <- source), optionally corrupted."""
    rng = np.random.default_rng(seed)
    nodes = {r: room_node(r, rng.uniform(-2.5, 2.5, (500, 3)) * [1, 1, 0.4] + [0, 0, 1.2], fixed=r == "A",
                          pose=true_poses["A"] if r == "A" else None) for r in true_poses}
    edges = []
    for source, target in links:
        z = true_poses[target].inverse().compose(true_poses[source])
        if noise_m:
            z = Similarity(1.0, rotation([0, 0, 1], rng.normal(0, 0.05)), rng.normal(0, noise_m, 3) * [1, 1, 0]).compose(z)
        if corrupt and (source, target) in corrupt:
            z = corrupt[(source, target)].compose(z)
        edges.append(Edge(target, source, z, 1.0))
    return nodes, edges


TRUE = {r: Similarity(1.0, rotation([0, 0, 1], 20 * i), np.array([5.2 * i, 0.3 * i, 0.0])) for i, r in enumerate("ABCD")}


def test_loop_closure_spreads_the_drift_a_chain_accumulates():
    """A chain A-B-C-D each 1 cm off in the same direction accumulates 3 cm at D; the edge D-A closes the loop, and the
    optimised poses spread the error over the loop instead."""
    bias = Similarity(1.0, np.eye(3), np.array([0.01, 0.0, 0.0]))
    nodes, edges = _graph(TRUE, [("B", "A"), ("C", "B"), ("D", "C")], corrupt={("B", "A"): bias, ("C", "B"): bias, ("D", "C"): bias})
    chain = solve_room_graph(nodes, edges, gates=GATES)
    nodes, closed = _graph(TRUE, [("B", "A"), ("C", "B"), ("D", "C"), ("D", "A")],
                           corrupt={("B", "A"): bias, ("C", "B"): bias, ("D", "C"): bias})
    loop = solve_room_graph(nodes, closed, gates=GATES)

    def error(g, r):
        return float(np.linalg.norm(g.poses[r].translation - TRUE[r].translation))
    assert error(chain, "D") == pytest.approx(0.03, abs=0.005)
    assert error(loop, "D") < 0.5 * error(chain, "D"), (error(loop, "D"), error(chain, "D"))
    [closure] = loop.loop_closures
    assert closure["cycle_translation_m"] == pytest.approx(0.03, abs=0.01) and closure["status_after"] == "ACCEPTED"


def test_a_wrong_registration_is_identified_by_the_other_loops_and_dropped():
    """Every room registered to every other; one registration is 40 cm off. It lies on several loops whose other edges
    agree, so removing it alone makes every loop consistent and removing any other does not: it is the one dropped, and
    every room is still placed (by the others) where it really is."""
    links = [("B", "A"), ("C", "B"), ("D", "C"), ("D", "A"), ("C", "A"), ("D", "B")]  # every room registered to every other
    wrong = Similarity(1.0, np.eye(3), np.array([0.4, 0.0, 0.0]))
    nodes, edges = _graph(TRUE, links, corrupt={("C", "B"): wrong}, noise_m=0.002)
    graph = solve_room_graph(nodes, edges, gates=GATES)
    status = {e["edge"]: e["status"] for e in graph.edges}
    assert status["C->B"] == "INCONSISTENT", status
    assert sum(s != "ACCEPTED" for s in status.values()) == 1
    assert graph.registered == ["A", "B", "C", "D"]
    for r in "BCD":
        at = nodes[r].centroid  # at the room, not at the origin 10 m away, where rotation noise is multiplied by the distance
        # each registration carries 2 mm and 0.05 degrees of noise (~4 mm at 5 m between rooms); D is three rooms out
        assert np.linalg.norm(graph.poses[r].apply(at) - TRUE[r].apply(at)) < 0.02


def test_a_wrong_registration_in_a_single_loop_rejects_the_rooms_it_could_have_misplaced():
    """With one loop, removing any one of its edges makes it consistent: nothing shows which registration is wrong. All
    of them are dropped and the rooms are reported unregistered, rather than placed by a guess."""
    wrong = Similarity(1.0, np.eye(3), np.array([0.3, 0.0, 0.0]))
    nodes, edges = _graph({r: TRUE[r] for r in "ABC"}, [("B", "A"), ("C", "B"), ("C", "A")], corrupt={("B", "A"): wrong})
    graph = solve_room_graph(nodes, edges, gates=GATES)
    assert {e["status"] for e in graph.edges} == {"AMBIGUOUS"}
    assert graph.registered == ["A"] and set(graph.unregistered) == {"B", "C"}
    assert "dropped" in graph.unregistered["B"]


def test_a_room_placed_by_a_single_chain_of_registrations_is_reported_unconfirmed():
    """A chain A-B-C places every room, but a wrong B-C would be consistent with everything: nothing confirms C or B."""
    nodes, edges = _graph({r: TRUE[r] for r in "ABC"}, [("B", "A"), ("C", "B")])
    graph = solve_room_graph(nodes, edges, gates=GATES)
    assert graph.registered == ["A", "B", "C"]
    assert graph.confirmed == {"A": True, "B": False, "C": False}
    nodes, edges = _graph({r: TRUE[r] for r in "ABCD"}, [("B", "A"), ("C", "B"), ("C", "A"), ("D", "C")])
    assert solve_room_graph(nodes, edges, gates=GATES).confirmed == {"A": True, "B": True, "C": True, "D": False}


def test_a_room_with_no_registration_is_unregistered_with_the_reason():
    nodes, edges = _graph({r: TRUE[r] for r in "ABC"}, [("B", "A")])
    graph = solve_room_graph(nodes, edges, gates=GATES)
    assert graph.registered == ["A", "B"]
    assert graph.unregistered == {"C": "no accepted registration links it to the fixed rooms"}


def test_a_graph_needs_a_fixed_room_to_define_its_frame():
    nodes, edges = _graph({r: TRUE[r] for r in "AB"}, [("B", "A")])
    nodes["A"].fixed = False
    with pytest.raises(ValueError, match="fixed"):
        solve_room_graph(nodes, edges, gates=GATES)
