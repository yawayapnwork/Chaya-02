"""SYNTHETIC point clouds for the incremental re-scan math tests. They are never venue data and never benchmark input.

A small room in canonical metres (+Z up): a 4 x 4 m floor, two 2.5 m walls, a 1 x 0.6 x 0.8 m box and a column. It is
asymmetric on purpose, so a registration has one right answer. Surfaces are sampled uniformly at random, so two calls
with different seeds are two different samplings of the same surfaces, as a venue scan and a re-scan are.
"""

from __future__ import annotations

import numpy as np

from chaya_worker.frames import Similarity
from chaya_worker.ply import GaussianCloud


def rotation(axis, degrees: float) -> np.ndarray:
    a = np.asarray(axis, dtype=np.float64)
    a = a / np.linalg.norm(a)
    t = np.radians(degrees)
    k = np.array([[0, -a[2], a[1]], [a[2], 0, -a[0]], [-a[1], a[0], 0]])
    return np.eye(3) + np.sin(t) * k + (1 - np.cos(t)) * (k @ k)


def _rect(rng, origin, u, v, n):
    a, b = rng.random((n, 1)), rng.random((n, 1))
    return np.asarray(origin, float) + a * np.asarray(u, float) + b * np.asarray(v, float)


def room(seed: int, density: int = 600) -> np.ndarray:
    """~density points per square metre of surface."""
    rng = np.random.default_rng(seed)
    parts = [
        _rect(rng, [0, 0, 0], [4, 0, 0], [0, 4, 0], 16 * density),  # floor
        _rect(rng, [0, 0, 0], [4, 0, 0], [0, 0, 2.5], 10 * density),  # wall y = 0
        _rect(rng, [0, 0, 0], [0, 4, 0], [0, 0, 2.5], 10 * density),  # wall x = 0
        _rect(rng, [1.5, 1.0, 0.8], [1.0, 0, 0], [0, 0.6, 0], int(0.6 * density)),  # box top
        _rect(rng, [1.5, 1.0, 0], [1.0, 0, 0], [0, 0, 0.8], int(0.8 * density)),  # box front
        _rect(rng, [1.5, 1.0, 0], [0, 0.6, 0], [0, 0, 0.8], int(0.5 * density)),  # box side
        _rect(rng, [3.0, 3.0, 0], [0.3, 0, 0], [0, 0, 2.5], int(0.75 * density)),  # column face
    ]
    return np.vstack(parts)


def rotation_error_deg(a: Similarity, b: Similarity) -> float:
    r = a.rotation @ b.rotation.T
    return float(np.degrees(np.arccos(np.clip((np.trace(r) - 1) / 2, -1, 1))))


def cloud(positions: np.ndarray, seed: int = 0) -> GaussianCloud:
    """Gaussians at `positions`, with distinct (random) colours, scales and orientations so row identity is testable."""
    rng = np.random.default_rng(seed)
    n = len(positions)
    q = rng.normal(size=(n, 4))
    return GaussianCloud(np.asarray(positions, np.float32), rng.normal(-4.5, 0.2, (n, 3)).astype(np.float32),
                         (q / np.linalg.norm(q, axis=1, keepdims=True)).astype(np.float32),
                         rng.normal(1, 1, n).astype(np.float32), rng.normal(0, 1, (n, 3)).astype(np.float32))
