#!/usr/bin/env bash
# Local equivalent of .github/workflows/security.yml. Runs every scanner whose tooling is present and says plainly
# which ones were skipped -- a skipped scan is not a passed scan. Reports go to reports/security/ (git-ignored).
#
#   scripts/security-scan.sh
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT="$ROOT/reports/security"
mkdir -p "$OUT"
status=0
skipped=()

run() { # name command...
  local name=$1; shift
  echo "== $name"
  if "$@" > "$OUT/$name.txt" 2>&1; then echo "   ok"; else echo "   FINDINGS (see reports/security/$name.txt)"; status=1; fi
}

if command -v npm > /dev/null && [ -d "$ROOT/apps/web/node_modules" ]; then
  run npm-audit bash -c "cd '$ROOT/apps/web' && npm audit --omit=dev --audit-level=high"
  run npm-licenses node "$ROOT/scripts/licenses/npm-licenses.mjs" --prod
else
  skipped+=("npm (run npm ci in apps/web)")
fi

for svc in reconstruction vision; do
  py="$ROOT/services/$svc/.venv/bin/python"
  [ -x "$py" ] || py="$ROOT/services/$svc/.venv/Scripts/python.exe"
  if [ -x "$py" ]; then
    if "$py" -m pip_audit --version > /dev/null 2>&1; then
      run "pip-audit-$svc" "$py" -m pip_audit --progress-spinner off
    elif command -v pip-audit > /dev/null || python -m pip_audit --version > /dev/null 2>&1; then
      req="$OUT/$svc-freeze.txt"
      "$py" -m pip freeze --exclude-editable | grep -v '^chaya' > "$req"
      run "pip-audit-$svc" python -m pip_audit -r "$req" --no-deps --disable-pip --progress-spinner off
    else
      skipped+=("pip-audit for $svc (pip install pip-audit)")
    fi
    run "python-licenses-$svc" "$py" "$ROOT/scripts/licenses/python-licenses.py" --exclude chaya-worker chaya-vision pip-audit
  else
    skipped+=("python $svc (no .venv in services/$svc)")
  fi
done

if command -v mvn > /dev/null; then
  run maven-dependency-check bash -c "cd '$ROOT/services/api' && mvn -B -q -ntp org.owasp:dependency-check-maven:12.1.0:check -DfailBuildOnCVSS=7"
  run maven-licenses bash -c "cd '$ROOT/services/api' && mvn -B -q -ntp org.codehaus.mojo:license-maven-plugin:2.4.0:add-third-party -Dlicense.failOnMissing=true -Dlicense.includedScopes=compile,runtime && python '$ROOT/scripts/licenses/maven-licenses.py' target/generated-sources/license/THIRD-PARTY.txt"
else
  skipped+=("maven (no mvn on PATH; the CI workflow runs it)")
fi

if [ ${#skipped[@]} -gt 0 ]; then
  echo "== NOT SCANNED:"
  printf '   - %s\n' "${skipped[@]}"
  status=1
fi
exit $status
