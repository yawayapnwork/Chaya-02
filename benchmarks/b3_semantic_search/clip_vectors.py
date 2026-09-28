"""Real CLIP vectors for search tests and B3: text-tower vectors of phrases and image-tower vectors of real crops.

Runs inside the services/vision image (which has torch + open_clip), with the repository mounted at /repo, and loads
the two production code paths unchanged:

    image tower   chaya_worker.clip_embeddings.ClipEmbedder   (what SEMANTIC_INDEXING stores for a detected object)
    text tower    services/vision app.clip_text_encoder.ClipTextEncoder   (what embeds every query and POI text)

A crop is cut exactly as SEMANTIC_INDEXING cuts a detection (integer box clamped to the frame, RGB, the model's own
preprocessing). Images are downloaded, used and discarded; only their URL, the box and the SHA-256 of the image bytes
are recorded, never the image.

    docker run --rm -v chaya-e2e_vision-cache:/home/vision/.cache -v "<repo>:/repo:ro" -v "<out dir>:/out" \\
        chaya-e2e-vision python /repo/benchmarks/b3_semantic_search/clip_vectors.py /out/request.json /out/vectors.json

request.json: {"texts": ["couch", ...], "crops": [{"id": "couch-1", "url": "http://...", "bbox_xywh": [x, y, w, h]}],
               "model_name": "ViT-B-32", "pretrained": "openai"}   (model defaults: the production configuration)
"""

from __future__ import annotations

import hashlib
import importlib.util
import io
import json
import sys
import urllib.request
from pathlib import Path

import numpy as np

REPO = Path("/repo")


def load(path: Path, name: str):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def crop_like_semantic_indexing(img_rgb: np.ndarray, bbox_xywh: list[float]) -> np.ndarray:
    """chaya_worker.stages.semantic_indexing: `x0, y0, x1, y1 = (int(max(0, v)) for v in box_xyxy)`, clamped to the frame."""
    h, w = img_rgb.shape[:2]
    x, y, bw, bh = bbox_xywh
    x0, y0, x1, y1 = (int(max(0, v)) for v in (x, y, x + bw, y + bh))
    x1, y1 = min(w, x1), min(h, y1)
    if x1 <= x0 or y1 <= y0:
        raise ValueError(f"empty crop {bbox_xywh} in a {w}x{h} image")
    return img_rgb[y0:y1, x0:x1]


def main() -> None:
    from PIL import Image

    request = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
    model_name = request.get("model_name", "ViT-B-32")
    pretrained = request.get("pretrained", "openai")
    clip_embeddings = load(REPO / "services/reconstruction/chaya_worker/clip_embeddings.py", "clip_embeddings")
    text_encoder = load(REPO / "services/vision/app/clip_text_encoder.py", "clip_text_encoder")

    embedder = clip_embeddings.ClipEmbedder(model_name, pretrained, device="cpu")
    encoder = text_encoder.ClipTextEncoder(model_name=model_name, pretrained=pretrained)

    out = {"image_model": embedder.model_id, "text_model": encoder.model_id, "texts": {}, "crops": {}}
    for text in request.get("texts", []):
        out["texts"][text] = [round(float(x), 7) for x in encoder.embed(text)]
    for c in request.get("crops", []):
        with urllib.request.urlopen(c["url"], timeout=60) as r:
            data = r.read()
        img = np.asarray(Image.open(io.BytesIO(data)).convert("RGB"))
        crop = crop_like_semantic_indexing(img, c["bbox_xywh"])
        vec = embedder.embed_images([crop])[0]
        out["crops"][c["id"]] = {"image_sha256": hashlib.sha256(data).hexdigest(), "crop_shape": list(crop.shape[:2]),
                                 "embedding": [round(float(x), 7) for x in vec]}
        print(f"{c['id']}: {crop.shape[1]}x{crop.shape[0]} crop", file=sys.stderr)
    Path(sys.argv[2]).write_text(json.dumps(out), encoding="utf-8")


if __name__ == "__main__":
    main()
