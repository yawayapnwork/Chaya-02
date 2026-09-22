"""Orchestration tests: the real stages run on tiny real files (FFmpeg-encoded video, real photographs, JSON).
No GPU and no external services are needed. Nothing here needs COLMAP, GLOMAP or gsplat; behaviour that
depends on them is tested as an explicit, structured dependency error."""

from __future__ import annotations

import json
import sys
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path

import cv2
import numpy as np
import pytest

from chaya_worker import archive
from chaya_worker.api_client import ApiError
from chaya_worker.contract import ArtifactSpec, StageResult
from chaya_worker.errors import DependencyError
from chaya_worker.privacy import FaceDetector, ScreenDocumentDetector, anonymize
from chaya_worker.stages.pose_estimation import PoseEstimation
from chaya_worker.stages.unimplemented import PLANNED, PlannedStage
from chaya_worker.storage import LocalStorage, StorageError
from chaya_worker.toolchain import Toolchain, ToolStatus
from tests.conftest import DERIVED_BUCKET, Harness, astronaut_bgr, shaken_face_frames

pytestmark = pytest.mark.orchestration

NO_TOOLS = Toolchain(env={}, which=lambda _n: None)


class AllAvailable(Toolchain):
    """Reports every dependency as present, to prove that even then an unwritten stage does not fake output."""

    def status(self, requirement: str) -> ToolStatus:
        return ToolStatus(requirement, True, path="/usr/bin/x", version="test")


def read_logs(h: Harness, report: dict, which: str = "stdout") -> list[dict]:
    dest = h.storage.root / DERIVED_BUCKET / report[which]["key"]
    return [json.loads(line) for line in dest.read_text().splitlines() if line.startswith("{")]


def download(h: Harness, key: str, tmp: Path) -> Path:
    dest = tmp / Path(key).name
    h.storage.download(DERIVED_BUCKET, key, dest)
    return dest


def artifact(report: dict, kind: str) -> dict:
    return next(a for a in report["artifacts"] if a["kind"] == kind)


def frame_archive(h: Harness, tmp_path: Path, frames: list[np.ndarray], kind: str, pii: bool = True) -> dict:
    d = tmp_path / f"frames-{kind}"
    d.mkdir()
    for i, f in enumerate(frames):
        cv2.imwrite(str(d / f"{i:06d}.jpg"), f, [cv2.IMWRITE_JPEG_QUALITY, 95])
    tar = tmp_path / f"{kind}.tar"
    archive.pack(d, tar)
    ref = h.raw_input(kind, tar, "application/x-tar")
    ref["containsPii"] = pii
    return ref


# ---- stage 1: input validation ---------------------------------------------------------------

def test_input_validation_succeeds_on_real_media_and_the_report_follows_the_stage_contract(harness, testsrc_video, jpeg_file, metadata_file, tmp_path):
    inputs = [harness.raw_input("RAW_VIDEO", testsrc_video), harness.raw_input("RAW_IMAGE", jpeg_file), harness.raw_input("RAW_METADATA", metadata_file)]
    order = harness.order("INPUT_VALIDATION", inputs)
    report = harness.run(order)

    assert report["status"] == "SUCCEEDED" and report["errorCode"] is None
    assert report["startedAt"] <= report["finishedAt"]
    assert report["exitStatus"] == 0
    assert report["command"]["commands"] and report["command"]["argv"][0].lower().endswith(("ffmpeg", "ffmpeg.exe")) or "ffmpeg" in report["command"]["argv"][0].lower()
    assert report["command"]["stage"] == "INPUT_VALIDATION" and "ffmpeg" in report["command"]["tools"]
    assert report["command"]["worker"]["id"]
    assert report["inputArtifactIds"] == [i["artifactId"] for i in inputs]
    # checksum, size, location of every output and both logs
    for a in report["artifacts"] + [report["stdout"], report["stderr"]]:
        assert a["key"].startswith(order["outputPrefix"]) and len(a["sha256"]) == 64 and a["sizeBytes"] >= 0
        assert harness.storage.exists(DERIVED_BUCKET, a["key"])
    doc = json.loads(download(harness, artifact(report, "INPUT_REPORT")["key"], tmp_path).read_text())
    video = next(f for f in doc["files"] if f["kind"] == "RAW_VIDEO")
    assert (video["width"], video["height"]) == (320, 240) and video["duration_seconds"] == pytest.approx(3.0, abs=0.2)
    assert not harness.settings.workdir.joinpath(order["id"]).exists(), "the working directory must be removed"


def test_structured_json_logs_are_produced_and_preserved(harness, testsrc_video):
    report = harness.run(harness.order("INPUT_VALIDATION", [harness.raw_input("RAW_VIDEO", testsrc_video)]))
    events = read_logs(harness, report)
    assert {e["msg"] for e in events} >= {"stage started", "stage finished"}
    for e in events:
        assert e["job_id"] and e["stage"] == "INPUT_VALIDATION" and e["run_id"] and e["level"] and e["ts"]
    assert next(e for e in events if e["msg"] == "stage finished")["status"] == "SUCCEEDED"


def test_a_corrupt_video_fails_the_stage_keeps_the_logs_and_publishes_nothing(harness, testsrc_video, tmp_path):
    corrupt = tmp_path / "broken.mp4"
    corrupt.write_bytes(testsrc_video.read_bytes()[:300])
    report = harness.run(harness.order("INPUT_VALIDATION", [harness.raw_input("RAW_VIDEO", corrupt)]))
    assert report["status"] == "FAILED" and report["errorCode"] == "INPUT_INVALID"
    assert report["errorDetails"]["problems"][0]["kind"] == "RAW_VIDEO"
    assert report["artifacts"] == []
    assert any(e["msg"] == "input rejected" for e in read_logs(harness, report))
    assert report["stderr"] and harness.storage.exists(DERIVED_BUCKET, report["stderr"]["key"])


def test_an_input_that_does_not_match_its_recorded_checksum_is_refused(harness, testsrc_video):
    ref = harness.raw_input("RAW_VIDEO", testsrc_video)
    ref["sha256"] = "0" * 64
    report = harness.run(harness.order("INPUT_VALIDATION", [ref]))
    assert report["status"] == "FAILED" and report["errorCode"] == "INPUT_CHECKSUM_MISMATCH" and report["artifacts"] == []


# ---- stage 2 and 3: frames and quality ---------------------------------------------------------

def test_frame_extraction_produces_real_frames_flagged_as_pii(testsrc_video, tmp_path):
    h = Harness(tmp_path, frame_fps=5.0)
    report = h.run(h.order("FFMPEG_PREPROCESS", [h.raw_input("RAW_VIDEO", testsrc_video)]))
    assert report["status"] == "SUCCEEDED", report["errorMessage"]
    frames = artifact(report, "FRAME_ARCHIVE")
    assert frames["containsPii"] is True and "/pii/" in frames["key"]
    assert artifact(report, "FRAME_MANIFEST")["containsPii"] is False
    names = [p for p in archive.unpack(download(h, frames["key"], tmp_path), tmp_path / "x")]
    assert 14 <= len(names) <= 16
    img = cv2.imread(str(names[0]))
    assert img is not None and img.shape[:2] == (240, 320)
    assert report["command"]["commands"][0][-1].endswith("%06d.jpg") and "fps=5" in " ".join(report["command"]["commands"][0])


def test_quality_filter_drops_blurred_badly_exposed_and_duplicate_frames_and_says_why(harness, tmp_path):
    def scene(tint: int, dark: bool = False) -> np.ndarray:
        checker = (np.indices((240, 320)).sum(axis=0) // 8 % 2).astype(np.uint8)
        base = checker * (12 if dark else 120) + (2 if dark else 40 + tint)
        return cv2.merge([base, (base * 0.8).astype(np.uint8), (base * 0.6).astype(np.uint8)])

    sharp = [scene(t) for t in (0, 30, 60, 90)]
    frames = [sharp[0], sharp[0].copy(), cv2.GaussianBlur(sharp[1], (31, 31), 0), sharp[1], scene(0, dark=True), sharp[2], sharp[3]]
    ref = frame_archive(harness, tmp_path, frames, "FRAME_ARCHIVE")
    report = harness.run(harness.order("FRAME_QUALITY_FILTER", [ref]))
    assert report["status"] == "SUCCEEDED", report["errorMessage"]
    doc = json.loads(download(harness, artifact(report, "FRAME_QUALITY_REPORT")["key"], tmp_path).read_text())
    reasons = {f["frame"]: f["reason"] for f in doc["frames"]}
    assert reasons["000001.jpg"] == "near_duplicate"
    assert reasons["000002.jpg"] == "blurred"
    assert reasons["000004.jpg"] in ("underexposed", "blurred")
    assert doc["kept"] == 4 and doc["total"] == 7
    selected = archive.unpack(download(harness, artifact(report, "FRAME_ARCHIVE_SELECTED")["key"], tmp_path), tmp_path / "sel")
    assert len(selected) == 4 and artifact(report, "FRAME_ARCHIVE_SELECTED")["containsPii"] is True


def test_quality_filter_fails_rather_than_passing_on_too_few_good_frames(harness, tmp_path):
    blurry = [cv2.GaussianBlur(np.random.default_rng(i).integers(0, 255, (120, 160, 3), dtype=np.uint8), (41, 41), 0) for i in range(6)]
    report = harness.run(harness.order("FRAME_QUALITY_FILTER", [frame_archive(harness, tmp_path, blurry, "FRAME_ARCHIVE")]))
    assert report["status"] == "FAILED" and report["errorCode"] == "INSUFFICIENT_QUALITY_FRAMES"
    assert report["errorDetails"]["dropped"]["blurred"] == 6 and report["artifacts"] == []


# ---- stage 4: privacy ---------------------------------------------------------------------------

def test_privacy_stage_really_blurs_a_real_face_and_verifies_the_result(harness, tmp_path):
    frames = shaken_face_frames(5)
    detector = FaceDetector()
    assert all(len(detector.detect(f)) >= 1 for f in frames), "test premise: the fixture photo shows a detectable face"

    report = harness.run(harness.order("PRIVACY_PREPROCESS", [frame_archive(harness, tmp_path, frames, "FRAME_ARCHIVE_SELECTED")]))
    assert report["status"] == "SUCCEEDED", report["errorMessage"]
    anon = artifact(report, "FRAME_ARCHIVE_ANON")
    assert anon["containsPii"] is False and "/pii/" not in anon["key"]
    out_frames = archive.unpack(download(harness, anon["key"], tmp_path), tmp_path / "anon")
    assert len(out_frames) == 5
    for original, path in zip(frames, out_frames):
        out = cv2.imread(str(path))
        assert detector.detect(out) == [], "no face may remain detectable in published frames"
        assert np.abs(out.astype(int) - original.astype(int)).mean() > 0.5, "the frame must actually have changed"
    doc = json.loads(download(harness, artifact(report, "PRIVACY_REPORT")["key"], tmp_path).read_text())
    assert doc["totals"]["face"] >= 5 and doc["policy"] == "fail-closed"
    assert doc["detectors"] == {"faces": "opencv-haar-faces", "screens_documents": "heuristic-quad"}


def test_privacy_stage_publishes_nothing_when_a_detector_is_unavailable(harness, tmp_path, monkeypatch):
    def missing(*_a, **_k):
        raise DependencyError("face detection model is not available", details={"missing": ["opencv-haar-cascades"]})

    monkeypatch.setattr("chaya_worker.stages.privacy.FaceDetector", missing)
    report = harness.run(harness.order("PRIVACY_PREPROCESS", [frame_archive(harness, tmp_path, shaken_face_frames(4), "FRAME_ARCHIVE_SELECTED")]))
    assert report["status"] == "FAILED" and report["errorCode"] == "DEPENDENCY_UNAVAILABLE"
    assert report["artifacts"] == [] and not any("frames-anon" in k for k in harness.storage.keys(DERIVED_BUCKET))


def test_privacy_stage_rejects_an_unknown_screen_detector_instead_of_skipping_it(tmp_path):
    h = Harness(tmp_path, privacy_screen_detector="off")
    report = h.run(h.order("PRIVACY_PREPROCESS", [frame_archive(h, tmp_path, shaken_face_frames(4), "FRAME_ARCHIVE_SELECTED")]))
    assert report["status"] == "FAILED" and report["errorCode"] == "DEPENDENCY_UNAVAILABLE" and report["artifacts"] == []


def test_privacy_stage_fails_closed_on_an_unreadable_frame(harness, tmp_path):
    d = tmp_path / "bad"
    d.mkdir()
    (d / "000000.jpg").write_bytes(b"not an image")
    tar = tmp_path / "bad.tar"
    archive.pack(d, tar)
    ref = harness.raw_input("FRAME_ARCHIVE_SELECTED", tar)
    report = harness.run(harness.order("PRIVACY_PREPROCESS", [ref]))
    assert report["status"] == "FAILED" and report["errorCode"] == "PRIVACY_FRAME_UNREADABLE" and report["artifacts"] == []


def test_screen_or_document_like_rectangles_are_detected_and_blurred():
    img = np.full((360, 480, 3), 40, np.uint8)
    cv2.rectangle(img, (100, 80), (320, 230), (225, 225, 225), -1)
    for i, y in enumerate(range(100, 220, 14)):  # "text" lines
        cv2.line(img, (115, y), (300 - 6 * (i % 3), y), (30, 30, 30), 2)
    regions = ScreenDocumentDetector().detect(img)
    assert regions, "a bright rectangle on a dark background should be flagged"
    r = max(regions, key=lambda r: r.area())
    assert abs(r.x - 100) < 25 and abs(r.y - 80) < 25 and r.label == "screen_or_document"
    blurred = anonymize(img, regions)
    inside = (slice(100, 220), slice(120, 300))
    assert blurred[inside].std() < img[inside].std() * 0.5, "the text lines must no longer be legible"


# ---- the privacy boundary in the orchestrator ------------------------------------------------------

def test_a_stage_after_privacy_refuses_inputs_that_may_contain_pii(harness):
    pii_input = {"artifactId": "a1", "kind": "FRAME_ARCHIVE", "stage": None, "bucket": DERIVED_BUCKET, "key": "does/not/matter", "sha256": "0" * 64,
                 "contentType": "application/x-tar", "sizeBytes": 1, "containsPii": True}
    report = harness.run(harness.order("POSE_ESTIMATION", [pii_input], privacy=True), toolchain=AllAvailable())
    assert report["status"] == "FAILED" and report["errorCode"] == "PRIVACY_VIOLATION"
    assert report["errorDetails"]["artifact_ids"] == ["a1"] and report["exitStatus"] is None


# ---- dependencies: explicit structured errors, never fake output ---------------------------------------

def test_pose_estimation_reports_missing_reconstruction_tools_as_a_structured_dependency_error(harness):
    report = harness.run(harness.order("POSE_ESTIMATION", []), toolchain=NO_TOOLS)
    assert report["status"] == "FAILED" and report["errorCode"] == "DEPENDENCY_UNAVAILABLE"
    assert report["errorDetails"]["missing"] == ["colmap"]
    assert report["artifacts"] == [] and report["exitStatus"] is None
    assert "colmap" in report["errorMessage"]
    assert harness.storage.keys(DERIVED_BUCKET) and all("/logs/" in k for k in harness.storage.keys(DERIVED_BUCKET)), "only logs were stored"


@pytest.mark.parametrize("stage", sorted(PLANNED))
def test_unwritten_stages_never_succeed_and_never_create_reconstruction_files(harness, stage):
    missing = harness.run(harness.order(stage, []), toolchain=NO_TOOLS)
    assert missing["status"] == "FAILED" and missing["errorCode"] == "DEPENDENCY_UNAVAILABLE" and missing["artifacts"] == []
    assert missing["errorDetails"]["missing"], "the missing dependencies are named"

    present = harness.run(harness.order(stage, []), toolchain=AllAvailable())
    assert present["status"] == "FAILED" and present["errorCode"] == "STAGE_NOT_IMPLEMENTED" and present["artifacts"] == []
    assert not [k for k in harness.storage.keys(DERIVED_BUCKET) if k.endswith((".ply", ".splat", ".ksplat", ".obj", ".glb"))]


@pytest.mark.parametrize("stage", ["SPLAT_RECONSTRUCTION", "SEMANTIC_SEGMENTATION", "GEOMETRIC_CLEANUP", "PLANE_FITTING", "SEMANTIC_INDEXING"])
def test_implemented_reconstruction_stages_refuse_to_run_without_their_real_dependencies(harness, stage):
    """SPLAT_RECONSTRUCTION..PLANE_FITTING are real implementations (not PlannedStage placeholders), but on a
    worker without torch/gsplat/CUDA/Open3D/transformers they must still fail structured, produce nothing,
    and never reach the point of touching (nonexistent) inputs with a fake result."""
    report = harness.run(harness.order(stage, []), toolchain=NO_TOOLS)
    assert report["status"] == "FAILED" and report["errorCode"] == "DEPENDENCY_UNAVAILABLE"
    assert report["artifacts"] == [] and report["errorDetails"]["missing"]
    assert not [k for k in harness.storage.keys(DERIVED_BUCKET) if k.endswith((".ply", ".ksplat"))]


def test_artifact_generation_refuses_to_run_without_a_splat_and_never_writes_a_placeholder_ksplat(harness):
    report = harness.run(harness.order("ARTIFACT_GENERATION", []))
    assert report["status"] == "FAILED" and report["errorCode"] == "INPUT_INVALID" and report["artifacts"] == []
    assert not [k for k in harness.storage.keys(DERIVED_BUCKET) if k.endswith(".ksplat")]


# ---- time box, crashes, storage, transport ------------------------------------------------------------

class _Stage:
    def __init__(self, name, fn):
        self.name, self._fn = name, fn

    def run(self, ctx):
        return self._fn(ctx)


def iso_in(seconds: float) -> str:
    return (datetime.now(timezone.utc) + timedelta(seconds=seconds)).isoformat()


def test_a_stage_that_outlives_the_time_budget_is_stopped_and_reported_as_time_limit_exceeded(harness):
    stage = _Stage("POSE_ESTIMATION", lambda ctx: ctx.runner.run([sys.executable, "-c", "import time; time.sleep(60)"]))
    started = time.time()
    report = harness.run(harness.order("POSE_ESTIMATION", [], deadline=iso_in(1.5)), registry={"POSE_ESTIMATION": stage})
    assert time.time() - started < 30
    assert report["status"] == "FAILED" and report["errorCode"] == "TIME_LIMIT_EXCEEDED" and report["artifacts"] == []
    assert report["stdout"] and report["stderr"], "logs are preserved on time-out"
    assert report["command"]["commands"][0][0] == sys.executable


def test_a_crashing_stage_becomes_an_internal_error_with_the_traceback_kept_in_the_log(harness):
    def boom(ctx):
        raise RuntimeError("kaboom")

    report = harness.run(harness.order("POSE_ESTIMATION", []), registry={"POSE_ESTIMATION": _Stage("POSE_ESTIMATION", boom)})
    assert report["status"] == "FAILED" and report["errorCode"] == "INTERNAL_ERROR" and "kaboom" in report["errorMessage"]
    assert "Traceback" in (harness.storage.root / DERIVED_BUCKET / report["stderr"]["key"]).read_text()


def test_an_unknown_stage_fails_explicitly(harness):
    report = harness.run(harness.order("NOT_A_STAGE", []))
    assert report["status"] == "FAILED" and report["errorCode"] == "UNKNOWN_STAGE"


class FailingStorage(LocalStorage):
    def upload(self, bucket, key, src, content_type):
        raise StorageError("disk full")


def test_a_storage_failure_is_reported_as_a_failed_stage_with_no_registered_artifacts(tmp_path):
    h = Harness(tmp_path)
    h.storage = FailingStorage(tmp_path / "objects")

    def make(ctx):
        f = ctx.workdir / "x.json"
        f.write_text("{}")
        return StageResult("SUCCEEDED", {"stage": ctx.stage, "argv": ["x"]}, 0, [ArtifactSpec("X", f, "x.json", "application/json")])

    report = h.run(h.order("POSE_ESTIMATION", []), registry={"POSE_ESTIMATION": _Stage("POSE_ESTIMATION", make)})
    assert report["status"] == "FAILED" and report["errorCode"] == "STORAGE_ERROR" and report["artifacts"] == []


def test_a_report_the_control_plane_rejects_is_answered_with_an_explicit_failure(harness, testsrc_video):
    harness.api.reject_first = ApiError(409, "ARTIFACT_MISSING", "artifact was not found in storage")
    order = harness.order("INPUT_VALIDATION", [harness.raw_input("RAW_VIDEO", testsrc_video)])
    harness.run(order)
    assert len(harness.api.reports) == 2
    rejected, fallback = harness.api.reports[0][1], harness.api.reports[1][1]
    assert rejected["status"] == "SUCCEEDED"
    assert fallback["status"] == "FAILED" and fallback["errorCode"] == "REPORT_REJECTED" and fallback["artifacts"] == []
    assert fallback["errorDetails"]["rejected_code"] == "ARTIFACT_MISSING"


def test_a_job_the_control_plane_took_back_is_not_reported(harness):
    harness.api.keep_going = False
    stage = _Stage("POSE_ESTIMATION", lambda ctx: ctx.runner.run([sys.executable, "-c", "import time; time.sleep(1.5)"]) and None)
    result = harness.run(harness.order("POSE_ESTIMATION", []), registry={"POSE_ESTIMATION": stage})
    assert result is None and harness.api.reports == [] and harness.api.heartbeats >= 1


def test_the_worker_polls_only_the_stages_it_is_configured_for(tmp_path, testsrc_video):
    h = Harness(tmp_path)
    h.settings = type(h.settings)(**{**h.settings.__dict__, "stages": ("FFMPEG_PREPROCESS",)})
    h.api.queue.append(h.order("INPUT_VALIDATION", [h.raw_input("RAW_VIDEO", testsrc_video)]))
    assert h.orchestrator().run_once() is False and len(h.api.queue) == 1


# ---- the first four stages chained, as the control plane would drive them -----------------------------

def test_stages_one_to_four_chain_on_a_real_video_and_the_privacy_boundary_holds(tmp_path, face_video):
    h = Harness(tmp_path, frame_fps=2.0, blur_threshold=1.0, duplicate_distance=0.3)
    validation = h.run(h.order("INPUT_VALIDATION", [h.raw_input("RAW_VIDEO", face_video)]))
    assert validation["status"] == "SUCCEEDED", validation["errorMessage"]
    raw_inputs = [h.raw_input("RAW_VIDEO", face_video)]
    frames = h.run(h.order("FFMPEG_PREPROCESS", raw_inputs))
    assert frames["status"] == "SUCCEEDED", frames["errorMessage"]
    quality = h.run(h.order("FRAME_QUALITY_FILTER", h.outputs_as_inputs(frames)))
    assert quality["status"] == "SUCCEEDED", quality["errorMessage"]
    privacy_inputs = [i for i in h.outputs_as_inputs(quality) if i["kind"] == "FRAME_ARCHIVE_SELECTED"]
    privacy = h.run(h.order("PRIVACY_PREPROCESS", privacy_inputs))
    assert privacy["status"] == "SUCCEEDED", privacy["errorMessage"]

    pii_keys = [a["key"] for r in (frames, quality) for a in r["artifacts"] if a["containsPii"]]
    assert pii_keys and all("/pii/" in k for k in pii_keys)
    assert not any(a["containsPii"] for a in privacy["artifacts"])
    # The control plane deletes PII objects once privacy succeeded; what remains for later stages is clean.
    for k in pii_keys:
        h.storage.delete(DERIVED_BUCKET, k)
    later_inputs = h.outputs_as_inputs(privacy, drop_pii=True)
    assert {i["kind"] for i in later_inputs} == {"FRAME_ARCHIVE_ANON", "PRIVACY_REPORT"}
    anon_frames = archive.unpack(h.storage.root / DERIVED_BUCKET / artifact(privacy, "FRAME_ARCHIVE_ANON")["key"], tmp_path / "final")
    detector = FaceDetector()
    assert anon_frames and all(detector.detect(cv2.imread(str(p))) == [] for p in anon_frames)

    # Then pose estimation on this machine: a structured dependency error, and the run would stop here.
    pose = h.run(h.order("POSE_ESTIMATION", later_inputs), toolchain=NO_TOOLS)
    assert pose["status"] == "FAILED" and pose["errorCode"] == "DEPENDENCY_UNAVAILABLE"
