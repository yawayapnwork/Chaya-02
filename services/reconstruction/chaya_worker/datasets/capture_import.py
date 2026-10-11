"""Adds a venue capture's frames to a dataset -- only ever the anonymised ones.

    python -m chaya_worker.datasets.capture_import --dataset <dir> --frames frames-anon.tar --privacy-report privacy-report.json \\
        --run-id <pipeline run> --venue-id <venue> [--capture-id <capture>] --split train|val|test

The inputs are a pipeline run's own artifacts: FRAME_ARCHIVE_ANON (the frames after PRIVACY_PREPROCESS) and its
PRIVACY_REPORT. A frame is admitted only if the report is a fail-closed run that lists it; the stored bytes are the
anonymised frame, and their SHA-256 is recorded as the privacy evidence that chaya_worker.datasets.validate checks. There
is deliberately no way to import raw frames (FRAME_ARCHIVE / FRAME_ARCHIVE_SELECTED). The whole capture is one split
group, so its frames can never be spread across train and test (near-identical neighbouring frames would leak).

Images are added unlabelled; annotations are added afterwards by annotators (chaya_worker.datasets.jsonl).
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import tempfile
from pathlib import Path

from .. import archive
from .jsonl import append_images, read_images
from .schema import SPLITS, ImageRecord
from .validate import sha256_file

IMAGE_SUFFIXES = (".jpg", ".jpeg", ".png")


class PrivacyGateError(ValueError):
    """The frames cannot be shown to have passed privacy preprocessing."""


def admissible_frames(report: dict) -> dict[str, dict]:
    """Pure: the frames a privacy report proves were anonymised, by file name. Raises PrivacyGateError when the report is
    not from a fail-closed privacy run."""
    if report.get("policy") != "fail-closed":
        raise PrivacyGateError(f"privacy report policy is {report.get('policy')!r}, not 'fail-closed'")
    per_frame = report.get("per_frame")
    if not isinstance(per_frame, list) or len(per_frame) != report.get("frames"):
        raise PrivacyGateError("privacy report does not list every frame it processed")
    return {f["frame"]: f for f in per_frame}


def import_capture(dataset: Path, frames_tar: Path, report_path: Path, *, run_id: str, venue_id: str, split: str,
                   capture_id: str | None = None) -> list[ImageRecord]:
    if split not in SPLITS:
        raise ValueError(f"split must be one of {SPLITS}")
    report = json.loads(Path(report_path).read_text(encoding="utf-8"))
    admitted = admissible_frames(report)
    report_sha = sha256_file(Path(report_path))
    existing = {r.image_id for r in read_images(dataset / "images.jsonl")}
    group = f"capture:{capture_id or run_id}"
    records = []
    from PIL import Image

    with tempfile.TemporaryDirectory() as tmp:
        archive.unpack(Path(frames_tar), Path(tmp))
        files = sorted(p for p in Path(tmp).rglob("*") if p.suffix.lower() in IMAGE_SUFFIXES)
        unlisted = [p.name for p in files if p.name not in admitted]
        if unlisted:
            raise PrivacyGateError(f"{len(unlisted)} frame(s) in the archive are not in the privacy report, e.g. {unlisted[:3]}")
        dest = dataset / "images" / "captures" / run_id
        dest.mkdir(parents=True, exist_ok=True)
        for f in files:
            image_id = f"{run_id}/{f.name}"
            if image_id in existing:
                continue
            shutil.copyfile(f, dest / f.name)
            digest = sha256_file(dest / f.name)
            with Image.open(dest / f.name) as im:
                width, height = im.size
            frame = admitted[f.name]
            records.append(ImageRecord(
                image_id=image_id, relative_path=f"captures/{run_id}/{f.name}", width=width, height=height,
                venue_id=venue_id, source_frame=f.name, split=split, group_id=group, content_sha256=digest, origin="capture",
                privacy={"status": "ANONYMISED", "frame_archive_kind": "FRAME_ARCHIVE_ANON", "policy": report["policy"],
                         "privacy_report_sha256": report_sha, "anonymised_frame_sha256": digest, "run_id": run_id,
                         "masked_fraction": frame.get("masked_fraction"), "detectors": report.get("detectors")}))
    append_images(dataset / "images.jsonl", records)
    return records


def assign_split(group_id: str, *, seed: str, ratios: tuple[float, float, float] = (0.7, 0.15, 0.15)) -> str:
    """Pure: a deterministic split for a whole group (hash of seed and group), so re-running an import never moves a
    group between splits."""
    if abs(sum(ratios) - 1.0) > 1e-9 or min(ratios) < 0:
        raise ValueError("ratios must be non-negative and sum to 1")
    u = int(hashlib.sha256(f"{seed}:{group_id}".encode()).hexdigest()[:12], 16) / float(16 ** 12)
    if u < ratios[0]:
        return "train"
    return "val" if u < ratios[0] + ratios[1] else "test"


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dataset", type=Path, required=True)
    ap.add_argument("--frames", type=Path, required=True, help="FRAME_ARCHIVE_ANON of the run")
    ap.add_argument("--privacy-report", type=Path, required=True)
    ap.add_argument("--run-id", required=True)
    ap.add_argument("--venue-id", required=True)
    ap.add_argument("--capture-id")
    ap.add_argument("--split", choices=SPLITS, required=True)
    args = ap.parse_args(argv)
    records = import_capture(args.dataset, args.frames, args.privacy_report, run_id=args.run_id, venue_id=args.venue_id,
                             split=args.split, capture_id=args.capture_id)
    print(f"imported {len(records)} anonymised frame(s) into {args.dataset} ({args.split})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
