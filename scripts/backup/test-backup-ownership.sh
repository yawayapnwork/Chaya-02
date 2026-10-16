#!/usr/bin/env bash
# Tests that the object mirror written by scripts/backup (mc_run, through the mc image, which runs as root) belongs to the
# user who ran the backup. backup.sh itself deletes rejected uploads and erased objects from the mirror as that user
# (docs/privacy-erasure.md); with root-owned files those deletions failed on a Linux host, and so did removing old backups
# (reconstruction-smoke's cleanup: "rm: Permission denied"). Needs Docker; no stack. Meaningful on Linux, where the
# owner of a bind-mounted file is enforced; Docker Desktop mounts pass trivially.
#
#   scripts/backup/test-backup-ownership.sh
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
WORK=$(mktemp -d)
printf 'POSTGRES_USER=postgres\nPOSTGRES_DB=chaya\n' > "$WORK/.env"
export ENV_FILE="$WORK/.env" BACKUP_DIR="$WORK/backups" COMPOSE_NETWORK=bridge
# shellcheck source=common.sh
. "$ROOT/scripts/backup/common.sh"
trap 'rm -rf "$PRIVATE_TMP"' EXIT
passed=0
failed=0
check() { local what=$1; shift; if "$@"; then passed=$((passed + 1)); else failed=$((failed + 1)); echo "FAIL: $what"; fi; }
me="$(id -u):$(id -g)"
owned_by_me() { [ -z "$(find "$1" ! -user "$(id -u)" -print -quit)" ]; }

# A mirror an earlier version left behind: written by root.
docker run --rm -v "$(native_path "$BACKUP_DIR/minio"):/backup" --entrypoint sh "$MC_IMAGE" \
  -c 'mkdir -p /backup/chaya-raw/org/o1/venue/v1/capture/c0/raw && echo old > /backup/chaya-raw/org/o1/venue/v1/capture/c0/raw/old.mp4'

key=org/o1/venue/v1/capture/c1/raw/walk.mp4
mc_out=$(mc_run src unused unused cp /etc/hostname "/backup/chaya-raw/$key" 2>&1) || true
check "a mirrored object exists" [ -f "$BACKUP_DIR/minio/chaya-raw/$key" ]
[ -f "$BACKUP_DIR/minio/chaya-raw/$key" ] || echo "    mc said: $mc_out"
check "the mirror belongs to the invoking user ($me), including the part an earlier version wrote as root" owned_by_me "$BACKUP_DIR/minio"
check "an erased object can be removed from the mirror (backup.sh, erasure_object)" rm -f "$BACKUP_DIR/minio/chaya-raw/$key"
check "an erased prefix can be removed from the mirror" rm -rf "$BACKUP_DIR/minio/chaya-raw/org/o1/venue/v1/capture/c0"
set +e; mc_run src unused unused cp /does/not/exist /backup/x > /dev/null 2>&1; rc=$?; set -e
check "mc's failure is still reported (exit $rc)" [ "$rc" -ne 0 ]
check "the whole backup directory can be removed" rm -rf "$WORK"

echo "backup ownership tests: $passed passed, $failed failed"
[ "$failed" -eq 0 ]
