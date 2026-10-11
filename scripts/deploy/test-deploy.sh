#!/usr/bin/env bash
# Tests infra/deploy/deploy.sh's control flow without a host: docker, curl and the helper scripts are stubs that record
# what deploy.sh asked for and answer as a healthy or failing version would. Checks: a good deploy is recorded; a bad
# configuration or a failed migration rehearsal stops before anything changes; an unhealthy version is rolled back
# automatically (and a failed rollback is reported); a schema change during a failed deploy names the restore command;
# no secret from .env ever reaches the output.
#
#   scripts/deploy/test-deploy.sh        (exit 0 when every case passes)
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
SECRET="s3cr3t-$(date +%s)-never-print-me"
passed=0
failed=0

new_host() { # creates a fake deploy directory with stubs; prints its path
  local d
  d=$(mktemp -d)
  cp "$ROOT/infra/deploy/deploy.sh" "$d/"
  mkdir -p "$d/bin" "$d/scripts/backup" "$d/backups/postgres"
  cat > "$d/.env" <<EOF
POSTGRES_USER=postgres
POSTGRES_DB=chaya
POSTGRES_PASSWORD=$SECRET
CHAYA_API_DOMAIN=api.example.invalid
CHAYA_APP_DOMAIN=app.example.invalid
EOF
  echo v1 > "$d/.deployed-version"
  echo 33 > "$d/schema"
  # docker: answers from the environment deploy.sh exports (CHAYA_VERSION) and the STUB_* settings of the case.
  cat > "$d/bin/docker" <<'EOF'
#!/usr/bin/env bash
d=$STUB_DIR
echo "docker $* [CHAYA_VERSION=${CHAYA_VERSION:-}]" >> "$d/calls"
bad() { case " ${STUB_UNHEALTHY:-} " in *" $1 "*) return 0 ;; esac; return 1; }
if [ "$1" = compose ]; then
  shift; while [ $# -gt 0 ] && [ "${1#-}" != "$1" ]; do shift 2; done   # drop --env-file X -f Y
  case "$1" in
    config)
      if [ "${2:-}" = --images ]; then echo "ghcr.io/owner/chaya-api:$CHAYA_VERSION"; echo "ghcr.io/owner/chaya-web:$CHAYA_VERSION"; exit 0; fi
      if [ -n "${STUB_BAD_CONFIG:-}" ]; then echo 'error while interpolating services.api.environment.POSTGRES_APP_PASSWORD: required variable POSTGRES_APP_PASSWORD is missing a value' >&2; exit 1; fi
      exit 0 ;;
    pull) exit 0 ;;
    ps) if [ "$2" = -q ] && [ "${3:-}" = --status ]; then echo pg-running; else echo "cid-${!#}"; fi; exit 0 ;;
    exec) cat "$d/schema"; exit 0 ;;
    up) [ "${STUB_MIGRATE_ON:-}" = "$CHAYA_VERSION" ] && echo 34 > "$d/schema"; exit 0 ;;
    logs) echo "stub log line for ${!#}"; exit 0 ;;
  esac
  exit 0
fi
if [ "$1" = inspect ]; then
  if bad "$CHAYA_VERSION"; then echo unhealthy; else echo healthy; fi; exit 0
fi
exit 0
EOF
  cat > "$d/bin/curl" <<'EOF'
#!/usr/bin/env bash
out=""; while [ $# -gt 0 ]; do [ "$1" = -o ] && { out=$2; shift; }; shift; done
[ -n "$out" ] && [ "$out" != /dev/null ] && echo '{"status":"UP","database":"UP"}' > "$out"
echo 200
EOF
  printf '#!/usr/bin/env bash\necho "keycloak-configure" >> "$STUB_DIR/calls"\n' > "$d/keycloak-configure.sh"
  cat > "$d/scripts/backup/backup.sh" <<'EOF'
#!/usr/bin/env bash
echo "backup $*" >> "$STUB_DIR/calls"
touch "$STUB_DIR/backups/postgres/chaya-20261011T000000Z.dump"
EOF
  cat > "$d/scripts/backup/restore-drill.sh" <<'EOF'
#!/usr/bin/env bash
echo "drill $* image=$DRILL_API_IMAGE db_only=$DRILL_DB_ONLY" >> "$STUB_DIR/calls"
exit "${STUB_DRILL_RC:-0}"
EOF
  chmod +x "$d"/bin/* "$d"/*.sh "$d"/scripts/backup/*.sh
  echo "$d"
}

run() { # host version [VAR=value...] -> sets rc and out
  local d=$1 v=$2; shift 2
  set +e
  out=$(cd "$d" && env "$@" STUB_DIR="$d" PATH="$d/bin:$PATH" HEALTH_TIMEOUT=0 BACKUP_DIR="$d/backups" ./deploy.sh "$v" 2>&1)
  rc=$?
  set -e
}

check() { # description condition...
  local what=$1; shift
  if "$@"; then passed=$((passed + 1)); else failed=$((failed + 1)); echo "FAIL: $what"; echo "$out" | sed 's/^/    | /' | tail -25; fi
}
has() { grep -q -- "$2" "$1"; }
says() { echo "$out" | grep -q -- "$1"; }
no_secret() { ! echo "$out" | grep -q -- "$SECRET" && ! grep -q -- "$SECRET" "$d/calls" "$d/.deploy-history" 2>/dev/null; }

# 1. a healthy version is deployed, rehearsed first, and recorded
d=$(new_host); run "$d" v2
check "healthy deploy exits 0" [ "$rc" -eq 0 ]
check "healthy deploy records the version" [ "$(cat "$d/.deployed-version")" = v2 ]
check "rehearsal ran with the new api image, database only" has "$d/calls" "drill .*chaya-20261011T000000Z.dump image=ghcr.io/owner/chaya-api:v2 db_only=1"
check "history says ok" has "$d/.deploy-history" " v2 ok"
check "no secret in output (healthy)" no_secret

# 2. invalid configuration: stops before pulling or changing anything
d=$(new_host); run "$d" v2 STUB_BAD_CONFIG=1
check "bad config exits non-zero" [ "$rc" -ne 0 ]
check "bad config names the variable" says "POSTGRES_APP_PASSWORD is missing"
check "bad config says nothing changed" says "Nothing was changed"
check "bad config never pulls or starts" bash -c "! grep -qE 'compose .*(pull|up)' '$d/calls'"
check "bad config keeps the deployed version" [ "$(cat "$d/.deployed-version")" = v1 ]

# 3. the migration rehearsal fails: nothing live changes
d=$(new_host); run "$d" v2 STUB_DRILL_RC=1
check "failed rehearsal exits non-zero" [ "$rc" -ne 0 ]
check "failed rehearsal never starts containers" bash -c "! grep -qE 'compose .* up ' '$d/calls'"
check "failed rehearsal is recorded" has "$d/.deploy-history" " v2 rehearsal-failed"
check "failed rehearsal keeps the deployed version" [ "$(cat "$d/.deployed-version")" = v1 ]
check "failed rehearsal says nothing live changed" says "Nothing live was changed"

# 4. the new version never becomes healthy: automatic rollback to the previous one
d=$(new_host); run "$d" v2 STUB_UNHEALTHY=v2
check "unhealthy deploy exits non-zero" [ "$rc" -ne 0 ]
check "unhealthy deploy shows the failing service's log" says "stub log line for"
check "rollback started the previous version after the new one" bash -c "grep -E 'compose .* up ' '$d/calls' | sed 's/.*CHAYA_VERSION=//' | tr -d ']' | tr '\n' ' ' | grep -q 'v2 v1'"
check "history records failure and rollback" bash -c "grep -q ' v2 failed' '$d/.deploy-history' && grep -q ' v1 rollback-ok' '$d/.deploy-history'"
check "the failed version is not recorded as deployed" [ "$(cat "$d/.deployed-version")" = v1 ]
check "no secret in output (rollback)" no_secret

# 5. the failed version had migrated the schema: the restore command for the pre-deploy dump is named
d=$(new_host); run "$d" v2 STUB_UNHEALTHY=v2 STUB_MIGRATE_ON=v2
check "schema change is reported" says "schema moved from 33 to 34"
check "the restore command names the pre-deploy dump" says "restore.sh postgres .*chaya-20261011T000000Z.dump --yes-replace-live-data"

# 6. the rollback fails too: reported as needing manual recovery
d=$(new_host); run "$d" v2 STUB_UNHEALTHY="v1 v2"
check "double failure exits non-zero" [ "$rc" -ne 0 ]
check "double failure asks for manual recovery" says "ROLLBACK ALSO FAILED"
check "no secret in output (double failure)" no_secret

echo "deploy.sh tests: $passed passed, $failed failed"
[ "$failed" -eq 0 ]
