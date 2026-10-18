"""Benchmark 7: multi-room registration and stitching (docs/BENCHMARKS.md, docs/multi-room.md).

Runs the production stages (ROOM_REGISTRATION -> ROOM_STITCH -> NAVIGATION_BAKING) through the worker's orchestrator on
the SYNTHETIC three-room floor (services/reconstruction/tests/multiroom_scene.py: rooms A and B and a corridor C, joined
by three 1.2 m doorways, which gives one loop A-B-C). The truth is known exactly, so errors are exact. Nothing here is
a real room: every number describes the code on synthetic point clouds, never a real venue.

1. Registration error (MEASURED): each placed room's transform against the truth: median point error over the room's
   points (metres) and rotation error (degrees).
2. Loop-closure consistency (MEASURED): the cycle error of the loop before optimisation, and every accepted edge's
   residual after it.
3. Stitching time (MEASURED, this CPU): the stages' own timings and the wall clock of each stage run.
4. Seam quality (MEASURED, geometry only): the overlap misalignment ICP still finds, the median seam step, the fraction
   of duplicate Gaussians left, the colour step. Rendered seam quality (PSNR/SSIM across a seam) is UNAVAILABLE: it
   needs trained splats (CUDA GPU).
5. Route connectivity (MEASURED, real Recast/Detour): whether the baked navmesh joins every room, its doorway clear
   widths, and Detour routes between rooms. UNAVAILABLE without the chaya-navmesh binary.
6. Rejection (MEASURED): the share of trials the stages refuse for consistently wrong matches, a 5 % scale disagreement
   between two calibrations, and a room that saw only its neighbours' door frames.

Feature matching is the stand-in `oracle_matches` (true correspondences inside the real overlap, 60 % of them replaced
by random wrong matches). With Open3D installed the real FPFH matcher runs too; otherwise it is UNAVAILABLE with the
command that runs it.

    services/reconstruction/.venv/Scripts/python benchmarks/b7_multiroom/run.py [--trials 5] [--navmesh <chaya-navmesh>]
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import os
import shutil
import statistics
import sys
import tempfile
import time
from collections import Counter
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
WORKER = ROOT / "services" / "reconstruction"
sys.path[:0] = [str(ROOT), str(WORKER)]
from chaya_worker.frames import Similarity
from chaya_worker.ply import read_ply
from chaya_worker.recast import find_path, sha256_file
from chaya_worker.runner import CommandRunner
from chaya_worker.similarity_registration import rotation_angle_degrees
from chaya_worker.stages import room_registration as stage_module
from chaya_worker.toolchain import Toolchain
from tests.conftest import DERIVED_BUCKET, Harness
from tests.multiroom_scene import (
    CALIBRATION,
    LOCAL,
    Floor,
    oracle_matches,
    wire,
    wrong_matches,
)
from tests.rescan_scene import rotation

from benchmarks.common.results import Metric, Run, unavailable

NOISE_M = (0.003, 0.01)       # capture position noise
OUTLIERS = 0.6                # share of the stand-in matcher's correspondences replaced by random wrong ones
SCALE_ERROR = 1.05            # room B's calibration scale is off by this factor in the scale-inconsistency condition
WEAK_PEEKS = {"B": [], "A": [((1.0, 4.0), (4.0, 6.0))], "C": [((1.5, 2.5), (3.5, 4.0))]}  # B saw only its door frames
FLOOR_DENSITY = 2500          # points per square metre of floor for the navmesh condition (no hole in the 0.1 m grid)
ROUTES = {("A", "B"): ([1.0, 1.5, 0.0], [9.0, 2.0, 0.0]), ("A", "C"): ([1.0, 1.5, 0.0], [8.5, 5.0, 0.0]),
          ("B", "C"): ([9.0, 2.0, 0.0], [1.5, 5.0, 0.0])}  # venue metres, inside each room
OUTPUT_TO_VENUE = LOCAL["A"].inverse().compose(CALIBRATION["A"])  # the stitched floor is in room A's reconstruction frame
SCENE = "synthetic three-room floor (tests/multiroom_scene.py)"


def _download(h: Harness, report: dict, kind: str, dest: Path) -> Path:
    art = next(a for a in report["artifacts"] if a["kind"] == kind)
    out = dest / Path(art["key"]).name
    dest.mkdir(parents=True, exist_ok=True)
    h.storage.download(DERIVED_BUCKET, art["key"], out)
    return out


def _timed(h: Harness, order: dict, **kw) -> tuple[dict, float]:
    t = time.perf_counter()
    report = h.run(order, **kw)
    return report, time.perf_counter() - t


def registration_errors(floor: Floor, report: dict) -> dict[str, dict]:
    """Per placed room: the median point error in metres and the rotation error, against the scene's truth."""
    out = {}
    for entry in report["rooms"]:
        r = entry["space_id"][-1]
        if not entry.get("registered") or entry["space_id"] == report["anchor"]:
            continue
        truth = CALIBRATION["A"].inverse().compose(LOCAL["A"]).compose(LOCAL[r].inverse()).compose(CALIBRATION[r])
        recon = CALIBRATION[r].inverse().apply(floor.canonical[r])
        got = Similarity.from_dict(entry["to_output"])
        err = np.linalg.norm(got.apply(recon) - truth.apply(recon), axis=1) * CALIBRATION["A"].scale  # A recon units -> m
        rot = rotation_angle_degrees(Similarity.from_dict(entry["pose_canonical"]).rotation
                                     @ LOCAL["A"].compose(LOCAL[r].inverse()).rotation.T)
        out[r] = {"median_error_m": float(np.median(err)), "p95_error_m": float(np.percentile(err, 95)), "rotation_error_deg": rot}
    return out


def _pairs(report: dict) -> list[dict]:
    return [{k: p[k] for k in ("source", "target", "status", "outcome", "failed_gates")} for p in report.get("pairs") or []]


def stitch_trial(work: Path, *, seed: int, noise_m: float, matcher: str, density: int = 200, floor_density: int | None = None,
                 allow_unconfirmed: bool = False) -> dict:
    """One registration + stitch of the three rooms. Returns the measurements, or the refusal (with the error of every room
    the refused registration had placed: a refusal must never hide a wrong placement)."""
    work.mkdir(parents=True)
    h = Harness(work)
    floor = Floor(h, work, density=density, floor_density=floor_density, seed=seed, noise_m=noise_m)
    saved = stage_module.match_features
    if matcher == "oracle":
        stage_module.match_features = oracle_matches(floor.canonical, LOCAL, outliers=OUTLIERS, seed=100 + seed)
    try:
        reg, reg_wall = _timed(h, floor.order("ROOM_REGISTRATION", allow_unconfirmed=allow_unconfirmed))
        if reg["status"] != "SUCCEEDED":
            details = reg.get("errorDetails") or {}
            return {"status": "REGISTRATION_FAILED", "code": reg.get("errorCode"), "message": reg.get("errorMessage"),
                    "unregistered": details.get("unregistered"), "pairs": _pairs(details),
                    "placed_errors": registration_errors(floor, details) if details.get("rooms") else {}}
        rreport = json.loads(_download(h, reg, "ROOM_REGISTRATION_REPORT", work / "dl").read_text())
        stitch, stitch_wall = _timed(h, floor.order("ROOM_STITCH", extra_inputs=h.outputs_as_inputs(reg)))
    finally:
        stage_module.match_features = saved
    out = {"status": "REGISTERED", "registration": registration_errors(floor, rreport),
           "loops": rreport["graph"]["loop_closures"],
           "edges": [e for e in rreport["graph"]["edges"] if e["status"] == "ACCEPTED"],
           "pairs": _pairs(rreport), "unconfirmed": rreport.get("unconfirmed"),
           "registration_seconds": rreport["timing_seconds"], "registration_wall_seconds": reg_wall,
           "h": h, "work": work}
    if stitch["status"] != "SUCCEEDED":
        out.update(status="STITCH_FAILED", code=stitch.get("errorCode"), message=stitch.get("errorMessage"))
        return out
    files = {k: _download(h, stitch, k, work / "dl") for k in ("SPLAT_MERGED", "SEMANTIC_LABELS_MERGED", "STITCH_REPORT")}
    sreport = json.loads(files["STITCH_REPORT"].read_text())
    out.update(status="STITCHED", files=files, stitch_seconds=sreport["timing_seconds"], stitch_wall_seconds=stitch_wall,
               gaussians_in=sum(b["input"] for b in sreport["blocks"]), gaussians_out=sreport["total_after"],
               duplicate_fraction=sreport["duplicate_fraction_after"],
               overlaps=[{"pieces": p["pieces"], "offset_m": (p["misalignment"] or {}).get("offset_m"),
                          "rotation_deg": (p["misalignment"] or {}).get("rotation_deg"),
                          "plane_median_m": (p["misalignment"] or {}).get("median_m"), "colour_step": p["colour_step"]}
                         for p in sreport["pairs"]],
               seams=[{"pieces": s["pieces"], "median_m": s.get("median_m"), "p90_m": s.get("p90_m")} for s in sreport["seams"]])
    return out


def rejection_trial(work: Path, *, seed: int, condition: str) -> dict:
    """ROOM_REGISTRATION on a floor it should refuse; returns its status, code and the pairs' outcomes."""
    work.mkdir(parents=True)
    h = Harness(work)
    floor = Floor(h, work, seed=seed, peeks=WEAK_PEEKS if condition == "weak overlap" else None)
    order = floor.order("ROOM_REGISTRATION", allow_unconfirmed=condition == "weak overlap")
    if condition == "consistently wrong matches":
        matcher = wrong_matches(Similarity(1.0, rotation([0, 0, 1], 90.0), np.array([1.5, -0.5, 0.0])), seed=200 + seed)
    elif condition == "scale inconsistency":
        # Room B's calibration claims 5 % more metres than there are. The matcher still pairs the points that really
        # correspond, so the geometry, not the matcher, has to show that the two calibrations disagree.
        b = next(r for r in order["rooms"] if r["spaceId"] == "space-B")
        b["coordinateFrame"]["scale"] *= SCALE_ERROR
        b["coordinateFrame"]["translation"] = {k: v * SCALE_ERROR for k, v in b["coordinateFrame"]["translation"].items()}
        seen_frames = {**LOCAL, "B": Similarity(SCALE_ERROR, np.eye(3), np.zeros(3)).compose(LOCAL["B"])}
        seen = {**floor.canonical, "B": floor.canonical["B"] * SCALE_ERROR}
        matcher = oracle_matches(seen, seen_frames, outliers=OUTLIERS, seed=300 + seed)
    else:
        matcher = oracle_matches(floor.canonical, LOCAL, outliers=OUTLIERS, seed=400 + seed)
    saved = stage_module.match_features
    stage_module.match_features = matcher
    try:
        reg = h.run(order)
    finally:
        stage_module.match_features = saved
    details = reg.get("errorDetails") or {}
    pairs = details.get("pairs") or []
    placed = registration_errors(floor, details) if details.get("rooms") and condition != "scale inconsistency" else {}
    return {"status": reg["status"], "code": reg.get("errorCode"), "unregistered": sorted(details.get("unregistered") or {}),
            "outcomes": sorted({p["outcome"] for p in pairs if p["status"] != "ACCEPTED"}), "placed_errors": placed}


def _plane_model(splat: Path, labels: list[str]) -> dict:
    """PLANE_FITTING's floor plane for the stitched cloud (Open3D RANSAC is not run): the floor-labelled Gaussians at floor
    height and the venue's up direction in the cloud's own frame (as tests/navmesh/test_stitched_floor.py)."""
    cloud = read_ply(splat)
    venue = OUTPUT_TO_VENUE.apply(cloud.positions)
    floor = np.flatnonzero((np.array(labels) == "floor") & (np.abs(venue[:, 2]) < 0.02))
    up = OUTPUT_TO_VENUE.inverse().apply_direction(np.array([0.0, 0.0, 1.0]))
    return {"planes": [{"equation": [*up.tolist(), 0.0], "inlier_indices": floor.tolist()}],
            "source": {"splat": {"kind": "SPLAT_MERGED", "sha256": sha256_file(splat)}}}


def navigation_trial(trial: dict, tool: str) -> dict:
    h, work, files = trial["h"], trial["work"], trial["files"]
    planes = work / "planes.json"
    planes.write_text(json.dumps(_plane_model(files["SPLAT_MERGED"], json.loads(files["SEMANTIC_LABELS_MERGED"].read_text())["labels"])))

    def ref(kind, path, ct="application/octet-stream"):
        r = h.raw_input(kind, path, ct)
        r["containsPii"] = False
        return r
    order = h.order("NAVIGATION_BAKING", [ref("SPLAT_MERGED", files["SPLAT_MERGED"]), ref("PLANE_MODEL", planes),
                                          ref("SEMANTIC_LABELS_MERGED", files["SEMANTIC_LABELS_MERGED"], "application/json"),
                                          ref("STITCH_REPORT", files["STITCH_REPORT"], "application/json")])
    order["coordinateFrame"] = wire("A")
    report, wall = _timed(h, order, toolchain=Toolchain(env={"CHAYA_NAVMESH_BIN": tool}))
    if report["status"] != "SUCCEEDED":
        return {"status": "FAILED", "code": report.get("errorCode"), "message": report.get("errorMessage"), "wall_seconds": wall}
    manifest = json.loads(_download(h, report, "NAVMESH_MANIFEST", work / "nav").read_text())
    navmesh = _download(h, report, "NAVMESH", work / "nav")
    rooms = manifest["rebuild"]["rooms"]
    runner = CommandRunner(work / "o.log", work / "e.log")
    routes = {}
    for (a, b), (start, end) in ROUTES.items():
        try:
            path = find_path(tool, navmesh, LOCAL["A"].apply(np.array(start)), LOCAL["A"].apply(np.array(end)), work, runner,
                             snap_horizontal_m=0.5, snap_vertical_m=0.5)["straight_path"]
            pts = LOCAL["A"].inverse().apply(np.array(path))
            routes[f"{a}-{b}"] = {"found": True, "length_m": float(np.linalg.norm(np.diff(pts[:, :2], axis=0), axis=1).sum()),
                                  "reaches_m": float(np.linalg.norm(pts[-1, :2] - np.array(end[:2])))}
        except Exception as exc:  # noqa: BLE001 - a route that cannot be found is a measurement, not a crash
            routes[f"{a}-{b}"] = {"found": False, "error": str(exc)}
    return {"status": "SUCCEEDED", "wall_seconds": wall, "connected": rooms["connected"],
            "step_free_connected": rooms["step_free_connected"], "unreachable": rooms["unreachable_rooms"],
            "clear_widths": [{"rooms": w["rooms"], "clear_width_m": w["clear_width_m"]} for w in rooms["clear_widths"]],
            "connections": [{"rooms": c["rooms"], "step_free": c["step_free"]} for c in rooms["connections"]], "routes": routes}


def _navmesh_tool(explicit: str | None) -> str | None:
    exe = "chaya-navmesh.exe" if os.name == "nt" else "chaya-navmesh"
    for c in (explicit, os.environ.get("CHAYA_NAVMESH_BIN"), shutil.which("chaya-navmesh"),
              str(WORKER / "native" / "chaya-navmesh" / "build" / exe)):
        if c and Path(c).is_file():
            return str(Path(c).resolve())
    return None


def _median(values) -> float:
    v = [x for x in values if x is not None]
    return round(statistics.median(v), 5) if v else float("nan")


def summarize(run: Run, condition: str, trials: list[dict], src: str) -> None:
    ok = [t for t in trials if t["status"] == "STITCHED"]
    run.add(Metric("trials registered and stitched", f"{len(ok)}/{len(trials)}", "trials", "measured", condition, src,
                   note="; ".join(sorted({f"{t['status']}: {t.get('code')}" for t in trials if t["status"] != "STITCHED"})) or None))
    outcomes = Counter(p["outcome"] for t in trials for p in t.get("pairs") or [])
    run.add(Metric("pairwise outcomes, all trials", ", ".join(f"{k} {v}" for k, v in sorted(outcomes.items())), "pairs", "measured",
                   condition, src, note="3 pairs per trial"))
    refused_placed = [e for t in trials if t["status"] == "REGISTRATION_FAILED" for e in (t.get("placed_errors") or {}).values()]
    if refused_placed:
        run.add(Metric("rooms placed in refused trials, worst median error", round(max(e["median_error_m"] for e in refused_placed), 5),
                       "m", "measured", condition, src, note=f"{len(refused_placed)} placements; a refusal must not hide a wrong one"))
    reg = [e for t in trials if t["status"] != "REGISTRATION_FAILED" for e in t["registration"].values()]
    if reg:
        run.add(Metric("registration error, median over rooms and trials", _median(e["median_error_m"] for e in reg), "m", "measured",
                       condition, src, note="per room: median point distance from the true placement"),
                Metric("registration error, worst room p95", round(max(e["p95_error_m"] for e in reg), 5), "m", "measured", condition, src),
                Metric("rotation error, worst room", round(max(e["rotation_error_deg"] for e in reg), 4), "deg", "measured", condition, src))
    loops = [lp for t in trials if t["status"] != "REGISTRATION_FAILED" for lp in t["loops"]]
    edges = [e for t in trials if t["status"] != "REGISTRATION_FAILED" for e in t["edges"]]
    if loops:
        run.add(Metric("loop A-B-C cycle error before optimisation, median", _median(lp["cycle_translation_m"] for lp in loops), "m",
                       "measured", condition, src),
                Metric("loop A-B-C cycle rotation before optimisation, max", round(max(lp["cycle_rotation_deg"] for lp in loops), 4),
                       "deg", "measured", condition, src),
                Metric("loops consistent before optimisation", f"{sum(lp['consistent_before_optimisation'] for lp in loops)}/{len(loops)}",
                       "loops", "measured", condition, src))
    if edges:
        run.add(Metric("edge residual after optimisation, max", round(max(e.get("residual_translation_m", 0.0) for e in edges), 5), "m",
                       "measured", condition, src))
    if ok:
        overlaps = [o for t in ok for o in t["overlaps"]]
        seams = [s for t in ok for s in t["seams"]]
        run.add(Metric("ROOM_REGISTRATION time, median", _median(t["registration_seconds"] for t in ok), "s", "measured", condition,
                       src, note="stage timing_seconds; this CPU"),
                Metric("ROOM_STITCH time, median", _median(t["stitch_seconds"] for t in ok), "s", "measured", condition, src,
                       note=f"{_median(t['gaussians_in'] for t in ok):.0f} Gaussians in"),
                Metric("registration + stitch wall clock, median", _median(t["registration_wall_seconds"] + t["stitch_wall_seconds"]
                                                                           for t in ok), "s", "measured", condition, src,
                       note="both stage runs through the orchestrator, including I/O"),
                Metric("overlap misalignment after stitching, max", round(max(o["offset_m"] or 0.0 for o in overlaps), 5), "m",
                       "measured", condition, src, note="rigid offset ICP still finds in each pair's overlap"),
                Metric("seam step, median", _median(s["median_m"] for s in seams), "m", "measured", condition, src),
                Metric("seam step p90, max", round(max((s["p90_m"] or 0.0) for s in seams), 5), "m", "measured", condition, src),
                Metric("duplicate Gaussians left, max", round(max(t["duplicate_fraction"] for t in ok), 5), "fraction", "measured",
                       condition, src),
                Metric("Gaussians removed as duplicates, median", _median(1 - t["gaussians_out"] / t["gaussians_in"] for t in ok),
                       "fraction", "measured", condition, src),
                Metric("colour step in shared voxels, median", _median(o["colour_step"] for o in overlaps), "RGB [0,1]", "measured",
                       condition, src, note="both captures sample the same synthetic surface colour"))


FPFH_COMMAND = ("docker run --rm -v \"$PWD:/repo\" -w /repo chaya-bench-open3d sh -c \"pip install -q pytest && "
                "python benchmarks/b7_multiroom/run.py --fpfh-only\"")


def _fpfh(run: Run, details: dict, tmp: Path, trials: int) -> None:
    for allow in (False, True):
        cond = "3 rooms, loop, noise 3 mm, real FPFH (Open3D)" + (", unconfirmed rooms accepted" if allow else "")
        results = [stitch_trial(tmp / f"fpfh-{allow}-{s}", seed=s, noise_m=NOISE_M[0], matcher="fpfh", allow_unconfirmed=allow)
                   for s in range(trials)]
        summarize(run, cond, results, "ROOM_REGISTRATION + ROOM_STITCH")
        details["trials"][cond] = [{k: v for k, v in t.items() if k not in ("h", "work", "files")} for t in results]


def fpfh_only(trials: int) -> None:
    run = Run("b7_multiroom_fpfh", "Multi-room registration and stitching on a synthetic floor, real FPFH matches")
    run.inputs = {"scene": SCENE, "scene_sha256": sha256_file(WORKER / "tests" / "multiroom_scene.py"), "trials": trials}
    run.add(Metric("floor", "two rooms and a corridor, three 1.2 m doorways (one loop)", "", "assumption", "setup", SCENE,
                   note="stands in for independently captured rooms; NOT real-room data"),
            Metric("feature matcher", "FPFH (Open3D) mutual nearest neighbours, as in production", "", "configuration", "setup",
                   "chaya_worker.region_alignment.fpfh_correspondences"))
    details: dict = {"trials": {}}
    with tempfile.TemporaryDirectory(prefix="b7f-") as tmp:
        _fpfh(run, details, Path(tmp), trials)
    run.details = details
    print(f"wrote {run.write().relative_to(ROOT)}")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--trials", type=int, default=5)
    ap.add_argument("--navmesh", help="path to the chaya-navmesh binary (default: CHAYA_NAVMESH_BIN, PATH, the native build)")
    ap.add_argument("--fpfh-only", action="store_true", help="only the real-FPFH condition (needs Open3D), written as b7_multiroom_fpfh")
    args = ap.parse_args()
    if args.fpfh_only:
        return fpfh_only(args.trials)

    run = Run("b7_multiroom", "Multi-room registration and stitching on a synthetic floor")
    run.inputs = {"scene": SCENE, "scene_sha256": sha256_file(WORKER / "tests" / "multiroom_scene.py"), "trials": args.trials}
    c0 = "setup"
    run.add(Metric("floor", "two rooms and a corridor, three 1.2 m doorways (one loop), 0.15 m walls, asymmetric furniture", "",
                   "assumption", c0, SCENE, note="stands in for independently captured rooms; NOT real-room data"),
            Metric("room calibrations", "each room metric and gravity-aligned with its own origin, heading and reconstruction scale "
                   "(0.5-2 units/m)", "", "assumption", c0, SCENE),
            Metric("feature matcher", f"oracle_matches: true correspondences inside the real overlap, {OUTLIERS:.0%} replaced by random "
                   "wrong matches", "", "configuration", c0, "stand-in for FPFH"),
            Metric("capture noise levels", ", ".join(f"{n * 1000:.0f} mm" for n in NOISE_M), "", "configuration", c0, "this benchmark"),
            Metric("gates", "production defaults (chaya_worker.settings room_* and stitch_*)", "", "configuration", c0, "Settings"))
    details: dict = {"trials": {}, "rejection": {}, "navigation": {}}
    tool = _navmesh_tool(args.navmesh)
    with tempfile.TemporaryDirectory(prefix="b7-") as tmp:
        tmp = Path(tmp)
        for noise in NOISE_M:
            cond = f"3 rooms, loop, noise {noise * 1000:.0f} mm, oracle matches"
            trials = [stitch_trial(tmp / f"n{noise}-{s}", seed=s, noise_m=noise, matcher="oracle") for s in range(args.trials)]
            summarize(run, cond, trials, "ROOM_REGISTRATION + ROOM_STITCH")
            details["trials"][cond] = [{k: v for k, v in t.items() if k not in ("h", "work", "files")} for t in trials]

        if importlib.util.find_spec("open3d") is not None:
            _fpfh(run, details, tmp, args.trials)
        else:
            run.add(unavailable("registration error with real FPFH matches", "m", "Open3D is not installed in this interpreter; "
                                "see results/b7_multiroom_fpfh", FPFH_COMMAND, "3 rooms, loop, real FPFH"))

        for condition in ("consistently wrong matches", "scale inconsistency", "weak overlap"):
            results = [rejection_trial(tmp / f"rej-{condition[:5]}-{s}", seed=s, condition=condition) for s in range(args.trials)]
            refused = [r for r in results if r["status"] == "FAILED" and r["code"] == "REGISTRATION_REJECTED"]
            run.add(Metric("trials refused with REGISTRATION_REJECTED", f"{len(refused)}/{len(results)}", "trials", "measured", condition,
                           "ROOM_REGISTRATION", note="outcomes: " + ", ".join(sorted({o for r in results for o in r["outcomes"]})) or None))
            placed = [e for r in results for e in r["placed_errors"].values()]
            if placed:
                run.add(Metric("rooms still placed, worst median error", round(max(e["median_error_m"] for e in placed), 5), "m",
                               "measured", condition, "ROOM_REGISTRATION", note=f"{len(placed)} placements in refused trials"))
            details["rejection"][condition] = results

        cond = "3 rooms stitched, noise 3 mm, baked by Recast/Detour"
        if tool is None:
            run.add(unavailable("rooms connected by the stitched navmesh", "", "the chaya-navmesh binary was not found",
                                "cmake --build services/reconstruction/native/chaya-navmesh/build, then pass --navmesh", cond))
        else:
            stitched = stitch_trial(tmp / "nav", seed=0, noise_m=NOISE_M[0], matcher="oracle", density=400, floor_density=FLOOR_DENSITY)
            nav = navigation_trial(stitched, tool) if stitched["status"] == "STITCHED" else {"status": stitched["status"],
                                                                                             "code": stitched.get("code")}
            details["navigation"] = nav
            if nav["status"] != "SUCCEEDED":
                run.add(Metric("NAVIGATION_BAKING", f"{nav['status']}: {nav.get('code')}", "", "measured", cond, "NAVIGATION_BAKING",
                               note=nav.get("message")))
            else:
                routes = nav["routes"].values()
                run.add(Metric("every room reached (STANDARD)", nav["connected"], "", "measured", cond, "navmesh.room_connectivity"),
                        Metric("every room reached (STEP_FREE)", nav["step_free_connected"], "", "measured", cond,
                               "navmesh.room_connectivity"),
                        Metric("room pairs with a direct navmesh connection", len(nav["connections"]), "pairs", "measured", cond,
                               "navmesh.room_connectivity", note="the floor has 3 doorways"),
                        Metric("doorway clear width, min", round(min(w["clear_width_m"] for w in nav["clear_widths"]), 3), "m",
                               "measured", cond, "navmesh.seed_clearance", note="1.2 m openings, measured on the 0.1 m grid"),
                        Metric("cross-room Detour routes found", f"{sum(r['found'] for r in routes)}/{len(nav['routes'])}", "routes",
                               "measured", cond, "chaya-navmesh path"),
                        Metric("NAVIGATION_BAKING wall clock", round(nav["wall_seconds"], 2), "s", "measured", cond, "orchestrator"))

    run.add(unavailable("registration and seam quality on real rooms", "", "no independently captured real rooms have been "
                        "reconstructed: SPLAT_RECONSTRUCTION needs a CUDA GPU (docs/REAL_VENUE_ACCEPTANCE.md)",
                        "capture two adjoining rooms, reconstruct and calibrate each, then POST "
                        "/api/v1/venues/{venueId}/floors/{floorId}/stitches", "real rooms"),
            unavailable("rendered seam quality (PSNR/SSIM across a seam)", "dB", "needs trained splats rendered from held-out views "
                        "(CUDA GPU)", "after a real stitch: render held-out views across each doorway from the stitched and the "
                        "per-room splats", "real rooms"))
    run.details = details
    path = run.write()
    print(f"wrote {path.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
