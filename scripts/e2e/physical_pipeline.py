"""Physical-pipeline validation: real capture -> every real worker stage, in plan order, with per-stage evidence.

This drives the worker's own claim loop (chaya_worker.orchestrator.Orchestrator) over the full-venue plan
(dev.chaya.api.pipeline.PipelineDefinition.STAGES) with the real toolchain found on this host. The control plane is
stood in for by PlanControlPlane below, which hands each stage the inputs dev.chaya.api.pipeline.PipelineService#inputs
would (accepted raw media to the first two stages, earlier successful non-log outputs after that, nothing PII-flagged
after PRIVACY_PREPROCESS), passes coordinateFrame = null on every order (a run has no calibration until an operator
calibrates it through the API, which this driver cannot do), and stops at the first failed stage, as PipelineService#advance does.

Nothing is inserted: no splat, graph, POI or frame is supplied that a stage did not produce. A stage after the first
failure is recorded as NOT_RUN with the dependencies this worker lacks for it, never as a success.

Per stage the evidence records: inputs (kind, key, sha256), outputs (kind, key, size, sha256, each re-hashed from the
stored bytes), status, duration, configuration (the stage's command record), coordinate frame, and failure reason.

Usage (in the toolchain worker image, repository mounted at /repo):
    python /repo/scripts/e2e/physical_pipeline.py --media capture.mp4 --out /evidence/physical-pipeline.json --store /tmp/objects
"""

from __future__ import annotations

import argparse
import hashlib
import json
import logging
import platform
import subprocess
import sys
import tarfile
import time
import uuid
from pathlib import Path
from typing import Any

from chaya_worker import __version__
from chaya_worker.orchestrator import Orchestrator
from chaya_worker.settings import Settings
from chaya_worker.stages import PRIVACY_STAGE
from chaya_worker.storage import LocalStorage
from chaya_worker.toolchain import Toolchain

RAW_BUCKET, DERIVED_BUCKET = "chaya-raw", "chaya-derived"

# dev.chaya.api.pipeline.PipelineDefinition.STAGES (the full-venue plan; REGION_* only exist in a re-scan plan).
FULL_PLAN = ["INPUT_VALIDATION", "FFMPEG_PREPROCESS", "FRAME_QUALITY_FILTER", "PRIVACY_PREPROCESS", "POSE_ESTIMATION",
             "SPLAT_RECONSTRUCTION", "SEMANTIC_SEGMENTATION", "GEOMETRIC_CLEANUP", "PLANE_FITTING", "ARTIFACT_GENERATION",
             "SEMANTIC_INDEXING", "NAVIGATION_BAKING"]

# What each stage needs beyond its upstream artifacts, as the stage code checks it (chaya_worker.stages.*).
STAGE_REQUIREMENTS = {
    "INPUT_VALIDATION": ["ffmpeg"], "FFMPEG_PREPROCESS": ["ffmpeg"], "FRAME_QUALITY_FILTER": ["py:cv2"],
    "PRIVACY_PREPROCESS": ["py:cv2"], "POSE_ESTIMATION": ["colmap"],
    "SPLAT_RECONSTRUCTION": ["py:torch", "py:gsplat", "cuda", "colmap"],
    "SEMANTIC_SEGMENTATION": ["py:torch", "py:transformers", "colmap"],
    "GEOMETRIC_CLEANUP": ["py:open3d"], "PLANE_FITTING": ["py:open3d"], "ARTIFACT_GENERATION": [],
    "SEMANTIC_INDEXING": ["py:torch", "py:transformers", "py:open_clip", "py:PIL", "colmap"],
    "NAVIGATION_BAKING": ["exe:chaya-navmesh"],
}
# The upstream artifact each later stage cannot run without, and whether it needs a calibrated canonical frame.
STAGE_UPSTREAM = {
    "SPLAT_RECONSTRUCTION": (["SPARSE_MODEL", "POSES", "FRAME_ARCHIVE_ANON"], False),
    "SEMANTIC_SEGMENTATION": (["SPLAT"], False), "GEOMETRIC_CLEANUP": (["SPLAT"], False),
    "PLANE_FITTING": (["SPLAT_CLEAN"], False), "ARTIFACT_GENERATION": (["SPLAT_CLEAN or SPLAT"], False),
    "SEMANTIC_INDEXING": (["SPLAT_CLEAN or SPLAT"], True), "NAVIGATION_BAKING": (["SPLAT_CLEAN or SPLAT", "PLANE_MODEL"], True),
}
# The geometric space each stage's outputs are expressed in (docs/coordinate-frames.md).
OUTPUT_SPACE = {
    "INPUT_VALIDATION": "none (media metadata)", "FFMPEG_PREPROCESS": "image pixels", "FRAME_QUALITY_FILTER": "image pixels",
    "PRIVACY_PREPROCESS": "image pixels",
    "POSE_ESTIMATION": "RECONSTRUCTION frame (COLMAP): arbitrary scale, rotation and origin; not metric",
    "SPLAT_RECONSTRUCTION": "RECONSTRUCTION frame", "SEMANTIC_SEGMENTATION": "RECONSTRUCTION frame",
    "GEOMETRIC_CLEANUP": "RECONSTRUCTION frame", "PLANE_FITTING": "RECONSTRUCTION frame (+ gravity estimate)",
    "ARTIFACT_GENERATION": "RECONSTRUCTION frame", "SEMANTIC_INDEXING": "CANONICAL (metres, +Z up); requires calibration",
    "NAVIGATION_BAKING": "CANONICAL (metres, +Z up); requires calibration",
}


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


class PlanControlPlane:
    """The control plane's job sequencing for one run (see the module docstring). Records every order and report."""

    def __init__(self, plan: list[str], raw_inputs: list[dict[str, Any]], coordinate_frame: dict[str, Any] | None):
        self.plan, self.raw, self.frame = plan, raw_inputs, coordinate_frame
        self.run_id = str(uuid.uuid4())
        self.queue: list[dict[str, Any]] = []
        self.outputs: list[dict[str, Any]] = []
        self.orders: dict[str, dict[str, Any]] = {}
        self.reports: dict[str, dict[str, Any]] = {}
        self.stopped_at: str | None = None
        self._enqueue(0)

    def _enqueue(self, idx: int) -> None:
        stage = self.plan[idx]
        after_privacy = self.plan.index(PRIVACY_STAGE) < idx
        inputs = list(self.raw) if idx <= 1 else []
        if idx > 0:
            inputs += [o for o in self.outputs if not (after_privacy and o["containsPii"]) and not o["kind"].startswith("LOG_")]
        order = {"id": str(uuid.uuid4()), "organizationId": "e2e-org", "venueId": "e2e-venue", "scanId": "e2e-scan",
                 "scanVersionId": None, "stage": stage, "runId": self.run_id, "attempt": 1, "deadlineAt": None,
                 "privacyEnabled": True, "derivedBucket": DERIVED_BUCKET,
                 "outputPrefix": f"org/e2e-org/venue/e2e-venue/scan/e2e-scan/run/{self.run_id}/{stage}/attempt-1/",
                 "inputs": inputs, "regionGeometry": None, "coordinateFrame": self.frame, "parentCoordinateFrame": None}
        self.orders[stage] = order
        self.queue.append(order)

    def claim(self, stages, worker_id):
        for i, order in enumerate(self.queue):
            if order["stage"] in stages:
                return self.queue.pop(i)
        return None

    def heartbeat(self, job_id, worker_id):
        return {"keepGoing": True}

    def report(self, job_id, report):
        stage = next(s for s, o in self.orders.items() if o["id"] == job_id)
        self.reports[stage] = report
        if report["status"] != "SUCCEEDED":
            self.stopped_at = stage
            return
        self.outputs += [{"artifactId": str(uuid.uuid4()), "kind": a["kind"], "stage": stage, "bucket": DERIVED_BUCKET, "key": a["key"],
                          "sha256": a["sha256"], "contentType": a["contentType"], "sizeBytes": a["sizeBytes"],
                          "containsPii": a["containsPii"]} for a in report["artifacts"]]
        nxt = self.plan.index(stage) + 1
        if nxt < len(self.plan):
            self._enqueue(nxt)


def verify_artifact(storage: LocalStorage, a: dict[str, Any]) -> dict[str, Any]:
    stored = storage.root / DERIVED_BUCKET / a["key"]
    actual = sha256(stored) if stored.is_file() else None
    return {"kind": a["kind"], "key": a["key"], "sizeBytes": a["sizeBytes"], "sha256": a["sha256"], "containsPii": a["containsPii"],
            "partial": a.get("partial", False), "stored": stored.is_file(),
            "verified": actual == a["sha256"] and stored.stat().st_size == a["sizeBytes"] if actual else False}


def sparse_model_stats(storage: LocalStorage, key: str, colmap: str | None, scratch: Path) -> dict[str, Any] | None:
    """COLMAP's own analysis of the stored SPARSE_MODEL (registered images, points, observations, reprojection error)."""
    if not colmap:
        return None
    model = scratch / "sparse-analyze"
    model.mkdir(parents=True, exist_ok=True)
    with tarfile.open(storage.root / DERIVED_BUCKET / key) as tar:
        tar.extractall(model, filter="data")
    proc = subprocess.run([colmap, "model_analyzer", "--path", str(model)], capture_output=True, text=True, timeout=300)
    lines = [ln.split("]", 1)[-1].strip() for ln in (proc.stdout + proc.stderr).splitlines() if ":" in ln]
    return {"command": "colmap model_analyzer --path <SPARSE_MODEL>", "exit": proc.returncode,
            "output": [ln for ln in lines if ln and not ln.startswith("=")]}


def export_small_artifacts(storage: LocalStorage, api: PlanControlPlane, dest: Path) -> None:
    """Copies each stage's non-PII JSON outputs and its stdout/stderr logs next to the evidence, for inspection.
    Frame archives (images) and binary models are not copied: they are identified by checksum in the evidence."""
    for stage, rep in api.reports.items():
        files = [(a["key"], a["key"].rsplit("/", 1)[-1]) for a in rep["artifacts"]
                 if a["contentType"] == "application/json" and not a["containsPii"]]
        files += [(rep[k]["key"], f"{k}.txt") for k in ("stdout", "stderr") if rep.get(k)]
        for key, name in files:
            src = storage.root / DERIVED_BUCKET / key
            if src.is_file():
                (dest / stage).mkdir(parents=True, exist_ok=True)
                (dest / stage / name).write_bytes(src.read_bytes())


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--media", type=Path, required=True, help="the captured video (RAW_VIDEO)")
    ap.add_argument("--media-source", default="", help="where the capture came from, recorded verbatim")
    ap.add_argument("--out", type=Path, required=True, help="evidence JSON")
    ap.add_argument("--store", type=Path, required=True, help="local object store root (scratch)")
    args = ap.parse_args()

    logging.basicConfig(level=logging.WARNING, stream=sys.stderr)
    storage = LocalStorage(args.store / "objects")
    settings = Settings.from_env()
    settings = Settings(**{**settings.__dict__, "workdir": args.store / "work", "worker_id": "e2e-physical", "stages": tuple(FULL_PLAN),
                           "heartbeat_interval": 30.0})
    toolchain = Toolchain()

    media_sha = sha256(args.media)
    key = f"org/e2e-org/venue/e2e-venue/capture/e2e-capture/raw/{uuid.uuid4()}"
    storage.upload(RAW_BUCKET, key, args.media, "video/mp4")
    raw = [{"artifactId": str(uuid.uuid4()), "kind": "RAW_VIDEO", "stage": None, "bucket": RAW_BUCKET, "key": key, "sha256": media_sha,
            "contentType": "video/mp4", "sizeBytes": args.media.stat().st_size, "containsPii": True}]
    frame = None  # no ACTIVE calibration exists for a run that has not been calibrated
    api = PlanControlPlane(FULL_PLAN, raw, frame)
    worker = Orchestrator(api, storage, settings, toolchain=toolchain)

    timings: dict[str, float] = {}
    started = time.time()
    while api.queue:
        stage = api.queue[0]["stage"]
        t0 = time.monotonic()
        print(f"[{time.strftime('%H:%M:%S')}] {stage} ...", flush=True)
        if not worker.run_once():
            break
        timings[stage] = round(time.monotonic() - t0, 2)
        rep = api.reports.get(stage, {})
        print(f"[{time.strftime('%H:%M:%S')}] {stage} {rep.get('status')} {rep.get('errorCode') or ''} in {timings[stage]} s", flush=True)

    stages = []
    for stage in FULL_PLAN:
        order, rep = api.orders.get(stage), api.reports.get(stage)
        reqs = STAGE_REQUIREMENTS[stage]
        missing = [m.name for m in toolchain.missing(reqs)]
        entry: dict[str, Any] = {"stage": stage, "requirements": reqs, "missing_on_this_worker": missing,
                                 "output_space": OUTPUT_SPACE[stage]}
        if rep is None:
            upstream, needs_frame = STAGE_UPSTREAM.get(stage, ([], False))
            reasons = [f"not run: the run stopped at {api.stopped_at}"]
            reasons += [f"needs {', '.join(upstream)} (never produced)"] if upstream else []
            reasons += ["needs a calibrated canonical frame (none exists: no measured distances or control points)"] if needs_frame and not frame else []
            reasons += [f"this worker also lacks: {', '.join(missing)}"] if missing else []
            entry.update({"status": "NOT_RUN", "failure_reason": "; ".join(reasons), "coordinate_frame": None})
        else:
            outputs = [verify_artifact(storage, a) for a in rep["artifacts"]]
            entry.update({
                "status": rep["status"], "started": rep["startedAt"], "finished": rep["finishedAt"], "duration_s": timings.get(stage),
                "inputs": [{k: i[k] for k in ("kind", "stage", "key", "sha256", "sizeBytes", "containsPii")} for i in order["inputs"]],
                "outputs": outputs, "all_outputs_verified": all(o["verified"] for o in outputs),
                "configuration": rep["command"], "exit_status": rep["exitStatus"],
                "coordinate_frame": {"work_order_coordinateFrame": order["coordinateFrame"]},
                "failure_reason": None if rep["status"] == "SUCCEEDED" else {
                    "code": rep["errorCode"], "message": rep["errorMessage"], "details": rep["errorDetails"]},
                "logs": {k: rep[k] and {"key": rep[k]["key"], "sha256": rep[k]["sha256"]} for k in ("stdout", "stderr")},
            })
            if stage == "POSE_ESTIMATION" and rep["status"] == "SUCCEEDED":
                sparse = next(o for o in outputs if o["kind"] == "SPARSE_MODEL")
                entry["sparse_model_analysis"] = sparse_model_stats(storage, sparse["key"], toolchain.colmap().path, args.store)
        stages.append(entry)

    reached_route = api.reports.get("NAVIGATION_BAKING", {}).get("status") == "SUCCEEDED"
    evidence = {
        "generated": time.strftime("%Y-%m-%dT%H:%M:%S%z"), "wall_clock_s": round(time.time() - started, 1),
        "host": {"platform": platform.platform(), "python": platform.python_version(), "worker_version": __version__,
                 "toolchain": {r: toolchain.status(r).as_dict() for r in sorted({r for v in STAGE_REQUIREMENTS.values() for r in v})},
                 "cuda_devices": toolchain.cuda_devices()},
        "capture": {"kind": "RAW_VIDEO", "file": args.media.name, "bytes": args.media.stat().st_size, "sha256": media_sha,
                    "source": args.media_source},
        "plan": FULL_PLAN, "coordinate_frame_supplied": frame, "settings": settings.config_snapshot(),
        "stopped_at": api.stopped_at,
        "result": "SUCCEEDED" if reached_route else "INCOMPLETE",
        "stages": stages,
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    export_small_artifacts(storage, api, args.out.parent / "physical-artifacts")
    args.out.write_text(json.dumps(evidence, indent=2, default=str), encoding="utf-8")
    print(f"result {evidence['result']}; stopped at {api.stopped_at}; evidence -> {args.out}")
    return 0 if reached_route else 2


if __name__ == "__main__":
    sys.exit(main())
