#!/usr/bin/env bash
# Deploy gate: refuses to deploy a commit unless no CI workflow failed for it (.github/workflows/deploy.yml).
#
#   scripts/ci/require-green.sh <full commit sha>
#   env: GH_TOKEN (actions:read), GITHUB_REPOSITORY, optional GATE_TIMEOUT (seconds, default 2700)
#
# The docker workflow (which triggers staging deploys) does not depend on the test workflows, so without this an image
# whose tests failed, or are still running, would be deployed. For each gated workflow, the latest run for this exact
# commit must have concluded "success" (or "skipped"). A workflow still running is waited for. A workflow with NO run
# for this commit is reported and allowed: the test workflows are path-filtered, so a commit that touched only the web
# app has no backend run -- that is not a failure (DEPLOYMENT.md "Deploy gate").
set -euo pipefail
SHA=${1:?usage: require-green.sh <commit sha>}
: "${GITHUB_REPOSITORY:?}" "${GH_TOKEN:?}"
GATED=${GATED_WORKFLOWS:-"frontend backend python security reconstruction-smoke docker"}
deadline=$((SECONDS + ${GATE_TIMEOUT:-2700}))

while :; do
  # name<TAB>status<TAB>conclusion<TAB>url of the newest run of each workflow for this commit
  latest=$(gh api --paginate "repos/$GITHUB_REPOSITORY/actions/runs?head_sha=$SHA&per_page=100" \
    --jq '.workflow_runs[] | [.name, .run_number, .status, (.conclusion // ""), .html_url] | @tsv' \
    | sort -t$'\t' -k1,1 -k2,2nr | awk -F'\t' '!seen[$1]++ { print $1 "\t" $3 "\t" $4 "\t" $5 }')
  pending=(); failed=(); passed=(); absent=()
  for wf in $GATED; do
    row=$(printf '%s\n' "$latest" | awk -F'\t' -v w="$wf" '$1 == w' || true)
    if [ -z "$row" ]; then absent+=("$wf"); continue; fi
    IFS=$'\t' read -r _ status conclusion url <<< "$row"
    if [ "$status" != completed ]; then pending+=("$wf ($status)")
    elif [ "$conclusion" = success ] || [ "$conclusion" = skipped ]; then passed+=("$wf")
    else failed+=("$wf: $conclusion $url")
    fi
  done
  if [ ${#failed[@]} -gt 0 ]; then
    printf '::error::CI did not pass for %s: %s\n' "$SHA" "${failed[@]}"
    exit 1
  fi
  if [ ${#pending[@]} -eq 0 ]; then
    echo "passed: ${passed[*]:-none}"
    [ ${#absent[@]} -eq 0 ] || echo "no run for this commit (path-filtered, not a failure): ${absent[*]}"
    exit 0
  fi
  if [ $SECONDS -ge $deadline ]; then
    printf '::error::still running after %ss: %s\n' "${GATE_TIMEOUT:-2700}" "${pending[*]}"
    exit 1
  fi
  echo "waiting for: ${pending[*]}"
  sleep 30
done
