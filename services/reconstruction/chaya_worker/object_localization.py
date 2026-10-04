"""Placing a 2D detection in 3D, with occlusion and depth evidence (review CV-1). Pure numpy.

The only 3D evidence SEMANTIC_INDEXING has is the trained splat's Gaussian centres, projected into the detection's
camera. A detection box covers the object **and whatever is behind it**: the wall a sign hangs on, the floor around a
chair's legs, the room beyond a doorway. The old placement took the median of every centre that projected into the box,
so a small object in front of a wall landed on the wall.

Method (``localize_detection``), all depths in canonical metres along the camera's optical axis:

1. **Occlusion (z-buffer of the centres).** Every visible centre is binned into `cell_px` x `cell_px` pixel cells, and
   each cell keeps its nearest depth. A centre more than `depth_band(d)` behind its cell's nearest depth is hidden
   behind other geometry at that pixel. It is never treated as the surface the camera saw.
2. **Depth layers inside the box.** The front (unhidden) centres inside the box are sorted by depth and split into layers
   wherever two consecutive depths are more than `depth_band(d)` apart. A layer is a candidate surface if it holds at least
   `min_support` centres.
3. **The object is the nearest candidate layer that covers the box.** Coverage is the fraction of the box's occupied
   cells (cells holding any front centre) that this layer occupies. The nearest layer with coverage >= `min_coverage` is
   the object; a thin foreground occluder (a chair arm across a table) does not cover the box and is passed over, and the
   background (the wall) is behind the object and never reached.
4. **Position** = the median of that layer's centres. **Depth spread** = 1.4826 x the median absolute deviation of its
   depths: how thick the surface the placement rests on is, in metres.

Nothing is placed without evidence. The detection is rejected, not placed, when:
  * NO_DEPTH: no visible, unmasked centre projects into the box;
  * INSUFFICIENT_DEPTH: fewer than `min_support` front centres in the box, or no layer with that many;
  * AMBIGUOUS_DEPTH: layers exist but none covers `min_coverage` of the box.

What this is not. Centres are points; a Gaussian's footprint is ignored, so a large, sparse Gaussian can occlude pixels its
centre does not reach. No depth is rendered. The depth spread is the measured thickness of the chosen surface, not an
error against ground truth: object-level placement accuracy has not been measured on real data.
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np

METHOD = "ZBUFFERED_NEAREST_COVERING_LAYER"
NO_DEPTH = "NO_DEPTH"
INSUFFICIENT_DEPTH = "INSUFFICIENT_DEPTH"
AMBIGUOUS_DEPTH = "AMBIGUOUS_DEPTH"
REJECTIONS = (NO_DEPTH, INSUFFICIENT_DEPTH, AMBIGUOUS_DEPTH)


@dataclass(frozen=True)
class LocalizationParams:
    cell_px: int = 16  # z-buffer cell, pixels
    depth_band_m: float = 0.15  # absolute part of the depth tolerance
    depth_band_rel: float = 0.05  # relative part: the tolerance grows with distance (depth noise does)
    min_support: int = 12  # centres a surface needs before it counts as depth evidence
    min_coverage: float = 0.25  # fraction of the box's occupied cells the object's layer must occupy

    def band(self, depth_m: np.ndarray | float) -> np.ndarray | float:
        return self.depth_band_m + self.depth_band_rel * np.asarray(depth_m)


@dataclass
class Localized:
    position: np.ndarray  # in the frame of the input positions
    support_points: int  # centres in the chosen layer
    depth_m: float  # median depth of the chosen layer
    depth_spread_m: float  # robust spread of its depths
    coverage: float  # fraction of the box's occupied cells it occupies
    layers: int  # candidate layers in the box (>= 2: something was in front of or behind the object)
    hidden_in_box: int  # centres in the box rejected as hidden behind nearer geometry


@dataclass
class Rejected:
    reason: str  # one of REJECTIONS
    detail: dict


def camera_depth(positions: np.ndarray, viewmat: np.ndarray) -> np.ndarray:
    """Pure: each point's depth along the camera's optical axis (+Z of camera space), in the units of `positions`."""
    p = np.asarray(positions, dtype=np.float64)
    v = np.asarray(viewmat, dtype=np.float64)
    return p @ v[2, :3] + v[2, 3]


def front_surface(px: np.ndarray, depth_m: np.ndarray, visible: np.ndarray, params: LocalizationParams) -> np.ndarray:
    """Pure: `visible` restricted to centres not hidden behind nearer geometry in their z-buffer cell (step 1)."""
    out = np.zeros(len(depth_m), dtype=bool)
    ids = np.flatnonzero(visible)
    if len(ids) == 0:
        return out
    cells = np.floor(px[ids] / params.cell_px).astype(np.int64)
    cells -= cells.min(axis=0)
    key = cells[:, 0] * (int(cells[:, 1].max()) + 1) + cells[:, 1]
    nearest = np.full(int(key.max()) + 1, np.inf)
    np.minimum.at(nearest, key, depth_m[ids])
    out[ids] = depth_m[ids] <= nearest[key] + params.band(nearest[key])
    return out


def localize_detection(box_xyxy, positions: np.ndarray, px: np.ndarray, depth_m: np.ndarray, visible: np.ndarray,
                       params: LocalizationParams) -> Localized | Rejected:
    """Pure: the 3D position of the surface a detection box shows, or why there is no depth evidence for one (module
    docstring). `positions` (N, 3) in any frame; `px` (N, 2) their pixels; `depth_m` (N,) their camera depths in metres;
    `visible` (N,) in front of the camera, in the image and on an unmasked pixel."""
    x0, y0, x1, y1 = box_xyxy
    in_box = visible & (px[:, 0] >= x0) & (px[:, 0] < x1) & (px[:, 1] >= y0) & (px[:, 1] < y1)
    if not in_box.any():
        return Rejected(NO_DEPTH, {"centres_in_box": 0})
    front = front_surface(px, depth_m, visible, params) & in_box
    hidden = int(in_box.sum() - front.sum())
    ids = np.flatnonzero(front)
    if len(ids) < params.min_support:
        return Rejected(INSUFFICIENT_DEPTH, {"front_centres_in_box": len(ids), "hidden_in_box": hidden,
                                             "min_support": params.min_support})
    order = ids[np.argsort(depth_m[ids], kind="stable")]
    d = depth_m[order]
    breaks = np.flatnonzero(np.diff(d) > params.band(d[:-1])) + 1
    layers = [layer for layer in np.split(order, breaks) if len(layer) >= params.min_support]
    if not layers:
        return Rejected(INSUFFICIENT_DEPTH, {"front_centres_in_box": len(ids), "hidden_in_box": hidden,
                                             "largest_layer": int(max(len(x) for x in np.split(order, breaks))),
                                             "min_support": params.min_support})

    def cells(members: np.ndarray) -> set[tuple[int, int]]:
        return set(map(tuple, np.floor(px[members] / params.cell_px).astype(np.int64).tolist()))

    occupied = len(cells(ids))
    coverages = [len(cells(layer)) / occupied for layer in layers]
    chosen = next((i for i, c in enumerate(coverages) if c >= params.min_coverage), None)
    if chosen is None:
        return Rejected(AMBIGUOUS_DEPTH, {"layers": len(layers), "coverages": [round(c, 3) for c in coverages],
                                          "min_coverage": params.min_coverage})
    layer = layers[chosen]
    depths = depth_m[layer]
    spread = float(1.4826 * np.median(np.abs(depths - np.median(depths))))
    return Localized(np.median(np.asarray(positions, dtype=np.float64)[layer], axis=0), len(layer), float(np.median(depths)),
                     spread, coverages[chosen], len(layers), hidden)
