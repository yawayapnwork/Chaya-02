"""Stage 9: RANSAC plane fitting on the cleaned geometry (chaya_worker.geometry_cleanup.fit_planes), and the
reconstruction's gravity estimate (chaya_worker.gravity).

Extracts up to `plane_max_planes` dominant planes. The RANSAC inlier distance is a multiple of the cloud's
own median nearest-neighbour spacing, because this stage runs in reconstruction units whose scale is not
known yet (chaya_worker.frames).

Planes are classified against a real up direction, from the first source available:
  * the work order's calibrated coordinate frame, when one exists (always the case for an incremental
    re-scan, whose merged geometry is in the parent reconstruction's already-calibrated frame);
  * otherwise this reconstruction's own gravity estimate: the largest plane the registered cameras stand on,
    oriented by their image-up directions (chaya_worker.gravity);
  * otherwise nothing better than fit_planes' provisional, sign-free guess, and the report says so.

For a full reconstruction the stage also publishes GRAVITY_ESTIMATE: the estimated up vector, the floor
point and the camera centroid, all in reconstruction coordinates -- the structural input the control
plane's calibration (dev.chaya.api.frame) combines with measured distances to build the canonical frame. When
gravity cannot be estimated, GRAVITY_ESTIMATE says NOT_ESTIMATED and why; the stage still succeeds, because
an operator can provide gravity from floor points instead.

When SEMANTIC_LABELS_CLEAN is available, each plane also reports how well its geometric classification agrees
with the semantic segmentation of its own inlier points. Needs Open3D; fails structured, produces nothing,
if it is missing.
"""

from __future__ import annotations

import json

import numpy as np

from ..contract import ArtifactSpec, StageContext, StageError, StageResult
from ..frames import CANONICAL_UP, frame_from_order
from ..geometry_cleanup import FLOOR, characteristic_spacing, fit_planes, reclassify_planes
from ..gravity import Plane, cameras_from_poses, estimate_gravity, not_estimated
from ..ply import read_ply
from .base import command_record, write_json


class PlaneFitting:
    name = "PLANE_FITTING"

    def run(self, ctx: StageContext) -> StageResult:
        ctx.toolchain.require(["py:open3d"], stage=self.name)
        # SPLAT_MERGED (REGION_SPLICE's venue-wide spliced result) takes priority when present -- an
        # incremental re-scan's plane fit must run on the full merged geometry, not the region alone.
        merged = ctx.inputs_of("SPLAT_MERGED")
        splats = merged or ctx.inputs_of("SPLAT_CLEAN")
        if not splats:
            raise StageError("no SPLAT_CLEAN was provided by GEOMETRIC_CLEANUP", code="INPUT_INVALID")
        labels_inputs = ctx.inputs_of("SEMANTIC_LABELS_CLEAN")

        cloud = read_ply(splats[0].path)
        labels = None
        if labels_inputs:
            labels = np.array(json.loads(labels_inputs[0].path.read_text(encoding="utf-8"))["labels"], dtype=object)
            if len(labels) != len(cloud):
                raise StageError(f"SEMANTIC_LABELS_CLEAN has {len(labels)} entries but the splat has {len(cloud)} Gaussians",
                                 code="INPUT_INVALID")

        s = ctx.settings
        spacing = characteristic_spacing(cloud.positions)
        threshold = s.plane_ransac_distance_spacing_factor * spacing
        planes = fit_planes(cloud.positions, distance_threshold=threshold, ransac_n=s.plane_ransac_n,
                            num_iterations=s.plane_ransac_iterations, max_planes=s.plane_max_planes, min_inliers=s.plane_min_inliers,
                            labels=labels)

        frame = frame_from_order(ctx.order)
        poses_inputs = ctx.inputs_of("POSES")
        artifacts: list[ArtifactSpec] = []
        up_source = "PROVISIONAL_PLANE_NORMAL_CLUSTER"
        if frame is not None and frame.canonical:
            # The reconstruction frame's up is the calibrated rotation's inverse applied to canonical +Z.
            to_canonical = frame.to_canonical()
            up = to_canonical.rotation.T @ CANONICAL_UP
            # No camera centres for merged geometry: a horizontal plane below the cloud's median height is a floor,
            # one above it a ceiling.
            reference = np.median(cloud.positions.astype(np.float64), axis=0)
            planes = reclassify_planes(planes, cloud.positions, up, reference, labels)
            up_source = f"CALIBRATED_FRAME:{frame.id}"
        elif merged:
            # Merged geometry lives in the parent's frame; this run's own poses are the region's and cannot be used.
            up_source = "PROVISIONAL_PLANE_NORMAL_CLUSTER (incremental re-scan without a calibrated parent frame)"
        elif poses_inputs:
            poses = json.loads(poses_inputs[0].path.read_text(encoding="utf-8")).get("poses", [])
            centres, ups = cameras_from_poses(poses)
            candidates = [Plane(np.array(p.equation[:3]), cloud.positions[p.inlier_indices].mean(axis=0), len(p.inlier_indices))
                          for p in planes]
            estimate, reason = estimate_gravity(
                candidates, centres, ups, max_camera_plane_angle_deg=s.gravity_max_camera_plane_angle_deg,
                min_camera_up_consistency=s.gravity_min_camera_up_consistency,
                min_cameras_above_fraction=s.gravity_min_cameras_above_fraction)
            if estimate is not None:
                planes = reclassify_planes(planes, cloud.positions, estimate.up, estimate.camera_centroid, labels)
                up_source = "GRAVITY_ESTIMATE"
                gravity_doc = {**estimate.to_dict(), "reconstruction_spacing": spacing, "cameras": len(centres)}
            else:
                gravity_doc = {**not_estimated(reason or "unknown"), "cameras": len(centres)}
            gravity_path = write_json(ctx.workdir / "gravity-estimate.json", gravity_doc)
            artifacts.append(ArtifactSpec("GRAVITY_ESTIMATE", gravity_path, "gravity-estimate.json", "application/json"))
        else:
            gravity_path = write_json(ctx.workdir / "gravity-estimate.json", not_estimated("no POSES input was provided"))
            artifacts.append(ArtifactSpec("GRAVITY_ESTIMATE", gravity_path, "gravity-estimate.json", "application/json"))

        floors = sum(1 for p in planes if p.classification == FLOOR)
        ctx.logger.info("plane fitting done", extra={"planes_found": len(planes), "points": len(cloud), "floors": floors,
                                                      "up_source": up_source})

        planes_doc = {
            "gaussian_count": len(cloud), "planes_found": len(planes), "coordinate_space": "RECONSTRUCTION",
            "up_source": up_source, "ransac_distance_threshold_reconstruction_units": threshold,
            "median_nn_spacing_reconstruction_units": spacing,
            "planes": [{"equation": list(p.equation), "classification": p.classification, "inlier_count": len(p.inlier_indices),
                       "confidence": p.confidence, "inlier_indices": [int(i) for i in p.inlier_indices]} for p in planes],
        }
        planes_path = write_json(ctx.workdir / "planes.json", planes_doc)
        artifacts.insert(0, ArtifactSpec("PLANE_MODEL", planes_path, "planes.json", "application/json"))
        return StageResult(
            "SUCCEEDED", command_record(ctx, {"planes_found": len(planes), "up_source": up_source}), ctx.runner.last_exit_status(),
            artifacts)
