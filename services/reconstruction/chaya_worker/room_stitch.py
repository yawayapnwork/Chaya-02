"""Combining registered rooms' Gaussians into one cloud (docs/multi-room.md, "Stitching"). Pure numpy + scipy.

Every piece is already in the output frame (chaya_worker.region_splice.transform_gaussians moved it: positions s R p + t,
orientations R q, log-scales + ln s; colours are SH degree 0, which no rotation changes). Decisions are taken in canonical
metres through `to_canonical`, as the re-scan splice does.

Where rooms overlap (around doorways, along a shared hallway) both captures reconstructed the same surfaces. Keeping both
would give doubled, slightly offset surfaces. So, on a grid of `voxel_m`:

  * a voxel is **contested** when, besides the Gaussians of the piece in it, another piece has Gaussians in it or in one
    of its 26 neighbours. Not only the voxel itself: a surface lying on a voxel boundary (a floor at z = 0, with
    millimetres of noise) puts one capture's samples in one layer and the other's in the next, and a per-voxel test
    kept both copies (measured: half of the synthetic floor's overlap, with "no shared voxel left" reported);
  * an uncontested voxel keeps all its Gaussians;
  * a contested voxel is **owned** by one piece, and only the owner's Gaussians there are kept. The owner is the piece for which the voxel lies **deepest** inside what it observed (the horizontal distance
    from the voxel to the edge of that piece's observed footprint), among the pieces that observed the voxel's
    neighbourhood (3 x 3 x 3 voxels) at least half as densely as the densest one. A room's own capture saw its floor
    whole; a neighbour saw it through a door, as a window whose edge is near. (The footprint is the cells holding any of the
    piece's points, holes filled, so a cell that happens to hold no sample is not an edge.) A capture that saw a voxel more
    than twice as densely as another wins it regardless: that is a better observation. Depth changes smoothly, so owned regions are
    contiguous. Density alone does not: two captures that sampled a surface equally well split it voxel by voxel at
    random (sampling noise in a neighbourhood is ~13 %), and on the synthetic floor each room's kept floor then reached
    deep into its neighbours'. A tie in depth goes to the earlier piece (the anchor comes first).

Measured, not assumed:

  * **overlap misalignment** (before deduplication): for each pair of pieces, the rigid motion coarse-to-fine
    point-to-plane ICP still
    applies to one piece's overlap (its Gaussians within `overlap_radius_m` of the other piece) to bring it onto the other
    piece's surface: the offset of the overlap's centroid and the rotation. About the sampling noise when the
    registration is right; the error when it is not. Above `max_misalignment_m` or `max_misalignment_deg` the stitch is
    refused (StitchRejected). The overlap is chosen by distance, not by the voxels both pieces occupy: an offset larger
    than a voxel moves the offset surfaces out of the shared voxels, and a measurement on the voxels that remain shared
    sees only the surfaces parallel to the offset (it read 0.7 cm for a 10 cm slide). The median point-to-plane distance
    there is reported too;
  * **seam step** (after deduplication): where what is kept of one piece meets what is kept of another, the median
    point-to-plane step between the two on the same surface: kept points of one piece within `seam_band_m` of the other,
    paired with the other's nearest kept point within 2 x `max_seam_step_m` whose surface faces the same way. Pairing
    farther, or across orientations, measures the room's geometry instead of the seam: the two faces of a 15 cm wall, a
    jamb against the wall it meets (measured: a correct stitch read 4.2 cm). A larger step is a misregistration, which the
    misalignment measure above sees directly. A visible step is refused too;
  * **colour step**: the median per-voxel difference of the pieces' mean display colour in shared voxels. Two captures
    under different exposure meet with a visible colour seam even when the geometry is right; it is reported (and
    flagged above `colour_warning`), not refused: geometry decides whether the stitch is valid;
  * **duplicate surfaces left**: kept Gaussians with a kept Gaussian of another piece within half a voxel (only the
    ownership boundary should have any; a doubled surface would be most of the overlap).

The merged cloud is the kept Gaussians of each piece, piece after piece, so each piece is one contiguous block whose
start and count are in the report: an individual room can be cut out of the stitched cloud again.
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np
from scipy.spatial import cKDTree

from .frames import Similarity
from .ply import GaussianCloud
from .similarity_registration import (
    RegistrationError,
    constraint_ratio,
    icp_point_to_plane,
    rotation_angle_degrees,
    surface_normals,
    voxel_downsample,
)

# Point-to-point ICP converges slowly along directions few surfaces constrain (a slide along a corridor that only the door
# jambs fix): 30 iterations per stage left the synthetic corridor overlap 1.1 cm short of its minimum, 100 reach it (6 mm).
ICP_ITERATIONS = 100
FOOTPRINT_CELL_M = 0.1  # the horizontal grid a piece's observed footprint (for ownership depth) is taken on
MIN_RELATIVE_DENSITY = 0.5  # a piece observing a voxel's neighbourhood less than this fraction as densely as another never owns it

_BIAS = 1 << 20
_SHIFT_X, _SHIFT_Y = 42, 21


class StitchRejected(ValueError):
    """The rooms would not combine into a valid venue: misaligned overlap or a visible seam. `details` holds the
    measurements behind the refusal."""

    def __init__(self, message: str, *, details: dict | None = None) -> None:
        super().__init__(message)
        self.details = details or {}


@dataclass
class Piece:
    id: str
    cloud: GaussianCloud  # in the output frame
    labels: np.ndarray | None = None  # one per Gaussian, or None


@dataclass
class StitchResult:
    merged: GaussianCloud
    labels: np.ndarray | None
    blocks: list[dict]  # [{piece, start, count}] in merged order
    report: dict
    kept: list[np.ndarray] | None = None  # per piece, which of its Gaussians the merged cloud keeps (boolean masks)


def _keys(points: np.ndarray, voxel_m: float) -> np.ndarray:
    v = np.floor(points / voxel_m).astype(np.int64) + _BIAS
    return (v[:, 0] << _SHIFT_X) | (v[:, 1] << _SHIFT_Y) | v[:, 2]


_OFFSETS = np.array([(dx << _SHIFT_X) + (dy << _SHIFT_Y) + dz for dx in (-1, 0, 1) for dy in (-1, 0, 1) for dz in (-1, 0, 1)],
                    dtype=np.int64)


def _lookup(sorted_keys: np.ndarray, counts: np.ndarray, query: np.ndarray) -> np.ndarray:
    idx = np.clip(np.searchsorted(sorted_keys, query), 0, max(len(sorted_keys) - 1, 0))
    hit = (len(sorted_keys) > 0) & (sorted_keys[idx] == query) if len(sorted_keys) else np.zeros(len(query), dtype=bool)
    return np.where(hit, counts[idx] if len(counts) else 0, 0)


def _plane_distance(points: np.ndarray, surface: np.ndarray, max_d: float, k: int = 10) -> dict:
    """Median |point-to-plane| distance from `points` to the surface sampled by `surface` (normals from its
    k-neighbourhood), over the points with a surface point within `max_d`. A median alone misses an offset only some
    surfaces can see (a slide along a wall moves nothing on the floor or that wall), which is why the misalignment is the
    ICP offset (_overlap_misalignment) and this only describes the step that is visible."""
    if len(points) < 3 or len(surface) < k:
        return {"points": int(len(points)), "matched": 0, "median_m": None}
    tree = cKDTree(surface)
    normals = surface_normals(surface, k, tree)
    d, j = tree.query(points, distance_upper_bound=max_d)
    ok = np.isfinite(d)
    if ok.sum() < 3:
        return {"points": int(len(points)), "matched": int(ok.sum()), "median_m": None}
    p, q, n = points[ok], surface[j[ok]], normals[j[ok]]
    step = np.abs(np.einsum("ij,ij->i", p - q, n))
    return {"points": int(len(points)), "matched": int(ok.sum()), "median_m": float(np.median(step)),
            "p90_m": float(np.percentile(step, 90))}


def _seam_step(ka: np.ndarray, kb: np.ndarray, *, band_m: float, match_m: float, k: int = 10, min_cos: float = 0.9) -> dict:
    """The step between two kept pieces on the surfaces they share: kept points of `ka` within `band_m` of `kb`, each paired
    with its nearest `kb` point within `match_m` whose normal is within acos(min_cos) of its own; the median |distance| along
    that normal."""
    if len(ka) < k or len(kb) < k:
        return {"points": 0, "matched": 0, "median_m": None}
    ta, tb = cKDTree(ka), cKDTree(kb)
    band = np.flatnonzero(np.isfinite(tb.query(ka, distance_upper_bound=band_m)[0]))
    if len(band) < 3:
        return {"points": int(len(band)), "matched": 0, "median_m": None}
    p = ka[band]
    d, j = tb.query(p, distance_upper_bound=match_m)
    ok = np.isfinite(d)
    na = surface_normals(ka, k, ta)[band[ok]]
    nb = surface_normals(kb, k, tb)[j[ok]]
    same = np.abs(np.einsum("ij,ij->i", na, nb)) >= min_cos
    if same.sum() < 3:
        return {"points": int(len(band)), "matched": int(same.sum()), "median_m": None}
    step = np.abs(np.einsum("ij,ij->i", p[ok][same] - kb[j[ok]][same], nb[same]))
    return {"points": int(len(band)), "matched": int(same.sum()), "median_m": float(np.median(step)),
            "p90_m": float(np.percentile(step, 90))}


def _footprint_depth(canon: list[np.ndarray], shared: np.ndarray, voxel_m: float, cell_m: float = FOOTPRINT_CELL_M) -> np.ndarray:
    """(pieces, shared voxels): how far inside each piece's observed horizontal footprint each shared voxel's column lies,
    metres to the footprint's edge (Euclidean distance transform). The footprint is the cell_m cells holding any of the
    piece's points with its holes filled: a cell that happens to hold no sample, or the inside of a column, is no edge."""
    from scipy.ndimage import binary_fill_holes, distance_transform_edt  # noqa: PLC0415

    cells = [np.floor(c[:, :2] / cell_m).astype(np.int64) for c in canon]
    lo = np.min([c.min(axis=0) for c in cells if len(c)], axis=0) - 1
    hi = np.max([c.max(axis=0) for c in cells if len(c)], axis=0) + 1
    vx = (shared >> _SHIFT_X) - _BIAS
    vy = ((shared >> _SHIFT_Y) & ((1 << (_SHIFT_X - _SHIFT_Y)) - 1)) - _BIAS
    ix = np.floor((vx + 0.5) * voxel_m / cell_m).astype(np.int64) - lo[0]
    iy = np.floor((vy + 0.5) * voxel_m / cell_m).astype(np.int64) - lo[1]
    out = np.zeros((len(canon), len(shared)))
    for i, c in enumerate(cells):
        occupied = np.zeros(tuple(hi - lo + 1), dtype=bool)
        if len(c):
            occupied[c[:, 0] - lo[0], c[:, 1] - lo[1]] = True
        out[i] = (distance_transform_edt(binary_fill_holes(occupied)) * cell_m)[ix, iy]
    return out


def _overlap_misalignment(a: np.ndarray, b: np.ndarray, *, radius_m: float, schedule_m: list[float], voxel_m: float,
                          min_points: int = 50) -> dict | None:
    """How far piece `a`'s overlap with piece `b` (a's points within `radius_m` of b) sits from b's surface: the rigid
    motion coarse-to-fine ICP finds from where a is now. None when they do not overlap."""
    a_down, b_down = voxel_downsample(a, voxel_m), voxel_downsample(b, voxel_m)
    if len(a_down) < min_points or len(b_down) < min_points:
        return None
    tree = cKDTree(b_down)
    near = np.isfinite(tree.query(a_down, distance_upper_bound=radius_m)[0])
    if near.sum() < min_points:
        return None
    p = a_down[near]
    fine = schedule_m[-1]
    out = _plane_distance(p, b_down, max_d=fine)
    out["overlap_points"] = int(near.sum())
    try:
        T = icp_point_to_plane(p, b_down, Similarity.identity(), schedule_m=schedule_m, dst_tree=tree,
                               max_iterations_per_stage=ICP_ITERATIONS).transform
    except RegistrationError as exc:
        return {**out, "offset_m": None, "rotation_deg": None, "error": str(exc)}
    c = p.mean(axis=0)
    d, j = tree.query(T.apply(p), distance_upper_bound=fine)
    ok = np.isfinite(d)
    constraint = constraint_ratio(T.apply(p)[ok], surface_normals(b_down, 10, tree)[j[ok]]) if ok.sum() >= 6 else 0.0
    return {**out, "offset_m": float(np.linalg.norm(T.apply(c) - c)), "rotation_deg": rotation_angle_degrees(T.rotation),
            "constraint_ratio": constraint}


def floor_footprint(points_canonical: np.ndarray, labels: np.ndarray | None, cell_m: float, ground_labels) -> list[list[int]]:
    """The horizontal cells (cell_m grid, canonical) holding floor Gaussians: where the room can be walked. Without labels,
    the lowest 15 cm of the room stands in for the floor."""
    p = np.asarray(points_canonical, dtype=np.float64)
    if labels is not None and len(labels) == len(p):
        mask = np.isin(np.asarray(labels, dtype=object), list(ground_labels))
    else:
        mask = p[:, 2] <= np.percentile(p[:, 2], 2) + 0.15 if len(p) else np.zeros(0, dtype=bool)
    cells = np.unique(np.floor(p[mask, :2] / cell_m).astype(np.int64), axis=0)
    return cells.tolist()


def stitch_pieces(pieces: list[Piece], to_canonical: Similarity, *, voxel_m: float, max_misalignment_m: float, max_seam_step_m: float,
                  seam_band_m: float, overlap_radius_m: float = 0.25, icp_schedule_m: list[float] | None = None,
                  icp_voxel_m: float = 0.02, max_misalignment_deg: float = 1.0, colour_warning: float = 0.15) -> StitchResult:
    """Combines `pieces` (see the module docstring). Raises StitchRejected on a misaligned overlap or a visible seam."""
    if len(pieces) < 2:
        raise StitchRejected("stitching needs at least two pieces")
    if len({p.id for p in pieces}) != len(pieces):
        raise StitchRejected("every piece must have its own id")
    schedule = icp_schedule_m or [overlap_radius_m, 0.1, 0.05]
    canon = [to_canonical.apply(p.cloud.positions) for p in pieces]
    keys = [_keys(c, voxel_m) for c in canon]
    uniq = []
    for k in keys:
        u, c = np.unique(k, return_counts=True)
        uniq.append((u, c))
    all_voxels = np.unique(np.concatenate([u for u, _ in uniq]))

    # every piece's density in the 3 x 3 x 3 neighbourhood of every occupied voxel; contested where two pieces have some
    density = np.zeros((len(pieces), len(all_voxels)), dtype=np.int64)
    for i, (u, c) in enumerate(uniq):
        for off in _OFFSETS:
            density[i] += _lookup(u, c, all_voxels + off)
    contested = (density > 0).sum(axis=0) >= 2
    shared, density = all_voxels[contested], density[:, contested]
    near = density > 0
    present = np.stack([_lookup(u, c, shared) > 0 for u, c in uniq]) if len(shared) else np.zeros((len(pieces), 0), dtype=bool)
    depth = _footprint_depth(canon, shared, voxel_m) if len(shared) else np.zeros((len(pieces), 0))
    contenders = near & (density >= MIN_RELATIVE_DENSITY * density.max(axis=0, initial=0))
    owner = np.argmax(np.where(contenders, depth, -1.0), axis=0) if len(shared) else np.zeros(0, dtype=int)  # first on ties

    pairs = []
    for a in range(len(pieces)):
        for b in range(a + 1, len(pieces)):
            misalign = _overlap_misalignment(canon[a], canon[b], radius_m=overlap_radius_m, schedule_m=schedule, voxel_m=icp_voxel_m)
            both = near[a] & near[b]
            if misalign is None and not both.any():
                continue  # these two pieces do not overlap
            voxels = shared[present[a] & present[b]]
            in_a, in_b = np.isin(keys[a], voxels), np.isin(keys[b], voxels)
            colour = _colour_step(pieces[a], pieces[b], keys[a], keys[b], voxels, in_a, in_b)
            pairs.append({"pieces": [pieces[a].id, pieces[b].id], "contested_voxels": int(both.sum()),
                          "gaussians_in_shared": [int(in_a.sum()), int(in_b.sum())], "misalignment": misalign,
                          "colour_step": colour, "colour_seam_visible": colour is not None and colour > colour_warning,
                          "owned_voxels": [int((owner[both] == a).sum()), int((owner[both] == b).sum())]})

    def misaligned(m: dict | None) -> str | None:
        if m is None:
            return None  # a few shared voxels but no overlap to measure on: nothing to verify, and nothing doubled
        if m.get("offset_m") is None:
            return f"their overlap could not be brought onto one surface ({m.get('error')})"
        if m["offset_m"] > max_misalignment_m:
            return f"their shared surfaces are {m['offset_m']:.3f} m apart (limit {max_misalignment_m} m)"
        if m["rotation_deg"] > max_misalignment_deg:
            return f"their shared surfaces are rotated {m['rotation_deg']:.2f} degrees apart (limit {max_misalignment_deg})"
        return None

    for p in pairs:
        p["misaligned"] = misaligned(p["misalignment"])
    bad = [p for p in pairs if p["misaligned"]]
    if bad:
        worst = max(bad, key=lambda p: (p["misalignment"] or {}).get("offset_m") or float("inf"))
        raise StitchRejected(f"rooms {worst['pieces'][0]} and {worst['pieces'][1]}: {worst['misaligned']}. The registration "
                             "is wrong, and stitching would double every surface they share",
                             details={"pairs": pairs, "limits": {"max_misalignment_m": max_misalignment_m,
                                                                 "max_misalignment_deg": max_misalignment_deg}})

    # keep: a Gaussian survives unless its voxel is shared and owned by another piece
    kept = []
    for i, k in enumerate(keys):
        pos = np.searchsorted(shared, k)
        pos = np.clip(pos, 0, max(len(shared) - 1, 0))
        is_shared = (len(shared) > 0) & (shared[pos] == k) if len(shared) else np.zeros(len(k), dtype=bool)
        keep = ~is_shared | (owner[pos] == i) if len(shared) else np.ones(len(k), dtype=bool)
        kept.append(keep)

    seams = []
    for p in pairs:
        a, b = (next(i for i, x in enumerate(pieces) if x.id == pid) for pid in p["pieces"])
        ka, kb = canon[a][kept[a]], canon[b][kept[b]]
        if len(ka) < 3 or len(kb) < 3:
            continue
        # the visible step only: ICP over a thin seam band slides along whatever the band does not constrain (it reported
        # 8 cm for a correct stitch), so the rigid offset is measured on the whole shared overlap above, not here
        step = _seam_step(ka, kb, band_m=seam_band_m, match_m=2 * max_seam_step_m)
        seams.append({"pieces": p["pieces"], "band_m": seam_band_m, "match_m": 2 * max_seam_step_m, **step})
    visible = [s for s in seams if s["median_m"] is not None and s["median_m"] > max_seam_step_m]
    if visible:
        worst = max(visible, key=lambda s: s["median_m"])
        raise StitchRejected(f"rooms {worst['pieces'][0]} and {worst['pieces'][1]} meet with a median step of "
                             f"{worst['median_m']:.3f} m at their seam (limit {max_seam_step_m} m)",
                             details={"pairs": pairs, "seams": seams, "limits": {"max_seam_step_m": max_seam_step_m}})

    blocks, start = [], 0
    parts = []
    for p, keep in zip(pieces, kept, strict=True):
        parts.append(p.cloud.subset(keep))
        blocks.append({"piece": p.id, "start": start, "count": int(keep.sum()), "input": len(p.cloud),
                       "removed_as_duplicate": int((~keep).sum())})
        start += int(keep.sum())
    merged = GaussianCloud(*(np.concatenate([getattr(c, f) for c in parts]) for f in
                             ("positions", "scales_log", "rotations_wxyz", "opacity_logit", "colors_dc")))
    labels = None
    if all(p.labels is not None for p in pieces):
        labels = np.concatenate([np.asarray(p.labels, dtype=object)[keep] for p, keep in zip(pieces, kept, strict=True)])

    duplicates = 0
    for p in pairs:
        a, b = (next(i for i, x in enumerate(pieces) if x.id == pid) for pid in p["pieces"])
        ka, kb = canon[a][kept[a]], canon[b][kept[b]]
        p["duplicates_after"] = int(np.isfinite(cKDTree(kb).query(ka, distance_upper_bound=voxel_m / 2)[0]).sum()) if len(ka) and len(kb) else 0
        duplicates += p["duplicates_after"]
    report = {
        "voxel_m": voxel_m, "pieces": [p.id for p in pieces], "blocks": blocks, "total_after": len(merged),
        "contested_voxels": int(len(shared)), "pairs": pairs, "seams": seams,
        "duplicates_after": duplicates, "duplicate_fraction_after": duplicates / max(len(merged), 1),
        "limits": {"max_misalignment_m": max_misalignment_m, "max_misalignment_deg": max_misalignment_deg,
                   "max_seam_step_m": max_seam_step_m, "colour_warning": colour_warning},
        "overlap": {"radius_m": overlap_radius_m, "icp_schedule_m": list(schedule), "icp_voxel_m": icp_voxel_m},
        "colour_seams_visible": [p["pieces"] for p in pairs if p["colour_seam_visible"]],
        "ownership": (f"a voxel is contested when another piece has Gaussians in its 3 x 3 x 3 neighbourhood; among the pieces observing it at least {MIN_RELATIVE_DENSITY:.0%} as densely as the "
                      "densest, the one the voxel lies deepest inside (horizontal distance to the edge of its observed "
                      "footprint); then the earlier piece"),
    }
    return StitchResult(merged, labels, blocks, report, kept)


def _colour_step(pa: Piece, pb: Piece, ka, kb, voxels, in_a, in_b) -> float | None:
    """Median over shared voxels of the largest per-channel difference between the two pieces' mean display colours."""
    if not in_a.any() or not in_b.any():
        return None

    def means(piece, k, mask):
        rgb = piece.cloud.colors_rgb01()[mask]
        idx = np.searchsorted(voxels, k[mask])
        sums = np.zeros((len(voxels), 3))
        np.add.at(sums, idx, rgb)
        n = np.bincount(idx, minlength=len(voxels))
        return sums, n

    sa, na = means(pa, ka, in_a)
    sb, nb = means(pb, kb, in_b)
    ok = (na > 0) & (nb > 0)
    if not ok.any():
        return None
    diff = np.abs(sa[ok] / na[ok, None] - sb[ok] / nb[ok, None]).max(axis=1)
    return float(np.median(diff))
