"""No stage in the pipeline plan is unimplemented anymore -- all twelve stages (INPUT_VALIDATION through
SEMANTIC_INDEXING) have real implementations, registered directly in chaya_worker/stages/__init__.py.

This module (and PlannedStage) stays as the pattern a future stage should follow: report missing
dependencies first (structured, actionable), and never fabricate output. There is deliberately no code
path anywhere in this worker that returns SUCCEEDED without the real algorithm behind it.
"""

from __future__ import annotations

from ..contract import StageContext, StageResult
from ..errors import StageNotImplemented

# stage -> (requirements, what it will do)
PLANNED: dict[str, tuple[list[str], str]] = {}


class PlannedStage:
    def __init__(self, name: str) -> None:
        self.name = name
        self.requirements, self.description = PLANNED[name]

    def run(self, ctx: StageContext) -> StageResult:
        ctx.toolchain.require(self.requirements, stage=self.name)
        raise StageNotImplemented(
            f"{self.name} is not implemented yet ({self.description}). No output was produced and none was simulated.",
            details={"requirements": self.requirements, "requirements_present": True})
