"""Camera intrinsics and lens distortion (review finding G-1), on synthetic calibrations with known ground truth.

The distortion arithmetic is checked against OpenCV's independent implementation (cv2.projectPoints); the
undistortion is checked the way the review asks: known 3D points projected through the training (pinhole) camera land
within 0.5 px of where they appear in the undistorted photograph."""

from __future__ import annotations

import json

import cv2
import numpy as np
import pytest

from chaya_worker.camera_model import (
    PARAM_NAMES,
    UNSUPPORTED_MODELS,
    Camera,
    CameraModelError,
    FrameRectifier,
    UnsupportedCameraModel,
    parse_capture_calibration,
    undistortion_for,
)
from chaya_worker.colmap_txt import parse_cameras_txt
from chaya_worker.stages.base import _ROTATION
from chaya_worker.stages.pose_estimation import build_pose_commands, camera_metadata
from chaya_worker.stages.splat_reconstruction import build_cameras, prepare_training_cameras, training_cameras_doc
from tests.calibration_scene import TRUE_CAMERA, centroid, render, scene_points, to_camera, viewmats

SYNTHETIC = {  # made-up parameters per supported model, every coefficient non-zero where the model has one
    "SIMPLE_PINHOLE": (500.0, 320.5, 240.25),
    "PINHOLE": (500.0, 505.0, 320.5, 240.25),
    "SIMPLE_RADIAL": (500.0, 320.5, 240.25, -0.12),
    "RADIAL": (500.0, 320.5, 240.25, -0.12, 0.03),
    "OPENCV": (500.0, 505.0, 320.5, 240.25, -0.12, 0.03, 0.001, -0.002),
    "FULL_OPENCV": (500.0, 505.0, 320.5, 240.25, -0.12, 0.03, 0.001, -0.002, 0.004, 0.01, -0.002, 0.0005),
}


def _random_points(n: int = 300, seed: int = 0) -> np.ndarray:
    rng = np.random.default_rng(seed)
    return np.c_[rng.uniform(-0.6, 0.6, (n, 2)), rng.uniform(1.0, 3.0, n)]


# ---- the model: parsing keeps every parameter ------------------------------------------------------------------------

@pytest.mark.parametrize("model", sorted(SYNTHETIC))
def test_cameras_txt_keeps_every_parameter_including_distortion(model):
    params = SYNTHETIC[model]
    cams = parse_cameras_txt(f"# c\n7 {model} 640 480 {' '.join(repr(p) for p in params)}\n")
    cam = cams[7]
    assert (cam.model, cam.width, cam.height, cam.params) == (model, 640, 480, params)
    record = cam.record()
    assert record["fx"] == params[0] and record["cx"] == 320.5 and record["cy"] == 240.25
    expected = {k: v for k, v in zip(PARAM_NAMES[model], params, strict=True) if k not in ("f", "fx", "fy", "cx", "cy")}
    assert record["distortion_coefficients"] == expected
    assert cam.has_distortion == bool(expected)
    assert json.loads(json.dumps(record)) == record  # serialisable as recorded


@pytest.mark.parametrize("model", sorted(UNSUPPORTED_MODELS) + ["NOT_A_MODEL"])
def test_unsupported_camera_models_are_refused_not_approximated(model):
    with pytest.raises(UnsupportedCameraModel, match=model):
        parse_cameras_txt(f"1 {model} 640 480 500 320 240 0.1 0.2 0.3 0.4 0.5\n")


def test_wrong_parameter_counts_and_impossible_values_are_refused():
    with pytest.raises(CameraModelError, match="takes 8 parameters"):
        Camera("OPENCV", 640, 480, (500.0, 500.0, 320.0, 240.0, 0.1))
    with pytest.raises(CameraModelError, match="focal"):
        Camera("PINHOLE", 640, 480, (-500.0, 500.0, 320.0, 240.0))
    with pytest.raises(CameraModelError, match="outside"):
        Camera("PINHOLE", 640, 480, (500.0, 500.0, 700.0, 240.0))
    with pytest.raises(CameraModelError, match="finite"):
        Camera("SIMPLE_RADIAL", 640, 480, (500.0, 320.0, 240.0, float("nan")))


# ---- the distortion arithmetic matches OpenCV's -------------------------------------------------------------------------

@pytest.mark.parametrize("model", ["SIMPLE_RADIAL", "RADIAL", "OPENCV", "FULL_OPENCV"])
def test_projection_matches_opencv_project_points(model):
    cam = Camera(model, 640, 480, SYNTHETIC[model])
    names = ("k1", "k2", "p1", "p2", "k3", "k4", "k5", "k6")
    d = cam.distortion
    if "k" in d:
        d = {"k1": d["k"]}
    coeffs = np.array([d.get(n, 0.0) for n in names])
    K = cam.K.copy()
    K[0, 2] -= 0.5  # OpenCV's pixel centre is (0, 0); COLMAP's is (0.5, 0.5)
    K[1, 2] -= 0.5
    pts = _random_points()
    expected, _ = cv2.projectPoints(pts, np.zeros(3), np.zeros(3), K, coeffs)
    assert np.abs(cam.project(pts) - (expected.reshape(-1, 2) + 0.5)).max() < 1e-9


def test_rescaling_a_camera_rescales_its_projections_and_keeps_its_coefficients():
    pts = _random_points()
    for model, params in SYNTHETIC.items():
        cam = Camera(model, 640, 480, params)
        half = cam.scaled(0.5, 0.5, 320, 240)
        assert half.model == model and half.distortion == cam.distortion
        assert np.abs(half.project(pts) - cam.project(pts) * 0.5).max() < 1e-9
        # FFmpeg rounds widths to even numbers, so the two axes can scale differently. A single-focal model becomes its
        # exact two-focal equivalent instead of being approximated.
        sx, sy = 427 / 640, 0.666
        odd = cam.scaled(sx, sy, 427, 320)
        assert odd.model in ("PINHOLE", "OPENCV", "FULL_OPENCV")
        assert np.abs(odd.project(pts) - cam.project(pts) * [sx, sy]).max() < 1e-9


# ---- capture metadata ------------------------------------------------------------------------------------------------

def _metadata(**overrides) -> dict:
    block = {"model": "OPENCV", "width": 640, "height": 480, "params": list(SYNTHETIC["OPENCV"]),
             "pixelCoordinateOrigin": "CORNER", "source": "synthetic test values"}
    block.update(overrides)
    return {"device": "test", "cameraCalibration": block}


def test_capture_metadata_without_a_calibration_declares_none():
    assert parse_capture_calibration({"fps": 10, "lens": "wide"}) is None


def test_capture_metadata_calibration_is_read_exactly_and_opencv_pixel_origin_is_converted():
    camera, meta = parse_capture_calibration(_metadata())
    assert camera == Camera("OPENCV", 640, 480, SYNTHETIC["OPENCV"])
    assert meta["declared_source"] == "synthetic test values" and meta["pixel_coordinate_origin"] == "CORNER"
    centred, _ = parse_capture_calibration(_metadata(pixelCoordinateOrigin="CENTER"))
    assert (centred.cx, centred.cy) == (321.0, 240.75)
    assert centred.distortion == camera.distortion and (centred.fx, centred.fy) == (camera.fx, camera.fy)


@pytest.mark.parametrize("overrides,error", [
    ({"pixelCoordinateOrigin": None}, "pixelCoordinateOrigin"),
    ({"model": "OPENCV_FISHEYE", "params": [500, 500, 320, 240, 0.1, 0.0, 0.0, 0.0]}, "not supported"),
    ({"params": [500, 500, 320, 240]}, "takes 8 parameters"),
    ({"params": [500, 500, 320, 240, "0.1", 0, 0, 0]}, "finite number"),
    ({"width": True}, "positive integer"),
    ({"height": 0}, "positive integer"),
])
def test_capture_metadata_calibration_is_refused_when_incomplete_or_unsupported(overrides, error):
    with pytest.raises(CameraModelError, match=error):
        parse_capture_calibration(_metadata(**overrides))


def test_unsupported_model_in_metadata_has_its_own_error_code():
    with pytest.raises(UnsupportedCameraModel) as exc:
        parse_capture_calibration(_metadata(model="FOV", params=[500, 500, 320, 240, 0.9]))
    assert exc.value.code == "CAMERA_MODEL_UNSUPPORTED"


# ---- undistortion: the review's required fixture -----------------------------------------------------------------------

def test_projections_through_the_training_camera_land_within_half_a_pixel_of_the_undistorted_observations():
    """Known 3D points, photographed through a distorted camera. After undistortion, projecting them through the
    pinhole training camera with the unchanged pose must land within 0.5 px of the blobs in the undistorted frame."""
    points = scene_points()
    rectifier = FrameRectifier()
    errors, pinhole_errors = [], []
    for viewmat in viewmats():
        photo, observed = render(TRUE_CAMERA, viewmat, points)
        undistorted, pinhole = rectifier.rectify(TRUE_CAMERA, photo)
        assert pinhole.model == "PINHOLE" and (pinhole.width, pinhole.height) == (640, 480) and not pinhole.has_distortion
        predicted = pinhole.project(to_camera(points, viewmat))
        for p in predicted:
            found = centroid(undistorted, p, radius=4 * 2.5)
            if found is not None:
                errors.append(np.linalg.norm(found - p))
        # The old behaviour: the distorted camera's fx, fy, cx, cy used as a pinhole on the distorted photograph.
        naive = Camera("PINHOLE", 640, 480, (TRUE_CAMERA.fx, TRUE_CAMERA.fy, TRUE_CAMERA.cx, TRUE_CAMERA.cy))
        pinhole_errors.append(np.linalg.norm(naive.project(to_camera(points, viewmat)) - observed, axis=1).max())
    assert len(errors) >= 4 * 40, "most blobs must be inside the undistorted frames"
    assert max(errors) < 0.5, f"max reprojection error {max(errors):.3f} px"
    # Not an accuracy claim about any real capture: it shows this fixture's distortion is large enough that ignoring
    # it would have been caught by the 0.5 px bound above.
    assert min(pinhole_errors) > 10.0


def test_undistortion_fills_every_pixel_from_inside_the_photograph():
    for model in ("SIMPLE_RADIAL", "RADIAL", "OPENCV", "FULL_OPENCV"):
        for sign in (-1.0, 1.0):  # barrel and pincushion
            params = list(SYNTHETIC[model])
            first_k = len(PARAM_NAMES[model]) - len([n for n in PARAM_NAMES[model] if n not in ("f", "fx", "fy", "cx", "cy")])
            params[first_k] = sign * abs(params[first_k])
            plan = undistortion_for(Camera(model, 640, 480, tuple(params)))
            assert plan.map_x.min() >= 0 and plan.map_x.max() <= 639 and plan.map_y.min() >= 0 and plan.map_y.max() <= 479
            assert plan.target.cx == plan.source.cx and plan.target.cy == plan.source.cy
            assert (plan.focal_scale < 1.0) == (sign < 0), "barrel keeps a wider view, pincushion a narrower one"


def test_a_distortion_that_folds_inside_the_image_is_refused():
    folding = Camera("SIMPLE_RADIAL", 640, 480, (500.0, 320.0, 240.0, -1000.0))
    with pytest.raises(CameraModelError, match="not invertible"):
        undistortion_for(folding)
    with pytest.raises(CameraModelError, match="not invertible"):
        FrameRectifier().rectify(folding, np.zeros((480, 640, 3), np.uint8))


def test_a_camera_without_distortion_passes_frames_through_unchanged_and_says_so():
    rectifier = FrameRectifier()
    cam = Camera("SIMPLE_RADIAL", 640, 480, (500.0, 320.0, 240.0, 0.0))
    frame = np.random.default_rng(1).integers(0, 255, (480, 640, 3), dtype=np.uint8)
    out, pinhole = rectifier.rectify(cam, frame)
    assert out is frame and np.allclose(pinhole.K, cam.K)
    (record,) = rectifier.records()
    assert record["undistorted"] is False and record["undistortion"] is None and record["frames"] == 1


def test_a_frame_of_the_wrong_size_for_its_camera_is_refused():
    with pytest.raises(CameraModelError, match="640x480"):
        FrameRectifier().rectify(TRUE_CAMERA, np.zeros((240, 320, 3), np.uint8))


# ---- the stages' camera plumbing ---------------------------------------------------------------------------------------

def test_training_cameras_are_undistorted_and_recorded(tmp_path):
    points = scene_points()
    poses = []
    for i, viewmat in enumerate(viewmats()):
        photo, _ = render(TRUE_CAMERA, viewmat, points)
        cv2.imwrite(str(tmp_path / f"{i:06d}.png"), photo)
        q = _quat_wxyz(viewmat[:3, :3])
        poses.append({"name": f"{i:06d}.png", "camera_id": 1, "rotation_wxyz": q.tolist(), "translation": viewmat[:3, 3].tolist()})
    poses.append({"name": "missing.png", "camera_id": 1, "rotation_wxyz": [1, 0, 0, 0], "translation": [0, 0, 0]})
    cams = build_cameras(poses, {1: TRUE_CAMERA})
    assert all("K" not in c for c in cams), "no pinhole K before undistortion"
    rectifier = FrameRectifier()
    ready = prepare_training_cameras(cams, tmp_path, rectifier)
    assert len(ready) == 4
    plan = rectifier.plan(TRUE_CAMERA)
    for cam in ready:
        assert np.allclose(cam["K"], plan.target.K) and cam["image"].shape == (480, 640, 3) and cam["image"].dtype == np.float32
    doc = training_cameras_doc(rectifier, {"calibration_source": "CAPTURE_METADATA", "capture_calibration": {"x": 1}})
    (record,) = doc["cameras"]
    assert doc["calibration_source"] == "CAPTURE_METADATA"
    assert record["undistorted"] is True and record["frames"] == 4
    assert record["source_camera"]["distortion_model"] == "RADIAL_TANGENTIAL"
    assert record["source_camera"]["distortion_coefficients"] == {"k1": -0.25, "k2": 0.08, "p1": 0.0012, "p2": -0.0008}
    assert record["training_camera"]["model"] == "PINHOLE" and record["training_camera"]["distortion_coefficients"] == {}
    assert record["undistortion"]["focal_scale"] == plan.focal_scale
    legacy = training_cameras_doc(rectifier, {})
    assert legacy["calibration_source"].startswith("UNRECORDED")


def _quat_wxyz(R: np.ndarray) -> np.ndarray:
    w = np.sqrt(max(0.0, 1.0 + R[0, 0] + R[1, 1] + R[2, 2])) / 2.0
    return np.array([w, (R[2, 1] - R[1, 2]) / (4 * w), (R[0, 2] - R[2, 0]) / (4 * w), (R[1, 0] - R[0, 1]) / (4 * w)])


def test_pose_commands_seed_colmap_with_the_declared_calibration(tmp_path):
    kw = {"colmap": "colmap", "glomap": None, "database": tmp_path / "d.db", "images": tmp_path, "sparse": tmp_path,
          "use_gpu": False, "frame_count": 10, "mapper": "colmap"}
    default = build_pose_commands(**kw)["feature_extractor"]
    assert default[default.index("--ImageReader.camera_model") + 1] == "SIMPLE_RADIAL"
    assert "--ImageReader.camera_params" not in default
    seeded = build_pose_commands(**kw, camera=TRUE_CAMERA)["feature_extractor"]
    assert seeded[seeded.index("--ImageReader.camera_model") + 1] == "OPENCV"
    params = seeded[seeded.index("--ImageReader.camera_params") + 1]
    assert tuple(float(p) for p in params.split(",")) == TRUE_CAMERA.params  # full precision, COLMAP's order


def test_poses_record_where_the_camera_came_from():
    self_calibrated = camera_metadata({1: Camera("SIMPLE_RADIAL", 640, 480, (500.0, 320.0, 240.0, -0.1))}, None, None)
    assert self_calibrated["calibration_source"] == "SFM_SELF_CALIBRATION" and self_calibrated["capture_calibration"] is None
    assert self_calibrated["cameras"]["1"]["distortion_coefficients"] == {"k": -0.1}
    refined = Camera("OPENCV", 640, 480, (521.0, *TRUE_CAMERA.params[1:]))
    declared = camera_metadata({1: refined}, TRUE_CAMERA, {"metadata_artifact_id": "m", "declared_source": "lab"})
    assert declared["calibration_source"] == "CAPTURE_METADATA"
    assert declared["capture_calibration"]["frame_camera"]["params"] == list(TRUE_CAMERA.params)
    assert declared["capture_calibration"]["refinement_by_sfm"]["1"] == {"model_changed": False, "max_abs_param_change": 1.0}


@pytest.mark.parametrize("line,degrees", [("      displaymatrix: rotation of -90.00 degrees", "-90.00"),
                                          ("    rotate          : 90", "90")])
def test_video_rotation_is_detected_in_ffmpeg_output(line, degrees):
    m = _ROTATION.search(f"Stream #0:0: Video: h264, 1920x1080\n{line}\n")
    assert m and (m.group(1) or m.group(2)) == degrees
