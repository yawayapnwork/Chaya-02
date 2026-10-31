"""Tests for the reconstruction CUDA pipeline requirements:
1. Malformed camera data detection and validation
2. Missing GPU detection, driver checks and worker capabilities
3. Invalid poses parsing, registration thresholds and SfM failure handling
4. Failed CUDA kernels, OOM, diverged training and runtime errors
5. Interrupted optimization, timeouts, and cancellation
6. Checkpoint saving, corruption detection, and exact resume
7. Corrupted outputs and viewer contract validation
8. Artifact publication rules and partial reconstruction flags
"""

from __future__ import annotations

import json
from dataclasses import replace
from pathlib import Path
from unittest.mock import MagicMock

import numpy as np
import pytest
import torch

from chaya_worker import ksplat, splat_preflight
from chaya_worker.capabilities import assess
from chaya_worker.contract import ArtifactSpec, StageContext, StageError, StageResult
from chaya_worker.ply import GaussianCloud, read_ply, write_ply
from chaya_worker.runner import CommandRunner
from chaya_worker.settings import Settings
from chaya_worker.splat_color import rgb_to_sh0
from chaya_worker.splat_training import (
    CameraConventionError,
    CheckpointCorrupt,
    CheckpointMismatch,
    DensityControl,
    GsplatContractError,
    TrainingConfig,
    TrainingDiverged,
    init_params,
    l1_dssim_loss,
    load_checkpoint,
    make_optimizer,
    save_checkpoint,
    source_identity,
    train,
    validate_camera,
    validate_trained_state,
)
from chaya_worker.stages.pose_estimation import parse_images_txt
from chaya_worker.stages.splat_reconstruction import (
    initial_scale_log,
    publish_outcome,
    training_failure,
)
from chaya_worker.toolchain import Toolchain, ToolStatus
from tests.splat.renderer import cpu_quality, cpu_rasterizer, plane_points, rig

CPU = torch.device("cpu")
SOURCE = source_identity(run_id="run-cuda-test", inputs=[])
CONFIG = TrainingConfig(iterations=10, densify_start=2, densify_stop=8, densify_every=2, checkpoint_every=4)


def _sample_params():
    points = plane_points(3)
    return init_params(points, rgb_to_sh0(np.full(points.shape, 0.5, np.float32)), initial_scale_log(points), CPU)


def _sample_cam(name="cam1", size=64):
    K = np.array([[50.0, 0, size / 2], [0, 50.0, size / 2], [0, 0, 1.0]], dtype=np.float64)
    viewmat = np.eye(4, dtype=np.float64)
    image = np.ones((size, size, 3), dtype=np.float32) * 0.5
    return {"name": name, "viewmat": viewmat, "K": K, "width": size, "height": size, "image": image}


# ==============================================================================
# 1. Malformed Camera Data
# ==============================================================================

def test_validate_camera_rejects_negative_or_zero_focal_length():
    cam = _sample_cam()
    cam["K"][0, 0] = -50.0
    with pytest.raises(CameraConventionError, match="positive focal lengths"):
        validate_camera(cam)

    cam["K"][0, 0] = 0.0
    with pytest.raises(CameraConventionError, match="positive focal lengths"):
        validate_camera(cam)


def test_validate_camera_rejects_skew():
    cam = _sample_cam()
    cam["K"][0, 1] = 1.5  # Skew
    with pytest.raises(CameraConventionError, match="without skew"):
        validate_camera(cam)


def test_validate_camera_rejects_principal_point_outside_image():
    cam = _sample_cam(size=64)
    cam["K"][0, 2] = 100.0  # cx > width
    with pytest.raises(CameraConventionError, match="principal point inside the image"):
        validate_camera(cam)

    cam["K"][0, 2] = -5.0  # cx < 0
    with pytest.raises(CameraConventionError, match="principal point inside the image"):
        validate_camera(cam)


def test_validate_camera_rejects_non_rigid_or_flipped_rotation():
    cam = _sample_cam()
    # Reflection matrix: det(R) = -1 (flips handedness)
    cam["viewmat"][:3, :3] = np.diag([1.0, 1.0, -1.0])
    with pytest.raises(CameraConventionError, match="proper rotation"):
        validate_camera(cam)

    # Non-orthogonal matrix
    cam["viewmat"][:3, :3] = np.array([[1.0, 2.0, 0.0], [0.0, 1.0, 0.0], [0.0, 0.0, 1.0]])
    with pytest.raises(CameraConventionError, match="proper rotation"):
        validate_camera(cam)


def test_validate_camera_rejects_image_shape_and_dtype_mismatch():
    cam = _sample_cam(size=64)
    cam["image"] = np.ones((32, 32, 3), dtype=np.float32)  # Shape mismatch
    with pytest.raises(CameraConventionError, match="expected"):
        validate_camera(cam)

    cam["image"] = np.ones((64, 64, 3), dtype=np.uint8)  # Dtype mismatch
    with pytest.raises(CameraConventionError, match="expected"):
        validate_camera(cam)


def test_validate_camera_rejects_non_finite_or_out_of_range_pixels():
    cam = _sample_cam(size=64)
    cam["image"][0, 0, 0] = np.nan
    with pytest.raises(CameraConventionError, match="sRGB values in"):
        validate_camera(cam)

    cam["image"][0, 0, 0] = 1.5  # > 1.0
    with pytest.raises(CameraConventionError, match="sRGB values in"):
        validate_camera(cam)


# ==============================================================================
# 2. Missing GPU & Capabilities Checks
# ==============================================================================

class MissingGpuToolchain(Toolchain):
    def __init__(self):
        super().__init__(env={}, which=lambda _n: None)

    def module(self, name):
        return ToolStatus(name, True)

    def module_version(self, name):
        return "1.5.3" if name == "gsplat" else "2.7.1"

    def torch_build(self):
        return {"version": "2.7.1+cu126", "cuda": "12.6", "error": None}

    def cuda(self):
        return ToolStatus("cuda", False, detail="no CUDA device found")

    def cuda_devices(self):
        return []

    def gsplat_backend(self, *, allow_jit=False):
        return ToolStatus("gsplat-cuda-backend", False, detail="CUDA unavailable")

    def nvidia_driver(self):
        return {"present": False, "version": None, "gpus": [], "source": None, "detail": "nvidia-smi not found"}

    def colmap(self):
        return ToolStatus("colmap", True, path="/usr/bin/colmap", version="COLMAP 3.9.1")

    def glomap(self):
        return ToolStatus("glomap", False, detail="not found")


def test_preflight_reports_gpu_not_present_when_driver_missing():
    tc = MissingGpuToolchain()
    report = splat_preflight.environment_report(tc, min_compute_capability=7.0)
    assert not report["ok"]
    codes = [p["code"] for p in report["problems"]]
    assert "GPU_NOT_PRESENT" in codes
    assert "GSPLAT_CUDA_BACKEND_MISSING" in codes


def test_worker_capabilities_refuses_splat_reconstruction_without_gpu():
    tc = MissingGpuToolchain()
    s = Settings(stages=["POSE_ESTIMATION", "SPLAT_RECONSTRUCTION", "ARTIFACT_GENERATION"])
    assessment = assess(s, tc)
    assert "SPLAT_RECONSTRUCTION" in assessment.refused
    refused_codes = [r["code"] for r in assessment.refused["SPLAT_RECONSTRUCTION"]]
    assert "GPU_NOT_PRESENT" in refused_codes
    # POSE_ESTIMATION (with colmap) and ARTIFACT_GENERATION (pure CPU) can be offered
    assert "POSE_ESTIMATION" in assessment.offered
    assert "ARTIFACT_GENERATION" in assessment.offered


# ==============================================================================
# 3. Invalid Poses & SfM Checks
# ==============================================================================

def test_parse_images_txt_rejects_malformed_lines():
    # Fewer than 10 tokens
    with pytest.raises(ValueError, match="malformed images.txt"):
        parse_images_txt("1 1.0 0.0 0.0 0.0 0.0 0.0\n")

    # Non-float numbers in quaternion or translation
    with pytest.raises(ValueError):
        parse_images_txt("1 abc 0.0 0.0 0.0 0.0 0.0 0.0 1 image.jpg\n")


def test_parse_images_txt_succeeds_on_valid_colmap_output():
    text = (
        "# Image list with two lines of data per image:\n"
        "1 0.7071 0.0 0.7071 0.0 1.0 2.0 3.0 1 frame_001.jpg\n"
        "100.0 200.0 1\n"
        "2 1.0 0.0 0.0 0.0 0.0 0.0 0.0 1 frame_002.jpg\n"
        "150.0 250.0 2\n"
    )
    poses = parse_images_txt(text)
    assert len(poses) == 2
    assert poses[0]["name"] == "frame_001.jpg"
    assert poses[0]["rotation_wxyz"] == [0.7071, 0.0, 0.7071, 0.0]
    assert poses[0]["translation"] == [1.0, 2.0, 3.0]


# ==============================================================================
# 4. Failed Kernels, OOM and Diverged Training
# ==============================================================================

def test_training_diverged_on_nan_loss(tmp_path):
    from chaya_worker.splat_training import RenderOutput

    def nan_loss_rasterizer(*_args):
        renders = torch.zeros((64, 64, 3), dtype=torch.float32)
        means2d = torch.zeros((3, 2), dtype=torch.float32, requires_grad=True)
        means2d.retain_grad()
        visible = torch.ones(3, dtype=torch.bool)
        return RenderOutput(renders, means2d, visible)

    def nan_loss(_pred, _gt, _cfg, valid=None):
        return torch.tensor(float("nan")), {}

    with pytest.raises(TrainingDiverged, match="loss became nan"):
        train(
            params=_sample_params(),
            cams=[_sample_cam()],
            rasterize=nan_loss_rasterizer,
            config=TrainingConfig(iterations=5),
            loss_fn=nan_loss,
            checkpoint_path=tmp_path / "ckpt.pt",
            source=SOURCE,
        )


def test_training_diverged_on_nan_parameters(tmp_path):
    params = _sample_params()
    # Inject NaN into initial mean
    params["means"].data[0, 0] = float("nan")
    problems = validate_trained_state(params)
    assert any("non-finite" in p for p in problems)


def test_training_failure_maps_cuda_errors():
    err_oom = torch.cuda.OutOfMemoryError("CUDA out of memory. Tried to allocate 4.00 GiB")
    fail_oom = training_failure(err_oom)
    assert fail_oom.code == "SPLAT_GPU_OUT_OF_MEMORY"

    err_cuda = RuntimeError("CUDA error: an illegal memory access was encountered")
    fail_cuda = training_failure(err_cuda)
    assert fail_cuda.code == "GPU_RUNTIME_ERROR"

    err_gsplat = GsplatContractError("gsplat returned unexpected shape")
    fail_gsplat = training_failure(err_gsplat)
    assert fail_gsplat.code == "GSPLAT_INCOMPATIBLE"


# ==============================================================================
# 5. Interrupted Optimization & Timeouts
# ==============================================================================

def test_training_stops_at_time_budget_and_produces_partial_status(tmp_path):
    clock_times = iter([0.0, 0.1, 0.2, 5.0, 10.0])  # Rapidly advances past deadline
    outcome = train(
        params=_sample_params(),
        cams=[_sample_cam()],
        rasterize=cpu_rasterizer,
        config=TrainingConfig(iterations=100),
        loss_fn=l1_dssim_loss,
        checkpoint_path=tmp_path / "ckpt.pt",
        source=SOURCE,
        deadline=1.0,
        stop_margin_seconds=0.5,
        clock=lambda: next(clock_times),
    )
    assert outcome.status == "PARTIAL"
    assert outcome.stop_reason == "time budget"
    assert outcome.completed_iterations < 100
    assert outcome.checkpoint_path is not None
    assert (tmp_path / "ckpt.pt").is_file()


def test_training_cancelled_raises_interrupted_error(tmp_path):
    with pytest.raises(InterruptedError, match="cancelled"):
        train(
            params=_sample_params(),
            cams=[_sample_cam()],
            rasterize=cpu_rasterizer,
            config=TrainingConfig(iterations=10),
            loss_fn=l1_dssim_loss,
            checkpoint_path=tmp_path / "ckpt.pt",
            source=SOURCE,
            is_cancelled=lambda: True,
        )


# ==============================================================================
# 6. Checkpoint Resume & Integrity
# ==============================================================================

def test_checkpoint_round_trip_and_exact_resume(tmp_path):
    ckpt_path = tmp_path / "valid.pt"
    params = _sample_params()
    scene_extent = 5.0
    optimizer = make_optimizer(params, CONFIG, scene_extent)
    density = DensityControl.empty(len(params["means"]), CPU)
    history = [{"iteration": 4, "loss": 0.42}]

    save_checkpoint(
        ckpt_path,
        iteration=4,
        params=params,
        optimizer=optimizer,
        density=density,
        config=CONFIG,
        source=SOURCE,
        scene_extent=scene_extent,
        history=history,
    )

    it, res_params, res_opt, res_density, res_extent, res_hist = load_checkpoint(
        ckpt_path, config=CONFIG, source=SOURCE, device=CPU
    )
    assert it == 4
    assert res_extent == 5.0
    assert res_hist == history
    assert torch.allclose(res_params["means"], params["means"])
    assert len(res_opt.param_groups) == len(optimizer.param_groups)


def test_checkpoint_mismatch_rejected(tmp_path):
    ckpt_path = tmp_path / "diff_source.pt"
    params = _sample_params()
    optimizer = make_optimizer(params, CONFIG, 5.0)
    density = DensityControl.empty(len(params["means"]), CPU)

    save_checkpoint(
        ckpt_path,
        iteration=4,
        params=params,
        optimizer=optimizer,
        density=density,
        config=CONFIG,
        source=source_identity(run_id="run-A", inputs=[]),
        scene_extent=5.0,
        history=[],
    )

    with pytest.raises(CheckpointMismatch, match="the checkpoint was trained from different inputs"):
        load_checkpoint(
            ckpt_path,
            config=CONFIG,
            source=source_identity(run_id="run-B", inputs=[]),
            device=CPU,
        )


def test_corrupted_checkpoint_fails_fast(tmp_path):
    corrupt_path = tmp_path / "corrupt.pt"
    corrupt_path.write_bytes(b"garbage-bytes-not-a-pytorch-checkpoint")

    with pytest.raises(CheckpointCorrupt):
        load_checkpoint(corrupt_path, config=CONFIG, source=SOURCE, device=CPU)


# ==============================================================================
# 7. Corrupted Outputs & Viewer Contract
# ==============================================================================

def test_ksplat_validation_detects_corrupt_or_truncated_file(tmp_path):
    cloud = GaussianCloud(
        positions=np.zeros((10, 3), dtype=np.float32),
        scales_log=np.zeros((10, 3), dtype=np.float32),
        rotations_wxyz=np.tile([1.0, 0.0, 0.0, 0.0], (10, 1)).astype(np.float32),
        opacity_logit=np.zeros(10, dtype=np.float32),
        colors_dc=np.zeros((10, 3), dtype=np.float32),
    )
    ksplat_path = tmp_path / "test.ksplat"
    ksplat.write_ksplat(cloud, ksplat_path)
    assert ksplat_path.is_file()

    # Valid file passes
    fmt = ksplat.validate_file(ksplat_path)
    assert fmt["splatCount"] == 10

    # Truncated file fails
    truncated = tmp_path / "truncated.ksplat"
    truncated.write_bytes(ksplat_path.read_bytes()[:1000])
    with pytest.raises(ksplat.KsplatInvalid, match="TRUNCATED"):
        ksplat.validate_file(truncated)


def test_ply_validation_rejects_corrupted_cloud(tmp_path):
    bad_ply = tmp_path / "bad.ply"
    bad_ply.write_bytes(b"ply\nformat ascii 1.0\nelement vertex 0\nend_header\n")
    with pytest.raises(ValueError, match="only binary_little_endian"):
        read_ply(bad_ply)

    # Empty cloud rejected by validate_trained_state
    problems = validate_trained_state(
        {
            "means": torch.zeros((0, 3), dtype=torch.float32),
            "scales_log": torch.zeros((0, 3), dtype=torch.float32),
            "quats": torch.zeros((0, 4), dtype=torch.float32),
            "opacity_logit": torch.zeros((0,), dtype=torch.float32),
            "sh0": torch.zeros((0, 3), dtype=torch.float32),
        }
    )
    assert "no Gaussian is left" in problems


# ==============================================================================
# 8. Artifact Publication Rules
# ==============================================================================

def test_partial_training_outcome_publishes_splat_partial_flag(tmp_path):
    work = tmp_path / "work"
    work.mkdir()
    ctx = StageContext(
        {"stage": "SPLAT_RECONSTRUCTION", "inputs": [], "runId": "run-part"},
        [],
        work,
        MagicMock(),
        CommandRunner(work / "out.log", work / "err.log"),
        Toolchain(env={}, which=lambda _n: None),
        Settings(),
        None,
    )
    clock_iter = iter([0.0, 0.05, 0.1, 10.0, 20.0])
    outcome = train(
        params=_sample_params(),
        cams=rig(lambda cam: np.ones((cam["height"], cam["width"], 3), dtype=np.float32) * 0.5),
        rasterize=cpu_rasterizer,
        config=TrainingConfig(iterations=10),
        loss_fn=l1_dssim_loss,
        checkpoint_path=tmp_path / "c.pt",
        source=SOURCE,
        deadline=1.0,
        stop_margin_seconds=0.5,
        clock=lambda: next(clock_iter),
    )
    assert outcome.status == "PARTIAL"
    assert outcome.completed_iterations > 0

    quality = {"passed": False, "basis": "TIME_LIMIT_EXCEEDED", "reason": "time budget stopped training early"}
    result = publish_outcome(ctx, outcome, source=SOURCE, cameras_used=4, versions={}, quality=quality)
    assert result.status == "FAILED"
    assert result.error_code == "TIME_LIMIT_EXCEEDED"
    kinds = {a.kind: a for a in result.artifacts}
    assert "SPLAT_PARTIAL" in kinds
    assert kinds["SPLAT_PARTIAL"].partial is True
    assert "SPLAT" not in kinds  # Never published as final SPLAT
