"""Writes the viewer FORMAT-VALIDATION fixture: a deterministic, synthetic Gaussian scene exported by the PRODUCTION
exporter (the worker's ARTIFACT_GENERATION stage, chaya_worker.stages.artifact_generation).

THIS IS NOT A RECONSTRUCTION. No capture, no camera and no training produced it. It exists so the viewing path
(exporter -> stored artifact -> API -> browser download -> GaussianSplats3D) can be validated with bytes the production
exporter really writes, while no real reconstruction can be produced here (no CUDA GPU; docs/E2E_VALIDATION.md).

    services/reconstruction/.venv/Scripts/python packages/contracts/fixtures/viewer-scene/generate_viewer_scene.py

The scene, in canonical metres (+Z up): a 6 m x 4 m checkerboard floor at z = 0 (flat Gaussians) and three 2 m tall
pillars, red, green and blue. It is stored in a "reconstruction frame" that differs from canonical by a known similarity
(scale, a COLMAP-like -Y up, a yaw and an offset), X_canonical = s R X_reconstruction + t, so calibration and canonical
placement are exercised rather than an identity.

Outputs, next to this file:
    fixture.json   the similarity, the scene's control points and POI positions (both exact BY CONSTRUCTION, not
                   measured), the splat count and the checksums of the two files below
    scene.ply      the SPLAT input (chaya_worker.ply.write_ply)
    scene.ksplat   ARTIFACT_GENERATION's KSPLAT output for scene.ply

services/reconstruction/tests/unit/test_viewer_scene_fixture.py fails unless the committed files are exactly what this
script and the production stage produce today.
"""

from __future__ import annotations

import hashlib
import json
import logging
import sys
import tempfile
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parents[3] / "services" / "reconstruction"))

from chaya_worker.contract import InputFile, StageContext
from chaya_worker.frames import matrix_to_quaternion
from chaya_worker.ply import GaussianCloud, write_ply
from chaya_worker.runner import CommandRunner
from chaya_worker.settings import Settings
from chaya_worker.splat_color import rgb_to_sh0
from chaya_worker.stages.artifact_generation import ArtifactGeneration
from chaya_worker.toolchain import Toolchain

NOTE = ("FORMAT VALIDATION FIXTURE -- a deterministic synthetic Gaussian scene exported by the production exporter. "
        "NOT a reconstruction of any venue.")

# X_canonical = SCALE * R @ X_reconstruction + T
SCALE = 2.5
YAW_DEG = 30.0
T = np.array([1.2, -0.7, 0.0])


def rotation() -> np.ndarray:
    """Reconstruction -> canonical rotation: reconstruction -Y (a COLMAP-like image-down) becomes canonical +Z, then a yaw."""
    rx = np.array([[1.0, 0.0, 0.0], [0.0, 0.0, 1.0], [0.0, -1.0, 0.0]])  # -90 degrees about X: (0, -1, 0) -> (0, 0, 1)
    a = np.radians(YAW_DEG)
    rz = np.array([[np.cos(a), -np.sin(a), 0.0], [np.sin(a), np.cos(a), 0.0], [0.0, 0.0, 1.0]])
    return rz @ rx


def to_reconstruction(canonical: np.ndarray) -> np.ndarray:
    return ((canonical - T) @ rotation()) / SCALE  # R^T (X - t) / s, row-vector form


PILLARS = [("Red pillar", (1.0, 1.0), (0.85, 0.15, 0.15)), ("Green pillar", (4.0, 3.0), (0.15, 0.75, 0.2)),
           ("Blue pillar", (5.0, 1.0), (0.15, 0.3, 0.9))]
PILLAR_HALF, PILLAR_HEIGHT, STEP = 0.2, 2.0, 0.1


def canonical_scene() -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """(positions, canonical log-scales, rgb) of every Gaussian, canonical metres."""
    pos, scl, rgb = [], [], []
    for i in range(61):  # floor: 6 m x 4 m checkerboard, 0.1 m grid, flat Gaussians
        for j in range(41):
            pos.append((i * STEP, j * STEP, 0.0))
            scl.append((0.06, 0.06, 0.008))
            rgb.append((0.82, 0.82, 0.8) if ((i // 5) + (j // 5)) % 2 == 0 else (0.1, 0.45, 0.5))
    offsets = np.arange(-PILLAR_HALF, PILLAR_HALF + 1e-9, STEP)
    for _, (cx, cy), colour in PILLARS:  # pillars: the four faces, sampled every 0.1 m
        for k in range(1, round(PILLAR_HEIGHT / STEP) + 1):
            z = k * STEP
            for o in offsets:
                for x, y in ((cx + o, cy - PILLAR_HALF), (cx + o, cy + PILLAR_HALF), (cx - PILLAR_HALF, cy + o), (cx + PILLAR_HALF, cy + o)):
                    pos.append((x, y, z))
                    scl.append((0.05, 0.05, 0.05))
                    rgb.append(colour)
    return np.array(pos), np.array(scl), np.array(rgb)


def build_cloud() -> GaussianCloud:
    pos_c, scl_c, rgb = canonical_scene()
    n = len(pos_c)
    # Canonical axis-aligned Gaussians expressed in the reconstruction frame: rotation R^T, sizes divided by s.
    q = matrix_to_quaternion(rotation().T)
    return GaussianCloud(
        positions=to_reconstruction(pos_c).astype(np.float32),
        scales_log=np.log(scl_c / SCALE).astype(np.float32),
        rotations_wxyz=np.tile(q, (n, 1)).astype(np.float32),
        opacity_logit=np.full(n, 4.0, dtype=np.float32),
        colors_dc=rgb_to_sh0(rgb).astype(np.float32))


def export_ksplat(ply: Path, workdir: Path) -> Path:
    """Runs the production ARTIFACT_GENERATION stage on `ply` as its SPLAT input; returns the KSPLAT it wrote."""
    ref = {"artifactId": "format-fixture-splat", "kind": "SPLAT", "stage": "FORMAT_VALIDATION_FIXTURE", "key": ply.name,
           "sha256": hashlib.sha256(ply.read_bytes()).hexdigest(), "sizeBytes": ply.stat().st_size}
    runner = CommandRunner(workdir / "stdout.log", workdir / "stderr.log")
    ctx = StageContext({"stage": "ARTIFACT_GENERATION", "runId": "format-fixture", "scanId": "format-fixture", "inputs": [ref]},
                       [InputFile(ref, ply)], workdir, logging.getLogger("viewer-scene"), runner, Toolchain(), Settings(), None)
    result = ArtifactGeneration().run(ctx)
    if result.status != "SUCCEEDED":
        raise RuntimeError(f"ARTIFACT_GENERATION failed: {result.error_code} {result.error_message}")
    return next(a.path for a in result.artifacts if a.kind == "KSPLAT")


def fixture_doc(splat_count: int, ply_sha: str, ksplat_sha: str) -> dict:
    r = rotation()
    w, x, y, z = matrix_to_quaternion(r)
    corners = [("floor corner (0, 0)", (0.0, 0.0, 0.0)), ("floor corner (6, 0)", (6.0, 0.0, 0.0)),
               ("floor corner (6, 4)", (6.0, 4.0, 0.0)), ("floor corner (0, 4)", (0.0, 4.0, 0.0)),
               ("red pillar top centre", (1.0, 1.0, PILLAR_HEIGHT))]
    return {
        "note": NOTE,
        "splat_count": splat_count,
        "files": {"scene.ply": {"sha256": ply_sha, "role": "SPLAT input"},
                  "scene.ksplat": {"sha256": ksplat_sha, "role": "ARTIFACT_GENERATION KSPLAT output"}},
        "reconstruction_to_canonical": {"scale": SCALE, "rotation_wxyz": [float(w), float(x), float(y), float(z)],
                                        "translation": [float(v) for v in T]},
        "control_points_by_construction": [
            {"label": label, "venue": list(p), "reconstruction": [float(v) for v in to_reconstruction(np.array(p))]}
            for label, p in corners],
        "pois_by_construction": [{"label": label, "canonical": [cx, cy, 0.0]} for label, (cx, cy), _ in PILLARS],
        "canonical_bounds": {"min": [float(v) for v in canonical_scene()[0].min(axis=0)],
                             "max": [float(v) for v in canonical_scene()[0].max(axis=0)]},
    }


def main() -> None:
    cloud = build_cloud()
    ply = write_ply(cloud, HERE / "scene.ply")
    with tempfile.TemporaryDirectory() as tmp:
        ksplat = export_ksplat(ply, Path(tmp))
        (HERE / "scene.ksplat").write_bytes(ksplat.read_bytes())
    sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
    doc = fixture_doc(len(cloud), sha(HERE / "scene.ply"), sha(HERE / "scene.ksplat"))
    (HERE / "fixture.json").write_text(json.dumps(doc, indent=2) + "\n", encoding="utf-8")
    print(f"{len(cloud)} Gaussians; scene.ply {doc['files']['scene.ply']['sha256']}; scene.ksplat {doc['files']['scene.ksplat']['sha256']}")


if __name__ == "__main__":
    main()
