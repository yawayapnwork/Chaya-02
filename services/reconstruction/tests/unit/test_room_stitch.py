"""Stitching registered rooms' Gaussians (chaya_worker.room_stitch) and the stitched floor's room connectivity
(chaya_worker.navmesh.room_connectivity), on the SYNTHETIC multi-room floor (tests/multiroom_scene.py). The rooms are placed
with their exact transforms (or deliberately wrong ones), so what is checked is the stitch itself: transformation,
duplicate removal, the misalignment and seam gates. Mathematical tests only."""

from __future__ import annotations

import numpy as np
import pytest

from chaya_worker.frames import Similarity, quaternion_to_matrix
from chaya_worker.navmesh import room_connectivity
from chaya_worker.ply import GaussianCloud
from chaya_worker.region_splice import transform_gaussians
from chaya_worker.room_stitch import Piece, StitchRejected, floor_footprint, stitch_pieces
from chaya_worker.settings import Settings
from tests.multiroom_scene import capture, gaussians, local_frame
from tests.rescan_scene import rotation

S = Settings()
ROOMS = ("A", "B", "C")
LOCAL = {r: local_frame(i) for i, r in enumerate(ROOMS)}  # canonical -> each room's own frame
OUTPUT = LOCAL["A"]  # the stitched floor is written in the anchor's (A's) frame; this is canonical -> output


@pytest.fixture(scope="module")
def rooms():
    """Each room's Gaussians in its own frame, and its labels."""
    out = {}
    for i, r in enumerate(ROOMS):
        p, labels = capture(r, seed=i, density=200, noise_m=0.003)
        g = gaussians(p, seed=i)  # coloured by where the surface is (canonical), then moved into the room's own frame
        out[r] = (transform_gaussians(g, LOCAL[r]), labels)
    return out


def pieces(rooms, *, error: dict[str, Similarity] | None = None, colour_shift: dict[str, float] | None = None) -> list[Piece]:
    """The rooms moved into the output frame with their true transforms, each optionally followed by an `error`."""
    out = []
    for r in ROOMS:
        cloud, labels = rooms[r]
        to_output = OUTPUT.compose(LOCAL[r].inverse())
        if error and r in error:
            to_output = OUTPUT.compose(error[r]).compose(OUTPUT.inverse()).compose(to_output)  # the error is in canonical metres
        moved = transform_gaussians(cloud, to_output)
        if colour_shift and r in colour_shift:
            moved = GaussianCloud(moved.positions, moved.scales_log, moved.rotations_wxyz, moved.opacity_logit,
                                  moved.colors_dc + np.float32(colour_shift[r]))
        out.append(Piece(r, moved, labels))
    return out


def stitch(ps, **kw):
    args = dict(voxel_m=S.stitch_voxel_m, max_misalignment_m=S.stitch_max_misalignment_m, max_misalignment_deg=S.stitch_max_misalignment_deg,
                max_seam_step_m=S.stitch_max_seam_step_m, seam_band_m=S.stitch_seam_band_m, overlap_radius_m=S.room_overlap_radius_m,
                icp_schedule_m=S.room_schedule(), icp_voxel_m=S.room_icp_voxel_m, colour_warning=S.stitch_colour_warning)
    return stitch_pieces(ps, OUTPUT.inverse(), **{**args, **kw})


def covariances(cloud: GaussianCloud) -> np.ndarray:
    R = np.stack([quaternion_to_matrix(q) for q in cloud.rotations_wxyz.astype(np.float64)])
    s2 = np.exp(2 * cloud.scales_log.astype(np.float64))
    return np.einsum("nij,nj,nkj->nik", R, s2, R)


# ---- Gaussian transformation ------------------------------------------------------------------------------------------------


@pytest.mark.parametrize("scale", [1.0, 0.5])
def test_a_similarity_moves_every_gaussian_s_position_orientation_and_extent_and_nothing_else(scale):
    """X -> s R X + t moves a Gaussian's mean to s R mu + t and its covariance to s^2 R Sigma R^T: checked on the covariance
    itself, not on the parameters, so a wrong quaternion order or a scale applied twice would show. Opacity and colour
    (SH degree 0, rotation-invariant) are untouched."""
    cloud = gaussians(np.random.default_rng(3).uniform(-4, 4, (500, 3)), seed=3)
    T = Similarity(scale, rotation([1, -2, 0.5], 73), np.array([3.0, -1.0, 2.0]))
    moved = transform_gaussians(cloud, T)
    np.testing.assert_allclose(moved.positions, T.apply(cloud.positions), atol=1e-5)
    expected = scale**2 * np.einsum("ij,njk,lk->nil", T.rotation, covariances(cloud), T.rotation)
    np.testing.assert_allclose(covariances(moved), expected, rtol=1e-4, atol=1e-10)
    assert np.array_equal(moved.opacity_logit, cloud.opacity_logit) and np.array_equal(moved.colors_dc, cloud.colors_dc)


# ---- stitching ----------------------------------------------------------------------------------------------------------------


def test_three_registered_rooms_stitch_into_one_cloud_without_doubled_surfaces(rooms):
    ps = pieces(rooms)
    result = stitch(ps)
    report = result.report
    assert report["duplicate_fraction_after"] < 0.01, report["duplicates_after"]  # the ownership boundary only
    assert report["contested_voxels"] > 1000, "the doorways and the corridor really overlap"
    removed = [b["removed_as_duplicate"] for b in result.blocks]
    assert all(r > 0 for r in removed), "each room gives up its view through the doors where another room owns it"
    assert len(result.merged) == sum(b["count"] for b in result.blocks) == sum(len(p.cloud) for p in ps) - sum(removed)
    for pair in report["pairs"]:  # at the true placement; the corridor overlaps read up to ~1 cm (boundary bias of ICP)
        assert pair["misaligned"] is None
        assert pair["misalignment"]["offset_m"] < 0.015 and pair["misalignment"]["rotation_deg"] < 0.2, pair
    assert all(s["median_m"] < 0.01 for s in report["seams"]), report["seams"]
    assert report["colour_seams_visible"] == []


def test_each_room_is_one_block_of_its_own_gaussians_moved_and_otherwise_unchanged(rooms):
    """Block i of the merged cloud is room i's kept Gaussians, in order, each exactly the transformed original: an
    individual room can be cut out of the stitched floor again."""
    ps = pieces(rooms)
    result = stitch(ps)
    for i, (block, piece) in enumerate(zip(result.blocks, ps, strict=True)):
        assert block["piece"] == piece.id
        part = slice(block["start"], block["start"] + block["count"])
        kept = result.kept[i]
        assert kept.sum() == block["count"]
        for field in ("positions", "scales_log", "rotations_wxyz", "opacity_logit", "colors_dc"):
            assert np.array_equal(getattr(result.merged, field)[part], getattr(piece.cloud, field)[kept]), field
        assert np.array_equal(result.labels[part], np.asarray(piece.labels, dtype=object)[kept])


@pytest.mark.parametrize(("offset", "what"), [([0.10, 0.0, 0.0], "slide along the wall"), ([0.0, 0.10, 0.0], "slide through the door"),
                                              ([0.0, 0.0, 0.05], "5 cm vertical")])
def test_a_misregistered_room_is_refused_before_it_doubles_the_surfaces_it_shares(rooms, offset, what):
    with pytest.raises(StitchRejected, match="registration is wrong|seam") as refused:
        stitch(pieces(rooms, error={"B": Similarity(1.0, np.eye(3), np.array(offset))}))
    pairs = {tuple(p["pieces"]): p for p in refused.value.details.get("pairs", [])}
    measured = pairs[("A", "B")]["misalignment"]["offset_m"]
    assert measured == pytest.approx(np.linalg.norm(offset), rel=0.2), (what, measured)


def test_a_room_rotated_against_the_others_is_refused(rooms):
    centre = np.array([7.5, 2.0, 0.0])  # room B's centre, canonical metres
    twist = Similarity(1.0, rotation([0, 0, 1], 2.0), centre - rotation([0, 0, 1], 2.0) @ centre)  # 2 degrees about B's centre
    with pytest.raises(StitchRejected):
        stitch(pieces(rooms, error={"B": twist}))


def test_a_small_error_within_the_limits_is_stitched_and_reported(rooms):
    result = stitch(pieces(rooms, error={"B": Similarity(1.0, np.eye(3), np.array([0.015, 0.0, 0.0]))}))
    ab = next(p for p in result.report["pairs"] if p["pieces"] == ["A", "B"])
    assert 0.008 < ab["misalignment"]["offset_m"] < S.stitch_max_misalignment_m


def test_a_colour_seam_is_reported_but_geometry_decides_whether_the_stitch_is_valid(rooms):
    result = stitch(pieces(rooms, colour_shift={"B": 1.0}))  # B captured under different exposure
    assert ["A", "B"] in result.report["colour_seams_visible"] and ["B", "C"] in result.report["colour_seams_visible"]
    assert ["A", "C"] not in result.report["colour_seams_visible"]
    assert result.report["duplicate_fraction_after"] < 0.01


def test_shared_geometry_is_owned_by_the_densest_observer_and_the_anchor_wins_a_tie(rooms):
    cloud, labels = rooms["A"]
    copy = Piece("A2", GaussianCloud(*(getattr(cloud, f).copy() for f in ("positions", "scales_log", "rotations_wxyz",
                                                                           "opacity_logit", "colors_dc"))), labels)
    result = stitch([Piece("A", cloud, labels), copy])
    assert result.blocks[0]["removed_as_duplicate"] == 0 and result.blocks[1]["count"] == 0
    assert np.array_equal(result.merged.positions, cloud.positions)


def test_pieces_that_do_not_overlap_are_kept_whole(rooms):
    far = Similarity(1.0, np.eye(3), np.array([0.0, 50.0, 0.0]))
    ps = pieces(rooms)[:2]
    ps[1] = Piece("B", transform_gaussians(ps[1].cloud, OUTPUT.compose(far).compose(OUTPUT.inverse())), ps[1].labels)
    result = stitch(ps)
    assert result.report["pairs"] == [] and all(b["removed_as_duplicate"] == 0 for b in result.blocks)


def test_stitching_needs_two_distinct_pieces(rooms):
    ps = pieces(rooms)
    with pytest.raises(StitchRejected, match="two"):
        stitch(ps[:1])
    with pytest.raises(StitchRejected, match="own id"):
        stitch([ps[0], Piece("A", ps[1].cloud, ps[1].labels)])


def test_a_room_s_footprint_is_where_its_floor_is(rooms):
    cloud, labels = rooms["A"]
    cells = np.array(floor_footprint(LOCAL["A"].inverse().apply(cloud.positions), labels, 0.25, ("floor",)))
    xy = (cells + 0.5) * 0.25
    assert xy[:, 0].min() >= -0.25 and xy[:, 0].max() <= 7.25  # A's floor, plus what it saw of B's through the door
    assert len(cells) > 0.9 * (4.925 * 3.925) / 0.0625


# ---- connectivity of a stitched floor's routing graph --------------------------------------------------------------------------


def _graph(nodes: dict[str, tuple[float, float]], edges: list[tuple[str, str, float, bool]]) -> dict:
    n = [{"local_id": k, "x": x, "y": y, "z": 0.0} for k, (x, y) in nodes.items()]
    e = [{"from": a, "to": b, "min_clearance_m": w, "step_free": sf, "portal": [[0, 0, 0], [1, 0, 0]]} for a, b, w, sf in edges]
    return {"STANDARD": {"nodes": n, "edges": e}, "STEP_FREE": {"nodes": n, "edges": [x for x in e if x["step_free"]]}}


FOOTPRINTS = {"A": np.array([(i, j) for i in range(0, 20) for j in range(0, 16)]),  # x 0-5, y 0-4 at 0.25 m
              "B": np.array([(i, j) for i in range(20, 40) for j in range(0, 16)])}  # x 5-10
# the rooms' seeds are their footprints' deepest points, (2.5, 2.0) and (7.5, 2.0); the graph has a node at each
NODES = {"a0": (2.5, 2.0), "a1": (4.5, 2.0), "b1": (5.5, 2.0), "b0": (7.5, 2.0)}


def test_a_room_s_seed_is_the_deepest_point_of_its_floor():
    from chaya_worker.navmesh import room_seed

    assert np.allclose(room_seed(FOOTPRINTS["A"], 0.25), [2.5, 2.0], atol=0.25)
    corridor = np.array([(i, j) for i in range(0, 40) for j in range(16, 24)])  # x 0-10, y 4-6
    assert room_seed(corridor, 0.25)[1] == pytest.approx(5.0, abs=0.25), "along the middle of a corridor"
    assert room_seed(np.zeros((0, 2)), 0.25) is None


def test_rooms_joined_through_a_doorway_are_connected():
    g = _graph(NODES, [("a0", "a1", 2.0, True), ("a1", "b1", 0.95, True), ("b1", "b0", 2.0, True)])
    result = room_connectivity(g, FOOTPRINTS, 0.25, accessible_width_m=0.9)
    assert result["connected"] and result["step_free_connected"] and result["unreachable_rooms"] == []
    [door] = result["connections"]
    assert door["rooms"] == ["A", "B"] and door["max_edge_clearance_m"] == 0.95 and door["step_free"]
    assert result["rooms"]["A"]["reached"] and result["rooms"]["B"]["seed_xy"] == pytest.approx([7.5, 2.0], abs=0.25)


def test_a_doorway_the_navmesh_does_not_cross_disconnects_the_room():
    g = _graph(NODES, [("a0", "a1", 2.0, True), ("b1", "b0", 2.0, True)])
    result = room_connectivity(g, FOOTPRINTS, 0.25, accessible_width_m=0.9)
    assert not result["connected"] and result["connections"] == []
    assert len(result["unreachable_rooms"]) == 1


def test_a_stepped_doorway_is_reported_not_hidden():
    g = _graph(NODES, [("a0", "a1", 2.0, True), ("a1", "b1", 0.7, False), ("b1", "b0", 2.0, True)])
    result = room_connectivity(g, FOOTPRINTS, 0.25, accessible_width_m=0.9)
    assert result["connected"] and not result["step_free_connected"]
    assert result["rooms"]["B"] == {**result["rooms"]["B"], "reached": True, "step_free_reached": False}
    assert not result["connections"][0]["step_free"]


def test_a_room_without_navmesh_near_its_middle_is_unreachable():
    g = _graph({"a0": (2.5, 2.0), "a1": (4.5, 2.0)}, [("a0", "a1", 2.0, True)])
    result = room_connectivity(g, FOOTPRINTS, 0.25, accessible_width_m=0.9)
    assert result["unreachable_rooms"] == ["B"] and result["rooms"]["B"]["nodes"] == 0
