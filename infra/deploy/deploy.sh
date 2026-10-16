#!/usr/bin/env bash
# Deploy (or roll back to) one version of Chaya 02 on this host. Run from the deploy directory (/srv/chaya), normally
# by .github/workflows/deploy.yml over SSH:
#
#   ./deploy.sh sha-abc1234          # deploy
#   ./deploy.sh sha-0123456          # roll back = deploy the previous version (see DEPLOYMENT.md "Rollback")
#
# Steps (any failure stops the deploy; a failure after the switch triggers an automatic rollback):
#   1. validate the configuration (.env against the compose file) -- names a missing variable, never prints values
#   2. pull every image of the target version (a missing or unreadable image fails here, before anything changes)
#   3. back up the database (pg_dump) -- the restore point if a migration must be undone
#   4. migration rehearsal: restore that dump into an isolated throwaway database and boot the NEW api image on it
#      (scripts/backup/restore-drill.sh, DRILL_DB_ONLY). Migrations that fail on production data fail here, before
#      anything live changes. DEPLOY_SKIP_REHEARSAL=1 skips it (logged).
#   5. start/replace containers; Flyway migrations run as the API starts
#   6. wait until api, web, worker and keycloak report HEALTHY (readiness, not "process started")
#   7. apply the environment's Keycloak settings; check the public endpoints through the proxy
#   8. record the version
#
# A deploy is never left half done by the session that started it: the work runs in its own session (setsid) with its
# output in .deploy-logs/<time>-<version>.log, and this process only streams that log. If the SSH connection drops or
# the CI job is cancelled or times out, the deploy still finishes -- or rolls back -- and the log says how. Only one
# deploy runs at a time on a host (.deploy.lock); a second one refuses to start.
set -euo pipefail
cd "$(dirname "$0")"
VERSION=${1:?usage: deploy.sh <image tag, e.g. sha-abc1234>}
if [ -z "${DEPLOY_DETACHED:-}" ] && command -v setsid > /dev/null; then
  umask 077
  mkdir -p .deploy-logs
  logf=".deploy-logs/$(date -u +%Y%m%dT%H%M%SZ)-$VERSION.log"
  : > "$logf"
  DEPLOY_DETACHED=1 setsid "$PWD/$(basename "$0")" "$@" > "$logf" 2>&1 < /dev/null &
  worker=$!
  echo "deploy log: $PWD/$logf (the deploy continues if this session ends)"
  tail -n +1 -f --pid="$worker" "$logf" || true
  wait "$worker"
  exit $?
fi
if command -v flock > /dev/null; then
  exec 9>> .deploy.lock
  if ! flock -n 9; then
    echo "error: another deploy is running on this host (.deploy.lock); wait for it, see .deploy-logs/" >&2
    exit 3
  fi
else
  echo "warning: flock not found; concurrent deploys are not prevented" >&2
fi
ENV_FILE=${ENV_FILE:-./.env}
[ -f "$ENV_FILE" ] || { echo "error: $ENV_FILE missing (see .env.production.example)" >&2; exit 2; }
# Parsed as Compose parses it, never executed (scripts/backup/env-file.sh).
# shellcheck source=../../scripts/backup/env-file.sh
. ./scripts/backup/env-file.sh
load_env_file "$ENV_FILE"
export CHAYA_VERSION=$VERSION
# A function, not a command string, so an env-file path with spaces stays one argument.
compose() { docker compose --env-file "$ENV_FILE" -f docker-compose.yml "$@"; }
HEALTH_TIMEOUT=${HEALTH_TIMEOUT:-600}
PREVIOUS=$(cat .deployed-version 2>/dev/null || true)
log() { echo "[$(date -u +%H:%M:%S)] $*"; }
# The newest applied Flyway migration. flyway_schema_history.version is text, so max(version) is wrong from V10 on
# ('9' > '16'): the last one installed is the current one.
schema_version() {
  compose exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc \
    "SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1" \
    2>/dev/null || true
}

wait_healthy() { # service...
  local deadline=$((SECONDS + HEALTH_TIMEOUT)) svc id state
  for svc in "$@"; do
    while :; do
      id=$(compose ps -q "$svc" 2>/dev/null | head -1)
      state=$([ -n "$id" ] && docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}no-healthcheck{{end}}' "$id" || echo missing)
      [ "$state" = healthy ] && { log "$svc healthy"; break; }
      if [ $SECONDS -ge $deadline ] || [ "$state" = missing ]; then
        log "$svc is $state after ${HEALTH_TIMEOUT}s"; compose logs --no-color --tail 80 "$svc" || true; return 1
      fi
      sleep 5
    done
  done
}

public_checks() {
  local api web body
  body=$(mktemp)
  api=$(curl -sS -o "$body" -w '%{http_code}' "https://$CHAYA_API_DOMAIN/api/v1/health") || { rm -f "$body"; return 1; }
  # The health report is public and holds only status words and counts.
  log "https://$CHAYA_API_DOMAIN/api/v1/health -> $api $(cat "$body")"
  rm -f "$body"
  [ "$api" = 200 ] || return 1        # 503 = DOWN; DEGRADED answers 200 and is logged above
  web=$(curl -sS -o /dev/null -w '%{http_code}' "https://$CHAYA_APP_DOMAIN/api/health") || return 1
  log "https://$CHAYA_APP_DOMAIN/api/health -> $web"
  [ "$web" = 200 ]
}

rollout() { # version
  export CHAYA_VERSION=$1
  # Chained with && on purpose: rollout runs as an `if` condition, where `set -e` is suspended, so without the chain an
  # unhealthy service would not stop it and the deploy would count as successful whenever the public checks passed.
  compose up -d --remove-orphans &&
    wait_healthy postgres keycloak api web worker &&
    ./keycloak-configure.sh &&
    public_checks
}

log "deploying $VERSION (currently: ${PREVIOUS:-nothing})"

log "1/8 validating configuration"
# --quiet prints errors only (a missing variable is named, its value never printed); the resolved file is not shown.
if ! compose config --quiet; then
  log "configuration invalid for $VERSION: fix $ENV_FILE against .env.production.example (the error above names the variable). Nothing was changed."
  exit 1
fi

# Container and network names follow the compose project: COMPOSE_PROJECT_NAME if set, else the file's `name:` (chaya).
# A second stack on the same host (staging next to production) has its own; the backup and rehearsal must use it.
PROJECT=$(compose config 2>/dev/null | sed -n 's/^name: //p' | head -1)
NETWORK="${PROJECT:-chaya}_internal"

log "2/8 pulling images"
compose pull --quiet

log "3/8 pre-deploy database backup"
DUMP=""
if [ -n "$(compose ps -q --status running postgres 2>/dev/null)" ]; then
  CHAYA_COMPOSE_FILE="$PWD/docker-compose.yml" COMPOSE_NETWORK="$NETWORK" ENV_FILE="$ENV_FILE" ./scripts/backup/backup.sh postgres
  DUMP=$(ls -1t "${BACKUP_DIR:-./backups}"/postgres/chaya-*.dump 2>/dev/null | head -1 || true)
  log "restore point: ${DUMP:-none}"
else
  log "postgres not running yet (first deploy): no backup to take"
fi
MIGRATION_BEFORE=$(schema_version)

log "4/8 migration rehearsal on a restored copy of the pre-deploy dump"
if [ -z "$DUMP" ]; then
  log "no dump (first deploy): nothing to rehearse"
elif [ -n "${DEPLOY_SKIP_REHEARSAL:-}" ]; then
  log "WARNING: rehearsal skipped (DEPLOY_SKIP_REHEARSAL); migrations meet production data for the first time on the live database"
else
  API_IMAGE=$(compose config --images 2>/dev/null | grep '/chaya-api:' | head -1 || true)
  [ -n "$API_IMAGE" ] || { log "cannot resolve the api image of $VERSION"; exit 1; }
  if ! DRILL_DB_ONLY=1 DRILL_API_IMAGE="$API_IMAGE" CHAYA_COMPOSE_FILE="$PWD/docker-compose.yml" COMPOSE_NETWORK="$NETWORK" \
       ENV_FILE="$ENV_FILE" ./scripts/backup/restore-drill.sh "$DUMP"; then
    log "REHEARSAL FAILED: $VERSION cannot start on a copy of the current data (see the drill output above). Nothing live was changed."
    echo "$(date -u +%FT%TZ) $VERSION rehearsal-failed" >> .deploy-history
    exit 1
  fi
fi

log "5/8 rolling out $VERSION"
if rollout "$VERSION"; then
  echo "$VERSION" > .deployed-version
  echo "$(date -u +%FT%TZ) $VERSION ok" >> .deploy-history
  log "8/8 deployed $VERSION"
  exit 0
fi

log "DEPLOY FAILED for $VERSION"
echo "$(date -u +%FT%TZ) $VERSION failed" >> .deploy-history
MIGRATION_AFTER=$(schema_version)
if [ "$MIGRATION_BEFORE" != "$MIGRATION_AFTER" ]; then
  log "schema moved from ${MIGRATION_BEFORE:-none} to ${MIGRATION_AFTER:-none} during this deploy."
  log "Migrations are forward-only. The previous version must be schema-compatible (DEPLOYMENT.md \"Migrations\");"
  log "if it is not, restore the pre-deploy dump: ./scripts/backup/restore.sh postgres ${DUMP:-<dump>} --yes-replace-live-data"
fi
if [ -n "$PREVIOUS" ] && [ "$PREVIOUS" != "$VERSION" ]; then
  log "rolling back to $PREVIOUS"
  if rollout "$PREVIOUS"; then
    echo "$(date -u +%FT%TZ) $PREVIOUS rollback-ok" >> .deploy-history
    log "rolled back to $PREVIOUS; the failed version is NOT deployed"
  else
    log "ROLLBACK ALSO FAILED: manual recovery needed (DEPLOYMENT.md \"Recovery\")"
  fi
fi
exit 1
