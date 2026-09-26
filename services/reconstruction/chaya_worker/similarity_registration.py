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
    else:
        icp_rmse, rot_res, trans_res, surface_rms = float("inf"), 90.0, float("inf"), float("inf")
    return AlignmentMetrics(n_overlap, n_corr, float(ratio), float(transform.scale), rot_res, trans_res, surface_rms, icp_rmse,
                            alignment_confidence(ratio, trans_res, max_translation_residual_m))


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
