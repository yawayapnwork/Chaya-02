"""The GPU acceptance test's synthetic dataset (tests/gpu/splat_dataset.py) is itself a valid posed dataset: checked on
the CPU, so a GPU run never fails because of its fixture. The binary model is also converted by real COLMAP inside the
GPU acceptance test (and was checked with `colmap model_converter` when this test was written)."""

from __future__ import annotations

import json
import struct

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
