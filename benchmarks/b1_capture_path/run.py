"""Benchmark 1: capture path (docs/BENCHMARKS.md).

    A  reconnaissance lap only
    B  recon lap + Chaya 02 planned path
    and, so that "planning" is not confused with "walking further": the same number of waypoints placed by two naive
    strategies (boundary loop, lawnmower sweep), and a lawnmower cut off at the planner's own route length.

What this runner can measure today is the planner's PREDICTED coverage on hand-drawn SYNTHETIC scenes
(dev.chaya.api.planning.PlannerBenchmark, the planner's own visibility/triangulation model, one metric for every
strategy). Real reconstruction coverage needs paired real captures reconstructed on a GPU host; it is reported as
unavailable with the command that measures it (reconstruction_coverage.py).

    python benchmarks/b1_capture_path/run.py              # Maven on PATH, or Docker (maven:3.9-eclipse-temurin-21)
"""

from __future__ import annotations

import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from benchmarks.common.results import ROOT, Metric, Run, unavailable  # noqa: E402

API = ROOT / "services" / "api"
MAIN = "dev.chaya.api.planning.PlannerBenchmark"


def run_java(out_json: Path) -> str:
    """Runs PlannerBenchmark and returns its console output. Uses local Maven if present, else the Maven image."""
    if shutil.which("mvn"):
        cmd = ["mvn", "-B", "-q", "-f", str(API / "pom.xml"), "test-compile", "org.codehaus.mojo:exec-maven-plugin:3.5.0:java",
               "-Dexec.classpathScope=test", f"-Dexec.mainClass={MAIN}", f"-Dexec.args=--json {out_json}"]
        return subprocess.run(cmd, capture_output=True, text=True, check=True).stdout

    def win(p: Path) -> str:  # Docker Desktop on Windows wants native paths for bind mounts
        if sys.platform != "win32":
            return str(p)
        return subprocess.run(["cygpath", "-w", str(p)], capture_output=True, text=True).stdout.strip() or str(p)
    script = ("cp -r /src/. /work/ && rm -rf target && mvn -B -ntp -q test-compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java "
              f"-Dexec.classpathScope=test -Dexec.mainClass={MAIN} '-Dexec.args=--json /out/{out_json.name}'")
    cmd = ["docker", "run", "--rm", "-v", f"{win(API)}:/src:ro", "-v", f"{win(out_json.parent)}:/out", "-v", "chaya-m2:/root/.m2",
           "-w", "/work", "maven:3.9-eclipse-temurin-21", "sh", "-c", script]
    return subprocess.run(cmd, capture_output=True, text=True, check=True, env={"MSYS_NO_PATHCONV": "1", **__import__("os").environ}).stdout


def main() -> None:
    run = Run("b1_capture_path", "Capture path: recon lap vs recon lap + planned path (synthetic scenes, predicted coverage)")
    with tempfile.TemporaryDirectory(dir=ROOT / "benchmarks") as tmp:
        out = Path(tmp) / "planner.json"
        console = run_java(out)
        doc = json.loads(out.read_text(encoding="utf-8"))
    run.environment = {"java": doc["java"], "cores_visible_to_jvm": doc["cores"]}
    run.inputs = {"scenes": sorted({r["fixture"] for r in doc["rows"]}),
                  "scene_source": "services/api/src/test/java/dev/chaya/api/planning/PlannerFixtures.java + PlannerBenchmark.office()"}
    cfg = doc["config"]
    run.add(Metric("walking speed", cfg["walk_speed_mps"], "m/s", "configuration", "all", "PlannerConfig.defaults()"),
            Metric("dwell per waypoint", cfg["dwell_s"], "s", "configuration", "all", "PlannerConfig.defaults()"),
            Metric("target capture distance", cfg["target_distance_m"], "m", "configuration", "all", "PlannerConfig.defaults()"),
            Metric("redundant-waypoint threshold (marginal covered area)", cfg["redundant_gain_m2"], "m2", "configuration", "all",
                   "PlannerBenchmark.REDUNDANT_GAIN_M2"),
            Metric("scenes", "7 hand-drawn synthetic floor plans with scripted recon laps", "", "assumption", "all", "PlannerFixtures",
                   note="stand in for real venues; say nothing about real captures"),
            Metric("coverage model", "planner visibility + triangulation model (docs/route-planning.md section 3)", "", "assumption", "all",
                   "CapturePathPlanner", note="the planner optimises this same model, so B > A on it is expected by construction; "
                   "the fair test is B against the naive strategies at equal effort"))

    for r in doc["rows"]:
        cond = f"{r['fixture']} | {r['strategy']}"
        src = "PlannerBenchmark (synthetic scene, planner coverage model)"
        run.add(Metric("predicted coverage", round(r["coverage_pct"], 2), "%", "measured", cond, src),
                Metric("predicted uncovered area", round(r["uncovered_m2"], 2), "m2", "measured", cond, src),
                Metric("route length (secondary route)", round(r["route_m"], 2), "m", "measured", cond, src),
                Metric("waypoints", r["waypoints"], "count", "measured", cond, src),
                Metric("estimated capture duration (lap + route)", round(r["total_capture_s"], 1), "s", "estimate", cond, src,
                       note=f"lap {r['lap_s']:.1f} s from the scripted trajectory + route length / {cfg['walk_speed_mps']} m/s + "
                            f"waypoints x {cfg['dwell_s']} s"),
                Metric("redundant capture (observation beyond coverage need)", round(r["redundant_capture_pct"], 2), "%", "measured", cond, src))
        if r["redundant_waypoints"] >= 0:
            run.add(Metric("redundant viewpoints (marginal gain < threshold)", r["redundant_waypoints"], "count", "measured", cond, src))
        if r["planner_ms"] is not None:
            run.add(Metric("planning time (median of 7)", round(r["planner_ms"], 1), "ms", "measured", cond, src))
    run.details["rows"] = doc["rows"]
    run.details["console"] = console

    why = ("needs paired real captures of the same venue (A: recon lap only, B: recon lap + planned route) by the same "
           "operator and device, each reconstructed on a GPU host, plus a surveyed floor plan in the reconstruction's frame; "
           "no such dataset exists yet")
    coverage_cmd = ("python benchmarks/b1_capture_path/reconstruction_coverage.py --floor-plan <venue>/floor.json "
                    "--splat <venue>/A/splat-clean.ply --splat <venue>/B/splat-clean.ply --labels A B --out <venue>/coverage.json")
    duration_cmd = ("SELECT id, duration_seconds FROM capture_session WHERE id IN ('<capture A>', '<capture B>')   "
                    "-- recorded by POST .../captures/{id}/complete-upload")
    redundant_cmd = ("read dropped.near_duplicate from each run's FRAME_QUALITY_REPORT artifact "
                     "(quality-report.json, written by FRAME_QUALITY_FILTER) for captures A and B")
    for metric, unit, cmd in (("reconstruction coverage", "%", coverage_cmd), ("uncovered area (reconstructed)", "m2", coverage_cmd),
                              ("capture duration (recorded, real capture)", "s", duration_cmd),
                              ("redundant viewpoints (near-duplicate frames dropped)", "count", redundant_cmd)):
        run.add(unavailable(metric, unit, why, cmd, condition="real venue, A vs B"))
    print(console)
    print(f"wrote {run.write()}")


if __name__ == "__main__":
    main()
