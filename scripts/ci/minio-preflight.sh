#!/usr/bin/env bash
# CI preflight for the MinIO images (infra/mirror/README.md). Run before anything that needs MinIO
# (backend.yml, reconstruction-smoke.yml stack-smoke).
#
#   scripts/ci/minio-preflight.sh
#
# For the server (minio) and client (mc) mirrors:
#   1. every consumer in the repository pins MIRROR@DIGEST, and none still names the unpullable quay.io/docker.io image;
#   2. any local copy is removed first, then the image is pulled from the registry by digest -- this proves the registry
#      serves it, it does not accept a cached copy;
#   3. the pulled image carries the expected digest;
#   4. the server starts, answers /minio/health/ready, and the client image reaches it (`mc ready`).
# Any failure is a clear ::error:: naming the image. No credentials are used: the mirrors are public packages.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

load() { # prints MIRROR@DIGEST for infra/mirror/$1
  ( . "infra/mirror/$1/image.env"; echo "$MIRROR@$DIGEST" )
}
MINIO=$(load minio)
MC=$(load mc)
fail() { echo "::error::$*"; exit 1; }

echo "== 1. consumers pin the mirrors"
CONSUMERS=(
  services/api/src/test/java/dev/chaya/api/AbstractIntegrationTest.java
  infra/docker/docker-compose.yml
  infra/deploy/docker-compose.yml
  scripts/backup/common.sh
)
for f in "${CONSUMERS[@]}"; do
  for ref in "$MINIO" "$MC"; do
    repo=${ref%@*} digest=${ref#*@}
    case "$f:$repo" in *AbstractIntegrationTest.java:*/mc|*common.sh:*/minio) continue;; esac
    grep -Eq "${repo//./\\.}(:[A-Za-z0-9._-]+)?@$digest" "$f" || fail "$f does not pin $ref"
  done
done
# Comments may name the upstream as provenance; code and configuration may not use it.
if grep -nE '(quay\.io|docker\.io)/minio/|"minio/(minio|mc)|image: minio/' "${CONSUMERS[@]}" .github/workflows/*.yml \
     | grep -vE '^[^:]+:[0-9]+:[[:space:]]*(//|#|\*)'; then
  fail "the files above still reference the upstream MinIO registries, which refuse anonymous pulls"
fi

for ref in "$MINIO" "$MC"; do
  echo "== 2. pull $ref from the registry (local copies removed first)"
  docker image rm -f "$ref" >/dev/null 2>&1 || true
  docker image inspect "$ref" >/dev/null 2>&1 && fail "$ref is still cached after removal; cannot prove the registry serves it"
  docker pull "$ref" || fail "cannot pull $ref. Is the GHCR package public and published? See infra/mirror/README.md"
  echo "== 3. digest"
  digests=$(docker image inspect --format '{{join .RepoDigests " "}} {{.Id}}' "$ref")
  case " $digests " in *"${ref#*@}"*) ;; *) fail "$ref pulled, but the image reports [$digests]";; esac
done

echo "== 4. start MinIO and reach it with mc"
name=chaya-minio-preflight-$$
trap 'docker rm -f "$name" >/dev/null 2>&1 || true' EXIT
docker run -d --name "$name" -e MINIO_ROOT_USER=preflight -e MINIO_ROOT_PASSWORD=preflight-secret \
  "$MINIO" server /data >/dev/null
mc() { docker run --rm --network "container:$name" -e MC_HOST_local=http://preflight:preflight-secret@127.0.0.1:9000 "$MC" "$@"; }
ready=false
for i in $(seq 1 60); do
  if mc ready local >/dev/null 2>&1; then ready=true; break; fi
  sleep 1
done
if ! $ready; then docker logs "$name" 2>&1 | tail -20; fail "$MINIO did not become ready within 60 s"; fi
# Readiness alone is not S3: make a bucket and list it.
mc mb local/preflight >/dev/null || fail "mc could not create a bucket on $MINIO"
mc ls local | grep -q preflight || fail "the bucket just created is not listed"
docker exec "$name" minio --version | head -1
echo "OK: $MINIO ready after ${i}s and serving S3; $MC reaches it"
