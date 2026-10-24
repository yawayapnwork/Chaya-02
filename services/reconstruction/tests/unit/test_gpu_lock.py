"""gpu/write_lock.py, the step that fails a Dockerfile.gpu build when the image cannot train. A build container never
sees a GPU, so exactly the "no usable GPU here" problems are tolerated; anything about the image itself fails the build."""

from __future__ import annotations

import importlib.util
import json
from pathlib import Path

import pytest

from chaya_worker.toolchain import ToolStatus

SPEC = importlib.util.spec_from_file_location("write_lock", Path(__file__).resolve().parents[2] / "gpu" / "write_lock.py")
write_lock = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(write_lock)

VERSIONS = {"python": "3.12", "torch": "2.7.1+cu126", "torch_cuda": "12.6", "gsplat": "1.5.3", "gsplat_backend": "prebuilt",
            "numpy": "2.2.6", "scipy": "1.15.3", "cv2": "4.11.0.86", "colmap": "COLMAP 3.9.1"}


class Done:
    def __init__(self, out="", code=0):
        self.stdout, self.returncode = out, code


def _run(monkeypatch, tmp_path, codes, dpkg=lambda name: "1.0"):
    problems = [{"code": c, "message": c.lower(), "fix": "x", "subject": None} for c in codes]
    monkeypatch.setattr(write_lock, "environment_report",
                        lambda tc, **kw: {"ok": not problems, "problems": problems, "versions": VERSIONS})

    class TC:
        def glomap(self):
            return ToolStatus("glomap", False)

    monkeypatch.setattr(write_lock, "Toolchain", TC)
    monkeypatch.setattr(write_lock, "dpkg_versions", lambda: {n: dpkg(n) for n in write_lock.DPKG_PACKAGES})
    out = tmp_path / "gpu-lock.json"
    monkeypatch.setattr("sys.argv", ["write_lock.py", str(out), "7.0;8.6"])
    return write_lock.main(), out


@pytest.mark.parametrize("codes", [[], ["GPU_NOT_PRESENT"], ["CUDA_UNAVAILABLE"]])
def test_a_build_host_without_a_gpu_writes_the_lock(monkeypatch, tmp_path, codes):
    status, out = _run(monkeypatch, tmp_path, codes)
    assert status == 0
    lock = json.loads(out.read_text(encoding="utf-8"))
    assert lock["gsplat"] == "1.5.3" and lock["colmap"] == "COLMAP 3.9.1" and lock["cuda_arch_list"] == "7.0;8.6"
    assert lock["glomap"] is None and set(lock["dpkg"]) == set(write_lock.DPKG_PACKAGES)


@pytest.mark.parametrize("codes", [["GSPLAT_CUDA_BACKEND_MISSING"], ["TOOL_MISSING"], ["TORCH_WITHOUT_CUDA"],
                                   ["GPU_NOT_PRESENT", "PACKAGE_MISSING"], ["CUDA_DRIVER_INCOMPATIBLE"]])
def test_an_image_that_cannot_train_fails_the_build(monkeypatch, tmp_path, codes):
    status, out = _run(monkeypatch, tmp_path, codes)
    assert status == 1 and not out.exists()


def test_a_missing_os_package_fails_the_build(monkeypatch, tmp_path):
    status, out = _run(monkeypatch, tmp_path, [], dpkg=lambda name: None if name == "colmap" else "1.0")
    assert status == 1 and not out.exists()


def test_dpkg_versions_reads_each_package(monkeypatch):
    seen = []

    def run(argv, **kw):
        seen.append(argv[-1])
        return Done("3.9.1-2build2" if argv[-1] == "colmap" else "", 0 if argv[-1] == "colmap" else 1)

    assert write_lock.dpkg_versions(("colmap", "nothing"), run=run) == {"colmap": "3.9.1-2build2", "nothing": None}
    assert seen == ["colmap", "nothing"]
