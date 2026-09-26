"""REGION_ALIGNMENT: places a newly captured region (this run's GEOMETRIC_CLEANUP output, in its own capture's
reconstruction frame) in the venue's existing reconstruction (GLOBAL_CLOUD, the selected parent version's cloud).
docs/rescan.md, "ALIGNMENT".

It never does a rigid-only fit between two reconstruction frames. Registration happens in the canonical venue frame
(metres, +Z up; chaya_worker.frames):

  * ``parentCoordinateFrame`` must be canonical. It moves the venue cloud into canonical metres, where the region
    polygon crops it.
  * ``coordinateFrame`` (this capture's calibration) must at least be metric. Otherwise: NOT_CALIBRATED, nothing is
    produced, nothing is merged.

The mode follows from the region's calibration (chaya_worker.region_alignment):

  * **DIRECT_CANONICAL**: region and parent are both canonical and surveyed to the venue control points. The region's
    own calibration places it; ICP verifies and refines, and the size of its correction is gated.
  * **FEATURE_SIMILARITY**: otherwise. A full similarity (scale, rotation, translation) comes from FPFH
    correspondences + RANSAC + ICP. The starting point is the region's canonical pose if it is gravity-aligned (then
    the correction may not tilt it), or its metric scale alone. Needs Open3D for the FPFH descriptors.

Every alignment is measured and gated: correspondence count, inlier ratio, scale, rotation / translation / ICP
residuals, confidence, and the mode's own gates. If any gate fails, the stage fails with **ALIGNMENT_REJECTED**. It
publishes nothing, and the error details carry the full report: every gate's value and threshold, and the metrics. The
control plane marks the ScanVersion ALIGNMENT_REJECTED. The parent's finalized reconstruction is only ever read, never
written.

On success: SPLAT_ALIGNED (the region's Gaussians in the **parent reconstruction's** frame, so the splice and every
later stage work in that one calibrated frame) and ALIGNMENT_REPORT. The report holds the same numbers plus the
region->canonical and region->parent-reconstruction similarities, which SEMANTIC_INDEXING uses to move this capture's
cameras into the merged frame.
"""

from __future__ import annotations

import numpy as np

from ..contract import ArtifactSpec, StageContext, StageError, StageResult
from ..frames import Similarity, frame_provenance, require_canonical, require_metric
from ..ply import read_ply, write_ply
from ..region_alignment import MODE_DIRECT, MODE_FEATURE, align_region
from ..region_splice import transform_gaussians
from .base import command_record, write_json

VENUE_DATUM = "VENUE_CONTROL_POINTS"


def choose_mode(parent_frame, region_frame) -> str:
    """DIRECT_CANONICAL only when both frames are canonical and surveyed to the same venue datum. Two FLOOR_LOCAL frames
    each have their own origin and heading, so a region calibrated that way still has to be registered."""
    if (region_frame.canonical and region_frame.horizontal_datum == VENUE_DATUM and parent_frame.horizontal_datum == VENUE_DATUM):
        return MODE_DIRECT
    return MODE_FEATURE


def region_box(ctx: StageContext, margin_m: float) -> tuple[np.ndarray, np.ndarray] | None:
    """The polygon's horizontal bounding box grown by `margin_m`, in canonical metres, or None without region geometry."""
    geometry = ctx.order.get("regionGeometry")
    points = geometry.get("points") if isinstance(geometry, dict) else None
    if not points or len(points) < 3:
        return None
    polygon = np.array(points, dtype=np.float64)
    return polygon.min(axis=0) - margin_m, polygon.max(axis=0) + margin_m


def crop_xy(points: np.ndarray, box) -> np.ndarray:
    if box is None:
        return points
    lo, hi = box
    return points[np.all((points[:, :2] >= lo) & (points[:, :2] <= hi), axis=1)]


class RegionAlignment:
    name = "REGION_ALIGNMENT"

    def run(self, ctx: StageContext) -> StageResult:
        s = ctx.settings
        parent_frame = require_canonical(ctx.order, self.name, key="parentCoordinateFrame")
        region_frame = require_metric(ctx.order, self.name, key="coordinateFrame")
        mode = choose_mode(parent_frame, region_frame)
        if mode == MODE_FEATURE:
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
        gravity_known = region_frame.canonical
        # The region in metres: its canonical pose when known, otherwise its metric scale only (registration finds the rest).
        region_to_metric = (region_frame.to_canonical() if gravity_known
                            else Similarity(region_frame.metric_scale(), np.eye(3), np.zeros(3)))

        box = region_box(ctx, s.alignment_crop_margin_m)
        target = crop_xy(parent_to_canonical.apply(global_cloud.positions), box)
        source = region_to_metric.apply(region_cloud.positions)
        if mode == MODE_DIRECT and box is not None:  # the region's position is known, so drop what cannot overlap
            source = crop_xy(source, (box[0] - s.alignment_crop_margin_m, box[1] + s.alignment_crop_margin_m))

        result = align_region(source, target, mode=mode, gates=s.alignment_gates(), icp_schedule_m=s.alignment_schedule(),
                              icp_voxel_m=s.alignment_icp_voxel_m, feature_voxel_m=s.alignment_voxel_size_m,
                              gravity_aligned=gravity_known, seed=0)
        region_to_canonical = result.correction.compose(region_to_metric)
        region_to_parent = parent_to_canonical.inverse().compose(region_to_canonical)

        report = _json_safe({
            "status": result.status, "mode": result.mode, "method": result.method, "error": result.error,
            "confidence": result.metrics.confidence, "inlier_rmse_m": result.metrics.icp_residual_m,
            "metrics": result.metrics.as_dict(), "gates": result.gates.as_list(), "gates_passed": result.gates.passed,
            "failed_gates": result.gates.failed, "details": result.details,
            "scale_correction": result.correction.scale,
            "inputs": {"region": _ref(region_inputs[0]), "global_cloud": _ref(global_inputs[0])},
            "parent_coordinate_frame": frame_provenance(parent_frame),
            "region_coordinate_frame": {**frame_provenance(region_frame), "gravity_aligned": region_frame.gravity_aligned},
            "correction": result.correction.to_dict(),
            "region_to_canonical": region_to_canonical.to_dict(),
            "region_to_parent_reconstruction": region_to_parent.to_dict(),
        })
        ctx.logger.info("region alignment measured", extra={"status": result.status, "mode": result.mode,
                        "failed_gates": result.gates.failed, **{k: v for k, v in result.metrics.as_dict().items()}})
        if not result.accepted:
            reasons = result.error or ", ".join(f"{r.name}={r.value} (needs {r.comparison} {r.threshold})"
                                                for r in result.gates.results if not r.passed)
            raise StageError(f"alignment rejected ({result.mode}): {reasons}. The venue's existing reconstruction is unchanged.",
                             code="ALIGNMENT_REJECTED", details=report)

        report_path = write_json(ctx.workdir / "alignment-report.json", report)
        aligned = transform_gaussians(region_cloud, region_to_parent)
        aligned_path = write_ply(aligned, ctx.workdir / "splat-aligned.ply")
        return StageResult("SUCCEEDED", command_record(ctx, {"alignment": report}), ctx.runner.last_exit_status(),
                           [ArtifactSpec("ALIGNMENT_REPORT", report_path, "alignment-report.json", "application/json"),
                            ArtifactSpec("SPLAT_ALIGNED", aligned_path, "splat-aligned.ply", "application/octet-stream")])


def _ref(f) -> dict:
    return {"artifact_id": f.artifact_id, "kind": f.kind, "sha256": f.ref.get("sha256")}


def _json_safe(value):
    """Non-finite numbers (an infinite residual when nothing corresponded) become null: the report must be valid JSON."""
    if isinstance(value, dict):
        return {k: _json_safe(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [_json_safe(v) for v in value]
    if isinstance(value, (float, np.floating)):
        return float(value) if np.isfinite(value) else None
    if isinstance(value, np.integer):
        return int(value)
    return value
