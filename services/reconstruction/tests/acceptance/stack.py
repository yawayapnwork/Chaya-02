"""The cross-service acceptance suite's harness (docs/ACCEPTANCE.md): how it reaches a running Chaya stack, records evidence,
plays the stages this host cannot execute, and drives a capture through the pipeline.

Everything is driven through the product's own interfaces: the public API, Keycloak's token and admin endpoints, the worker
control plane, and object storage with the worker's own account (as a worker publishes). Nothing is intercepted or mocked.

STAND-INS. Four stages need what no CI runner has: COLMAP over frames that depict the scene (POSE_ESTIMATION), CUDA/gsplat
(SPLAT_RECONSTRUCTION) and the vision models over those frames (SEMANTIC_SEGMENTATION, SEMANTIC_INDEXING). The capture is a
synthetic video, so even with the tools those stages would have nothing of the scene to reconstruct. The suite claims them
through the worker API as the worker service account and reports each with ``command.standIn = true``; what each publishes
is derived from the committed synthetic viewer scene (packages/contracts/fixtures/viewer-scene), never presented as a
reconstruction. Every other stage is claimed and executed by a real worker running production stage code.
"""

from __future__ import annotations

import base64
import datetime as dt
import hashlib
import json
import os
import secrets
import sys
import time
from collections.abc import Callable
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import numpy as np
import requests

REPO = Path(__file__).resolve().parents[4]
FIXTURE_DIR = REPO / "packages" / "contracts" / "fixtures" / "viewer-scene"
FIXTURE = json.loads((FIXTURE_DIR / "fixture.json").read_text(encoding="utf-8"))
sys.path.insert(0, str(FIXTURE_DIR))

STAND_IN_STAGES = ("POSE_ESTIMATION", "SPLAT_RECONSTRUCTION", "SEMANTIC_SEGMENTATION", "SEMANTIC_INDEXING")
STAND_IN_NOTE = ("ACCEPTANCE STAND-IN: this stage was NOT executed. Its output is derived from the committed synthetic "
                 "viewer-scene fixture (not a reconstruction of any venue), so the stages after it run on known geometry.")
TERMINAL = ("SUCCEEDED", "PARTIAL", "FAILED", "CANCELLED")



def sha(b: bytes) -> str:
    return hashlib.sha256(b).hexdigest()


def now() -> str:
    return dt.datetime.now(dt.UTC).isoformat()


def jwt_claims(token: str) -> dict[str, Any]:
    payload = token.split(".")[1]
    return json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))


# ------------------------------------------------------------------------------------------------ configuration
@dataclass(frozen=True)
class Config:
    """Where the stack is, from ACC_* variables (scripts/acceptance/run-acceptance.sh sets them). Nothing is defaulted to a
    credential: a missing required value is reported, and the suite does not run against a guess."""

    api: str
    keycloak: str
    kc_admin: str
    kc_admin_password: str
    worker_secret: str
    s3_endpoint: str
    s3_worker_key: str
    s3_worker_secret: str
    bucket_derived: str
    org_a: str
    org_b: str
    run_tag: str
    vision: str | None
    s3_root_key: str | None
    s3_root_secret: str | None
    evidence: Path | None
    handoff: Path | None
    lease_wait_max: int

    REQUIRED = ("ACC_API_URL", "ACC_KEYCLOAK_URL", "ACC_KEYCLOAK_ADMIN", "ACC_KEYCLOAK_ADMIN_PASSWORD", "ACC_WORKER_CLIENT_SECRET",
                "ACC_S3_ENDPOINT", "ACC_S3_WORKER_ACCESS_KEY", "ACC_S3_WORKER_SECRET_KEY", "ACC_ORG_A", "ACC_ORG_B")

    @staticmethod
    def missing() -> list[str]:
        return [k for k in Config.REQUIRED if not os.environ.get(k)]

    @staticmethod
    def from_env() -> Config:
        e = os.environ
        return Config(
            api=e["ACC_API_URL"].rstrip("/"), keycloak=e["ACC_KEYCLOAK_URL"].rstrip("/"), kc_admin=e["ACC_KEYCLOAK_ADMIN"],
            kc_admin_password=e["ACC_KEYCLOAK_ADMIN_PASSWORD"], worker_secret=e["ACC_WORKER_CLIENT_SECRET"],
            s3_endpoint=e["ACC_S3_ENDPOINT"], s3_worker_key=e["ACC_S3_WORKER_ACCESS_KEY"], s3_worker_secret=e["ACC_S3_WORKER_SECRET_KEY"],
            bucket_derived=e.get("ACC_S3_BUCKET_DERIVED", "chaya-derived"), org_a=e["ACC_ORG_A"], org_b=e["ACC_ORG_B"],
            run_tag=e.get("ACC_RUN_TAG") or secrets.token_hex(3), vision=(e.get("ACC_VISION_URL") or "").rstrip("/") or None,
            s3_root_key=e.get("ACC_S3_ROOT_ACCESS_KEY") or None, s3_root_secret=e.get("ACC_S3_ROOT_SECRET_KEY") or None,
            evidence=Path(e["ACC_EVIDENCE"]) if e.get("ACC_EVIDENCE") else None,
            handoff=Path(e["ACC_HANDOFF"]) if e.get("ACC_HANDOFF") else None,
            lease_wait_max=int(e.get("ACC_LEASE_WAIT_MAX", "480")))


# ------------------------------------------------------------------------------------------------ evidence
@dataclass
class Check:
    id: str
    name: str
    expected: str
    status: str  # PASS | FAIL
    actual: str
    evidence: Any = None


class Evidence:
    """Every check of the current test, in order. A test passes only if every check passed; a FAIL stops it."""

    def __init__(self) -> None:
        self.checks: list[Check] = []

    def check(self, cid: str, name: str, expected: str, ok: bool, actual: str, evidence: Any = None) -> None:
        c = Check(cid, name, expected, "PASS" if ok else "FAIL", str(actual)[:1500], evidence)
        self.checks.append(c)
        print(f"[{c.status}] {cid} {name}: {c.actual[:300]}", flush=True)
        assert ok, f"{cid} {name}: expected {expected}; got {actual}"


# ------------------------------------------------------------------------------------------------ HTTP and identity
class Keycloak:
    """Keycloak's admin and token endpoints. Test users and the test-only clients are created in the stack's realm, as the
    stack smoke does: chaya-web itself has no password grant."""

    CLIENT = "chaya-acceptance"
    SHORT_CLIENT = "chaya-acceptance-short"  # access tokens live 10 s: lets the suite observe a real expiry
    SCOPES = ["basic", "chaya-roles", "chaya-api-audience", "chaya-tenant"]

    def __init__(self, cfg: Config) -> None:
        self.cfg, self.passwords = cfg, {}

    def _admin(self) -> dict[str, str]:
        t = requests.post(f"{self.cfg.keycloak}/realms/master/protocol/openid-connect/token", timeout=30, data={
            "grant_type": "password", "client_id": "admin-cli", "username": self.cfg.kc_admin, "password": self.cfg.kc_admin_password})
        t.raise_for_status()
        return {"Authorization": "Bearer " + t.json()["access_token"], "Content-Type": "application/json"}

    def ensure_clients(self) -> None:
        for client_id, attrs in ((self.CLIENT, {}), (self.SHORT_CLIENT, {"access.token.lifespan": "10"})):
            body = {"clientId": client_id, "publicClient": True, "standardFlowEnabled": False, "directAccessGrantsEnabled": True,
                    "defaultClientScopes": self.SCOPES, "attributes": attrs}
            r = requests.post(f"{self.cfg.keycloak}/admin/realms/chaya/clients", headers=self._admin(), json=body, timeout=30)
            assert r.status_code in (201, 409), f"creating Keycloak client {client_id}: {r.status_code} {r.text[:200]}"

    def create_user(self, username: str, role: str, org: str, venues: list[str]) -> str:
        password = secrets.token_urlsafe(18) + "Aa1!"
        attrs: dict[str, list[str]] = {"org_id": [org]}
        if venues:
            attrs["venue_id"] = venues
        h = self._admin()
        r = requests.post(f"{self.cfg.keycloak}/admin/realms/chaya/users", headers=h, timeout=30, json={
            "username": username, "enabled": True, "email": f"{username}@example.invalid", "emailVerified": True,
            "firstName": "Acceptance", "lastName": username, "attributes": attrs})
        assert r.status_code == 201, f"creating {username}: {r.status_code} {r.text[:200]}"
        uid = requests.get(f"{self.cfg.keycloak}/admin/realms/chaya/users", params={"username": username, "exact": "true"},
                           headers=h, timeout=30).json()[0]["id"]
        requests.put(f"{self.cfg.keycloak}/admin/realms/chaya/users/{uid}/reset-password", headers=h, timeout=30,
                     json={"type": "password", "value": password, "temporary": False}).raise_for_status()
        role_rep = requests.get(f"{self.cfg.keycloak}/admin/realms/chaya/roles/{role}", headers=h, timeout=30).json()
        requests.post(f"{self.cfg.keycloak}/admin/realms/chaya/users/{uid}/role-mappings/realm", headers=h, timeout=30,
                      json=[role_rep]).raise_for_status()
        self.passwords[username] = password
        return uid

    def set_venues(self, username: str, org: str, venues: list[str]) -> None:
        h = self._admin()
        uid = requests.get(f"{self.cfg.keycloak}/admin/realms/chaya/users", params={"username": username, "exact": "true"},
                           headers=h, timeout=30).json()[0]["id"]
        rep = requests.get(f"{self.cfg.keycloak}/admin/realms/chaya/users/{uid}", headers=h, timeout=30).json()
        rep["attributes"] = {"org_id": [org], "venue_id": venues}
        requests.put(f"{self.cfg.keycloak}/admin/realms/chaya/users/{uid}", headers=h, json=rep, timeout=30).raise_for_status()

    def token(self, username: str, client: str = CLIENT, password: str | None = None) -> requests.Response:
        return requests.post(f"{self.cfg.keycloak}/realms/chaya/protocol/openid-connect/token", timeout=30, data={
            "grant_type": "password", "client_id": client, "username": username, "password": password or self.passwords[username]})

    def service_token(self) -> str:
        r = requests.post(f"{self.cfg.keycloak}/realms/chaya/protocol/openid-connect/token", timeout=30, data={
            "grant_type": "client_credentials", "client_id": "chaya-worker", "client_secret": self.cfg.worker_secret})
        r.raise_for_status()
        return r.json()["access_token"]


class Http:
    """An API caller. A named user's token is re-issued shortly before it expires, as the web app's silent renewal does; a
    fixed token (tampered, expired, a service token) is sent as it is."""

    def __init__(self, cfg: Config, kc: Keycloak | None = None, user: str | None = None, token: str | None = None,
                 service: bool = False) -> None:
        self.cfg, self.kc, self.user, self.token, self.service = cfg, kc, user, token, service

    def bearer(self) -> str | None:
        if (self.user or self.service) and (not self.token or jwt_claims(self.token)["exp"] - time.time() < 60):
            self.token = self.kc.service_token() if self.service else self.kc.token(self.user).json()["access_token"]
        return self.token

    def __call__(self, method: str, path: str, **kw: Any) -> requests.Response:
        h: dict[str, str] = {}
        if self.bearer():
            h["Authorization"] = "Bearer " + self.token
        h.update(kw.pop("headers", {}))
        return requests.request(method, self.cfg.api + path, headers=h, timeout=kw.pop("timeout", 90), **kw)

    def ok(self, method: str, path: str, expect: tuple[int, ...] = (200, 201, 202, 204), **kw: Any) -> Any:
        r = self(method, path, **kw)
        assert r.status_code in expect, f"{method} {path}: {r.status_code} {r.text[:400]}"
        return r.json() if r.content and r.headers.get("Content-Type", "").startswith(("application/json", "application/problem")) else r


def s3_client(cfg: Config, key: str, secret: str):
    import boto3  # noqa: PLC0415 - the worker image has it; imported lazily so collection never needs it
    from botocore.config import Config as BotoConfig  # noqa: PLC0415
    return boto3.client("s3", endpoint_url=cfg.s3_endpoint, aws_access_key_id=key, aws_secret_access_key=secret,
                        region_name="us-east-1", config=BotoConfig(s3={"addressing_style": "path"}))


# ------------------------------------------------------------------------------------------------ the synthetic scene
def scene_module():
    import generate_viewer_scene  # noqa: PLC0415 - packages/contracts/fixtures/viewer-scene, on sys.path above
    return generate_viewer_scene


def ply_bytes(cloud) -> bytes:
    from chaya_worker.ply import write_ply  # noqa: PLC0415
    tmp = Path(os.environ.get("TMPDIR", "/tmp")) / f"acc-{secrets.token_hex(4)}.ply"
    write_ply(cloud, tmp)
    data = tmp.read_bytes()
    tmp.unlink()
    return data


@dataclass
class Scene:
    """A stood-in reconstruction: the cloud the SPLAT stand-in publishes, its per-Gaussian labels (SEMANTIC_SEGMENTATION
    stand-in) and the objects the SEMANTIC_INDEXING stand-in reports, in canonical metres."""

    name: str
    ply: bytes
    labels: list[str]
    objects: list[tuple[str, tuple[float, float, float]]]

    @property
    def sha256(self) -> str:
        return sha(self.ply)


WALL_HEIGHT = 2.5
FLOOR_STEP = 0.025  # 16 floor Gaussians per 0.1 m navmesh cell; NAVIGATION_BAKING needs >= 3 (navmesh_floor_min_points_per_cell)


def _dense_scene(pillars, region: list[list[float]] | None = None):
    """The viewer-scene fixture's scene (same frame, pillars and colours: generate_viewer_scene), with a floor dense enough to
    be observed ground. The committed fixture's 0.1 m floor lattice puts one Gaussian in each navmesh cell, and the real bake
    rightly refuses that as nothing walkable (NO_WALKABLE_SURFACE). Returns (cloud, labels) in the reconstruction frame,
    optionally cut to a canonical region polygon (axis-aligned)."""
    g = scene_module()
    from chaya_worker.frames import matrix_to_quaternion  # noqa: PLC0415
    from chaya_worker.ply import GaussianCloud  # noqa: PLC0415
    from chaya_worker.splat_color import rgb_to_sh0  # noqa: PLC0415
    pos, scl, rgb, lab = [], [], [], []
    nx, ny = round(6.0 / FLOOR_STEP) + 1, round(4.0 / FLOOR_STEP) + 1
    for i in range(nx):
        for j in range(ny):
            pos.append((i * FLOOR_STEP, j * FLOOR_STEP, 0.0))
            scl.append((0.02, 0.02, 0.004))
            rgb.append((0.82, 0.82, 0.8) if ((int(i * FLOOR_STEP / 0.5)) + int(j * FLOOR_STEP / 0.5)) % 2 == 0 else (0.1, 0.45, 0.5))
            lab.append("floor")
    # Pillar faces at the floor's density too: GEOMETRIC_CLEANUP's outlier filters judge density against the cloud's own
    # spacing, and the fixture's 0.1 m pillar sampling next to a 0.025 m floor was (rightly) removed as sparse outliers.
    offsets = np.arange(-g.PILLAR_HALF, g.PILLAR_HALF + 1e-9, FLOOR_STEP)
    for _, (cx, cy), colour in pillars:
        for k in range(1, round(g.PILLAR_HEIGHT / FLOOR_STEP) + 1):
            for o in offsets:
                for x, y in ((cx + o, cy - g.PILLAR_HALF), (cx + o, cy + g.PILLAR_HALF), (cx - g.PILLAR_HALF, cy + o), (cx + g.PILLAR_HALF, cy + o)):
                    pos.append((x, y, k * FLOOR_STEP))
                    scl.append((0.02, 0.02, 0.02))
                    rgb.append(colour)
                    lab.append("furniture")
    # A south wall (y = 0) and an east wall (x = 6), 2.5 m tall: the unchanged structure a re-scan region is registered on.
    # Without it the region is a flat floor and the moved pillar, and the real REGION_ALIGNMENT rightly rejects aligning
    # the moved pillar onto the old one (ALIGNMENT_REJECTED). The viewer's initial view looks from -Y, so this wall hides the
    # pillars until the camera is turned (scripts/e2e/viewer_format_check.cjs orbits it; docs/ACCEPTANCE.md, findings).
    for k in range(0, round(WALL_HEIGHT / FLOOR_STEP) + 1):
        z = k * FLOOR_STEP
        for i in range(nx):
            pos.append((i * FLOOR_STEP, 0.0, z))
            scl.append((0.02, 0.004, 0.02))
            rgb.append((0.6, 0.55, 0.5))
            lab.append("wall")
        for j in range(1, ny):
            pos.append((6.0, j * FLOOR_STEP, z))
            scl.append((0.004, 0.02, 0.02))
            rgb.append((0.55, 0.5, 0.6))
            lab.append("wall")
    pos_c, scl_c, rgb = np.array(pos), np.array(scl), np.array(rgb)
    keep = np.arange(len(pos_c))
    if region:
        (x0, y0), (x1, y1) = region[0], region[2]
        keep = np.where((pos_c[:, 0] >= x0) & (pos_c[:, 0] <= x1) & (pos_c[:, 1] >= y0) & (pos_c[:, 1] <= y1))[0]
    n = len(keep)
    q = matrix_to_quaternion(g.rotation().T)
    cloud = GaussianCloud(positions=g.to_reconstruction(pos_c[keep]).astype(np.float32), scales_log=np.log(scl_c[keep] / g.SCALE).astype(np.float32),
                          rotations_wxyz=np.tile(q, (n, 1)).astype(np.float32), opacity_logit=np.full(n, 4.0, dtype=np.float32),
                          colors_dc=rgb_to_sh0(rgb[keep]).astype(np.float32))
    return cloud, [lab[i] for i in keep]


def full_scene() -> Scene:
    """Floor 6 m x 4 m (0.025 m lattice) and the fixture's three 0.4 m x 0.4 m x 2 m pillars, in the fixture's frame."""
    g = scene_module()
    cloud, labels = _dense_scene(g.PILLARS)
    objects = [(p["label"].lower(), tuple(p["canonical"])) for p in FIXTURE["pois_by_construction"]]
    return Scene("viewer-scene fixture (dense floor)", ply_bytes(cloud), labels, objects)


# Canonical metres: the east half, holding the moved blue pillar, the unchanged green pillar and the walls' corner. A region of
# only the blue pillar and the corner (x >= 4.4, y <= 2.7) was rightly rejected by the real REGION_ALIGNMENT: two walls and
# the floor meeting at (6, 0) do not fix scale about that corner, and its coarse ICP (first correspondence distance 1.0 m)
# paired the moved pillar with its old position and shrank the region by 0.62 (scale_correction 0.38 > 0.1). The unchanged
# green pillar pins it: accepted at scale 0.99, 1.5 cm correction (docs/ACCEPTANCE.md, findings).
RESCAN_REGION = [[3.0, -0.1], [6.1, -0.1], [6.1, 4.1], [3.0, 4.1]]
MOVED_BLUE = (5.0, 2.0)


def region_scene() -> Scene:
    """The re-scan's capture: the same scene with the blue pillar moved from (5, 1) to (5, 2), cut to the region polygon. In
    the parent reconstruction's frame, as a region REGION_ALIGNMENT has nothing to correct."""
    g = scene_module()
    pillars = [(n, MOVED_BLUE if n == "Blue pillar" else c, col) for n, c, col in g.PILLARS]
    cloud, labels = _dense_scene(pillars, RESCAN_REGION)
    (x0, y0), (x1, y1) = RESCAN_REGION[0], RESCAN_REGION[2]
    # What the region's detector would report: the moved blue pillar where it now stands, and the region's unchanged objects.
    unchanged = [(p["label"].lower(), tuple(p["canonical"])) for p in FIXTURE["pois_by_construction"]
                 if p["label"] != "Blue pillar" and x0 <= p["canonical"][0] <= x1 and y0 <= p["canonical"][1] <= y1]
    return Scene("re-scan region (blue pillar moved)", ply_bytes(cloud), labels,
                 [("blue pillar", (MOVED_BLUE[0], MOVED_BLUE[1], 0.0)), *unchanged])


# ------------------------------------------------------------------------------------------------ the stand-in worker
@dataclass
class RunPlan:
    """What the stand-ins publish for one run, and how the run is calibrated once its stand-in poses exist."""

    scene: Scene
    labels_override: list[str] | None = None  # a deliberately inconsistent labelling (the failure-propagation run)
    abandon_once: str | None = None  # a stage whose first claim is abandoned (no heartbeat, no report): a lost worker
    abandoned: dict[str, Any] = field(default_factory=dict)


class StandIns:
    """Claims the stand-in stages of the suite's own runs and reports them. A job of another run is never claimed: a claim
    is made only while one of the suite's runs has that stage QUEUED, and only the suite's runs are ever queued for it on a
    dedicated stack (docs/ACCEPTANCE.md, "Shared stacks")."""

    def __init__(self, cfg: Config, kc: Keycloak, vision_embed: Callable[[str], tuple[list[float], str]] | None) -> None:
        self.cfg, self.svc, self.plans, self.embed = cfg, Http(cfg, kc, service=True), {}, vision_embed
        self.s3 = s3_client(cfg, cfg.s3_worker_key, cfg.s3_worker_secret)
        self.reported: list[dict[str, Any]] = []

    def claim(self, stage: str) -> dict[str, Any] | None:
        r = self.svc("POST", "/api/v1/internal/jobs/claim", json={"stages": [stage], "workerId": "acceptance-stand-in"})
        if r.status_code == 204:
            return None
        assert r.status_code == 200, f"claim {stage}: {r.status_code} {r.text[:300]}"
        return r.json()

    def _put(self, order: dict[str, Any], name: str, kind: str, body: bytes, content_type: str) -> dict[str, Any]:
        key = order["outputPrefix"] + name
        self.s3.put_object(Bucket=self.cfg.bucket_derived, Key=key, Body=body, ContentType=content_type)
        return {"kind": kind, "key": key, "sha256": sha(body), "contentType": content_type, "sizeBytes": len(body),
                "containsPii": False, "partial": False}

    def outputs(self, order: dict[str, Any], plan: RunPlan) -> list[dict[str, Any]]:
        stage, sc = order["stage"], plan.scene
        doc = lambda d: json.dumps({"standIn": True, "note": STAND_IN_NOTE, **d}).encode()  # noqa: E731
        if stage == "POSE_ESTIMATION":
            return [self._put(order, "sparse-model.json", "SPARSE_MODEL", doc({"model": None}), "application/json"),
                    self._put(order, "poses.json", "POSES", doc({"poses": []}), "application/json")]
        if stage == "SPLAT_RECONSTRUCTION":
            return [self._put(order, "stand-in-scene.ply", "SPLAT", sc.ply, "application/octet-stream")]
        if stage == "SEMANTIC_SEGMENTATION":
            labels = plan.labels_override or sc.labels
            return [self._put(order, "semantic-labels.json", "SEMANTIC_LABELS",
                              doc({"model": "stand-in", "labels": labels, "cloud": {"sha256": sc.sha256}}), "application/json")]
        if stage == "SEMANTIC_INDEXING":
            frame = order.get("coordinateFrame") or {}
            objects, model = [], None
            for label, pos in sc.objects:
                vec, model = self.embed(label) if self.embed else ([], None)
                objects.append({"label": label, "confidence": 0.9, "position": [pos[0], pos[1], 0.05], "embedding": vec,
                                "localization": {"status": "MULTI_VIEW", "uncertainty_m": 0.1}})
            body = {"standIn": True, "note": STAND_IN_NOTE + " Embeddings are CLIP TEXT vectors of each label from the running "
                    "vision service, not image crops.", "coordinate_frame": {"id": frame.get("id"), "units": "m", "up_axis": "+Z"},
                    "embedding_model": model or "open_clip:ViT-B-32:openai",
                    "source": {"run_id": order.get("runId"), "scan_version_id": order.get("scanVersionId")}, "objects": objects}
            return [self._put(order, "detected-objects.json", "DETECTED_OBJECTS", json.dumps(body).encode(), "application/json")]
        raise AssertionError(f"no stand-in for {stage}")

    def report(self, order: dict[str, Any], status: str, artifacts: list[dict[str, Any]], code: str | None = None,
               message: str | None = None, lease: str | None = None) -> requests.Response:
        report = {"status": status, "startedAt": now(), "finishedAt": now(), "exitStatus": None,
                  "command": {"standIn": True, "fixture": "packages/contracts/fixtures/viewer-scene", "note": STAND_IN_NOTE},
                  "inputArtifactIds": [i["artifactId"] for i in order.get("inputs", [])], "artifacts": artifacts,
                  "errorCode": code, "errorMessage": message}
        return self.svc("POST", f"/api/v1/internal/jobs/{order['id']}/report", json=report,
                        headers={"X-Chaya-Lease-Token": lease or order["leaseToken"]})

    def serve(self, stage: str) -> dict[str, Any] | None:
        """Claims one job of `stage` and reports it for the run it belongs to."""
        order = self.claim(stage)
        if order is None:
            return None
        plan = self.plans.get(order["runId"])
        if plan is None:
            # Not one of the suite's runs (a shared stack): left unreported, so its lease expires and the control plane
            # fails it as WORKER_LOST, retryable. Recorded so the report says it happened.
            self.reported.append({"stage": stage, "runId": order["runId"], "foreign": True})
            return order
        if plan.abandon_once == stage and stage not in plan.abandoned:
            plan.abandoned[stage] = order  # a worker that took the job and died: no heartbeat, no report
            return order
        r = self.report(order, "SUCCEEDED", self.outputs(order, plan))
        assert r.status_code < 300, f"stand-in report {stage}: {r.status_code} {r.text[:400]}"
        self.reported.append({"stage": stage, "runId": order["runId"], "jobId": order["id"], "attempt": order.get("attempt")})
        return order


# ------------------------------------------------------------------------------------------------ driving a capture
VIDEO_FRAMES = (0.4, 2.4)  # the two frames of the capture video in which each control point is marked


def upload(user: Http, venue: str, capture: str, data: bytes, filename: str, content_type: str, kind: str = "VIDEO",
           declared_sha: str | None = None, settle: bool = True) -> dict[str, Any]:
    base = f"/api/v1/venues/{venue}/captures/{capture}"
    init = user.ok("POST", base + "/media", json={"kind": kind, "filename": filename, "contentType": content_type,
                                                   "sizeBytes": len(data), "sha256": declared_sha or sha(data)})
    part = init["partSizeBytes"]
    for n in range(init["totalParts"]):
        chunk = data[n * part:(n + 1) * part]
        user.ok("PUT", f"{base}/media/{init['mediaId']}/parts/{n + 1}", data=chunk,
                headers={"Content-Type": "application/octet-stream", "X-Part-Sha256": sha(chunk)})
    user.ok("POST", f"{base}/media/{init['mediaId']}/complete")
    return settle_media(user, venue, capture, init["mediaId"]) if settle else init


def settle_media(user: Http, venue: str, capture: str, media: str, timeout: float = 300) -> dict[str, Any]:
    end = time.time() + timeout
    m: dict[str, Any] = {}
    while time.time() < end:
        m = user.ok("GET", f"/api/v1/venues/{venue}/captures/{capture}/media/{media}")
        if m["status"] in ("ACCEPTED", "REJECTED", "QUARANTINED"):
            return m
        time.sleep(1)
    return m


def record_control_points(user: Http, venue: str, capture: str, media: str) -> list[dict[str, Any]]:
    """The fixture's surveyed points as CONTROL_POINT evidence, each marked in two frames of the capture video."""
    out = []
    for i, cp in enumerate(FIXTURE["control_points_by_construction"]):
        obs = [{"point": "P", "mediaId": media, "frameTimeSeconds": t, "u": 40.0 + 30 * i, "v": 60.0 + 10 * k}
               for k, t in enumerate(VIDEO_FRAMES)]
        out.append(user.ok("POST", f"/api/v1/venues/{venue}/captures/{capture}/measurements", json={
            "kind": "CONTROL_POINT", "label": cp["label"], "method": "SURVEY_PLAN", "unit": "m", "datum": "Synthetic fixture datum",
            "venue": cp["venue"], "observations": obs}))
    return out


def calibrate(user: Http, venue: str, capture: str, measurements: list[dict[str, Any]]) -> requests.Response:
    points = [{"measurementId": m["id"], "point": "P", "reconstruction": cp["reconstruction"]}
              for m, cp in zip(measurements, FIXTURE["control_points_by_construction"], strict=True)]
    return user("POST", f"/api/v1/venues/{venue}/captures/{capture}/calibration",
                json={"resolvedPoints": points, "note": "control points of the synthetic viewer-scene fixture"})


@dataclass
class Drive:
    status: dict[str, Any]
    timeline: list[tuple[float, str, str, int]]  # (seconds since start, stage, state, attempts)
    calibrated: bool


def drive(user: Http, stand_ins: StandIns, venue: str, capture: str, plan: RunPlan, measurements: list[dict[str, Any]] | None,
          timeout: float = 1200, stop: Callable[[dict[str, Any]], bool] | None = None) -> Drive:
    """Follows a run until it ends (or `stop` says so): serves its stand-in stages, calibrates it from the recorded evidence
    once its POSES exist, and records every stage state it is seen in. Real stages are left to the real workers."""
    t0, end = time.time(), time.time() + timeout
    seen: dict[str, tuple[str, int]] = {}
    timeline: list[tuple[float, str, str, int]] = []
    calibrated = False
    path = f"/api/v1/venues/{venue}/captures/{capture}/processing"
    while time.time() < end:
        st = user.ok("GET", path)
        run = st["run"]
        stand_ins.plans.setdefault(run["id"], plan)
        for s in run["stages"]:
            key = (s["state"], s.get("attempts") or 0)
            if seen.get(s["stage"]) != key:
                seen[s["stage"]] = key
                timeline.append((round(time.time() - t0, 1), s["stage"], s["state"], key[1]))
        if run["status"] in TERMINAL or (stop and stop(st)):
            return Drive(st, timeline, calibrated)
        states = {s["stage"]: s["state"] for s in run["stages"]}
        if measurements and not calibrated and states.get("POSE_ESTIMATION") == "SUCCEEDED":
            r = calibrate(user, venue, capture, measurements)
            assert r.status_code in (200, 201), f"calibration: {r.status_code} {r.text[:400]}"
            calibrated = True
        for stage in STAND_IN_STAGES:
            if states.get(stage) == "QUEUED":
                stand_ins.serve(stage)
        time.sleep(2)
    raise AssertionError(f"run of capture {capture} did not end within {timeout:.0f} s; last stages: "
                         f"{[(s, v[0]) for s, v in seen.items()]}")


def stage(st: dict[str, Any], name: str) -> dict[str, Any]:
    return next(s for s in st["run"]["stages"] if s["stage"] == name)


def artifacts_of(st: dict[str, Any], name: str) -> dict[str, dict[str, Any]]:
    return {a["kind"]: a for a in (stage(st, name).get("lastRun") or {}).get("artifacts") or []}


def ksplat_header_ok(data: bytes) -> tuple[bool, str]:
    """The production viewer contract (chaya_worker.ksplat.validate_file), applied to bytes as the API served them."""
    from chaya_worker.ksplat import KsplatInvalid, validate_file  # noqa: PLC0415
    tmp = Path(os.environ.get("TMPDIR", "/tmp")) / f"acc-{secrets.token_hex(4)}.ksplat"
    tmp.write_bytes(data)
    try:
        return True, json.dumps(validate_file(tmp))[:300]
    except KsplatInvalid as exc:
        return False, f"invalid: {exc}"
    finally:
        tmp.unlink()


# ------------------------------------------------------------------------------------------------ browser handoff
def handoff(cfg: Config, **values: Any) -> None:
    """Merges values into the browser check's input (ACC_HANDOFF). It holds secrets (a viewer link, a test password): the
    runner deletes it after the browser check, and it is never part of the evidence."""
    if not cfg.handoff:
        return
    doc = json.loads(cfg.handoff.read_text(encoding="utf-8")) if cfg.handoff.exists() else {}
    doc.update(values)
    cfg.handoff.write_text(json.dumps(doc), encoding="utf-8")


def write_restore(cfg: Config, bucket: str, key: str, original: bytes) -> None:
    """What the runner must put back after the browser check (tests/acceptance/restore.py): a deliberately corrupted object."""
    if not cfg.handoff:
        return
    p = cfg.handoff.with_name(".restore.json")
    items = json.loads(p.read_text(encoding="utf-8")) if p.exists() else []
    items.append({"bucket": bucket, "key": key, "sha256": sha(original), "body": base64.b64encode(original).decode()})
    p.write_text(json.dumps(items), encoding="utf-8")
