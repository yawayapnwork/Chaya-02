"""Similarity registration between two point clouds in metres: estimation, refinement and physically meaningful quality
metrics. Pure numpy + scipy, no Open3D. chaya_worker.region_alignment builds the rescan alignment on top of this.

A similarity is x -> s R x + t (chaya_worker.frames.Similarity). Two independent SfM reconstructions differ by exactly
such a transform, including the scale, so a rigid-only estimate cannot be right in general. Every estimate here
includes the scale. Callers bound how far it may move from 1 (the region has already been made metric by its own
calibration).

  * `umeyama`: the closed-form least-squares similarity between corresponding point sets (Umeyama 1991), with the
    reflection guard.
  * `ransac_similarity`: robust estimation from putative correspondences, many of them wrong (feature matches). It uses
    minimal 3-point samples, rejects hypotheses whose scale is outside the prior, scores by inlier count, and refits on
    the consensus set.
  * `icp_similarity`: point-to-point ICP with scale, coarse to fine over a schedule of correspondence distances,
    starting from an initial similarity.
  * `alignment_metrics` / `evaluate_gates`: the measured quality of a final alignment, and the gates it must pass.
"""

from __future__ import annotations

from dataclasses import asdict, dataclass, field

import numpy as np
from scipy.spatial import cKDTree

from .frames import Similarity


class RegistrationError(ValueError):
    """Registration could not produce an estimate: too few correspondences, degenerate geometry, or no hypothesis within
    the scale prior."""


# ---- estimation --------------------------------------------------------------------------------------------------------


def umeyama(src: np.ndarray, dst: np.ndarray, *, with_scale: bool = True) -> Similarity:
    """Least-squares similarity mapping src[i] onto dst[i] (Umeyama 1991). Needs 3 or more non-collinear points."""
    src = np.asarray(src, dtype=np.float64)
    dst = np.asarray(dst, dtype=np.float64)
    if src.shape != dst.shape or src.ndim != 2 or src.shape[1] != 3 or len(src) < 3:
        raise RegistrationError("umeyama needs two (N, 3) arrays with N >= 3")
    mu_s, mu_d = src.mean(axis=0), dst.mean(axis=0)
    xs, xd = src - mu_s, dst - mu_d
    var_s = float((xs**2).sum() / len(src))
    if var_s < 1e-18:
        raise RegistrationError("the source points are all at one location")
    cov = xd.T @ xs / len(src)
    U, S, Vt = np.linalg.svd(cov)
    if S[1] < 1e-12 * max(S[0], 1e-300):
        raise RegistrationError("the correspondences are collinear; rotation is undetermined")
    D = np.eye(3)
    if np.linalg.det(U) * np.linalg.det(Vt) < 0:
        D[2, 2] = -1.0  # never return a reflection: a mirrored venue is not a valid alignment
    R = U @ D @ Vt
    s = float(np.trace(np.diag(S) @ D) / var_s) if with_scale else 1.0
    if s <= 0:
        raise RegistrationError("degenerate scale estimate")
    return Similarity(s, R, mu_d - s * R @ mu_s)


@dataclass
class RansacResult:
    transform: Similarity
    inliers: np.ndarray  # (N,) bool over the putative correspondences
    hypotheses_tested: int
    hypotheses_rejected_by_scale: int

    @property
    def inlier_count(self) -> int:
        return int(self.inliers.sum())

    @property
    def inlier_ratio(self) -> float:
        return float(self.inliers.mean()) if len(self.inliers) else 0.0


def ransac_similarity(src: np.ndarray, dst: np.ndarray, *, inlier_threshold_m: float, scale_bounds: tuple[float, float],
                      iterations: int = 5000, confidence: float = 0.999, seed: int = 0) -> RansacResult:
    """Robust similarity from putative correspondences src[i] <-> dst[i], of which only some are right.

    Each hypothesis is a Umeyama fit to 3 random correspondences. A hypothesis whose scale falls outside `scale_bounds` is
    discarded before scoring: the region is already metric, so a large scale can only come from wrong matches. The best
    hypothesis (most correspondences within `inlier_threshold_m`, ties broken by lower inlier residual) is refitted on
    its inliers until the inlier set stops changing. The iteration count adapts to the best inlier ratio seen, to reach
    `confidence`."""
    src = np.asarray(src, dtype=np.float64)
    dst = np.asarray(dst, dtype=np.float64)
    n = len(src)
    if n < 3:
        raise RegistrationError(f"{n} putative correspondences; at least 3 are needed")
    rng = np.random.default_rng(seed)
    lo, hi = scale_bounds
    best: tuple[int, float] | None = None
    best_T: Similarity | None = None
    tested = rejected = 0
    needed = iterations
    it = 0
    while it < min(iterations, needed):
        it += 1
        idx = rng.choice(n, 3, replace=False)
        a, b, c = src[idx]
        if np.linalg.norm(np.cross(b - a, c - a)) < 1e-9:
            continue  # a degenerate (collinear) sample says nothing about rotation
        try:
            T = umeyama(src[idx], dst[idx])
        except RegistrationError:
            continue
        tested += 1
        if not lo <= T.scale <= hi:
            rejected += 1
            continue
        res = np.linalg.norm(T.apply(src) - dst, axis=1)
        inl = res < inlier_threshold_m
        count = int(inl.sum())
        score = (count, -float(res[inl].mean()) if count else 0.0)
        if best is None or score > best:
            best, best_T = score, T
            w = count / n
            if 0 < w < 1:
                needed = int(np.ceil(np.log(1 - confidence) / np.log(1 - w**3)))
            elif w == 1:
                needed = 0
    if best_T is None or best[0] < 3:
        raise RegistrationError(f"no hypothesis within the scale prior {scale_bounds} found 3 consistent correspondences "
                                f"({tested} tested, {rejected} rejected by scale)")
    T = best_T
    inliers = np.linalg.norm(T.apply(src) - dst, axis=1) < inlier_threshold_m
    for _ in range(10):
        refit = umeyama(src[inliers], dst[inliers])
        if not lo <= refit.scale <= hi:
            break
        new_inliers = np.linalg.norm(refit.apply(src) - dst, axis=1) < inlier_threshold_m
        T = refit
        if np.array_equal(new_inliers, inliers):
            break
        inliers = new_inliers
    return RansacResult(T, inliers, tested, rejected)


@dataclass
class Hypothesis:
    """One placement robust estimation found: the transform, the correspondences it explains, and the scale those
    correspondences support (a Umeyama fit with scale to them: evidence about two metric calibrations, never applied)."""

    transform: Similarity
    inliers: np.ndarray  # (N,) bool over the putative correspondences
    scale_estimate: float

    @property
    def inlier_count(self) -> int:
        return int(self.inliers.sum())


def _yaw_fit(p: np.ndarray, q: np.ndarray) -> Similarity:
    """Least-squares rotation about +Z and translation (scale 1) taking p onto q: 2-D Procrustes on (x, y), mean offset in z."""
    pc, qc = p.mean(axis=0), q.mean(axis=0)
    a, b = p[:, :2] - pc[:2], q[:, :2] - qc[:2]
    h = a.T @ b
    yaw = np.arctan2(h[0, 1] - h[1, 0], h[0, 0] + h[1, 1])
    c, s = np.cos(yaw), np.sin(yaw)
    R = np.array([[c, -s, 0.0], [s, c, 0.0], [0.0, 0.0, 1.0]])
    return Similarity(1.0, R, qc - R @ pc)


def ransac_yaw_hypotheses(src: np.ndarray, dst: np.ndarray, *, inlier_threshold_m: float, max_hypotheses: int = 4,
                          min_support: int = 20, iterations: int = 300000, confidence: float = 0.999, min_baseline_m: float = 0.3,
                          distinct_rotation_deg: float = 5.0, distinct_translation_m: float = 0.25, at: np.ndarray | None = None,
                          seed: int = 0, chunk: int = 400) -> tuple[list[Hypothesis], int]:
    """The strongest distinct placements of `src` onto `dst` (putative correspondences src[i] <-> dst[i], most of them
    wrong) when both are gravity-aligned: a rotation about +Z and a translation, scale 1. Returns them strongest first, and
    the number of hypotheses tested.

    Each hypothesis comes from TWO correspondences (yaw from the horizontal direction between them, translation from their
    midpoint), so with a fraction w of right matches a right sample needs ~1/w^2 tries instead of a similarity's 1/w^3: at
    the 1-5 % FPFH gives on a doorway overlap that is the difference between hundreds and tens of thousands. A pair whose
    horizontal lengths or height differences disagree by more than twice the threshold cannot be a right pair and is not
    scored.

    It samples until a placement supported by only `min_support` of the matches would have been drawn from two of its own
    with probability `confidence` (capped at `iterations`). Not until the BEST placement is found, as a single-answer RANSAC
    stops: here the best is often wrong (below), and stopping on it missed the weaker right one (measured: the corridor's
    true placement onto room A, 22 of 2,763 matches, was not sampled; the corridor was placed through room B alone, 5 m
    off).

    Several placements are kept, not one, because the strongest is not necessarily right: two box-shaped rooms explain more
    matches laid over each other (wall on wall) than joined through their doorway. The caller verifies each one. Two
    placements are distinct when their rotations differ by more than `distinct_rotation_deg` or they move `at` (default: the
    source centroid) more than `distinct_translation_m` apart."""
    src = np.asarray(src, dtype=np.float64)
    dst = np.asarray(dst, dtype=np.float64)
    n = len(src)
    if n < 2:
        raise RegistrationError(f"{n} putative correspondences; at least 2 are needed")
    rng = np.random.default_rng(seed)
    thr2 = inlier_threshold_m**2
    yaws, trans, counts = [], [], []
    w_min = min(1.0, max(min_support, 2) / n)
    needed = 1 if w_min >= 1 else int(np.ceil(np.log(1 - confidence) / np.log(1 - w_min * w_min)))
    tested, best = 0, 0
    while tested < min(iterations, needed):
        k = min(chunk, iterations - tested)
        tested += k
        i, j = rng.integers(0, n, k), rng.integers(0, n, k)
        dp, dq = src[j] - src[i], dst[j] - dst[i]
        lp, lq = np.linalg.norm(dp[:, :2], axis=1), np.linalg.norm(dq[:, :2], axis=1)
        ok = (i != j) & (lp > min_baseline_m) & (np.abs(lp - lq) < 2 * inlier_threshold_m) & (np.abs(dp[:, 2] - dq[:, 2]) < 2 * inlier_threshold_m)
        if not ok.any():
            continue
        i, j, dp, dq = i[ok], j[ok], dp[ok], dq[ok]
        yaw = np.arctan2(dq[:, 1], dq[:, 0]) - np.arctan2(dp[:, 1], dp[:, 0])
        c, s = np.cos(yaw), np.sin(yaw)
        pm, qm = (src[i] + src[j]) / 2, (dst[i] + dst[j]) / 2
        t = qm - np.stack([c * pm[:, 0] - s * pm[:, 1], s * pm[:, 0] + c * pm[:, 1], pm[:, 2]], axis=1)
        rx = c[:, None] * src[None, :, 0] - s[:, None] * src[None, :, 1] + t[:, 0, None] - dst[None, :, 0]
        ry = s[:, None] * src[None, :, 0] + c[:, None] * src[None, :, 1] + t[:, 1, None] - dst[None, :, 1]
        rz = src[None, :, 2] + t[:, 2, None] - dst[None, :, 2]
        cnt = (rx * rx + ry * ry + rz * rz < thr2).sum(axis=1)
        yaws.append(yaw)
        trans.append(t)
        counts.append(cnt)
        best = max(best, int(cnt.max()))
    if not counts or best < 2:
        raise RegistrationError(f"no pair of correspondences agrees on a placement ({tested} hypotheses tested)")
    yaw_all, t_all, cnt_all = np.concatenate(yaws), np.concatenate(trans), np.concatenate(counts)
    at = src.mean(axis=0) if at is None else np.asarray(at, dtype=np.float64)
    # Non-maximum suppression over every sampled hypothesis with any support: the strongest, then the strongest not within
    # the distinct thresholds of one taken, and so on. Not the top N by support: the strongest placement's near-duplicates
    # fill any top N, and the weaker right placement would never be looked at.
    keep = cnt_all >= 2
    yaw_all, t_all, cnt_all = yaw_all[keep], t_all[keep], cnt_all[keep]
    c_all, s_all = np.cos(yaw_all), np.sin(yaw_all)
    moved_at = np.stack([c_all * at[0] - s_all * at[1], s_all * at[0] + c_all * at[1], np.full_like(c_all, at[2])], axis=1) + t_all
    alive = np.ones(len(cnt_all), dtype=bool)
    chosen: list[Hypothesis] = []
    while alive.any() and len(chosen) < max_hypotheses:
        idx = int(np.flatnonzero(alive)[np.argmax(cnt_all[alive])])
        dyaw = np.degrees(np.abs(np.angle(np.exp(1j * (yaw_all - yaw_all[idx])))))
        alive &= ~((dyaw <= distinct_rotation_deg) & (np.linalg.norm(moved_at - moved_at[idx], axis=1) <= distinct_translation_m))
        T = Similarity(1.0, np.array([[c_all[idx], -s_all[idx], 0.0], [s_all[idx], c_all[idx], 0.0], [0.0, 0.0, 1.0]]), t_all[idx])
        inliers = np.linalg.norm(T.apply(src) - dst, axis=1) < inlier_threshold_m
        for _ in range(10):  # refit on the inliers until they stop changing
            if inliers.sum() < 2:
                break
            refit = _yaw_fit(src[inliers], dst[inliers])
            again = np.linalg.norm(refit.apply(src) - dst, axis=1) < inlier_threshold_m
            T = refit
            if np.array_equal(again, inliers):
                break
            inliers = again
        if any(rotation_angle_degrees(T.rotation @ h.transform.rotation.T) <= distinct_rotation_deg
               and np.linalg.norm(T.apply(at) - h.transform.apply(at)) <= distinct_translation_m for h in chosen):
            continue  # the refit converged onto a placement already taken
        try:
            scale = umeyama(src[inliers], dst[inliers]).scale if inliers.sum() >= 3 else 1.0
        except RegistrationError:
            scale = 1.0
        chosen.append(Hypothesis(T, inliers, float(scale)))
    chosen.sort(key=lambda h: -h.inlier_count)
    return chosen, tested


@dataclass
class IcpResult:
    transform: Similarity
    iterations: int
    schedule_m: list[float]


def icp_similarity(src: np.ndarray, dst: np.ndarray, init: Similarity, *, schedule_m: list[float], with_scale: bool = True,
                   max_iterations_per_stage: int = 30, tolerance: float = 1e-7, dst_tree: cKDTree | None = None) -> IcpResult:
    """Point-to-point ICP with scale, coarse to fine: for each correspondence distance in `schedule_m` (largest first),
    alternate nearest-neighbour matching within that distance with a Umeyama update, until the update is smaller than
    `tolerance`."""
    src = np.asarray(src, dtype=np.float64)
    dst = np.asarray(dst, dtype=np.float64)
    tree = dst_tree or cKDTree(dst)
    T = init
    total = 0
    for max_d in schedule_m:
        for _ in range(max_iterations_per_stage):
            total += 1
            moved = T.apply(src)
            d, j = tree.query(moved, distance_upper_bound=max_d)
            m = np.isfinite(d)
            if m.sum() < 3:
                raise RegistrationError(f"ICP found {int(m.sum())} correspondences within {max_d} m; the clouds do not overlap")
            try:
                step = umeyama(moved[m], dst[j[m]], with_scale=with_scale)
            except RegistrationError as exc:
                raise RegistrationError(f"ICP update is degenerate: {exc}") from exc
            T = step.compose(T)
            change = abs(step.scale - 1) + np.linalg.norm(step.rotation - np.eye(3)) + np.linalg.norm(step.translation) / max(max_d, 1e-9)
            if change < tolerance:
                break
    return IcpResult(T, total, list(schedule_m))


# ---- measurement ---------------------------------------------------------------------------------------------------------


def surface_normals(points: np.ndarray, k: int = 12, tree: cKDTree | None = None) -> np.ndarray:
    """Unit normals by PCA of each point's k nearest neighbours (the eigenvector of the smallest eigenvalue). Unsigned."""
    points = np.asarray(points, dtype=np.float64)
    k = min(k, len(points))
    tree = tree or cKDTree(points)
    _, idx = tree.query(points, k=k)
    nb = points[idx] - points[idx].mean(axis=1, keepdims=True)
    cov = np.einsum("nki,nkj->nij", nb, nb)
    _, vecs = np.linalg.eigh(cov)
    return vecs[:, :, 0]


@dataclass
class AlignmentMetrics:
    """What an alignment measured, in physical units. Everything is computed on the final transform."""

    source_points_in_overlap: int  # aligned source points inside the target's extent (the ones that could correspond)
    correspondence_count: int  # of those, the ones with a target point within the final ICP distance
    inlier_ratio: float  # correspondence_count / source_points_in_overlap
    scale: float  # the transform's scale (1 = the calibrations agree exactly)
    rotation_residual_deg: float  # |omega| of the residual rigid motion between the surfaces (see residual_rigid_motion)
    translation_residual_m: float  # |delta| of the residual rigid motion between the surfaces
    surface_rms_m: float  # RMS point-to-plane distance at correspondences (noise plus misalignment)
    icp_residual_m: float  # RMS point-to-point distance at correspondences (includes ~half the sampling spacing)
    confidence: float  # inlier_ratio * max(0, 1 - translation_residual_m / max_translation_residual_m), in [0, 1]
    constraint_ratio: float = 0.0  # how well the overlap constrains its weakest rigid direction (see constraint_ratio)

    def as_dict(self) -> dict:
        return asdict(self)


def alignment_confidence(inlier_ratio: float, translation_residual_m: float, max_translation_residual_m: float) -> float:
    """The single score both the worker and the control plane gate on. It is the fraction of the overlap that found a
    correspondence, scaled down linearly as the point-to-plane residual (the offset between the two surfaces along their
    normal) approaches its limit. It is 0 at the limit, and 0 with no correspondences.

    It uses the point-to-plane residual, not the point-to-point RMS. Two different samplings of the same surface, perfectly
    aligned, are still about half a sample spacing apart point to point, but on the same plane. A score built on the
    point-to-point RMS therefore moves with the sampling (the old voxel-dependent score, docs/BENCHMARKS.md B5). This one
    does not."""
    if max_translation_residual_m <= 0:
        raise ValueError("max_translation_residual_m must be positive")
    return float(np.clip(inlier_ratio * max(0.0, 1.0 - translation_residual_m / max_translation_residual_m), 0.0, 1.0))


def alignment_metrics(src: np.ndarray, dst: np.ndarray, transform: Similarity, *, correspondence_distance_m: float,
                      max_translation_residual_m: float, overlap_min: np.ndarray | None = None, overlap_max: np.ndarray | None = None,
                      dst_tree: cKDTree | None = None, normal_k: int = 12) -> AlignmentMetrics:
    src = np.asarray(src, dtype=np.float64)
    dst = np.asarray(dst, dtype=np.float64)
    tree = dst_tree or cKDTree(dst)
    moved = transform.apply(src)
    lo = dst.min(axis=0) if overlap_min is None else overlap_min
    hi = dst.max(axis=0) if overlap_max is None else overlap_max
    in_overlap = np.all((moved >= lo) & (moved <= hi), axis=1)
    d, j = tree.query(moved, distance_upper_bound=correspondence_distance_m)
    corr = np.isfinite(d) & in_overlap
    n_overlap, n_corr = int(in_overlap.sum()), int(corr.sum())
    ratio = n_corr / n_overlap if n_overlap else 0.0
    if n_corr >= 3:
        icp_rmse = float(np.sqrt(np.mean(d[corr] ** 2)))
        n_dst = surface_normals(dst, normal_k, tree)[j[corr]]
        omega, delta, surface_rms = residual_rigid_motion(moved[corr], dst[j[corr]], n_dst)
        rot_res, trans_res = float(np.degrees(np.linalg.norm(omega))), float(np.linalg.norm(delta))
        constraint = constraint_ratio(moved[corr], n_dst)
    else:
        icp_rmse, rot_res, trans_res, surface_rms, constraint = float("inf"), 90.0, float("inf"), float("inf"), 0.0
    return AlignmentMetrics(n_overlap, n_corr, float(ratio), float(transform.scale), rot_res, trans_res, surface_rms, icp_rmse,
                            alignment_confidence(ratio, trans_res, max_translation_residual_m), constraint)


def constraint_ratio(p: np.ndarray, normals: np.ndarray) -> float:
    """How well the overlapping surfaces pin down the rigid motion, in [0, 1]: sqrt(lambda_min / lambda_max) of the
    point-to-plane information matrix sum a a^T, a = [(p - c) x n / L, n], with L the RMS lever arm, so rotation and
    translation are compared in metres (Gelfand et al. 2003, "Geometrically stable sampling for the ICP algorithm").

    residual_rigid_motion reports a direction that no surface constrains as zero residual. A floor alone cannot
    fix a horizontal slide. A corridor's floor and one wall cannot fix a slide along the wall. In such a direction the
    region may sit anywhere, and the residuals, the inlier ratio and the confidence all still look perfect. This ratio is
    the evidence that every direction was observed. It is about 0 for those cases, and about 0.5 for a room corner."""
    if len(p) < 6:
        return 0.0
    pc = p - p.mean(axis=0)
    lever = float(np.sqrt(np.mean(np.sum(pc**2, axis=1))))
    if lever <= 0:
        return 0.0
    a = np.hstack([np.cross(pc, normals) / lever, normals])
    eig = np.linalg.eigvalsh(a.T @ a / len(a))
    return float(np.sqrt(max(eig[0], 0.0) / eig[-1])) if eig[-1] > 0 else 0.0


def icp_point_to_plane(src: np.ndarray, dst: np.ndarray, init: Similarity, *, schedule_m: list[float], dst_tree: cKDTree | None = None,
                       dst_normals: np.ndarray | None = None, max_iterations_per_stage: int = 50, tolerance: float = 1e-7) -> IcpResult:
    """Rigid point-to-plane ICP, coarse to fine: for each correspondence distance in `schedule_m` (largest first), match
    within that distance and apply the rigid motion residual_rigid_motion solves (Gauss-Newton on the linearised
    point-to-plane distances, Huber-weighted), until it is smaller than `tolerance`. The scale stays as `init` has it.

    Unlike point-to-point ICP it exerts no force along a surface. Where one capture saw only part of a surface the other
    saw whole (a floor glimpsed through a door), point-to-point pulls the larger set's points beyond the patch's edge onto
    the edge, a drag along the floor that the walls cannot hold back where the floor outnumbers them (measured: a correct
    corridor overlap read 10 cm off); point-to-plane leaves a direction no surface constrains where it is."""
    src = np.asarray(src, dtype=np.float64)
    dst = np.asarray(dst, dtype=np.float64)
    tree = dst_tree or cKDTree(dst)
    normals = surface_normals(dst, 12, tree) if dst_normals is None else dst_normals
    T = init
    total = 0
    for max_d in schedule_m:
        for _ in range(max_iterations_per_stage):
            total += 1
            moved = T.apply(src)
            d, j = tree.query(moved, distance_upper_bound=max_d)
            m = np.isfinite(d)
            if m.sum() < 6:
                raise RegistrationError(f"ICP found {int(m.sum())} correspondences within {max_d} m; the clouds do not overlap")
            omega, delta, _ = residual_rigid_motion(moved[m], dst[j[m]], normals[j[m]])
            c = moved[m].mean(axis=0)
            R = _rotvec_matrix(omega)
            T = Similarity(1.0, R, c - R @ c + delta).compose(T)
            if np.linalg.norm(omega) + np.linalg.norm(delta) / max(max_d, 1e-9) < tolerance:
                break
    return IcpResult(T, total, list(schedule_m))


def _rotvec_matrix(omega: np.ndarray) -> np.ndarray:
    theta = float(np.linalg.norm(omega))
    if theta < 1e-15:
        return np.eye(3)
    k = omega / theta
    K = np.array([[0, -k[2], k[1]], [k[2], 0, -k[0]], [-k[1], k[0], 0]])
    return np.eye(3) + np.sin(theta) * K + (1 - np.cos(theta)) * K @ K


def residual_rigid_motion(p: np.ndarray, q: np.ndarray, normals: np.ndarray) -> tuple[np.ndarray, np.ndarray, float]:
    """The small rigid motion (rotation vector omega in radians, about the correspondences' centroid, and translation
    delta in metres) that would still move the aligned points `p` onto the target surfaces through `q` with `normals`.
    It is the least-squares solution of the linearised point-to-plane system n . (p - q + omega x p' + delta) = 0,
    Huber-weighted so that correspondences straddling an edge do not dominate.

    This is what "rotational / translational residual" means here: the misalignment the surfaces can observe jointly.
    Each surface constrains only the directions along its normal. A median distance over mixed surfaces misses an offset
    that only some of them observe (a 6 cm vertical offset is invisible on walls). Directions no surface constrains (a
    single plane cannot fix a sliding motion) come back as zero: they are unobservable, not measured as good."""
    c = p.mean(axis=0)
    pc = p - c
    r = np.einsum("ij,ij->i", p - q, normals)  # signed point-to-plane distances
    A = np.hstack([np.cross(pc, normals), normals])
    mad = 1.4826 * np.median(np.abs(r - np.median(r))) + 1e-6
    k = 2.5 * mad
    w = np.where(np.abs(r) <= k, 1.0, k / np.maximum(np.abs(r), 1e-12))
    sw = np.sqrt(w)
    x, *_ = np.linalg.lstsq(A * sw[:, None], -r * sw, rcond=1e-6)
    return x[:3], x[3:], float(np.sqrt(np.mean(r**2)))


# ---- gates -----------------------------------------------------------------------------------------------------------------


@dataclass(frozen=True)
class AlignmentGates:
    """Thresholds, each in the physical unit its name says. Defaults come from chaya_worker.settings."""

    min_correspondences: int = 200
    min_inlier_ratio: float = 0.5
    max_scale_correction: float = 0.10  # |scale - 1|
    max_rotation_residual_deg: float = 1.0  # 1 degree is ~5 cm at a 3 m lever arm, in line with the translation limit
    max_translation_residual_m: float = 0.03
    max_icp_residual_m: float = 0.05
    min_confidence: float = 0.6
    # every rigid direction must be observed by some overlapping surface (constraint_ratio): 0.05 separates the measured
    # degenerate overlaps (open floor 0.02, corridor floor + one wall 0.02) from constrained ones (floor + one box 0.09,
    # a room corner 0.44, a room 0.48), docs/rescan.md "QUALITY GATES"
    min_constraint_ratio: float = 0.05
    # DIRECT_CANONICAL only: two surveyed calibrations must agree, so ICP may move the region only this far
    max_direct_rotation_correction_deg: float = 5.0
    max_direct_translation_correction_m: float = 0.5
    # FEATURE_SIMILARITY only
    min_ransac_inliers: int = 20
    max_tilt_deg: float = 5.0  # when the region is gravity-aligned, the correction may not tilt it


@dataclass
class GateResult:
    name: str
    value: float | None
    threshold: float
    comparison: str  # ">=" or "<="
    passed: bool
    unit: str = ""

    def as_dict(self) -> dict:
        return asdict(self)


@dataclass
class GateReport:
    results: list[GateResult] = field(default_factory=list)

    def add(self, name: str, value: float | None, threshold: float, comparison: str, unit: str = "") -> None:
        ok = value is not None and np.isfinite(value) and (value >= threshold if comparison == ">=" else value <= threshold)
        self.results.append(GateResult(name, None if value is None else float(value), float(threshold), comparison, bool(ok), unit))

    @property
    def passed(self) -> bool:
        return all(r.passed for r in self.results)

    @property
    def failed(self) -> list[str]:
        return [r.name for r in self.results if not r.passed]

    def as_list(self) -> list[dict]:
        return [r.as_dict() for r in self.results]


def evaluate_gates(metrics: AlignmentMetrics, gates: AlignmentGates) -> GateReport:
    """The gates every alignment must pass, whatever the mode."""
    report = GateReport()
    report.add("correspondence_count", metrics.correspondence_count, gates.min_correspondences, ">=", "points")
    report.add("inlier_ratio", metrics.inlier_ratio, gates.min_inlier_ratio, ">=")
    report.add("scale_correction", abs(metrics.scale - 1.0), gates.max_scale_correction, "<=")
    report.add("rotation_residual_deg", metrics.rotation_residual_deg, gates.max_rotation_residual_deg, "<=", "deg")
    report.add("translation_residual_m", metrics.translation_residual_m, gates.max_translation_residual_m, "<=", "m")
    report.add("icp_residual_m", metrics.icp_residual_m, gates.max_icp_residual_m, "<=", "m")
    report.add("confidence", metrics.confidence, gates.min_confidence, ">=")
    report.add("constraint_ratio", metrics.constraint_ratio, gates.min_constraint_ratio, ">=")
    return report


def rotation_angle_degrees(rotation_matrix: np.ndarray) -> float:
    """Angle of a rotation's axis-angle form, in degrees."""
    trace = np.clip((np.trace(rotation_matrix) - 1.0) / 2.0, -1.0, 1.0)
    return float(np.degrees(np.arccos(trace)))


def tilt_degrees(rotation_matrix: np.ndarray) -> float:
    """How far a rotation moves the vertical (+Z): its tilt component, in degrees. 0 for a pure rotation about +Z."""
    z = np.asarray(rotation_matrix, dtype=np.float64) @ np.array([0.0, 0.0, 1.0])
    return float(np.degrees(np.arccos(np.clip(z[2], -1.0, 1.0))))


def voxel_downsample(points: np.ndarray, voxel_m: float) -> np.ndarray:
    """One point per occupied voxel (the centroid of the voxel's points). Used to bound the work, not to change what is
    measured: metrics are taken at `voxel_m` spacing."""
    points = np.asarray(points, dtype=np.float64)
    if voxel_m <= 0 or len(points) == 0:
        return points
    keys = np.floor(points / voxel_m).astype(np.int64)
    _, inv, counts = np.unique(keys, axis=0, return_inverse=True, return_counts=True)
    inv = inv.reshape(-1)
    sums = np.zeros((len(counts), 3))
    np.add.at(sums, inv, points)
    return sums / counts[:, None]
