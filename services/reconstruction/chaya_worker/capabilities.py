"""Which stages this worker can actually execute, so that it offers (claims) only those.

The control plane decides that a stage is served from the stages live workers OFFER in their claim polls
(WorkerPresenceRepository; health reports NO_WORKER with `unservedStages` otherwise). A worker that offered a stage it
cannot run would make that stage look served, claim its jobs and fail them with DEPENDENCY_UNAVAILABLE until their
bounded retries were spent: a CPU worker started without WORKER_STAGES offered SPLAT_RECONSTRUCTION, and so did a GPU
image started without `--gpus`. So each configured stage's hard requirements are checked here, before claiming, and a
stage whose requirements are missing is not offered. Nothing is claimed on the strength of the process being alive.

Hard requirements are what a stage cannot run without for any capture (its `ctx.toolchain.require(...)` calls, and the
splat preflight for SPLAT_RECONSTRUCTION). Every stage still checks again when it runs (defence in depth).

    python -m chaya_worker.capabilities     # JSON: offered and refused stages with reasons; exit 0 = every configured stage offered

WORKER_OFFER_UNAVAILABLE_STAGES=true is a diagnostic mode: configured stages are offered even when refused here, so their
jobs fail with the stage's own structured DEPENDENCY_UNAVAILABLE in the run record. It is never the default.
"""

from __future__ import annotations

import json
import logging
import time
from dataclasses import dataclass, field
from typing import Any

from . import recast, splat_preflight
from .model_provenance import check_license_policy, provenance
from .settings import Settings
from .stages import STAGE_ORDER
from .toolchain import Toolchain

log = logging.getLogger("chaya_worker.capabilities")

OPEN3D = ["py:open3d"]


def stage_requirements(settings: Settings) -> dict[str, list[str]]:
    """Each stage's hard requirements in chaya_worker.toolchain terms. SPLAT_RECONSTRUCTION is checked by its preflight
    (splat_preflight.environment_report) on top of these. Kept in step with the stages by tests/unit/test_capabilities.py."""
    return {
        "INPUT_VALIDATION": ["ffmpeg"],  # every video is decoded with FFmpeg; a worker without it cannot validate one
        "FFMPEG_PREPROCESS": ["ffmpeg"],
        "FRAME_QUALITY_FILTER": [],
        "PRIVACY_PREPROCESS": [],
        "POSE_ESTIMATION": ["colmap"],  # GLOMAP is optional: COLMAP's mapper is the fallback
        "SPLAT_RECONSTRUCTION": [],
        "SEMANTIC_SEGMENTATION": ["py:torch", "py:transformers", "colmap", f"model:{settings.semantic_segmentation_model}"],
        "GEOMETRIC_CLEANUP": OPEN3D,
        "REGION_ALIGNMENT": OPEN3D,
        "REGION_SPLICE": [],
        "ROOM_REGISTRATION": OPEN3D,
        "ROOM_STITCH": [],
        "PLANE_FITTING": OPEN3D,
        "ARTIFACT_GENERATION": [],
        "SEMANTIC_INDEXING": ["py:torch", "py:transformers", "py:open_clip", "py:PIL", "colmap", f"model:{settings.grounding_dino_model}"],
        "NAVIGATION_BAKING": [recast.TOOL_REQUIREMENT],
    }


@dataclass
class Assessment:
    offered: list[str]
    refused: dict[str, list[dict[str, Any]]] = field(default_factory=dict)  # stage -> [{code, message, fix}]
    configured: list[str] = field(default_factory=list)
    splat_preflight: dict[str, Any] | None = None

    def as_dict(self) -> dict[str, Any]:
        return {"configured": self.configured, "offered": self.offered, "refused": self.refused,
                "splat_preflight": self.splat_preflight}


def assess(settings: Settings, toolchain: Toolchain) -> Assessment:
    """The configured stages (WORKER_STAGES, or every stage when it is empty), split into offered and refused."""
    configured = list(settings.stages) or list(STAGE_ORDER)
    requirements = stage_requirements(settings)
    offered: list[str] = []
    refused: dict[str, list[dict[str, Any]]] = {}
    preflight = None
    for stage in configured:
        reasons = [{"code": "DEPENDENCY_UNAVAILABLE", "message": m.detail or f"{m.name} is not available", "missing": m.name}
                   for m in toolchain.missing(requirements.get(stage, []))]
        if stage == "SPLAT_RECONSTRUCTION":
            preflight = splat_preflight.environment_report(
                toolchain, min_compute_capability=settings.gsplat_min_compute_capability,
                allow_jit=settings.gsplat_allow_jit_backend, lock=splat_preflight.load_lock(splat_preflight.DEFAULT_LOCK_PATH),
                min_driver_version=settings.gpu_min_driver_version, min_free_memory_mb=settings.gpu_min_free_memory_mb)
            reasons += [{"code": p["code"], "message": p["message"], "fix": p["fix"]} for p in preflight["problems"]]
        if stage == "SEMANTIC_SEGMENTATION" and settings.commercial_use_only:
            seg_prov = provenance(settings.semantic_segmentation_model,
                                  requested_revision=settings.semantic_segmentation_revision, resolved_revision=None)
            reasons += check_license_policy(seg_prov, commercial_use_only=True)
        if stage == "SEMANTIC_INDEXING" and settings.commercial_use_only:
            det_prov = provenance(settings.grounding_dino_model,
                                  requested_revision=settings.grounding_dino_revision, resolved_revision=None)
            clip_prov = provenance(f"open_clip:{settings.clip_model_name}:{settings.clip_pretrained}",
                                   requested_revision=None, resolved_revision=None)
            reasons += check_license_policy(det_prov, commercial_use_only=True)
            reasons += check_license_policy(clip_prov, commercial_use_only=True)
        if reasons:
            refused[stage] = reasons
        else:
            offered.append(stage)
    return Assessment(offered=offered, refused=refused, configured=configured, splat_preflight=preflight)


class StageOffer:
    """The stages to claim with, re-assessed every `recheck_seconds` (model weights fetched later, a GPU that went away)."""

    def __init__(self, settings: Settings, toolchain_factory=Toolchain, clock=time.monotonic) -> None:
        self.settings = settings
        self._toolchain_factory = toolchain_factory
        self._clock = clock
        self._checked_at: float | None = None
        self.assessment: Assessment | None = None

    def stages(self) -> list[str]:
        now = self._clock()
        if self._checked_at is None or now - self._checked_at >= self.settings.capability_recheck_seconds:
            previous = self.assessment
            self.assessment = assess(self.settings, self._toolchain_factory())
            self._checked_at = now
            if previous is None or previous.offered != self.assessment.offered or previous.refused.keys() != self.assessment.refused.keys():
                self._log(self.assessment)
        if self.settings.offer_unavailable_stages:
            return list(self.assessment.configured)
        return list(self.assessment.offered)

    def _log(self, a: Assessment) -> None:
        extra = {"offered": a.offered, "refused": {s: [r["code"] for r in rs] for s, rs in a.refused.items()},
                 "offer_unavailable_stages": self.settings.offer_unavailable_stages}
        if not a.offered and not self.settings.offer_unavailable_stages:
            log.error("this worker can execute none of its configured stages; it claims nothing", extra=extra)
        elif a.refused:
            log.warning("configured stages this worker cannot execute are not offered", extra=extra)
        else:
            log.info("every configured stage is offered", extra=extra)


def main(argv: list[str] | None = None) -> int:
    a = assess(Settings.from_env(), Toolchain())
    print(json.dumps(a.as_dict(), indent=2, default=str))
    return 0 if not a.refused else 1


if __name__ == "__main__":
    raise SystemExit(main())
