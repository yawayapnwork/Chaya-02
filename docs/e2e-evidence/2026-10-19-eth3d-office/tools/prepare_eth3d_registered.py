"""Builds a Chaya capture directory and calibration evidence from ETH3D 'office' (dslr undistorted).

Real data only:
  * media: the 26 undistorted DSLR photos, hard-linked (bytes unchanged), plus one metadata JSON with ETH3D's own PINHOLE
    calibration (cameras.txt), source stated;
  * measurements: DISTANCE between pairs of ETH3D ground-truth 3-D points (points3D.txt, registered to ETH3D's laser scan,
    metres), each point marked at its ground-truth pixel (images.txt POINTS2D, COLMAP corner convention) in two photos.
    Also writes gt_points.json (the points' GT coordinates) for later evaluation, and gt_cameras.json (GT camera centres).
"""
import json
import math
import os
import sys
from itertools import combinations
from pathlib import Path

root = Path(sys.argv[1])          # .../eth3d/office
out = Path(sys.argv[2])           # output dir
allowed = set(json.load(open(sys.argv[3]))) if len(sys.argv) > 3 else None  # only mark points in these photos
prefix = sys.argv[4] if len(sys.argv) > 4 else "eth3d-gt-distance"
gt = root / "dslr_calibration_undistorted"
imgdir = root / "images" / "dslr_images_undistorted"
cap = out / "capture"
cap.mkdir(parents=True, exist_ok=True)


def rows(p):
    return [l.split() for l in p.read_text().splitlines() if l and not l.startswith("#")]


cam = rows(gt / "cameras.txt")[0]
model, w, h, params = cam[1], int(cam[2]), int(cam[3]), [float(x) for x in cam[4:]]

lines = [l for l in (gt / "images.txt").read_text().splitlines() if not l.startswith("#")]
images = {}
for i in range(0, len(lines) - 1, 2):
    head = lines[i].split()
    iid, q, t, name = int(head[0]), [float(x) for x in head[1:5]], [float(x) for x in head[5:8]], head[9]
    pts = lines[i + 1].split()
    p2d = [(float(pts[k]), float(pts[k + 1]), int(pts[k + 2])) for k in range(0, len(pts), 3)]
    images[iid] = {"name": Path(name).name, "q": q, "t": t, "p2d": p2d}


def centre(img):
    qw, qx, qy, qz = img["q"]
    R = [[1 - 2 * (qy * qy + qz * qz), 2 * (qx * qy - qz * qw), 2 * (qx * qz + qy * qw)],
         [2 * (qx * qy + qz * qw), 1 - 2 * (qx * qx + qz * qz), 2 * (qy * qz - qx * qw)],
         [2 * (qx * qz - qy * qw), 2 * (qy * qz + qx * qw), 1 - 2 * (qx * qx + qy * qy)]]
    t = img["t"]
    return [-sum(R[r][c] * t[r] for r in range(3)) for c in range(3)]


points = {}
for r in rows(gt / "points3D.txt"):
    pid, xyz, err = int(r[0]), [float(x) for x in r[1:4]], float(r[7])
    track = [(int(r[k]), int(r[k + 1])) for k in range(8, len(r), 2)]
    points[pid] = {"xyz": xyz, "err": err, "track": track}

# well-observed points: seen in >= 4 images, low reprojection error
good = [pid for pid, p in points.items() if len(p["track"]) >= 4 and p["err"] < 1.0]
good.sort(key=lambda pid: (-len(points[pid]["track"]), points[pid]["err"]))
good = good[:300]


def obs(pid, label):
    out_ = []
    used = set()
    for iid, idx in points[pid]["track"]:
        img = images[iid]
        if img["name"] in used or (allowed is not None and img["name"] not in allowed):
            continue
        u, v, _ = img["p2d"][idx]
        if 0 <= u < w and 0 <= v < h:
            out_.append({"point": label, "media": img["name"], "u": round(u, 2), "v": round(v, 2)})
            used.add(img["name"])
        if len(out_) == 2:
            break
    return out_


pairs, taken = [], set()
for a, b in combinations(good, 2):
    if a in taken or b in taken:
        continue
    d = math.dist(points[a]["xyz"], points[b]["xyz"])
    if 1.5 <= d <= 5.0:
        oa, ob = obs(a, "A"), obs(b, "B")
        if len(oa) == 2 and len(ob) == 2:
            pairs.append((a, b, d, oa + ob))
            taken |= {a, b}
    if len(pairs) == 4:
        break

measurements, gt_points = [], {}
for i, (a, b, d, o) in enumerate(pairs, 1):
    label = f"{prefix}-{i}"
    measurements.append({"kind": "DISTANCE", "label": label, "method": "OTHER", "unit": "m", "value": round(d, 4),
                         "uncertainty": 0.005, "observations": o})
    gt_points[label] = {"A": points[a]["xyz"], "B": points[b]["xyz"], "pointIds": [a, b]}

for img in images.values():
    dst = cap / img["name"]
    if not dst.exists():
        os.link(imgdir / img["name"], dst)
(cap / "camera-calibration.json").write_text(json.dumps({"cameraCalibration": {
    "model": model, "width": w, "height": h, "params": params, "pixelCoordinateOrigin": "CORNER",
    "source": "ETH3D high-res multi-view benchmark, scene 'office', dslr_calibration_undistorted/cameras.txt (published calibration)"}}, indent=1))
(out / ("measurements.json" if allowed is None else "measurements-registered.json")).write_text(json.dumps(measurements, indent=1))
(out / ("gt_points.json" if allowed is None else "gt_points-registered.json")).write_text(json.dumps(gt_points, indent=1))
(out / "gt_cameras.json").write_text(json.dumps({img["name"]: centre(img) for img in images.values()}, indent=1))
print(model, w, h, params)
print(len(images), "images;", len(measurements), "distance measurements:", [m["value"] for m in measurements])
