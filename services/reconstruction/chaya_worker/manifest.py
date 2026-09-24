"""Builds the artifact manifest ARTIFACT_GENERATION publishes: for every artifact in the run (the upstream
ones it consumed and the deliverables it produced), the version/checksum/source-scan-version/processing
configuration/creation timestamp the task requires. Pure function, no I/O beyond the checksums it is given.
"""

from __future__ import annotations

from datetime import UTC, datetime
from typing import Any


def _iso_now() -> str:
    return datetime.now(UTC).isoformat(timespec="milliseconds")


def manifest_entry(*, name: str, kind: str, sha256: str, size_bytes: int, artifact_version: str,
                   source_scan_version: str | None, processing_configuration: dict[str, Any],
                   created_at: str | None = None) -> dict[str, Any]:
    return {
        "name": name, "kind": kind, "checksum": {"algorithm": "sha256", "value": sha256}, "sizeBytes": size_bytes,
        "artifactVersion": artifact_version, "sourceScanVersion": source_scan_version,
        "processingConfiguration": processing_configuration, "createdAt": created_at or _iso_now(),
    }


def build_manifest(*, run_id: str, scan_id: str, source_scan_version: str | None, worker_version: str,
                   processing_configuration: dict[str, Any], upstream_artifacts: list[dict[str, Any]],
                   generated_artifacts: list[dict[str, Any]]) -> dict[str, Any]:
    """`upstream_artifacts` / `generated_artifacts` entries need at least: name, kind, sha256, sizeBytes."""
    created_at = _iso_now()
    entries = [
        manifest_entry(name=a["name"], kind=a["kind"], sha256=a["sha256"], size_bytes=a["sizeBytes"],
                       artifact_version=worker_version, source_scan_version=source_scan_version,
                       processing_configuration=processing_configuration, created_at=created_at)
        for a in (*upstream_artifacts, *generated_artifacts)
    ]
    return {"manifestVersion": "1.0", "runId": run_id, "scanId": scan_id, "sourceScanVersion": source_scan_version,
           "workerVersion": worker_version, "createdAt": created_at, "processingConfiguration": processing_configuration,
           "artifacts": entries}
