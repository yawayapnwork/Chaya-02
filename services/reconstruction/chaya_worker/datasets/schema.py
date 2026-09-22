"""The project-specific object-detection dataset format: what a future Grounding DINO fine-tuning run
would train on. This is deliberately separate from the runtime SEMANTIC_INDEXING pipeline -- it exists so
humans (or a review workflow) can accumulate labelled, provenance-tracked examples from real venues over
time, independent of and prior to any actual fine-tuning work (which has not happened yet; see
chaya_worker.grounding_dino).
"""

from __future__ import annotations

from dataclasses import asdict, dataclass, field
from typing import Any


@dataclass(frozen=True)
class BoundingBox:
    """Pixel-space by default; `normalized=True` means x/y/width/height are each in [0, 1] relative to
    the image's own width/height (the convention most detection training frameworks expect)."""

    x: float
    y: float
    width: float
    height: float
    normalized: bool = False

    def __post_init__(self) -> None:
        if self.width <= 0 or self.height <= 0:
            raise ValueError(f"bounding box width/height must be positive, got {self.width}x{self.height}")
        if self.normalized and not (0 <= self.x <= 1 and 0 <= self.y <= 1 and self.x + self.width <= 1 + 1e-6
                                    and self.y + self.height <= 1 + 1e-6):
            raise ValueError(f"normalized bounding box out of [0, 1]: {self}")

    def to_xyxy(self, image_width: int, image_height: int) -> tuple[float, float, float, float]:
        if self.normalized:
            return (self.x * image_width, self.y * image_height,
                    (self.x + self.width) * image_width, (self.y + self.height) * image_height)
        return (self.x, self.y, self.x + self.width, self.y + self.height)


REVIEW_STATUSES = ("unreviewed", "approved", "rejected")
SOURCES = ("human", "model_assisted", "imported")


@dataclass(frozen=True)
class Provenance:
    """Where an annotation came from and whether anyone has checked it. `source="model_assisted"` records
    which model proposed it (e.g. the stock Grounding DINO checkpoint) so a later audit can tell a human
    label from a machine-proposed one that was only ever rubber-stamped."""

    source: str  # one of SOURCES
    annotator: str  # a person's identifier or a service/model id
    created_at: str  # ISO 8601
    review_status: str = "unreviewed"  # one of REVIEW_STATUSES
    model_id: str | None = None  # set when source == "model_assisted"
    notes: str | None = None

    def __post_init__(self) -> None:
        if self.source not in SOURCES:
            raise ValueError(f"provenance.source must be one of {SOURCES}, got {self.source!r}")
        if self.review_status not in REVIEW_STATUSES:
            raise ValueError(f"provenance.review_status must be one of {REVIEW_STATUSES}, got {self.review_status!r}")
        if self.source == "model_assisted" and not self.model_id:
            raise ValueError("provenance.model_id is required when source is 'model_assisted'")


@dataclass(frozen=True)
class ImageRecord:
    """One frame/image the dataset has annotations for."""

    image_id: str
    relative_path: str  # under the dataset root's images/ directory
    width: int
    height: int
    venue_id: str
    floor_id: str | None = None
    scan_id: str | None = None
    scan_version_id: str | None = None
    source_frame: str | None = None  # the original capture frame filename, if this was extracted from one
    captured_at: str | None = None

    def __post_init__(self) -> None:
        if self.width <= 0 or self.height <= 0:
            raise ValueError(f"image {self.image_id}: width/height must be positive")


@dataclass(frozen=True)
class Annotation:
    """One labelled object instance in one image."""

    annotation_id: str
    image_id: str
    object_class: str
    bounding_box: BoundingBox
    provenance: Provenance
    attributes: dict[str, Any] = field(default_factory=dict)

    def __post_init__(self) -> None:
        if not self.object_class.strip():
            raise ValueError(f"annotation {self.annotation_id}: object_class must not be blank")


@dataclass(frozen=True)
class DatasetManifest:
    name: str
    version: str
    created_at: str
    object_classes: tuple[str, ...]
    description: str = ""


def to_dict(record: Any) -> dict[str, Any]:
    return asdict(record)


def image_from_dict(d: dict[str, Any]) -> ImageRecord:
    return ImageRecord(**d)


def annotation_from_dict(d: dict[str, Any]) -> Annotation:
    d = dict(d)
    d["bounding_box"] = BoundingBox(**d["bounding_box"])
    d["provenance"] = Provenance(**d["provenance"])
    return Annotation(**d)
