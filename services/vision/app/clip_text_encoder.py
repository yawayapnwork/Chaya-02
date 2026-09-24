"""The CLIP text tower, loaded once and reused for every /v1/embed-text request.

Must stay configured identically (model name + pretrained tag) to
chaya_worker.clip_embeddings.ClipEmbedder on the reconstruction side: SEMANTIC_INDEXING embeds detected
objects with CLIP's image tower offline, this embeds a live search query with CLIP's text tower online.
Cosine similarity between the two is only meaningful because both come out of the SAME trained model.
"""

from __future__ import annotations

import importlib.util
import os
import threading

EMBEDDING_DIM = 512
DEFAULT_MODEL_NAME = os.environ.get("CLIP_MODEL_NAME", "ViT-B-32")
DEFAULT_PRETRAINED = os.environ.get("CLIP_PRETRAINED", "openai")


class ModelUnavailable(RuntimeError):
    """torch/open_clip are not installed, or the model failed to load. Never caught to fabricate a
    zero/random embedding -- the caller (app.main) turns this into a 503."""


class ClipTextEncoder:
    def __init__(self, model_name: str = DEFAULT_MODEL_NAME, pretrained: str = DEFAULT_PRETRAINED) -> None:
        self.model_name = model_name
        self.pretrained = pretrained
        self._model = None
        self._tokenizer = None
        self._torch = None
        # FastAPI runs these sync handlers on a thread pool. Without this lock every request that arrives during the
        # first load (Docker's health check every 30 s, searches) starts its own load: ~1 GiB each, until the container
        # is OOM-killed. With it the model is loaded exactly once.
        self._load_lock = threading.Lock()

    @property
    def model_id(self) -> str:
        return f"open_clip:{self.model_name}:{self.pretrained}"

    def available(self) -> bool:
        return importlib.util.find_spec("torch") is not None and importlib.util.find_spec("open_clip") is not None

    def _ensure_loaded(self) -> None:
        if self._model is not None:
            return
        if not self.available():
            raise ModelUnavailable("torch and open_clip are not installed")
        with self._load_lock:
            if self._model is not None:  # loaded by another thread while this one waited
                return
            try:
                import open_clip
                import torch

                model, _, _ = open_clip.create_model_and_transforms(self.model_name, pretrained=self.pretrained)
                self._tokenizer = open_clip.get_tokenizer(self.model_name)
                self._torch = torch
                self._model = model.eval()  # last: _model set means everything above is ready
            except Exception as exc:  # noqa: BLE001 - any load failure means "unavailable", not a crash
                raise ModelUnavailable(f"failed to load {self.model_id}: {exc}") from exc

    def ready(self) -> tuple[bool, str | None]:
        """Whether the model is actually loaded (loading it if needed), not merely installed. A failed load is
        retried at most once a minute so a readiness probe cannot hammer a broken model download."""
        import time

        if self._model is not None:
            return True, None
        if self._load_lock.locked():
            # Answer the probe now instead of queueing it behind the load (and holding a pool thread for it).
            return False, f"{self.model_id} is loading"
        now = time.monotonic()
        last = getattr(self, "_last_failure", None)
        if last is not None and now - last[0] < 60:
            return False, last[1]
        try:
            self._ensure_loaded()
            return True, None
        except ModelUnavailable as exc:
            self._last_failure = (now, str(exc))
            return False, str(exc)

    def embed(self, text: str) -> list[float]:
        self._ensure_loaded()
        with self._torch.no_grad():
            tokens = self._tokenizer([text])
            features = self._model.encode_text(tokens)
            features = features / features.norm(dim=-1, keepdim=True)
        vector = features[0].cpu().numpy().tolist()
        if len(vector) != EMBEDDING_DIM:
            raise ModelUnavailable(f"{self.model_id} produced {len(vector)}-d embeddings, expected {EMBEDDING_DIM}")
        return vector
