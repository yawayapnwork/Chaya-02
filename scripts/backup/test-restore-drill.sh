#!/usr/bin/env bash
# Tests that scripts/backup/restore-drill.sh FAILS on a bad backup, not only that it passes on a good one. Builds a small
# synthetic backup (a pg_dump of a minimal schema with the columns the drill reads, and an object mirror whose files match
# the recorded SHA-256), then runs the drill four times: intact (must pass), one object corrupted, one object missing, and
# a dump that no longer matches its .sha256 (each must fail). Needs Docker; touches no stack (no API image: step 6 skips).
#
#   scripts/backup/test-restore-drill.sh
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
export MSYS_NO_PATHCONV=1
native() { if command -v cygpath > /dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
PG_IMAGE=${DRILL_POSTGRES_IMAGE:-pgvector/pgvector:pg17}
WORK=$(mktemp -d)
SRC="test-drill-src-$$"
cleanup() { docker rm -f -v "$SRC" > /dev/null 2>&1 || true; rm -rf "$WORK"; }
trap cleanup EXIT

cat > "$WORK/.env" <<EOF
POSTGRES_USER=postgres
POSTGRES_DB=chaya
POSTGRES_APP_USER=chaya_app
S3_BUCKET_RAW=chaya-raw
S3_BUCKET_DERIVED=chaya-derived
EOF
B="$WORK/backups"
mkdir -p "$B/postgres" "$B/minio/chaya-raw/org/o1/venue/v1/capture/c1/raw" "$B/minio/chaya-derived/sealed/org/o1/venue/v1/run/a"
printf 'raw video bytes' > "$B/minio/chaya-raw/org/o1/venue/v1/capture/c1/raw/walk.mp4"
printf 'ksplat bytes' > "$B/minio/chaya-derived/sealed/org/o1/venue/v1/run/a/scene.ksplat"
RAW_SHA=$(sha256sum "$B/minio/chaya-raw/org/o1/venue/v1/capture/c1/raw/walk.mp4" | cut -d' ' -f1)
ART_SHA=$(sha256sum "$B/minio/chaya-derived/sealed/org/o1/venue/v1/run/a/scene.ksplat" | cut -d' ' -f1)

echo "== building a synthetic backup"
docker run -d --name "$SRC" -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=x -e POSTGRES_DB=chaya "$PG_IMAGE" > /dev/null
for i in $(seq 1 60); do docker exec "$SRC" pg_isready -U postgres -d chaya > /dev/null 2>&1 && break; [ "$i" -eq 60 ] && exit 1; sleep 2; done
sleep 2
docker exec -i "$SRC" psql -q -v ON_ERROR_STOP=1 -U postgres -d chaya > /dev/null <<SQL
CREATE ROLE chaya_app LOGIN PASSWORD 'x';
ALTER SCHEMA public OWNER TO chaya_app;   -- as infra/docker/postgres/initdb/10-app-role.sh does
SET ROLE chaya_app;
CREATE TABLE flyway_schema_history (installed_rank int PRIMARY KEY, version text, success boolean NOT NULL);
INSERT INTO flyway_schema_history VALUES (1, '1', true), (2, '34', true);
CREATE TABLE organization (id int);  CREATE TABLE venue (id int);  CREATE TABLE capture_session (id int);
CREATE TABLE processing_job (id int, status text);  CREATE TABLE pipeline_run (id int);  CREATE TABLE scan_version (id int);
CREATE TABLE audit_log (id int);
CREATE TABLE capture_media (bucket text, object_key text, verified_sha256 text, status text);
CREATE TABLE processing_artifact (bucket text, object_key text, checksum_sha256 text, contains_pii boolean);
INSERT INTO capture_media VALUES ('chaya-raw', 'org/o1/venue/v1/capture/c1/raw/walk.mp4', '$RAW_SHA', 'ACCEPTED'),
                                 ('chaya-raw', 'org/o1/venue/v1/capture/c1/raw/infected.mp4', 'f00d', 'REJECTED');
INSERT INTO processing_artifact VALUES ('chaya-derived', 'sealed/org/o1/venue/v1/run/a/scene.ksplat', '$ART_SHA', false),
                                       ('chaya-derived', 'org/o1/venue/v1/run/a/pii/frame.jpg', 'beef', true);
SQL
DUMP="$B/postgres/chaya-20261011T000000Z.dump"
docker exec "$SRC" pg_dump -U postgres -d chaya --format=custom > "$DUMP"
sha256sum "$DUMP" | cut -d' ' -f1 > "$DUMP.sha256"

passed=0
failed=0
drill() { # expectation description
  local expect=$1 what=$2 rc
  set +e
  out=$(ENV_FILE=$(native "$WORK/.env") BACKUP_DIR=$(native "$B") COMPOSE_PROJECT_NAME="test-drill-none-$$" DRILL_API_IMAGE='' \
    bash "$ROOT/scripts/backup/restore-drill.sh" "$(native "$DUMP")" 2>&1)
  rc=$?
  set -e
  if { [ "$expect" = pass ] && [ $rc -eq 0 ]; } || { [ "$expect" = fail ] && [ $rc -ne 0 ]; }; then
    passed=$((passed + 1)); echo "ok: $what"
  else
    failed=$((failed + 1)); echo "FAIL: $what (exit $rc)"; echo "$out" | sed 's/^/    | /' | tail -20
  fi
}
leftovers() { docker ps -a --format '{{.Names}}' | grep -c '^chaya-drill-' || true; }

drill pass "an intact backup restores and every referenced object matches"
echo "$out" | grep -q "referenced objects: 2, checked: 2, missing or corrupt: 0" \
  && { passed=$((passed + 1)); echo "ok: exactly the accepted media and non-PII artifact were checked"; } \
  || { failed=$((failed + 1)); echo "FAIL: unexpected object count"; echo "$out" | tail -8; }

cp "$B/minio/chaya-derived/sealed/org/o1/venue/v1/run/a/scene.ksplat" "$WORK/keep"
printf 'tampered' > "$B/minio/chaya-derived/sealed/org/o1/venue/v1/run/a/scene.ksplat"
drill fail "a corrupted object fails the drill"
echo "$out" | grep -q "MISSING OR CORRUPT chaya-derived/sealed/org/o1/venue/v1/run/a/scene.ksplat" \
  && { passed=$((passed + 1)); echo "ok: the corrupted object is named"; } || { failed=$((failed + 1)); echo "FAIL: corrupted object not named"; }
cp "$WORK/keep" "$B/minio/chaya-derived/sealed/org/o1/venue/v1/run/a/scene.ksplat"

rm "$B/minio/chaya-raw/org/o1/venue/v1/capture/c1/raw/walk.mp4"
drill fail "a missing object fails the drill"
printf 'raw video bytes' > "$B/minio/chaya-raw/org/o1/venue/v1/capture/c1/raw/walk.mp4"

printf 'x' >> "$DUMP"
drill fail "a dump that does not match its checksum is refused"

[ "$(leftovers)" = 0 ] && { passed=$((passed + 1)); echo "ok: no drill container left behind"; } \
  || { failed=$((failed + 1)); echo "FAIL: drill containers left behind"; }
echo "restore drill tests: $passed passed, $failed failed"
[ "$failed" -eq 0 ]
