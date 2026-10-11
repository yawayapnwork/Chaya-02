# Benchmark-only image for B6 (semantic models: segmentation, detection, retrieval) and the training harness smoke tests.
# Not a production image. It extends the vision service image -- the exact CPU torch + open_clip build that serves search
# embeddings -- with transformers (SegFormer, Grounding DINO) and the worker package, so the CLIP numbers come from the
# same build production uses.
#
#   docker build --build-arg INSTALL_MODEL=true -t chaya-vision services/vision
#   docker build -f benchmarks/docker/ml-eval.Dockerfile --build-arg BASE=chaya-vision -t chaya-ml-eval services/reconstruction
#   (the build context is the worker directory, so its .dockerignore keeps .venv and build output out)
#   docker run --rm -v "$PWD:/repo" -v <dataset>:/data:ro -v chaya-ml-cache:/home/vision/.cache -w /repo chaya-ml-eval \
#     python benchmarks/b6_semantic_models/run.py --dataset /data/coco-indoor-eval
#
# Model weights are not baked in: they download on first use into the cache volume, at the revisions run.py pins.

# syntax=docker/dockerfile:1.7
ARG BASE=chaya-vision
FROM ${BASE}
USER root
ENV PIP_DISABLE_PIP_VERSION_CHECK=1 PIP_TIMEOUT=180 PIP_RETRIES=20 PYTHONDONTWRITEBYTECODE=1
# transformers is pinned: Grounding DINO's post-processing changed its arguments between minor versions
# (chaya_worker.grounding_dino.post_process handles both, and the benchmark records the version it ran with).
RUN --mount=type=cache,target=/root/.cache/pip pip install "transformers==4.57.1" "pytest>=8"
COPY . /src/reconstruction
RUN --mount=type=cache,target=/root/.cache/pip pip install /src/reconstruction && rm -rf /src
USER 10001:10001
