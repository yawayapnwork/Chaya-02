"""Benchmark 5: incremental rescan (docs/BENCHMARKS.md).

Full reconstruction vs regional rescan. What can be measured without a GPU is measured; the rest is reported as
unavailable with the command that measures it.

1. Alignment accuracy (MEASURED, real geometry, controlled misalignment). The venue's "existing reconstruction" is a
   real COLMAP sparse cloud. The "rescanned region" is a crop of it, re-sampled, given sensor noise, and moved by a
   KNOWN similarity transform: a rotation, a translation and a scale error of the kind a re-scan's own metric
   calibration leaves. The pipeline's own chaya_worker.region_alignment.align_region must recover it: FEATURE_SIMILARITY
   mode, i.e. FPFH matches, RANSAC similarity, then similarity ICP. The true transform is known, so the error is exact,
   and the production quality gates can be scored: do they reject the alignments that are actually wrong, and pass the
   ones that are right?

   SfM clouds have an arbitrary unit. Lengths are reported relative to the cloud (its bounding-box diagonal D and median
   nearest-neighbour spacing h). Every metre threshold of the production gates is applied in multiples of h, with the
   production defaults' own ratios to their 2 cm ICP voxel (see GATE_H below).

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
SCALES = (1.0, 1.08)            # scale error between the re-scan's metric calibration and the venue's (prior: +/-10 %)
NOISE_H = (0.0, 1.0)            # Gaussian position noise, in multiples of the median NN spacing h
REGION_FRACTIONS = (0.3, 0.15)  # share of the cloud's points inside the rescanned region
KEEP = 0.8                      # the re-capture sees 80 % of the region's points (a different sampling of the same surfaces)
TRANSLATION_D = 0.05            # translation magnitude, as a fraction of D
VOXEL_H = 2.0                   # registration voxel size, in multiples of h
VOXEL_SWEEP_H = (2.0, 4.0, 8.0, 16.0)  # sensitivity sweep on the noisy condition
SUCCESS_ROT_DEG = 2.0           # success criterion (assumption): rotation error below this ...
SUCCESS_TRANS_D = 0.01          # ... and translation error below this fraction of D ...
SUCCESS_SCALE = 0.01            # ... and relative scale error below this
# Production defaults in metres, with a 0.02 m ICP voxel: final ICP distance 0.1 (5 voxels), ICP residual 0.05 (2.5),
# translation residual 0.03 (1.5). The same ratios, with the ICP voxel = h:
ICP_VOXEL_H = 1.0
ICP_SCHEDULE_H = (40.0, 20.0, 10.0, 5.0)
GATE_H = {"max_icp_residual_m": 2.5, "max_translation_residual_m": 1.5}


def read_xyz(path: Path) -> np.ndarray:
    rows = [ln.split()[1:4] for ln in path.read_text(encoding="utf-8").splitlines() if ln and not ln.startswith("#")]
    return np.asarray(rows, dtype=float)


def random_rotation(angle_deg: float, rng: np.random.Generator) -> np.ndarray:
    axis = rng.normal(size=3)
    axis /= np.linalg.norm(axis)
    a = np.radians(angle_deg)
    k = np.array([[0, -axis[2], axis[1]], [axis[2], 0, -axis[0]], [-axis[1], axis[0], 0]])
    return np.eye(3) + np.sin(a) * k + (1 - np.cos(a)) * (k @ k)


def _gates(h: float):
    from chaya_worker.settings import Settings

    g = Settings().alignment_gates()
    return type(g)(**{**g.__dict__, **{k: v * h for k, v in GATE_H.items()}})


def _align(source: np.ndarray, target: np.ndarray, *, h: float, voxel_h: float):
    from chaya_worker.region_alignment import MODE_FEATURE, align_region

    return align_region(source, target, mode=MODE_FEATURE, gates=_gates(h), icp_schedule_m=[x * h for x in ICP_SCHEDULE_H],
                        icp_voxel_m=ICP_VOXEL_H * h, feature_voxel_m=voxel_h * h)


def one_trial(target: np.ndarray, *, angle: float, noise_h: float, fraction: float, seed: int, h: float, diag: float,
              scale: float = 1.0, voxel_h: float = VOXEL_H) -> dict:
    from chaya_worker.frames import Similarity
    from chaya_worker.similarity_registration import rotation_angle_degrees

    rng = np.random.default_rng(seed)
    centre = target[rng.integers(len(target))]
    d = np.linalg.norm(target - centre, axis=1)
    region = target[d <= np.quantile(d, fraction)]
    region = region[rng.random(len(region)) < KEEP]
    if noise_h > 0:
        region = region + rng.normal(0, noise_h * h, region.shape)
    direction = rng.normal(size=3)
    moved = Similarity(scale, random_rotation(angle, rng), direction / np.linalg.norm(direction) * TRANSLATION_D * diag)
    source = moved.apply(region)  # the region as the re-capture sees it: its own frame, its own scale error
    t0 = time.perf_counter()
    res = _align(source, target, h=h, voxel_h=voxel_h)
    secs = time.perf_counter() - t0
    truth = moved.inverse()  # align_region maps source -> target
    rot_err = rotation_angle_degrees(res.correction.rotation @ truth.rotation.T)
    trans_err = float(np.linalg.norm(res.correction.apply(source.mean(axis=0)) - region.mean(axis=0)))
    scale_err = abs(res.correction.scale / truth.scale - 1.0)
    ok = rot_err < SUCCESS_ROT_DEG and trans_err < SUCCESS_TRANS_D * diag and scale_err < SUCCESS_SCALE
    m = res.metrics
    return {"angle": angle, "scale": scale, "noise_h": noise_h, "fraction": fraction, "seed": seed, "voxel_h": voxel_h,
            "points": len(source), "rotation_error_deg": rot_err, "translation_error_D": trans_err / diag, "scale_error": scale_err,
            "icp_residual_h": m.icp_residual_m / h if np.isfinite(m.icp_residual_m) else None, "inlier_ratio": m.inlier_ratio,
            "confidence": m.confidence, "passes_gate": res.accepted, "failed_gates": res.gates.failed, "error": res.error,
            "ransac_inliers": res.details.get("ransac_inliers"), "success": ok, "seconds": secs}


def negative_control(target: np.ndarray, *, seed: int, h: float, diag: float) -> dict:
    """A 'region' that does not exist in the venue: the crop is mirrored, so no similarity maps it onto the venue."""
    rng = np.random.default_rng(1000 + seed)
    centre = target[rng.integers(len(target))]
    d = np.linalg.norm(target - centre, axis=1)
    region = target[d <= np.quantile(d, 0.3)] * np.array([-1.0, 1.0, 1.0])  # a reflection is not a similarity
    res = _align(region, target, h=h, voxel_h=VOXEL_H)
    return {"confidence": res.metrics.confidence, "inlier_ratio": res.metrics.inlier_ratio, "passes_gate": res.accepted,
            "failed_gates": res.gates.failed}


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
            Metric("alignment", "FEATURE_SIMILARITY: FPFH matches, RANSAC similarity (scale prior +/-10 %), similarity ICP", "",
                   "configuration", cond0, "chaya_worker.region_alignment.align_region"),
            Metric("FPFH voxel size", f"{VOXEL_H} h", "", "configuration", cond0, "this benchmark",
                   note="production uses 0.05 m on metric splats; SfM units are arbitrary"),
            Metric("quality gates", "production defaults (Settings.alignment_gates); length limits in h: ICP voxel "
                   f"{ICP_VOXEL_H} h, ICP schedule {'/'.join(f'{x:g}' for x in ICP_SCHEDULE_H)} h, "
                   + ", ".join(f"{k} {v} h" for k, v in GATE_H.items()), "", "configuration", cond0, "this benchmark",
                   note="a trial 'passes' only when every gate passes, as in production"),
            Metric("rescan simulation", f"crop {', '.join(f'{f:.0%}' for f in REGION_FRACTIONS)} of the points, keep {KEEP:.0%}, "
                   f"rotate {', '.join(f'{x:g}' for x in ANGLES_DEG)} deg about a random axis, translate {TRANSLATION_D:.0%} D, "
                   f"scale x{'/'.join(f'{x:g}' for x in SCALES)}, noise {'/'.join(f'{x:g}' for x in NOISE_H)} h", "", "configuration",
                   cond0, "this benchmark"),
            Metric("success criterion", f"rotation error < {SUCCESS_ROT_DEG} deg, translation error < {SUCCESS_TRANS_D:.0%} D and "
                   f"scale error < {SUCCESS_SCALE:.0%}", "", "assumption", cond0, "this benchmark"))

    trials = []
    for fraction in REGION_FRACTIONS:
        for noise in NOISE_H:
            for scale in SCALES:
                for angle in ANGLES_DEG:
                    for seed in range(a.trials):
                        trials.append(one_trial(target, angle=angle, noise_h=noise, fraction=fraction, seed=seed, h=h, diag=diag,
                                                scale=scale))
    run.details["trials"] = trials
    for fraction in REGION_FRACTIONS:
        for noise in NOISE_H:
            for scale in SCALES:
                for angle in ANGLES_DEG:
                    ts = [t for t in trials if t["fraction"] == fraction and t["noise_h"] == noise and t["angle"] == angle
                          and t["scale"] == scale]
                    c = f"region {fraction:.0%}, noise {noise:g} h, scale x{scale:g}, rotation {angle:g} deg"
                    src = f"align_region x{len(ts)} (seeds 0..{len(ts) - 1})"
                    run.add(Metric("alignment success rate", round(sum(t["success"] for t in ts) / len(ts), 3), "fraction", "measured", c, src),
                            Metric("gate pass rate", round(sum(t["passes_gate"] for t in ts) / len(ts), 3), "fraction", "measured", c, src),
                            Metric("median rotation error", round(statistics.median(t["rotation_error_deg"] for t in ts), 3), "deg",
                                   "measured", c, src),
                            Metric("median translation error", round(100 * statistics.median(t["translation_error_D"] for t in ts), 3),
                                   "% of D", "measured", c, src),
                            Metric("median scale error", round(100 * statistics.median(t["scale_error"] for t in ts), 3), "%", "measured",
                                   c, src),
                            Metric("median confidence", round(statistics.median(t["confidence"] for t in ts), 3), "", "measured", c, src),
                            Metric("median alignment time", round(statistics.median(t["seconds"] for t in ts), 3), "s", "measured", c, src))
    good = [t for t in trials if t["success"]]
    bad = [t for t in trials if not t["success"]]
    g = "quality gates, all trials"
    run.add(Metric("successful alignments", f"{len(good)}/{len(trials)}", "trials", "measured", g, "align_region"),
            Metric("failed alignments the gates reject", f"{sum(not t['passes_gate'] for t in bad)}/{len(bad)}", "trials", "measured", g,
                   "any gate failed", note="a failed alignment that passes the gates would be spliced in wrongly"),
            Metric("successful alignments the gates reject", f"{sum(not t['passes_gate'] for t in good)}/{len(good)}", "trials", "measured",
                   g, "any gate failed", note="a false rejection costs a re-capture, not a wrong splice"))
    # Voxel-size sensitivity: is the noisy failure a property of the registration or of the voxel chosen above?
    sweep = []
    for voxel in VOXEL_SWEEP_H:
        ts = [one_trial(target, angle=10.0, noise_h=1.0, fraction=0.3, seed=sd, h=h, diag=diag, voxel_h=voxel)
              for sd in range(a.trials)]
        sweep += ts
        c = f"FPFH voxel sensitivity: region 30%, noise 1 h, scale x1, rotation 10 deg, voxel {voxel:g} h"
        src = f"align_region x{len(ts)}"
        run.add(Metric("alignment success rate", round(sum(t["success"] for t in ts) / len(ts), 3), "fraction", "measured", c, src),
                Metric("median rotation error", round(statistics.median(t["rotation_error_deg"] for t in ts), 3), "deg", "measured", c, src),
                Metric("median translation error", round(100 * statistics.median(t["translation_error_D"] for t in ts), 3), "% of D",
                       "measured", c, src),
                Metric("median confidence", round(statistics.median(t["confidence"] for t in ts), 3), "", "measured", c, src),
                Metric("gate decisions correct", f"{sum(t['success'] == t['passes_gate'] for t in ts)}/{len(ts)}", "trials", "measured", c, src))
    run.details["voxel_sweep"] = sweep
    neg = [negative_control(target, seed=s, h=h, diag=diag) for s in range(a.trials)]
    run.add(Metric("non-matching regions the gates reject", f"{sum(not n['passes_gate'] for n in neg)}/{len(neg)}", "trials", "measured",
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
            Metric("semantic re-index scope", "AUTO_DETECTED POIs inside the region polygon (superseded) plus the new detections; "
                   "MANUAL POIs never", "", "configuration", sc, "PipelineService#applyRescanDetections",
                   note="applied only when the re-scan's version finalizes; a failed or rejected re-scan changes no POI"),
            Metric("navigation rebuild scope", "whole floor when the region touches the ACTIVE graph, otherwise none", "", "configuration",
                   sc, "RescanService#navigationIntersectsRegion",
                   note="Recast re-bakes the full floor (no per-tile partial bake); the new graph goes live only when the version finalizes"),
            Metric("viewer asset / plane rebuild scope", "whole floor", "", "configuration", sc, "ARTIFACT_GENERATION, PLANE_FITTING",
                   note="the .ksplat is one file and the planes are refitted over the merged cloud: not selective"))

    why = "both a full and a regional run need SPLAT_RECONSTRUCTION, which needs a CUDA GPU; this host has none"
    sql = ("SELECT r.id, s.stage, extract(epoch FROM s.finished_at - s.started_at) FROM pipeline_stage_run s JOIN pipeline_run r "
           "ON r.id = s.run_id WHERE r.id IN ('<full run>', '<regional run>')")
    for metric, unit, cmd in (
            ("processing time, full vs regional", "s", sql),
            ("artifacts produced, full vs regional", "count", "SELECT s.run_id, count(*) FROM processing_artifact a JOIN pipeline_stage_run s "
             "ON s.id = a.stage_run_id WHERE s.run_id IN ('<full run>', '<regional run>') GROUP BY 1"),
            ("changed-area percentage", "%", "scan_version.region_geometry area / floor area, from GET /venues/{v}/floors/{f}/scan-versions"),
            ("alignment error on a real re-capture", "m", "scan_version.alignment_report of the regional run (residuals, gates); "
             "absolute error additionally needs surveyed control points"),
            ("navigation nodes rebuilt", "count", "compare navigation_node counts of the ACTIVE graph before and after the regional run"),
            ("POIs re-indexed", "count", "audit_log 'rescan.downstream_applied' of the regional run: poisSuperseded + poisCreated")):
        run.add(unavailable(metric, unit, why, cmd, condition="real venue, full vs regional"))
    path = run.write()
    for m in run.metrics:
        print(f"  [{m.kind:13}] {m.condition:48} {m.metric}: {m.value} {m.unit}")
    print(f"wrote {path}")


if __name__ == "__main__":
    main()
