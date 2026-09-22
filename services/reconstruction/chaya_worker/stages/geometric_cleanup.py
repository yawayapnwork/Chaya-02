"""Stage 8: Open3D geometric cleanup of the trained Gaussian splat.

Three real cleanup passes, applied in order, each recorded (count and reason) so the report explains
exactly what was removed and why -- never a single opaque "N points dropped":
  1. statistical outlier removal  (chaya_worker.geometry_cleanup.statistical_outlier_mask)
  2. radius-based outlier removal (chaya_worker.geometry_cleanup.radius_outlier_mask)
  3. semantic class-aware filtering, when SEMANTIC_LABELS is available (semantic_aware_mask): isolated
     "clutter" splats are dropped, floor/wall/furniture are protected even if geometrically sparse.

Opacity is used only to seed the benchmark's naive baseline (chaya_worker.benchmarks.cleanup_benchmark),
never as a cleanup criterion here -- exactly the "do not blindly delete geometry based only on opacity"
requirement. Needs Open3D; without it the stage fails with DEPENDENCY_UNAVAILABLE and nothing is produced.
"""

from __future__ import annotations

import numpy as np

from ..contract import ArtifactSpec, StageContext, StageError, StageResult
from ..geometry_cleanup import radius_outlier_mask, semantic_aware_mask, statistical_outlier_mask
from ..ply import read_ply, write_ply
from .base import command_record, write_json


class GeometricCleanup:
    name = "GEOMETRIC_CLEANUP"

    def run(self, ctx: StageContext) -> StageResult:
        ctx.toolchain.require(["py:open3d"], stage=self.name)
        splats = ctx.inputs_of("SPLAT")
        if not splats:
            raise StageError("no SPLAT was provided by SPLAT_RECONSTRUCTION", code="INPUT_INVALID")
        labels_inputs = ctx.inputs_of("SEMANTIC_LABELS")

        cloud = read_ply(splats[0].path)
        s = ctx.settings
        steps: list[dict] = []
        keep = np.ones(len(cloud), dtype=bool)

        stat_mask, stat_cfg = statistical_outlier_mask(cloud, nb_neighbors=s.cleanup_stat_nb_neighbors, std_ratio=s.cleanup_stat_std_ratio)
        steps.append({"step": "statistical_outlier_removal", "config": stat_cfg, "removed": int((~stat_mask).sum())})
        keep &= stat_mask

        radius_mask, radius_cfg = radius_outlier_mask(cloud, nb_points=s.cleanup_radius_nb_points, radius=s.cleanup_radius)
        steps.append({"step": "radius_outlier_removal", "config": radius_cfg, "removed": int((keep & ~radius_mask).sum())})
        keep &= radius_mask

        labels = None
        if labels_inputs:
            import json

            doc = json.loads(labels_inputs[0].path.read_text(encoding="utf-8"))
            labels = np.array(doc["labels"], dtype=object)
            if len(labels) != len(cloud):
                raise StageError(f"SEMANTIC_LABELS has {len(labels)} entries but the splat has {len(cloud)} Gaussians",
                                 code="INPUT_INVALID")
            surviving = cloud.subset(keep)
            surviving_labels = labels[keep]
            sem_mask_survivors, sem_cfg = semantic_aware_mask(surviving, surviving_labels, radius=s.cleanup_radius,
                                                               min_same_class_neighbors=s.cleanup_semantic_min_neighbors)
            full_sem_mask = np.ones(len(cloud), dtype=bool)
            full_sem_mask[np.where(keep)[0]] = sem_mask_survivors
            steps.append({"step": "semantic_aware_filtering", "config": sem_cfg, "removed": int((keep & ~full_sem_mask).sum())})
            keep &= full_sem_mask
        else:
            steps.append({"step": "semantic_aware_filtering", "config": None, "removed": 0,
                         "skipped_reason": "no SEMANTIC_LABELS input was provided to this stage"})

        cleaned = cloud.subset(keep)
        if len(cleaned) == 0:
            raise StageError("cleanup removed every Gaussian; refusing to publish an empty scene", code="CLEANUP_EMPTIED_SCENE",
                             details={"steps": steps})
        ctx.logger.info("geometric cleanup done", extra={"before": len(cloud), "after": len(cleaned), "steps": steps})

        cleaned_path = write_ply(cleaned, ctx.workdir / "splat-clean.ply")
        report_path = write_json(ctx.workdir / "geometric-cleanup-report.json", {
            "gaussian_count_before": len(cloud), "gaussian_count_after": len(cleaned),
            "semantic_labels_available": labels is not None, "steps": steps})

        cleaned_labels_artifact = None
        if labels is not None:
            cleaned_labels_path = write_json(ctx.workdir / "semantic-labels-clean.json",
                                             {"labels": [str(l) for l in labels[keep]]})
            cleaned_labels_artifact = ArtifactSpec("SEMANTIC_LABELS_CLEAN", cleaned_labels_path, "semantic-labels-clean.json",
                                                   "application/json")

        artifacts = [ArtifactSpec("SPLAT_CLEAN", cleaned_path, "splat-clean.ply", "application/octet-stream"),
                    ArtifactSpec("GEOMETRIC_CLEANUP_REPORT", report_path, "geometric-cleanup-report.json", "application/json")]
        if cleaned_labels_artifact:
            artifacts.append(cleaned_labels_artifact)

        return StageResult(
            "SUCCEEDED", command_record(ctx, {"gaussian_count_before": len(cloud), "gaussian_count_after": len(cleaned)}),
            ctx.runner.last_exit_status(), artifacts)
