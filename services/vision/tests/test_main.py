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


def test_concurrent_requests_during_the_first_load_load_the_model_exactly_once(monkeypatch):
    """Sync FastAPI handlers run on a thread pool: health checks and searches that arrive while the model is loading
    must wait for (or report) that one load, not each start their own (~1 GiB each, until the container is OOM-killed)."""
    import threading
    import types

    loads, started, release = [], threading.Event(), threading.Event()

    def slow_create(name, pretrained):
        loads.append(name)
        started.set()
        release.wait(5)
        return types.SimpleNamespace(eval=lambda: "model"), None, None

    fake = types.SimpleNamespace(create_model_and_transforms=slow_create, get_tokenizer=lambda name: "tok")
    monkeypatch.setitem(sys.modules, "open_clip", fake)
    monkeypatch.setitem(sys.modules, "torch", types.SimpleNamespace())
    monkeypatch.setattr(ClipTextEncoder, "available", lambda self: True)
    enc = ClipTextEncoder()

    threads = [threading.Thread(target=enc._ensure_loaded) for _ in range(8)]
    threads[0].start()
    assert started.wait(5)
    for t in threads[1:]:
        t.start()
    ok, reason = enc.ready()
    assert (ok, "loading" in (reason or "")) == (False, True), "a probe during the load is answered, not queued"
    release.set()
    for t in threads:
        t.join(5)
    assert len(loads) == 1
    assert enc.ready() == (True, None)
