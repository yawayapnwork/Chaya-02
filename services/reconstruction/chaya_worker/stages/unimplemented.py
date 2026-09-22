"""Stage 11 exists in the pipeline plan, but its implementation is not written yet.

It never fabricates output: it first reports missing dependencies (structured, actionable); if they are
present it still fails with STAGE_NOT_IMPLEMENTED. There is deliberately no code path that returns
SUCCEEDED or writes a navmesh without the real algorithm behind it.

Stages 6-10 and 12 (SPLAT_RECONSTRUCTION, SEMANTIC_SEGMENTATION, GEOMETRIC_CLEANUP, PLANE_FITTING,
ARTIFACT_GENERATION, SEMANTIC_INDEXING) are implemented -- see chaya_worker/stages/{splat_reconstruction,
semantic_segmentation,geometric_cleanup,plane_fitting,artifact_generation,semantic_indexing}.py -- and are
registered directly in chaya_worker/stages/__init__.py, not here.
"""

from __future__ import annotations

from ..contract import StageContext, StageResult
from ..errors import StageNotImplemented

# stage -> (requirements, what it will do)
PLANNED: dict[str, tuple[list[str], str]] = {
    "NAVIGATION_BAKING": (["exe:recast-cli"], "Recast navigation mesh baking"),
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
