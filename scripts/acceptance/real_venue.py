#!/usr/bin/env python3
"""REAL-VENUE ACCEPTANCE RUN of the whole capture-to-digital-twin workflow, through the production API only.

Nothing here substitutes for a stage: it uploads YOUR capture, records YOUR measurements, starts processing, and then only
observes and verifies what the workers and the control plane really did. It never retries past a failure on its own
(unless --retry-failed), never injects an artifact, and never marks anything published. Python 3.11+, standard library only.

  python scripts/acceptance/real_venue.py --api https://api.example --token-file op.token --venue <id> --floor <id> \\
      --media ./capture --measurements measurements.json --out ./acceptance-out [--resolved-points resolved.json] \\
      [--search "exit sign" --search "reception desk"] [--route-from 1.0,2.0,0.0 --route-to "exit sign"]

Steps, each recorded in <out>/report.json:
  1. capture: create a session on the floor, upload every file in --media through the resumable multipart protocol
     (per-part SHA-256), wait for the server's verdict on each, record the measurements (--measurements: the
     /captures/{id}/measurements bodies, with "media": "<file name>" in place of mediaId in each observation);
  2. processing: complete the upload, start processing, poll the persisted run, stage runs, artifacts and jobs (worker id,
     lease expiry, attempts) until the run ends or needs the operator;
  3. calibration: when the reconstruction exists and is not calibrated (the metric stages fail NOT_CALIBRATED), submit
     --resolved-points (reconstruction coordinates of each measured point, picked by the operator) and retry; without
     them, write what is needed and exit 3, so the run can be resumed with --resume;
  4. verification once the run SUCCEEDED and its version is published: every stage's artifacts (content type, size,
     SHA-256, tenant-scoped key), the viewer asset downloaded and re-hashed, the manifest's references, the floor's
     current version and frame, the POIs, search for --search labels, and routes (standard and step-free) on the
     navigation graph of this very run;
  5. optional: --viewer-check loads the downloaded .ksplat with the pinned viewer library
     (apps/web/scripts/verify-ksplat-artifact.ts) against the downloaded SPLAT_CLEAN cloud.

Exit status: 0 verified; 1 the workflow failed (the report says where and why); 2 bad arguments or access;
3 operator input needed (calibration points); 4 timed out.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import mimetypes
import os
import platform
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

MEDIA_TYPES = {".mp4": ("VIDEO", "video/mp4"), ".mov": ("VIDEO", "video/quicktime"), ".webm": ("VIDEO", "video/webm"),
               ".mkv": ("VIDEO", "video/x-matroska"), ".jpg": ("IMAGE", "image/jpeg"), ".jpeg": ("IMAGE", "image/jpeg"),
               ".png": ("IMAGE", "image/png"), ".heic": ("IMAGE", "image/heic"), ".json": ("METADATA", "application/json")}
REPO = Path(__file__).resolve().parents[2]


class Failure(Exception):
    def __init__(self, code: int, message: str):
        super().__init__(message)
        self.code = code


class Api:
    def __init__(self, base: str, token: str):
        if urllib.parse.urlsplit(base).scheme not in ("http", "https"):
            raise Failure(2, f"--api must be an http(s) URL, not {base!r}")
        self.base = base.rstrip("/") + "/api/v1"
        self.token = token

    def call(self, method: str, path: str, body: Any = None, *, raw: bytes | None = None, headers: dict | None = None,
             ok: tuple[int, ...] = (200, 201, 202, 204)) -> tuple[int, Any, bytes]:
        data = raw if raw is not None else (None if body is None else json.dumps(body).encode())
        req = urllib.request.Request(self.base + path, data=data, method=method)  # noqa: S310 - http(s) only (__init__)
        req.add_header("Authorization", f"Bearer {self.token}")
        if raw is not None:
            req.add_header("Content-Type", "application/octet-stream")
        elif body is not None:
            req.add_header("Content-Type", "application/json")
        for k, v in (headers or {}).items():
            req.add_header(k, v)
        try:
            with urllib.request.urlopen(req, timeout=300) as res:  # noqa: S310 - http(s) only (__init__)
                status, payload = res.status, res.read()
        except urllib.error.HTTPError as e:
            status, payload = e.code, e.read()
        parsed = None
        if payload and payload[:1] in (b"{", b"["):
            parsed = json.loads(payload)
        if status not in ok:
            detail = parsed if isinstance(parsed, dict) else payload[:500].decode(errors="replace")
            raise Failure(2 if status in (401, 403, 404) else 1, f"{method} {path} -> {status}: {detail}")
        return status, parsed, payload


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def log(msg: str) -> None:
    print(time.strftime("%H:%M:%S"), msg, flush=True)


# ---- 1. capture ---------------------------------------------------------------------------------------------------------


def upload(api: Api, venue: str, capture: str, path: Path, existing: list[dict]) -> dict:
    kind, ctype = MEDIA_TYPES[path.suffix.lower()]
    size, sha = path.stat().st_size, sha256_file(path)
    known = next((m for m in existing if m["sha256"] == sha and m["sizeBytes"] == size and m["status"] != "REJECTED"), None)
    base = f"/venues/{venue}/captures/{capture}/media"
    if known is None:
        _, init, _ = api.call("POST", base, {"kind": kind, "filename": path.name, "contentType": ctype, "sizeBytes": size, "sha256": sha})
        media_id, part_size = init["mediaId"], init["partSizeBytes"]
        have: set[int] = set()
    else:
        media_id, part_size = known["id"], known["partSizeBytes"]
        have = set(known["uploadedParts"])
        if known["status"] != "PENDING":
            return {"file": path.name, "mediaId": media_id, "sha256": sha, "sizeBytes": size, "resumed": "already uploaded"}
    with path.open("rb") as f:
        part = 1
        while chunk := f.read(part_size):
            if part not in have:
                for attempt in range(1, 4):
                    try:
                        api.call("PUT", f"{base}/{media_id}/parts/{part}", raw=chunk,
                                 headers={"X-Part-Sha256": hashlib.sha256(chunk).hexdigest()})
                        break
                    except Failure:
                        if attempt == 3:
                            raise
                        time.sleep(2 * attempt)
            part += 1
    api.call("POST", f"{base}/{media_id}/complete")
    return {"file": path.name, "mediaId": media_id, "sha256": sha, "sizeBytes": size, "resumed": bool(have)}


def await_media(api: Api, venue: str, capture: str, media_id: str, timeout: float) -> dict:
    end = time.time() + timeout
    while time.time() < end:
        _, m, _ = api.call("GET", f"/venues/{venue}/captures/{capture}/media/{media_id}")
        if m["status"] in ("ACCEPTED", "REJECTED", "QUARANTINED"):
            return m
        time.sleep(3)
    raise Failure(4, f"media {media_id} was not validated in time")


def record_measurements(api: Api, venue: str, capture: str, measurements: list[dict], media_by_name: dict[str, str]) -> list[dict]:
    recorded = []
    for m in measurements:
        body = {**m, "observations": []}
        for o in m.get("observations", []):
            name = o.get("media")
            if name not in media_by_name:
                raise Failure(2, f"measurement {m.get('label')!r}: observation names media {name!r}, which is not in --media")
            body["observations"].append({k: v for k, v in o.items() if k != "media"} | {"mediaId": media_by_name[name]})
        _, view, _ = api.call("POST", f"/venues/{venue}/captures/{capture}/measurements", body)
        recorded.append({"label": view["label"], "id": view["id"], "kind": view["kind"], "unit": view["unit"],
                         "measuredMetres": view["measuredMetres"], "observations": len(view["observations"])})
    return recorded


# ---- 2/3. processing and calibration ------------------------------------------------------------------------------------


def snapshot(api: Api, venue: str, capture: str) -> dict:
    _, p, _ = api.call("GET", f"/venues/{venue}/captures/{capture}/processing")
    try:
        _, jobs, _ = api.call("GET", f"/venues/{venue}/ops/jobs?limit=200")
        own = [j for j in jobs["jobs"] if j.get("captureId") == capture]
    except Failure as e:  # the ops view needs a venue manager or admin
        own = [{"unavailable": str(e)}]
    return {"processing": p, "jobs": own}


def stage_line(run: dict) -> str:
    return " ".join(f"{s['stage'][:10]}={s['state']}" for s in run["stages"])


def calibrate(api: Api, venue: str, capture: str, resolved: dict, report: dict) -> None:
    _, measurements, _ = api.call("GET", f"/venues/{venue}/captures/{capture}/measurements")
    points = []
    for m in measurements:
        if m["status"] != "ACTIVE" or m["label"] not in resolved.get("points", {}):
            continue
        for point, xyz in resolved["points"][m["label"]].items():
            points.append({"measurementId": m["id"], "point": point, "reconstruction": xyz})
    body = {"resolvedPoints": points, "note": "real-venue acceptance run"}
    if resolved.get("gravity"):
        body["gravity"] = resolved["gravity"]
    status, view, raw = api.call("POST", f"/venues/{venue}/captures/{capture}/calibration", body, ok=(201, 400, 409, 422))
    report["calibration"] = {"status": status, "result": view if view is not None else raw.decode(errors="replace")}
    if status != 201:
        raise Failure(1, f"the calibration was refused: {view}")


# ---- 4. verification ----------------------------------------------------------------------------------------------------


def verify(api: Api, args, capture: str, run: dict, out: Path, report: dict) -> list[str]:
    problems: list[str] = []
    venue, floor = args.venue, args.floor
    run_id = run["id"]

    # Every stage's artifacts: tenant-scoped keys, declared sizes and checksums.
    arts = []
    for stage in run["stages"]:
        last = stage.get("lastRun") or {}
        for a in last.get("artifacts", []):
            arts.append({"stage": stage["stage"], **{k: a.get(k) for k in ("kind", "key", "contentType", "sizeBytes", "sha256", "partial")}})
            if f"/venue/{venue}/" not in a["key"] or f"/run/{run_id}/" not in a["key"]:
                problems.append(f"{stage['stage']} {a['kind']} key is not scoped to this venue and run: {a['key']}")
            if not a.get("sha256") or len(a["sha256"]) != 64 or a.get("sizeBytes", 0) <= 0:
                problems.append(f"{stage['stage']} {a['kind']} has no checksum or size")
    report["artifacts"] = arts

    # The published version and the viewer's reconstruction of exactly this run.
    _, versions, _ = api.call("GET", f"/venues/{venue}/floors/{floor}/reconstructions")
    mine = next((v for v in versions if v["runId"] == run_id), None)
    report["publication"] = {"listed": mine}
    if mine is None or not mine.get("current"):
        problems.append("the run's version is not the floor's current published version")
    _, recon, _ = api.call("GET", f"/venues/{venue}/reconstructions/{run_id}")
    frame = recon.get("coordinateFrame") or {}
    report["reconstruction"] = {"scanVersionId": recon.get("scanVersionId"), "coordinateFrame": frame}
    if not frame.get("canonical"):
        problems.append("the published reconstruction has no canonical (metric, gravity-aligned) frame")

    downloaded = {}
    for ref in recon.get("artifacts", []):
        kind = ref["kind"]
        status, _, body = api.call("GET", f"/venues/{venue}/reconstructions/{run_id}/artifacts/{kind}", ok=(200, 404, 403, 409))
        entry = {"kind": kind, "status": status, "declared": {k: ref.get(k) for k in ("sha256", "sizeBytes", "contentType")}}
        if status == 200:
            entry["downloadedSha256"], entry["downloadedBytes"] = hashlib.sha256(body).hexdigest(), len(body)
            if entry["downloadedSha256"] != ref.get("sha256") or entry["downloadedBytes"] != ref.get("sizeBytes"):
                problems.append(f"{kind}: the downloaded bytes do not match the declared checksum and size")
            (out / "artifacts").mkdir(exist_ok=True)
            (out / "artifacts" / kind.lower()).write_bytes(body)
            downloaded[kind] = body
        report.setdefault("downloads", []).append(entry)
    if "KSPLAT" not in downloaded:
        problems.append("the viewer asset (KSPLAT) could not be downloaded")
    manifest = json.loads(downloaded["ARTIFACT_MANIFEST"]) if "ARTIFACT_MANIFEST" in downloaded else None
    if manifest is not None:
        named = {(e["kind"], e["checksum"]["value"]) for e in manifest.get("artifacts", [])}
        if "KSPLAT" in downloaded and ("KSPLAT", hashlib.sha256(downloaded["KSPLAT"]).hexdigest()) not in named:
            problems.append("the manifest does not reference the served .ksplat by its checksum")
        report["manifest"] = {"coordinateSpace": manifest.get("coordinateSpace"), "entries": len(named)}

    # POIs of this version, search, routes.
    version = recon.get("scanVersionId")
    _, pois, _ = api.call("GET", f"/venues/{venue}/pois?scanVersionId={version}")
    report["pois"] = {"total": len(pois), "labels": sorted({p["label"] for p in pois})[:200]}
    if not pois:
        problems.append("the published version has no POIs: SEMANTIC_INDEXING detected nothing in this venue")
    report["search"] = []
    for q in args.search:
        _, res, _ = api.call("GET", f"/venues/{venue}/search?" + urllib.parse.urlencode({"q": q, "floorId": floor, "scanVersionId": version}))
        hits = [r for r in res["results"] if q.lower() in r["label"].lower() and r.get("source") == "AUTO_DETECTED"]
        report["search"].append({"query": q, "matchType": res["matchType"], "results": len(res["results"]),
                                 "matching": [{k: h.get(k) for k in ("label", "poiId", "source", "x", "y", "z")} for h in hits[:5]]})
        if not hits:
            problems.append(f"search for {q!r} found no detected object (AUTO_DETECTED POI) with that label")
    if args.route_from and args.route_to:
        target = next((h for s in report["search"] if s["query"] == args.route_to for h in s["matching"]), None)
        if target is None:
            problems.append(f"no POI found for the route destination {args.route_to!r} (add it to --search)")
        else:
            report["routes"] = []
            nav_sha = next((a["sha256"] for a in arts if a["kind"] == "NAVMESH"), None)
            for profile in ("STANDARD", "STEP_FREE"):
                body = {"venueId": venue, "floorId": floor, "start": args.route_from, "destinationPoiId": target["poiId"],
                        "accessibility": profile, "scanVersionId": version}
                status, route, raw = api.call("POST", "/navigation/routes", body, ok=(200, 404, 409, 422))
                entry = {"profile": profile, "status": status}
                if status == 200:
                    entry |= {"distanceMeters": route["distanceMeters"], "waypoints": len(route["waypoints"]),
                              "routingSources": route["routingSources"]}
                    if not any(s.get("navmeshSha256") == nav_sha for s in route["routingSources"]):
                        problems.append(f"{profile} route does not run on this run's NAVMESH {nav_sha}")
                else:
                    entry["refusal"] = route if route is not None else raw.decode(errors="replace")
                    problems.append(f"{profile} route refused ({status}): {entry['refusal']}")
                report["routes"].append(entry)

    if args.viewer_check:
        report["viewerCheck"] = viewer_check(api, venue, run_id, downloaded, out)
        if not report["viewerCheck"].get("ok"):
            problems.append(f"the pinned viewer library rejected the .ksplat: {report['viewerCheck']}")
    return problems


def viewer_check(api: Api, venue: str, run_id: str, downloaded: dict, out: Path) -> dict:
    """Loads the served .ksplat with the pinned viewer library (needs node and apps/web's dependencies). The API serves the
    viewer's own artifacts only, not the SPLAT_CLEAN cloud, so this checks what the viewer can check: the library accepts
    the file, reads every splat with finite values, and the count matches the file's size. The bit-exact cloud comparison
    is the worker's tests/splat/viewer_artifacts.py, run on the same files by the GPU acceptance test."""
    if shutil.which("node") is None or not (REPO / "apps" / "web" / "node_modules").is_dir():
        return {"ok": False, "notRun": "node or apps/web/node_modules is missing"}
    if "KSPLAT" not in downloaded:
        return {"ok": False, "notRun": "no .ksplat was downloaded"}
    d = out / "viewer-check"
    d.mkdir(exist_ok=True)
    (d / "scene.ksplat").write_bytes(downloaded["KSPLAT"])
    res = subprocess.run(["node", "--experimental-strip-types", "--no-warnings", "scripts/verify-ksplat-artifact.ts", str(d)],
                         cwd=REPO / "apps" / "web", capture_output=True, text=True, timeout=1800)
    lines = [ln for ln in res.stdout.splitlines() if ln.startswith("{")]
    return json.loads(lines[-1]) if lines else {"ok": False, "stderr": res.stderr[-2000:]}


# ---- main ---------------------------------------------------------------------------------------------------------------


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--api", required=True)
    ap.add_argument("--token-file", required=True, help="a bearer token of the venue's operator (venue manager for job leases)")
    ap.add_argument("--venue", required=True)
    ap.add_argument("--floor", required=True)
    ap.add_argument("--media", type=Path, help="directory of the capture's videos, photos and metadata JSON")
    ap.add_argument("--measurements", type=Path, help="JSON list of measurement bodies (observations name media by file name)")
    ap.add_argument("--resolved-points", type=Path, help='{"points": {"<label>": {"A": [x,y,z], "B": [...]}}, "gravity": {...}}')
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--resume", action="store_true", help="continue the capture recorded in <out>/state.json")
    ap.add_argument("--no-privacy", action="store_true", help="start processing with privacy disabled (admin token only)")
    ap.add_argument("--time-budget-hours", type=float, default=6.0)
    ap.add_argument("--timeout-hours", type=float, default=12.0)
    ap.add_argument("--retry-failed", type=int, default=0, help="retry a failed stage up to N times (never past a refusal)")
    ap.add_argument("--search", action="append", default=[])
    ap.add_argument("--route-from", type=lambda s: [float(v) for v in s.split(",")])
    ap.add_argument("--route-to")
    ap.add_argument("--viewer-check", action="store_true")
    args = ap.parse_args()

    out: Path = args.out
    out.mkdir(parents=True, exist_ok=True)
    api = Api(args.api, Path(args.token_file).read_text(encoding="utf-8").strip())
    state_path, report_path = out / "state.json", out / "report.json"
    state = json.loads(state_path.read_text()) if args.resume and state_path.exists() else {}
    report: dict[str, Any] = {"startedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "api": args.api,
                              "venue": args.venue, "floor": args.floor, "input": {"media": str(args.media)}}
    start = time.time()
    try:
        _, version, _ = api.call("GET", "/version")
        report["serverVersion"] = version

        # 1. capture
        capture = state.get("captureId")
        if capture is None:
            device = {"source": "scripts/acceptance/real_venue.py", "uploadHost": platform.platform(), "note":
                      "files recorded elsewhere; camera intrinsics are not known to this script unless a metadata file declares them"}
            _, cap, _ = api.call("POST", f"/venues/{args.venue}/captures", {"floorId": args.floor, "device": device})
            capture = cap["id"]
            state["captureId"] = capture
            state_path.write_text(json.dumps(state))
        report["captureId"] = capture
        _, cap, _ = api.call("GET", f"/venues/{args.venue}/captures/{capture}")
        if cap["status"] in ("CREATED", "UPLOADING"):
            if not args.media or not args.media.is_dir():
                raise Failure(2, "--media must be the capture directory")
            files = sorted(p for p in args.media.iterdir() if p.suffix.lower() in MEDIA_TYPES)
            _, existing, _ = api.call("GET", f"/venues/{args.venue}/captures/{capture}/media")
            uploads = [upload(api, args.venue, capture, f, existing) for f in files]
            for u in uploads:
                m = await_media(api, args.venue, capture, u["mediaId"], 3600)
                u |= {"status": m["status"], "rejection": m.get("rejectionCode"), "pixelSize": [m.get("pixelWidth"), m.get("pixelHeight")]}
                log(f"{u['file']}: {m['status']}")
            report["media"] = uploads
            bad = [u for u in uploads if u["status"] != "ACCEPTED"]
            if bad:
                raise Failure(1, f"the server did not accept {[(u['file'], u['status'], u['rejection']) for u in bad]}")
            if args.measurements and not state.get("measurementsRecorded"):
                by_name = {u["file"]: u["mediaId"] for u in uploads}
                report["measurements"] = record_measurements(api, args.venue, capture, json.loads(args.measurements.read_text()), by_name)
                state["measurementsRecorded"] = True
                state_path.write_text(json.dumps(state))
            api.call("POST", f"/venues/{args.venue}/captures/{capture}/complete-upload", {})
        _, cal, _ = api.call("GET", f"/venues/{args.venue}/captures/{capture}/calibration")
        report["calibrationEvidence"] = {k: cal[k] for k in ("state", "activeDistances", "activeControlPoints", "requirements")}

        # 2. processing
        _, cap, _ = api.call("GET", f"/venues/{args.venue}/captures/{capture}")
        if cap["status"] == "READY_FOR_PROCESSING":
            api.call("POST", f"/venues/{args.venue}/captures/{capture}/processing",
                     {"timeBudgetSeconds": int(args.time_budget_hours * 3600), **({"privacyEnabled": False} if args.no_privacy else {})})
        retries, last_line, history = 0, None, []
        while True:
            if time.time() - start > args.timeout_hours * 3600:
                raise Failure(4, "the run did not end in time")
            snap = snapshot(api, args.venue, capture)
            run = snap["processing"]["run"]
            line = f"{run['status']} {stage_line(run)}"
            if line != last_line:
                log(line)
                history.append({"t": round(time.time() - start, 1), "status": run["status"],
                                "stages": {s["stage"]: s["state"] for s in run["stages"]},
                                "jobs": [{k: j.get(k) for k in ("stage", "status", "workerId", "leaseExpiresAt", "retryCount")}
                                         for j in snap["jobs"]]})
                last_line = line
            report["timeline"] = history
            if run["status"] == "RUNNING":
                time.sleep(30)
                continue
            if run["status"] == "SUCCEEDED":
                break
            report["failure"] = {k: run.get(k) for k in ("status", "failureStage", "failureCode", "failureMessage", "retryable")}
            if run["status"] == "FAILED" and run.get("failureCode") == "NOT_CALIBRATED":
                _, cal, _ = api.call("GET", f"/venues/{args.venue}/captures/{capture}/calibration")
                report["calibrationStatus"] = cal
                if not args.resolved_points:
                    (out / "calibration-needed.json").write_text(json.dumps(cal, indent=2))
                    raise Failure(3, "the reconstruction needs calibration: pick the measured points in the reconstruction "
                                     "(see calibration-needed.json), then re-run with --resume --resolved-points <file>")
                calibrate(api, args.venue, capture, json.loads(args.resolved_points.read_text()), report)
                api.call("POST", f"/venues/{args.venue}/captures/{capture}/processing/retry")
                continue
            if run["status"] == "FAILED" and run.get("retryable") and retries < args.retry_failed:
                retries += 1
                log(f"retrying {run['failureStage']} ({run['failureCode']}), {retries}/{args.retry_failed}")
                api.call("POST", f"/venues/{args.venue}/captures/{capture}/processing/retry")
                continue
            raise Failure(1, f"the run ended {run['status']} at {run.get('failureStage')}: {run.get('failureCode')} "
                             f"{run.get('failureMessage')}")

        # 4. verification
        problems = verify(api, args, capture, run, out, report)
        report["problems"] = problems
        report["result"] = "VERIFIED" if not problems else "FAILED"
        return 0 if not problems else 1
    except Failure as e:
        report["result"] = {1: "FAILED", 2: "BAD_INPUT_OR_ACCESS", 3: "OPERATOR_INPUT_NEEDED", 4: "TIMED_OUT"}[e.code]
        report["error"] = str(e)
        log(str(e))
        return e.code
    finally:
        report["elapsedSeconds"] = round(time.time() - start, 1)
        report_path.write_text(json.dumps(report, indent=2, default=str))
        log(f"report: {report_path}")


if __name__ == "__main__":
    mimetypes.init()
    os.environ.setdefault("PYTHONUNBUFFERED", "1")
    sys.exit(main())
