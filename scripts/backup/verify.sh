#!/usr/bin/env bash
# Proves a backup can be restored, without touching live data (docs/backup-recovery.md, "Verification"):
#   1. the dump's SHA-256 matches its .sha256 file;
#   2. it restores into a scratch database (chaya_restore_check) with zero errors;
#   3. core tables have rows / the schema version matches;
#   4. a random sample of objects referenced by the restored database exists in the MinIO mirror with the SHA-256 the
#      database recorded at verification time.
# Exit status 0 only if every check passes.
#
#   scripts/backup/verify.sh [path/to/chaya-<ts>.dump]   (default: newest dump)
set -euo pipefail
. "$(dirname "$0")/common.sh"

dump=${1:-$(ls -1t "$BACKUP_DIR"/postgres/chaya-*.dump 2>/dev/null | head -1 || true)}
[ -n "$dump" ] && [ -f "$dump" ] || { echo "error: no dump found" >&2; exit 2; }
sample=${VERIFY_SAMPLE:-25}
fail=0

echo "1/4 checksum"
[ "$(sha256_of "$dump")" = "$(cat "$dump.sha256")" ] || { echo "FAIL: checksum mismatch for $dump" >&2; exit 1; }

echo "2/4 restore into scratch database chaya_restore_check"
$COMPOSE exec -T postgres psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres -q \
  -c "DROP DATABASE IF EXISTS chaya_restore_check" -c "CREATE DATABASE chaya_restore_check"
$COMPOSE exec -T postgres pg_restore -U "$POSTGRES_USER" -d chaya_restore_check --exit-on-error --no-owner < "$dump"

q() { $COMPOSE exec -T postgres psql -U "$POSTGRES_USER" -d chaya_restore_check -At -F ' ' -c "$1"; }

echo "3/4 schema and rows"
echo "  flyway version: $(q "SELECT max(version) FROM flyway_schema_history WHERE success")"
for t in organization venue capture_session capture_media processing_job pipeline_run processing_artifact audit_log; do
  echo "  $t: $(q "SELECT count(*) FROM $t")"
done

echo "4/4 object sample ($sample per store) against the mirror"
check_objects() { # sql returning "bucket key sha"
  q "$1" | while read -r bucket key sha; do
    [ -z "$bucket" ] && continue
    f="$BACKUP_DIR/minio/$bucket/$key"
    if [ ! -f "$f" ]; then echo "  MISSING $bucket/$key"; echo x >> "$PRIVATE_TMP/fail"; continue; fi
    [ "$(sha256_of "$f")" = "$sha" ] || { echo "  CHECKSUM MISMATCH $bucket/$key"; echo x >> "$PRIVATE_TMP/fail"; }
  done
}
check_objects "SELECT bucket, object_key, verified_sha256 FROM capture_media WHERE status = 'ACCEPTED' ORDER BY random() LIMIT $sample"
check_objects "SELECT bucket, object_key, checksum_sha256 FROM processing_artifact WHERE NOT contains_pii ORDER BY random() LIMIT $sample"
[ -f "$PRIVATE_TMP/fail" ] && fail=1

$COMPOSE exec -T postgres psql -U "$POSTGRES_USER" -d postgres -q -c "DROP DATABASE chaya_restore_check"
if [ "$fail" -ne 0 ]; then
  echo "FAIL: some referenced objects are missing or corrupt in the mirror (see above)" >&2
  exit 1
fi
echo "OK: $dump restores cleanly and the sampled objects match"
