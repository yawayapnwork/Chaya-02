"""Stage 9: RANSAC plane fitting on the cleaned geometry (chaya_worker.geometry_cleanup.fit_planes).

Extracts up to `plane_max_planes` dominant planes and classifies each as floor/wall/other from its normal
relative to a data-driven "up" axis; when SEMANTIC_LABELS_CLEAN is available, each plane also reports how
well that geometric classification agrees with the semantic segmentation of its own inlier points. Needs
Open3D (RANSAC plane segmentation); fails structured, produces nothing, if it is missing.
"""

from __future__ import annotations

import numpy as np

from ..contract import ArtifactSpec, StageContext, StageError, StageResult
from ..geometry_cleanup import fit_planes
from ..ply import read_ply
from .base import command_record, write_json


class PlaneFitting:
    name = "PLANE_FITTING"

    def run(self, ctx: StageContext) -> StageResult:
        ctx.toolchain.require(["py:open3d"], stage=self.name)
        splats = ctx.inputs_of("GAUSSIAN_SPLAT_CLEAN")
        if not splats:
            raise StageError("no GAUSSIAN_SPLAT_CLEAN was provided by GEOMETRIC_CLEANUP", code="INPUT_INVALID")
        labels_inputs = ctx.inputs_of("SEMANTIC_LABELS_CLEAN")

        cloud = read_ply(splats[0].path)
        labels = None
        if labels_inputs:
            import json

            labels = np.array(json.loads(labels_inputs[0].path.read_text(encoding="utf-8"))["labels"], dtype=object)
            if len(labels) != len(cloud):
                raise StageError(f"SEMANTIC_LABELS_CLEAN has {len(labels)} entries but the splat has {len(cloud)} Gaussians",
                                 code="INPUT_INVALID")

        s = ctx.settings
        planes = fit_planes(cloud.positions, distance_threshold=s.plane_ransac_distance_threshold, ransac_n=s.plane_ransac_n,
                            num_iterations=s.plane_ransac_iterations, max_planes=s.plane_max_planes, min_inliers=s.plane_min_inliers,
                            labels=labels)
        ctx.logger.info("plane fitting done", extra={"planes_found": len(planes), "points": len(cloud)})

        planes_doc = {
            "gaussian_count": len(cloud), "planes_found": len(planes),
            "planes": [{"equation": list(p.equation), "classification": p.classification, "inlier_count": len(p.inlier_indices),
                       "confidence": p.confidence, "inlier_indices": [int(i) for i in p.inlier_indices]} for p in planes],
        }
        planes_path = write_json(ctx.workdir / "planes.json", planes_doc)
        return StageResult(
            "SUCCEEDED", command_record(ctx, {"planes_found": len(planes)}), ctx.runner.last_exit_status(),
            [ArtifactSpec("PLANE_MODEL", planes_path, "planes.json", "application/json")])
