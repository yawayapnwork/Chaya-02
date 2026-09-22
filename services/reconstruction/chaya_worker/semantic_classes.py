"""Maps a semantic segmentation model's own class names (whatever label set its training dataset used --
ADE20K, Cityscapes, ...) onto the four scene-cleanup buckets this pipeline needs: floor, wall, furniture,
clutter. Keyword-based rather than a hardcoded index table, so it is not tied to one specific model's
label ordering (which is not part of any stable public contract) and stays correct if the worker's
configured model changes. Pure function, no ML dependency, unit-testable on its own.
"""

from __future__ import annotations

FLOOR, WALL, FURNITURE, CLUTTER, UNKNOWN = "floor", "wall", "furniture", "clutter", "unknown"

# Longest/most specific keywords first within a bucket only matters for readability; matching is
# substring-based over the lower-cased label name, checked in a fixed bucket order (floor/wall are
# structural surfaces and take priority over an ambiguous word that also appears in a furniture name).
_FLOOR_KEYWORDS = ("floor", "rug", "carpet", "mat", "runway", "road", "sidewalk", "pavement", "ground", "grass", "field")
_WALL_KEYWORDS = ("wall", "fence", "wall-", "partition", "column", "pillar")
_CEILING_AS_WALL = ("ceiling",)
_FURNITURE_KEYWORDS = (
    "chair", "table", "sofa", "couch", "bed", "desk", "cabinet", "shelf", "shelving", "bookcase", "wardrobe",
    "armchair", "bench", "counter", "cupboard", "chest of drawers", "ottoman", "stool", "sink", "toilet",
    "bathtub", "refrigerator", "stove", "oven", "microwave", "dishwasher", "wardrobe", "door", "window",
    "stairs", "staircase", "railing", "fireplace", "bar", "pool table", "buffet", "dresser",
)


def bucket_label(model_label: str) -> str:
    name = model_label.strip().lower()
    if any(k in name for k in _FLOOR_KEYWORDS):
        return FLOOR
    if any(k in name for k in _WALL_KEYWORDS) or any(k in name for k in _CEILING_AS_WALL):
        return WALL
    if any(k in name for k in _FURNITURE_KEYWORDS):
        return FURNITURE
    return CLUTTER


def bucket_all(id2label: dict[int, str]) -> dict[int, str]:
    """id2label as reported by the model's own config (transformers `model.config.id2label`)."""
    return {i: bucket_label(name) for i, name in id2label.items()}
