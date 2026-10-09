"""The worker presents each claim's lease token on every later call about that job (docs/security.md,
"Service-to-service authentication"): the control plane refuses heartbeats and reports from anyone else."""

from __future__ import annotations

from typing import Any

from chaya_worker.api_client import LEASE_HEADER, HttpControlPlane
from chaya_worker.settings import Settings


class _Response:
    def __init__(self, status: int, body: Any = None) -> None:
        self.status_code = status
        self._body = body
        self.ok = 200 <= status < 300
        self.text = ""

    def json(self) -> Any:
        return self._body


class _Session:
    """Records every request; answers the token endpoint, a claim, a heartbeat and a report."""

    def __init__(self) -> None:
        self.calls: list[tuple[str, str, dict[str, str]]] = []

    def post(self, url: str, data: Any = None, timeout: float = 0) -> _Response:  # the token endpoint
        return _Response(200, {"access_token": "svc-token", "expires_in": 300})

    def request(self, method: str, url: str, json: Any = None, headers: dict[str, str] | None = None, timeout: float = 0) -> _Response:
        self.calls.append((method, url, dict(headers or {})))
        if url.endswith("/claim"):
            return _Response(200, {"id": "job-1", "stage": "INPUT_VALIDATION", "leaseToken": "cjl_lease-of-job-1"})
        if url.endswith("/heartbeat"):
            return _Response(200, {"keepGoing": True})
        return _Response(204)


def _client() -> tuple[HttpControlPlane, _Session]:
    session = _Session()
    settings = Settings(api_url="https://api.example", token_url="https://idp.example/token", client_secret="s")  # noqa: S106
    return HttpControlPlane(settings, session=session), session  # type: ignore[arg-type]


def test_the_claims_lease_token_is_sent_with_heartbeats_and_the_report() -> None:
    api, session = _client()
    order = api.claim(["INPUT_VALIDATION"], "w1")
    assert order is not None and order["id"] == "job-1"
    api.heartbeat("job-1", "w1")
    api.report("job-1", {"status": "SUCCEEDED"})

    claim, heartbeat, report = session.calls
    assert LEASE_HEADER not in claim[2], "nothing is leased before the claim"
    assert heartbeat[2][LEASE_HEADER] == "cjl_lease-of-job-1"
    assert report[2][LEASE_HEADER] == "cjl_lease-of-job-1"
    assert all(c[2]["Authorization"] == "Bearer svc-token" for c in session.calls)


def test_a_job_this_worker_never_claimed_gets_no_lease_token() -> None:
    api, session = _client()
    api.claim(["INPUT_VALIDATION"], "w1")
    api.heartbeat("job-of-someone-else", "w1")
    assert LEASE_HEADER not in session.calls[-1][2]


def test_the_lease_is_forgotten_once_the_report_is_accepted() -> None:
    api, session = _client()
    api.claim(["INPUT_VALIDATION"], "w1")
    api.report("job-1", {"status": "SUCCEEDED"})
    api.heartbeat("job-1", "w1")
    assert LEASE_HEADER not in session.calls[-1][2]
