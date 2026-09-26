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
    poll_interval: float = 5.0
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
    # SPLAT_RECONSTRUCTION (gsplat)
    gsplat_iterations: int = 7000
    gsplat_lr_position: float = 1.6e-4
    gsplat_lr_other: float = 5e-3
    gsplat_ssim_weight: float = 0.2
    gsplat_keyframe_every: int = 500
    gsplat_min_compute_capability: float = 7.0
    # SEMANTIC_SEGMENTATION
    semantic_confidence_min: float = 0.5
    semantic_sample_every: int = 1  # segment every Nth registered frame (cost control)
    semantic_segmentation_model: str = "nvidia/segformer-b0-finetuned-ade-512-512"
    semantic_segmentation_revision: str = ""  # commit SHA; empty = unpinned (logged)
    # GEOMETRIC_CLEANUP (Open3D). Runs before metric calibration exists, in arbitrary reconstruction units, so its
    # distance threshold is a multiple of the cloud's own median nearest-neighbour spacing (scale-invariant), never
    # a number of metres. 3.0 is the scale-relative radius benchmarks/b2_geometry_cleanup already measured with;
    # it has not been tuned on a real venue reconstruction.
    cleanup_stat_nb_neighbors: int = 20
    cleanup_stat_std_ratio: float = 2.0
    cleanup_radius_nb_points: int = 8
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
    # Recast's input geometry from a reconstruction (chaya_worker.navmesh.geometry_from_reconstruction): floor
    # occupancy-grid cell size, how many floor-plane inliers make a cell observed floor, and the least height an
    # obstacle box is given above the floor (so a detected wall/furniture cell always blocks).
    navmesh_floor_grid_m: float = 0.2
    navmesh_floor_min_points_per_cell: int = 3
    navmesh_obstacle_min_height_m: float = 1.0
    # ADA-inspired accessible-ramp threshold (1:12 rise:run ~= 4.8 degrees); a polygon steeper than this
    # is excluded from the STEP_FREE routing graph regardless of whether Recast still considers it walkable.
    navmesh_max_ramp_slope_deg: float = 5.0
    # A plane counts as horizontal (a floor candidate) within this angle of canonical +Z.
    navmesh_floor_max_tilt_deg: float = 10.0
    # REGION_ALIGNMENT / REGION_SPLICE (incremental re-scan; see docs/rescan.md). Registration runs in canonical
    # metres: the region is pre-scaled by its own metric calibration, the venue by the parent's frame.
    alignment_voxel_size_m: float = 0.05
    # Registration may refine the region's scale (its calibration is measured, not exact) but a correction beyond
    # this fraction means the region's calibration and the venue's disagree; the stage fails instead of merging.
    alignment_max_scale_correction: float = 0.1
    # The control plane independently re-checks this against chaya.rescan.min-alignment-confidence
    # (RescanProperties) before ever finalizing a ScanVersion -- this is the worker's own gate so a bad
    # splice is refused even before the report reaches the server. Never merge below this line.
    min_alignment_confidence: float = 0.6

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
            poll_interval=_float(e, "WORKER_POLL_INTERVAL", d.poll_interval),
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
            gsplat_iterations=_int(e, "GSPLAT_ITERATIONS", d.gsplat_iterations),
            gsplat_lr_position=_float(e, "GSPLAT_LR_POSITION", d.gsplat_lr_position),
            gsplat_lr_other=_float(e, "GSPLAT_LR_OTHER", d.gsplat_lr_other),
            gsplat_ssim_weight=_float(e, "GSPLAT_SSIM_WEIGHT", d.gsplat_ssim_weight),
            gsplat_keyframe_every=_int(e, "GSPLAT_KEYFRAME_EVERY", d.gsplat_keyframe_every),
            gsplat_min_compute_capability=_float(e, "GSPLAT_MIN_COMPUTE_CAPABILITY", d.gsplat_min_compute_capability),
            semantic_confidence_min=_float(e, "SEMANTIC_CONFIDENCE_MIN", d.semantic_confidence_min),
            semantic_sample_every=_int(e, "SEMANTIC_SAMPLE_EVERY", d.semantic_sample_every),
            semantic_segmentation_model=e.get("SEMANTIC_SEGMENTATION_MODEL", d.semantic_segmentation_model),
            semantic_segmentation_revision=e.get("SEMANTIC_SEGMENTATION_REVISION", d.semantic_segmentation_revision),
            cleanup_stat_nb_neighbors=_int(e, "CLEANUP_STAT_NB_NEIGHBORS", d.cleanup_stat_nb_neighbors),
            cleanup_stat_std_ratio=_float(e, "CLEANUP_STAT_STD_RATIO", d.cleanup_stat_std_ratio),
            cleanup_radius_nb_points=_int(e, "CLEANUP_RADIUS_NB_POINTS", d.cleanup_radius_nb_points),
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
            navmesh_max_ramp_slope_deg=_float(e, "NAVMESH_MAX_RAMP_SLOPE_DEG", d.navmesh_max_ramp_slope_deg),
            navmesh_floor_max_tilt_deg=_float(e, "NAVMESH_FLOOR_MAX_TILT_DEG", d.navmesh_floor_max_tilt_deg),
            alignment_voxel_size_m=_float(e, "ALIGNMENT_VOXEL_SIZE_M", d.alignment_voxel_size_m),
            alignment_max_scale_correction=_float(e, "ALIGNMENT_MAX_SCALE_CORRECTION", d.alignment_max_scale_correction),
            min_alignment_confidence=_float(e, "MIN_ALIGNMENT_CONFIDENCE", d.min_alignment_confidence),
        )

    def require_service_config(self) -> None:
        missing = [n for n, v in {
            "CHAYA_API_URL": self.api_url, "CHAYA_TOKEN_URL": self.token_url, "CHAYA_CLIENT_SECRET": self.client_secret,
            "S3_ENDPOINT": self.s3_endpoint, "S3_ACCESS_KEY": self.s3_access_key, "S3_SECRET_KEY": self.s3_secret_key,
        }.items() if not v]
        if missing:
            raise SystemExit("worker is not configured; set: " + ", ".join(missing))

    def config_snapshot(self) -> dict[str, object]:
        """The tunables that influence stage output; recorded in the stage command for reproducibility."""
        return {
            "frame_fps": self.frame_fps, "max_frame_height": self.max_frame_height,
            "max_frames_per_video": self.max_frames_per_video, "blur_threshold": self.blur_threshold,
            "dark_fraction_max": self.dark_fraction_max, "bright_fraction_max": self.bright_fraction_max,
            "duplicate_distance": self.duplicate_distance, "min_frames": self.min_frames,
            "min_registered_ratio": self.min_registered_ratio, "colmap_num_threads": self.colmap_num_threads,
            "privacy_screen_detector": self.privacy_screen_detector,
            "gsplat_iterations": self.gsplat_iterations, "gsplat_lr_position": self.gsplat_lr_position,
            "gsplat_lr_other": self.gsplat_lr_other, "gsplat_ssim_weight": self.gsplat_ssim_weight,
            "gsplat_keyframe_every": self.gsplat_keyframe_every,
            "gsplat_min_compute_capability": self.gsplat_min_compute_capability,
            "semantic_confidence_min": self.semantic_confidence_min, "semantic_sample_every": self.semantic_sample_every,
            "semantic_segmentation_model": self.semantic_segmentation_model,
            "semantic_segmentation_revision": self.semantic_segmentation_revision or None,
            "cleanup_stat_nb_neighbors": self.cleanup_stat_nb_neighbors, "cleanup_stat_std_ratio": self.cleanup_stat_std_ratio,
            "cleanup_radius_nb_points": self.cleanup_radius_nb_points,
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
            "navmesh_cell_size_m": self.navmesh_cell_size_m, "navmesh_cell_height_m": self.navmesh_cell_height_m,
            "navmesh_agent_height_m": self.navmesh_agent_height_m, "navmesh_agent_radius_m": self.navmesh_agent_radius_m,
            "navmesh_agent_max_climb_m": self.navmesh_agent_max_climb_m, "navmesh_agent_max_slope_deg": self.navmesh_agent_max_slope_deg,
            "navmesh_region_min_area_m2": self.navmesh_region_min_area_m2, "navmesh_region_merge_area_m2": self.navmesh_region_merge_area_m2,
            "navmesh_edge_max_len_m": self.navmesh_edge_max_len_m, "navmesh_edge_max_error_m": self.navmesh_edge_max_error_m,
            "navmesh_verts_per_poly": self.navmesh_verts_per_poly, "navmesh_detail_sample_dist_m": self.navmesh_detail_sample_dist_m,
            "navmesh_detail_sample_max_error_m": self.navmesh_detail_sample_max_error_m, "navmesh_floor_grid_m": self.navmesh_floor_grid_m,
            "navmesh_floor_min_points_per_cell": self.navmesh_floor_min_points_per_cell, "navmesh_obstacle_min_height_m": self.navmesh_obstacle_min_height_m,
            "navmesh_max_ramp_slope_deg": self.navmesh_max_ramp_slope_deg,
            "navmesh_floor_max_tilt_deg": self.navmesh_floor_max_tilt_deg,
            "alignment_voxel_size_m": self.alignment_voxel_size_m,
            "alignment_max_scale_correction": self.alignment_max_scale_correction,
            "min_alignment_confidence": self.min_alignment_confidence,
        }
