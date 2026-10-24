"""A REAL, permissively licensed multi-view dataset for the GPU acceptance test, prepared reproducibly.

The manifest (tests/gpu/datasets/korno-climbing-rock-24.json) names 24 CC0 photographs on Wikimedia Commons by URL and
SHA-1. `prepare` downloads them (verifying each SHA-1), derives 1600x900 frames, runs the real COLMAP (feature
extraction, exhaustive matching, the incremental mapper) and writes a COLMAP project that tests/gpu/splat_dataset.py
`from_colmap_project` reads -- exactly what CHAYA_SPLAT_DATASET points the acceptance test at:

    <out>/images/*.jpg   <out>/sparse/0/{cameras,images,points3D}.bin   <out>/dataset.json (provenance, hashes, COLMAP)

    python -m tests.gpu.real_dataset prepare --out <dir> [--originals <dir>] [--colmap colmap] [--threads 4]
    python -m tests.gpu.real_dataset validate <dir>

Nothing here is synthetic, and nothing is substituted: a file whose SHA-1 does not match is refused, and a project that
COLMAP could not reconstruct (too few registered images) is refused, never patched up.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

MANIFEST = Path(__file__).resolve().parent / "datasets" / "korno-climbing-rock-24.json"
USER_AGENT = "chaya-02-gpu-acceptance/1.0 (dataset preparation; https://github.com/yawayapnwork/Chaya-02)"
MIN_REGISTERED_SHARE = 0.75  # below this the project is refused: too few views for a meaningful splat


class DatasetError(Exception):
    """The dataset cannot be used: a code (DATASET_CHECKSUM_MISMATCH, DATASET_INVALID, DATASET_RECONSTRUCTION_FAILED)
    and a message saying what to do."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(f"{code}: {message}")
        self.code = code


def load_manifest(path: Path = MANIFEST) -> dict:
    m = json.loads(path.read_text(encoding="utf-8"))
    if m.get("license") != "CC0-1.0" or not m.get("files"):
        raise DatasetError("DATASET_INVALID", f"{path.name}: expected a CC0-1.0 manifest with files")
    for f in m["files"]:
        if f.get("license") != "CC0" or len(f.get("sha1", "")) != 40 or not f.get("url", "").startswith("https://upload.wikimedia.org/"):
            raise DatasetError("DATASET_INVALID", f"{f.get('file')}: each file needs licence CC0, a SHA-1 and a Commons upload URL")
    return m


def _sha(path: Path, algo: str) -> str:
    h = hashlib.new(algo)
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def download(manifest: dict, dest: Path, opener=urllib.request.urlopen, attempts: int = 3) -> list[Path]:
    """Every original, verified by its Commons SHA-1. Existing files that verify are kept (resumable)."""
    dest.mkdir(parents=True, exist_ok=True)
    paths = []
    for f in manifest["files"]:
        if not f["url"].startswith("https://upload.wikimedia.org/"):
            raise DatasetError("DATASET_INVALID", f"{f['file']}: only Wikimedia Commons https URLs are fetched, not {f['url']!r}")
        path = dest / f["file"]
        if not (path.is_file() and _sha(path, "sha1") == f["sha1"]):
            for attempt in range(1, attempts + 1):
                tmp = path.with_suffix(path.suffix + ".part")
                try:
                    request = urllib.request.Request(f["url"], headers={"User-Agent": USER_AGENT})  # noqa: S310 - https Commons URL, checked above
                    with opener(request, timeout=120) as r, tmp.open("wb") as out:
                        while chunk := r.read(1 << 20):
                            out.write(chunk)
                    tmp.replace(path)
                    break
                except OSError:
                    if attempt == attempts:
                        raise
                    time.sleep(5 * attempt)
            got = _sha(path, "sha1")
            if got != f["sha1"]:
                path.unlink()
                raise DatasetError("DATASET_CHECKSUM_MISMATCH", f"{f['file']}: SHA-1 {got}, the manifest says {f['sha1']}; "
                                   "the file on Commons changed or the download was corrupted")
        paths.append(path)
    return paths


def derive(manifest: dict, originals: Path, project: Path) -> list[dict]:
    """The training frames: each original resized with INTER_AREA and written as JPEG (the format the pipeline's frame
    archives use). Returns each frame's name, size and SHA-256 for dataset.json."""
    import cv2  # noqa: PLC0415

    d = manifest["derived"]
    images = project / "images"
    images.mkdir(parents=True, exist_ok=True)
    out = []
    for f in manifest["files"]:
        img = cv2.imread(str(originals / f["file"]), cv2.IMREAD_COLOR)
        if img is None or img.shape[1] != f["width"] or img.shape[0] != f["height"]:
            raise DatasetError("DATASET_INVALID", f"{f['file']} does not decode to {f['width']}x{f['height']}")
        small = cv2.resize(img, (d["width"], d["height"]), interpolation=cv2.INTER_AREA)
        name = f"korno-{f['file'].rsplit(' ', 1)[-1]}"  # "korno-25.jpg"
        cv2.imwrite(str(images / name), small, [cv2.IMWRITE_JPEG_QUALITY, 95])
        out.append({"name": name, "source": f["file"], "source_sha1": f["sha1"],
                    "source_sha256": _sha(originals / f["file"], "sha256"), "sha256": _sha(images / name, "sha256"),
                    "width": d["width"], "height": d["height"]})
    return out


def reconstruct(project: Path, colmap: str, *, threads: int = 4, run=subprocess.run) -> dict:
    """Real COLMAP, CPU flags (no CUDA needed; the GPU host runs the same commands): SIFT on every frame with one shared
    SIMPLE_RADIAL camera, exhaustive matching, the incremental mapper. The mapper's model 0 becomes sparse/0."""
    db, raw = project / "database.db", project / "sparse-raw"
    raw.mkdir(parents=True, exist_ok=True)
    steps = [
        [colmap, "feature_extractor", "--database_path", str(db), "--image_path", str(project / "images"),
         "--ImageReader.single_camera", "1", "--ImageReader.camera_model", "SIMPLE_RADIAL",
         "--SiftExtraction.use_gpu", "0", "--SiftExtraction.num_threads", str(threads)],
        [colmap, "exhaustive_matcher", "--database_path", str(db), "--SiftMatching.use_gpu", "0",
         "--SiftMatching.num_threads", str(threads)],
        [colmap, "mapper", "--database_path", str(db), "--image_path", str(project / "images"), "--output_path", str(raw),
         "--Mapper.num_threads", str(threads)],
    ]
    timings = []
    for argv in steps:
        t0 = time.time()
        res = run(argv, capture_output=True, text=True)
        timings.append({"argv": argv[1], "exit_status": res.returncode, "seconds": round(time.time() - t0, 1)})
        if res.returncode != 0:
            raise DatasetError("DATASET_RECONSTRUCTION_FAILED", f"colmap {argv[1]} exited {res.returncode}: {res.stderr[-500:]}")
    models = sorted(p for p in raw.iterdir() if p.is_dir())
    if not models:
        raise DatasetError("DATASET_RECONSTRUCTION_FAILED", "the COLMAP mapper produced no model")
    sparse = project / "sparse" / "0"
    sparse.parent.mkdir(parents=True, exist_ok=True)
    models[0].replace(sparse)
    version = run([colmap, "help"], capture_output=True, text=True).stdout.strip().splitlines()[:1]
    return {"colmap": version[0] if version else None, "models": len(models), "steps": timings}


def _registered(project: Path, colmap: str, run=subprocess.run) -> dict:
    txt = project / "sparse-txt"
    txt.mkdir(exist_ok=True)
    res = run([colmap, "model_converter", "--input_path", str(project / "sparse" / "0"), "--output_path", str(txt),
               "--output_type", "TXT"], capture_output=True, text=True)
    if res.returncode != 0:
        raise DatasetError("DATASET_INVALID", f"colmap model_converter could not read sparse/0: {res.stderr[-300:]}")
    images = [ln for ln in (txt / "images.txt").read_text(encoding="utf-8").splitlines() if ln and not ln.startswith("#")]
    points = [ln for ln in (txt / "points3D.txt").read_text(encoding="utf-8").splitlines() if ln and not ln.startswith("#")]
    cameras = [ln for ln in (txt / "cameras.txt").read_text(encoding="utf-8").splitlines() if ln and not ln.startswith("#")]
    return {"registered_images": len(images) // 2, "points3d": len(points), "cameras": [c.split()[1] for c in cameras]}


def validate(project: Path) -> dict:
    """A project the acceptance test can use, or DatasetError saying what is wrong. Needs no COLMAP."""
    if not (project / "images").is_dir():
        raise DatasetError("DATASET_INVALID", f"{project}: no images/ directory")
    for name in ("cameras.bin", "images.bin", "points3D.bin"):
        if not (project / "sparse" / "0" / name).is_file():
            raise DatasetError("DATASET_INVALID", f"{project}: sparse/0/{name} is missing (run prepare)")
    meta_path = project / "dataset.json"
    if not meta_path.is_file():
        raise DatasetError("DATASET_INVALID", f"{project}: dataset.json (provenance) is missing")
    meta = json.loads(meta_path.read_text(encoding="utf-8"))
    for frame in meta["frames"]:
        path = project / "images" / frame["name"]
        if not path.is_file():
            raise DatasetError("DATASET_INVALID", f"{frame['name']} is listed in dataset.json but missing from images/")
        if _sha(path, "sha256") != frame["sha256"]:
            raise DatasetError("DATASET_CHECKSUM_MISMATCH", f"{frame['name']} differs from the frame dataset.json recorded")
    reg = meta["reconstruction"]["registered_images"]
    if reg < MIN_REGISTERED_SHARE * len(meta["frames"]):
        raise DatasetError("DATASET_RECONSTRUCTION_FAILED", f"only {reg} of {len(meta['frames'])} frames registered "
                           f"(< {MIN_REGISTERED_SHARE:.0%})")
    return meta


def prepare(out: Path, originals: Path, colmap: str, threads: int) -> dict:
    manifest = load_manifest()
    t0 = time.time()
    download(manifest, originals)
    download_s = round(time.time() - t0, 1)
    frames = derive(manifest, originals, out)
    recon = reconstruct(out, colmap, threads=threads)
    recon.update(_registered(out, colmap))
    meta = {"name": manifest["name"], "synthetic": False, "source": manifest["source"], "author": manifest["author"],
            "license": manifest["license"], "license_url": manifest["license_url"], "manifest_sha256": _sha(MANIFEST, "sha256"),
            "camera_assumptions": manifest["camera_assumptions"], "frames": frames, "derived": manifest["derived"],
            "reconstruction": recon, "prepared_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "download_seconds": download_s}
    (out / "dataset.json").write_text(json.dumps(meta, indent=1), encoding="utf-8")
    return validate(out)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("prepare")
    p.add_argument("--out", type=Path, required=True)
    p.add_argument("--originals", type=Path, help="where the full-size originals are cached (default: <out>/../originals)")
    p.add_argument("--colmap", default="colmap")
    p.add_argument("--threads", type=int, default=4, help="COLMAP threads; each needs memory (docs/pipeline.md)")
    v = sub.add_parser("validate")
    v.add_argument("project", type=Path)
    args = ap.parse_args(argv)
    try:
        if args.cmd == "prepare":
            meta = prepare(args.out, args.originals or args.out.parent / "originals", args.colmap, args.threads)
        else:
            meta = validate(args.project)
    except DatasetError as exc:
        print(json.dumps({"ok": False, "code": exc.code, "message": str(exc)}), file=sys.stderr)
        return 2
    r = meta["reconstruction"]
    print(json.dumps({"ok": True, "name": meta["name"], "license": meta["license"], "frames": len(meta["frames"]),
                      "registered_images": r["registered_images"], "points3d": r["points3d"], "cameras": r["cameras"],
                      "colmap": r.get("colmap")}, indent=1))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
