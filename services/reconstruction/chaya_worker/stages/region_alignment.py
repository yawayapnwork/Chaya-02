"""REGION_ALIGNMENT: aligns a newly captured region (this run's own GEOMETRIC_CLEANUP output, in an
arbitrary local frame from this capture's own POSE_ESTIMATION) onto the venue's existing global
reconstruction (the GLOBAL_CLOUD input, supplied by the control plane from the selected parent
ScanVersion). See chaya_worker.region_alignment for the real feature-matching + ICP algorithm and
docs/rescan.md for the end-to-end incremental re-scan design.

The stage fails closed (ALIGNMENT_CONFIDENCE_BELOW_THRESHOLD) when the measured confidence is below
`settings.min_alignment_confidence` -- this is the worker's own copy of the quality gate; the control plane
re-checks the same number independently before ever finalizing a ScanVersion (see
dev.chaya.api.rescan.RescanService), so a bad alignment cannot be merged even if one side's threshold were
misconfigured. Needs Open3D; without it the stage fails with DEPENDENCY_UNAVAILABLE and nothing is produced.
"""

from __future__ import annotations

import numpy as np

from ..contract import ArtifactSpec, StageContext, StageError, StageResult
from ..ply import read_ply, write_ply
from ..region_alignment import align_region
from ..region_splice import transform_gaussians
from .base import command_record, write_json


class RegionAlignment:
    name = "REGION_ALIGNMENT"

    def run(self, ctx: StageContext) -> StageResult:
        ctx.toolchain.require(["py:open3d"], stage=self.name)

        region_inputs = ctx.inputs_of("SPLAT_CLEAN")
        if not region_inputs:
            raise StageError("no SPLAT_CLEAN (the captured region's cleaned splat) was provided", code="INPUT_INVALID")
        global_inputs = ctx.inputs_of("GLOBAL_CLOUD")
        if not global_inputs:
            raise StageError("no GLOBAL_CLOUD (the venue's existing reconstruction) was provided; "
                             "an incremental re-scan cannot align against nothing", code="INPUT_INVALID")

        region_cloud = read_ply(region_inputs[0].path)
        global_cloud = read_ply(global_inputs[0].path)

        target_positions = _crop_to_region(ctx, global_cloud.positions)
        if len(target_positions) < 10:
            raise StageError("fewer than 10 points of the existing reconstruction fall within (or near) the "
                             "selected region; there is not enough overlap to align against", code="INSUFFICIENT_OVERLAP")

        result = align_region(region_cloud.positions.astype(np.float64), target_positions, voxel_size=ctx.settings.alignment_voxel_size_m)

        report = {
            "method": result.method, "confidence": result.confidence, "fitness": result.fitness,
            "inlier_rmse_m": result.inlier_rmse, "translation_m": result.translation_m, "rotation_deg": result.rotation_deg,
            "transform": result.transform.tolist(), "region_points": len(region_cloud), "target_points": len(target_positions),
            "min_alignment_confidence": ctx.settings.min_alignment_confidence,
        }
        report_path = write_json(ctx.workdir / "alignment-report.json", report)
        report_artifact = ArtifactSpec("ALIGNMENT_REPORT", report_path, "alignment-report.json", "application/json")

        if result.confidence < ctx.settings.min_alignment_confidence:
            ctx.logger.warning("alignment confidence below threshold", extra={"confidence": result.confidence,
                               "threshold": ctx.settings.min_alignment_confidence})
            raise StageError(
                f"alignment confidence {result.confidence:.3f} is below the configured threshold "
                f"{ctx.settings.min_alignment_confidence:.3f}; refusing to splice a badly aligned region",
                code="ALIGNMENT_CONFIDENCE_BELOW_THRESHOLD", details=report)

        aligned = transform_gaussians(region_cloud, result.transform)
        aligned_path = write_ply(aligned, ctx.workdir / "splat-aligned.ply")
        aligned_artifact = ArtifactSpec("SPLAT_ALIGNED", aligned_path, "splat-aligned.ply", "application/octet-stream")

        ctx.logger.info("region aligned", extra={"confidence": result.confidence, "fitness": result.fitness,
                        "inlier_rmse_m": result.inlier_rmse})
        return StageResult("SUCCEEDED", command_record(ctx, {"alignment": report}), ctx.runner.last_exit_status(),
                           [report_artifact, aligned_artifact])


def _crop_to_region(ctx: StageContext, positions: np.ndarray) -> np.ndarray:
    """Restricts the (large) existing global cloud to the selected region's bounding box, expanded by a
    fixed margin, before registration -- real, useful scoping (matching against the whole venue would be
    slower and more error-prone), never a substitute for the polygon test REGION_SPLICE itself does."""
    geometry = ctx.order.get("regionGeometry")
    points = geometry.get("points") if isinstance(geometry, dict) else None
    if not points or len(points) < 3:
        return positions  # no usable region geometry on the order; align against everything provided
    polygon = np.array(points, dtype=np.float64)
    margin = 1.0  # meters; real registration needs some context beyond the exact selected boundary
    min_xy = polygon.min(axis=0) - margin
    max_xy = polygon.max(axis=0) + margin
    in_box = np.all((positions[:, :2] >= min_xy) & (positions[:, :2] <= max_xy), axis=1)
    return positions[in_box]
