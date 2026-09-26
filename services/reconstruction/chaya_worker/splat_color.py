"""The one colour convention every Gaussian splat in Chaya follows, from input frame to viewer.

Colour space: display-referred 8-bit sRGB-encoded values, used as they are at every step. Nothing linearises or
re-encodes gamma. The frames are sRGB JPEG/PNG, COLMAP averages their sRGB pixel values into points3D colours, training
compares rendered values with those same pixel values, and the viewer displays the stored bytes. Keeping one encoding
throughout means a fitted colour is the colour that is shown. It also means the Gaussian blending happens on encoded,
not linear, values. That is what the reference 3D Gaussian Splatting implementation does too.

Representation: a Gaussian's colour is its degree-0 spherical-harmonic coefficient f_dc (three floats, the PLY's
f_dc_0..2), and the colour it contributes is

    rgb = SH_C0 * f_dc + 0.5        (SH_C0 = 1 / (2 * sqrt(pi)) = 0.28209479177387814)

This is what gsplat's own SH evaluation computes for degree 0, what the INRIA PLY format means, and what the pinned viewer
(@mkkellogg/gaussian-splats-3d 0.4.7, chaya_worker.ksplat) decodes. The whole chain:

    frame pixel (uint8 sRGB)  --/255-->  training target in [0, 1]
    COLMAP points3D RGB (uint8, the mean of observing pixels)  --rgb_to_sh0-->  initial f_dc
    f_dc (optimised as is)  --sh0_to_rgb, clamped at 0-->  colour handed to the rasteriser
    f_dc  --PLY f_dc_0..2 (float32, unchanged)-->  f_dc  --ksplat: floor(sh0_to_rgb * 255) clamped to 0..255-->  byte
    byte  --viewer: / 255-->  displayed value

Tolerances (tests/unit/test_splat_color.py proves each):
  * RGB byte -> f_dc -> rgb: exact to float32 rounding (|error| < 1e-6).
  * PLY write/read: bit-exact (float32 stored as float32).
  * ksplat byte: the byte is floor(rgb * 255), so a value that started as byte b comes back as b, or as b - 1 when
    float32 rounding lands just below b / 255. Documented bound: at most 1/255 below the input, never above.
  * A trained colour is only as close as the optimisation got. tests/splat checks that a converged single-colour fit
    exports within 0.02 of its target.

The defect this replaces (review R-3): training rendered sigmoid(f_dc) while every consumer shows SH_C0 * f_dc + 0.5.
A target of 0.8 then exported as about 0.89, and 0.2 as about 0.11.
"""

from __future__ import annotations

import numpy as np

SH_C0 = 0.28209479177387814

# How far below its input byte a colour may come back from the ksplat, as a fraction of full scale.
KSPLAT_BYTE_TOLERANCE = 1.0 / 255.0


def rgb_to_sh0(rgb01):
    """[0, 1] colour -> degree-0 SH coefficient. Works on numpy arrays and torch tensors alike."""
    return (rgb01 - 0.5) / SH_C0


def sh0_to_rgb(f_dc):
    """Degree-0 SH coefficient -> colour, without clamping (callers clamp as their consumer does). Works on numpy
    arrays and torch tensors alike."""
    return f_dc * SH_C0 + 0.5


def rgb_bytes_to_sh0(rgb_uint8: np.ndarray) -> np.ndarray:
    """COLMAP points3D colours (0..255) -> float32 f_dc."""
    return rgb_to_sh0(np.asarray(rgb_uint8, dtype=np.float32) / np.float32(255.0)).astype(np.float32)
