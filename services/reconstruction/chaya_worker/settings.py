"""Worker configuration, read from environment variables only. Nothing secret has a default."""

from __future__ import annotations

import os
import socket
from dataclasses import dataclass, field
from pathlib import Path
from typing import Mapping


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
    privacy_screen_detector: str = "heuristic-quad"

    @staticmethod
    def from_env(env: Mapping[str, str] | None = None) -> "Settings":
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
            privacy_screen_detector=e.get("PRIVACY_SCREEN_DETECTOR", d.privacy_screen_detector),
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
            "min_registered_ratio": self.min_registered_ratio, "privacy_screen_detector": self.privacy_screen_detector,
        }
