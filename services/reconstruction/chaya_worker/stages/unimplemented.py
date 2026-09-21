"""Stages 6-12 exist in the pipeline plan, but their implementations are not written yet.

They never fabricate output. Each one first reports missing dependencies (structured, actionable); if the
dependencies are present it still fails with STAGE_NOT_IMPLEMENTED. There is deliberately no code path
that returns SUCCEEDED or writes a .ply/.splat/.ksplat/mesh without the real algorithm behind it.
"""

from __future__ import annotations

from ..contract import StageContext, StageResult
from ..errors import StageNotImplemented

# stage -> (requirements, what it will do)
PLANNED: dict[str, tuple[list[str], str]] = {
    "SPLAT_RECONSTRUCTION": (["py:torch", "py:gsplat", "cuda"], "3D Gaussian Splatting training with gsplat"),
    "SEMANTIC_SEGMENTATION": (["py:torch", "py:transformers"], "semantic segmentation (SegFormer / Mask2Former)"),
    "GEOMETRIC_CLEANUP": (["py:open3d"], "Open3D geometric cleanup"),
    "PLANE_FITTING": (["py:open3d"], "plane fitting on the cleaned geometry"),
    "ARTIFACT_GENERATION": (["py:open3d"], "generation of the deliverable artifacts (.ksplat, mesh)"),
    "NAVIGATION_BAKING": (["exe:recast-cli"], "Recast navigation mesh baking"),
    "SEMANTIC_INDEXING": (["py:torch", "py:open_clip"], "object detection and CLIP semantic indexing"),
}


class PlannedStage:
    def __init__(self, name: str) -> None:
        self.name = name
        self.requirements, self.description = PLANNED[name]

    def run(self, ctx: StageContext) -> StageResult:
        ctx.toolchain.require(self.requirements, stage=self.name)
        raise StageNotImplemented(
            f"{self.name} is not implemented yet ({self.description}). No output was produced and none was simulated.",
            details={"requirements": self.requirements, "requirements_present": True})
