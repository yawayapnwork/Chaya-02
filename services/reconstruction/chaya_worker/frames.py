"""The Chaya canonical venue coordinate system, and the similarity transform into it from a reconstruction.

Canonical frame (docs/coordinate-frames.md is the normative description; this module is its executable form):

    units        metres
    up axis      +Z, opposite to gravity
    horizontal   X and Y span the horizontal plane
    handedness   right-handed (X x Y = Z)
    origin       set by the frame's horizontal datum:
                   FLOOR_LOCAL            z = 0 on the floor plane, x = y = 0 at the calibration's floor reference
                                          point, +X = the horizontal projection of the reconstruction's own +X axis
                                          (its +Y if +X is within ~25 degrees of vertical). Not shared between floors.
                   VENUE_CONTROL_POINTS   whatever venue datum the operator's surveyed control points are expressed in.
    rotations    unit quaternions, (w, x, y, z) order on the wire and in storage
    precision    all transform arithmetic in float64; stored transforms are float64; point clouds in PLY/ksplat
                 artifacts stay float32 (sub-millimetre up to several kilometres from the origin)

A reconstruction (COLMAP/GLOMAP output and everything computed from it) lives in its own frame whose scale,
rotation and origin are arbitrary. The only way from there to metres is a calibrated similarity transform:

    X_canonical = s * R @ X_reconstruction + t

The control plane owns calibration (dev.chaya.api.frame). A stage receives the calibrated frame, if one
exists, on its work order as ``coordinateFrame`` (and, for an incremental re-scan, ``parentCoordinateFrame``).
A stage whose output is only meaningful in metres or needs a known up direction calls
:func:`require_canonical` / :func:`require_metric` and fails with ``NOT_CALIBRATED`` when the frame is
missing. Nothing in the worker ever assumes that reconstruction units are metres or that any reconstruction
axis is vertical.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

import numpy as np

from .errors import StageError

UNITS = "m"
UP_AXIS = "+Z"
HANDEDNESS = "right"
CANONICAL_UP = np.array([0.0, 0.0, 1.0])

# Tolerance for accepting a 3x3 matrix as a proper rotation (orthonormal, det +1).
_ROTATION_TOLERANCE = 1e-6


class NotCalibrated(StageError):
    """The stage needs a calibrated coordinate frame that does not exist (yet). Retryable after calibration."""

    def __init__(self, message: str, *, details: dict[str, Any] | None = None) -> None:
        super().__init__(message, code="NOT_CALIBRATED", details=details)


# ---- quaternions (w, x, y, z) -----------------------------------------------------------------------


def quaternion_to_matrix(q_wxyz: np.ndarray) -> np.ndarray:
    """Unit quaternion (w, x, y, z) to a 3x3 rotation matrix. The quaternion is normalised first."""
    q = np.asarray(q_wxyz, dtype=np.float64)
    norm = np.linalg.norm(q)
    if not np.isfinite(norm) or norm < 1e-12:
        raise ValueError("a rotation quaternion must be finite and non-zero")
    w, x, y, z = q / norm
    return np.array([
        [1 - 2 * (y * y + z * z), 2 * (x * y - w * z), 2 * (x * z + w * y)],
        [2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x)],
        [2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y)],
    ])


def matrix_to_quaternion(m: np.ndarray) -> np.ndarray:
    """3x3 rotation matrix to a unit quaternion (w, x, y, z) with w >= 0 (Shepperd's method, numerically stable)."""
    m = np.asarray(m, dtype=np.float64)
    trace = m[0, 0] + m[1, 1] + m[2, 2]
    if trace > 0:
        s = np.sqrt(trace + 1.0) * 2
        q = np.array([0.25 * s, (m[2, 1] - m[1, 2]) / s, (m[0, 2] - m[2, 0]) / s, (m[1, 0] - m[0, 1]) / s])
    elif m[0, 0] > m[1, 1] and m[0, 0] > m[2, 2]:
        s = np.sqrt(1.0 + m[0, 0] - m[1, 1] - m[2, 2]) * 2
        q = np.array([(m[2, 1] - m[1, 2]) / s, 0.25 * s, (m[0, 1] + m[1, 0]) / s, (m[0, 2] + m[2, 0]) / s])
    elif m[1, 1] > m[2, 2]:
        s = np.sqrt(1.0 + m[1, 1] - m[0, 0] - m[2, 2]) * 2
        q = np.array([(m[0, 2] - m[2, 0]) / s, (m[0, 1] + m[1, 0]) / s, 0.25 * s, (m[1, 2] + m[2, 1]) / s])
    else:
        s = np.sqrt(1.0 + m[2, 2] - m[0, 0] - m[1, 1]) * 2
        q = np.array([(m[1, 0] - m[0, 1]) / s, (m[0, 2] + m[2, 0]) / s, (m[1, 2] + m[2, 1]) / s, 0.25 * s])
    q = q / np.linalg.norm(q)
    return -q if q[0] < 0 else q


def quaternion_multiply(a_wxyz: np.ndarray, b_wxyz: np.ndarray) -> np.ndarray:
    """Hamilton product a * b. `b` may be (4,) or (N, 4); the result has b's shape."""
    a = np.asarray(a_wxyz, dtype=np.float64)
    b = np.asarray(b_wxyz, dtype=np.float64)
    single = b.ndim == 1
    b = np.atleast_2d(b)
    aw, ax, ay, az = a
    bw, bx, by, bz = b[:, 0], b[:, 1], b[:, 2], b[:, 3]
    out = np.stack([
        aw * bw - ax * bx - ay * by - az * bz,
        aw * bx + ax * bw + ay * bz - az * by,
        aw * by - ax * bz + ay * bw + az * bx,
        aw * bz + ax * by - ay * bx + az * bw,
    ], axis=1)
    return out[0] if single else out


def _require_rotation(r: np.ndarray) -> np.ndarray:
    r = np.asarray(r, dtype=np.float64)
    if r.shape != (3, 3) or not np.all(np.isfinite(r)):
        raise ValueError("rotation must be a finite 3x3 matrix")
    if not np.allclose(r @ r.T, np.eye(3), atol=_ROTATION_TOLERANCE) or abs(np.linalg.det(r) - 1.0) > _ROTATION_TOLERANCE:
        raise ValueError("rotation must be orthonormal with determinant +1 (no reflection, no scale)")
    return r


def nearest_rotation(m: np.ndarray) -> np.ndarray:
    """The proper rotation closest (Frobenius) to `m`, via SVD. Used to remove float drift, never to hide a
    reflection: a matrix whose closest orthogonal matrix has det -1 is rejected."""
    u, _, vt = np.linalg.svd(np.asarray(m, dtype=np.float64))
    r = u @ vt
    if np.linalg.det(r) < 0:
        raise ValueError("matrix is a reflection, not a rotation")
    return r


# ---- the similarity transform ----------------------------------------------------------------------


@dataclass(frozen=True)
class Similarity:
    """X_out = scale * rotation @ X_in + translation. Immutable; all arithmetic in float64."""

    scale: float
    rotation: np.ndarray  # (3, 3) proper rotation
    translation: np.ndarray  # (3,)

    def __post_init__(self) -> None:
        if not (np.isfinite(self.scale) and self.scale > 0):
            raise ValueError(f"scale must be finite and positive, got {self.scale!r}")
        object.__setattr__(self, "scale", float(self.scale))
        object.__setattr__(self, "rotation", _require_rotation(self.rotation))
        t = np.asarray(self.translation, dtype=np.float64).reshape(3)
        if not np.all(np.isfinite(t)):
            raise ValueError("translation must be finite")
        object.__setattr__(self, "translation", t)

    # -- construction --

    @staticmethod
    def identity() -> Similarity:
        return Similarity(1.0, np.eye(3), np.zeros(3))

    @staticmethod
    def from_quaternion(scale: float, q_wxyz: np.ndarray, translation: np.ndarray) -> Similarity:
        return Similarity(scale, quaternion_to_matrix(q_wxyz), translation)

    @staticmethod
    def from_matrix4(m: np.ndarray) -> Similarity:
        """From a 4x4 [[s*R, t], [0, 1]]. Scale is the cube root of det(s*R); rejects shear and reflection."""
        m = np.asarray(m, dtype=np.float64)
        if m.shape != (4, 4) or not np.allclose(m[3], [0, 0, 0, 1], atol=1e-9):
            raise ValueError("a similarity transform must be a 4x4 matrix with last row [0, 0, 0, 1]")
        a = m[:3, :3]
        det = np.linalg.det(a)
        if det <= 0:
            raise ValueError("transform has non-positive determinant (reflection or degenerate)")
        scale = float(np.cbrt(det))
        rotation = a / scale
        if not np.allclose(rotation @ rotation.T, np.eye(3), atol=1e-5):
            raise ValueError("transform has shear or non-uniform scale; it is not a similarity")
        return Similarity(scale, nearest_rotation(rotation), m[:3, 3])

    # -- use --

    @property
    def quaternion(self) -> np.ndarray:
        return matrix_to_quaternion(self.rotation)

    def matrix4(self) -> np.ndarray:
        m = np.eye(4)
        m[:3, :3] = self.scale * self.rotation
        m[:3, 3] = self.translation
        return m

    def apply(self, points: np.ndarray) -> np.ndarray:
        """Transforms points, shape (3,) or (N, 3). Always returns float64."""
        p = np.asarray(points, dtype=np.float64)
        return self.scale * (p @ self.rotation.T) + self.translation

    def apply_direction(self, directions: np.ndarray) -> np.ndarray:
        """Rotates directions (no scale, no translation) and renormalises them."""
        d = np.asarray(directions, dtype=np.float64) @ self.rotation.T
        return d / np.linalg.norm(d, axis=-1, keepdims=True)

    def apply_orientation(self, q_wxyz: np.ndarray) -> np.ndarray:
        """Rotates orientation quaternion(s) (w, x, y, z): R_out = R @ R_in."""
        return quaternion_multiply(self.quaternion, q_wxyz)

    def inverse(self) -> Similarity:
        r_inv = self.rotation.T
        return Similarity(1.0 / self.scale, r_inv, -(r_inv @ self.translation) / self.scale)

    def compose(self, inner: Similarity) -> Similarity:
        """self after inner: X -> self(inner(X))."""
        return Similarity(self.scale * inner.scale, self.rotation @ inner.rotation,
                          self.scale * (self.rotation @ inner.translation) + self.translation)

    def to_dict(self) -> dict[str, Any]:
        w, x, y, z = self.quaternion
        tx, ty, tz = self.translation
        return {"scale": self.scale, "rotation": {"w": float(w), "x": float(x), "y": float(y), "z": float(z)},
                "translation": {"x": float(tx), "y": float(ty), "z": float(tz)}}

    @staticmethod
    def from_dict(d: dict[str, Any]) -> Similarity:
        r, t = d["rotation"], d["translation"]
        return Similarity.from_quaternion(float(d["scale"]), np.array([r["w"], r["x"], r["y"], r["z"]], dtype=np.float64),
                                          np.array([t["x"], t["y"], t["z"]], dtype=np.float64))


def rotation_between(a: np.ndarray, b: np.ndarray) -> np.ndarray:
    """The minimal rotation taking unit direction `a` onto unit direction `b` (Rodrigues)."""
    a = np.asarray(a, dtype=np.float64) / np.linalg.norm(a)
    b = np.asarray(b, dtype=np.float64) / np.linalg.norm(b)
    v = np.cross(a, b)
    c = float(np.dot(a, b))
    if c < -1 + 1e-12:  # opposite: rotate 180 degrees about any axis perpendicular to a
        axis = np.cross(a, [1.0, 0.0, 0.0])
        if np.linalg.norm(axis) < 1e-6:
            axis = np.cross(a, [0.0, 1.0, 0.0])
        axis /= np.linalg.norm(axis)
        return 2 * np.outer(axis, axis) - np.eye(3)
    vx = np.array([[0, -v[2], v[1]], [v[2], 0, -v[0]], [-v[1], v[0], 0]])
    return np.eye(3) + vx + vx @ vx / (1 + c)


def tilt_degrees(up_in_canonical: np.ndarray) -> float:
    """Angle between a direction and canonical +Z, in degrees."""
    u = np.asarray(up_in_canonical, dtype=np.float64)
    u = u / np.linalg.norm(u)
    return float(np.degrees(np.arccos(np.clip(u @ CANONICAL_UP, -1.0, 1.0))))


# ---- the calibrated frame as the control plane hands it to a stage ---------------------------------------


@dataclass(frozen=True)
class CoordinateFrame:
    """One version of a reconstruction's calibration, as it appears on a work order.

    metric           the scale to metres is known
    gravity_aligned  the rotation to a +Z-up frame is known
    Only a frame that is both is *canonical*: only then does :meth:`to_canonical` exist. A metric frame that is
    not gravity-aligned still knows its scale (:meth:`metric_scale`), which is all an incremental re-scan region
    needs before registration.
    """

    id: str
    source_run_id: str
    version: int
    metric: bool
    gravity_aligned: bool
    horizontal_datum: str
    scale: float | None
    transform: Similarity | None  # present iff canonical

    @property
    def canonical(self) -> bool:
        return self.metric and self.gravity_aligned and self.transform is not None

    def to_canonical(self) -> Similarity:
        if not self.canonical:
            raise NotCalibrated(f"coordinate frame {self.id} is not canonical (metric={self.metric}, "
                                f"gravity_aligned={self.gravity_aligned})", details=self.describe())
        return self.transform  # type: ignore[return-value]

    def metric_scale(self) -> float:
        if not self.metric or self.scale is None:
            raise NotCalibrated(f"coordinate frame {self.id} has no metric scale", details=self.describe())
        return self.scale

    def describe(self) -> dict[str, Any]:
        return {"coordinate_frame_id": self.id, "source_run_id": self.source_run_id, "version": self.version,
                "metric": self.metric, "gravity_aligned": self.gravity_aligned, "horizontal_datum": self.horizontal_datum}

    @staticmethod
    def from_wire(d: dict[str, Any]) -> CoordinateFrame:
        """Parses the control plane's work-order representation (dev.chaya.api.frame.FrameDtos.WireFrame)."""
        metric = d.get("metricStatus") == "METRIC"
        aligned = d.get("gravityStatus") == "ALIGNED"
        scale = d.get("scale")
        if metric and (scale is None or not scale > 0):
            raise ValueError("a METRIC frame must carry a positive scale")
        transform = None
        if metric and aligned:
            if d.get("rotation") is None or d.get("translation") is None:
                raise ValueError("a METRIC, ALIGNED frame must carry a rotation and a translation")
            transform = Similarity.from_dict(d)
        return CoordinateFrame(str(d["id"]), str(d["sourceRunId"]), int(d["version"]), metric, aligned,
                               str(d.get("horizontalDatum") or "NONE"), float(scale) if scale is not None else None, transform)


def frame_from_order(order: dict[str, Any], key: str = "coordinateFrame") -> CoordinateFrame | None:
    raw = order.get(key)
    return None if raw is None else CoordinateFrame.from_wire(raw)


def require_canonical(order: dict[str, Any], stage: str, key: str = "coordinateFrame") -> CoordinateFrame:
    """The work order's frame, which must be canonical (metric and gravity-aligned). Raises NOT_CALIBRATED."""
    frame = frame_from_order(order, key)
    if frame is None:
        raise NotCalibrated(f"{stage} works in metres with a known up direction, and this reconstruction has no "
                            "calibrated coordinate frame. Calibrate it (POST .../reconstructions/{runId}/coordinate-frames) "
                            "and retry the run.", details={"work_order_key": key})
    frame.to_canonical()  # raises NOT_CALIBRATED with the frame's own status
    return frame


def require_metric(order: dict[str, Any], stage: str, key: str = "coordinateFrame") -> CoordinateFrame:
    """The work order's frame, which must at least know its metric scale. Raises NOT_CALIBRATED."""
    frame = frame_from_order(order, key)
    if frame is None:
        raise NotCalibrated(f"{stage} needs this reconstruction's metric scale, and no calibration exists for it. "
                            "Calibrate it with measured distances and retry the run.", details={"work_order_key": key})
    frame.metric_scale()
    return frame


def frame_provenance(frame: CoordinateFrame) -> dict[str, Any]:
    """What a canonical-frame artifact records about the frame its coordinates are in."""
    return {"id": frame.id, "source_run_id": frame.source_run_id, "version": frame.version,
            "horizontal_datum": frame.horizontal_datum, "units": UNITS, "up_axis": UP_AXIS, "handedness": HANDEDNESS}
