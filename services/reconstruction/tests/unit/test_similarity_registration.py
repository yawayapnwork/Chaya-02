"""The similarity estimation and ICP under the re-scan alignment (chaya_worker.similarity_registration), on SYNTHETIC
point sets with known transforms. Mathematical tests only."""

from __future__ import annotations

import numpy as np
import pytest

from chaya_worker.frames import Similarity
from chaya_worker.similarity_registration import (
    AlignmentGates,
    RegistrationError,
    alignment_confidence,
    alignment_metrics,
    evaluate_gates,
    icp_similarity,
    ransac_similarity,
    tilt_degrees,
    umeyama,
    voxel_downsample,
)
from tests.rescan_scene import room, rotation, rotation_error_deg

POINTS = np.random.default_rng(0).uniform(-2, 2, (500, 3))


@pytest.mark.parametrize("truth", [
    Similarity(1.25, np.eye(3), np.zeros(3)),  # scale only
    Similarity(1.0, rotation([0.2, 1, 0.3], 47), np.zeros(3)),  # rotation only
    Similarity(1.0, np.eye(3), np.array([3.0, -2.0, 0.5])),  # translation only
    Similarity(0.62, rotation([1, -1, 2], 133), np.array([-7.0, 4.0, 1.5])),  # all three
], ids=["scale", "rotation", "translation", "combined"])
def test_umeyama_recovers_scale_rotation_and_translation_exactly(truth):
    est = umeyama(POINTS, truth.apply(POINTS))
    assert est.scale == pytest.approx(truth.scale, rel=1e-9)
    assert rotation_error_deg(est, truth) < 1e-6
    np.testing.assert_allclose(est.translation, truth.translation, atol=1e-9)


def test_a_rigid_estimate_cannot_explain_a_scale_difference():
    """Why the alignment is never rigid-only: with a 1.25 scale between two frames, the best rigid fit leaves residuals
    in the tens of centimetres, and the similarity fit leaves none."""
    truth = Similarity(1.25, rotation([0, 0, 1], 20), np.array([1.0, 0, 0]))
    target = truth.apply(POINTS)
    rigid = umeyama(POINTS, target, with_scale=False)
    assert np.abs(rigid.apply(POINTS) - target).max() > 0.2
    assert np.abs(umeyama(POINTS, target).apply(POINTS) - target).max() < 1e-9


def test_umeyama_never_returns_a_reflection_and_refuses_degenerate_input():
    mirrored = POINTS * np.array([1, 1, -1])
    assert np.linalg.det(umeyama(POINTS, mirrored).rotation) > 0
    with pytest.raises(RegistrationError, match="collinear"):
        umeyama(np.outer(np.arange(5.0), [1, 1, 1]), np.outer(np.arange(5.0), [1, 2, 3]))
    with pytest.raises(RegistrationError):
        umeyama(POINTS[:2], POINTS[:2])


def test_ransac_finds_the_similarity_among_mostly_wrong_correspondences():
    truth = Similarity(1.06, rotation([1, 2, 3], 71), np.array([0.5, -1.0, 2.0]))
    rng = np.random.default_rng(3)
    dst = truth.apply(POINTS)
    wrong = rng.random(len(POINTS)) < 0.75  # 75 % outliers
    dst[wrong] = rng.uniform(-5, 5, (wrong.sum(), 3))
    res = ransac_similarity(POINTS, dst, inlier_threshold_m=0.02, scale_bounds=(0.9, 1.1))
    assert res.transform.scale == pytest.approx(1.06, rel=1e-6)
    assert rotation_error_deg(res.transform, truth) < 1e-4
    assert res.inlier_count == int((~wrong).sum())


def test_ransac_rejects_a_scale_outside_the_prior():
    """The region is already metric: a 1.4 scale between it and the venue means a wrong calibration or wrong matches,
    and is not accepted as an alignment."""
    truth = Similarity(1.4, np.eye(3), np.zeros(3))
    with pytest.raises(RegistrationError, match="scale prior"):
        ransac_similarity(POINTS, truth.apply(POINTS), inlier_threshold_m=0.02, scale_bounds=(1 / 1.1, 1.1))


@pytest.mark.parametrize("n", [0, 2])
def test_ransac_needs_three_correspondences(n):
    with pytest.raises(RegistrationError, match="at least 3"):
        ransac_similarity(POINTS[:n], POINTS[:n], inlier_threshold_m=0.02, scale_bounds=(0.9, 1.1))


def test_icp_refines_a_rough_start_to_the_true_similarity_on_surfaces():
    venue = voxel_downsample(room(1), 0.03)
    truth = Similarity(1.04, rotation([0, 0, 1], 3), np.array([0.12, -0.08, 0.03]))
    rescan = truth.inverse().apply(voxel_downsample(room(2), 0.03))  # a different sampling, displaced
    icp = icp_similarity(rescan, venue, Similarity.identity(), schedule_m=[1.0, 0.5, 0.25, 0.1])
    assert icp.transform.scale == pytest.approx(1.04, abs=0.005)
    assert rotation_error_deg(icp.transform, truth) < 0.2
    assert np.linalg.norm(icp.transform.translation - truth.translation) < 0.01


def test_icp_refuses_clouds_that_do_not_overlap():
    venue = room(1, density=200)
    with pytest.raises(RegistrationError, match="do not overlap"):
        icp_similarity(venue + np.array([50.0, 0, 0]), venue, Similarity.identity(), schedule_m=[1.0, 0.1])


def test_metrics_are_physical_and_the_gates_use_them():
    venue = voxel_downsample(room(1), 0.03)
    rescan = voxel_downsample(room(2), 0.03)
    good = alignment_metrics(rescan, venue, Similarity.identity(), correspondence_distance_m=0.1, max_translation_residual_m=0.03)
    assert good.inlier_ratio > 0.95 and good.correspondence_count > 1000
    assert good.rotation_residual_deg < 5 and good.translation_residual_m < 0.005 and good.icp_residual_m < 0.03
    assert evaluate_gates(good, AlignmentGates()).passed

    off = Similarity(1.0, np.eye(3), np.array([0.0, 0.0, 0.06]))  # the whole re-scan 6 cm too high
    bad = alignment_metrics(rescan, venue, off, correspondence_distance_m=0.1, max_translation_residual_m=0.03)
    assert bad.translation_residual_m > 0.03, "the offset shows up along the floor's normal"
    gates = evaluate_gates(bad, AlignmentGates())
    assert not gates.passed and "translation_residual_m" in gates.failed


def test_confidence_is_a_function_of_measured_quantities_only():
    assert alignment_confidence(1.0, 0.0, 0.03) == 1.0
    assert alignment_confidence(0.8, 0.015, 0.03) == pytest.approx(0.4)
    assert alignment_confidence(1.0, 0.03, 0.03) == 0.0
    assert alignment_confidence(0.0, 0.0, 0.03) == 0.0


def test_confidence_does_not_depend_on_the_sampling_density():
    """The same perfect alignment at 2, 3 and 5 cm sampling: the point-to-point RMS grows with the spacing, the
    confidence does not move."""
    venue, rescan = room(1), room(2)
    scores, rms = [], []
    for voxel in (0.02, 0.03, 0.05):
        m = alignment_metrics(voxel_downsample(rescan, voxel), voxel_downsample(venue, voxel), Similarity.identity(),
                              correspondence_distance_m=0.1, max_translation_residual_m=0.03)
        scores.append(m.confidence)
        rms.append(m.icp_residual_m)
    assert rms[0] < rms[1] < rms[2]
    assert max(scores) - min(scores) < 0.02 and min(scores) > 0.9


def test_tilt_separates_a_yaw_from_a_tilt():
    assert tilt_degrees(rotation([0, 0, 1], 80)) == pytest.approx(0.0, abs=1e-9)
    assert tilt_degrees(rotation([1, 0, 0], 7)) == pytest.approx(7.0)


@pytest.mark.parametrize("offset, rotation_about_z, seen", [
    ((0.0, 0.0, 0.06), 0.0, "translation"),  # only the floor sees this
    ((0.05, 0.0, 0.0), 0.0, "translation"),  # only the wall x = 0 and the box/column see this
    ((0.0, 0.0, 0.0), 2.0, "rotation"),  # a yaw: only the walls see it
])
def test_residuals_see_misalignments_only_some_surfaces_observe(offset, rotation_about_z, seen):
    venue, rescan = voxel_downsample(room(1), 0.03), voxel_downsample(room(2), 0.03)
    centre = rescan.mean(axis=0)
    rot = rotation([0, 0, 1], rotation_about_z)
    off = Similarity(1.0, rot, centre - rot @ centre + np.asarray(offset))
    m = alignment_metrics(rescan, venue, off, correspondence_distance_m=0.1, max_translation_residual_m=0.03)
    failed = evaluate_gates(m, AlignmentGates()).failed
    if seen == "translation":
        assert m.translation_residual_m == pytest.approx(np.linalg.norm(offset), rel=0.2)
        assert "translation_residual_m" in failed
    else:
        # Huber weighting down-weights the far walls, so the estimate is conservative (lower), not exact
        assert rotation_about_z * 0.6 < m.rotation_residual_deg < rotation_about_z * 1.2
        assert "rotation_residual_deg" in failed
