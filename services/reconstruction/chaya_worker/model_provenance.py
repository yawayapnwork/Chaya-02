"""What exactly produced a prediction: the model, the checkpoint revision, its licence, and whether it was fine-tuned.

Two kinds of checkpoint exist and are never confused:

* a **stock** checkpoint, used as published. STOCK_MODELS records its licence and the revision whose metadata was checked;
  it is reported with ``fine_tuned: false``.
* a checkpoint **trained by this project** (chaya_worker.training): a local directory with a ``chaya_model_card.json``
  written by the training run, naming the base model and revision, the dataset version and SHA-256, the hyperparameters
  and seed. Only a valid card makes a checkpoint ``fine_tuned: true``; a directory without one is reported as an
  unidentified local checkpoint, never as fine-tuned.

Every SEMANTIC_LABELS and DETECTED_OBJECTS artifact carries this record (``model_provenance``), so an evaluation or a
search index can always tell which weights produced a label or a vector.
"""

from __future__ import annotations

import json
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any

MODEL_CARD = "chaya_model_card.json"
CARD_SCHEMA = "chaya-model-card/1"
CARD_REQUIRED = ("schema", "task", "base_model", "base_revision", "dataset", "training", "created_at")

# Licence facts checked on 2026-10-11 against the model cards and licence files at the listed revision (Hugging Face Hub
# API). commercial_use=False means the published terms restrict use to research or evaluation.
STOCK_MODELS: dict[str, dict[str, Any]] = {
    "nvidia/segformer-b0-finetuned-ade-512-512": {
        "task": "semantic-segmentation",
        "checked_revision": "489d5cd81a0b59fab9b7ea758d3548ebe99677da",
        "license": "NVIDIA Source Code License for SegFormer (https://github.com/NVlabs/SegFormer/blob/master/LICENSE)",
        "commercial_use": False,
        "license_note": "Use and derivative works only non-commercially, i.e. for research or evaluation. A model fine-tuned "
                        "from these weights is a derivative work under the same terms.",
        "training_data": "ADE20K / SceneParse150 (150 classes)",
    },
    "IDEA-Research/grounding-dino-tiny": {
        "task": "zero-shot-object-detection",
        "checked_revision": "a2bb814dd30d776dcf7e30523b00659f4f141c71",
        "license": "Apache-2.0",
        "commercial_use": True,
        "license_note": None,
        "training_data": "Objects365, GoldG (Flickr30k Entities, GQA/Visual Genome), Cap4M -- per the Grounding DINO paper; "
                        "GQA images overlap COCO, so COCO evaluation is not strictly held out",
    },
    "open_clip:ViT-B-32:openai": {
        "task": "image-text-embedding",
        "checked_revision": None,  # open_clip resolves the "openai" tag to a fixed published checkpoint
        "license": "MIT (OpenAI CLIP weights and open_clip code)",
        "commercial_use": True,
        "license_note": "OpenAI's model card lists deployed use as out of scope without domain-specific testing.",
        "training_data": "OpenAI WIT-400M (not public)",
    },
}


class ModelCardError(ValueError):
    """A checkpoint claims to be trained by this project, but its card is incomplete or inconsistent."""


@dataclass
class ModelProvenance:
    model_id: str
    requested_revision: str | None
    resolved_revision: str | None
    fine_tuned: bool
    kind: str  # "stock" | "trained" | "unregistered"
    license: str | None = None
    commercial_use: bool | None = None
    base_model: str | None = None
    base_revision: str | None = None
    dataset: dict[str, Any] | None = None
    training: dict[str, Any] | None = None
    notes: list[str] = field(default_factory=list)

    def as_dict(self) -> dict[str, Any]:
        return asdict(self)


def read_card(model_dir: Path) -> dict[str, Any] | None:
    """The model card of a locally trained checkpoint; None when the directory has none. Raises ModelCardError when it
    exists but cannot back a claim of fine-tuning."""
    path = Path(model_dir) / MODEL_CARD
    if not path.is_file():
        return None
    try:
        card = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise ModelCardError(f"{path}: not JSON ({exc})") from exc
    missing = [k for k in CARD_REQUIRED if not card.get(k)]
    if missing:
        raise ModelCardError(f"{path}: missing {missing}")
    if card["schema"] != CARD_SCHEMA:
        raise ModelCardError(f"{path}: schema {card['schema']!r}, expected {CARD_SCHEMA!r}")
    ds = card["dataset"]
    if not (isinstance(ds, dict) and ds.get("version") and ds.get("sha256") and ds.get("splits_used")):
        raise ModelCardError(f"{path}: dataset must name version, sha256 and splits_used")
    if "test" in ds["splits_used"]:
        raise ModelCardError(f"{path}: a checkpoint trained on the test split cannot be evaluated on it")
    tr = card["training"]
    if not (isinstance(tr, dict) and "seed" in tr and tr.get("steps")):
        raise ModelCardError(f"{path}: training must record seed and steps")
    return card


def provenance(model_id: str, *, requested_revision: str | None, resolved_revision: str | None) -> ModelProvenance:
    """Provenance of a loaded checkpoint. `model_id` is a Hub id or a local directory."""
    local = Path(model_id)
    card = read_card(local) if local.is_dir() else None
    if card is not None:
        base = STOCK_MODELS.get(card["base_model"], {})
        notes = []
        if base and base.get("commercial_use") is False:
            notes.append(f"trained from {card['base_model']}, whose licence restricts use to research/evaluation")
        return ModelProvenance(model_id=model_id, requested_revision=requested_revision or None, resolved_revision=resolved_revision,
                               fine_tuned=True, kind="trained", license=base.get("license"), commercial_use=base.get("commercial_use"),
                               base_model=card["base_model"], base_revision=card["base_revision"], dataset=card["dataset"],
                               training=card["training"], notes=notes)
    stock = STOCK_MODELS.get(model_id)
    if stock is not None:
        notes = []
        if not requested_revision:
            notes.append("loaded from an unpinned revision")
        if stock["checked_revision"] and resolved_revision and resolved_revision != stock["checked_revision"]:
            notes.append(f"licence was checked at {stock['checked_revision']}, loaded {resolved_revision}")
        return ModelProvenance(model_id=model_id, requested_revision=requested_revision or None, resolved_revision=resolved_revision,
                               fine_tuned=False, kind="stock", license=stock["license"], commercial_use=stock["commercial_use"],
                               notes=notes)
    return ModelProvenance(model_id=model_id, requested_revision=requested_revision or None, resolved_revision=resolved_revision,
                           fine_tuned=False, kind="unregistered",
                           notes=["not in STOCK_MODELS and no chaya_model_card.json: licence and training unknown"])
