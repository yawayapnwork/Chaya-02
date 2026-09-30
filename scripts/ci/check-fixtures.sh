#!/usr/bin/env bash
# Committed test fixtures really are committed (packages/contracts/fixtures/README.md).
#
#   scripts/ci/check-fixtures.sh        (from anywhere inside the repository)
#
# Fails when
#   1. a file under a fixture directory exists but is git-ignored or untracked: it would make tests pass on this machine
#      and fail on a clean checkout (how the .ksplat/.ply fixtures went missing from CI before 2026-09-30);
#   2. a fixture a test loads is not in the index (on a clean checkout: it is missing, and the tests would fail with a
#      bare ENOENT/FileNotFoundError instead of this message);
#   3. a file listed in viewer-scene/fixture.json "files" does not match its recorded SHA-256.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

DIRS=(packages/contracts/fixtures services/reconstruction/tests/fixtures)
REQUIRED=(
  packages/contracts/fixtures/ksplat/cloud.json
  packages/contracts/fixtures/ksplat/scene.ksplat
  packages/contracts/fixtures/ksplat/scene.ply
  packages/contracts/fixtures/viewer-scene/fixture.json
  packages/contracts/fixtures/viewer-scene/scene.ksplat
  packages/contracts/fixtures/viewer-scene/scene.ply
  packages/contracts/fixtures/navmesh/navmesh.bin
  packages/contracts/fixtures/navmesh/navigation-graph.json
  packages/contracts/fixtures/navmesh/navmesh-manifest.json
  packages/contracts/fixtures/synthetic-calibration.json
  packages/contracts/fixtures/search/clip-vit-b32-openai.json
  services/reconstruction/tests/fixtures/navmesh/room_with_doorway.obj
)
fail=0

stray=$( (git ls-files --others --exclude-standard -- "${DIRS[@]}"
          git ls-files --others --ignored --exclude-standard -- "${DIRS[@]}") | grep -v -e '__pycache__/' -e '\.pyc$' || true)
if [ -n "$stray" ]; then
  echo "::error::fixture files present here but not committed (a clean checkout will not have them):"
  echo "$stray" | sed 's/^/  /'
  fail=1
fi

for f in "${REQUIRED[@]}"; do
  if ! git ls-files --error-unmatch -- "$f" >/dev/null 2>&1; then
    echo "::error::required test fixture is not committed: $f"
    fail=1
  fi
done

PY=$(command -v python3 || command -v python)
"$PY" - packages/contracts/fixtures/viewer-scene <<'EOF' || fail=1
import hashlib, json, sys
from pathlib import Path
d = Path(sys.argv[1])
bad = 0
for name, meta in json.loads((d / "fixture.json").read_text(encoding="utf-8"))["files"].items():
    f = d / name
    actual = hashlib.sha256(f.read_bytes()).hexdigest() if f.is_file() else "missing"
    if actual != meta["sha256"]:
        print(f"::error::{f.as_posix()} SHA-256 {actual}, fixture.json records {meta['sha256']}")
        bad = 1
sys.exit(bad)
EOF

[ "$fail" -eq 0 ] && echo "fixtures: ${#REQUIRED[@]} required files committed, none ignored or untracked, checksums match"
exit "$fail"
