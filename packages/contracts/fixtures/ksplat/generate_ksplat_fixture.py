"""Writes the .ksplat viewer-compatibility fixture with the PRODUCTION encoder (chaya_worker.ksplat) and PLY writer
(chaya_worker.ply). A tiny, deterministic, hand-chosen Gaussian cloud: a test fixture, not a reconstruction of anything.

    services/reconstruction/.venv/Scripts/python packages/contracts/fixtures/ksplat/generate_ksplat_fixture.py

Outputs, next to this file:
    cloud.json    the input cloud (log scales, wxyz rotations, opacity logits, SH-DC colours) -- the ground truth the
                  Node test derives its expected values from, independently of the Python encoder
    scene.ksplat  chaya_worker.ksplat.write_ksplat(cloud)
    scene.ply     chaya_worker.ply.write_ply(cloud): the standard 3DGS PLY the library's own PlyLoader reads

services/reconstruction/tests/unit/test_ksplat_fixture.py fails if the committed files differ from what the production
code writes today; apps/web/lib/ksplat-compat.test.ts loads them with the pinned GaussianSplats3D.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parents[3] / "services" / "reconstruction"))

from chaya_worker.ksplat import write_ksplat  # noqa: E402
from chaya_worker.ply import GaussianCloud, write_ply  # noqa: E402

CLOUD = {
    "note": "Deterministic viewer-compatibility fixture. Not a reconstruction.",
    # Distinct, float32-exact positions so a mis-ordered field is visible.
    "positions": [[1.5, -2.0, 0.25], [-3.25, 0.5, 4.0], [0.0, 0.0, -1.0]],
    "scales_log": [[-2.302585, -1.609438, -1.203973], [0.0, -0.5, 0.5], [-4.0, -4.0, -4.0]],
    # identity; 90 degrees about +Y; a deliberately unnormalised quaternion (the encoder must normalise it)
    "rotations_wxyz": [[1.0, 0.0, 0.0, 0.0], [0.70710678, 0.0, 0.70710678, 0.0], [2.0, 0.5, -1.0, 0.25]],
    "opacity_logit": [2.0, -1.0, 0.0],
    "colors_dc": [[1.0, 0.0, -1.0], [-1.7724539, 1.7724539, 0.25], [0.5, -0.5, 0.0]],
}


def cloud_from(doc: dict) -> GaussianCloud:
    return GaussianCloud(np.array(doc["positions"], np.float32), np.array(doc["scales_log"], np.float32),
                         np.array(doc["rotations_wxyz"], np.float32), np.array(doc["opacity_logit"], np.float32),
                         np.array(doc["colors_dc"], np.float32))


def main() -> None:
    (HERE / "cloud.json").write_text(json.dumps(CLOUD, indent=2) + "\n", encoding="utf-8")
    cloud = cloud_from(CLOUD)
    write_ksplat(cloud, HERE / "scene.ksplat")
    write_ply(cloud, HERE / "scene.ply")


if __name__ == "__main__":
    main()
