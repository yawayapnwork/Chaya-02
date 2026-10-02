"""A synthetic distorted-camera fixture with known ground truth (review G-1).

A known OPENCV camera photographs known 3D points. Each point is drawn as a small Gaussian blob centred exactly where
the camera model (distortion included) puts it, so the photographs carry the lens distortion and every blob's true
position in every view is known. Nothing here estimates a calibration: the parameters are made up for the test and
are not a physical camera's.
"""

from __future__ import annotations

import numpy as np

from chaya_worker.camera_model import Camera

# fx, fy, cx, cy (COLMAP convention), k1, k2, p1, p2. Barrel distortion of a wide phone lens's order of magnitude.
TRUE_CAMERA = Camera("OPENCV", 640, 480, (520.0, 515.0, 322.4, 237.9, -0.25, 0.08, 0.0012, -0.0008))
BLOB_SIGMA_PX = 2.5
BACKGROUND, PEAK = 40.0, 230.0


def scene_points() -> np.ndarray:
    """A 9 x 7 grid of points on a plane two units in front of the reference camera, reaching into the image corners."""
    xs, ys = np.meshgrid(np.linspace(-1.15, 1.15, 9), np.linspace(-0.85, 0.85, 7))
    return np.stack([xs.ravel(), ys.ravel(), np.full(xs.size, 2.0)], axis=1)


def rotation(axis: str, degrees: float) -> np.ndarray:
    c, s = np.cos(np.radians(degrees)), np.sin(np.radians(degrees))
    return {"x": np.array([[1, 0, 0], [0, c, -s], [0, s, c]]), "y": np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]]),
            "z": np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])}[axis]


def viewmats() -> list[np.ndarray]:
    """World-to-camera transforms of a few views of the grid (small rotations and offsets)."""
    out = []
    for axis, deg, t in (("y", 0.0, (0.0, 0.0, 0.0)), ("y", 3.0, (0.05, 0.0, 0.02)), ("x", -2.5, (0.0, 0.04, -0.03)),
                         ("z", 4.0, (-0.03, 0.02, 0.05))):
        m = np.eye(4)
        m[:3, :3], m[:3, 3] = rotation(axis, deg), t
        out.append(m)
    return out


def to_camera(points: np.ndarray, viewmat: np.ndarray) -> np.ndarray:
    return (viewmat[:3, :3] @ points.T).T + viewmat[:3, 3]


def render(camera: Camera, viewmat: np.ndarray, points: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """(BGR uint8 photograph, the (N, 2) true blob centres in it, COLMAP pixel convention)."""
    centres = camera.project(to_camera(points, viewmat))
    u = np.arange(camera.width) + 0.5  # pixel centres
    v = np.arange(camera.height) + 0.5
    img = np.full((camera.height, camera.width), BACKGROUND)
    for cx, cy in centres:
        gx = np.exp(-0.5 * ((u - cx) / BLOB_SIGMA_PX) ** 2)
        gy = np.exp(-0.5 * ((v - cy) / BLOB_SIGMA_PX) ** 2)
        img += (PEAK - BACKGROUND) * np.outer(gy, gx)
    gray = np.clip(np.rint(img), 0, 255).astype(np.uint8)
    return np.dstack([gray, gray, gray]), centres


def centroid(image: np.ndarray, near: np.ndarray, radius: float) -> np.ndarray | None:
    """Background-subtracted intensity centroid (COLMAP convention) of the blob near `near`, or None at the border."""
    gray = image if image.ndim == 2 else image[..., 0]
    x0, y0 = int(np.floor(near[0] - radius)), int(np.floor(near[1] - radius))
    x1, y1 = int(np.ceil(near[0] + radius)), int(np.ceil(near[1] + radius))
    if x0 < 0 or y0 < 0 or x1 > gray.shape[1] or y1 > gray.shape[0]:
        return None
    window = gray[y0:y1, x0:x1].astype(np.float64)
    weights = np.clip(window - np.median(window), 0, None)
    if weights.sum() <= 0:
        return None
    ys, xs = np.mgrid[y0:y1, x0:x1]
    return np.array([((xs + 0.5) * weights).sum() / weights.sum(), ((ys + 0.5) * weights).sum() / weights.sum()])
