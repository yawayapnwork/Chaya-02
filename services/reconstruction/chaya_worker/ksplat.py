"""Conversion of a GaussianCloud to the .ksplat file the web viewer loads.

The target is the KSplat reader of the pinned viewer library, @mkkellogg/gaussian-splats-3d **0.4.7**
(apps/web/package-lock.json). Every constant below was read from that version's own source
(`build/gaussian-splats-3d.module.js`: `SplatBuffer`, `SplatBuffer.parseHeader`, `SplatBuffer.parseSectionHeaders`,
`SplatBuffer.getSplatCenter` / `getSplatScaleAndRotation` / `getSplatColor`, `KSplatLoader.checkVersion`), and the
per-splat values reproduce what that library's own INRIA PLY path computes (`INRIAV1PlyParser.parseToUncompressedSplat`
followed by `SplatBuffer.writeSplatDataToSectionBuffer`). Only compression level 0 with spherical-harmonics degree 0 is
written. All multi-byte values are little-endian.

File header, 4096 bytes (`SplatBuffer.HeaderSizeBytes`):
    uint8   [0]      versionMajor = 0      KSplatLoader.checkVersion accepts 0.>=1
    uint8   [1]      versionMinor = 1
    uint32  [1]      maxSectionCount = 1   (byte offset 4)
    uint32  [2]      sectionCount = 1      (8)
    uint32  [3]      maxSplatCount = N     (12)
    uint32  [4]      splatCount = N        (16)
    uint16  [10]     compressionLevel = 0  (20)
    float32 [6..8]   sceneCenter = 0,0,0   (24) stored only; the library uses it for partitioning when it builds files
    float32 [9]      minSphericalHarmonicsCoeff = -1.5   (36) the library's default; unused at SH degree 0
    float32 [10]     maxSphericalHarmonicsCoeff = +1.5   (40)
    everything else zero

Section header, 1024 bytes (`SplatBuffer.SectionHeaderSizeBytes`), immediately after the file header:
    uint32  [0]      splatCount = N
    uint32  [1]      maxSplatCount = N
    uint32  [2], [3] bucketSize, bucketCount = 0        level 0 has no position buckets
    float32 [4]      bucketBlockSize = 0
    uint16  [10]     bucketStorageSizeBytes = 0
    uint32  [6]      compressionScaleRange = 0          (the reader falls back to CompressionLevels[0].ScaleRange)
    uint32  [7]      storageSizeBytes = N * 44
    uint32  [8], [9] fullBucketCount, partiallyFilledBucketCount = 0
    uint16  [20]     sphericalHarmonicsDegree = 0
    everything else zero

Splat records, 44 bytes each (`CompressionLevels[0]`, SH degree 0), starting at byte 4096 + 1024:
    0   float32 x, y, z         position, as stored in the cloud
    12  float32 sx, sy, sz      linear scale: exp(scale_log)
    24  float32 w, x, y, z      unit quaternion, **w first** (the reader builds THREE.Quaternion(x=f[1], y=f[2],
                                z=f[3], w=f[0]) from these four floats)
    40  uint8   r, g, b         floor((0.5 + SH_C0 * f_dc) * 255), clamped to [0, 255]; SH_C0 = 0.28209479177387814
    43  uint8   a               floor(sigmoid(opacity_logit) * 255), clamped to [0, 255]

The float32 inputs are widened to float64, transformed exactly as the library does in JavaScript (double precision),
then narrowed to float32 or truncated to a byte -- so the values equal what the library itself produces from the same
cloud written as a standard 3DGS PLY.

**Compatibility proof** is `apps/web/lib/ksplat-compat.test.ts`. It loads a file written by this module with the real
pinned `KSplatLoader`, and cross-checks it against the library's own PLY loader reading the same cloud. `decode()`
below is a diagnostic reader for this layout only; passing a round trip through it proves nothing about the viewer.
"""

from __future__ import annotations

from pathlib import Path

import numpy as np

from .ply import GaussianCloud

VIEWER_LIBRARY = "@mkkellogg/gaussian-splats-3d@0.4.7"
VERSION_MAJOR, VERSION_MINOR = 0, 1
FILE_HEADER_SIZE = 4096
SECTION_HEADER_SIZE = 1024
BYTES_PER_SPLAT = 44
DATA_OFFSET = FILE_HEADER_SIZE + SECTION_HEADER_SIZE
SH_C0 = 0.28209479177387814
SH_8BIT_HALF_RANGE = 1.5  # the library's DefaultSphericalHarmonics8BitCompressionHalfRange (range 3 / 2)

_RECORD = np.dtype({"names": ["center", "scale", "rotation", "rgba"],
                    "formats": [("<f4", (3,)), ("<f4", (3,)), ("<f4", (4,)), ("u1", (4,))],
                    "offsets": [0, 12, 24, 40], "itemsize": BYTES_PER_SPLAT})


class KsplatUnsupported(ValueError):
    """Requested a compression level or feature this encoder does not implement."""


def _to_byte(values01_times_255: np.ndarray) -> np.ndarray:
    """Math.floor then clamp to [0, 255], as the library does before writing a Uint8ClampedArray."""
    return np.clip(np.floor(values01_times_255), 0, 255).astype(np.uint8)


def encode(cloud: GaussianCloud, *, compression_level: int = 0) -> bytes:
    if compression_level != 0:
        raise KsplatUnsupported(f"only ksplat compression level 0 (uncompressed) is implemented, got {compression_level}")
    n = len(cloud)

    header = np.zeros(FILE_HEADER_SIZE, dtype=np.uint8)
    header[0], header[1] = VERSION_MAJOR, VERSION_MINOR
    u32 = header.view("<u4")
    u32[1], u32[2], u32[3], u32[4] = 1, 1, n, n
    header.view("<u2")[10] = compression_level
    f32 = header.view("<f4")
    f32[6:9] = 0.0
    f32[9], f32[10] = -SH_8BIT_HALF_RANGE, SH_8BIT_HALF_RANGE

    section = np.zeros(SECTION_HEADER_SIZE, dtype=np.uint8)
    s32 = section.view("<u4")
    s32[0], s32[1] = n, n
    s32[7] = n * BYTES_PER_SPLAT
    section.view("<u2")[20] = 0  # spherical-harmonics degree

    positions = cloud.positions.astype(np.float32)
    scales = np.exp(cloud.scales_log.astype(np.float32).astype(np.float64))
    quats = cloud.rotations_wxyz.astype(np.float32).astype(np.float64)
    norms = np.linalg.norm(quats, axis=1, keepdims=True)
    if np.any(norms < 1e-12) or not np.all(np.isfinite(norms)):
        raise ValueError("every Gaussian needs a finite, non-zero rotation quaternion")
    quats = quats / norms
    rgb = _to_byte((0.5 + SH_C0 * cloud.colors_dc.astype(np.float32).astype(np.float64)) * 255.0)
    alpha = _to_byte((1.0 / (1.0 + np.exp(-cloud.opacity_logit.astype(np.float32).astype(np.float64)))) * 255.0)

    records = np.zeros(n, dtype=_RECORD)
    records["center"] = positions
    records["scale"] = scales.astype(np.float32)
    records["rotation"] = quats.astype(np.float32)  # (w, x, y, z)
    records["rgba"][:, :3] = rgb
    records["rgba"][:, 3] = alpha
    if records.itemsize != BYTES_PER_SPLAT:  # a format invariant; not an assert, which python -O would strip
        raise ValueError(f"ksplat record layout is {records.itemsize} bytes, expected {BYTES_PER_SPLAT}")
    return header.tobytes() + section.tobytes() + records.tobytes()


def write_ksplat(cloud: GaussianCloud, path: Path, *, compression_level: int = 0) -> Path:
    path.write_bytes(encode(cloud, compression_level=compression_level))
    return path


def decode(blob: bytes) -> dict[str, np.ndarray]:
    """DIAGNOSTIC ONLY: reads back the level-0 fields of a file in this layout (for inspecting an artifact from Python).
    It is not evidence of viewer compatibility -- apps/web/lib/ksplat-compat.test.ts is. Returns the stored values:
    centers, linear scales, (w, x, y, z) rotations and RGBA bytes."""
    if len(blob) < DATA_OFFSET:
        raise ValueError("not a valid ksplat file: shorter than its headers")
    header = np.frombuffer(blob, dtype=np.uint8, count=FILE_HEADER_SIZE)
    if (int(header[0]), int(header[1])) < (VERSION_MAJOR, VERSION_MINOR):
        raise ValueError(f"unsupported ksplat version {header[0]}.{header[1]}")
    splat_count = int(header.view("<u4")[4])
    level = int(header.view("<u2")[10])
    if level != 0:
        raise KsplatUnsupported(f"only ksplat compression level 0 is supported for decoding, file has level {level}")
    sh_degree = int(np.frombuffer(blob, dtype=np.uint8, count=SECTION_HEADER_SIZE, offset=FILE_HEADER_SIZE).view("<u2")[20])
    if sh_degree != 0:
        raise KsplatUnsupported(f"only spherical-harmonics degree 0 is supported for decoding, file has {sh_degree}")
    if len(blob) != DATA_OFFSET + splat_count * BYTES_PER_SPLAT:
        raise ValueError(f"ksplat size {len(blob)} does not match {splat_count} splats of {BYTES_PER_SPLAT} bytes")
    records = np.frombuffer(blob, dtype=_RECORD, count=splat_count, offset=DATA_OFFSET)
    return {"centers": records["center"].copy(), "scales": records["scale"].copy(),
            "rotations_wxyz": records["rotation"].copy(), "rgba": records["rgba"].copy()}
