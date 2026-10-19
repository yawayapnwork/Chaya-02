"""Records measurements through the API, then submits a calibration with the triangulated reconstruction coordinates.

argv: <api> <token file> <venue> <capture> <measurements json> <media.json> <resolved.json> <out json>
"""
import json
import sys
import urllib.request

api, tok, venue, capture, meas_path, media_path, resolved_path, out = sys.argv[1:9]
token = open(tok).read().strip()
by_name = {x["filename"]: x["id"] for x in json.load(open(media_path))}


def call(method, path, body=None):
    req = urllib.request.Request(api + "/api/v1" + path, method=method, data=None if body is None else json.dumps(body).encode(),
                                 headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            return r.status, json.loads(r.read() or b"null")
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"null")


log = {"recorded": [], "calibration": None}
ids = {}
for m in json.load(open(meas_path)):
    body = dict(m)
    body["observations"] = [{k: v for k, v in o.items() if k != "media"} | {"mediaId": by_name[o["media"]]} for o in m["observations"]]
    s, r = call("POST", f"/venues/{venue}/captures/{capture}/measurements", body)
    log["recorded"].append({"label": m["label"], "status": s, "response": r if s >= 400 else {"id": r["id"], "valueMeters": r.get("valueMeters")}})
    if s < 300:
        ids[m["label"]] = r["id"]
resolved = json.load(open(resolved_path))
points = [{"measurementId": ids[label], "point": p, "reconstruction": xyz}
          for label, ab in resolved["points"].items() if label in ids for p, xyz in ab.items()]
s, r = call("POST", f"/venues/{venue}/captures/{capture}/calibration",
            {"resolvedPoints": points, "gravity": resolved["gravity"],
             "note": "ETH3D office GT distances; reconstruction points triangulated from the marked pixels through this run's POSES"})
log["calibration"] = {"status": s, "response": r}
json.dump(log, open(out, "w"), indent=1)
print(json.dumps(log, indent=1)[:4000])
