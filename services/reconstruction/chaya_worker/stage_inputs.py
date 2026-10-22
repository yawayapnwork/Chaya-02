"""What each stage reads: the inputs half of packages/contracts/pipeline/stage-artifacts.json.

The contract is not shipped in the worker image, so it is mirrored here (tests/unit/test_stage_artifact_contract.py keeps
the two equal, and checks every stage reads exactly these kinds). The control plane already offers a stage only its
declared kinds (dev.chaya.api.pipeline.PipelineDefinition#INPUTS); the orchestrator applies the same filter before it
downloads anything, so a work order from an older control plane does not make every stage after SPLAT_RECONSTRUCTION
download its checkpoint and keyframes (gaussian-splatting-audit D-4).

REQUIRED is a list of alternatives per stage: for each group, at least one of its kinds must be offered. Each stage
reports a missing input with its own structured code, after its dependency checks; the orchestrator names the missing
groups only for a stage that crashed instead (Orchestrator._execute).
"""

from __future__ import annotations

from typing import Any

CLOUDS = ["SPLAT_MERGED", "SPLAT_CLEAN", "SPLAT"]
LABELS = ["SEMANTIC_LABELS_MERGED", "SEMANTIC_LABELS_CLEAN", "SEMANTIC_LABELS"]
FRAMES = ["FRAME_ARCHIVE_ANON", "FRAME_ARCHIVE_SELECTED"]
MEDIA = ["RAW_VIDEO", "RAW_IMAGE"]

REQUIRED: dict[str, list[list[str]]] = {
    "INPUT_VALIDATION": [MEDIA],
    "FFMPEG_PREPROCESS": [MEDIA],
    "FRAME_QUALITY_FILTER": [["FRAME_ARCHIVE"]],
    "PRIVACY_PREPROCESS": [["FRAME_ARCHIVE_SELECTED"]],
    "POSE_ESTIMATION": [FRAMES],
    "SPLAT_RECONSTRUCTION": [["SPARSE_MODEL"], ["POSES"], FRAMES],
    "SEMANTIC_SEGMENTATION": [["SPLAT"], ["SPARSE_MODEL"], ["POSES"], FRAMES],
    "GEOMETRIC_CLEANUP": [["SPLAT"], ["SEMANTIC_LABELS"]],
    "REGION_ALIGNMENT": [["GLOBAL_CLOUD"], ["SPLAT_CLEAN"]],
    "REGION_SPLICE": [["GLOBAL_CLOUD"], ["SPLAT_ALIGNED"]],
    "ROOM_REGISTRATION": [["ROOM_CLOUD"]],
    "ROOM_STITCH": [["ROOM_CLOUD"], ["ROOM_LABELS"], ["ROOM_REGISTRATION_REPORT"]],
    "PLANE_FITTING": [["SPLAT_MERGED", "SPLAT_CLEAN"]],
    "ARTIFACT_GENERATION": [CLOUDS],
    "SEMANTIC_INDEXING": [CLOUDS, ["SPARSE_MODEL"], ["POSES"], FRAMES],
    "NAVIGATION_BAKING": [CLOUDS, LABELS, ["PLANE_MODEL"]],
}

OPTIONAL: dict[str, list[str]] = {
    "INPUT_VALIDATION": ["RAW_METADATA"],
    "FFMPEG_PREPROCESS": ["RAW_METADATA"],
    "FRAME_QUALITY_FILTER": [],
    "PRIVACY_PREPROCESS": [],
    "POSE_ESTIMATION": ["CAMERA_CALIBRATION", "PRIVACY_MASKS"],
    # The checkpoint a retry resumes from (dev.chaya.api PipelineService#resumableCheckpoint).
    "SPLAT_RECONSTRUCTION": ["SPLAT_CHECKPOINT", "PRIVACY_MASKS"],
    "SEMANTIC_SEGMENTATION": ["PRIVACY_MASKS"],
    "GEOMETRIC_CLEANUP": [],
    "REGION_ALIGNMENT": [],
    "REGION_SPLICE": ["GLOBAL_LABELS", "SEMANTIC_LABELS_CLEAN"],
    "ROOM_REGISTRATION": ["ROOM_LABELS"],
    "ROOM_STITCH": [],
    "PLANE_FITTING": ["POSES", "SEMANTIC_LABELS_MERGED", "SEMANTIC_LABELS_CLEAN"],
    "ARTIFACT_GENERATION": ["PLANE_MODEL", *LABELS],
    "SEMANTIC_INDEXING": ["ALIGNMENT_REPORT", "PRIVACY_MASKS", *LABELS],
    "NAVIGATION_BAKING": ["GLOBAL_CLOUD", "GLOBAL_LABELS", "SPLICE_REPORT", "STITCH_REPORT"],
}


def declared(stage: str) -> set[str] | None:
    """Every kind `stage` reads, or None for a stage the contract does not cover (it is offered everything)."""
    if stage not in REQUIRED:
        return None
    return {k for group in REQUIRED[stage] for k in group} | set(OPTIONAL[stage])


def offered(stage: str, refs: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """The work order's inputs `stage` reads, in work-order order."""
    kinds = declared(stage)
    return list(refs) if kinds is None else [r for r in refs if r["kind"] in kinds]


def missing(stage: str, refs: list[dict[str, Any]]) -> list[list[str]]:
    """The required groups of `stage` none of whose kinds `refs` offers."""
    present = {r["kind"] for r in refs}
    return [group for group in REQUIRED.get(stage, []) if not present.intersection(group)]
