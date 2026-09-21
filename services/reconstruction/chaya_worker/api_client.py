"""Client for the Spring Boot control plane's worker API (/api/v1/internal/jobs/*).

Authenticates with the OAuth2 client-credentials grant against Keycloak (service account `chaya-worker`)
and refreshes the token before it expires. The worker has no other identity and no database access.
"""

from __future__ import annotations

import logging
import time
from typing import Any, Protocol

import requests

from .settings import Settings

log = logging.getLogger(__name__)


class ApiError(Exception):
    def __init__(self, status: int, code: str, message: str) -> None:
        super().__init__(f"{status} {code}: {message}")
        self.status = status
        self.code = code
        self.message = message


class ControlPlane(Protocol):
    def claim(self, stages: list[str], worker_id: str) -> dict[str, Any] | None: ...

    def heartbeat(self, job_id: str, worker_id: str) -> dict[str, Any]: ...

    def report(self, job_id: str, report: dict[str, Any]) -> None: ...


class HttpControlPlane:
    def __init__(self, settings: Settings, session: requests.Session | None = None) -> None:
        self._s = settings
        self._http = session or requests.Session()
        self._token: str | None = None
        self._expires_at = 0.0

    def _bearer(self) -> str:
        if self._token is None or time.time() > self._expires_at - 30:
            res = self._http.post(self._s.token_url, data={"grant_type": "client_credentials", "client_id": self._s.client_id,
                                                           "client_secret": self._s.client_secret}, timeout=15)
            if res.status_code != 200:
                raise ApiError(res.status_code, "TOKEN_REFUSED", f"the identity provider refused the worker credentials: {res.text[:200]}")
            body = res.json()
            self._token = body["access_token"]
            self._expires_at = time.time() + int(body.get("expires_in", 60))
        return self._token

    def _call(self, method: str, path: str, json: Any = None) -> requests.Response:
        url = f"{self._s.api_url}/api/v1/internal/jobs{path}"
        for attempt in range(3):
            try:
                res = self._http.request(method, url, json=json, headers={"Authorization": f"Bearer {self._bearer()}"}, timeout=60)
            except requests.RequestException as exc:
                if attempt == 2:
                    raise ApiError(0, "UNREACHABLE", f"control plane unreachable: {exc}") from exc
                time.sleep(2 ** attempt)
                continue
            if res.status_code == 401 and attempt < 2:
                self._token = None  # expired or rotated; fetch a new one
                continue
            if res.status_code >= 500 and attempt < 2:
                time.sleep(2 ** attempt)
                continue
            return res
        raise AssertionError("unreachable")

    @staticmethod
    def _raise(res: requests.Response) -> None:
        if res.ok:
            return
        try:
            body = res.json()
            raise ApiError(res.status_code, body.get("code", f"HTTP_{res.status_code}"), body.get("detail", res.text[:200]))
        except ValueError:
            raise ApiError(res.status_code, f"HTTP_{res.status_code}", res.text[:200]) from None

    def claim(self, stages: list[str], worker_id: str) -> dict[str, Any] | None:
        res = self._call("POST", "/claim", {"stages": stages, "workerId": worker_id})
        if res.status_code == 204:
            return None
        self._raise(res)
        return res.json()

    def heartbeat(self, job_id: str, worker_id: str) -> dict[str, Any]:
        res = self._call("POST", f"/{job_id}/heartbeat", {"workerId": worker_id})
        self._raise(res)
        return res.json()

    def report(self, job_id: str, report: dict[str, Any]) -> None:
        self._raise(self._call("POST", f"/{job_id}/report", report))
