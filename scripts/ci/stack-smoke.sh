#!/usr/bin/env bash
# Stack smoke test: capture -> upload -> validation -> processing through the REAL API, object storage, malware scanner
# and identity provider, with the real worker code claiming and running jobs.
#
#   scripts/ci/stack-smoke.sh            # bring the stack up, seed, test, tear down
#   KEEP_STACK=1 scripts/ci/stack-smoke.sh   # leave it running for inspection
#
# What it proves: an uploaded video is scanned and accepted, processing starts, the worker runs the CPU stages
# (validation, frame extraction, quality filter, privacy) for real, PII staging objects are purged, and the run stops
# with a structured, retryable DEPENDENCY_UNAVAILABLE at the first GPU stage (POSE_ESTIMATION) -- the GPU stages are
# EXCLUDED here by construction (no GPU, no COLMAP), never faked. Artifact generation is covered by the worker smoke
# test (services/reconstruction/tests/smoke), which runs without the stack.
#
# Everything is throwaway: random credentials in a temp env file, containers and volumes removed at the end.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$ROOT"
export MSYS_NO_PATHCONV=1

PY=${PYTHON:-python3}
rand() { "$PY" -c "import secrets; print(secrets.token_urlsafe(24))"; }
# json_get a.0.b  -> prints d["a"][0]["b"] of the JSON on stdin
json_get() { "$PY" -c "import json,sys
d = json.load(sys.stdin)
for k in sys.argv[1].split('.'):
    d = d[int(k)] if k.isdigit() else d[k]
print(d)" "$1"; }
ENV_FILE=$(mktemp)
chmod 600 "$ENV_FILE"
cat > "$ENV_FILE" <<EOF
POSTGRES_DB=chaya
POSTGRES_USER=postgres
POSTGRES_PASSWORD=$(rand)
POSTGRES_APP_USER=chaya
POSTGRES_APP_PASSWORD=$(rand)
REDIS_PASSWORD=$(rand)
MINIO_ROOT_USER=smokeroot
MINIO_ROOT_PASSWORD=$(rand)
S3_ACCESS_KEY=smoke-api
S3_SECRET_KEY=$(rand)
S3_WORKER_ACCESS_KEY=smoke-worker
S3_WORKER_SECRET_KEY=$(rand)
S3_BACKUP_ACCESS_KEY=smoke-backup
S3_BACKUP_SECRET_KEY=$(rand)
S3_BUCKET_RAW=chaya-raw
S3_BUCKET_DERIVED=chaya-derived
CHAYA_WORKER_CLIENT_SECRET=$(rand)
KEYCLOAK_ADMIN=smokeadmin
KEYCLOAK_ADMIN_PASSWORD=$(rand)
KEYCLOAK_PORT=${SMOKE_KEYCLOAK_PORT:-8081}
API_PORT=${SMOKE_API_PORT:-8080}
MINIO_API_PORT=${SMOKE_MINIO_PORT:-9000}
# Only MinIO, Keycloak and the API are used from the host (override SMOKE_MINIO_PORT / SMOKE_KEYCLOAK_PORT /
# SMOKE_API_PORT if taken locally); the rest go to unlikely ports so the smoke stack does not collide with a local
# Postgres/Redis/ClamAV.
POSTGRES_PORT=55432
REDIS_PORT=56379
CLAMAV_PORT=53310
MINIO_CONSOLE_PORT=59001
EOF
set -a
# The host's own .env, chosen at run time.
# shellcheck disable=SC1090
. "$ENV_FILE"
set +a

# Git Bash on Windows: the docker CLI needs a native path for the env file.
ENV_FILE_DOCKER=$(command -v cygpath > /dev/null && cygpath -w "$ENV_FILE" || echo "$ENV_FILE")
COMPOSE="docker compose -p chaya-smoke --env-file $ENV_FILE_DOCKER -f infra/docker/docker-compose.yml -f infra/ci/docker-compose.smoke.yml"
cleanup() {
  status=$?
  # The compose file paths are relative to the repository root, and the test step below changes directory: without
  # this, a failure there would print no API log and a successful run would leave the whole stack running.
  cd "$ROOT"
  if [ $status -ne 0 ]; then
    echo "---- API log (last 200 lines) ----"; $COMPOSE logs --no-color --tail 200 api || true
  fi
  if [ -z "${KEEP_STACK:-}" ]; then $COMPOSE down -v --remove-orphans > /dev/null 2>&1 || true; rm -f "$ENV_FILE"; fi
  exit $status
}
trap cleanup EXIT

echo "== starting infrastructure and API (ClamAV loads signatures on first start: several minutes)"
# `up` waits for each depends_on condition (healthy / completed) before starting the API.
$COMPOSE up -d --build api

KC=http://localhost:$KEYCLOAK_PORT
API=http://localhost:$API_PORT

echo "== waiting for API readiness (migrations applied, dependencies answering)"
for i in $(seq 1 90); do
  curl -fsS "$API/actuator/health/readiness" > /dev/null 2>&1 && break
  [ "$i" -eq 90 ] && { echo "API never became ready"; exit 1; }
  sleep 5
done
curl -fsS "$API/actuator/health/readiness"; echo
curl -fsS "$API/api/v1/health"; echo

echo "== seed an organization and venue (there is no API for creating organizations)"
# -q drops the "INSERT 0 1" command tag that would otherwise follow the RETURNING value.
psql() { $COMPOSE exec -T postgres psql -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At "$@" | tr -d '\r'; }
ORG=$(psql -c "INSERT INTO organization (slug, name) VALUES ('smoke-org', 'Smoke Org') RETURNING id")
VENUE=$(psql -c "INSERT INTO venue (organization_id, slug, name) VALUES ('$ORG', 'smoke-venue', 'Smoke Venue') RETURNING id")
echo "org=$ORG venue=$VENUE"

echo "== seed an operator in Keycloak and a test-only password-grant client"
ADMIN_TOKEN=$(curl -fsS "$KC/realms/master/protocol/openid-connect/token" -d grant_type=password -d client_id=admin-cli \
  --data-urlencode "username=$KEYCLOAK_ADMIN" --data-urlencode "password=$KEYCLOAK_ADMIN_PASSWORD" | json_get access_token)
kc() { curl -fsS -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" "$@"; }
# Only in this throwaway realm: chaya-web deliberately has no password grant.
kc -X POST "$KC/admin/realms/chaya/clients" -d '{"clientId":"chaya-smoke","publicClient":true,"standardFlowEnabled":false,"directAccessGrantsEnabled":true,"defaultClientScopes":["basic","chaya-roles","chaya-api-audience","chaya-tenant"]}'
USER_PASSWORD=$(rand)Aa1!
kc -X POST "$KC/admin/realms/chaya/users" -d "{\"username\":\"smoke-operator\",\"enabled\":true,\"email\":\"smoke@example.invalid\",\"emailVerified\":true,\"firstName\":\"Smoke\",\"lastName\":\"Operator\",\"attributes\":{\"org_id\":[\"$ORG\"],\"venue_id\":[\"$VENUE\"]}}"
USER_ID=$(kc "$KC/admin/realms/chaya/users?username=smoke-operator&exact=true" | json_get 0.id)
kc -X PUT "$KC/admin/realms/chaya/users/$USER_ID/reset-password" -d "{\"type\":\"password\",\"value\":\"$USER_PASSWORD\",\"temporary\":false}"
ROLE=$(kc "$KC/admin/realms/chaya/roles/operator")
kc -X POST "$KC/admin/realms/chaya/users/$USER_ID/role-mappings/realm" -d "[$ROLE]"
USER_TOKEN=$(curl -fsS "$KC/realms/chaya/protocol/openid-connect/token" -d grant_type=password -d client_id=chaya-smoke \
  -d username=smoke-operator --data-urlencode "password=$USER_PASSWORD" | json_get access_token)

echo "== the token is accepted by the API (tenant claims present)"
curl -fsS -H "Authorization: Bearer $USER_TOKEN" "$API/api/v1/venues" | grep -q "$VENUE"

echo "== stack end-to-end test (real worker code against the real control plane)"
cd services/reconstruction
CHAYA_IT_API=$API \
CHAYA_IT_TOKEN_URL=$KC/realms/chaya/protocol/openid-connect/token \
CHAYA_IT_WORKER_SECRET=$CHAYA_WORKER_CLIENT_SECRET \
CHAYA_IT_USER_TOKEN=$USER_TOKEN \
CHAYA_IT_VENUE_ID=$VENUE \
CHAYA_IT_S3_ENDPOINT=http://localhost:$MINIO_API_PORT \
CHAYA_IT_S3_ACCESS_KEY=$S3_WORKER_ACCESS_KEY \
CHAYA_IT_S3_SECRET_KEY=$S3_WORKER_SECRET_KEY \
  "$PY" -m pytest -m integration tests/integration/test_stack_e2e.py -v -rA

echo "== stack smoke passed"
