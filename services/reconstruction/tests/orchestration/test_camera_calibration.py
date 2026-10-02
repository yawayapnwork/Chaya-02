"""Integration: a camera calibration declared in capture metadata survives ingestion and reaches reconstruction
preparation (review finding G-1).

Real stages run through the real orchestrator: INPUT_VALIDATION and FFMPEG_PREPROCESS on real image files and a real
metadata file, with the frames downscaled on the way. Their published CAMERA_CALIBRATION artifact is then handed on as
the control plane would (PII withheld) and used to build POSE_ESTIMATION's COLMAP command and SPLAT_RECONSTRUCTION's
training cameras from the extracted frames. COLMAP is not installed here and is NOT run: the sparse model's
cameras.txt is written from the declared camera, i.e. as if bundle adjustment left it unchanged, and the poses are the
synthetic ground truth. What this proves is the plumbing and the geometry of the calibration, not SfM.
"""

from __future__ import annotations

import json
from pathlib import Path

import cv2
import numpy as np
import pytest

from chaya_worker import archive
from chaya_worker.camera_model import Camera, FrameRectifier
from chaya_worker.colmap_txt import parse_cameras_txt
from chaya_worker.stages.pose_estimation import build_pose_commands
from chaya_worker.stages.splat_reconstruction import build_cameras, prepare_training_cameras, training_cameras_doc
from tests.calibration_scene import TRUE_CAMERA, centroid, render, scene_points, to_camera, viewmats
from tests.conftest import DERIVED_BUCKET, Harness

pytestmark = pytest.mark.orchestration


def _artifact(report: dict, kind: str) -> dict:
    return next(a for a in report["artifacts"] if a["kind"] == kind)


def _download(h: Harness, key: str, dest: Path) -> Path:
    h.storage.download(DERIVED_BUCKET, key, dest)
    return dest


def _capture(h: Harness, tmp_path: Path, calibration: dict | None, size: tuple[int, int] | None = None) -> list[dict]:
    inputs = []
    for i, viewmat in enumerate(viewmats()):
        photo, _ = render(TRUE_CAMERA, viewmat, scene_points())
        if size is not None:
            photo = cv2.resize(photo, size)
        path = tmp_path / f"photo-{i}.png"
        cv2.imwrite(str(path), photo)
        inputs.append(h.raw_input("RAW_IMAGE", path, "image/png"))
    meta = tmp_path / "capture.json"
    meta.write_text(json.dumps({"device": "synthetic", **({"cameraCalibration": calibration} if calibration else {})}))
    inputs.append(h.raw_input("RAW_METADATA", meta, "application/json"))
    return inputs


def _declared(origin: str = "CENTER", **overrides) -> dict:
    params = list(TRUE_CAMERA.params)
    if origin == "CENTER":  # the same lens written in OpenCV's convention
        params[2] -= 0.5
        params[3] -= 0.5
    return {"model": "OPENCV", "width": 640, "height": 480, "params": params, "pixelCoordinateOrigin": origin,
            "source": "synthetic values for a test, not a measured lens", **overrides}


def test_declared_calibration_survives_ingestion_and_drives_reconstruction_preparation(tmp_path):
    h = Harness(tmp_path, max_frame_height=240)  # frames are downscaled 2x: the calibration must follow
    raw = _capture(h, tmp_path, _declared())

    validated = h.run(h.order("INPUT_VALIDATION", raw))
    assert validated["status"] == "SUCCEEDED", validated["errorMessage"]
    input_report = json.loads(_download(h, _artifact(validated, "INPUT_REPORT")["key"], tmp_path / "ir.json").read_text())
    assert input_report["camera_calibration_status"] == "DECLARED"
    assert input_report["camera_calibration"]["camera"]["params"] == list(TRUE_CAMERA.params)  # CENTER -> COLMAP corner

    extracted = h.run(h.order("FFMPEG_PREPROCESS", raw))
    assert extracted["status"] == "SUCCEEDED", extracted["errorMessage"]
    calibration_ref = _artifact(extracted, "CAMERA_CALIBRATION")
    assert calibration_ref["containsPii"] is False

    # What the control plane offers the stages after PRIVACY_PREPROCESS: everything not flagged as PII.
    later_inputs = h.outputs_as_inputs(extracted, drop_pii=True)
    assert [i["kind"] for i in later_inputs if i["kind"] == "CAMERA_CALIBRATION"] == ["CAMERA_CALIBRATION"]

    doc = json.loads(_download(h, calibration_ref["key"], tmp_path / "cal.json").read_text())
    assert doc["source"] == "CAPTURE_METADATA" and doc["undistorted"] is False
    assert doc["declared_source"] == "synthetic values for a test, not a measured lens"
    frame_camera = Camera.from_record(doc["frame_camera"])
    assert (frame_camera.width, frame_camera.height) == (320, 240)
    assert doc["frame_scale"] == {"x": 0.5, "y": 0.5, "uniform": True}
    assert frame_camera.distortion == TRUE_CAMERA.distortion, "coefficients are scale-free and must not change"
    assert np.allclose([frame_camera.fx, frame_camera.fy, frame_camera.cx, frame_camera.cy],
                       np.array([TRUE_CAMERA.fx, TRUE_CAMERA.fy, TRUE_CAMERA.cx, TRUE_CAMERA.cy]) * 0.5, rtol=0, atol=1e-12)

    # POSE_ESTIMATION seeds COLMAP with exactly that camera.
    cmd = build_pose_commands(colmap="colmap", glomap=None, database=tmp_path / "db", images=tmp_path, sparse=tmp_path,
                              use_gpu=False, frame_count=4, mapper="colmap", camera=frame_camera)["feature_extractor"]
    assert cmd[cmd.index("--ImageReader.camera_model") + 1] == "OPENCV"
    seeded = Camera("OPENCV", 320, 240, tuple(float(p) for p in cmd[cmd.index("--ImageReader.camera_params") + 1].split(",")))
    assert seeded == frame_camera

    # SPLAT_RECONSTRUCTION's preparation on the real extracted frames. (COLMAP not run: see the module docstring.)
    cameras_txt = f"1 {seeded.model} {seeded.width} {seeded.height} {' '.join(repr(p) for p in seeded.params)}\n"
    sfm_cameras = parse_cameras_txt(cameras_txt)
    frames_dir = tmp_path / "frames"
    names = sorted(p.name for p in archive.unpack(_download(h, _artifact(extracted, "FRAME_ARCHIVE")["key"], tmp_path / "f.tar"),
                                                  frames_dir))
    assert len(names) == 4
    poses = [{"name": name, "camera_id": 1, "viewmat": vm} for name, vm in zip(names, viewmats(), strict=True)]
    cams = build_cameras([{"name": p["name"], "camera_id": 1, "rotation_wxyz": _quat_wxyz(p["viewmat"][:3, :3]).tolist(),
                           "translation": p["viewmat"][:3, 3].tolist()} for p in poses], sfm_cameras)
    rectifier = FrameRectifier()
    ready = prepare_training_cameras(cams, frames_dir, rectifier)
    assert len(ready) == 4

    # The geometry survived: known points through the training camera land on the blobs in the undistorted frames,
    # after a 2x downscale and JPEG re-encoding (looser bound than the full-resolution unit test for that reason).
    errors = []
    for cam, p in zip(ready, poses, strict=True):
        image = (cam["image"] * 255).astype(np.uint8)
        pinhole = Camera("PINHOLE", cam["width"], cam["height"], (cam["K"][0, 0], cam["K"][1, 1], cam["K"][0, 2], cam["K"][1, 2]))
        for xy in pinhole.project(to_camera(scene_points(), p["viewmat"])):
            found = centroid(image, xy, radius=5.0)
            if found is not None:
                errors.append(float(np.linalg.norm(found - xy)))
    assert len(errors) >= 100 and max(errors) < 0.5, f"max {max(errors):.3f} px over {len(errors)} blobs"

    record = training_cameras_doc(rectifier, {"calibration_source": "CAPTURE_METADATA",
                                              "capture_calibration": {"frame_camera": doc["frame_camera"]}})
    (cam_record,) = record["cameras"]
    assert cam_record["undistorted"] is True
    assert cam_record["source_camera"]["params"] == list(frame_camera.params)
    assert cam_record["training_camera"]["model"] == "PINHOLE"


def _quat_wxyz(R: np.ndarray) -> np.ndarray:
    w = np.sqrt(max(0.0, 1.0 + R[0, 0] + R[1, 1] + R[2, 2])) / 2.0
    return np.array([w, (R[2, 1] - R[1, 2]) / (4 * w), (R[0, 2] - R[2, 0]) / (4 * w), (R[1, 0] - R[0, 1]) / (4 * w)])


def test_a_capture_without_a_calibration_says_so_and_publishes_none(tmp_path):
    h = Harness(tmp_path)
    raw = _capture(h, tmp_path, None)
    validated = h.run(h.order("INPUT_VALIDATION", raw))
    assert validated["status"] == "SUCCEEDED"
    report = json.loads(_download(h, _artifact(validated, "INPUT_REPORT")["key"], tmp_path / "ir.json").read_text())
    assert report["camera_calibration_status"] == "NOT_PROVIDED" and report["camera_calibration"] is None
    extracted = h.run(h.order("FFMPEG_PREPROCESS", raw))
    assert "CAMERA_CALIBRATION" not in {a["kind"] for a in extracted["artifacts"]}
    manifest = json.loads(_download(h, _artifact(extracted, "FRAME_MANIFEST")["key"], tmp_path / "m.json").read_text())
    assert manifest["camera_calibration"] == "NOT_PROVIDED"


@pytest.mark.parametrize("calibration,size,code", [
    (_declared(model="OPENCV_FISHEYE"), None, "CAMERA_MODEL_UNSUPPORTED"),
    (_declared(pixelCoordinateOrigin="UNKNOWN"), None, "CAMERA_CALIBRATION_INVALID"),
    (_declared(), (320, 240), "CAMERA_CALIBRATION_INVALID"),  # media are not the calibrated size
    (_declared(model="SIMPLE_RADIAL", params=[500.0, 320.0, 240.0, -1000.0]), None, "CAMERA_CALIBRATION_INVALID"),  # folds
])
def test_an_unusable_declared_calibration_fails_ingestion(tmp_path, calibration, size, code):
    h = Harness(tmp_path)
    report = h.run(h.order("INPUT_VALIDATION", _capture(h, tmp_path, calibration, size)))
    assert report["status"] == "FAILED" and report["errorCode"] == code, report["errorMessage"]
    assert report["artifacts"] == []
