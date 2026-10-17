#!/usr/bin/env bash
# Prints one classification's surefire totals (docs/ACCEPTANCE.md): passed, failed, errors, skipped, from TEST-*.xml.
#   scripts/ci/surefire-summary.sh <reports dir> <label>
# Exit 1 if nothing ran (an empty classification is not a pass).
set -euo pipefail
dir=${1:?usage: surefire-summary.sh <reports dir> <label>}
label=${2:?label}
shopt -s nullglob
reports=("$dir"/TEST-*.xml)
if [ ${#reports[@]} -eq 0 ]; then
  echo "**$label:** nothing ran (no reports in $dir)"
  exit 1
fi
# The <testsuite> element's counts, summed over every report.
read -r tests failures errors skipped < <(awk '
  match($0, /<testsuite [^>]*/) {
    e = substr($0, RSTART, RLENGTH)
    n = split("tests failures errors skipped", k, " ")
    for (i = 1; i <= n; i++) if (match(e, " " k[i] "=\"[0-9]+\"")) { v = substr(e, RSTART, RLENGTH); gsub(/[^0-9]/, "", v); s[i] += v }
  }
  END { print s[1] + 0, s[2] + 0, s[3] + 0, s[4] + 0 }' "${reports[@]}")
echo "**$label:** $((tests - failures - errors - skipped)) passed, $failures failed, $errors errors, $skipped skipped (of $tests)"
[ "$tests" -gt 0 ]
