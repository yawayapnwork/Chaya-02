"""Re-scan alignment (chaya_worker.region_alignment) on SYNTHETIC point clouds with known similarity transforms.
Mathematical tests only.

FEATURE_SIMILARITY mode normally gets its putative correspondences from Open3D FPFH (tests/gpu/test_region_alignment.py
runs that). Here a stand-in supplies them: true correspondences for a random subset of points, with 70 % replaced by
random wrong matches. It tests everything after feature matching, which is where scale, rotation and translation are
estimated and gated."""

from __future__ import annotations

import numpy as np
import pytest

from chaya_worker.frames import Similarity
from chaya_worker.region_alignment import MODE_DIRECT, MODE_FEATURE, align_region
from chaya_worker.similarity_registration import AlignmentGates
from tests.rescan_scene import room, rotation, rotation_error_deg

GATES = AlignmentGates()
SCHEDULE = [1.0, 0.5, 0.25, 0.1]
VENUE = room(1, density=400)
RESCAN = room(2, density=400)  # a different sampling of the same surfaces


def synthetic_matches(truth: Similarity, *, n: int = 300, outliers: float = 0.7, seed: int = 5):
    """Stand-in for FPFH matching: `n` putative pairs, `outliers` of them wrong."""
    def correspondences(src, dst, _voxel):
        rng = np.random.default_rng(seed)
        if len(src) == 0:
            return np.zeros((0, 3)), np.zeros((0, 3))
        idx = rng.choice(len(src), min(n, len(src)), replace=False)
        ps = src[idx]
        pd = truth.apply(ps)
        wrong = rng.random(len(idx)) < outliers
        pd[wrong] = dst[rng.choice(len(dst), int(wrong.sum()))]
        return ps, pd
    return correspondences


def align(source, *, mode, truth=None, gravity=False, matches=None, **kw):
    corr = matches or (synthetic_matches(truth) if truth is not None else None)
    extra = {"correspondences": corr} if corr is not None else {}
    return align_region(source, VENUE, mode=mode, gates=kw.pop("gates", GATES), icp_schedule_m=SCHEDULE, icp_voxel_m=0.03,
                        feature_voxel_m=0.05, gravity_aligned=gravity, **extra, **kw)


def assert_recovered(result, truth: Similarity):
    assert result.accepted, (result.error, result.gates.failed)
    assert result.correction.scale == pytest.approx(truth.scale, rel=0.005)
    assert rotation_error_deg(result.correction, truth) < 0.3
    centre = RESCAN.mean(axis=0)
    source_centre = truth.inverse().apply(centre)
    assert np.linalg.norm(result.correction.apply(source_centre) - centre) < 0.02  # where the region lands, metres


# ---- FEATURE_SIMILARITY: the full similarity, from correspondences ----------------------------------------------------


@pytest.mark.parametrize("truth", [
    Similarity(1.07, np.eye(3), np.zeros(3)),
    Similarity(1.0, rotation([0.3, 0.2, 1.0], 60), np.zeros(3)),
    Similarity(1.0, np.eye(3), np.array([2.5, -1.5, 0.4])),
    Similarity(0.94, rotation([1, -2, 4], 125), np.array([-3.0, 6.0, 1.0])),
], ids=["scale", "rotation", "translation", "combined"])
def test_feature_mode_recovers_the_similarity(truth):
    """The re-scan's metric calibration is off by the scale, its frame by the rotation and translation."""
    result = align(truth.inverse().apply(RESCAN), mode=MODE_FEATURE, truth=truth)
    assert_recovered(result, truth)
    assert result.method == "FEATURE_SIMILARITY_ICP" and result.details["ransac_inliers"] >= GATES.min_ransac_inliers


def test_too_few_correspondences_are_rejected_not_guessed():
    truth = Similarity(1.0, rotation([0, 0, 1], 30), np.array([1.0, 0, 0]))
    result = align(truth.inverse().apply(RESCAN), mode=MODE_FEATURE, matches=synthetic_matches(truth, n=2))
    assert not result.accepted and result.status == "REJECTED"
    assert "at least 3" in result.error


def test_too_little_overlap_is_rejected_on_correspondence_count():
    """A re-scan of 40 points: every one aligns perfectly, but 40 points cannot vouch for a region."""
    patch = RESCAN[:40]
    result = align(patch, mode=MODE_DIRECT)
    assert not result.accepted and "correspondence_count" in result.gates.failed


def test_a_scale_outside_the_prior_is_rejected():
    """A 30 % scale difference means the re-scan's metric calibration is wrong. It is not "corrected".

    RANSAC cannot find the true transform inside the +/-10 % prior. The best it has is a handful of chance matches, and ICP
    then settles on a wrong local fit: a shrunken room fitted onto part of the venue. That fit's inlier ratio and surface
    residuals look acceptable, so only the RANSAC support and the scale gate expose it. This is why those gates exist."""
    truth = Similarity(1.3, np.eye(3), np.array([0.5, 0, 0]))
    result = align(truth.inverse().apply(RESCAN), mode=MODE_FEATURE, truth=truth)
    assert not result.accepted
    assert {"ransac_inliers", "scale_correction"} <= set(result.gates.failed) or "scale prior" in (result.error or "")


def test_a_noisy_rescan_is_rejected_on_icp_residual():
    rng = np.random.default_rng(9)
    noisy = RESCAN + rng.normal(0, 0.06, RESCAN.shape)  # 6 cm noise
    result = align(noisy, mode=MODE_DIRECT)
    assert not result.accepted and "icp_residual_m" in result.gates.failed


def test_a_gravity_aligned_rescan_may_not_be_tilted():
    """Both clouds are +Z up in the canonical frame. A correction that tilts the re-scan by 12 degrees contradicts its
    own gravity calibration, whatever the fit says."""
    truth = Similarity(1.0, rotation([1, 0, 0], 12), np.array([0.3, 0.2, 0.0]))
    result = align(truth.inverse().apply(RESCAN), mode=MODE_FEATURE, truth=truth, gravity=True)
    assert result.correction.scale == pytest.approx(1.0, abs=0.01)  # the fit itself is right
    assert not result.accepted and result.gates.failed == ["tilt_deg"]


# ---- DIRECT_CANONICAL: the calibration places the region, ICP only verifies -------------------------------------------


def test_direct_mode_refines_a_small_calibration_disagreement():
    truth = Similarity(1.02, rotation([0, 0, 1], 1.5), np.array([0.12, -0.08, 0.02]))
    result = align(truth.inverse().apply(RESCAN), mode=MODE_DIRECT)
    assert_recovered(result, truth)
    assert result.method == "DIRECT_CANONICAL_ICP"
    assert result.metrics.confidence > 0.9


def test_direct_mode_rejects_calibrations_that_disagree_by_too_much():
    """Two surveyed calibrations 0.8 m apart: even if ICP could pull the region into place, the calibrations contradict
    each other and the merge is refused."""
    truth = Similarity(1.0, np.eye(3), np.array([0.8, 0.0, 0.0]))
    result = align(truth.inverse().apply(RESCAN), mode=MODE_DIRECT)
    assert not result.accepted and "direct_translation_correction_m" in result.gates.failed


def test_direct_mode_rejects_a_scale_disagreement():
    truth = Similarity(1.15, np.eye(3), np.zeros(3))
    result = align(truth.inverse().apply(RESCAN), mode=MODE_DIRECT)
    assert not result.accepted and "scale_correction" in result.gates.failed


def test_a_perfect_rescan_is_accepted_with_an_identity_correction():
    result = align(RESCAN, mode=MODE_DIRECT)
    assert result.accepted
    assert result.correction.scale == pytest.approx(1.0, abs=1e-3) and rotation_error_deg(result.correction, Similarity.identity()) < 0.05
    assert np.linalg.norm(result.correction.translation) < 0.005


def test_every_gate_is_reported_with_its_value_and_threshold():
    result = align(RESCAN, mode=MODE_DIRECT)
    names = [g["name"] for g in result.gates.as_list()]
    assert names == ["correspondence_count", "inlier_ratio", "scale_correction", "rotation_residual_deg", "translation_residual_m",
                     "icp_residual_m", "confidence", "direct_rotation_correction_deg", "direct_translation_correction_m"]
    assert all({"value", "threshold", "comparison", "passed"} <= set(g) for g in result.gates.as_list())
