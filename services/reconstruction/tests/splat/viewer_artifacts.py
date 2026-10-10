"""From a published SPLAT to the viewer's files, checked end to end. Shared by the CPU smoke test
(tests/smoke/test_splat_artifact_smoke.py) and the GPU acceptance test (tests/gpu/test_splat_acceptance.py).

`generate_and_verify` runs the real ARTIFACT_GENERATION stage through the orchestrator on the SPLAT a training job
published, then checks:
  * upload and read-back integrity: every stored object of both jobs hashes to the sha256 and size its report claims;
  * the .ksplat against the shared viewer contract (chaya_worker.ksplat.validate_file); its splat count is the trained
    cloud's;
  * the coordinate and colour conventions: every centre is the trained position bit for bit (reconstruction frame, no
    transform applied); every RGBA byte is floor((0.5 + SH_C0 * f_dc) * 255) and floor(sigmoid(opacity) * 255),
    computed here from the PLY independently of the encoder;
  * the manifest: the trained SPLAT is named with its checksum as the upstream input, the KSPLAT with the stored file's
    checksum, and the coordinate space is RECONSTRUCTION;
  * the viewer bundle carries the identical .ksplat.

`viewer_library_check` loads the same files with the pinned viewer library itself (apps/web/scripts/verify-ksplat-artifact.ts).
"""

from __future__ import annotations

import hashlib
import json
import shutil
import subprocess
import tarfile
from pathlib import Path
from typing import Any

import numpy as np

from chaya_worker import ksplat
from chaya_worker.ply import read_ply
from tests.conftest import DERIVED_BUCKET

WEB = Path(__file__).resolve().parents[4] / "apps" / "web"
SH_C0 = 0.28209479177387814


def _sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def stored(harness, artifact: dict[str, Any]) -> Path:
    return harness.storage.root / DERIVED_BUCKET / artifact["key"]


def check_read_back(harness, report: dict[str, Any]) -> None:
    for a in report["artifacts"]:
        path = stored(harness, a)
        assert path.is_file(), f"{a['kind']} was reported but not stored"
        assert path.stat().st_size == a["sizeBytes"], f"{a['kind']}: stored size differs from the report"
        assert _sha(path) == a["sha256"], f"{a['kind']}: stored bytes do not hash to the reported sha256"


def expected_rgba(cloud) -> np.ndarray:
    rgb = np.clip(np.floor((0.5 + SH_C0 * cloud.colors_dc.astype(np.float64)) * 255.0), 0, 255)
    alpha = np.clip(np.floor(255.0 / (1.0 + np.exp(-cloud.opacity_logit.astype(np.float64)))), 0, 255)
    return np.concatenate([rgb, alpha[:, None]], axis=1).astype(np.uint8)


def generate_and_verify(harness, splat_report: dict[str, Any]) -> dict[str, Any]:
    check_read_back(harness, splat_report)
    splat = next(a for a in splat_report["artifacts"] if a["kind"] == "SPLAT")
    splat_path = stored(harness, splat)
    cloud = read_ply(splat_path)
    assert len(cloud) > 0, "the trained SPLAT is empty"

    inputs = [i for i in harness.outputs_as_inputs(splat_report) if i["kind"] == "SPLAT"]
    report = harness.run(harness.order("ARTIFACT_GENERATION", inputs))
    assert report["status"] == "SUCCEEDED", report["errorMessage"]
    check_read_back(harness, report)
    by_kind = {a["kind"]: a for a in report["artifacts"]}
    assert {"KSPLAT", "ARTIFACT_MANIFEST", "VIEWER_BUNDLE"} <= set(by_kind)

    ksplat_path = stored(harness, by_kind["KSPLAT"])
    fmt = ksplat.validate_file(ksplat_path)
    assert fmt["splatCount"] == len(cloud) and fmt["contract"] == ksplat.VIEWER_LIBRARY
    decoded = ksplat.decode(ksplat_path.read_bytes())
    assert np.array_equal(decoded["centers"], cloud.positions), "centres must be the trained positions, untransformed"
    assert np.array_equal(decoded["rgba"], expected_rgba(cloud)), "colour/opacity bytes break the viewer's convention"

    manifest = json.loads(stored(harness, by_kind["ARTIFACT_MANIFEST"]).read_text(encoding="utf-8"))
    assert manifest["coordinateSpace"] == "RECONSTRUCTION"
    entries = {(e["kind"], e["checksum"]["value"]) for e in manifest["artifacts"] if e["checksum"]["algorithm"] == "sha256"}
    assert ("SPLAT", splat["sha256"]) in entries, "the manifest names the trained SPLAT by its checksum"
    assert ("KSPLAT", _sha(ksplat_path)) in entries, "the manifest names the stored .ksplat by its checksum"

    with tarfile.open(stored(harness, by_kind["VIEWER_BUNDLE"])) as tar:
        member = tar.extractfile("scene.ksplat")
        assert member is not None and member.read() == ksplat_path.read_bytes(), "the bundle carries the identical .ksplat"

    return {"splat": splat_path, "ksplat": ksplat_path, "gaussian_count": len(cloud), "splat_bytes": splat_path.stat().st_size,
            "ksplat_bytes": ksplat_path.stat().st_size, "manifest_bytes": by_kind["ARTIFACT_MANIFEST"]["sizeBytes"],
            "bundle_bytes": by_kind["VIEWER_BUNDLE"]["sizeBytes"], "ksplat_format": fmt,
            "ksplat_sha256": by_kind["KSPLAT"]["sha256"], "splat_sha256": splat["sha256"]}


def viewer_library_available() -> str | None:
    """Why the pinned viewer library cannot be run here, or None when it can."""
    if shutil.which("node") is None:
        return "node is not installed"
    if not (WEB / "node_modules" / "@mkkellogg" / "gaussian-splats-3d").is_dir():
        return "apps/web dependencies are not installed (npm ci in apps/web)"
    return None


def prepare_viewer_check(splat_path: Path, ksplat_path: Path, workdir: Path) -> Path:
    """The directory apps/web/scripts/verify-ksplat-artifact.ts reads: splat.ply, scene.ksplat and cloud.json."""
    cloud = read_ply(splat_path)
    workdir.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(splat_path, workdir / "splat.ply")
    shutil.copyfile(ksplat_path, workdir / "scene.ksplat")
    (workdir / "cloud.json").write_text(json.dumps({
        "positions": cloud.positions.tolist(), "scales_log": cloud.scales_log.tolist(), "rotations_wxyz": cloud.rotations_wxyz.tolist(),
        "opacity_logit": cloud.opacity_logit.tolist(), "colors_dc": cloud.colors_dc.tolist()}), encoding="utf-8")
    return workdir


def viewer_library_check(splat_path: Path, ksplat_path: Path, workdir: Path) -> dict[str, Any]:
    """Loads the artifact with the pinned library's KSplatLoader and PlyLoader; returns the script's JSON summary."""
    prepare_viewer_check(splat_path, ksplat_path, workdir)
    out = subprocess.run([shutil.which("node"), "--experimental-strip-types", "--no-warnings", "scripts/verify-ksplat-artifact.ts",
                          str(workdir)], cwd=WEB, capture_output=True, text=True, timeout=300)
    lines = [ln for ln in out.stdout.splitlines() if ln.startswith("{")]
    assert lines, f"the viewer check printed no result (exit {out.returncode}): {out.stderr[-2000:]}"
    result = json.loads(lines[-1])
    assert out.returncode == 0 and result["ok"], f"the pinned viewer library rejects the artifact: {result}"
    return result
