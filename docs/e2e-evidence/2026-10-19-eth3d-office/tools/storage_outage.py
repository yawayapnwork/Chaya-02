"""Object-storage outage against the running stack: upload one real photo with MinIO stopped, then after it is back.

argv: <api> <token file> <venue> <floor> <photo> <phase: down|up> <out json>
Records every HTTP status and problem code; asserts nothing itself (the report states what was expected).
"""
import hashlib
import json
import sys
import time
import urllib.request

api, tok, venue, floor, photo, phase, out = sys.argv[1:8]
token = open(tok).read().strip()
data = open(photo, "rb").read()
sha = hashlib.sha256(data).hexdigest()
steps = []


def call(method, path, body=None, raw=None, headers=None):
    h = {"Authorization": "Bearer " + token}
    if raw is None and body is not None:
        h["Content-Type"] = "application/json"
    h |= headers or {}
    req = urllib.request.Request(api + path, method=method, headers=h,
                                 data=raw if raw is not None else (json.dumps(body).encode() if body is not None else None))
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            b = r.read()
            res = (r.status, json.loads(b) if b else None)
    except urllib.error.HTTPError as e:
        b = e.read()
        try:
            res = (e.code, json.loads(b))
        except ValueError:
            res = (e.code, b[:200].decode(errors="replace"))
    except Exception as e:  # noqa: BLE001 - recorded
        res = (None, repr(e))
    steps.append({"call": f"{method} {path}", "status": res[0], "ms": round((time.perf_counter() - t0) * 1000, 1),
                  "code": res[1].get("code") if isinstance(res[1], dict) else None,
                  "detail": (res[1].get("detail") if isinstance(res[1], dict) else str(res[1]))})
    return res


call("GET", "/actuator/health/readiness")
call("GET", "/api/v1/health")
s, cap = call("POST", f"/api/v1/venues/{venue}/captures", {"floorId": floor, "device": {"model": "storage-outage-check"}})
if s and s < 300:
    base = f"/api/v1/venues/{venue}/captures/{cap['id']}/media"
    s, init = call("POST", base, {"kind": "IMAGE", "filename": photo.split("/")[-1], "contentType": "image/jpeg",
                                  "sizeBytes": len(data), "sha256": sha})
    if s and s < 300:
        part = init.get("partSizeBytes", len(data))
        n = (len(data) + part - 1) // part
        for i in range(n):
            chunk = data[i * part:(i + 1) * part]
            s, _ = call("PUT", f"{base}/{init["mediaId"]}/parts/{i + 1}", raw=chunk,
                        headers={"Content-Type": "application/octet-stream", "X-Part-SHA256": hashlib.sha256(chunk).hexdigest()})
            if not s or s >= 300:
                break
        else:
            call("POST", f"{base}/{init["mediaId"]}/complete", {})
            for _ in range(60):
                s, m = call("GET", f"{base}/{init["mediaId"]}")
                if isinstance(m, dict) and m.get("status") not in ("UPLOADING", "PENDING", "VALIDATING", "SCANNING"):
                    break
                time.sleep(2)
json.dump({"phase": phase, "steps": steps}, open(out, "w"), indent=1)
for st in steps:
    print(phase, st["call"][:90], st["status"], st["code"], (st["detail"] or "")[:120])
