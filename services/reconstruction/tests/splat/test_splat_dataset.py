"""The GPU acceptance test's synthetic dataset (tests/gpu/splat_dataset.py) is itself a valid posed dataset: checked on
the CPU, so a GPU run never fails because of its fixture. The binary model is also converted by real COLMAP inside the
GPU acceptance test (and was checked with `colmap model_converter` when this test was written)."""

from __future__ import annotations

import json
import struct
from pathlib import Path

import numpy as np
import pytest

torch = pytest.importorskip("torch", reason="torch is not installed (validate_camera lives in the torch-based training module)")

from chaya_worker.splat_training import validate_camera  # noqa: E402
from chaya_worker.stages.splat_reconstruction import quat_wxyz_to_rotmat  # noqa: E402
from tests.gpu import splat_dataset as ds  # noqa: E402


@pytest.fixture(scope="module")
def dataset(tmp_path_factory):
    return ds.rendered_room_corner(tmp_path_factory.mktemp("room"), n_points=600)


def test_every_camera_is_in_the_training_convention(dataset):
    assert len(dataset.cameras) == 16 and dataset.synthetic
    for cam in dataset.cameras:
        validate_camera({**cam, "image": dataset.images[cam["name"]]})


def test_poses_json_round_trips_to_the_same_world_to_camera_matrices(dataset):
    poses = json.loads(dataset.poses_json.read_text())["poses"]
    for p, cam in zip(poses, dataset.cameras, strict=True):
        assert np.allclose(quat_wxyz_to_rotmat(np.array(p["rotation_wxyz"])), cam["viewmat"][:3, :3], atol=1e-9)
        assert np.allclose(p["translation"], cam["viewmat"][:3, 3])


def test_points_are_seen_where_their_tracks_say_with_their_colour(dataset, tmp_path):
    import tarfile

    with tarfile.open(dataset.sparse_tar) as tar:
        tar.extractall(tmp_path, filter="data")
    data = (tmp_path / "points3D.bin").read_bytes()
    (n,) = struct.unpack_from("<Q", data, 0)
    off, errors, colour_diff = 8, [], []
    for _ in range(n):
        _pid, x, y, z, r, g, b, _err = struct.unpack_from("<Q3d3Bd", data, off)
        off += struct.calcsize("<Q3d3Bd")
        (tl,) = struct.unpack_from("<Q", data, off)
        off += 8
        for _ in range(tl):
            image_id, _idx = struct.unpack_from("<II", data, off)
            off += 8
            cam = dataset.cameras[image_id - 1]
            pc = cam["viewmat"][:3, :3] @ np.array([x, y, z]) + cam["viewmat"][:3, 3]
            u, v = cam["K"][0, 0] * pc[0] / pc[2] + cam["K"][0, 2], cam["K"][1, 1] * pc[1] / pc[2] + cam["K"][1, 2]
            errors.append(0 <= u < cam["width"] and 0 <= v < cam["height"])
            seen = dataset.images[cam["name"]][int(v), int(u)]
            colour_diff.append(np.abs(seen - np.array([r, g, b]) / 255.0).max())
    assert off == len(data), "points3D.bin parses to its exact length"
    assert n == dataset.points and all(errors)
    assert np.median(colour_diff) < 0.08, "a point's colour is what the views show at its projection"


# ---- a real COLMAP project (CHAYA_SPLAT_DATASET) ------------------------------------------------------------------------


def _colmap_project(dataset, root):
    """A COLMAP project laid out as CHAYA_SPLAT_DATASET expects (images/ and sparse/0), from the synthetic scene, with JPEG
    frames as real captures have, and a converter standing in for `colmap model_converter`: it writes COLMAP's
    documented TXT for exactly that model. (CI's CPU job has no COLMAP; the real converter reads the same model in the
    GPU acceptance test.)"""
    import cv2

    names = [c["name"].replace(".png", ".jpg") for c in dataset.cameras]
    (root / "images").mkdir(parents=True)
    for cam, name in zip(dataset.cameras, names, strict=True):
        rgb8 = (dataset.images[cam["name"]] * 255).round().astype(np.uint8)
        cv2.imwrite(str(root / "images" / name), cv2.cvtColor(rgb8, cv2.COLOR_RGB2BGR), [cv2.IMWRITE_JPEG_QUALITY, 90])
    (root / "sparse" / "0").mkdir(parents=True)
    (root / "sparse" / "0" / "cameras.bin").write_bytes(b"")  # read only by the stand-in converter below

    def convert(argv):
        out = Path(argv[argv.index("--output_path") + 1])
        (out / "cameras.txt").write_text(f"1 PINHOLE {ds.W} {ds.H} {ds.F} {ds.F} {ds.W / 2} {ds.H / 2}\n")
        lines = []
        for image_id, (cam, name) in enumerate(zip(dataset.cameras, names, strict=True), start=1):
            q, t = ds._quat_wxyz(cam["viewmat"][:3, :3]), cam["viewmat"][:3, 3]
            lines += [f"{image_id} {' '.join(repr(float(x)) for x in (*q, *t))} 1 {name}", "10.0 10.0 -1"]
        (out / "images.txt").write_text("\n".join(lines) + "\n")
        (out / "points3D.txt").write_text("".join(f"{i} {i * 0.01} 0.5 0.5 128 128 128 0.5 1 0\n" for i in range(1, 9)))

    return names, convert


def test_a_real_colmap_project_yields_the_cameras_and_ground_truth_the_stage_trains_with(dataset, tmp_path):
    """The acceptance test only measures whether training learned (PSNR over the views) when the dataset has cameras; a
    real COLMAP project must therefore have them, with the ground truth the stage trains against."""
    import tarfile

    import cv2

    names, convert = _colmap_project(dataset, tmp_path / "project")
    real = ds.from_colmap_project(tmp_path / "project", tmp_path / "out", "colmap", convert)
    assert not real.synthetic and [c["name"] for c in real.cameras] == names
    for cam, synthetic_cam in zip(real.cameras, dataset.cameras, strict=True):
        validate_camera({**cam, "image": real.images[cam["name"]]})
        assert np.allclose(cam["viewmat"], synthetic_cam["viewmat"], atol=1e-9) and np.allclose(cam["K"], synthetic_cam["K"])
    with tarfile.open(real.frames_tar) as tar:
        tar.extractall(tmp_path / "frames", filter="data")
    for name in names:  # the frames the stage trains on are the project's own files, not a lossy re-encode
        original = (tmp_path / "project" / "images" / name).read_bytes()
        assert (tmp_path / "frames" / name).read_bytes() == original
        decoded = cv2.cvtColor(cv2.imdecode(np.frombuffer(original, np.uint8), cv2.IMREAD_COLOR), cv2.COLOR_BGR2RGB)
        assert np.array_equal(real.images[name], decoded.astype(np.float32) / 255.0)


def test_a_colmap_project_missing_a_registered_image_is_refused(dataset, tmp_path):
    names, convert = _colmap_project(dataset, tmp_path / "project")
    (tmp_path / "project" / "images" / names[3]).unlink()
    with pytest.raises(FileNotFoundError, match=names[3]):
        ds.from_colmap_project(tmp_path / "project", tmp_path / "out", "colmap", convert)
