"""Creates the on-disk layout for a new project-specific annotation dataset:

    <root>/<name>/
        manifest.json       -- name, version, object classes, description
        images/              -- the actual image files, annotations reference paths under here
        images.jsonl         -- one ImageRecord per line
        annotations.jsonl    -- one Annotation per line
        README.md

Usage:
    python -m chaya_worker.datasets.scaffold --root ./datasets --name venue-fixtures-v1 \\
        --classes "chair,table,sofa,reception desk,exit sign"
"""

from __future__ import annotations

import argparse
import json
from datetime import UTC, datetime
from pathlib import Path

from .schema import DatasetManifest, to_dict

README_TEMPLATE = """# {name}

A project-specific object-detection annotation dataset for fine-tuning Grounding DINO
(chaya_worker.grounding_dino) on Chaya venues. Nothing in this repository has been fine-tuned on this
data yet -- see chaya_worker/grounding_dino.py.

## Layout

- `manifest.json` -- dataset identity and the object class vocabulary.
- `images/` -- source image files. `images.jsonl` records point at paths under here.
- `images.jsonl` -- one `chaya_worker.datasets.schema.ImageRecord` per line.
- `annotations.jsonl` -- one `chaya_worker.datasets.schema.Annotation` per line.

## Adding annotations

Use `chaya_worker.datasets.jsonl.append_images` / `append_annotations` (or hand-append well-formed JSON
lines). Run `chaya_worker.datasets.jsonl.validate_dataset(root)` before using the dataset for training;
it never raises, only reports problems, since a dataset mid-annotation is expected to be inconsistent.
"""


def scaffold_dataset(root: Path, name: str, object_classes: list[str], description: str = "") -> Path:
    if not name or "/" in name or "\\" in name:
        raise ValueError(f"invalid dataset name: {name!r}")
    dataset_dir = root / name
    if dataset_dir.exists() and any(dataset_dir.iterdir()):
        raise FileExistsError(f"{dataset_dir} already exists and is not empty")
    (dataset_dir / "images").mkdir(parents=True, exist_ok=True)
    (dataset_dir / "images.jsonl").touch()
    (dataset_dir / "annotations.jsonl").touch()

    manifest = DatasetManifest(name=name, version="0.1.0", created_at=datetime.now(UTC).isoformat(),
                               object_classes=tuple(object_classes), description=description)
    (dataset_dir / "manifest.json").write_text(json.dumps(to_dict(manifest), indent=2, sort_keys=True), encoding="utf-8")
    (dataset_dir / "README.md").write_text(README_TEMPLATE.format(name=name), encoding="utf-8")
    return dataset_dir


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", required=True, type=Path)
    parser.add_argument("--name", required=True)
    parser.add_argument("--classes", required=True, help="comma-separated object class names")
    parser.add_argument("--description", default="")
    args = parser.parse_args(argv)
    classes = [c.strip() for c in args.classes.split(",") if c.strip()]
    path = scaffold_dataset(args.root, args.name, classes, args.description)
    print(f"created dataset at {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
