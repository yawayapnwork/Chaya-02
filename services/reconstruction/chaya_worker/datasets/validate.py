"""Validates a chaya-dataset/2 directory before it may be used for training or evaluation, and computes its hash.

    python -m chaya_worker.datasets.validate <dataset-dir> [--json report.json] [--lock]

Errors (any one makes the dataset unusable; exit status 1):
  * manifest: not schema chaya-dataset/2, or no source / license / annotation_method / preprocessing record;
  * images: duplicate image_id; file missing; stored bytes differ from content_sha256; real size differs from the record;
    the same bytes under two image_ids (a duplicate sample); no or unknown split;
  * leakage: one group_id, or one file content, in more than one split;
  * privacy: a captured image without evidence that its bytes are the anonymised frame of a fail-closed privacy run
    (chaya_worker.datasets.capture_import); a public-dataset image without its own licence;
  * labels: an annotation of an unknown image or class, a duplicate annotation_id, a box outside its image, an
    annotation that is a model proposal nobody approved (an unverified prediction is never ground truth), a rejected
    annotation still present; a semantic mask that is missing, of the wrong size or with values outside the classes;
  * missing annotations: an image with no approved annotation and no mask that is not marked `negative`.
Warnings: class imbalance (a class missing from a split that has others, or most/least frequent above
manifest.imbalance_ratio_warn), and splits that are empty.

`--lock` writes DATASET_LOCK.json (dataset version and SHA-256) when there are no errors; training and evaluation refuse a
dataset whose lock does not match its current contents.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from .jsonl import read_annotations, read_images
from .schema import IGNORE_INDEX, ORIGINS, SCHEMA_VERSION, SPLITS, manifest_from_dict, to_dict

LOCK = "DATASET_LOCK.json"
REQUIRED_PRIVACY = ("status", "privacy_report_sha256", "anonymised_frame_sha256", "frame_archive_kind", "policy")


@dataclass
class Report:
    errors: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)
    stats: dict[str, Any] = field(default_factory=dict)

    @property
    def ok(self) -> bool:
        return not self.errors

    def as_dict(self) -> dict[str, Any]:
        return {"ok": self.ok, "errors": self.errors, "warnings": self.warnings, "stats": self.stats}


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def _image_size(path: Path) -> tuple[int, int] | None:
    try:
        from PIL import Image
    except ImportError:  # pragma: no cover - Pillow is a worker dependency
        return None
    with Image.open(path) as im:
        return im.size


def _check_privacy(rec, errors: list[str]) -> None:
    where = f"image {rec.image_id!r}"
    if rec.origin not in ORIGINS:
        errors.append(f"{where}: origin {rec.origin!r} is not one of {ORIGINS}")
        return
    if rec.origin == "public_dataset":
        if not rec.license:
            errors.append(f"{where}: a public-dataset image needs its own licence")
        return
    p = rec.privacy or {}
    missing = [k for k in REQUIRED_PRIVACY if not p.get(k)]
    if missing:
        errors.append(f"{where}: a captured image needs privacy evidence {missing} (import it with "
                      "chaya_worker.datasets.capture_import from the run's FRAME_ARCHIVE_ANON and PRIVACY_REPORT)")
        return
    if p["status"] != "ANONYMISED" or p["frame_archive_kind"] != "FRAME_ARCHIVE_ANON" or p["policy"] != "fail-closed":
        errors.append(f"{where}: privacy evidence is not an anonymised frame from a fail-closed privacy run: {p}")
    if rec.content_sha256 and p["anonymised_frame_sha256"] != rec.content_sha256:
        errors.append(f"{where}: the stored image is not the anonymised frame the privacy evidence names")


def validate(root: Path) -> Report:
    root = Path(root)
    r = Report()
    manifest_path = root / "manifest.json"
    if not manifest_path.is_file():
        r.errors.append("manifest.json is missing")
        return r
    manifest = manifest_from_dict(json.loads(manifest_path.read_text(encoding="utf-8")))
    if manifest.schema_version != SCHEMA_VERSION:
        r.errors.append(f"manifest schema_version is {manifest.schema_version!r}, expected {SCHEMA_VERSION!r} "
                        "(add splits, content hashes, licences and provenance; see chaya_worker.datasets.schema)")
    for key in ("source", "license", "annotation_method"):
        if not getattr(manifest, key):
            r.errors.append(f"manifest.{key} is empty: record where the data and its labels come from and under which terms")
    if not manifest.preprocessing:
        r.errors.append("manifest.preprocessing is empty: record the selection rule, label mapping and tool versions")

    images = list(read_images(root / "images.jsonl"))
    annotations = list(read_annotations(root / "annotations.jsonl"))
    by_id = {}
    by_content: dict[str, str] = {}
    split_of_group: dict[str, set[str]] = defaultdict(set)
    split_of_content: dict[str, set[str]] = defaultdict(set)
    for rec in images:
        where = f"image {rec.image_id!r}"
        if rec.image_id in by_id:
            r.errors.append(f"duplicate image_id {rec.image_id!r}")
            continue
        by_id[rec.image_id] = rec
        if rec.split not in SPLITS:
            r.errors.append(f"{where}: split {rec.split!r} is not one of {SPLITS}")
        path = root / "images" / rec.relative_path
        if not path.is_file():
            r.errors.append(f"{where}: file images/{rec.relative_path} is missing")
        else:
            actual = sha256_file(path)
            if not rec.content_sha256:
                r.errors.append(f"{where}: no content_sha256")
            elif actual != rec.content_sha256:
                r.errors.append(f"{where}: images/{rec.relative_path} does not match its content_sha256")
            if actual in by_content:
                r.errors.append(f"{where}: same bytes as image {by_content[actual]!r} (duplicate sample)")
            by_content.setdefault(actual, rec.image_id)
            split_of_content[actual].add(str(rec.split))
            size = _image_size(path)
            if size is not None and size != (rec.width, rec.height):
                r.errors.append(f"{where}: recorded {rec.width}x{rec.height}, file is {size[0]}x{size[1]}")
        split_of_group[rec.group_id or f"image:{rec.image_id}"].add(str(rec.split))
        _check_privacy(rec, r.errors)
        if rec.semantic_mask:
            _check_mask(root, rec, len(manifest.semantic_classes), r.errors)

    for group, splits in sorted(split_of_group.items()):
        if len(splits) > 1:
            r.errors.append(f"leakage: group {group!r} appears in splits {sorted(splits)}")
    for content, splits in split_of_content.items():
        if len(splits) > 1:
            r.errors.append(f"leakage: the same image bytes ({content[:12]}…) appear in splits {sorted(splits)}")

    classes = set(manifest.object_classes)
    seen_ann = set()
    usable_per_image: Counter[str] = Counter()
    instances: dict[str, Counter[str]] = defaultdict(Counter)
    for a in annotations:
        where = f"annotation {a.annotation_id!r}"
        if a.annotation_id in seen_ann:
            r.errors.append(f"duplicate annotation_id {a.annotation_id!r}")
        seen_ann.add(a.annotation_id)
        rec = by_id.get(a.image_id)
        if rec is None:
            r.errors.append(f"{where}: unknown image_id {a.image_id!r}")
            continue
        if a.object_class not in classes:
            r.errors.append(f"{where}: class {a.object_class!r} is not in manifest.object_classes")
        x0, y0, x1, y1 = a.bounding_box.to_xyxy(rec.width, rec.height)
        if x0 < -1 or y0 < -1 or x1 > rec.width + 1 or y1 > rec.height + 1:
            r.errors.append(f"{where}: box {[round(v, 1) for v in (x0, y0, x1, y1)]} is outside its {rec.width}x{rec.height} image")
        prov = a.provenance
        if prov.review_status == "rejected":
            r.errors.append(f"{where}: rejected annotations must be removed, not kept")
            continue
        if prov.source == "model_assisted" and prov.review_status != "approved":
            r.errors.append(f"{where}: a model proposal ({prov.model_id}) that nobody approved is not ground truth")
            continue
        usable_per_image[a.image_id] += 1
        instances[str(rec.split)][a.object_class] += 1

    for rec in by_id.values():
        if not usable_per_image[rec.image_id] and not rec.semantic_mask and not rec.negative:
            r.errors.append(f"image {rec.image_id!r}: no usable annotation and no mask (mark it negative if it has no objects)")

    per_split = Counter(str(rec.split) for rec in by_id.values())
    for split in SPLITS:
        if not per_split.get(split):
            r.warnings.append(f"split {split!r} is empty")
    present = {c for split in instances.values() for c in split}
    for split, counts in sorted(instances.items()):
        missing = sorted(present - set(counts))
        if missing:
            r.warnings.append(f"imbalance: split {split!r} has no instances of {missing}")
        if counts and min(counts.values()) and max(counts.values()) / min(counts.values()) > manifest.imbalance_ratio_warn:
            r.warnings.append(f"imbalance: split {split!r} most/least frequent class ratio "
                              f"{max(counts.values()) / min(counts.values()):.1f} > {manifest.imbalance_ratio_warn}")
    r.stats = {"images": len(by_id), "annotations": len(annotations), "images_per_split": dict(per_split),
               "instances_per_split": {k: dict(v) for k, v in instances.items()},
               "origins": dict(Counter(rec.origin for rec in by_id.values())),
               "images_with_masks": sum(1 for rec in by_id.values() if rec.semantic_mask)}
    return r


def _check_mask(root: Path, rec, n_classes: int, errors: list[str]) -> None:
    where = f"image {rec.image_id!r}"
    path = root / "masks" / rec.semantic_mask
    if not path.is_file():
        errors.append(f"{where}: mask masks/{rec.semantic_mask} is missing")
        return
    import numpy as np
    from PIL import Image

    with Image.open(path) as im:
        if im.size != (rec.width, rec.height):
            errors.append(f"{where}: mask is {im.size[0]}x{im.size[1]}, image is {rec.width}x{rec.height}")
            return
        values = np.unique(np.asarray(im))
    bad = [int(v) for v in values if v != IGNORE_INDEX and v >= n_classes]
    if bad:
        errors.append(f"{where}: mask values {bad} are not semantic classes (0..{n_classes - 1}) or {IGNORE_INDEX} (ignore)")


def dataset_sha256(root: Path) -> str:
    """SHA-256 over the manifest, both JSONL files and every image and mask file (sorted, with paths), so the same
    contents always give the same hash on any machine."""
    root = Path(root)
    h = hashlib.sha256()
    manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
    h.update(json.dumps(manifest, sort_keys=True).encode())
    for name in ("images.jsonl", "annotations.jsonl"):
        path = root / name
        h.update(name.encode())
        h.update(sha256_file(path).encode() if path.is_file() else b"-")
    for sub in ("images", "masks"):
        base = root / sub
        if base.is_dir():
            for f in sorted(p for p in base.rglob("*") if p.is_file()):
                h.update(f.relative_to(root).as_posix().encode())
                h.update(sha256_file(f).encode())
    return h.hexdigest()


def write_lock(root: Path, report: Report) -> dict[str, Any]:
    if not report.ok:
        raise ValueError("a dataset with validation errors cannot be locked")
    manifest = json.loads((Path(root) / "manifest.json").read_text(encoding="utf-8"))
    lock = {"name": manifest["name"], "version": manifest["version"], "schema_version": manifest.get("schema_version"),
            "sha256": dataset_sha256(root), "stats": report.stats}
    (Path(root) / LOCK).write_text(json.dumps(lock, indent=2, sort_keys=True), encoding="utf-8")
    return lock


def require_locked(root: Path) -> dict[str, Any]:
    """The lock of a dataset that validates and whose contents still match it; raises otherwise. Used by training and
    evaluation so that a result always names the exact data it came from."""
    root = Path(root)
    report = validate(root)
    if not report.ok:
        raise ValueError(f"dataset {root} does not validate: {report.errors[:5]}")
    path = root / LOCK
    if not path.is_file():
        raise ValueError(f"dataset {root} is not locked: run python -m chaya_worker.datasets.validate {root} --lock")
    lock = json.loads(path.read_text(encoding="utf-8"))
    actual = dataset_sha256(root)
    if lock.get("sha256") != actual:
        raise ValueError(f"dataset {root} changed since it was locked ({lock.get('sha256')} != {actual}); validate and lock it again")
    return lock


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("root", type=Path)
    ap.add_argument("--json", type=Path)
    ap.add_argument("--lock", action="store_true")
    args = ap.parse_args(argv)
    report = validate(args.root)
    out = report.as_dict()
    if report.ok:
        out["sha256"] = dataset_sha256(args.root)
        if args.lock:
            out["lock"] = write_lock(args.root, report)
    text = json.dumps(out, indent=2, sort_keys=True)
    if args.json:
        args.json.write_text(text, encoding="utf-8")
    print(text)
    return 0 if report.ok else 1


__all__ = ["Report", "dataset_sha256", "require_locked", "to_dict", "validate", "write_lock"]

if __name__ == "__main__":
    raise SystemExit(main())
