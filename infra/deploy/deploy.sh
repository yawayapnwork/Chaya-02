#!/usr/bin/env bash
# Deploy (or roll back to) one version of Chaya 02 on this host. Run from the deploy directory (/srv/chaya), normally
# by .github/workflows/deploy.yml over SSH:
#
#   ./deploy.sh sha-abc1234          # deploy
#   ./deploy.sh sha-0123456          # roll back = deploy the previous version (see DEPLOYMENT.md "Rollback")
#
# Steps (any failure stops the deploy; a failure after the switch triggers an automatic rollback):
#   1. pull every image of the target version (a missing or unreadable image fails here, before anything changes)
#   2. back up the database (pg_dump) -- the restore point if a migration must be undone
#   3. start/replace containers; Flyway migrations run as the API starts
#   4. wait until api, web, worker and keycloak report HEALTHY (readiness, not "process started")
#   5. apply the environment's Keycloak settings; check the public endpoints through the proxy
#   6. record the version
set -euo pipefail
cd "$(dirname "$0")"
VERSION=${1:?usage: deploy.sh <image tag, e.g. sha-abc1234>}
ENV_FILE=${ENV_FILE:-./.env}
[ -f "$ENV_FILE" ] || { echo "error: $ENV_FILE missing (see .env.production.example)" >&2; exit 2; }
set -a; . "$ENV_FILE"; set +a
export CHAYA_VERSION=$VERSION
COMPOSE="docker compose --env-file $ENV_FILE -f docker-compose.yml"
HEALTH_TIMEOUT=${HEALTH_TIMEOUT:-600}
PREVIOUS=$(cat .deployed-version 2>/dev/null || true)
log() { echo "[$(date -u +%H:%M:%S)] $*"; }

wait_healthy() { # service...
  local deadline=$((SECONDS + HEALTH_TIMEOUT)) svc id state
  for svc in "$@"; do
    while :; do
      id=$($COMPOSE ps -q "$svc" 2>/dev/null | head -1)
      state=$([ -n "$id" ] && docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}no-healthcheck{{end}}' "$id" || echo missing)
      [ "$state" = healthy ] && { log "$svc healthy"; break; }
      if [ $SECONDS -ge $deadline ] || [ "$state" = missing ]; then
        log "$svc is $state after ${HEALTH_TIMEOUT}s"; $COMPOSE logs --no-color --tail 80 "$svc" || true; return 1
      fi
      sleep 5
    done
  done
}

public_checks() {
  local api web
  api=$(curl -sS -o /tmp/chaya-api-health.json -w '%{http_code}' "https://$CHAYA_API_DOMAIN/api/v1/health") || return 1
  log "https://$CHAYA_API_DOMAIN/api/v1/health -> $api $(cat /tmp/chaya-api-health.json)"
  [ "$api" = 200 ] || return 1        # 503 = DOWN; DEGRADED answers 200 and is logged above
  web=$(curl -sS -o /dev/null -w '%{http_code}' "https://$CHAYA_APP_DOMAIN/api/health") || return 1
  log "https://$CHAYA_APP_DOMAIN/api/health -> $web"
  [ "$web" = 200 ]
}

rollout() { # version
  export CHAYA_VERSION=$1
  $COMPOSE up -d --remove-orphans
  wait_healthy postgres keycloak api web worker
  ./keycloak-configure.sh
  public_checks
}

log "deploying $VERSION (currently: ${PREVIOUS:-nothing})"

log "1/5 pulling images"
$COMPOSE pull --quiet

log "2/5 pre-deploy database backup"
DUMP=""
if [ -n "$($COMPOSE ps -q --status running postgres 2>/dev/null)" ]; then
  CHAYA_COMPOSE_FILE="$PWD/docker-compose.yml" COMPOSE_NETWORK=chaya_internal ENV_FILE="$ENV_FILE" ./scripts/backup/backup.sh postgres
  DUMP=$(ls -1t "${BACKUP_DIR:-./backups}"/postgres/chaya-*.dump 2>/dev/null | head -1 || true)
  log "restore point: ${DUMP:-none}"
else
  log "postgres not running yet (first deploy): no backup to take"
fi
MIGRATION_BEFORE=$($COMPOSE exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc \
  "SELECT max(version) FROM flyway_schema_history WHERE success" 2>/dev/null || true)

log "3/5 rolling out $VERSION"
if rollout "$VERSION"; then
  echo "$VERSION" > .deployed-version
  echo "$(date -u +%FT%TZ) $VERSION ok" >> .deploy-history
  log "5/5 deployed $VERSION"
  exit 0
fi

log "DEPLOY FAILED for $VERSION"
echo "$(date -u +%FT%TZ) $VERSION failed" >> .deploy-history
MIGRATION_AFTER=$($COMPOSE exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc \
  "SELECT max(version) FROM flyway_schema_history WHERE success" 2>/dev/null || true)
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
