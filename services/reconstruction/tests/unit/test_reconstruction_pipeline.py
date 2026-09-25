"""Pure unit tests for the SPLAT_RECONSTRUCTION..ARTIFACT_GENERATION pipeline: PLY/ksplat I/O, COLMAP TEXT
parsing, semantic class bucketing, camera-projection geometry, the artifact manifest and the parts of the
cleanup benchmark that do not need Open3D/gsplat/CUDA. No external tools, no services."""

from __future__ import annotations

import json

import numpy as np
import pytest

from chaya_worker.benchmarks.cleanup_benchmark import METHODS, run_methods
from chaya_worker.colmap_txt import parse_cameras_txt, parse_points3d_txt
from chaya_worker.geometry_cleanup import opacity_threshold_mask
from chaya_worker.ksplat import KsplatUnsupported, decode, encode
from chaya_worker.manifest import build_manifest
from chaya_worker.ply import GaussianCloud, read_ply, write_ply
from chaya_worker.semantic_classes import CLUTTER, FLOOR, FURNITURE, UNKNOWN, WALL, bucket_all, bucket_label
from chaya_worker.settings import Settings
from chaya_worker.stages.semantic_segmentation import project_points, vote_labels
from chaya_worker.stages.splat_reconstruction import build_cameras, initial_scale_log, quat_wxyz_to_rotmat
from chaya_worker.toolchain import Toolchain


def _random_cloud(n: int, seed: int = 0) -> GaussianCloud:
    rng = np.random.default_rng(seed)
    return GaussianCloud(
        positions=rng.normal(size=(n, 3)).astype(np.float32),
        scales_log=rng.normal(size=(n, 3)).astype(np.float32),
        rotations_wxyz=rng.normal(size=(n, 4)).astype(np.float32),
        opacity_logit=rng.normal(size=(n,)).astype(np.float32),
        colors_dc=rng.normal(size=(n, 3)).astype(np.float32))


# ---- ply.py -----------------------------------------------------------------------------------

def test_ply_round_trips_a_gaussian_cloud(tmp_path):
    cloud = _random_cloud(37)
    path = write_ply(cloud, tmp_path / "splat.ply")
    back = read_ply(path)
    assert len(back) == 37
    for field in ("positions", "scales_log", "rotations_wxyz", "opacity_logit", "colors_dc"):
        assert np.allclose(getattr(cloud, field), getattr(back, field), atol=1e-5)


def test_gaussian_cloud_subset_and_derived_quantities():
    cloud = _random_cloud(10)
    mask = np.array([True, False] * 5)
    sub = cloud.subset(mask)
    assert len(sub) == 5
    assert np.array_equal(sub.positions, cloud.positions[mask])
    assert np.all(cloud.opacities() >= 0) and np.all(cloud.opacities() <= 1)
    assert np.all(cloud.scales() > 0)
    norm = np.linalg.norm(cloud.rotations_normalized(), axis=1)
    assert np.allclose(norm, 1.0, atol=1e-5)


def test_ply_rejects_a_non_ply_file(tmp_path):
    p = tmp_path / "not.ply"
    p.write_bytes(b"not a ply file at all")
    with pytest.raises(ValueError, match="not a PLY file"):
        read_ply(p)


# ---- ksplat.py ----------------------------------------------------------------------------------

# Viewer compatibility is NOT proven here: apps/web/lib/ksplat-compat.test.ts loads these files with the pinned
# GaussianSplats3D KSplatLoader. These tests pin the byte layout that loader was verified against, and the
# diagnostic decoder, so an accidental layout change fails fast in the worker's own suite as well.

def test_ksplat_headers_match_the_pinned_viewer_layout():
    blob = encode(_random_cloud(10))
    assert len(blob) == 4096 + 1024 + 10 * 44
    header = np.frombuffer(blob, dtype=np.uint8, count=4096)
    assert (header[0], header[1]) == (0, 1)  # KSplatLoader.checkVersion: 0.>=1
    assert header.view("<u4")[1:5].tolist() == [1, 1, 10, 10]  # max sections, sections, max splats, splats
    assert header.view("<u2")[10] == 0  # compression level
    section = np.frombuffer(blob, dtype=np.uint8, count=1024, offset=4096)
    assert section.view("<u4")[[0, 1, 7]].tolist() == [10, 10, 440]
    assert section.view("<u2")[20] == 0  # spherical-harmonics degree


def test_ksplat_records_hold_linear_scale_wxyz_rotation_and_library_colour_bytes():
    cloud = _random_cloud(64)
    stored = decode(encode(cloud))  # diagnostic read-back of the stored values, not a compatibility check
    np.testing.assert_array_equal(stored["centers"], cloud.positions.astype(np.float32))
    np.testing.assert_allclose(stored["scales"], np.exp(cloud.scales_log.astype(np.float64)), rtol=1e-6)
    np.testing.assert_allclose(stored["rotations_wxyz"], cloud.rotations_normalized(), atol=1e-6)
    expected_rgb = np.clip(np.floor((0.5 + 0.28209479177387814 * cloud.colors_dc.astype(np.float64)) * 255), 0, 255)
    np.testing.assert_array_equal(stored["rgba"][:, :3], expected_rgb)
    np.testing.assert_array_equal(stored["rgba"][:, 3], np.floor(cloud.opacities().astype(np.float64) * 255))


def test_ksplat_refuses_a_zero_quaternion():
    cloud = _random_cloud(3)
    cloud.rotations_wxyz[1] = 0
    with pytest.raises(ValueError, match="quaternion"):
        encode(cloud)


def test_ksplat_refuses_unimplemented_compression_levels():
    cloud = _random_cloud(5)
    with pytest.raises(KsplatUnsupported):
        encode(cloud, compression_level=1)


# ---- colmap_txt.py ------------------------------------------------------------------------------

def test_parse_cameras_txt_simple_radial():
    text = "# comment\n1 SIMPLE_RADIAL 640 480 500.0 320.0 240.0 0.01\n"
    cams = parse_cameras_txt(text)
    assert cams[1] == {"model": "SIMPLE_RADIAL", "width": 640, "height": 480, "fx": 500.0, "fy": 500.0, "cx": 320.0, "cy": 240.0}


def test_parse_cameras_txt_rejects_unsupported_model():
    with pytest.raises(ValueError, match="unsupported"):
        parse_cameras_txt("1 OPENCV_FISHEYE 640 480 1 2 3 4 5 6 7 8\n")


def test_parse_points3d_txt():
    text = "3 1.0 2.0 3.0 255 0 0 0.5 1 0 2 0\n"
    doc = parse_points3d_txt(text)
    assert doc["ids"] == [3] and doc["xyz"] == [[1.0, 2.0, 3.0]] and doc["rgb"] == [[255, 0, 0]] and doc["track_length"] == [2]


# ---- semantic_classes.py -------------------------------------------------------------------------

@pytest.mark.parametrize("name,expected", [("floor, wood", FLOOR), ("wall", WALL), ("brick wall", WALL),
                                           ("chair", FURNITURE), ("sofa; couch", FURNITURE), ("sky", CLUTTER),
                                           ("person", CLUTTER)])
def test_bucket_label(name, expected):
    assert bucket_label(name) == expected


def test_bucket_all_maps_a_full_id2label_table():
    id2label = {0: "wall", 1: "floor, flooring", 2: "person", 3: "chair"}
    assert bucket_all(id2label) == {0: WALL, 1: FLOOR, 2: CLUTTER, 3: FURNITURE}


# ---- geometry_cleanup.py (the parts that don't need Open3D) ---------------------------------------

def test_opacity_threshold_mask_keeps_only_confident_gaussians():
    cloud = _random_cloud(5)
    cloud.opacity_logit[:] = [10, -10, 10, -10, 10]  # sigmoid(10) ~ 1, sigmoid(-10) ~ 0
    mask = opacity_threshold_mask(cloud, threshold=0.5)
    assert list(mask) == [True, False, True, False, True]


# ---- splat_reconstruction.py pure helpers ----------------------------------------------------------

def test_quat_wxyz_to_rotmat_identity_is_the_identity_matrix():
    r = quat_wxyz_to_rotmat(np.array([1.0, 0.0, 0.0, 0.0]))
    assert np.allclose(r, np.eye(3))


def test_build_cameras_skips_poses_with_unknown_camera_id():
    poses = [{"name": "a.jpg", "camera_id": 1, "rotation_wxyz": [1, 0, 0, 0], "translation": [0, 0, 0]},
             {"name": "b.jpg", "camera_id": 99, "rotation_wxyz": [1, 0, 0, 0], "translation": [0, 0, 0]}]
    models = {1: {"model": "SIMPLE_RADIAL", "width": 100, "height": 100, "fx": 50, "fy": 50, "cx": 50, "cy": 50}}
    cams = build_cameras(poses, models)
    assert len(cams) == 1 and cams[0]["name"] == "a.jpg"
    assert cams[0]["viewmat"].shape == (4, 4) and np.allclose(cams[0]["viewmat"], np.eye(4))
    assert np.allclose(cams[0]["K"], [[50, 0, 50], [0, 50, 50], [0, 0, 1]])


def test_initial_scale_log_is_finite_and_grows_with_point_spacing():
    dense = np.random.default_rng(0).normal(scale=0.1, size=(50, 3))
    sparse = np.random.default_rng(0).normal(scale=10.0, size=(50, 3))
    dense_scale = initial_scale_log(dense)
    sparse_scale = initial_scale_log(sparse)
    assert np.isfinite(dense_scale).all() and np.isfinite(sparse_scale).all()
    assert sparse_scale.mean() > dense_scale.mean()


# ---- semantic_segmentation.py pure helpers ----------------------------------------------------------

def test_project_points_and_vote_labels():
    viewmat = np.eye(4)
    K = np.array([[100.0, 0, 50], [0, 100.0, 50], [0, 0, 1]])
    positions = np.array([[0.0, 0.0, 5.0], [0.0, 0.0, -5.0]])  # one in front, one behind the camera
    px, visible = project_points(positions, viewmat, K, 100, 100)
    assert visible[0] and not visible[1]

    class_votes = np.array([[0.0, 0.0, 0.0, 0.9], [0.0, 0.0, 0.0, 0.0]])
    confidence_sum = np.array([0.9, 0.0])
    seen = np.array([True, False])
    labels = vote_labels(class_votes, confidence_sum, seen, min_confidence=0.5)
    assert labels == [CLUTTER, UNKNOWN]


# ---- manifest.py ---------------------------------------------------------------------------------

def test_build_manifest_covers_upstream_and_generated_artifacts():
    manifest = build_manifest(run_id="r1", scan_id="s1", source_scan_version="v3", worker_version="0.1.0",
                              processing_configuration={"min_frames": 10},
                              upstream_artifacts=[{"name": "poses.json", "kind": "POSES", "sha256": "a" * 64, "sizeBytes": 10}],
                              generated_artifacts=[{"name": "scene.ksplat", "kind": "KSPLAT", "sha256": "b" * 64, "sizeBytes": 20}])
    assert manifest["runId"] == "r1" and manifest["sourceScanVersion"] == "v3" and len(manifest["artifacts"]) == 2
    ksplat_entry = next(a for a in manifest["artifacts"] if a["kind"] == "KSPLAT")
    assert ksplat_entry["checksum"] == {"algorithm": "sha256", "value": "b" * 64}
    assert ksplat_entry["processingConfiguration"] == {"min_frames": 10}
    assert json.loads(json.dumps(manifest)) == manifest  # every field must be JSON-serialisable


# ---- benchmarks/cleanup_benchmark.py (methods that don't need Open3D/gsplat/CUDA) ------------------

def test_benchmark_always_computes_the_two_dependency_free_methods_and_is_honest_about_the_rest():
    cloud = _random_cloud(20)
    toolchain = Toolchain()
    results = {r.method: r for r in run_methods(cloud, labels=None, settings=Settings(), toolchain=toolchain)}
    assert set(results) == set(METHODS)

    assert results["no_cleanup"].available and results["no_cleanup"].point_count_after == 20
    assert results["opacity_threshold"].available and results["opacity_threshold"].wall_clock_seconds is not None
    assert results["no_cleanup"].psnr is None and results["no_cleanup"].psnr_reason

    if not toolchain.module("open3d").available:
        for method in ("statistical_outlier", "density_outlier", "semantic_aware"):
            assert not results[method].available and results[method].reason
            assert results[method].point_count_after is None  # never fabricated when unavailable
