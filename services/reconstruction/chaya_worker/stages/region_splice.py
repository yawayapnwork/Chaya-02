"""REGION_SPLICE: replaces the changed region of the venue's existing global reconstruction with the
newly aligned region from REGION_ALIGNMENT. Real crop + concatenation (chaya_worker.region_splice); the
rest of the venue's geometry is untouched. Requires REGION_ALIGNMENT to have actually succeeded first (its
SPLAT_ALIGNED output is this stage's required input) -- there is no path that reaches SPLICE without a
real alignment having run and cleared the confidence gate.

The region polygon is in canonical metres (chaya_worker.frames); both clouds are in the parent reconstruction's
frame. The work order's ``coordinateFrame`` for this stage is that parent frame, and it must be canonical
(NOT_CALIBRATED otherwise) so the polygon test can be done on canonical (x, y). The merged output stays in the
parent reconstruction's frame, so the parent's calibration applies to it unchanged.
"""

from __future__ import annotations

import numpy as np

from ..contract import ArtifactSpec, StageContext, StageError, StageResult
from ..frames import require_canonical
from ..ply import read_ply, write_ply
from ..region_splice import splice_region
from .base import command_record, write_json


class RegionSplice:
    name = "REGION_SPLICE"

    def run(self, ctx: StageContext) -> StageResult:
        parent_frame = require_canonical(ctx.order, self.name)
        aligned_inputs = ctx.inputs_of("SPLAT_ALIGNED")
        if not aligned_inputs:
            raise StageError("no SPLAT_ALIGNED was provided by REGION_ALIGNMENT", code="INPUT_INVALID")
        global_inputs = ctx.inputs_of("GLOBAL_CLOUD")
        if not global_inputs:
            raise StageError("no GLOBAL_CLOUD (the venue's existing reconstruction) was provided", code="INPUT_INVALID")

        geometry = ctx.order.get("regionGeometry")
        points = geometry.get("points") if isinstance(geometry, dict) else None
        if not points or len(points) < 3:
            raise StageError("no region geometry (>= 3 points) was provided on the work order", code="INPUT_INVALID")
        polygon = np.array(points, dtype=np.float64)

        aligned_cloud = read_ply(aligned_inputs[0].path)
        global_cloud = read_ply(global_inputs[0].path)

        try:
            merged, report = splice_region(global_cloud, aligned_cloud, polygon, parent_frame.to_canonical())
        except ValueError as exc:
            raise StageError(str(exc), code="SPLICE_EMPTY_REGION") from exc
        report["coordinate_frame_id"] = parent_frame.id
        ctx.logger.info("region spliced", extra=report)

        merged_path = write_ply(merged, ctx.workdir / "splat-merged.ply")
        report_path = write_json(ctx.workdir / "splice-report.json", report)

        artifacts = [
            ArtifactSpec("SPLAT_MERGED", merged_path, "splat-merged.ply", "application/octet-stream"),
            ArtifactSpec("SPLICE_REPORT", report_path, "splice-report.json", "application/json"),
        ]
        return StageResult("SUCCEEDED", command_record(ctx, {"splice": report}), ctx.runner.last_exit_status(), artifacts)
