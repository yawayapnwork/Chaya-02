# TEST-ONLY worker image for the end-to-end validation (docs/E2E_VALIDATION.md): the production CPU worker image plus
# Debian's CPU build of COLMAP, so POSE_ESTIMATION (COLMAP feature extraction, matching and mapping) runs for real on a
# host without a GPU. The stage already passes --SiftExtraction.use_gpu 0 when nvidia-smi is absent
# (chaya_worker.stages.pose_estimation). Nothing else is added: SPLAT_RECONSTRUCTION and everything after it still
# lack torch/gsplat/CUDA/Open3D/recast-cli here and must fail with a structured DEPENDENCY_UNAVAILABLE.
#
# Not published and not used in production: production reconstruction runs on GPU hosts (DEPLOYMENT.md "GPU workers").
# `base` is the production worker image, supplied as a named build context:
#   compose:  additional_contexts: { base: "service:reconstruction-worker" }   (infra/ci/docker-compose.e2e.yml)
#   docker:   docker build -f infra/ci/worker-colmap.Dockerfile --build-context base=docker-image://<worker image> infra/ci

FROM base
USER 0
RUN apt-get update \
 && apt-get install -y --no-install-recommends colmap \
 && rm -rf /var/lib/apt/lists/*
# COLMAP links Qt; its command-line tools need no display, but Qt must not try to open one.
ENV QT_QPA_PLATFORM=offscreen
USER 10001:10001
