"""A controlled, SYNTHETIC indoor scene with ground truth for every point, for comparing cleanup methods where no real
labelled splat exists (benchmarks/b2_geometry_cleanup/synthetic.py). Pure numpy; deterministic per seed.

Not a reconstruction and not evidence about real splats: point layout, opacities and noise are ASSUMPTIONS (SCENE_PARAMS),
chosen to contain the cases the pipeline must handle -- two floor levels, stairs, a ramp, thin furniture (table legs, a
bannister), dense small clutter on a table, and three kinds of noise (isolated floaters, a near-surface halo, small
floater clusters). Every number a benchmark reports on it is about this scene only.

Ground truth per point (`truth`): floor, upper_floor, ramp, stairs, wall, furniture, thin_furniture, clutter,
floater, halo, floater_cluster. `bucket` gives the runtime class each structure point should carry
(chaya_worker.semantic_classes; ramp and upper_floor are floor).
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np

SCENE_PARAMS = {
    "room_m": [8.0, 6.0, 3.0], "spacing_m": 0.03, "jitter_m": 0.004,
    "mezzanine": {"x": [5.5, 8.0], "y": [0.0, 2.5], "z": 1.02},
    "stairs": {"steps": 6, "rise_m": 0.17, "tread_m": 0.28, "y": [0.0, 1.2]},
    "ramp": {"x": [1.0, 4.0], "y": [4.5, 5.5], "slope": 1 / 12, "dais_z": 0.25},
    "floater_fraction": 0.015, "halo_fraction": 0.01, "floater_clusters": 40, "points_per_cluster": [4, 10],
    "surface_opacity": "90% U(0.5, 1.0), 10% U(0.01, 0.08) (semi-transparent detail)",
    "noise_opacity": "60% U(0.005, 0.06), 40% U(0.1, 0.9)",
}
STRUCTURE = ("floor", "upper_floor", "ramp", "stairs", "wall", "furniture", "thin_furniture", "clutter")
NOISE = ("floater", "halo", "floater_cluster")
BUCKET = {"floor": "floor", "upper_floor": "floor", "ramp": "floor", "stairs": "stairs", "wall": "wall", "furniture": "furniture",
          "thin_furniture": "furniture", "clutter": "clutter"}


@dataclass
class Scene:
    positions: np.ndarray  # (N, 3) metres, +Z up
    opacity: np.ndarray  # (N,) sigmoid opacity in (0, 1)
    truth: np.ndarray  # (N,) object: ground-truth part name
    normals: np.ndarray  # (N, 3) surface normal (noise points: zero)


def _plane(rng, origin, u, v, nu, nv, normal, spacing, jitter):
    a = np.arange(0, nu, spacing) + spacing / 2
    b = np.arange(0, nv, spacing) + spacing / 2
    aa, bb = np.meshgrid(a, b, indexing="ij")
    p = np.asarray(origin) + aa.reshape(-1, 1) * np.asarray(u) + bb.reshape(-1, 1) * np.asarray(v)
    p = p + rng.normal(0, jitter, p.shape)
    return p, np.tile(np.asarray(normal, dtype=float), (len(p), 1))


def _box(rng, lo, hi, spacing, jitter, sides=("top", "x0", "x1", "y0", "y1")):
    lo, hi = np.asarray(lo, float), np.asarray(hi, float)
    d = hi - lo
    parts = []
    if "top" in sides:
        parts.append(_plane(rng, [lo[0], lo[1], hi[2]], [1, 0, 0], [0, 1, 0], d[0], d[1], [0, 0, 1], spacing, jitter))
    if "x0" in sides:
        parts.append(_plane(rng, lo, [0, 1, 0], [0, 0, 1], d[1], d[2], [-1, 0, 0], spacing, jitter))
    if "x1" in sides:
        parts.append(_plane(rng, [hi[0], lo[1], lo[2]], [0, 1, 0], [0, 0, 1], d[1], d[2], [1, 0, 0], spacing, jitter))
    if "y0" in sides:
        parts.append(_plane(rng, lo, [1, 0, 0], [0, 0, 1], d[0], d[2], [0, -1, 0], spacing, jitter))
    if "y1" in sides:
        parts.append(_plane(rng, [lo[0], hi[1], lo[2]], [1, 0, 0], [0, 0, 1], d[0], d[2], [0, 1, 0], spacing, jitter))
    return np.concatenate([p for p, _ in parts]), np.concatenate([n for _, n in parts])


def _line(rng, a, b, spacing, jitter, radius=0.015):
    a, b = np.asarray(a, float), np.asarray(b, float)
    n = max(2, int(np.linalg.norm(b - a) / (spacing / 2)))
    t = np.linspace(0, 1, n).reshape(-1, 1)
    p = a + t * (b - a)
    ang = rng.uniform(0, 2 * np.pi, n)
    offset = np.stack([np.cos(ang), np.sin(ang), np.zeros(n)], axis=1) * radius
    return p + offset + rng.normal(0, jitter, p.shape), offset / radius


def build(seed: int = 0) -> Scene:
    rng = np.random.default_rng(seed)
    P = SCENE_PARAMS
    sp, jit = P["spacing_m"], P["jitter_m"]
    lx, ly, lz = P["room_m"]
    parts: list[tuple[np.ndarray, np.ndarray, str]] = []

    def add(pn, name):
        parts.append((pn[0], pn[1], name))

    # floor, with holes under nothing; walls and ceiling
    add(_plane(rng, [0, 0, 0], [1, 0, 0], [0, 1, 0], lx, ly, [0, 0, 1], sp, jit), "floor")
    add(_plane(rng, [0, 0, lz], [1, 0, 0], [0, 1, 0], lx, ly, [0, 0, -1], sp * 1.5, jit), "wall")
    for origin, u, n_len, normal in (([0, 0, 0], [1, 0, 0], lx, [0, 1, 0]), ([0, ly, 0], [1, 0, 0], lx, [0, -1, 0]),
                                     ([0, 0, 0], [0, 1, 0], ly, [1, 0, 0]), ([lx, 0, 0], [0, 1, 0], ly, [-1, 0, 0])):
        add(_plane(rng, origin, u, [0, 0, 1], n_len, lz, normal, sp * 1.5, jit), "wall")
    # mezzanine (upper level) and its front face
    m = P["mezzanine"]
    add(_plane(rng, [m["x"][0], m["y"][0], m["z"]], [1, 0, 0], [0, 1, 0], m["x"][1] - m["x"][0], m["y"][1] - m["y"][0],
               [0, 0, 1], sp, jit), "upper_floor")
    add(_plane(rng, [m["x"][0], m["y"][1], 0], [1, 0, 0], [0, 0, 1], m["x"][1] - m["x"][0], m["z"], [0, 1, 0], sp, jit), "wall")
    # stairs up to the mezzanine: treads and risers
    st = P["stairs"]
    x_end = m["x"][0]
    for k in range(st["steps"]):
        x0 = x_end - (st["steps"] - k) * st["tread_m"]
        z = (k + 1) * st["rise_m"]
        add(_plane(rng, [x0, st["y"][0], z], [1, 0, 0], [0, 1, 0], st["tread_m"], st["y"][1] - st["y"][0], [0, 0, 1], sp, jit), "stairs")
        add(_plane(rng, [x0, st["y"][0], z - st["rise_m"]], [0, 1, 0], [0, 0, 1], st["y"][1] - st["y"][0], st["rise_m"], [-1, 0, 0],
                   sp, jit), "stairs")
    # bannister along the mezzanine edge: a rail and balusters (thin furniture)
    rail_z = m["z"] + 1.0
    rp, rn = _line(rng, [m["x"][0], m["y"][1], rail_z], [m["x"][1], m["y"][1], rail_z], sp, jit)
    parts.append((rp, rn, "thin_furniture"))
    for x in np.arange(m["x"][0], m["x"][1], 0.3):
        bp, bn = _line(rng, [x, m["y"][1], m["z"]], [x, m["y"][1], rail_z], sp, jit, radius=0.01)
        parts.append((bp, bn, "thin_furniture"))
    # ramp (1:12) onto a dais
    r = P["ramp"]
    length = r["x"][1] - r["x"][0]
    rp, rn = _plane(rng, [r["x"][0], r["y"][0], 0], [1, 0, r["slope"]], [0, 1, 0], length, r["y"][1] - r["y"][0],
                    [-r["slope"], 0, 1], sp, jit)
    parts.append((rp, rn, "ramp"))
    add(_plane(rng, [r["x"][1], r["y"][0], r["dais_z"]], [1, 0, 0], [0, 1, 0], 2.0, ly - r["y"][0], [0, 0, 1], sp, jit), "floor")
    # furniture: a table (top + thin legs), a sofa block, a shelf
    add(_box(rng, [2.0, 1.5, 0.72], [3.2, 2.3, 0.75], sp, jit), "furniture")
    for lx_, ly_ in ((2.05, 1.55), (3.15, 1.55), (2.05, 2.25), (3.15, 2.25)):
        lp, ln = _line(rng, [lx_, ly_, 0.0], [lx_, ly_, 0.72], sp, jit)
        parts.append((lp, ln, "thin_furniture"))
    add(_box(rng, [0.3, 3.0, 0.0], [2.3, 3.9, 0.85], sp, jit), "furniture")
    add(_box(rng, [7.6, 3.0, 0.0], [7.95, 5.0, 2.0], sp, jit, sides=("top", "x0", "y0", "y1")), "furniture")
    # clutter: cups and a plant pot on the table and the floor (dense, small, real)
    for cx, cy, cz in ((2.3, 1.7, 0.75), (2.6, 2.0, 0.75), (2.9, 1.8, 0.75), (3.0, 2.1, 0.75), (1.0, 1.0, 0.0), (6.5, 4.0, 0.25)):
        add(_box(rng, [cx - 0.04, cy - 0.04, cz], [cx + 0.04, cy + 0.04, cz + 0.1], sp / 3, jit), "clutter")
    structure = np.concatenate([p for p, _, _ in parts])
    s_normals = np.concatenate([n for _, n, _ in parts])
    s_truth = np.concatenate([[name] * len(p) for p, _, name in parts]).astype(object)
    # noise
    n_struct = len(structure)
    floaters = rng.uniform([0, 0, 0], [lx, ly, lz], (int(P["floater_fraction"] * n_struct), 3))
    idx = rng.integers(0, n_struct, int(P["halo_fraction"] * n_struct))
    halo = structure[idx] + s_normals[idx] * rng.uniform(0.05, 0.15, (len(idx), 1))
    clusters = []
    for _ in range(P["floater_clusters"]):
        centre = rng.uniform([0.5, 0.5, 0.5], [lx - 0.5, ly - 0.5, lz - 0.5])
        clusters.append(centre + rng.normal(0, 0.02, (int(rng.integers(*P["points_per_cluster"])), 3)))
    clusters = np.concatenate(clusters)
    positions = np.concatenate([structure, floaters, halo, clusters])
    truth = np.concatenate([s_truth, np.array(["floater"] * len(floaters) + ["halo"] * len(halo) +
                                              ["floater_cluster"] * len(clusters), dtype=object)])
    normals = np.concatenate([s_normals, np.zeros((len(positions) - n_struct, 3))])
    opacity = np.empty(len(positions))
    noise = np.isin(truth, NOISE)
    low = rng.random(len(positions))
    opacity[~noise] = np.where(low[~noise] < 0.10, rng.uniform(0.01, 0.08, (~noise).sum()), rng.uniform(0.5, 1.0, (~noise).sum()))
    opacity[noise] = np.where(low[noise] < 0.60, rng.uniform(0.005, 0.06, noise.sum()), rng.uniform(0.1, 0.9, noise.sum()))
    return Scene(positions.astype(np.float32), opacity, truth, normals)


def nearest_structure_bucket(scene: Scene) -> np.ndarray:
    """For each point, the runtime bucket of the nearest STRUCTURE point: what a camera sees behind a floater, and so
    the label projection from 2D segmentation gives it (chaya_worker.stages.semantic_segmentation)."""
    from scipy.spatial import cKDTree

    struct = ~np.isin(scene.truth, NOISE)
    tree = cKDTree(scene.positions[struct])
    _, nn = tree.query(scene.positions, k=1)
    buckets = np.array([BUCKET[t] for t in scene.truth[struct]], dtype=object)
    return buckets[nn]
