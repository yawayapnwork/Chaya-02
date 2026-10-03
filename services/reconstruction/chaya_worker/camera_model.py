"""Camera intrinsics and lens distortion (review finding G-1).

One camera description is used from capture metadata to training: a COLMAP camera model name, the image size and the
model's parameters in COLMAP's order. Nothing downstream may drop the distortion coefficients. A stage that needs a
pinhole camera (gsplat, which has no distortion parameters, and the semantic stages' projection) resamples the frame
with `FrameRectifier` and uses the pinhole camera it returns, never the distorted camera's fx, fy, cx, cy alone.

Supported models, with the distortion applied to normalised coordinates (x, y) = ((u - cx) / fx, (v - cy) / fy) and
r² = x² + y², exactly as COLMAP defines them (src/colmap/sensor/models.h):

| Model | Parameters | Distortion |
|---|---|---|
| SIMPLE_PINHOLE | f, cx, cy | none |
| PINHOLE | fx, fy, cx, cy | none |
| SIMPLE_RADIAL | f, cx, cy, k | radial 1 + k r² |
| RADIAL | f, cx, cy, k1, k2 | radial 1 + k1 r² + k2 r⁴ |
| OPENCV | fx, fy, cx, cy, k1, k2, p1, p2 | radial 1 + k1 r² + k2 r⁴, tangential p1, p2 |
| FULL_OPENCV | fx, fy, cx, cy, k1, k2, p1, p2, k3, k4, k5, k6 | rational radial (1 + k1 r² + k2 r⁴ + k3 r⁶) / (1 + k4 r² + k5 r⁴ + k6 r⁶), tangential p1, p2 |

The fisheye and field-of-view models (and anything else) are refused with `UnsupportedCameraModel`; they are never
approximated by one of the models above.

Pixel convention: COLMAP's. (0, 0) is the top-left corner of the top-left pixel, whose centre is (0.5, 0.5). gsplat
samples pixel j at j + 0.5, the same convention. OpenCV puts that centre at (0, 0); the remap tables handed to
cv2.remap are shifted by -0.5 for that reason, and capture metadata in OpenCV's convention is converted on input.

Undistortion: the training camera is PINHOLE with the same image size and principal point and focal lengths
`focal_scale * (fx, fy)`. `focal_scale` is the smallest value for which every pixel of the undistorted image has its
source inside the photograph (no blank pixels), searched by bisection. A calibration whose distortion folds over itself
inside the image (non-positive Jacobian) is refused rather than resampled. Undistortion is a 2D resampling: the camera
centre and orientation do not change, so the world-to-camera pose estimated for the photograph applies unchanged to the
undistorted frame.
"""

from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Any

import numpy as np

PARAM_NAMES: dict[str, tuple[str, ...]] = {
    "SIMPLE_PINHOLE": ("f", "cx", "cy"),
    "PINHOLE": ("fx", "fy", "cx", "cy"),
    "SIMPLE_RADIAL": ("f", "cx", "cy", "k"),
    "RADIAL": ("f", "cx", "cy", "k1", "k2"),
    "OPENCV": ("fx", "fy", "cx", "cy", "k1", "k2", "p1", "p2"),
    "FULL_OPENCV": ("fx", "fy", "cx", "cy", "k1", "k2", "p1", "p2", "k3", "k4", "k5", "k6"),
}
DISTORTION_MODEL: dict[str, str] = {
    "SIMPLE_PINHOLE": "NONE",
    "PINHOLE": "NONE",
    "SIMPLE_RADIAL": "RADIAL_K1",
    "RADIAL": "RADIAL_K1_K2",
    "OPENCV": "RADIAL_TANGENTIAL",
    "FULL_OPENCV": "RATIONAL_RADIAL_TANGENTIAL",
}
UNSUPPORTED_MODELS: dict[str, str] = {
    name: "fisheye / field-of-view projection; undistorting it is not implemented"
    for name in ("SIMPLE_RADIAL_FISHEYE", "RADIAL_FISHEYE", "OPENCV_FISHEYE", "FOV", "THIN_PRISM_FISHEYE",
                 "RAD_TAN_THIN_PRISM_FISHEYE")
}
PIXEL_CONVENTION = "COLMAP: (0, 0) is the top-left corner of the top-left pixel; that pixel's centre is (0.5, 0.5)"

# The capture-metadata block (RAW_METADATA JSON, top-level key). See docs/capture-ingestion.md.
CAPTURE_KEY = "cameraCalibration"
_ORIGINS = {"CORNER": 0.0, "CENTER": 0.5}  # added to cx, cy to reach COLMAP's convention

_MIN_FOCAL_SCALE, _MAX_FOCAL_SCALE = 0.25, 16.0


class CameraModelError(ValueError):
    """An invalid or unusable calibration. `code` is the stage error code to report."""

    code = "CAMERA_CALIBRATION_INVALID"


class UnsupportedCameraModel(CameraModelError):
    code = "CAMERA_MODEL_UNSUPPORTED"


def _number(value: Any, what: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        raise CameraModelError(f"{what} must be a finite number, got {value!r}")
    return float(value)


def _dimension(value: Any, what: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        raise CameraModelError(f"{what} must be a positive integer, got {value!r}")
    return value


@dataclass(frozen=True)
class Camera:
    """A COLMAP camera: model name, image size in pixels and the model's parameters in COLMAP's order."""

    model: str
    width: int
    height: int
    params: tuple[float, ...]

    def __post_init__(self) -> None:
        if self.model in UNSUPPORTED_MODELS:
            raise UnsupportedCameraModel(f"camera model {self.model!r} is not supported: {UNSUPPORTED_MODELS[self.model]}")
        if self.model not in PARAM_NAMES:
            raise UnsupportedCameraModel(f"camera model {self.model!r} is not supported (supported: {', '.join(PARAM_NAMES)})")
        _dimension(self.width, "width")
        _dimension(self.height, "height")
        names = PARAM_NAMES[self.model]
        if len(self.params) != len(names):
            raise CameraModelError(f"{self.model} takes {len(names)} parameters ({', '.join(names)}), got {len(self.params)}")
        object.__setattr__(self, "params", tuple(_number(p, n) for p, n in zip(self.params, names, strict=True)))
        if self.fx <= 0 or self.fy <= 0:
            raise CameraModelError(f"focal lengths must be positive (fx={self.fx}, fy={self.fy})")
        if not (0 < self.cx < self.width and 0 < self.cy < self.height):
            raise CameraModelError(f"principal point ({self.cx}, {self.cy}) lies outside the {self.width}x{self.height} image")

    # ---- parameters -------------------------------------------------------------------------------------------------

    @property
    def _named(self) -> dict[str, float]:
        return dict(zip(PARAM_NAMES[self.model], self.params, strict=True))

    @property
    def fx(self) -> float:
        n = self._named
        return n["f"] if "f" in n else n["fx"]

    @property
    def fy(self) -> float:
        n = self._named
        return n["f"] if "f" in n else n["fy"]

    @property
    def cx(self) -> float:
        return self._named["cx"]

    @property
    def cy(self) -> float:
        return self._named["cy"]

    @property
    def distortion_model(self) -> str:
        return DISTORTION_MODEL[self.model]

    @property
    def distortion(self) -> dict[str, float]:
        """The distortion coefficients by name (empty for the pinhole models)."""
        return {k: v for k, v in self._named.items() if k not in ("f", "fx", "fy", "cx", "cy")}

    @property
    def has_distortion(self) -> bool:
        return any(v != 0.0 for v in self.distortion.values())

    @property
    def K(self) -> np.ndarray:
        """The linear part only. On its own it describes the camera only when `has_distortion` is False."""
        return np.array([[self.fx, 0.0, self.cx], [0.0, self.fy, self.cy], [0.0, 0.0, 1.0]])

    # ---- geometry ---------------------------------------------------------------------------------------------------

    def distort(self, x: np.ndarray, y: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
        """Normalised undistorted coordinates -> normalised distorted coordinates, per COLMAP's model."""
        d = self.distortion
        x, y = np.asarray(x, dtype=np.float64), np.asarray(y, dtype=np.float64)
        if self.model in ("SIMPLE_PINHOLE", "PINHOLE"):
            return x, y
        r2 = x * x + y * y
        if self.model == "SIMPLE_RADIAL":
            radial = 1.0 + d["k"] * r2
            return x * radial, y * radial
        if self.model == "RADIAL":
            radial = 1.0 + d["k1"] * r2 + d["k2"] * r2 * r2
            return x * radial, y * radial
        if self.model == "OPENCV":
            radial = 1.0 + d["k1"] * r2 + d["k2"] * r2 * r2
        else:  # FULL_OPENCV
            r4, r6 = r2 * r2, r2 * r2 * r2
            with np.errstate(divide="ignore", invalid="ignore"):
                radial = (1.0 + d["k1"] * r2 + d["k2"] * r4 + d["k3"] * r6) / (1.0 + d["k4"] * r2 + d["k5"] * r4 + d["k6"] * r6)
        p1, p2 = d["p1"], d["p2"]
        xy = x * y
        return (x * radial + 2.0 * p1 * xy + p2 * (r2 + 2.0 * x * x),
                y * radial + p1 * (r2 + 2.0 * y * y) + 2.0 * p2 * xy)

    def project(self, points_camera: np.ndarray) -> np.ndarray:
        """(N, 3) points in this camera's frame -> (N, 2) pixel coordinates (COLMAP convention), distortion applied.
        Points at or behind the camera give non-finite values."""
        p = np.asarray(points_camera, dtype=np.float64)
        with np.errstate(divide="ignore", invalid="ignore"):
            z = np.where(p[:, 2] > 0, p[:, 2], np.nan)
            xd, yd = self.distort(p[:, 0] / z, p[:, 1] / z)
        return np.stack([self.fx * xd + self.cx, self.fy * yd + self.cy], axis=1)

    def scaled(self, sx: float, sy: float, width: int, height: int) -> Camera:
        """The same lens for an image resampled by (sx, sy) to width x height. Focal lengths and principal point scale
        with the image (COLMAP convention: the image corner is the origin, so cx scales by sx exactly); the distortion
        coefficients act on normalised coordinates, which a per-axis rescale does not change. A single-focal model rescaled
        non-uniformly becomes its exact two-focal equivalent (SIMPLE_PINHOLE -> PINHOLE, SIMPLE_RADIAL/RADIAL -> OPENCV)."""
        named = dict(self._named)
        for key in ("fx", "cx"):
            if key in named:
                named[key] *= sx
        for key in ("fy", "cy"):
            if key in named:
                named[key] *= sy
        if "f" in named:
            if not math.isclose(sx, sy, rel_tol=1e-12):
                # A single focal length cannot hold a non-uniform rescale (FFmpeg rounds widths to even numbers). Each
                # single-focal model is exactly a two-focal model with the extra coefficients at zero: switch to it.
                equivalent = {"SIMPLE_PINHOLE": "PINHOLE", "SIMPLE_RADIAL": "OPENCV", "RADIAL": "OPENCV"}[self.model]
                d = self.distortion
                coeffs = {"PINHOLE": (), "OPENCV": (d.get("k", d.get("k1", 0.0)), d.get("k2", 0.0), 0.0, 0.0)}[equivalent]
                return Camera(equivalent, width, height, (named["f"] * sx, named["f"] * sy, named["cx"], named["cy"], *coeffs))
            named["f"] *= sx
        return Camera(self.model, width, height, tuple(named[n] for n in PARAM_NAMES[self.model]))

    # ---- records ----------------------------------------------------------------------------------------------------

    def record(self) -> dict[str, Any]:
        return {"model": self.model, "width": self.width, "height": self.height, "params": list(self.params),
                "fx": self.fx, "fy": self.fy, "cx": self.cx, "cy": self.cy,
                "distortion_model": self.distortion_model, "distortion_coefficients": self.distortion,
                "pixel_convention": PIXEL_CONVENTION}

    @classmethod
    def from_record(cls, doc: dict[str, Any]) -> Camera:
        return cls(doc["model"], doc["width"], doc["height"], tuple(doc["params"]))

    def colmap_params(self) -> str:
        """`--ImageReader.camera_params` value: the parameters in COLMAP's order, full precision."""
        return ",".join(format(p, ".17g") for p in self.params)


def parse_capture_calibration(doc: dict[str, Any]) -> tuple[Camera, dict[str, Any]] | None:
    """The `cameraCalibration` block of a capture metadata file, or None when the file has none.

        {"cameraCalibration": {"model": "OPENCV", "width": 1920, "height": 1080,
                               "params": [fx, fy, cx, cy, k1, k2, p1, p2],
                               "pixelCoordinateOrigin": "CORNER" | "CENTER",
                               "source": "free text: how it was measured (optional)"}}

    `pixelCoordinateOrigin` is required: COLMAP (CORNER) and OpenCV (CENTER) principal points differ by half a pixel
    and there is no safe default. The returned camera is in COLMAP's convention."""
    block = doc.get(CAPTURE_KEY)
    if block is None:
        return None
    if not isinstance(block, dict):
        raise CameraModelError(f"{CAPTURE_KEY} must be a JSON object")
    model = block.get("model")
    if not isinstance(model, str):
        raise CameraModelError(f"{CAPTURE_KEY}.model must be a COLMAP camera model name")
    params = block.get("params")
    if not isinstance(params, list):
        raise CameraModelError(f"{CAPTURE_KEY}.params must be a list of numbers in COLMAP's order for {model}")
    origin = block.get("pixelCoordinateOrigin")
    if origin not in _ORIGINS:
        raise CameraModelError(f"{CAPTURE_KEY}.pixelCoordinateOrigin must be CORNER (COLMAP) or CENTER (OpenCV), got {origin!r}")
    width, height = _dimension(block.get("width"), f"{CAPTURE_KEY}.width"), _dimension(block.get("height"), f"{CAPTURE_KEY}.height")
    declared = Camera(model, width, height, tuple(params))  # raises for an unsupported model or bad parameters
    camera = declared
    if _ORIGINS[origin]:
        named = dict(zip(PARAM_NAMES[model], declared.params, strict=True))
        named["cx"] += _ORIGINS[origin]
        named["cy"] += _ORIGINS[origin]
        camera = Camera(model, width, height, tuple(named[n] for n in PARAM_NAMES[model]))
    source = block.get("source")
    return camera, {"declared": block, "declared_source": source if isinstance(source, str) else None,
                    "pixel_coordinate_origin": origin}


# ---- undistortion ---------------------------------------------------------------------------------------------------


def _target_grid(camera: Camera) -> tuple[np.ndarray, np.ndarray]:
    """Pixel centres (COLMAP convention) of the target image: every border pixel, plus a coarse interior grid."""
    w, h = camera.width, camera.height
    xs, ys = np.arange(w) + 0.5, np.arange(h) + 0.5
    border_u = np.concatenate([xs, xs, np.full(h, 0.5), np.full(h, w - 0.5)])
    border_v = np.concatenate([np.full(w, 0.5), np.full(w, h - 0.5), ys, ys])
    gu, gv = np.meshgrid(np.linspace(0.5, w - 0.5, 49), np.linspace(0.5, h - 0.5, 49))
    return np.concatenate([border_u, gu.ravel()]), np.concatenate([border_v, gv.ravel()])


def _source_of(camera: Camera, scale: float, u: np.ndarray, v: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """Target pixel (u, v) of the pinhole camera with focal lengths scale * (fx, fy) -> source pixel in the photo."""
    xd, yd = camera.distort((u - camera.cx) / (scale * camera.fx), (v - camera.cy) / (scale * camera.fy))
    return camera.fx * xd + camera.cx, camera.fy * yd + camera.cy


def _jacobian_positive(camera: Camera, x: np.ndarray, y: np.ndarray) -> bool:
    eps = 1e-6
    x1, y1 = camera.distort(x + eps, y)
    x0, y0 = camera.distort(x - eps, y)
    x3, y3 = camera.distort(x, y + eps)
    x2, y2 = camera.distort(x, y - eps)
    det = ((x1 - x0) * (y3 - y2) - (x3 - x2) * (y1 - y0)) / (4 * eps * eps)
    return bool(np.all(np.isfinite(det)) and np.all(det > 0))


def _inside(camera: Camera, su: np.ndarray, sv: np.ndarray) -> bool:
    # Bilinear sampling needs the source position between the outermost pixel centres.
    return bool(np.all(np.isfinite(su)) and np.all(np.isfinite(sv))
                and np.all((su >= 0.5) & (su <= camera.width - 0.5) & (sv >= 0.5) & (sv <= camera.height - 0.5)))


def _feasible(camera: Camera, scale: float) -> bool:
    u, v = _target_grid(camera)
    if not _jacobian_positive(camera, (u - camera.cx) / (scale * camera.fx), (v - camera.cy) / (scale * camera.fy)):
        return False
    return _inside(camera, *_source_of(camera, scale, u, v))


@dataclass(frozen=True)
class Undistortion:
    """How one distorted camera's frames are resampled to a pinhole camera."""

    source: Camera
    target: Camera  # PINHOLE, same size and principal point
    focal_scale: float
    map_x: np.ndarray  # (H, W) float32 source x for every target pixel, OpenCV convention (pixel centre at integer)
    map_y: np.ndarray

    def apply(self, image: np.ndarray) -> np.ndarray:
        import cv2  # noqa: PLC0415

        if image.shape[:2] != (self.source.height, self.source.width):
            raise CameraModelError(f"image is {image.shape[1]}x{image.shape[0]}, the camera is "
                                   f"{self.source.width}x{self.source.height}")
        return cv2.remap(image, self.map_x, self.map_y, interpolation=cv2.INTER_LINEAR, borderMode=cv2.BORDER_REPLICATE)

    def apply_mask(self, valid: np.ndarray) -> np.ndarray:
        """A bool (H, W) source validity mask resampled like apply() resamples the frame: a target pixel is valid only
        if every source pixel its bilinear sample reads is valid (no undistorted pixel mixes in a masked one), and
        never when it samples outside the photograph."""
        import cv2  # noqa: PLC0415

        if valid.shape != (self.source.height, self.source.width):
            raise CameraModelError(f"mask is {valid.shape[1]}x{valid.shape[0]}, the camera is "
                                   f"{self.source.width}x{self.source.height}")
        sampled = cv2.remap(valid.astype(np.float32), self.map_x, self.map_y, interpolation=cv2.INTER_LINEAR,
                            borderMode=cv2.BORDER_CONSTANT, borderValue=0.0)
        return sampled >= 1.0 - 1e-6

    def record(self) -> dict[str, Any]:
        return {"method": "bilinear resampling (cv2.remap) through the camera model's forward distortion",
                "focal_scale": self.focal_scale,
                "blank_pixels": 0,
                "training_to_source": "x_source = K_source · distort(K_training⁻¹ · x_training)"}


def focal_scale_for(camera: Camera) -> float:
    """The smallest focal-length scale of a same-size pinhole camera whose every pixel has its source inside the
    photograph. Raises CameraModelError when the distortion folds over itself inside the image (checked on every border
    pixel and a 49x49 interior grid) or no such pinhole camera exists."""
    if not camera.has_distortion:
        raise CameraModelError(f"{camera.model} camera has no distortion to remove")
    hi = 1.0
    while not _feasible(camera, hi):
        hi *= 2.0
        if hi > _MAX_FOCAL_SCALE:
            raise CameraModelError(f"the {camera.model} distortion {camera.distortion} is not invertible over the "
                                   f"{camera.width}x{camera.height} image; refusing to approximate it")
    lo = hi / 2.0
    while _feasible(camera, lo):  # strong barrel distortion: a wider pinhole still fits inside the photograph
        hi, lo = lo, lo / 2.0
        if hi <= _MIN_FOCAL_SCALE:
            lo = hi
            break
    for _ in range(60):
        if hi - lo <= 1e-9 * hi:
            break
        mid = 0.5 * (lo + hi)
        if _feasible(camera, mid):
            hi = mid
        else:
            lo = mid
    return hi


def undistortion_for(camera: Camera) -> Undistortion:
    """Plans the undistortion of a camera with distortion (see focal_scale_for), checking every pixel."""
    scale = focal_scale_for(camera)
    target = Camera("PINHOLE", camera.width, camera.height, (scale * camera.fx, scale * camera.fy, camera.cx, camera.cy))
    gu, gv = np.meshgrid(np.arange(camera.width) + 0.5, np.arange(camera.height) + 0.5)
    su, sv = _source_of(camera, scale, gu, gv)
    if not _inside(camera, su, sv):  # the bisection checked the border and a grid; this checks every pixel
        raise CameraModelError(f"the {camera.model} distortion {camera.distortion} maps part of the undistorted image "
                               "outside the photograph; refusing to approximate it")
    return Undistortion(camera, target, scale, (su - 0.5).astype(np.float32), (sv - 0.5).astype(np.float32))


class FrameRectifier:
    """Gives every frame a pinhole camera: frames of a camera without distortion pass through untouched, frames of a
    camera with distortion are undistorted (one plan per distinct camera). `records()` says which happened."""

    def __init__(self) -> None:
        self._plans: dict[Camera, Undistortion | None] = {}
        self._frames: dict[Camera, int] = {}

    def plan(self, camera: Camera) -> Undistortion | None:
        if camera not in self._plans:
            self._plans[camera] = undistortion_for(camera) if camera.has_distortion else None
        return self._plans[camera]

    def pinhole(self, camera: Camera) -> Camera:
        plan = self.plan(camera)
        if plan is not None:
            return plan.target
        return Camera("PINHOLE", camera.width, camera.height, (camera.fx, camera.fy, camera.cx, camera.cy))

    def rectify(self, camera: Camera, image: np.ndarray) -> tuple[np.ndarray, Camera]:
        """(the frame as the pinhole camera sees it, that pinhole camera)."""
        plan = self.plan(camera)
        if image.shape[:2] != (camera.height, camera.width):
            raise CameraModelError(f"frame is {image.shape[1]}x{image.shape[0]} but its camera is {camera.width}x{camera.height}")
        self._frames[camera] = self._frames.get(camera, 0) + 1
        return (image if plan is None else plan.apply(image)), self.pinhole(camera)

    def rectify_mask(self, camera: Camera, valid: np.ndarray) -> np.ndarray:
        """A frame's bool validity mask, as the pinhole camera of rectify() sees it (Undistortion.apply_mask)."""
        plan = self.plan(camera)
        if valid.shape != (camera.height, camera.width):
            raise CameraModelError(f"mask is {valid.shape[1]}x{valid.shape[0]} but its camera is {camera.width}x{camera.height}")
        return valid if plan is None else plan.apply_mask(valid)

    def records(self) -> list[dict[str, Any]]:
        out = []
        for camera, plan in self._plans.items():
            out.append({
                "source_camera": camera.record(),
                "undistorted": plan is not None,
                "reason": ("distortion coefficients are non-zero" if plan is not None
                           else "the camera model has no non-zero distortion coefficient; frames used as recorded"),
                "training_camera": self.pinhole(camera).record(),
                "undistortion": plan.record() if plan is not None else None,
                "frames": self._frames.get(camera, 0),
                "pose": "unchanged: undistortion resamples the image plane only, so the world-to-camera pose estimated "
                        "for the photograph is the pose of the undistorted frame",
            })
        return out
