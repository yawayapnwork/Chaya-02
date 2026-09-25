"""Generates synthetic-calibration.json: a MATHEMATICAL UNIT-TEST FIXTURE for the coordinate-frame code in all
three languages (chaya_worker.frames, dev.chaya.api.frame, apps/web/lib/coordinate-frame.ts).

It is not a venue, not a reconstruction and not a measurement. It is a hand-chosen similarity transform plus
points placed in canonical metres, mapped into a "reconstruction" frame with the exact inverse transform, so
that every language can check it recovers / applies the same transform to double precision. Nothing in it
may be used as, or presented as, real calibration data.

    python packages/contracts/fixtures/generate_synthetic_calibration.py   # rewrites the JSON (deterministic)
"""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np

OUT = Path(__file__).with_name("synthetic-calibration.json")


def axis_angle_to_quaternion(axis: np.ndarray, angle_deg: float) -> np.ndarray:
    axis = axis / np.linalg.norm(axis)
    half = np.radians(angle_deg) / 2
    return np.concatenate([[np.cos(half)], np.sin(half) * axis])


def quaternion_to_matrix(q: np.ndarray) -> np.ndarray:
    w, x, y, z = q / np.linalg.norm(q)
    return np.array([
        [1 - 2 * (y * y + z * z), 2 * (x * y - w * z), 2 * (x * z + w * y)],
        [2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x)],
        [2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y)],
    ])


def main() -> None:
    # Reconstruction -> canonical: X_c = s R X_r + t. One reconstruction unit is 1/0.37 m here.
    scale = 0.37
    q = axis_angle_to_quaternion(np.array([0.3, -0.8, 0.5]), 127.0)
    r = quaternion_to_matrix(q)
    t = np.array([12.5, -4.25, 1.75])

    def to_reconstruction(p_canonical: np.ndarray) -> np.ndarray:
        return (r.T @ (np.asarray(p_canonical, dtype=np.float64) - t)) / scale

    control_venue = np.array([[0.0, 0.0, 0.0], [8.0, 0.0, 0.0], [8.0, 6.0, 0.0], [0.0, 6.0, 0.0], [4.0, 3.0, 2.4]])
    control_recon = np.array([to_reconstruction(p) for p in control_venue])

    # Two measured distances between reconstruction points (a doorway and a wall). Synthetic, so "measured" = exact.
    pairs = [(control_venue[0], control_venue[1], "wall A"), (np.array([2.0, 6.0, 0.0]), np.array([2.0, 6.0, 2.1]), "door jamb")]
    distances = [{"label": label, "a": to_reconstruction(a).tolist(), "b": to_reconstruction(b).tolist(),
                  "measuredMetres": float(np.linalg.norm(b - a))} for a, b, label in pairs]

    doc = {
        "SYNTHETIC": "Mathematical unit-test fixture only. Not a venue, not a reconstruction, not a measurement.",
        "generator": "packages/contracts/fixtures/generate_synthetic_calibration.py",
        "convention": "X_canonical = scale * R(rotation) * X_reconstruction + translation; quaternion (w, x, y, z)",
        "truth": {"scale": scale, "rotation": {"w": q[0], "x": q[1], "y": q[2], "z": q[3]},
                  "translation": {"x": t[0], "y": t[1], "z": t[2]}},
        "controlPoints": [{"label": f"cp{i}", "reconstruction": control_recon[i].tolist(), "venue": control_venue[i].tolist()}
                          for i in range(len(control_venue))],
        "distanceReferences": distances,
        "gravity": {
            "upReconstruction": (r.T @ np.array([0.0, 0.0, 1.0])).tolist(),
            "floorPointReconstruction": to_reconstruction(np.array([3.0, 2.0, 0.0])).tolist(),
            "cameraCentroidReconstruction": to_reconstruction(np.array([3.0, 2.0, 1.5])).tolist(),
        },
    }
    OUT.write_text(json.dumps(doc, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
