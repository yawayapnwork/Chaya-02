"""Privacy masks (review G-2): the pixels anonymisation rewrote are known, published, survive the JPEG archive and
undistortion, and every stage that learns from frames is handed them. Deterministic: fixed frames, real OpenCV codecs,
the real privacy stage with the real detectors."""

from __future__ import annotations

import json
from pathlib import Path
from types import SimpleNamespace

import cv2
import numpy as np
import pytest

from chaya_worker import archive
from chaya_worker.camera_model import FrameRectifier
from chaya_worker.errors import StageError
from chaya_worker.privacy import Region, anonymize, covered_box
from chaya_worker.privacy import masks as pm
from chaya_worker.stages.pose_estimation import build_pose_commands, write_sfm_masks
from chaya_worker.stages.semantic_indexing import box_masked_fraction, visible_through_valid
from chaya_worker.stages.splat_reconstruction import build_cameras, prepare_training_cameras, training_source
from tests.calibration_scene import TRUE_CAMERA
from tests.conftest import DERIVED_BUCKET, shaken_face_frames
from tests.orchestration.test_stages_and_orchestrator import artifact, download, frame_archive

FILL = (255, 0, 255)


def _textured(h: int = 240, w: int = 320, seed: int = 0) -> np.ndarray:
    rng = np.random.default_rng(seed)
    base = cv2.resize(rng.integers(0, 255, (h // 8, w // 8, 3), dtype=np.uint8), (w, h), interpolation=cv2.INTER_CUBIC)
    return base


def _jpeg(img: np.ndarray, quality: int = 92) -> np.ndarray:
    ok, buf = cv2.imencode(".jpg", img, [cv2.IMWRITE_JPEG_QUALITY, quality])
    assert ok
    return cv2.imdecode(buf, cv2.IMREAD_COLOR)


# ---- the mask itself -------------------------------------------------------------------------------------------------

def test_the_mask_covers_every_pixel_anonymize_rewrites_and_leaves_the_rest_valid():
    img = _textured()
    regions = [Region(40, 50, 30, 36, "face"), Region(250, 10, 60, 40, "screen_or_document")]
    for solid in (False, True):
        out = anonymize(img, regions, solid=solid)
        changed = (out != img).any(axis=2)
        valid = pm.valid_mask(regions, *img.shape[:2]) == pm.VALID
        assert changed.any() and not (changed & valid).any(), "a rewritten pixel was left reconstruction-valid"
    # and only the regions' neighbourhood is masked, not the frame
    assert 0.05 < (~valid).mean() < 0.5
    x0, y0, x1, y1 = covered_box(regions[0], *img.shape[:2])
    assert valid[y1 + 2 * pm.CODEC_BLOCK:, :].any() and valid[:, x1 + 2 * pm.CODEC_BLOCK:].any()


def test_a_frame_without_regions_is_entirely_valid():
    assert (pm.valid_mask([], 100, 130) == pm.VALID).all()


@pytest.mark.parametrize("quality", [75, 92, 100])
@pytest.mark.parametrize("shape", [(240, 320), (237, 331)])  # MCU-aligned and not
def test_outside_the_mask_the_jpeg_archive_is_bit_identical_to_the_untouched_frame(quality, shape):
    """Format conversion: the anonymised frame travels as JPEG. A solid fill bleeds through JPEG blocks and chroma
    upsampling; outside the mask the decoded anonymised frame must be exactly the decoded original."""
    img = _textured(*shape, seed=3)
    regions = [Region(37, 41, 23, 29, "face"), Region(150, 100, 70, 60, "face")]
    anon = anonymize(img, regions, solid=True)
    anon[covered(regions, shape)] = FILL  # the most contrasting fill there is
    valid = pm.valid_mask(regions, *shape) == pm.VALID
    a, b = _jpeg(img, quality), _jpeg(anon, quality)
    assert np.array_equal(a[valid], b[valid]), "fill colour leaked into reconstruction-valid pixels through JPEG"
    # the margin is needed: without it, the codec does leak
    tight = ~pm.covered(regions, *shape)
    assert not np.array_equal(a[tight], b[tight])


def covered(regions, shape):
    return pm.covered(regions, *shape)


def test_mask_files_round_trip_and_bad_masks_fail_closed(tmp_path):
    valid = pm.valid_mask([Region(10, 10, 20, 20, "face")], 64, 80)
    pm.write_mask(tmp_path, "000001.jpg", valid)
    masks = pm.FrameMasks(tmp_path)
    assert np.array_equal(masks.valid("000001.jpg", (64, 80)), valid == pm.VALID)
    with pytest.raises(StageError) as missing:
        masks.valid("000002.jpg", (64, 80))
    assert missing.value.code == "PRIVACY_MASK_INVALID"
    with pytest.raises(StageError, match="80x64"):
        masks.valid("000001.jpg", (64, 81))
    cv2.imwrite(str(tmp_path / "000003.jpg.png"), np.full((64, 80), 128, np.uint8))
    with pytest.raises(StageError, match="not binary"):
        masks.valid("000003.jpg", (64, 80))


def _ctx(tmp_path: Path, inputs: list, privacy: bool):
    return SimpleNamespace(inputs_of=lambda *k: [i for i in inputs if i.kind in k], order={"privacyEnabled": privacy},
                           workdir=tmp_path, stage="SPLAT_RECONSTRUCTION")


def test_a_privacy_run_without_masks_refuses_to_learn_from_anonymised_frames(tmp_path):
    with pytest.raises(StageError) as exc:
        pm.from_inputs(_ctx(tmp_path, [], privacy=True))
    assert exc.value.code == "PRIVACY_MASKS_MISSING"
    assert pm.from_inputs(_ctx(tmp_path, [], privacy=False)) is None


# ---- the privacy stage publishes them --------------------------------------------------------------------------------

def test_the_privacy_stage_publishes_a_mask_for_every_frame_covering_every_changed_pixel(harness, tmp_path):
    selected = frame_archive(harness, tmp_path, shaken_face_frames(4), "FRAME_ARCHIVE_SELECTED")
    # the stage's input, exactly as it reads it
    frames = [cv2.imread(str(f)) for f in archive.unpack(harness.storage.root / selected["bucket"] / selected["key"],
                                                          tmp_path / "selected")]
    report = harness.run(harness.order("PRIVACY_PREPROCESS", [selected]))
    assert report["status"] == "SUCCEEDED", report["errorMessage"]
    masks_art = artifact(report, pm.KIND)
    assert masks_art["containsPii"] is False and "/pii/" not in masks_art["key"]
    anon = archive.unpack(download(harness, artifact(report, "FRAME_ARCHIVE_ANON")["key"], tmp_path), tmp_path / "anon")
    mask_files = archive.unpack(harness.storage.root / DERIVED_BUCKET / masks_art["key"], tmp_path / "masks")
    assert [m.name for m in mask_files] == [pm.mask_name(f.name) for f in anon]
    masks = pm.FrameMasks(tmp_path / "masks")
    doc = json.loads(download(harness, artifact(report, "PRIVACY_REPORT")["key"], tmp_path).read_text())
    for original, path, entry in zip(frames, anon, doc["per_frame"], strict=True):
        out = cv2.imread(str(path))
        valid = masks.valid(path.name, out.shape[:2])
        # every pixel the stage changed is masked: compared after the same JPEG round trip the frames go through
        assert np.array_equal(_jpeg(original)[valid], out[valid]), "a changed pixel is marked reconstruction-valid"
        assert (~valid).any() and valid.any()
        assert entry["masked_fraction"] == pytest.approx((~valid).mean(), abs=1e-6)
        # the report names every region that was covered, escalation rounds included
        assert len(entry["covered_regions"]) >= len(entry["regions"])
    assert doc["masks"]["artifact"] == "privacy-masks.tar" and 0 < doc["masks"]["masked_fraction"] < 1


# ---- masks survive preprocessing -------------------------------------------------------------------------------------

def test_undistorting_a_mask_never_lets_a_valid_pixel_depend_on_a_masked_one():
    """Resampling: whatever the masked source pixels hold, no valid undistorted pixel changes."""
    rectifier = FrameRectifier()
    regions = [Region(200, 150, 90, 80, "face"), Region(5, 5, 40, 40, "face")]
    valid = pm.valid_mask(regions, 480, 640) == pm.VALID
    out = rectifier.rectify_mask(TRUE_CAMERA, valid)
    assert out.shape == valid.shape and out.dtype == bool and (~out).any() and out.mean() > 0.8
    scene = _textured(480, 640, seed=7)
    for fill in ((0, 0, 0), FILL, (255, 255, 255)):
        filled = scene.copy()
        filled[~valid] = fill
        a, _ = rectifier.rectify(TRUE_CAMERA, scene)
        b, _ = rectifier.rectify(TRUE_CAMERA, filled)
        assert np.array_equal(a[out], b[out]), "a masked source pixel reached a valid undistorted pixel"
        assert not np.array_equal(a[~out], b[~out])


def test_training_frames_keep_their_masks_through_undistortion_and_lose_the_fill(tmp_path):
    frames_dir, masks_dir = tmp_path / "frames", tmp_path / "masks"
    frames_dir.mkdir(), masks_dir.mkdir()
    clean_dir = tmp_path / "clean"
    clean_dir.mkdir()
    regions = [Region(260, 180, 100, 90, "face")]
    poses = []
    for i in range(3):
        img = _textured(480, 640, seed=i)
        anon = anonymize(img, regions, solid=True)
        anon[pm.covered(regions, 480, 640)] = FILL
        name = f"{i:06d}.jpg"
        cv2.imwrite(str(frames_dir / name), anon, [cv2.IMWRITE_JPEG_QUALITY, 92])
        cv2.imwrite(str(clean_dir / name), img, [cv2.IMWRITE_JPEG_QUALITY, 92])  # the same frame, never anonymised
        pm.write_mask(masks_dir, name, pm.valid_mask(regions, 480, 640))
        poses.append({"name": name, "camera_id": 1, "rotation_wxyz": [1, 0, 0, 0], "translation": [0.1 * i, 0, 0]})
    cams = prepare_training_cameras(build_cameras(poses, {1: TRUE_CAMERA}), frames_dir, FrameRectifier(),
                                    pm.FrameMasks(masks_dir))
    clean = prepare_training_cameras(build_cameras(poses, {1: TRUE_CAMERA}), clean_dir, FrameRectifier())
    assert len(cams) == 3
    for cam, reference in zip(cams, clean, strict=True):
        valid, image = cam["valid"], cam["image"]
        assert valid.shape == image.shape[:2] and (~valid).any() and valid.mean() > 0.8
        assert (image[~valid] == 0).all(), "masked training pixels must be zeroed, not kept"
        # unmasked pixels stay usable, exactly as the never-anonymised frame would have given them
        assert np.array_equal(image[valid], reference["image"][valid]), "the fill reached a reconstruction-valid pixel"
        assert cam["masked_fraction"] == pytest.approx((~valid).mean())


def test_a_training_frame_without_its_mask_fails_the_stage(tmp_path):
    (tmp_path / "masks").mkdir()
    cv2.imwrite(str(tmp_path / "000000.jpg"), _textured(480, 640))
    cams = build_cameras([{"name": "000000.jpg", "camera_id": 1, "rotation_wxyz": [1, 0, 0, 0], "translation": [0, 0, 0]}],
                         {1: TRUE_CAMERA})
    with pytest.raises(StageError) as exc:
        prepare_training_cameras(cams, tmp_path, FrameRectifier(), pm.FrameMasks(tmp_path / "masks"))
    assert exc.value.code == "PRIVACY_MASK_INVALID"


def test_the_checkpoint_identity_includes_the_masks():
    def ref(kind, sha):
        return SimpleNamespace(kind=kind, artifact_id=kind.lower(), ref={"sha256": sha})

    base = [ref("SPARSE_MODEL", "a"), ref("POSES", "b"), ref("FRAME_ARCHIVE_ANON", "c")]
    one = training_source({"runId": "r"}, [*base, ref(pm.KIND, "d")])
    other = training_source({"runId": "r"}, [*base, ref(pm.KIND, "e")])
    assert one["digest"] != other["digest"]


# ---- SfM and semantics are handed them -------------------------------------------------------------------------------

def test_colmap_extracts_no_feature_from_a_masked_pixel_or_its_margin(tmp_path):
    frames_dir, masks_dir = tmp_path / "frames", tmp_path / "masks"
    frames_dir.mkdir(), masks_dir.mkdir()
    cv2.imwrite(str(frames_dir / "a.jpg"), _textured())
    pm.write_mask(masks_dir, "a.jpg", pm.valid_mask([Region(100, 100, 20, 20, "face")], 240, 320))
    doc = write_sfm_masks([frames_dir / "a.jpg"], pm.FrameMasks(masks_dir), tmp_path / "sfm", margin_px=16)
    sfm = cv2.imread(str(tmp_path / "sfm" / "a.jpg.png"), cv2.IMREAD_UNCHANGED)
    privacy = pm.FrameMasks(masks_dir).valid("a.jpg", (240, 320))
    assert not ((sfm == pm.VALID) & ~privacy).any()
    ys, xs = np.nonzero(~privacy)
    assert (sfm[max(0, ys.min() - 16):ys.max() + 17, max(0, xs.min() - 16):xs.max() + 17] == pm.MASKED).all()
    assert doc["applied"] and doc["feature_masked_fraction"] > (~privacy).mean()
    cmd = build_pose_commands(colmap="colmap", glomap=None, database=tmp_path / "db", images=frames_dir, sparse=tmp_path / "sp",
                              use_gpu=False, frame_count=1, mapper="colmap", mask_path=tmp_path / "sfm")
    fe = cmd["feature_extractor"]
    assert fe[fe.index("--ImageReader.mask_path") + 1] == str(tmp_path / "sfm")


def test_detections_and_geometry_do_not_come_from_masked_pixels():
    valid = np.ones((100, 100), bool)
    valid[20:60, 20:60] = False
    assert box_masked_fraction(valid, 20, 20, 60, 60) == 1.0
    assert box_masked_fraction(valid, 60, 60, 100, 100) == 0.0
    assert box_masked_fraction(None, 0, 0, 10, 10) == 0.0
    px = np.array([[30.0, 30.0], [80.0, 80.0], [500.0, 500.0]])
    visible = np.array([True, True, False])
    assert visible_through_valid(px, visible, valid).tolist() == [False, True, False]
    assert visible_through_valid(px, visible, None) is visible
