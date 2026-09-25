"""REGION_ALIGNMENT: aligns a newly captured region (this run's own GEOMETRIC_CLEANUP output, in the arbitrary
frame of this capture's own POSE_ESTIMATION) onto the venue's existing reconstruction (the GLOBAL_CLOUD input,
supplied by the control plane from the selected parent ScanVersion). See chaya_worker.region_alignment for
the feature-matching + ICP algorithm and docs/rescan.md for the end-to-end design.

Two independent SfM reconstructions differ by an arbitrary similarity -- scale included -- so registration
runs in canonical metres (chaya_worker.frames), and both sides must be calibrated:

  * ``parentCoordinateFrame``: the parent reconstruction's canonical frame (metres, +Z up). The venue cloud is
    moved into it, and the region polygon (canonical x/y) crops it.
  * ``coordinateFrame``: this capture's own calibration. Only its metric scale is required; the rotation is
    found by registration. If it is also gravity-aligned the region starts from its canonical pose.

Either missing -> NOT_CALIBRATED, nothing produced. Registration then refines scale; a correction beyond
``alignment_max_scale_correction`` means the two calibrations disagree, and the stage fails
(ALIGNMENT_SCALE_INCONSISTENT) rather than merge.

Output: SPLAT_ALIGNED, the region's Gaussians expressed in the **parent reconstruction's** frame (so the splice
and every later stage keep working in that one calibrated frame), and ALIGNMENT_REPORT with the measured
numbers and the region->parent-reconstruction similarity that SEMANTIC_INDEXING uses to move this capture's
cameras into the same frame.

The stage fails closed (ALIGNMENT_CONFIDENCE_BELOW_THRESHOLD) when the measured confidence is below
`settings.min_alignment_confidence`; the control plane re-checks the same number before finalizing a
ScanVersion. Needs Open3D; without it the stage fails with DEPENDENCY_UNAVAILABLE and nothing is produced.
"""

from __future__ import annotations

import numpy as np

from ..contract import ArtifactSpec, StageContext, StageError, StageResult
from ..frames import Similarity, frame_provenance, require_canonical, require_metric
from ..ply import read_ply, write_ply
from ..region_alignment import align_region
from ..region_splice import transform_gaussians
from .base import command_record, write_json


class RegionAlignment:
    name = "REGION_ALIGNMENT"

    def run(self, ctx: StageContext) -> StageResult:
        parent_frame = require_canonical(ctx.order, self.name, key="parentCoordinateFrame")
        region_frame = require_metric(ctx.order, self.name, key="coordinateFrame")
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
        parent_to_canonical = parent_frame.to_canonical()
        # The region in metres: its full canonical pose if known, otherwise scale only (rotation left to registration).
        region_to_metric = (region_frame.to_canonical() if region_frame.canonical
                            else Similarity(region_frame.metric_scale(), np.eye(3), np.zeros(3)))

        target_positions = _crop_to_region(ctx, parent_to_canonical.apply(global_cloud.positions))
        if len(target_positions) < 10:
            raise StageError("fewer than 10 points of the existing reconstruction fall within (or near) the "
                             "selected region; there is not enough overlap to align against", code="INSUFFICIENT_OVERLAP")

        result = align_region(region_to_metric.apply(region_cloud.positions), target_positions,
                              voxel_size=ctx.settings.alignment_voxel_size_m, with_scaling=True)
        correction = Similarity.from_matrix4(result.transform)
        region_to_canonical = correction.compose(region_to_metric)
        region_to_parent = parent_to_canonical.inverse().compose(region_to_canonical)

        report = {
            "method": result.method, "confidence": result.confidence, "fitness": result.fitness,
            "inlier_rmse_m": result.inlier_rmse, "translation_m": result.translation_m, "rotation_deg": result.rotation_deg,
            "scale_correction": correction.scale, "region_points": len(region_cloud), "target_points": len(target_positions),
            "min_alignment_confidence": ctx.settings.min_alignment_confidence,
            "max_scale_correction": ctx.settings.alignment_max_scale_correction,
            "parent_coordinate_frame": frame_provenance(parent_frame),
            "region_coordinate_frame": {**frame_provenance(region_frame), "gravity_aligned": region_frame.gravity_aligned},
            "region_to_canonical": region_to_canonical.to_dict(),
            "region_to_parent_reconstruction": region_to_parent.to_dict(),
        }
        report_path = write_json(ctx.workdir / "alignment-report.json", report)

        if abs(correction.scale - 1.0) > ctx.settings.alignment_max_scale_correction:
            raise StageError(
                f"registration needed a scale correction of {correction.scale:.3f}; the region's metric calibration and the "
                f"venue's disagree by more than {ctx.settings.alignment_max_scale_correction:.0%}. Refusing to splice.",
                code="ALIGNMENT_SCALE_INCONSISTENT", details=report)
        if result.confidence < ctx.settings.min_alignment_confidence:
            ctx.logger.warning("alignment confidence below threshold", extra={"confidence": result.confidence,
                               "threshold": ctx.settings.min_alignment_confidence})
            raise StageError(
                f"alignment confidence {result.confidence:.3f} is below the configured threshold "
                f"{ctx.settings.min_alignment_confidence:.3f}; refusing to splice a badly aligned region",
                code="ALIGNMENT_CONFIDENCE_BELOW_THRESHOLD", details=report)

        aligned = transform_gaussians(region_cloud, region_to_parent)
        aligned_path = write_ply(aligned, ctx.workdir / "splat-aligned.ply")
        ctx.logger.info("region aligned", extra={"confidence": result.confidence, "fitness": result.fitness,
                        "inlier_rmse_m": result.inlier_rmse, "scale_correction": correction.scale})
        return StageResult("SUCCEEDED", command_record(ctx, {"alignment": report}), ctx.runner.last_exit_status(),
                           [ArtifactSpec("ALIGNMENT_REPORT", report_path, "alignment-report.json", "application/json"),
                            ArtifactSpec("SPLAT_ALIGNED", aligned_path, "splat-aligned.ply", "application/octet-stream")])


def _crop_to_region(ctx: StageContext, canonical_positions: np.ndarray) -> np.ndarray:
    """Restricts the venue cloud (already in canonical metres) to the selected region's horizontal bounding box,
    expanded by a fixed 1 m margin, before registration -- real, useful scoping (matching against the whole venue
    would be slower and more error-prone), never a substitute for the polygon test REGION_SPLICE itself does."""
    geometry = ctx.order.get("regionGeometry")
    points = geometry.get("points") if isinstance(geometry, dict) else None
    if not points or len(points) < 3:
        return canonical_positions  # no usable region geometry on the order; align against everything provided
    polygon = np.array(points, dtype=np.float64)
    margin = 1.0  # metres (canonical); registration needs some context beyond the exact selected boundary
    min_xy = polygon.min(axis=0) - margin
    max_xy = polygon.max(axis=0) + margin
    in_box = np.all((canonical_positions[:, :2] >= min_xy) & (canonical_positions[:, :2] <= max_xy), axis=1)
    return canonical_positions[in_box]
