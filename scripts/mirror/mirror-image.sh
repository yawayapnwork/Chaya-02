#!/usr/bin/env bash
# Publishes (or re-checks) an owned GHCR mirror of an upstream image, byte for byte (infra/mirror/README.md).
#
#   scripts/mirror/mirror-image.sh <minio|mc> [--verify-only] [SOURCE]
#
# SOURCE is a local Docker image holding the upstream manifest list (default: UPSTREAM@UPSTREAM_LIST_DIGEST, pulled
# if the upstream registry allows it). Docker must use the containerd image store (Docker Desktop's default), where an
# image's ID is its manifest-list digest. Pushing needs `docker login ghcr.io` with a token that has write:packages;
# that credential stays on the maintainer's machine and never enters the repository or CI.
#
# Nothing is rebuilt: the platform manifest the upstream list names for PLATFORM is pushed with its original config and
# layers, so the mirror's digest IS the upstream platform digest. Every step checks a SHA-256:
#   1. infra/mirror/<name>/upstream-manifest-list.json hashes to UPSTREAM_LIST_DIGEST and lists DIGEST for PLATFORM;
#      linux-amd64-manifest.json hashes to DIGEST;
#   2. SOURCE's ID is UPSTREAM_LIST_DIGEST;
#   3. after the push, the registry serves MIRROR@DIGEST with exactly the committed manifest bytes, and MIRROR:MIRROR_TAG
#      resolves to DIGEST.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

name=${1:?usage: mirror-image.sh <minio|mc> [--verify-only] [SOURCE]}
shift
verify_only=false
if [ "${1:-}" = "--verify-only" ]; then verify_only=true; shift; fi
dir="infra/mirror/$name"
# shellcheck source=/dev/null
. "$dir/image.env"
source_ref=${1:-$UPSTREAM@$UPSTREAM_LIST_DIGEST}
PY=$(command -v python3 || command -v python)
sha() { "$PY" -c 'import hashlib,sys; print("sha256:" + hashlib.sha256(sys.stdin.buffer.read()).hexdigest())'; }

echo "== 1. committed provenance ($dir)"
[ "$(sha < "$dir/upstream-manifest-list.json")" = "$UPSTREAM_LIST_DIGEST" ] || { echo "upstream-manifest-list.json is not $UPSTREAM_LIST_DIGEST"; exit 1; }
[ "$(sha < "$dir/linux-amd64-manifest.json")" = "$DIGEST" ] || { echo "linux-amd64-manifest.json is not $DIGEST"; exit 1; }
listed=$("$PY" - "$dir/upstream-manifest-list.json" "$PLATFORM" <<'EOF'
import json, sys
os_, arch = sys.argv[2].split("/")
doc = json.load(open(sys.argv[1], "rb"))
print(next(m["digest"] for m in doc["manifests"] if m["platform"]["os"] == os_ and m["platform"]["architecture"] == arch))
EOF
)
[ "$listed" = "$DIGEST" ] || { echo "the upstream list names $listed for $PLATFORM, not $DIGEST"; exit 1; }
echo "   $UPSTREAM:$UPSTREAM_TAG@$UPSTREAM_LIST_DIGEST lists $PLATFORM $DIGEST"

if ! $verify_only; then
  echo "== 2. source $source_ref"
  docker image inspect "$source_ref" >/dev/null 2>&1 || docker pull "$source_ref"
  id=$(docker image inspect --format '{{.Id}}' "$source_ref")
  [ "$id" = "$UPSTREAM_LIST_DIGEST" ] || { echo "source ID is $id, not $UPSTREAM_LIST_DIGEST (containerd image store required)"; exit 1; }
  echo "== pushing $MIRROR:$MIRROR_TAG ($PLATFORM only)"
  docker tag "$source_ref" "$MIRROR:$MIRROR_TAG"
  docker push --platform "$PLATFORM" "$MIRROR:$MIRROR_TAG"
fi

echo "== 3. registry check"
served=$(docker buildx imagetools inspect --raw "$MIRROR@$DIGEST")
[ "$(printf '%s' "$served" | sha)" = "$DIGEST" ] || { echo "the registry's manifest for $MIRROR@$DIGEST does not hash to $DIGEST"; exit 1; }
cmp -s <(printf '%s' "$served") "$dir/linux-amd64-manifest.json" || { echo "served manifest differs from the committed upstream bytes"; exit 1; }
tag_digest=$(docker buildx imagetools inspect --format '{{json .Manifest}}' "$MIRROR:$MIRROR_TAG" | "$PY" -c 'import json,sys; print(json.load(sys.stdin)["digest"])')
[ "$tag_digest" = "$DIGEST" ] || { echo "$MIRROR:$MIRROR_TAG resolves to $tag_digest, not $DIGEST"; exit 1; }
echo "OK: $MIRROR:$MIRROR_TAG@$DIGEST is byte-identical to $UPSTREAM:$UPSTREAM_TAG ($PLATFORM)"
