#!/usr/bin/env bash
# Backs up PostgreSQL (dated pg_dump, custom format, with SHA-256) and MinIO (additive mirror of both buckets through
# the read-only chaya-backup account). See docs/backup-recovery.md.
#
#   scripts/backup/backup.sh            # both
#   scripts/backup/backup.sh postgres   # database only
#   scripts/backup/backup.sh minio      # objects only
#
# Order matters: the database is dumped first. Objects are write-once, so every object a dump references already
# exists when the mirror runs afterwards.
set -euo pipefail
. "$(dirname "$0")/common.sh"

what=${1:-all}
ts=$(now_utc)

if [ "$what" = all ] || [ "$what" = postgres ]; then
  dump="$BACKUP_DIR/postgres/chaya-$ts.dump"
  echo "postgres: dumping $POSTGRES_DB -> $dump"
  compose exec -T postgres pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom --compress=6 > "$dump.partial"
  mv "$dump.partial" "$dump"
  sha256_of "$dump" > "$dump.sha256"
  # A dump that pg_restore cannot list is not a backup.
  compose exec -T postgres pg_restore --list < "$dump" > /dev/null
  echo "postgres: ok ($(du -h "$dump" | cut -f1), sha256 $(cat "$dump.sha256"))"
  find "$BACKUP_DIR/postgres" -name 'chaya-*.dump*' -mtime +"${BACKUP_KEEP_DAYS:-14}" -print -delete
fi

if [ "$what" = all ] || [ "$what" = minio ]; then
  : "${S3_BACKUP_ACCESS_KEY:?set S3_BACKUP_ACCESS_KEY}" "${S3_BACKUP_SECRET_KEY:?set S3_BACKUP_SECRET_KEY}"
  for bucket in "$S3_BUCKET_RAW" "$S3_BUCKET_DERIVED"; do
    echo "minio: mirroring $bucket"
    # An empty bucket (nothing yet, or only excluded pii/ objects) still gets its directory: restore.sh treats a
    # missing one as "never backed up" and refuses.
    mkdir -p "$BACKUP_DIR/minio/$bucket"
    # Additive (no --remove): a deletion in the live bucket, accidental or by anyone holding the API's credentials,
    # must not also delete the only other copy at the next run. PII staging prefixes are excluded outright, so purged
    # unblurred frames never reach the backup in the first place.
    mc_run src "$S3_BACKUP_ACCESS_KEY" "$S3_BACKUP_SECRET_KEY" \
      mirror --overwrite --exclude "*/pii/*" "src/$bucket" "/backup/$bucket"
  done
  # The one legitimate deletion the mirror no longer propagates: rejected uploads (for example infected files), which
  # validation deletes from the live bucket. Drop any copy a mirror took while validation was still running. Keys are
  # server-generated; anything not matching their layout is ignored rather than used as a path.
  compose exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At \
    -c "SELECT object_key FROM capture_media WHERE status = 'REJECTED'" | tr -d '\r' | while read -r key; do
    case "$key" in
      *..*|*//*) continue ;;
      org/*/venue/*/capture/*/raw/*) rm -f "$BACKUP_DIR/minio/$S3_BUCKET_RAW/$key" ;;
    esac
  done
  echo "$ts" > "$BACKUP_DIR/minio/LAST_MIRROR_UTC"
  echo "minio: ok"
fi
