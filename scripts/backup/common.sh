# shellcheck shell=bash
# Shared by scripts/backup/*.sh. Sourced, not executed.
# Loads .env from the repo root, sets up the compose command and a private temp dir for credential files.

REPO_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
ENV_FILE=${ENV_FILE:-$REPO_ROOT/.env}
[ -f "$ENV_FILE" ] || { echo "error: $ENV_FILE not found (copy .env.example)" >&2; exit 2; }
set -a
# shellcheck disable=SC1090
. "$ENV_FILE"
set +a

export MSYS_NO_PATHCONV=1   # Git Bash on Windows: do not rewrite container paths like /backup
# Development stack by default; the production host sets CHAYA_COMPOSE_FILE (infra/deploy) and COMPOSE_NETWORK
# (chaya_internal), see DEPLOYMENT.md.
COMPOSE_FILE_PATH=${CHAYA_COMPOSE_FILE:-$REPO_ROOT/infra/docker/docker-compose.yml}
# A function, not a command string: paths with spaces (a Windows home directory, for example) must stay one argument.
compose() { docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE_PATH" "$@"; }
NETWORK=${COMPOSE_NETWORK:-chaya_default}
MC_IMAGE=${MC_IMAGE:-quay.io/minio/mc:latest}

BACKUP_DIR=${BACKUP_DIR:-$REPO_ROOT/backups}
case "$BACKUP_DIR" in /*|[A-Za-z]:*) ;; *) BACKUP_DIR="$REPO_ROOT/${BACKUP_DIR#./}" ;; esac
mkdir -p "$BACKUP_DIR/postgres" "$BACKUP_DIR/minio"
chmod 700 "$BACKUP_DIR" 2>/dev/null || true

PRIVATE_TMP=$(mktemp -d)
chmod 700 "$PRIVATE_TMP"
trap 'rm -rf "$PRIVATE_TMP"' EXIT

: "${POSTGRES_USER:?}" "${POSTGRES_DB:?}" "${S3_BUCKET_RAW:=chaya-raw}" "${S3_BUCKET_DERIVED:=chaya-derived}"

# Host paths handed to docker.exe on Git Bash for Windows must be Windows paths: MSYS_NO_PATHCONV (above) stops the
# automatic translation, and docker cannot open /tmp/... or /c/... itself. Elsewhere this is the identity.
native_path() { if command -v cygpath > /dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

# mc_run ALIAS ACCESS SECRET -- mc args...   (credentials go through a 600 env file, never the command line)
mc_run() {
  alias_name=$1; key=$2; secret=$3; shift 3
  envf="$PRIVATE_TMP/mc.env"
  umask 077
  printf 'MC_HOST_%s=http://%s:%s@minio:9000\n' "$alias_name" "$key" "$secret" > "$envf"
  docker run --rm --network "$NETWORK" --env-file "$(native_path "$envf")" -v "$(native_path "$BACKUP_DIR/minio"):/backup" \
    "$MC_IMAGE" "$@"
  rm -f "$envf"
}

sha256_of() { sha256sum "$1" | cut -d' ' -f1; }
now_utc() { date -u +%Y%m%dT%H%M%SZ; }
