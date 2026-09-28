"""The committed viewer FORMAT-VALIDATION fixture (packages/contracts/fixtures/viewer-scene) must be exactly what the
production exporter writes today. The web viewer's tests (apps/web/lib/ksplat-compat.test.ts, e2e/ksplat-viewer.spec.ts,
e2e/viewer.spec.ts) and the full-stack viewer check load those committed bytes; this test is what ties them to the
current ARTIFACT_GENERATION stage. If it fails, the exporter changed: regenerate the fixture
(generate_viewer_scene.py) and re-run the web tests.

The fixture is a synthetic scene, not a reconstruction."""

from __future__ import annotations

import hashlib
import importlib.util
import json
from pathlib import Path

import numpy as np

from chaya_worker.frames import quaternion_to_matrix
from chaya_worker.ply import read_ply

FIXTURE = Path(__file__).resolve().parents[4] / "packages" / "contracts" / "fixtures" / "viewer-scene"


def _generator():
    spec = importlib.util.spec_from_file_location("generate_viewer_scene", FIXTURE / "generate_viewer_scene.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_committed_ksplat_is_the_artifact_generation_stage_output_for_the_committed_ply(tmp_path):
    produced = _generator().export_ksplat(FIXTURE / "scene.ply", tmp_path)
    assert produced.read_bytes() == (FIXTURE / "scene.ksplat").read_bytes()


def test_committed_ply_is_the_generator_cloud():
    # Values, not bytes: the cloud is computed with float64 trigonometry, whose last bit may differ between platforms.
    expected, committed = _generator().build_cloud(), read_ply(FIXTURE / "scene.ply")
    assert len(committed) == len(expected)
    for field in ("positions", "scales_log", "rotations_wxyz", "opacity_logit", "colors_dc"):
        np.testing.assert_allclose(getattr(committed, field), getattr(expected, field), atol=1e-6, err_msg=field)


def test_fixture_metadata_matches_the_files_and_the_scene():
    doc = json.loads((FIXTURE / "fixture.json").read_text(encoding="utf-8"))
    assert "NOT a reconstruction" in doc["note"]
    for name, meta in doc["files"].items():
        assert hashlib.sha256((FIXTURE / name).read_bytes()).hexdigest() == meta["sha256"], name
    cloud = read_ply(FIXTURE / "scene.ply")
    assert doc["splat_count"] == len(cloud)
    t = doc["reconstruction_to_canonical"]
    r = quaternion_to_matrix(np.array(t["rotation_wxyz"]))
    canonical = t["scale"] * cloud.positions.astype(np.float64) @ r.T + np.array(t["translation"])
    np.testing.assert_allclose(canonical.min(axis=0), doc["canonical_bounds"]["min"], atol=1e-5)
    np.testing.assert_allclose(canonical.max(axis=0), doc["canonical_bounds"]["max"], atol=1e-5)
    for cp in doc["control_points_by_construction"]:
        np.testing.assert_allclose(t["scale"] * r @ np.array(cp["reconstruction"]) + t["translation"], cp["venue"], atol=1e-9)
