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
def test_the_reconstruction_stage_does_not_pretend_to_exist_even_when_the_toolchain_is_present(tmp_path):
    """Guard against fake success: until gsplat training is implemented this stage must FAIL, not produce a splat.
    When the implementation lands, replace this test with one that trains on a real posed dataset."""
    h = Harness(tmp_path)
    report = h.run(h.order("SPLAT_RECONSTRUCTION", []))
    assert report["status"] == "FAILED" and report["errorCode"] == "STAGE_NOT_IMPLEMENTED" and report["artifacts"] == []
