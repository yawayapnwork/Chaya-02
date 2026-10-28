"""Unit tests for Stage 4: Privacy Preprocessing (chaya_worker.stages.privacy).

Verifies:
- Mask creation and anonymization of detected faces and documents/screens.
- Verification rounds and escalation to solid fill if faces remain detectable.
- SfM feature impact auditing: warning when heuristic screen/document masking covers building textures (doors, windows, panels).
- Fail-closed error PRIVACY_SFM_DEGRADED when masking reduces usable frames below MIN_USABLE_SFM_FRAMES.
- Privacy report artifact contents and metrics.
"""

from __future__ import annotations

import json
import logging
from pathlib import Path

import cv2
import numpy as np
import pytest

from chaya_worker import archive
from chaya_worker.contract import InputFile, StageContext, StageError
from chaya_worker.privacy import Region
from chaya_worker.runner import CommandRunner
from chaya_worker.settings import Settings
from chaya_worker.stages.privacy import (
    MIN_USABLE_SFM_FRAMES,
    PrivacyPreprocess,
)
from chaya_worker.toolchain import Toolchain


class SimpleRegionDetector:
    def __init__(self, name: str, region_fn) -> None:
        self.name = name
        self.region_fn = region_fn

    def detect(self, img_bgr: np.ndarray) -> list[Region]:
        return self.region_fn(img_bgr)


def _make_textured_frame(w: int = 160, h: int = 120, seed: int = 42) -> np.ndarray:
    """Creates a synthetic frame with rich realistic FAST keypoints across the image."""
    rng = np.random.default_rng(seed)
    noise = rng.integers(0, 256, (h, w, 3), dtype=np.uint8)
    return cv2.GaussianBlur(noise, (3, 3), 0)


def _build_context(tmp_path: Path, frames: dict[str, np.ndarray], privacy_enabled: bool = True) -> StageContext:
    in_dir = tmp_path / "raw_frames"
    in_dir.mkdir(parents=True, exist_ok=True)
    for name, img in frames.items():
        cv2.imwrite(str(in_dir / name), img)

    tar_path = tmp_path / "frames.tar"
    archive.pack(in_dir, tar_path)

    inputs = [InputFile({"artifactId": "FRAME_ARCHIVE_SELECTED", "kind": "FRAME_ARCHIVE_SELECTED"}, tar_path)]
    workdir = tmp_path / "work"
    workdir.mkdir(parents=True, exist_ok=True)
    return StageContext(
        {"stage": "PRIVACY_PREPROCESS", "privacyEnabled": privacy_enabled},
        inputs,
        workdir,
        logging.getLogger("test_privacy"),
        CommandRunner(tmp_path / "out.log", tmp_path / "err.log"),
        Toolchain(env={}),
        Settings(privacy_sfm_mask_margin_px=2),
        None,
    )


def test_privacy_normal_processing_preserves_sfm_features(tmp_path):
    """When faces or screens occupy a small portion, privacy runs succeed, generate valid masks, and retain >50% SfM features."""
    frame1 = _make_textured_frame(seed=1)
    frame2 = _make_textured_frame(seed=2)
    frame3 = _make_textured_frame(seed=3)
    frame4 = _make_textured_frame(seed=4)

    # Frame 1: small face at corner (10, 10, 16, 16). Detect returns region only if not solid/blurred (mean != 0)
    def detect_face(img: np.ndarray) -> list[Region]:
        sub = img[10:26, 10:26]
        # When anonymized in round 1, blur/pixelate reduces standard deviation; round 2 solid fills with 0
        if np.std(sub) > 15.0:
            return [Region(10, 10, 16, 16, "face")]
        return []

    # Frame 2: small screen in corner
    def detect_screen(img: np.ndarray) -> list[Region]:
        if np.std(img[130:146, 90:106]) > 15.0:
            return [Region(130, 90, 16, 16, "screen_or_document")]
        return []

    face_det = SimpleRegionDetector("mock_face", detect_face)
    screen_det = SimpleRegionDetector("mock_screen", detect_screen)

    stage = PrivacyPreprocess(faces=face_det, screens=screen_det)
    ctx = _build_context(tmp_path, {"f1.png": frame1, "f2.png": frame2, "f3.png": frame3, "f4.png": frame4})

    result = stage.run(ctx)
    assert result.status == "SUCCEEDED"
    assert len(result.artifacts) == 3

    report_art = next(a for a in result.artifacts if a.kind == "PRIVACY_REPORT")
    report = json.loads(report_art.path.read_text(encoding="utf-8"))
    assert report["frames"] == 4
    assert report["policy"] == "fail-closed"
    assert report["sfm_impact"]["usable_frames_after_mask"] >= MIN_USABLE_SFM_FRAMES
    assert report["sfm_impact"]["mean_retained_feature_ratio"] > 0.80


def test_privacy_detects_structural_texture_degradation_and_warns(tmp_path):
    """When screen/document detector flags large rectangular areas (e.g. doors, wall panels), the stage records sfm_degraded and warnings."""
    # Frame where features are concentrated only in a central rectangle
    frame = np.full((120, 160, 3), 128, dtype=np.uint8)
    rng = np.random.default_rng(99)
    noise = rng.integers(0, 256, (60, 80, 3), dtype=np.uint8)
    frame[30:90, 40:120] = cv2.GaussianBlur(noise, (3, 3), 0)

    # Detect this central panel as screen/document when its texture is present
    def detect_screen(img: np.ndarray) -> list[Region]:
        # Only frame with uniform border has std < 1 at corner
        if np.std(img[0:20, 0:20]) < 1.0:
            return [Region(35, 25, 90, 70, "screen_or_document")]
        return []

    screen_det = SimpleRegionDetector("mock_screen", detect_screen)
    face_det = SimpleRegionDetector("mock_face", lambda img: [])

    stage = PrivacyPreprocess(faces=face_det, screens=screen_det)
    # Include 3 other fully textured frames so total usable frames >= MIN_USABLE_SFM_FRAMES (3)
    f2 = _make_textured_frame(seed=20)
    f3 = _make_textured_frame(seed=30)
    f4 = _make_textured_frame(seed=40)
    ctx = _build_context(tmp_path, {"degraded_panel.png": frame, "f2.png": f2, "f3.png": f3, "f4.png": f4})

    result = stage.run(ctx)
    assert result.status == "SUCCEEDED"
    report_art = next(a for a in result.artifacts if a.kind == "PRIVACY_REPORT")
    report = json.loads(report_art.path.read_text(encoding="utf-8"))

    sfm_impact = report["sfm_impact"]
    assert sfm_impact["degraded_frame_count"] >= 1
    assert "degraded_panel.png" in sfm_impact["degraded_frames"]
    assert any("structural keypoints" in w for w in sfm_impact["warnings"])


def test_privacy_fails_closed_when_sfm_features_destroyed(tmp_path):
    """If privacy masks eliminate features from so many frames that usable SfM frames drop below threshold, fail closed with PRIVACY_SFM_DEGRADED."""
    # 4 frames that each have features only within a central block that gets masked
    frames = {}
    for i in range(1, 5):
        f = np.full((120, 160, 3), 128, dtype=np.uint8)
        rng = np.random.default_rng(100 + i)
        noise = rng.integers(0, 256, (60, 80, 3), dtype=np.uint8)
        f[30:90, 40:120] = cv2.GaussianBlur(noise, (3, 3), 0)
        frames[f"frame_{i}.png"] = f

    # Mask the entire region containing all keypoints for all frames
    screen_det = SimpleRegionDetector("mock_screen", lambda img: [Region(35, 25, 90, 70, "screen_or_document")])
    face_det = SimpleRegionDetector("mock_face", lambda img: [])

    stage = PrivacyPreprocess(faces=face_det, screens=screen_det)
    ctx = _build_context(tmp_path, frames)

    with pytest.raises(StageError) as exc_info:
        stage.run(ctx)

    assert exc_info.value.code == "PRIVACY_SFM_DEGRADED"
    assert "privacy masking removed too much building texture for SfM" in str(exc_info.value)
    assert exc_info.value.details["usable_frames_after_mask"] < MIN_USABLE_SFM_FRAMES
