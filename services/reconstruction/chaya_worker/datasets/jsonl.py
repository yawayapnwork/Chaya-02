"""Reads and writes the dataset's images.jsonl / annotations.jsonl files: one JSON object per line, the
standard format for object-detection training pipelines (COCO-JSON's single-file-per-dataset approach
does not append well; JSONL does)."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Iterable, Iterator

from .schema import Annotation, ImageRecord, annotation_from_dict, image_from_dict, to_dict


def write_images(path: Path, images: Iterable[ImageRecord]) -> int:
    n = 0
    with open(path, "w", encoding="utf-8") as f:
        for image in images:
            f.write(json.dumps(to_dict(image), sort_keys=True) + "\n")
            n += 1
    return n


def append_images(path: Path, images: Iterable[ImageRecord]) -> int:
    n = 0
    with open(path, "a", encoding="utf-8") as f:
        for image in images:
            f.write(json.dumps(to_dict(image), sort_keys=True) + "\n")
            n += 1
    return n


def read_images(path: Path) -> Iterator[ImageRecord]:
    if not path.exists():
        return
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                yield image_from_dict(json.loads(line))


def write_annotations(path: Path, annotations: Iterable[Annotation]) -> int:
    n = 0
    with open(path, "w", encoding="utf-8") as f:
        for a in annotations:
            f.write(json.dumps(to_dict(a), sort_keys=True) + "\n")
            n += 1
    return n


def append_annotations(path: Path, annotations: Iterable[Annotation]) -> int:
    n = 0
    with open(path, "a", encoding="utf-8") as f:
        for a in annotations:
            f.write(json.dumps(to_dict(a), sort_keys=True) + "\n")
            n += 1
    return n


def read_annotations(path: Path) -> Iterator[Annotation]:
    if not path.exists():
        return
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                yield annotation_from_dict(json.loads(line))


def validate_dataset(root: Path) -> list[str]:
    """Cross-checks annotations.jsonl against images.jsonl. Returns a list of problems (empty = valid).
    Does not raise: a dataset mid-annotation is expected to be temporarily inconsistent."""
    problems: list[str] = []
    image_ids = set()
    for image in read_images(root / "images.jsonl"):
        if image.image_id in image_ids:
            problems.append(f"duplicate image_id {image.image_id!r}")
        image_ids.add(image.image_id)
        if not (root / "images" / image.relative_path).exists():
            problems.append(f"image {image.image_id!r} references missing file images/{image.relative_path}")

    annotation_ids = set()
    for a in read_annotations(root / "annotations.jsonl"):
        if a.annotation_id in annotation_ids:
            problems.append(f"duplicate annotation_id {a.annotation_id!r}")
        annotation_ids.add(a.annotation_id)
        if a.image_id not in image_ids:
            problems.append(f"annotation {a.annotation_id!r} references unknown image_id {a.image_id!r}")
    return problems
