"""chaya_worker.gravity: up direction from the floor plane plus camera orientations.

The scene is a synthetic mathematical fixture -- a floor at z=0, a ceiling at z=3 and cameras walking at 1.5 m
looking horizontally -- that is then moved into an arbitrary "reconstruction" frame with a known similarity,
exactly the ambiguity COLMAP leaves. The estimator must recover the true up in that frame."""

from __future__ import annotations

import numpy as np
import pytest

from chaya_worker.frames import Similarity, matrix_to_quaternion, quaternion_to_matrix
from chaya_worker.gravity import Plane, cameras_from_poses, estimate_gravity

GAUGE = Similarity(0.37, quaternion_to_matrix(np.array([0.4462, 0.2712, -0.7232, 0.4520])), np.array([12.5, -4.25, 1.75]))
TO_RECON = GAUGE.inverse()


def _camera_pose(centre_c: np.ndarray, yaw_deg: float) -> dict:
    """A COLMAP world-to-camera pose for a camera at `centre_c` (canonical) looking horizontally along `yaw`,
    image-up = canonical +Z, then expressed in the reconstruction frame."""
    yaw = np.radians(yaw_deg)
    forward = np.array([np.cos(yaw), np.sin(yaw), 0.0])
    down = np.array([0.0, 0.0, -1.0])  # OpenCV camera y points down in the image
    right = np.cross(down, forward)
    r_cw_canonical = np.stack([right, down, forward])  # rows: camera axes in canonical
    # In the reconstruction frame the camera axes are rotated by TO_RECON's rotation.
    r_cw = r_cw_canonical @ GAUGE.rotation
    centre_r = TO_RECON.apply(centre_c)
    t = -r_cw @ centre_r
    return {"rotation_wxyz": matrix_to_quaternion(r_cw).tolist(), "translation": t.tolist(), "name": "x.jpg", "camera_id": 1}


def _scene(n_cameras: int = 12):
    poses = [_camera_pose(np.array([np.cos(a) * 3, np.sin(a) * 2, 1.5]), np.degrees(a) + 90)
             for a in np.linspace(0, 2 * np.pi, n_cameras, endpoint=False)]
    planes = [
        Plane(TO_RECON.apply_direction(np.array([0.0, 0.0, 1.0])), TO_RECON.apply(np.array([0.0, 0.0, 0.0])), 5000),  # floor
        Plane(TO_RECON.apply_direction(np.array([0.0, 0.0, -1.0])), TO_RECON.apply(np.array([0.0, 0.0, 3.0])), 8000),  # ceiling
        Plane(TO_RECON.apply_direction(np.array([1.0, 0.0, 0.0])), TO_RECON.apply(np.array([-4.0, 0.0, 1.0])), 9000),  # wall
    ]
    return poses, planes


def test_camera_centres_and_up_vectors_come_from_the_colmap_convention():
    centre = np.array([1.0, 2.0, 1.5])
    centres, ups = cameras_from_poses([_camera_pose(centre, 30.0)])
    np.testing.assert_allclose(centres[0], TO_RECON.apply(centre), atol=1e-9)
    np.testing.assert_allclose(GAUGE.apply_direction(ups[0]), [0.0, 0.0, 1.0], atol=1e-9)


def test_recovers_true_up_in_an_arbitrary_reconstruction_frame_and_picks_the_floor_not_the_ceiling():
    poses, planes = _scene()
    centres, ups = cameras_from_poses(poses)
    estimate, reason = estimate_gravity(planes, centres, ups)
    assert reason is None and estimate is not None
    np.testing.assert_allclose(GAUGE.apply_direction(estimate.up), [0.0, 0.0, 1.0], atol=1e-9)
    assert estimate.floor_inlier_count == 5000, "the ceiling has more inliers but the cameras are below it"
    assert estimate.cameras_above_floor_fraction == 1.0
    assert estimate.camera_agreement_deg == pytest.approx(0.0, abs=1e-6)
    assert GAUGE.apply(estimate.floor_point)[2] == pytest.approx(0.0, abs=1e-9)


def test_no_estimate_when_no_horizontal_plane_has_cameras_above_it():
    poses, planes = _scene()
    centres, ups = cameras_from_poses(poses)
    estimate, reason = estimate_gravity(planes[1:], centres, ups)  # ceiling and wall only
    assert estimate is None and "no floor plane" in reason


def test_no_estimate_when_camera_orientations_disagree():
    poses, planes = _scene()
    centres, ups = cameras_from_poses(poses)
    ups[::2] = -ups[::2]  # half the frames upside down: no consistent up
    estimate, reason = estimate_gravity(planes, centres, ups)
    assert estimate is None and "disagree" in reason


def test_no_estimate_from_too_few_cameras():
    poses, planes = _scene(n_cameras=2)
    centres, ups = cameras_from_poses(poses)
    estimate, reason = estimate_gravity(planes, centres, ups)
    assert estimate is None and "at least 3" in reason
