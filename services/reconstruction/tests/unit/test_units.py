"""Pure unit tests: no external tools, no services."""

from __future__ import annotations

import io
import json
import logging
import sys
import tarfile
import time
from pathlib import Path

import pytest

from chaya_worker import archive
from chaya_worker.errors import DependencyError, TimeLimitExceeded
from chaya_worker.logging_json import JsonFormatter, stage_logger
from chaya_worker.runner import CommandRunner
from chaya_worker.settings import Settings
from chaya_worker.stages import STAGE_ORDER, default_registry, runs_after_privacy
from chaya_worker.stages.ffmpeg_preprocess import build_extract_command
from chaya_worker.stages.pose_estimation import build_pose_commands, parse_images_txt
from chaya_worker.storage import LocalStorage, StorageError
from chaya_worker.toolchain import Toolchain


def test_structured_logs_are_one_json_object_per_line_with_context():
    stream = io.StringIO()
    handler = logging.StreamHandler(stream)
    handler.setFormatter(JsonFormatter())
    logger = logging.getLogger("unit.json")
    logger.addHandler(handler)
    logger.setLevel(logging.INFO)
    stage_logger("unit.json", job_id="j1", stage="POSE_ESTIMATION").info("hello", extra={"frames": 3})
    doc = json.loads(stream.getvalue())
    assert doc["msg"] == "hello" and doc["job_id"] == "j1" and doc["stage"] == "POSE_ESTIMATION" and doc["frames"] == 3
    assert doc["level"] == "INFO" and doc["ts"].endswith("+00:00")


def test_settings_come_from_the_environment_and_secrets_have_no_default():
    s = Settings.from_env({"CHAYA_API_URL": "http://api/", "WORKER_STAGES": "POSE_ESTIMATION,INPUT_VALIDATION", "MIN_FRAMES": "7"})
    assert s.api_url == "http://api" and s.stages == ("POSE_ESTIMATION", "INPUT_VALIDATION") and s.min_frames == 7
    assert s.client_secret == "" and s.s3_secret_key == ""
    with pytest.raises(SystemExit, match="CHAYA_CLIENT_SECRET"):
        s.require_service_config()


def test_the_registry_covers_every_planned_stage_in_order():
    assert list(STAGE_ORDER) == [
        "INPUT_VALIDATION", "FFMPEG_PREPROCESS", "FRAME_QUALITY_FILTER", "PRIVACY_PREPROCESS", "POSE_ESTIMATION", "SPLAT_RECONSTRUCTION",
        "SEMANTIC_SEGMENTATION", "GEOMETRIC_CLEANUP", "REGION_ALIGNMENT", "REGION_SPLICE",
        "PLANE_FITTING", "ARTIFACT_GENERATION", "SEMANTIC_INDEXING", "NAVIGATION_BAKING"]
    assert set(default_registry()) == set(STAGE_ORDER)
    assert not runs_after_privacy("FRAME_QUALITY_FILTER") and not runs_after_privacy("PRIVACY_PREPROCESS")
    assert runs_after_privacy("POSE_ESTIMATION") and runs_after_privacy("SEMANTIC_INDEXING")


def test_archives_round_trip_and_refuse_unsafe_members(tmp_path):
    src = tmp_path / "in"
    src.mkdir()
    (src / "a.jpg").write_bytes(b"1")
    (src / "b.jpg").write_bytes(b"2")
    tar = tmp_path / "x.tar"
    assert archive.pack(src, tar) == 2
    assert [p.name for p in archive.unpack(tar, tmp_path / "out")] == ["a.jpg", "b.jpg"]

    evil = tmp_path / "evil.tar"
    with tarfile.open(evil, "w") as t:
        info = tarfile.TarInfo("../escape.txt")
        info.size = 1
        t.addfile(info, io.BytesIO(b"x"))
    with pytest.raises(ValueError, match="unsafe"):
        archive.unpack(evil, tmp_path / "out2")
    assert not (tmp_path / "escape.txt").exists()


def test_local_storage_refuses_keys_that_escape_the_root(tmp_path):
    store = LocalStorage(tmp_path / "root")
    with pytest.raises(StorageError):
        store.upload("b", "../../outside", tmp_path / "x", "text/plain")


def test_ffmpeg_extract_command_never_upscales_and_is_exact():
    argv = build_extract_command("ffmpeg", Path("in.mp4"), Path("out/%06d.jpg"), 2.0, 1080, 900)
    assert argv[:2] == ["ffmpeg", "-hide_banner"] and "fps=2,scale=-2:'min(1080,ih)'" in argv and argv[argv.index("-frames:v") + 1] == "900"


def test_pose_commands_use_glomap_when_available_and_colmap_otherwise():
    common = dict(colmap="colmap", database=Path("db"), images=Path("img"), sparse=Path("sp"), use_gpu=False, frame_count=50)
    glo = build_pose_commands(glomap="glomap", mapper="glomap", **common)
    assert glo["mapper"][:2] == ["glomap", "mapper"] and glo["feature_extractor"][:2] == ["colmap", "feature_extractor"]
    assert glo["matcher"][1] == "exhaustive_matcher" and "0" == glo["feature_extractor"][glo["feature_extractor"].index("--SiftExtraction.use_gpu") + 1]
    col = build_pose_commands(glomap=None, mapper="colmap", **common)
    assert col["mapper"][:2] == ["colmap", "mapper"]
    assert build_pose_commands(glomap=None, mapper="colmap", **{**common, "frame_count": 1000})["matcher"][1] == "sequential_matcher"
    with pytest.raises(ValueError):
        build_pose_commands(glomap=None, mapper="glomap", **common)


def test_images_txt_parser_reads_colmap_text_model_format():
    # Format documented by COLMAP: header comment lines, then per image a pose line followed by a 2D-points line.
    text = """# Image list with two lines of data per image:
#   IMAGE_ID, QW, QX, QY, QZ, TX, TY, TZ, CAMERA_ID, NAME
#   POINTS2D[] as (X, Y, POINT3D_ID)
# Number of images: 2, mean observations per image: 2
1 0.99 0.01 0.02 0.03 1.0 2.0 3.0 1 f001.jpg
100.5 200.5 7 300.1 50.2 -1
2 0.98 0.0 0.1 0.0 -1.0 0.5 2.5 1 f002.jpg

"""
    poses = parse_images_txt(text)
    assert [p["name"] for p in poses] == ["f001.jpg", "f002.jpg"]
    assert poses[0]["rotation_wxyz"] == [0.99, 0.01, 0.02, 0.03] and poses[1]["translation"] == [-1.0, 0.5, 2.5]
    with pytest.raises(ValueError):
        parse_images_txt("1 0.9 0 0\n\n")


def test_toolchain_detects_missing_tools_explicitly_and_names_them():
    tc = Toolchain(env={}, which=lambda _n: None)
    assert not tc.colmap().available and "COLMAP_BIN" in tc.colmap().detail
    with pytest.raises(DependencyError) as info:
        tc.require(["colmap", "glomap", "py:definitely_not_a_module"], stage="POSE_ESTIMATION")
    assert info.value.code == "DEPENDENCY_UNAVAILABLE"
    assert info.value.details["missing"] == ["colmap", "glomap", "definitely_not_a_module"]
    assert Toolchain(env={}, which=lambda _n: None).module("json").available


def test_the_runner_captures_output_and_records_exact_commands(tmp_path):
    runner = CommandRunner(tmp_path / "out.log", tmp_path / "err.log")
    runner.run([sys.executable, "-c", "import sys; print('to-stdout'); print('to-stderr', file=sys.stderr)"])
    assert "to-stdout" in (tmp_path / "out.log").read_text() and "to-stderr" in (tmp_path / "err.log").read_text()
    assert runner.commands()[0][0] == sys.executable and runner.last_exit_status() == 0
    from chaya_worker.errors import StageError
    with pytest.raises(StageError) as info:
        runner.run([sys.executable, "-c", "raise SystemExit(3)"], error_code="BOOM")
    assert info.value.code == "BOOM" and info.value.exit_status == 3 and runner.last_exit_status() == 3


def test_the_runner_stops_commands_when_the_time_budget_runs_out(tmp_path):
    runner = CommandRunner(tmp_path / "o", tmp_path / "e", deadline=time.time() + 1.0)
    started = time.time()
    with pytest.raises(TimeLimitExceeded):
        runner.run([sys.executable, "-c", "import time; time.sleep(60)"])
    assert time.time() - started < 20
    with pytest.raises(TimeLimitExceeded, match="already spent"):
        CommandRunner(tmp_path / "o", tmp_path / "e", deadline=time.time() - 1).run([sys.executable, "-c", "pass"])
