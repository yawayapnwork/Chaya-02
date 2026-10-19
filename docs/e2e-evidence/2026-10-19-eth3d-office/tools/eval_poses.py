"""Compares Chaya's POSE_ESTIMATION output (poses.json, COLMAP world-to-camera) with ETH3D ground truth camera centres.

Similarity (Umeyama) alignment of estimated centres onto GT centres, then per-camera position error in metres. Also the
recovered scale, and whether the estimated model is metric (it is not: SfM has arbitrary scale until calibrated).
argv: <run artifacts dir> <gt_cameras.json> <out json>
"""
import glob
import json
import math
import sys

import numpy as np

run, gt_path, out = sys.argv[1:4]
manifest = json.load(open(glob.glob(f"{run}/FFMPEG_PREPROCESS/*/*/frames-manifest.json")[0]))
poses = json.load(open(glob.glob(f"{run}/POSE_ESTIMATION/*/*/poses.json")[0]))
gt = json.load(open(gt_path))


media = {x["id"]: x["filename"] for x in json.load(open(sys.argv[4]))}


def frame_sources(m):
    # frames[i] came from sources[i] (one frame per RAW_IMAGE); the artifact id is the capture media id
    assert all(s_["frames"] == 1 for s_ in m["sources"]), "a source with several frames: map by counts"
    return {f: media[s_["artifact_id"]] for f, s_ in zip(m["frames"], m["sources"], strict=True)}


src = frame_sources(manifest)


def centre(q, t):
    w, x, y, z = q
    R = np.array([[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
                  [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
                  [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])
    return -R.T @ np.array(t)


est, ref, names = [], [], []
for p in poses["poses"]:
    original = src.get(p["name"])
    if original is None:
        continue
    key = original.split("/")[-1]
    if key not in gt:
        continue
    est.append(centre(p["rotation_wxyz"], p["translation"]))
    ref.append(gt[key])
    names.append((p["name"], key))
est, ref = np.array(est), np.array(ref)
n = len(est)
mu_e, mu_r = est.mean(0), ref.mean(0)
E, Rr = est - mu_e, ref - mu_r
U, S, Vt = np.linalg.svd(Rr.T @ E / n)
D = np.eye(3)
if np.linalg.det(U @ Vt) < 0:
    D[2, 2] = -1
R = U @ D @ Vt
s = np.trace(np.diag(S) @ D) / (E ** 2).sum(1).mean()
aligned = (s * (R @ E.T)).T + mu_r
err = np.linalg.norm(aligned - ref, axis=1)
spread = np.linalg.norm(Rr, axis=1).max()
res = {"matched_cameras": n, "registered": poses["registered"], "similarity_scale_est_to_metric": float(s),
       "position_error_m": {"rmse": float(math.sqrt((err ** 2).mean())), "median": float(np.median(err)), "max": float(err.max())},
       "gt_camera_spread_m": float(spread), "per_camera": [{"frame": a, "image": b, "error_m": float(e)} for (a, b), e in zip(names, err)],
       "note": "Similarity alignment of camera centres only (7 DoF fitted on the same cameras it is evaluated on); "
               "the estimated model is in arbitrary SfM units until calibrated."}
json.dump(res, open(out, "w"), indent=1)
print(json.dumps({k: v for k, v in res.items() if k != "per_camera"}, indent=1))
print("registered images:", sorted(b for _, b in names))
