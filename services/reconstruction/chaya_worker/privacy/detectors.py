"""Privacy detectors and anonymisation: faces, and screens / documents.

Both detectors are classical computer-vision, calibrated with multi-cue verification:
  * faces: OpenCV Haar cascades (frontal default, frontal alt2, profile in both orientations) with
    confidence scoring (detectMultiScale3), aspect ratio and area bounds, color/skin-chrominance
    verification, and secondary eye cascade confirmation. This prevents false positives on empty walls,
    furniture, and architectural textures while retaining high recall on actual human faces.
  * screens / documents: quadrilateral contour detection verified by interior content entropy and
    edge density. Blank architectural panels, doors, and empty whiteboards without writing are excluded,
    while documents with text and active display screens are masked.

The privacy stage FAILS CLOSED: if a detector cannot run, the stage fails; unblurred frames are never
passed on.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Protocol

import cv2
import numpy as np

from ..errors import DependencyError


@dataclass(frozen=True)
class Region:
    x: int
    y: int
    w: int
    h: int
    label: str
    confidence: float = 1.0
    uncertain: bool = False

    def area(self) -> int:
        return self.w * self.h


class RegionDetector(Protocol):
    name: str

    def detect(self, img_bgr: np.ndarray) -> list[Region]: ...


def _iou(a: Region, b: Region) -> float:
    x0, y0 = max(a.x, b.x), max(a.y, b.y)
    x1, y1 = min(a.x + a.w, b.x + b.w), min(a.y + a.h, b.y + b.h)
    inter = max(0, x1 - x0) * max(0, y1 - y0)
    union = a.area() + b.area() - inter
    return inter / union if union else 0.0


def merge(regions: list[Region], iou: float = 0.3) -> list[Region]:
    """Merge overlapping detections into their union box."""
    merged: list[Region] = []
    for r in sorted(regions, key=lambda r: -r.area()):
        for i, m in enumerate(merged):
            if _iou(r, m) > iou:
                x0, y0 = min(r.x, m.x), min(r.y, m.y)
                x1, y1 = max(r.x + r.w, m.x + m.w), max(r.y + r.h, m.y + m.h)
                merged[i] = Region(
                    x0, y0, x1 - x0, y1 - y0, m.label,
                    confidence=max(r.confidence, m.confidence),
                    uncertain=r.uncertain and m.uncertain,
                )
                break
        else:
            merged.append(r)
    return merged


class FaceDetector:
    name = "opencv-haar-faces"
    FRONTAL = ("haarcascade_frontalface_alt2.xml", "haarcascade_frontalface_default.xml")
    PROFILE = ("haarcascade_profileface.xml",)
    EYE = "haarcascade_eye.xml"

    def __init__(self, cascade_dir: str | Path | None = None) -> None:
        directory = Path(cascade_dir) if cascade_dir else Path(getattr(getattr(cv2, "data", None), "haarcascades", ""))
        self._frontal = [self._load(directory, n) for n in self.FRONTAL]
        self._profile = [self._load(directory, n) for n in self.PROFILE]
        eye_path = directory / self.EYE
        self._eye = cv2.CascadeClassifier(str(eye_path)) if eye_path.is_file() else None

    @staticmethod
    def _load(directory: Path, name: str) -> cv2.CascadeClassifier:
        path = directory / name
        clf = cv2.CascadeClassifier(str(path)) if path.is_file() else None
        if clf is None or clf.empty():
            raise DependencyError(f"face detection model {name} is not available in {directory}",
                                  details={"missing": ["opencv-haar-cascades"], "model": name, "directory": str(directory)})
        return clf

    def detect(self, img_bgr: np.ndarray) -> list[Region]:
        h, w = img_bgr.shape[:2]
        scale = min(1.0, 1280 / w)
        small = cv2.resize(img_bgr, (round(w * scale), round(h * scale)), interpolation=cv2.INTER_AREA) if scale < 1 else img_bgr
        gray = cv2.cvtColor(small, cv2.COLOR_BGR2GRAY)
        min_side = max(24, gray.shape[1] // 40)
        # Upper size bound: in indoor architectural capture, a face cannot realistically exceed 40% of image height
        max_side = int(min(gray.shape[0], gray.shape[1]) * 0.40)
        if max_side < min_side:
            max_side = min_side + 10

        found: list[Region] = []

        def collect(clf: cv2.CascadeClassifier, image: np.ndarray, flipped: bool, min_neighbors: int, min_conf: float) -> None:
            try:
                rects, _, weights = clf.detectMultiScale3(
                    image, scaleFactor=1.08, minNeighbors=min_neighbors,
                    minSize=(min_side, min_side), maxSize=(max_side, max_side),
                    outputRejectLevels=True,
                )
                for (x, y, fw, fh), weight in zip(rects, weights, strict=False):
                    conf = float(weight)
                    if conf >= min_conf:
                        if flipped:
                            x = image.shape[1] - x - fw
                        found.append(Region(int(x / scale), int(y / scale), int(fw / scale), int(fh / scale), "face", confidence=conf))
            except Exception:
                for x, y, fw, fh in clf.detectMultiScale(
                    image, scaleFactor=1.1, minNeighbors=min_neighbors,
                    minSize=(min_side, min_side), maxSize=(max_side, max_side),
                ):
                    if flipped:
                        x = image.shape[1] - x - fw
                    found.append(Region(int(x / scale), int(y / scale), int(fw / scale), int(fh / scale), "face"))

        for clf in self._frontal:
            collect(clf, gray, False, min_neighbors=4, min_conf=1.0)

        flipped = cv2.flip(gray, 1)
        for clf in self._profile:
            collect(clf, gray, False, min_neighbors=5, min_conf=2.0)
            collect(clf, flipped, True, min_neighbors=5, min_conf=2.0)

        merged_candidates = merge(found, iou=0.25)
        if not merged_candidates:
            return []

        # Color variance across frame
        is_color_image = bool(
            img_bgr.ndim == 3 and img_bgr.shape[2] == 3 and
            float(np.std(img_bgr[:, :, 0].astype(float) - img_bgr[:, :, 1].astype(float))) > 3.0
        )

        verified: list[Region] = []
        for reg in merged_candidates:
            # Aspect ratio check: human face aspect ratio is between 0.65 and 1.45
            ar = reg.w / max(1, reg.h)
            if not (0.65 <= ar <= 1.45):
                continue

            # Physical area check: face cannot occupy > 35% of the frame in venue survey
            if (reg.area() / (h * w)) > 0.35 or reg.h > (0.50 * h):
                continue

            roi = img_bgr[reg.y:reg.y + reg.h, reg.x:reg.x + reg.w]
            if roi.size == 0:
                continue

            # Eye verification
            eyes_count = 0
            if self._eye is not None:
                roi_gray = cv2.cvtColor(roi, cv2.COLOR_BGR2GRAY)
                eyes = self._eye.detectMultiScale(
                    roi_gray[:max(1, reg.h // 2), :],
                    scaleFactor=1.1, minNeighbors=2,
                    minSize=(max(8, reg.w // 8), max(8, reg.h // 8)),
                )
                eyes_count = len(eyes)

            # Skin chrominance verification
            skin_fraction = 0.0
            if is_color_image:
                ycrcb = cv2.cvtColor(roi, cv2.COLOR_BGR2YCrCb)
                cr = ycrcb[:, :, 1]
                cb = ycrcb[:, :, 2]
                skin_mask = (cr >= 133) & (cr <= 173) & (cb >= 77) & (cb <= 127)
                skin_fraction = float(skin_mask.mean())

            # Confirmation logic:
            # In color images, a real human face exhibits skin tones or eye patterns.
            # In monochrome images, eye features or high cascade confidence are verified.
            if is_color_image:
                if skin_fraction < 0.05 and eyes_count == 0:
                    continue
                is_confirmed = (reg.confidence >= 15.0) or (skin_fraction >= 0.08) or (eyes_count >= 1)
            else:
                is_confirmed = (reg.confidence >= 10.0) or (eyes_count >= 1)

            if not is_confirmed:
                continue

            uncertain = bool(eyes_count == 0 and skin_fraction < 0.18 and reg.confidence < 12.0)
            verified.append(Region(reg.x, reg.y, reg.w, reg.h, reg.label, confidence=reg.confidence, uncertain=uncertain))

        return verified


class ScreenDocumentDetector:
    name = "heuristic-quad"

    def detect(self, img_bgr: np.ndarray) -> list[Region]:
        h, w = img_bgr.shape[:2]
        scale = min(1.0, 800 / w)
        small = cv2.resize(img_bgr, (round(w * scale), round(h * scale)), interpolation=cv2.INTER_AREA) if scale < 1 else img_bgr
        gray = cv2.GaussianBlur(cv2.cvtColor(small, cv2.COLOR_BGR2GRAY), (5, 5), 0)
        edges = cv2.dilate(cv2.Canny(gray, 50, 150), np.ones((3, 3), np.uint8))
        contours, _ = cv2.findContours(edges, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
        frame_area = gray.shape[0] * gray.shape[1]
        out: list[Region] = []
        for c in contours:
            area = cv2.contourArea(c)
            # A document/screen should not cover more than 70% of the frame (prevent masking entire rooms/walls)
            if not 0.02 * frame_area <= area <= 0.7 * frame_area:
                continue
            approx = cv2.approxPolyDP(c, 0.02 * cv2.arcLength(c, True), True)
            if len(approx) != 4 or not cv2.isContourConvex(approx):
                continue
            x, y, bw, bh = cv2.boundingRect(approx)
            if not 0.4 <= bw / bh <= 3.2 or area / (bw * bh) < 0.7:
                continue
            inside = gray[y:y + bh, x:x + bw]
            ring = gray[max(0, y - bh // 6):y + bh + bh // 6, max(0, x - bw // 6):x + bw + bw // 6]
            if abs(float(inside.mean()) - float(ring.mean())) < 12:  # differs in brightness from surroundings
                continue

            # Interior texture check:
            # Blank architectural surfaces (doors, blank wall panels, empty whiteboards)
            # have very low interior edge density and Laplacian variance.
            # Real documents with text or active monitors have rich internal edge and gradient content.
            pad_x = max(2, bw // 10)
            pad_y = max(2, bh // 10)
            interior_core = inside[pad_y:bh - pad_y, pad_x:bw - pad_x]
            if interior_core.size > 0:
                interior_lap = float(cv2.Laplacian(interior_core, cv2.CV_64F).var())
                interior_edges = float((cv2.Canny(interior_core, 50, 150) > 0).mean())
                # If interior is completely blank (e.g. painted door or empty panel), skip
                if interior_lap < 12.0 and interior_edges < 0.012:
                    continue
                uncertain = bool((area / frame_area > 0.25) or (interior_lap < 35.0))
            else:
                uncertain = True

            out.append(Region(int(x / scale), int(y / scale), int(bw / scale), int(bh / scale), "screen_or_document", uncertain=uncertain))
        return merge(out)


def _odd(n: int) -> int:
    return n if n % 2 == 1 else n + 1


def covered_box(region: Region, height: int, width: int) -> tuple[int, int, int, int]:
    """The pixels anonymize() rewrites for a region: the region padded by 25% of its longer side, clipped to the image.
    (x0, y0, x1, y1), end-exclusive. Nothing outside this box is touched. chaya_worker.privacy.masks builds the
    privacy mask from exactly these boxes."""
    pad = int(0.25 * max(region.w, region.h))
    return (max(0, region.x - pad), max(0, region.y - pad), min(width, region.x + region.w + pad),
            min(height, region.y + region.h + pad))


def anonymize(img_bgr: np.ndarray, regions: list[Region], *, solid: bool = False) -> np.ndarray:
    """Return a copy with each region's covered_box pixelated then blurred, or filled solid when solid=True."""
    out = img_bgr.copy()
    H, W = out.shape[:2]
    for r in regions:
        x0, y0, x1, y1 = covered_box(r, H, W)
        roi = out[y0:y1, x0:x1]
        if roi.size == 0:
            continue
        h, w = roi.shape[:2]
        if solid:
            out[y0:y1, x0:x1] = roi.reshape(-1, 3).mean(axis=0).astype(np.uint8)
            continue
        blocks_x = max(2, min(5, w))  # coarse block size obscuring facial features
        small = cv2.resize(roi, (blocks_x, max(1, round(blocks_x * h / w))), interpolation=cv2.INTER_AREA)
        pixelated = cv2.resize(small, (w, h), interpolation=cv2.INTER_NEAREST)
        k = _odd(max(31, min(w, h) // 2))
        out[y0:y1, x0:x1] = cv2.GaussianBlur(pixelated, (k, k), 0)
    return out
