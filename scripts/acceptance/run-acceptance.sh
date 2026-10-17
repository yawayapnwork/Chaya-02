#!/usr/bin/env bash
# Runs the cross-service acceptance suite (services/reconstruction/tests/acceptance; docs/ACCEPTANCE.md, classification C)
# against a real Chaya stack, then the browser viewer check against the same stack.
#
#   scripts/acceptance/run-acceptance.sh ci
#       Brings up a throwaway stack (infra/docker + smoke + acceptance overlays: real API, Keycloak, MinIO, Postgres,
#       ClamAV, vision, web, the production CPU worker and the test-only Open3D worker), runs the suite, tears it down.
#   scripts/acceptance/run-acceptance.sh existing <compose file> <env file> <project>
#       Runs against a stack that is already up (for example infra/deploy on a staging host). Starts a test-only Open3D
#       worker on its internal network for the duration of the run. Creates test users, venues and runs in it.
#
# Environment: ACC_OUT (evidence directory; default a temp dir), ACC_PYTEST_ARGS (extra pytest arguments),
# ACC_SKIP_VIEWER=1 (no browser check), ACC_WEB_URL (the web app as the browser reaches it), ACC_IGNORE_HTTPS_ERRORS=1 (a
# local CA the browser does not trust; TLS is checked separately), WORKER_SERVICE (default: reconstruction-worker or worker).
# Exit status: 0 every test passed; 1 a test failed; 2 bad arguments; 3 nothing failed but something was BLOCKED.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$ROOT"
export MSYS_NO_PATHCONV=1
native() { if command -v cygpath > /dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }
mode=${1:-}
OUT=${ACC_OUT:-$(mktemp -d)}
mkdir -p "$OUT"
TAG=$(date -u +%H%M%S)$RANDOM
PRIVATE=$(mktemp -d)
chmod 700 "$PRIVATE"
O3D=""
COMPOSE_ARGS=()
# A function over an array, not a command string: paths with spaces (a Windows home directory) stay one argument.
compose() { docker compose "${COMPOSE_ARGS[@]}" "$@"; }
cleanup() {
  status=$?
  [ -n "$O3D" ] && docker rm -f "$O3D" > /dev/null 2>&1 || true
  if [ "$mode" = ci ] && [ -z "${KEEP_STACK:-}" ]; then
    [ $status -ne 0 ] && compose logs --no-color --tail 120 api reconstruction-worker open3d-worker > "$OUT/stack-logs.txt" 2>&1 || true
    compose down -v --remove-orphans > /dev/null 2>&1 || true
  fi
  rm -rf "$PRIVATE"
  exit $status
}
trap cleanup EXIT
. scripts/backup/env-file.sh
rand() { head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n' | cut -c1-32; }

case "$mode" in
  ci)
    ENV_FILE="$PRIVATE/stack.env"
    {
      echo "POSTGRES_DB=chaya"; echo "POSTGRES_USER=postgres"; echo "POSTGRES_PASSWORD=$(rand)"
      echo "POSTGRES_APP_USER=chaya"; echo "POSTGRES_APP_PASSWORD=$(rand)"
      echo "MINIO_ROOT_USER=accroot"; echo "MINIO_ROOT_PASSWORD=$(rand)"
      echo "S3_ACCESS_KEY=acc-api"; echo "S3_SECRET_KEY=$(rand)"
      echo "S3_WORKER_ACCESS_KEY=acc-worker"; echo "S3_WORKER_SECRET_KEY=$(rand)"
      echo "S3_BACKUP_ACCESS_KEY=acc-backup"; echo "S3_BACKUP_SECRET_KEY=$(rand)"
      echo "S3_BUCKET_RAW=chaya-raw"; echo "S3_BUCKET_DERIVED=chaya-derived"
      echo "CHAYA_WORKER_CLIENT_SECRET=$(rand)"; echo "KEYCLOAK_ADMIN=accadmin"; echo "KEYCLOAK_ADMIN_PASSWORD=$(rand)"
      echo "KEYCLOAK_PORT=${ACC_KEYCLOAK_PORT:-8081}"; echo "API_PORT=${ACC_API_PORT:-8080}"; echo "WEB_PORT=${ACC_WEB_PORT:-3000}"
      echo "MINIO_API_PORT=59000"; echo "POSTGRES_PORT=55432"; echo "CLAMAV_PORT=53310"; echo "MINIO_CONSOLE_PORT=59001"
    } > "$ENV_FILE"
    PROJECT=chaya-acceptance
    COMPOSE_ARGS=(-p "$PROJECT" --env-file "$(native "$ENV_FILE")" -f infra/docker/docker-compose.yml -f infra/ci/docker-compose.smoke.yml
                  -f infra/ci/docker-compose.acceptance.yml --profile pipeline)
    NETWORK=${PROJECT}_default
    WORKER_SERVICE=${WORKER_SERVICE:-reconstruction-worker}
    echo "== starting the stack (first start: ClamAV loads signatures and vision downloads CLIP weights, several minutes)"
    compose up -d --build --wait --wait-timeout 1200 api reconstruction-worker open3d-worker vision web
    WEB_URL=${ACC_WEB_URL:-http://localhost:${ACC_WEB_PORT:-3000}}
    # The realm file allows only http://localhost:3000 as chaya-web's redirect and origin. On this throwaway stack, allow the
    # web app where it actually runs, as infra/deploy/keycloak-configure.sh does for a deployment.
    compose exec -T -e APP="$WEB_URL" keycloak sh -s <<'IN_CONTAINER'
set -eu
K=/opt/keycloak/bin/kcadm.sh
$K config credentials --server http://localhost:8080 --realm master --user "$KC_BOOTSTRAP_ADMIN_USERNAME" \
  --password "$KC_BOOTSTRAP_ADMIN_PASSWORD" --config /tmp/kcadm.config > /dev/null
id=$($K get clients -r chaya -q clientId=chaya-web --fields id --format csv --noquotes --config /tmp/kcadm.config)
$K update "clients/$id" -r chaya --config /tmp/kcadm.config -s "redirectUris=[\"$APP/auth/callback\"]" -s "webOrigins=[\"$APP\"]" \
  -s "attributes.\"post.logout.redirect.uris\"=$APP"
rm -f /tmp/kcadm.config
IN_CONTAINER
    ;;
  existing)
    [ $# -ge 4 ] || { echo "usage: $0 existing <compose file> <env file> <project>" >&2; exit 2; }
    ENV_FILE=$3
    PROJECT=$4
    # infra/deploy needs the version deploy.sh recorded; the dev compose ignores it.
    CHAYA_VERSION=${CHAYA_VERSION:-$(cat "$(dirname "$2")/.deployed-version" 2>/dev/null || true)}
    export CHAYA_VERSION
    COMPOSE_ARGS=(-p "$PROJECT" --env-file "$(native "$ENV_FILE")" -f "$(native "$2")")
    NETWORK=$(docker inspect -f '{{range $k, $v := .NetworkSettings.Networks}}{{println $k}}{{end}}' "$(compose ps -q api)" | grep -E '_(internal|default)$' | head -1)
    WORKER_SERVICE=${WORKER_SERVICE:-worker}
    WEB_URL=${ACC_WEB_URL:-}
    ;;
  *) echo "usage: $0 ci | existing <compose file> <env file> <project>" >&2; exit 2 ;;
esac
load_env_file "$ENV_FILE"
psql() { compose exec -T postgres psql -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At "$@" | tr -d '\r'; }

echo "== seed two organizations (there is no API for creating one)"
ORG_A=$(psql -c "INSERT INTO organization (slug, name) VALUES ('acc-a-$TAG', 'Acceptance org A $TAG') RETURNING id")
ORG_B=$(psql -c "INSERT INTO organization (slug, name) VALUES ('acc-b-$TAG', 'Acceptance org B $TAG') RETURNING id")

WORKER_IMAGE=$(docker inspect -f '{{.Config.Image}}' "$(compose ps -q "$WORKER_SERVICE" | head -1)")
echo "== the suite's runner image: $WORKER_IMAGE + pytest (infra/ci/acceptance.Dockerfile)"
docker build -q -t chaya-acceptance-runner:local -f infra/ci/acceptance.Dockerfile --build-context "base=docker-image://$WORKER_IMAGE" infra/ci > /dev/null

if [ "$mode" = existing ]; then
  # The three Open3D stages, by a TEST-ONLY worker of this checkout's code (benchmarks/docker/open3d.Dockerfile).
  docker image inspect chaya-bench-open3d > /dev/null 2>&1 || docker build -q -t chaya-bench-open3d -f benchmarks/docker/open3d.Dockerfile . > /dev/null
  O3D=acc-open3d-$TAG
  {
    echo "CHAYA_API_URL=http://api:8080"; echo "CHAYA_TOKEN_URL=http://keycloak:8080/realms/chaya/protocol/openid-connect/token"
    echo "CHAYA_CLIENT_ID=chaya-worker"; echo "CHAYA_CLIENT_SECRET=$CHAYA_WORKER_CLIENT_SECRET"; echo "S3_ENDPOINT=http://minio:9000"
    echo "S3_ACCESS_KEY=$S3_WORKER_ACCESS_KEY"; echo "S3_SECRET_KEY=$S3_WORKER_SECRET_KEY"; echo "WORKER_ID=acceptance-open3d-worker"
    echo "WORKER_WORKDIR=/tmp/work"; echo "WORKER_STAGES=GEOMETRIC_CLEANUP,PLANE_FITTING,REGION_ALIGNMENT"
    echo "PYTHONPATH=/repo/services/reconstruction"; echo "PYTHONDONTWRITEBYTECODE=1"
  } > "$PRIVATE/o3d.env"
  docker run -d --name "$O3D" --network "$NETWORK" --security-opt no-new-privileges:true --cap-drop ALL --memory 2g \
    --env-file "$(native "$PRIVATE/o3d.env")" -v "$(native "$ROOT"):/repo:ro" chaya-bench-open3d python -m chaya_worker > /dev/null
fi

VISION=""
docker run --rm --network "$NETWORK" --entrypoint python chaya-acceptance-runner:local -c \
  "import urllib.request; urllib.request.urlopen('http://vision:8090/health/ready', timeout=10)" > /dev/null 2>&1 && VISION=http://vision:8090
{
  echo "ACC_API_URL=http://api:8080"; echo "ACC_KEYCLOAK_URL=http://keycloak:8080"
  echo "ACC_KEYCLOAK_ADMIN=$KEYCLOAK_ADMIN"; echo "ACC_KEYCLOAK_ADMIN_PASSWORD=$KEYCLOAK_ADMIN_PASSWORD"
  echo "ACC_WORKER_CLIENT_SECRET=$CHAYA_WORKER_CLIENT_SECRET"; echo "ACC_S3_ENDPOINT=http://minio:9000"
  echo "ACC_S3_WORKER_ACCESS_KEY=$S3_WORKER_ACCESS_KEY"; echo "ACC_S3_WORKER_SECRET_KEY=$S3_WORKER_SECRET_KEY"
  echo "ACC_S3_ROOT_ACCESS_KEY=$MINIO_ROOT_USER"; echo "ACC_S3_ROOT_SECRET_KEY=$MINIO_ROOT_PASSWORD"
  echo "ACC_S3_BUCKET_DERIVED=${S3_BUCKET_DERIVED:-chaya-derived}"; echo "ACC_ORG_A=$ORG_A"; echo "ACC_ORG_B=$ORG_B"
  echo "ACC_RUN_TAG=$TAG"; echo "ACC_VISION_URL=$VISION"; echo "ACC_EVIDENCE=/out/acceptance-c.json"; echo "ACC_HANDOFF=/out/.handoff.json"
  echo "ACC_LEASE_WAIT_MAX=${ACC_LEASE_WAIT_MAX:-480}"; echo "TMPDIR=/tmp"; echo "HOME=/tmp"
} > "$PRIVATE/acc.env"

echo "== acceptance suite (classification C) on network $NETWORK"
chmod 777 "$OUT"
set +e
# shellcheck disable=SC2086
docker run --rm --network "$NETWORK" --env-file "$(native "$PRIVATE/acc.env")" -v "$(native "$ROOT"):/repo:ro" -v "$(native "$OUT"):/out" \
  chaya-acceptance-runner:local tests/acceptance -m acceptance -p no:randomly -rA ${ACC_PYTEST_ARGS:-} 2>&1 | tee "$OUT/pytest.log"
suite=${PIPESTATUS[0]}
set -e

viewer=0
if [ -z "${ACC_SKIP_VIEWER:-}" ] && [ -n "$WEB_URL" ] && [ -f "$OUT/.handoff.json" ] && [ -d apps/web/node_modules/playwright ]; then
  echo "== browser: the real web app in Chromium, against the same stack (scripts/e2e/viewer_format_check.cjs)"
  # Native paths: on Windows, node reads /tmp/... as C:\tmp\... (it is not an MSYS program).
  handoff=$(native "$OUT/.handoff.json") viewer_out=$(native "$OUT/acceptance-c-viewer.json")
  (cd apps/web && WEB_URL=$WEB_URL HANDOFF="$handoff" OUT="$viewer_out" \
     IGNORE_HTTPS_ERRORS=${ACC_IGNORE_HTTPS_ERRORS:-} node ../../scripts/e2e/viewer_format_check.cjs) || viewer=$?
  if [ ! -s "$OUT/acceptance-c-viewer.json" ]; then
    # The check died before writing its results: that is a failure, never an absent (and so silently passing) result.
    echo "{\"results\":[{\"id\":\"browser\",\"status\":\"FAIL\",\"detail\":\"viewer_format_check.cjs exited $viewer without results\"}]}" \
      > "$OUT/acceptance-c-viewer.json"
    [ "$viewer" -ne 0 ] || viewer=1
  fi
else
  echo "== browser check NOT RUN (needs ACC_WEB_URL or ci mode, the handoff and apps/web/node_modules): recorded as BLOCKED"
  echo '{"results":[{"id":"browser","status":"BLOCKED","blocked_dependency":"no web URL, handoff or Playwright on this runner"}]}' > "$OUT/acceptance-c-viewer.json"
fi
# The handoff holds a viewer link and a test password. Kept only with KEEP_STACK (the throwaway stack is still up, to re-run the
# browser check against it); delete it with the stack.
if [ -n "${KEEP_STACK:-}" ]; then echo "KEEP_STACK: the browser handoff (test secrets) is kept at $OUT/.handoff.json"; else rm -f "$OUT/.handoff.json"; fi
# The suite corrupts one stored model on purpose (test_04_viewer); put it back whether or not the browser saw it.
if [ -f "$OUT/.restore.json" ]; then
  docker run --rm --network "$NETWORK" --env-file "$(native "$PRIVATE/acc.env")" -v "$(native "$ROOT"):/repo:ro" -v "$(native "$OUT"):/out" \
    --entrypoint python chaya-acceptance-runner:local -m tests.acceptance.restore /out/.restore.json || echo "WARNING: restoring a corrupted test model failed"
  rm -f "$OUT/.restore.json"
fi
echo "evidence: $OUT"
# The results file decides as well as the exit status: a FAIL recorded anywhere fails the run.
grep -q '"status": *"FAIL"' "$OUT/acceptance-c.json" "$OUT/acceptance-c-viewer.json" 2>/dev/null && viewer=1
[ "$suite" -eq 0 ] && [ "$viewer" -eq 0 ] || exit 1
grep -q '"BLOCKED"' "$OUT/acceptance-c.json" "$OUT/acceptance-c-viewer.json" 2>/dev/null && exit 3
exit 0
