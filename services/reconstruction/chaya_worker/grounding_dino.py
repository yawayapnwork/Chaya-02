"""Open-vocabulary object detection via Grounding DINO (HuggingFace transformers).

The checkpoint is entirely configurable (`Settings.grounding_dino_model`, default
"IDEA-Research/grounding-dino-tiny") so a project-specific fine-tuned checkpoint can be dropped in later
by pointing that setting at it -- nothing here hardcodes the public checkpoint. As of this stage's first
implementation NO fine-tuning has happened: this wraps the stock, publicly pretrained model. Do not claim
otherwise; `DetectionRun.model_id` and `DetectionRun.fine_tuned` are reported in every stage's output for
exactly that reason.

The dataset structure to eventually fine-tune this model on project-specific data lives in
chaya_worker.datasets and is intentionally decoupled from this module: training is a separate,
not-yet-implemented milestone (see PROJECT_PLAN.md M12).
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np


@dataclass
class Detection:
    label: str
    confidence: float
    box_xyxy: tuple[float, float, float, float]  # pixel coordinates in the source frame


class GroundingDinoDetector:
    """Loads once, detects many times. `text_prompt` is Grounding DINO's own input format: labels of
    interest separated by ". " (a period and a space), e.g. "chair. sofa. table.". Never a single
    hardcoded label->label mapping -- the caller supplies whatever vocabulary the venue needs detected,
    and matching a user's search query to a stored detection is CLIP's job (chaya_worker.clip_embeddings),
    not this module's.
    """

    def __init__(self, model_id: str, *, device: str = "cpu", box_threshold: float = 0.35, text_threshold: float = 0.25,
                 revision: str = "", allow_pickle: bool = False) -> None:
        from transformers import AutoModelForZeroShotObjectDetection, AutoProcessor

        from .model_loading import load_pretrained, resolved_revision

        self.model_id = model_id
        self.fine_tuned = False  # stock checkpoint; see module docstring
        self.device = device
        self.box_threshold = box_threshold
        self.text_threshold = text_threshold
        # The processor is configuration and tokenizer files only (no weights), so it is pinned but not safetensors-gated.
        self.processor = AutoProcessor.from_pretrained(model_id, **({"revision": revision} if revision else {}))
        self.model = load_pretrained(AutoModelForZeroShotObjectDetection.from_pretrained, model_id, revision=revision,
                                     allow_pickle=allow_pickle, what="Grounding DINO model").to(device).eval()
        self.revision = resolved_revision(self.model) or revision or None

    def detect(self, image_rgb: np.ndarray, text_prompt: str) -> list[Detection]:
        import torch

        inputs = self.processor(images=image_rgb, text=text_prompt, return_tensors="pt").to(self.device)
        with torch.no_grad():
            outputs = self.model(**inputs)
        h, w = image_rgb.shape[:2]
        results = self.processor.post_process_grounded_object_detection(
            outputs, inputs["input_ids"], box_threshold=self.box_threshold, text_threshold=self.text_threshold,
            target_sizes=[(h, w)])[0]
        detections = []
        for box, score, label in zip(results["boxes"], results["scores"], results["labels"], strict=True):
            x0, y0, x1, y1 = (float(v) for v in box.tolist())
            detections.append(Detection(label=str(label).strip(), confidence=float(score), box_xyxy=(x0, y0, x1, y1)))
        return detections
