"""Open-vocabulary object detection via Grounding DINO (HuggingFace transformers).

The checkpoint is entirely configurable (`Settings.grounding_dino_model`, default
"IDEA-Research/grounding-dino-tiny"): a Hub id, or a local directory such as one written by
chaya_worker.training.detection. Whether it is fine-tuned is not asserted here but derived
(chaya_worker.model_provenance): only a local checkpoint with a valid chaya_model_card.json is reported as
fine-tuned. The default is the stock, publicly pretrained model; no checkpoint trained by this project exists yet
(docs/ML_EVALUATION.md).
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
        from .model_provenance import provenance

        self.model_id = model_id
        self.device = device
        self.box_threshold = box_threshold
        self.text_threshold = text_threshold
        # The processor is configuration and tokenizer files only (no weights), so it is pinned but not safetensors-gated.
        self.processor = AutoProcessor.from_pretrained(model_id, **({"revision": revision} if revision else {}))
        self.model = load_pretrained(AutoModelForZeroShotObjectDetection.from_pretrained, model_id, revision=revision,
                                     allow_pickle=allow_pickle, what="Grounding DINO model").to(device).eval()
        self.revision = resolved_revision(self.model) or revision or None
        self.provenance = provenance(model_id, requested_revision=revision, resolved_revision=resolved_revision(self.model))
        self.fine_tuned = self.provenance.fine_tuned

    def detect(self, image_rgb: np.ndarray, text_prompt: str) -> list[Detection]:
        import torch

        inputs = self.processor(images=image_rgb, text=text_prompt, return_tensors="pt").to(self.device)
        with torch.no_grad():
            outputs = self.model(**inputs)
        h, w = image_rgb.shape[:2]
        results = post_process(self.processor, outputs, inputs["input_ids"], box_threshold=self.box_threshold,
                               text_threshold=self.text_threshold, target_size=(h, w))
        detections = []
        labels = results["text_labels"] if "text_labels" in results else results["labels"]
        for box, score, label in zip(results["boxes"], results["scores"], labels, strict=True):
            x0, y0, x1, y1 = (float(v) for v in box.tolist())
            detections.append(Detection(label=str(label).strip(), confidence=float(score), box_xyxy=(x0, y0, x1, y1)))
        return detections


def post_process(processor, outputs, input_ids, *, box_threshold: float, text_threshold: float, target_size: tuple[int, int]):
    """`post_process_grounded_object_detection` across transformers versions: the box threshold argument was renamed
    from `box_threshold` to `threshold` (4.51), and newer versions return the phrases as `text_labels` (with integer
    `labels`). Passing the old name to a new version fails or is ignored, which would silently change what is detected."""
    import inspect

    fn = processor.post_process_grounded_object_detection
    params = inspect.signature(fn).parameters
    kwargs = {"text_threshold": text_threshold, "target_sizes": [target_size]}
    if "threshold" in params:
        kwargs["threshold"] = box_threshold
    elif "box_threshold" in params:
        kwargs["box_threshold"] = box_threshold
    else:
        raise TypeError("post_process_grounded_object_detection accepts neither threshold nor box_threshold")
    return fn(outputs, input_ids, **kwargs)[0]
