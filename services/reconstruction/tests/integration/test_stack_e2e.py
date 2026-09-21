"""Integration: the real worker against the real control plane, MinIO and Keycloak. Skipped unless configured.

Needs the whole local stack (Postgres+pgvector, MinIO, ClamAV, Keycloak, the Spring Boot API), an operator
token for a venue, and the worker service-account secret:

    CHAYA_IT_API=http://localhost:8080  CHAYA_IT_TOKEN_URL=<keycloak>/realms/chaya/protocol/openid-connect/token
    CHAYA_IT_WORKER_SECRET=...  CHAYA_IT_USER_TOKEN=<operator access token>  CHAYA_IT_VENUE_ID=<uuid>
    CHAYA_IT_S3_ENDPOINT=... CHAYA_IT_S3_ACCESS_KEY=... CHAYA_IT_S3_SECRET_KEY=...   pytest -m integration

What it proves end to end with real components: a video uploaded through the ingestion API is processed by the
real worker, stages 1-4 succeed with real FFmpeg / OpenCV, PII staging objects are deleted after the privacy
stage, and the run then stops at the first stage whose dependencies are missing with a structured error, never
as a success. It also proves the failed stage can be retried.
"""

from __future__ import annotations

import hashlib
import os
import time
from pathlib import Path

import pytest
import requests

from chaya_worker.api_client import HttpControlPlane
from chaya_worker.orchestrator import Orchestrator
from chaya_worker.settings import Settings
from chaya_worker.storage import S3Storage

pytestmark = pytest.mark.integration

REQUIRED = ["CHAYA_IT_API", "CHAYA_IT_TOKEN_URL", "CHAYA_IT_WORKER_SECRET", "CHAYA_IT_USER_TOKEN", "CHAYA_IT_VENUE_ID",
            "CHAYA_IT_S3_ENDPOINT", "CHAYA_IT_S3_ACCESS_KEY", "CHAYA_IT_S3_SECRET_KEY"]
missing = [n for n in REQUIRED if not os.environ.get(n)]
needs_stack = pytest.mark.skipif(bool(missing), reason="stack not configured; missing " + ", ".join(missing))


def _settings(tmp_path: Path) -> Settings:
    e = os.environ
    return Settings(api_url=e["CHAYA_IT_API"], token_url=e["CHAYA_IT_TOKEN_URL"], client_secret=e["CHAYA_IT_WORKER_SECRET"],
                    s3_endpoint=e["CHAYA_IT_S3_ENDPOINT"], s3_access_key=e["CHAYA_IT_S3_ACCESS_KEY"], s3_secret_key=e["CHAYA_IT_S3_SECRET_KEY"],
                    workdir=tmp_path / "work", worker_id="it-worker", min_frames=3, blur_threshold=1.0, duplicate_distance=0.3,
                    heartbeat_interval=5)


class Api:
    def __init__(self) -> None:
        self.base = os.environ["CHAYA_IT_API"] + "/api/v1/venues/" + os.environ["CHAYA_IT_VENUE_ID"] + "/captures"
        self.h = {"Authorization": "Bearer " + os.environ["CHAYA_IT_USER_TOKEN"]}

    def call(self, method: str, path: str = "", **kw):
        headers = {**self.h, **kw.pop("headers", {})}
        return requests.request(method, self.base + path, headers=headers, timeout=60, **kw)

    def upload_video(self, capture: str, video: Path) -> None:
        data = video.read_bytes()
        init = self.call("POST", f"/{capture}/media", json={"kind": "VIDEO", "filename": video.name, "contentType": "video/mp4",
                                                              "sizeBytes": len(data), "sha256": hashlib.sha256(data).hexdigest()})
        assert init.status_code == 201, init.text
        media, part = init.json()["mediaId"], init.json()["partSizeBytes"]
        assert len(data) <= part, "the fixture video must fit one part"
        put = self.call("PUT", f"/{capture}/media/{media}/parts/1", data=data, headers={"Content-Type": "application/octet-stream"})
        assert put.status_code == 204, put.text
        assert self.call("POST", f"/{capture}/media/{media}/complete").status_code == 202
        for _ in range(120):
            status = self.call("GET", f"/{capture}/media/{media}").json()["status"]
            if status not in ("PENDING", "VALIDATING"):
                assert status == "ACCEPTED", status
                return
            time.sleep(0.5)
        raise AssertionError("media validation timed out")

    def processing(self, capture: str) -> dict:
        return self.call("GET", f"/{capture}/processing").json()


def drain(orchestrator: Orchestrator, api: Api, capture: str, limit: int = 40) -> dict:
    """Run the worker until the run stops (FAILED/PARTIAL/SUCCEEDED/CANCELLED) or nothing is left to claim."""
    for _ in range(limit):
        worked = orchestrator.run_once()
        run = api.processing(capture)["run"]
        if run["status"] != "RUNNING":
            return run
        if not worked:
            time.sleep(1)
    raise AssertionError("the run did not settle")


@needs_stack
def test_real_worker_runs_the_first_stages_and_stops_honestly_at_the_first_missing_dependency(face_video, tmp_path):
    settings = _settings(tmp_path)
    api = Api()
    capture = api.call("POST", json={"device": {"model": "integration-test"}}).json()["id"]
    api.upload_video(capture, face_video)
    assert api.call("POST", f"/{capture}/complete-upload").status_code == 200
    started = api.call("POST", f"/{capture}/processing", json={"timeBudgetSeconds": 900})
    assert started.status_code == 202 and started.json()["run"]["stages"][0]["state"] == "QUEUED"

    orchestrator = Orchestrator(HttpControlPlane(settings), S3Storage(settings), settings)
    run = drain(orchestrator, api, capture)

    # Never a success: stages 6-12 are not implemented, so a run cannot complete.
    assert run["status"] == "FAILED" and run["quality"] is None and run["retryable"] is True
    assert run["failureStage"] in ("POSE_ESTIMATION", "SPLAT_RECONSTRUCTION"), run["failureStage"]
    assert run["failureCode"] in ("DEPENDENCY_UNAVAILABLE", "POSE_ESTIMATION_INSUFFICIENT", "MAPPING_FAILED", "STAGE_NOT_IMPLEMENTED", "FEATURE_MATCHING_FAILED")
    stages = {s["stage"]: s for s in run["stages"]}
    order = [s["stage"] for s in run["stages"]]
    for name in order[:order.index(run["failureStage"])]:
        assert stages[name]["state"] == "SUCCEEDED", (name, stages[name])
    failed = stages[run["failureStage"]]
    assert failed["state"] == "FAILED" and failed["lastRun"]["errorCode"] == run["failureCode"]

    # The stage contract is fully recorded for every stage that ran.
    for name in order[:order.index(run["failureStage"]) + 1]:
        last = stages[name]["lastRun"]
        assert last["startedAt"] and last["finishedAt"] and last["command"]["stage"] == name and last["stdout"] and last["stderr"]
        for art in last["artifacts"]:
            assert len(art["sha256"]) == 64
        if last["status"] == "SUCCEEDED":
            assert last["outputSha256"]

    # Artifacts in storage match their recorded checksums, PII staging objects are gone, logs exist.
    import boto3
    from botocore.config import Config
    s3 = boto3.client("s3", endpoint_url=settings.s3_endpoint, aws_access_key_id=settings.s3_access_key, aws_secret_access_key=settings.s3_secret_key,
                      region_name="us-east-1", config=Config(s3={"addressing_style": "path"}))
    bucket = os.environ.get("CHAYA_IT_DERIVED_BUCKET", "chaya-derived")
    keys = [o["Key"] for o in s3.list_objects_v2(Bucket=bucket, Prefix="org/").get("Contents", [])]
    run_keys = [k for k in keys if f"/run/{run['id']}/" in k]
    assert run_keys and not [k for k in run_keys if "/pii/" in k], "unblurred frames must have been deleted"
    for name in ("PRIVACY_PREPROCESS",):
        for art in stages[name]["lastRun"]["artifacts"]:
            body = s3.get_object(Bucket=bucket, Key=art["key"])["Body"].read()
            assert hashlib.sha256(body).hexdigest() == art["sha256"]
    assert api.call("GET", f"/{capture}").json()["status"] == "PROCESSING", "a failed run must not complete the capture"

    # Retry is possible: the failed stage runs again as attempt 2 and fails again for the same missing reason.
    assert api.call("POST", f"/{capture}/processing/retry").status_code == 202
    again = drain(orchestrator, api, capture)
    assert again["status"] == "FAILED" and again["failureStage"] == run["failureStage"]
    assert next(s for s in again["stages"] if s["stage"] == run["failureStage"])["attempts"] == 2
