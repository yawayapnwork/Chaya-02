#!/usr/bin/env bash
# Compares capture-path route strategies on the synthetic planner fixtures with one coverage metric.
# Needs JDK 21 and Maven on PATH. Usage: scripts/benchmark-planner.sh [--csv out.csv]
set -euo pipefail
cd "$(dirname "$0")/../services/api"
ARGS=""
if [ "$#" -gt 0 ]; then ARGS="-Dexec.args=\"$*\""; fi
eval mvn -B -q test-compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
  -Dexec.classpathScope=test \
  -Dexec.mainClass=dev.chaya.api.planning.PlannerBenchmark $ARGS
