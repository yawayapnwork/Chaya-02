"""The committed viewer-compatibility fixture (packages/contracts/fixtures/ksplat) must be exactly what the production
encoder and PLY writer produce today. apps/web/lib/ksplat-compat.test.ts proves those committed bytes load correctly in
the pinned GaussianSplats3D; this test is what ties that proof to the current production code. If it fails, the
encoder changed: regenerate the fixture (generate_ksplat_fixture.py) and re-run the Node compatibility test."""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np

from chaya_worker.ksplat import encode
from chaya_worker.ply import GaussianCloud, write_ply

FIXTURE = Path(__file__).resolve().parents[4] / "packages" / "contracts" / "fixtures" / "ksplat"


def _cloud() -> GaussianCloud:
    doc = json.loads((FIXTURE / "cloud.json").read_text(encoding="utf-8"))
    return GaussianCloud(np.array(doc["positions"], np.float32), np.array(doc["scales_log"], np.float32),
                         np.array(doc["rotations_wxyz"], np.float32), np.array(doc["opacity_logit"], np.float32),
                         np.array(doc["colors_dc"], np.float32))


def test_committed_ksplat_fixture_is_the_production_encoder_output():
    assert (FIXTURE / "scene.ksplat").read_bytes() == encode(_cloud())


def test_committed_ply_fixture_is_the_production_ply_writer_output(tmp_path):
    written = write_ply(_cloud(), tmp_path / "scene.ply")
    assert (FIXTURE / "scene.ply").read_bytes() == written.read_bytes()
