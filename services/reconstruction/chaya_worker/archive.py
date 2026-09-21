"""Frame archives: a stage's frames travel between stages as one uncompressed tar (one object, one checksum)."""

from __future__ import annotations

import tarfile
from pathlib import Path


def pack(directory: Path, archive: Path) -> int:
    """Tar all regular files of a directory (flat, sorted). Returns the number of files."""
    files = sorted(p for p in directory.iterdir() if p.is_file())
    with tarfile.open(archive, "w") as tar:
        for f in files:
            tar.add(f, arcname=f.name)
    return len(files)


def unpack(archive: Path, directory: Path) -> list[Path]:
    """Extract a frame archive, refusing anything that is not a plain file directly inside it."""
    directory.mkdir(parents=True, exist_ok=True)
    out: list[Path] = []
    with tarfile.open(archive, "r") as tar:
        for member in tar.getmembers():
            name = Path(member.name)
            if not member.isfile() or name.name != member.name or member.name.startswith((".", "/")):
                raise ValueError(f"unsafe archive member {member.name!r}")
            target = directory / member.name
            with tar.extractfile(member) as src, open(target, "wb") as dst:  # type: ignore[union-attr]
                dst.write(src.read())
            out.append(target)
    return sorted(out)
