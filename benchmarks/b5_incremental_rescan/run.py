"""Benchmark 5: incremental rescan (docs/BENCHMARKS.md).

Full reconstruction vs regional rescan. What can be measured without a GPU is measured; the rest is reported as
unavailable with the command that measures it.

1. Alignment accuracy (MEASURED, real geometry, controlled misalignment). The venue's "existing reconstruction" is a
   real COLMAP sparse cloud; the "rescanned region" is a crop of it, re-sampled, given sensor noise and moved by a
   KNOWN rigid transform. The pipeline's own chaya_worker.region_alignment.align_region (FPFH + RANSAC + ICP) must
   recover it. Because the true transform is known, the error is exact, and the production confidence gate
   (min_alignment_confidence) can be scored: does it reject the alignments that are actually wrong, and pass the
   ones that are right? SfM clouds have an arbitrary unit, so lengths are reported relative to the cloud (its
   bounding-box diagonal D and median nearest-neighbour spacing h).

2. Scope (CONFIGURATION, from the code): which stages a full run and a regional run execute
   (PipelineDefinition.STAGES / INCREMENTAL_STAGES), when navigation is rebuilt and which POIs are re-indexed
   (docs/rescan.md NAVIGATION / SEARCH).

3. Processing time, changed-area %, artifact counts, navigation and semantic re-index scope on a real venue
   (UNAVAILABLE here: both runs need SPLAT_RECONSTRUCTION, i.e. a CUDA GPU).

    python benchmarks/b5_incremental_rescan/run.py --sfm-points castle/points3D.txt [--trials 8]
"""

from __future__ import annotations

import argparse
import re
import statistics
import sys
import time
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from benchmarks.common.results import ROOT, Metric, Run, sha256_file, unavailable  # noqa: E402

ANGLES_DEG = (2.0, 10.0, 30.0, 90.0)
NOISE_H = (0.0, 1.0)            # Gaussian position noise, in multiples of the median NN spacing h
REGION_FRACTIONS = (0.3, 0.15)  # share of the cloud's points inside the rescanned region
KEEP = 0.8                      # the re-capture sees 80 % of the region's points (a different sampling of the same surfaces)
TRANSLATION_D = 0.05            # translation magnitude, as a fraction of D
VOXEL_H = 2.0                   # registration voxel size, in multiples of h
VOXEL_SWEEP_H = (2.0, 4.0, 8.0, 16.0)  # sensitivity sweep on the noisy condition
SUCCESS_ROT_DEG = 2.0           # success criterion (assumption): rotation error below this ...
SUCCESS_TRANS_D = 0.01          # ... and translation error below this fraction of D


def read_xyz(path: Path) -> np.ndarray:
    rows = [ln.split()[1:4] for ln in path.read_text(encoding="utf-8").splitlines() if ln and not ln.startswith("#")]
    return np.asarray(rows, dtype=float)


def random_rotation(angle_deg: float, rng: np.random.Generator) -> np.ndarray:
    axis = rng.normal(size=3)
    axis /= np.linalg.norm(axis)
    a = np.radians(angle_deg)
    k = np.array([[0, -axis[2], axis[1]], [axis[2], 0, -axis[0]], [-axis[1], axis[0], 0]])
    return np.eye(3) + np.sin(a) * k + (1 - np.cos(a)) * (k @ k)


def one_trial(target: np.ndarray, *, angle: float, noise_h: float, fraction: float, seed: int, h: float, diag: float,
              min_conf: float, voxel_h: float = VOXEL_H) -> dict:
    import open3d as o3d
    from chaya_worker.region_alignment import align_region, rotation_angle_degrees

    rng = np.random.default_rng(seed)
    o3d.utility.random.seed(seed)
    centre = target[rng.integers(len(target))]
    d = np.linalg.norm(target - centre, axis=1)
    region = target[d <= np.quantile(d, fraction)]
    region = region[rng.random(len(region)) < KEEP]
    if noise_h > 0:
        region = region + rng.normal(0, noise_h * h, region.shape)
    rot = random_rotation(angle, rng)
    direction = rng.normal(size=3)
    trans = direction / np.linalg.norm(direction) * TRANSLATION_D * diag
    source = region @ rot.T + trans  # the region as the new capture sees it, in its own frame
    t0 = time.perf_counter()
    res = align_region(source, target, voxel_size=voxel_h * h)
    secs = time.perf_counter() - t0
    # align_region maps source -> target; source = R x + t, so the truth is the inverse (R^T, -R^T t).
    exp_r, exp_t = rot.T, -rot.T @ trans
    rot_err = rotation_angle_degrees(res.transform[:3, :3] @ exp_r.T)
    trans_err = float(np.linalg.norm(res.transform[:3, 3] - exp_t))
    ok = rot_err < SUCCESS_ROT_DEG and trans_err < SUCCESS_TRANS_D * diag
    return {"angle": angle, "noise_h": noise_h, "fraction": fraction, "seed": seed, "voxel_h": voxel_h, "points": len(source),
            "rotation_error_deg": rot_err, "translation_error_D": trans_err / diag, "inlier_rmse_h": res.inlier_rmse / h,
            "fitness": res.fitness, "confidence": res.confidence, "passes_gate": res.confidence >= min_conf, "success": ok,
            "seconds": secs}


def negative_control(target: np.ndarray, *, seed: int, h: float, diag: float, min_conf: float) -> dict:
    """A 'region' that does not exist in the venue: the crop is mirrored, so nothing in the target matches it rigidly."""
    import open3d as o3d
    from chaya_worker.region_alignment import align_region
    rng = np.random.default_rng(1000 + seed)
    o3d.utility.random.seed(1000 + seed)
    centre = target[rng.integers(len(target))]
    d = np.linalg.norm(target - centre, axis=1)
    region = target[d <= np.quantile(d, 0.3)] * np.array([-1.0, 1.0, 1.0])  # a reflection is not a rigid motion
    res = align_region(region, target, voxel_size=VOXEL_H * h)
    return {"confidence": res.confidence, "fitness": res.fitness, "passes_gate": res.confidence >= min_conf}


def stages(name: str) -> list[str]:
    src = (ROOT / "services/api/src/main/java/dev/chaya/api/pipeline/PipelineDefinition.java").read_text(encoding="utf-8")
    block = re.search(rf"List<JobStage> {name} = List\.of\((.*?)\);", src, re.S).group(1)
    return re.findall(r"JobStage\.(\w+)", block)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--sfm-points", type=Path, required=True)
    ap.add_argument("--trials", type=int, default=8)
    a = ap.parse_args()
    import open3d
    from chaya_worker.settings import Settings

    min_conf = Settings().min_alignment_confidence
    target = read_xyz(a.sfm_points)
    from scipy.spatial import cKDTree
    h = float(np.median(cKDTree(target).query(target, k=2)[0][:, 1]))
    diag = float(np.linalg.norm(target.max(axis=0) - target.min(axis=0)))

    run = Run("b5_incremental_rescan", "Incremental rescan: alignment accuracy (measured) and scope (configuration)")
    run.environment = {"open3d": open3d.__version__}
    run.inputs = {"sfm_points": {"file": a.sfm_points.name, "sha256": sha256_file(a.sfm_points), "points": len(target)}}
    cond0 = "alignment experiment"
    run.add(Metric("existing reconstruction", f"real COLMAP sparse cloud, {len(target)} points", "", "assumption", cond0,
                   a.sfm_points.name, note="stands in for a venue's cleaned splat; sparse SfM points are noisier and far sparser"),
            Metric("cloud diagonal D", round(diag, 4), "SfM units", "measured", cond0, "bounding box"),
            Metric("median nearest-neighbour spacing h", round(h, 5), "SfM units", "measured", cond0, "cKDTree"),
            Metric("registration voxel size", f"{VOXEL_H} h", "", "configuration", cond0, "this benchmark",
                   note="production uses 0.05 m on metric splats; SfM units are arbitrary"),
            Metric("confidence gate", min_conf, "", "configuration", cond0, "Settings.min_alignment_confidence"),
            Metric("rescan simulation", f"crop {', '.join(f'{f:.0%}' for f in REGION_FRACTIONS)} of the points, keep {KEEP:.0%}, "
                   f"rotate {', '.join(f'{x:g}' for x in ANGLES_DEG)} deg about a random axis, translate {TRANSLATION_D:.0%} D, "
                   f"noise {'/'.join(f'{x:g}' for x in NOISE_H)} h", "", "configuration", cond0, "this benchmark"),
            Metric("success criterion", f"rotation error < {SUCCESS_ROT_DEG} deg and translation error < {SUCCESS_TRANS_D:.0%} D",
                   "", "assumption", cond0, "this benchmark"))

    trials = []
    for fraction in REGION_FRACTIONS:
        for noise in NOISE_H:
            for angle in ANGLES_DEG:
                for seed in range(a.trials):
                    trials.append(one_trial(target, angle=angle, noise_h=noise, fraction=fraction, seed=seed, h=h, diag=diag,
                                            min_conf=min_conf))
    run.details["trials"] = trials
    for fraction in REGION_FRACTIONS:
        for noise in NOISE_H:
            for angle in ANGLES_DEG:
                ts = [t for t in trials if t["fraction"] == fraction and t["noise_h"] == noise and t["angle"] == angle]
                c = f"region {fraction:.0%}, noise {noise:g} h, rotation {angle:g} deg"
                src = f"align_region x{len(ts)} (seeds 0..{len(ts) - 1})"
                run.add(Metric("alignment success rate", round(sum(t["success"] for t in ts) / len(ts), 3), "fraction", "measured", c, src),
                        Metric("median rotation error", round(statistics.median(t["rotation_error_deg"] for t in ts), 3), "deg", "measured", c, src),
                        Metric("median translation error", round(100 * statistics.median(t["translation_error_D"] for t in ts), 3), "% of D",
                               "measured", c, src),
                        Metric("median inlier RMSE", round(statistics.median(t["inlier_rmse_h"] for t in ts), 3), "h", "measured", c, src),
                        Metric("median confidence", round(statistics.median(t["confidence"] for t in ts), 3), "", "measured", c, src),
                        Metric("median alignment time", round(statistics.median(t["seconds"] for t in ts), 3), "s", "measured", c, src))
    good = [t for t in trials if t["success"]]
    bad = [t for t in trials if not t["success"]]
    g = "confidence gate, all trials"
    run.add(Metric("successful alignments", f"{len(good)}/{len(trials)}", "trials", "measured", g, "align_region"),
            Metric("failed alignments the gate rejects", f"{sum(not t['passes_gate'] for t in bad)}/{len(bad)}", "trials", "measured", g,
                   "confidence < gate", note="a failed alignment that passes the gate would be spliced in wrongly"),
            Metric("successful alignments the gate rejects", f"{sum(not t['passes_gate'] for t in good)}/{len(good)}", "trials", "measured",
                   g, "confidence < gate", note="a false rejection costs a re-capture, not a wrong splice"))
    # Voxel-size sensitivity: is the noisy failure a property of the registration or of the voxel chosen above?
    sweep = []
    for voxel in VOXEL_SWEEP_H:
        ts = [one_trial(target, angle=10.0, noise_h=1.0, fraction=0.3, seed=sd, h=h, diag=diag, min_conf=min_conf, voxel_h=voxel)
              for sd in range(a.trials)]
        sweep += ts
        c = f"voxel sensitivity: region 30%, noise 1 h, rotation 10 deg, voxel {voxel:g} h"
        src = f"align_region x{len(ts)}"
        run.add(Metric("alignment success rate", round(sum(t["success"] for t in ts) / len(ts), 3), "fraction", "measured", c, src),
                Metric("median rotation error", round(statistics.median(t["rotation_error_deg"] for t in ts), 3), "deg", "measured", c, src),
                Metric("median translation error", round(100 * statistics.median(t["translation_error_D"] for t in ts), 3), "% of D",
                       "measured", c, src),
                Metric("median confidence", round(statistics.median(t["confidence"] for t in ts), 3), "", "measured", c, src),
                Metric("gate decisions correct", f"{sum(t['success'] == t['passes_gate'] for t in ts)}/{len(ts)}", "trials", "measured", c, src))
    run.details["voxel_sweep"] = sweep
    neg = [negative_control(target, seed=s, h=h, diag=diag, min_conf=min_conf) for s in range(a.trials)]
    run.add(Metric("non-matching regions the gate rejects", f"{sum(not n['passes_gate'] for n in neg)}/{len(neg)}", "trials", "measured",
                   "negative control (mirrored crop)", "align_region", note="max confidence "
                   f"{max(n['confidence'] for n in neg):.3f}"))
    run.details["negative_control"] = neg

    full, inc = stages("STAGES"), stages("INCREMENTAL_STAGES")
    sc = "stage plan"
    run.add(Metric("stages, full reconstruction", len(full), "stages", "configuration", sc, "PipelineDefinition.STAGES", note=" > ".join(full)),
            Metric("stages, regional rescan (navigation affected)", len(inc), "stages", "configuration", sc, "PipelineDefinition.INCREMENTAL_STAGES",
                   note=" > ".join(inc)),
            Metric("stages, regional rescan (navigation not affected)", len(inc) - 1, "stages", "configuration", sc,
                   "incrementalPlan(..., includeNavigationBaking=false)",
                   note="NAVIGATION_BAKING is left out when no ACTIVE routing-graph node lies in the region"),
            Metric("stages only in the regional plan", ", ".join(s for s in inc if s not in full), "", "configuration", sc, "diff"),
            Metric("stages only in the full plan", ", ".join(s for s in full if s not in inc), "", "configuration", sc, "diff",
                   note="a regional rescan runs GEOMETRIC_CLEANUP without semantic labels"),
            Metric("frames a regional run processes", "only the region's own capture", "", "configuration", sc, "docs/rescan.md",
                   note="the first stages never see the rest of the venue's frames"),
            Metric("semantic re-index scope", "POIs whose latest position lies inside the region polygon (superseded), plus new detections",
                   "", "configuration", sc, "PipelineService#supersedePoisInRegion"),
            Metric("navigation rebuild scope", "whole floor when the region touches the ACTIVE graph, otherwise none", "", "configuration",
                   sc, "RescanService#navigationIntersectsRegion", note="Recast re-bakes the full floor; no per-tile partial bake"))

    why = "both a full and a regional run need SPLAT_RECONSTRUCTION, which needs a CUDA GPU; this host has none"
    sql = ("SELECT r.id, s.stage, extract(epoch FROM s.finished_at - s.started_at) FROM pipeline_stage_run s JOIN pipeline_run r "
           "ON r.id = s.run_id WHERE r.id IN ('<full run>', '<regional run>')")
    for metric, unit, cmd in (
            ("processing time, full vs regional", "s", sql),
            ("artifacts produced, full vs regional", "count", "SELECT s.run_id, count(*) FROM processing_artifact a JOIN pipeline_stage_run s "
             "ON s.id = a.stage_run_id WHERE s.run_id IN ('<full run>', '<regional run>') GROUP BY 1"),
            ("changed-area percentage", "%", "scan_version.region_geometry area / floor area, from GET /venues/{v}/floors/{f}/scan-versions"),
            ("alignment error on a real re-capture", "m", "scan_version.alignment_residual_m of the regional run (Open3D inlier RMSE); "
             "absolute error additionally needs surveyed control points"),
            ("navigation nodes rebuilt", "count", "compare navigation_node counts of the ACTIVE graph before and after the regional run"),
            ("POIs re-indexed", "count", "count poi rows soft-deleted by the regional run (supersedePoisInRegion) + AUTO_DETECTED rows it inserted")):
        run.add(unavailable(metric, unit, why, cmd, condition="real venue, full vs regional"))
    path = run.write()
    for m in run.metrics:
        print(f"  [{m.kind:13}] {m.condition:48} {m.metric}: {m.value} {m.unit}")
    print(f"wrote {path}")


if __name__ == "__main__":
    main()
