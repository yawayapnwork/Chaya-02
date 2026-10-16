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
# Characters a shell would act on if the .env file were sourced: $, backticks, a space.
SECRET='s3cr3t-$HOME-`id`-'"$(date +%s)"'-never print-me'
passed=0
failed=0

new_host() { # creates a fake deploy directory with stubs; prints its path
  local d
  d=$(mktemp -d)
  cp "$ROOT/infra/deploy/deploy.sh" "$d/"
  mkdir -p "$d/bin" "$d/scripts/backup" "$d/backups/postgres"
  cp "$ROOT/scripts/backup/env-file.sh" "$d/scripts/backup/"
  cat > "$d/.env" <<EOF
POSTGRES_USER=postgres
POSTGRES_DB=chaya
POSTGRES_PASSWORD='$SECRET'
CHAYA_API_DOMAIN=api.example.invalid
# Valid for Compose; sourcing the file as shell ran the second range as a command and read <owner> as a redirect.
KEYCLOAK_ADMIN_ALLOW=203.0.113.0/24 198.51.100.0/24   # two ranges
CHAYA_REGISTRY=ghcr.io/<owner>
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
    ps) if [ "$2" = -q ] && [ "${3:-}" = --status ]; then
          { [ -z "${STUB_PG_STOPPED:-}" ] || [ -e "$d/pg-started" ]; } && echo pg-running
        else echo "cid-${!#}"; fi; exit 0 ;;
    exec) cat "$d/schema"; exit 0 ;;
    up) if [ "${!#}" = postgres ]; then [ -n "${STUB_PG_START_FAILS:-}" ] && exit 1; touch "$d/pg-started"; exit 0; fi
        [ "${STUB_MIGRATE_ON:-}" = "$CHAYA_VERSION" ] && echo 34 > "$d/schema"; sleep "${STUB_UP_SLEEP:-0}"; exit 0 ;;
    logs) echo "stub log line for ${!#}"; exit 0 ;;
  esac
  exit 0
fi
if [ "$1" = volume ] && [ "$2" = inspect ]; then
  [ -z "${STUB_NO_PG_VOLUME:-}" ]; exit $?
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
no_secret() { ! echo "$out" | grep -qF -- "$SECRET" && ! grep -qF -- "$SECRET" "$d/calls" "$d/.deploy-history" 2>/dev/null; }

# 0. the env file is parsed as Compose parses it, never executed
envf=$(mktemp)
cat > "$envf" <<'EOF'
# comment
export PLAIN=value
SPACED=10.0.0.0/8 192.168.0.0/16   # inline comment
HASH=a#b
QUOTED='$HOME `id` <owner>'
DQUOTED="two words # not a comment"
EMPTY=
EOF
printf 'CRLF=yes
' >> "$envf"
out=$(env -i PATH="$PATH" PRESET=kept bash -c '. "$1"; load_env_file "$2" && printf "%s|" "$PLAIN" "$SPACED" "$HASH" "$QUOTED" "$DQUOTED" "[$EMPTY]" "$CRLF"' _ "$ROOT/scripts/backup/env-file.sh" "$envf")
check "env file: values are literal, comments and CRLF stripped" [ "$out" = 'value|10.0.0.0/8 192.168.0.0/16|a#b|$HOME `id` <owner>|two words # not a comment|[]|yes|' ]
printf 'GOOD=1
this line is s3cret garbage
' > "$envf"
set +e; out=$(bash -c '. "$1"; load_env_file "$2"' _ "$ROOT/scripts/backup/env-file.sh" "$envf" 2>&1); rc=$?; set -e
check "env file: a malformed line is refused by number" [ "$rc" -ne 0 ]
check "env file: the malformed line's content is not echoed" bash -c "echo \"\$1\" | grep -q 'line 2' && ! echo \"\$1\" | grep -q s3cret" _ "$out"
out=$(env -i PATH="$PATH" PLAIN=from-env bash -c '. "$1"; printf "PLAIN=from-file
" > "$2"; load_env_file "$2"; printf %s "$PLAIN"' _ "$ROOT/scripts/backup/env-file.sh" "$envf")
check "env file: like Compose, the environment wins over the file" [ "$out" = from-env ]
rm -f "$envf"

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

# 6b. postgres is stopped on a host that has data: it is started and backed up, not treated as a first deploy
d=$(new_host); run "$d" v2 STUB_PG_STOPPED=1
check "stopped database: deploy exits 0" [ "$rc" -eq 0 ]
check "stopped database: it is started before the backup" bash -c "grep -n '' '$d/calls' | grep -E 'compose .*up -d --wait postgres|backup postgres' | head -1 | grep -q 'up -d --wait postgres'"
check "stopped database: the backup is taken" has "$d/calls" "backup postgres"
check "stopped database: the rehearsal runs" has "$d/calls" "drill .*db_only=1"
check "stopped database: never called a first deploy" bash -c "! echo \"\$1\" | grep -q 'first deploy'" _ "$out"
d=$(new_host); run "$d" v2 STUB_PG_STOPPED=1 STUB_PG_START_FAILS=1
check "database that cannot start: deploy stops" [ "$rc" -ne 0 ]
check "database that cannot start: nothing is rolled out" bash -c "! grep -E 'compose .* up -d --remove-orphans' '$d/calls'"

# 6c. a host that deployed before has no database volume: refused, never a new empty database
d=$(new_host); run "$d" v2 STUB_NO_PG_VOLUME=1 STUB_PG_STOPPED=1
check "missing database volume: refused" [ "$rc" -ne 0 ]
check "missing database volume: says why" says "database volume chaya_postgres-data does not exist"
check "missing database volume: nothing is started" bash -c "! grep -qE 'compose .* up ' '$d/calls'"
check "missing database volume: the deployed version is kept" [ "$(cat "$d/.deployed-version")" = v1 ]
d=$(new_host); rm "$d/.deployed-version"; run "$d" v2 STUB_NO_PG_VOLUME=1 STUB_PG_STOPPED=1
check "true first deploy (no volume, nothing deployed) proceeds" [ "$rc" -eq 0 ]

# 7. the session that started the deploy goes away (SSH dropped, CI job cancelled): the deploy still finishes.
#    sshd sends SIGHUP to the session's process group; the deploy runs in its own session.
if command -v setsid > /dev/null && command -v flock > /dev/null; then
  d=$(new_host)
  (cd "$d" && exec setsid env STUB_DIR="$d" PATH="$d/bin:$PATH" HEALTH_TIMEOUT=0 BACKUP_DIR="$d/backups" STUB_UP_SLEEP=3      ./deploy.sh v2 > "$d/session.out" 2>&1) &
  starter=$!
  for _ in $(seq 50); do grep -q "compose .* up " "$d/calls" 2>/dev/null && break; sleep 0.1; done
  kill -HUP -- "-$starter" 2>/dev/null || true
  wait "$starter" 2>/dev/null || true
  for _ in $(seq 100); do [ -s "$d/.deploy-history" ] && break; sleep 0.1; done
  out=$(cat "$d"/.deploy-logs/*.log 2>/dev/null || true)
  check "a deploy whose session was hung up mid-rollout still completes" has "$d/.deploy-history" " v2 ok"
  check "it is recorded as deployed" [ "$(cat "$d/.deployed-version")" = v2 ]
  check "its log is kept on the host" says "8/8 deployed v2"
  check "no secret in the kept log" no_secret

  # 8. a second deploy while one runs refuses to start and changes nothing
  d=$(new_host)
  (cd "$d" && env STUB_DIR="$d" PATH="$d/bin:$PATH" HEALTH_TIMEOUT=0 BACKUP_DIR="$d/backups" STUB_UP_SLEEP=3 ./deploy.sh v2 > /dev/null 2>&1) &
  first=$!
  for _ in $(seq 50); do grep -q "compose .* up " "$d/calls" 2>/dev/null && break; sleep 0.1; done
  run "$d" v3
  check "a concurrent deploy is refused" [ "$rc" -ne 0 ]
  check "it says another deploy is running" says "another deploy is running"
  check "it never starts its own version" bash -c "! grep -q 'CHAYA_VERSION=v3' '$d/calls'"
  wait "$first" || true
  check "the first deploy is unaffected" [ "$(cat "$d/.deployed-version")" = v2 ]
else
  echo "skipped: session and lock cases need setsid and flock (util-linux)"
fi

echo "deploy.sh tests: $passed passed, $failed failed"
[ "$failed" -eq 0 ]
