# syntax=docker/dockerfile:1.7
# Chaya 02 vision service: CLIP text embeddings for semantic search.
#
# INSTALL_MODEL=true (default) installs the CPU build of PyTorch + open_clip. The model WEIGHTS are not baked in: they
# are fetched on first use into $HF_HOME (mount a volume) and the service reports ready (/health/ready) only once they
# are loaded. INSTALL_MODEL=false gives a small image that is honestly never ready (503) -- useful only for API tests.
# Must stay on an internal network: it has no authentication (docs/security-hardening.md).

ARG INSTALL_MODEL=true

FROM python:3.12-slim-bookworm AS build
ARG INSTALL_MODEL
# Longer timeout and more retries than pip's defaults (15 s, 5): a stalled download should not fail a release build.
ENV PIP_NO_CACHE_DIR=1 PIP_DISABLE_PIP_VERSION_CHECK=1 PIP_TIMEOUT=60 PIP_RETRIES=10
RUN python -m venv /opt/venv
ENV PATH=/opt/venv/bin:$PATH
WORKDIR /src
COPY pyproject.toml ./
COPY app ./app
RUN if [ "$INSTALL_MODEL" = "true" ]; then \
      pip install --extra-index-url https://download.pytorch.org/whl/cpu ".[model]"; \
    else \
      pip install .; \
    fi

FROM python:3.12-slim-bookworm
RUN groupadd -g 10001 vision \
 && useradd -u 10001 -g vision -m -d /home/vision -s /usr/sbin/nologin vision
COPY --from=build /opt/venv /opt/venv
WORKDIR /srv
COPY app ./app
ENV PATH=/opt/venv/bin:$PATH \
    HF_HOME=/home/vision/.cache/huggingface \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1
USER 10001:10001
EXPOSE 8090
# Readiness: the model is actually loaded (app.main.ready). start-period covers the first weight download.
HEALTHCHECK --interval=30s --timeout=10s --start-period=300s --retries=3 \
  CMD ["python", "-c", "import sys,urllib.request; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8090/health/ready', timeout=8).status == 200 else 1)"]
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8090", "--proxy-headers", "--no-server-header"]
