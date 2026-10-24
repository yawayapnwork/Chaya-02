#!/usr/bin/env bash
# Tests for the deploy gate (scripts/ci/require-green.sh) against recorded run lists: a stub `gh` prints the TSV rows the
# real `gh api ... --jq` would. No network, no token. Run: bash scripts/ci/test-require-green.sh
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/bin"
# The stub serves the run list in $RUNS_FILE; with $RUNS_FILE_LATER it switches to that list from the second call on
# (a workflow that starts, or finishes, while the gate is waiting).
cat > "$work/bin/gh" <<'STUB'
#!/usr/bin/env bash
n=$(( $(cat "$CALLS" 2>/dev/null || echo 0) + 1 )); echo "$n" > "$CALLS"
if [ -n "${RUNS_FILE_LATER:-}" ] && [ "$n" -gt 1 ]; then cat "$RUNS_FILE_LATER"; else cat "$RUNS_FILE"; fi
STUB
chmod +x "$work/bin/gh"

pass=0; fail=0
GATED="frontend backend python docker"
row() { printf '%s\t%s\t%s\t%s\t%s\thttps://example.test/%s\n' "$1" "${2:-1}" "${3:-1}" "${4:-completed}" "${5:-success}" "$1"; }

# expect <name> <exit status> <output pattern> [env assignments...] -- with RUNS_FILE (and RUNS_FILE_LATER) prepared
expect() {
  local name=$1 want=$2 pattern=$3; shift 3
  rm -f "$work/calls"
  set +e
  out=$(env PATH="$work/bin:$PATH" CALLS="$work/calls" GH_TOKEN=x GITHUB_REPOSITORY=o/r GATED_WORKFLOWS="$GATED" \
    POLL_SECONDS=0 ABSENT_GRACE=0 GATE_TIMEOUT=0 "$@" bash "$here/require-green.sh" abc123 2>&1)
  got=$?
  set -e
  if [ "$got" -eq "$want" ] && printf '%s' "$out" | grep -q -- "$pattern"; then
    pass=$((pass + 1)); echo "ok   $name"
  else
    fail=$((fail + 1)); echo "FAIL $name (exit $got, want $want; pattern '$pattern')"; printf '%s\n' "$out" | sed 's/^/     /'
  fi
}

{ row frontend; row backend; row python; row docker; } > "$work/green"
expect "every required workflow succeeded" 0 "passed: frontend backend python docker" RUNS_FILE="$work/green"

{ row frontend; row backend; row docker; } > "$work/absent"
expect "a required workflow with no run fails (it is not 'path-filtered')" 1 "no run for abc123 of required workflow(s): python" \
  RUNS_FILE="$work/absent"

expect "a workflow that appears within the grace period is waited for" 0 "passed:" \
  RUNS_FILE="$work/absent" RUNS_FILE_LATER="$work/green" ABSENT_GRACE=60 GATE_TIMEOUT=60

{ row frontend; row backend; row python 1 1 completed failure; row docker; } > "$work/red"
expect "a failed workflow fails and is named" 1 "python: failure" RUNS_FILE="$work/red"

{ row frontend; row backend; row python 1 1 completed skipped; row docker; } > "$work/skipped"
expect "a skipped workflow is not a pass" 1 "python: skipped" RUNS_FILE="$work/skipped"

{ row frontend; row backend; row python 1 1 completed cancelled; row docker; } > "$work/cancelled"
expect "a cancelled workflow (superseded by a newer push) fails" 1 "python: cancelled" RUNS_FILE="$work/cancelled"

{ row frontend; row backend; row python 2 1 completed success; row python 1 1 completed failure; row docker; } > "$work/rerun"
expect "the newest run of a workflow decides (a later run after a failure)" 0 "passed:" RUNS_FILE="$work/rerun"

{ row frontend; row backend; row python 1 2 completed failure; row python 1 1 completed success; row docker; } > "$work/attempt"
expect "the newest attempt of a run decides (a failed re-run is not hidden by the first attempt)" 1 "python: failure" \
  RUNS_FILE="$work/attempt"

{ row frontend; row backend; row python 1 1 in_progress ""; row docker; } > "$work/running"
expect "a running workflow is waited for, then passes" 0 "passed:" \
  RUNS_FILE="$work/running" RUNS_FILE_LATER="$work/green" GATE_TIMEOUT=60
expect "a workflow still running at the timeout fails" 1 "still running after" RUNS_FILE="$work/running"

echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
