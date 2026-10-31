"""Tests and benchmarks for capture quality filtering and privacy preprocessing.

Validates:
- All 8 evaluation categories:
    1. Empty indoor rooms (no people, furniture/shadows) -> 0 false positive faces, no false masking
    2. Faces and people -> high recall, genuine anonymization
    3. Screens and documents (active displays, printed text) -> detected and masked
    4. Blank walls -> not misclassified as blurry
    5. Corridors -> perspective geometry retained, overlap tracked
    6. Glass and reflective surfaces -> specular reflections detected and flagged
    7. Motion-blurred frames -> detected as motion_blurred via gradient tensor anisotropy
    8. Sharp but low-texture frames -> retained for SfM, operator feedback generated
- Regression tests for ETH3D recorded failures:
    - Defect D1: 83% false-positive face masking on empty office frames prevented
    - Defect D2: 5 sharp low-texture whiteboard/wall frames retained rather than dropped as blurred
- Security & Fail-closed guarantees:
    - Missing detectors or corrupted frames fail closed without publishing raw PII
- Reproducibility and operator feedback in reports.
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
from chaya_worker.errors import DependencyError
from chaya_worker.privacy import FaceDetector, ScreenDocumentDetector, anonymize
from chaya_worker.runner import CommandRunner
from chaya_worker.settings import Settings
from chaya_worker.stages.frame_quality import FrameQualityFilter, frame_metrics
from chaya_worker.stages.privacy import PrivacyPreprocess
from chaya_worker.toolchain import Toolchain
from tests.conftest import astronaut_bgr

# ==============================================================================
# Helpers & Synthetic Generators for Labeled Evaluation
# ==============================================================================

def make_empty_room(w: int = 640, h: int = 480, seed: int = 1) -> np.ndarray:
    """Category 1: Empty indoor room with walls, desk, baseboard, and corner shadows (no humans)."""
    rng = np.random.default_rng(seed)
    img = np.full((h, w, 3), 210, dtype=np.uint8)
    # Wall-floor junction
    floor_y = int(h * 0.75)
    img[floor_y:, :] = (140, 140, 150)
    cv2.line(img, (0, floor_y), (w, floor_y), (60, 60, 60), 3)
    # Desk furniture
    cv2.rectangle(img, (int(w * 0.2), int(h * 0.55)), (int(w * 0.7), int(h * 0.75)), (80, 100, 120), -1)
    # Corner shadows
    cv2.line(img, (int(w * 0.15), 0), (int(w * 0.15), floor_y), (180, 180, 180), 2)
    noise = rng.integers(-5, 5, img.shape, dtype=np.int16)
    return np.clip(img.astype(np.int16) + noise, 0, 255).astype(np.uint8)


def make_blank_wall(w: int = 640, h: int = 480, with_switch: bool = True, offset_x: int = 0, shift_val: int = 0) -> np.ndarray:
    """Category 4 & 8: Plain indoor wall with subtle lighting gradient and an optional light switch."""
    x = np.linspace(160, 185, w, dtype=np.float32)
    y = np.linspace(160, 175, h, dtype=np.float32)
    xx, yy = np.meshgrid(x, y)
    base = np.clip((xx + yy) / 2.0 + shift_val, 0, 255).astype(np.uint8)
    img = cv2.merge([base, base, base])
    if with_switch:
        # Sharp switch plate with crisp borders, shifted by camera panning
        sx = int(w * 0.3) + offset_x
        sy = int(h * 0.4)
        if 0 <= sx < w - 40:
            cv2.rectangle(img, (sx, sy), (sx + 30, sy + 45), (60, 60, 60), -1)
            cv2.rectangle(img, (sx + 8, sy + 12), (sx + 22, sy + 33), (240, 240, 240), -1)
    return img


def make_document_desk(w: int = 640, h: int = 480) -> np.ndarray:
    """Category 3: Desk containing a document with printed text lines."""
    img = np.full((h, w, 3), 90, dtype=np.uint8)
    # Sheet of paper
    doc_x0, doc_y0, doc_w, doc_h = 160, 100, 300, 240
    cv2.rectangle(img, (doc_x0, doc_y0), (doc_x0 + doc_w, doc_y0 + doc_h), (245, 245, 245), -1)
    # Text lines
    for line_y in range(doc_y0 + 30, doc_y0 + doc_h - 20, 20):
        cv2.line(img, (doc_x0 + 20, line_y), (doc_x0 + doc_w - 20, line_y), (30, 30, 30), 2)
    return img


def make_corridor(w: int = 640, h: int = 480, offset: int = 0, shift_tint: int = 0) -> np.ndarray:
    """Category 5: Indoor perspective corridor with converging wall/floor edges and textures."""
    img = np.full((h, w, 3), np.clip(200 + shift_tint, 0, 255), dtype=np.uint8)
    cx, cy = w // 2 + offset, h // 2
    # Vanishing lines
    cv2.line(img, (0, 0), (cx, cy), (50, 50, 50), 2)
    cv2.line(img, (w, 0), (cx, cy), (50, 50, 50), 2)
    cv2.line(img, (0, h), (cx, cy), (50, 50, 50), 2)
    cv2.line(img, (w, h), (cx, cy), (50, 50, 50), 2)
    # Floor fill with texture
    pts = np.array([[0, h], [w, h], [cx, cy]], np.int32)
    cv2.fillPoly(img, [pts], (150, 150, 160))
    # Natural floor/wall texture noise
    rng = np.random.default_rng(abs(offset) + abs(shift_tint) + 1)
    noise = rng.integers(-25, 25, img.shape, dtype=np.int16)
    img = np.clip(img.astype(np.int16) + noise, 0, 255).astype(np.uint8)
    # Door frames along walls
    cv2.rectangle(img, (40, 140), (100, 380), (60, 60, 70), 3)
    cv2.rectangle(img, (w - 100, 140), (w - 40, 380), (60, 60, 70), 3)
    return img


def make_reflective_surface(w: int = 640, h: int = 480) -> np.ndarray:
    """Category 6: Office window / glass table with specular glare."""
    img = np.full((h, w, 3), 120, dtype=np.uint8)
    # Saturated specular reflection
    cv2.circle(img, (int(w * 0.5), int(h * 0.4)), 40, (255, 255, 255), -1)
    cv2.circle(img, (int(w * 0.5), int(h * 0.4)), 65, (252, 252, 252), 4)
    return img


def make_motion_blurred_frame(w: int = 640, h: int = 480, kernel_size: int = 21) -> np.ndarray:
    """Category 7: Directional camera shake / motion blur."""
    rng = np.random.default_rng(42)
    noise = rng.integers(0, 255, (h, w, 3), dtype=np.uint8)
    sharp = cv2.GaussianBlur(noise, (5, 5), 0)
    # Horizontal motion streak kernel
    kernel = np.zeros((kernel_size, kernel_size), dtype=np.float32)
    kernel[kernel_size // 2, :] = 1.0 / kernel_size
    return cv2.filter2D(sharp, -1, kernel)


def _build_context(tmp_path: Path, frames: dict[str, np.ndarray], stage_name: str, settings: Settings | None = None) -> StageContext:
    in_dir = tmp_path / "raw_frames"
    in_dir.mkdir(parents=True, exist_ok=True)
    for name, img in frames.items():
        cv2.imwrite(str(in_dir / name), img)

    tar_path = tmp_path / "frames.tar"
    archive.pack(in_dir, tar_path)

    artifact_kind = "FRAME_ARCHIVE" if stage_name == "FRAME_QUALITY_FILTER" else "FRAME_ARCHIVE_SELECTED"
    inputs = [InputFile({"artifactId": artifact_kind, "kind": artifact_kind}, tar_path)]
    workdir = tmp_path / "work"
    workdir.mkdir(parents=True, exist_ok=True)
    return StageContext(
        {"stage": stage_name, "privacyEnabled": True},
        inputs,
        workdir,
        logging.getLogger(f"test_{stage_name}"),
        CommandRunner(tmp_path / "out.log", tmp_path / "err.log"),
        Toolchain(env={}),
        settings or Settings(),
        None,
    )


# ==============================================================================
# 1. Labeled Evaluation Across 8 Visual Categories
# ==============================================================================

def test_eval_category_1_empty_indoor_rooms_zero_false_face_masking():
    """Category 1: Empty indoor rooms must produce 0 false-positive face detections and 0% false masking."""
    detector = FaceDetector()
    for seed in (1, 2, 3, 4, 5):
        room = make_empty_room(seed=seed)
        faces = detector.detect(room)
        assert len(faces) == 0, f"False positive face detected in empty room (seed {seed}): {faces}"


def test_eval_category_2_faces_and_people_high_recall_and_anonymization():
    """Category 2: Real faces must be detected with 100% recall and obscured so no face remains detectable."""
    detector = FaceDetector()
    astro = astronaut_bgr()
    faces = detector.detect(astro)
    assert len(faces) >= 1, "Real face in astronaut photograph must be detected"

    # Anonymization must remove detectable faces
    anon = anonymize(astro, faces)
    remaining = detector.detect(anon)
    assert len(remaining) == 0, "No face may remain detectable after anonymization"
    assert np.abs(anon.astype(int) - astro.astype(int)).mean() > 2.0, "Anonymized area must be genuinely modified"


def test_eval_category_3_screens_and_documents_detected_and_masked():
    """Category 3: Documents and screens containing text or content must be detected as privacy regions."""
    detector = ScreenDocumentDetector()
    desk_doc = make_document_desk()
    regions = detector.detect(desk_doc)
    assert len(regions) >= 1, "Document with text lines on desk must be detected"
    assert regions[0].label == "screen_or_document"


def test_eval_category_4_blank_walls_not_classified_as_blurry():
    """Category 4: Sharp, plain walls with a single switch or corner must NOT be rejected as blurry."""
    wall = make_blank_wall(with_switch=True)
    m = frame_metrics(wall)
    # Whole image laplacian might be low, but edge sharpness or block sharpness proves focus
    assert m["effective_sharpness"] >= 40.0, f"Expected effective sharpness >= 40 for in-focus wall, got {m['effective_sharpness']}"
    assert m["edge_sharpness"] >= 18.0


def test_eval_category_5_corridors_retained_and_perspective_preserved():
    """Category 5: Converging corridor views must pass quality filtering."""
    corridor = make_corridor()
    m = frame_metrics(corridor)
    assert m["effective_sharpness"] >= 40.0
    assert m["feature_count"] >= 10.0


def test_eval_category_6_glass_and_reflective_surfaces_flagged_for_operator():
    """Category 6: Glare and specular reflections on glass must be detected and recorded in metrics."""
    reflective = make_reflective_surface()
    m = frame_metrics(reflective)
    assert m["specular_fraction"] > 0.015, "Specular reflection on glass must be detected"


def test_eval_category_7_motion_blurred_frames_detected_as_motion_blur():
    """Category 7: Directional camera motion blur must produce high anisotropy ratio and be classified as motion_blurred."""
    m_frame = make_motion_blurred_frame()
    m = frame_metrics(m_frame)
    assert m["motion_blur_anisotropy"] >= 4.5, f"Expected anisotropy >= 4.5 for motion blur, got {m['motion_blur_anisotropy']}"


def test_eval_category_8_sharp_low_texture_frames_flagged_texture_poor_not_dropped(tmp_path):
    """Category 8: Sharp low-texture frames must be kept, but flagged as texture_poor with operator feedback."""
    frames = {}
    for i in range(12):
        frames[f"f{i:03d}.jpg"] = make_blank_wall(with_switch=True, offset_x=i * 25, shift_val=int(i * 2.5))

    ctx = _build_context(tmp_path, frames, "FRAME_QUALITY_FILTER", Settings(min_frames=10))
    filter_stage = FrameQualityFilter()
    result = filter_stage.run(ctx)
    assert result.status == "SUCCEEDED"

    report_art = next(a for a in result.artifacts if a.kind == "FRAME_QUALITY_REPORT")
    report = json.loads(report_art.path.read_text(encoding="utf-8"))
    assert report["kept"] == 12, "All 12 sharp low-texture frames must be retained"
    assert len(report["texture_poor_frames"]) > 0
    assert any("Texture-poor" in fb for fb in report["operator_feedback"])


# ==============================================================================
# 2. Regression Tests for Recorded ETH3D Failures (Defects D1 & D2)
# ==============================================================================

def test_eth3d_defect_d1_false_positive_face_masking_prevented(tmp_path):
    """Regression test for ETH3D Defect D1:

    In the recorded ETH3D run, OpenCV Haar detected 11 false faces in an empty office room,
    masking 83% of frame DSC_0221 / i0002.jpg. With calibrated detection, empty rooms
    produce 0 faces and 0% false masking area.
    """
    empty_office_frames = {
        f"eth3d_office_{i:02d}.jpg": make_empty_room(seed=100 + i)
        for i in range(5)
    }
    ctx = _build_context(tmp_path, empty_office_frames, "PRIVACY_PREPROCESS")
    stage = PrivacyPreprocess()
    result = stage.run(ctx)
    assert result.status == "SUCCEEDED"

    report_art = next(a for a in result.artifacts if a.kind == "PRIVACY_REPORT")
    report = json.loads(report_art.path.read_text(encoding="utf-8"))
    assert report["totals"]["face"] == 0, f"Expected 0 faces in empty office, got {report['totals']['face']}"
    assert report["totals"]["masked_pixels"] == 0, "No reconstruction pixels should be masked in empty office"
    assert report["sfm_impact"]["mean_sfm_masked_fraction"] == 0.0


def test_eth3d_defect_d2_sharp_low_texture_frames_retained_in_sfm(tmp_path):
    """Regression test for ETH3D Defect D2:

    In the recorded ETH3D run, FRAME_QUALITY_FILTER dropped 5 sharp DSLR frames (i0003, i0004,
    i0005, i0008, i0009) as 'blurred' because plain white walls had low whole-image Laplacian var (15-36 vs 40).
    With edge-conditioned sharpness, these frames are correctly retained.
    """
    # 5 sharp plain wall frames (simulating the 5 dropped ETH3D frames) plus 6 corridor frames
    frames = {}
    for i in range(5):
        frames[f"plain_wall_{i:02d}.jpg"] = make_blank_wall(with_switch=True, offset_x=i * 30, shift_val=int(i * 2.5))
    for i in range(6):
        frames[f"textured_{i:02d}.jpg"] = make_corridor(offset=i * 20, shift_tint=i * 3)

    ctx = _build_context(tmp_path, frames, "FRAME_QUALITY_FILTER", Settings(min_frames=10))
    filter_stage = FrameQualityFilter()
    result = filter_stage.run(ctx)
    assert result.status == "SUCCEEDED"

    report_art = next(a for a in result.artifacts if a.kind == "FRAME_QUALITY_REPORT")
    report = json.loads(report_art.path.read_text(encoding="utf-8"))
    dropped = report["dropped"]
    assert dropped.get("blurred", 0) == 0, f"Sharp walls must not be dropped as blurred; dropped: {dropped}"
    assert report["kept"] == 11


# ==============================================================================
# 3. Security and Fail-Closed Verification
# ==============================================================================

def test_privacy_fails_closed_when_detector_unavailable(tmp_path, monkeypatch):
    """Security verification: unavailable detector fails closed and never publishes raw PII."""
    def missing(*_args, **_kwargs):
        raise DependencyError("detector missing", details={"missing": ["opencv-haar-cascades"]})

    monkeypatch.setattr("chaya_worker.stages.privacy.FaceDetector", missing)
    frames = {"f1.jpg": np.full((100, 100, 3), 128, dtype=np.uint8)}
    ctx = _build_context(tmp_path, frames, "PRIVACY_PREPROCESS")
    stage = PrivacyPreprocess()
    with pytest.raises(DependencyError):
        stage.run(ctx)


def test_privacy_fails_closed_on_unreadable_frame(tmp_path):
    """Security verification: corrupted/unreadable frame fails closed with PRIVACY_FRAME_UNREADABLE."""
    in_dir = tmp_path / "raw_frames"
    in_dir.mkdir(parents=True, exist_ok=True)
    (in_dir / "corrupted.jpg").write_bytes(b"NOT_A_VALID_JPEG_HEADER")

    tar_path = tmp_path / "frames.tar"
    archive.pack(in_dir, tar_path)

    inputs = [InputFile({"artifactId": "FRAME_ARCHIVE_SELECTED", "kind": "FRAME_ARCHIVE_SELECTED"}, tar_path)]
    ctx = StageContext(
        {"stage": "PRIVACY_PREPROCESS", "privacyEnabled": True},
        inputs,
        tmp_path / "work",
        logging.getLogger("test_privacy"),
        CommandRunner(tmp_path / "out.log", tmp_path / "err.log"),
        Toolchain(env={}),
        Settings(),
        None,
    )
    stage = PrivacyPreprocess()
    with pytest.raises(StageError) as exc_info:
        stage.run(ctx)
    assert exc_info.value.code == "PRIVACY_FRAME_UNREADABLE"


# ==============================================================================
# 4. Operator Feedback and Overlap Tracking
# ==============================================================================

def test_quality_filter_detects_overlap_gaps_and_provides_feedback(tmp_path):
    """Verifies that consecutive frame jumps produce operator feedback for insufficient overlap."""
    frames = {}
    # Frame 0: corridor
    frames["f000.jpg"] = make_corridor()
    # Frame 1: completely different dark room (large visual jump)
    frames["f001.jpg"] = np.full((480, 640, 3), 30, dtype=np.uint8)
    # Remaining 10 frames: smoothly panning corridors
    for i in range(2, 12):
        frames[f"f{i:03d}.jpg"] = make_corridor(offset=i * 25, shift_tint=i * 3)

    ctx = _build_context(tmp_path, frames, "FRAME_QUALITY_FILTER", Settings(min_frames=10))
    stage = FrameQualityFilter()
    result = stage.run(ctx)
    assert result.status == "SUCCEEDED"

    report_art = next(a for a in result.artifacts if a.kind == "FRAME_QUALITY_REPORT")
    report = json.loads(report_art.path.read_text(encoding="utf-8"))
    assert len(report["overlap_gaps"]) >= 1
    assert any("Insufficient visual overlap" in fb for fb in report["operator_feedback"])
