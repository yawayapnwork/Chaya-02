"""Stage 10: generates the deliverable artifacts -- the .ksplat the web viewer loads and the manifest that
records exactly how every artifact in the run was produced.

Conversion to .ksplat (chaya_worker.ksplat) only runs, and only publishes a file, when the source Gaussian
cloud actually decoded; there is no code path that writes an empty or placeholder .ksplat. The manifest
(chaya_worker.manifest) covers both this stage's own outputs and the upstream artifacts it consumed, each
with its checksum, artifact/worker version, source scan version and the processing configuration used.
"""

from __future__ import annotations

import tarfile
from pathlib import Path

from .. import __version__
from ..contract import ArtifactSpec, StageContext, StageError, StageResult
from ..ksplat import write_ksplat
from ..manifest import build_manifest
from ..ply import read_ply
from .base import command_record, sha256_file, write_json


class ArtifactGeneration:
    name = "ARTIFACT_GENERATION"

    def run(self, ctx: StageContext) -> StageResult:
        splats = ctx.inputs_of("SPLAT_CLEAN") or ctx.inputs_of("SPLAT")
        if not splats:
            raise StageError("no cleaned (or raw) Gaussian splat was provided to ARTIFACT_GENERATION", code="INPUT_INVALID")
        planes_inputs = ctx.inputs_of("PLANE_MODEL")

        cloud = read_ply(splats[0].path)
        s = ctx.settings
        ksplat_path = ctx.workdir / "scene.ksplat"
        write_ksplat(cloud, ksplat_path, compression_level=s.ksplat_compression_level)

        bundle_members = [ksplat_path]
        if planes_inputs:
            bundle_members.append(planes_inputs[0].path)

        order = ctx.order
        processing_configuration = s.config_snapshot()
        upstream = [{"name": Path(i.ref["key"]).name, "kind": i.kind, "sha256": i.ref["sha256"], "sizeBytes": i.ref["sizeBytes"]}
                   for i in ctx.inputs]
        generated = [{"name": "scene.ksplat", "kind": "KSPLAT", "sha256": sha256_file(ksplat_path), "sizeBytes": ksplat_path.stat().st_size}]
        manifest = build_manifest(run_id=str(order.get("runId")), scan_id=str(order.get("scanId")),
                                  source_scan_version=order.get("scanVersionId"), worker_version=__version__,
                                  processing_configuration=processing_configuration, upstream_artifacts=upstream,
                                  generated_artifacts=generated)
        manifest_path = write_json(ctx.workdir / "manifest.json", manifest)
        bundle_members.append(manifest_path)

        bundle_path = ctx.workdir / "viewer-bundle.tar.gz"
        with tarfile.open(bundle_path, "w:gz") as tar:
            for member in bundle_members:
                tar.add(member, arcname=member.name)

        ctx.logger.info("artifact generation done", extra={"gaussian_count": len(cloud), "ksplat_bytes": ksplat_path.stat().st_size,
                                                            "bundle_bytes": bundle_path.stat().st_size})
        return StageResult(
            "SUCCEEDED", command_record(ctx, {"gaussian_count": len(cloud), "ksplat_compression_level": s.ksplat_compression_level}),
            ctx.runner.last_exit_status(),
            [ArtifactSpec("KSPLAT", ksplat_path, "scene.ksplat", "application/octet-stream"),
             ArtifactSpec("ARTIFACT_MANIFEST", manifest_path, "manifest.json", "application/json"),
             ArtifactSpec("VIEWER_BUNDLE", bundle_path, "viewer-bundle.tar.gz", "application/gzip")])
