"""The stage registry. Order and names mirror PipelineDefinition on the control plane."""

from __future__ import annotations

from ..contract import Stage
from .artifact_generation import ArtifactGeneration
from .ffmpeg_preprocess import FfmpegPreprocess
from .frame_quality import FrameQualityFilter
from .geometric_cleanup import GeometricCleanup
from .input_validation import InputValidation
from .plane_fitting import PlaneFitting
from .pose_estimation import PoseEstimation
from .privacy import PrivacyPreprocess
from .semantic_indexing import SemanticIndexing
from .semantic_segmentation import SemanticSegmentation
from .splat_reconstruction import SplatReconstruction
from .unimplemented import PLANNED, PlannedStage

STAGE_ORDER = [
    "INPUT_VALIDATION", "FFMPEG_PREPROCESS", "FRAME_QUALITY_FILTER", "PRIVACY_PREPROCESS", "POSE_ESTIMATION",
    "SPLAT_RECONSTRUCTION", "SEMANTIC_SEGMENTATION", "GEOMETRIC_CLEANUP", "PLANE_FITTING", "ARTIFACT_GENERATION",
    "NAVIGATION_BAKING", "SEMANTIC_INDEXING",
]
PRIVACY_STAGE = "PRIVACY_PREPROCESS"


def default_registry() -> dict[str, Stage]:
    stages: list[Stage] = [InputValidation(), FfmpegPreprocess(), FrameQualityFilter(), PrivacyPreprocess(), PoseEstimation(),
                           SplatReconstruction(), SemanticSegmentation(), GeometricCleanup(), PlaneFitting(), ArtifactGeneration(),
                           SemanticIndexing(), *(PlannedStage(name) for name in PLANNED)]
    registry = {s.name: s for s in stages}
    missing = [n for n in STAGE_ORDER if n not in registry]
    if missing:
        raise RuntimeError(f"stages missing from the registry: {missing}")
    return registry


def runs_after_privacy(stage: str) -> bool:
    return stage in STAGE_ORDER and STAGE_ORDER.index(stage) > STAGE_ORDER.index(PRIVACY_STAGE)
