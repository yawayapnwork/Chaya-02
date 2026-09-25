"""chaya_worker.frames: the canonical frame's similarity transform, X_c = s R X_r + t, and the work-order frame.

Geometry here is a mathematical fixture (packages/contracts/fixtures/synthetic-calibration.json and hand-built
points), not venue data."""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pytest

from chaya_worker.frames import (
    CANONICAL_UP,
    CoordinateFrame,
    NotCalibrated,
    Similarity,
    matrix_to_quaternion,
    quaternion_to_matrix,
    require_canonical,
    require_metric,
    rotation_between,
    tilt_degrees,
)

FIXTURE = json.loads((Path(__file__).resolve().parents[4] / "packages/contracts/fixtures/synthetic-calibration.json")
                     .read_text(encoding="utf-8"))


def _rot_z(deg: float) -> np.ndarray:
    a = np.radians(deg)
    return np.array([[np.cos(a), -np.sin(a), 0], [np.sin(a), np.cos(a), 0], [0, 0, 1.0]])


def _truth() -> Similarity:
    return Similarity.from_dict(FIXTURE["truth"])


POINTS = np.array([[0.0, 0.0, 0.0], [1.0, 2.0, 3.0], [-4.5, 0.25, 7.0]])


def test_identity_leaves_points_unchanged():
    np.testing.assert_array_equal(Similarity.identity().apply(POINTS), POINTS)


def test_pure_translation():
    t = Similarity(1.0, np.eye(3), np.array([1.0, -2.0, 0.5]))
    np.testing.assert_allclose(t.apply(POINTS), POINTS + [1.0, -2.0, 0.5])


def test_pure_rotation_preserves_distances_and_rotates():
    t = Similarity(1.0, _rot_z(90), np.zeros(3))
    np.testing.assert_allclose(t.apply(np.array([1.0, 0.0, 0.0])), [0.0, 1.0, 0.0], atol=1e-15)
    moved = t.apply(POINTS)
    assert np.linalg.norm(moved[1] - moved[2]) == pytest.approx(np.linalg.norm(POINTS[1] - POINTS[2]), rel=1e-15)


def test_pure_scale_scales_distances():
    t = Similarity(2.5, np.eye(3), np.zeros(3))
    np.testing.assert_allclose(t.apply(POINTS), 2.5 * POINTS)


def test_combined_similarity_matches_the_formula():
    s, r, tr = 0.37, _rot_z(33.0), np.array([12.5, -4.25, 1.75])
    t = Similarity(s, r, tr)
    np.testing.assert_allclose(t.apply(POINTS), s * (POINTS @ r.T) + tr, atol=1e-12)
    np.testing.assert_allclose(t.matrix4()[:3, :3], s * r)


def test_inverse_and_round_trip_error_is_at_float64_precision():
    t = _truth()
    inv = t.inverse()
    rng = np.random.default_rng(7)
    pts = rng.uniform(-500, 500, size=(1000, 3))
    round_trip = inv.apply(t.apply(pts))
    assert np.max(np.abs(round_trip - pts)) < 1e-9
    np.testing.assert_allclose(t.compose(inv).matrix4(), np.eye(4), atol=1e-12)


def test_compose_applies_inner_first():
    a = Similarity(2.0, _rot_z(90), np.array([1.0, 0, 0]))
    b = Similarity(0.5, _rot_z(-30), np.array([0, 3.0, 0]))
    np.testing.assert_allclose(a.compose(b).apply(POINTS), a.apply(b.apply(POINTS)), atol=1e-12)


def test_quaternion_matrix_round_trip():
    for q in (np.array([1.0, 0, 0, 0]), np.array([0.0, 1, 0, 0]), np.array([0.2, -0.5, 0.7, 0.1])):
        q = q / np.linalg.norm(q)
        back = matrix_to_quaternion(quaternion_to_matrix(q))
        assert abs(abs(back @ q) - 1.0) < 1e-12  # q and -q are the same rotation


def test_from_matrix4_recovers_scale_and_rejects_reflection_and_shear():
    t = _truth()
    back = Similarity.from_matrix4(t.matrix4())
    assert back.scale == pytest.approx(t.scale, rel=1e-12)
    np.testing.assert_allclose(back.rotation, t.rotation, atol=1e-12)
    reflect = np.diag([1.0, 1.0, -1.0, 1.0])
    with pytest.raises(ValueError):
        Similarity.from_matrix4(reflect)
    shear = np.eye(4)
    shear[0, 1] = 0.3
    with pytest.raises(ValueError):
        Similarity.from_matrix4(shear)


def test_rejects_non_rotations_and_non_positive_scale():
    with pytest.raises(ValueError):
        Similarity(1.0, np.diag([1.0, 1.0, -1.0]), np.zeros(3))
    with pytest.raises(ValueError):
        Similarity(0.0, np.eye(3), np.zeros(3))
    with pytest.raises(ValueError):
        Similarity(float("nan"), np.eye(3), np.zeros(3))


def test_orientation_quaternions_rotate_with_the_frame():
    t = Similarity(3.0, _rot_z(90), np.zeros(3))
    q = t.apply_orientation(np.array([[1.0, 0, 0, 0]]))[0]
    np.testing.assert_allclose(quaternion_to_matrix(q), _rot_z(90), atol=1e-12)


def test_reconstruction_to_metric_with_the_synthetic_fixture():
    truth = _truth()
    for cp in FIXTURE["controlPoints"]:
        np.testing.assert_allclose(truth.apply(np.array(cp["reconstruction"])), cp["venue"], atol=1e-9)


def test_metres_to_reconstruction_with_the_synthetic_fixture():
    to_recon = _truth().inverse()
    for cp in FIXTURE["controlPoints"]:
        np.testing.assert_allclose(to_recon.apply(np.array(cp["venue"])), cp["reconstruction"], atol=1e-9)


def test_measured_distances_become_metres_only_through_the_scale():
    truth = _truth()
    for ref in FIXTURE["distanceReferences"]:
        recon_length = np.linalg.norm(np.subtract(ref["b"], ref["a"]))
        assert truth.scale * recon_length == pytest.approx(ref["measuredMetres"], rel=1e-12)
        assert recon_length != pytest.approx(ref["measuredMetres"], rel=0.05), "reconstruction units are not metres"


def test_gravity_alignment_maps_the_reconstruction_up_to_canonical_plus_z():
    truth = _truth()
    up_recon = np.array(FIXTURE["gravity"]["upReconstruction"])
    np.testing.assert_allclose(truth.apply_direction(up_recon), CANONICAL_UP, atol=1e-12)
    assert tilt_degrees(truth.apply_direction(up_recon)) < 1e-6
    align = rotation_between(up_recon, CANONICAL_UP)
    np.testing.assert_allclose(align @ up_recon, CANONICAL_UP, atol=1e-12)
    np.testing.assert_allclose(rotation_between(CANONICAL_UP, -CANONICAL_UP) @ CANONICAL_UP, -CANONICAL_UP, atol=1e-12)


def _wire(metric: bool, aligned: bool) -> dict:
    truth = _truth().to_dict()
    return {"id": "f1", "sourceRunId": "r1", "version": 2, "metricStatus": "METRIC" if metric else "NOT_CALIBRATED",
            "gravityStatus": "ALIGNED" if aligned else "NOT_ALIGNED", "horizontalDatum": "FLOOR_LOCAL" if aligned else "NONE",
            "scale": truth["scale"] if metric else None,
            "rotation": truth["rotation"] if metric and aligned else None,
            "translation": truth["translation"] if metric and aligned else None}


def test_a_canonical_work_order_frame_round_trips_the_wire_format():
    frame = CoordinateFrame.from_wire(_wire(True, True))
    assert frame.canonical
    np.testing.assert_allclose(frame.to_canonical().matrix4(), _truth().matrix4(), atol=1e-12)
    assert require_canonical({"coordinateFrame": _wire(True, True)}, "TEST").id == "f1"


def test_a_scale_only_frame_is_metric_but_not_canonical():
    frame = CoordinateFrame.from_wire(_wire(True, False))
    assert frame.metric and not frame.canonical
    assert frame.metric_scale() == pytest.approx(0.37)
    assert require_metric({"coordinateFrame": _wire(True, False)}, "TEST").scale == pytest.approx(0.37)
    with pytest.raises(NotCalibrated):
        frame.to_canonical()


def test_missing_or_uncalibrated_frames_raise_not_calibrated_never_assume_metres():
    for order in ({}, {"coordinateFrame": _wire(False, False)}, {"coordinateFrame": _wire(True, False)}):
        with pytest.raises(NotCalibrated) as exc:
            require_canonical(order, "TEST")
        assert exc.value.code == "NOT_CALIBRATED"
    with pytest.raises(NotCalibrated):
        require_metric({}, "TEST")
    with pytest.raises(NotCalibrated):
        require_metric({"coordinateFrame": _wire(False, False)}, "TEST")
