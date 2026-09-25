"""The one place Chaya canonical coordinates meet Recast's.

Chaya canonical (chaya_worker.frames): metres, right-handed, +Z up.
Recast/Detour: metres (it takes whatever unit it is given; Chaya gives it metres), right-handed, +Y up --
its voxelisation, cellHeight, agentHeight, agentMaxClimb and agentMaxSlope are all measured along Y.

The conversion is the proper rotation of -90 degrees about X (no reflection, so handedness is preserved):

    canonical (x, y, z)  ->  Recast (x, z, -y)
    Recast    (x, y, z)  ->  canonical (x, -z, y)

Everything that crosses into or out of Recast goes through these two functions, called only from
chaya_worker.navmesh.write_walkable_obj and chaya_worker.navmesh.parse_recast_polygons. No other module
converts axes for Recast.
"""

from __future__ import annotations

import numpy as np

# Rows map canonical to Recast: recast = CANONICAL_TO_RECAST @ canonical.
CANONICAL_TO_RECAST = np.array([[1.0, 0.0, 0.0],
                                [0.0, 0.0, 1.0],
                                [0.0, -1.0, 0.0]])
RECAST_TO_CANONICAL = CANONICAL_TO_RECAST.T

RECAST_UP_AXIS = "+Y"


def canonical_to_recast(points: np.ndarray) -> np.ndarray:
    """(N, 3) or (3,) canonical metres -> Recast coordinates."""
    return np.asarray(points, dtype=np.float64) @ CANONICAL_TO_RECAST.T


def recast_to_canonical(points: np.ndarray) -> np.ndarray:
    """(N, 3) or (3,) Recast coordinates -> canonical metres."""
    return np.asarray(points, dtype=np.float64) @ RECAST_TO_CANONICAL.T
