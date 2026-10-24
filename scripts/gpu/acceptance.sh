#!/usr/bin/env bash
# GPU acceptance on a CUDA host: build the GPU worker image, prove it can train, and run a genuine reconstruction of a
# real CC0 dataset through the production SPLAT_RECONSTRUCTION and ARTIFACT_GENERATION code (docs/pipeline.md, "GPU
# acceptance"). Every step writes its evidence to $OUT; nothing is summarised as passed unless it ran.
#
#   bash scripts/gpu/acceptance.sh [out dir]          (default: ./gpu-acceptance-<UTC time>)
#
# Host: an NVIDIA GPU of compute capability >= 7.0, a driver >= 560.28 (CUDA 12.6), Docker with the NVIDIA Container
# Toolkit, >= 40 GB free disk for the build, internet access (the CUDA bases, the pinned wheels, ~760 MB of photos).
# Environment: IMAGE (default chaya-worker-gpu:<git sha>), SKIP_BUILD=1 (use an existing IMAGE), COLMAP_THREADS (4),
#              MIN_FREE_DISK_GB (40), DATA (dataset cache, default $OUT/data).
# Exit: 0 every step passed; 1 a step failed (see $OUT/steps.tsv); 3 BLOCKED: this host cannot run it (no GPU, no
# container toolkit, not enough disk). A BLOCKED or failed run is never GPU evidence.
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
SHA=$(git -C "$ROOT" rev-parse --short=12 HEAD 2>/dev/null || echo unknown)
OUT=${1:-$PWD/gpu-acceptance-$(date -u +%Y%m%dT%H%M%SZ)}
IMAGE=${IMAGE:-chaya-worker-gpu:$SHA}
DATA=${DATA:-$OUT/data}
mkdir -p "$OUT" "$DATA"
OUT=$(cd "$OUT" && pwd); DATA=$(cd "$DATA" && pwd)
STEPS="$OUT/steps.tsv"
printf 'step\tstatus\tseconds\tevidence\n' > "$STEPS"

step() { # step <name> <evidence file> <command...>: runs it, records status and duration
  local name=$1 evidence=$2; shift 2
  local t0=$SECONDS
  echo "== $name"
  "$@" > "$OUT/$evidence" 2>&1
  local status=$?
  printf '%s\t%s\t%s\t%s\n' "$name" "$([ $status -eq 0 ] && echo PASS || echo "FAIL($status)")" "$((SECONDS - t0))" "$evidence" >> "$STEPS"
  tail -n 5 "$OUT/$evidence"
  return $status
}

blocked() {
  printf '%s\tBLOCKED\t0\t%s\n' "$1" "$2" >> "$STEPS"
  echo "BLOCKED: $2" | tee "$OUT/BLOCKED"
  exit 3
}

{ echo "commit $SHA"; date -u +%Y-%m-%dT%H:%M:%SZ; uname -a; docker version --format '{{.Server.Version}}' 2>&1; } > "$OUT/host.txt"

# 1. The host: a GPU, the container toolkit, disk. Each missing prerequisite is BLOCKED, never a failed test.
command -v nvidia-smi > /dev/null || blocked host "nvidia-smi not found: no NVIDIA driver on this host"
nvidia-smi --query-gpu=name,driver_version,compute_cap,memory.total,memory.free --format=csv > "$OUT/nvidia-smi.csv" 2>&1 \
  || blocked host "nvidia-smi failed: $(head -c 300 "$OUT/nvidia-smi.csv")"
free_gb=$(df -Pk "$OUT" | awk 'NR==2 {print int($4 / 1048576)}')
[ "${SKIP_BUILD:-}" = 1 ] || [ "$free_gb" -ge "${MIN_FREE_DISK_GB:-40}" ] \
  || blocked host "only ${free_gb} GB free under $OUT; the GPU image build needs ${MIN_FREE_DISK_GB:-40} GB"

# 2. The image (pinned bases, hash-checked wheels, gsplat compiled with nvcc; write_lock.py fails the build if it cannot train)
if [ "${SKIP_BUILD:-}" != 1 ]; then
  step build build.log docker build --progress=plain -f "$ROOT/services/reconstruction/Dockerfile.gpu" -t "$IMAGE" \
    "$ROOT/services/reconstruction" || exit 1
fi
docker image inspect "$IMAGE" --format '{{.Id}} {{.Size}}' > "$OUT/image.txt" || exit 1
docker run --rm --entrypoint cat "$IMAGE" /opt/chaya/gpu-lock.json > "$OUT/gpu-lock.json" || exit 1

# 3. The container sees the GPU (else the toolkit is missing or misconfigured: BLOCKED, not an image failure)
docker run --rm --gpus all --entrypoint nvidia-smi "$IMAGE" -L > "$OUT/container-gpus.txt" 2>&1 \
  || blocked toolkit "docker run --gpus all failed: $(head -c 300 "$OUT/container-gpus.txt")"

# 4. Preflight (exit 0 only if training can start) and the stages this image offers on this host
step preflight preflight.json docker run --rm --gpus all "$IMAGE" python -m chaya_worker.splat_preflight || exit 1
step capabilities capabilities.json docker run --rm --gpus all "$IMAGE" python -m chaya_worker.capabilities \
  || echo "(some configured stages are refused, e.g. the semantic ones until /models is filled: see capabilities.json)"

# 5. The real dataset: 24 CC0 photographs, SHA-1-verified, reconstructed with the image's own COLMAP
TESTS="$ROOT/services/reconstruction/tests"
step dataset dataset.log docker run --rm --user 0 -v "$TESTS:/src/tests:ro" -v "$DATA:/data" -w /src "$IMAGE" \
  python -m tests.gpu.real_dataset prepare --out /data/korno-24 --originals /data/originals --threads "${COLMAP_THREADS:-4}" \
  || exit 1
cp "$DATA/korno-24/dataset.json" "$OUT/dataset.json"

# 6. The acceptance test on CUDA: real gsplat, the quality gate on held-out views, resume, a corrupt checkpoint, the .ksplat
step acceptance acceptance.log docker run --rm --gpus all --user 0 -e CHAYA_REQUIRE_GPU=1 -e CHAYA_REQUIRE_REAL_DATASET=1 \
  -e CHAYA_SPLAT_DATASET=/data/korno-24 -e CHAYA_GPU_ACCEPTANCE_REPORT=/out/gpu-acceptance.json \
  -v "$TESTS:/src/tests:ro" -v "$DATA:/data:ro" -v "$OUT:/out" -w /src "$IMAGE" \
  sh -c "pip install -q pytest==8.4.1 && python -m pytest -p no:cacheprovider -m gpu tests/gpu/test_splat_acceptance.py tests/gpu/test_gsplat_cuda.py -v -rA --junitxml=/out/gpu.xml" \
  || exit 1

# 7. The trained .ksplat in the pinned viewer library, where node and apps/web's dependencies are present
if command -v node > /dev/null && [ -d "$ROOT/apps/web/node_modules" ] && [ -d "$OUT/viewer-check" ]; then
  step viewer-library viewer-check.log sh -c "cd '$ROOT/apps/web' && node --experimental-strip-types scripts/verify-ksplat-artifact.ts '$OUT/viewer-check'" \
    || exit 1
else
  printf 'viewer-library\tNOT RUN\t0\tno node or apps/web/node_modules on this host; run scripts/verify-ksplat-artifact.ts on %s\n' \
    "$OUT/viewer-check" >> "$STEPS"
fi

echo "== every step passed; evidence in $OUT"
cat "$STEPS"
