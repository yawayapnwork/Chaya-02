"""Privacy detectors and anonymisation: faces, and screens / documents.

Both detectors are classical computer-vision, not learned models:
  * faces: OpenCV Haar cascades (frontal, alternate frontal, profile in both orientations), tuned for recall;
  * screens / documents: a quadrilateral heuristic (bright or contrasting rectangles). It over-blurs rather
    than under-blurs, misses strongly tilted or dim screens, and is the reason the stage records which
    detector produced each result. A learned detector can replace it behind the RegionDetector interface.

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
                merged[i] = Region(x0, y0, x1 - x0, y1 - y0, m.label)
                break
        else:
            merged.append(r)
    return merged


class FaceDetector:
    name = "opencv-haar-faces"
    FRONTAL = ("haarcascade_frontalface_default.xml", "haarcascade_frontalface_alt2.xml")
    PROFILE = ("haarcascade_profileface.xml",)

    def __init__(self, cascade_dir: str | Path | None = None) -> None:
        directory = Path(cascade_dir) if cascade_dir else Path(getattr(getattr(cv2, "data", None), "haarcascades", ""))
        self._frontal = [self._load(directory, n) for n in self.FRONTAL]
        self._profile = [self._load(directory, n) for n in self.PROFILE]

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
        gray = cv2.equalizeHist(cv2.cvtColor(small, cv2.COLOR_BGR2GRAY))
        min_side = max(20, gray.shape[1] // 40)
        found: list[Region] = []

        def collect(clf: cv2.CascadeClassifier, image: np.ndarray, flipped: bool) -> None:
            for x, y, fw, fh in clf.detectMultiScale(image, scaleFactor=1.1, minNeighbors=4, minSize=(min_side, min_side)):
                if flipped:
                    x = image.shape[1] - x - fw
                found.append(Region(int(x / scale), int(y / scale), int(fw / scale), int(fh / scale), "face"))

        for clf in self._frontal:
            collect(clf, gray, False)
        flipped = cv2.flip(gray, 1)
        for clf in self._profile:
            collect(clf, gray, False)
            collect(clf, flipped, True)
        return merge(found)


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
            if not 0.02 * frame_area <= area <= 0.7 * frame_area:
                continue
            approx = cv2.approxPolyDP(c, 0.02 * cv2.arcLength(c, True), True)
            if len(approx) != 4 or not cv2.isContourConvex(approx):
                continue
            x, y, bw, bh = cv2.boundingRect(approx)
            if not 0.4 <= bw / bh <= 3.2 or area / (bw * bh) < 0.7:
                continue
            inside = gray[y:y + bh, x:x + bw].mean()
            ring = gray[max(0, y - bh // 6):y + bh + bh // 6, max(0, x - bw // 6):x + bw + bw // 6].mean()
            if abs(float(inside) - float(ring)) < 12:  # a screen or sheet of paper differs in brightness from its surroundings
                continue
            out.append(Region(int(x / scale), int(y / scale), int(bw / scale), int(bh / scale), "screen_or_document"))
        return merge(out)


def _odd(n: int) -> int:
    return n if n % 2 == 1 else n + 1


def anonymize(img_bgr: np.ndarray, regions: list[Region], *, solid: bool = False) -> np.ndarray:
    """Return a copy with each region (padded by 25%) pixelated then blurred, or filled solid when solid=True."""
    out = img_bgr.copy()
    H, W = out.shape[:2]
    for r in regions:
        pad = int(0.25 * max(r.w, r.h))
        x0, y0, x1, y1 = max(0, r.x - pad), max(0, r.y - pad), min(W, r.x + r.w + pad), min(H, r.y + r.h + pad)
        roi = out[y0:y1, x0:x1]
        if roi.size == 0:
            continue
        h, w = roi.shape[:2]
        if solid:
            out[y0:y1, x0:x1] = roi.reshape(-1, 3).mean(axis=0).astype(np.uint8)
            continue
        blocks_x = min(8, w)  # about 8 blocks across: far too coarse to recognise anyone
        small = cv2.resize(roi, (blocks_x, max(1, round(blocks_x * h / w))), interpolation=cv2.INTER_AREA)
        pixelated = cv2.resize(small, (w, h), interpolation=cv2.INTER_NEAREST)
        k = _odd(max(31, min(w, h) // 3))
        out[y0:y1, x0:x1] = cv2.GaussianBlur(pixelated, (k, k), 0)
    return out
