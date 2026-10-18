"""SYNTHETIC multi-room floor for the room registration and stitching tests. Never venue data, never benchmark input on
its own: tests/unit/test_room_registration.py and benchmarks/b7_multiroom use it to check the mathematics on surfaces
whose truth is known exactly.

Canonical metres, +Z up. Two rooms and a corridor:

      y=6  +-------------------------------------------+
           |                 corridor C                |   C: x 0..10, y 4..6
      y=4  +----[door]---------+-------[door]----------+
           |                   |                       |
           |     room A      [door]      room B        |   A: x 0..5, B: x 5..10, y 0..4
           |                   |                       |
      y=0  +-------------------+-----------------------+
          x=0                 x=5                     x=10

Walls are 0.15 m thick, so each doorway has two jambs (the faces across the wall's thickness): they are the only surfaces
in a doorway overlap whose normals run along the wall, and without them nothing would fix a slide along it. Each room
has asymmetric furniture. A capture observes every surface of its own space, the jambs of its doors, and the surfaces of
the neighbouring space inside a "peek" box beyond each of its doors (what a camera standing in the room sees through the
opening). Rooms therefore overlap only around doorways, as real captures do. The doors give the loop A-B, B-C, C-A.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np

from chaya_worker.frames import Similarity
from chaya_worker.ply import write_ply
from chaya_worker.region_splice import transform_gaussians
from tests.rescan_scene import rotation

WALL = 0.15
HALF = WALL / 2
WALL_HEIGHT = 2.5
DOOR_HEIGHT = 2.1


@dataclass
class Surface:
    space: str
    origin: np.ndarray
    u: np.ndarray
    v: np.ndarray
    label: str

    @property
    def area(self) -> float:
        return float(np.linalg.norm(np.cross(self.u, self.v)))


@dataclass
class Space:
    name: str
    lo: tuple[float, float]
    hi: tuple[float, float]
    peeks: list[tuple[tuple[float, float], tuple[float, float]]] = field(default_factory=list)  # boxes seen through doors
    doors: list[str] = field(default_factory=list)


def _rect(space, origin, u, v, label="wall"):
    return Surface(space, np.asarray(origin, float), np.asarray(u, float), np.asarray(v, float), label)


def _wall_x(space, x, y0, y1, doors=(), face=+1):
    """A wall face at x (facing +x when face=+1) from y0 to y1, with door openings [(ya, yb)] cut out (the lintel above
    each door is kept)."""
    out, cursor = [], y0
    for ya, yb in sorted(doors):
        if ya > cursor:
            out.append(_rect(space, [x, cursor, 0], [0, ya - cursor, 0], [0, 0, WALL_HEIGHT]))
        out.append(_rect(space, [x, ya, DOOR_HEIGHT], [0, yb - ya, 0], [0, 0, WALL_HEIGHT - DOOR_HEIGHT]))
        cursor = yb
    if y1 > cursor:
        out.append(_rect(space, [x, cursor, 0], [0, y1 - cursor, 0], [0, 0, WALL_HEIGHT]))
    return out


def _wall_y(space, y, x0, x1, doors=()):
    out, cursor = [], x0
    for xa, xb in sorted(doors):
        if xa > cursor:
            out.append(_rect(space, [cursor, y, 0], [xa - cursor, 0, 0], [0, 0, WALL_HEIGHT]))
        out.append(_rect(space, [xa, y, DOOR_HEIGHT], [xb - xa, 0, 0], [0, 0, WALL_HEIGHT - DOOR_HEIGHT]))
        cursor = xb
    if x1 > cursor:
        out.append(_rect(space, [cursor, y, 0], [x1 - cursor, 0, 0], [0, 0, WALL_HEIGHT]))
    return out


def _box(space, lo, size, label="furniture"):
    (x, y, z), (a, b, c) = lo, size
    return [_rect(space, [x, y, z + c], [a, 0, 0], [0, b, 0], label), _rect(space, [x, y, z], [a, 0, 0], [0, 0, c], label),
            _rect(space, [x, y + b, z], [a, 0, 0], [0, 0, c], label), _rect(space, [x, y, z], [0, b, 0], [0, 0, c], label),
            _rect(space, [x + a, y, z], [0, b, 0], [0, 0, c], label)]


def _jambs(spaces, axis, at, a, b):
    """The two jamb faces (and the head) of a doorway through a wall at `axis` = at, opening from a to b."""
    out = []
    if axis == "y":  # wall along x at y = at
        for x in (a, b):
            out.append(_rect(spaces, [x, at - HALF, 0], [0, WALL, 0], [0, 0, DOOR_HEIGHT], "wall"))
        out.append(_rect(spaces, [a, at - HALF, DOOR_HEIGHT], [b - a, 0, 0], [0, WALL, 0], "wall"))
    else:  # wall along y at x = at
        for y in (a, b):
            out.append(_rect(spaces, [at - HALF, y, 0], [WALL, 0, 0], [0, 0, DOOR_HEIGHT], "wall"))
        out.append(_rect(spaces, [at - HALF, a, DOOR_HEIGHT], [0, b - a, 0], [WALL, 0, 0], "wall"))
    return out


DOORS = {"AC": ("y", 4.0, (1.9, 3.1)), "BC": ("y", 4.0, (6.9, 8.1)), "AB": ("x", 5.0, (1.4, 2.6))}  # wall axis, at, opening (1.2 m)


def floor_plan(closed_doors: tuple[str, ...] = ()) -> tuple[dict[str, Space], list[Surface]]:
    """The floor. A door in `closed_doors` is shut: a panel fills its opening (seen from both sides, like the jambs)."""
    door_ac, door_bc, door_ab = DOORS["AC"][2], DOORS["BC"][2], DOORS["AB"][2]
    spaces = {
        "A": Space("A", (0.0, 0.0), (5.0 - HALF, 4.0 - HALF), peeks=[((1.0, 4.0), (4.0, 6.0)), ((5.0, 0.5), (7.0, 3.5))],
                   doors=["AC", "AB"]),
        "B": Space("B", (5.0 + HALF, 0.0), (10.0, 4.0 - HALF), peeks=[((6.0, 4.0), (9.0, 6.0)), ((3.0, 0.5), (5.0, 3.5))],
                   doors=["BC", "AB"]),
        "C": Space("C", (0.0, 4.0 + HALF), (10.0, 6.0), peeks=[((1.5, 2.5), (3.5, 4.0)), ((6.5, 2.5), (8.5, 4.0))],
                   doors=["AC", "BC"]),
    }
    s: list[Surface] = []
    # floors
    s.append(_rect("A", [0, 0, 0], [5 - HALF, 0, 0], [0, 4 - HALF, 0], "floor"))
    s.append(_rect("B", [5 + HALF, 0, 0], [5 - HALF, 0, 0], [0, 4 - HALF, 0], "floor"))
    s.append(_rect("C", [0, 4 + HALF, 0], [10, 0, 0], [0, 2 - HALF, 0], "floor"))
    # room A walls (inner faces)
    s += _wall_y("A", 0.0, 0.0, 5 - HALF)
    s += _wall_x("A", 0.0, 0.0, 4 - HALF)
    s += _wall_y("A", 4 - HALF, 0.0, 5 - HALF, doors=[door_ac])
    s += _wall_x("A", 5 - HALF, 0.0, 4 - HALF, doors=[door_ab])
    # room B walls
    s += _wall_y("B", 0.0, 5 + HALF, 10.0)
    s += _wall_x("B", 10.0, 0.0, 4 - HALF)
    s += _wall_y("B", 4 - HALF, 5 + HALF, 10.0, doors=[door_bc])
    s += _wall_x("B", 5 + HALF, 0.0, 4 - HALF, doors=[door_ab])
    # corridor walls
    s += _wall_y("C", 6.0, 0.0, 10.0)
    s += _wall_y("C", 4 + HALF, 0.0, 10.0, doors=[door_ac, door_bc])
    s += _wall_x("C", 0.0, 4 + HALF, 6.0)
    s += _wall_x("C", 10.0, 4 + HALF, 6.0)
    # jambs and the threshold floor across the wall's thickness: seen from both sides of each door
    s += _jambs("AC", "y", 4.0, *door_ac) + _jambs("BC", "y", 4.0, *door_bc) + _jambs("AB", "x", 5.0, *door_ab)
    for door, (axis, at, (a, b)) in DOORS.items():
        if axis == "y":
            s.append(_rect(door, [a, at - HALF, 0], [b - a, 0, 0], [0, WALL, 0], "floor"))
        else:
            s.append(_rect(door, [at - HALF, a, 0], [WALL, 0, 0], [0, b - a, 0], "floor"))
    # asymmetric furniture
    s += _box("A", (0.4, 0.4, 0), (1.2, 0.6, 0.75)) + _box("A", (3.6, 2.6, 0), (0.5, 0.5, 1.8))
    s += _box("B", (8.2, 0.3, 0), (1.4, 0.8, 0.9)) + _box("B", (6.0, 2.9, 0), (0.6, 0.4, 1.2))
    s += _box("C", (4.3, 5.4, 0), (1.5, 0.45, 0.45)) + _box("C", (0.2, 5.2, 0), (0.4, 0.6, 1.0))
    for door in closed_doors:
        axis, at, (a, b) = DOORS[door]
        if axis == "y":
            s.append(_rect(door, [a, at, 0], [b - a, 0, 0], [0, 0, DOOR_HEIGHT], "wall"))
        else:
            s.append(_rect(door, [at, a, 0], [0, b - a, 0], [0, 0, DOOR_HEIGHT], "wall"))
    return spaces, s


def _inside(points_xy, lo, hi, tol=0.02):
    return np.all((points_xy >= np.asarray(lo) - tol) & (points_xy <= np.asarray(hi) + tol), axis=1)


def capture(space: str, seed: int, density: int = 400, noise_m: float = 0.0,
            peeks: list[tuple[tuple[float, float], tuple[float, float]]] | None = None,
            closed_doors: tuple[str, ...] = (), floor_density: int | None = None) -> tuple[np.ndarray, np.ndarray]:
    """What a capture of `space` observes, in CANONICAL metres: (points, labels). Every surface of its own space, the jambs
    of its doors, and other spaces' surfaces inside its peek boxes (`peeks` replaces the plan's: a camera that barely
    looked through a door). `density` points per square metre; floors at `floor_density` when given (a navmesh needs a
    few floor points in every 0.1 m cell: at 800 per square metre about 1 % of cells get fewer than 3, and Recast erodes
    the agent's radius around each such hole)."""
    spaces, surfaces = floor_plan(closed_doors)
    sp = spaces[space]
    boxes = sp.peeks if peeks is None else peeks
    rng = np.random.default_rng(seed)
    pts, labels = [], []
    for surf in surfaces:
        n = max(1, int(surf.area * (floor_density if floor_density and surf.label == "floor" else density)))
        a, b = rng.random((n, 1)), rng.random((n, 1))
        p = surf.origin + a * surf.u + b * surf.v
        if surf.space == space or (len(surf.space) == 2 and space in surf.space):
            keep = np.ones(n, dtype=bool)
        else:
            keep = np.zeros(n, dtype=bool)
            for lo, hi in boxes:
                keep |= _inside(p[:, :2], lo, hi)
        pts.append(p[keep])
        labels += [surf.label] * int(keep.sum())
    points = np.vstack(pts)
    if noise_m:
        points = points + rng.normal(0, noise_m, points.shape)
    return points, np.array(labels, dtype=object)


def local_frame(seed: int, *, gravity_aligned: bool = True) -> Similarity:
    """A capture's own FLOOR_LOCAL-like frame relative to canonical: an arbitrary heading and origin (and, when not
    gravity-aligned, a tilt). Returns canonical -> local; points_local = T.apply(points_canonical)."""
    rng = np.random.default_rng(1000 + seed)
    yaw = rng.uniform(-180, 180)
    R = rotation([0, 0, 1], yaw)
    if not gravity_aligned:
        R = rotation(rng.normal(size=3), rng.uniform(5, 30)) @ R
    return Similarity(1.0, R, rng.uniform(-8, 8, 3) * [1, 1, 0.2])


def gaussians(points: np.ndarray, seed: int = 0, colour_of=None):
    """A Gaussian cloud on `points`: anisotropic scales, random orientations and opacities, so every attribute a transform
    must move (or must not touch) is distinct per Gaussian. The colour is the surface's (`colour_of(points)`, SH DC terms;
    by default a smooth function of where the point is) plus a little per-capture noise: two captures of one surface agree
    on its colour, as two real captures under the same light do."""
    from chaya_worker.ply import GaussianCloud

    rng = np.random.default_rng(seed)
    p = np.asarray(points, np.float64)
    n = len(p)
    q = rng.normal(size=(n, 4))
    q /= np.linalg.norm(q, axis=1, keepdims=True)
    colour = colour_of(p) if colour_of is not None else 0.6 * np.sin(np.stack([p[:, 0] * 1.3, p[:, 1] * 1.7, p[:, 2] * 2.1], axis=1))
    return GaussianCloud(p.astype(np.float32), np.log(rng.uniform(0.004, 0.02, (n, 3))).astype(np.float32), q.astype(np.float32),
                         rng.normal(0, 1, n).astype(np.float32), (colour + rng.normal(0, 0.02, (n, 3))).astype(np.float32))


def true_matches(truth: Similarity, *, n: int = 400, outliers: float = 0.6, seed: int = 5, within_m: float = 0.04):
    """Stand-in for FPFH matching between two rooms (source -> target): true pairs only where the target really has a point
    near the truly transformed source point (inside the overlap), with `outliers` of the pairs replaced by random wrong
    matches. tests/gpu/test_room_registration.py runs the real FPFH descriptors."""
    from scipy.spatial import cKDTree

    def correspondences(src, dst, _voxel):
        rng = np.random.default_rng(seed)
        d, j = cKDTree(dst).query(truth.apply(src), distance_upper_bound=within_m)
        ok = np.flatnonzero(np.isfinite(d))
        if len(ok) == 0:
            return np.zeros((0, 3)), np.zeros((0, 3))
        pick = rng.choice(ok, min(n, len(ok)), replace=False)
        ps, pd = src[pick], dst[j[pick]].copy()
        wrong = rng.random(len(pick)) < outliers
        pd[wrong] = dst[rng.choice(len(dst), int(wrong.sum()))]
        return ps, pd
    return correspondences


def oracle_matches(clouds: dict[str, np.ndarray], frames: dict[str, Similarity], **kw):
    """true_matches for whichever two rooms it is handed: each cloud is recognised by its centroid among `clouds` (each
    room's points in its own frame) and `frames` (canonical -> each room's frame) gives the truth between them. Stands in
    for FPFH when a stage registers every pair itself."""
    centroids = {r: np.asarray(c).mean(axis=0) for r, c in clouds.items()}

    def which(points: np.ndarray) -> str:
        c = points.mean(axis=0)
        return min(centroids, key=lambda r: np.linalg.norm(centroids[r] - c))

    def correspondences(src, dst, voxel):
        s, t = which(src), which(dst)
        return true_matches(frames[t].compose(frames[s].inverse()), **kw)(src, dst, voxel)
    return correspondences


def wrong_matches(wrong: Similarity, *, n: int = 400, seed: int = 9):
    """Correspondences that agree with each other but on the WRONG transform: what repetitive structure produces."""
    from scipy.spatial import cKDTree

    def correspondences(src, dst, _voxel):
        rng = np.random.default_rng(seed)
        _, j = cKDTree(dst).query(wrong.apply(src))
        pick = rng.choice(len(src), min(n, len(src)), replace=False)
        return src[pick], dst[j[pick]]
    return correspondences


# ---- work-order fixtures: the floor as a stitch run sees it ------------------------------------------------------------------

ROOMS = ("A", "B", "C")
LOCAL = {r: local_frame(i) for i, r in enumerate(ROOMS)}  # venue -> each room's own canonical (FLOOR_LOCAL) frame
CALIBRATION = {  # each room's reconstruction -> its canonical frame
    "A": Similarity(2.0, rotation([1, 0.3, 0.2], 40), np.array([1.0, -2.0, 0.5])),
    "B": Similarity(0.5, rotation([0.2, 1, 0], 110), np.array([-3.0, 1.0, 2.0])),
    "C": Similarity(1.25, rotation([0, 0.4, 1], -70), np.array([0.0, 4.0, -1.0])),
}


def wire(room: str, *, canonical: bool = True) -> dict:
    t = CALIBRATION[room]
    base = {"id": f"frame-{room}", "sourceRunId": f"run-{room}", "version": 1, "horizontalDatum": "FLOOR_LOCAL"}
    if not canonical:
        return {**base, "metricStatus": "NOT_CALIBRATED", "gravityStatus": "NOT_ALIGNED", "scale": None, "rotation": None,
                "translation": None}
    return {**base, "metricStatus": "METRIC", "gravityStatus": "ALIGNED", **t.to_dict()}


class Floor:
    """The three rooms as a stitch's work orders see them: each room's Gaussians stored in its own reconstruction frame
    (CALIBRATION maps it to the room's FLOOR_LOCAL canonical frame, LOCAL relates that to the venue) as ROOM_CLOUD, its
    labels as ROOM_LABELS, uploaded to the harness's storage. `seed` draws another sampling of every capture (0: the one the
    tests use); `noise_m` is the position noise of each capture."""

    def __init__(self, harness, tmp_path: Path, peeks: dict | None = None, density: int = 200, closed_doors: tuple[str, ...] = (),
                 floor_density: int | None = None, seed: int = 0, noise_m: float = 0.003):
        self.harness, self.tmp, self.density, self.noise_m = harness, tmp_path, density, noise_m
        self.canonical, self.labels, self.refs = {}, {}, {}
        for i, r in enumerate(ROOMS):
            s = i + 10 * seed
            p, labels = capture(r, seed=s, density=density, noise_m=noise_m, peeks=(peeks or {}).get(r), closed_doors=closed_doors,
                                floor_density=floor_density)
            g = transform_gaussians(gaussians(p, seed=s), CALIBRATION[r].inverse().compose(LOCAL[r]))  # stored in its own recon frame
            self.canonical[r], self.labels[r] = LOCAL[r].apply(p), labels
            cloud_path = write_ply(g, tmp_path / f"room-{r}.ply")
            labels_path = tmp_path / f"room-{r}-labels.json"
            labels_path.write_text(json.dumps({"labels": [str(x) for x in labels]}))
            self.refs[r] = (self._ref("ROOM_CLOUD", cloud_path), self._ref("ROOM_LABELS", labels_path, "application/json"))

    def recapture(self, room: str, seed: int) -> None:
        p, _ = capture(room, seed=seed, density=self.density, noise_m=self.noise_m)
        path = write_ply(transform_gaussians(gaussians(p, seed=seed), CALIBRATION[room].inverse().compose(LOCAL[room])),
                         self.tmp / f"room-{room}-{seed}.ply")
        self.refs[room] = (self._ref("ROOM_CLOUD", path), self.refs[room][1])

    def _ref(self, kind, path, content_type="application/octet-stream"):
        ref = self.harness.raw_input(kind, path, content_type)
        ref["containsPii"] = False
        return ref

    def rooms(self, anchor="A", uncalibrated=(), only=ROOMS) -> list[dict]:
        return [{"spaceId": f"space-{r}", "name": f"Room {r}", "scanVersionId": f"version-{r}", "runId": f"run-{r}",
                 "anchor": r == anchor, "coordinateFrame": wire(r, canonical=r not in uncalibrated),
                 "cloudArtifactId": self.refs[r][0]["artifactId"], "labelsArtifactId": self.refs[r][1]["artifactId"]} for r in only]

    def order(self, stage, *, extra_inputs=(), anchor="A", uncalibrated=(), frame_of=None, only=ROOMS, allow_unconfirmed=False):
        """A work order for `stage` over the rooms `only` (default: all three); `allow_unconfirmed` accepts rooms no loop
        confirms (dev.chaya.api StitchRequest.allowUnconfirmedRooms)."""
        inputs = [ref for r in only for ref in self.refs[r]] + list(extra_inputs)
        order = self.harness.order(stage, inputs)
        order["rooms"] = self.rooms(anchor, uncalibrated, only)
        order["stitchOptions"] = {"allowUnconfirmedRooms": allow_unconfirmed}
        order["coordinateFrame"] = wire(frame_of or anchor)
        return order
