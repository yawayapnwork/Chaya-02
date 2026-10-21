"""Resuming a time-boxed SPLAT_RECONSTRUCTION through the orchestrator (gaussian-splatting-audit D-3).

The control plane offers a retry of the stage the SPLAT_CHECKPOINT of the latest attempt the time budget stopped
(PipelineService#resumableCheckpoint, tested in PipelineControlPlaneTest). These tests are the worker's half: what each
attempt reports, and that a retry given that checkpoint continues from its iteration with the same parameters, Adam
state and density-control state, or refuses it with the code the control plane keys on.

What is real: the orchestrator (download, checksum check, upload, report), the stage's input identity (`training_source`)
and checkpoint handling (`resume_state`), the training loop, checkpoints and publish_outcome. What is NOT production: the
rasteriser is the CPU TEST RENDERER (tests/splat/renderer.py), not gsplat, and the scene is synthetic. Resume on CUDA is
tests/gpu/test_splat_acceptance.py.
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import replace

import numpy as np
import pytest

torch = pytest.importorskip("torch", reason="torch is not installed: pip install torch (CPU is enough for these tests)")

from chaya_worker.ply import read_ply  # noqa: E402
from chaya_worker.splat_color import rgb_to_sh0  # noqa: E402
from chaya_worker.splat_training import PARAM_NAMES, TrainingConfig, init_params, l1_dssim_loss, train  # noqa: E402
from chaya_worker.stages.splat_reconstruction import initial_scale_log, publish_outcome, resume_state, training_source  # noqa: E402
from tests.conftest import DERIVED_BUCKET, Harness  # noqa: E402
from tests.splat.renderer import cpu_rasterizer, plane_points, rig  # noqa: E402

CPU = torch.device("cpu")
# Densification and an opacity reset fall on both sides of where the budget stops the first attempt, so a resume that
# lost any of the density-control or Adam state would not reproduce the uninterrupted run.
CONFIG = TrainingConfig(iterations=40, densify_start=10, densify_stop=35, densify_every=10, densify_grad_threshold=1e-5,
                        opacity_reset_every=30, lr_position_decay_steps=30, checkpoint_every=0)


def _checker(cam):
    ys, xs = np.mgrid[0:cam["height"], 0:cam["width"]]
    board = ((xs // 3 + ys // 3) % 2).astype(np.float32)
    return np.stack([board, 1 - board, 0.5 * board], axis=-1)


class FakeClock:
    def __init__(self) -> None:
        self.t = 1_000.0

    def __call__(self) -> float:
        self.t += 1.0
        return self.t


class CpuResumableSplat:
    """A TEST stage: SplatReconstruction's input identity, checkpoint handling, training loop and publishing, with the CPU
    test renderer in place of gsplat. `budget` (fake-clock seconds) stands in for the work order's deadline, so where the
    time box stops training is deterministic; None trains to the end."""

    name = "SPLAT_RECONSTRUCTION"

    def __init__(self, budget: float | None = None, config: TrainingConfig = CONFIG) -> None:
        self.budget, self.config = budget, config

    def run(self, ctx):
        source = training_source(ctx.order, ctx.inputs)
        resume, resumed_from = resume_state(ctx, config=self.config, source=source, device=CPU)
        points = plane_points(4, half=3.0)
        params = init_params(points, rgb_to_sh0(np.full(points.shape, 0.5, np.float32)), initial_scale_log(points), CPU)
        clock = FakeClock()
        outcome = train(params=params, cams=rig(_checker), rasterize=cpu_rasterizer, config=self.config, loss_fn=l1_dssim_loss,
                        checkpoint_path=ctx.workdir / "splat-checkpoint.pt", source=source,
                        deadline=None if self.budget is None else clock.t + self.budget, stop_margin_seconds=10, clock=clock,
                        resume=resume)
        return publish_outcome(ctx, outcome, source=source, cameras_used=4, versions={"rasterizer": "CPU TEST RENDERER, not gsplat"},
                               resumed_from=resumed_from)


@pytest.fixture()
def scan(harness, tmp_path):
    """The run's SPLAT_RECONSTRUCTION inputs, as POSE_ESTIMATION and PRIVACY_PREPROCESS would have published them. The test
    stage does not read them; they are what the checkpoint's input identity is bound to."""
    inputs = []
    for kind, body in (("SPARSE_MODEL", b"sparse"), ("POSES", b'{"poses": []}'), ("FRAME_ARCHIVE_ANON", b"frames")):
        path = tmp_path / f"{kind}.bin"
        path.write_bytes(body)
        ref = harness.raw_input(kind, path)
        ref["containsPii"] = False  # after PRIVACY_PREPROCESS
        inputs.append(ref)
    return inputs


def _run(harness: Harness, inputs, attempt: int, stage: CpuResumableSplat, **order):
    return harness.run(harness.order("SPLAT_RECONSTRUCTION", inputs, attempt=attempt, **order),
                       registry={"SPLAT_RECONSTRUCTION": stage})


def _artifact(report, kind):
    return next(a for a in report["artifacts"] if a["kind"] == kind)


def _training_report(harness, report):
    return json.loads((harness.storage.root / DERIVED_BUCKET / _artifact(report, "SPLAT_TRAINING_REPORT")["key"]).read_text())


def _checkpoint_input(harness, report):
    """What PipelineService#resumableCheckpoint offers the retry: the attempt's SPLAT_CHECKPOINT, by its sealed key and checksum."""
    return [i for i in harness.outputs_as_inputs(report) if i["kind"] == "SPLAT_CHECKPOINT"]


def _cloud(harness, report, kind="SPLAT"):
    return read_ply(harness.storage.root / DERIVED_BUCKET / _artifact(report, kind)["key"])


def _same_cloud(a, b):
    for name in ("positions", "scales_log", "rotations_wxyz", "opacity_logit", "colors_dc"):
        np.testing.assert_array_equal(getattr(a, name), getattr(b, name), err_msg=name)


def _payload(harness, report):
    return torch.load(harness.storage.root / DERIVED_BUCKET / _artifact(report, "SPLAT_CHECKPOINT")["key"], weights_only=True)


# ---- interrupted, then resumed --------------------------------------------------------------------------------------


def test_an_interrupted_training_resumes_from_its_checkpoint_to_the_uninterrupted_result(harness, scan, tmp_path):
    straight = _run(harness, scan, 9, CpuResumableSplat())  # the uninterrupted reference, under its own prefix
    assert straight["status"] == "SUCCEEDED"

    stopped = _run(harness, scan, 1, CpuResumableSplat(budget=40))
    assert stopped["status"] == "FAILED" and stopped["errorCode"] == "TIME_LIMIT_EXCEEDED"
    assert {a["kind"] for a in stopped["artifacts"]} == {"SPLAT_PARTIAL", "SPLAT_CHECKPOINT", "SPLAT_TRAINING_REPORT"}
    assert _artifact(stopped, "SPLAT_PARTIAL")["partial"] is True  # what PipelineService#advance turns into a PARTIAL run
    done = stopped["errorDetails"]["completed_iterations"]
    assert 0 < done < CONFIG.iterations
    saved = _payload(harness, stopped)
    assert saved["iteration"] == done and saved["density"]["events"], "the checkpoint holds the stop's iteration and density state"
    adam = {name: state for name, state in saved["optimizer"].items() if state}  # the CPU renderer gives quats no gradient
    assert set(adam) >= {"means", "scales_log", "opacity_logit", "sh0"} and set(saved["optimizer"]) == set(PARAM_NAMES)
    assert all(float(state["step"]) == done for state in adam.values()), "and Adam's step counts and moments"

    checkpoint = _checkpoint_input(harness, stopped)
    resumed = _run(harness, scan + checkpoint, 2, CpuResumableSplat())
    assert resumed["status"] == "SUCCEEDED", resumed["errorMessage"]
    assert checkpoint[0]["artifactId"] in resumed["inputArtifactIds"]
    report = _training_report(harness, resumed)
    assert report["resumed_from_iteration"] == done and report["completed_iterations"] == CONFIG.iterations
    assert len(report["loss_history"]) > 0 and report["loss_history"][-1]["iteration"] == CONFIG.iterations
    assert report["provenance"]["resumed_from"] == {"artifact_id": checkpoint[0]["artifactId"], "sha256": checkpoint[0]["sha256"],
                                                   "iteration": done}
    # Continued, not restarted: exactly the cloud the uninterrupted run trained, bit for bit.
    _same_cloud(_cloud(harness, resumed), _cloud(harness, straight))
    assert _training_report(harness, straight)["densification"] == report["densification"]
    # Each attempt wrote under its own prefix: nothing of the first attempt is overwritten or re-registered.
    assert all("/attempt-2/" in a["key"] for a in resumed["artifacts"])
    assert harness.storage.exists(DERIVED_BUCKET, _artifact(stopped, "SPLAT_CHECKPOINT")["key"])


def test_repeated_time_boxed_attempts_each_continue_from_the_latest_checkpoint(harness, scan):
    straight = _run(harness, scan, 9, CpuResumableSplat())  # the uninterrupted reference, under its own prefix
    first = _run(harness, scan, 1, CpuResumableSplat(budget=30))
    second = _run(harness, scan + _checkpoint_input(harness, first), 2, CpuResumableSplat(budget=30))
    assert first["errorCode"] == second["errorCode"] == "TIME_LIMIT_EXCEEDED"
    a, b = first["errorDetails"]["completed_iterations"], second["errorDetails"]["completed_iterations"]
    assert 0 < a < b < CONFIG.iterations, "the second attempt went further than the first, from where it stopped"
    assert _training_report(harness, second)["resumed_from_iteration"] == a

    third = _run(harness, scan + _checkpoint_input(harness, second), 3, CpuResumableSplat())
    assert third["status"] == "SUCCEEDED"
    assert _training_report(harness, third)["resumed_from_iteration"] == b
    _same_cloud(_cloud(harness, third), _cloud(harness, straight))


def test_a_retry_without_a_checkpoint_trains_from_the_sfm_seed(harness, scan):
    report = _run(harness, scan, 2, CpuResumableSplat())
    assert report["status"] == "SUCCEEDED"
    training = _training_report(harness, report)
    assert training["resumed_from_iteration"] == 0 and training["provenance"]["resumed_from"] is None


# ---- refused checkpoints ----------------------------------------------------------------------------------------------


def _stopped_checkpoint(harness, scan):
    return _checkpoint_input(harness, _run(harness, scan, 1, CpuResumableSplat(budget=40)))


def _refused(report, code, checkpoint):
    assert report["status"] == "FAILED" and report["errorCode"] == code, report["errorMessage"]
    assert report["artifacts"] == [], "a refused resume publishes nothing: no splat, no checkpoint"
    # The key PipelineService#resumableCheckpoint uses to never offer this checkpoint again.
    key = "artifact_id" if code == "INPUT_CHECKSUM_MISMATCH" else "checkpoint"
    assert report["errorDetails"][key] == checkpoint["artifactId"]


@pytest.mark.parametrize("change", ["input_artifact", "input_bytes", "run", "scan", "scan_version"])
def test_a_checkpoint_of_other_inputs_or_another_run_or_scan_is_refused(harness, scan, change):
    checkpoint = _stopped_checkpoint(harness, scan)
    inputs = [dict(i) for i in scan]
    if change == "input_artifact":  # the same bytes registered as another artifact
        inputs[0]["artifactId"] = "00000000-0000-0000-0000-000000000000"
    elif change == "input_bytes":  # another SfM model
        path = harness.storage.root / "other-sparse.bin"
        path.write_bytes(b"another sparse model")
        inputs[0] = {**harness.raw_input("SPARSE_MODEL", path), "containsPii": False}
    elif change == "run":
        harness.run_id = "11111111-1111-1111-1111-111111111111"
    report = harness.run({**harness.order("SPLAT_RECONSTRUCTION", inputs + checkpoint, attempt=2),
                          **({"scanId": "another-scan"} if change == "scan" else {}),
                          **({"scanVersionId": "another-version"} if change == "scan_version" else {})},
                         registry={"SPLAT_RECONSTRUCTION": CpuResumableSplat()})
    _refused(report, "CHECKPOINT_MISMATCH", checkpoint[0])
    assert "different inputs" in report["errorMessage"]


def test_a_checkpoint_trained_with_other_settings_is_refused(harness, scan):
    checkpoint = _stopped_checkpoint(harness, scan)
    report = _run(harness, scan + checkpoint, 2, CpuResumableSplat(config=replace(CONFIG, lr_scale=CONFIG.lr_scale * 2)))
    _refused(report, "CHECKPOINT_MISMATCH", checkpoint[0])
    assert "lr_scale" in report["errorMessage"]


def test_more_iterations_is_a_setting_a_resume_may_change(harness, scan):
    checkpoint = _stopped_checkpoint(harness, scan)
    report = _run(harness, scan + checkpoint, 2, CpuResumableSplat(config=replace(CONFIG, iterations=50)))
    assert report["status"] == "SUCCEEDED" and _training_report(harness, report)["completed_iterations"] == 50


def _tampered(harness, checkpoint, body: bytes, *, record_new_checksum: bool):
    """The checkpoint object replaced by `body`: with the checksum recorded for it (so the download verifies and the file
    itself is what is wrong), or with the original checksum (so the object no longer matches its registration)."""
    path = harness.storage.root / "tampered.pt"
    path.write_bytes(body)
    harness.storage.upload(DERIVED_BUCKET, checkpoint["key"], path, "application/octet-stream")
    return [{**checkpoint, "sha256": hashlib.sha256(body).hexdigest() if record_new_checksum else checkpoint["sha256"]}]


@pytest.mark.parametrize("damage", ["garbage", "truncated"])
def test_a_corrupt_checkpoint_is_refused_before_any_training(harness, scan, damage):
    checkpoint = _stopped_checkpoint(harness, scan)[0]
    original = (harness.storage.root / DERIVED_BUCKET / checkpoint["key"]).read_bytes()
    body = b"not a checkpoint" if damage == "garbage" else original[: len(original) // 2]
    report = _run(harness, scan + _tampered(harness, checkpoint, body, record_new_checksum=True), 2, CpuResumableSplat())
    _refused(report, "CHECKPOINT_CORRUPT", checkpoint)
    assert "not offered again" in report["errorMessage"]


def test_a_checkpoint_object_that_no_longer_matches_its_recorded_checksum_is_refused(harness, scan):
    checkpoint = _stopped_checkpoint(harness, scan)[0]
    report = _run(harness, scan + _tampered(harness, checkpoint, b"replaced", record_new_checksum=False), 2, CpuResumableSplat())
    _refused(report, "INPUT_CHECKSUM_MISMATCH", checkpoint)


def test_a_checkpoint_without_a_recorded_checksum_is_not_resumed(harness, scan):
    checkpoint = _stopped_checkpoint(harness, scan)[0]
    report = _run(harness, scan + [{**checkpoint, "sha256": None}], 2, CpuResumableSplat())
    _refused(report, "CHECKPOINT_CORRUPT", checkpoint)


def test_more_than_one_offered_checkpoint_is_refused(harness, scan):
    first = _stopped_checkpoint(harness, scan)
    second = _checkpoint_input(harness, _run(harness, scan + first, 2, CpuResumableSplat(budget=20)))
    report = _run(harness, scan + first + second, 3, CpuResumableSplat())
    assert report["status"] == "FAILED" and report["errorCode"] == "INPUT_INVALID" and report["artifacts"] == []
    assert sorted(report["errorDetails"]["checkpoints"]) == sorted([first[0]["artifactId"], second[0]["artifactId"]])


def test_the_real_stage_resolves_its_checkpoint_with_resume_state():
    """SplatReconstruction.run needs CUDA, so the tests above drive resume_state through a CPU stage; this pins that the
    production stage uses that same function, not a copy of it."""
    import inspect

    from chaya_worker.stages.splat_reconstruction import SplatReconstruction

    source = inspect.getsource(SplatReconstruction.run)
    assert "resume_state(ctx, config=config, source=source, device=device)" in source and "resumed_from=resumed_from" in source
    assert "load_checkpoint" not in source
