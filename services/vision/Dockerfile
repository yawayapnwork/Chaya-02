# Chaya 02 vision service: real-time CLIP text embeddings for dev.chaya.api.search.
#
# The base image does not include the model extra (torch/open_clip): /v1/embed-text answers 503
# (ModelUnavailable), never a fabricated embedding, until the image is built with the `model` extra or
# those packages are installed at deploy time. See app/clip_text_encoder.py.
FROM python:3.12-slim

WORKDIR /app
COPY pyproject.toml ./
COPY app ./app
RUN pip install --no-cache-dir .

RUN useradd --create-home vision
USER vision
ENV PYTHONUNBUFFERED=1

EXPOSE 8090
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8090"]
