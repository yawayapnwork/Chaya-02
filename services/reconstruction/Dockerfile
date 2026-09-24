# syntax=docker/dockerfile:1.7
# Chaya 02 reconstruction worker (execution plane), CPU image.
#
# Runs the stages that need only FFmpeg, OpenCV and numpy: input validation, frame extraction, frame quality filtering,
# privacy preprocessing, artifact generation (and region splice). It deliberately does NOT contain COLMAP, GLOMAP,
# gsplat, PyTorch/CUDA, Open3D or Recast: a worker built from it reports DEPENDENCY_UNAVAILABLE (a structured, retryable
# failure) for the stages that need them and never fakes their output. GPU stages run on a GPU host with the toolchain
# installed (docs/pipeline.md, DEPLOYMENT.md "GPU workers"); restrict each worker to what it can run with WORKER_STAGES.
#
# No secrets in the image: the service-account secret and MinIO credentials arrive as environment variables.

FROM python:3.12-slim-bookworm AS build
ENV PIP_NO_CACHE_DIR=1 PIP_DISABLE_PIP_VERSION_CHECK=1
RUN python -m venv /opt/venv
ENV PATH=/opt/venv/bin:$PATH
WORKDIR /src
COPY pyproject.toml ./
COPY chaya_worker ./chaya_worker
RUN pip install .

FROM python:3.12-slim-bookworm
# ffmpeg/ffprobe are the only system packages the CPU stages execute.
RUN apt-get update \
 && apt-get install -y --no-install-recommends ffmpeg \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd -g 10001 worker \
 && useradd -u 10001 -g worker -m -d /home/worker -s /usr/sbin/nologin worker \
 && mkdir /work && chown worker:worker /work
COPY --from=build /opt/venv /opt/venv
ENV PATH=/opt/venv/bin:$PATH \
    WORKER_WORKDIR=/work \
    LOG_LEVEL=INFO \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1
USER 10001:10001
# Healthy only while control-plane calls succeed (chaya_worker.liveness), not merely because the process runs.
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 CMD ["python", "-m", "chaya_worker.liveness"]
CMD ["python", "-m", "chaya_worker"]
