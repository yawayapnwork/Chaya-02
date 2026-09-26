"""REGION_SPLICE: replaces the changed volume of the venue's existing reconstruction with the region REGION_ALIGNMENT
aligned (chaya_worker.region_splice). It needs REGION_ALIGNMENT to have passed its gates first: SPLAT_ALIGNED, its
required input, is only ever published by an accepted alignment.

The region polygon is in canonical metres; both clouds are in the parent reconstruction's frame. The work order's
``coordinateFrame`` for this stage is that parent frame, and it must be canonical (NOT_CALIBRATED otherwise). The merged
output stays in the parent reconstruction's frame, so the parent's calibration applies to it unchanged. The parent's own
artifacts are only read: the merge is a new SPLAT_MERGED, under this run.

Published:
  * SPLAT_MERGED;
  * SPLICE_REPORT: the replaced volume, the counts, the seam measurement, and the exact inputs and output (artifact ids
    and SHA-256);
  * SPLICE_INDEX (`splice-index.npz`): the indices of the removed venue Gaussians and the added region Gaussians, so
    exactly what was replaced can be reconstructed or audited.

A visible seam or nothing to add fails the stage with SPLICE_REJECTED, and nothing is published.
"""

from __future__ import annotations

import numpy as np

from ..contract import ArtifactSpec, StageContext, StageError, StageResult
from ..frames import require_canonical
from ..ply import read_ply, write_ply
from ..region_splice import SpliceRejected, splice_region
from .base import command_record, sha256_file, write_json


class RegionSplice:
    name = "REGION_SPLICE"

    def run(self, ctx: StageContext) -> StageResult:
        s = ctx.settings
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
            result = splice_region(global_cloud, aligned_cloud, polygon, parent_frame.to_canonical(), z_margin_m=s.splice_z_margin_m,
                                   seam_band_m=s.splice_seam_band_m, max_seam_step_m=s.splice_max_seam_step_m)
        except SpliceRejected as exc:
            raise StageError(str(exc), code="SPLICE_REJECTED") from exc

        merged_path = write_ply(result.merged, ctx.workdir / "splat-merged.ply")
        index_path = ctx.workdir / "splice-index.npz"
        with open(index_path, "wb") as f:  # a file handle: np.savez would otherwise append .npz to the name
            np.savez_compressed(f, removed_global_indices=result.removed_global_indices, added_region_indices=result.added_region_indices)
        report = {
            **result.report,
            "coordinate_frame_id": parent_frame.id,
            "inputs": {"global_cloud": _ref(global_inputs[0], len(global_cloud)), "aligned_region": _ref(aligned_inputs[0], len(aligned_cloud))},
            "output": {"artifact": "splat-merged.ply", "sha256": sha256_file(merged_path), "gaussians": len(result.merged)},
            "index": {"artifact": "splice-index.npz", "sha256": sha256_file(index_path),
                      "arrays": ["removed_global_indices", "added_region_indices"]},
        }
        report_path = write_json(ctx.workdir / "splice-report.json", report)
        ctx.logger.info("region spliced", extra={k: v for k, v in report.items() if isinstance(v, int)})
        return StageResult("SUCCEEDED", command_record(ctx, {"splice": report}), ctx.runner.last_exit_status(), [
            ArtifactSpec("SPLAT_MERGED", merged_path, "splat-merged.ply", "application/octet-stream"),
            ArtifactSpec("SPLICE_REPORT", report_path, "splice-report.json", "application/json"),
            ArtifactSpec("SPLICE_INDEX", index_path, "splice-index.npz", "application/octet-stream"),
        ])


def _ref(f, gaussians: int) -> dict:
    return {"artifact_id": f.artifact_id, "sha256": f.ref.get("sha256"), "gaussians": gaussians}
