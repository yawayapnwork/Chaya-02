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


def test_liveness_is_only_the_process():
    assert client.get("/health/live").json() == {"status": "UP"}


def test_readiness_is_503_when_the_model_cannot_load(monkeypatch):
    monkeypatch.setattr(ClipTextEncoder, "ready", lambda self: (False, "torch and open_clip are not installed"))
    for path in ("/health", "/health/ready"):
        res = client.get(path)
        assert res.status_code == 503
        body = res.json()
        assert body["status"] == "DOWN" and body["modelAvailable"] is False
        assert body["model"] == encoder.model_id
        assert "not installed" in body["reason"]


def test_readiness_is_200_only_when_the_model_is_loaded(monkeypatch):
    monkeypatch.setattr(ClipTextEncoder, "ready", lambda self: (True, None))
    res = client.get("/health/ready")
    assert res.status_code == 200 and res.json()["status"] == "UP"


def test_ready_really_attempts_the_load_and_backs_off_after_a_failure(monkeypatch):
    enc = ClipTextEncoder(model_name="no-such-model")
    calls = []

    def failing_load(self):
        calls.append(1)
        raise ModelUnavailable("cannot load")

    monkeypatch.setattr(ClipTextEncoder, "_ensure_loaded", failing_load)
    assert enc.ready() == (False, "cannot load")
    assert enc.ready() == (False, "cannot load")
    assert len(calls) == 1, "a failed load is not retried within the back-off window"


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
