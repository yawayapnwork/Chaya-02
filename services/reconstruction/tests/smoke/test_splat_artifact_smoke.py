"""Smoke test of the splat path from training to the viewer's files, on a machine without a GPU.

What is real: the training loop (chaya_worker.splat_training.train: Adam, the L1 + D-SSIM loss, adaptive density control,
checkpoints), the publishing rules (publish_outcome), the orchestrator (checksums, uploads, the stage report), the real
ARTIFACT_GENERATION stage (.ksplat encoder, manifest, viewer bundle), and the pinned viewer library loading the result.

What is NOT production: the rasteriser. It is the CPU TEST RENDERER (tests/splat/renderer.py), not gsplat, and the scene
is a synthetic textured plane seen by four cameras. So this proves the plumbing around training, not that gsplat trains a
venue. That is tests/gpu/test_splat_acceptance.py, which runs the real SPLAT_RECONSTRUCTION stage on CUDA.
"""

from __future__ import annotations

from dataclasses import replace

import numpy as np
import pytest

torch = pytest.importorskip("torch", reason="torch is not installed: pip install torch (CPU is enough for this test)")

from chaya_worker.splat_color import rgb_to_sh0  # noqa: E402
from chaya_worker.splat_training import TrainingConfig, init_params, l1_dssim_loss, source_identity, train  # noqa: E402
from chaya_worker.stages.splat_reconstruction import initial_scale_log, publish_outcome  # noqa: E402
from tests.splat.renderer import cpu_rasterizer, plane_points, rig  # noqa: E402
from tests.splat.viewer_artifacts import generate_and_verify, viewer_library_available, viewer_library_check  # noqa: E402

pytestmark = pytest.mark.smoke

CONFIG = TrainingConfig(iterations=60, densify_start=10, densify_stop=50, densify_every=10, densify_grad_threshold=1e-7,
                        opacity_reset_every=0, checkpoint_every=20)


def _checker(cam):
    ys, xs = np.mgrid[0:cam["height"], 0:cam["width"]]
    board = ((xs // 3 + ys // 3) % 2).astype(np.float32)
    return np.stack([board, 1 - board, 0.5 * board], axis=-1)


class CpuTrainedSplat:
    """A TEST stage: the real loop and publishing with the CPU test renderer in place of gsplat (module docstring)."""

    name = "SPLAT_RECONSTRUCTION"

    def run(self, ctx):
        points = plane_points(4)
        params = init_params(points, rgb_to_sh0(np.full(points.shape, 0.5, np.float32)), initial_scale_log(points), torch.device("cpu"))
        source = source_identity(run_id=ctx.order["runId"], inputs=[])
        outcome = train(params=params, cams=rig(_checker), rasterize=cpu_rasterizer, config=CONFIG, loss_fn=l1_dssim_loss,
                        checkpoint_path=ctx.workdir / "splat-checkpoint.pt", source=source)
        return publish_outcome(ctx, outcome, source=source, cameras_used=4, versions={"torch": torch.__version__,
                                                                                     "rasterizer": "CPU TEST RENDERER, not gsplat"})


@pytest.fixture()
def trained(harness):
    report = harness.run(harness.order("SPLAT_RECONSTRUCTION", []), registry={"SPLAT_RECONSTRUCTION": CpuTrainedSplat()})
    assert report["status"] == "SUCCEEDED", report["errorMessage"]
    return report


def test_a_trained_splat_becomes_a_valid_viewer_artifact_with_matching_checksums_and_manifest(harness, trained):
    kinds = {a["kind"] for a in trained["artifacts"]}
    assert kinds == {"SPLAT", "SPLAT_CHECKPOINT", "SPLAT_TRAINING_REPORT"}
    summary = generate_and_verify(harness, trained)
    assert summary["gaussian_count"] != 16, "density control changed the seed's 16 Gaussians: the cloud was trained"
    assert summary["ksplat_bytes"] == 4096 + 1024 + 44 * summary["gaussian_count"]


def test_the_pinned_viewer_library_loads_the_trained_artifact(harness, trained, tmp_path):
    why = viewer_library_available()
    if why:
        pytest.skip(f"the pinned viewer library cannot run here: {why}")
    summary = generate_and_verify(harness, trained)
    result = viewer_library_check(summary["splat"], summary["ksplat"], tmp_path / "viewer-check")
    assert result["splats"] == summary["gaussian_count"]


def test_a_partial_result_is_never_turned_into_the_viewer_asset(harness):
    class CpuPartialSplat(CpuTrainedSplat):
        def run(self, ctx):
            points = plane_points(4)
            params = init_params(points, rgb_to_sh0(np.full(points.shape, 0.5, np.float32)), initial_scale_log(points),
                                 torch.device("cpu"))
            clock = iter(range(1_000, 10_000, 1))
            outcome = train(params=params, cams=rig(_checker), rasterize=cpu_rasterizer, config=replace(CONFIG, iterations=1000),
                            loss_fn=l1_dssim_loss, checkpoint_path=ctx.workdir / "splat-checkpoint.pt",
                            source=source_identity(run_id="r", inputs=[]), deadline=1_010, stop_margin_seconds=2,
                            clock=lambda: float(next(clock)))
            return publish_outcome(ctx, outcome, source={}, cameras_used=4, versions={})

    report = harness.run(harness.order("SPLAT_RECONSTRUCTION", []), registry={"SPLAT_RECONSTRUCTION": CpuPartialSplat()})
    assert report["status"] == "FAILED" and report["errorCode"] == "TIME_LIMIT_EXCEEDED"
    assert [a["kind"] for a in report["artifacts"] if a["partial"]] == ["SPLAT_PARTIAL"]
    inputs = harness.outputs_as_inputs(report)
    generation = harness.run(harness.order("ARTIFACT_GENERATION", inputs))
    assert generation["status"] == "FAILED" and generation["errorCode"] == "INPUT_INVALID", "SPLAT_PARTIAL is not a viewer source"
