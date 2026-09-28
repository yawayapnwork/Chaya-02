"""Choose the real object crops that stand in for detected objects in B3 (detected_objects.json).

There is no reconstructed venue with Grounding DINO detections (no GPU here, docs/BENCHMARKS.md), so B3's detected
objects are human-annotated boxes from COCO val2017 (instances_val2017.json), cut and embedded exactly as
SEMANTIC_INDEXING cuts and embeds a detection (clip_vectors.py). The rule is fixed and mechanical, so nobody picks crops
that happen to search well:

    classes    the COCO categories that are also phrases of the worker's detection prompt
               (Settings.object_detection_prompt): chair, couch, potted plant -> "plant", dining table -> "table", bench
    per class  annotations with iscrowd = 0, a box at least 200 x 200 px covering at least 20 % of the image, in
               ascending annotation id, at most one per image; the first 3

    python benchmarks/b3_semantic_search/select_crops.py <instances_val2017.json>   # prints the "detected" entries
"""

from __future__ import annotations

import json
import sys

CLASSES = {"chair": "chair", "couch": "couch", "potted plant": "plant", "dining table": "table", "bench": "bench"}
PER_CLASS = 3
MIN_SIDE_PX = 200
MIN_AREA_FRACTION = 0.20


def select(coco: dict) -> list[dict]:
    cats = {c["id"]: c["name"] for c in coco["categories"]}
    images = {i["id"]: i for i in coco["images"]}
    licenses = {lic["id"]: lic for lic in coco["licenses"]}
    out = []
    for coco_name, label in CLASSES.items():
        chosen, used_images = [], set()
        for a in sorted(coco["annotations"], key=lambda a: a["id"]):
            if cats[a["category_id"]] != coco_name or a["iscrowd"] or a["image_id"] in used_images:
                continue
            img = images[a["image_id"]]
            _, _, w, h = a["bbox"]
            if w < MIN_SIDE_PX or h < MIN_SIDE_PX or w * h < MIN_AREA_FRACTION * img["width"] * img["height"]:
                continue
            used_images.add(a["image_id"])
            chosen.append({
                "id": f"{label}-{len(chosen) + 1}", "label": label, "coco_category": coco_name,
                "coco_annotation_id": a["id"], "coco_image_id": a["image_id"], "url": img["coco_url"],
                "bbox_xywh": [round(v, 2) for v in a["bbox"]],
                "license": licenses[img["license"]]["name"], "flickr_url": img.get("flickr_url")})
            if len(chosen) == PER_CLASS:
                break
        out.extend(chosen)
    return out


if __name__ == "__main__":
    with open(sys.argv[1], encoding="utf-8") as f:
        print(json.dumps(select(json.load(f)), indent=1))
