"""The stage registry. Order and names mirror PipelineDefinition on the control plane."""

from __future__ import annotations

from ..contract import Stage
from .artifact_generation import ArtifactGeneration
from .ffmpeg_preprocess import FfmpegPreprocess
from .frame_quality import FrameQualityFilter
from .geometric_cleanup import GeometricCleanup
from .input_validation import InputValidation
from .navigation_baking import NavigationBaking
from .plane_fitting import PlaneFitting
from .pose_estimation import PoseEstimation
from .privacy import PrivacyPreprocess
from .region_alignment import RegionAlignment
from .region_splice import RegionSplice
from .semantic_indexing import SemanticIndexing
from .semantic_segmentation import SemanticSegmentation
from .splat_reconstruction import SplatReconstruction
from .unimplemented import PLANNED, PlannedStage

STAGE_ORDER = [
    "INPUT_VALIDATION", "FFMPEG_PREPROCESS", "FRAME_QUALITY_FILTER", "PRIVACY_PREPROCESS", "POSE_ESTIMATION",
    "SPLAT_RECONSTRUCTION", "SEMANTIC_SEGMENTATION", "GEOMETRIC_CLEANUP",
    # REGION_ALIGNMENT/REGION_SPLICE only appear in an incremental re-scan's plan (see
    # dev.chaya.api.pipeline.PipelineDefinition.INCREMENTAL_STAGES and docs/rescan.md); a full-venue
    # reconstruction plan never includes them. They sit here, after GEOMETRIC_CLEANUP and before
    # PLANE_FITTING, because PLANE_FITTING/ARTIFACT_GENERATION/NAVIGATION_BAKING/SEMANTIC_INDEXING must run
    # on the spliced, venue-wide result (SPLAT_MERGED), not on the region alone.
    "REGION_ALIGNMENT", "REGION_SPLICE",
    "PLANE_FITTING", "ARTIFACT_GENERATION", "SEMANTIC_INDEXING", "NAVIGATION_BAKING",
]
# SEMANTIC_INDEXING runs before NAVIGATION_BAKING (not the order the two were originally planned in):
# neither stage depends on the other's output, and a stage failure stops the run from advancing (see
# dev.chaya.api.pipeline.PipelineService#advance). chaya-navmesh (NAVIGATION_BAKING's hard dependency, the
# Recast/Detour tool in services/reconstruction/native/chaya-navmesh) is a separately built binary a worker may not have, so putting
# it last means a worker without it still gets a fully searchable reconstruction -- only routing is
# unavailable, not search too.
PRIVACY_STAGE = "PRIVACY_PREPROCESS"


def default_registry() -> dict[str, Stage]:
    stages: list[Stage] = [InputValidation(), FfmpegPreprocess(), FrameQualityFilter(), PrivacyPreprocess(), PoseEstimation(),
                           SplatReconstruction(), SemanticSegmentation(), GeometricCleanup(), RegionAlignment(), RegionSplice(),
                           PlaneFitting(), ArtifactGeneration(), NavigationBaking(), SemanticIndexing(),
                           *(PlannedStage(name) for name in PLANNED)]
    registry = {s.name: s for s in stages}
    missing = [n for n in STAGE_ORDER if n not in registry]
    if missing:
        raise RuntimeError(f"stages missing from the registry: {missing}")
    return registry


def runs_after_privacy(stage: str) -> bool:
    return stage in STAGE_ORDER and STAGE_ORDER.index(stage) > STAGE_ORDER.index(PRIVACY_STAGE)
