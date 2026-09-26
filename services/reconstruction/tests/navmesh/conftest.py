"""Locates the real chaya-navmesh tool (native/chaya-navmesh: Recast/Detour). Tests marked `navmesh` run it; when it
is not built they are skipped, never passed -- and CI builds it and fails the job if any of them skipped (see
.github/workflows/python.yml)."""

from __future__ import annotations

import os
import shutil
from pathlib import Path

import pytest

from chaya_worker.recast import tool_version

BUILD = Path(__file__).resolve().parents[2] / "native" / "chaya-navmesh" / "build"


def find_tool() -> str | None:
    candidates = [os.environ.get("CHAYA_NAVMESH_BIN"), shutil.which("chaya-navmesh"),
                  str(BUILD / "chaya-navmesh.exe"), str(BUILD / "chaya-navmesh")]
    return next((c for c in candidates if c and Path(c).is_file()), None)


@pytest.fixture(scope="session")
def navmesh_tool() -> str:
    path = find_tool()
    if path is None:
        pytest.skip("chaya-navmesh is not built: cmake -S native/chaya-navmesh -B native/chaya-navmesh/build && "
                    "cmake --build native/chaya-navmesh/build (or set CHAYA_NAVMESH_BIN)")
    tool_version(path)  # a binary that is not chaya-navmesh fails here, loudly
    return path
