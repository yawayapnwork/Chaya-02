"""Placing a detection in 3D with occlusion and depth evidence (chaya_worker.object_localization, review CV-1). SYNTHETIC
scenes: a camera at the origin looking down +Z at a dense wall 5 m away, with objects in front of it. Mathematical tests
of the placement rule, not measurements on real reconstructions."""

from __future__ import annotations

import numpy as np
import pytest

from chaya_worker.object_localization import (
    AMBIGUOUS_DEPTH,
    INSUFFICIENT_DEPTH,
    NO_DEPTH,
    LocalizationParams,
    Localized,
    Rejected,
    camera_depth,
    front_surface,
    localize_detection,
)
from chaya_worker.stages.semantic_segmentation import project_points

W, H = 640, 480
K = np.array([[500.0, 0, W / 2], [0, 500.0, H / 2], [0, 0, 1]])
VIEW = np.eye(4)
P = LocalizationParams()


def grid(x0, x1, y0, y1, z, step=0.05):
    xs, ys = np.meshgrid(np.arange(x0, x1, step), np.arange(y0, y1, step))
    return np.stack([xs.ravel(), ys.ravel(), np.full(xs.size, z)], axis=1)


def box_surface(centre, size, step=0.04):
    """Points on the six faces of an axis-aligned cube (a solid object's splat is its surface)."""
    c, h = np.asarray(centre, dtype=np.float64), size / 2
    u = np.arange(-h, h + 1e-9, step)
    a, b = (m.ravel() for m in np.meshgrid(u, u))
    faces = []
    for axis in range(3):
        for sign in (-1, 1):
            f = np.zeros((a.size, 3))
            f[:, axis] = sign * h
            f[:, (axis + 1) % 3], f[:, (axis + 2) % 3] = a, b
            faces.append(f)
    return np.vstack(faces) + c


# A dense wall and a small, sparsely sampled object: as in a splat, the object has far fewer Gaussians than the wall
# around it in its box.
WALL = grid(-3, 3, -2, 2, 5.0, step=0.025)
OBJECT_CENTRE = np.array([0.5, 0.2, 3.0])  # 2 m in front of the wall
OBJECT = box_surface(OBJECT_CENTRE, 0.3, step=0.05)


def observe(points, view=VIEW):
    px, visible = project_points(points, view, K, W, H)
    return px, camera_depth(points, view), visible


def box_of(points, pad=0.25):
    """A detector's box: the object's projected bounds, padded (real boxes include background)."""
    px, _, _ = observe(points)
    lo, hi = px.min(axis=0), px.max(axis=0)
    m = (hi - lo) * pad
    return (*(lo - m), *(hi + m))


def localize(points, box, params=P, visible=None):
    px, depth, vis = observe(points)
    return localize_detection(box, points, px, depth, vis if visible is None else visible, params)


def test_a_small_object_two_metres_in_front_of_a_wall_is_placed_on_the_object_not_the_wall():
    """Review CV-1's required test. The box shows far more wall than object; the old placement (median of every centre
    projecting into the box) put the object on the wall. Error must be < 0.2 m."""
    scene = np.vstack([WALL, OBJECT])
    box = box_of(OBJECT)
    result = localize(scene, box)
    assert isinstance(result, Localized), result
    assert np.linalg.norm(result.position - OBJECT_CENTRE) < 0.2
    assert 2.85 <= result.depth_m <= 3.15, "on the faces the camera sees"
    assert result.layers == 2 and result.hidden_in_box > 0

    px, _, vis = observe(scene)
    in_box = vis & (px[:, 0] >= box[0]) & (px[:, 0] < box[2]) & (px[:, 1] >= box[1]) & (px[:, 1] < box[3])
    old = np.median(scene[in_box], axis=0)
    assert np.linalg.norm(old - OBJECT_CENTRE) > 1.0, "the scene really does defeat the depth-blind placement"


def test_centres_hidden_behind_nearer_geometry_are_not_surface():
    """The wall's centres behind the object project into the same pixels as the object, but the camera cannot see them."""
    scene = np.vstack([WALL, OBJECT])
    px, depth, vis = observe(scene)
    front = front_surface(px, depth, vis, P)
    # the wall centres straight behind the object's front face, seen from the camera (the face spans 0.35..0.65 x 0.05..0.35
    # at 2.85 m; at 5 m that is x 0.61..1.14, y 0.09..0.61), shrunk by a cell so they share z-buffer cells with it
    wall_behind = np.zeros(len(scene), dtype=bool)
    wall_behind[: len(WALL)] = ((WALL[:, 0] > 0.7) & (WALL[:, 0] < 1.05) & (WALL[:, 1] > 0.18) & (WALL[:, 1] < 0.52))
    assert wall_behind.sum() > 0 and not front[wall_behind].any(), "hidden wall centres are never front surface"
    assert front[len(WALL):].sum() > 0.4 * len(OBJECT), "the object's camera-facing centres are"
    assert front[: len(WALL)].sum() > 0.9 * (len(WALL) - wall_behind.sum()), "and so is the rest of the wall"


def test_a_thin_occluder_in_front_of_the_object_does_not_capture_the_placement():
    """A pole 1 m in front of the object crosses its box. It is the nearest surface, but it covers little of the box: it
    is passed over, and the object behind it is placed."""
    pole = np.stack([np.full(60, 0.5), np.linspace(-0.1, 0.5, 60), np.full(60, 2.0)], axis=1)
    result = localize(np.vstack([WALL, OBJECT, pole]), box_of(OBJECT))
    assert isinstance(result, Localized), result
    assert np.linalg.norm(result.position - OBJECT_CENTRE) < 0.2 and result.layers == 3


def test_an_object_fully_hidden_behind_another_is_placed_on_what_the_camera_sees():
    """A large panel 1 m in front hides the object entirely. The detector cannot have seen the hidden object; what the box
    shows is the panel, and the placement says so instead of reaching through it."""
    panel = grid(0.0, 1.0, -0.3, 0.7, 2.0, step=0.03)
    result = localize(np.vstack([WALL, OBJECT, panel]), box_of(OBJECT))
    assert isinstance(result, Localized)
    assert result.depth_m == pytest.approx(2.0, abs=0.05) and result.hidden_in_box > len(OBJECT) // 3


# ---- invalid 3D association: no position is invented ----------------------------------------------------------------------


def test_a_box_with_no_reconstructed_geometry_is_not_placed():
    result = localize(WALL, (0, 0, 40, 40))  # the wall does not reach the image corner
    assert isinstance(result, Rejected) and result.reason == NO_DEPTH


def test_a_box_with_too_few_centres_is_not_placed():
    sparse = np.vstack([WALL, OBJECT[::200]])
    result = localize(sparse[len(WALL):], box_of(OBJECT))
    assert isinstance(result, Rejected) and result.reason == INSUFFICIENT_DEPTH
    assert result.detail["front_centres_in_box"] < P.min_support


def test_centres_the_camera_cannot_see_are_no_evidence():
    """Behind the camera, outside the image or on privacy-masked pixels (visible = False): not depth evidence."""
    scene = np.vstack([WALL, OBJECT])
    result = localize(scene, box_of(OBJECT), visible=np.zeros(len(scene), dtype=bool))
    assert isinstance(result, Rejected) and result.reason == NO_DEPTH
    behind = OBJECT * np.array([1.0, 1.0, -1.0])
    assert isinstance(localize(behind, (0, 0, W, H)), Rejected)


def test_a_box_over_scattered_surfaces_at_different_depths_is_ambiguous():
    """Six strips at six depths, each a sixth of the box: no surface the box is about. Rejected, not averaged."""
    strips = []
    for i in range(6):
        depth = 2.0 + 0.5 * i
        u, v = (m.ravel() for m in np.meshgrid(np.arange(200 + 40 * i, 240 + 40 * i, 2.0), np.arange(200, 280, 2.0)))
        strips.append(np.stack([(u - K[0, 2]) * depth / K[0, 0], (v - K[1, 2]) * depth / K[1, 1], np.full(u.size, depth)], axis=1))
    result = localize(np.vstack(strips), (200, 200, 440, 280))
    assert isinstance(result, Rejected) and result.reason == AMBIGUOUS_DEPTH, result
    assert len(result.detail["coverages"]) == 6 and max(result.detail["coverages"]) < P.min_coverage


def test_depths_are_along_the_optical_axis_in_any_pose():
    angle = np.radians(30)
    view = np.eye(4)
    view[:3, :3] = [[np.cos(angle), 0, -np.sin(angle)], [0, 1, 0], [np.sin(angle), 0, np.cos(angle)]]
    view[:3, 3] = [0.2, -0.1, 1.5]
    pts = np.array([[0.0, 0.0, 2.0], [1.0, 2.0, 3.0]])
    expected = (pts @ view[:3, :3].T + view[:3, 3])[:, 2]
    assert camera_depth(pts, view) == pytest.approx(expected)


def test_the_depth_tolerance_grows_with_distance():
    assert P.band(1.0) == pytest.approx(0.2) and P.band(10.0) == pytest.approx(0.65)
