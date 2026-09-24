#!/usr/bin/env bash
# Assembles the files a deploy host needs into one directory, each from its single source in the repository.
#   scripts/deploy/bundle.sh <out-dir>
# Contents: compose file, Caddyfile, deploy/rollback + Keycloak configuration scripts, database and MinIO init scripts,
# the realm file, the backup scripts, and the environment template. No secrets: the host keeps its own .env.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT=${1:?usage: bundle.sh <out-dir>}
rm -rf "$OUT"
mkdir -p "$OUT/initdb" "$OUT/keycloak" "$OUT/scripts/backup"
cp "$ROOT/infra/deploy/docker-compose.yml" "$ROOT/infra/deploy/Caddyfile" "$ROOT/infra/deploy/deploy.sh" \
   "$ROOT/infra/deploy/keycloak-configure.sh" "$ROOT/infra/deploy/.env.production.example" "$OUT/"
cp "$ROOT/infra/docker/postgres/initdb/10-app-role.sh" "$OUT/initdb/"
cp "$ROOT/infra/deploy/initdb/20-keycloak-db.sh" "$OUT/initdb/"
cp "$ROOT/infra/docker/minio/init.sh" "$OUT/minio-init.sh"
cp "$ROOT/infra/keycloak/chaya-realm.json" "$OUT/keycloak/"
cp "$ROOT"/scripts/backup/*.sh "$OUT/scripts/backup/"
chmod +x "$OUT"/*.sh "$OUT"/scripts/backup/*.sh
git -C "$ROOT" rev-parse HEAD > "$OUT/BUNDLE_COMMIT" 2>/dev/null || true
echo "bundle: $OUT"
