"""The one place Chaya canonical coordinates meet Recast's.

Chaya canonical (chaya_worker.frames): metres, right-handed, +Z up.
Recast/Detour: metres (it takes whatever unit it is given; Chaya gives it metres), right-handed, +Y up --
its voxelisation, cellHeight, agentHeight, agentMaxClimb and agentMaxSlope are all measured along Y.

The conversion is the proper rotation of -90 degrees about X (no reflection, so handedness is preserved):

    canonical (x, y, z)  ->  Recast (x, z, -y)
    Recast    (x, y, z)  ->  canonical (x, -z, y)

Everything that crosses into or out of Recast/Detour (the chaya-navmesh tool, services/reconstruction/native/chaya-navmesh) goes through the
functions below, and they are called only from chaya_worker.recast, the module that talks to that tool. No other module
converts axes for Recast: chaya_worker.navmesh and chaya_worker.stages.navigation_baking only ever see canonical
coordinates.
"""

from __future__ import annotations

import numpy as np

# Rows map canonical to Recast: recast = CANONICAL_TO_RECAST @ canonical.
CANONICAL_TO_RECAST = np.array([[1.0, 0.0, 0.0],
                                [0.0, 0.0, 1.0],
                                [0.0, -1.0, 0.0]])
RECAST_TO_CANONICAL = CANONICAL_TO_RECAST.T

RECAST_UP_AXIS = "+Y"
# Recorded in navmesh provenance so an artifact says exactly how its Recast-frame data relates to the canonical frame.
CONVERSION = "canonical (x, y, z) -> Recast (x, z, -y); Recast (x, y, z) -> canonical (x, -z, y)"


def canonical_to_recast(points: np.ndarray) -> np.ndarray:
    """(N, 3) or (3,) canonical metres -> Recast coordinates."""
    return np.asarray(points, dtype=np.float64) @ CANONICAL_TO_RECAST.T


def recast_to_canonical(points: np.ndarray) -> np.ndarray:
    """(N, 3) or (3,) Recast coordinates -> canonical metres."""
    return np.asarray(points, dtype=np.float64) @ RECAST_TO_CANONICAL.T


def canonical_half_extents_to_recast(horizontal_m: float, vertical_m: float) -> np.ndarray:
    """A Detour search box's half extents, given as a horizontal radius and a vertical half height in canonical metres.
    Extents are unsigned lengths along axes, so they map by axis (canonical x, y horizontal; z vertical), not by
    rotating a point."""
    return np.array([horizontal_m, vertical_m, horizontal_m], dtype=np.float64)
