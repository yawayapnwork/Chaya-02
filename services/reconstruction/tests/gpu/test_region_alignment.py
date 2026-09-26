"""The re-scan alignment's FEATURE_SIMILARITY mode with real FPFH feature matching (Open3D), on the SYNTHETIC room of
tests/rescan_scene.py, with KNOWN similarity transforms. It is SKIPPED, with the reason printed by `pytest -rs`, when
Open3D is not installed. Nothing here can pass without running the registration.

Everything after feature matching (RANSAC similarity, ICP, metrics, gates) is also covered without Open3D in
tests/unit/test_region_alignment.py. This file checks that real FPFH matches are good enough to feed it.
"""

from __future__ import annotations

import numpy as np
import pytest

from chaya_worker.frames import Similarity
from chaya_worker.region_alignment import MODE_FEATURE, align_region, fpfh_correspondences
from chaya_worker.settings import Settings
from chaya_worker.similarity_registration import AlignmentGates
from chaya_worker.toolchain import Toolchain
from tests.rescan_scene import room, rotation, rotation_error_deg

_tc = Toolchain()
needs_open3d = pytest.mark.skipif(not _tc.module("open3d").available, reason="Open3D is not installed")
pytestmark = [pytest.mark.gpu, needs_open3d]

VENUE = room(1, density=800)
RESCAN = room(2, density=800)


def _align(source):
    return align_region(source, VENUE, mode=MODE_FEATURE, gates=AlignmentGates(), icp_schedule_m=[1.0, 0.5, 0.25, 0.1],
                        icp_voxel_m=0.02, feature_voxel_m=Settings().alignment_voxel_size_m)


@pytest.mark.parametrize("truth", [
    Similarity(1.05, np.eye(3), np.zeros(3)),
    Similarity(1.0, rotation([0.2, 0.1, 1], 40), np.array([0.8, -0.5, 0.1])),
    Similarity(0.95, rotation([0, 0, 1], 75), np.array([-1.5, 2.0, 0.0])),
], ids=["scale", "rotation+translation", "combined"])
def test_real_fpfh_matching_recovers_a_known_similarity(truth):
    result = _align(truth.inverse().apply(RESCAN))
    assert result.accepted, (result.error, result.gates.failed, result.details)
    assert result.correction.scale == pytest.approx(truth.scale, rel=0.01)
    assert rotation_error_deg(result.correction, truth) < 0.5
    centre = RESCAN.mean(axis=0)
    assert np.linalg.norm(result.correction.apply(truth.inverse().apply(centre)) - centre) < 0.03


def test_fpfh_produces_putative_matches_on_real_structure():
    src, dst = fpfh_correspondences(RESCAN, VENUE, Settings().alignment_voxel_size_m)
    assert len(src) == len(dst) > 50


def test_a_region_that_is_not_in_the_venue_is_rejected():
    """A mirrored room exists nowhere in the venue. It must be rejected, never forced into a "best" fit."""
    mirrored = RESCAN * np.array([-1.0, 1.0, 1.0]) + np.array([4.0, 0, 0])
    result = _align(mirrored)
    assert not result.accepted
