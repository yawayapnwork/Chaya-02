"""Stage 10: generates the deliverable artifacts -- the .ksplat the web viewer loads and the manifest that
records exactly how every artifact in the run was produced.

Conversion to .ksplat (chaya_worker.ksplat: the level-0 KSplat layout of the pinned viewer library,
@mkkellogg/gaussian-splats-3d 0.4.7, proven by apps/web/lib/ksplat-compat.test.ts) only runs, and only publishes a
file, when the source Gaussian cloud actually decoded; there is no code path that writes an empty or placeholder
.ksplat. The manifest
(chaya_worker.manifest) covers both this stage's own outputs and the upstream artifacts it consumed, each
with its checksum, artifact/worker version, source scan version and the processing configuration used.
"""

from __future__ import annotations

import tarfile
from pathlib import Path

from .. import __version__
from ..contract import ArtifactSpec, StageContext, StageError, StageResult
from ..ksplat import MAX_BYTES, KsplatInvalid, encoded_size, validate_file, write_ksplat
from ..manifest import build_manifest
from ..ply import read_ply
from .base import command_record, sha256_file, venue_cloud, write_json


class ArtifactGeneration:
    name = "ARTIFACT_GENERATION"

    def run(self, ctx: StageContext) -> StageResult:
        # A re-scan's viewer asset is the merged venue, never the region alone (venue_cloud refuses that).
        _, splats, _, _ = venue_cloud(ctx, self.name)
        planes_inputs = ctx.inputs_of("PLANE_MODEL")

        cloud = read_ply(splats[0].path)
        s = ctx.settings
        ksplat_path = ctx.workdir / "scene.ksplat"
        if encoded_size(len(cloud)) > MAX_BYTES:  # known before encoding; the API and the viewer would refuse it anyway
            raise StageError(f"{len(cloud)} Gaussians make a {encoded_size(len(cloud))}-byte .ksplat; the viewer limit is "
                             f"{MAX_BYTES} bytes", code="KSPLAT_TOO_LARGE",
                             details={"gaussian_count": len(cloud), "max_bytes": MAX_BYTES})
        write_ksplat(cloud, ksplat_path, compression_level=s.ksplat_compression_level)
        try:
            # The same contract the API checks before publishing: never upload a file the viewer cannot load.
            ksplat_format = validate_file(ksplat_path)
        except KsplatInvalid as exc:
            raise StageError(f"the written .ksplat does not meet the viewer contract: {exc}", code="KSPLAT_INVALID",
                             details={"reason": exc.reason}) from exc

        bundle_members = [ksplat_path]
        if planes_inputs:
            bundle_members.append(planes_inputs[0].path)

        order = ctx.order
        processing_configuration = s.config_snapshot()
        upstream = [{"name": Path(i.ref["key"]).name, "kind": i.kind, "sha256": i.ref["sha256"], "sizeBytes": i.ref["sizeBytes"]}
                   for i in ctx.inputs]
        generated = [{"name": "scene.ksplat", "kind": "KSPLAT", "sha256": sha256_file(ksplat_path), "sizeBytes": ksplat_path.stat().st_size,
                      "format": ksplat_format}]
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
