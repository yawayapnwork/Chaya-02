"""Reproducible fine-tuning of the semantic models (segmentation: segmentation.py; detection: detection.py).

Nothing here has produced a checkpoint used by this project yet: no legally usable indoor training set exists, and this
project has no GPU host (docs/ML_EVALUATION.md). The harness is complete and smoke-tested on tiny randomly initialised
models (tests/ml), so that a real run needs only a dataset, a GPU and a base model whose licence permits the use.

Every run refuses to start unless (common.py):
  * the dataset validates and matches its lock (chaya_worker.datasets.validate), has a train and a val split, and the test
    split is never read;
  * the base model's licence is recorded and, if it restricts use (e.g. SegFormer's research-only licence), the operator
    acknowledged that with --accept-base-license research-only;
  * a CUDA device is present, unless --allow-cpu (smoke tests only; the card records the device).
It writes the checkpoint in safetensors with chaya_model_card.json (chaya_worker.model_provenance), which is the only thing
that makes a checkpoint count as fine-tuned anywhere in the system.
"""
