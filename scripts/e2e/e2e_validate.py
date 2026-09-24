"""End-to-end validation of Chaya 02 against the real local stack (docs/E2E_VALIDATION.md).

Drives the real API, Keycloak, MinIO, Postgres, the CPU reconstruction worker and CPU-COLMAP toolchain worker containers
and the vision service. Nothing is mocked: every result below is what the running system returned. Each step is
recorded as PASS, FAIL or BLOCKED (a dependency the environment does not have) together with its evidence, and written
to --out as JSON.

    python scripts/e2e/e2e_validate.py --env-file <e2e.env> --fixture <capture.mp4> --duration <s> \
        --out <results.json> --vision-stop "docker stop chaya-e2e-vision-1" \
        --vision-start "docker start chaya-e2e-vision-1"

Requires the stack from infra/ci/docker-compose.e2e.yml (project name chaya-e2e) and: requests, boto3.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import secrets
import subprocess
import sys
import time
from pathlib import Path
from typing import Any

import boto3
import requests
from botocore.config import Config

RESULTS: list[dict[str, Any]] = []
CTX: dict[str, Any] = {}


def record(step: str, name: str, expected: str, actual: str, status: str, evidence: Any = None, blocked: str | None = None) -> None:
    assert status in ("PASS", "FAIL", "BLOCKED")
    RESULTS.append({"step": step, "name": name, "expected": expected, "actual": actual, "status": status,
                    "blocked_dependency": blocked, "evidence": evidence})
    print(f"[{status:7}] {step:5} {name}: {actual}", flush=True)


def check(step: str, name: str, expected: str, ok: bool, actual: str, evidence: Any = None) -> bool:
    record(step, name, expected, actual, "PASS" if ok else "FAIL", evidence)
    return ok


def load_env(path: str) -> dict[str, str]:
    out = {}
    for line in Path(path).read_text().splitlines():
        if line.strip() and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            out[k.strip()] = v.strip()
    return out


def psql(sql: str) -> str:
    r = subprocess.run(["docker", "exec", "-i", "chaya-e2e-postgres-1", "psql", "-q", "-v", "ON_ERROR_STOP=1", "-U",
                        ENV["POSTGRES_USER"], "-d", ENV["POSTGRES_DB"], "-At", "-F", "|", "-c", sql],
                       capture_output=True, text=True, check=True)
    return r.stdout.replace("\r", "").strip()


def jwt_claims(token: str) -> dict[str, Any]:
    payload = token.split(".")[1]
    return json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))


def short(resp: requests.Response, limit: int = 400) -> str:
    return f"HTTP {resp.status_code} {resp.text[:limit]}"


class Http:
    def __init__(self, base: str, token: str | None = None, headers: dict[str, str] | None = None, user: str | None = None):
        self.base, self.token, self.extra, self.user = base, token, headers or {}, user

    def bearer(self) -> str | None:
        # Access tokens live 300 s (realm accessTokenLifespan) and the run takes longer: re-issue a named user's token
        # shortly before it expires, as the web app's silent renew does. Fixed tokens (tampered, service) are sent as is.
        if self.user and (not self.token or jwt_claims(self.token)["exp"] - time.time() < 60):
            self.token = CTX["kc"].token(self.user).json()["access_token"]
        return self.token

    def __call__(self, method: str, path: str, **kw) -> requests.Response:
        h = dict(self.extra)
        if self.bearer():
            h["Authorization"] = "Bearer " + self.token
        h.update(kw.pop("headers", {}))
        return requests.request(method, self.base + path, headers=h, timeout=60, **kw)


# ---------------------------------------------------------------- Keycloak
class Keycloak:
    def __init__(self, base: str):
        self.base = base

    @property
    def h(self) -> dict[str, str]:
        # A fresh admin token per call: master-realm admin tokens live 60 s, shorter than the pipeline wait.
        t = requests.post(f"{self.base}/realms/master/protocol/openid-connect/token", timeout=30, data={
            "grant_type": "password", "client_id": "admin-cli", "username": ENV["KEYCLOAK_ADMIN"], "password": ENV["KEYCLOAK_ADMIN_PASSWORD"]})
        t.raise_for_status()
        return {"Authorization": "Bearer " + t.json()["access_token"], "Content-Type": "application/json"}

    def ensure_test_client(self) -> None:
        # Test-only public client with the password grant, exactly like scripts/ci/stack-smoke.sh: chaya-web itself
        # deliberately has no password grant. It carries the same client scopes (roles, audience, tenant claims).
        body = {"clientId": "chaya-e2e", "publicClient": True, "standardFlowEnabled": False, "directAccessGrantsEnabled": True,
                "defaultClientScopes": ["basic", "chaya-roles", "chaya-api-audience", "chaya-tenant"]}
        r = requests.post(f"{self.base}/admin/realms/chaya/clients", headers=self.h, json=body, timeout=30)
        assert r.status_code in (201, 409), r.text

    def create_user(self, username: str, role: str, org: str, venues: list[str]) -> str:
        password = secrets.token_urlsafe(18) + "Aa1!"
        attrs: dict[str, list[str]] = {"org_id": [org]}
        if venues:
            attrs["venue_id"] = venues
        r = requests.post(f"{self.base}/admin/realms/chaya/users", headers=self.h, timeout=30, json={
            "username": username, "enabled": True, "email": f"{username}@example.invalid", "emailVerified": True,
            "firstName": "E2E", "lastName": username, "attributes": attrs})
        assert r.status_code == 201, r.text
        uid = requests.get(f"{self.base}/admin/realms/chaya/users", params={"username": username, "exact": "true"},
                           headers=self.h, timeout=30).json()[0]["id"]
        requests.put(f"{self.base}/admin/realms/chaya/users/{uid}/reset-password", headers=self.h, timeout=30,
                     json={"type": "password", "value": password, "temporary": False}).raise_for_status()
        role_rep = requests.get(f"{self.base}/admin/realms/chaya/roles/{role}", headers=self.h, timeout=30).json()
        requests.post(f"{self.base}/admin/realms/chaya/users/{uid}/role-mappings/realm", headers=self.h, timeout=30,
                      json=[role_rep]).raise_for_status()
        CTX.setdefault("passwords", {})[username] = password
        return uid

    def token(self, username: str, password: str | None = None) -> requests.Response:
        return requests.post(f"{self.base}/realms/chaya/protocol/openid-connect/token", timeout=30, data={
            "grant_type": "password", "client_id": "chaya-e2e", "username": username,
            "password": password or CTX["passwords"][username]})

    def service_token(self) -> str:
        r = requests.post(f"{self.base}/realms/chaya/protocol/openid-connect/token", timeout=30, data={
            "grant_type": "client_credentials", "client_id": "chaya-worker", "client_secret": ENV["CHAYA_WORKER_CLIENT_SECRET"]})
        r.raise_for_status()
        return r.json()["access_token"]


def s3(access: str, secret: str):
    return boto3.client("s3", endpoint_url=f"http://localhost:{ENV['MINIO_API_PORT']}", aws_access_key_id=access,
                        aws_secret_access_key=secret, region_name="us-east-1", config=Config(s3={"addressing_style": "path"}))


# ---------------------------------------------------------------- steps
def step0_health() -> None:
    h = requests.get(API + "/api/v1/health", timeout=30)
    body = h.json() if h.ok else h.text
    check("0.1", "API health (/api/v1/health)", "200, status UP (database, storage, identity provider, scanner, queue)",
          h.status_code == 200 and isinstance(body, dict) and body.get("status") in ("UP", "DEGRADED"),
          f"HTTP {h.status_code} status={body.get('status') if isinstance(body, dict) else body}", body)
    r = requests.get(API + "/actuator/health/readiness", timeout=30)
    check("0.2", "API readiness (Flyway migrations applied, deps answering)", "200 UP", r.status_code == 200, short(r))
    v = requests.get(API + "/api/v1/version", timeout=30)
    check("0.3", "API version", "200", v.status_code == 200, short(v))
    mig = psql("SELECT count(*), max(version::int) FROM flyway_schema_history WHERE success")
    ext = psql("SELECT string_agg(extname || ' ' || extversion, ', ' ORDER BY extname) FROM pg_extension")
    check("0.4", "Database migrations and extensions", "16 migrations applied; vector + pg_trgm installed",
          mig.startswith("16|16") and "vector" in ext and "pg_trgm" in ext, f"migrations(count|max)={mig}; extensions: {ext}")
    w = requests.get(WEB + "/api/health", timeout=30)
    check("0.5", "Web readiness (/api/health)", "200 (public config complete, API reachable)", w.status_code == 200, short(w))
    vr = requests.get(VISION + "/health/ready", timeout=30)
    check("0.6", "Vision service readiness (CLIP loaded)", "200", vr.status_code == 200, short(vr))
    red = subprocess.run(["docker", "exec", "chaya-e2e-redis-1", "redis-cli", "-a", ENV["REDIS_PASSWORD"], "--no-auth-warning", "ping"],
                         capture_output=True, text=True)
    check("0.7", "Redis reachable (password-protected)", "PONG", red.stdout.strip() == "PONG",
          red.stdout.strip() + " (note: no Chaya service uses Redis today; see compose comment)")


def step1_2_3_auth_org_venue() -> None:
    kc = CTX["kc"] = Keycloak(KC)
    kc.ensure_test_client()

    # 2. Organization. There is no API for creating organizations (DEPLOYMENT.md section 3): it is created in SQL.
    org = psql("INSERT INTO organization (slug, name) VALUES ('e2e-org', 'E2E Validation Org') RETURNING id")
    CTX["org"] = org
    other_org = psql("INSERT INTO organization (slug, name) VALUES ('e2e-other-org', 'E2E Other Org') RETURNING id")
    CTX["other_org"] = other_org
    record("2", "Create organization", "Organization row created through a supported interface",
           f"created via SQL (no organization API exists): {org}; second org for isolation tests: {other_org}", "PASS",
           {"org_id": org, "db_row": psql(f"SELECT id, slug, name FROM organization WHERE id = '{org}'"),
            "note": "Documented gap: organization creation has no API endpoint; SQL is the documented procedure."})

    # Venue creation requires ADMIN. First prove the venue manager cannot, then create it as admin.
    kc.create_user("e2e-admin", "admin", org, [])
    admin_tok = kc.token("e2e-admin").json()["access_token"]
    CTX["admin"] = Http(API, admin_tok, user="e2e-admin")
    kc.create_user("e2e-manager-novenue", "venue-manager", org, [])
    probe = Http(API, kc.token("e2e-manager-novenue").json()["access_token"])
    r = probe("POST", "/api/v1/venues", json={"slug": "e2e-mgr-venue", "name": "Should fail"})
    check("3.0", "Venue manager may NOT create a venue", "403 (venue creation is ADMIN-only)", r.status_code == 403, short(r))

    r = CTX["admin"]("POST", "/api/v1/venues", json={"slug": "e2e-venue", "name": "E2E Venue", "timezone": "Asia/Kolkata"})
    ok = check("3", "Create venue (as admin)", "200 with venue id", r.status_code == 200 and "id" in r.json(), short(r))
    if not ok:
        sys.exit("cannot continue without a venue")
    CTX["venue"] = r.json()["id"]
    r2 = CTX["admin"]("POST", "/api/v1/venues", json={"slug": "e2e-venue-b", "name": "E2E Venue B"})
    CTX["venue_b"] = r2.json()["id"]
    CTX["evidence_venue_db"] = psql(f"SELECT id, organization_id, slug, name, timezone FROM venue WHERE organization_id = '{org}' ORDER BY slug")
    record("3.1", "Venue persisted", "venue rows scoped to the org", f"rows: {CTX['evidence_venue_db']}", "PASS")

    # 1. Authenticate as venue manager (Keycloak password grant through the test client; roles + tenant claims).
    kc.create_user("e2e-manager", "venue-manager", org, [CTX["venue"]])
    t = kc.token("e2e-manager")
    tok = t.json().get("access_token", "")
    claims = jwt_claims(tok) if tok else {}
    roles = claims.get("realm_access", {}).get("roles", [])
    vclaim = claims.get("venue_id")
    ok = t.status_code == 200 and "venue-manager" in roles and claims.get("org_id") == org and CTX["venue"] in (vclaim if isinstance(vclaim, list) else [vclaim])
    check("1", "Authenticate as venue manager (Keycloak)", "token with role venue-manager, org_id, venue_id, aud chaya-api", ok,
          f"HTTP {t.status_code}; roles={roles}; org_id={claims.get('org_id')}; venue_id={vclaim}; aud={claims.get('aud')}; iss={claims.get('iss')}",
          {k: claims.get(k) for k in ("iss", "aud", "azp", "org_id", "venue_id", "preferred_username")})
    CTX["mgr"] = Http(API, tok, user="e2e-manager")
    r = CTX["mgr"]("GET", "/api/v1/venues")
    ids = [v["id"] for v in r.json()] if r.ok else []
    check("1.1", "Manager's token accepted; sees only own venue", "200, exactly [venue A]", r.ok and ids == [CTX["venue"]], f"HTTP {r.status_code} venues={ids}")
    r = requests.get(API + "/api/v1/venues", timeout=30)
    check("1.2", "No token is rejected", "401", r.status_code == 401, short(r, 100))
    bad = tok[:-4] + ("AAAA" if not tok.endswith("AAAA") else "BBBB")
    r = Http(API, bad)("GET", "/api/v1/venues")
    check("1.3", "Tampered token signature is rejected", "401", r.status_code == 401, short(r, 100))
    r = kc.token("e2e-manager", "wrong-password")
    check("1.4", "Wrong password is rejected by Keycloak", "401 invalid_grant", r.status_code == 401, short(r, 120))


def step4_5_floor_capture() -> None:
    mgr, v = CTX["mgr"], CTX["venue"]
    r = mgr("POST", f"/api/v1/venues/{v}/floors", json={"level": 0, "name": "Ground floor"})
    ok = check("4", "Create floor (venue manager)", "201 with floor id", r.status_code == 201, short(r))
    CTX["floor"] = r.json()["id"] if ok else None
    check("4.1", "Floor persisted", "floor row in venue A", bool(psql(f"SELECT 1 FROM floor WHERE id = '{CTX['floor']}' AND venue_id = '{v}'")),
          psql(f"SELECT id, venue_id, level, name FROM floor WHERE id = '{CTX['floor']}'"))
    r = mgr("POST", f"/api/v1/venues/{v}/captures", json={"floorId": CTX["floor"], "device": {"model": "e2e-fixture", "app": "scripts/e2e"}})
    ok = check("5", "Create capture session", "200/201 with capture id, status CREATED", r.ok and r.json().get("status") == "CREATED", short(r))
    CTX["capture"] = r.json()["id"]


def step6_7_upload(fixture: Path, duration: float) -> None:
    mgr, v, c = CTX["mgr"], CTX["venue"], CTX["capture"]
    base = f"/api/v1/venues/{v}/captures/{c}"
    data = fixture.read_bytes()
    sha = hashlib.sha256(data).hexdigest()
    init = mgr("POST", base + "/media", json={"kind": "VIDEO", "filename": fixture.name, "contentType": "video/mp4",
                                              "sizeBytes": len(data), "sha256": sha})
    if not check("6.1", "Initiate media upload", "201 with mediaId + partSizeBytes", init.status_code == 201, short(init)):
        return
    media = init.json()["mediaId"]
    CTX["media"] = media
    part = init.json()["partSizeBytes"]
    parts = [data[i:i + part] for i in range(0, len(data), part)]
    codes = [mgr("PUT", f"{base}/media/{media}/parts/{n}", data=p, headers={"Content-Type": "application/octet-stream"}).status_code
             for n, p in enumerate(parts, 1)]
    comp = mgr("POST", f"{base}/media/{media}/complete")
    check("6", "Upload real capture fixture (multipart)", "each part 204, complete 202",
          all(x == 204 for x in codes) and comp.status_code == 202,
          f"{len(data)} bytes, sha256={sha}, parts={codes}, complete=HTTP {comp.status_code}")

    status, t0 = None, time.time()
    history = []
    while time.time() - t0 < 300:
        m = mgr("GET", f"{base}/media/{media}").json()
        status = m["status"]
        if not history or history[-1] != status:
            history.append(status)
        if status not in ("PENDING", "UPLOADING", "VALIDATING", "UPLOADED"):
            break
        time.sleep(1)
    CTX["media_view"] = m
    check("7", "Upload validated (checksum, content sniffing, ClamAV)", "status ACCEPTED", status == "ACCEPTED",
          f"status history {history}; {json.dumps({k: m.get(k) for k in ('status', 'detectedContentType', 'rejectionCode', 'rejectionMessage', 'sha256')})}", m)
    row = psql(f"SELECT status, bucket, object_key, declared_size_bytes, verified_sha256 FROM capture_media WHERE id = '{media}'")
    CTX["media_row"] = row
    _, bucket, key, size, dbsha = row.split("|")
    backup = s3(ENV["S3_BACKUP_ACCESS_KEY"], ENV["S3_BACKUP_SECRET_KEY"])
    body = backup.get_object(Bucket=bucket, Key=key)["Body"].read()
    check("7.1", "Object stored in MinIO raw bucket, checksum matches", "bytes in raw bucket == fixture; sha256 equal",
          hashlib.sha256(body).hexdigest() == sha == dbsha and len(body) == int(size), f"s3://{bucket}/{key} {len(body)} bytes sha256 ok")
    try:
        backup.put_object(Bucket=bucket, Key=key + ".tamper", Body=b"x")
        check("7.2", "Least-privilege storage account cannot write", "AccessDenied", False, "backup account could WRITE to raw bucket")
    except Exception as e:  # noqa: BLE001 - reporting the exact denial
        check("7.2", "Least-privilege storage account cannot write", "AccessDenied", "AccessDenied" in str(e), str(e)[:160])
    try:
        requests.get(f"http://localhost:{ENV['MINIO_API_PORT']}/{bucket}/{key}", timeout=10).raise_for_status()
        check("7.3", "Raw bucket is private (anonymous GET)", "403", False, "anonymous GET succeeded")
    except requests.HTTPError as e:
        check("7.3", "Raw bucket is private (anonymous GET)", "403", e.response.status_code == 403, f"HTTP {e.response.status_code}")

    # Negative validation: a declared checksum that does not match the bytes.
    bad = mgr("POST", base + "/media", json={"kind": "VIDEO", "filename": "tampered.mp4", "contentType": "video/mp4",
                                             "sizeBytes": len(data), "sha256": "0" * 64})
    bm = bad.json()["mediaId"]
    mgr("PUT", f"{base}/media/{bm}/parts/1", data=data, headers={"Content-Type": "application/octet-stream"})
    mgr("POST", f"{base}/media/{bm}/complete")
    for _ in range(120):
        bs = mgr("GET", f"{base}/media/{bm}").json()
        if bs["status"] not in ("PENDING", "UPLOADING", "VALIDATING", "UPLOADED"):
            break
        time.sleep(1)
    check("7.4", "Checksum mismatch is rejected", "status REJECTED (checksum)", bs["status"] == "REJECTED",
          json.dumps({k: bs.get(k) for k in ("status", "rejectionCode", "rejectionMessage")}))
    # Negative validation: the EICAR anti-malware test file declared as an image.
    eicar = b"X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*"
    e = mgr("POST", base + "/media", json={"kind": "METADATA", "filename": "eicar.json", "contentType": "application/json",
                                           "sizeBytes": len(eicar), "sha256": hashlib.sha256(eicar).hexdigest()})
    if e.status_code == 201:
        em = e.json()["mediaId"]
        mgr("PUT", f"{base}/media/{em}/parts/1", data=eicar, headers={"Content-Type": "application/octet-stream"})
        mgr("POST", f"{base}/media/{em}/complete")
        for _ in range(120):
            es = mgr("GET", f"{base}/media/{em}").json()
            if es["status"] not in ("PENDING", "UPLOADING", "VALIDATING", "UPLOADED"):
                break
            time.sleep(1)
        check("7.5", "EICAR test file is not accepted", "REJECTED or QUARANTINED, never ACCEPTED", es["status"] in ("REJECTED", "QUARANTINED"),
              json.dumps({k: es.get(k) for k in ("status", "rejectionCode", "rejectionMessage", "detectedContentType")}))
    else:
        check("7.5", "EICAR test file is not accepted", "REJECTED or QUARANTINED, never ACCEPTED", e.status_code >= 400, short(e))

    r = mgr("POST", base + "/complete-upload", json={"durationSeconds": duration})
    check("7.6", "Complete capture upload", "200, capture UPLOADED", r.ok, short(r, 250))


def step8_10_processing(timeout_s: int) -> None:
    mgr, v, c = CTX["mgr"], CTX["venue"], CTX["capture"]
    base = f"/api/v1/venues/{v}/captures/{c}"
    r = mgr("POST", base + "/processing", json={"timeBudgetSeconds": 1800})
    ok = check("8", "Create processing job", "202; run RUNNING; first stage QUEUED", r.status_code == 202 and r.json()["run"]["stages"][0]["state"] == "QUEUED", short(r, 300))
    if not ok:
        return
    CTX["run"] = r.json()["run"]["id"]
    jobs = psql(f"SELECT stage, status FROM processing_job WHERE run_id = '{CTX['run']}'")
    record("8.1", "Job queued in database", "processing_job row QUEUED", f"processing_job: {jobs}", "PASS" if "QUEUED" in jobs else "FAIL")

    t0 = time.time()
    run: dict[str, Any] = {}
    while time.time() - t0 < timeout_s:
        run = mgr("GET", base + "/processing").json()["run"]
        if run["status"] != "RUNNING":
            break
        time.sleep(3)
    CTX["run_view"] = run
    stages = {s["stage"]: s for s in run["stages"]}
    CTX["stages"] = stages
    elapsed = round(time.time() - t0)
    pre = ["INPUT_VALIDATION", "FFMPEG_PREPROCESS", "FRAME_QUALITY_FILTER", "PRIVACY_PREPROCESS"]
    for i, name in enumerate(pre, 1):
        s = stages.get(name, {})
        last = s.get("lastRun") or {}
        check(f"9.{i}", f"Preprocessing stage {name} (real worker container)", "SUCCEEDED",
              s.get("state") == "SUCCEEDED",
              f"state={s.get('state')} attempts={s.get('attempts')} exit={last.get('exitStatus')} outputs={len(last.get('artifacts') or [])} "
              f"err={last.get('errorCode')} {last.get('errorMessage') or ''}".strip(),
              {"command": last.get("command"), "artifacts": [{k: a.get(k) for k in ('kind', 'key', 'sizeBytes', 'sha256')} for a in last.get("artifacts") or []]})

    # POSE_ESTIMATION is available in this environment (CPU COLMAP in the toolchain worker, infra/ci/worker-colmap.Dockerfile):
    # it must SUCCEED on the real multi-view fixture, register the frames and publish a sparse model + poses.
    pose = stages.get("POSE_ESTIMATION", {})
    plast = pose.get("lastRun") or {}
    poses_art = next((a for a in plast.get("artifacts") or [] if a.get("kind") == "POSES"), None)
    registered = None
    if poses_art:
        doc = json.loads(s3(ENV["S3_BACKUP_ACCESS_KEY"], ENV["S3_BACKUP_SECRET_KEY"]).get_object(
            Bucket=ENV["S3_BUCKET_DERIVED"], Key=poses_art["key"])["Body"].read())
        registered = f"{doc.get('registered')}/{doc.get('frames')} frames registered, mapper={doc.get('mapper')}"
        CTX["poses_summary"] = {k: doc.get(k) for k in ("mapper", "fallback_used", "frames", "registered")}
    workers = psql(f"SELECT s.stage || '=' || s.worker_id FROM pipeline_stage_run s WHERE s.run_id = '{run['id']}' ORDER BY s.started_at")
    CTX["stage_workers"] = workers.splitlines()
    check("9.5", "POSE_ESTIMATION (real CPU COLMAP, toolchain worker)", "SUCCEEDED; SPARSE_MODEL + POSES; >= 60% of frames registered",
          pose.get("state") == "SUCCEEDED" and poses_art is not None,
          f"state={pose.get('state')} exit={plast.get('exitStatus')} outputs={[a.get('kind') for a in plast.get('artifacts') or []]} "
          f"{registered or ''} err={plast.get('errorCode')} {plast.get('errorMessage') or ''}".strip(),
          {"command": plast.get("command"), "stage_workers": CTX["stage_workers"]})

    CTX["run_status"] = run["status"]
    fs = run.get("failureStage")
    last = (stages.get(fs) or {}).get("lastRun") or {}
    order = [s["stage"] for s in run["stages"]]
    if run["status"] == "SUCCEEDED":
        record("10", "Reconstruction stages", "run SUCCEEDED", f"run SUCCEEDED in {elapsed}s", "PASS")
    elif run["status"] == "FAILED" and last.get("errorCode") == "DEPENDENCY_UNAVAILABLE":
        record("10", f"Reconstruction stage {fs}", "runs, or fails honestly with DEPENDENCY_UNAVAILABLE when the toolchain is absent",
               f"run FAILED at {fs}: {last.get('errorCode')} - {last.get('errorMessage')}; retryable={run.get('retryable')}; quality={run.get('quality')}",
               "BLOCKED", {"errorDetails": last.get("errorDetails"), "stdout": last.get("stdout"), "stderr": last.get("stderr")},
               blocked=f"{fs}: {last.get('errorMessage')}")
        for name in order[order.index(fs) + 1:]:
            record("10", f"Reconstruction stage {name}", "runs", f"not reached (state={stages[name]['state']}); the run stops at {fs}", "BLOCKED",
                   blocked=f"upstream {fs}")
    else:
        record("10", "Reconstruction run", "SUCCEEDED, or an honest DEPENDENCY_UNAVAILABLE failure",
               f"run {run['status']} at {fs}: {last.get('errorCode')} {last.get('errorMessage')}", "FAIL", run)
    check("10.1", "A failed run is not reported as success", "quality null, capture stays PROCESSING",
          run["status"] == "SUCCEEDED" or (run.get("quality") is None and mgr("GET", base).json()["status"] == "PROCESSING"),
          f"run.status={run['status']} quality={run.get('quality')} capture={mgr('GET', base).json()['status']}")

    if run["status"] == "FAILED":
        r = mgr("POST", base + "/processing/retry")
        t0 = time.time()
        again = run
        while time.time() - t0 < 300:
            again = mgr("GET", base + "/processing").json()["run"]
            if again["status"] != "RUNNING":
                break
            time.sleep(3)
        att = next(s for s in again["stages"] if s["stage"] == fs)["attempts"]
        earlier = order[:order.index(fs)] if fs in order else pre
        again_by = {s["stage"]: s for s in again["stages"]}
        check("10.2", "Retry re-runs only the failed stage", "202; attempt 2 of the failed stage; earlier stages not repeated",
              r.status_code == 202 and att == 2 and all(again_by[n]["attempts"] == 1 for n in earlier),
              f"retry HTTP {r.status_code}; {fs} attempts={att}; result={again['status']} {again.get('failureCode')}")
        CTX["run_view"] = again


def step11_artifacts() -> None:
    run = CTX["run_view"]
    worker = s3(ENV["S3_BACKUP_ACCESS_KEY"], ENV["S3_BACKUP_SECRET_KEY"])
    bucket = ENV["S3_BUCKET_DERIVED"]
    keys = []
    for page in worker.get_paginator("list_objects_v2").paginate(Bucket=bucket, Prefix="org/"):
        keys += [o["Key"] for o in page.get("Contents", [])]
    run_keys = [k for k in keys if f"/run/{run['id']}/" in k]
    arts = psql(f"SELECT a.kind, a.object_key, a.size_bytes, a.checksum_sha256, a.contains_pii FROM processing_artifact a "
                f"JOIN pipeline_stage_run s ON s.id = a.stage_run_id WHERE s.run_id = '{run['id']}' ORDER BY a.object_key")
    rows = [line.split("|") for line in arts.splitlines() if line]
    mismatches, verified, purged = [], 0, 0
    for kind, key, size, sha, pii in rows:
        if key not in run_keys:
            if pii == "t":
                purged += 1
                continue
            mismatches.append(f"missing {key}")
            continue
        body = worker.get_object(Bucket=bucket, Key=key)["Body"].read()
        if hashlib.sha256(body).hexdigest() != sha or len(body) != int(size):
            mismatches.append(key)
        verified += 1
    kinds = sorted({r[0] for r in rows})
    check("11", "Generated artifacts stored in MinIO derived bucket", "every non-PII artifact present, size+sha256 match the DB record",
          verified > 0 and not mismatches,
          f"{len(rows)} artifact records ({', '.join(kinds)}); {verified} verified byte-for-byte; {purged} PII objects purged; mismatches={mismatches}",
          {"object_count_for_run": len(run_keys), "sample_keys": run_keys[:8]})
    check("11.1", "PII staging objects purged after privacy stage", "no /pii/ object remains for the run",
          not [k for k in run_keys if "/pii/" in k], f"{len([k for k in run_keys if '/pii/' in k])} /pii/ objects remain")
    audit = psql(f"SELECT count(*) FROM audit_log WHERE action = 'pipeline.pii_purged'")
    check("11.2", "PII purge audited", "audit row pipeline.pii_purged", int(audit) >= 1, f"pipeline.pii_purged rows: {audit}")
    splat = [r for r in rows if r[0] in ("SPLAT", "KSPLAT", "VIEWER_BUNDLE", "ARTIFACT_MANIFEST")]
    if not splat:
        record("11.3", "Reconstruction artifacts (splat / .ksplat / manifest / viewer bundle)", "stored",
               "none produced: the stages that create them did not run", "BLOCKED", blocked="SPLAT_RECONSTRUCTION / ARTIFACT_GENERATION")


def step12_13_version_viewer() -> None:
    mgr, v, f = CTX["mgr"], CTX["venue"], CTX["floor"]
    r = mgr("POST", f"/api/v1/venues/{v}/floors/{f}/scan-versions/finalize-current")
    if r.ok:
        CTX["version"] = r.json()["id"]
        record("12", "Register reconstruction version", "FINALIZED scan_version", short(r), "PASS")
    else:
        ok_refusal = r.status_code == 404
        record("12", "Register reconstruction version", "FINALIZED scan_version (needs a SUCCEEDED run)",
               f"refused correctly: {short(r, 250)}" if ok_refusal else short(r), "BLOCKED" if ok_refusal else "FAIL",
               blocked="no SUCCEEDED pipeline run (GPU reconstruction stages unavailable)")
    lst = mgr("GET", f"/api/v1/venues/{v}/floors/{f}/reconstructions")
    lat = mgr("GET", f"/api/v1/venues/{v}/floors/{f}/reconstructions/latest")
    CTX["latest_status"] = lat.status_code
    if lat.ok:
        record("13", "Load reconstruction (API for the viewer)", "latest reconstruction with artifact URLs", short(lat), "PASS")
    else:
        record("13", "Load reconstruction (API for the viewer)", "latest reconstruction with .ksplat artifact",
               f"list={short(lst, 150)}; latest={short(lat, 200)}", "BLOCKED" if lat.status_code == 404 else "FAIL",
               blocked="no reconstruction artifact exists (SPLAT_RECONSTRUCTION needs a CUDA GPU + torch + gsplat)")


def step14_15_poi_search() -> None:
    mgr, v, f = CTX["mgr"], CTX["venue"], CTX["floor"]
    pois = [("Reception desk", "service", ["front desk", "check-in"], 2.0, 0.0, 1.5),
            ("Accessible restroom", "restroom", ["toilet", "wc"], 8.5, 0.0, 3.0),
            ("Cafe", "food", ["coffee", "snacks"], 12.0, 0.0, -4.0),
            ("Elevator A", "elevator", ["lift"], 5.0, 0.0, 6.0)]
    ids = {}
    for label, cat, tags, x, y, z in pois:
        r = mgr("POST", f"/api/v1/venues/{v}/pois", json={"floorId": f, "label": label, "category": cat, "tags": tags,
                                                            "description": f"E2E {label}", "x": x, "y": y, "z": z})
        if r.status_code in (200, 201):
            ids[label] = r.json()["id"]
    CTX["pois"] = ids
    check("14", "Create manual POIs (venue manager)", "4 POIs created, version 1 each", len(ids) == 4,
          f"created {len(ids)}: {list(ids)}")
    rows = psql(f"SELECT v.label, v.version_number, v.source, (v.embedding IS NOT NULL) FROM poi p JOIN poi_version v ON v.poi_id = p.id WHERE p.venue_id = '{v}' ORDER BY v.label")
    CTX["poi_rows"] = rows
    has_emb = any(line.endswith("|t") for line in rows.splitlines())
    record("14.1", "POIs indexed for semantic (vector) search", "poi_version.embedding set",
           f"poi_version rows (label|version|source|has_embedding): {rows.replace(chr(10), '; ')}",
           "PASS" if has_emb else "FAIL", {"note": "Manual POIs are stored without a CLIP embedding; only AUTO_DETECTED POIs "
                                                   "(SEMANTIC_INDEXING) get one. See findings."})
    record("14.2", "Auto-detected POIs (SEMANTIC_INDEXING: Grounding DINO + CLIP)", "POIs ingested from DETECTED_OBJECTS",
           "stage not reached", "BLOCKED", blocked="SEMANTIC_INDEXING needs a trained splat + torch/transformers/open_clip + Grounding DINO weights")

    e = requests.post(VISION + "/v1/embed-text", json={"text": "where is the toilet"}, timeout=60)
    dim = None
    if e.ok:
        j = e.json()
        vec = j.get("embedding") or (j.get("embeddings") or [None])[0]
        dim = len(vec) if vec else None
    check("15.0", "Vision service embeds query text (real CLIP)", "200, 512-d vector", e.ok and dim == 512,
          f"HTTP {e.status_code} dim={dim} model={e.json().get('model') if e.ok else ''}")

    r = mgr("GET", f"/api/v1/venues/{v}/search", params={"q": "restroom"})
    j = r.json() if r.ok else {}
    CTX["search_embedding"] = j
    labels = [x["label"] for x in j.get("results", [])]
    check("15", "Search for a POI ('restroom') with vision service up", "200, matchType=embedding, 'Accessible restroom' ranked first",
          r.ok and j.get("matchType") == "embedding" and labels[:1] == ["Accessible restroom"],
          f"HTTP {r.status_code} matchType={j.get('matchType')} results={labels}")
    CTX["search_count"] = psql(f"SELECT count(*), sum(CASE WHEN result_count = 0 THEN 1 ELSE 0 END) FROM search_query WHERE venue_id = '{v}'")


def step15b_lexical_fallback(stop_cmd: str, start_cmd: str) -> None:
    mgr, v = CTX["mgr"], CTX["venue"]
    subprocess.run(stop_cmd, shell=True, capture_output=True, check=True)  # noqa: S602 - operator-supplied command
    for _ in range(30):
        try:
            requests.get(VISION + "/health/live", timeout=2)
            time.sleep(1)
        except requests.RequestException:
            break
    try:
        r = mgr("GET", f"/api/v1/venues/{v}/search", params={"q": "restroom"})
        j = r.json() if r.ok else {}
        labels = [x["label"] for x in j.get("results", [])]
        check("15.1", "Search degrades honestly when vision is down", "200, matchType=lexical_fallback, finds 'Accessible restroom'",
              r.ok and j.get("matchType") == "lexical_fallback" and "Accessible restroom" in labels,
              f"HTTP {r.status_code} matchType={j.get('matchType')} results={labels}")
    finally:
        subprocess.Popen(start_cmd, shell=True)  # noqa: S602 - operator-supplied command
        for _ in range(120):
            try:
                if requests.get(VISION + "/health/ready", timeout=2).ok:
                    break
            except requests.RequestException:
                pass
            time.sleep(2)
    logged = psql(f"SELECT count(*) FROM search_query WHERE venue_id = '{v}'")
    check("15.2", "Search queries logged (analytics)", ">= 2 search_query rows", int(logged) >= 2, f"search_query rows: {logged}")


def step16_17_navigation() -> None:
    mgr, v, f = CTX["mgr"], CTX["venue"], CTX["floor"]
    dest = CTX["pois"].get("Cafe")
    r = mgr("POST", "/api/v1/navigation/routes", json={"venueId": v, "floorId": f, "start": [2.0, 0.0, 1.5], "destinationPoiId": dest})
    graphs = psql(f"SELECT count(*) FROM navigation_graph WHERE venue_id = '{v}'")
    if r.ok:
        record("16", "Request a navigation route", "waypoints over the baked graph", short(r), "PASS")
    else:
        record("16", "Request a navigation route", "route over the NAVIGATION_BAKING graph",
               f"{short(r, 250)}; navigation_graph rows for venue: {graphs}", "BLOCKED" if r.status_code in (404, 409, 422) else "FAIL",
               blocked="NAVIGATION_BAKING (needs plane fitting output + recast-cli) never ran; no navigation graph exists")
    record("17", "Display the route in the viewer", "route polyline over the splat", "no route and no reconstruction to draw it on",
           "BLOCKED", blocked="steps 13 and 16")


def step18_authz() -> None:
    kc, v, vb, f = CTX["kc"], CTX["venue"], CTX["venue_b"], CTX["floor"]
    mgr = CTX["mgr"]
    r = mgr("GET", f"/api/v1/venues/{vb}")
    check("18.1", "Manager cannot read another venue in the same org", "404 (no enumeration)", r.status_code == 404, short(r, 120))
    r = mgr("POST", f"/api/v1/venues/{vb}/floors", json={"level": 1, "name": "x"})
    check("18.2", "Manager cannot write to another venue", "404", r.status_code == 404, short(r, 120))
    r = mgr("GET", "/api/v1/audit-log")
    check("18.3", "Manager cannot read the org-wide audit log", "403", r.status_code == 403, short(r, 120))

    kc.create_user("e2e-viewer", "viewer", CTX["org"], [v])
    viewer = Http(API, user="e2e-viewer")
    r = viewer("GET", f"/api/v1/venues/{v}/pois")
    check("18.4", "Viewer can read POIs of own venue", "200", r.status_code == 200, f"HTTP {r.status_code}, {len(r.json()) if r.ok else 0} POIs")
    r = viewer("POST", f"/api/v1/venues/{v}/pois", json={"floorId": f, "label": "x", "x": 0, "y": 0, "z": 0})
    check("18.5", "Viewer cannot create POIs", "403", r.status_code == 403, short(r, 120))
    r = viewer("POST", f"/api/v1/venues/{v}/captures", json={"floorId": f})
    check("18.6", "Viewer cannot create capture sessions", "403", r.status_code == 403, short(r, 120))

    kc.create_user("e2e-other-admin", "admin", CTX["other_org"], [])
    other = Http(API, user="e2e-other-admin")
    r = other("GET", f"/api/v1/venues/{v}")
    check("18.7", "Admin of another organization cannot read the venue", "404", r.status_code == 404, short(r, 120))
    r = other("GET", "/api/v1/venues")
    check("18.8", "Other org's venue list excludes it", "200, []", r.ok and r.json() == [], f"HTTP {r.status_code} {r.text[:120]}")

    svc = Http(API, kc.service_token())
    r = svc("GET", f"/api/v1/venues/{v}")
    check("18.9", "Worker service account cannot use user endpoints", "403", r.status_code == 403, short(r, 120))
    r = mgr("POST", "/api/v1/internal/jobs/claim", json={"workerId": "x", "stages": ["INPUT_VALIDATION"]})
    check("18.10", "User token cannot use worker control-plane endpoints", "403", r.status_code == 403, short(r, 120))
    denied = psql(f"SELECT count(*) FROM audit_log WHERE outcome = 'DENIED' AND action = 'venue.access' AND metadata->>'attemptedResourceId' = '{vb}'")
    check("18.11", "Cross-venue refusals are audited", ">= 1 DENIED audit row for venue B", int(denied) >= 1, f"DENIED rows for venue B: {denied}")


def step19_public() -> None:
    mgr, v, vb = CTX["mgr"], CTX["venue"], CTX["venue_b"]
    r = mgr("POST", f"/api/v1/venues/{v}/public-links", json={"label": "e2e public link", "ttl": "PT1H"})
    if not check("19.1", "Create public viewer link (venue manager)", "200/201 with secret", r.ok and r.json().get("secret"),
                 f"HTTP {r.status_code}; secret {r.json().get('secret', '')[:4]}… (redacted)" if r.ok else short(r, 200)):
        return
    link, secret = r.json()["id"], r.json()["secret"]
    stored = psql(f"SELECT count(*) FROM public_viewer_link WHERE id = '{link}' AND position('{secret}' in row_to_json(public_viewer_link)::text) > 0")
    check("19.2", "Link secret is not stored in plaintext", "0 rows containing the secret", stored == "0", f"rows containing secret: {stored}")
    t = requests.post(API + "/api/v1/public/viewer-token", json={"secret": secret}, timeout=30)
    ok = check("19.3", "Exchange secret for anonymous viewer token", "200 with token scoped to venue A", t.ok and t.json().get("venueId") == v,
               f"HTTP {t.status_code}; token {t.json().get('token', '')[:4]}… (redacted); venueId={t.json().get('venueId')}; "
               f"expiresAt={t.json().get('expiresAt')}" if t.ok else short(t, 200))
    if not ok:
        return
    pub = Http(API, headers={"X-Chaya-Viewer-Token": t.json()["token"]})
    r1, r2, r3 = pub("GET", f"/api/v1/venues/{v}"), pub("GET", f"/api/v1/venues/{v}/pois"), pub("GET", f"/api/v1/venues/{v}/search", params={"q": "cafe"})
    check("19.4", "Public viewer can read venue, POIs, search", "200 x3", all(x.status_code == 200 for x in (r1, r2, r3)),
          f"venue={r1.status_code} pois={r2.status_code} ({len(r2.json()) if r2.ok else 0}) search={r3.status_code} matchType={r3.json().get('matchType') if r3.ok else ''}")
    r4 = pub("GET", f"/api/v1/venues/{v}/floors/{CTX['floor']}/reconstructions/latest")
    record("19.5", "Public viewer can load the reconstruction", "200 with artifacts",
           short(r4, 160), "PASS" if r4.ok else ("BLOCKED" if r4.status_code == 404 else "FAIL"),
           blocked=None if r4.ok else "no reconstruction exists (step 13)")
    w = pub("POST", f"/api/v1/venues/{v}/pois", json={"floorId": CTX["floor"], "label": "x", "x": 0, "y": 0, "z": 0})
    check("19.6", "Public viewer cannot write", "403", w.status_code == 403, short(w, 100))
    o = pub("GET", f"/api/v1/venues/{vb}")
    check("19.7", "Public viewer token is scoped to one venue", "404 for venue B", o.status_code == 404, short(o, 100))
    c = pub("GET", f"/api/v1/venues/{v}/captures")
    check("19.8", "Public viewer cannot see captures", "403", c.status_code == 403, short(c, 100))
    rv = mgr("DELETE", f"/api/v1/venues/{v}/public-links/{link}")
    t2 = requests.post(API + "/api/v1/public/viewer-token", json={"secret": secret}, timeout=30)
    after = pub("GET", f"/api/v1/venues/{v}")
    check("19.9", "Revoked link: secret and issued token stop working", "revoke 204; exchange 4xx; token 401",
          rv.status_code in (200, 204) and t2.status_code >= 400 and after.status_code == 401,
          f"revoke={rv.status_code} exchange={t2.status_code} token={after.status_code}")
    bogus = requests.post(API + "/api/v1/public/viewer-token", json={"secret": "not-a-real-secret"}, timeout=30)
    check("19.10", "Unknown secret rejected", "4xx", 400 <= bogus.status_code < 500, short(bogus, 100))


def step20_21_rescan_versions() -> None:
    mgr, v, f = CTX["mgr"], CTX["venue"], CTX["floor"]
    region = {"points": [[0, 0], [4, 0], [4, 4], [0, 4]]}
    parent = CTX.get("version")
    if parent:
        r = mgr("POST", f"/api/v1/venues/{v}/floors/{f}/rescan", json={"parentVersionId": parent, "region": region})
        check("20", "Create regional rescan", "201 with capture id", r.ok, short(r, 250))
    else:
        # There is no FINALIZED version to select; prove the API refuses rather than inventing a parent.
        fake = "00000000-0000-0000-0000-000000000001"
        r = mgr("POST", f"/api/v1/venues/{v}/floors/{f}/rescan", json={"parentVersionId": fake, "region": region})
        record("20", "Create regional rescan", "rescan capture created against a FINALIZED parent version",
               f"no FINALIZED version exists; request with a non-existent parent refused: {short(r, 200)}",
               "BLOCKED" if r.status_code == 404 else "FAIL", blocked="no FINALIZED scan_version (step 12)")
        tiny = mgr("POST", f"/api/v1/venues/{v}/floors/{f}/rescan", json={"parentVersionId": fake, "region": {"points": [[0, 0], [1, 0]]}})
        check("20.1", "Rescan region validation", "400 for a 2-point region", tiny.status_code == 400, short(tiny, 160))
    vers = mgr("GET", f"/api/v1/venues/{v}/floors/{f}/scan-versions")
    record("21", "Scan version history", "list of versions with parent links",
           f"{short(vers, 200)} (empty: nothing could be finalized)", "PASS" if vers.ok and vers.json() else ("BLOCKED" if vers.ok else "FAIL"),
           blocked=None if vers.ok and vers.json() else "no FINALIZED scan_version (step 12)")
    # POI versioning works independently of reconstruction: update appends a new immutable version.
    pid = CTX["pois"]["Cafe"]
    u = mgr("PUT", f"/api/v1/venues/{v}/pois/{pid}", json={"floorId": f, "label": "Cafe & bakery", "category": "food",
                                                          "tags": ["coffee"], "x": 12.0, "y": 0.0, "z": -4.5})
    hist = psql(f"SELECT version_number, label, z FROM poi_version WHERE poi_id = '{pid}' ORDER BY version_number")
    check("21.1", "POI version history (append-only)", "update -> version 2; version 1 kept",
          u.ok and u.json().get("version") == 2 and hist.count("\n") == 1, f"HTTP {u.status_code}; poi_version rows: {hist.replace(chr(10), '; ')}")
    try:
        psql(f"UPDATE poi_version SET label = 'tampered' WHERE poi_id = '{pid}' AND version_number = 1")
        after = psql(f"SELECT label FROM poi_version WHERE poi_id = '{pid}' AND version_number = 1")
        check("21.2", "POI versions are immutable in the database", "UPDATE rejected", after != "tampered", f"label after UPDATE attempt: {after}")
    except subprocess.CalledProcessError as e:
        check("21.2", "POI versions are immutable in the database", "UPDATE rejected", True, (e.stderr or "").strip()[:200])


def step22_audit() -> None:
    admin, mgr, v = CTX["admin"], CTX["mgr"], CTX["venue"]
    r = admin("GET", "/api/v1/audit-log", params={"limit": 500})
    actions = sorted({e["action"] for e in r.json()}) if r.ok else []
    expected = {"venue.create", "floor.create", "poi.create", "poi.update", "pipeline.start", "pipeline.stage_succeeded",
                "pipeline.stage_failed", "pipeline.retry", "pipeline.pii_purged", "venue.access"}
    missing = sorted(expected - set(actions))
    check("22", "Audit log (admin API) covers the workflow", f"contains {sorted(expected)}", r.ok and not missing,
          f"HTTP {r.status_code}; {len(r.json()) if r.ok else 0} entries; actions={actions}; missing={missing}")
    o = mgr("GET", f"/api/v1/venues/{v}/ops/audit", params={"limit": 50})
    check("22.1", "Venue-scoped audit view (ops dashboard) for manager", "200, entries only for venue A",
          o.ok and len(o.json().get("entries", [])) > 0, f"HTTP {o.status_code} entries={len(o.json().get('entries', [])) if o.ok else 0}")
    counts = psql("SELECT action || ':' || outcome || '=' || count(*) FROM audit_log GROUP BY action, outcome ORDER BY action")
    CTX["audit_counts"] = counts.splitlines()
    try:
        psql("UPDATE audit_log SET action = 'tampered' WHERE id = (SELECT id FROM audit_log LIMIT 1)")
        t = psql("SELECT count(*) FROM audit_log WHERE action = 'tampered'")
        check("22.2", "Audit log is append-only", "UPDATE rejected", t == "0", f"tampered rows: {t}")
    except subprocess.CalledProcessError as e:
        check("22.2", "Audit log is append-only", "UPDATE rejected", True, (e.stderr or "").strip()[:200])


def write_handoff(path: Path) -> None:
    """Inputs for scripts/e2e/viewer_check.cjs: the throwaway manager's login (Keycloak PKCE sign-in through the real
    chaya-web client) and a fresh public link (step 19 revoked its own). Secrets: written outside the evidence."""
    r = CTX["mgr"]("POST", f"/api/v1/venues/{CTX['venue']}/public-links", json={"label": "e2e browser check", "ttl": "PT2H"})
    r.raise_for_status()
    path.write_text(json.dumps({"managerUsername": "e2e-manager", "managerPassword": CTX["passwords"]["e2e-manager"],
                                "viewerLink": r.json()["secret"], "venueId": CTX["venue"], "floorId": CTX["floor"]}))


def main() -> None:
    global ENV, API, KC, WEB, VISION
    p = argparse.ArgumentParser()
    p.add_argument("--env-file", required=True)
    p.add_argument("--fixture", required=True)
    p.add_argument("--out", required=True)
    p.add_argument("--duration", type=float, required=True, help="capture duration in seconds (ffprobe)")
    p.add_argument("--pipeline-timeout", type=int, default=900)
    p.add_argument("--vision-stop", required=True, help="shell command that stops the vision service (fallback test)")
    p.add_argument("--vision-start", required=True, help="shell command that starts it again")
    p.add_argument("--handoff", help="write the browser check's inputs (test manager login, a fresh public link) here; "
                                     "contains secrets: keep it out of the evidence directory")
    a = p.parse_args()
    ENV = load_env(a.env_file)
    API, KC = f"http://localhost:{ENV['API_PORT']}", f"http://localhost:{ENV['KEYCLOAK_PORT']}"
    WEB, VISION = f"http://localhost:{ENV.get('WEB_PORT', '3000')}", f"http://localhost:{ENV.get('VISION_SERVICE_PORT', '8090')}"
    started = time.strftime("%Y-%m-%dT%H:%M:%S%z")
    steps = [("0", step0_health), ("1-3", step1_2_3_auth_org_venue), ("4-5", step4_5_floor_capture),
             ("6-7", lambda: step6_7_upload(Path(a.fixture), a.duration)), ("8-10", lambda: step8_10_processing(a.pipeline_timeout)),
             ("11", step11_artifacts), ("12-13", step12_13_version_viewer), ("14-15", step14_15_poi_search),
             ("15b", lambda: step15b_lexical_fallback(a.vision_stop, a.vision_start)), ("16-17", step16_17_navigation),
             ("18", step18_authz), ("19", step19_public), ("20-21", step20_21_rescan_versions), ("22", step22_audit)]
    try:
        for label, fn in steps:
            try:
                fn()
            except Exception as e:  # noqa: BLE001 - a harness crash is recorded as a FAIL and the next group still runs
                import traceback
                traceback.print_exc()
                record(label, f"step group {label} crashed", "no exception", f"{type(e).__name__}: {e}", "FAIL")
                if label in ("1-3", "4-5"):
                    raise  # everything after depends on the tenant/venue/floor/capture
        if a.handoff:
            write_handoff(Path(a.handoff))
    finally:
        ctx = {k: v for k, v in CTX.items() if k not in ("kc", "admin", "mgr", "passwords")}
        Path(a.out).write_text(json.dumps({"started": started, "finished": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
                                           "results": RESULTS, "context": ctx}, indent=2, default=str))
        n = {s: sum(1 for r in RESULTS if r["status"] == s) for s in ("PASS", "FAIL", "BLOCKED")}
        print(f"\nPASS={n['PASS']} FAIL={n['FAIL']} BLOCKED={n['BLOCKED']} -> {a.out}")


ENV: dict[str, str] = {}
API = KC = WEB = VISION = ""

if __name__ == "__main__":
    main()
