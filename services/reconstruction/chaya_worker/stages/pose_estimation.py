"""Stage 5: camera pose estimation with GLOMAP, falling back to COLMAP's incremental mapper.

Pipeline (all real COLMAP/GLOMAP command lines, executed through the stage runner so stdout/stderr and exit
codes are captured):
    colmap feature_extractor -> colmap {exhaustive,sequential}_matcher -> glomap mapper (or colmap mapper)
    -> colmap model_converter (TXT) -> poses.json

Nothing is simulated. If COLMAP is missing the stage fails with DEPENDENCY_UNAVAILABLE; GLOMAP is optional
(COLMAP alone can map). Only command construction and the poses parser are unit-tested without the tools;
executing the stage needs the toolchain and is covered by the gpu-marked tests, which skip when it is absent.
COLMAP flag names follow the 3.9/3.10 CLI (--SiftExtraction.*); newer releases renamed some options.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

from .. import archive
from ..contract import ArtifactSpec, StageContext, StageError, StageResult
from .base import command_record, write_json

EXHAUSTIVE_MAX_FRAMES = 300


def build_pose_commands(*, colmap: str, glomap: str | None, database: Path, images: Path, sparse: Path, use_gpu: bool,
                        frame_count: int, mapper: str) -> dict[str, list[str]]:
    """Pure: the command lines of each step. mapper is 'glomap' or 'colmap'."""
    gpu = "1" if use_gpu else "0"
    matcher = "exhaustive_matcher" if frame_count <= EXHAUSTIVE_MAX_FRAMES else "sequential_matcher"
    commands = {
        "feature_extractor": [colmap, "feature_extractor", "--database_path", str(database), "--image_path", str(images),
                              "--ImageReader.single_camera", "1", "--ImageReader.camera_model", "SIMPLE_RADIAL",
                              "--SiftExtraction.use_gpu", gpu],
        "matcher": [colmap, matcher, "--database_path", str(database), "--SiftMatching.use_gpu", gpu],
    }
    if mapper == "glomap":
        if not glomap:
            raise ValueError("glomap mapper requested but no glomap binary given")
        commands["mapper"] = [glomap, "mapper", "--database_path", str(database), "--image_path", str(images), "--output_path", str(sparse)]
    else:
        commands["mapper"] = [colmap, "mapper", "--database_path", str(database), "--image_path", str(images), "--output_path", str(sparse)]
    return commands


def parse_images_txt(text: str) -> list[dict[str, Any]]:
    """Parse COLMAP's images.txt: every other non-comment line is `IMAGE_ID QW QX QY QZ TX TY TZ CAMERA_ID NAME`."""
    poses: list[dict[str, Any]] = []
    lines = [ln for ln in text.splitlines() if ln.strip() and not ln.startswith("#")]
    for header in lines[::2]:  # the lines in between list 2D points
        parts = header.split()
        if len(parts) < 10:
            raise ValueError(f"malformed images.txt line: {header!r}")
        qw, qx, qy, qz, tx, ty, tz = map(float, parts[1:8])
        poses.append({"image_id": int(parts[0]), "name": parts[9], "camera_id": int(parts[8]),
                      "rotation_wxyz": [qw, qx, qy, qz], "translation": [tx, ty, tz]})
    return poses


def _model_dirs(sparse: Path) -> list[Path]:
    return sorted(p for p in sparse.iterdir() if p.is_dir() and (p / "images.bin").is_file()) if sparse.is_dir() else []


class PoseEstimation:
    name = "POSE_ESTIMATION"

    def run(self, ctx: StageContext) -> StageResult:
        ctx.toolchain.require(["colmap"], stage=self.name)  # GLOMAP is optional: COLMAP can map on its own
        archives = ctx.inputs_of("FRAME_ARCHIVE_ANON")
        if not archives:
            raise StageError("no anonymised frame archive was provided by the previous stage", code="INPUT_INVALID")
        colmap = ctx.toolchain.colmap().path
        glomap = ctx.toolchain.glomap()
        images_dir = ctx.workdir / "images"
        frames = archive.unpack(archives[0].path, images_dir)
        database, sparse = ctx.workdir / "database.db", ctx.workdir / "sparse"
        sparse.mkdir()
        use_gpu = ctx.toolchain.gpu_present()

        first_mapper = "glomap" if glomap.available else "colmap"
        steps = build_pose_commands(colmap=colmap, glomap=glomap.path, database=database, images=images_dir, sparse=sparse,
                                    use_gpu=use_gpu, frame_count=len(frames), mapper=first_mapper)
        ctx.runner.run(steps["feature_extractor"], error_code="FEATURE_EXTRACTION_FAILED", timeout=7200)
        ctx.runner.run(steps["matcher"], error_code="FEATURE_MATCHING_FAILED", timeout=7200)

        used, fallback = first_mapper, False
        try:
            ctx.runner.run(steps["mapper"], error_code="MAPPING_FAILED", timeout=14400)
            if not _model_dirs(sparse):
                raise StageError(f"{first_mapper} finished but produced no model", code="MAPPING_FAILED")
        except StageError as exc:
            if first_mapper == "glomap" and exc.code != "TIME_LIMIT_EXCEEDED":
                ctx.logger.warning("GLOMAP failed; falling back to COLMAP mapper", extra={"reason": exc.message})
                steps = build_pose_commands(colmap=colmap, glomap=None, database=database, images=images_dir, sparse=sparse,
                                            use_gpu=use_gpu, frame_count=len(frames), mapper="colmap")
                ctx.runner.run(steps["mapper"], error_code="MAPPING_FAILED", timeout=14400)
                used, fallback = "colmap", True
            else:
                raise
        models = _model_dirs(sparse)
        if not models:
            raise StageError("no sparse model was produced", code="MAPPING_FAILED")
        model = models[0]

        txt = ctx.workdir / "txt"
        txt.mkdir()
        ctx.runner.run([colmap, "model_converter", "--input_path", str(model), "--output_path", str(txt), "--output_type", "TXT"],
                       error_code="MODEL_CONVERSION_FAILED", timeout=600)
        poses = parse_images_txt((txt / "images.txt").read_text(encoding="utf-8"))
        ratio = len(poses) / len(frames)
        if len(poses) < 3 or ratio < ctx.settings.min_registered_ratio:
            raise StageError(f"only {len(poses)} of {len(frames)} frames were registered ({ratio:.0%}); need at least "
                             f"{ctx.settings.min_registered_ratio:.0%}", code="POSE_ESTIMATION_INSUFFICIENT",
                             details={"registered": len(poses), "frames": len(frames), "mapper": used})
        sparse_tar = ctx.workdir / "sparse-model.tar"
        archive.pack(model, sparse_tar)
        poses_json = write_json(ctx.workdir / "poses.json", {"mapper": used, "fallback_used": fallback, "frames": len(frames),
                                                              "registered": len(poses), "poses": poses})
        return StageResult(
            "SUCCEEDED", command_record(ctx, {"mapper_used": used, "fallback_used": fallback, "gpu": use_gpu}),
            ctx.runner.last_exit_status(),
            [ArtifactSpec("SPARSE_MODEL", sparse_tar, "sparse-model.tar", "application/x-tar"),
             ArtifactSpec("POSES", poses_json, "poses.json", "application/json")])
