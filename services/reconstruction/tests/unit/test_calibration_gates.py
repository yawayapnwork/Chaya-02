"""Every stage whose output is only meaningful in metres or needs a known up direction refuses to run without a
calibrated coordinate frame -- NOT_CALIBRATED, raised before any toolchain check or model load, so the failure is
the same on a CPU worker and a GPU host and never produces output in fabricated units."""

from __future__ import annotations

import logging

import numpy as np
import pytest

from chaya_worker.contract import StageContext
from chaya_worker.frames import NotCalibrated, Similarity, quaternion_to_matrix
from chaya_worker.runner import CommandRunner
from chaya_worker.settings import Settings
from chaya_worker.stages.navigation_baking import NavigationBaking
from chaya_worker.stages.region_alignment import RegionAlignment
from chaya_worker.stages.region_splice import RegionSplice
from chaya_worker.stages.semantic_indexing import SemanticIndexing, viewmat_in_other_frame
from chaya_worker.stages.semantic_segmentation import project_points
from chaya_worker.toolchain import Toolchain

SCALE_ONLY = {"id": "f-region", "sourceRunId": "r2", "version": 1, "metricStatus": "METRIC", "gravityStatus": "NOT_ALIGNED",
              "horizontalDatum": "NONE", "scale": 0.5, "rotation": None, "translation": None}
UNCALIBRATED = {"id": "f0", "sourceRunId": "r1", "version": 1, "metricStatus": "NOT_CALIBRATED", "gravityStatus": "ALIGNED",
                "horizontalDatum": "NONE", "scale": None, "rotation": None, "translation": None}
CANONICAL = {"id": "f1", "sourceRunId": "r1", "version": 1, "metricStatus": "METRIC", "gravityStatus": "ALIGNED",
             "horizontalDatum": "FLOOR_LOCAL", **Similarity.identity().to_dict()}


def _ctx(tmp_path, stage: str, **order) -> StageContext:
    runner = CommandRunner(tmp_path / "out.log", tmp_path / "err.log")
    return StageContext({"stage": stage, "inputs": [], **order}, [], tmp_path, logging.getLogger("test"), runner,
                        Toolchain(), Settings(), None)


@pytest.mark.parametrize("stage", [NavigationBaking(), SemanticIndexing(), RegionSplice()])
@pytest.mark.parametrize("frame", [None, UNCALIBRATED, SCALE_ONLY])
def test_canonical_frame_stages_refuse_without_a_canonical_frame(tmp_path, stage, frame):
    order = {} if frame is None else {"coordinateFrame": frame}
    with pytest.raises(NotCalibrated) as exc:
        stage.run(_ctx(tmp_path, stage.name, **order))
    assert exc.value.code == "NOT_CALIBRATED"


def test_region_alignment_needs_a_canonical_parent_and_a_metric_region(tmp_path):
    stage = RegionAlignment()
    for order in ({}, {"parentCoordinateFrame": CANONICAL}, {"parentCoordinateFrame": CANONICAL, "coordinateFrame": UNCALIBRATED},
                  {"parentCoordinateFrame": SCALE_ONLY, "coordinateFrame": SCALE_ONLY}):
        with pytest.raises(NotCalibrated):
            stage.run(_ctx(tmp_path, stage.name, **order))


def test_region_alignment_passes_the_frame_gate_with_a_scale_only_region(tmp_path):
    """With both frames present the next failure is the toolchain or the inputs -- never NOT_CALIBRATED."""
    stage = RegionAlignment()
    with pytest.raises(Exception) as exc:
        stage.run(_ctx(tmp_path, stage.name, parentCoordinateFrame=CANONICAL, coordinateFrame=SCALE_ONLY))
    assert getattr(exc.value, "code", None) != "NOT_CALIBRATED"


def test_viewmat_in_other_frame_projects_every_point_to_the_same_pixel():
    """A region camera re-expressed for the parent reconstruction frame sees the same image."""
    rng = np.random.default_rng(11)
    region_to_parent = Similarity(1.7, quaternion_to_matrix(np.array([0.9, 0.1, -0.3, 0.2])), np.array([4.0, -1.0, 2.0]))
    viewmat = np.eye(4)
    viewmat[:3, :3] = quaternion_to_matrix(np.array([0.7, 0.2, 0.6, -0.1]))
    viewmat[:3, 3] = [0.3, -0.2, 5.0]
    k = np.array([[800.0, 0, 320], [0, 800.0, 240], [0, 0, 1]])
    pts_region = rng.uniform(-1, 1, (200, 3))
    px_region, vis_region = project_points(pts_region, viewmat, k, 640, 480)
    px_parent, vis_parent = project_points(region_to_parent.apply(pts_region), viewmat_in_other_frame(viewmat, region_to_parent),
                                           k, 640, 480)
    assert vis_region.any()
    np.testing.assert_array_equal(vis_region, vis_parent)
    np.testing.assert_allclose(px_parent[vis_region], px_region[vis_region], atol=1e-7)
