"""Worker configuration, read from environment variables only. Nothing secret has a default."""

from __future__ import annotations

import os
import socket
from collections.abc import Mapping
from dataclasses import dataclass, field
from pathlib import Path


def _int(env: Mapping[str, str], name: str, default: int) -> int:
    return int(env.get(name, default))


def _float(env: Mapping[str, str], name: str, default: float) -> float:
    return float(env.get(name, default))


def _bool(env: Mapping[str, str], name: str, default: bool) -> bool:
    raw = env.get(name)
    if raw is None:
        return default
    if raw.strip().lower() in ("1", "true", "yes"):
        return True
    if raw.strip().lower() in ("0", "false", "no"):
        return False
    raise ValueError(f"{name} must be true or false, got {raw!r}")


@dataclass(frozen=True)
class Settings:
    # control plane
    api_url: str = ""
    token_url: str = ""
    client_id: str = "chaya-worker"
    client_secret: str = ""
    # object storage (derived bucket comes from each work order)
    s3_endpoint: str = ""
    s3_region: str = "us-east-1"
    s3_access_key: str = ""
    s3_secret_key: str = ""
    # worker
    worker_id: str = field(default_factory=lambda: f"{socket.gethostname()}-{os.getpid()}")
    stages: tuple[str, ...] = ()
    # The rate-card class this worker's machine is priced at (the API's CHAYA_COST_RATES; chaya_worker.resources). None:
    # its attempts are recorded but not priced.
    resource_class: str | None = None
    poll_interval: float = 5.0
    # Stage offer (chaya_worker.capabilities): configured stages whose hard requirements are missing are not claimed, and
    # the check is repeated this often (model weights fetched later, a GPU that went away).
    capability_recheck_seconds: float = 300.0
    # Diagnostic only: offer configured stages even when they cannot run, so their jobs fail with the stage's structured
    # DEPENDENCY_UNAVAILABLE in the run record instead of waiting for a capable worker.
    offer_unavailable_stages: bool = False
    heartbeat_interval: float = 60.0
    workdir: Path = Path("work")
    log_level: str = "INFO"
    # stage tunables
    frame_fps: float = 2.0
    max_frame_height: int = 1080
    max_frames_per_video: int = 900
    blur_threshold: float = 40.0
    dark_fraction_max: float = 0.6
    bright_fraction_max: float = 0.6
    duplicate_distance: float = 2.0
    min_frames: int = 10
    min_registered_ratio: float = 0.6
    # POSE_ESTIMATION: COLMAP SIFT extraction/matching threads. -1 = COLMAP's default (one per host core). On a CPU-only
    # worker each extraction thread holds ~450 MiB for a 1600x1200 frame, so an 18-core host wants ~8.5 GiB; bound it
    # to fit the worker's memory limit. GPU hosts extract on the GPU and do not need this.
    colmap_num_threads: int = -1
    privacy_screen_detector: str = "heuristic-quad"
    # Privacy masks (chaya_worker.privacy.masks). POSE_ESTIMATION keeps SIFT keypoints this many pixels away from any
    # privacy-masked pixel, so no descriptor window reads a blurred or filled pixel. SEMANTIC_INDEXING drops a detection
    # whose box is more than this fraction privacy-masked: what it "sees" there is the fill, not the scene.
    privacy_sfm_mask_margin_px: int = 16
    privacy_detection_max_masked_fraction: float = 0.25
    # SPLAT_RECONSTRUCTION (gsplat; chaya_worker.splat_training). Learning rates are the 3D Gaussian Splatting paper's
    # (position is scaled by the scene extent). Iteration counts are 1-based step numbers.
    gsplat_iterations: int = 7000  # the configured training target; stopping earlier is PARTIAL, never COMPLETED
    gsplat_lr_position: float = 1.6e-4
    gsplat_lr_position_final: float = 1.6e-6  # the reference schedule's end value (chaya_worker.splat_training.position_lr)
    gsplat_lr_position_decay_steps: int = 30_000  # the reference horizon; not changeable on resume
    gsplat_lr_scale: float = 5e-3
    gsplat_lr_rotation: float = 1e-3
    gsplat_lr_opacity: float = 5e-2
    gsplat_lr_color: float = 2.5e-3
    gsplat_ssim_weight: float = 0.2
    # Adaptive density control (splat_training.DensityControl): clone/split on screen-space gradient, prune by opacity
    # and size, periodic opacity reset, and a hard cap on the Gaussian count.
    gsplat_densify_start: int = 500
    gsplat_densify_stop: int = 3500
    gsplat_densify_every: int = 100
    gsplat_densify_grad_threshold: float = 2e-4  # NDC units, as in 3DGS/gsplat
    gsplat_percent_dense: float = 0.01  # clone below, split above, this fraction of the scene extent
    gsplat_prune_opacity: float = 0.005
    gsplat_prune_scale_fraction: float = 0.1
    gsplat_opacity_reset_every: int = 3000
    gsplat_max_gaussians: int = 3_000_000
    gsplat_checkpoint_every: int = 1000
    gsplat_seed: int = 0
    # Time reserved at the end of the run's budget to write the checkpoint and splat and upload them. Training stops
    # when less than this plus one iteration's time is left.
    gsplat_stop_margin_seconds: float = 120.0
    gsplat_keyframe_every: int = 500
    gsplat_min_compute_capability: float = 7.0
    # The GPU image is CUDA 12.6 (Dockerfile.gpu): NVIDIA's minimum Linux driver for CUDA 12.6 is 560.28. An older driver is
    # CUDA_DRIVER_INCOMPATIBLE in the splat preflight, not a vague "no CUDA device".
    gpu_min_driver_version: str = "560.28"
    # Free device memory below which training is refused before it starts (GPU_MEMORY_INSUFFICIENT); 0 = no check.
    gpu_min_free_memory_mb: int = 1024
    gsplat_min_training_frames: int = 3  # posed frames with images below which training is refused (INSUFFICIENT_FRAMES)
    # Reconstruction quality gate (chaya_worker.splat_quality; gaussian-splatting-audit D-5). Every Nth posed view (sorted by
    # name) is held out of training and only rendered for evaluation: 8 is the 3DGS / Mip-NeRF 360 evaluation protocol.
    # 0 trains on every view, and the gate then measures on training views and says so. Part of the checkpoint's config.
    gsplat_eval_holdout_every: int = 8
    # A COMPLETED training is published as SPLAT only if its mean PSNR on the evaluation views beats the untrained SfM seed's
    # by MORE than this. The default 0 is the weakest evidence-based floor (it must render unseen views better than where it
    # started); no real-capture distribution has been measured yet to justify a stricter one.
    gsplat_quality_min_psnr_gain_db: float = 0.0
    gsplat_quality_min_psnr_db: float = 0.0  # absolute floor on that mean PSNR; 0 = off (it depends on the scene and exposure)
    # ... and only if the mean training loss of the last tenth of iterations is below this fraction of the first tenth's.
    gsplat_quality_max_loss_ratio: float = 1.0
    gsplat_quality_require_held_out: bool = False  # true: too few views to hold any out fails the gate instead of falling back
    # gsplat's PyPI wheel compiles its CUDA kernels with nvcc on first use. Production images carry them prebuilt
    # (Dockerfile.gpu); allowing JIT is for development hosts with the CUDA toolkit (chaya_worker.splat_preflight).
    gsplat_allow_jit_backend: bool = False
    # SEMANTIC_SEGMENTATION
    semantic_confidence_min: float = 0.5
    # A Gaussian whose winning class holds less than this share of its confidence-weighted votes is labelled "unknown"
    # (uncertain) instead of taking the narrow winner. 0 keeps every winner (the behaviour before this setting existed).
    semantic_min_vote_share: float = 0.0
    semantic_sample_every: int = 1  # segment every Nth registered frame (cost control)
    semantic_segmentation_model: str = "nvidia/segformer-b0-finetuned-ade-512-512"
    semantic_segmentation_revision: str = ""  # commit SHA; empty = unpinned (logged)
    # GEOMETRIC_CLEANUP (Open3D). Runs before metric calibration exists, in arbitrary reconstruction units, so its
    # distance threshold is a multiple of the cloud's own median nearest-neighbour spacing (scale-invariant), never
    # a number of metres. 3.0 is the scale-relative radius benchmarks/b2_geometry_cleanup already measured with;
    # it has not been tuned on a real venue reconstruction.
    cleanup_stat_nb_neighbors: int = 20
    cleanup_stat_std_ratio: float = 2.0
    # The radius filter keeps a point with at least cleanup_radius_neighbor_share of the cloud's median neighbour count
    # within the radius, at most cleanup_radius_nb_points (geometry_cleanup.radius_min_neighbors explains why a fixed
    # count removed most of a randomly sampled surface).
    cleanup_radius_nb_points: int = 8
    cleanup_radius_neighbor_share: float = 0.35
    cleanup_radius_spacing_factor: float = 3.0
    cleanup_opacity_threshold: float = 0.05
    cleanup_semantic_min_neighbors: int = 3  # clutter points with fewer same-class neighbours within the cleanup radius are dropped
    # PLANE_FITTING. Same reason: the RANSAC inlier distance is a multiple of the median nearest-neighbour spacing.
    plane_ransac_distance_spacing_factor: float = 1.0
    plane_ransac_n: int = 3
    plane_ransac_iterations: int = 1000
    plane_max_planes: int = 6
    plane_min_inliers: int = 200
    # Gravity estimate (chaya_worker.gravity), emitted by PLANE_FITTING as GRAVITY_ESTIMATE.
    gravity_max_camera_plane_angle_deg: float = 30.0
    gravity_min_camera_up_consistency: float = 0.5
    gravity_min_cameras_above_fraction: float = 0.9
    # ARTIFACT_GENERATION
    ksplat_compression_level: int = 0
    # SEMANTIC_INDEXING (Grounding DINO + CLIP)
    grounding_dino_model: str = "IDEA-Research/grounding-dino-tiny"
    grounding_dino_revision: str = ""  # commit SHA; empty = unpinned (logged)
    # Pickle weights can run code on load; only safetensors unless this is set deliberately.
    allow_pickle_weights: bool = False
    object_detection_prompt: str = (
        "chair. table. sofa. couch. desk. door. window. sign. elevator. stairs. plant. artwork. "
        "reception desk. bench. shelf. counter. restroom sign. exit sign. fire extinguisher."
    )
    object_detection_box_threshold: float = 0.35
    object_detection_text_threshold: float = 0.25
    clip_model_name: str = "ViT-B-32"
    clip_pretrained: str = "openai"  # 512-d, matches poi_version.embedding vector(512)
    semantic_indexing_sample_every: int = 3
    object_cluster_distance_m: float = 0.75  # canonical metres (SEMANTIC_INDEXING requires a calibrated frame)
    # Placing a detection in 3D (chaya_worker.object_localization, review CV-1): z-buffer cell, depth tolerance
    # (absolute + relative to depth), the centres a surface needs to count as depth evidence, and the fraction of the box
    # the object's surface must cover. Chosen on synthetic scenes only; not calibrated on real reconstructions.
    localization_zbuffer_cell_px: int = 16
    localization_depth_band_m: float = 0.15
    localization_depth_band_rel: float = 0.05
    localization_min_support: int = 12
    localization_min_coverage: float = 0.25
    # NAVIGATION_BAKING (Recast/Detour via services/reconstruction/native/chaya-navmesh; see chaya_worker.recast). Every value is in canonical
    # physical units, named by its unit: lengths in metres, heights along canonical +Z (Recast's +Y after
    # chaya_worker.recast_boundary), areas in square metres, slopes in degrees. The tool converts them to Recast's voxel
    # units itself and records both. The stage refuses to run without a calibrated frame, so these numbers are never
    # applied to arbitrary reconstruction units.
    navmesh_cell_size_m: float = 0.1  # horizontal voxel size
    navmesh_cell_height_m: float = 0.05  # vertical voxel size
    navmesh_agent_height_m: float = 1.8  # head clearance an agent needs
    navmesh_agent_radius_m: float = 0.35  # walkable area is eroded by this much from every wall and edge
    navmesh_agent_max_climb_m: float = 0.4  # highest step an agent walks up (a stair riser ~0.18 m; a curb ~0.15 m)
    navmesh_agent_max_slope_deg: float = 45.0  # steepest walkable surface
    navmesh_region_min_area_m2: float = 0.64  # isolated walkable islands smaller than this (0.8 m x 0.8 m) are dropped
    navmesh_region_merge_area_m2: float = 4.0  # regions smaller than this are merged into a neighbour
    navmesh_edge_max_len_m: float = 6.0  # longest polygon border edge
    navmesh_edge_max_error_m: float = 0.13  # how far a simplified border may deviate from the voxelised one
    navmesh_verts_per_poly: int = 6  # a count, not a length; Detour's maximum is 6
    navmesh_detail_sample_dist_m: float = 0.6  # height-detail sampling spacing
    navmesh_detail_sample_max_error_m: float = 0.05  # how far the detail mesh may deviate from the heightfield
    # Recast's input geometry from a reconstruction (chaya_worker.navmesh.build_surface_model / geometry_from_surface):
    # the surface grid's cell size (also the resolution of step detection and of measured clearance), how many
    # floor/stairs points in one band of navmesh_ground_band_m make a cell observed ground, the least height an obstacle
    # box is given above its ground, the smallest rise between neighbouring cells that counts as a step (a 1:12 ramp
    # rises 8 mm per 0.1 m cell; reconstruction noise is assumed to stay below this), and how far above the floor plane
    # a walkable level (landing, raised area, stair tread) of the same floor may be.
    navmesh_floor_grid_m: float = 0.1
    navmesh_floor_min_points_per_cell: int = 3
    navmesh_ground_band_m: float = 0.05
    navmesh_obstacle_min_height_m: float = 1.0
    navmesh_step_min_rise_m: float = 0.03
    navmesh_max_level_above_floor_m: float = 2.0
    # Clear width (chaya_worker.navmesh.centred_width): measured up to navmesh_width_cap_m; cells where a body
    # navmesh_accessible_width_m wide cannot be centred are counted in the report. The API's STEP_FREE limit is
    # chaya.navigation.min-accessible-clearance-m, also 0.9 m, applied to each edge's measured width.
    navmesh_accessible_width_m: float = 0.9
    navmesh_width_cap_m: float = 2.0
    # ADA-inspired accessible-ramp threshold (1:12 rise:run ~= 4.8 degrees); a polygon steeper than this
    # is excluded from the STEP_FREE routing graph regardless of whether Recast still considers it walkable.
    navmesh_max_ramp_slope_deg: float = 5.0
    # A plane counts as horizontal (a floor candidate) within this angle of canonical +Z.
    navmesh_floor_max_tilt_deg: float = 10.0
    # REGION_ALIGNMENT / REGION_SPLICE (incremental re-scan; see docs/rescan.md). Registration runs in canonical
    # metres: the region is made metric by its own calibration, the venue by the parent's frame. Every threshold is in
    # the unit its name says (chaya_worker.similarity_registration.AlignmentGates).
    # FPFH feature scale (FEATURE_SIMILARITY mode); descriptors span 5x this. 0.05 m (the old value) was too local on
    # plane-heavy interiors: a synthetic room's 75-degree case got too few matches and was rejected, while 0.10 m recovered
    # every case (tests/gpu/test_region_alignment.py, Open3D). Chosen from synthetic data and B5, not from real splats.
    alignment_voxel_size_m: float = 0.10
    alignment_icp_voxel_m: float = 0.02  # both clouds are downsampled to this before ICP; metrics are taken at it
    alignment_icp_schedule_m: str = "1.0,0.5,0.25,0.1"  # coarse-to-fine ICP correspondence distances; the last one measures
    alignment_crop_margin_m: float = 1.0  # venue context kept around the region polygon's bounding box
    # Registration may refine the region's scale (its calibration is measured, not exact) but a correction beyond
    # this fraction means the region's calibration and the venue's disagree; the alignment is rejected.
    alignment_max_scale_correction: float = 0.1
    alignment_min_correspondences: int = 200
    alignment_min_inlier_ratio: float = 0.5
    alignment_max_rotation_residual_deg: float = 1.0
    alignment_max_translation_residual_m: float = 0.03
    alignment_max_icp_residual_m: float = 0.05
    alignment_max_direct_rotation_correction_deg: float = 5.0
    alignment_max_direct_translation_correction_m: float = 0.5
    alignment_min_ransac_inliers: int = 20
    alignment_max_tilt_deg: float = 5.0
    # Every rigid direction must be observed by an overlapping surface (similarity_registration.constraint_ratio).
    alignment_min_constraint_ratio: float = 0.05
    # The control plane independently re-checks this against chaya.rescan.min-alignment-confidence
    # (RescanProperties) before ever finalizing a ScanVersion -- this is the worker's own gate so a bad
    # splice is refused even before the report reaches the server. Never merge below this line.
    min_alignment_confidence: float = 0.6
    # REGION_SPLICE (chaya_worker.region_splice): the replaced volume is the polygon x the region's own height range
    # grown by splice_z_margin_m. The seam is measured over splice_seam_band_m either side of the polygon edge; a
    # median step across it above splice_max_seam_step_m is a visible seam and the splice is refused.
    splice_z_margin_m: float = 0.2
    splice_seam_band_m: float = 0.15
    splice_max_seam_step_m: float = 0.03
    # Unintended deletion: a venue Gaussian is removed only where the re-scan observed something. On a grid of
    # splice_coverage_cell_m (horizontal), removed Gaussians with no added re-scan Gaussian in their cell or a neighbouring
    # one were deleted without a replacement; above splice_max_uncovered_fraction of the removed ones, the splice is refused.
    splice_coverage_cell_m: float = 0.25
    splice_max_uncovered_fraction: float = 0.05
    # The replaced volume reaches at least this far above the re-scan's floor inside the polygon, so furniture that was
    # removed is removed from the venue too. Geometry above it (a ceiling, a mezzanine) is kept. 0 disables it.
    splice_min_volume_height_m: float = 2.0
    # ROOM_REGISTRATION (multi-room stitching; docs/multi-room.md). Every room is its own capture with its own canonical
    # calibration, so registration is rigid in metres: the scale RANSAC estimates is gated (room_max_relative_scale), never
    # applied. ICP runs on the overlap only (source points within room_overlap_radius_m of the other room after RANSAC),
    # coarse to fine over room_icp_schedule_m. The residual, constraint, RANSAC and tilt gates are the alignment_* ones.
    # Chosen on the synthetic floor (tests/multiroom_scene.py), not on real splats.
    room_feature_voxel_m: float = 0.10
    room_icp_voxel_m: float = 0.02
    room_icp_schedule_m: str = "0.25,0.1,0.05"
    room_overlap_radius_m: float = 0.3
    room_min_correspondences: int = 300
    # Stricter than alignment_min_constraint_ratio: a doorway seen only as its jambs (no surface facing along the passage
    # through it) measured 0.06-0.08 and was then registered 15 cm off at the room's centre, every residual looking
    # perfect; doorway overlaps that include what each camera saw through the door measured 0.23-0.46. Synthetic floor only.
    room_min_constraint_ratio: float = 0.15
    room_max_relative_scale: float = 0.02  # |RANSAC scale - 1|: two metric calibrations that disagree more are refused
    room_max_ambiguity_ratio: float = 0.8  # a second, different hypothesis this strong means the overlap is ambiguous
    # the fraction of one room's surfaces allowed in space the other capture saw empty (room_registration.free_space_conflict);
    # measured on the synthetic floor: right placements 0.000-0.004, rooms laid over each other 0.29-1.0
    room_max_free_space_conflict: float = 0.10
    # loop closure: an edge's residual after the pose graph is optimised
    room_max_loop_translation_m: float = 0.05
    room_max_loop_rotation_deg: float = 1.0
    # ROOM_STITCH (chaya_worker.room_stitch): duplicate surfaces are resolved on a stitch_voxel_m grid; the registered
    # overlap is verified again in the output frame (offset and rotation ICP still finds) and the seam is measured across
    # ownership boundaries. Either above its limit refuses the stitch (STITCH_REJECTED).
    stitch_voxel_m: float = 0.05
    stitch_max_misalignment_m: float = 0.03
    stitch_max_misalignment_deg: float = 1.0
    stitch_max_seam_step_m: float = 0.03
    stitch_seam_band_m: float = 0.15
    stitch_colour_warning: float = 0.15  # median per-channel colour difference in shared voxels; reported, never refused
    stitch_footprint_cell_m: float = 0.25  # the horizontal grid each room's walkable footprint is recorded on
    # NAVIGATION_BAKING of a stitched floor refuses a navmesh on which some room cannot be reached from the others.
    stitch_require_connected_rooms: bool = True

    def room_gates(self):
        from .room_registration import RoomGates  # noqa: PLC0415 - avoid a settings -> scipy import at load
        from .similarity_registration import AlignmentGates  # noqa: PLC0415

        alignment = AlignmentGates(
            min_correspondences=self.room_min_correspondences, min_inlier_ratio=self.alignment_min_inlier_ratio,
            max_scale_correction=max(0.05, 2 * self.room_max_relative_scale),
            max_rotation_residual_deg=self.alignment_max_rotation_residual_deg,
            max_translation_residual_m=self.alignment_max_translation_residual_m,
            max_icp_residual_m=self.alignment_max_icp_residual_m, min_confidence=self.min_alignment_confidence,
            min_ransac_inliers=self.alignment_min_ransac_inliers, max_tilt_deg=self.alignment_max_tilt_deg,
            min_constraint_ratio=self.room_min_constraint_ratio)
        return RoomGates(alignment=alignment, max_ambiguity_ratio=self.room_max_ambiguity_ratio,
                         max_relative_scale=self.room_max_relative_scale,
                         max_free_space_conflict=self.room_max_free_space_conflict,
                         max_loop_translation_m=self.room_max_loop_translation_m,
                         max_loop_rotation_deg=self.room_max_loop_rotation_deg)

    def room_schedule(self) -> list[float]:
        schedule = [float(v) for v in self.room_icp_schedule_m.split(",") if v.strip()]
        if not schedule or any(v <= 0 for v in schedule):
            raise ValueError("ROOM_ICP_SCHEDULE_M must be a comma-separated list of positive distances in metres")
        return sorted(schedule, reverse=True)

    def localization_params(self):
        from .object_localization import LocalizationParams  # noqa: PLC0415

        return LocalizationParams(cell_px=self.localization_zbuffer_cell_px, depth_band_m=self.localization_depth_band_m,
                                  depth_band_rel=self.localization_depth_band_rel, min_support=self.localization_min_support,
                                  min_coverage=self.localization_min_coverage)

    def alignment_gates(self):
        from .similarity_registration import AlignmentGates  # noqa: PLC0415 - avoid a settings -> scipy import at load

        return AlignmentGates(
            min_correspondences=self.alignment_min_correspondences, min_inlier_ratio=self.alignment_min_inlier_ratio,
            max_scale_correction=self.alignment_max_scale_correction,
            max_rotation_residual_deg=self.alignment_max_rotation_residual_deg,
            max_translation_residual_m=self.alignment_max_translation_residual_m,
            max_icp_residual_m=self.alignment_max_icp_residual_m, min_confidence=self.min_alignment_confidence,
            max_direct_rotation_correction_deg=self.alignment_max_direct_rotation_correction_deg,
            max_direct_translation_correction_m=self.alignment_max_direct_translation_correction_m,
            min_ransac_inliers=self.alignment_min_ransac_inliers, max_tilt_deg=self.alignment_max_tilt_deg,
            min_constraint_ratio=self.alignment_min_constraint_ratio)

    def alignment_schedule(self) -> list[float]:
        schedule = [float(v) for v in self.alignment_icp_schedule_m.split(",") if v.strip()]
        if not schedule or any(v <= 0 for v in schedule):
            raise ValueError("ALIGNMENT_ICP_SCHEDULE_M must be a comma-separated list of positive distances in metres")
        return sorted(schedule, reverse=True)

    @staticmethod
    def from_env(env: Mapping[str, str] | None = None) -> Settings:
        e = os.environ if env is None else env
        d = Settings()
        return Settings(
            api_url=e.get("CHAYA_API_URL", "").rstrip("/"),
            token_url=e.get("CHAYA_TOKEN_URL", ""),
            client_id=e.get("CHAYA_CLIENT_ID", d.client_id),
            client_secret=e.get("CHAYA_CLIENT_SECRET", ""),
            s3_endpoint=e.get("S3_ENDPOINT", ""),
            s3_region=e.get("S3_REGION", d.s3_region),
            s3_access_key=e.get("S3_ACCESS_KEY", ""),
            s3_secret_key=e.get("S3_SECRET_KEY", ""),
            worker_id=e.get("WORKER_ID", d.worker_id),
            stages=tuple(s for s in e.get("WORKER_STAGES", "").split(",") if s),
            resource_class=e.get("WORKER_RESOURCE_CLASS", "").strip() or None,
            poll_interval=_float(e, "WORKER_POLL_INTERVAL", d.poll_interval),
            capability_recheck_seconds=_float(e, "WORKER_CAPABILITY_RECHECK_SECONDS", d.capability_recheck_seconds),
            offer_unavailable_stages=_bool(e, "WORKER_OFFER_UNAVAILABLE_STAGES", d.offer_unavailable_stages),
            heartbeat_interval=_float(e, "WORKER_HEARTBEAT_INTERVAL", d.heartbeat_interval),
            workdir=Path(e.get("WORKER_WORKDIR", str(d.workdir))),
            log_level=e.get("LOG_LEVEL", d.log_level),
            frame_fps=_float(e, "FRAME_FPS", d.frame_fps),
            max_frame_height=_int(e, "MAX_FRAME_HEIGHT", d.max_frame_height),
            max_frames_per_video=_int(e, "MAX_FRAMES_PER_VIDEO", d.max_frames_per_video),
            blur_threshold=_float(e, "BLUR_THRESHOLD", d.blur_threshold),
            dark_fraction_max=_float(e, "DARK_FRACTION_MAX", d.dark_fraction_max),
            bright_fraction_max=_float(e, "BRIGHT_FRACTION_MAX", d.bright_fraction_max),
            duplicate_distance=_float(e, "DUPLICATE_DISTANCE", d.duplicate_distance),
            min_frames=_int(e, "MIN_FRAMES", d.min_frames),
            min_registered_ratio=_float(e, "MIN_REGISTERED_RATIO", d.min_registered_ratio),
            colmap_num_threads=_int(e, "COLMAP_NUM_THREADS", d.colmap_num_threads),
            privacy_screen_detector=e.get("PRIVACY_SCREEN_DETECTOR", d.privacy_screen_detector),
            privacy_sfm_mask_margin_px=_int(e, "PRIVACY_SFM_MASK_MARGIN_PX", d.privacy_sfm_mask_margin_px),
            privacy_detection_max_masked_fraction=_float(e, "PRIVACY_DETECTION_MAX_MASKED_FRACTION",
                                                         d.privacy_detection_max_masked_fraction),
            gsplat_iterations=_int(e, "GSPLAT_ITERATIONS", d.gsplat_iterations),
            gsplat_lr_position=_float(e, "GSPLAT_LR_POSITION", d.gsplat_lr_position),
            gsplat_lr_position_final=_float(e, "GSPLAT_LR_POSITION_FINAL", d.gsplat_lr_position_final),
            gsplat_lr_position_decay_steps=_int(e, "GSPLAT_LR_POSITION_DECAY_STEPS", d.gsplat_lr_position_decay_steps),
            gsplat_lr_scale=_float(e, "GSPLAT_LR_SCALE", d.gsplat_lr_scale),
            gsplat_lr_rotation=_float(e, "GSPLAT_LR_ROTATION", d.gsplat_lr_rotation),
            gsplat_lr_opacity=_float(e, "GSPLAT_LR_OPACITY", d.gsplat_lr_opacity),
            gsplat_lr_color=_float(e, "GSPLAT_LR_COLOR", d.gsplat_lr_color),
            gsplat_densify_start=_int(e, "GSPLAT_DENSIFY_START", d.gsplat_densify_start),
            gsplat_densify_stop=_int(e, "GSPLAT_DENSIFY_STOP", d.gsplat_densify_stop),
            gsplat_densify_every=_int(e, "GSPLAT_DENSIFY_EVERY", d.gsplat_densify_every),
            gsplat_densify_grad_threshold=_float(e, "GSPLAT_DENSIFY_GRAD_THRESHOLD", d.gsplat_densify_grad_threshold),
            gsplat_percent_dense=_float(e, "GSPLAT_PERCENT_DENSE", d.gsplat_percent_dense),
            gsplat_prune_opacity=_float(e, "GSPLAT_PRUNE_OPACITY", d.gsplat_prune_opacity),
            gsplat_prune_scale_fraction=_float(e, "GSPLAT_PRUNE_SCALE_FRACTION", d.gsplat_prune_scale_fraction),
            gsplat_opacity_reset_every=_int(e, "GSPLAT_OPACITY_RESET_EVERY", d.gsplat_opacity_reset_every),
            gsplat_max_gaussians=_int(e, "GSPLAT_MAX_GAUSSIANS", d.gsplat_max_gaussians),
            gsplat_checkpoint_every=_int(e, "GSPLAT_CHECKPOINT_EVERY", d.gsplat_checkpoint_every),
            gsplat_seed=_int(e, "GSPLAT_SEED", d.gsplat_seed),
            gsplat_stop_margin_seconds=_float(e, "GSPLAT_STOP_MARGIN_SECONDS", d.gsplat_stop_margin_seconds),
            gsplat_ssim_weight=_float(e, "GSPLAT_SSIM_WEIGHT", d.gsplat_ssim_weight),
            gsplat_keyframe_every=_int(e, "GSPLAT_KEYFRAME_EVERY", d.gsplat_keyframe_every),
            gsplat_min_compute_capability=_float(e, "GSPLAT_MIN_COMPUTE_CAPABILITY", d.gsplat_min_compute_capability),
            gpu_min_driver_version=e.get("GPU_MIN_DRIVER_VERSION", d.gpu_min_driver_version).strip(),
            gpu_min_free_memory_mb=_int(e, "GPU_MIN_FREE_MEMORY_MB", d.gpu_min_free_memory_mb),
            gsplat_min_training_frames=_int(e, "GSPLAT_MIN_TRAINING_FRAMES", d.gsplat_min_training_frames),
            gsplat_eval_holdout_every=_int(e, "GSPLAT_EVAL_HOLDOUT_EVERY", d.gsplat_eval_holdout_every),
            gsplat_quality_min_psnr_gain_db=_float(e, "GSPLAT_QUALITY_MIN_PSNR_GAIN_DB", d.gsplat_quality_min_psnr_gain_db),
            gsplat_quality_min_psnr_db=_float(e, "GSPLAT_QUALITY_MIN_PSNR_DB", d.gsplat_quality_min_psnr_db),
            gsplat_quality_max_loss_ratio=_float(e, "GSPLAT_QUALITY_MAX_LOSS_RATIO", d.gsplat_quality_max_loss_ratio),
            gsplat_quality_require_held_out=_bool(e, "GSPLAT_QUALITY_REQUIRE_HELD_OUT", d.gsplat_quality_require_held_out),
            gsplat_allow_jit_backend=e.get("GSPLAT_ALLOW_JIT_BACKEND", "false").strip().lower() == "true",
            semantic_confidence_min=_float(e, "SEMANTIC_CONFIDENCE_MIN", d.semantic_confidence_min),
            semantic_min_vote_share=_float(e, "SEMANTIC_MIN_VOTE_SHARE", d.semantic_min_vote_share),
            semantic_sample_every=_int(e, "SEMANTIC_SAMPLE_EVERY", d.semantic_sample_every),
            semantic_segmentation_model=e.get("SEMANTIC_SEGMENTATION_MODEL", d.semantic_segmentation_model),
            semantic_segmentation_revision=e.get("SEMANTIC_SEGMENTATION_REVISION", d.semantic_segmentation_revision),
            cleanup_stat_nb_neighbors=_int(e, "CLEANUP_STAT_NB_NEIGHBORS", d.cleanup_stat_nb_neighbors),
            cleanup_stat_std_ratio=_float(e, "CLEANUP_STAT_STD_RATIO", d.cleanup_stat_std_ratio),
            cleanup_radius_nb_points=_int(e, "CLEANUP_RADIUS_NB_POINTS", d.cleanup_radius_nb_points),
            cleanup_radius_neighbor_share=_float(e, "CLEANUP_RADIUS_NEIGHBOR_SHARE", d.cleanup_radius_neighbor_share),
            cleanup_radius_spacing_factor=_float(e, "CLEANUP_RADIUS_SPACING_FACTOR", d.cleanup_radius_spacing_factor),
            cleanup_opacity_threshold=_float(e, "CLEANUP_OPACITY_THRESHOLD", d.cleanup_opacity_threshold),
            cleanup_semantic_min_neighbors=_int(e, "CLEANUP_SEMANTIC_MIN_NEIGHBORS", d.cleanup_semantic_min_neighbors),
            plane_ransac_distance_spacing_factor=_float(e, "PLANE_RANSAC_DISTANCE_SPACING_FACTOR",
                                                        d.plane_ransac_distance_spacing_factor),
            plane_ransac_n=_int(e, "PLANE_RANSAC_N", d.plane_ransac_n),
            plane_ransac_iterations=_int(e, "PLANE_RANSAC_ITERATIONS", d.plane_ransac_iterations),
            plane_max_planes=_int(e, "PLANE_MAX_PLANES", d.plane_max_planes),
            plane_min_inliers=_int(e, "PLANE_MIN_INLIERS", d.plane_min_inliers),
            gravity_max_camera_plane_angle_deg=_float(e, "GRAVITY_MAX_CAMERA_PLANE_ANGLE_DEG", d.gravity_max_camera_plane_angle_deg),
            gravity_min_camera_up_consistency=_float(e, "GRAVITY_MIN_CAMERA_UP_CONSISTENCY", d.gravity_min_camera_up_consistency),
            gravity_min_cameras_above_fraction=_float(e, "GRAVITY_MIN_CAMERAS_ABOVE_FRACTION", d.gravity_min_cameras_above_fraction),
            ksplat_compression_level=_int(e, "KSPLAT_COMPRESSION_LEVEL", d.ksplat_compression_level),
            grounding_dino_model=e.get("GROUNDING_DINO_MODEL", d.grounding_dino_model),
            grounding_dino_revision=e.get("GROUNDING_DINO_REVISION", d.grounding_dino_revision),
            allow_pickle_weights=e.get("MODEL_ALLOW_PICKLE_WEIGHTS", "false").strip().lower() == "true",
            object_detection_prompt=e.get("OBJECT_DETECTION_PROMPT", d.object_detection_prompt),
            object_detection_box_threshold=_float(e, "OBJECT_DETECTION_BOX_THRESHOLD", d.object_detection_box_threshold),
            object_detection_text_threshold=_float(e, "OBJECT_DETECTION_TEXT_THRESHOLD", d.object_detection_text_threshold),
            clip_model_name=e.get("CLIP_MODEL_NAME", d.clip_model_name),
            clip_pretrained=e.get("CLIP_PRETRAINED", d.clip_pretrained),
            semantic_indexing_sample_every=_int(e, "SEMANTIC_INDEXING_SAMPLE_EVERY", d.semantic_indexing_sample_every),
            object_cluster_distance_m=_float(e, "OBJECT_CLUSTER_DISTANCE_M", d.object_cluster_distance_m),
            localization_zbuffer_cell_px=_int(e, "LOCALIZATION_ZBUFFER_CELL_PX", d.localization_zbuffer_cell_px),
            localization_depth_band_m=_float(e, "LOCALIZATION_DEPTH_BAND_M", d.localization_depth_band_m),
            localization_depth_band_rel=_float(e, "LOCALIZATION_DEPTH_BAND_REL", d.localization_depth_band_rel),
            localization_min_support=_int(e, "LOCALIZATION_MIN_SUPPORT", d.localization_min_support),
            localization_min_coverage=_float(e, "LOCALIZATION_MIN_COVERAGE", d.localization_min_coverage),
            navmesh_cell_size_m=_float(e, "NAVMESH_CELL_SIZE_M", d.navmesh_cell_size_m),
            navmesh_cell_height_m=_float(e, "NAVMESH_CELL_HEIGHT_M", d.navmesh_cell_height_m),
            navmesh_agent_height_m=_float(e, "NAVMESH_AGENT_HEIGHT_M", d.navmesh_agent_height_m),
            navmesh_agent_radius_m=_float(e, "NAVMESH_AGENT_RADIUS_M", d.navmesh_agent_radius_m),
            navmesh_agent_max_climb_m=_float(e, "NAVMESH_AGENT_MAX_CLIMB_M", d.navmesh_agent_max_climb_m),
            navmesh_agent_max_slope_deg=_float(e, "NAVMESH_AGENT_MAX_SLOPE_DEG", d.navmesh_agent_max_slope_deg),
            navmesh_region_min_area_m2=_float(e, "NAVMESH_REGION_MIN_AREA_M2", d.navmesh_region_min_area_m2),
            navmesh_region_merge_area_m2=_float(e, "NAVMESH_REGION_MERGE_AREA_M2", d.navmesh_region_merge_area_m2),
            navmesh_edge_max_len_m=_float(e, "NAVMESH_EDGE_MAX_LEN_M", d.navmesh_edge_max_len_m),
            navmesh_edge_max_error_m=_float(e, "NAVMESH_EDGE_MAX_ERROR_M", d.navmesh_edge_max_error_m),
            navmesh_verts_per_poly=_int(e, "NAVMESH_VERTS_PER_POLY", d.navmesh_verts_per_poly),
            navmesh_detail_sample_dist_m=_float(e, "NAVMESH_DETAIL_SAMPLE_DIST_M", d.navmesh_detail_sample_dist_m),
            navmesh_detail_sample_max_error_m=_float(e, "NAVMESH_DETAIL_SAMPLE_MAX_ERROR_M", d.navmesh_detail_sample_max_error_m),
            navmesh_floor_grid_m=_float(e, "NAVMESH_FLOOR_GRID_M", d.navmesh_floor_grid_m),
            navmesh_floor_min_points_per_cell=_int(e, "NAVMESH_FLOOR_MIN_POINTS_PER_CELL", d.navmesh_floor_min_points_per_cell),
            navmesh_obstacle_min_height_m=_float(e, "NAVMESH_OBSTACLE_MIN_HEIGHT_M", d.navmesh_obstacle_min_height_m),
            navmesh_ground_band_m=_float(e, "NAVMESH_GROUND_BAND_M", d.navmesh_ground_band_m),
            navmesh_step_min_rise_m=_float(e, "NAVMESH_STEP_MIN_RISE_M", d.navmesh_step_min_rise_m),
            navmesh_max_level_above_floor_m=_float(e, "NAVMESH_MAX_LEVEL_ABOVE_FLOOR_M", d.navmesh_max_level_above_floor_m),
            navmesh_accessible_width_m=_float(e, "NAVMESH_ACCESSIBLE_WIDTH_M", d.navmesh_accessible_width_m),
            navmesh_width_cap_m=_float(e, "NAVMESH_WIDTH_CAP_M", d.navmesh_width_cap_m),
            navmesh_max_ramp_slope_deg=_float(e, "NAVMESH_MAX_RAMP_SLOPE_DEG", d.navmesh_max_ramp_slope_deg),
            navmesh_floor_max_tilt_deg=_float(e, "NAVMESH_FLOOR_MAX_TILT_DEG", d.navmesh_floor_max_tilt_deg),
            alignment_voxel_size_m=_float(e, "ALIGNMENT_VOXEL_SIZE_M", d.alignment_voxel_size_m),
            alignment_max_scale_correction=_float(e, "ALIGNMENT_MAX_SCALE_CORRECTION", d.alignment_max_scale_correction),
            min_alignment_confidence=_float(e, "MIN_ALIGNMENT_CONFIDENCE", d.min_alignment_confidence),
            alignment_icp_voxel_m=_float(e, "ALIGNMENT_ICP_VOXEL_M", d.alignment_icp_voxel_m),
            alignment_icp_schedule_m=e.get("ALIGNMENT_ICP_SCHEDULE_M", d.alignment_icp_schedule_m),
            alignment_crop_margin_m=_float(e, "ALIGNMENT_CROP_MARGIN_M", d.alignment_crop_margin_m),
            alignment_min_correspondences=_int(e, "ALIGNMENT_MIN_CORRESPONDENCES", d.alignment_min_correspondences),
            alignment_min_inlier_ratio=_float(e, "ALIGNMENT_MIN_INLIER_RATIO", d.alignment_min_inlier_ratio),
            alignment_max_rotation_residual_deg=_float(e, "ALIGNMENT_MAX_ROTATION_RESIDUAL_DEG", d.alignment_max_rotation_residual_deg),
            alignment_max_translation_residual_m=_float(e, "ALIGNMENT_MAX_TRANSLATION_RESIDUAL_M", d.alignment_max_translation_residual_m),
            alignment_max_icp_residual_m=_float(e, "ALIGNMENT_MAX_ICP_RESIDUAL_M", d.alignment_max_icp_residual_m),
            alignment_max_direct_rotation_correction_deg=_float(e, "ALIGNMENT_MAX_DIRECT_ROTATION_CORRECTION_DEG",
                                                                d.alignment_max_direct_rotation_correction_deg),
            alignment_max_direct_translation_correction_m=_float(e, "ALIGNMENT_MAX_DIRECT_TRANSLATION_CORRECTION_M",
                                                                 d.alignment_max_direct_translation_correction_m),
            alignment_min_ransac_inliers=_int(e, "ALIGNMENT_MIN_RANSAC_INLIERS", d.alignment_min_ransac_inliers),
            alignment_max_tilt_deg=_float(e, "ALIGNMENT_MAX_TILT_DEG", d.alignment_max_tilt_deg),
            alignment_min_constraint_ratio=_float(e, "ALIGNMENT_MIN_CONSTRAINT_RATIO", d.alignment_min_constraint_ratio),
            splice_z_margin_m=_float(e, "SPLICE_Z_MARGIN_M", d.splice_z_margin_m),
            splice_seam_band_m=_float(e, "SPLICE_SEAM_BAND_M", d.splice_seam_band_m),
            splice_max_seam_step_m=_float(e, "SPLICE_MAX_SEAM_STEP_M", d.splice_max_seam_step_m),
            splice_coverage_cell_m=_float(e, "SPLICE_COVERAGE_CELL_M", d.splice_coverage_cell_m),
            splice_max_uncovered_fraction=_float(e, "SPLICE_MAX_UNCOVERED_FRACTION", d.splice_max_uncovered_fraction),
            splice_min_volume_height_m=_float(e, "SPLICE_MIN_VOLUME_HEIGHT_M", d.splice_min_volume_height_m),
            room_feature_voxel_m=_float(e, "ROOM_FEATURE_VOXEL_M", d.room_feature_voxel_m),
            room_icp_voxel_m=_float(e, "ROOM_ICP_VOXEL_M", d.room_icp_voxel_m),
            room_icp_schedule_m=e.get("ROOM_ICP_SCHEDULE_M", d.room_icp_schedule_m),
            room_overlap_radius_m=_float(e, "ROOM_OVERLAP_RADIUS_M", d.room_overlap_radius_m),
            room_min_correspondences=_int(e, "ROOM_MIN_CORRESPONDENCES", d.room_min_correspondences),
            room_min_constraint_ratio=_float(e, "ROOM_MIN_CONSTRAINT_RATIO", d.room_min_constraint_ratio),
            room_max_relative_scale=_float(e, "ROOM_MAX_RELATIVE_SCALE", d.room_max_relative_scale),
            room_max_ambiguity_ratio=_float(e, "ROOM_MAX_AMBIGUITY_RATIO", d.room_max_ambiguity_ratio),
            room_max_free_space_conflict=_float(e, "ROOM_MAX_FREE_SPACE_CONFLICT", d.room_max_free_space_conflict),
            room_max_loop_translation_m=_float(e, "ROOM_MAX_LOOP_TRANSLATION_M", d.room_max_loop_translation_m),
            room_max_loop_rotation_deg=_float(e, "ROOM_MAX_LOOP_ROTATION_DEG", d.room_max_loop_rotation_deg),
            stitch_voxel_m=_float(e, "STITCH_VOXEL_M", d.stitch_voxel_m),
            stitch_max_misalignment_m=_float(e, "STITCH_MAX_MISALIGNMENT_M", d.stitch_max_misalignment_m),
            stitch_max_misalignment_deg=_float(e, "STITCH_MAX_MISALIGNMENT_DEG", d.stitch_max_misalignment_deg),
            stitch_max_seam_step_m=_float(e, "STITCH_MAX_SEAM_STEP_M", d.stitch_max_seam_step_m),
            stitch_seam_band_m=_float(e, "STITCH_SEAM_BAND_M", d.stitch_seam_band_m),
            stitch_colour_warning=_float(e, "STITCH_COLOUR_WARNING", d.stitch_colour_warning),
            stitch_footprint_cell_m=_float(e, "STITCH_FOOTPRINT_CELL_M", d.stitch_footprint_cell_m),
            stitch_require_connected_rooms=_bool(e, "STITCH_REQUIRE_CONNECTED_ROOMS", d.stitch_require_connected_rooms),
        )

    def require_service_config(self) -> None:
        missing = [n for n, v in {
            "CHAYA_API_URL": self.api_url, "CHAYA_TOKEN_URL": self.token_url, "CHAYA_CLIENT_SECRET": self.client_secret,
            "S3_ENDPOINT": self.s3_endpoint, "S3_ACCESS_KEY": self.s3_access_key, "S3_SECRET_KEY": self.s3_secret_key,
        }.items() if not v]
        if missing:
            raise SystemExit("worker is not configured; set: " + ", ".join(missing))
        # Values are never echoed: they sit next to secrets in the same environment.
        from urllib.parse import urlsplit
        malformed = [n for n, v in {"CHAYA_API_URL": self.api_url, "CHAYA_TOKEN_URL": self.token_url,
                                    "S3_ENDPOINT": self.s3_endpoint}.items()
                     if urlsplit(v).scheme not in ("http", "https") or not urlsplit(v).hostname]
        if malformed:
            raise SystemExit("worker is misconfigured; not an absolute http(s) URL: " + ", ".join(malformed))
        # A misspelt stage would never be claimed: the worker would run, report itself alive, and that stage's jobs
        # would wait for another worker until their deadline. Refuse to start instead.
        from .stages import STAGE_ORDER
        unknown = [s for s in self.stages if s not in STAGE_ORDER]
        if unknown:
            raise SystemExit("worker is misconfigured; WORKER_STAGES names unknown stages: " + ", ".join(unknown)
                             + " (known: " + ", ".join(STAGE_ORDER) + ")")

    def config_snapshot(self) -> dict[str, object]:
        """The tunables that influence stage output; recorded in the stage command for reproducibility."""
        return {
            "frame_fps": self.frame_fps, "max_frame_height": self.max_frame_height,
            "max_frames_per_video": self.max_frames_per_video, "blur_threshold": self.blur_threshold,
            "dark_fraction_max": self.dark_fraction_max, "bright_fraction_max": self.bright_fraction_max,
            "duplicate_distance": self.duplicate_distance, "min_frames": self.min_frames,
            "min_registered_ratio": self.min_registered_ratio, "colmap_num_threads": self.colmap_num_threads,
            "privacy_screen_detector": self.privacy_screen_detector,
            "privacy_sfm_mask_margin_px": self.privacy_sfm_mask_margin_px,
            "privacy_detection_max_masked_fraction": self.privacy_detection_max_masked_fraction,
            "gsplat_iterations": self.gsplat_iterations, "gsplat_lr_position": self.gsplat_lr_position,
            "gsplat_lr_position_final": self.gsplat_lr_position_final,
            "gsplat_lr_position_decay_steps": self.gsplat_lr_position_decay_steps,
            "gsplat_lr_scale": self.gsplat_lr_scale, "gsplat_lr_rotation": self.gsplat_lr_rotation,
            "gsplat_lr_opacity": self.gsplat_lr_opacity, "gsplat_lr_color": self.gsplat_lr_color,
            "gsplat_ssim_weight": self.gsplat_ssim_weight,
            "gsplat_densify_start": self.gsplat_densify_start, "gsplat_densify_stop": self.gsplat_densify_stop,
            "gsplat_densify_every": self.gsplat_densify_every, "gsplat_densify_grad_threshold": self.gsplat_densify_grad_threshold,
            "gsplat_percent_dense": self.gsplat_percent_dense, "gsplat_prune_opacity": self.gsplat_prune_opacity,
            "gsplat_prune_scale_fraction": self.gsplat_prune_scale_fraction,
            "gsplat_opacity_reset_every": self.gsplat_opacity_reset_every, "gsplat_max_gaussians": self.gsplat_max_gaussians,
            "gsplat_checkpoint_every": self.gsplat_checkpoint_every, "gsplat_seed": self.gsplat_seed,
            "gsplat_stop_margin_seconds": self.gsplat_stop_margin_seconds,
            "gsplat_keyframe_every": self.gsplat_keyframe_every,
            "gsplat_min_compute_capability": self.gsplat_min_compute_capability,
            "gsplat_min_training_frames": self.gsplat_min_training_frames,
            "gsplat_eval_holdout_every": self.gsplat_eval_holdout_every,
            "gsplat_quality_min_psnr_gain_db": self.gsplat_quality_min_psnr_gain_db,
            "gsplat_quality_min_psnr_db": self.gsplat_quality_min_psnr_db,
            "gsplat_quality_max_loss_ratio": self.gsplat_quality_max_loss_ratio,
            "gsplat_quality_require_held_out": self.gsplat_quality_require_held_out,
            "gsplat_allow_jit_backend": self.gsplat_allow_jit_backend,
            "semantic_confidence_min": self.semantic_confidence_min, "semantic_min_vote_share": self.semantic_min_vote_share, "semantic_sample_every": self.semantic_sample_every,
            "semantic_segmentation_model": self.semantic_segmentation_model,
            "semantic_segmentation_revision": self.semantic_segmentation_revision or None,
            "cleanup_stat_nb_neighbors": self.cleanup_stat_nb_neighbors, "cleanup_stat_std_ratio": self.cleanup_stat_std_ratio,
            "cleanup_radius_nb_points": self.cleanup_radius_nb_points,
            "cleanup_radius_neighbor_share": self.cleanup_radius_neighbor_share,
            "cleanup_radius_spacing_factor": self.cleanup_radius_spacing_factor,
            "cleanup_opacity_threshold": self.cleanup_opacity_threshold,
            "cleanup_semantic_min_neighbors": self.cleanup_semantic_min_neighbors,
            "plane_ransac_distance_spacing_factor": self.plane_ransac_distance_spacing_factor, "plane_ransac_n": self.plane_ransac_n,
            "plane_ransac_iterations": self.plane_ransac_iterations, "plane_max_planes": self.plane_max_planes,
            "plane_min_inliers": self.plane_min_inliers, "ksplat_compression_level": self.ksplat_compression_level,
            "gravity_max_camera_plane_angle_deg": self.gravity_max_camera_plane_angle_deg,
            "gravity_min_camera_up_consistency": self.gravity_min_camera_up_consistency,
            "gravity_min_cameras_above_fraction": self.gravity_min_cameras_above_fraction,
            "grounding_dino_model": self.grounding_dino_model, "object_detection_prompt": self.object_detection_prompt,
            "grounding_dino_revision": self.grounding_dino_revision or None, "allow_pickle_weights": self.allow_pickle_weights,
            "object_detection_box_threshold": self.object_detection_box_threshold,
            "object_detection_text_threshold": self.object_detection_text_threshold,
            "clip_model_name": self.clip_model_name, "clip_pretrained": self.clip_pretrained,
            "semantic_indexing_sample_every": self.semantic_indexing_sample_every,
            "object_cluster_distance_m": self.object_cluster_distance_m,
            "localization": self.localization_params().__dict__,
            "navmesh_cell_size_m": self.navmesh_cell_size_m, "navmesh_cell_height_m": self.navmesh_cell_height_m,
            "navmesh_agent_height_m": self.navmesh_agent_height_m, "navmesh_agent_radius_m": self.navmesh_agent_radius_m,
            "navmesh_agent_max_climb_m": self.navmesh_agent_max_climb_m, "navmesh_agent_max_slope_deg": self.navmesh_agent_max_slope_deg,
            "navmesh_region_min_area_m2": self.navmesh_region_min_area_m2, "navmesh_region_merge_area_m2": self.navmesh_region_merge_area_m2,
            "navmesh_edge_max_len_m": self.navmesh_edge_max_len_m, "navmesh_edge_max_error_m": self.navmesh_edge_max_error_m,
            "navmesh_verts_per_poly": self.navmesh_verts_per_poly, "navmesh_detail_sample_dist_m": self.navmesh_detail_sample_dist_m,
            "navmesh_detail_sample_max_error_m": self.navmesh_detail_sample_max_error_m, "navmesh_floor_grid_m": self.navmesh_floor_grid_m,
            "navmesh_floor_min_points_per_cell": self.navmesh_floor_min_points_per_cell, "navmesh_obstacle_min_height_m": self.navmesh_obstacle_min_height_m,
            "navmesh_max_ramp_slope_deg": self.navmesh_max_ramp_slope_deg,
            "navmesh_ground_band_m": self.navmesh_ground_band_m, "navmesh_step_min_rise_m": self.navmesh_step_min_rise_m,
            "navmesh_max_level_above_floor_m": self.navmesh_max_level_above_floor_m,
            "navmesh_accessible_width_m": self.navmesh_accessible_width_m, "navmesh_width_cap_m": self.navmesh_width_cap_m,
            "navmesh_floor_max_tilt_deg": self.navmesh_floor_max_tilt_deg,
            "alignment_voxel_size_m": self.alignment_voxel_size_m,
            "alignment_max_scale_correction": self.alignment_max_scale_correction,
            "min_alignment_confidence": self.min_alignment_confidence,
            "alignment_icp_voxel_m": self.alignment_icp_voxel_m, "alignment_icp_schedule_m": self.alignment_icp_schedule_m,
            "alignment_crop_margin_m": self.alignment_crop_margin_m,
            "alignment_gates": self.alignment_gates().__dict__,
            "splice_z_margin_m": self.splice_z_margin_m, "splice_seam_band_m": self.splice_seam_band_m,
            "splice_max_seam_step_m": self.splice_max_seam_step_m,
            "splice_coverage_cell_m": self.splice_coverage_cell_m, "splice_max_uncovered_fraction": self.splice_max_uncovered_fraction,
            "splice_min_volume_height_m": self.splice_min_volume_height_m,
            "room_feature_voxel_m": self.room_feature_voxel_m, "room_icp_voxel_m": self.room_icp_voxel_m,
            "room_icp_schedule_m": self.room_icp_schedule_m, "room_overlap_radius_m": self.room_overlap_radius_m,
            "room_min_correspondences": self.room_min_correspondences, "room_max_relative_scale": self.room_max_relative_scale,
            "room_min_constraint_ratio": self.room_min_constraint_ratio,
            "room_max_ambiguity_ratio": self.room_max_ambiguity_ratio,
            "room_max_free_space_conflict": self.room_max_free_space_conflict,
            "room_max_loop_translation_m": self.room_max_loop_translation_m,
            "room_max_loop_rotation_deg": self.room_max_loop_rotation_deg,
            "stitch_voxel_m": self.stitch_voxel_m, "stitch_max_misalignment_m": self.stitch_max_misalignment_m,
            "stitch_max_misalignment_deg": self.stitch_max_misalignment_deg, "stitch_max_seam_step_m": self.stitch_max_seam_step_m,
            "stitch_seam_band_m": self.stitch_seam_band_m, "stitch_colour_warning": self.stitch_colour_warning,
            "stitch_footprint_cell_m": self.stitch_footprint_cell_m,
            "stitch_require_connected_rooms": self.stitch_require_connected_rooms,
        }
