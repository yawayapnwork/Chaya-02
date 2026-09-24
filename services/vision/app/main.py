"""services/vision: a small FastAPI service whose only job is turning search query text into a CLIP
embedding for dev.chaya.api.search (SemanticSearchService -> HttpTextEmbeddingClient). Never returns a
fabricated embedding: if the model cannot be loaded, /v1/embed-text answers 503 with a structured reason,
and SemanticSearchService falls back to lexical search rather than treating a fake vector as real.
"""

from __future__ import annotations

from fastapi import FastAPI, HTTPException
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from .clip_text_encoder import ClipTextEncoder, ModelUnavailable

app = FastAPI(title="chaya-vision", version="0.1.0")
encoder = ClipTextEncoder()


class EmbedTextRequest(BaseModel):
    text: str = Field(min_length=1, max_length=512)


class EmbedTextResponse(BaseModel):
    embedding: list[float]
    model: str


@app.get("/health/live")
def live() -> dict:
    """Liveness only: the process answers HTTP. Never used to decide whether to send traffic."""
    return {"status": "UP"}


@app.get("/health")
@app.get("/health/ready")
def ready() -> JSONResponse:
    """Readiness: the embedding model is actually loaded and usable. 503 otherwise -- the service is not healthy just
    because the process started (without the model every embed request would fail)."""
    ok, reason = encoder.ready()
    body = {"status": "UP" if ok else "DOWN", "model": encoder.model_id, "modelAvailable": ok, "reason": reason}
    return JSONResponse(body, status_code=200 if ok else 503)


@app.post("/v1/embed-text", response_model=EmbedTextResponse)
def embed_text(body: EmbedTextRequest) -> EmbedTextResponse:
    try:
        vector = encoder.embed(body.text)
    except ModelUnavailable as exc:
        raise HTTPException(status_code=503, detail=f"embedding model unavailable: {exc}") from exc
    return EmbedTextResponse(embedding=vector, model=encoder.model_id)
