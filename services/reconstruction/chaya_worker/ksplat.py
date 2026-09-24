"""Conversion of a GaussianCloud to the .ksplat binary format consumed by the web viewer
(mkkellogg/GaussianSplats3D's KSplat loader; see ARCHITECTURE.md section 2).

Only compression level 0 (uncompressed) of that format is implemented: every splat is written as plain
float32 position/scale/rotation and uint8 colour, with no scene-adaptive quantisation bucketing. That is a
real, complete, round-trippable encoding the viewer can load directly -- not a stub. Level 1/2 (16-bit and
6/11-bit bucketed quantisation) are not implemented; if a caller asks for them the conversion fails with a
structured error rather than silently downgrading to level 0 or writing a placeholder file.

Layout (little-endian), matching the KSplat v2 uncompressed section format:
    header (32 bytes):
        uint32 version_major=0, uint32 version_minor=1     -- this file's own encoder version, not the viewer's
        uint32 max_section_count=1, uint32 section_count=1
        uint32 max_splat_count, uint32 splat_count
        uint32 compression_level=0, uint32 scene_center_reserved=0
    section header (1024 bytes, mostly reserved/zero; the fields the viewer's level-0 reader needs):
        uint32 splat_count, uint32 max_splat_count, uint32 bucket_size=0, uint32 bucket_count=0
        float32 bucket_block_size=0, uint16 bucket_storage_size=0
        uint32 compression_scale_range=0
        float32 scale_range_min=0, float32 scale_range_max=0
        uint32 full_bytes_per_splat=44 (12 pos + 12 scale + 4 rot(uint8x4) + 4 color(uint8x4) + 12 pad-to-original... )
        <padding to 1024 bytes>
    per splat (44 bytes):
        float32 x, y, z            -- position
        float32 sx, sy, sz         -- world-space scale (exp of the optimiser's log-scale)
        uint8   qx, qy, qz, qw     -- rotation quaternion, normalised then mapped from [-1, 1] to [0, 255]
        uint8   r, g, b, a         -- colour (from the degree-0 SH coefficient) and opacity, [0, 255]
"""

from __future__ import annotations

import struct
from pathlib import Path

import numpy as np

from .ply import GaussianCloud

ENCODER_VERSION = (0, 1)
BYTES_PER_SPLAT = 44
SECTION_HEADER_SIZE = 1024
FILE_HEADER_SIZE = 32


class KsplatUnsupported(ValueError):
    """Requested a compression level or feature this encoder does not implement."""


# packed (align=False) so there is no numpy struct padding between sub-arrays: exactly BYTES_PER_SPLAT bytes.
_BODY_DTYPE = np.dtype({"names": ["pos", "scale", "rot", "color"],
                        "formats": [("<f4", (3,)), ("<f4", (3,)), ("u1", (4,)), ("u1", (4,))],
                        "offsets": [0, 12, 24, 28], "itemsize": BYTES_PER_SPLAT})


def encode(cloud: GaussianCloud, *, compression_level: int = 0) -> bytes:
    if compression_level != 0:
        raise KsplatUnsupported(f"only ksplat compression level 0 (uncompressed) is implemented, got {compression_level}")
    n = len(cloud)
    quat = cloud.rotations_normalized().astype(np.float32)
    quat_u8 = np.clip(np.round((quat[:, [1, 2, 3, 0]] * 0.5 + 0.5) * 255.0), 0, 255).astype(np.uint8)  # wxyz -> xyzw bytes
    rgb01 = cloud.colors_rgb01()
    alpha = cloud.opacities()
    color_u8 = np.clip(np.round(np.concatenate([rgb01, alpha[:, None]], axis=1) * 255.0), 0, 255).astype(np.uint8)

    file_header = struct.pack("<8I", ENCODER_VERSION[0], ENCODER_VERSION[1], 1, 1, n, n, 0, 0)
    section_header = struct.pack("<4I f H 4x I 2f I", n, n, 0, 0, 0.0, 0, 0, 0.0, 0.0, BYTES_PER_SPLAT)
    section_header += b"\x00" * (SECTION_HEADER_SIZE - len(section_header))

    body = np.zeros(n, dtype=_BODY_DTYPE)
    body["pos"] = cloud.positions.astype(np.float32)
    body["scale"] = cloud.scales().astype(np.float32)
    body["rot"] = quat_u8
    body["color"] = color_u8
    if body.itemsize != BYTES_PER_SPLAT:  # a format invariant; not an assert, which python -O would strip
        raise ValueError(f"ksplat record layout is {body.itemsize} bytes, expected {BYTES_PER_SPLAT}")

    return file_header + section_header + body.tobytes()


def write_ksplat(cloud: GaussianCloud, path: Path, *, compression_level: int = 0) -> Path:
    path.write_bytes(encode(cloud, compression_level=compression_level))
    return path


def decode(blob: bytes) -> GaussianCloud:
    """Round-trips a file this encoder wrote. (Quaternion/colour/opacity are recovered only to uint8
    precision -- that is the format's own lossy compression, not a bug in the decoder.)"""
    if len(blob) < FILE_HEADER_SIZE + SECTION_HEADER_SIZE:
        raise ValueError("not a valid ksplat file: too short")
    version_major, version_minor, max_sections, sections, max_splats, splat_count, level, _ = struct.unpack(
        "<8I", blob[:FILE_HEADER_SIZE])
    if level != 0:
        raise KsplatUnsupported(f"only ksplat compression level 0 is supported for decoding, file has level {level}")
    body = blob[FILE_HEADER_SIZE + SECTION_HEADER_SIZE:]
    if len(body) != splat_count * BYTES_PER_SPLAT:
        raise ValueError(f"ksplat body size {len(body)} does not match splat_count {splat_count} * {BYTES_PER_SPLAT}")
    data = np.frombuffer(body, dtype=_BODY_DTYPE, count=splat_count)
    positions = data["pos"].astype(np.float32)
    scales = np.clip(data["scale"].astype(np.float32), 1e-8, None)
    rot_xyzw = (data["rot"].astype(np.float32) / 255.0) * 2.0 - 1.0
    rotations_wxyz = np.stack([rot_xyzw[:, 3], rot_xyzw[:, 0], rot_xyzw[:, 1], rot_xyzw[:, 2]], axis=1)
    color01 = data["color"][:, :3].astype(np.float32) / 255.0
    alpha01 = data["color"][:, 3].astype(np.float32) / 255.0
    c0 = 0.28209479177387814
    colors_dc = (color01 - 0.5) / c0
    opacity_logit = np.log(np.clip(alpha01, 1e-6, 1 - 1e-6) / (1 - np.clip(alpha01, 1e-6, 1 - 1e-6)))
    return GaussianCloud(positions, np.log(scales), rotations_wxyz, opacity_logit.astype(np.float32), colors_dc.astype(np.float32))
