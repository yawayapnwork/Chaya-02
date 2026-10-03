"""A deterministic synthetic venue for navigation tests: labelled points in canonical metres (+Z up), the shape of what
GEOMETRIC_CLEANUP hands NAVIGATION_BAKING (a cleaned splat's positions and SEMANTIC_LABELS_CLEAN), so the real surface
model, the real Recast build and the real Detour queries all run on it. It is SYNTHETIC: it shows the pipeline treats
walls, doorways, furniture, a column, a step and a ramp correctly; it says nothing about real reconstructed venues.

Layout (x, y in metres; floor at z = 0 unless stated):

    y=7 +---------------------+-----------------------------------+
        |                     |D2 wide doorway (1.6 m)    RAMP    |  PLATFORM
        |     SOFA            |                    lane: 1:14 up  |  z = 0.17
        |  (x 1-2, y 3.6-4.8) |                  x 7.12-9.5       |  x 9.5-12
        |                     W  (wall x 3.9-4.1)   ---RAILING---  |  (all y)
        |                     |D1 narrow doorway (1.1 m) x 7-9.5,  |
        |                     |               COLUMN   y 3.0-3.3   |
        |                     |            (x 6-6.4,  STEP lane:   |
        |                     |             y 1-1.4)  0.17 m step  |
    y=0 +---------------------+---------------------- at x = 9.5 --+
       x=0                   x=4                                x=12

The step lane (y < 3.0) rises 0.17 m in one step at x = 9.5; the ramp lane (y > 3.3) rises the same 0.17 m over 2.38 m
(1:14, 4.1 degrees). A railing (furniture, 1 m high) separates them between x = 7 and the platform, so from the low floor
the platform is reached either over the step or up the ramp. The wall has a lintel above both doorways (above the agent's
head, so it does not block). No floor is observed under the wall, the column, the sofa or the railing, as in a real scan.
"""

from __future__ import annotations

import numpy as np

STEP_RISE_M = 0.17
STEP_X = 9.5
RAMP_X0 = STEP_X - STEP_RISE_M * 14  # 1:14
WALL = (3.9, 4.1, 0.0, 7.0)
D1 = (2.0, 3.1)  # narrow doorway, y
D2 = (5.0, 6.6)  # wide doorway, y
SOFA = (1.0, 2.0, 3.6, 4.8)
COLUMN = (6.0, 6.4, 1.0, 1.4)
RAILING = (7.0, STEP_X, 3.0, 3.3)
EXTENT = (0.0, 12.0, 0.0, 7.0)
STEP_LANE_Y = (0.0, 3.0)
RAMP_LANE_Y = (3.3, 7.0)


def _grid(x0, x1, y0, y1, step=0.05):
    xs, ys = np.meshgrid(np.arange(x0, x1 - 1e-9, step) + step / 2, np.arange(y0, y1 - 1e-9, step) + step / 2)
    return xs.ravel(), ys.ravel()


def _inside(x, y, box) -> np.ndarray:
    return (x >= box[0]) & (x < box[1]) & (y >= box[2]) & (y < box[3])


def ground_height(x: np.ndarray, y: np.ndarray, *, with_ramp: bool = True) -> np.ndarray:
    """The true walkable surface height of the scene at (x, y)."""
    z = np.where(x >= STEP_X, STEP_RISE_M, 0.0)
    if with_ramp:
        ramp = (y >= RAMP_LANE_Y[0]) & (x >= RAMP_X0) & (x < STEP_X)
        z = np.where(ramp, STEP_RISE_M * (x - RAMP_X0) / (STEP_X - RAMP_X0), z)
    return z


def _block(box, z0, z1, step=0.1):
    """Points on the faces and top of an axis-aligned block (what a splat of a solid object holds)."""
    x0, x1, y0, y1 = box
    pts = []
    xs_face = np.append(np.arange(x0 + 0.02, x1 - 0.02, step / 2), x1 - 0.02)  # inside the block, ends included
    ys_face = np.append(np.arange(y0 + 0.02, y1 - 0.02, step / 2), y1 - 0.02)
    for z in np.arange(z0, z1 + 1e-9, step):
        for x in xs_face:
            pts += [(x, y0 + 0.02, z), (x, y1 - 0.02, z)]
        for y in ys_face:
            pts += [(x0 + 0.02, y, z), (x1 - 0.02, y, z)]
    xs, ys = _grid(x0, x1, y0, y1, step / 2)
    pts += list(zip(xs, ys, np.full(xs.size, z1), strict=True))
    return np.array(pts, dtype=np.float64)


def build(*, with_ramp: bool = True, with_wide_doorway: bool = True) -> tuple[np.ndarray, np.ndarray]:
    """(points (N, 3) canonical metres, labels (N,)). `with_ramp=False` makes the ramp lane a second step (no step-free way
    onto the platform); `with_wide_doorway=False` walls up D2 (only the narrow doorway joins the two rooms)."""
    x, y = _grid(*EXTENT)
    gaps = [D1] + ([D2] if with_wide_doorway else [])
    in_doorway = np.zeros(x.size, dtype=bool)
    for y0, y1 in gaps:  # the floor in a doorway is seen
        in_doorway |= _inside(x, y, (WALL[0], WALL[1], y0, y1))
    occluded = (_inside(x, y, WALL) & ~in_doorway) | _inside(x, y, SOFA) | _inside(x, y, COLUMN) | _inside(x, y, RAILING)
    x, y = x[~occluded], y[~occluded]
    floor = np.stack([x, y, ground_height(x, y, with_ramp=with_ramp)], axis=1)

    walls = []
    edges = [WALL[2]] + [v for g in gaps for v in g] + [WALL[3]]
    for y0, y1 in zip(edges[0::2], edges[1::2], strict=True):  # solid wall pieces between the doorways
        walls.append(_block((WALL[0], WALL[1], y0, y1), 0.05, 2.6))
    for y0, y1 in gaps:  # lintels: above the agent's head
        walls.append(_block((WALL[0], WALL[1], y0, y1), 2.1, 2.6))
    walls.append(_block(COLUMN, 0.05, 2.6))
    furniture = np.vstack([_block(SOFA, 0.2, 0.8), _block(RAILING, 0.1, 1.0)])
    ceiling = np.stack([*_grid(*EXTENT, step=0.2), np.full(_grid(*EXTENT, step=0.2)[0].size, 2.8)], axis=1)

    walls = np.vstack(walls)
    points = np.vstack([floor, walls, furniture, ceiling])
    labels = np.array(["floor"] * len(floor) + ["wall"] * len(walls) + ["furniture"] * len(furniture) + ["wall"] * len(ceiling),
                      dtype=object)
    return points, labels


def in_box(points: np.ndarray, box, margin: float = 0.0) -> np.ndarray:
    p = np.asarray(points, dtype=np.float64)
    return ((p[:, 0] > box[0] - margin) & (p[:, 0] < box[1] + margin) & (p[:, 1] > box[2] - margin) & (p[:, 1] < box[3] + margin))
