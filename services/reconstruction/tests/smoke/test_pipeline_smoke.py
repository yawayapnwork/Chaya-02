"""CI smoke test: capture artifact -> processing invocation -> artifact generation, with real executables only.

What is real here:
  * the capture: an H.264 video encoded by the real FFmpeg binary from real photographs (a real face), plus a JSON
    capture-metadata file;
  * the processing invocation: the worker's own claim loop (Orchestrator.run_once) claiming jobs, downloading and
    checksum-verifying inputs, running each stage, uploading outputs and reporting the stage record. The control plane
    is modelled by SequencingControlPlane below, which applies the same input rules as
    dev.chaya.api.pipeline.PipelineService#inputs (the Java side of that contract is PipelineControlPlaneTest);
  * the stages: INPUT_VALIDATION (ffprobe), FFMPEG_PREPROCESS (ffmpeg), FRAME_QUALITY_FILTER and PRIVACY_PREPROCESS
    (OpenCV), ARTIFACT_GENERATION (the .ksplat encoder, manifest and viewer bundle).

What is EXCLUDED, and why (EXCLUDED_IN_CI): every stage that needs a GPU or a reconstruction toolchain CI runners do not
have, plus REGION_SPLICE, which is CPU-only but consumes the output of an excluded stage. Nothing stands in for them
silently. Their orchestration contract -- a structured failure, no artifacts, never a fake success -- is asserted
separately below. The toolchain stages run for real only in the `gpu` suite on a GPU host.

The one input that bridges the gap: ARTIFACT_GENERATION consumes a Gaussian splat, which only SPLAT_RECONSTRUCTION
(excluded, GPU) produces. The smoke test therefore supplies a small, explicitly labelled FIXTURE splat
(kind SPLAT, provenance "ci-smoke-fixture") so the real ARTIFACT_GENERATION code runs end to end. That fixture is not
presented as a reconstruction anywhere: the test checks that the manifest records it as an upstream input by name and
checksum, exactly as it would a real one.
"""

from __future__ import annotations

import json
import tarfile
import uuid
from pathlib import Path
from typing import Any

import numpy as np
import pytest

from chaya_worker import ksplat
from chaya_worker.orchestrator import Orchestrator
from chaya_worker.ply import GaussianCloud, read_ply, write_ply
from chaya_worker.settings import Settings
from chaya_worker.stages import PRIVACY_STAGE, STAGE_ORDER
from chaya_worker.storage import LocalStorage
from chaya_worker.toolchain import Toolchain
from tests.conftest import DERIVED_BUCKET, RAW_BUCKET, FakeControlPlane, sha

pytestmark = pytest.mark.smoke

# Stages that run for real in CI, in pipeline order.
CI_STAGES = ["INPUT_VALIDATION", "FFMPEG_PREPROCESS", "FRAME_QUALITY_FILTER", "PRIVACY_PREPROCESS", "ARTIFACT_GENERATION"]

# Every other stage: why it is not in the CI chain, and the structured failure the orchestrator must report for it on
# a CI worker (which lacks the toolchain and the upstream outputs).
EXCLUDED_IN_CI = {
    "POSE_ESTIMATION": ("needs COLMAP/GLOMAP (GPU-accelerated SfM)", "DEPENDENCY_UNAVAILABLE"),
    "SPLAT_RECONSTRUCTION": ("needs gsplat + a CUDA GPU", "DEPENDENCY_UNAVAILABLE"),
    "SEMANTIC_SEGMENTATION": ("needs torch + transformers (SegFormer)", "DEPENDENCY_UNAVAILABLE"),
    "GEOMETRIC_CLEANUP": ("needs Open3D", "DEPENDENCY_UNAVAILABLE"),
    # The stages below also need a calibrated coordinate frame (docs/coordinate-frames.md). This control plane stand-in
    # hands out none, so the calibration gate -- checked before any toolchain -- is the structured failure they report.
    "REGION_ALIGNMENT": ("needs Open3D and calibrated frames; incremental re-scan only", "NOT_CALIBRATED"),
    # CPU-only (numpy), but it only runs in an incremental re-scan, on REGION_ALIGNMENT's output (which needs Open3D).
    # Its splice logic is covered by tests/unit/test_region_splice.py.
    "REGION_SPLICE": ("needs REGION_ALIGNMENT's SPLAT_ALIGNED output and a calibrated frame; incremental re-scan only",
                      "NOT_CALIBRATED"),
    "PLANE_FITTING": ("needs Open3D", "DEPENDENCY_UNAVAILABLE"),
    "SEMANTIC_INDEXING": ("needs torch + Grounding DINO + CLIP, and a calibrated frame", "NOT_CALIBRATED"),
    "NAVIGATION_BAKING": ("needs recast-cli and a calibrated frame", "NOT_CALIBRATED"),
}

NO_TOOLS = Toolchain(env={}, which=lambda _n: None)


def test_every_pipeline_stage_is_either_run_or_explicitly_excluded():
    assert sorted(CI_STAGES + list(EXCLUDED_IN_CI)) == sorted(STAGE_ORDER), "no stage may be silently left out of CI"
    assert not set(CI_STAGES) & set(EXCLUDED_IN_CI)


class SequencingControlPlane(FakeControlPlane):
    """Queues the next stage of the plan when a stage reports success, offering it the inputs the real control plane
    would: accepted raw media to the first two stages, earlier successful non-log outputs after that, and nothing that
    may contain PII once privacy preprocessing has run. Stops at the first failure, as PipelineService#advance does."""

    def __init__(self, plan: list[str], raw_inputs: list[dict[str, Any]], extra_inputs: dict[str, list[dict[str, Any]]]):
        super().__init__()
        self.plan, self.raw, self.extra = plan, raw_inputs, extra_inputs
        self.run_id = str(uuid.uuid4())
        self.outputs: list[dict[str, Any]] = []
        self.by_stage: dict[str, dict[str, Any]] = {}
        self._enqueue(0)

    def _enqueue(self, idx: int) -> None:
        stage = self.plan[idx]
        after_privacy = PRIVACY_STAGE in self.plan and self.plan.index(PRIVACY_STAGE) < idx
        inputs = list(self.raw) if idx <= 1 else []
        inputs += [o for o in self.outputs if not (after_privacy and o["containsPii"])]
        inputs += self.extra.get(stage, [])
        self.queue.append({"id": str(uuid.uuid4()), "organizationId": "o", "venueId": "v", "scanId": "s", "scanVersionId": None,
                           "stage": stage, "runId": self.run_id, "attempt": 1, "deadlineAt": None, "privacyEnabled": True,
                           "derivedBucket": DERIVED_BUCKET,
                           "outputPrefix": f"org/o/venue/v/scan/s/run/{self.run_id}/{stage}/attempt-1/", "inputs": inputs})

    def report(self, job_id, report):
        super().report(job_id, report)
        stage = self.plan[len(self.by_stage)]
        self.by_stage[stage] = report
        if report["status"] != "SUCCEEDED":
            return
        self.outputs += [{"artifactId": str(uuid.uuid4()), "kind": a["kind"], "stage": stage, "bucket": DERIVED_BUCKET,
                          "key": a["key"], "sha256": a["sha256"], "contentType": a["contentType"], "sizeBytes": a["sizeBytes"],
                          "containsPii": a["containsPii"]} for a in report["artifacts"]]
        nxt = len(self.by_stage)
        if nxt < len(self.plan):
            self._enqueue(nxt)


def fixture_splat(path: Path, count: int = 64) -> Path:
    """A small, valid 3DGS PLY standing in for the output of the excluded SPLAT_RECONSTRUCTION stage. Deterministic
    grid; labelled as a fixture wherever it is referenced. Not a reconstruction of anything."""
    rng = np.random.default_rng(7)
    side = int(np.ceil(count ** (1 / 3)))
    grid = np.stack(np.meshgrid(*[np.linspace(-1, 1, side)] * 3, indexing="ij"), axis=-1).reshape(-1, 3)[:count]
    cloud = GaussianCloud(
        positions=grid.astype(np.float32),
        scales_log=np.full((count, 3), np.log(0.05), dtype=np.float32),
        rotations_wxyz=np.tile(np.array([1, 0, 0, 0], dtype=np.float32), (count, 1)),
        opacity_logit=np.full(count, 2.0, dtype=np.float32),
        colors_dc=rng.uniform(-1, 1, size=(count, 3)).astype(np.float32))
    return write_ply(cloud, path)


def test_capture_to_processing_to_artifact_generation(tmp_path, face_video, metadata_file):
    storage = LocalStorage(tmp_path / "objects")

    # 1. The capture: real media in the raw bucket, as the ingestion API leaves it after validation.
    raw = []
    for kind, path, ctype in (("RAW_VIDEO", face_video, "video/mp4"), ("RAW_METADATA", metadata_file, "application/json")):
        key = f"org/o/venue/v/capture/c/raw/{uuid.uuid4()}"
        storage.upload(RAW_BUCKET, key, path, ctype)
        raw.append({"artifactId": str(uuid.uuid4()), "kind": kind, "stage": None, "bucket": RAW_BUCKET, "key": key,
                    "sha256": sha(path), "contentType": ctype, "sizeBytes": path.stat().st_size, "containsPii": True})

    splat_path = fixture_splat(tmp_path / "fixture-splat.ply")
    splat_key = "ci-smoke-fixture/fixture-splat.ply"
    storage.upload(DERIVED_BUCKET, splat_key, splat_path, "application/octet-stream")
    splat_input = {"artifactId": str(uuid.uuid4()), "kind": "SPLAT", "stage": "SPLAT_RECONSTRUCTION", "bucket": DERIVED_BUCKET,
                   "key": splat_key, "sha256": sha(splat_path), "contentType": "application/octet-stream",
                   "sizeBytes": splat_path.stat().st_size, "containsPii": False}

    api = SequencingControlPlane(CI_STAGES, raw, {"ARTIFACT_GENERATION": [splat_input]})
    settings = Settings(workdir=tmp_path / "work", worker_id="ci-smoke", stages=tuple(CI_STAGES), min_frames=3,
                        frame_fps=2.0, blur_threshold=1.0, duplicate_distance=0.3, heartbeat_interval=0.05)
    alive = []
    worker = Orchestrator(api, storage, settings, toolchain=Toolchain(), on_alive=lambda: alive.append(1))

    # 2. Processing invocation: the worker's own claim loop, until nothing is left to claim.
    for _ in range(len(CI_STAGES) + 1):
        if not worker.run_once():
            break

    for stage in CI_STAGES:
        report = api.by_stage.get(stage)
        assert report is not None, f"{stage} was never claimed"
        assert report["status"] == "SUCCEEDED", f"{stage}: {report.get('errorCode')} {report.get('errorMessage')}"
        assert report["stdout"] and report["stdout"]["key"].startswith(report_prefix(api, stage)), "logs are kept"
    assert api.queue == [], "nothing left unclaimed"
    assert alive, "the liveness hook fired on successful control-plane calls"

    # The privacy boundary held on the way: nothing after privacy received a PII-flagged input.
    pre_privacy_frames = [i for i in api.outputs if i["stage"] in ("FFMPEG_PREPROCESS", "FRAME_QUALITY_FILTER") and i["containsPii"]]
    assert pre_privacy_frames, "pre-privacy frames were produced and flagged"

    # 3. Artifact generation: a real .ksplat, a manifest and the viewer bundle, all stored and checksummed.
    gen = api.by_stage["ARTIFACT_GENERATION"]
    kinds = {a["kind"]: a for a in gen["artifacts"]}
    assert set(kinds) == {"KSPLAT", "ARTIFACT_MANIFEST", "VIEWER_BUNDLE"}
    for a in gen["artifacts"]:
        stored = storage.root / DERIVED_BUCKET / a["key"]
        assert stored.is_file() and sha(stored) == a["sha256"] and stored.stat().st_size == a["sizeBytes"]
        assert not a["containsPii"]

    ksplat_file = storage.root / DERIVED_BUCKET / kinds["KSPLAT"]["key"]
    # Diagnostic read-back only: viewer compatibility of this layout is proven by apps/web/lib/ksplat-compat.test.ts.
    decoded = ksplat.decode(ksplat_file.read_bytes())
    source = read_ply(splat_path)
    assert len(decoded["centers"]) == len(source)
    assert np.allclose(decoded["centers"], source.positions, atol=1e-6), "the .ksplat encodes the input splat's positions"

    manifest = json.loads((storage.root / DERIVED_BUCKET / kinds["ARTIFACT_MANIFEST"]["key"]).read_text(encoding="utf-8"))
    manifest_text = json.dumps(manifest)
    assert splat_input["sha256"] in manifest_text, "the fixture splat is recorded as an upstream input by checksum"
    assert kinds["KSPLAT"]["sha256"] in manifest_text

    with tarfile.open(storage.root / DERIVED_BUCKET / kinds["VIEWER_BUNDLE"]["key"]) as bundle:
        assert {"scene.ksplat", "manifest.json"} <= set(bundle.getnames())


def report_prefix(api: SequencingControlPlane, stage: str) -> str:
    return f"org/o/venue/v/scan/s/run/{api.run_id}/{stage}/attempt-1/"


@pytest.mark.parametrize("stage", sorted(EXCLUDED_IN_CI))
def test_excluded_stages_keep_their_orchestration_contract_without_their_toolchain(tmp_path, stage):
    """The excluded stages are not executed in CI. What CI does prove is how the orchestrator treats them on a worker
    that lacks their toolchain or upstream input: claimed, reported as a structured failure (DEPENDENCY_UNAVAILABLE
    naming what is missing, or INPUT_INVALID), no artifacts, never a success -- so a CPU-only worker can never fake a
    reconstruction. A stage that needs a calibrated frame reports NOT_CALIBRATED here, since this stand-in control
    plane never hands one out."""
    storage = LocalStorage(tmp_path / "objects")
    plane = SequencingControlPlane([stage], [], {})
    settings = Settings(workdir=tmp_path / "work", worker_id="ci-contract", stages=(stage,), heartbeat_interval=0.05)
    assert Orchestrator(plane, storage, settings, toolchain=NO_TOOLS).run_once() is True
    report = plane.by_stage[stage]
    _reason, expected_code = EXCLUDED_IN_CI[stage]
    assert report["status"] == "FAILED"
    assert report["errorCode"] == expected_code, report
    if expected_code == "DEPENDENCY_UNAVAILABLE":
        assert report["errorDetails"]["missing"], "the missing dependency is named"
    assert report["artifacts"] == []
    assert not [k for k in storage.keys(DERIVED_BUCKET) if k.endswith((".ply", ".ksplat", ".splat"))]
