# Benchmark-only image: the reconstruction worker package plus Open3D (CPU build), for the benchmarks that run the
# worker's own cleanup and alignment code (B2, B5). Not a production image; production workers get Open3D on the GPU
# host (DEPLOYMENT.md "GPU workers").
#
#   docker build -f benchmarks/docker/open3d.Dockerfile -t chaya-bench-open3d .
#   docker run --rm -v "$PWD:/repo" -v <data>:/data:ro -w /repo chaya-bench-open3d python benchmarks/b2_geometry_cleanup/run.py ...

# syntax=docker/dockerfile:1.7
FROM python:3.12-slim-bookworm
# Open3D's CPU wheel links OpenMP and libGL even when nothing is rendered.
RUN apt-get update \
 && apt-get install -y --no-install-recommends libgomp1 libgl1 \
 && rm -rf /var/lib/apt/lists/*
ENV PIP_DISABLE_PIP_VERSION_CHECK=1 PIP_TIMEOUT=180 PIP_RETRIES=20 PYTHONDONTWRITEBYTECODE=1
# `import open3d` pulls in its web visualiser's dependencies (plotly, dash, ipywidgets, ...), about 40 packages. The
# cache mount keeps whatever was downloaded, so a retried build after a network stall only fetches what is missing.
RUN --mount=type=cache,target=/root/.cache/pip pip install "open3d-cpu==0.19.0"
COPY services/reconstruction /src/reconstruction
RUN --mount=type=cache,target=/root/.cache/pip pip install /src/reconstruction && rm -rf /src
