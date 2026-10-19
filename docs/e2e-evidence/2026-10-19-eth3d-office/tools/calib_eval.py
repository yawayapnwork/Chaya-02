"""Triangulates the recorded measurement points (their marked pixels, in the ORIGINAL photos) through the run's estimated
poses, and derives the metric scale each measured distance implies. Writes resolved.json for the calibration API.

Pixels are scaled from the 6221x4146 photo to the 1080p frame the poses belong to (FFMPEG_PREPROCESS downscale; corner
convention, so scale is a plain multiply). argv: <run artifacts dir> <measurements.json> <media.json> <out dir>
"""
import glob
import json
import sys

import numpy as np

run, meas_path, media_path, out = sys.argv[1:5]
manifest = json.load(open(glob.glob(f"{run}/FFMPEG_PREPROCESS/*/*/frames-manifest.json")[0]))
poses = json.load(open(glob.glob(f"{run}/POSE_ESTIMATION/*/*/poses.json")[0]))
media = {x["id"]: x["filename"] for x in json.load(open(media_path))}
frame_of = {media[s["artifact_id"]]: f for f, s in zip(manifest["frames"], manifest["sources"])}
cam = poses["cameras"]["1"]
K = np.array([[cam["fx"], 0, cam["cx"]], [0, cam["fy"], cam["cy"]], [0, 0, 1]])
sx = cam["width"] / 6221 if "width" in cam else 1620 / 6221
sy = cam["height"] / 4146
by_frame = {p["name"]: p for p in poses["poses"]}


def P(p):
    w, x, y, z = p["rotation_wxyz"]
    R = np.array([[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
                  [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
                  [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])
    return K @ np.hstack([R, np.array(p["translation"])[:, None]])


def triangulate(obs):
    rows = []
    used = []
    for o in obs:
        f = frame_of.get(o["media"])
        if f not in by_frame:
            continue
        u, v = o["u"] * sx, o["v"] * sy
        M = P(by_frame[f])
        rows += [u * M[2] - M[0], v * M[2] - M[1]]
        used.append(o["media"])
    if len(used) < 2:
        return None, used
    _, _, Vt = np.linalg.svd(np.array(rows))
    X = Vt[-1]
    return X[:3] / X[3], used


meas = json.load(open(meas_path))
results, scales, resolved = [], [], {"points": {}, "gravity": {"source": "RECONSTRUCTED_FLOOR_PLANE"}}
for m in meas:
    A, ua = triangulate([o for o in m["observations"] if o["point"] == "A"])
    B, ub = triangulate([o for o in m["observations"] if o["point"] == "B"])
    if A is None or B is None:
        results.append({"label": m["label"], "status": "UNRESOLVED", "reason": "a point's marked views are not both registered",
                        "registeredViews": {"A": ua, "B": ub}})
        continue
    d_est = float(np.linalg.norm(A - B))
    scales.append(m["value"] / d_est)
    results.append({"label": m["label"], "measured_m": m["value"], "reconstruction_units": d_est, "implied_scale": m["value"] / d_est})
    resolved["points"][m["label"]] = {"A": A.tolist(), "B": B.tolist()}
summary = {"measurements": results, "resolved": len(resolved["points"]),
           "scales": scales, "scale_mean": float(np.mean(scales)) if scales else None,
           "scale_spread_rel": float((max(scales) - min(scales)) / np.mean(scales)) if len(scales) > 1 else None}
json.dump(summary, open(f"{out}/calibration-eval.json", "w"), indent=1)
json.dump(resolved, open(f"{out}/resolved.json", "w"), indent=1)
print(json.dumps(summary, indent=1))
