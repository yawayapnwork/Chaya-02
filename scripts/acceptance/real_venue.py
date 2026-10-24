#!/usr/bin/env python3
"""REAL-VENUE ACCEPTANCE RUN of the whole capture-to-digital-twin workflow, through the production API only.

Nothing here substitutes for a stage: it uploads YOUR capture, records YOUR measurements, starts processing, and then only
observes and verifies what the workers and the control plane really did. It never retries past a failure on its own
(unless --retry-failed), never injects an artifact, and never marks anything published. Python 3.11+, standard library only.
The procedure, the input files' formats and the thresholds: docs/REAL_VENUE_ACCEPTANCE.md.

  python scripts/acceptance/real_venue.py --api https://api.example --token-file op.token [--token-command "<cmd>"] \\
      --venue <id> --floor <id> --media ./capture --measurements measurements.json --capture-metadata capture.json \\
      --consent consent.json --thresholds thresholds.json --out ./acceptance-out [--resolved-points resolved.json] \\
      [--search "exit sign" --search "reception desk"] [--route-from 1.0,2.0,0.0 --route-to "exit sign"] [--viewer-check]
  python scripts/acceptance/real_venue.py ... --check-prerequisites      # step 0 only: exit 0 ready, 5 BLOCKED

Steps, each recorded in <out>/report.json (merged, never overwritten, on --resume):
  0. prerequisites: the API answers, the venue and floor exist, a live worker offers every stage of the plan (the GPU
     stages included), capture metadata and a consent record are complete, the measurements are enough (>= 2 distances or
     >= 3 control points, each with an uncertainty, each point marked in >= 2 media), the media are supported, the
     thresholds are fixed. Anything missing is BLOCKED (exit 5) before a byte is uploaded. The source files are hashed
     into <out>/source-manifest.json; on --resume they must be unchanged.
  1. capture: create a session on the floor, upload every file in --media through the resumable multipart protocol
     (per-part SHA-256), wait for the server's verdict on each, record the measurements ("role": "check" measurements
     are recorded too, but kept out of the calibration: they are the independent scale check);
  2. processing: complete the upload, start processing, poll the persisted run, stage runs, artifacts and jobs until the
     run ends or needs the operator;
  3. calibration: when the metric stages fail NOT_CALIBRATED, submit --resolved-points for the calibration measurements
     and retry; without them, write what is needed and exit 3, so the run can be resumed with --resume;
  4. verification once the run SUCCEEDED and its version is published: artifacts, the served viewer asset re-hashed, the
     manifest, the floor's current version and frame, POIs, search, routes on this run's navigation graph;
  5. thresholds: every pre-registered threshold (--thresholds, fixed by its SHA-256 at the first run) is evaluated against
     what the API reports; a metric that was not measured FAILS its threshold, never passes it;
  6. <out>/stages.json and stages.md: per stage attempt, its inputs and outputs (ids, kinds, SHA-256), tool versions,
     times, exit status, error code and quality numbers; the run's resource usage when the token may read it.

Exit status: 0 verified; 1 the workflow or a threshold failed (the report says which); 2 bad arguments or access;
3 operator input needed (calibration points); 4 timed out; 5 BLOCKED (a prerequisite is missing).
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import mimetypes
import os
import platform
import shlex
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Callable
from pathlib import Path
from typing import Any

MEDIA_TYPES = {".mp4": ("VIDEO", "video/mp4"), ".mov": ("VIDEO", "video/quicktime"), ".webm": ("VIDEO", "video/webm"),
               ".mkv": ("VIDEO", "video/x-matroska"), ".jpg": ("IMAGE", "image/jpeg"), ".jpeg": ("IMAGE", "image/jpeg"),
               ".png": ("IMAGE", "image/png"), ".heic": ("IMAGE", "image/heic"), ".json": ("METADATA", "application/json")}
REPO = Path(__file__).resolve().parents[2]
# The stages of a full reconstruction plan (dev.chaya.api.pipeline.PipelineDefinition.STAGES): a live worker must offer each.
PLAN_STAGES = ["INPUT_VALIDATION", "FFMPEG_PREPROCESS", "FRAME_QUALITY_FILTER", "PRIVACY_PREPROCESS", "POSE_ESTIMATION",
               "SPLAT_RECONSTRUCTION", "SEMANTIC_SEGMENTATION", "GEOMETRIC_CLEANUP", "PLANE_FITTING", "ARTIFACT_GENERATION",
               "SEMANTIC_INDEXING", "NAVIGATION_BAKING"]
MIN_IMAGES = 20
METADATA_REQUIRED = {"venue_name": str, "room": str, "floor_label": str, "operator": str, "device": dict, "capture": dict,
                     "unavailable_sensors": list}
DEVICE_REQUIRED = ("model", "os", "app")
CAPTURE_REQUIRED = ("started_at", "ended_at", "settings", "orientation")
CONSENT_REQUIRED = ("authorized_by", "role", "date", "scope", "people_policy", "sensitive_material_reviewed")
PEOPLE_POLICIES = ("NO_PEOPLE_PRESENT", "CONSENT_OBTAINED", "BLURRED_BY_PIPELINE")
THRESHOLDS = {  # name -> (direction, what it is measured on)
    "min_registered_share": (">=", "POSE_ESTIMATION command: registered / frames"),
    "max_scale_relative_spread": ("<=", "the active coordinate frame's scaleRelativeSpread"),
    "max_control_point_rms_m": ("<=", "the active coordinate frame's controlPointRmsM (control points only)"),
    "max_check_distance_relative_error": ("<=", "worst |scale * reconstructed - measured| / measured over the check measurements"),
    "min_psnr_gain_db": (">=", "SPLAT_RECONSTRUCTION command: quality.psnr_gain_db (held-out views vs the SfM seed)"),
    "require_quality_gate": ("==", "SPLAT_RECONSTRUCTION command: quality.passed"),
    "require_canonical_frame": ("==", "the published reconstruction's frame is canonical (metric and gravity-aligned)"),
    "min_pois": (">=", "POIs of the published version"),
}


class Failure(Exception):
    def __init__(self, code: int, message: str):
        super().__init__(message)
        self.code = code


class TokenSource:
    """The bearer token, renewable. Access tokens live minutes, a real run hours (VALIDATION_2026-10-19 D4: the harness read
    its token once and every run longer than the token's lifetime failed). The file is re-read whenever it changes (an
    external refresher may rewrite it), and on a 401 the token is renewed once: by --token-command when given (its stdout
    is the new token), else by re-reading the file."""

    def __init__(self, path: Path, command: str | None = None, runner: Callable[..., Any] = subprocess.run):
        self.path, self.command, self._run = path, command, runner
        self._mtime: float | None = None
        self._token = ""
        self.renewals = 0
        self._read()

    def _read(self) -> None:
        try:
            st = self.path.stat()
        except OSError as e:
            raise Failure(2, f"cannot read the token file {self.path}: {e}") from e
        if st.st_mtime != self._mtime:
            self._token, self._mtime = self.path.read_text(encoding="utf-8").strip(), st.st_mtime

    def get(self) -> str:
        self._read()
        return self._token

    def renew(self) -> str:
        self.renewals += 1
        if self.command:
            res = self._run(shlex.split(self.command), capture_output=True, text=True, timeout=120)
            if res.returncode != 0 or not res.stdout.strip():
                raise Failure(2, f"--token-command failed ({res.returncode}): {res.stderr.strip()[:300]}")
            self._token = res.stdout.strip().splitlines()[-1]
            self.path.write_text(self._token, encoding="utf-8")
            self._mtime = self.path.stat().st_mtime
            return self._token
        self._mtime = None
        return self.get()


class Api:
    def __init__(self, base: str, token: TokenSource | str, opener: Callable[..., Any] = urllib.request.urlopen):
        if urllib.parse.urlsplit(base).scheme not in ("http", "https"):
            raise Failure(2, f"--api must be an http(s) URL, not {base!r}")
        self.base = base.rstrip("/") + "/api/v1"
        self.tokens = token
        self._open = opener

    def _token(self) -> str:
        return self.tokens if isinstance(self.tokens, str) else self.tokens.get()

    def _send(self, method: str, path: str, data: bytes | None, ctype: str | None, headers: dict | None, token: str):
        req = urllib.request.Request(self.base + path, data=data, method=method)  # noqa: S310 - http(s) only (__init__)
        req.add_header("Authorization", f"Bearer {token}")
        if ctype:
            req.add_header("Content-Type", ctype)
        for k, v in (headers or {}).items():
            req.add_header(k, v)
        try:
            with self._open(req, timeout=300) as res:
                return res.status, res.read()
        except urllib.error.HTTPError as e:
            return e.code, e.read()
        except (urllib.error.URLError, OSError) as e:  # refused, DNS, TLS, timeout: the API cannot be reached at all
            raise Failure(2, f"{method} {self.base}{path}: the API cannot be reached ({getattr(e, 'reason', e)})") from e

    def call(self, method: str, path: str, body: Any = None, *, raw: bytes | None = None, headers: dict | None = None,
             ok: tuple[int, ...] = (200, 201, 202, 204)) -> tuple[int, Any, bytes]:
        data = raw if raw is not None else (None if body is None else json.dumps(body).encode())
        ctype = "application/octet-stream" if raw is not None else ("application/json" if body is not None else None)
        status, payload = self._send(method, path, data, ctype, headers, self._token())
        if status == 401 and isinstance(self.tokens, TokenSource):
            status, payload = self._send(method, path, data, ctype, headers, self.tokens.renew())
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


def load_json(path: Path | None) -> Any:
    return None if path is None else json.loads(path.read_text(encoding="utf-8"))


# ---- 0. prerequisites -----------------------------------------------------------------------------------------------------


def check(name: str, ok: bool | None, detail: str) -> dict:
    return {"check": name, "status": "PASS" if ok else ("UNVERIFIED" if ok is None else "FAIL"), "detail": detail}


def placeholders(doc: Any, path: str = "") -> list[str]:
    """Fields still holding a template's "<...>" placeholder (scripts/acceptance/templates): unfilled, so not evidence."""
    if isinstance(doc, dict):
        return [p for k, v in doc.items() for p in placeholders(v, f"{path}.{k}" if path else k)]
    if isinstance(doc, list):
        return [p for i, v in enumerate(doc) for p in placeholders(v, f"{path}[{i}]")]
    return [path] if isinstance(doc, str) and doc.strip().startswith("<") and doc.strip().endswith(">") else []


def check_metadata(meta: Any) -> dict:
    if not isinstance(meta, dict):
        return check("capture metadata", False, "--capture-metadata is required (venue, room, floor, operator, device, capture, "
                                                "unavailable_sensors; docs/REAL_VENUE_ACCEPTANCE.md)")
    missing = [k for k, t in METADATA_REQUIRED.items() if not isinstance(meta.get(k), t) or meta.get(k) in ("", {})]
    missing += [f"device.{k}" for k in DEVICE_REQUIRED if not (meta.get("device") or {}).get(k)]
    missing += [f"capture.{k}" for k in CAPTURE_REQUIRED if not (meta.get("capture") or {}).get(k)]
    missing += [f"{p} (unfilled template placeholder)" for p in placeholders(meta)]
    return check("capture metadata", not missing, "missing: " + ", ".join(missing) if missing else
                 f"{meta['venue_name']} / {meta['floor_label']} / {meta['room']}, {meta['device']['model']}")


def check_consent(consent: Any, no_privacy: bool) -> dict:
    if not isinstance(consent, dict):
        return check("consent and authorization", False, "--consent is required: who authorized the capture, when, for what")
    missing = [k for k in CONSENT_REQUIRED if consent.get(k) in (None, "")]
    missing += [f"{p} (unfilled template placeholder)" for p in placeholders(consent)]
    if missing:
        return check("consent and authorization", False, "missing: " + ", ".join(missing))
    if consent["people_policy"] not in PEOPLE_POLICIES:
        return check("consent and authorization", False, f"people_policy must be one of {PEOPLE_POLICIES}")
    if consent["sensitive_material_reviewed"] is not True:
        return check("consent and authorization", False, "sensitive_material_reviewed must be true: screens, documents and "
                                                         "faces in the media were reviewed before upload")
    if no_privacy and consent["people_policy"] != "NO_PEOPLE_PRESENT":
        return check("consent and authorization", False, "--no-privacy is allowed only for a capture with people_policy "
                                                         "NO_PEOPLE_PRESENT")
    return check("consent and authorization", True, f"{consent['authorized_by']} ({consent['role']}), {consent['date']}")


def check_measurements(measurements: Any, media_names: set[str]) -> list[dict]:
    if not isinstance(measurements, list) or not measurements:
        return [check("measurements", False, "--measurements is required: >= 2 tape-measured distances or >= 3 surveyed "
                                             "control points")]
    out = []
    calib = [m for m in measurements if m.get("role") != "check"]
    distances = [m for m in calib if m.get("kind") == "DISTANCE"]
    controls = [m for m in calib if m.get("kind") == "CONTROL_POINT"]
    out.append(check("enough measurements", len(distances) >= 2 or len(controls) >= 3,
                     f"{len(distances)} distances, {len(controls)} control points for calibration (need >= 2 or >= 3)"))
    no_unc = [m.get("label") for m in measurements if not isinstance(m.get("uncertainty"), (int, float)) or m["uncertainty"] <= 0]
    out.append(check("measurement uncertainty", not no_unc, "every measurement has an uncertainty" if not no_unc else
                     f"no positive uncertainty: {no_unc}"))
    weak, unknown = [], []
    for m in measurements:
        points: dict[str, set[str]] = {}
        for o in m.get("observations", []):
            if o.get("media") not in media_names:
                unknown.append(f"{m.get('label')}: {o.get('media')}")
            points.setdefault(o.get("point"), set()).add(o.get("media"))
        need = ("A", "B") if m.get("kind") == "DISTANCE" else ("P",)
        weak += [f"{m.get('label')} {p}" for p in need if len(points.get(p, ())) < 2]
    out.append(check("measured points marked in >= 2 media", not weak and not unknown,
                     "every point is marked in at least two media" if not weak and not unknown else
                     f"under-observed: {weak}; naming media not in --media: {unknown}"))
    checks = [m for m in measurements if m.get("role") == "check"]
    out.append(check("independent check measurements", bool(checks) or None,
                     f"{len(checks)} held out of calibration" if checks else
                     "none: the scale cannot be checked independently (max_check_distance_relative_error will FAIL)"))
    return out


def check_media(files: list[Path], media_dir: Path | None) -> dict:
    if media_dir is None or not media_dir.is_dir():
        return check("capture media", False, "--media must be the capture directory")
    unsupported = sorted(p.name for p in media_dir.iterdir() if p.is_file() and p.suffix.lower() not in MEDIA_TYPES)
    kinds = [MEDIA_TYPES[p.suffix.lower()][0] for p in files]
    usable = "VIDEO" in kinds or kinds.count("IMAGE") >= MIN_IMAGES
    return check("capture media", usable and not unsupported,
                 f"{kinds.count('VIDEO')} videos, {kinds.count('IMAGE')} images" + (f"; unsupported: {unsupported}" if unsupported else "")
                 + ("" if usable else f"; need a video or >= {MIN_IMAGES} images"))


def check_thresholds(thresholds: Any) -> dict:
    if not isinstance(thresholds, dict) or not thresholds:
        return check("pre-registered thresholds", False, "--thresholds is required, fixed before the run "
                                                         "(docs/REAL_VENUE_ACCEPTANCE.md, 'Thresholds')")
    unknown = sorted(set(thresholds) - set(THRESHOLDS) - {"note"})
    return check("pre-registered thresholds", not unknown, f"{len(set(thresholds) - {'note'})} thresholds" + (f"; unknown: {unknown}" if unknown else ""))


def check_workers(api: Api, venue: str) -> dict:
    try:
        _, jobs, _ = api.call("GET", f"/venues/{venue}/ops/jobs?limit=1")
    except Failure as e:
        return check("live workers serve every stage", None, f"not readable with this token ({e}); a venue manager's token shows it")
    offered = {s for w in jobs["queue"]["workers"] if w.get("live") for s in w.get("stages", [])}
    missing = [s for s in PLAN_STAGES if s not in offered]
    return check("live workers serve every stage", not missing,
                 "every stage is offered by a live worker" if not missing else f"no live worker offers: {missing}")


def free_bytes(path: Path) -> int:
    return shutil.disk_usage(path).free


def prerequisites(api: Api, args, files: list[Path]) -> dict:
    checks = []
    try:
        _, version, _ = api.call("GET", "/version")
        checks.append(check("API reachable", True, json.dumps(version)[:200]))
        _, floors, _ = api.call("GET", f"/venues/{args.venue}/floors")
        checks.append(check("venue and floor", any(f["id"] == args.floor for f in floors), f"floor {args.floor} of venue {args.venue}"))
        checks.append(check_workers(api, args.venue))
    except Failure as e:
        checks.append(check("API reachable", False, str(e)))
    checks.append(check_metadata(load_json(args.capture_metadata)))
    checks.append(check_consent(load_json(args.consent), args.no_privacy))
    checks += check_measurements(load_json(args.measurements), {p.name for p in files})
    checks.append(check_media(files, args.media))
    checks.append(check_thresholds(load_json(args.thresholds)))
    need = 2 * sum(p.stat().st_size for p in files)
    checks.append(check("local disk", free_bytes(args.out) >= need, f"{free_bytes(args.out) // 2**20} MiB free, {need // 2**20} MiB wanted"))
    blockers = [c for c in checks if c["status"] == "FAIL"]
    return {"checks": checks, "blocked": bool(blockers), "blockers": [f"{c['check']}: {c['detail']}" for c in blockers]}


def source_manifest(files: list[Path], extra: list[Path]) -> dict:
    """The original source media and input files, hashed before upload. Kept with the report, never with the media."""
    entries = []
    for p in [*files, *extra]:
        st = p.stat()
        entries.append({"file": p.name, "role": "media" if p in files else "input", "sizeBytes": st.st_size, "sha256": sha256_file(p),
                        "modified": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(st.st_mtime))})
    return {"createdAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "files": entries}


def verify_unchanged(previous: dict, current: dict) -> list[str]:
    before = {e["file"]: e["sha256"] for e in previous["files"]}
    now = {e["file"]: e["sha256"] for e in current["files"]}
    return sorted({f for f in before.keys() | now.keys() if before.get(f) != now.get(f)})


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
    retries = 0
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
                        retries += 1
                        time.sleep(2 * attempt)
            part += 1
    api.call("POST", f"{base}/{media_id}/complete")
    return {"file": path.name, "mediaId": media_id, "sha256": sha, "sizeBytes": size, "resumed": bool(have),
            "partsAlreadyUploaded": len(have), "partRetries": retries}


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
        body = {k: v for k, v in m.items() if k != "role"} | {"observations": []}
        for o in m.get("observations", []):
            name = o.get("media")
            if name not in media_by_name:
                raise Failure(2, f"measurement {m.get('label')!r}: observation names media {name!r}, which is not in --media")
            body["observations"].append({k: v for k, v in o.items() if k != "media"} | {"mediaId": media_by_name[name]})
        _, view, _ = api.call("POST", f"/venues/{venue}/captures/{capture}/measurements", body)
        recorded.append({"label": view["label"], "id": view["id"], "kind": view["kind"], "unit": view["unit"], "role": m.get("role", "calibration"),
                         "measuredMetres": view["measuredMetres"], "uncertaintyMetres": view.get("uncertaintyMetres"),
                         "observations": len(view["observations"])})
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


def calibration_body(measurements: list[dict], resolved: dict, check_labels: set[str]) -> dict:
    """The calibration request: every ACTIVE calibration measurement the operator resolved. Check measurements are never
    sent: they stay independent of the scale they are used to check."""
    points = []
    for m in measurements:
        if m["status"] != "ACTIVE" or m["label"] in check_labels or m["label"] not in resolved.get("points", {}):
            continue
        for point, xyz in resolved["points"][m["label"]].items():
            points.append({"measurementId": m["id"], "point": point, "reconstruction": xyz})
    body = {"resolvedPoints": points, "note": "real-venue acceptance run"}
    if resolved.get("gravity"):
        body["gravity"] = resolved["gravity"]
    return body


def calibrate(api: Api, venue: str, capture: str, resolved: dict, check_labels: set[str], report: dict) -> None:
    _, measurements, _ = api.call("GET", f"/venues/{venue}/captures/{capture}/measurements")
    body = calibration_body(measurements, resolved, check_labels)
    status, view, raw = api.call("POST", f"/venues/{venue}/captures/{capture}/calibration", body, ok=(201, 400, 409, 422))
    report["calibration"] = {"status": status, "result": view if view is not None else raw.decode(errors="replace"),
                             "usedMeasurements": sorted({p["measurementId"] for p in body["resolvedPoints"]})}
    if status != 201:
        raise Failure(1, f"the calibration was refused: {view}")


def check_distances(measured: list[dict], resolved: dict, scale: float | None) -> list[dict]:
    """The independent scale check: each held-out DISTANCE's reconstructed length times the calibrated scale, against its
    tape value. Its uncertainty is reported next to the error, so an error inside the tape's own tolerance is visible."""
    out = []
    for m in measured:
        if m.get("role") != "check":
            continue
        pts = resolved.get("points", {}).get(m["label"], {})
        entry = {"label": m["label"], "measuredMetres": m["measuredMetres"], "uncertaintyMetres": m.get("uncertaintyMetres")}
        if scale is None or "A" not in pts or "B" not in pts:
            entry["status"] = "NOT_MEASURED"
            entry["why"] = "no calibrated scale" if scale is None else "its points A and B are not in --resolved-points"
        else:
            recon = math.dist(pts["A"], pts["B"]) * scale
            entry |= {"reconstructedMetres": round(recon, 4), "errorMetres": round(recon - m["measuredMetres"], 4),
                      "relativeError": round(abs(recon - m["measuredMetres"]) / m["measuredMetres"], 5), "status": "MEASURED"}
        out.append(entry)
    return out


# ---- 4. verification ----------------------------------------------------------------------------------------------------


def verify(api: Api, args, capture: str, run: dict, out: Path, report: dict) -> list[str]:
    problems: list[str] = []
    venue, floor = args.venue, args.floor
    run_id = run["id"]

    arts = []
    for stage in run["stages"]:
        last = stage.get("lastRun") or {}
        for a in last.get("artifacts", []):
            arts.append({"stage": stage["stage"], **{k: a.get(k) for k in ("id", "kind", "key", "contentType", "sizeBytes", "sha256", "partial")}})
            if f"/venue/{venue}/" not in a["key"] or f"/run/{run_id}/" not in a["key"]:
                problems.append(f"{stage['stage']} {a['kind']} key is not scoped to this venue and run: {a['key']}")
            if not a.get("sha256") or len(a["sha256"]) != 64 or a.get("sizeBytes", 0) <= 0:
                problems.append(f"{stage['stage']} {a['kind']} has no checksum or size")
    report["artifacts"] = arts

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


# ---- 5/6. thresholds and the stage table ----------------------------------------------------------------------------------


def _stage(run: dict | None, name: str) -> dict:
    for s in (run or {}).get("stages", []):
        if s["stage"] == name:
            return s.get("lastRun") or {}
    return {}


def measurements_for_thresholds(run: dict | None, frame: dict | None, checks: list[dict], report: dict) -> dict[str, Any]:
    pose = (_stage(run, "POSE_ESTIMATION").get("command") or {}).get("config") or {}
    splat = (_stage(run, "SPLAT_RECONSTRUCTION").get("command") or {}).get("config") or {}
    quality = splat.get("quality") or {}
    measured = [c["relativeError"] for c in checks if c["status"] == "MEASURED"]
    published = report.get("reconstruction", {}).get("coordinateFrame")
    return {
        "min_registered_share": pose["registered"] / pose["frames"] if pose.get("frames") else None,
        "max_scale_relative_spread": (frame or {}).get("scaleRelativeSpread"),
        "max_control_point_rms_m": (frame or {}).get("controlPointRmsM"),
        "max_check_distance_relative_error": max(measured) if measured and len(measured) == len(checks) else None,
        "min_psnr_gain_db": quality.get("psnr_gain_db"),
        "require_quality_gate": quality.get("passed"),
        "require_canonical_frame": None if published is None else bool(published.get("canonical")),
        "min_pois": (report.get("pois") or {}).get("total"),
    }


def evaluate_thresholds(thresholds: dict, values: dict[str, Any]) -> list[dict]:
    """Every pre-registered threshold against what was measured. Not measured is a FAIL, never a pass."""
    out = []
    for name, limit in thresholds.items():
        if name == "note":
            continue
        direction, source = THRESHOLDS[name]
        value = values.get(name)
        if value is None:
            status = "NOT_MEASURED"
        elif direction == ">=":
            status = "PASS" if value >= limit else "FAIL"
        elif direction == "<=":
            status = "PASS" if value <= limit else "FAIL"
        else:
            status = "PASS" if value == limit else "FAIL"
        out.append({"threshold": name, "limit": limit, "direction": direction, "measured": value, "source": source, "status": status})
    return out


def stage_table(run: dict | None) -> list[dict]:
    rows = []
    for s in (run or {}).get("stages", []):
        last = s.get("lastRun") or {}
        started, finished = last.get("startedAt"), last.get("finishedAt")
        command = last.get("command") or {}
        rows.append({
            "stage": s["stage"], "state": s["state"], "attempts": s.get("attempts"), "attempt": last.get("attempt"),
            "workerId": last.get("workerId"), "startedAt": started, "finishedAt": finished, "durationSeconds": _duration(started, finished),
            "exitStatus": last.get("exitStatus"), "errorCode": last.get("errorCode"),
            "inputs": [{k: i.get(k) for k in ("id", "kind", "stage", "sha256")} for i in last.get("inputs", [])],
            "outputs": [{k: a.get(k) for k in ("id", "kind", "sha256", "sizeBytes")} for a in last.get("artifacts", [])],
            "tools": command.get("tools"), "metrics": _metrics(s["stage"], command.get("config") or {}),
        })
    return rows


def _duration(a: str | None, b: str | None) -> float | None:
    if not a or not b:
        return None
    from datetime import datetime  # noqa: PLC0415

    def parse(t: str):
        return datetime.fromisoformat(t.replace("Z", "+00:00"))

    return round((parse(b) - parse(a)).total_seconds(), 3)


def _metrics(stage: str, config: dict) -> dict:
    keys = {"POSE_ESTIMATION": ("registered", "frames", "mapper_used", "camera_models", "gpu"),
            "SPLAT_RECONSTRUCTION": ("status", "completed_iterations", "gaussian_count", "quality", "training_seconds", "gpu_memory")}
    return {k: config.get(k) for k in keys.get(stage, ()) if k in config}


def stage_markdown(rows: list[dict]) -> str:
    lines = ["| Stage | State | Attempt | Worker | Duration (s) | Exit | Error | Inputs | Outputs | Metrics |", "|---|---|---|---|---|---|---|---|---|---|"]
    for r in rows:
        ins = "<br>".join(f"{i['kind']} `{(i['sha256'] or '')[:12]}`" for i in r["inputs"]) or "—"
        outs = "<br>".join(f"{o['kind']} `{(o['sha256'] or '')[:12]}`" for o in r["outputs"]) or "—"
        lines.append(f"| {r['stage']} | {r['state']} | {r['attempt'] or '—'} | {r['workerId'] or '—'} | {r['durationSeconds'] or '—'} | "
                     f"{r['exitStatus'] if r['exitStatus'] is not None else '—'} | {r['errorCode'] or '—'} | {ins} | {outs} | "
                     f"{json.dumps(r['metrics']) if r['metrics'] else '—'} |")
    return "\n".join(lines) + "\n"


def resources(api: Api, venue: str, scan_version: str | None) -> dict:
    try:
        _, res, _ = api.call("GET", f"/venues/{venue}/ops/resources?days=30")
    except Failure as e:
        return {"unavailable": str(e)}
    mine = next((v for v in res.get("scanVersions", []) if v.get("scanVersionId") == scan_version), None)
    return {"scanVersion": mine, "workers": res.get("workers"), "rateCard": res.get("rateCard")}


# ---- main ---------------------------------------------------------------------------------------------------------------


def parse_args(argv: list[str] | None = None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--api", required=True)
    ap.add_argument("--token-file", required=True, type=Path, help="the operator's bearer token (venue manager for job leases)")
    ap.add_argument("--token-command", help="a command whose stdout is a fresh token; run on a 401 (written to --token-file)")
    ap.add_argument("--venue", required=True)
    ap.add_argument("--floor", required=True)
    ap.add_argument("--media", type=Path, help="directory of the capture's videos, photos and metadata JSON")
    ap.add_argument("--measurements", type=Path, help="JSON list of measurement bodies (observations name media by file name)")
    ap.add_argument("--capture-metadata", type=Path, help="venue, room, floor, operator, device and capture settings (JSON)")
    ap.add_argument("--consent", type=Path, help="who authorized the capture, when, and how people and sensitive material are handled")
    ap.add_argument("--thresholds", type=Path, help="pre-registered acceptance thresholds (JSON), fixed by SHA-256 at the first run")
    ap.add_argument("--resolved-points", type=Path, help='{"points": {"<label>": {"A": [x,y,z], "B": [...]}}, "gravity": {...}}')
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--resume", action="store_true", help="continue the capture recorded in <out>/state.json")
    ap.add_argument("--check-prerequisites", action="store_true", help="run step 0 only")
    ap.add_argument("--no-privacy", action="store_true", help="start processing with privacy disabled (admin; NO_PEOPLE_PRESENT only)")
    ap.add_argument("--time-budget-hours", type=float, default=6.0)
    ap.add_argument("--timeout-hours", type=float, default=12.0)
    ap.add_argument("--retry-failed", type=int, default=0, help="retry a failed stage up to N times (never past a refusal)")
    ap.add_argument("--search", action="append", default=[])
    ap.add_argument("--route-from", type=lambda s: [float(v) for v in s.split(",")])
    ap.add_argument("--route-to")
    ap.add_argument("--viewer-check", action="store_true")
    return ap.parse_args(argv)


RESULTS = {1: "FAILED", 2: "BAD_INPUT_OR_ACCESS", 3: "OPERATOR_INPUT_NEEDED", 4: "TIMED_OUT", 5: "BLOCKED"}


def post_run(api: Api, args, run: dict, report: dict, out: Path) -> None:
    """Thresholds, the independent scale check, the stage table and resources, from whatever the run reached. A run that
    did not finish fails its thresholds (NOT_MEASURED), and a VERIFIED run with a failed threshold becomes FAILED."""
    try:
        _, cal, _ = api.call("GET", f"/venues/{args.venue}/captures/{report.get('captureId')}/calibration")
        frame = cal.get("activeFrame")
    except Failure:
        frame = None
    report["coordinateFrame"] = frame
    checks = check_distances(report.get("measurements") or [], load_json(args.resolved_points) or {}, (frame or {}).get("scale"))
    report["checkDistances"] = checks
    if args.thresholds:
        results = evaluate_thresholds(load_json(args.thresholds), measurements_for_thresholds(run, frame, checks, report))
        report["thresholds"] = results
        failed = [r for r in results if r["status"] != "PASS"]
        if failed and report.get("result") == "VERIFIED":
            report["result"] = "FAILED"
        report.setdefault("problems", []).extend(f"threshold {r['threshold']}: {r['status']} (measured {r['measured']}, "
                                                 f"required {r['direction']} {r['limit']})" for r in failed)
    rows = stage_table(run)
    (out / "stages.json").write_text(json.dumps(rows, indent=2, default=str))
    (out / "stages.md").write_text(stage_markdown(rows))
    report["resources"] = resources(api, args.venue, (report.get("reconstruction") or {}).get("scanVersionId"))


def main(argv: list[str] | None = None, *, api: Api | None = None) -> int:
    args = parse_args(argv)
    out: Path = args.out
    out.mkdir(parents=True, exist_ok=True)
    state_path, report_path = out / "state.json", out / "report.json"
    state = json.loads(state_path.read_text()) if args.resume and state_path.exists() else {}
    # A resumed run continues the same report: earlier sections (media, measurements) are kept, never overwritten (D4).
    report: dict[str, Any] = json.loads(report_path.read_text()) if args.resume and report_path.exists() else {}
    report.setdefault("startedAt", time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()))
    report.setdefault("sessions", []).append({"at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "resume": args.resume})
    report |= {"api": args.api, "venue": args.venue, "floor": args.floor, "input": {"media": str(args.media)}}
    start = time.time()
    run: dict | None = None
    finished = False
    try:
        if api is None:
            api = Api(args.api, TokenSource(args.token_file, args.token_command))
        files = sorted(p for p in args.media.iterdir() if p.suffix.lower() in MEDIA_TYPES) if args.media and args.media.is_dir() else []

        # 0. prerequisites, the source manifest and the frozen thresholds
        pre = prerequisites(api, args, files)
        report["prerequisites"] = pre
        if pre["blocked"]:
            raise Failure(5, "BLOCKED: " + "; ".join(pre["blockers"]))
        t_sha = sha256_file(args.thresholds)
        if state.setdefault("thresholdsSha256", t_sha) != t_sha:
            raise Failure(2, "the thresholds changed after the run started; they are pre-registered and may not be edited")
        extra = [p for p in (args.measurements, args.capture_metadata, args.consent, args.thresholds) if p]
        manifest = source_manifest(files, extra)
        manifest_path = out / "source-manifest.json"
        if args.resume and manifest_path.exists():
            changed = verify_unchanged(json.loads(manifest_path.read_text()), manifest)
            if changed:
                raise Failure(2, f"source files changed since the run started: {changed}")
        else:
            manifest_path.write_text(json.dumps(manifest, indent=2))
        state_path.write_text(json.dumps(state))
        report["thresholdsSha256"], report["captureMetadata"], report["consent"] = t_sha, load_json(args.capture_metadata), load_json(args.consent)
        if args.check_prerequisites:
            report["result"] = "READY"
            return 0

        _, version, _ = api.call("GET", "/version")
        report["serverVersion"] = version
        measurements_in = load_json(args.measurements)
        check_labels = {m["label"] for m in measurements_in if m.get("role") == "check"}

        # 1. capture
        capture = state.get("captureId")
        if capture is None:
            meta = report["captureMetadata"]
            device = {**meta["device"], "capture": meta["capture"], "unavailableSensors": meta["unavailable_sensors"],
                      "room": meta["room"], "source": "scripts/acceptance/real_venue.py", "uploadHost": platform.platform()}
            _, cap, _ = api.call("POST", f"/venues/{args.venue}/captures", {"floorId": args.floor, "device": device})
            capture = cap["id"]
            state["captureId"] = capture
            state_path.write_text(json.dumps(state))
        report["captureId"] = capture
        _, cap, _ = api.call("GET", f"/venues/{args.venue}/captures/{capture}")
        if cap["status"] in ("CREATED", "UPLOADING"):
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
            if not state.get("measurementsRecorded"):
                by_name = {u["file"]: u["mediaId"] for u in uploads}
                report["measurements"] = record_measurements(api, args.venue, capture, measurements_in, by_name)
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
        retries, last_line = 0, None
        history = report.setdefault("timeline", [])
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
            if run["status"] == "RUNNING":
                time.sleep(30)
                continue
            if run["status"] == "SUCCEEDED":
                break
            report["failure"] = {k: run.get(k) for k in ("status", "quality", "failureStage", "failureCode", "failureMessage", "retryable")}
            if run["status"] == "FAILED" and run.get("failureCode") == "NOT_CALIBRATED":
                _, cal, _ = api.call("GET", f"/venues/{args.venue}/captures/{capture}/calibration")
                report["calibrationStatus"] = cal
                if not args.resolved_points:
                    (out / "calibration-needed.json").write_text(json.dumps(cal, indent=2))
                    raise Failure(3, "the reconstruction needs calibration: pick the measured points in the reconstruction "
                                     "(see calibration-needed.json), then re-run with --resume --resolved-points <file>")
                calibrate(api, args.venue, capture, load_json(args.resolved_points), check_labels, report)
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
        post_run(api, args, run, report, out)
        finished = True
        return 0 if report["result"] == "VERIFIED" else 1
    except Failure as e:
        report["result"] = RESULTS[e.code]
        report["error"] = str(e)
        log(str(e))
        return e.code
    finally:
        if run is not None and api is not None and not finished:
            try:
                post_run(api, args, run, report, out)
            except Failure as e:
                report["postRunError"] = str(e)
        report["elapsedSeconds"] = round(time.time() - start, 1)
        report["tokenRenewals"] = getattr(getattr(api, "tokens", None), "renewals", 0)
        report_path.write_text(json.dumps(report, indent=2, default=str))
        log(f"report: {report_path}")


if __name__ == "__main__":
    mimetypes.init()
    os.environ.setdefault("PYTHONUNBUFFERED", "1")
    sys.exit(main())
