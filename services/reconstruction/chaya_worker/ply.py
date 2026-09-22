"""Reading and writing 3D Gaussian Splat point clouds as binary little-endian PLY.

This is the interchange format between SPLAT_RECONSTRUCTION, SEMANTIC_SEGMENTATION, GEOMETRIC_CLEANUP,
PLANE_FITTING and ARTIFACT_GENERATION. It follows the property layout used by the reference 3D Gaussian
Splatting implementation (INRIA) and gsplat: position, normal (unused, written as zero), the degree-0
spherical harmonic (f_dc_0..2, i.e. base colour in SH space), opacity (pre-sigmoid logit), scale
(pre-exp log-scale) and rotation as a wxyz quaternion (rot_0..3). Values are stored exactly as the
optimiser holds them (log/logit space) so re-loading a PLY for further processing does not lose precision
to a sigmoid/exp round trip. No optional dependency (Open3D, gsplat) is needed to read or write this file.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path

import numpy as np

GAUSSIAN_PROPERTIES = [
    "x", "y", "z", "nx", "ny", "nz", "f_dc_0", "f_dc_1", "f_dc_2", "opacity",
    "scale_0", "scale_1", "scale_2", "rot_0", "rot_1", "rot_2", "rot_3",
]
_DTYPE = np.dtype([(name, "<f4") for name in GAUSSIAN_PROPERTIES])


@dataclass
class GaussianCloud:
    """N Gaussians. positions/scales/colors are float32 (N,3); rotations is float32 (N,4) wxyz;
    opacity_logit and scales are stored in the optimiser's unconstrained space (see module docstring)."""

    positions: np.ndarray  # (N, 3)
    scales_log: np.ndarray  # (N, 3)
    rotations_wxyz: np.ndarray  # (N, 4), not necessarily normalised
    opacity_logit: np.ndarray  # (N,)
    colors_dc: np.ndarray  # (N, 3), SH degree-0 coefficients

    def __post_init__(self) -> None:
        n = len(self.positions)
        for name, arr, width in (("scales_log", self.scales_log, 3), ("rotations_wxyz", self.rotations_wxyz, 4),
                                 ("colors_dc", self.colors_dc, 3)):
            if arr.shape != (n, width):
                raise ValueError(f"{name} must have shape ({n}, {width}), got {arr.shape}")
        if self.opacity_logit.shape != (n,):
            raise ValueError(f"opacity_logit must have shape ({n},), got {self.opacity_logit.shape}")

    def __len__(self) -> int:
        return len(self.positions)

    def subset(self, mask: np.ndarray) -> "GaussianCloud":
        return GaussianCloud(self.positions[mask], self.scales_log[mask], self.rotations_wxyz[mask],
                             self.opacity_logit[mask], self.colors_dc[mask])

    # ---- derived, constrained quantities ---------------------------------------------------

    def scales(self) -> np.ndarray:
        return np.exp(self.scales_log)

    def opacities(self) -> np.ndarray:
        return 1.0 / (1.0 + np.exp(-self.opacity_logit))

    def rotations_normalized(self) -> np.ndarray:
        norm = np.linalg.norm(self.rotations_wxyz, axis=1, keepdims=True)
        norm = np.where(norm > 1e-12, norm, 1.0)
        return self.rotations_wxyz / norm

    def colors_rgb01(self) -> np.ndarray:
        """SH degree-0 coefficient to [0, 1] display colour (the standard 3DGS constant C0 = 0.28209479177387814)."""
        c0 = 0.28209479177387814
        return np.clip(self.colors_dc * c0 + 0.5, 0.0, 1.0)


def write_ply(cloud: GaussianCloud, path: Path) -> Path:
    n = len(cloud)
    data = np.zeros(n, dtype=_DTYPE)
    data["x"], data["y"], data["z"] = cloud.positions[:, 0], cloud.positions[:, 1], cloud.positions[:, 2]
    data["f_dc_0"], data["f_dc_1"], data["f_dc_2"] = cloud.colors_dc[:, 0], cloud.colors_dc[:, 1], cloud.colors_dc[:, 2]
    data["opacity"] = cloud.opacity_logit
    data["scale_0"], data["scale_1"], data["scale_2"] = cloud.scales_log[:, 0], cloud.scales_log[:, 1], cloud.scales_log[:, 2]
    data["rot_0"], data["rot_1"], data["rot_2"], data["rot_3"] = (cloud.rotations_wxyz[:, 0], cloud.rotations_wxyz[:, 1],
                                                                   cloud.rotations_wxyz[:, 2], cloud.rotations_wxyz[:, 3])
    header = "\n".join([
        "ply", "format binary_little_endian 1.0", f"element vertex {n}",
        *(f"property float {name}" for name in GAUSSIAN_PROPERTIES), "end_header", "",
    ])
    with open(path, "wb") as f:
        f.write(header.encode("ascii"))
        f.write(data.tobytes())
    return path


_HEADER_LINE = re.compile(rb"[^\n]*\n")


def read_ply(path: Path) -> GaussianCloud:
    with open(path, "rb") as f:
        raw = f.read()
    header_end = raw.find(b"end_header\n")
    if header_end == -1:
        raise ValueError(f"{path}: not a PLY file (no end_header)")
    header = raw[:header_end].decode("ascii", errors="replace")
    if "format binary_little_endian" not in header:
        raise ValueError(f"{path}: only binary_little_endian PLY is supported")
    match = re.search(r"element vertex (\d+)", header)
    if not match:
        raise ValueError(f"{path}: no 'element vertex' count in header")
    n = int(match.group(1))
    props = re.findall(r"property float (\w+)", header)
    missing = [p for p in GAUSSIAN_PROPERTIES if p not in props]
    if missing:
        raise ValueError(f"{path}: missing PLY properties {missing}")
    body = raw[header_end + len(b"end_header\n"):]
    dtype = np.dtype([(name, "<f4") for name in props])
    data = np.frombuffer(body, dtype=dtype, count=n)
    positions = np.stack([data["x"], data["y"], data["z"]], axis=1).astype(np.float32)
    colors_dc = np.stack([data["f_dc_0"], data["f_dc_1"], data["f_dc_2"]], axis=1).astype(np.float32)
    scales_log = np.stack([data["scale_0"], data["scale_1"], data["scale_2"]], axis=1).astype(np.float32)
    rotations = np.stack([data["rot_0"], data["rot_1"], data["rot_2"], data["rot_3"]], axis=1).astype(np.float32)
    opacity_logit = data["opacity"].astype(np.float32).copy()
    return GaussianCloud(positions, scales_log, rotations, opacity_logit, colors_dc)
