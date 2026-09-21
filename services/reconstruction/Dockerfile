# Chaya 02 reconstruction worker (execution plane).
#
# This image can run the stages that need only FFmpeg and OpenCV: input validation, frame extraction,
# frame quality filtering and privacy preprocessing. It deliberately does NOT contain COLMAP, GLOMAP,
# gsplat, PyTorch/CUDA, Open3D or Recast. A worker built from it reports DEPENDENCY_UNAVAILABLE (a
# structured, retryable failure) for the stages that need them; it never fakes their output.
# A GPU image adds those tools on a CUDA base; see docs/pipeline.md.
FROM python:3.12-slim

RUN apt-get update \
 && apt-get install -y --no-install-recommends ffmpeg \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY pyproject.toml ./
COPY chaya_worker ./chaya_worker
RUN pip install --no-cache-dir .

RUN useradd --create-home worker && mkdir /work && chown worker /work
USER worker
ENV WORKER_WORKDIR=/work \
    LOG_LEVEL=INFO \
    PYTHONUNBUFFERED=1

# Configuration comes from environment variables (see .env.example); there are no defaults for secrets.
CMD ["python", "-m", "chaya_worker"]
