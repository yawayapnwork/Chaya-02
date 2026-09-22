"""Stages 11-12 exist in the pipeline plan, but their implementations are not written yet.

They never fabricate output. Each one first reports missing dependencies (structured, actionable); if the
dependencies are present it still fails with STAGE_NOT_IMPLEMENTED. There is deliberately no code path
that returns SUCCEEDED or writes a navmesh/embedding index without the real algorithm behind it.

Stages 6-10 (SPLAT_RECONSTRUCTION, SEMANTIC_SEGMENTATION, GEOMETRIC_CLEANUP, PLANE_FITTING,
ARTIFACT_GENERATION) are implemented -- see chaya_worker/stages/{splat_reconstruction,semantic_segmentation,
geometric_cleanup,plane_fitting,artifact_generation}.py -- and are registered directly in
chaya_worker/stages/__init__.py, not here.
"""

from __future__ import annotations

from ..contract import StageContext, StageResult
from ..errors import StageNotImplemented

# stage -> (requirements, what it will do)
PLANNED: dict[str, tuple[list[str], str]] = {
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
