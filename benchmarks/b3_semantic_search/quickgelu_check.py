"""Does the production CLIP configuration run the `openai` weights as they were trained?

open_clip 3.3 warns, when loading `ViT-B-32` with `pretrained="openai"`: "QuickGELU mismatch between final model
config (quick_gelu=False) and pretrained tag 'openai' (quick_gelu=True)". The OpenAI checkpoint was trained with
QuickGELU; `ViT-B-32` builds the tower with standard GELU. services/vision and the worker both use `ViT-B-32`, so their
vectors agree with each other, but not with the trained model. This measures what that costs on B3's own data, offline,
for both configurations (same weights, `ViT-B-32` vs `ViT-B-32-quickgelu`):

    manual       B3 dataset.json: top-1 / top-5 over the answerable queries, cosine ranking of label+category+tags
    zero-shot    detected_objects.json crops classified over the 5 detector labels (CLIP zero-shot, argmax)

Runs in the services/vision image like clip_vectors.py:

    docker run --rm -v <vision cache volume>:/home/vision/.cache -v "<repo>:/repo:ro" <vision image> \\
        python /repo/benchmarks/b3_semantic_search/quickgelu_check.py
"""

from __future__ import annotations

import importlib.util
import io
import json
import urllib.request
from pathlib import Path

import numpy as np

REPO = Path("/repo")
HERE = REPO / "benchmarks/b3_semantic_search"


def load(path: Path, name: str):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def main() -> None:
    from PIL import Image

    cv = load(HERE / "clip_vectors.py", "clip_vectors")
    ce = load(REPO / "services/reconstruction/chaya_worker/clip_embeddings.py", "clip_embeddings")
    te = load(REPO / "services/vision/app/clip_text_encoder.py", "clip_text_encoder")
    ds = json.loads((HERE / "dataset.json").read_text(encoding="utf-8"))
    det = json.loads((HERE / "detected_objects.json").read_text(encoding="utf-8"))
    crops = []
    for o in det["detected"]:
        with urllib.request.urlopen(o["url"], timeout=60) as r:
            img = np.asarray(Image.open(io.BytesIO(r.read())).convert("RGB"))
        crops.append(cv.crop_like_semantic_indexing(img, o["bbox_xywh"]))
    labels = sorted({o["label"] for o in det["detected"]})

    out = {}
    for model_name in ("ViT-B-32", "ViT-B-32-quickgelu"):
        enc = te.ClipTextEncoder(model_name=model_name, pretrained="openai")
        emb = ce.ClipEmbedder(model_name, "openai", device="cpu")

        def t(s: str) -> np.ndarray:
            return np.asarray(enc.embed(s))

        texts = []
        for p in ds["pois"]:
            parts, seen = [], set()
            for x in [p["label"], p["category"], *p["tags"]]:
                if x and x.strip() and x.strip().lower() not in seen:
                    seen.add(x.strip().lower())
                    parts.append(x.strip())
            texts.append(", ".join(parts))
        mat = np.stack([t(x) for x in texts])
        answerable = [q for q in ds["queries"] if q["expected"]]
        top1 = top5 = 0
        for q in answerable:
            order = np.argsort(-(mat @ t(q["q"].strip().lower())), kind="stable")
            names = [ds["pois"][i]["label"] for i in order]
            top1 += names[0] in q["expected"]
            top5 += bool(set(names[:5]) & set(q["expected"]))
        lab = np.stack([t(x) for x in labels])
        img = emb.embed_images(crops)
        pred = [labels[i] for i in np.argmax(img @ lab.T, axis=1)]
        zs = sum(p == o["label"] for p, o in zip(pred, det["detected"], strict=True))
        out[model_name] = {"manual_top1": f"{top1}/{len(answerable)}", "manual_top5": f"{top5}/{len(answerable)}",
                           "zero_shot_crops": f"{zs}/{len(crops)}"}
    print(json.dumps(out, indent=1))


if __name__ == "__main__":
    main()
