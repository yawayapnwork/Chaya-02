"""Parsers for the COLMAP TEXT model format (cameras.txt / points3D.txt), the same output produced by
`colmap model_converter --output_type TXT` that chaya_worker.stages.pose_estimation already parses
images.txt from. Pure functions, independent of any running tool, so they are unit-testable without COLMAP
installed.
"""

from __future__ import annotations

from typing import Any


def parse_cameras_txt(text: str) -> dict[int, dict[str, Any]]:
    """`CAMERA_ID MODEL WIDTH HEIGHT PARAMS[]`. Only the pinhole-family models POSE_ESTIMATION can produce
    (SIMPLE_RADIAL, SIMPLE_PINHOLE, PINHOLE) are supported; an unsupported model raises ValueError naming it."""
    cameras: dict[int, dict[str, Any]] = {}
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split()
        camera_id, model, width, height = int(parts[0]), parts[1], int(parts[2]), int(parts[3])
        params = [float(p) for p in parts[4:]]
        if model == "SIMPLE_RADIAL":  # f, cx, cy, k
            fx = fy = params[0]
            cx, cy = params[1], params[2]
        elif model == "SIMPLE_PINHOLE":  # f, cx, cy
            fx = fy = params[0]
            cx, cy = params[1], params[2]
        elif model == "PINHOLE":  # fx, fy, cx, cy
            fx, fy, cx, cy = params[0], params[1], params[2], params[3]
        else:
            raise ValueError(f"camera {camera_id}: unsupported COLMAP camera model {model!r} (expected a pinhole-family model)")
        cameras[camera_id] = {"model": model, "width": width, "height": height, "fx": fx, "fy": fy, "cx": cx, "cy": cy}
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
