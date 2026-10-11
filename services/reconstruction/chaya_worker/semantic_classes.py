"""Maps a semantic segmentation model's own class names (whatever label set its training dataset used --
ADE20K, Cityscapes, ...) onto the scene-cleanup buckets this pipeline needs: floor, wall, furniture, clutter, stairs.

Three ways, chosen per model by `class_mapping` and recorded with every SEMANTIC_LABELS artifact:

* ``canonical``: a model trained on Chaya's own label set (chaya_worker.training.segmentation) names the buckets
  themselves, so each label maps to itself.
* ``reviewed:ade20k-150``: the label set of the default SegFormer checkpoints (ADE20K, 150 classes) has a reviewed table,
  ADE20K_150_REVIEWED, written before any evaluation (docs/ML_EVALUATION.md). It fixes what the keyword rule gets wrong on
  that label set: thin or large fixed structures (bannister, escalator, kitchen island, seat, booth, washer, radiator,
  shower, ...) fell through to ``clutter`` -- the one class geometric cleanup deletes when isolated -- and ``barrel``
  became furniture only because it contains "bar".
* ``keyword``: any other label set. Substring rules over the lower-cased label, checked in a fixed bucket order
  (floor/wall are structural and take priority over an ambiguous word that also appears in a furniture name).

Pure functions, no ML dependency, unit-testable on their own.

Ramps: no supported label set has a ramp class. A ramp is segmented as floor (or not at all) and handled geometrically
by navigation baking (slope limits), not semantically. The dataset schema has a ``ramp`` class so that custom
annotations can record one; at run time it maps to ``floor`` (RUNTIME_BUCKET_OF).
"""

from __future__ import annotations

FLOOR, WALL, FURNITURE, CLUTTER, STAIRS, UNKNOWN = "floor", "wall", "furniture", "clutter", "stairs", "unknown"
BUCKETS = (FLOOR, WALL, FURNITURE, CLUTTER, STAIRS)

# Classes a training/evaluation dataset may carry (chaya_worker.datasets) and what each becomes at run time. A ramp is
# walkable floor whose slope navigation judges; "ignore" marks pixels whose class is uncertain or not annotated.
DATASET_SEMANTIC_CLASSES = (FLOOR, WALL, FURNITURE, CLUTTER, STAIRS, "ramp")
RUNTIME_BUCKET_OF = {FLOOR: FLOOR, WALL: WALL, FURNITURE: FURNITURE, CLUTTER: CLUTTER, STAIRS: STAIRS, "ramp": FLOOR}

_FLOOR_KEYWORDS = ("floor", "rug", "carpet", "mat", "runway", "road", "sidewalk", "pavement", "ground", "grass", "field")
_WALL_KEYWORDS = ("wall", "fence", "wall-", "partition", "column", "pillar")
_CEILING_AS_WALL = ("ceiling",)
# Stairs and steps are walkable surface at their own heights (chaya_worker.navmesh.GROUND_LABELS), not furniture: NAVIGATION_BAKING
# needs their treads as ground to put a step into the navmesh. Railings and escalators stay obstacles.
_STAIRS_KEYWORDS = ("stairs", "staircase", "stairway", "step")
_FURNITURE_KEYWORDS = (
    "chair", "table", "sofa", "couch", "bed", "desk", "cabinet", "shelf", "shelving", "bookcase", "wardrobe",
    "armchair", "bench", "counter", "cupboard", "chest of drawers", "ottoman", "stool", "sink", "toilet",
    "bathtub", "refrigerator", "stove", "oven", "microwave", "dishwasher", "wardrobe", "door", "window",
    "railing", "fireplace", "bar", "pool table", "buffet", "dresser",
)


def bucket_label(model_label: str) -> str:
    """The keyword rule (see the module docstring). A canonical bucket name maps to itself."""
    name = model_label.strip().lower()
    if name in BUCKETS:
        return name
    if name == "ramp":
        return FLOOR
    if any(k in name for k in _FLOOR_KEYWORDS):
        return FLOOR
    if any(k in name for k in _WALL_KEYWORDS) or any(k in name for k in _CEILING_AS_WALL):
        return WALL
    if any(k in name for k in _STAIRS_KEYWORDS) and "escalator" not in name:
        return STAIRS
    if any(k in name for k in _FURNITURE_KEYWORDS):
        return FURNITURE
    return CLUTTER


# ---- the reviewed ADE20K-150 table -----------------------------------------------------------------------------------
# Every one of the 150 labels, in the checkpoints' own id order (nvidia/segformer-*-finetuned-ade-*: config.id2label;
# the label strings are stripped). Review rule: an object that is fixed, large, or a thin barrier one must walk around is
# FURNITURE (an obstacle cleanup keeps); small movable objects and things outside a building are CLUTTER; ground surfaces
# are FLOOR (a stage is a raised floor: navigation decides by height whether it is reachable); ceilings stay WALL
# (structural, as before). Version the table when a decision changes.
ADE20K_150_REVIEWED_VERSION = "2026-10-11.1"
ADE20K_150_REVIEWED: dict[str, str] = {
    "wall": WALL, "building": CLUTTER, "sky": CLUTTER, "floor": FLOOR, "tree": CLUTTER, "ceiling": WALL, "road": FLOOR,
    "bed": FURNITURE, "windowpane": FURNITURE, "grass": FLOOR, "cabinet": FURNITURE, "sidewalk": FLOOR, "person": CLUTTER,
    "earth": FLOOR, "door": FURNITURE, "table": FURNITURE, "mountain": CLUTTER, "plant": CLUTTER, "curtain": CLUTTER,
    "chair": FURNITURE, "car": CLUTTER, "water": CLUTTER, "painting": CLUTTER, "sofa": FURNITURE, "shelf": FURNITURE,
    "house": CLUTTER, "sea": CLUTTER, "mirror": CLUTTER, "rug": FLOOR, "field": FLOOR, "armchair": FURNITURE,
    "seat": FURNITURE, "fence": WALL, "desk": FURNITURE, "rock": CLUTTER, "wardrobe": FURNITURE, "lamp": CLUTTER,
    "bathtub": FURNITURE, "railing": FURNITURE, "cushion": CLUTTER, "base": CLUTTER, "box": CLUTTER, "column": WALL,
    "signboard": CLUTTER, "chest of drawers": FURNITURE, "counter": FURNITURE, "sand": FLOOR, "sink": FURNITURE,
    "skyscraper": CLUTTER, "fireplace": FURNITURE, "refrigerator": FURNITURE, "grandstand": FURNITURE, "path": FLOOR,
    "stairs": STAIRS, "runway": FLOOR, "case": FURNITURE, "pool table": FURNITURE, "pillow": CLUTTER,
    "screen door": FURNITURE, "stairway": STAIRS, "river": CLUTTER, "bridge": CLUTTER, "bookcase": FURNITURE,
    "blind": CLUTTER, "coffee table": FURNITURE, "toilet": FURNITURE, "flower": CLUTTER, "book": CLUTTER, "hill": CLUTTER,
    "bench": FURNITURE, "countertop": FURNITURE, "stove": FURNITURE, "palm": CLUTTER, "kitchen island": FURNITURE,
    "computer": CLUTTER, "swivel chair": FURNITURE, "boat": CLUTTER, "bar": FURNITURE, "arcade machine": FURNITURE,
    "hovel": CLUTTER, "bus": CLUTTER, "towel": CLUTTER, "light": CLUTTER, "truck": CLUTTER, "tower": CLUTTER,
    "chandelier": CLUTTER, "awning": CLUTTER, "streetlight": CLUTTER, "booth": FURNITURE, "television receiver": CLUTTER,
    "airplane": CLUTTER, "dirt track": FLOOR, "apparel": CLUTTER, "pole": CLUTTER, "land": FLOOR, "bannister": FURNITURE,
    "escalator": FURNITURE, "ottoman": FURNITURE, "bottle": CLUTTER, "buffet": FURNITURE, "poster": CLUTTER,
    "stage": FLOOR, "van": CLUTTER, "ship": CLUTTER, "fountain": CLUTTER, "conveyer belt": FURNITURE, "canopy": CLUTTER,
    "washer": FURNITURE, "plaything": CLUTTER, "swimming pool": CLUTTER, "stool": FURNITURE, "barrel": CLUTTER,
    "basket": CLUTTER, "waterfall": CLUTTER, "tent": CLUTTER, "bag": CLUTTER, "minibike": CLUTTER, "cradle": FURNITURE,
    "oven": FURNITURE, "ball": CLUTTER, "food": CLUTTER, "step": STAIRS, "tank": CLUTTER, "trade name": CLUTTER,
    "microwave": FURNITURE, "pot": CLUTTER, "animal": CLUTTER, "bicycle": CLUTTER, "lake": CLUTTER,
    "dishwasher": FURNITURE, "screen": CLUTTER, "blanket": CLUTTER, "sculpture": CLUTTER, "hood": CLUTTER,
    "sconce": CLUTTER, "vase": CLUTTER, "traffic light": CLUTTER, "tray": CLUTTER, "ashcan": CLUTTER, "fan": CLUTTER,
    "pier": CLUTTER, "crt screen": CLUTTER, "plate": CLUTTER, "monitor": CLUTTER, "bulletin board": CLUTTER,
    "shower": FURNITURE, "radiator": FURNITURE, "glass": CLUTTER, "clock": CLUTTER, "flag": CLUTTER,
}


def _normalized(name: str) -> str:
    return name.strip().lower()


def class_mapping(id2label: dict[int, str], *, method: str = "auto") -> tuple[dict[int, str], str]:
    """Each model class id -> bucket, and the method used (recorded as provenance).

    method="auto": ``canonical`` when every label is a bucket name (or ``ramp``); ``reviewed:ade20k-150`` when the label
    set is exactly ADE20K-150's; otherwise ``keyword``. method="keyword" forces the keyword rule (the behaviour before the
    reviewed table existed, kept for evaluation and as an explicit fallback)."""
    names = {i: _normalized(n) for i, n in id2label.items()}
    if method not in ("auto", "keyword"):
        raise ValueError(f"unknown class mapping method {method!r}")
    if method == "auto":
        if names and all(n in RUNTIME_BUCKET_OF for n in names.values()):
            return {i: RUNTIME_BUCKET_OF[n] for i, n in names.items()}, "canonical"
        if len(names) == len(ADE20K_150_REVIEWED) and set(names.values()) == set(ADE20K_150_REVIEWED):
            return {i: ADE20K_150_REVIEWED[n] for i, n in names.items()}, f"reviewed:ade20k-150@{ADE20K_150_REVIEWED_VERSION}"
    return {i: bucket_label(n) for i, n in names.items()}, "keyword"


def bucket_all(id2label: dict[int, str]) -> dict[int, str]:
    """id2label as reported by the model's own config (transformers `model.config.id2label`)."""
    return class_mapping(id2label)[0]
