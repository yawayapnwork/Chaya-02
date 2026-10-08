"""The worker checks every .ksplat against the shared viewer contract (packages/contracts/viewer/ksplat-contract.json)
before uploading it; the API (KsplatValidator) and the viewer (lib/ksplat-validation.ts) apply the same rules. Run on the
committed production-encoder files, with every way a file can break the contract."""

from __future__ import annotations

import hashlib
import json
import logging
import struct
from pathlib import Path

import pytest

from chaya_worker import ksplat
from chaya_worker.contract import InputFile, StageContext, StageError
from chaya_worker.runner import CommandRunner
from chaya_worker.settings import Settings
from chaya_worker.stages import artifact_generation
from chaya_worker.stages.artifact_generation import ArtifactGeneration
from chaya_worker.toolchain import Toolchain

CONTRACTS = Path(__file__).resolve().parents[4] / "packages" / "contracts"
SMALL = CONTRACTS / "fixtures" / "ksplat" / "scene.ksplat"
SCENE = CONTRACTS / "fixtures" / "viewer-scene" / "scene.ksplat"


def check(blob: bytes) -> dict:
    return ksplat.validate(blob[: ksplat.DATA_OFFSET], len(blob))


def refused(blob: bytes, reason: str) -> None:
    with pytest.raises(ksplat.KsplatInvalid) as exc:
        check(blob)
    assert exc.value.reason == reason


def mutated(fmt: str, offset: int, value) -> bytes:
    b = bytearray(SMALL.read_bytes())
    struct.pack_into(fmt, b, offset, value)
    return bytes(b)


def test_constants_are_the_shared_contract():
    c = json.loads((CONTRACTS / "viewer" / "ksplat-contract.json").read_text(encoding="utf-8"))
    assert ksplat.VIEWER_LIBRARY == f"{c['library']}@{c['libraryVersion']}"
    assert (ksplat.VERSION_MAJOR, ksplat.VERSION_MINOR) == (c["versionMajor"], c["versionMinor"])
    assert (ksplat.FILE_HEADER_SIZE, ksplat.SECTION_HEADER_SIZE) == (c["fileHeaderBytes"], c["sectionHeaderBytes"])
    assert (ksplat.COMPRESSION_LEVEL, ksplat.SH_DEGREE) == (c["compressionLevel"], c["sphericalHarmonicsDegree"])
    assert (ksplat.BYTES_PER_SPLAT, ksplat.SECTION_COUNT) == (c["bytesPerSplat"], c["sectionCount"])
    assert (ksplat.MIN_SPLAT_COUNT, ksplat.MAX_BYTES) == (c["minSplatCount"], c["maxBytes"])


def test_the_committed_production_files_meet_the_contract():
    assert check(SMALL.read_bytes()) == {
        "format": "ksplat", "contract": "@mkkellogg/gaussian-splats-3d@0.4.7", "version": "0.1", "compressionLevel": 0,
        "sphericalHarmonicsDegree": 0, "sectionCount": 1, "splatCount": 3}
    assert ksplat.validate_file(SCENE)["splatCount"] == 3701


@pytest.mark.parametrize("cut", [1, 44, 100])
def test_truncation_is_refused(cut):
    refused(SMALL.read_bytes()[:-cut], "TRUNCATED")


def test_trailing_bytes_and_foreign_files_are_refused():
    refused(SMALL.read_bytes() + b"\0", "TRAILING_BYTES")
    refused(b"", "TRUNCATED")
    refused((CONTRACTS / "fixtures" / "ksplat" / "scene.ply").read_bytes(), "TRUNCATED")


@pytest.mark.parametrize("fmt,offset,value,reason", [
    ("<B", 1, 0, "UNSUPPORTED_VERSION"),
    ("<B", 1, 2, "UNSUPPORTED_VERSION"),
    ("<B", 0, 1, "UNSUPPORTED_VERSION"),
    ("<H", 20, 1, "UNSUPPORTED_COMPRESSION"),
    ("<H", 4096 + 40, 1, "UNSUPPORTED_SH_DEGREE"),
    ("<I", 4, 2, "UNSUPPORTED_LAYOUT"),
    ("<I", 4096 + 12, 1, "UNSUPPORTED_LAYOUT"),
    ("<I", 16, 2, "INCONSISTENT_COUNTS"),
    ("<I", 4096 + 4, 4, "INCONSISTENT_COUNTS"),
    ("<I", 4096 + 28, 0, "INCONSISTENT_COUNTS"),
])
def test_headers_outside_the_contract_are_refused(fmt, offset, value, reason):
    refused(mutated(fmt, offset, value), reason)


def test_size_limit_is_checked_before_reading():
    with pytest.raises(ksplat.KsplatInvalid) as exc:
        ksplat.validate(b"", ksplat.MAX_BYTES + 1)
    assert exc.value.reason == "TOO_LARGE"


def _context(tmp_path: Path) -> StageContext:
    ply = CONTRACTS / "fixtures" / "ksplat" / "scene.ply"
    ref = {"artifactId": "a", "kind": "SPLAT", "stage": "TEST", "key": ply.name,
           "sha256": hashlib.sha256(ply.read_bytes()).hexdigest(), "sizeBytes": ply.stat().st_size}
    return StageContext({"stage": "ARTIFACT_GENERATION", "runId": "r", "scanId": "s", "inputs": [ref]}, [InputFile(ref, ply)],
                        tmp_path, logging.getLogger("test"), CommandRunner(tmp_path / "o.log", tmp_path / "e.log"),
                        Toolchain(), Settings(), None)


def test_artifact_generation_records_the_validated_format_in_the_manifest(tmp_path):
    result = ArtifactGeneration().run(_context(tmp_path))
    assert result.status == "SUCCEEDED"
    manifest = json.loads(next(a.path for a in result.artifacts if a.kind == "ARTIFACT_MANIFEST").read_text(encoding="utf-8"))
    entry = next(a for a in manifest["artifacts"] if a["kind"] == "KSPLAT")
    assert entry["format"] == ksplat.validate_file(next(a.path for a in result.artifacts if a.kind == "KSPLAT"))
    assert entry["format"]["splatCount"] == 3


def test_artifact_generation_refuses_a_scene_over_the_limit_without_writing_a_ksplat(tmp_path, monkeypatch):
    monkeypatch.setattr(artifact_generation, "MAX_BYTES", ksplat.encoded_size(3) - 1)
    with pytest.raises(StageError) as exc:
        ArtifactGeneration().run(_context(tmp_path))
    assert exc.value.code == "KSPLAT_TOO_LARGE"
    assert not (tmp_path / "scene.ksplat").exists()
