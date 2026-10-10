"""GPU ACCEPTANCE TEST of the production splat path: the real SPLAT_RECONSTRUCTION stage (gsplat on CUDA, COLMAP, the
orchestrator), then the real ARTIFACT_GENERATION, then the pinned viewer library loading the result.

Run it on a GPU worker (docs/pipeline.md, "GPU acceptance"):

    docker build -f services/reconstruction/Dockerfile.gpu -t chaya-worker-gpu services/reconstruction
    docker run --rm --gpus all --user 0 -e CHAYA_REQUIRE_GPU=1 -e CHAYA_GPU_ACCEPTANCE_REPORT=/out/gpu-acceptance.json \
      -v "$PWD/services/reconstruction/tests:/src/tests:ro" -v "$PWD/gpu-acceptance:/out" -w /src chaya-worker-gpu \
      sh -c "pip install pytest==8.4.1 && python -m pytest -p no:cacheprovider -m gpu tests/gpu/test_splat_acceptance.py -v -rs -s"

(--user 0 only so pip can add pytest to the image's root-owned venv for the test run.)

The dataset is CHAYA_SPLAT_DATASET (a REAL COLMAP project: images/ and sparse/0) when set. Otherwise it is the SYNTHETIC
rendered room corner (tests/gpu/splat_dataset.py), and the report says which one was used.

With CHAYA_REQUIRE_GPU=1, a missing requirement FAILS the test with the preflight report instead of skipping it. Use it
on a GPU worker, where a skip would hide a broken image. Without it the test skips, and the skip is not a pass. A skipped
or unrun acceptance test means GPU training is NOT VALIDATED.

It asserts:
  * training ran on a CUDA device chosen by preflight, with the pinned gsplat, every iteration, and density control;
  * it learned: the mean PSNR of the trained cloud over all training views, rendered with gsplat, beats the untrained
    SfM seed by at least MIN_PSNR_GAIN_DB;
  * a second job resumes exactly from the first job's checkpoint;
  * a corrupt checkpoint fails fast with CHECKPOINT_CORRUPT and publishes nothing;
  * the published SPLAT becomes a .ksplat that passes the viewer contract, with matching checksums, manifest and bundle
    (tests/splat/viewer_artifacts.py), and loads in the pinned viewer library when node is available.
It writes device, versions, timings, sizes and validation results to CHAYA_GPU_ACCEPTANCE_REPORT (default: under tmp).
"""

from __future__ import annotations

import json
import os
import time
from dataclasses import replace
from pathlib import Path

import numpy as np
import pytest

from chaya_worker import splat_preflight
from chaya_worker.runner import CommandRunner
from chaya_worker.toolchain import Toolchain
from tests.conftest import DERIVED_BUCKET, Harness

pytestmark = pytest.mark.gpu

MIN_PSNR_GAIN_DB = 3.0
ITERATIONS = 1500
RESUMED_ITERATIONS = 1800


def _preflight() -> dict:
    return splat_preflight.environment_report(Toolchain(), min_compute_capability=7.0,
                                              lock=splat_preflight.load_lock(splat_preflight.DEFAULT_LOCK_PATH))


@pytest.fixture(scope="module")
def preflight():
    report = _preflight()
    if not report["ok"]:
        why = "; ".join(f"{p['code']}: {p['message']}" for p in report["problems"])
        if os.environ.get("CHAYA_REQUIRE_GPU") == "1":
            pytest.fail(f"CHAYA_REQUIRE_GPU=1 but this worker cannot train: {why}\n{json.dumps(report, indent=2, default=str)}")
        pytest.skip(f"NOT RUN, GPU training is not validated here: {why}")
    return report


def _input(h: Harness, kind: str, path: Path, content_type: str) -> dict:
    ref = h.raw_input(kind, path, content_type)
    ref["containsPii"] = False
    return ref


def _dataset(tmp: Path):
    from tests.gpu import splat_dataset

    project = os.environ.get("CHAYA_SPLAT_DATASET")
    if project:
        runner = CommandRunner(tmp / "dataset-out.log", tmp / "dataset-err.log")
        return splat_dataset.from_colmap_project(Path(project), tmp / "dataset", Toolchain().colmap().path,
                                                 lambda argv: runner.run(argv, error_code="DATASET", timeout=600))
    return splat_dataset.rendered_room_corner(tmp / "dataset")


def _psnr(a: np.ndarray, b: np.ndarray) -> float:
    mse = float(np.mean((a.astype(np.float64) - b.astype(np.float64)) ** 2))
    return 10 * np.log10(1.0 / max(mse, 1e-12))


def _mean_psnr(params, dataset, cams, device) -> float:
    """Every training view rendered with gsplat (the production adapter) against its ground-truth frame."""
    import torch

    from chaya_worker import splat_training as st

    render = st.gsplat_rasterizer(device)
    values = []
    with torch.no_grad():
        inputs = st.render_inputs(params)
        for cam in cams:
            out = render(*inputs, cam)
            values.append(_psnr(out.image.clamp(0, 1).cpu().numpy(), dataset.images[cam["name"]]))
    return float(np.mean(values))


def _params_from_ply(path: Path, device):
    import torch

    from chaya_worker.ply import read_ply

    c = read_ply(path)
    t = lambda a: torch.tensor(np.asarray(a), dtype=torch.float32, device=device)  # noqa: E731
    return {"means": t(c.positions), "scales_log": t(c.scales_log), "quats": t(c.rotations_wxyz), "opacity_logit": t(c.opacity_logit),
            "sh0": t(c.colors_dc)}


def test_gsplat_training_on_cuda_produces_a_validated_viewer_artifact(preflight, tmp_path):
    import torch

    from chaya_worker.splat_color import rgb_bytes_to_sh0
    from chaya_worker.stages.splat_reconstruction import initial_scale_log
    from tests.splat.viewer_artifacts import (
        generate_and_verify,
        prepare_viewer_check,
        viewer_library_available,
        viewer_library_check,
    )

    summary: dict = {"preflight": preflight, "started_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())}
    t0 = time.time()
    dataset = _dataset(tmp_path)
    summary["dataset"] = {"name": dataset.name, "synthetic": dataset.synthetic, "frames": len(dataset.images), "sfm_points": dataset.points,
                          "prepare_seconds": round(time.time() - t0, 3)}

    h = Harness(tmp_path, gsplat_iterations=ITERATIONS, gsplat_densify_start=100, gsplat_densify_stop=1200, gsplat_densify_every=100,
                gsplat_opacity_reset_every=1000, gsplat_checkpoint_every=500, gsplat_keyframe_every=500)
    inputs = [_input(h, "SPARSE_MODEL", dataset.sparse_tar, "application/x-tar"),
              _input(h, "POSES", dataset.poses_json, "application/json"),
              _input(h, "FRAME_ARCHIVE_ANON", dataset.frames_tar, "application/x-tar"),
              _input(h, "PRIVACY_MASKS", dataset.masks_tar, "application/x-tar")]

    # 1. train
    t0 = time.time()
    report = h.run(h.order("SPLAT_RECONSTRUCTION", inputs))
    summary["training_job_seconds"] = round(time.time() - t0, 3)
    assert report["status"] == "SUCCEEDED", f"{report['errorCode']}: {report['errorMessage']}\n{report.get('errorDetails')}"
    arts = {a["kind"]: a for a in report["artifacts"]}
    assert {"SPLAT", "SPLAT_CHECKPOINT", "SPLAT_TRAINING_REPORT", "KEYFRAME_RENDERS", "TRAINING_CAMERAS"} <= set(arts)
    training = json.loads((h.storage.root / DERIVED_BUCKET / arts["SPLAT_TRAINING_REPORT"]["key"]).read_text())
    versions = training["provenance"]["versions"]
    assert training["status"] == "COMPLETED" and training["completed_iterations"] == ITERATIONS
    assert training["validation"]["valid"] and training["densification"]
    assert versions["training_device"]["index"] == preflight["selected_device"]["index"]
    assert versions["gsplat"] == splat_preflight.GSPLAT_VALIDATED_VERSION and versions["torch_cuda"]
    losses = [e["loss"] for e in training["loss_history"]]
    summary["training"] = {"device": versions["training_device"], "versions": {k: versions.get(k) for k in
                           ("torch", "torch_cuda", "gsplat", "gsplat_backend")}, "lock": versions.get("lock"),
                           "training_seconds": versions["training_seconds"], "iterations": training["completed_iterations"],
                           "initial_gaussians": training["initial_gaussian_count"], "final_gaussians": training["gaussian_count"],
                           "first_loss": losses[0], "last_loss": losses[-1]}

    # 2. it learned: trained cloud vs the untrained SfM seed, over every training view, rendered by gsplat
    device = torch.device("cuda", preflight["selected_device"]["index"])
    if dataset.cameras:
        from chaya_worker import splat_training as st

        cams = [{**c} for c in dataset.cameras]
        trained = _params_from_ply(h.storage.root / DERIVED_BUCKET / arts["SPLAT"]["key"], device)
        txt_points = _seed_points(dataset.sparse_tar, tmp_path / "seed")
        seed = st.init_params(txt_points["xyz"], rgb_bytes_to_sh0(txt_points["rgb"]), initial_scale_log(txt_points["xyz"]), device)
        psnr_trained, psnr_seed = _mean_psnr(trained, dataset, cams, device), _mean_psnr(seed, dataset, cams, device)
        summary["quality"] = {"mean_psnr_trained_db": round(psnr_trained, 3), "mean_psnr_sfm_seed_db": round(psnr_seed, 3),
                              "views": len(cams), "note": "training views of the dataset; not a held-out evaluation"}
        assert psnr_trained >= psnr_seed + MIN_PSNR_GAIN_DB, summary["quality"]

    # 3. resume from the first job's checkpoint
    h.settings = replace(h.settings, gsplat_iterations=RESUMED_ITERATIONS)
    checkpoint = [i for i in h.outputs_as_inputs(report) if i["kind"] == "SPLAT_CHECKPOINT"]
    resumed = h.run(h.order("SPLAT_RECONSTRUCTION", inputs + checkpoint, attempt=2))
    assert resumed["status"] == "SUCCEEDED", f"{resumed['errorCode']}: {resumed['errorMessage']}"
    resumed_report = json.loads((h.storage.root / DERIVED_BUCKET / next(a for a in resumed["artifacts"]
                                                                        if a["kind"] == "SPLAT_TRAINING_REPORT")["key"]).read_text())
    assert resumed_report["resumed_from_iteration"] == ITERATIONS and resumed_report["completed_iterations"] == RESUMED_ITERATIONS
    summary["resume"] = {"from": ITERATIONS, "to": RESUMED_ITERATIONS, "gaussians": resumed_report["gaussian_count"]}

    # 4. a corrupt checkpoint fails fast, publishes nothing
    bad = tmp_path / "corrupt.pt"
    bad.write_bytes(b"not a checkpoint")
    t0 = time.time()
    corrupt = h.run(h.order("SPLAT_RECONSTRUCTION", inputs + [_input(h, "SPLAT_CHECKPOINT", bad, "application/octet-stream")], attempt=3))
    assert corrupt["status"] == "FAILED" and corrupt["errorCode"] == "CHECKPOINT_CORRUPT" and corrupt["artifacts"] == []
    summary["corrupt_checkpoint"] = {"code": corrupt["errorCode"], "seconds": round(time.time() - t0, 3)}

    # 5. the viewer artifact
    viewer = generate_and_verify(h, report)
    summary["artifacts"] = {k: viewer[k] for k in ("gaussian_count", "splat_bytes", "ksplat_bytes", "manifest_bytes", "bundle_bytes",
                                                    "ksplat_sha256", "splat_sha256", "ksplat_format")}
    out = Path(os.environ.get("CHAYA_GPU_ACCEPTANCE_REPORT", tmp_path / "gpu-acceptance.json"))
    out.parent.mkdir(parents=True, exist_ok=True)
    check_dir = out.parent / "viewer-check"  # kept with the report, so the check can also be run where node is
    why = viewer_library_available()
    if why is None:
        summary["viewer_library_check"] = viewer_library_check(viewer["splat"], viewer["ksplat"], check_dir)
    else:
        prepare_viewer_check(viewer["splat"], viewer["ksplat"], check_dir)
        summary["viewer_library_check"] = (f"NOT RUN here ({why}). Run it on the trained artifact: cd apps/web && node "
                                           f"--experimental-strip-types scripts/verify-ksplat-artifact.ts <{check_dir.name} directory>")
    out.write_text(json.dumps(summary, indent=2, default=str), encoding="utf-8")
    print(f"\nGPU acceptance report: {out}\n{json.dumps(summary, indent=2, default=str)}")


def _seed_points(sparse_tar: Path, work: Path) -> dict:
    """The SfM points the stage seeded from, read through the real COLMAP converter as the stage reads them."""
    from chaya_worker import archive
    from chaya_worker.colmap_txt import parse_points3d_txt

    sparse, txt = work / "bin", work / "txt"
    archive.unpack(sparse_tar, sparse)
    txt.mkdir(parents=True)
    CommandRunner(work / "o.log", work / "e.log").run([Toolchain().colmap().path, "model_converter", "--input_path", str(sparse),
                                                      "--output_path", str(txt), "--output_type", "TXT"], error_code="SEED", timeout=600)
    p = parse_points3d_txt((txt / "points3D.txt").read_text(encoding="utf-8"))
    return {"xyz": np.array(p["xyz"], dtype=np.float32), "rgb": np.array(p["rgb"])}
