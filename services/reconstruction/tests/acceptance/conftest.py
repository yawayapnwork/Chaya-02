"""Fixtures and evidence for the cross-service acceptance suite (docs/ACCEPTANCE.md, classification C).

Results are PASS, FAIL or BLOCKED. BLOCKED is a pytest skip whose reason starts with "BLOCKED:" and names the missing
dependency; it is never counted as a pass. With ACC_EVIDENCE set, every test's checks are written there as JSON.

The four pipeline runs are session fixtures, created when a test first needs them (test files run in order):
  floor0_v1  floor 0, full plan                       -> published version 1 (viewer, search, navigation)
  floor1_v1  floor 1, full plan, one claim abandoned  -> lost worker, retry, then version 1 of floor 1 (multi-floor)
  failed_scan floor 0, inconsistent semantic labels   -> the REAL navmesh bake fails; version 1 stays published
  rescan_v2  floor 0, incremental re-scan             -> real alignment, splice and re-bake: version 2
"""

from __future__ import annotations

import json
import os
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import pytest
import requests

from . import stack
from .stack import Config, Evidence, Http, Keycloak, RunPlan, StandIns

RESULTS: list[dict[str, Any]] = []
_CURRENT: dict[str, Evidence] = {}


def pytest_collection_modifyitems(items):
    missing = Config.missing()
    for item in items:
        if "tests/acceptance" in str(item.fspath).replace("\\", "/"):
            item.add_marker(pytest.mark.acceptance)
            if missing:
                item.add_marker(pytest.mark.skip(reason="BLOCKED: no stack configured (missing " + ", ".join(missing) + ")"))


@pytest.fixture()
def ev(request) -> Evidence:
    e = Evidence()
    _CURRENT[request.node.nodeid] = e
    return e


@pytest.hookimpl(hookwrapper=True)
def pytest_runtest_makereport(item, call):
    outcome = yield
    rep = outcome.get_result()
    if "tests/acceptance" not in str(item.fspath).replace("\\", "/"):
        return
    if rep.when == "call" or (rep.when == "setup" and (rep.failed or rep.skipped)):
        e = _CURRENT.get(item.nodeid)
        if rep.skipped:
            reason = rep.longrepr[2] if isinstance(rep.longrepr, tuple) else str(rep.longrepr)
            status = "BLOCKED" if "BLOCKED:" in reason else "SKIPPED"
            detail = reason.split("Skipped: ", 1)[-1]
        else:
            status, detail = ("PASS", "") if rep.passed else ("FAIL", str(rep.longrepr)[-1500:])
        RESULTS.append({"test": item.nodeid, "classification": "C", "scenario": item.module.__name__.split(".")[-1],
                        "name": (item.function.__doc__ or item.name).strip().splitlines()[0], "status": status, "detail": detail,
                        "checks": [c.__dict__ for c in e.checks] if e else []})


def pytest_sessionfinish(session):
    if not RESULTS:
        return
    counts: dict[str, int] = {}
    for r in RESULTS:
        counts[r["status"]] = counts.get(r["status"], 0) + 1
    summary = ", ".join(f"{v} {k}" for k, v in sorted(counts.items()))
    print(f"\n[acceptance C: cross-service, synthetic fixtures] {summary}")
    cfg_out = Path(os.environ["ACC_EVIDENCE"]) if os.environ.get("ACC_EVIDENCE") else None
    if cfg_out:
        cfg_out.parent.mkdir(parents=True, exist_ok=True)
        cfg_out.write_text(json.dumps({"classification": "C", "kind": "cross-service acceptance on the real stack; stand-ins for "
                                       + ", ".join(stack.STAND_IN_STAGES) + " (synthetic viewer-scene fixture)",
                                       "finished": stack.now(), "counts": counts, "results": RESULTS}, indent=2, default=str))


# ------------------------------------------------------------------------------------------------ the stack
@pytest.fixture(scope="session")
def cfg() -> Config:
    return Config.from_env()


@pytest.fixture(scope="session")
def kc(cfg) -> Keycloak:
    k = Keycloak(cfg)
    k.ensure_clients()
    return k


@dataclass
class World:
    """Two organizations (seeded by the runner: there is no API for creating one), their venues, floors and users."""

    cfg: Config
    kc: Keycloak
    tag: str
    users: dict[str, Http] = field(default_factory=dict)
    venue_a: str = ""
    venue_a2: str = ""
    venue_b: str = ""
    floor0: str = ""
    floor1: str = ""

    def u(self, name: str) -> Http:
        return self.users[name]


@pytest.fixture(scope="session")
def world(cfg, kc) -> World:
    w = World(cfg, kc, cfg.run_tag)
    t = w.tag
    kc.create_user(f"acc-admin-{t}", "admin", cfg.org_a, [])
    admin = w.users["admin"] = Http(cfg, kc, user=f"acc-admin-{t}")
    w.venue_a = admin.ok("POST", "/api/v1/venues", json={"slug": f"acc-a-{t}", "name": f"Acceptance venue A {t} (synthetic)"})["id"]
    w.venue_a2 = admin.ok("POST", "/api/v1/venues", json={"slug": f"acc-a2-{t}", "name": f"Acceptance venue A2 {t} (synthetic)"})["id"]
    kc.create_user(f"acc-admin-b-{t}", "admin", cfg.org_b, [])
    admin_b = w.users["admin_b"] = Http(cfg, kc, user=f"acc-admin-b-{t}")
    w.venue_b = admin_b.ok("POST", "/api/v1/venues", json={"slug": f"acc-b-{t}", "name": f"Acceptance venue B {t} (synthetic)"})["id"]
    for name, role, org, venues in (("manager", "venue-manager", cfg.org_a, [w.venue_a]), ("operator", "operator", cfg.org_a, [w.venue_a]),
                                    ("viewer", "viewer", cfg.org_a, [w.venue_a]), ("operator_a2", "operator", cfg.org_a, [w.venue_a2]),
                                    ("manager_b", "venue-manager", cfg.org_b, [w.venue_b])):
        kc.create_user(f"acc-{name.replace('_', '-')}-{t}", role, org, venues)
        w.users[name] = Http(cfg, kc, user=f"acc-{name.replace('_', '-')}-{t}")
    mgr = w.u("manager")
    w.floor0 = mgr.ok("POST", f"/api/v1/venues/{w.venue_a}/floors", json={"level": 0, "name": "Ground (synthetic scene)"})["id"]
    w.floor1 = mgr.ok("POST", f"/api/v1/venues/{w.venue_a}/floors", json={"level": 1, "name": "Level 1 (synthetic scene)"})["id"]
    return w


@pytest.fixture(scope="session")
def vision_embed(cfg):
    """Real CLIP text vectors from the running vision service, or None when the stack has none."""
    if not cfg.vision:
        return None

    def embed(text: str) -> tuple[list[float], str]:
        r = requests.post(cfg.vision + "/v1/embed-text", json={"text": text}, timeout=120)
        r.raise_for_status()
        j = r.json()
        return (j.get("embedding") or j["embeddings"][0]), j.get("model")
    return embed


@pytest.fixture(scope="session")
def stand_ins(cfg, kc, vision_embed) -> StandIns:
    return StandIns(cfg, kc, vision_embed)


CAPTURE_SECONDS = 8.0


@pytest.fixture(scope="session")
def capture_video(tmp_path_factory, ffmpeg_bin) -> bytes:
    """A real H.264 video of a real face (the stack smoke's frames, tests/conftest.py), 8 s long: the production worker
    extracts 2 frames per second (FRAME_FPS) and FRAME_QUALITY_FILTER needs at least 10 (MIN_FRAMES). It carries no scene;
    it exercises the real ClamAV scan, content sniffing, frame extraction, quality filtering and face anonymisation."""
    import subprocess  # noqa: PLC0415

    import cv2  # noqa: PLC0415

    from tests.conftest import shaken_face_frames  # noqa: PLC0415
    d = tmp_path_factory.mktemp("capture")
    for i, frame in enumerate(shaken_face_frames(int(CAPTURE_SECONDS * 5))):
        cv2.imwrite(str(d / f"f{i:03d}.png"), frame)
    out = d / "capture.mp4"
    subprocess.run([ffmpeg_bin, "-v", "error", "-y", "-framerate", "5", "-i", str(d / "f%03d.png"), "-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2",
                    "-pix_fmt", "yuv420p", str(out)], check=True)
    return out.read_bytes()


# ------------------------------------------------------------------------------------------------ pipeline runs
@dataclass
class Run:
    venue: str
    floor: str
    capture: str
    media: str
    drive: stack.Drive
    plan: RunPlan
    measurements: list[dict[str, Any]]
    extra: dict[str, Any] = field(default_factory=dict)

    @property
    def status(self) -> dict[str, Any]:
        return self.drive.status


def start_capture(w: World, floor: str, video: bytes, init: dict[str, Any] | None = None) -> tuple[str, str]:
    op = w.u("operator")
    if init is None:
        cap = op.ok("POST", f"/api/v1/venues/{w.venue_a}/captures", json={"floorId": floor, "device": {"app": "acceptance suite"}})["id"]
    else:
        cap = init["captureId"]
    m = stack.upload(op, w.venue_a, cap, video, "capture.mp4", "video/mp4")
    assert m["status"] == "ACCEPTED", f"capture media not accepted: {m}"
    return cap, m["id"] if "id" in m else m["mediaId"]


def run_full(w: World, stand_ins: StandIns, floor: str, video: bytes, plan: RunPlan, stop=None) -> Run:
    op = w.u("operator")
    cap, media = start_capture(w, floor, video)
    ms = stack.record_control_points(op, w.venue_a, cap, media)
    op.ok("POST", f"/api/v1/venues/{w.venue_a}/captures/{cap}/complete-upload", json={"durationSeconds": CAPTURE_SECONDS})
    op.ok("POST", f"/api/v1/venues/{w.venue_a}/captures/{cap}/processing", json={})
    d = stack.drive(op, stand_ins, w.venue_a, cap, plan, ms, stop=stop)
    return Run(w.venue_a, floor, cap, media, d, plan, ms)


def succeeded(run: Run) -> Run:
    """A run every later test builds on must have succeeded; otherwise they fail with the run's own reason, not a symptom."""
    r = run.status["run"]
    failed = [(s["stage"], (s.get("lastRun") or {}).get("errorCode"), (s.get("lastRun") or {}).get("errorMessage"))
              for s in r["stages"] if s["state"] == "FAILED"]
    assert r["status"] == "SUCCEEDED", f"run {r['id']} ended {r['status']}: {r.get('failure')} {failed}"
    return run


@pytest.fixture(scope="session")
def floor0_v1(world, stand_ins, capture_video) -> Run:
    return succeeded(run_full(world, stand_ins, world.floor0, capture_video, RunPlan(stack.full_scene())))


@pytest.fixture(scope="session")
def floor1_v1(world, stand_ins, capture_video, cfg) -> Run:
    """Floor 1's run, whose first SPLAT_RECONSTRUCTION claim is abandoned: the job's lease expires, the control plane fails it
    as WORKER_LOST, the operator retries, and the second attempt completes the run."""
    plan = RunPlan(stack.full_scene(), abandon_once="SPLAT_RECONSTRUCTION")
    lost = lambda st: stack.stage(st, "SPLAT_RECONSTRUCTION")["state"] == "FAILED"  # noqa: E731
    first = run_full(world, stand_ins, world.floor1, capture_video, plan, stop=lost)
    op = world.u("operator")
    base = f"/api/v1/venues/{world.venue_a}/captures/{first.capture}"
    lost_status = first.status
    abandoned = plan.abandoned.get("SPLAT_RECONSTRUCTION")
    late = stand_ins.report(abandoned, "SUCCEEDED", stand_ins.outputs(abandoned, plan)) if abandoned else None
    retry = op("POST", base + "/processing/retry")
    d = stack.drive(op, stand_ins, world.venue_a, first.capture, plan, None if first.drive.calibrated else first.measurements)
    run = Run(world.venue_a, world.floor1, first.capture, first.media, d, plan, first.measurements)
    run.extra.update(lost_status=lost_status, lost_timeline=first.drive.timeline, late_report=late, retry=retry)
    return succeeded(run)


@pytest.fixture(scope="session")
def failed_scan(world, stand_ins, capture_video, floor0_v1) -> Run:
    """A new full scan of floor 0 whose stand-in segmentation has 7 labels fewer than the cloud has Gaussians. The real
    stages after it must refuse labels that do not describe their cloud (NAVIGATION_BAKING: NAVMESH_LABELS_UNAVAILABLE).
    Nothing is reported as failed by the suite: a real stage fails on inconsistent upstream data."""
    sc = stack.full_scene()
    plan = RunPlan(sc, labels_override=sc.labels[:-7])
    return run_full(world, stand_ins, world.floor0, capture_video, plan)


@pytest.fixture(scope="session")
def rescan_v2(world, stand_ins, capture_video, floor0_v1, failed_scan) -> Run:
    op = world.u("operator")
    current = op.ok("GET", f"/api/v1/venues/{world.venue_a}/floors/{world.floor0}/scan-versions")
    versions = current if isinstance(current, list) else current.get("versions", current.get("items", []))
    parent = next(v for v in versions if v.get("current") or v.get("isCurrent") or v.get("status") == "FINALIZED")
    init = op.ok("POST", f"/api/v1/venues/{world.venue_a}/floors/{world.floor0}/rescan",
                 json={"parentVersionId": parent["id"], "region": {"points": stack.RESCAN_REGION}})
    cap, media = start_capture(world, world.floor0, capture_video, init)
    # The re-scan's own reconstruction needs its metric scale too (REGION_ALIGNMENT refuses NOT_CALIBRATED): the operator
    # records the same surveyed points against the re-scan's video. Its region cloud is in the fixture's frame, so they hold.
    ms = stack.record_control_points(op, world.venue_a, cap, media)
    op.ok("POST", f"/api/v1/venues/{world.venue_a}/captures/{cap}/complete-upload", json={"durationSeconds": CAPTURE_SECONDS})
    op.ok("POST", f"/api/v1/venues/{world.venue_a}/captures/{cap}/processing", json={})
    plan = RunPlan(stack.region_scene())
    d = stack.drive(op, stand_ins, world.venue_a, cap, plan, ms)
    run = Run(world.venue_a, world.floor0, cap, media, d, plan, ms)
    run.extra.update(parent=parent, rescan_init=init, versions_before=versions)
    return succeeded(run)


def wait(pred, timeout: float, every: float = 2.0):
    end = time.time() + timeout
    last = None
    while time.time() < end:
        last = pred()
        if last:
            return last
        time.sleep(every)
    return last
