"""Registering independently captured rooms of one floor into one coordinate frame (docs/multi-room.md). Pure numpy +
scipy; Open3D only for the FPFH descriptors, as in chaya_worker.region_alignment.

Every room is a separate capture with its own SfM reconstruction and its own calibration, so it is already metric, and
gravity-aligned when its calibration is canonical. Rooms overlap only where one capture saw into the other: through a
doorway, along a shared hallway. Registration therefore has three parts:

  * **Pairwise** (`register_pair`): putative correspondences from FPFH feature matching (geometric descriptors of the
    reconstructed surfaces: door frames, wall corners, furniture seen through the opening); robust estimation of several
    distinct candidate placements (for gravity-aligned rooms a rotation about the vertical and a translation from pairs of
    matches); each VERIFIED by rigid ICP restricted to the overlap and every gate below; the strongest verified one wins. Measured and gated with the same physical metrics and
    gates as the re-scan alignment (chaya_worker.similarity_registration), plus:
      - **ambiguity**: a second, clearly different placement that ALSO passes every gate, with nearly as much support,
        means the overlap does not determine the transform (repeated structure: two identical rooms, a corridor of
        identical doors). A placement that fails verification is no alternative;
      - **relative scale**: both rooms are metric, so the transform's scale is held at 1. The scale the placement's matches
        support (a fit with scale to them) is evidence about the two calibrations: if it differs from 1 by more than
        `max_relative_scale` the rooms' calibrations disagree and the pair is rejected by name. Nothing is ever rescaled to
        make it fit;
      - **tilt**: two gravity-aligned rooms may only be rotated about the vertical;
      - **free space** (`free_space_conflict`): two different rooms cannot occupy the same space. Where a capture observed
        its floor, the space above it was empty unless the capture saw something there. The other room's surfaces in
        that space (FREE_SPACE_HEIGHT_M above the floor, farther than FREE_SPACE_CLEARANCE_M from anything the capture
        saw) contradict the placement. This is the gate that refuses one room laid over another: two box-shaped rooms,
        floor on floor and walls on walls, pass every surface gate (measured on the synthetic floor: a 90-degree overlay
        of room A onto room B was accepted with every overlap gate passing, and stood 100 % in B's observed free space;
        right placements measured 0.0-0.4 %). It is measured both ways and the larger fraction is gated.
    ICP uses only the overlap (source points near the target after the RANSAC estimate). Two rooms share a strip, and
    ICP over everything would pull each room's far walls towards the other room's nearest surfaces. It is point-to-plane
    (similarity_registration.icp_point_to_plane): each capture saw only part of the other room's surfaces, and
    point-to-point ICP drags a surface seen whole towards the edge of the part seen through the door.

  * **Pose graph and loop closure** (`optimize_pose_graph`, `solve_room_graph`): rooms are nodes, accepted pairwise
    registrations are edges. A spanning tree from the fixed rooms places every reachable room; every edge outside the
    tree closes a loop, and its cycle error (rotation, translation, scale) is reported. The graph is then optimised
    (Gauss-Newton, robust Huber weights, residuals in metres at each room's extent) with the fixed rooms held. Then every
    pair of PLACED rooms, registered to each other or not, is checked for free-space conflicts
    (`global_free_space_conflicts`). An edge whose residual stays above the loop-consistency limits, or a conflict, means a
    wrong registration. An edge is removed only when removing it alone makes everything consistent (when several would,
    the one that leaves the most rooms placed). When several edges could each explain the conflict equally, there is no
    evidence which one is right, and all of them are dropped. A room left with no accepted path to a fixed room is
    unregistered, with the reason. A placed room that only one chain of registrations places is reported unconfirmed
    (`loop_confirmed`).

Nothing here decides what is published. chaya_worker.stages.room_registration turns a result with unregistered rooms
into a structured REGISTRATION_REJECTED failure.
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass, field

import numpy as np
from scipy.spatial import cKDTree
from scipy.spatial.transform import Rotation

from .frames import Similarity
from .region_alignment import fpfh_correspondences
from .similarity_registration import (
    AlignmentGates,
    AlignmentMetrics,
    GateReport,
    Hypothesis,
    RegistrationError,
    alignment_metrics,
    evaluate_gates,
    icp_point_to_plane,
    ransac_similarity,
    ransac_yaw_hypotheses,
    rotation_angle_degrees,
    tilt_degrees,
    voxel_downsample,
)

Correspondences = Callable[[np.ndarray, np.ndarray, float], tuple[np.ndarray, np.ndarray]]

METHOD = "ROOM_FEATURE_GRAPH"
# per ICP stage; see chaya_worker.room_stitch.ICP_ITERATIONS
ICP_ITERATIONS = 100

# The free-space test: the height band above a capture's observed floor taken to be empty space it saw (above skirting
# and floor clutter, below where a camera ray to the floor could have passed over something), and how close one of its own
# points must be for the other room's surface there to count as seen by it too.
FREE_SPACE_HEIGHT_M = (0.3, 1.5)
FREE_SPACE_CLEARANCE_M = 0.10
FREE_SPACE_MIN_POINTS = 50  # fewer of the other room's points in that band is no evidence either way
FLOOR_LABELS = ("floor",)


@dataclass(frozen=True)
class RoomGates:
    """Pairwise and loop-closure thresholds, each in the unit its name says. Defaults come from chaya_worker.settings."""

    alignment: AlignmentGates = field(default_factory=lambda: AlignmentGates(min_correspondences=300, max_scale_correction=0.05))
    max_ambiguity_ratio: float = 0.8  # second distinct hypothesis' inliers / the winner's
    distinct_rotation_deg: float = 5.0  # a hypothesis further than this (or distinct_translation_m) is a different answer
    distinct_translation_m: float = 0.25
    max_relative_scale: float = 0.02  # |RANSAC scale - 1|: how far two metric calibrations may disagree
    # loop closure: an edge's residual after optimisation
    max_loop_translation_m: float = 0.05
    max_loop_rotation_deg: float = 1.0
    max_loop_log_scale: float = 0.01
    max_free_space_conflict: float = 0.10  # fraction of the other room's surfaces standing in space a capture saw empty


@dataclass
class PairRegistration:
    source: str  # room id whose points were moved
    target: str  # room id they were registered onto
    transform: Similarity  # source metric -> target metric
    metrics: AlignmentMetrics
    gates: GateReport
    details: dict = field(default_factory=dict)
    error: str | None = None

    @property
    def accepted(self) -> bool:
        return self.error is None and self.gates.passed

    @property
    def status(self) -> str:
        return "ACCEPTED" if self.accepted else "REJECTED"

    @property
    def outcome(self) -> str:
        """Why, in one word an operator can act on (see OUTCOMES)."""
        if self.accepted:
            return "REGISTERED"
        failed = set(self.gates.failed)
        if self.error is not None and ("robust estimation" in self.error or "do not overlap" in self.error
                                       or "too few points" in self.error) or "ransac_inliers" in failed:
            return "NO_SHARED_FEATURES"
        if "relative_scale" in failed:
            return "SCALE_INCONSISTENT"
        if "ambiguity_ratio" in failed:
            return "AMBIGUOUS"
        if "free_space_conflict" in failed:
            return "FREE_SPACE_CONFLICT"
        if failed & {"correspondence_count", "inlier_ratio", "constraint_ratio"}:
            return "WEAK_OVERLAP"
        if "tilt_deg" in failed:
            return "TILTED"
        return "RESIDUAL_TOO_HIGH"

    def as_dict(self) -> dict:
        return {"source": self.source, "target": self.target, "status": self.status, "outcome": self.outcome, "error": self.error,
                "transform": self.transform.to_dict(), "metrics": self.metrics.as_dict(), "gates": self.gates.as_list(),
                "failed_gates": self.gates.failed, "details": self.details}


# What a pairwise registration's `outcome` means. NO_SHARED_FEATURES is the usual answer for two rooms that simply do not
# see each other; it is not an error unless it leaves a room without any registration.
OUTCOMES = {
    "REGISTERED": "accepted: every gate passed",
    "NO_SHARED_FEATURES": "too few consistent feature matches: the two captures do not observe a common surface",
    "FREE_SPACE_CONFLICT": "the placement puts one room's surfaces where the other capture saw empty space (one room laid over the other)",
    "SCALE_INCONSISTENT": "the matches agree on a scale the two metric calibrations do not: one of them is wrong",
    "AMBIGUOUS": "a second, different placement explains almost as many matches (repeated structure)",
    "WEAK_OVERLAP": "the shared surfaces are too few, too poorly matched, or leave a direction unconstrained",
    "TILTED": "the placement would tilt one gravity-aligned room against the other",
    "RESIDUAL_TOO_HIGH": "the shared surfaces still disagree after ICP",
}


@dataclass(frozen=True)
class FloorGrid:
    """Where a capture observed its floor: the horizontal cells (cell_m grid, the room's canonical frame) holding floor
    points, eroded by one cell (a cell next to a wall also holds the wall's far side), with the floor height in each."""

    cell_m: float
    keys: np.ndarray  # sorted cell keys
    floor_z: np.ndarray  # median floor height of each cell

    @staticmethod
    def of(points: np.ndarray, labels: np.ndarray | None, cell_m: float = 0.25) -> FloorGrid:
        p = np.asarray(points, dtype=np.float64)
        if labels is not None and len(labels) == len(p):
            floor = p[np.isin(np.asarray(labels, dtype=object), FLOOR_LABELS)]
        else:  # unlabelled: the lowest band of the room
            floor = p[p[:, 2] <= np.percentile(p[:, 2], 2) + 0.15] if len(p) else p
        if len(floor) == 0:
            return FloorGrid(cell_m, np.zeros(0, np.int64), np.zeros(0))
        cells = np.floor(floor[:, :2] / cell_m).astype(np.int64)
        keys, inv = np.unique(_cell_key(cells), return_inverse=True)
        inv = inv.reshape(-1)
        order = np.argsort(inv, kind="stable")
        splits = np.flatnonzero(np.diff(inv[order])) + 1
        z = np.array([np.median(chunk) for chunk in np.split(floor[order, 2], splits)])
        neighbours = np.array([(dx << 32) + dy for dx in (-1, 0, 1) for dy in (-1, 0, 1)], dtype=np.int64)
        interior = np.all(np.isin(keys[:, None] + neighbours[None, :], keys), axis=1)
        return FloorGrid(cell_m, keys[interior], z[interior])

    def floor_height(self, points: np.ndarray) -> np.ndarray:
        """The observed floor height under each point, NaN where this capture observed no floor."""
        if len(self.keys) == 0:
            return np.full(len(points), np.nan)
        k = _cell_key(np.floor(np.asarray(points)[:, :2] / self.cell_m).astype(np.int64))
        idx = np.clip(np.searchsorted(self.keys, k), 0, len(self.keys) - 1)
        return np.where(self.keys[idx] == k, self.floor_z[idx], np.nan)


def _cell_key(cells: np.ndarray) -> np.ndarray:
    return (cells[:, 0] << 32) + cells[:, 1]


def free_space_conflict(observer_floor: FloorGrid, observer_tree: cKDTree, other: np.ndarray) -> dict:
    """The fraction of `other`'s points (in the observer's frame) that stand in space the observer saw empty: above a floor
    cell it observed, within FREE_SPACE_HEIGHT_M of that floor, with none of the observer's own points within
    FREE_SPACE_CLEARANCE_M. `measurable` is false (and the fraction 0) with fewer than FREE_SPACE_MIN_POINTS candidates."""
    h = other[:, 2] - observer_floor.floor_height(other)
    candidates = np.isfinite(h) & (h >= FREE_SPACE_HEIGHT_M[0]) & (h <= FREE_SPACE_HEIGHT_M[1])
    n = int(candidates.sum())
    if n < FREE_SPACE_MIN_POINTS:
        return {"candidates": n, "conflicting": 0, "fraction": 0.0, "measurable": False}
    d, _ = observer_tree.query(other[candidates], distance_upper_bound=FREE_SPACE_CLEARANCE_M)
    conflicting = int((~np.isfinite(d)).sum())
    return {"candidates": n, "conflicting": conflicting, "fraction": conflicting / n, "measurable": True}


def _distinct(a: Similarity, b: Similarity, at: np.ndarray, gates: RoomGates) -> bool:
    return (rotation_angle_degrees(a.rotation @ b.rotation.T) > gates.distinct_rotation_deg
            or float(np.linalg.norm(a.apply(at) - b.apply(at))) > gates.distinct_translation_m)


# Distinct placements verified per pair of rooms. With real FPFH on the synthetic floor, the 4 strongest placements of the
# corridor onto room A did not include the true one; among the 8 strongest it was there and verified (8 mm off). With 4,
# the corridor was placed through room B alone, 5 m off along itself, and no loop could show it (loop_confirmed).
MAX_HYPOTHESES = 8
PREFILTER_FREE_SPACE = 0.5  # a placement whose unrefined pose already puts this much in observed free space is not refined


def _hypotheses(p_src: np.ndarray, p_dst: np.ndarray, *, gravity_aligned: bool, inlier_threshold_m: float, scale_bound: float,
                centre: np.ndarray, gates: RoomGates, seed: int) -> tuple[list[Hypothesis], int]:
    """The strongest distinct placements robust estimation finds, strongest first, and how many hypotheses it tested.
    Gravity-aligned rooms: rotation about +Z and translation from pairs of matches (similarity_registration
    .ransac_yaw_hypotheses). Otherwise: a similarity from triples, again on the matches the earlier ones did not explain."""
    if gravity_aligned:
        return ransac_yaw_hypotheses(p_src, p_dst, inlier_threshold_m=inlier_threshold_m, max_hypotheses=MAX_HYPOTHESES,
                                     min_support=gates.alignment.min_ransac_inliers, distinct_rotation_deg=gates.distinct_rotation_deg,
                                     distinct_translation_m=gates.distinct_translation_m, at=centre, seed=seed)
    out: list[Hypothesis] = []
    rest = np.ones(len(p_src), dtype=bool)
    tested = 0
    while len(out) < MAX_HYPOTHESES and rest.sum() >= 3:
        try:
            r = ransac_similarity(p_src[rest], p_dst[rest], inlier_threshold_m=inlier_threshold_m,
                                  scale_bounds=(1.0 / scale_bound, scale_bound), seed=seed + len(out))
        except RegistrationError:
            break
        tested += r.hypotheses_tested
        inliers = np.zeros(len(p_src), dtype=bool)
        inliers[np.flatnonzero(rest)[r.inliers]] = True
        rigid = Similarity(1.0, r.transform.rotation, r.transform.translation)
        if all(_distinct(rigid, h.transform, centre, gates) for h in out):
            out.append(Hypothesis(rigid, inliers, r.transform.scale))
        rest &= ~inliers
    if not out:
        raise RegistrationError("no hypothesis found 3 consistent correspondences")
    return out, tested


def register_pair(source_id: str, source_m: np.ndarray, target_id: str, target_m: np.ndarray, *, gates: RoomGates,
                  feature_voxel_m: float, icp_voxel_m: float, overlap_radius_m: float, icp_schedule_m: list[float],
                  gravity_aligned: bool, correspondences: Correspondences = fpfh_correspondences, seed: int = 0,
                  source_floor: FloorGrid | None = None, target_floor: FloorGrid | None = None) -> PairRegistration:
    """Registers room `source_m` onto room `target_m` (both metric, in their own frames). Never raises for an estimation
    failure: that comes back as a REJECTED result with `error` set and every gate reported. The free-space gate needs both
    rooms' floors (`FloorGrid.of` their own points and labels, in their own frames); it is computed from the points alone
    (their lowest band) when they are not given.

    Robust estimation proposes up to MAX_HYPOTHESES distinct placements (_hypotheses); each is VERIFIED: refined by ICP on
    its overlap and measured against every gate, free space included. The strongest placement that passes every gate is the
    registration. If a second, distinct one passes too, the overlap does not determine the placement (repeated structure)
    and the `ambiguity_ratio` gate (its support / the winner's) refuses it. Choosing the strongest placement unverified,
    as one RANSAC does, fails exactly where rooms are alike: with real FPFH matches on the synthetic floor the strongest
    placement of a room was the other room, 5.1 m off and laid over it (refused by the free-space gate), while the
    doorway placement had fewer matches. When none passes, the strongest one's measurements are reported."""
    g = gates.alignment
    src = voxel_downsample(np.asarray(source_m, dtype=np.float64), icp_voxel_m)
    dst = voxel_downsample(np.asarray(target_m, dtype=np.float64), icp_voxel_m)
    base: dict = {"source_points": len(src), "target_points": len(dst), "icp_voxel_m": icp_voxel_m,
                  "overlap_radius_m": overlap_radius_m, "icp_schedule_m": list(icp_schedule_m),
                  "scale_policy": "METRIC_CALIBRATIONS: relative scale held at 1; the matches' own scale is gated as evidence",
                  "estimation": "GRAVITY_YAW_2POINT" if gravity_aligned else "SIMILARITY_3POINT"}
    empty = AlignmentMetrics(0, 0, 0.0, 1.0, 90.0, float("inf"), float("inf"), float("inf"), 0.0, 0.0)

    def rejected(error: str, details: dict, report: GateReport | None = None, T: Similarity | None = None,
                 metrics: AlignmentMetrics = empty) -> PairRegistration:
        report = report or GateReport()
        for r in evaluate_gates(metrics, g).results:
            report.results.append(r)
        report.add("estimation", None, 0.0, ">=")
        return PairRegistration(source_id, target_id, T or Similarity.identity(), metrics, report, details, error)

    if len(src) < 3 or len(dst) < 3:
        return rejected(f"too few points ({len(src)} source, {len(dst)} target after {icp_voxel_m} m downsampling)", dict(base))
    p_src, p_dst = correspondences(src, dst, feature_voxel_m)
    base["putative_correspondences"] = len(p_src)
    centre = src.mean(axis=0)
    try:
        hypotheses, tested = _hypotheses(p_src, p_dst, gravity_aligned=gravity_aligned, inlier_threshold_m=1.5 * feature_voxel_m,
                                         scale_bound=1.0 + g.max_scale_correction, centre=centre, gates=gates, seed=seed)
    except RegistrationError as exc:
        report = GateReport()
        report.add("ransac_inliers", 0, g.min_ransac_inliers, ">=", "correspondences")
        return rejected(f"robust estimation failed: {exc}", dict(base), report)
    base["ransac_hypotheses"] = tested

    tree = cKDTree(dst)
    src_tree = cKDTree(src)
    source_floor = source_floor or FloorGrid.of(src, None)
    target_floor = target_floor or FloorGrid.of(dst, None)

    def free_space(T: Similarity) -> tuple[dict, dict]:
        return free_space_conflict(target_floor, tree, T.apply(src)), free_space_conflict(source_floor, src_tree, T.inverse().apply(dst))

    def verify(h: Hypothesis) -> PairRegistration:
        details = dict(base)
        details.update(ransac_inliers=h.inlier_count, ransac_inlier_ratio=h.inlier_count / max(len(p_src), 1),
                       ransac_scale=h.scale_estimate)
        report = GateReport()
        report.add("ransac_inliers", h.inlier_count, g.min_ransac_inliers, ">=", "correspondences")
        report.add("relative_scale", abs(h.scale_estimate - 1.0), gates.max_relative_scale, "<=")
        init = Similarity(1.0, h.transform.rotation, h.transform.translation)  # rigid: the scale is the calibrations'
        if h.inlier_count < g.min_ransac_inliers:
            return rejected(f"the placement is supported by only {h.inlier_count} correspondences", details, report, init)
        pre = free_space(init)
        if max(pre[0]["fraction"], pre[1]["fraction"]) > PREFILTER_FREE_SPACE:
            # Refused before ICP: only what was measured is reported (the surface gates were not evaluated, not failed).
            details["free_space"] = {"source_in_target": pre[0], "target_in_source": pre[1], "before_icp": True}
            report.add("free_space_conflict", max(pre[0]["fraction"], pre[1]["fraction"]), gates.max_free_space_conflict, "<=")
            return PairRegistration(source_id, target_id, init, empty, report, details,
                                    "the placement puts one room where the other capture saw empty space (refused before ICP)")

        def overlap(T: Similarity, radius: float = overlap_radius_m) -> np.ndarray:
            d, _ = tree.query(T.apply(src), distance_upper_bound=radius)
            return np.isfinite(d)

        in_overlap = overlap(init)
        if in_overlap.sum() < 3:
            return rejected(f"the robust estimate leaves only {int(in_overlap.sum())} source points within {overlap_radius_m} m of "
                            "the target: the rooms do not overlap", details, report, init)
        try:
            icp = icp_point_to_plane(src[in_overlap], dst, init, schedule_m=icp_schedule_m, dst_tree=tree,
                                     max_iterations_per_stage=ICP_ITERATIONS)
        except RegistrationError as exc:
            return rejected(f"ICP failed: {exc}", details, report, init)
        T = icp.transform
        details["icp_iterations"] = icp.iterations
        # The overlap the metrics are taken on: within twice the final correspondence distance. ICP needed the wider radius to
        # converge from the RANSAC estimate; measured at that radius, the band of surface just beyond the other capture's view
        # (on the same plane, up to the radius away) would count as missing correspondences that no alignment could find.
        measure_radius = 2.0 * icp_schedule_m[-1]
        in_overlap = overlap(T, measure_radius)
        # the target's side of the overlap: every target point within the radius of the moved source
        d_back, _ = cKDTree(T.apply(src[in_overlap])).query(dst, distance_upper_bound=measure_radius)
        overlap_target = dst[np.isfinite(d_back)]
        details.update(measure_radius_m=measure_radius, overlap_source_points=int(in_overlap.sum()), overlap_target_points=len(overlap_target),
                       overlap_source_fraction=float(in_overlap.mean()), overlap_target_fraction=len(overlap_target) / len(dst))
        if len(overlap_target) < 3:
            return rejected("no target points in the overlap after ICP", details, report, T)
        metrics = alignment_metrics(src[in_overlap], overlap_target, T, correspondence_distance_m=icp_schedule_m[-1],
                                    max_translation_residual_m=g.max_translation_residual_m, dst_tree=cKDTree(overlap_target))
        for r in evaluate_gates(metrics, g).results:
            report.results.append(r)
        if gravity_aligned:
            details["tilt_deg"] = tilt_degrees(T.rotation)
            report.add("tilt_deg", details["tilt_deg"], g.max_tilt_deg, "<=", "deg")
        # Free space, both ways: the moved source in the target's observed free space, and the target in the source's.
        into_target, into_source = free_space(T)
        details["free_space"] = {"source_in_target": into_target, "target_in_source": into_source,
                                 "height_band_m": list(FREE_SPACE_HEIGHT_M), "clearance_m": FREE_SPACE_CLEARANCE_M}
        report.add("free_space_conflict", max(into_target["fraction"], into_source["fraction"]), gates.max_free_space_conflict, "<=")
        details["rotation_deg"] = rotation_angle_degrees(T.rotation)
        return PairRegistration(source_id, target_id, T, metrics, report, details, None)

    candidates = [verify(h) for h in hypotheses]
    verified = [k for k, c in enumerate(candidates) if c.accepted]
    chosen = verified[0] if verified else 0
    result = candidates[chosen]
    # Uniqueness: a second, distinct placement that passes every gate too (repeated structure) -- by its support.
    ambiguity = 0.0
    if len(verified) >= 2 and _distinct(candidates[verified[0]].transform, candidates[verified[1]].transform, centre, gates):
        ambiguity = hypotheses[verified[1]].inlier_count / max(hypotheses[verified[0]].inlier_count, 1)
    result.details["ambiguity_ratio"] = ambiguity
    result.details["chosen_hypothesis"] = chosen
    result.details["hypotheses"] = [{
        "inliers": h.inlier_count, "scale_estimate": h.scale_estimate, "verified": c.accepted, "failed_gates": c.gates.failed,
        "error": c.error, "rotation_deg": rotation_angle_degrees(c.transform.rotation),
        "translation_at_source_centre": c.transform.apply(centre).tolist(),
        "free_space": max((c.details.get("free_space") or {}).get("source_in_target", {}).get("fraction", 0.0),
                          (c.details.get("free_space") or {}).get("target_in_source", {}).get("fraction", 0.0)),
    } for h, c in zip(hypotheses, candidates, strict=True)]
    result.gates.add("ambiguity_ratio", ambiguity, gates.max_ambiguity_ratio, "<=")
    return result


# ---- the pose graph ------------------------------------------------------------------------------------------------------


@dataclass
class RoomNode:
    id: str
    centroid: np.ndarray  # in the room's own metric frame
    extent_m: float  # RMS distance of its points from the centroid: the lever arm that turns rotation into metres
    fixed: bool = False
    pose: Similarity | None = None  # room metric -> global; required for fixed rooms
    # for the global free-space check (solve_room_graph): its points (downsampled) and the floor it observed, own frame
    points: np.ndarray | None = None
    floor: FloorGrid | None = None


@dataclass
class Edge:
    target: str
    source: str
    measurement: Similarity  # source metric -> target metric
    weight: float = 1.0
    id: str = ""

    def key(self) -> str:
        return self.id or f"{self.source}->{self.target}"


def edge_error(e: Edge, poses: dict[str, Similarity], nodes: dict[str, RoomNode]) -> tuple[float, float, float]:
    """(translation m at the source room's centroid, rotation deg, |log scale|) of Z^-1 X_t^-1 X_s: how far the measured
    transform is from the one the poses imply."""
    E = e.measurement.inverse().compose(poses[e.target].inverse().compose(poses[e.source]))
    c = nodes[e.source].centroid
    return (float(np.linalg.norm(E.apply(c) - c)), rotation_angle_degrees(E.rotation), float(abs(np.log(E.scale))))


def _consistent(err: tuple[float, float, float], gates: RoomGates) -> bool:
    return err[0] <= gates.max_loop_translation_m and err[1] <= gates.max_loop_rotation_deg and err[2] <= gates.max_loop_log_scale


def spanning_tree_poses(nodes: dict[str, RoomNode], edges: list[Edge]) -> tuple[dict[str, Similarity], list[Edge], list[Edge]]:
    """Places every room reachable from a fixed room through the strongest edges first (Prim from all fixed rooms).
    Returns (poses, tree edges, loop-closing edges between placed rooms)."""
    poses = {n.id: n.pose for n in nodes.values() if n.fixed}
    tree: list[Edge] = []
    remaining = sorted(edges, key=lambda e: -e.weight)
    grew = True
    while grew:
        grew = False
        for e in remaining:
            if (e.target in poses) != (e.source in poses):
                if e.target in poses:
                    poses[e.source] = poses[e.target].compose(e.measurement)
                else:
                    poses[e.target] = poses[e.source].compose(e.measurement.inverse())
                tree.append(e)
                remaining.remove(e)
                grew = True
                break
    loops = [e for e in remaining if e.source in poses and e.target in poses]
    return poses, tree, loops


def _exp(delta: np.ndarray, estimate_scale: bool) -> Similarity:
    omega, v, sigma = delta[:3], delta[3:6], delta[6] if estimate_scale else 0.0
    return Similarity(float(np.exp(sigma)), Rotation.from_rotvec(omega).as_matrix(), v)


def optimize_pose_graph(nodes: dict[str, RoomNode], edges: list[Edge], initial: dict[str, Similarity], *, sigma_m: float = 0.01,
                        gravity_aligned: bool = False, estimate_scale: bool = False, scale_prior_sigma: float = 0.01,
                        iterations: int = 30, huber_k: float = 3.0) -> tuple[dict[str, Similarity], dict]:
    """Gauss-Newton (Levenberg-damped) over the free rooms' poses, left-perturbed: X <- Exp(delta) X. An edge contributes 7
    residuals in metres at its source room's extent: the displacement of the room's centroid, rotation x extent and log
    scale x extent, divided by `sigma_m` / sqrt(weight). With `gravity_aligned` a room may only turn about +Z; without
    `estimate_scale` scales are held (the rooms are metric); with it, each free room's log scale also has a prior
    residual log(s) / scale_prior_sigma (its own calibration is a measurement too). Robust Huber weights on each edge."""
    free = [n for n in nodes if not nodes[n].fixed and n in initial]
    mask = np.array([not gravity_aligned, not gravity_aligned, True, True, True, True, estimate_scale])
    dim = int(mask.sum())
    poses = dict(initial)
    usable = [e for e in edges if e.source in poses and e.target in poses]

    def residuals(p: dict[str, Similarity]) -> np.ndarray:
        out = []
        for e in usable:
            E = e.measurement.inverse().compose(p[e.target].inverse().compose(p[e.source]))
            n = nodes[e.source]
            r = np.concatenate([E.apply(n.centroid) - n.centroid, Rotation.from_matrix(E.rotation).as_rotvec() * n.extent_m,
                                [np.log(E.scale) * n.extent_m]])
            res = r * np.sqrt(e.weight) / sigma_m
            norm = np.linalg.norm(res)
            if norm > huber_k:  # Huber: linear beyond k
                res = res * np.sqrt(huber_k / norm)
            out.append(res)
        if estimate_scale:
            out.extend([[np.log(p[f].scale) / scale_prior_sigma] for f in free])
        return np.concatenate(out) if out else np.zeros(0)

    def perturbed(p: dict[str, Similarity], x: np.ndarray) -> dict[str, Similarity]:
        q = dict(p)
        for k, f in enumerate(free):
            d = np.zeros(7)
            d[mask] = x[k * dim:(k + 1) * dim]
            q[f] = _exp(d, estimate_scale).compose(p[f])
        return q

    if not free or not usable:
        r = residuals(poses)
        return poses, {"iterations": 0, "cost": float(r @ r), "free_rooms": free}
    lam, cost = 1e-3, float(residuals(poses) @ residuals(poses))
    it = 0
    for it in range(1, iterations + 1):  # noqa: B007 - reported below
        r0 = residuals(poses)
        J = np.zeros((len(r0), dim * len(free)))
        eps = 1e-6
        for c in range(J.shape[1]):
            x = np.zeros(J.shape[1])
            x[c] = eps
            J[:, c] = (residuals(perturbed(poses, x)) - residuals(perturbed(poses, -x))) / (2 * eps)
        H, b = J.T @ J, J.T @ r0
        step = np.linalg.solve(H + lam * np.diag(np.diag(H) + 1e-9), -b)
        candidate = perturbed(poses, step)
        new_cost = float(residuals(candidate) @ residuals(candidate))
        if new_cost < cost:
            poses, cost, lam = candidate, new_cost, max(lam / 10, 1e-9)
            if np.linalg.norm(step) < 1e-10:
                break
        else:
            lam *= 10
            if lam > 1e8:
                break
    return poses, {"iterations": it, "cost": cost, "free_rooms": free}


@dataclass
class GraphResult:
    poses: dict[str, Similarity]  # registered rooms only: room metric -> global
    edges: list[dict]  # every candidate edge with its status and residuals
    loop_closures: list[dict]
    unregistered: dict[str, str]  # room id -> why
    optimisation: dict
    # placed room -> whether two independent chains of accepted registrations place it (no single one does alone)
    confirmed: dict[str, bool] = field(default_factory=dict)

    @property
    def registered(self) -> list[str]:
        return sorted(self.poses)

    def as_dict(self) -> dict:
        return {"registered": self.registered, "unregistered": self.unregistered, "edges": self.edges,
                "loop_closures": self.loop_closures, "optimisation": self.optimisation, "confirmed": self.confirmed,
                "poses": {k: v.to_dict() for k, v in sorted(self.poses.items())}}


def loop_confirmed(nodes: dict[str, RoomNode], edges: list[Edge]) -> dict[str, bool]:
    """For every room reachable from the fixed rooms over `edges`: whether it stays reachable when any ONE edge is removed,
    i.e. whether two independent chains of registrations place it, so that loop closure could have caught a wrong one. A
    room placed by a single chain is only as reliable as each registration on it: repeated structure (identical doors along
    a corridor) gives a wrong placement that passes every pairwise gate and is consistent with everything else, and only a
    second chain shows it. Fixed rooms are confirmed by definition."""
    fixed = {n for n, v in nodes.items() if v.fixed}

    def reach(used: list[Edge]) -> set[str]:
        seen, frontier = set(fixed), list(fixed)
        while frontier:
            r = frontier.pop()
            for e in used:
                for a, b in ((e.source, e.target), (e.target, e.source)):
                    if a == r and b not in seen:
                        seen.add(b)
                        frontier.append(b)
        return seen

    placed = reach(edges)
    confirmed = {r: r in fixed for r in placed}
    for r in placed - fixed:
        confirmed[r] = all(r in reach([x for x in edges if x is not e]) for e in edges)
    return confirmed


def solve_room_graph(nodes: dict[str, RoomNode], edges: list[Edge], *, gates: RoomGates, gravity_aligned: bool = False,
                     estimate_scale: bool = False, sigma_m: float = 0.01) -> GraphResult:
    """Spanning tree, loop-closure check, robust optimisation and consistency-driven edge rejection (see the module
    docstring). Fixed rooms keep their poses; every other room is either placed consistently or reported unregistered."""
    if not any(n.fixed for n in nodes.values()):
        raise ValueError("at least one room must be fixed: it defines the global frame")
    active = list(edges)
    status: dict[str, str] = {e.key(): "ACCEPTED" for e in edges}
    reasons: dict[str, str] = {}
    tree_poses, _, initial_loops = spanning_tree_poses(nodes, active)
    loop_report = []
    for e in initial_loops:
        err = edge_error(e, tree_poses, nodes)
        loop_report.append({"edge": e.key(), "cycle_translation_m": err[0], "cycle_rotation_deg": err[1], "cycle_log_scale": err[2],
                            "consistent_before_optimisation": _consistent(err, gates)})

    def solve(edge_set: list[Edge]):
        init, _, _ = spanning_tree_poses(nodes, edge_set)
        poses, info = optimize_pose_graph(nodes, edge_set, init, sigma_m=sigma_m, gravity_aligned=gravity_aligned,
                                          estimate_scale=estimate_scale)
        bad = [e for e in edge_set if e.source in poses and e.target in poses and not _consistent(edge_error(e, poses, nodes), gates)]
        return poses, info, bad, global_free_space_conflicts(nodes, poses, gates.max_free_space_conflict)

    poses, info, bad, conflicts = solve(active)
    first_conflicts = conflicts
    rounds = 0
    while (bad or conflicts) and rounds < len(edges):
        rounds += 1
        # Every active edge is a suspect, not only those showing a residual now: the robust optimum can put a whole loop's
        # error on one correct edge (measured: a wrong A->B left A->B and B->C exact and A->C 0.3 m off).
        outcomes = {id(e): solve([x for x in active if x is not e]) for e in active}
        resolving = [e for e in active if not outcomes[id(e)][2] and not outcomes[id(e)][3]]
        # Among removals that each make everything consistent, the one that leaves the most rooms placed is the smallest
        # explanation (a wrong registration of the corridor explains a conflict between the corridor and room A; dropping
        # room B's registration instead would leave the corridor, placed through room B, unplaced too).
        if len(resolving) > 1:
            most = max(len(outcomes[id(e)][0]) for e in resolving)
            resolving = [e for e in resolving if len(outcomes[id(e)][0]) == most] if sum(
                len(outcomes[id(e)][0]) == most for e in resolving) == 1 else resolving
        if len(resolving) == 1:
            drop = resolving
            why = ("INCONSISTENT: removing it alone makes every loop and every room's observed free space consistent"
                   + (", and keeps the most rooms placed" if len([e for e in active if not outcomes[id(e)][2] and not outcomes[id(e)][3]]) > 1 else ""))
        elif len(resolving) > 1:
            drop = resolving
            why = (f"AMBIGUOUS: one of {len(resolving)} conflicting registrations is wrong and nothing shows which; "
                   "all are dropped")
        elif bad:
            drop = [max(bad, key=lambda e: max(a / b for a, b in zip(edge_error(e, poses, nodes),
                                                                      (gates.max_loop_translation_m, gates.max_loop_rotation_deg,
                                                                       gates.max_loop_log_scale), strict=True)))]
            why = "INCONSISTENT: largest residual among several conflicting registrations"
        else:
            # free-space conflicts no single removal explains: every registration of the conflicting rooms that are not
            # fixed is dropped, and they are reported unregistered
            suspects = {r for c in conflicts for r in c["rooms"] if not nodes[r].fixed}
            drop = [e for e in active if e.source in suspects or e.target in suspects]
            why = "AMBIGUOUS: the placed rooms contradict each other's observed free space and no single registration explains it"
        for e in drop:
            status[e.key()] = why.split(":")[0]
            reasons[e.key()] = why
        active = [x for x in active if all(x is not d for d in drop)]
        poses, info, bad, conflicts = solve(active)

    edge_rows = []
    for e in edges:
        row = {"edge": e.key(), "source": e.source, "target": e.target, "weight": e.weight, "status": status[e.key()],
               "reason": reasons.get(e.key())}
        if e.source in poses and e.target in poses:
            t, r, s = edge_error(e, poses, nodes)
            row.update(residual_translation_m=t, residual_rotation_deg=r, residual_log_scale=s)
        edge_rows.append(row)
    for row in loop_report:
        row["status_after"] = status[row["edge"]]
    unregistered = {}
    for n in nodes:
        if n not in poses:
            dropped = [e.key() for e in edges if n in (e.source, e.target) and status[e.key()] != "ACCEPTED"]
            unregistered[n] = (f"no consistent registration links it to the fixed rooms (dropped: {', '.join(dropped)})" if dropped
                               else "no accepted registration links it to the fixed rooms")
    info = {**info, "rejection_rounds": rounds, "sigma_m": sigma_m, "gravity_aligned": gravity_aligned, "estimate_scale": estimate_scale,
            "free_space_conflicts_found": first_conflicts, "free_space_conflicts_left": conflicts,
            "limits": {"translation_m": gates.max_loop_translation_m, "rotation_deg": gates.max_loop_rotation_deg,
                       "log_scale": gates.max_loop_log_scale}}
    kept = [e for e in edges if status[e.key()] == "ACCEPTED" and e.source in poses and e.target in poses]
    return GraphResult({k: v for k, v in poses.items()}, edge_rows, loop_report, unregistered, info, loop_confirmed(nodes, kept))


def room_node(room_id: str, points_m: np.ndarray, *, fixed: bool = False, pose: Similarity | None = None,
              floor: FloorGrid | None = None, voxel_m: float = 0.05) -> RoomNode:
    """A room of the graph. With its `floor` (FloorGrid of its own points and labels) the graph also checks every pair of
    placed rooms for free-space conflicts, not only the registered pairs (solve_room_graph)."""
    p = np.asarray(points_m, dtype=np.float64)
    c = p.mean(axis=0)
    return RoomNode(room_id, c, float(np.sqrt(np.mean(np.sum((p - c) ** 2, axis=1)))), fixed, pose,
                    voxel_downsample(p, voxel_m) if floor is not None else None, floor)


def global_free_space_conflicts(nodes: dict[str, RoomNode], poses: dict[str, Similarity], limit: float) -> list[dict]:
    """Every pair of PLACED rooms (registered to each other or not) whose placements put one where the other capture saw
    empty space (free_space_conflict, both ways), above `limit`. A pairwise registration only checks the two rooms it
    joins; a room placed through a chain of others can still be placed where a third saw nothing (measured with real FPFH
    on the synthetic floor: the corridor placed 5 m off through room B, overlapping nothing room B saw, stood in room A's
    free space). Rooms without points or floor are not checked."""
    out = []
    placed = [r for r in sorted(poses) if nodes[r].points is not None and nodes[r].floor is not None]
    for i, a in enumerate(placed):
        for b in placed[i + 1:]:
            T = poses[b].inverse().compose(poses[a])  # a's frame -> b's frame
            na, nb = nodes[a], nodes[b]
            ab = free_space_conflict(nb.floor, cKDTree(nb.points), T.apply(na.points))
            ba = free_space_conflict(na.floor, cKDTree(na.points), T.inverse().apply(nb.points))
            worst = max(ab["fraction"], ba["fraction"])
            if worst > limit:
                out.append({"rooms": [a, b], "fraction": worst, f"{a}_in_{b}": ab, f"{b}_in_{a}": ba})
    return out
