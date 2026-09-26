"""The colour chain of a Gaussian splat, with known RGB values, from SfM point colour to the bytes the viewer shows.

This is deterministic and pure numpy. Each step is checked against its tolerance in chaya_worker.splat_color:
  * COLMAP points3D RGB -> f_dc -> rendered colour: exact to float32 rounding (< 1e-6).
  * PLY write/read: bit-exact.
  * ksplat byte: equal to the input byte, or one below it when float32 rounding lands just under b / 255. Never above.
Training (the rendered colour is the displayed colour) is in tests/splat/test_splat_training.py.
"""

from __future__ import annotations

import numpy as np
import pytest

from chaya_worker import ksplat
from chaya_worker.colmap_txt import parse_points3d_txt
from chaya_worker.ply import GaussianCloud, read_ply, write_ply
from chaya_worker.splat_color import KSPLAT_BYTE_TOLERANCE, SH_C0, rgb_bytes_to_sh0, rgb_to_sh0, sh0_to_rgb

# Known colours: the extremes, mid-grey, and a spread of saturated and dark values.
KNOWN_RGB = np.array([[0, 0, 0], [255, 255, 255], [128, 128, 128], [255, 0, 0], [0, 255, 0], [0, 0, 255],
                      [204, 51, 26], [13, 200, 91], [1, 2, 254], [127, 129, 64]] + [[b, 255 - b, (3 * b) % 256] for b in range(0, 256, 5)],
                     dtype=np.uint8)


def _points3d_txt(rgb: np.ndarray) -> str:
    lines = ["# 3D point list with one line of data per point:", "#   POINT3D_ID, X, Y, Z, R, G, B, ERROR, TRACK[] as (IMAGE_ID, POINT2D_IDX)"]
    lines += [f"{i + 1} {i * 0.1:.3f} 0 5 {r} {g} {b} 0.5 1 {i} 2 {i}" for i, (r, g, b) in enumerate(rgb)]
    return "\n".join(lines) + "\n"


def test_the_shared_constant_is_the_viewer_constant():
    assert SH_C0 == pytest.approx(1 / (2 * np.sqrt(np.pi)), abs=1e-15)
    assert ksplat.SH_C0 == SH_C0, "the ksplat encoder must use the same SH constant as training"


def test_known_rgb_survives_sfm_colours_to_viewer_bytes(tmp_path):
    # 1. SfM point colours, exactly as COLMAP writes them.
    points = parse_points3d_txt(_points3d_txt(KNOWN_RGB))
    rgb_bytes = np.array(points["rgb"])
    np.testing.assert_array_equal(rgb_bytes, KNOWN_RGB)

    # 2. Gaussian initialisation, and 3. the colour the rasteriser is given (splat_training.render_inputs uses sh0_to_rgb).
    f_dc = rgb_bytes_to_sh0(rgb_bytes)
    assert f_dc.dtype == np.float32
    rendered = sh0_to_rgb(f_dc.astype(np.float64))
    np.testing.assert_allclose(rendered, KNOWN_RGB / 255.0, atol=1e-6)

    # 4. PLY export/import: bit-exact.
    n = len(f_dc)
    cloud = GaussianCloud(np.array(points["xyz"], dtype=np.float32), np.full((n, 3), -3.0, np.float32),
                          np.tile(np.array([1, 0, 0, 0], np.float32), (n, 1)), np.full(n, 5.0, np.float32), f_dc)
    back = read_ply(write_ply(cloud, tmp_path / "c.ply"))
    np.testing.assert_array_equal(back.colors_dc, f_dc)
    np.testing.assert_allclose(back.colors_rgb01(), KNOWN_RGB / 255.0, atol=1e-6)

    # 5. The viewer's bytes: exactly what the pinned library reads (apps/web/lib/ksplat-compat.test.ts proves the layout).
    shown = ksplat.decode(ksplat.encode(back))["rgba"][:, :3].astype(int)
    diff = shown - KNOWN_RGB.astype(int)
    assert diff.max() <= 0, "a colour must never come back brighter"
    assert diff.min() >= -round(KSPLAT_BYTE_TOLERANCE * 255), f"worst byte error {diff.min()}"
    assert (diff == 0).mean() > 0.5, "most bytes survive exactly; floor() only costs one where float32 lands just below"


def test_the_old_sigmoid_training_convention_would_have_failed_this_chain():
    """Review R-3: training rendered sigmoid(f_dc). If training fits sigmoid(f) = target, the viewer shows
    SH_C0 * f + 0.5 -- far outside the documented tolerance. This pins the size of the defect the convention removes."""
    target = np.array([0.2, 0.5, 0.8])
    f_fitted_under_sigmoid = np.log(target / (1 - target))
    shown = sh0_to_rgb(f_fitted_under_sigmoid)
    assert np.abs(shown - target).max() > 0.08
    np.testing.assert_allclose(sh0_to_rgb(rgb_to_sh0(target)), target, atol=1e-12)
