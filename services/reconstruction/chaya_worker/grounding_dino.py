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
    raw_label: str | None = None


def prompt_phrases(text_prompt: str) -> list[str]:
    """Canonical lowercase prompt phrases in order, split on Grounding DINO's '.' separator."""
    out: list[str] = []
    for part in text_prompt.split("."):
        norm = " ".join(part.lower().split())
        if norm and norm not in out:
            out.append(norm)
    return out


def phrase_token_spans(input_ids_1d: list[int], separator_ids: set[int]) -> list[list[int]]:
    """Contiguous token-index spans between '.' and special-token separators in `input_ids_1d`."""
    spans: list[list[int]] = []
    current: list[int] = []
    for idx, tok in enumerate(input_ids_1d):
        if int(tok) in separator_ids:
            if current:
                spans.append(current)
                current = []
        else:
            current.append(idx)
    if current:
        spans.append(current)
    return spans


def resolve_prompt_phrase(raw_label: str, text_prompt: str, *, phrase_scores: dict[str, float] | None = None,
                          text_threshold: float = 0.0) -> str:
    """Resolve a raw Grounding DINO decoded label (which may merge several prompt phrases that exceeded
    `text_threshold`, e.g. 'desk reception desk counter', or decode only a BERT WordPiece suffix like
    '##tinguisher') to the single best-matching canonical phrase from `text_prompt`.

    When per-phrase token-span scores (`phrase_scores`) from the model's logits are supplied, picks the
    candidate prompt phrase with the highest token activation; otherwise falls back to deterministic
    phrase/subword matching against `text_prompt`. Never invents a label outside `text_prompt` and returns
    `""` if `raw_label` has no usable phrase.
    """
    raw_clean = " ".join(raw_label.strip().lower().split())
    if not raw_clean or not any(ch.isalnum() for ch in raw_clean):
        return ""
    phrases = prompt_phrases(text_prompt)
    if not phrases:
        return raw_label.strip()
    if raw_clean in phrases:
        return raw_clean

    padded_raw = f" {raw_clean} "
    whole_matches = [p for p in phrases if f" {p} " in padded_raw]

    fragments = [tok.lstrip("#") for tok in raw_clean.split() if len(tok.lstrip("#")) >= 3]
    subword_matches = [
        p for p in phrases
        if any(w == frag or w.endswith(frag) or w.startswith(frag) for w in p.split() for frag in fragments)
    ]

    candidates = whole_matches or subword_matches
    if phrase_scores:
        pool = candidates if candidates else [p for p in phrases if phrase_scores.get(p, 0.0) >= text_threshold]
        if pool:
            above = [p for p in pool if phrase_scores.get(p, 0.0) >= text_threshold]
            active = above if above else pool
            return max(active, key=lambda p: (phrase_scores.get(p, 0.0), len(p)))

    if whole_matches:
        return min(whole_matches, key=lambda p: (padded_raw.find(f" {p} "), -len(p)))
    if subword_matches:
        return max(subword_matches, key=len)
    return raw_label.strip()


def _per_box_phrase_scores(processor, outputs, input_ids, text_prompt: str,
                           scores_tensor) -> list[dict[str, float] | None]:
    """Extract per-prompt-phrase peak token sigmoid scores for each post-processed box when model logits are present."""
    n_boxes = len(scores_tensor)
    if outputs is None or not hasattr(outputs, "logits") or input_ids is None:
        return [None] * n_boxes
    phrases = prompt_phrases(text_prompt)
    if not phrases:
        return [None] * n_boxes
    try:
        tokenizer = getattr(processor, "tokenizer", None)
        sep_ids: set[int] = {101, 102, 0, 1012}
        if tokenizer is not None:
            sep_ids.update(int(i) for i in getattr(tokenizer, "all_special_ids", []) if i is not None)
            dot_id = tokenizer.convert_tokens_to_ids(".")
            if isinstance(dot_id, int):
                sep_ids.add(dot_id)
        ids_1d = [int(t) for t in input_ids[0].tolist()]
        spans = phrase_token_spans(ids_1d, sep_ids)
        if len(spans) != len(phrases):
            return [None] * n_boxes
        probs = outputs.logits[0].sigmoid()  # (num_queries, seq_len)
        max_probs = probs.max(dim=-1).values  # (num_queries,)
        out: list[dict[str, float] | None] = []
        used: set[int] = set()
        for box_score in scores_tensor.tolist():
            diffs = (max_probs - float(box_score)).abs()
            order = diffs.argsort().tolist()
            q_idx = next((q for q in order if q not in used), order[0])
            used.add(q_idx)
            q_probs = probs[q_idx]
            out.append({
                phrase: float(q_probs[span].max().item())
                for phrase, span in zip(phrases, spans, strict=True)
                if span and max(span) < q_probs.shape[0]
            })
        return out
    except Exception:
        return [None] * n_boxes


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
        box_phrase_scores = _per_box_phrase_scores(self.processor, outputs, inputs["input_ids"], text_prompt, results["scores"])
        for box, score, label, p_scores in zip(results["boxes"], results["scores"], labels, box_phrase_scores, strict=True):
            x0, y0, x1, y1 = (float(v) for v in box.tolist())
            raw_str = str(label).strip()
            canonical = resolve_prompt_phrase(raw_str, text_prompt, phrase_scores=p_scores, text_threshold=self.text_threshold)
            detections.append(Detection(label=canonical, confidence=float(score), box_xyxy=(x0, y0, x1, y1), raw_label=raw_str))
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
