#!/usr/bin/env bash
# Deploy gate: refuses to deploy a commit unless every required CI workflow passed for it (.github/workflows/deploy.yml).
#
#   scripts/ci/require-green.sh <full commit sha>
#   env: GH_TOKEN (actions:read), GITHUB_REPOSITORY, optional GATE_TIMEOUT (seconds, default 2700),
#        ABSENT_GRACE (seconds a required workflow may take to appear, default 180), POLL_SECONDS (default 30),
#        GATED_WORKFLOWS (space-separated names; default below)
#
# The docker workflow (which triggers staging deploys) does not depend on the test workflows, so without this an image
# whose tests failed, or are still running, would be deployed. For each gated workflow, the latest run for this exact
# commit must have concluded "success". A workflow still queued or running is waited for.
#
# A workflow with NO run for this commit fails the gate (after ABSENT_GRACE, in case GitHub has not created it yet). Every
# gated workflow runs on every push to main, without path filters (DEPLOYMENT.md "Deploy gate"), so a missing run means a
# check that never ran -- before 2026-10-24 it was allowed as "path-filtered", which let a commit that touched only the web
# app deploy while python was red on main. "skipped" (a workflow-level skip) counts as not run, never as passed.
set -euo pipefail
SHA=${1:?usage: require-green.sh <commit sha>}
: "${GITHUB_REPOSITORY:?}" "${GH_TOKEN:?}"
GATED=${GATED_WORKFLOWS:-"frontend backend python security reconstruction-smoke acceptance docker ops"}
POLL=${POLL_SECONDS:-30}
start=$SECONDS
deadline=$((start + ${GATE_TIMEOUT:-2700}))
absent_deadline=$((start + ${ABSENT_GRACE:-180}))

while :; do
  # name<TAB>status<TAB>conclusion<TAB>url of the newest run of each workflow for this commit
  latest=$(gh api --paginate "repos/$GITHUB_REPOSITORY/actions/runs?head_sha=$SHA&per_page=100" \
    --jq '.workflow_runs[] | [.name, .run_number, .run_attempt, .status, (.conclusion // ""), .html_url] | @tsv' \
    | sort -t$'\t' -k1,1 -k2,2nr -k3,3nr | awk -F'\t' '!seen[$1]++ { print $1 "\t" $4 "\t" $5 "\t" $6 }')
  pending=(); failed=(); passed=(); absent=()
  for wf in $GATED; do
    row=$(printf '%s\n' "$latest" | awk -F'\t' -v w="$wf" '$1 == w' || true)
    if [ -z "$row" ]; then absent+=("$wf"); continue; fi
    IFS=$'\t' read -r _ status conclusion url <<< "$row"
    if [ "$status" != completed ]; then pending+=("$wf ($status)")
    elif [ "$conclusion" = success ]; then passed+=("$wf")
    else failed+=("$wf: $conclusion $url")
    fi
  done
  if [ ${#failed[@]} -gt 0 ]; then
    printf '::error::CI did not pass for %s: %s\n' "$SHA" "${failed[@]}"
    exit 1
  fi
  if [ ${#absent[@]} -gt 0 ] && [ $SECONDS -ge $absent_deadline ]; then
    printf '::error::no run for %s of required workflow(s): %s (every gated workflow must run on each push to main)\n' \
      "$SHA" "${absent[*]}"
    exit 1
  fi
  if [ ${#pending[@]} -eq 0 ] && [ ${#absent[@]} -eq 0 ]; then
    echo "passed: ${passed[*]}"
    exit 0
  fi
  if [ $SECONDS -ge $deadline ]; then
    printf '::error::still running after %ss: %s\n' "${GATE_TIMEOUT:-2700}" "${pending[*]} ${absent[*]}"
    exit 1
  fi
  echo "waiting for: ${pending[*]} ${absent[*]/%/ (not created yet)}"
  sleep "$POLL"
done
