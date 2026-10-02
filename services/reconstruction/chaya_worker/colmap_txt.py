"""Parsers for the COLMAP TEXT model format (cameras.txt / points3D.txt), the same output produced by
`colmap model_converter --output_type TXT` that chaya_worker.stages.pose_estimation already parses
images.txt from. Pure functions, independent of any running tool, so they are unit-testable without COLMAP
installed.
"""

from __future__ import annotations

from typing import Any

from .camera_model import Camera, CameraModelError


def parse_cameras_txt(text: str) -> dict[int, Camera]:
    """`CAMERA_ID MODEL WIDTH HEIGHT PARAMS[]`, every parameter kept, distortion coefficients included (review G-1:
    this parser used to keep fx, fy, cx, cy and drop the rest). A model chaya_worker.camera_model does not support
    raises UnsupportedCameraModel (a ValueError) naming it."""
    cameras: dict[int, Camera] = {}
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split()
        camera_id = int(parts[0])
        try:
            cameras[camera_id] = Camera(parts[1], int(parts[2]), int(parts[3]), tuple(float(p) for p in parts[4:]))
        except CameraModelError as exc:
            raise type(exc)(f"camera {camera_id}: {exc}") from exc
    return cameras


def parse_points3d_txt(text: str) -> dict[str, Any]:
    """`POINT3D_ID X Y Z R G B ERROR TRACK[]`. Returns arrays (as plain lists) ready for np.asarray."""
    ids, xyz, rgb, error, track_len = [], [], [], [], []
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split()
        ids.append(int(parts[0]))
        xyz.append([float(parts[1]), float(parts[2]), float(parts[3])])
        rgb.append([int(parts[4]), int(parts[5]), int(parts[6])])
        error.append(float(parts[7]))
        track_len.append((len(parts) - 8) // 2)
    return {"ids": ids, "xyz": xyz, "rgb": rgb, "error": error, "track_length": track_len}
