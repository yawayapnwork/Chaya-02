#!/usr/bin/env bash
# Compares capture-path route strategies on the synthetic planner fixtures with one coverage metric.
# Needs JDK 21 and Maven on PATH. Usage: scripts/benchmark-planner.sh [--csv out.csv]
#   scripts/benchmark-planner.sh --bounds [--json out.json]   bounds estimation + planning on estimated bounds
set -euo pipefail
cd "$(dirname "$0")/../services/api"
MAIN=dev.chaya.api.planning.PlannerBenchmark
if [ "${1:-}" = "--bounds" ]; then MAIN=dev.chaya.api.planning.BoundsPlanningBenchmark; shift; fi
ARGS=""
if [ "$#" -gt 0 ]; then ARGS="-Dexec.args=\"$*\""; fi
eval mvn -B -q test-compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
  -Dexec.classpathScope=test \
  -Dexec.mainClass=$MAIN $ARGS
