"""Builds a held-out INDOOR EVALUATION dataset (chaya-dataset/2) from COCO 2017 val panoptic annotations.

    python -m chaya_worker.datasets.coco_import --out <dir> [--limit 120] [--cache <dir>]

What it is for: measuring the STOCK models (SegFormer + the class mapping, Grounding DINO, CLIP) on human-annotated indoor
images, as a baseline any later fine-tuned checkpoint must beat on the same samples (benchmarks/b6_semantic_models). It
is an evaluation set only: every image goes to the ``test`` split, and nothing here is training data.

Data and terms (recorded in the manifest and per image):
  * annotations: COCO 2017 panoptic val, CC BY 4.0 (https://cocodataset.org/#termsofuse), human-drawn;
  * images: each keeps its own Flickr licence from COCO's licence table. Only licences that permit commercial use and
    derivatives are selected (COCO licence ids 4 CC BY 2.0, 5 CC BY-SA 2.0, 7 no known copyright restrictions, 8 U.S.
    Government work). Images are downloaded to the dataset directory, never committed to the repository.

Selection rule (fixed before any model was run; changing it is a new dataset version):
  1. licence id in {4, 5, 7, 8};
  2. no ``person`` segment (no people: the evaluation set needs no privacy processing, and venues are evaluated empty);
  3. floor-like stuff (floor-wood, floor-other-merged, rug-merged) covering >= 2% of the image, and wall-like stuff
     (any wall-*, ceiling-merged) covering >= 2%: an indoor scene with visible structure;
  4. ascending image id, first --limit images.

Ground truth:
  * semantic mask: every panoptic segment's category through COCO_PANOPTIC_TO_BUCKET (written with the review rule of
    chaya_worker.semantic_classes.ADE20K_150_REVIEWED, before evaluation); categories listed in IGNORED and unlabelled
    pixels are 255 (ignore). ``ramp`` never occurs (COCO has no ramp class);
  * boxes: every non-crowd thing segment at least MIN_BOX_PX on each side, with COCO's own box and category. Detection is
    scored only on COCO_DETECTION_CLASSES (the categories the production prompt names); retrieval uses every category.

Download: the panoptic archive (~860 MB) is read with HTTP range requests, so only its central directory, the val JSON and
the val PNG archive are fetched. Every downloaded file's SHA-256 is recorded in manifest.preprocessing.
"""

from __future__ import annotations

import argparse
import io
import json
import urllib.request
import zipfile
from datetime import UTC, datetime
from pathlib import Path

from .jsonl import write_annotations, write_images
from .schema import IGNORE_INDEX, SCHEMA_VERSION, Annotation, BoundingBox, DatasetManifest, ImageRecord, Provenance, to_dict
from .validate import sha256_file

PANOPTIC_ZIP = "http://images.cocodataset.org/annotations/panoptic_annotations_trainval2017.zip"
IMAGE_URL = "http://images.cocodataset.org/val2017/{file_name}"
ALLOWED_LICENSES = {4, 5, 7, 8}
SEMANTIC_CLASSES = ("floor", "wall", "furniture", "clutter", "stairs", "ramp")
FLOOR_LIKE = {"floor-wood", "floor-other-merged", "rug-merged"}
MIN_STRUCTURE_FRACTION = 0.02
MIN_BOX_PX = 8
SELECTION_RULE_VERSION = "coco-indoor-2026-10-11.1"

# COCO panoptic category -> runtime bucket. Review rule as chaya_worker.semantic_classes.ADE20K_150_REVIEWED: ground
# surfaces floor; walls, ceilings, fences and window frames in walls wall; fixed, large or barrier objects furniture; small
# movable objects and outdoor things clutter. IGNORED: categories that mix structure and object (no defensible bucket).
_F, _W, _U, _C, _S = "floor", "wall", "furniture", "clutter", "stairs"
COCO_PANOPTIC_TO_BUCKET: dict[str, str] = {
    # floor
    "floor-wood": _F, "floor-other-merged": _F, "rug-merged": _F, "pavement-merged": _F, "road": _F, "dirt-merged": _F,
    "sand": _F, "gravel": _F, "grass-merged": _F, "playingfield": _F, "railroad": _F, "platform": _F,
    # wall
    "wall-brick": _W, "wall-stone": _W, "wall-tile": _W, "wall-wood": _W, "wall-other-merged": _W, "ceiling-merged": _W,
    "fence-merged": _W,
    # stairs
    "stairs": _S,
    # furniture (things)
    "chair": _U, "couch": _U, "bed": _U, "dining table": _U, "toilet": _U, "sink": _U, "refrigerator": _U, "oven": _U,
    "microwave": _U, "bench": _U,
    # furniture (stuff)
    "door-stuff": _U, "shelf": _U, "cabinet-merged": _U, "table-merged": _U, "counter": _U, "window-other": _U,
    "window-blind": _C,
    # clutter (things)
    "person": _C, "bicycle": _C, "car": _C, "motorcycle": _C, "airplane": _C, "bus": _C, "train": _C, "truck": _C, "boat": _C,
    "traffic light": _C, "fire hydrant": _C, "stop sign": _C, "parking meter": _C, "bird": _C, "cat": _C, "dog": _C,
    "horse": _C, "sheep": _C, "cow": _C, "elephant": _C, "bear": _C, "zebra": _C, "giraffe": _C, "backpack": _C,
    "umbrella": _C, "handbag": _C, "tie": _C, "suitcase": _C, "frisbee": _C, "skis": _C, "snowboard": _C, "sports ball": _C,
    "kite": _C, "baseball bat": _C, "baseball glove": _C, "skateboard": _C, "surfboard": _C, "tennis racket": _C,
    "bottle": _C, "wine glass": _C, "cup": _C, "fork": _C, "knife": _C, "spoon": _C, "bowl": _C, "banana": _C, "apple": _C,
    "sandwich": _C, "orange": _C, "broccoli": _C, "carrot": _C, "hot dog": _C, "pizza": _C, "donut": _C, "cake": _C,
    "potted plant": _C, "tv": _C, "laptop": _C, "mouse": _C, "remote": _C, "keyboard": _C, "cell phone": _C, "toaster": _C,
    "book": _C, "clock": _C, "vase": _C, "scissors": _C, "teddy bear": _C, "hair drier": _C, "toothbrush": _C,
    # clutter (stuff)
    "banner": _C, "blanket": _C, "bridge": _C, "cardboard": _C, "curtain": _C, "flower": _C, "fruit": _C, "house": _C,
    "light": _C, "mirror-stuff": _C, "net": _C, "pillow": _C, "river": _C, "roof": _C, "sea": _C, "snow": _C,
    "towel": _C, "tree-merged": _C, "water-other": _C, "sky-other-merged": _C, "mountain-merged": _C, "rock-merged": _C,
    "paper-merged": _C, "food-other-merged": _C, "building-other-merged": _C,
}
# Mixed or unclear categories: ignored (255) rather than forced into a bucket.
IGNORED = {"tent"}

# COCO thing categories the production detection prompt names (Settings.object_detection_prompt), and the prompt phrases
# that count as a correct label for each.
COCO_DETECTION_CLASSES: dict[str, tuple[str, ...]] = {
    "chair": ("chair",), "couch": ("sofa", "couch"), "dining table": ("table",), "potted plant": ("plant",), "bench": ("bench",),
}


class _HttpRangeFile(io.RawIOBase):
    """Seekable read-only view of a remote file through HTTP Range requests (enough for zipfile)."""

    def __init__(self, url: str, size: int) -> None:
        self.url, self.size, self.pos, self.fetched = url, size, 0, 0

    def seekable(self) -> bool:
        return True

    def readable(self) -> bool:
        return True

    def seek(self, offset: int, whence: int = 0) -> int:
        self.pos = {0: offset, 1: self.pos + offset, 2: self.size + offset}[whence]
        return self.pos

    def tell(self) -> int:
        return self.pos

    def read(self, n: int = -1) -> bytes:
        if n is None or n < 0:
            n = self.size - self.pos
        if n == 0 or self.pos >= self.size:
            return b""
        end = min(self.size, self.pos + n) - 1
        req = urllib.request.Request(self.url, headers={"Range": f"bytes={self.pos}-{end}"})  # noqa: S310 - fixed https/http COCO URL
        with urllib.request.urlopen(req, timeout=120) as res:  # noqa: S310
            data = res.read()
        self.pos += len(data)
        self.fetched += len(data)
        return data

    def readinto(self, b) -> int:
        data = self.read(len(b))
        b[: len(data)] = data
        return len(data)


def _remote_size(url: str) -> int:
    req = urllib.request.Request(url, method="HEAD")  # noqa: S310
    with urllib.request.urlopen(req, timeout=60) as res:  # noqa: S310
        return int(res.headers["Content-Length"])


def fetch_panoptic_val(cache: Path) -> tuple[Path, Path]:
    """panoptic_val2017.json and panoptic_val2017.zip, from the remote archive by range requests (cached)."""
    cache.mkdir(parents=True, exist_ok=True)
    out_json, out_zip = cache / "panoptic_val2017.json", cache / "panoptic_val2017.zip"
    if out_json.is_file() and out_zip.is_file():
        return out_json, out_zip
    remote = _HttpRangeFile(PANOPTIC_ZIP, _remote_size(PANOPTIC_ZIP))
    with zipfile.ZipFile(io.BufferedReader(remote, buffer_size=1 << 20)) as z:
        for member, dest in (("annotations/panoptic_val2017.json", out_json), ("annotations/panoptic_val2017.zip", out_zip)):
            with z.open(member) as src, open(dest, "wb") as f:
                while chunk := src.read(1 << 20):
                    f.write(chunk)
    return out_json, out_zip


def select_images(panoptic: dict) -> list[dict]:
    """Pure: the images the selection rule admits, ascending id, each with its annotation record."""
    cats = {c["id"]: c["name"] for c in panoptic["categories"]}
    by_image = {a["image_id"]: a for a in panoptic["annotations"]}
    chosen = []
    for img in sorted(panoptic["images"], key=lambda i: i["id"]):
        if img.get("license") not in ALLOWED_LICENSES:
            continue
        ann = by_image.get(img["id"])
        if ann is None:
            continue
        names = [cats[s["category_id"]] for s in ann["segments_info"]]
        if "person" in names:
            continue
        area = img["width"] * img["height"]
        floor = sum(s["area"] for s in ann["segments_info"] if cats[s["category_id"]] in FLOOR_LIKE) / area
        wall = sum(s["area"] for s in ann["segments_info"]
                   if cats[s["category_id"]].startswith("wall-") or cats[s["category_id"]] == "ceiling-merged") / area
        if floor >= MIN_STRUCTURE_FRACTION and wall >= MIN_STRUCTURE_FRACTION:
            chosen.append({"image": img, "annotation": ann})
    return chosen


def bucket_index(category_name: str) -> int:
    """Pure: the semantic mask value for a COCO panoptic category (IGNORE_INDEX when ignored or unmapped)."""
    if category_name in IGNORED:
        return IGNORE_INDEX
    bucket = COCO_PANOPTIC_TO_BUCKET.get(category_name)
    return SEMANTIC_CLASSES.index(bucket) if bucket else IGNORE_INDEX


def panoptic_to_semantic(png_rgb, segments: list[dict], cats: dict[int, str]):
    """Pure: panoptic PNG (H, W, 3 uint8, id = R + 256 G + 256^2 B) -> semantic mask (H, W uint8)."""
    import numpy as np

    ids = png_rgb[..., 0].astype(np.int64) + 256 * png_rgb[..., 1].astype(np.int64) + 65536 * png_rgb[..., 2].astype(np.int64)
    out = np.full(ids.shape, IGNORE_INDEX, dtype=np.uint8)
    for seg in segments:
        out[ids == seg["id"]] = bucket_index(cats[seg["category_id"]])
    return out


def build(out: Path, *, limit: int, cache: Path) -> dict:
    import numpy as np
    from PIL import Image

    pan_json, pan_zip = fetch_panoptic_val(cache)
    panoptic = json.loads(pan_json.read_text(encoding="utf-8"))
    cats = {c["id"]: c["name"] for c in panoptic["categories"]}
    things = {c["id"]: bool(c["isthing"]) for c in panoptic["categories"]}
    unmapped = sorted(n for n in cats.values() if n not in COCO_PANOPTIC_TO_BUCKET and n not in IGNORED)
    if unmapped:
        raise ValueError(f"COCO categories without a bucket decision: {unmapped}")
    licenses = {lic["id"]: lic for lic in panoptic["licenses"]}
    chosen = select_images(panoptic)[:limit]
    (out / "images").mkdir(parents=True, exist_ok=True)
    (out / "masks").mkdir(parents=True, exist_ok=True)
    images, annotations = [], []
    created = datetime.now(UTC).isoformat()
    with zipfile.ZipFile(pan_zip) as z:
        png_names = {Path(n).name: n for n in z.namelist() if n.endswith(".png")}
        for c in chosen:
            img, ann = c["image"], c["annotation"]
            dest = out / "images" / img["file_name"]
            if not dest.is_file():
                with urllib.request.urlopen(IMAGE_URL.format(file_name=img["file_name"]), timeout=120) as res:  # noqa: S310
                    dest.write_bytes(res.read())
            with z.open(png_names[ann["file_name"]]) as f:
                png = np.asarray(Image.open(io.BytesIO(f.read())).convert("RGB"))
            mask_name = Path(img["file_name"]).with_suffix(".png").name
            Image.fromarray(panoptic_to_semantic(png, ann["segments_info"], cats)).save(out / "masks" / mask_name)
            lic = licenses[img["license"]]
            images.append(ImageRecord(
                image_id=f"coco-val2017-{img['id']}", relative_path=img["file_name"], width=img["width"], height=img["height"],
                venue_id=None, split="test", group_id=f"coco-val2017-{img['id']}", content_sha256=sha256_file(dest),
                origin="public_dataset", license=f"{lic['name']} ({lic['url']})", source_url=img.get("flickr_url") or img.get("coco_url"),
                semantic_mask=mask_name))
            for seg in ann["segments_info"]:
                name = cats[seg["category_id"]]
                if not things[seg["category_id"]] or seg.get("iscrowd"):
                    continue
                x, y, w, h = seg["bbox"]
                if w < MIN_BOX_PX or h < MIN_BOX_PX:
                    continue
                annotations.append(Annotation(
                    annotation_id=f"coco-val2017-{img['id']}-{seg['id']}", image_id=f"coco-val2017-{img['id']}",
                    object_class=name, bounding_box=BoundingBox(float(x), float(y), float(w), float(h)),
                    provenance=Provenance(source="imported", annotator="COCO 2017 panoptic annotators", created_at=created,
                                          review_status="approved", notes="human-annotated COCO ground truth"),
                    attributes={"coco_segment_id": seg["id"], "area": seg["area"]}))
    write_images(out / "images.jsonl", images)
    write_annotations(out / "annotations.jsonl", annotations)
    manifest = DatasetManifest(
        name="coco-indoor-eval", version="1.0.0", created_at=created,
        object_classes=tuple(sorted({a.object_class for a in annotations})),
        description="Held-out indoor evaluation images from COCO 2017 val (panoptic): semantic masks in Chaya's buckets and "
                    "boxes of every COCO thing category. Evaluation only.",
        schema_version=SCHEMA_VERSION, source="COCO 2017 val, panoptic annotations (https://cocodataset.org)",
        license="Annotations CC BY 4.0 (COCO Consortium). Each image keeps its own Flickr licence (ImageRecord.license); only "
                "COCO licence ids 4, 5, 7 and 8 were selected.",
        annotation_method="COCO's human annotation (crowdsourced, COCO's own quality control); converted, not re-labelled. "
                          "Semantic buckets via COCO_PANOPTIC_TO_BUCKET.",
        semantic_classes=SEMANTIC_CLASSES,
        preprocessing={"selection_rule": SELECTION_RULE_VERSION, "allowed_licenses": sorted(ALLOWED_LICENSES),
                       "exclude": "images with a person segment", "min_floor_fraction": MIN_STRUCTURE_FRACTION,
                       "min_wall_fraction": MIN_STRUCTURE_FRACTION, "limit": limit,
                       "panoptic_val2017_json_sha256": sha256_file(pan_json), "panoptic_val2017_zip_sha256": sha256_file(pan_zip),
                       "category_to_bucket": COCO_PANOPTIC_TO_BUCKET, "ignored_categories": sorted(IGNORED),
                       "detection_classes": {k: list(v) for k, v in COCO_DETECTION_CLASSES.items()},
                       "boxes": f"all non-crowd thing segments, each side >= {MIN_BOX_PX} px",
                       "ignore_index": IGNORE_INDEX, "images_resized": False})
    (out / "manifest.json").write_text(json.dumps(to_dict(manifest), indent=2, sort_keys=True), encoding="utf-8")
    return {"images": len(images), "annotations": len(annotations)}


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--limit", type=int, default=120)
    ap.add_argument("--cache", type=Path, default=None, help="where the panoptic val files are kept (default <out>/.cache)")
    args = ap.parse_args(argv)
    result = build(args.out, limit=args.limit, cache=args.cache or args.out / ".cache")
    print(json.dumps(result))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
