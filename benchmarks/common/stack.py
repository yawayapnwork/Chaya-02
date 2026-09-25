"""Access to a running Chaya 02 stack (the E2E stack of docs/E2E_VALIDATION.md, compose project `chaya-e2e`):
the real API, Keycloak and PostgreSQL. Benchmarks that need a live system create their own tenant, so they never
touch anyone else's data, and every token is a real Keycloak-signed JWT.

The env file is the stack's own (random, throwaway) secrets file. Keycloak's `chaya-web` client has no password grant,
so, exactly like scripts/ci/stack-smoke.sh and scripts/e2e/e2e_validate.py, a test-only public client `chaya-e2e` is
added for scripted users.
"""

from __future__ import annotations

import base64
import json
import secrets
import subprocess
import time
from pathlib import Path
from typing import Any

import requests


def load_env(path: str | Path) -> dict[str, str]:
    out: dict[str, str] = {}
    for line in Path(path).read_text().splitlines():
        if line.strip() and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            out[k.strip()] = v.split("#", 1)[0].strip()
    return out


class Stack:
    def __init__(self, env_file: str | Path, project: str = "chaya-e2e"):
        self.env = load_env(env_file)
        self.project = project
        self.api = f"http://127.0.0.1:{self.env['API_PORT']}"  # not localhost: on Windows it tries ::1 first and stalls ~2 s
        self.kc = f"http://localhost:{self.env['KEYCLOAK_PORT']}"  # tokens must carry the issuer the API expects
        self.vision = f"http://127.0.0.1:{self.env.get('VISION_SERVICE_PORT', '8090')}"
        self._passwords: dict[str, str] = {}
        self._tokens: dict[str, str] = {}

    # ---- database --------------------------------------------------------------------------------------------
    def psql(self, sql: str) -> str:
        r = subprocess.run(["docker", "exec", "-i", f"{self.project}-postgres-1", "psql", "-q", "-v", "ON_ERROR_STOP=1",
                            "-U", self.env["POSTGRES_USER"], "-d", self.env["POSTGRES_DB"], "-At", "-F", "|", "-c", sql],
                           capture_output=True, text=True, check=True)
        return r.stdout.replace("\r", "").strip()

    def create_organization(self, slug: str, name: str) -> str:
        # There is no organization API (DEPLOYMENT.md section 3): the documented procedure is SQL.
        return self.psql(f"INSERT INTO organization (slug, name) VALUES ('{slug}', '{name}') RETURNING id")

    # ---- Keycloak --------------------------------------------------------------------------------------------
    def _admin_headers(self) -> dict[str, str]:
        t = requests.post(f"{self.kc}/realms/master/protocol/openid-connect/token", timeout=30, data={
            "grant_type": "password", "client_id": "admin-cli",
            "username": self.env["KEYCLOAK_ADMIN"], "password": self.env["KEYCLOAK_ADMIN_PASSWORD"]})
        t.raise_for_status()
        return {"Authorization": "Bearer " + t.json()["access_token"], "Content-Type": "application/json"}

    def ensure_test_client(self) -> None:
        body = {"clientId": "chaya-e2e", "publicClient": True, "standardFlowEnabled": False, "directAccessGrantsEnabled": True,
                "defaultClientScopes": ["basic", "chaya-roles", "chaya-api-audience", "chaya-tenant"]}
        r = requests.post(f"{self.kc}/admin/realms/chaya/clients", headers=self._admin_headers(), json=body, timeout=30)
        if r.status_code not in (201, 409):
            raise RuntimeError(f"cannot create test client: {r.status_code} {r.text}")

    def create_user(self, username: str, role: str, org: str, venues: list[str]) -> None:
        h = self._admin_headers()
        password = secrets.token_urlsafe(18) + "Aa1!"
        attrs: dict[str, list[str]] = {"org_id": [org]}
        if venues:
            attrs["venue_id"] = venues
        r = requests.post(f"{self.kc}/admin/realms/chaya/users", headers=h, timeout=30, json={
            "username": username, "enabled": True, "email": f"{username}@example.invalid", "emailVerified": True,
            "firstName": "Bench", "lastName": username, "attributes": attrs})
        if r.status_code != 201:
            raise RuntimeError(f"cannot create user {username}: {r.status_code} {r.text}")
        uid = requests.get(f"{self.kc}/admin/realms/chaya/users", params={"username": username, "exact": "true"},
                           headers=h, timeout=30).json()[0]["id"]
        requests.put(f"{self.kc}/admin/realms/chaya/users/{uid}/reset-password", headers=h, timeout=30,
                     json={"type": "password", "value": password, "temporary": False}).raise_for_status()
        role_rep = requests.get(f"{self.kc}/admin/realms/chaya/roles/{role}", headers=h, timeout=30).json()
        requests.post(f"{self.kc}/admin/realms/chaya/users/{uid}/role-mappings/realm", headers=h, timeout=30,
                      json=[role_rep]).raise_for_status()
        self._passwords[username] = password

    def token(self, username: str) -> str:
        """A valid access token for `username`, re-issued shortly before expiry (tokens live 300 s)."""
        tok = self._tokens.get(username)
        if tok:
            p = tok.split(".")[1]
            exp = json.loads(base64.urlsafe_b64decode(p + "=" * (-len(p) % 4)))["exp"]
            if exp - time.time() > 60:
                return tok
        r = requests.post(f"{self.kc}/realms/chaya/protocol/openid-connect/token", timeout=30, data={
            "grant_type": "password", "client_id": "chaya-e2e", "username": username, "password": self._passwords[username]})
        r.raise_for_status()
        self._tokens[username] = r.json()["access_token"]
        return self._tokens[username]

    def call(self, user: str, method: str, path: str, **kw: Any) -> requests.Response:
        headers = {"Authorization": "Bearer " + self.token(user), **kw.pop("headers", {})}
        return requests.request(method, self.api + path, headers=headers, timeout=60, **kw)

    # ---- containers ------------------------------------------------------------------------------------------
    def docker(self, *args: str) -> None:
        subprocess.run(["docker", *args], capture_output=True, check=True)

    def image_id(self, service: str) -> str:
        r = subprocess.run(["docker", "inspect", f"{self.project}-{service}-1", "--format", "{{.Image}}"],
                           capture_output=True, text=True)
        return r.stdout.strip()
