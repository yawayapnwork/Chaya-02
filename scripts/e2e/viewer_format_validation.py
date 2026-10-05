"""Viewer FORMAT VALIDATION against the real running stack (docs/E2E_VALIDATION.md, section V).

WHAT THIS IS -- AND IS NOT. No real reconstruction can be produced here (no CUDA GPU: section 0), so the viewing path is
validated with the synthetic FORMAT VALIDATION fixture (packages/contracts/fixtures/viewer-scene; NOT a reconstruction
of any venue). Everything from the production exporter onwards is real:

    fixture splat (scene.ply) -> the REAL worker container's ARTIFACT_GENERATION (production exporter), claimed through
    the REAL control plane -> .ksplat stored in REAL MinIO, recorded in REAL Postgres -> served by the REAL API under
    REAL authorization (Keycloak JWT, public viewer token) -> (scripts/e2e/viewer_format_check.cjs) the REAL web app in
    Chromium -> GaussianSplats3D -> rendered pixels.

The stages before ARTIFACT_GENERATION did not run. This harness claims them through the worker API as the worker service
account and reports each as a STAND-IN: nothing executed, command {"standIn": true, ...}. The pose stand-in publishes a
labelled placeholder POSES (calibration binds to a run's POSES), and the splat stand-in publishes the fixture PLY as
SPLAT. Nothing after ARTIFACT_GENERATION is stood in: SEMANTIC_INDEXING stays queued and NAVIGATION_BAKING never runs,
so no navigation graph exists and routing is expected to refuse.

    services/reconstruction/.venv/Scripts/python scripts/e2e/viewer_format_validation.py --env-file <viewer.env> \
        --project chaya-viewer --carrier <carrier.mp4> --out docs/e2e-evidence/viewer-format-results.json \
        --handoff <scratch>/viewer-handoff.json
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import subprocess
import sys
import time
from pathlib import Path
from typing import Any

import requests

sys.path.insert(0, str(Path(__file__).resolve().parent))
import e2e_validate as ev  # noqa: E402  (Keycloak, Http, s3, jwt_claims: the same helpers as the full E2E harness)

FIXTURE_DIR = Path(__file__).resolve().parents[2] / "packages" / "contracts" / "fixtures" / "viewer-scene"
FIXTURE = json.loads((FIXTURE_DIR / "fixture.json").read_text(encoding="utf-8"))
STAND_IN_STAGES = ["INPUT_VALIDATION", "FFMPEG_PREPROCESS", "FRAME_QUALITY_FILTER", "PRIVACY_PREPROCESS", "POSE_ESTIMATION",
                   "SPLAT_RECONSTRUCTION", "SEMANTIC_SEGMENTATION", "GEOMETRIC_CLEANUP", "PLANE_FITTING"]
STAND_IN_NOTE = ("FORMAT VALIDATION STAND-IN: this stage was NOT executed. The run exists only to carry the synthetic "
                 "viewer-scene fixture into the production exporter (ARTIFACT_GENERATION). Not a reconstruction.")

RESULTS: list[dict[str, Any]] = []
CTX: dict[str, Any] = {}


def record(step: str, name: str, expected: str, ok: bool | str, actual: str, evidence: Any = None) -> bool:
    status = "BLOCKED" if isinstance(ok, str) else "PASS" if ok else "FAIL"
    RESULTS.append({"step": step, "name": name, "expected": expected, "actual": actual, "status": status,
                    "blocked_dependency": ok if isinstance(ok, str) else None, "evidence": evidence})
    print(f"[{status:7}] {step:5} {name}: {actual}", flush=True)
    return ok is True


def psql(sql: str) -> str:
    r = subprocess.run(["docker", "exec", "-i", f"{PROJECT}-postgres-1", "psql", "-q", "-v", "ON_ERROR_STOP=1", "-U",
                        ev.ENV["POSTGRES_USER"], "-d", ev.ENV["POSTGRES_DB"], "-At", "-F", "|", "-c", sql],
                       capture_output=True, text=True, check=True)
    return r.stdout.replace("\r", "").strip()


def sha(b: bytes) -> str:
    return hashlib.sha256(b).hexdigest()


def now() -> str:
    return dt.datetime.now(dt.UTC).isoformat()


# ------------------------------------------------------------------------------------------------ steps
def v0_health() -> None:
    h = requests.get(API + "/api/v1/health", timeout=30)
    record("V0", "API health", "200", h.status_code == 200, f"HTTP {h.status_code} {h.json().get('status') if h.ok else h.text[:120]}")
    w = requests.get(WEB + "/api/health", timeout=30)
    record("V0.1", "Web readiness", "200", w.status_code == 200, f"HTTP {w.status_code}")


def v1_tenant() -> None:
    kc = ev.CTX["kc"] = ev.Keycloak(KC)
    kc.ensure_test_client()
    org = psql("INSERT INTO organization (slug, name) VALUES ('viewer-format-org', 'Viewer format validation org') RETURNING id")
    kc.create_user("vf-admin", "admin", org, [])
    admin = ev.Http(API, kc.token("vf-admin").json()["access_token"], user="vf-admin")
    ev.CTX["admin"] = admin
    va = admin("POST", "/api/v1/venues", json={"slug": "viewer-format-fixture", "name": "FORMAT FIXTURE (not a venue reconstruction)"}).json()["id"]
    vb = admin("POST", "/api/v1/venues", json={"slug": "viewer-format-other", "name": "Viewer format: second venue"}).json()["id"]
    kc.create_user("vf-manager", "venue-manager", org, [va])
    kc.create_user("vf-manager-b", "venue-manager", org, [vb])
    CTX.update(org=org, venue=va, venue_b=vb)
    CTX["mgr"] = ev.Http(API, user="vf-manager")
    CTX["mgr_b"] = ev.Http(API, user="vf-manager-b")
    record("V1", "Tenant, two venues, managers", "org (SQL), venues A/B, one manager per venue", bool(va and vb),
           f"org={org} venueA={va} venueB={vb}")


def v2_capture_and_run(carrier: Path) -> None:
    mgr, v = CTX["mgr"], CTX["venue"]
    floor = mgr("POST", f"/api/v1/venues/{v}/floors", json={"level": 0, "name": "Format fixture floor"}).json()["id"]
    cap = mgr("POST", f"/api/v1/venues/{v}/captures", json={"floorId": floor, "device": {"model": "format-fixture-carrier",
                                                                                           "app": "scripts/e2e/viewer_format_validation.py"}}).json()["id"]
    base = f"/api/v1/venues/{v}/captures/{cap}"
    data = carrier.read_bytes()
    init = mgr("POST", base + "/media", json={"kind": "VIDEO", "filename": carrier.name, "contentType": "video/mp4",
                                              "sizeBytes": len(data), "sha256": sha(data)}).json()
    mgr("PUT", f"{base}/media/{init['mediaId']}/parts/1", data=data, headers={"Content-Type": "application/octet-stream"})
    mgr("POST", f"{base}/media/{init['mediaId']}/complete")
    status = None
    for _ in range(300):
        status = mgr("GET", f"{base}/media/{init['mediaId']}").json()["status"]
        if status not in ("PENDING", "UPLOADING", "VALIDATING", "UPLOADED"):
            break
        time.sleep(1)
    mgr("POST", base + "/complete-upload", json={"durationSeconds": 2.0})
    r = mgr("POST", base + "/processing", json={"timeBudgetSeconds": 3600})
    CTX.update(floor=floor, capture=cap, run=r.json()["run"]["id"] if r.status_code == 202 else None)
    record("V2", "Carrier capture accepted and a pipeline run started", "media ACCEPTED (real ClamAV/sniffing); run 202",
           status == "ACCEPTED" and r.status_code == 202,
           f"carrier {carrier.name} ({len(data)} B, synthetic ffmpeg testsrc: carries no scene) {status}; run HTTP {r.status_code} {CTX['run']}")


def _upload_derived(key: str, body: bytes, content_type: str) -> dict[str, Any]:
    ev.s3(ev.ENV["S3_WORKER_ACCESS_KEY"], ev.ENV["S3_WORKER_SECRET_KEY"]).put_object(
        Bucket=ev.ENV["S3_BUCKET_DERIVED"], Key=key, Body=body, ContentType=content_type)
    return {"key": key, "sha256": sha(body), "contentType": content_type, "sizeBytes": len(body), "containsPii": False, "partial": False}


def v3_stand_ins() -> None:
    svc = ev.Http(API, CTX["kc_service"]())
    done = []
    for stage in STAND_IN_STAGES:
        order = None
        for _ in range(60):
            res = svc("POST", "/api/v1/internal/jobs/claim", json={"stages": [stage], "workerId": "viewer-format-stand-in"})
            if res.status_code == 200:
                order = res.json()
                break
            time.sleep(1)
        if order is None or order["stage"] != stage:
            record("V3", f"Stand-in {stage}", "claimed", False, f"could not claim {stage}")
            return
        started = now()
        artifacts = []
        if stage == "POSE_ESTIMATION":
            poses = json.dumps({"standIn": True, "note": STAND_IN_NOTE, "poses": []}).encode()
            artifacts.append({"kind": "POSES", **_upload_derived(order["outputPrefix"] + "poses.json", poses, "application/json")})
        if stage == "SPLAT_RECONSTRUCTION":
            ply = (FIXTURE_DIR / "scene.ply").read_bytes()
            artifacts.append({"kind": "SPLAT", **_upload_derived(order["outputPrefix"] + "format-fixture-scene.ply", ply,
                                                                 "application/octet-stream")})
        report = {"status": "SUCCEEDED", "startedAt": started, "finishedAt": now(), "exitStatus": None,
                  "command": {"standIn": True, "formatValidationFixture": "packages/contracts/fixtures/viewer-scene",
                              "note": STAND_IN_NOTE},
                  "inputArtifactIds": [i["artifactId"] for i in order["inputs"]], "artifacts": artifacts}
        # Only the claim's lease holder may report on the job (docs/security.md, "Service-to-service authentication").
        r = svc("POST", f"/api/v1/internal/jobs/{order['id']}/report", json=report,
                headers={"X-Chaya-Lease-Token": order["leaseToken"]})
        if r.status_code >= 300:
            record("V3", f"Stand-in {stage}", "report accepted", False, ev.short(r))
            return
        done.append(f"{stage}{'(' + ','.join(a['kind'] for a in artifacts) + ')' if artifacts else ''}")
    record("V3", "Stages before the exporter reported as labelled STAND-INS (not executed)",
           "each claimed and reported through the worker API with command.standIn=true", True, "; ".join(done),
           {"note": STAND_IN_NOTE, "db": psql(f"SELECT stage, command->>'standIn' FROM pipeline_stage_run WHERE run_id = '{CTX['run']}' ORDER BY started_at")})


def v4_exporter() -> None:
    mgr, v, c = CTX["mgr"], CTX["venue"], CTX["capture"]
    stage = {}
    for _ in range(180):
        run = mgr("GET", f"/api/v1/venues/{v}/captures/{c}/processing").json()["run"]
        stage = next(s for s in run["stages"] if s["stage"] == "ARTIFACT_GENERATION")
        if stage["state"] in ("SUCCEEDED", "FAILED"):
            break
        time.sleep(2)
    last = stage.get("lastRun") or {}
    worker = psql(f"SELECT worker_id FROM pipeline_stage_run WHERE run_id = '{CTX['run']}' AND stage = 'ARTIFACT_GENERATION'")
    cmd = last.get("command") or {}
    record("V4", "ARTIFACT_GENERATION run by the REAL worker container (production exporter)",
           "SUCCEEDED on the CPU worker, not a stand-in; KSPLAT + ARTIFACT_MANIFEST + VIEWER_BUNDLE",
           stage.get("state") == "SUCCEEDED" and worker == "viewer-format-cpu-worker" and not cmd.get("standIn")
           and (cmd.get('config') or {}).get('gaussian_count') == FIXTURE["splat_count"],
           f"state={stage.get('state')} worker={worker} gaussian_count={(cmd.get('config') or {}).get('gaussian_count')} "
           f"outputs={[a['kind'] for a in last.get('artifacts') or []]}", {"command": cmd})
    ks = next((a for a in last.get("artifacts") or [] if a["kind"] == "KSPLAT"), None)
    if not ks:
        return
    CTX["ksplat"] = ks
    stored = ev.s3(ev.ENV["S3_BACKUP_ACCESS_KEY"], ev.ENV["S3_BACKUP_SECRET_KEY"]).get_object(
        Bucket=ev.ENV["S3_BUCKET_DERIVED"], Key=ks["key"])["Body"].read()
    row = psql(f"SELECT checksum_sha256, size_bytes FROM processing_artifact WHERE object_key = '{ks['key']}'")
    fixture_sha = FIXTURE["files"]["scene.ksplat"]["sha256"]
    record("V4.1", "Stored artifact: MinIO bytes == Postgres record == committed fixture",
           "sha256(MinIO object) == processing_artifact.checksum_sha256 == fixture scene.ksplat sha256",
           sha(stored) == row.split("|")[0] == ks["sha256"] == fixture_sha and len(stored) == int(row.split("|")[1]),
           f"s3://{ev.ENV['S3_BUCKET_DERIVED']}/{ks['key']} {len(stored)} B sha256={sha(stored)}; db={row}; fixture={fixture_sha}")
    manifest = next((a for a in last.get("artifacts") or [] if a["kind"] == "ARTIFACT_MANIFEST"), None)
    if manifest:
        doc = json.loads(ev.s3(ev.ENV["S3_BACKUP_ACCESS_KEY"], ev.ENV["S3_BACKUP_SECRET_KEY"]).get_object(
            Bucket=ev.ENV["S3_BUCKET_DERIVED"], Key=manifest["key"])["Body"].read())
        text = json.dumps(doc)
        record("V4.2", "Exporter manifest names its input: the fixture splat, by name and checksum",
               "upstream SPLAT = format-fixture-scene.ply with the fixture PLY's sha256",
               "format-fixture-scene.ply" in text and FIXTURE["files"]["scene.ply"]["sha256"] in text,
               f"manifest artifacts: {[(e.get('kind'), e.get('name')) for e in doc.get('artifacts', [])]}; coordinateSpace={doc.get('coordinateSpace')}",
               doc)


def v5_calibrate_and_pois() -> None:
    mgr, v = CTX["mgr"], CTX["venue"]
    cps = [{"label": f"{c['label']} (fixture, exact by construction)", "reconstruction": c["reconstruction"], "venue": c["venue"]}
           for c in FIXTURE["control_points_by_construction"]]
    r = mgr("POST", f"/api/v1/venues/{v}/reconstructions/{CTX['run']}/coordinate-frames",
            json={"controlPoints": cps, "note": "FORMAT VALIDATION fixture: control points are the synthetic scene's own "
                                                "construction coordinates, not a survey"})
    f = r.json() if r.ok else {}
    t = FIXTURE["reconstruction_to_canonical"]
    ok = r.ok and f.get("canonical") and abs(f.get("scale", 0) - t["scale"]) < 1e-6 and (f.get("controlPointRmsM") or 1) < 1e-6
    record("V5", "Calibrate the fixture run through the real API (control points by construction)",
           f"canonical frame, scale {t['scale']}, control-point RMS ~0", bool(ok),
           f"HTTP {r.status_code} canonical={f.get('canonical')} datum={f.get('horizontalDatum')} scale={f.get('scale')} "
           f"rms={f.get('controlPointRmsM')}" if r.ok else ev.short(r), f)
    ids = {}
    for p in FIXTURE["pois_by_construction"]:
        x, y, z = p["canonical"]
        res = mgr("POST", f"/api/v1/venues/{v}/pois", json={"floorId": CTX["floor"], "label": p["label"], "category": "fixture",
                                                            "description": "format-fixture POI at a pillar base", "x": x, "y": y, "z": z})
        if res.status_code in (200, 201):
            ids[p["label"]] = res.json()["id"]
    CTX["pois"] = ids
    pois = mgr("GET", f"/api/v1/venues/{v}/pois").json()
    record("V5.1", "POIs placed in the calibrated frame", "3 POIs, frameStatus CURRENT",
           len(ids) == 3 and all(p.get("frameStatus") == "CURRENT" for p in pois),
           f"{[(p['label'], p.get('frameStatus')) for p in pois]}")


def v6_route() -> None:
    mgr, v = CTX["mgr"], CTX["venue"]
    red = next(p for p in FIXTURE["pois_by_construction"] if p["label"] == "Red pillar")
    r = mgr("POST", "/api/v1/navigation/routes", json={"venueId": v, "floorId": CTX["floor"], "start": red["canonical"],
                                                         "destinationPoiId": CTX["pois"]["Blue pillar"]})
    graphs = psql(f"SELECT count(*) FROM navigation_graph WHERE venue_id = '{v}'")
    record("V6", "Route over the fixture", "a route exists only if NAVIGATION_BAKING produced a navmesh-backed graph",
           True if r.ok else ("NAVIGATION_BAKING never ran for this run: no navmesh, no graph" if r.status_code in (404, 409) else False),
           f"{ev.short(r, 200)}; navigation_graph rows: {graphs}")


def v7_http_authorization() -> None:
    mgr, mgr_b, v, vb, run = CTX["mgr"], CTX["mgr_b"], CTX["venue"], CTX["venue_b"], CTX["run"]
    path = f"/api/v1/venues/{v}/reconstructions/{run}/artifacts/KSPLAT"
    want = FIXTURE["files"]["scene.ksplat"]["sha256"]
    link = mgr("POST", f"/api/v1/venues/{v}/public-links", json={"label": "viewer format validation", "ttl": "PT1H"}).json()
    tok = requests.post(API + "/api/v1/public/viewer-token", json={"secret": link["secret"]}, timeout=30).json()["token"]
    pub = ev.Http(API, headers={"X-Chaya-Viewer-Token": tok})

    r = pub("GET", path)
    record("V7.1", "Public viewer token: artifact over HTTP", "200, application/octet-stream, nosniff, bytes == fixture",
           r.status_code == 200 and r.headers.get("Content-Type") == "application/octet-stream"
           and r.headers.get("X-Content-Type-Options") == "nosniff" and sha(r.content) == want,
           f"HTTP {r.status_code} {r.headers.get('Content-Type')} len={r.headers.get('Content-Length')} "
           f"nosniff={r.headers.get('X-Content-Type-Options')} cache={r.headers.get('Cache-Control')} sha256={sha(r.content)}")
    r = mgr("GET", path)
    record("V7.2", "Venue manager (Keycloak JWT): artifact over HTTP", "200, bytes == fixture",
           r.status_code == 200 and sha(r.content) == want, f"HTTP {r.status_code} sha256 match={sha(r.content) == want}")
    r = requests.get(API + path, timeout=30)
    record("V7.3", "No credentials", "401", r.status_code == 401, f"HTTP {r.status_code}")
    r = pub("GET", f"/api/v1/venues/{vb}/reconstructions/{run}/artifacts/KSPLAT")
    record("V7.4", "Public token for venue A asks under venue B", "404 (token scoped to one venue)", r.status_code == 404, f"HTTP {r.status_code}")
    r = mgr_b("GET", path)
    record("V7.5", "Manager of venue B asks for venue A's artifact", "404 (venue-scoped)", r.status_code == 404, f"HTTP {r.status_code}")
    r = pub("GET", f"/api/v1/venues/{v}/reconstructions/{run}/artifacts/SPLAT")
    record("V7.6", "A non-viewer kind (the raw SPLAT) cannot be fetched", "400 (only viewer kinds are served)",
           r.status_code == 400, f"HTTP {r.status_code}")
    rec = pub("GET", f"/api/v1/venues/{v}/floors/{CTX['floor']}/reconstructions/latest")
    body = rec.json() if rec.ok else {}
    ks = next((a for a in body.get("artifacts", []) if a["kind"] == "KSPLAT"), {})
    record("V7.7", "Reconstruction record the viewer reads", "latest = this run; KSPLAT sha256 == fixture; canonical frame attached",
           rec.ok and body.get("runId") == run and ks.get("sha256") == want and (body.get("coordinateFrame") or {}).get("canonical") is True,
           f"HTTP {rec.status_code} runId={body.get('runId')} runStatus={body.get('runStatus')} KSPLAT sha256={ks.get('sha256')} "
           f"frame.canonical={(body.get('coordinateFrame') or {}).get('canonical')}")
    mgr("DELETE", f"/api/v1/venues/{v}/public-links/{link['id']}")
    r = pub("GET", path)
    record("V7.8", "Revoked link's token", "401", r.status_code == 401, f"HTTP {r.status_code}")


def write_handoff(path: Path) -> None:
    link = CTX["mgr"]("POST", f"/api/v1/venues/{CTX['venue']}/public-links", json={"label": "viewer format browser check", "ttl": "PT2H"}).json()
    path.write_text(json.dumps({"viewerLink": link["secret"], "managerUsername": "vf-manager", "managerPassword": ev.CTX["passwords"]["vf-manager"],
                                "venueId": CTX["venue"], "floorId": CTX["floor"], "runId": CTX["run"],
                                "ksplatSha256": FIXTURE["files"]["scene.ksplat"]["sha256"], "splatCount": FIXTURE["splat_count"],
                                "pois": [p["label"] for p in FIXTURE["pois_by_construction"]]}))


def main() -> None:
    global API, KC, WEB, PROJECT
    p = argparse.ArgumentParser()
    p.add_argument("--env-file", required=True)
    p.add_argument("--project", default="chaya-viewer", help="compose project name (container prefix)")
    p.add_argument("--carrier", required=True, help="small video accepted as the run's capture media; carries no scene")
    p.add_argument("--out", required=True)
    p.add_argument("--handoff", required=True, help="browser-check inputs (contains secrets: keep out of the evidence)")
    a = p.parse_args()
    ev.ENV = ev.load_env(a.env_file)
    PROJECT = a.project
    API, KC, WEB = f"http://localhost:{ev.ENV['API_PORT']}", f"http://localhost:{ev.ENV['KEYCLOAK_PORT']}", f"http://localhost:{ev.ENV['WEB_PORT']}"
    ev.API, ev.KC = API, KC
    CTX["kc_service"] = lambda: ev.CTX["kc"].service_token()
    started = time.strftime("%Y-%m-%dT%H:%M:%S%z")
    try:
        for fn in (v0_health, v1_tenant, lambda: v2_capture_and_run(Path(a.carrier)), v3_stand_ins, v4_exporter,
                   v5_calibrate_and_pois, v6_route, v7_http_authorization):
            fn()
        write_handoff(Path(a.handoff))
    finally:
        ctx = {k: v for k, v in CTX.items() if k not in ("mgr", "mgr_b", "kc_service")}
        Path(a.out).write_text(json.dumps({"started": started, "finished": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
                                           "kind": "FORMAT VALIDATION (synthetic fixture; not a venue reconstruction)",
                                           "fixture": {k: FIXTURE[k] for k in ("note", "splat_count", "files")},
                                           "results": RESULTS, "context": ctx}, indent=2, default=str))
        n = {s: sum(1 for r in RESULTS if r["status"] == s) for s in ("PASS", "FAIL", "BLOCKED")}
        print(f"\nPASS={n['PASS']} FAIL={n['FAIL']} BLOCKED={n['BLOCKED']} -> {a.out}")


API = KC = WEB = PROJECT = ""

if __name__ == "__main__":
    main()
