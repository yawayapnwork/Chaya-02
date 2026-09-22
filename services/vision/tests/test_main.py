"""Tests the HTTP contract with a real FastAPI TestClient. The actual CLIP model is not exercised here
(torch/open_clip may not be installed in this environment); ClipTextEncoder.available()/ModelUnavailable
are real code paths, just driven with the real encoder pointed at a model name that cannot load, which
is functionally identical to "torch is not installed" from this API's point of view."""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from fastapi.testclient import TestClient  # noqa: E402

from app.clip_text_encoder import ClipTextEncoder, ModelUnavailable  # noqa: E402
from app.main import app, encoder  # noqa: E402

client = TestClient(app)


def test_health_reports_the_configured_model_and_its_real_availability():
    res = client.get("/health")
    assert res.status_code == 200
    body = res.json()
    assert body["model"] == encoder.model_id
    assert body["modelAvailable"] == encoder.available()


def test_embed_text_rejects_an_empty_query():
    res = client.post("/v1/embed-text", json={"text": ""})
    assert res.status_code == 422


def test_embed_text_returns_503_not_a_fake_vector_when_the_model_is_unavailable(monkeypatch):
    def broken_embed(self, text):
        raise ModelUnavailable("torch and open_clip are not installed")

    monkeypatch.setattr(ClipTextEncoder, "embed", broken_embed)
    res = client.post("/v1/embed-text", json={"text": "a red chair"})
    assert res.status_code == 503
    assert "unavailable" in res.json()["detail"]


def test_embed_text_returns_a_real_looking_vector_when_the_encoder_succeeds(monkeypatch):
    def fake_embed(self, text):
        return [0.1] * 512

    monkeypatch.setattr(ClipTextEncoder, "embed", fake_embed)
    res = client.post("/v1/embed-text", json={"text": "a red chair"})
    assert res.status_code == 200
    body = res.json()
    assert len(body["embedding"]) == 512
    assert body["model"] == encoder.model_id
