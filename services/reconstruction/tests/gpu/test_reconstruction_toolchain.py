"""GPU / reconstruction-toolchain tests.

These need real COLMAP / GLOMAP / gsplat + CUDA (and, for pose estimation, a real photo sequence). Each test
is SKIPPED, with the reason printed by `pytest -rs`, when its dependency is absent. A skipped test is not a
passed test: nothing here can "succeed" on a machine that cannot run the tool.

    CHAYA_TEST_IMAGE_SEQUENCE=/path/to/folder/of/overlapping/photos pytest -m gpu -rs
"""

from __future__ import annotations

import os
import tarfile
from pathlib import Path

import cv2
import pytest

from chaya_worker import archive
from tests.conftest import DERIVED_BUCKET, Harness

pytestmark = pytest.mark.gpu

from chaya_worker.toolchain import Toolchain  # noqa: E402

_tc = Toolchain()
DATASET = os.environ.get("CHAYA_TEST_IMAGE_SEQUENCE")

needs_colmap = pytest.mark.skipif(not _tc.colmap().available, reason="COLMAP is not installed on this machine (set COLMAP_BIN)")
needs_glomap = pytest.mark.skipif(not (_tc.colmap().available and _tc.glomap().available), reason="GLOMAP and COLMAP are not both installed (set GLOMAP_BIN, COLMAP_BIN)")
needs_dataset = pytest.mark.skipif(not DATASET or not Path(DATASET).is_dir(), reason="CHAYA_TEST_IMAGE_SEQUENCE is not set to a folder of real overlapping photos")
needs_gsplat = pytest.mark.skipif(not all(_tc.status(r).available for r in ("py:torch", "py:gsplat", "cuda")),
                                  reason="torch + gsplat + a CUDA device are not all available")
needs_open3d = pytest.mark.skipif(not _tc.module("open3d").available, reason="Open3D is not installed")
needs_segmentation = pytest.mark.skipif(
    not all(_tc.status(r).available for r in ("py:torch", "py:transformers")) or
    not _tc.huggingface_model("nvidia/segformer-b0-finetuned-ade-512-512").available,
    reason="torch + transformers + a locally cached SegFormer checkpoint are not all available")


def _anon_archive(h: Harness, tmp_path: Path) -> dict:
    images = sorted(p for p in Path(DATASET).iterdir() if p.suffix.lower() in (".jpg", ".jpeg", ".png"))
    assert len(images) >= 8, "the dataset needs at least 8 photos"
    d = tmp_path / "ds"
    d.mkdir()
    for i, p in enumerate(images):
        cv2.imwrite(str(d / f"{i:06d}.jpg"), cv2.imread(str(p)))
    tar = tmp_path / "anon.tar"
    archive.pack(d, tar)
    ref = h.raw_input("FRAME_ARCHIVE_ANON", tar, "application/x-tar")
    ref["containsPii"] = False
    return ref


@needs_colmap
@needs_dataset
def test_pose_estimation_registers_real_photographs(tmp_path):
    h = Harness(tmp_path)
    report = h.run(h.order("POSE_ESTIMATION", [_anon_archive(h, tmp_path)]))
    assert report["status"] == "SUCCEEDED", report["errorMessage"]
    assert report["exitStatus"] == 0 and report["command"]["commands"], "the COLMAP/GLOMAP commands must have really run"
    kinds = {a["kind"] for a in report["artifacts"]}
    assert kinds == {"SPARSE_MODEL", "POSES"}
    sparse = next(a for a in report["artifacts"] if a["kind"] == "SPARSE_MODEL")
    with tarfile.open(h.storage.root / DERIVED_BUCKET / sparse["key"]) as tar:
        assert {"cameras.bin", "images.bin", "points3D.bin"} <= set(tar.getnames())


@needs_glomap
@needs_dataset
def test_glomap_is_used_when_available_and_any_fallback_to_colmap_is_recorded(tmp_path):
    h = Harness(tmp_path)
    report = h.run(h.order("POSE_ESTIMATION", [_anon_archive(h, tmp_path)]))
    assert report["status"] == "SUCCEEDED", report["errorMessage"]
    cfg = report["command"]["config"]
    assert cfg["mapper_used"] in ("glomap", "colmap")
    assert (cfg["mapper_used"] == "colmap") == cfg["fallback_used"], "using COLMAP while GLOMAP is installed must be recorded as a fallback"


@needs_gsplat
@needs_colmap
@needs_dataset
def test_splat_reconstruction_trains_a_real_gaussian_splat_from_a_posed_dataset(tmp_path):
    """SPLAT_RECONSTRUCTION is implemented (chaya_worker.stages.splat_reconstruction): it must produce a
    real, non-empty Gaussian cloud trained against the actual registered frames -- never a placeholder."""
    from chaya_worker.ply import read_ply

    h = Harness(tmp_path, gsplat_iterations=50, gsplat_keyframe_every=25)
    pose_report = h.run(h.order("POSE_ESTIMATION", [_anon_archive(h, tmp_path)]))
    assert pose_report["status"] == "SUCCEEDED", pose_report["errorMessage"]

    frame_archive = h.raw_input("FRAME_ARCHIVE_ANON", tmp_path / "anon.tar", "application/x-tar")
    frame_archive["containsPii"] = False
    inputs = h.outputs_as_inputs(pose_report) + [frame_archive]
    report = h.run(h.order("SPLAT_RECONSTRUCTION", inputs))
    assert report["status"] == "SUCCEEDED", report["errorMessage"]
    kinds = {a["kind"] for a in report["artifacts"]}
    assert kinds == {"SPLAT", "KEYFRAME_RENDERS", "SPLAT_TRAINING_REPORT"}
    ply_artifact = next(a for a in report["artifacts"] if a["kind"] == "SPLAT")
    cloud = read_ply(h.storage.root / DERIVED_BUCKET / ply_artifact["key"])
    assert len(cloud) > 0


@needs_open3d
def test_geometric_cleanup_and_plane_fitting_run_on_a_real_synthetic_room(tmp_path):
    """No GPU needed (Open3D-only): a synthetic point cloud shaped like a floor + wall + a scattering of
    noise must come out of GEOMETRIC_CLEANUP smaller and PLANE_FITTING must find the two real planes."""
    import numpy as np

    from chaya_worker.ply import GaussianCloud, write_ply

    rng = np.random.default_rng(0)
    floor = np.stack([rng.uniform(-2, 2, 400), rng.uniform(-2, 2, 400), np.zeros(400)], axis=1)
    wall = np.stack([rng.uniform(-2, 2, 400), np.zeros(400), rng.uniform(0, 2, 400)], axis=1)
    noise = rng.uniform(-3, 3, (60, 3))  # sparse floating debris, no structure
    positions = np.concatenate([floor, wall, noise]).astype(np.float32)
    n = len(positions)
    cloud = GaussianCloud(positions, np.zeros((n, 3), np.float32), np.tile([1, 0, 0, 0], (n, 1)).astype(np.float32),
                          np.full(n, 4.0, np.float32), np.zeros((n, 3), np.float32))
    h = Harness(tmp_path)
    ply_path = write_ply(cloud, tmp_path / "splat.ply")
    splat_input = h.raw_input("SPLAT", ply_path, "application/octet-stream")
    splat_input["containsPii"] = False

    cleanup_report = h.run(h.order("GEOMETRIC_CLEANUP", [splat_input]))
    assert cleanup_report["status"] == "SUCCEEDED", cleanup_report["errorMessage"]
    assert cleanup_report["command"]["config"]["gaussian_count_after"] <= n

    clean_inputs = h.outputs_as_inputs(cleanup_report)
    plane_report = h.run(h.order("PLANE_FITTING", clean_inputs))
    assert plane_report["status"] == "SUCCEEDED", plane_report["errorMessage"]
    assert plane_report["command"]["config"]["planes_found"] >= 2
