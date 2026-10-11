"""Gates, seeding and the model card shared by the training scripts (see chaya_worker.training)."""

from __future__ import annotations

import json
import os
import platform
import random
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from ..datasets.jsonl import read_images
from ..datasets.validate import require_locked
from ..model_provenance import CARD_SCHEMA, MODEL_CARD, STOCK_MODELS, read_card


class TrainingRefused(RuntimeError):
    """A precondition for a reproducible, legitimate training run is not met. Nothing was trained."""


@dataclass
class Prepared:
    dataset_root: Path
    lock: dict[str, Any]
    manifest: dict[str, Any]
    train: list
    val: list
    device: str
    base_license: dict[str, Any] | None


def prepare(dataset: Path, *, base_model: str, accept_base_license: str | None, allow_cpu: bool, need_masks: bool) -> Prepared:
    try:
        lock = require_locked(dataset)
    except ValueError as exc:
        raise TrainingRefused(str(exc)) from exc
    manifest = json.loads((Path(dataset) / "manifest.json").read_text(encoding="utf-8"))
    images = list(read_images(Path(dataset) / "images.jsonl"))
    train = [r for r in images if r.split == "train" and (r.semantic_mask or not need_masks)]
    val = [r for r in images if r.split == "val" and (r.semantic_mask or not need_masks)]
    if not train or not val:
        raise TrainingRefused(f"the dataset needs labelled train and val images (train {len(train)}, val {len(val)}); the test "
                              "split is reserved for evaluation and never trained or tuned on")
    stock = STOCK_MODELS.get(base_model)
    if stock is not None and stock.get("commercial_use") is False and accept_base_license != "research-only":
        raise TrainingRefused(f"{base_model} is licensed for research/evaluation only ({stock['license']}); a fine-tuned model "
                              "is a derivative under the same terms. Pass --accept-base-license research-only to train it "
                              "for research, or choose a base model whose licence permits the intended use")
    import torch

    device = "cuda" if torch.cuda.is_available() else "cpu"
    if device == "cpu" and not allow_cpu:
        raise TrainingRefused("no CUDA device: fine-tuning these models on CPU is not a supported run (use --allow-cpu only for "
                              "smoke tests of the harness)")
    return Prepared(Path(dataset), lock, manifest, train, val, device, stock)


def seed_everything(seed: int) -> None:
    import numpy as np
    import torch

    random.seed(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)
    os.environ.setdefault("CUBLAS_WORKSPACE_CONFIG", ":4096:8")
    torch.use_deterministic_algorithms(True, warn_only=True)


def environment(device: str) -> dict[str, Any]:
    import torch
    import transformers

    env = {"python": platform.python_version(), "platform": platform.platform(), "torch": torch.__version__,
           "transformers": transformers.__version__, "device": device, "cpus": os.cpu_count()}
    if device == "cuda":
        env["gpu"] = torch.cuda.get_device_name(0)
    return env


def write_card(out: Path, *, task: str, base_model: str, base_revision: str | None, prepared: Prepared, training: dict[str, Any],
               metrics: dict[str, Any], label_set: list[str], accept_base_license: str | None) -> dict[str, Any]:
    card = {
        "schema": CARD_SCHEMA, "task": task, "base_model": base_model, "base_revision": base_revision or "unpinned",
        "base_license": (prepared.base_license or {}).get("license", "not in STOCK_MODELS: check before use"),
        "base_license_acknowledged": accept_base_license,
        "dataset": {"name": prepared.lock["name"], "version": prepared.lock["version"], "sha256": prepared.lock["sha256"],
                    "splits_used": ["train", "val"], "train_images": len(prepared.train), "val_images": len(prepared.val)},
        "label_set": label_set, "training": training, "metrics": metrics,
        "created_at": datetime.now(UTC).isoformat(timespec="seconds"),
        "note": "Fine-tuned by chaya_worker.training. Validation metrics are for model selection only; report quality from a "
                "held-out evaluation (benchmarks/b6_semantic_models) on the test split.",
    }
    (out / MODEL_CARD).write_text(json.dumps(card, indent=2, sort_keys=True), encoding="utf-8")
    read_card(out)  # the card must back the claim it makes
    return card
