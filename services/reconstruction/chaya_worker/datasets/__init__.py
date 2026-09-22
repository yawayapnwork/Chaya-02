"""Project-specific object-detection annotation dataset format and tooling. See scaffold.py's module
docstring for the on-disk layout. Independent of the runtime SEMANTIC_INDEXING pipeline (chaya_worker
.stages.semantic_indexing): this is training data for a future fine-tuning run, not pipeline output."""

from .schema import Annotation, BoundingBox, DatasetManifest, ImageRecord, Provenance

__all__ = ["Annotation", "BoundingBox", "DatasetManifest", "ImageRecord", "Provenance"]
