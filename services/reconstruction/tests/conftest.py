"""Shared fixtures: tiny REAL files (an FFmpeg-encoded video, real photographs, JSON) and an orchestrator
harness. Test doubles exist only for the two network boundaries (control-plane HTTP, object storage), as
in-memory / on-disk implementations of the same interfaces."""

from __future__ import annotations

import hashlib
import json
import subprocess
import uuid
from pathlib import Path
from typing import Any

import cv2
import numpy as np
import pytest

from chaya_worker.api_client import ApiError
from chaya_worker.orchestrator import Orchestrator
from chaya_worker.settings import Settings
from chaya_worker.storage import LocalStorage
from chaya_worker.toolchain import Toolchain

RAW_BUCKET, DERIVED_BUCKET = "raw", "derived"


# ---- real media -----------------------------------------------------------------------------

def _ffmpeg() -> str:
    tc = Toolchain()
    if not tc.ffmpeg().available:
        pytest.skip("ffmpeg is not available (install ffmpeg or pip install imageio-ffmpeg)")
    return tc.ffmpeg().path


@pytest.fixture(scope="session")
def ffmpeg_bin() -> str:
    return _ffmpeg()


@pytest.fixture(scope="session")
def testsrc_video(tmp_path_factory, ffmpeg_bin) -> Path:
    """A real 3 s, 320x240, 10 fps H.264 video from FFmpeg's test-pattern source (sharp, changing content)."""
    out = tmp_path_factory.mktemp("media") / "testsrc.mp4"
    subprocess.run([ffmpeg_bin, "-v", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=320x240:rate=10", "-t", "3",
                    "-pix_fmt", "yuv420p", str(out)], check=True)
    return out


def astronaut_bgr() -> np.ndarray:
    """A real photograph of a person's face (public-domain NASA image shipped with scikit-image)."""
    skimage_data = pytest.importorskip("skimage.data")
    return cv2.cvtColor(skimage_data.astronaut(), cv2.COLOR_RGB2BGR)


def shaken_face_frames(count: int = 12) -> list[np.ndarray]:
    """Crops of the photograph at shifting positions, like a slowly panning camera (so frames are not duplicates)."""
    img = astronaut_bgr()
    h, w = img.shape[:2]
    cw, ch = int(w * 0.8), int(h * 0.8)
    frames = []
    for i in range(count):
        x = int((w - cw) * i / max(1, count - 1))
        y = int((h - ch) * ((i * 7) % count) / max(1, count - 1))
        frames.append(img[y:y + ch, x:x + cw].copy())
    return frames


@pytest.fixture(scope="session")
def face_video(tmp_path_factory, ffmpeg_bin) -> Path:
    """A real H.264 video whose frames show a real face."""
    d = tmp_path_factory.mktemp("facevideo")
    for i, frame in enumerate(shaken_face_frames(20)):
        cv2.imwrite(str(d / f"f{i:03d}.png"), frame)
    out = d / "face.mp4"
    subprocess.run([ffmpeg_bin, "-v", "error", "-y", "-framerate", "5", "-i", str(d / "f%03d.png"), "-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2",
                    "-pix_fmt", "yuv420p", str(out)], check=True)
    return out


@pytest.fixture()
def jpeg_file(tmp_path) -> Path:
    p = tmp_path / "photo.jpg"
    cv2.imwrite(str(p), shaken_face_frames(1)[0])
    return p


@pytest.fixture()
def metadata_file(tmp_path) -> Path:
    p = tmp_path / "capture.json"
    p.write_text(json.dumps({"fps": 10, "lens": "wide"}))
    return p


# ---- boundaries -----------------------------------------------------------------------------

class FakeControlPlane:
    """In-memory stand-in for the HTTP client (the control plane itself is covered by the Java tests)."""

    def __init__(self) -> None:
        self.queue: list[dict[str, Any]] = []
        self.reports: list[tuple[str, dict[str, Any]]] = []
        self.heartbeats = 0
        self.keep_going = True
        self.reject_first: ApiError | None = None

    def claim(self, stages, worker_id):
        for i, order in enumerate(self.queue):
            if order["stage"] in stages:
                return self.queue.pop(i)
        return None

    def heartbeat(self, job_id, worker_id):
        self.heartbeats += 1
        return {"keepGoing": self.keep_going}

    def report(self, job_id, report):
        if self.reject_first is not None:
            err, self.reject_first = self.reject_first, None
            self.reports.append((job_id, {**report, "_rejected": True}))
            raise err
        self.reports.append((job_id, report))

    @property
    def last(self) -> dict[str, Any]:
        return self.reports[-1][1]


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


class Harness:
    def __init__(self, tmp_path: Path, **settings: Any) -> None:
        self.storage = LocalStorage(tmp_path / "objects")
        self.api = FakeControlPlane()
        base = Settings(workdir=tmp_path / "work", min_frames=3, heartbeat_interval=0.05, **settings)
        self.settings = base
        self.toolchain = Toolchain()
        self.run_id = str(uuid.uuid4())

    def orchestrator(self, **kw: Any) -> Orchestrator:
        return Orchestrator(self.api, self.storage, self.settings, toolchain=kw.pop("toolchain", self.toolchain), **kw)

    def raw_input(self, kind: str, path: Path, content_type: str = "application/octet-stream") -> dict[str, Any]:
        aid = str(uuid.uuid4())
        key = f"capture/{aid}"
        self.storage.upload(RAW_BUCKET, key, path, content_type)
        return {"artifactId": aid, "kind": kind, "stage": None, "bucket": RAW_BUCKET, "key": key, "sha256": sha(path),
                "contentType": content_type, "sizeBytes": path.stat().st_size, "containsPii": True}

    def order(self, stage: str, inputs: list[dict[str, Any]], *, privacy: bool = True, deadline: str | None = None,
              attempt: int = 1) -> dict[str, Any]:
        return {"id": str(uuid.uuid4()), "organizationId": "o", "venueId": "v", "scanId": "s", "scanVersionId": None, "stage": stage,
                "runId": self.run_id, "attempt": attempt, "deadlineAt": deadline, "privacyEnabled": privacy,
                "derivedBucket": DERIVED_BUCKET, "outputPrefix": f"org/o/venue/v/scan/s/run/{self.run_id}/{stage}/attempt-{attempt}/",
                "inputs": inputs}

    def run(self, order: dict[str, Any], **kw: Any) -> dict[str, Any] | None:
        self.api.queue.append(order)
        return self.orchestrator(**kw).process(self.api.queue.pop())

    def outputs_as_inputs(self, report: dict[str, Any], *, drop_pii: bool = False) -> list[dict[str, Any]]:
        """What the control plane would offer the next stage: this stage's non-log artifacts."""
        return [{"artifactId": str(uuid.uuid4()), "kind": a["kind"], "stage": None, "bucket": DERIVED_BUCKET, "key": a["key"],
                 "sha256": a["sha256"], "contentType": a["contentType"], "sizeBytes": a["sizeBytes"], "containsPii": a["containsPii"]}
                for a in report["artifacts"] if not (drop_pii and a["containsPii"])]


@pytest.fixture()
def harness(tmp_path) -> Harness:
    return Harness(tmp_path)
