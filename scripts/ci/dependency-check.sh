#!/usr/bin/env bash
# OWASP Dependency-Check for services/api, with the vulnerability database handled as its own step so that an NVD
# outage is never mistaken for a finding, and never for a clean scan (.github/workflows/security.yml, docs/security-hardening.md).
#
#   scripts/ci/dependency-check.sh            update the database, then scan (CI and scripts/security-scan.sh)
#   scripts/ci/dependency-check.sh update     database only
#   scripts/ci/dependency-check.sh check      scan with the database as it is (no network update)
#
# Environment (all optional):
#   NVD_API_KEY             NVD API key. Handed to the plugin by environment-variable NAME (nvdApiKeyEnvironmentVariable),
#                           so it is never on a command line or in a log. Without it the NVD allows far fewer requests:
#                           a database built from nothing then takes very long and is often rate limited (HTTP 429).
#   DC_DATA_DIR             database directory, kept between runs (CI caches it)   default ~/.cache/dependency-check-data
#   DC_FAIL_ON_CVSS         fail at or above this CVSS score                         default 7
#   DC_UPDATE_ATTEMPTS      attempts for the database update                         default 3
#   DC_RETRY_DELAY_SECONDS  wait before the 2nd attempt; doubles each attempt        default 60
#   DC_MVN                  Maven command                                            default mvn
#
# Exit status (and `result=` in $GITHUB_OUTPUT):
#   0 clean           scanned against an up-to-date database; nothing at or above the threshold
#   1 findings        vulnerabilities at or above the threshold (also when the database update failed but the cached
#                     database still shows them: a real finding is never hidden behind an infrastructure error)
#   3 db-unavailable  the database could not be updated (or does not exist): NOT a clean scan
#   4 error           the analysis itself failed (no trustworthy report)
set -uo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
API="$ROOT/services/api"
VERSION=12.2.2 # >= 12.2.2: earlier releases crash building the NVD database ("Value too long for column URL", upstream #8467)
PLUGIN="org.owasp:dependency-check-maven:$VERSION"
DATA_DIR=${DC_DATA_DIR:-$HOME/.cache/dependency-check-data}
THRESHOLD=${DC_FAIL_ON_CVSS:-7}
ATTEMPTS=${DC_UPDATE_ATTEMPTS:-3}
DELAY=${DC_RETRY_DELAY_SECONDS:-60}
read -r -a MVN <<< "${DC_MVN:-mvn}" # may carry options, e.g. "mvn -o"
LOGS="$API/target/dependency-check-logs"
MARKER="$DATA_DIR/chaya-last-successful-update"
mode=${1:-all}

mkdir -p "$DATA_DIR" "$LOGS"
out() { [ -n "${GITHUB_OUTPUT:-}" ] && echo "$1" >> "$GITHUB_OUTPUT"; return 0; }
note() { # level title message: a GitHub annotation in CI, a plain line elsewhere
  if [ -n "${GITHUB_ACTIONS:-}" ]; then echo "::$1 title=$2::$3"; else echo "$1: $2: $3"; fi
}

common=(-B -ntp -f "$API/pom.xml" "-DdataDirectory=$DATA_DIR")
key_args=()
if [ -n "${NVD_API_KEY:-}" ]; then
  # The NAME of the variable, not its value. An empty key must not be passed at all: the NVD answers an empty
  # apiKey header with 404 "Invalid apiKey".
  key_args=(-DnvdApiKeyEnvironmentVariable=NVD_API_KEY)
fi

# Every Maven line goes through this before it reaches a log, the console or an artifact. The NVD client masks a rejected
# key only partly ("Invalid API Key: c0ffe-*****-89abc"), and GitHub masks only the whole secret, so the fragments are
# removed here, as is the full value should anything ever print it. The key is read from the environment inside awk,
# never passed as an argument (it would be visible in the process list).
redact() {
  awk '{ k = ENVIRON["NVD_API_KEY"]
         if (k != "") while ((i = index($0, k)) > 0) $0 = substr($0, 1, i - 1) "[redacted]" substr($0, i + length(k))
         print }' | sed -E 's/[0-9A-Za-z]{5}-\*{3,}-[0-9A-Za-z]{5}/[redacted]/g'
}

mvn_logged() { # log-file maven-args...: runs Maven, redacted output into the log; returns Maven's status
  local log=$1; shift
  "${MVN[@]}" "$@" 2>&1 | redact > "$log"
  return "${PIPESTATUS[0]}"
}

# Prints why an update attempt failed: auth | rate-limit | unavailable | network | other
classify_update_failure() {
  local log=$1
  if grep -q -i -E "Status Code: (401|403|404)|Invalid API ?Key" "$log"; then echo auth
  elif grep -q "Status Code: 429" "$log"; then echo rate-limit
  elif grep -q -E "Status Code: 5[0-9][0-9]" "$log"; then echo unavailable
  elif grep -q -i -E "UnknownHost|Connection (refused|reset)|timed out|SocketTimeout|ConnectException|No route to host|Network is unreachable|SSLHandshake" "$log"; then echo network
  else echo other
  fi
}

update() {
  if [ ${#key_args[@]} -eq 0 ]; then
    note warning "NVD API key not configured" "NVD_API_KEY is empty: the update runs unauthenticated (much lower NVD rate limit). Add the NVD_API_KEY repository secret."
  fi
  local attempt=1 wait=$DELAY log reason
  while :; do
    log="$LOGS/update-attempt-$attempt.log"
    echo "== vulnerability database update, attempt $attempt of $ATTEMPTS ($DATA_DIR)"
    if mvn_logged "$log" "${common[@]}" "${key_args[@]}" "$PLUGIN:update-only"; then
      if grep -q "Skipping the NVD API Update" "$log" && [ -f "$MARKER" ]; then
        # The plugin's own validity window (nvdValidForHours, 4 h): the data was fetched recently; the marker keeps
        # the time it actually was.
        echo "   current (last updated $(cat "$MARKER"), within the plugin's validity window; no NVD request)"
      else
        date -u +%Y-%m-%dT%H:%M:%SZ > "$MARKER"
        echo "   updated ($(grep -o -E "NVD API has [0-9,]+ records in this update" "$log" | tail -1 || true))"
      fi
      out "updated=true"
      return 0
    fi
    reason=$(classify_update_failure "$log")
    echo "   failed: $reason (log: ${log#"$ROOT/"})"
    grep -E "^\[(ERROR|WARNING)\].*(NVD|Status Code|Unable|Error updating)" "$log" | grep -v "Help 1" | tail -5 | sed 's/^/     /'
    if [ "$reason" = auth ]; then
      note error "NVD rejected the API key" "the NVD API refused the configured key (HTTP 401/403/404 'Invalid apiKey'); retrying cannot help. Check the NVD_API_KEY secret."
      out "update_failure=auth"
      return 1
    fi
    if [ "$attempt" -ge "$ATTEMPTS" ]; then
      out "update_failure=$reason"
      return 1
    fi
    echo "   retrying in ${wait}s"
    sleep "$wait"
    attempt=$((attempt + 1)); wait=$((wait * 2))
  done
}

# 0 clean, 1 findings, 3 no database, 4 error
check() {
  local log="$LOGS/check.log" status
  rm -f "$API"/target/dependency-check-report.*
  echo "== scan (failBuildOnCVSS=$THRESHOLD, database as of $(cat "$MARKER" 2>/dev/null || echo 'unknown'))"
  mvn_logged "$log" "${common[@]}" "$PLUGIN:check" -DautoUpdate=false "-DfailBuildOnCVSS=$THRESHOLD" -Dformats=HTML,JSON,SARIF
  status=$?
  if [ $status -eq 0 ] && [ -f "$API/target/dependency-check-report.json" ]; then
    return 0
  fi
  if grep -q "One or more dependencies were identified with vulnerabilities" "$log" && [ -f "$API/target/dependency-check-report.json" ]; then
    return 1
  fi
  if grep -q -E "NoDataException|No documents exist" "$log"; then
    return 3
  fi
  grep -E "^\[ERROR\]" "$log" | grep -v -E "Help 1|re-run|^\[ERROR\] *$|full stack|more information" | head -8 | sed 's/^/   /'
  return 4
}

summary() { # status db-state
  local py
  py=$(command -v python3 || command -v python) || return 0
  [ -f "$API/target/dependency-check-report.json" ] || return 0
  "$py" "$ROOT/scripts/ci/dependency-check-summary.py" "$API/target/dependency-check-report.json" \
    --threshold "$THRESHOLD" --database "$2" --database-date "$(cat "$MARKER" 2>/dev/null || echo unknown)" \
    | tee "$API/target/dependency-check-summary.md"
  [ -n "${GITHUB_STEP_SUMMARY:-}" ] && cat "$API/target/dependency-check-summary.md" >> "$GITHUB_STEP_SUMMARY"
  return 0
}

finish() { # code
  case $1 in
    0) out "result=clean"; echo "RESULT: clean (nothing at or above CVSS $THRESHOLD)" ;;
    1) out "result=findings"; note error "Vulnerabilities found" "dependencies with CVSS >= $THRESHOLD: see the dependency-check report artifact" ;;
    3) out "result=db-unavailable"; note error "Vulnerability database unavailable" "the NVD data could not be updated; this is an infrastructure failure, NOT a clean scan (logs: services/api/target/dependency-check-logs)" ;;
    *) out "result=error"; note error "Dependency-Check analysis failed" "the scan did not complete; no trustworthy report (log: services/api/target/dependency-check-logs/check.log)" ;;
  esac
  exit "$1"
}

case $mode in
  update)
    update || finish 3
    exit 0 ;;
  check)
    check; rc=$?
    summary "$rc" "as cached"
    finish $rc ;;
  all)
    if update; then
      check; rc=$?
      summary "$rc" "current"
      finish $rc
    fi
    # The update failed. A cached database can still show real findings, so scan with it, but the job fails either
    # way: without current data, "no findings" proves nothing.
    if [ -f "$MARKER" ]; then
      note warning "Scanning with the cached database" "last successful update $(cat "$MARKER"); the result cannot be called clean"
      check; rc=$?
      summary "$rc" "STALE (update failed; cached $(cat "$MARKER"))"
      [ $rc -eq 1 ] && finish 1
    fi
    finish 3 ;;
  *)
    echo "usage: $0 [update|check|all]" >&2; exit 2 ;;
esac
