"""CLIP image embeddings via open_clip, for storage in `poi_version.embedding vector(512)`.

The default model (ViT-B-32, "openai" pretrained weights) produces 512-dimensional embeddings, matching
the column's declared width -- see V5__poi.sql's comment. If the configured model is ever changed to one
with a different output width, `poi_version.embedding_model` records exactly which model produced each
row, and the column would need a matching migration; this module does not silently truncate or pad a
mismatched vector.

chaya_worker.stages.semantic_indexing (offline, batch, GPU) and services/vision (online, per search query,
CPU) both load this exact model configuration so image and text embeddings land in the same space. Keep
`Settings.clip_model_name` / `clip_pretrained` and services/vision's defaults in sync.
"""

from __future__ import annotations

import numpy as np

EMBEDDING_DIM = 512


class ClipEmbedder:
    def __init__(self, model_name: str, pretrained: str, *, device: str = "cpu") -> None:
        import open_clip
        import torch

        self.model_name = model_name
        self.pretrained = pretrained
        self.device = device
        self.model, _, self.preprocess = open_clip.create_model_and_transforms(model_name, pretrained=pretrained)
        self.model = self.model.to(device).eval()
        self._torch = torch
        dim = self.model.visual.output_dim if hasattr(self.model, "visual") else None
        if dim is not None and dim != EMBEDDING_DIM:
            raise ValueError(f"{model_name}/{pretrained} produces {dim}-d embeddings, but the schema expects {EMBEDDING_DIM}")

    @property
    def model_id(self) -> str:
        return f"open_clip:{self.model_name}:{self.pretrained}"

    def embed_images(self, images_rgb: list[np.ndarray]) -> np.ndarray:
        """(N, 512) float32, L2-normalised (so pgvector cosine distance == 1 - cosine similarity)."""
        if not images_rgb:
            return np.zeros((0, EMBEDDING_DIM), dtype=np.float32)
        from PIL import Image

        with self._torch.no_grad():
            batch = self._torch.stack([self.preprocess(Image.fromarray(img)) for img in images_rgb]).to(self.device)
            features = self.model.encode_image(batch)
            features = features / features.norm(dim=-1, keepdim=True)
        return features.cpu().numpy().astype(np.float32)
