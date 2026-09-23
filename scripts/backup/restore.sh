#!/usr/bin/env bash
# Restores the LIVE database and/or buckets from a backup. Destructive: it replaces current data. Stop the API and
# workers first (docs/backup-recovery.md, "Recovery procedure"), and run verify.sh on the backup before this.
#
#   scripts/backup/restore.sh postgres path/to/chaya-<ts>.dump --yes-replace-live-data
#   scripts/backup/restore.sh minio --yes-replace-live-data
set -euo pipefail
. "$(dirname "$0")/common.sh"

what=${1:?usage: restore.sh postgres <dump> | minio   --yes-replace-live-data}
shift
dump=""
if [ "$what" = postgres ]; then dump=${1:?dump file required}; shift; fi
[ "${1:-}" = "--yes-replace-live-data" ] || { echo "refusing: pass --yes-replace-live-data to confirm" >&2; exit 2; }

if [ "$what" = postgres ]; then
  [ "$(sha256_of "$dump")" = "$(cat "$dump.sha256")" ] || { echo "checksum mismatch; not restoring" >&2; exit 1; }
  : "${POSTGRES_APP_USER:?set POSTGRES_APP_USER}"
  echo "postgres: replacing $POSTGRES_DB from $dump"
  $COMPOSE exec -T postgres psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres \
    -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '$POSTGRES_DB' AND pid <> pg_backend_pid()" \
    -c "DROP DATABASE IF EXISTS \"$POSTGRES_DB\"" \
    -c "CREATE DATABASE \"$POSTGRES_DB\" OWNER \"$POSTGRES_APP_USER\""
  $COMPOSE exec -T postgres psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
    -c "CREATE EXTENSION IF NOT EXISTS vector" -c "CREATE EXTENSION IF NOT EXISTS pg_trgm"
  # Objects keep their recorded owner (the app role), so the restored schema is exactly as before.
  $COMPOSE exec -T postgres pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --exit-on-error < "$dump"
  echo "postgres: restored"
fi

if [ "$what" = minio ]; then
  : "${MINIO_ROOT_USER:?}" "${MINIO_ROOT_PASSWORD:?}"
  for bucket in "$S3_BUCKET_RAW" "$S3_BUCKET_DERIVED"; do
    [ -d "$BACKUP_DIR/minio/$bucket" ] || { echo "no mirror for $bucket in $BACKUP_DIR/minio" >&2; exit 1; }
    echo "minio: restoring $bucket (objects missing from the mirror are removed from the bucket)"
    mc_run dst "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" mirror --overwrite --remove "/backup/$bucket" "dst/$bucket"
  done
  echo "minio: restored"
fi
