#!/usr/bin/env bash
# Restore drill (docs/backup-recovery.md, "Restore drill"): proves a backup restores into an ISOLATED environment, not
# just that a backup command succeeded. Nothing live is read or written except the backup files themselves.
#
#   scripts/backup/restore-drill.sh [path/to/chaya-<ts>.dump]      (default: newest dump in $BACKUP_DIR)
#
# 1. checks the dump's SHA-256 against its .sha256 file;
# 2. starts a throwaway PostgreSQL and MinIO (the same images as the live stack when it is running) on a new
#    --internal Docker network: no published ports, no route out, random credentials, anonymous volumes;
# 3. restores the dump with its original owners (the roles it names are created with random passwords) and the
#    object mirror into fresh buckets, timing both;
# 4. checks the restored schema (no failed migration) and that EVERY object the restored database references (accepted
#    raw media, non-PII artifacts; DRILL_SAMPLE=<n> checks a random sample instead) exists in the restored MinIO with the
#    SHA-256 the database recorded when the object was verified;
# 5. if an API image is known (DRILL_API_IMAGE, or the live stack's api container), starts the API on the restored data:
#    Flyway validates (and applies any newer migrations to) the restored schema and Hibernate validates the mapping, so a
#    pass means the application can run on this backup. The identity provider is deliberately absent in the drill, so the
#    API's own health is DOWN for that reason only; liveness is what is checked;
# 6. removes every container, the network and the volumes, also on failure.
#
# DRILL_DB_ONLY=1 restores and checks the database only (empty buckets are created so the API can boot). deploy.sh uses
# it with the version about to be deployed as a migration rehearsal on the pre-deploy dump: a migration that fails on
# production data then fails there, before anything live changes.
#
# Exit 0 only if every check passes. A report without secrets is written to $BACKUP_DIR/drills/.
set -euo pipefail
. "$(dirname "$0")/common.sh"

dump=${1:-$(ls -1t "$BACKUP_DIR"/postgres/chaya-*.dump 2>/dev/null | head -1 || true)}
[ -n "$dump" ] && [ -f "$dump" ] || { echo "error: no dump found" >&2; exit 2; }
SAMPLE=${DRILL_SAMPLE:-all}
case "$SAMPLE" in all|[1-9]*) ;; *) echo "error: DRILL_SAMPLE must be 'all' or a positive number" >&2; exit 2 ;; esac
API_BOOT_TIMEOUT=${DRILL_API_BOOT_TIMEOUT:-300}
DB_ONLY=${DRILL_DB_ONLY:-}

id="chaya-drill-$(now_utc | tr 'A-Z' 'a-z')-$$"
mkdir -p "$BACKUP_DIR/drills"
report="$BACKUP_DIR/drills/$id.txt"
log() { echo "[$(date -u +%H:%M:%S)] $*" | tee -a "$report"; }
fail() { log "FAIL: $*"; exit 1; }
secret() { head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n'; }

cleanup() {
  status=$?
  docker rm -f -v "$id-api" "$id-pg" "$id-minio" > /dev/null 2>&1 || true
  docker network rm "$id" > /dev/null 2>&1 || true
  if [ $status -eq 0 ]; then log "RESULT: PASS ($dump)"; else log "RESULT: FAIL ($dump)"; fi
  rm -rf "$PRIVATE_TMP"
  exit $status
}
trap cleanup EXIT

# The images the live stack runs, so the drill restores into the same PostgreSQL/pgvector and MinIO versions.
image_of() { local cid; cid=$(compose ps -q "$1" 2>/dev/null | head -1 || true); [ -n "$cid" ] && docker inspect -f '{{.Config.Image}}' "$cid" 2>/dev/null || true; }
PG_IMAGE=${DRILL_POSTGRES_IMAGE:-$(image_of postgres)}
PG_IMAGE=${PG_IMAGE:-pgvector/pgvector:pg17}
MINIO_IMAGE=${DRILL_MINIO_IMAGE:-$(image_of minio)}
MINIO_IMAGE=${MINIO_IMAGE:-ghcr.io/yawayapnwork/mirror/minio:RELEASE.2025-09-07T16-13-09Z-linux-amd64@sha256:a1a8bd4ac40ad7881a245bab97323e18f971e4d4cba2c2007ec1bedd21cbaba2}
API_IMAGE=${DRILL_API_IMAGE:-$(image_of api)}

log "restore drill $id"
log "dump: $dump ($(du -h "$dump" | cut -f1))"
log "images: postgres=$PG_IMAGE minio=$MINIO_IMAGE api=${API_IMAGE:-<none: API boot check skipped>}"

log "1/6 checksum"
[ -f "$dump.sha256" ] || fail "no $dump.sha256"
[ "$(sha256_of "$dump")" = "$(cat "$dump.sha256")" ] || fail "checksum mismatch"

log "2/6 isolated environment (internal network $id, no published ports)"
docker network create --internal "$id" > /dev/null
PG_SUPER=${POSTGRES_USER}
PG_SUPER_PW=$(secret)
docker run -d --name "$id-pg" --network "$id" --network-alias postgres \
  -e POSTGRES_USER="$PG_SUPER" -e POSTGRES_PASSWORD="$PG_SUPER_PW" -e POSTGRES_DB=postgres "$PG_IMAGE" > /dev/null
MINIO_USER=drill
MINIO_PW=$(secret)
docker run -d --name "$id-minio" --network "$id" --network-alias minio \
  -e MINIO_ROOT_USER="$MINIO_USER" -e MINIO_ROOT_PASSWORD="$MINIO_PW" "$MINIO_IMAGE" server /data > /dev/null
for i in $(seq 1 60); do
  docker exec "$id-pg" pg_isready -U "$PG_SUPER" -d postgres > /dev/null 2>&1 && break
  [ "$i" -eq 60 ] && fail "drill postgres did not start"; sleep 2
done
for i in $(seq 1 60); do
  docker exec "$id-minio" mc ready local > /dev/null 2>&1 && break
  [ "$i" -eq 60 ] && fail "drill minio did not start"; sleep 2
done

pg() { docker exec -i "$id-pg" "$@"; }
dq() { pg psql -v ON_ERROR_STOP=1 -U "$PG_SUPER" -d "$POSTGRES_DB" -At -F ' ' -c "$1" | tr -d '\r'; }

log "3/6 restore database and objects"
# The roles that own objects in the dump, recreated (with random passwords) so the restore keeps the original owners.
owners=$(pg pg_restore -s -f - < "$dump" | sed -n 's/.*OWNER TO \([^;]*\);.*/\1/p' | tr -d '"\r' | sort -u)
APP_PW=$(secret)
for role in $owners; do
  [ "$role" = "$PG_SUPER" ] && continue
  case "$role" in *[!A-Za-z0-9_]*) fail "unexpected role name in dump: $role" ;; esac
  pg psql -q -v ON_ERROR_STOP=1 -U "$PG_SUPER" -d postgres -c "CREATE ROLE \"$role\" LOGIN NOSUPERUSER PASSWORD '$APP_PW'" > /dev/null
done
db_owner=${POSTGRES_APP_USER:-$PG_SUPER}
echo "$owners" | grep -qx "$db_owner" || db_owner=$PG_SUPER
t0=$SECONDS
pg psql -q -v ON_ERROR_STOP=1 -U "$PG_SUPER" -d postgres -c "CREATE DATABASE \"$POSTGRES_DB\" OWNER \"$db_owner\"" > /dev/null
pg psql -q -v ON_ERROR_STOP=1 -U "$PG_SUPER" -d "$POSTGRES_DB" -c "CREATE EXTENSION IF NOT EXISTS vector" -c "CREATE EXTENSION IF NOT EXISTS pg_trgm" > /dev/null
pg pg_restore -U "$PG_SUPER" -d "$POSTGRES_DB" --exit-on-error < "$dump" || fail "pg_restore reported errors"
db_seconds=$((SECONDS - t0))
log "  database restored in ${db_seconds}s"

envf="$PRIVATE_TMP/drill-mc.env"
umask 077
printf 'MC_HOST_drill=http://%s:%s@minio:9000\n' "$MINIO_USER" "$MINIO_PW" > "$envf"
drill_mc() { docker run --rm --network "$id" --env-file "$(native_path "$envf")" -v "$(native_path "$BACKUP_DIR/minio"):/backup:ro" \
  -v "$(native_path "$PRIVATE_TMP"):/work:ro" --entrypoint /bin/sh "$MC_IMAGE" -c "$1"; }
t0=$SECONDS
for bucket in "$S3_BUCKET_RAW" "$S3_BUCKET_DERIVED"; do
  if [ -n "$DB_ONLY" ]; then
    drill_mc "mc mb --ignore-existing drill/$bucket > /dev/null" || fail "creating bucket $bucket failed"
    continue
  fi
  [ -d "$BACKUP_DIR/minio/$bucket" ] || fail "no object mirror for $bucket in $BACKUP_DIR/minio"
  drill_mc "mc mb --ignore-existing drill/$bucket > /dev/null && mc mirror --quiet /backup/$bucket drill/$bucket > /dev/null" \
    || fail "restoring objects into $bucket failed"
done
obj_seconds=$((SECONDS - t0))
if [ -n "$DB_ONLY" ]; then log "  objects: not restored (DRILL_DB_ONLY)"; else log "  objects restored in ${obj_seconds}s"; fi

log "4/6 restored schema and data"
failed_migrations=$(dq "SELECT count(*) FROM flyway_schema_history WHERE NOT success")
schema=$(dq "SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1")
code_schema=$(ls "$REPO_ROOT/services/api/src/main/resources/db/migration" 2>/dev/null | sed -n 's/^V\([0-9]*\)__.*/\1/p' | sort -n | tail -1)
log "  flyway version $schema (this checkout's newest migration: ${code_schema:-unknown}); failed migrations: $failed_migrations"
[ "$failed_migrations" = 0 ] || fail "the restored schema records a failed migration"
for t in organization venue capture_session capture_media processing_job pipeline_run processing_artifact scan_version audit_log; do
  log "  $t: $(dq "SELECT count(*) FROM $t")"
done
stuck=$(dq "SELECT count(*) FROM processing_job WHERE status = 'RUNNING'")
[ "$stuck" = 0 ] || log "  note: $stuck job(s) were RUNNING when the dump was taken; after a real restore the lease enforcer fails them as WORKER_LOST"

log "5/6 every referenced object, by SHA-256, in the restored MinIO"
if [ -n "$DB_ONLY" ]; then
  log "  SKIPPED (DRILL_DB_ONLY): objects are not part of this check"
else
limit=""
[ "$SAMPLE" = all ] || limit="ORDER BY random() LIMIT $SAMPLE"
{
  dq "SELECT bucket, object_key, verified_sha256 FROM capture_media WHERE status = 'ACCEPTED' $limit"
  dq "SELECT bucket, object_key, checksum_sha256 FROM processing_artifact WHERE NOT contains_pii $limit"
} > "$PRIVATE_TMP/objects.txt"
referenced=$(grep -c . "$PRIVATE_TMP/objects.txt" || true)
result=$(drill_mc 'n=0; bad=0
while read -r b k s; do
  [ -z "$b" ] && continue; n=$((n+1))
  got=$(mc cat "drill/$b/$k" 2>/dev/null | sha256sum | cut -d" " -f1)
  if [ "$got" != "$s" ]; then bad=$((bad+1)); echo "  MISSING OR CORRUPT $b/$k"; fi
done < /work/objects.txt
echo "checked=$n bad=$bad"') || true
echo "$result" | tee -a "$report" | grep -v '^checked=' || true
checked=$(echo "$result" | sed -n 's/^checked=\([0-9]*\) .*/\1/p')
bad=$(echo "$result" | sed -n 's/.* bad=\([0-9]*\)$/\1/p')
log "  referenced objects: $referenced, checked: ${checked:-0}, missing or corrupt: ${bad:-unknown}"
[ "${checked:-}" = "$referenced" ] && [ "${bad:-1}" = 0 ] || fail "objects referenced by the restored database are missing or corrupt"
fi

log "6/6 application boot on the restored data"
if [ -z "$API_IMAGE" ]; then
  log "  SKIPPED: no API image (set DRILL_API_IMAGE). The restore is verified at the data level only."
else
  [ "$db_owner" != "$PG_SUPER" ] || fail "the dump has no application role owner to connect as"
  t0=$SECONDS
  docker run -d --name "$id-api" --network "$id" --read-only --tmpfs /tmp \
    -e POSTGRES_HOST=postgres -e POSTGRES_PORT=5432 -e POSTGRES_DB="$POSTGRES_DB" \
    -e POSTGRES_APP_USER="$db_owner" -e POSTGRES_APP_PASSWORD="$APP_PW" \
    -e S3_ENDPOINT=http://minio:9000 -e S3_ACCESS_KEY="$MINIO_USER" -e S3_SECRET_KEY="$MINIO_PW" \
    -e S3_BUCKET_RAW="$S3_BUCKET_RAW" -e S3_BUCKET_DERIVED="$S3_BUCKET_DERIVED" -e CLAMAV_ENABLED=false \
    -e KEYCLOAK_ISSUER_URI=http://identity-provider.invalid/realms/chaya "$API_IMAGE" > /dev/null
  booted=""
  while [ $((SECONDS - t0)) -lt "$API_BOOT_TIMEOUT" ]; do
    [ "$(docker inspect -f '{{.State.Running}}' "$id-api" 2>/dev/null)" = true ] || break
    if docker exec "$id-api" wget -q -O /dev/null http://127.0.0.1:8080/actuator/health/liveness > /dev/null 2>&1; then booted=1; break; fi
    sleep 3
  done
  if [ -z "$booted" ]; then
    docker logs --tail 60 "$id-api" 2>&1 | grep -E "ERROR|Exception|FlywayException|Caused by" | head -20 | tee -a "$report" || true
    fail "the API did not start on the restored data within ${API_BOOT_TIMEOUT}s"
  fi
  after=$(dq "SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1")
  log "  API started in $((SECONDS - t0))s; flyway validated the schema (now version $after)"
  [ "$(dq "SELECT count(*) FROM flyway_schema_history WHERE NOT success")" = 0 ] || fail "a migration failed on the restored data"
fi

log "timings: database ${db_seconds}s, objects ${obj_seconds}s (recovery-time evidence for this data size)"
log "report: $report"
