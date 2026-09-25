"""Gravity (the up direction) of a reconstruction, estimated from its own reconstructed structure.

COLMAP/GLOMAP put no axis of the reconstruction along gravity. The estimate here uses two independent,
real signals from the same reconstruction and requires them to agree:

1. Horizontal structural planes. PLANE_FITTING's RANSAC planes include the floor (and often a ceiling):
   large planes whose normal is parallel to gravity.
2. The registered cameras. A capture is walked holding the device upright, so the mean of the cameras'
   image-up directions (-Y of the OpenCV camera frame COLMAP uses, expressed in the reconstruction frame)
   points roughly away from gravity. That direction is too loose to be the answer (people tilt devices), but it
   is enough to tell which planes are horizontal and which way along their normal is up.

The floor is then the largest horizontal plane with the camera centres above it. Its normal, oriented
towards the cameras, is the estimated up direction. The estimate is reported with the evidence it rests on,
and none is produced (``status = NOT_ESTIMATED`` with a reason) when that evidence is missing or
inconsistent. Nothing here assumes any reconstruction axis is vertical.

This is a *structural* estimate: it cannot be better than the floor plane fit. A device-IMU gravity vector
recorded during capture would be a second, independent source; this capture pipeline does not record one
(docs/coordinate-frames.md, "Gravity").
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

import numpy as np

from .frames import quaternion_to_matrix

METHOD = "RECONSTRUCTED_FLOOR_PLANE"


@dataclass(frozen=True)
class Plane:
    """A fitted plane in reconstruction coordinates: unit normal (either sign), a point on it, and its support."""

    normal: np.ndarray
    point: np.ndarray
    inlier_count: int


@dataclass(frozen=True)
class GravityEstimate:
    up: np.ndarray  # unit, reconstruction frame
    floor_point: np.ndarray  # a point on the floor plane (centroid of its inliers), reconstruction frame
    floor_inlier_count: int
    camera_centroid: np.ndarray  # reconstruction frame
    camera_up_mean: np.ndarray  # unit, reconstruction frame
    camera_up_consistency: float  # mean resultant length of the camera up vectors, in [0, 1]
    camera_agreement_deg: float  # angle between `up` and `camera_up_mean`
    cameras_above_floor_fraction: float

    def to_dict(self) -> dict[str, Any]:
        return {
            "status": "ESTIMATED", "method": METHOD,
            "up_reconstruction": [float(v) for v in self.up],
            "floor_point_reconstruction": [float(v) for v in self.floor_point],
            "floor_inlier_count": self.floor_inlier_count,
            "camera_centroid_reconstruction": [float(v) for v in self.camera_centroid],
            "camera_up_mean_reconstruction": [float(v) for v in self.camera_up_mean],
            "camera_up_consistency": self.camera_up_consistency,
            "camera_agreement_deg": self.camera_agreement_deg,
            "cameras_above_floor_fraction": self.cameras_above_floor_fraction,
        }


def cameras_from_poses(poses: list[dict[str, Any]]) -> tuple[np.ndarray, np.ndarray]:
    """Camera centres and image-up directions, both in the reconstruction frame, from COLMAP poses
    (world-to-camera rotation quaternion wxyz and translation, as POSE_ESTIMATION's poses.json stores them).

    centre = -R^T t;  up = R^T (0, -1, 0)   (OpenCV camera frame: x right, y down, z forward)."""
    centres, ups = [], []
    for p in poses:
        r = quaternion_to_matrix(np.asarray(p["rotation_wxyz"], dtype=np.float64))
        t = np.asarray(p["translation"], dtype=np.float64)
        centres.append(-r.T @ t)
        ups.append(r.T @ np.array([0.0, -1.0, 0.0]))
    return np.array(centres).reshape(-1, 3), np.array(ups).reshape(-1, 3)


def estimate_gravity(planes: list[Plane], camera_centres: np.ndarray, camera_ups: np.ndarray, *,
                     max_camera_plane_angle_deg: float = 30.0, min_camera_up_consistency: float = 0.5,
                     min_cameras_above_fraction: float = 0.9) -> tuple[GravityEstimate | None, str | None]:
    """Returns (estimate, None) or (None, reason). Pure; see the module docstring for the method."""
    centres = np.asarray(camera_centres, dtype=np.float64).reshape(-1, 3)
    ups = np.asarray(camera_ups, dtype=np.float64).reshape(-1, 3)
    if len(centres) < 3:
        return None, f"only {len(centres)} registered cameras; at least 3 are needed"
    unit_ups = ups / np.linalg.norm(ups, axis=1, keepdims=True)
    resultant = unit_ups.mean(axis=0)
    consistency = float(np.linalg.norm(resultant))
    if consistency < min_camera_up_consistency:
        return None, (f"camera orientations disagree about up (consistency {consistency:.2f} < {min_camera_up_consistency}); "
                      "the capture mixes device orientations")
    cam_up = resultant / consistency
    centroid = centres.mean(axis=0)
    cos_limit = np.cos(np.radians(max_camera_plane_angle_deg))

    best: tuple[Plane, np.ndarray, float] | None = None
    for plane in planes:
        n = np.asarray(plane.normal, dtype=np.float64)
        n = n / np.linalg.norm(n)
        if n @ cam_up < 0:
            n = -n
        if n @ cam_up < cos_limit:
            continue  # not horizontal according to the cameras
        above = float(np.mean((centres - plane.point) @ n > 0))
        if above < min_cameras_above_fraction:
            continue  # a ceiling (cameras below it), or a plane the capture walked through
        if best is None or plane.inlier_count > best[0].inlier_count:
            best = (plane, n, above)
    if best is None:
        return None, (f"no fitted plane is within {max_camera_plane_angle_deg} degrees of the cameras' up direction with at "
                      f"least {min_cameras_above_fraction:.0%} of the cameras above it; no floor plane to take gravity from")
    plane, up, above = best
    agreement = float(np.degrees(np.arccos(np.clip(up @ cam_up, -1.0, 1.0))))
    return GravityEstimate(up, np.asarray(plane.point, dtype=np.float64), plane.inlier_count, centroid, cam_up,
                           consistency, agreement, above), None


def not_estimated(reason: str) -> dict[str, Any]:
    return {"status": "NOT_ESTIMATED", "method": METHOD, "reason": reason}
