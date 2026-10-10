"""Fills the GPU worker's model volume (/models) with exactly the weights its stages load, at the pinned revisions in the
image's environment (Dockerfile.gpu). Run once per volume, with network access:

    docker run --rm -v chaya-models:/models chaya-worker-gpu python /opt/chaya/fetch_models.py

The stages never download mid-job. chaya_worker.toolchain refuses a Hugging Face model that is not in the local cache, so
a worker without the volume fails with a structured DEPENDENCY_UNAVAILABLE that names the model. Safetensors only, as
chaya_worker.model_loading requires. The CLIP weights are fetched through the same open_clip call the embedder makes, so
they land wherever open_clip itself looks.
"""

from __future__ import annotations

import json
import os
import sys


def main() -> int:
    from huggingface_hub import snapshot_download

    fetched = {}
    for model_var, revision_var in (("SEMANTIC_SEGMENTATION_MODEL", "SEMANTIC_SEGMENTATION_REVISION"),
                                    ("GROUNDING_DINO_MODEL", "GROUNDING_DINO_REVISION")):
        model, revision = os.environ[model_var], os.environ.get(revision_var) or None
        if not revision:
            print(f"{revision_var} is not set: refusing to fetch an unpinned {model}", file=sys.stderr)
            return 1
        path = snapshot_download(model, revision=revision, allow_patterns=["*.json", "*.safetensors", "*.txt", "*.model"])
        fetched[model] = {"revision": revision, "path": path}

    import open_clip  # noqa: PLC0415

    name, pretrained = os.environ.get("CLIP_MODEL_NAME", "ViT-B-32"), os.environ.get("CLIP_PRETRAINED", "openai")
    open_clip.create_model_and_transforms(name, pretrained=pretrained)
    fetched[f"open_clip:{name}:{pretrained}"] = {"via": "open_clip.create_model_and_transforms"}
    print(json.dumps(fetched, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
